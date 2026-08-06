# Synchronization Hardening Report — Sprint 3.7.1

**Sprint type:** correctness and architecture hardening. Not a feature sprint — no gameplay feature, bidding write, card-play write, turn authority, matchmaking, replay, chat, voice, or UI redesign was implemented, per the brief's explicit rules. Spark-compatible throughout.

## 1. Executive Summary

An independent architecture review of Sprint 3.7 (Real-Time Match Synchronization) found two real defects and one systemic documentation problem, all in `design-ui/match-service.js`'s `subscribeToMatch()` and its surrounding documentation:

1. **Unconditional retry.** Every `onSnapshot` error was retried forever, including permanent ones (`permission-denied`, etc.) — genuinely unacceptable, matching the brief's own framing. **Fixed.**
2. **Overstated ordering guarantee.** The ordering guard exists in code but is never active for any real document, since nothing writes the `version` field it depends on. Sprint 3.7's documentation did not consistently make this clear. **Corrected — no code change needed, since the guard's logic was already correct, only inactive.**
3. **Testing claims lacked explicit "mocked"/"simulated" qualifiers.** Several Sprint 3.7 test-result descriptions read as unqualified facts about real browser/network behavior that was never actually exercised. **Corrected**, and — as a direct consequence of this review — **a real bug was found and fixed in the test mock itself** (see §4).

This report also delivers a documentation-only Seat Identity Model (Task 3) and a small, verified-behavior-preserving cleanup pass (Task 5). All six tasks are addressed below in order.

## 2. Task 1 — Retry Policy Hardening

**Before:** `attachListener()`'s `onSnapshot` error handler called `scheduleReconnect(matchId, entry)` unconditionally, with no inspection of the error at all.

**After:** every error is classified via `.code` against the brief's exact two lists (`RETRYABLE_CODES`, `NON_RETRYABLE_CODES` in `match-service.js`). Retryable → unchanged exponential-backoff reconnect. Non-retryable, OR any code not in either list (including a missing `.code`) → stop immediately, record `entry.terminalError`, log a `console.warn` naming the classification and code, and expose the error to every current subscriber and any future late joiner. The error is delivered alongside the last known good data in both cases — this was already correct in Sprint 3.7 and is unchanged.

**Decision log (every retry decision, explicit):**
- Retryable codes retry because they represent states a client cannot distinguish from "will resolve itself shortly" — the same class of error every production Firestore SDK itself retries internally.
- Non-retryable codes stop immediately because retrying them can never succeed — `permission-denied` will not become `permission-denied: false` by waiting; only a NEW subscription attempt (a different call, potentially after the underlying cause — e.g. re-authentication — is fixed) can help, and that is the caller's decision to make, not this layer's to loop on forever.
- An unrecognized or missing code is treated as non-retryable, not retryable, on the theory that assuming "safe to retry" for an error we cannot positively classify recreates the exact problem this task exists to close. This is the one place a genuinely retryable-but-unlisted future Firestore error code would be mis-classified as terminal rather than retried — a known, accepted tradeoff, recorded here rather than silently chosen.
- No maximum retry-attempt CAP was added for retryable errors. This is deliberate: "never enter an infinite reconnect loop" is read here as "never retry something that cannot succeed" (now fixed), not as "never keep trying something that legitimately might succeed later" — capping retryable attempts would trade a real reconnect capability for an arbitrary UX regression without closing any actual defect.

**Verified:** `tests/match-sync.test.cjs` — 9 new checks with mocked errors carrying specific `.code` values (`unavailable` retries and recovers; `permission-denied` stops permanently and informs a late joiner; no code at all is treated the same as non-retryable).

## 3. Task 2 — Ordering Guard Review

