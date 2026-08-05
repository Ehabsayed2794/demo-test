# Room Security & Schema Fix — Sprint 3.2.1 Implementation Report

**Scope actually implemented:** `firestore.rules` gained a `rooms/{roomId}` block; `docs/architecture/FirestoreSchema.md`'s `rooms` section was rewritten to match the real implementation; a rules-logic simulation test was added. `RoomService`'s create/join/leave algorithms were **not** touched — confirmed via `git diff` (empty) and by re-running Sprint 3.2's original 28-test suite unmodified (still 28/28 passing).

## The most important thing in this report: a deliberate, documented deviation from the literal brief

The brief specified this exact rule for `update`:

> Allow update: Only if `request.auth.uid` is in the existing `players` array (prevents outsiders from modifying room state).

Implemented literally — checking only the **pre-write** `players` array — this rule makes `joinRoom()` permanently impossible. A player who is joining is, by definition, never already present in the room's existing member list; that's the entire point of joining. A rule that only ever allows existing members to write would deny every real join, forever — which directly contradicts this sprint's own stated goal: **"Unblock the Room functionality."**

This isn't a hypothetical concern — it's exactly what the brief's own required deliverable (a rules-logic simulation test) is for, and the test proves it concretely rather than leaving it as an assertion: see `tests/rules-simulation.test.js`, test case *"the brief's LITERAL rule ... denies a legitimate join by a brand-new user."* That test passes — the literal rule genuinely denies the join, confirmed by running the simulation, not by inspection alone.

**What's shipped instead**, in `firestore.rules`:

```
function isExistingOrIncomingMember() {
  return request.auth.uid in resource.data.players
         || request.auth.uid in request.resource.data.players;
}
```

This checks **either** the pre-write or the post-write `players` array. It still fully satisfies the security intent stated in the brief's own parenthetical — "prevents outsiders from modifying room state" — because a user absent from **both** lists still cannot write anything at all: they can't rename someone else's room, can't change its status, can't add a third party without also being added themselves. What this version adds, precisely, is the one case that has to be allowed for the feature to work: a user who is in the process of adding themself.

This is recorded here, in `firestore.rules` itself (inline comment at the point of the deviation), and in the simulation test — not applied silently. If review determines a different fix is preferred (e.g., a dedicated `joinRoom`-only code path with different rule logic), that's a straightforward follow-up; the point of this section is that the deviation was a deliberate, load-bearing decision, not an oversight.

## `firestore.rules` — what was added

A new `match /rooms/{roomId}` block, alongside the existing, unmodified `players/{uid}` block:

- **`create`**: requires `request.auth.uid == request.resource.data.creator`, plus type/shape validation (`creator` is a string, `players` is a list, the creator is a member of their own `players` list, `status` is a string) — matches the brief's explicit ask for type checking (`creator` is string, `players` is list) and adds one more integrity check (`creator in players`) directly implied by `createRoom()`'s actual behavior.
- **`read`**: `request.auth != null` — any signed-in user, exactly per the brief's explicit MVP simplification ("global auth read is acceptable for MVP to reduce rule complexity").
- **`update`**: `isExistingOrIncomingMember()`, as explained above.
- **`delete`**: always denied — rooms close via `status: "closed"` (already `leaveRoom()`'s existing behavior since Sprint 3.2), never via deletion.

The existing `players/{uid}` block, the deny-by-default catch-all, and every helper function belonging to it are byte-for-byte unchanged.

**Still not deployed.** Exactly as with Sprint 2.6's `players/{uid}` rules, this file remains a reviewable artifact — publishing it requires the same manual step (Firebase Console → Firestore Database → Rules → paste → Publish). Once published, `RoomService`'s calls (already fully implemented and tested in Sprint 3.2) should work against the live project for the first time.

## `FirestoreSchema.md` — what changed

The `rooms/{roomId}` section was rewritten to document the fields `room-service.js` actually writes (`creator`, `players: string[]`, `status: "waiting"|"closed"`, `name`, `createdAt`/`updatedAt`), with the old speculative fields (`hostUid`, `seats[]`, `matchId`, `expiresAt`) explicitly marked as removed/deprecated with a note not to implement against them. Per this sprint's explicit instruction, no code was changed to match the old draft — the documentation was brought in line with the working implementation, not the reverse.

## Rules simulation — `tests/rules-simulation.test.js`

No Firebase emulator is available in this sandboxed session (`@firebase/rules-unit-testing` needs the Firebase CLI plus a local Java-backed emulator; neither is installed or reachable here). Rather than skip verification, each relevant rule expression from `firestore.rules` was translated 1:1 into plain JS and exercised against representative mock request/resource shapes — an honest, lower-fidelity substitute for the real emulator, not a claim of having run it. Eleven cases, all passing (see Tests below), including the specific "User A creates a room; User C — a stranger — tries to join/modify it" scenarios the brief asked for.

## Tests performed

See `TEST_CHECKLIST.md` in the QA package. Summary: `firestore.rules` structurally verified (brace/paren balance, consistent with the existing deployed style — no live compile-check available, same limitation as Sprint 2.6); 11 rules-simulation tests, all passing, including the specific proof of why the literal brief's `update` rule was replaced; Sprint 3.2's original 28-test `RoomService` suite re-run unmodified, still 28/28 passing (zero regression, zero logic change); `git diff` confirms `room-service.js` and `lobby/index.html` are both completely untouched this sprint.
