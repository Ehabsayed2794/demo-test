# Changelog — Sprint 3.2.1: Critical Security & Schema Fix (Hotfix)

## Fixed (Hotfix)
- **`firestore.rules`** — added a `rooms/{roomId}` block (was previously absent entirely, meaning every `RoomService` call would `permission-denied` against the live project — the Blocker identified by the Architecture Review):
  - `create`: requires `request.resource.data.creator == request.auth.uid`, plus type/shape validation (`creator` is a string, `players` is a list containing the creator, `status` is a string).
  - `read`: any authenticated user (per the brief's explicit MVP simplification).
  - `update`: **deliberately deviates from the brief's literal wording** — allows the acting user if they're present in *either* the pre-write or the post-write `players` array, instead of only the pre-write array. The literal wording would make `joinRoom()` permanently impossible (a joining user is by definition not yet an existing member) — proven concretely by `tests/rules-simulation.test.js`, not just asserted. Still fully prevents outsiders (absent from both lists) from writing anything. See `docs/implementation/RoomSecurityFix.md` for the full reasoning.
  - `delete`: always denied — unchanged from how `leaveRoom()` already worked (closes via `status`, never deletes).
  - The existing `players/{uid}` block and the deny-by-default catch-all are byte-for-byte unchanged.
  - **Still not deployed** — same manual publish step as Sprint 2.6's rules, pending review.
- **`docs/architecture/FirestoreSchema.md`** — `rooms/{roomId}` section rewritten to document the actual shipped fields (`creator`, `players: string[]`, `status: "waiting"|"closed"`, `name`, `createdAt`/`updatedAt`). The earlier speculative fields (`hostUid`, `seats[]`, `matchId`, `expiresAt`) are explicitly marked removed/deprecated with a "do not implement against these" note. Docs were updated to match the working code, not the reverse, per this sprint's explicit instruction.

## Added
- `tests/rules-simulation.test.js` — 11 tests translating `firestore.rules`' `rooms` logic 1:1 into JS and exercising it against mock request/resource data (no Firebase emulator available in this sandboxed session — see the file's own header comment for why this is an honest substitute, not a claim of running the real emulator).
- `docs/implementation/RoomSecurityFix.md` — full implementation report.
- This QA package.

## Not changed
- `design-ui/room-service.js` — **zero diff**. Create/join/leave algorithms are byte-for-byte identical to Sprint 3.2.
- `design-ui/lobby/index.html` — **zero diff**. No UI logic changed.
- No Game Engine file, `GameState`/`GameSession`, `PlayerService`/`SessionService` core logic, or any other service stub. Verified via `git diff`.
- No new features (Ready state, Chat, Room Listing, Matchmaking).

## Regression check
Sprint 3.2's original 28-test `RoomService` suite was re-run unmodified against the untouched `room-service.js` — still 28/28 passing.
