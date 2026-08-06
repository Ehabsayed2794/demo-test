# Real-Time Match Synchronization — Sprint 3.7

**Scope: synchronization only.** No gameplay rule, scoring formula, bidding rule, or engine file (`Dealer`, `Deck`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`) changed this sprint. No AI, chat, voice, matchmaking, Ready-state improvement, replay, or leaderboard work was started — see this document's "Not started" section. Spark-compatible throughout — no Cloud Functions, no Blaze.

## Goal

Every connected player should observe the same `matches/{matchId}` document state in real time, through the architecture the project already committed to:

```
Firestore  ⇄  MatchService  →  GameSession  →  (future) UI/engine consumers
                 (Services)      (gameplay state holder)
```

- `MatchService` is the single source of truth for match synchronization — the only file that calls `onSnapshot()` on a `matches/{matchId}` document.
- `GameSession` remains the gameplay state holder — it now also holds a live mirror of whatever `MatchService` publishes, but it never talks to Firestore directly. It consumes `MatchService`.
- The UI never talks to Firestore directly (unchanged from every prior sprint — it never has).

## What was implemented

### 1. `MatchService.subscribeToMatch(matchId, callback)` — production-ready

This is the brief's requested `subscribe(matchId)`. It keeps its existing name, `subscribeToMatch`, for the same reason already on record in Sprint 3.4's implementation and in `docs/architecture/ServiceArchitecture.md`: naming consistency with every other service's subscribe method in this codebase (`PlayerService.subscribeToPlayerProfile`, the design intent for `RoomService.subscribeToRoom`). Deviating from the brief's literal method name is documented here, not hidden, matching this project's established practice.

Six properties, all new this sprint, all inside `design-ui/match-service.js`:

| # | Requirement | Mechanism |
|---|---|---|
| 1 | Uses `onSnapshot()` | Unchanged from Sprint 3.4 — `db().collection("matches").doc(matchId).onSnapshot(onNext, onError)`. |
| 2 | Publishes the newest state on every change | `onNext` decodes the snapshot and calls every locally-registered callback with `(data, null)`. |
| 3 | Event-driven only, no polling | The ENTIRE implementation is callback-driven — there is no `setInterval`/timer anywhere in the data path. (The one timer in this file, `scheduleReconnect`'s backoff, only ever fires after a genuine `onSnapshot` error — it is not a polling loop, and it stops scheduling itself the moment either a snapshot succeeds again or the last subscriber unsubscribes.) |
| 4 | Ignore local duplicate updates / prevent infinite update loops | Two guards, both in `attachListener()`'s `onNext`: an **ordering guard** (below) and a **duplicate-content guard** — a snapshot whose data is structurally identical to the last one published is never re-published. This is what makes a hypothetical future write-then-listen round trip loop-safe by construction, even though nothing in this codebase writes back to a match document yet (see "Known Limitation" below). |
| 5 | Ordering consistency | The ordering guard: a document written with a numeric `version` field is only published if it is *strictly greater* than the last `version` seen for that `matchId`. An old/out-of-order snapshot can never overwrite newer state. See "Known Limitation" for why this guard is armed but currently dormant in production. |
| 6 | Disconnect handling | On an `onSnapshot` error: (a) the last known good data is delivered *alongside* the error, never replaced with `null` — "keep local game alive"; (b) `scheduleReconnect()` schedules exactly one pending resubscribe attempt with exponential backoff (250ms → 500ms → 1s → 2s → capped at 4s), resetting to the base delay the moment a snapshot succeeds again; (c) nothing ever throws — every failure path calls the caller's callback with an `Error`, never an unhandled exception. |
| 7 | Clean unsubscribe, no leaks, no duplicated listeners | `matchSubscriptions[matchId]` is a **ref-counted** registry entry: the first `subscribeToMatch(matchId, cb)` call for a given `matchId` creates ONE entry and ONE real `onSnapshot()` registration; every subsequent call for the *same* `matchId` just appends `cb` to that entry's `listeners` array — never a second Firestore listener. Each call's own `unsubscribe()` removes only its own callback; the underlying Firestore listener (and any pending reconnect timer) is torn down the moment `listeners.length` reaches zero, and the registry entry itself is deleted — nothing is left to leak. |
| 8 | Multiple tabs stay synchronized | A natural consequence of the above: each browser tab is its own separate JS process with its own call to `subscribeToMatch()`; Firestore's own snapshot delivery (server-ordered per document) plus the guards above mean every tab converges on the same state. Verified directly in `tests/match-sync.test.cjs` via two independent subscriber callbacks against the same mocked document. |

### 2. `GameSession` consumes `MatchService` updates

New, additive-only surface on `design-ui/engine/session.js` (nothing existing was changed):

- `subscribeToRemoteMatch(matchId)` — begins consuming `MatchService.subscribeToMatch(matchId, ...)`. Idempotent for the same `matchId` (a repeat call is a no-op); switching to a different `matchId` cleanly tears down the old subscription first. Fail-open (warns, never throws) if `MatchService` isn't loaded on the page.
- `unsubscribeFromRemoteMatch()` — the one path that ever calls the stored unsubscribe function.
- `getRemoteMatch()` / `getRemoteMatchError()` / `isSubscribedToRemoteMatch()` — read the current mirrored state.
- `onRemoteMatchUpdate(callback)` — GameSession's own local pub/sub over the mirror, deliberately shaped exactly like `SessionService.subscribe()` (fires immediately with the current value, then again on every change, returns an unsubscribe) — reusing an already-established project pattern rather than inventing a new one.

`GameSession` still never touches Firestore, `firebase`, or `window.Db` anywhere in `session.js` — every remote update arrives already-decoded through `MatchService`'s public API. This is verified directly (not just by code inspection) in `tests/match-sync.test.cjs`'s GameSession section, which asserts the underlying `onSnapshot()` call count is exactly one per matchId regardless of how GameSession is called.

## Known Limitation — UID vs. seat-id, deliberately not solved this sprint

`matches/{matchId}` identifies `players`/`dealer`/`turn` by real Firebase Auth **uid** (see `FirestoreSchema.md`). The gameplay engine (`GameSession`'s existing `round`/`biddingState`/`playState` fields, `Dealer`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`) identifies seats by canonical **seat id** (`p1`..`p4`). This sprint's sync pipe deliberately does **not** attempt to reconcile these two id spaces — `GameSession.getRemoteMatch()` returns the raw, uid-keyed document as MatchService published it, with no merge into the seat-id-keyed fields above it in the same file. Attempting that merge would mean inventing a uid↔seat mapping convention, which is gameplay-adjacent design work outside "synchronization only," and the brief's explicit "Do NOT redesign gameplay" instruction. This is recorded here, not hidden, as the concrete prerequisite the next gameplay-writing sprint needs to solve.

