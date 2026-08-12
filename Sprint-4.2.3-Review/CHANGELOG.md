# Changelog — Sprint 4.2.3: Firestore Rules Compile-Safe Card Turn Hotfix

**A small hotfix, not a feature sprint.** Removes an unsupported Firestore Rules List method (`.exists()`) and three other pre-existing, unrelated occurrences of the same class of issue (`.all()`), found by a direct review of the shipped `firestore.rules`. No gameplay behavior change. No schema change. No `MatchService`/`MatchAdapter`/`TableEngine` change. Spark only.

## The finding

`isValidCardSubmission()`'s turn-validation expression (added Sprint 4.2.2, Task 6) used `oldData.seats.keys().exists(s, oldData.seats[s] == newData.turn)`. `.exists()` is not part of Firestore Rules' officially documented List method surface (https://firebase.google.com/docs/reference/rules/rules.List). This project's `tests/rules-simulation.test.js` re-implements each rule's intended LOGIC in plain JavaScript, exercised as JS — it can prove logical intent, but it does not compile or execute real CEL, so it could never have caught this. A full-file audit found three more pre-existing `.all()` occurrences (Sprint 3.8/3.9-era, unrelated to Sprint 4.2.2's own change) under the same standard.

## Changed

- **`firestore.rules` — `isValidCardSubmission()`**: the flagged `.exists()` expression replaced with explicit `Map.get('p1'/'p2'/'p3'/'p4', null)` lookups against the 4 fixed, canonical seat keys, OR'd together. Semantically identical to the original.
- **`firestore.rules` — `isValidSeatMap()`**: both `.all()` uses (the "every seat owns a real player" check and the nested-`.all()` "no two seats share a uid" self-join) replaced with explicit per-seat `Map.get(key, null)` lookups and the 6 explicit pairwise inequality checks a 4-element self-join reduces to.
- **`firestore.rules` — `isValidNewMatch()`**: the "every bid starts null" `.all()` check replaced with 4 explicit per-seat checks.
- **`firestore.rules` — `isValidBidSubmission()`**: the `biddingOpen` computation's `.all()` check replaced with 4 precomputed `let`-bound per-seat facts, ANDed together.
- **`tests/rules-simulation.test.js`**: added a new Sprint 4.2.3 test section (9 new checks, all labeled SIMULATED) covering: null-turn-allowed, p1/p2/p3/p4-allowed, unknown-uid-rejected, empty-string-rejected, partial-seats-map-behaves-safely, and partial-seats-map-does-not-crash-against-an-unknown-uid. Added a restated honesty note (near the file's own existing top-of-file disclaimer) that this simulation proves logical intent only, never compile validity.
- **`docs/architecture/SecurityArchitecture.md`**: new "Compile-safe Rules syntax" section documenting the finding, the fix, and the restated pending-Emulator-verification limitation. Updated the Sprint 4.2.2 "Card write authority" bullet to stop describing `.keys().exists()` as the current mechanism.
- **`docs/reviews/CardTurnProgressionHotfix_4.2.2.md`**: superseding-update banner added, pointing to the new report below. Body text otherwise unedited (historical record).
- **New `docs/reviews/CardCompileSafeTurnHotfix_4.2.3.md`**: the full implementation report for this sprint.

## Not changed

- `MatchService`, `MatchAdapter`, `TableEngine` — byte-for-byte unchanged.
- No schema change — no new/removed/renamed field anywhere.
- No gameplay behavior change — every rewrite is a syntax-level, semantics-preserving change to already-existing, already-correct rules.
- `bidding-engine.js`, `scoring-engine.js`, `Dealer`, `Deck`, `Cards`, `RoomService`, `PlayerService`, `SessionService` — byte-for-byte unchanged.
- No trick resolution, trick winner persistence, scoring, next round, match end, replay, voice chat, AI, or matchmaking work was started.

## Testing (labeled MOCKED, SIMULATED, EMULATOR, or REAL — never mixed)

- `tests/rules-simulation.test.js`: 158/158 passing (up from 149; +9 new checks).
- Full regression suite re-run, zero regression: `deck` (39), `bid-sync` (39), `turn-sync` (26), `match-service` (67), `match-sync` (58), `submit-bid` (66), `room-service` (31), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31), `match-adapter` (119), `card-sync` (41), `submit-card` (34).
- **898 automated tests total, all passing** (up from 889).
- No test in this project, this sprint or any prior one, has run against the Firebase Emulator or a real Rules compiler — that verification remains explicitly PENDING, stated directly, not claimed.
