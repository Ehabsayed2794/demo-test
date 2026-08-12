# Implementation Report — Sprint 4.2.3: Firestore Rules Compile-Safe Card Turn Hotfix

**A small hotfix, not a feature sprint.** A direct review of `firestore.rules` as shipped through Sprint 4.2.2 found that the specific turn-validation expression Sprint 4.2.2's own Task 6 added used a List method (`.exists()`) that is not part of Firestore Rules' officially documented syntax. No gameplay behavior, schema, `MatchService`, `MatchAdapter`, or `TableEngine` change. Spark only.

## 1. The finding, restated precisely

`isValidCardSubmission()`'s Sprint 4.2.2 turn check read:

```
(newData.turn == null || oldData.seats.keys().exists(s, oldData.seats[s] == newData.turn))
```

`.exists()` is a List method. Firestore Rules' officially documented List method reference (https://firebase.google.com/docs/reference/rules/rules.List) catalogues `concat`, `hasAll`, `hasAny`, `hasOnly`, `in`, `indexOf`, `isEmpty`, `join`, `removeAll`, `reverse`, `size`, `toSet` — no `.exists()`. This project's own `tests/rules-simulation.test.js` never caught this, because it is a JS re-implementation of each rule's INTENDED logic, exercised as plain JavaScript — it proves the LOGIC is right; it does not compile or execute real CEL (the language Firestore Rules actually runs), so it cannot prove any given construct — including `.exists()` — is actually part of that language's supported surface.

## 2. Task 1 — Remove the unsupported `.exists()` usage

Replaced with explicit, compile-safe `Map.get(key, default)` lookups against the 4 fixed, canonical seat keys:

```
(
  newData.turn == null
  || newData.turn == oldData.seats.get('p1', null)
  || newData.turn == oldData.seats.get('p2', null)
  || newData.turn == oldData.seats.get('p3', null)
  || newData.turn == oldData.seats.get('p4', null)
)
```

`Map.get(key, default)` is officially documented and returns the default (here, `null`) rather than erroring when the key is absent — exactly what a partial seats map (fewer than 4 real players) needs, since `newData.turn` (a real uid, or `null`) can never legitimately equal a missing seat's placeholder `null` default unless `newData.turn` is itself `null`, which the first disjunct already allows explicitly.

**Verified directly**: `tests/rules-simulation.test.js`'s new Task 3 checks (see §4 below) exercise all four real seat UIDs, an unknown UID, an empty string, and a partial (2-seat) map — all against this exact rewritten expression's JS mirror.

## 3. Task 2 — Full-file audit for unsupported constructs

Searched the entire `firestore.rules` file for `.exists(`, `.all(`, `.any(`, nested `.diff(`, lambda-style syntax, and undocumented collection/list iteration. Found and fixed three MORE occurrences of `.all()` — unrelated to Sprint 4.2.2's own Task 6 change, pre-existing since Sprint 3.8/3.9 — under the same compile-safety standard:

1. **`isValidSeatMap()`'s bijection checks** (`seatKeys.all(s, seats[s] in players)` and a nested `seatKeys.all(s1, seatKeys.all(s2, s1 == s2 || seats[s1] != seats[s2]))`) — rewritten to explicit per-seat `Map.get(key, null)` checks (4 "does this seat's uid belong to `players`" checks) plus the 6 explicit pairwise inequality checks a 4-element self-join reduces to.
2. **`isValidNewMatch()`'s "every bid starts null" check** (`data.seats.keys().all(s, data.bids[s] == null)`) — rewritten to 4 explicit per-seat checks.
3. **`isValidBidSubmission()`'s `biddingOpen` computation** (`oldData.seats.keys().all(s, s in newData.bids && newData.bids[s] != null)`) — rewritten to 4 precomputed `let`-bound per-seat facts, ANDed together.

Every remaining `.diff(...).affectedKeys().hasOnly([...])` occurrence in the file (the `players/{uid}` and `rooms/{roomId}` field-restriction rules, and the un-flagged parts of `isValidBidSubmission()`/`isValidCardSubmission()`) was individually re-checked and found to be genuinely, officially documented (`Map.diff()`, `MapDiff.affectedKeys()`, `List.hasOnly()`). None of those needed to change. `exists(/path/)` (the room/match existence checks in `isValidNewMatch()`, e.g. `exists(/databases/.../rooms/$(data.roomId))`) is a DIFFERENT, unrelated, officially-documented top-level Rules function for path existence — not the List method this finding is about, correctly left untouched.

