# Real-Time Match Synchronization — Sprint 3.7, hardened in Sprint 3.7.1, gained a real gameplay write in Sprint 3.8

**Scope: synchronization only.** No gameplay rule, scoring formula, or engine file (`Dealer`, `Deck`, `Cards`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`) has ever been changed by any of these three sprints. No AI, chat, voice, matchmaking, Ready-state improvement, replay, leaderboard, card-play, trick-resolution, scoring-update, or turn-rotation work was started — see "Not started" below. Spark-compatible throughout — no Cloud Functions, no Blaze.

> **Sprint 3.8 (Gameplay Synchronization: Bidding Authority) — see its own full section near the end of this document.** In one sentence: `MatchService.submitBid(matchId, seatId, bid)` is now real (the first gameplay write this whole file's plumbing has ever carried), `docs/architecture/SeatIdentityModel.md`'s seat mapping is implemented for real (not just designed), and Sprint 3.7/3.7.1's dormant `version`-based ordering guard is finally exercised by a real write path — with ZERO changes to `subscribeToMatch()` itself, exactly as this document predicted it would work once a write path existed.

> **Sprint 4.1 (Turn Authority & Remote Play Validation) — see its own full section near the end of this document.** In one sentence: `MatchAdapter.applyRemoteTurn()` now keeps `GameSession`'s top-level turn mirror continuously synchronized with `matches/{matchId}.turn` (previously a one-shot bootstrap value), and `isLocalSeatsTurn()`/`assertLocalTurn()` give any future gameplay-write function a way to check "is it actually my turn" BEFORE attempting a write — with ZERO changes to `subscribeToMatch()` itself, `bidding-engine.js`, or any other engine file.

> **Sprint 4.3 (Trick Resolution Synchronization) — see its own full section near the end of this document.** In one sentence: a new `MatchAdapter.applyRemoteTrick()`/`startTrickSync()` pair resolves a completed trick, on every client, by calling `table-engine.js`'s own existing, unmodified `resolveTrick()` — no new Firestore field, no rules change, no engine change; the trick winner and next leader are synchronized entirely by DETERMINISM (every client's engine, replaying the same cards, reaches the same answer), not by a broadcast write.

> **Sprint 4.2.2 (Atomic Card Turn Progression & Card-Log Desync Hardening) — see its own full section near the end of this document.** In one sentence: `MatchService.submitCard()` now atomically persists the played card AND the next player's turn (or the resolving/null boundary) in ONE Firestore transaction, using a new, minimal `TableEngine.previewPlay()` export — closing the gap where four sequential seats could not actually submit in production without a test-only turn mutation between each call; `MatchAdapter.applyRemoteCard()` now treats a malformed cardLog entry and a content-mismatched local echo as durable desyncs, never a silent skip.

> **Sprint 4.2 (Online Card Synchronization: Engine Authority) — see its own full section near the end of this document.** In one sentence: `MatchService.submitCard(matchId, card)` and `MatchAdapter.applyRemoteCard()` extend the SAME pipeline shape Sprint 4.0 built for bids to card plays — a legal card played by one player now appears exactly once on every connected client, with `table-engine.js` remaining the sole authority on legality, trick state, and turn order; Task 3's "verify it's my turn before sending a write" REUSES Sprint 4.1's `assertLocalTurn()` verbatim, no new authority function was written. ZERO changes to `subscribeToMatch()`, `bidding-engine.js`, `table-engine.js`, `Dealer`, `Deck`, or `Cards`.

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

## Sprint 4.1 (Turn Authority & Remote Play Validation)

**This sprint is NOT about card play. It is ONLY about determining WHO is allowed to act.** Sprint 4.0 synchronized bid VALUES; this sprint synchronizes turn OWNERSHIP — a separate, narrower concern, using the same "Firestore delivers, the engine decides, the adapter translates" architecture every prior sprint in this document already established.

### Task 1 — Turn Synchronization

`MatchAdapter.startTurnSync(matchId)` — the turn-sync analog of Sprint 4.0's `startBidSync()`. Subscribes through the SAME, unmodified `MatchService.subscribeToMatch()` (no second listener — ref-counted by `matchId`, not by which adapter function subscribed) and pipes every delivery through `applyRemoteTurn()`. **No gameplay logic inside `MatchService`** — confirmed unchanged, zero reference to `GameSession`/`setTurn`/any engine file, exactly as every prior sprint's forbidden-scope sweep already found.

### Task 2 — Remote Turn Application

`MatchAdapter.applyRemoteTurn(matchId, matchDoc)` keeps `GameSession`'s top-level turn mirror (`getTurn()`/`setTurn()` — a Firestore-facing field DISTINCT from `GameSession.getBiddingState().turnId`, the bidding-phase-specific field `bidding-engine.js`'s own reducer already owns and this sprint does not touch — see `docs/architecture/EngineAdapter.md`'s Sprint 4.1 section for the full account of why these are two different fields) continuously synchronized with `matches/{matchId}.turn`, translated uid → seat.

- **Ignores duplicate turn updates** — its own independent version registry (`lastAppliedTurnVersionByMatch`, deliberately separate from `applyRemoteBid()`'s own) rejects an equal version.
- **Ignores stale versions** — a lower version is rejected the same way, never applied, never rolls the mirror back.
- **Rejects malformed snapshots** — a non-object, a missing/non-numeric `version`, or a `turn` uid resolving to no real seat is rejected before any GameSession mutation, never throws.
- **Never mutates Firestore** — no `db()`/write-path reference anywhere in the function.
- **Updates only `GameSession`** — via its own existing, unmodified `setTurn()` setter; no other engine file is called, consulted, or re-implemented.

### Task 3 — Local Authority Validation

`MatchAdapter.isLocalSeatsTurn(matchDoc, localSeat)` / `assertLocalTurn(matchDoc, localSeat)` — the gate any FUTURE gameplay-write function must call before attempting a write: "verify currentPlayer == localSeat... if false: reject locally... do not send writes." Reads the general-purpose `matches/{matchId}.turn` mirror (not `BiddingEngine.getState().waitingFor`, which is bidding-phase-specific and would become meaningless in a future card-play phase — see `EngineAdapter.md`). `assertLocalTurn()` throws a structured `NOT_LOCAL_TURN` error on mismatch, matching this project's "neither layer trusts the other alone" convention — this is the CLIENT-side half; no server-side (`firestore.rules`) half exists yet, since no gameplay-write field beyond bidding is defined to write a rule against.

**Built and tested now, called by nothing yet** — same "deliver the mechanism ahead of its first real caller" pattern as `bootstrapGameSession()` (Sprint 3.9) and `applyRemoteBid()` (Sprint 4.0).

### Task 4 — Duplicate Protection

Two independent layers, mirroring `applyRemoteBid()`'s own: the version gate (rejects a byte-identical redelivery) and a content-level check (`GameSession.getTurn() === turnSeat` already, so nothing changed even though the version is genuinely newer). Verified directly in `tests/turn-sync.test.cjs`: "receiving identical turn snapshots twice" causes zero change to `GameSession.getTurn()`, zero advancement of the adapter's own version gate, and (since `applyRemoteTurn()` never calls any engine reducer at all) trivially zero re-run of engine logic.

### Task 5 — Adapter Isolation

`design-ui/match-adapter.js` remains the ONLY file that calls `GameSession.setTurn()` on behalf of a remote update — confirmed by this sprint's own forbidden-scope sweep. No other file (not `match-service.js`, not any engine file, not any UI screen — none of which exist for gameplay yet) manipulates engine turn state.

### Why Firestore never decides whose turn it is

`applyRemoteTurn()` contains no decision rule — no branch computes a turn value that isn't already present, verbatim, in `matchDoc.turn`. It is a lookup-and-copy, never an inference. Whose turn is next remains entirely `bidding-engine.js`'s own decision (via its own `turnId`, unchanged) — and, honestly stated, nothing in this codebase yet writes that computed decision BACK into `matches/{matchId}.turn`, so a remote opponent's client currently only sees whatever `turn` was set to at match creation, not bidding's real, locally-advancing pointer. This gap is recorded, not hidden — closing it (a future write-back path from `bidding-engine.js`'s `turnId` into `matches/{matchId}.turn`, presumably via a new `MatchService` method) is exactly the kind of "next gameplay-write sprint's job" this document has flagged at every prior boundary.

### Where authority lives (Task 7, restated for turn ownership specifically)

| Concern | Owner | Enforced by |
|---|---|---|
| Whose turn is next (the actual decision) | `bidding-engine.js` (bidding phase); a future card-play engine (later phases) | Its own, unmodified reducer's `turnId` — never computed by this adapter or any Service |
| Turn MIRROR synchronization (keeping every client's local copy current) | `design-ui/match-adapter.js`'s `applyRemoteTurn()` | Reads `matches/{matchId}.turn` only, translated uid→seat, never inferred |
| Local authority check (may THIS client act right now) | `design-ui/match-adapter.js`'s `isLocalSeatsTurn()`/`assertLocalTurn()` | Compares the mirror against the caller's own seat — client-side only, no server-side rule yet |
| Delivery (getting the latest `turn` value to every client) | Firestore, via `MatchService.subscribeToMatch()` | Sprint 3.7/3.7.1/3.8, unmodified |
| Persistence of `turn` itself | `MatchService` (existing `turn` field, set once at match creation — no write-back path from the engine yet) | Unchanged this sprint |

Testing: `tests/match-adapter.test.cjs` gained 23 new checks (82 total, MOCKED); `tests/turn-sync.test.cjs` (new, 26 checks, MOCKED, against the REAL `match-service.js`/`session.js`) — covering new/duplicate/stale/advance snapshots, late subscriber, listener restart, listener duplicate event, correct/wrong player attempts driven through the live pipeline, GameSession consistency, and adapter isolation. **718 automated tests total, all passing**, all labeled MOCKED (no SIMULATED checks — this sprint touches no `firestore.rules`).

## Sprint 4.2 (Online Card Synchronization: Engine Authority)

**This sprint is NOT about trick resolution, NOT about scoring, NOT about determining the winner — it is ONLY about synchronizing legal card plays while `table-engine.js` remains the single authority.** Sprint 4.2's own Task 9 (Architecture Verification) required checking, before writing any code, whether the engine already exposes what's needed: it does (`TableEngine.emit()`/`getState()`, exactly the same shape `BiddingEngine.emit()`/`getState()` already proved out for bids) — no missing API, no engine change needed.

### Task 1 — Card Submission

`MatchService.submitCard(matchId, card)` — no `seatId` parameter, unlike `submitBid()`. The acting seat is resolved from the calling uid via `MatchAdapter.uidToSeat()` (Task 1's own "Calls MatchAdapter only") — a NEW, documented, read-only translation edge from `MatchService` into `MatchAdapter`, not into any engine file. Uses a real Firestore transaction. Does NOT evaluate card legality — appends `{seatId, card}` to `matches/{matchId}.cardLog` (an append-only log, unlike bids' single-slot-per-seat map — see `EngineAdapter.md`'s Sprint 4.2 section for why the schema shape had to differ), sets `lastCardSeat`, increments `version` by exactly 1.

### Task 2 — Remote Card Application

`MatchAdapter.applyRemoteCard(matchId, matchDoc)` replays every not-yet-applied `cardLog` entry, in order, through `TableEngine.emit({type:"PlayCard", playerId, card})` — the only call this function makes into any engine file. Never mutates Firestore; only ever updates `GameSession`, and only through `table-engine.js`'s own reducer.

### Task 3 — Authority Gate

Reuses Sprint 4.1's EXISTING `assertLocalTurn()` verbatim — no new function. "Before sending any card: verify assertLocalTurn(). If false: reject locally. No Firestore write" is satisfied by the SAME mechanism a future card-play UI is expected to call before ever invoking `submitCard()`. See `EngineAdapter.md`'s Sprint 4.2 section for the honest, pre-existing limitation this reuse inherits (the top-level turn mirror isn't yet kept current during the PLAY phase, same class of gap already documented for bidding).

### Task 4 — Duplicate Protection

Identical snapshots delivered twice do not play a card twice, advance the turn twice, modify `GameSession` twice, or re-render twice — verified directly in `tests/card-sync.test.cjs`'s "duplicate snapshot" and "listener duplicate event" sections against the REAL `table-engine.js`, not just asserted.

### Task 5 — Version Gate

Older and equal versions are rejected against `applyRemoteCard()`'s OWN independent version registry (a THIRD gate, alongside bid's and turn's — see `EngineAdapter.md` for why three independent gates, not one shared one, is correct here); malformed snapshots (non-object, non-numeric version, non-array `cardLog`) are rejected before any engine call. Never rolls back — verified with a forged, truncated `cardLog` at a lower version.

### Task 6 — Adapter Isolation

`design-ui/match-adapter.js` remains the ONLY layer that translates Firestore ↓ Gameplay Engine — confirmed by this sprint's own forbidden-scope sweep. `MatchService`'s own new call into `MatchAdapter.uidToSeat()` is read-only/translation-only, never an engine call — `MatchService` still has ZERO reference to `GameSession`/`BiddingEngine`/`TableEngine`, in any direction.

### Where authority lives (Task 8's ask, restated for cards specifically)

| Concern | Owner |
|---|---|
| Card legality, follow-suit, current trick state, played cards, next player | `table-engine.js` (unmodified — only called, never re-implemented) |
| Synchronization | Firestore, via `MatchService.subscribeToMatch()` (unmodified) |
| Persistence | `MatchService.submitCard()` (new, generic-shape-only) |
| Identity/format translation + sync gating | `design-ui/match-adapter.js` |
| Local authority check (may THIS client act now) | `design-ui/match-adapter.js`'s `assertLocalTurn()` (REUSED from Sprint 4.1) |
| Rendering | UI (not built yet) |

**Why Firestore never validates card legality:** the exact same reasoning already stated for bids and turns — `submitCard()`/`isValidCardSubmission()` check only a generic shape (a real suit key, a real rank range); whether a card is actually in the claimed seat's hand, follows the led suit, or is even that seat's turn to play remains entirely `table-engine.js`'s job, untouched and unconsulted by either the client-side service or the server-side rule.

Testing: `tests/match-adapter.test.cjs` gained 18 new checks (100 total, MOCKED, against a fake `TableEngine`); `tests/submit-card.test.cjs` (new, 32 checks, MOCKED, against the REAL `match-service.js`); `tests/card-sync.test.cjs` (new, 41 checks, MOCKED, against the REAL `table-engine.js`) — covering the full acceptance criteria (a legal card played by one player appears exactly once on every connected client, the engine executes exactly once), duplicate/stale/new snapshots, multiple sequential cards, remote vs. local card, late subscriber, listener restart, listener duplicate event, wrong-turn rejection/correct-player-accepted, adapter corruption, and GameSession consistency; `tests/rules-simulation.test.js` gained 31 new SIMULATED checks (140 total). **842 automated tests total, all passing**, all labeled MOCKED except the 31 new SIMULATED rules checks.

> **CORRECTION — Sprint 4.2.1 (Pre-Write Card Authority & Desync Safety).** A direct review of the above found two Critical defects the paragraphs above no longer accurately describe: `submitCard()` claimed to check "only generic shape," but this ALSO meant it never checked whose turn it was OR whether the card was legal — either gap let Firestore accept a write it shouldn't have. Both are now fixed. See the dedicated Sprint 4.2.1 section immediately below for the full, corrected account — read it before treating anything above about `submitCard()`'s validation scope as current.

## Sprint 4.2.1 (Pre-Write Card Authority & Desync Safety)

**A hotfix, not a feature sprint** — see `docs/reviews/CardAuthorityHotfix_4.2.1.md` for the full implementation report. Closes two Critical defects: (1) `submitCard()` never verified whose turn it was; (2) card legality was checked only AFTER the write, by whichever client replayed the log, leaving an engine-rejected entry in `cardLog` PERMANENTLY.

### Task 1 — Turn Authority Before Write

`submitCard()` now resolves the seat and calls `MatchAdapter.assertLocalTurn()` — Sprint 4.1's EXISTING authority gate, called verbatim — via a plain document read, BEFORE `runTransaction()` is ever invoked. A wrong-turn caller is rejected `NOT_YOUR_TURN` with zero writes attempted, verified directly (`STORE[...].version`/`.cardLog` provably untouched).

### Task 2 — Pre-Write Engine Validation

`submitCard()` now calls `TableEngine.canPlayCard(seatId, card)` — a new, purely additive, non-mutating export (see `EngineAdapter.md`'s Sprint 4.2.1 section for why this required no engine rewrite) — BEFORE the transaction, using the SAME `isLegal()`/turn/phase checks `emit()` already performs internally. An illegal card is rejected `ILLEGAL_CARD` with zero writes attempted — `cardLog` can no longer contain an entry the real engine would reject.

### Task 3 — Remote Rejection Causes Desync

`applyRemoteCard()` now stops immediately on `ENGINE_REJECTED`, never processes a later entry in that delivery, and advances its OWN registries only up to (never past) the rejected index — the version registry isn't advanced AT ALL, so a future delivery correctly re-attempts the same stuck point rather than being treated as fully synchronized. Returns a structured `{desync: true, reason, matchId, index, seatId, engineReason}` — never auto-retries.

### Task 4 — Card Log Integrity: a documented, not a fixed, limitation

Assessed and concluded CEL cannot safely prove `cardLog` prefix immutability (no index-by-index list-comparison primitive) — demonstrated directly via two new SIMULATED tests proving a rewrite AND a reorder of earlier entries both currently pass `isValidCardSubmission()`. `cardLog` is marked client-authoritative, MVP-only — see `docs/architecture/SecurityArchitecture.md`'s "Card write authority" section for the full risk statement and two documented future directions (a Cloud Function, or an append-only `plays` subcollection).

Testing: **870 automated tests total** (up from 842) — see `docs/reviews/CardAuthorityHotfix_4.2.1.md`'s own testing section for the full breakdown.

## Sprint 4.2.2 (Atomic Card Turn Progression & Card-Log Desync Hardening)

**A hotfix, not a feature sprint** — see `docs/reviews/CardTurnProgressionHotfix_4.2.2.md` for the full implementation report. Closes three remaining defects a direct review of Sprint 4.2.1's shipped code found: (1) `submitCard()` appended the card but never updated `matches/{matchId}.turn`, so the next player was incorrectly rejected as "not your turn"; (2) `applyRemoteCard()` silently skipped a `MALFORMED_ENTRY` item instead of treating it as a desync; (3) the local-echo check compared only seat identity, not card identity.

### Task 1 — Engine-Owned Next-Turn Preview

New, purely additive `TableEngine.previewPlay(playerId, card)` — composes the existing `canPlayCard()` (Sprint 4.2.1) plus the SAME `state.plays.length`/`nextCCW()` arithmetic `emit()` already performs internally. Never mutates, never calls `emit()`. Returns `{legal, nextTurnSeat, nextPhase}` — `nextTurnSeat: null, nextPhase: "RESOLVING"` on the trick's 4th card.

### Task 2/3 — Atomic Persist + Transaction Revalidation

`submitCard()` now writes `cardLog`, `lastCardSeat`, `turn` (the UID owning `preview.nextTurnSeat`, or `null`), `cardPhase`, `version+1`, and `updatedAt` in ONE `tx.update()` call — the fix that makes sequential p1→p2→p3→p4 production submission actually work, with no test-only turn mutation anywhere in the path. An `expectedVersion` fingerprint, captured OUTSIDE the transaction at the moment the local engine preview was computed, is re-checked on every invocation of the transaction callback (including automatic Firestore SDK retries); a mismatch throws `STALE_GAME_STATE` and writes nothing — deliberately never an automatic retry against changed engine state.

### Task 4/5 — `applyRemoteCard()`: two more desync paths

`MALFORMED_ENTRY` now stops processing immediately (previously skipped silently and advanced past it) — same stuck-index/no-version-advance contract already established for `ENGINE_REJECTED`. The local-echo check now compares the actual card (`suit`+`rank.v`), not just seat identity — a same-seat, different-card delivery now produces a structured `LOCAL_ECHO_MISMATCH` desync rather than being silently accepted as an echo.

### Task 6 — `firestore.rules`

`isValidCardSubmission()` extended in place to verify the caller owned the previous turn, the new `turn` is a structurally valid seat UID or `null`, and `cardPhase` is a valid enum value — still cannot verify the new turn is the CORRECT next seat (client-authoritative, unchanged in kind from every prior sprint's framing).

Testing: **889 automated tests total** (up from 870) — see `docs/reviews/CardTurnProgressionHotfix_4.2.2.md`'s own testing section for the full breakdown.

## Sprint 4.3 (Trick Resolution Synchronization)

**A STRICT implementation sprint: online trick-winner synchronization ONLY** — see `docs/reviews/TrickResolutionSync_4.3.md` for the full implementation report. `table-engine.js` remains the single, unmodified authority for trick winner, follow suit, trump, played cards, trick completion, and next leader.

### Task 1 — no engine change needed

`table-engine.js` already exposed everything required: `getState().phase === "RESOLVING"` (the existing signal `emit()` sets on the trick's 4th card) and `resolveTrick()` (exported since Sprint 3.6, the SAME function the real offline turn loop already calls internally) together fully determine trick completion, winner, and next leader.

### `applyRemoteTrick()` / `startTrickSync()` — Task 2/3

`applyRemoteTrick()`'s ONLY direct engine call is `TableEngine.resolveTrick()`; the winner is read back from `TableEngine.getState().lastTrick.winnerId` afterward — never computed or duplicated. Guarded by the engine's own `phase !== "RESOLVING"` precondition (an ordinary no-op) plus a dedicated `lastResolvedTrickNoByMatch` registry (deliberately not a version gate — see that function's own comment for why). `startTrickSync()` reuses `MatchService.subscribeToMatch()` verbatim (no second listener) but its callback LOOPS, alternating the existing `applyRemoteCard()` and the new `applyRemoteTrick()` up to 13 times per delivery — a documented necessity, since `cardLog` is append-only and never cleared across trick boundaries, so one delivery can carry multiple already-completed-but-unresolved tricks.

**Necessary completion beyond the original Task 2 wording**: `applyRemoteTrick()` also mirrors the resolved trick's next leader into `GameSession.setTurn()` (an existing, unmodified setter) — without this, `assertLocalTurn()`'s pre-existing fallback would keep reporting a stale turn-holder after every resolution (nothing else writes the real next leader back into `matches/{matchId}.turn`, which is `null` at the resolving boundary per Sprint 4.2.2), blocking all further play after trick 1.

### Task 4/5 — no MatchService or firestore.rules change

The trick winner, next leader, and tricksWon tally are ALL deterministically re-derivable, by every client, from data already synchronized (`cardLog` + the immutable rules `table-engine.js` already enforces identically everywhere) — synchronization by determinism, not by a broadcast write. No new field, no new write path.

Testing: **954 automated tests total** (up from 898) — see `docs/reviews/TrickResolutionSync_4.3.md`'s own testing section for the full breakdown.
