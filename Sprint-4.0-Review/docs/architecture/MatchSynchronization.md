# Real-Time Match Synchronization — Sprint 3.7, hardened in Sprint 3.7.1, gained a real gameplay write in Sprint 3.8

**Scope: synchronization only.** No gameplay rule, scoring formula, or engine file (`Dealer`, `Deck`, `Cards`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`) has ever been changed by any of these three sprints. No AI, chat, voice, matchmaking, Ready-state improvement, replay, leaderboard, card-play, trick-resolution, scoring-update, or turn-rotation work was started — see "Not started" below. Spark-compatible throughout — no Cloud Functions, no Blaze.

> **Sprint 3.8 (Gameplay Synchronization: Bidding Authority) — see its own full section near the end of this document.** In one sentence: `MatchService.submitBid(matchId, seatId, bid)` is now real (the first gameplay write this whole file's plumbing has ever carried), `docs/architecture/SeatIdentityModel.md`'s seat mapping is implemented for real (not just designed), and Sprint 3.7/3.7.1's dormant `version`-based ordering guard is finally exercised by a real write path — with ZERO changes to `subscribeToMatch()` itself, exactly as this document predicted it would work once a write path existed.

> **Sprint 3.7.1 (Synchronization Hardening & Identity Foundation) — corrections to this document.** An independent architecture review of Sprint 3.7 found two real issues and one documentation problem, all addressed this sprint:
> 1. **Retry policy was unconditional.** Sprint 3.7's reconnect logic retried EVERY `onSnapshot` error forever, including permanent ones (e.g. `permission-denied`). Fixed — see Task 1 below. This is a real behavior change to `design-ui/match-service.js`.
> 2. **The ordering guard's status was stated ambiguously.** Sprint 3.7's original wording ("an old/out-of-order snapshot can never overwrite newer state") was written as an unconditional guarantee in places, with a separate, easy-to-miss qualifier elsewhere ("armed but dormant"). Since Section 8 below is the actual finding: **no code in this codebase writes a `version` field anywhere**, this guard has never actually protected a real update. Corrected throughout this document — see Task 2.
> 3. **Testing claims did not consistently say "mocked"/"simulated."** Phrases like "Two browser instances stay synchronized" and "Reconnect restores synchronization" were stated as flat results in the original `TEST_CHECKLIST.md`, without the "against a mocked Firestore, not two real browsers / a real network drop" qualifier every such test actually needs. Corrected throughout this document, `tests/match-sync.test.cjs`'s own comments, and the new `Sprint-3.7.1-Review/TEST_CHECKLIST.md` — see Task 6.
>
> Nothing in this correction changes the underlying architecture (`Firestore ⇄ MatchService → GameSession`) or removes any capability — it corrects what was CLAIMED about capabilities that either didn't fully exist (unconditional retry) or were never activated (the ordering guard), and rewrites test documentation to say what was actually run.

## Goal

Every connected player should observe the same `matches/{matchId}` document state in real time, through the architecture the project already committed to:

```
Firestore  ⇄  MatchService  →  GameSession  →  (future) UI/engine consumers
                 (Services)      (gameplay state holder)
```

- `MatchService` is the single source of truth for match synchronization — the only file that calls `onSnapshot()` on a `matches/{matchId}` document.
- `GameSession` remains the gameplay state holder — it now also holds a live mirror of whatever `MatchService` publishes, but it never talks to Firestore directly. It consumes `MatchService`.
- The UI never talks to Firestore directly (unchanged from every prior sprint — it never has).

## What was implemented (Sprint 3.7, corrected/hardened in Sprint 3.7.1)

### 1. `MatchService.subscribeToMatch(matchId, callback)` — production-ready

This is the brief's requested `subscribe(matchId)`. It keeps its existing name, `subscribeToMatch`, for the same reason already on record in Sprint 3.4's implementation and in `docs/architecture/ServiceArchitecture.md`: naming consistency with every other service's subscribe method in this codebase (`PlayerService.subscribeToPlayerProfile`, the design intent for `RoomService.subscribeToRoom`). Deviating from the brief's literal method name is documented here, not hidden, matching this project's established practice.

| # | Requirement | Mechanism | Verified how |
|---|---|---|---|
| 1 | Uses `onSnapshot()` | Unchanged from Sprint 3.4 — `db().collection("matches").doc(matchId).onSnapshot(onNext, onError)`. | Code inspection. |
| 2 | Publishes the newest state on every change | `onNext` decodes the snapshot and calls every locally-registered callback with `(data, null)`. | `tests/match-sync.test.cjs`, against a **mocked** Firestore. |
| 3 | Event-driven only, no polling | The ENTIRE implementation is callback-driven — no `setInterval`/timer anywhere in the data path. The one timer in this file, `scheduleReconnect`'s backoff, only ever fires after a genuine `onSnapshot` error (never on a fixed interval), and only for a **retryable** error (Task 1) — it stops scheduling itself the moment a snapshot succeeds, the last subscriber unsubscribes, or the error turns out to be non-retryable. | Code inspection + `tests/match-sync.test.cjs`. |
| 4 | Ignore local duplicate updates / prevent infinite update loops | A **duplicate-content guard** in `attachListener()`'s `onNext`: a snapshot whose data is structurally identical to the last one published is never re-published. This is what makes a hypothetical future write-then-listen round trip loop-safe by construction, even though nothing in this codebase writes back to a match document yet (see "Known Limitation" below). | `tests/match-sync.test.cjs`, mocked. |
| 5 | Ordering consistency | A guard exists (`attachListener()`'s numeric-`version` check) that, IF ACTIVE, would reject a snapshot whose `version` is not strictly greater than the last one seen. **See Task 2 below — this guard is NOT currently active for any real match document, because no write path in this codebase ever sets a `version` field.** Do not read the presence of this code as evidence that ordering is protected today. | `tests/match-sync.test.cjs` exercises the guard's LOGIC with synthetic `version` fields injected directly into the mock — this proves the guard works correctly IF fed a versioned document, not that any real document is versioned. |
| 6 | Disconnect handling / retry policy | **Hardened in Sprint 3.7.1 — see Task 1 below in full.** Every `onSnapshot` error, retryable or not, still delivers the last known good data *alongside* the error (never `null`) and never throws. What changed: only a **retryable** error (per the brief's exact code lists) schedules a reconnect attempt (exponential backoff, 250ms→4s cap); a **non-retryable** error (or one with a code this project doesn't recognize — treated the same way, a deliberate safe default) stops immediately and is recorded as that subscription's terminal error, exposed to any late joiner too. | `tests/match-sync.test.cjs`, mocked errors with `.code` set to specific values from the brief's lists. |
| 7 | Clean unsubscribe, no leaks, no duplicated listeners | `matchSubscriptions[matchId]` is a **ref-counted** registry entry: the first `subscribeToMatch(matchId, cb)` call for a given `matchId` creates ONE entry and ONE real `onSnapshot()` registration; every subsequent call for the *same* `matchId` just appends `cb` to that entry's `listeners` array — never a second Firestore listener. Each call's own `unsubscribe()` removes only its own callback; the underlying Firestore listener (and any pending reconnect timer) is torn down the moment `listeners.length` reaches zero, and the registry entry itself is deleted. | `tests/match-sync.test.cjs`, via a direct `onSnapshot()` call counter in the mock — not inferred, counted. |
| 8 | Multiple tabs stay synchronized | Two independent, in-process `subscribeToMatch()` callers against the same mocked document both converge on the same state, proving MatchService's own fan-out/dedup logic is correct. **This has never been run against two real browser tabs, two real devices, or a real Firestore project — there is no such test harness in this repository.** The claim is about MatchService's in-process fan-out logic, not about verified real-world multi-device behavior. | `tests/match-sync.test.cjs`, two callbacks, one mocked document — explicitly NOT two real browsers. |

### 2. `GameSession` consumes `MatchService` updates

New, additive-only surface on `design-ui/engine/session.js` (nothing existing was changed):

- `subscribeToRemoteMatch(matchId)` — begins consuming `MatchService.subscribeToMatch(matchId, ...)`. Idempotent for the same `matchId` (a repeat call is a no-op); switching to a different `matchId` cleanly tears down the old subscription first. Fail-open (warns, never throws) if `MatchService` isn't loaded on the page.
- `unsubscribeFromRemoteMatch()` — the one path that ever calls the stored unsubscribe function.
- `getRemoteMatch()` / `getRemoteMatchError()` / `isSubscribedToRemoteMatch()` — read the current mirrored state.
- `onRemoteMatchUpdate(callback)` — GameSession's own local pub/sub over the mirror, deliberately shaped exactly like `SessionService.subscribe()` (fires immediately with the current value, then again on every change, returns an unsubscribe) — reusing an already-established project pattern rather than inventing a new one.

`GameSession` still never touches Firestore, `firebase`, or `window.Db` anywhere in `session.js` — every remote update arrives already-decoded through `MatchService`'s public API. This is checked directly (not just by code inspection) in `tests/match-sync.test.cjs`'s GameSession section, which asserts the underlying (mocked) `onSnapshot()` call count is exactly one per matchId regardless of how GameSession is called.

## Sprint 3.7.1 hardening tasks

### Task 1 — Retry Policy Hardening

**Problem (confirmed by re-reading Sprint 3.7's shipped code, not assumed):** `scheduleReconnect()` was called unconditionally from every `onSnapshot` error handler, with no inspection of the error at all. A `permission-denied` (e.g. a revoked session, a match the user is no longer part of) would retry forever, on the same backoff schedule as a genuine transient network blip — wasted work retrying something that can structurally never succeed.

**Fix:** `design-ui/match-service.js` now classifies every `onSnapshot` error by its Firestore `.code` before deciding:

| Code | Classification |
|---|---|
| `unavailable`, `deadline-exceeded`, `internal`, `unknown`, `resource-exhausted` | **Retryable** — schedules a reconnect attempt (existing exponential backoff, 250ms→4s cap, unchanged). |
| `permission-denied`, `unauthenticated`, `invalid-argument`, `failed-precondition`, `not-found` | **Non-retryable** — stops immediately, records the error as that subscription's terminal error. |
| Any other code, or no `.code` at all (e.g. a plain `Error` — exactly what a non-Firestore failure, or any test mock, produces) | **Treated as non-retryable.** This is the documented, deliberate default: retrying an error we cannot positively confirm is transient reproduces the exact "retry forever" problem this task exists to remove. See `isRetryable()`/`classifyError()` in `match-service.js`. |

In every case, the error is still delivered to every subscriber, alongside the last known good data — this sprint never changed the "always expose the error, never go silent" contract, only WHEN it keeps retrying versus WHEN it correctly gives up. A late joiner to a subscription that already hit a non-retryable error is told about the terminal error immediately (not left waiting on a reconnect that will never happen).

**Does this retry "forever" for a retryable error?** Yes, for as long as at least one subscriber remains — this is intentional, not an oversight of the "never enter an infinite reconnect loop" requirement. The phrase in the brief targets retrying something that can never succeed (a `permission-denied` is now the concrete example that stops); a persistent, genuinely transient `unavailable` is exactly the case where a real app should keep trying, the same way any production Firestore-backed app's own SDK behaves. No arbitrary attempt cap was added, because adding one would trade a real, useful reconnect for an arbitrary UX regression (e.g., a 30-second Wi-Fi hiccup permanently killing sync) without closing any actual "runs forever pointlessly" gap — the pointless case (non-retryable) is the one actually fixed.

**Verified how:** `tests/match-sync.test.cjs` (see Task 1's dedicated section there) — mocked errors with `.code` set to a specific retryable code (`unavailable`) prove reconnect happens and eventually recovers; a specific non-retryable code (`permission-denied`) proves reconnect stops permanently and a late joiner is told; an error with no code at all proves the safe default. All against a **mocked** Firestore — see Task 6.

### Task 2 — Ordering Guard Review

**The three questions the brief asks, answered directly:**

- **Where does `version` come from?** Nowhere. It is not written by `buildInitialMatchDoc()`, not written by any of `MatchService`'s gameplay methods (all ten are still unimplemented stubs, unchanged since Sprint 3.4), and not settable by any client at all today, since `firestore.rules`' `matches/{matchId}` block denies `update` outright (`allow update: if false`, unchanged) — there is no write path to a match document after creation, period.
- **Who increments it?** No one. Nothing increments a field that nothing writes.
- **Who owns it?** No one, currently. The design intent (documented, not built) is that a future gameplay-write sprint would have `MatchService`'s write methods own it — incrementing it on every accepted write, exactly the same way this project's `VERSION` map already works inside its own test mocks (`tests/match-service.test.cjs`) for optimistic-concurrency retries. That sprint has not happened.

**Correction:** Sprint 3.7's original documentation described the ordering guard's existence in ways that could be read as "ordering protection exists." It does not, for any document a real user will ever see, because the field it keys off of is never written. What DOES exist: dead-but-ready code in `attachListener()` that will start protecting ordering automatically, with no code change, the moment a future write path starts writing `version` — this is a reasonable thing to have built in advance, but it is not itself a currently-active guarantee, and this document (and `match-service.js`'s own comments) now say so explicitly everywhere the guard is mentioned, rather than in one qualifier easy to miss.

**Is there another mechanism providing ordering today?** Partially, and worth stating honestly rather than either overclaiming or ignoring it: within a SINGLE `onSnapshot` listener's lifetime, Firestore's own client SDK delivers snapshots for one document in server-commit order — so as long as `attachListener()` never detaches and reattaches, updates arrive in the right order "for free," from the SDK itself, not from anything this codebase wrote. This breaks down at exactly the moment a RECONNECT happens (a fresh `onSnapshot()` registration starts a new delivery sequence with no ordering relationship guaranteed against what the previous, now-detached listener had already delivered) — which is precisely the gap the (currently dormant) `version` guard exists to close once it's actually fed real data. Recorded here so "does ordering exist at all, ever" has a complete, honest answer: yes, trivially, within one uninterrupted listen; no, not across a reconnect, until a real write path adds `version`.

**No code change for this task** — `attachListener()`'s ordering-guard block is unchanged from Sprint 3.7 (it was already correct code, just inactive); only comments/documentation were corrected.

### Task 3 — Seat Identity Model

Full design in a dedicated new document: **`docs/architecture/SeatIdentityModel.md`**. Summary: `matches/{matchId}.seats = { p1: uid, p2: uid, p3: uid, p4: uid }`, owned by `MatchService`, assigned once (positionally from `rooms/{roomId}.players`) at match creation, immutable for the match's lifetime, synchronized for free by the existing `subscribeToMatch()` pipe (no new sync mechanism needed), and — the security-critical point — any future write claiming to act as a given seat MUST be validated against this map's `uid`, never against a client-supplied seat id alone. **Documentation only** — no field was added to any real match document, no rule references `seats`, and no translation code was added to `GameSession` or any engine file.

### Task 4 — QA Package Integrity

See `Sprint-3.7.1-Review/` — contains every implementation file this sprint modified (`design-ui/match-service.js`, `design-ui/engine/session.js`), every new/updated doc, the new test file, and this report. Verified via a direct diff against `git diff --stat` (see `TEST_CHECKLIST.md`'s Task 4 section) — nothing modified is missing from the package.

### Task 5 — Code Cleanup

Reviewed both files Sprint 3.7 touched for dead code, duplicated conditions/branches, and obsolete comments. Findings, stated honestly rather than inflated: the Sprint 3.7 code was already fairly lean; this pass found and fixed one real instance — `design-ui/engine/session.js` had the identical try/catch "safe-invoke a listener callback" logic written out twice (once in `notifyRemoteMatchListeners()`, once in `onRemoteMatchUpdate()`'s own immediate-callback call). Consolidated into one shared `safeInvokeRemoteMatchListener()` helper, no behavior change (verified: `tests/match-sync.test.cjs` and every other suite still pass identically before and after). No dead code or duplicated branches were found in `design-ui/match-service.js` beyond what Task 1 itself already touched while adding the retry classification.

### Task 6 — Documentation Honesty

Every claim in this document, `ServiceArchitecture.md`, `MatchLifecycle.md`, and `tests/match-sync.test.cjs`'s own comments/check labels that stated a mocked or simulated test result as an unqualified fact has been corrected to say "mocked"/"simulated" explicitly:

- "Two browser instances stay synchronized" → "two independent subscribers (simulating two tabs) against a mocked Firestore."
- "Reconnect restores synchronization" / "Offline recovery" → "a simulated disconnect against a mocked Firestore," with an explicit note that there is no real network/offline test harness in this project.
- The ordering guard's status (Task 2 above).
- A genuine bug this honesty pass surfaced while doing this: Sprint 3.7's `simulateDisconnect()` test helper fired an error callback WITHOUT actually detaching the mocked listener first — meaning the "reconnect" tests were passing without ever truly exercising `MatchService`'s own re-attach logic (a stale, never-removed mock listener kept quietly delivering updates regardless of whether a reconnect happened). Found and fixed in `tests/match-sync.test.cjs` this sprint — see that file's own header comment for the full account. This is exactly the kind of gap this task exists to surface, not hide.

Historical documents (`Sprint-3.7-Review/`'s own `CHANGELOG.md`/`TEST_CHECKLIST.md`) are left as the historical record of what was believed at the time, per this project's established "document deviations, don't hide them, don't rewrite history" convention — the corrections live here and in the new `Sprint-3.7.1-Review/` package, not by silently editing the old one.

## Known Limitation — UID vs. seat-id, still not solved (see Task 3 / SeatIdentityModel.md)

`matches/{matchId}` identifies `players`/`dealer`/`turn` by real Firebase Auth **uid** (see `FirestoreSchema.md`). The gameplay engine identifies seats by canonical **seat id** (`p1`..`p4`). **Sprint 3.8 implements the mapping itself for real** (see the Sprint 3.8 section below and `docs/architecture/SeatIdentityModel.md`) — but does NOT reconcile the two id spaces inside the ENGINE: `GameSession.getRemoteMatch()` still returns the raw, uid-keyed document with no merge into the engine's seat-id-keyed fields, and `bidding-engine.js` remains completely unaware any of this exists. The mapping now lives on the match document and is enforced as write authority by `firestore.rules`, but nothing translates it into what `bidding-engine.js`'s own local state machine would need to actually run against real multiplayer data — that remains the next gameplay-write sprint's job.

## Sprint 3.8 (Gameplay Synchronization: Bidding Authority)

**Scope: bidding synchronization only.** No card play, trick resolution, scoring update, or turn rotation. `MatchService.submitBid()` is the ONLY gameplay method implemented — every other one (`submitDashCall`, `submitPass`, `declareTrump`, `submitEstimate`, `playCard`, `resolveTrick`, `completeRound`, `advanceToNextRound`, `endMatch`) remains an unimplemented stub, unchanged.

### Task 1 — Seat Identity Implementation

`docs/architecture/SeatIdentityModel.md`'s design (documentation-only through Sprint 3.7.1) is now real: `design-ui/match-service.js`'s `buildSeatMap()` assigns `matches/{matchId}.seats = {p1..p4: uid}` positionally from `players[]`, once, at creation, inside `buildInitialMatchDoc()`. `firestore.rules`' `isValidSeatMap()` validates the result is a genuine bijection (see that file's own comments and `SeatIdentityModel.md`'s "Validation" section for exactly why it checks membership+size+uniqueness rather than re-deriving the exact positional order). This mapping is the ONLY authority `submitBid()`/`isValidBidSubmission()` consult for "who owns this seat" — never inferred, never guessed, never reassigned.

**Honest scope note, stated in both `match-service.js` and `SeatIdentityModel.md`:** this project's room system does not enforce a minimum of 4 players. Rather than fabricate a seat for a nonexistent player (which would mean inventing an AI/placeholder identity — explicitly out of scope), a match with fewer than 4 real players simply has fewer real seats. `p1`/`p2` exist for a 2-player match; `p3`/`p4` do not.

### Task 2 — Versioned Match Writes

`matches/{matchId}.version` starts at `1` at creation and must increase by EXACTLY `1` on every accepted write — enforced independently in TWO places: `submitBid()`'s own transaction (always computes `version = freshRead.version + 1`, never a cached number) AND `firestore.rules`' `isValidBidSubmission()` (`newData.version == oldData.version + 1`, rejecting anything else regardless of how it was produced). This is the ordering guard Sprint 3.7 built and Sprint 3.7.1 correctly refused to claim was active — **it is active now.** No change was needed to `subscribeToMatch()`'s own ordering-guard code at all; it was already correct, just waiting for a real `version` field to check. See `tests/submit-bid.test.cjs`'s "stale snapshot"/"duplicate snapshot" tests (MOCKED) for direct proof the guard now fires on real data.

### Task 3 — Submit Bid

`MatchService.submitBid(matchId, seatId, bid)` — the ONE public API the brief asked for, implemented with that exact signature (not the older, never-implemented Sprint 2.7 stub signature `submitBid(matchId, uid, bid)` — see `ServiceArchitecture.md`'s entry for why `uid` is never a parameter). Runs inside a real Firestore transaction. Enforces seat ownership, no-double-submit, and bidding-still-open, in that order, then increments `version` and — the only "phase" concept this sprint has — flips `biddingOpen` to `false` once every real seat has bid. Returns a structured error (`err.reason`) on every failure path. See `docs/architecture/ServiceArchitecture.md`'s `submitBid()` entry for the full contract.

### Task 4 — Realtime Synchronization

**Zero changes to `subscribeToMatch()`.** `bids`/`biddingOpen`/`version`/`lastBidSeat` are just more fields on the same `matches/{matchId}` document; the Sprint 3.7/3.7.1 subscription pipe delivers them automatically, to every already-subscribed client, the instant `submitBid()`'s transaction commits — proven directly in `tests/submit-bid.test.cjs`'s realtime-sync tests via the mock's `onSnapshot()` call counter staying flat across a real bid write (no second listener ever created).

### Task 5 — Firestore Rules

`isValidBidSubmission()` — the first real update rule `matches/{matchId}` has ever had. See `docs/architecture/SecurityArchitecture.md`'s "Bidding write authority" section for the full, individually-documented breakdown of every clause (authentication, match membership, seat ownership, own-bid-only, version-increment correctness, and the "reject every other write" field allow-list). Every clause maps 1:1 to one of this task's own required checks.

### Task 6 — Conflict Handling

Two DIFFERENT seats bidding concurrently both succeed, serialized by Firestore's own transaction retry into two sequential version increments — no data lost. Two attempts at the SAME seat racing: exactly one succeeds, the other fails `ALREADY_BID` (the loser's transaction retry re-reads the winner's already-committed bid and correctly refuses to double-submit). A permission violation (wrong seat/wrong uid) never triggers any retry at all — it's a thrown application error, not a Firestore-level write conflict, so the transaction machinery that retries version races doesn't apply to it; it propagates as an immediate rejection. See `tests/submit-bid.test.cjs`'s "two simultaneous bidders" section (MOCKED) for the executable proof of all three cases.

### Task 7 — Failure Recovery

Every failure path rejects with a structured error (`err.reason` — `INVALID_ARGUMENT`/`UNAUTHENTICATED`/`UNAVAILABLE`/`MATCH_NOT_FOUND`/`UNKNOWN_SEAT`/`PERMISSION_DENIED`/`BIDDING_CLOSED`/`ALREADY_BID`). `submitBid()` never mutates any local state at all — it only ever calls Firestore; a rejected transaction leaves `GameSession`'s remote mirror completely untouched, since nothing connects a failed `submitBid()` call to `GameSession.applyRemoteMatchUpdate` in the first place (that only ever runs off a REAL `subscribeToMatch()` delivery). `submitBid()` contains no internal retry/backoff loop of its own — a failed call is exactly one rejected promise; retrying is entirely the caller's decision, matching Task 6's "second retries only if appropriate."

### Known Limitation, restated for this sprint

The bid VALUE itself is never validated against auction rules by this layer — `submitBid()`/`isValidBidSubmission()` store/permit an opaque payload. Whether a bid is a legal trick count, a legal Dash/With shape, or submitted in the correct auction order is entirely `bidding-engine.js`'s concern, and that engine is not connected to any of this. This is the correct scope boundary for a synchronization-only sprint, not an oversight — see `SecurityArchitecture.md`'s "soft approximation" section for why this is explicitly flagged as a Ranked-Match blocker, same as every other rules-can't-validate-game-logic gap already on record in this project.

## Not started (per the brief's explicit stop list)

Card play, dealer synchronization, trick resolution, scoring, turn rotation/authority, matchmaking, replay, chat, voice, AI, leaderboards, tournament, Cloud Functions. None of these were touched by Sprint 3.8 or either prior sprint in this document.

## Files changed

**Sprint 3.7.1:**
- `design-ui/match-service.js` — retry classification added (Task 1); ordering-guard comments corrected (Task 2); one console.warn added on a non-retryable stop. `startMatch()`/`loadMatch()`/every gameplay stub/the ordering-and-duplicate-content guard LOGIC unchanged.
- `design-ui/engine/session.js` — one duplicated try/catch consolidated (Task 5). No behavioral change.
- `tests/match-sync.test.cjs` — the `simulateDisconnect()` mock bug fixed (Task 6); new retry-classification tests added (Task 1); labels corrected for honesty (Task 6).
- `docs/architecture/SeatIdentityModel.md` — new (Task 3, documentation only).
- `docs/architecture/ServiceArchitecture.md`, `docs/architecture/MatchLifecycle.md` — updated (see each file's own Sprint 3.7.1 note).

**Sprint 3.8:**
- `design-ui/match-service.js` — `buildSeatMap()`/`buildInitialMatchDoc()` extended (Task 1/2/3); `submitBid()` implemented for real (Task 3). `subscribeToMatch()` itself: **zero changes** (Task 4).
- `firestore.rules` — `isValidSeatMap()` (new), `isValidNewMatch()` extended, `isValidBidSubmission()` (new) — the first real `matches/{matchId}` update rule (Task 5).
- `tests/match-service.test.cjs` — updated for the new document shape; `tests/rules-simulation.test.js` — `isValidNewMatchV3`/`isValidBidSubmission` translations + tests added (all SIMULATED); `tests/submit-bid.test.cjs` — new, full dedicated suite (all MOCKED).
- `docs/architecture/SeatIdentityModel.md` — updated in place from documentation-only to implemented status.
- `docs/architecture/ServiceArchitecture.md`, `docs/architecture/MatchLifecycle.md`, `docs/architecture/SecurityArchitecture.md`, `docs/architecture/FirestoreSchema.md` — updated.
- Nothing else. `Dealer`/`Deck`/`Cards`/`bidding-engine.js`/`table-engine.js`/`scoring-engine.js`, `RoomService`, `SessionService`, `PlayerService`, and every UI screen remain untouched across all three sprints.

## Testing summary

**Sprint 3.7.1:** `tests/match-sync.test.cjs`: 59 checks (up from Sprint 3.7's 50 — 9 new checks for retry classification), all MOCKED. Re-run 4+ times with no flakiness.

**Sprint 3.8:** `tests/submit-bid.test.cjs` (new, 41 checks, all MOCKED); `tests/rules-simulation.test.js` gained 24 new checks, all SIMULATED (85 total); `tests/match-service.test.cjs` gained 7 net new checks (65 total, MOCKED); `tests/match-sync.test.cjs` adjusted by -1 (submitBid's stub-check removed since it's no longer a stub — 58 total, MOCKED).

Full suite after Sprint 3.8, all passing: `tests/deck.test.cjs` (39, MOCKED), `tests/match-service.test.cjs` (65, MOCKED), `tests/match-sync.test.cjs` (58, MOCKED), `tests/submit-bid.test.cjs` (41, MOCKED), `tests/room-service.test.cjs` (31, MOCKED), `tests/rules-simulation.test.js` (85, SIMULATED), `tests/match-flow-integration.test.cjs` (156, MOCKED), `tests/match-flow-normal-dash-scoring-fix.test.cjs` (16, MOCKED), `tests/match-flow-scoring-scenarios.test.cjs` (31, MOCKED). **522 automated tests total, all passing, all either MOCKED (a hand-written fake Firestore) or SIMULATED (a 1:1 JS translation of firestore.rules' CEL) — no real Firestore project, no Firebase emulator, no real browser instances were used anywhere in this project's test suite, this sprint or any prior one.**

## Sprint 3.8.1 (Bidding Validation & Rules Hardening)

**Scope: generic bid-value validation only — a small, isolated hardening pass, not a feature sprint.** Sprint 3.8's `submitBid()` validated WHO could write and WHEN, but never looked at the bid VALUE itself. This sprint closes that gap with GENERIC validation (`isValidGenericBidValue()` in `match-service.js`, mirrored independently in `firestore.rules`' `isValidBidSubmission()`) — rejecting `null`/`undefined`/`NaN`/`Infinity`/negative values/non-integers/values above 13/non-numeric types, with zero gameplay awareness. **See `docs/architecture/BidValidation.md` for the full, explicit line drawn between generic and gameplay validation** — `bidding-engine.js` remains completely untouched, unconsulted, and unconnected; gameplay legality (is 13 actually a legal bid for this seat right now) remains that engine's future job. No card synchronization, turn authority, trick resolution, or score sync was started. Testing: `tests/submit-bid.test.cjs` gained 25 new checks (66 total, MOCKED); `tests/rules-simulation.test.js` gained 24 new checks (109 total, SIMULATED). **571 automated tests total.**

## Sprint 3.9 (Engine Adapter Layer)

`design-ui/match-adapter.js` (new) — the seat↔uid translation layer and a one-shot `bootstrapGameSession()`. See `docs/architecture/EngineAdapter.md` for the full design. Deliberately did NOT connect `bidding-engine.js` to any Firestore data yet — that connection is Sprint 4.0's job. **613 automated tests total** after this sprint.

## Sprint 4.0 (Online Bidding Synchronization: Authority Layer)

**This is the sprint that finally completes the pipeline every prior sprint in this document built one piece of:**

```
Player -> submitBid() -> Firestore -> MatchService listener -> Engine Adapter -> bidding-engine.js -> GameSession -> UI
   (3.8)      (3.8)         (3.7)           (3.7)                  (3.9→4.0)         (pre-existing)    (pre-existing)  (not built)
```

Two new functions in `design-ui/match-adapter.js` (both additive — see `EngineAdapter.md`'s Sprint 4.0 section for the full account):

- **`applyRemoteBid(matchId, matchDoc)`** — translates a Firestore bid update into exactly one `bidding-engine.js` action (`SubmitFinalEstimate` — the only action shape Firestore's existing schema can represent, per `BidValidation.md`). Guarded by a strict version check (no equality, no rollback), a malformed-snapshot check, and a content-level idempotency check (an already-recorded local bid, covering both a genuine duplicate delivery AND the originating client's own bid echoing back).
- **`startBidSync(matchId)`** — wires the full pipeline in one call, reusing `MatchService.subscribeToMatch()` verbatim (no second listener, no reimplemented sync logic).

**Critically, `subscribeToMatch()` itself was not touched.** This sprint is proof that Sprint 3.7's original design (an ordering guard "armed but dormant... activates automatically the moment a future write path adds the field, with no change needed here") and Sprint 3.9's adapter design (pure translation, ready for a caller) both held up exactly as designed, two and three sprints later respectively, with zero modification required to either.

**Where authority lives, restated here for this document's own "no duplicate logic" framing:** Firestore/`MatchService` own synchronization and persistence only — neither ever calls `bidding-engine.js`, and this sprint's own forbidden-scope sweep confirms neither file changed. `bidding-engine.js` owns every gameplay decision (legality, order, phase) — unmodified, and `applyRemoteBid()` only ever reads its response. The adapter owns translation only — including, new this sprint, the version/duplicate gating that decides WHETHER to translate, which is a translation-layer decision (should this update even reach the engine), not a gameplay one.

Testing: `tests/match-adapter.test.cjs` gained 17 new unit-level checks (59 total, MOCKED, against a fake `BiddingEngine`); `tests/bid-sync.test.cjs` (new, 39 checks, MOCKED, against the REAL `bidding-engine.js`) — covering the full acceptance criteria (a remote bid executes the real engine exactly once), duplicate/stale/new snapshots, multiple sequential bids, remote vs. local bid, late subscriber, listener restart, listener duplicate event, and GameSession consistency. **690 automated tests total, all passing**, all labeled MOCKED (no SIMULATED checks — this sprint touches no `firestore.rules`).