No loops, lambdas, `.all()`, `.exists()`, or undocumented CEL constructs were introduced anywhere in this rewrite.

## 4. Task 3 — Tests

Added a new Sprint 4.2.3 section to `tests/rules-simulation.test.js` with the 8 scenarios (plus one extra, #8b, for the "does not crash" requirement specifically) the brief requires, all labeled SIMULATED:

1. Null turn allowed at the resolving boundary.
2. p1's own uid allowed.
3. p2's own uid allowed.
4. p3's own uid allowed.
5. p4's own uid allowed.
6. An unknown uid (owns no seat at all) rejected.
7. An empty string rejected.
8. A partial (2-seat, p1/p2-only) seats map behaves safely — p2's own uid is still allowed as the next turn with p3/p4 entirely absent (#8), and the same partial map does not crash the simulated logic when checked against an unknown uid (#8b).

A prominent, restated honesty note was added directly above these tests and near the top of the file: this JS harness's own passing checks verify LOGICAL INTENT only. They do not compile or execute the actual `firestore.rules` file, and cannot prove `firestore.rules` itself is free of unsupported CEL syntax generally — only that the SPECIFIC constructs this sprint's audit found and rewrote are gone from the source text, and that the intended logic behind each rewrite is unchanged. Real Firebase Emulator or Rules-compiler verification remains PENDING — this project has never run either, this sprint or any prior one.

## 5. Task 4 — Documentation honesty

Updated `docs/architecture/SecurityArchitecture.md` with a new "Compile-safe Rules syntax" section (placed directly above "Card write authority") stating explicitly: the JS rules simulation verifies logical intent only; it does not compile or execute `firestore.rules`; real Emulator/compiler verification remains pending. Updated that same document's Sprint 4.2.2 "Card write authority" bullet to stop describing `.keys().exists()` as the current mechanism (it now correctly points at the "Compile-safe Rules syntax" section instead). Added a superseding-update banner to `docs/reviews/CardTurnProgressionHotfix_4.2.2.md` pointing here, per this project's "correct forward, don't rewrite history" convention — that document's own body text is left otherwise unedited as the historical record of what Sprint 4.2.2 actually shipped.

## 6. Full regression

Full suite re-run after every change: `deck` (39), `bid-sync` (39), `turn-sync` (26), `match-service` (67), `match-sync` (58), `submit-bid` (66), `room-service` (31), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31), `match-adapter` (119), `card-sync` (41), `submit-card` (34), `rules-simulation` (158, up from 149 — 9 new Task 3 checks) — all passing. **898 automated tests total, all passing** (up from 889).

## 7. Honest limitations / what remains

- The unsupported `.exists()`/`.all()` List-method usages are gone from `firestore.rules`. This project's JS rules simulation STILL cannot prove the file compiles against the real Firebase Rules engine — no test in this project, this sprint or any prior one, has run against the Firebase Emulator or a real Rules compiler. That verification remains explicitly PENDING, not performed, not claimed.
- Every rewrite in this sprint is a syntax-level, semantics-preserving change to already-existing, already-correct rules — no new gameplay legality, turn-order, or scoring check was added or removed. `isValidCardSubmission()`'s own honest limitation (cannot verify the new turn is the CORRECT next seat, only that it's structurally possible) is unchanged by this sprint.
- `cardLog` prefix/order integrity (Sprint 4.2.1's Task 4 finding) is untouched, not re-assessed, this sprint.
- No trick resolution, trick winner persistence, scoring, next round, match end, replay, voice chat, AI, or matchmaking work was started, per this hotfix's own explicit stop list.

## 8. Conclusion

The unsupported `.exists()` expression named in this sprint's brief is gone from `firestore.rules`, along with three other pre-existing `.all()` occurrences the same audit found. All replacements use explicit, officially-documented syntax (`Map.get(key, default)`, plain boolean logic) — no loops, lambdas, or undocumented CEL constructs were introduced. The full regression suite passes (898/898). This project's rules-compile-validity gap is now explicitly documented rather than silently assumed closed by a JS simulation that was never capable of proving it. Stopping here per the brief's stop condition — no Trick Resolution, Winner Persistence, Score Sync, Next Round, Match End, Voice Chat, AI, or Matchmaking. Waiting for review.