A direct consequence: this sprint does not implement any of `MatchService`'s still-stubbed gameplay methods (`submitBid`, `submitEstimate`, `playCard`, etc. — unchanged, still throwing `Not implemented`), and does not touch `firestore.rules`' `matches/{matchId}` block, which still correctly has `allow update: if false` (there is still no legitimate write path to a match document after creation — see `FirestoreSchema.md`). The "Card play appears remotely" / "Estimates synchronize" / "Turn changes synchronize" test requirements are satisfied by proving the **sync pipe itself** carries an arbitrary change to a match document's `gameState`/`turn` fields correctly, in real time, to every subscriber — exactly the shape a future `playCard()`/`submitEstimate()` write will produce once that gameplay-write sprint reconciles the id-space question above and adds a real write path (and its own `firestore.rules` update) for it. Simulating that future write directly against the (mocked) Firestore document, rather than inventing a premature real one, is what `tests/match-sync.test.cjs` does — this is a deliberate, documented interpretation choice, not an oversight.

## Not started (per the brief's explicit stop list)

Ready-state improvements, Replay, Voice Chat, Chat, AI, Leaderboard, Matchmaking, Cloud Functions. None of these were touched.

## Files changed

- `design-ui/match-service.js` — `subscribeToMatch()` rewritten in place (ref-counted registry, ordering/duplicate guards, automatic reconnect). `startMatch()`/`loadMatch()`/every gameplay stub unchanged.
- `design-ui/engine/session.js` — additive only: `subscribeToRemoteMatch`/`unsubscribeFromRemoteMatch`/`getRemoteMatch`/`getRemoteMatchError`/`isSubscribedToRemoteMatch`/`onRemoteMatchUpdate` added; every existing function/field unchanged.
- `tests/match-sync.test.cjs` — new, 50 checks (see `TEST_CHECKLIST.md`).
- `docs/architecture/ServiceArchitecture.md`, `docs/architecture/MatchLifecycle.md` — updated (see each file's own Sprint 3.7 note).
- Nothing else. `firestore.rules`, `Dealer`/`Deck`/`Cards`/`bidding-engine.js`/`table-engine.js`/`scoring-engine.js`, `RoomService`, `SessionService`, `PlayerService`, and every UI screen are untouched.

## Testing summary

50 new checks in `tests/match-sync.test.cjs`, covering every one of the brief's ✓ items (two-tab sync, card-play/estimate/turn passthrough, reconnect, impossible-duplicate-listeners, memory-leak teardown/re-attach, offline recovery including a sustained-outage backoff sequence, snapshot ordering including same-version and late-arriving-stale cases, and a zero-gameplay-rule-changes sanity check), plus GameSession's consumption of all of the above. Re-run 4+ times with no flakiness (the reconnect tests use real, short timers — 250ms-4s backoff — not mocked time).

Full pre-existing regression suite re-run and unchanged: `tests/deck.test.cjs` (39), `tests/match-service.test.cjs` (59 — including its own pre-existing `subscribeToMatch` tests, now exercising the new implementation and still passing unmodified), `tests/room-service.test.cjs` (31), `tests/rules-simulation.test.js` (61), `tests/match-flow-integration.test.cjs` (156), `tests/match-flow-normal-dash-scoring-fix.test.cjs` (16), `tests/match-flow-scoring-scenarios.test.cjs` (31). **443 automated tests total, all passing.**
