# Test Checklist — Sprint 3.2.1: Critical Security & Schema Fix

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | `firestore.rules` syntax is structurally valid | **PASS** (structural check only) | Brace count (16/16) and paren count (36/36) balanced; structure follows the same, already-deployed style as the existing `players/{uid}` block. No live Firestore compiler is available in this sandboxed session to fully compile-check it — same limitation already noted for Sprint 2.6's rules file; final confirmation happens at publish time in the Firebase Console, which does live syntax-check on paste. |
| 2 | `FirestoreSchema.md` matches `room-service.js`'s actual implementation exactly | **PASS** | Compared field-by-field: `creator` (string), `players` (string array), `status` (`"waiting"`/`"closed"`), `name`, `createdAt`/`updatedAt` — all match the real code in `room-service.js`. Old speculative fields (`hostUid`, `seats[]`, `matchId`, `expiresAt`) explicitly marked deprecated, not silently deleted from the doc. |
| 3 | Sprint 3.2's automated `RoomService` tests still pass — no regression | **PASS** | Re-ran the original, unmodified 28-test suite from Sprint 3.2 against the (unchanged) `room-service.js` — 28/28 still passing. |
| 4 | `create` rule: valid new room is allowed | **PASS** | Simulation test: `creator == request.auth.uid`, `players` contains creator, `status` set → allowed. |
| 5 | `create` rule: spoofing another user's uid as creator is denied | **PASS** | Simulation test: `creator: "userB"` submitted by `userA` → denied. |
| 6 | `create` rule: type/shape validation (creator is string, players is list) | **PASS** | Simulation tests: non-string `creator`, non-list `players`, missing `status`, creator absent from its own `players[]` — all denied. |
| 7 | **The specific case the brief asked to verify: "User A tries to join Room owned by User B"** | **PASS**, with an important finding | Simulated as "User C, a total stranger, adds themself to User B's room's `players[]`." Under the brief's literal rule wording (checking only the pre-write array), this is **denied** — proving the literal spec would block real joins. Under the shipped, fixed rule, this is **allowed** — because User C is present in the post-write array. Both outcomes were verified via the simulation, not assumed. |
| 8 | A non-player (absent from both old and new `players[]`) cannot update someone else's room | **PASS** | Simulation test: `userX`, absent from both lists, attempting to change `status` or add a third party — denied in both cases. This is the concrete verification of "prevents outsiders from modifying room state." |
| 9 | An existing member leaving (removing themselves) is still allowed | **PASS** | Simulation test: `userB`, present in the pre-write `players[]` but not the post-write one (they're leaving) — allowed. |
| 10 | `delete` is always denied | **PASS** | Confirmed by direct reading of the shipped rule (`allow delete: if false;`) — matches `leaveRoom()`'s actual behavior (close via `status`, never delete). |
| 11 | `read` is allowed for any authenticated user | **PASS** | Confirmed by direct reading of the shipped rule (`allow read: if request.auth != null;`) — matches the brief's explicit MVP simplification. |
| 12 | `room-service.js` create/join/leave algorithms are byte-for-byte unchanged | **PASS** | `git diff -- design-ui/room-service.js` — empty. |
| 13 | `lobby/index.html` is byte-for-byte unchanged | **PASS** | `git diff -- design-ui/lobby/index.html` — empty. |
| 14 | No Game Engine file touched | **PASS** | `git diff` — no file under `design-ui/engine/` in this sprint's change set. |
| 15 | No new features added (Ready, Chat, Room Listing, Matchmaking) | **PASS** | Confirmed by reading the full diff — limited to `firestore.rules`, `FirestoreSchema.md`, one new test file, and one new doc. |
| 16 | `PlayerService`/`SessionService` core logic untouched | **PASS** | `git diff` — neither file in the change set. |

## Not performed

Live deployment/testing against the actual Firebase project's `rooms` collection — `firestore.rules` remains an undeployed, reviewable artifact (same pending-publish state already established for `players/{uid}` since Sprint 2.6). A full run against the real Firebase Rules Unit Testing emulator was also not performed — no Firebase CLI or local Java-backed emulator is available in this sandboxed session; the JS-based simulation in `tests/rules-simulation.test.js` is an honest, lower-fidelity substitute, documented as such rather than presented as equivalent.