**Where does `version` come from?** Nowhere — confirmed by a repository-wide search; no write path (`buildInitialMatchDoc()`, any of `MatchService`'s ten gameplay stubs, or any client write — `firestore.rules` denies `matches/{matchId}` update outright) ever sets it.

**Who increments it? Who owns it?** No one. There is no owner today because there is no writer today.

**Verdict, stated per the brief's exact instruction:** ordering protection is **not currently active** for any real match document. The guard's code is correct and will activate automatically, with no further change, the moment a future write path starts writing `version` — but until then, this document and every other doc referencing it now say so explicitly, rather than describing the guard's existence as if it were already-active protection.

**Another mechanism providing SOME ordering today, stated honestly:** within one uninterrupted `onSnapshot` listener lifetime, Firestore's client SDK itself delivers a single document's updates in server-commit order — this is the SDK's guarantee, not this codebase's. It does not survive a reconnect (a fresh listener starts a new delivery sequence with no ordering relationship to what a previous, detached listener already delivered) — which is exactly the gap the dormant `version` guard is meant to close once it has real data to act on.

**No code change required** — only documentation, in `match-service.js`'s own comments, `ServiceArchitecture.md`, and `MatchSynchronization.md`.

## 4. Task 6 — Documentation Honesty (reported here ahead of Tasks 3-5 because it surfaced a real bug)

While correcting test-result phrasing to explicitly say "mocked"/"simulated," a genuine defect was found in `tests/match-sync.test.cjs`'s own test harness: `simulateDisconnect()` fired the mocked `onError` callback but never actually removed the mocked `onNext` listener from the mock's internal registry. This meant a "reconnect" test could pass without MatchService ever actually re-attaching a new listener — the STALE, never-truly-detached original mock listener was quietly still delivering updates underneath the test, proving nothing about the real reconnect code path.

**Fixed:** `simulateDisconnect(id, code)` now genuinely detaches the mocked listener (removes it from the mock's `LISTENERS`/`pendingErrorCallbacks` registries) before firing the error, and accepts an explicit `code` parameter so a test can choose exactly which classification (Task 1) it is exercising. Re-verified: the reconnect tests now show the underlying mocked `onSnapshot()` call counter genuinely incrementing on reconnect (a fresh registration), which the OLD, buggy mock could never have proven.

This is reported prominently, not minimized, because it's the concrete illustration of why this task matters: an unqualified "PASS" on a mocked/simulated test can hide a broken mock, not just a broken implementation. Every test-result claim in `MatchSynchronization.md`, `ServiceArchitecture.md`, and the test file's own comments/labels has been reviewed and corrected to say "mocked" or "simulated" explicitly wherever it previously read as an unqualified real-world result (see `MatchSynchronization.md`'s Task 6 section for the itemized list).

**No real Firestore project, no Firebase emulator, and no real browser instances were used anywhere in this project's test suite, this sprint or any prior one.** Every "PASS" in every `tests/*.cjs`/`tests/*.js` file in this repository is a mocked or simulated result. This has been true since Sprint 2.6 and is restated here in full, not because it changed, but because it had never been stated this bluntly before.

## 5. Task 3 — Seat Identity Model

Documentation only, delivered as a new, dedicated file: `docs/architecture/SeatIdentityModel.md`. No code was written. Summary of the design (full reasoning in that file): a `seats: {p1..p4: uid}` map, owned by `MatchService`, assigned once (positionally from `rooms/{roomId}.players`) inside the existing `startMatch()` transaction, immutable for the match's lifetime, synchronized for free via the already-shipped `subscribeToMatch()` pipe, validated (once any write path exists — none does today) against the exact same `hasOnly`/set-equality pattern already used elsewhere in `firestore.rules`, and — the one point worth restating here — any future write claiming to act as a seat MUST be checked against this map's uid, never trusted from a client-supplied seat id alone.

## 6. Task 5 — Code Cleanup

Reviewed `design-ui/match-service.js` and `design-ui/engine/session.js` for dead code, duplicated conditions/branches, and obsolete comments, stated honestly rather than inflated: **the Sprint 3.7 code was already fairly lean.** One real duplication was found and fixed — `design-ui/engine/session.js` had the identical "safely invoke a listener callback, catching and logging any throw" logic written out twice (`notifyRemoteMatchListeners()`'s `forEach` body, and `onRemoteMatchUpdate()`'s own immediate-invocation call). Consolidated into one shared `safeInvokeRemoteMatchListener()` helper. No other dead code, unused branches, or obsolete comments were found in either file's Sprint 3.7 additions worth removing — Task 5 does not manufacture cleanup work where none exists. Verified: every automated test (452 total) passes identically before and after this change — no behavioral difference.

## 7. Task 4 — QA Package Integrity

`Sprint-3.7.1-Review/` contains a copy of every implementation file this sprint modified — `design-ui/match-service.js`, `design-ui/engine/session.js` — plus every new/updated doc (`MatchSynchronization.md`, `SeatIdentityModel.md`, `ServiceArchitecture.md`, `MatchLifecycle.md`, this report) and the new/updated test file (`tests/match-sync.test.cjs`). Verified directly against `git diff --stat`'s own file list (see `TEST_CHECKLIST.md`'s Task 4 section) — nothing modified is missing from the package.

## 8. Testing Summary

`tests/match-sync.test.cjs`: 59 checks (Sprint 3.7's 50 + 9 new retry-classification checks), all against a mocked Firestore, re-run 4+ times with no flakiness. Full pre-existing regression suite re-run and unchanged: `deck` (39), `match-service` (59), `room-service` (31), `rules-simulation` (61), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31). **452 automated tests total, all passing.**

## 9. Files Changed

| File | Change |
|---|---|
| `design-ui/match-service.js` | Retry classification (Task 1); ordering-guard comments corrected (Task 2); one `console.warn` added. Ordering/duplicate-content guard LOGIC, `startMatch()`, `loadMatch()`, every gameplay stub — unchanged. |
| `design-ui/engine/session.js` | One duplicated try/catch consolidated (Task 5). No behavioral change. |
| `tests/match-sync.test.cjs` | `simulateDisconnect()` mock bug fixed (Task 6); 9 new retry-classification checks added (Task 1); labels/comments corrected for honesty (Task 6). |
| `docs/architecture/SeatIdentityModel.md` | New (Task 3). |
| `docs/architecture/MatchSynchronization.md` | Corrected in place — retry policy, ordering-guard status, testing-claim honesty (Tasks 1, 2, 6). |
| `docs/architecture/ServiceArchitecture.md` | Updated — `subscribeToMatch()`'s entry now describes the retry policy and the ordering guard's inactive status accurately. |
| `docs/architecture/MatchLifecycle.md` | Sprint 3.7.1 note added. |
| `Sprint-3.7.1-Review/` | New QA package (Task 4). |
| **Not touched** | `firestore.rules`, `Dealer`, `Deck`, `Cards`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `RoomService`, `SessionService`, `PlayerService`, every UI screen. |

## 10. Honest Status / What Remains

- The ordering guard remains inactive until a future sprint introduces a real write path that sets `version`. This report does not claim otherwise.
- No gameplay write, bidding sync, card sync, or turn authority exists after this sprint, same as before it.
- The Seat Identity Model is a design, not an implementation — `matches/{matchId}` has no `seats` field, and `firestore.rules` has no rule referencing one.
- Every test in this project, including every test added this sprint, runs against a mocked/simulated harness — none against a real Firestore project, the Firebase emulator, or real browsers/devices.

## 11. Stop Condition

Per the brief: gameplay writes, bidding sync, card sync, turn authority, and multiplayer gameplay were not started. Waiting for review before any of those begin.
