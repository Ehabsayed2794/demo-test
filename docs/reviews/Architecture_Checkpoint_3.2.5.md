# Architecture Checkpoint — Sprint 3.2.5

**Type:** Audit only. No code, UI, gameplay engine, `GameState`/`GameSession`, or documentation was modified as part of producing this report. No commits, no push, no QA package, no test suite execution (no Critical issue was found that would justify one — see §12).

**Method:** Direct re-reading of the actual current files — `player-service.js`, `session-service.js`, `room-service.js`, `firestore.rules`, all three `game-state.js` copies, `login/index.html`, `lobby/index.html`, `profile/index.html`, `firebase-init.js`, and every relevant file under `docs/architecture/` — plus targeted static greps (Firestore/Auth access patterns, collection-query usage, Cloud Functions references). Nothing below is asserted from memory of prior sprints without having re-verified it against the current file contents today.

---

## 1. Executive Summary

The service-layer foundation (`PlayerService` → `SessionService` → `RoomService`) is architecturally sound: clean one-directional dependencies, no circular calls, no UI file touches Firestore or Auth outside one correct, deliberate exception (Login, which performs actual sign-in actions). The code itself — `room-service.js`, `player-service.js`, `session-service.js`, and `firestore.rules` — is internally consistent with itself and was already re-verified in Sprint 3.2.1.

The real findings are **documentation drift and rules permissiveness**, not code defects: three architecture documents (`SecurityArchitecture.md`, `ServiceArchitecture.md`, `RoomLifecycle.md`) still describe the pre-3.2 speculative room model (`hostUid`, seat arrays, join-code document IDs) and were never updated when `FirestoreSchema.md` was corrected in Sprint 3.2.1 — a future contributor reading the wrong document would design against a shape that doesn't exist. Separately, `firestore.rules`' `rooms/{roomId}` rules are looser than they need to be: there's no field whitelist on `create` (unlike `players/{uid}`'s equivalent rule) and no field-level restriction on `update` at all, meaning any current-or-incoming member can currently rewrite *any* field on a room document, including silently reassigning `creator` to themselves without going through `leaveRoom()`'s ownership-transfer logic.

None of this rises to Critical. Nothing here makes continuing development unsafe in the sense of risking data corruption, breaking the build, or violating the Spark/no-Cloud-Functions constraint. It's the same category of "soft enforcement, tighten later" limitation this project has knowingly accepted at every stage since `SecurityArchitecture.md`'s own "strong vs. soft" section was written — these are new *instances* of that already-accepted category, not a new, more severe category of risk.

## 2. Architecture Health Score

**7.5 / 10**

Deductions: documentation drift across three files (−1.0), rules permissiveness gaps on `rooms/{roomId}` create/update (−1.0), no room-expiration/cleanup mechanism despite one being planned in `ArchitectureDecisionLog.md` (−0.5). Everything else — service boundaries, UI isolation, data-flow consistency, Spark compatibility, migration readiness — is clean.

## 3. PASS / PASS WITH WARNINGS / FAIL

**PASS WITH WARNINGS.**

## 4. Findings

| # | Finding | Severity | Can this wait? |
|---|---|---|---|
| F1 | `firestore.rules` for `rooms`/`players` not yet deployed to the live project | High | Yes — already known, already tracked since Sprint 2.6; a manual publish step, not new |
| F2 | `SecurityArchitecture.md`'s `rooms/{roomId}` row describes the deprecated `hostUid`/seats model | High | No — recommend fixing before/alongside Sprint 3.3 |
| F3 | `rooms/{roomId}` `update` rule has no field-level restriction — any member can rewrite any field, including `creator` | High | No — recommend fixing before/alongside Sprint 3.3 |
| F4 | `ServiceArchitecture.md`'s `RoomService` section documents the wrong method signatures (`createRoom(hostUid)`, `joinRoom` returning a seat index) | Medium | Yes, but should accompany F2's fix |
| F5 | `RoomLifecycle.md`'s entire narrative (join-code document IDs, seats, ready flags, seat-based host transfer) no longer matches the shipped model | Medium | Yes, but should accompany F2's fix |
| F6 | `rooms/{roomId}` `create` rule has no field whitelist (`keys().hasOnly([...])`), unlike `players/{uid}`'s equivalent rule — a client can write arbitrary extra fields at creation | Medium | Yes — low real-world impact today, worth bundling with F3 |
| F7 | `rooms/{roomId}` `create` rule allows `players.size() >= 1` rather than exactly `1` — a client could fabricate extra "members" at creation who never actually joined | Medium | Yes — bundle with F3/F6 |
| F8 | No room-expiration/cleanup mechanism exists — `ArchitectureDecisionLog.md`'s ADR-005 planned a Firestore TTL field, but the shipped `rooms/{roomId}` schema has no `expiresAt` and no TTL policy is configured | Medium | Yes — matters more once Room Listing exists; not urgent on Spark's generous storage quota today |
| F9 | `currentRoomId` on a player's profile can go stale if a room changes/closes due to *another* member's action — it's only corrected when the affected player personally calls `leaveRoom()` | Medium | Yes — not yet user-visible (no screen reads `currentRoomId` for anything yet); revisit when a Room screen or reconnect flow is built |
| F10 | Stale comment in `firebase-init.js` claims Lobby loads "Firestore only, never Auth" — no longer true since Sprint 2.9 added `SessionService`'s Auth dependency there | Low | Yes |
| F11 | `PlayerService.subscribeToPlayerProfile` and `RoomService.subscribeToRoom` are both fully implemented/stubbed but never called by any UI — no live listeners currently run anywhere | Observation | N/A — noted for awareness, not a defect |

Full detail for each finding, with description/why-it-matters/risk/recommended-solution/effort, is in the relevant section below (§5–§9).

## 5. Service Boundary Review

**Verified clean — no violations found.**

- **`PlayerService`**: player CRUD only (`ensurePlayerProfile`, `getPlayerProfile`, `updatePlayerProfile`, `subscribeToPlayerProfile`). Zero references to `SessionService` or `RoomService` anywhere in the file — confirmed by reading it in full. It is the base layer; nothing depends on it depending on anything else.
- **`SessionService`**: session lifecycle, Auth-state tracking, and profile *caching* only. Calls `PlayerService.getPlayerProfile()` (read-only) — never `ensurePlayerProfile()` (profile creation stays exclusively Login's job, by design, per its own header comment, re-confirmed unchanged this audit). No Firestore or Auth call bypasses `PlayerService`/the Auth SDK it's explicitly responsible for watching.
- **`RoomService`**: room CRUD only. Its one cross-service interaction (`syncCurrentRoomOnProfile`) calls `PlayerService.updatePlayerProfile()` and `SessionService.refresh()` — both pre-existing **public** methods, called exactly as any other caller would, with no reach into either module's internal closures. This is calling a service through its API, not one service absorbing another's responsibility.
- **No circular dependencies:** the dependency graph is strictly one-directional — `RoomService → {PlayerService, SessionService}`, `SessionService → PlayerService`, `PlayerService →` nothing. No file imports/references anything "above" it in this chain.
- **No duplicated business logic:** profile-default generation exists in exactly one place (`PlayerService.mapAuthUserToProfileDefaults`); room-membership mutation exists in exactly one place (`RoomService`'s transactions). Nothing re-implements either.

## 6. UI Isolation

**Verified — one correct, deliberate exception; zero unexplained violations.**

- `design-ui/lobby/index.html` and `design-ui/profile/index.html`: zero matches for `firebase.firestore(`, `window.Db`, `.collection(`, `window.Auth`, or `firebase.auth(` in either file's inline scripts (confirmed via direct search of the current files, not inferred). Both talk exclusively to `SessionService`/`RoomService`.
- `design-ui/login/index.html`: **does** reference `window.Auth` directly (2 occurrences). This is correct, not a violation — Login is the one screen that performs actual authentication *actions* (create account, sign in, Google popup, password reset), none of which are in `SessionService`'s scope (session/cache only, by its own explicit design). This boundary was drawn deliberately in Sprint 2.9 and remains correctly honored.

## 7. Firestore Review

Compared `firestore.rules`, `FirestoreSchema.md`, `room-service.js`, and `player-service.js` against each other field-by-field.

- **`players/{uid}`**: all four sources agree exactly — field list, defaults, protected-field whitelist. No mismatch found.
- **`rooms/{roomId}`**: `firestore.rules` and `room-service.js` agree with each other (already cross-checked and fixed in Sprint 3.2.1) and with the *updated* section of `FirestoreSchema.md`. However, `FirestoreSchema.md` is the **only** architecture document that was updated — `SecurityArchitecture.md` (F2) and `ServiceArchitecture.md` (F4) still describe the old shape. **Recommendation: update the documentation, not the code** — the code and rules are correct and already tested; the two stale documents should be brought in line with them, the same way `FirestoreSchema.md` already was.
- **Collection names**: only `players` and `rooms` are written to anywhere in the codebase — confirmed via direct search. No stray/typo'd collection name exists.
- **Expected data flow**: `RoomService` writes `rooms/{roomId}` and mirrors `currentRoomId` onto `players/{uid}` via `PlayerService`'s existing API — traced and confirmed consistent (see §8 below).

## 8. Data Consistency

**`playerId` lifecycle — traced and confirmed consistent, single source of truth (the Firebase Auth `uid`):**

```
Firebase Auth uid
  → SessionService.getCurrentUser().uid            (same uid, cached)
  → PlayerService players/{uid} document ID         (same uid, as the doc ID itself)
  → RoomService.createRoom/joinRoom/leaveRoom(playerId, ...)  (same uid, passed through verbatim)
  → written into rooms/{roomId}.creator / .players[]           (same uid, no translation)
  → mirrored back onto players/{uid}.currentRoomId via PlayerService.updatePlayerProfile (same uid)
  → SessionService.refresh() re-reads the same players/{uid} doc
  → Lobby reads session.profile.* (same cached document)
```

No intermediate ID mapping, no second identifier scheme, no place where a different value could silently diverge from the Auth `uid`. This is clean.

**`roomId` lifecycle:**

```
RoomService.createRoom() → auto-generated Firestore document ID (rooms/{roomId})
  → returned to the caller (Lobby, via alert())
  → mirrored onto the creator's players/{uid}.currentRoomId
  → SessionService.refresh() picks it up into its cache
  → (Lobby does not currently read or display currentRoomId anywhere — see F9)
```

**Single source of truth: yes, for the data that's actually read today.** `rooms/{roomId}` itself is authoritative for room membership; `players/{uid}.currentRoomId` is a *mirror*, not a second authority — nothing ever reads `currentRoomId` to determine actual room membership; `RoomService`'s own transactions always re-read `rooms/{roomId}` directly. The mirror exists only so a future screen could show "you're currently in room X" without a second query. F9 (above) is the one caveat: the mirror can go stale relative to the authoritative document if changed by someone else, but since nothing currently *reads* the mirror for any decision, this is a latent gap, not an active inconsistency bug today.

## 9. Rules Review

- **`players/{uid}`**: tight. `create` has a full field whitelist and exact-value checks on every default; `update` has a field-level whitelist; `list` explicitly denied; `delete` denied. No changes recommended.
- **`rooms/{roomId}`**: functionally correct (proven via Sprint 3.2.1's simulation tests — a legitimate join succeeds, a true outsider still can't write anything), but looser than it needs to be:
  - **F6/F7 (create):** no `keys().hasOnly([...])` whitelist, and `players.size() >= 1` rather than `== 1`. A client could currently attach arbitrary extra fields to a new room, or list fictitious extra members at creation time who never joined through `joinRoom()`.
  - **F3 (update) — the most significant finding in this review:** `isExistingOrIncomingMember()` correctly gates *who* may write, but nothing gates *what* they write. Any current or incoming member can currently modify `creator` directly (self-promoting to owner without going through `leaveRoom()`'s transfer logic), rewrite `name`/`status` arbitrarily, or add fields with no shape validation at all.
  - **Missing validation, not overly permissive by omission:** there's no check that `status` is one of the two real values (`"waiting"`/`"closed"`) rather than an arbitrary string.
  - **Future scalability concern:** the blanket `allow read: if request.auth != null` (correctly chosen for MVP simplicity per the brief that introduced it) will need revisiting once Room Listing/browsing exists and a `list` query becomes common — not a problem today since nothing queries the collection yet, but worth remembering it's a deliberate, temporary simplification, not a permanent design decision.

**Recommendation:** tighten `create`'s field whitelist and exact-count check, and add a minimal field-level restriction to `update` (e.g., restrict a non-creator's writes to just `players`, and restrict `creator` reassignment to the ownership-transfer case specifically) — bundle this with F2/F4/F5's documentation sync, and ideally with whatever rules change Sprint 3.3 (Ready state) will need anyway, since that sprint will be touching this same `update` rule regardless.

## 10. Spark Compatibility

**Clean — no concerns found.**

- Every Firestore operation across `player-service.js` and `room-service.js` is a single-document `get`/`set`/`update`/`onSnapshot`, or a `runTransaction` — confirmed via direct search; **zero** `.where()`, `.orderBy()`, or `.limit()` calls exist anywhere in the codebase. There is no unbounded-read risk today because there is no query capability in use at all yet.
- Transaction usage (`PlayerService.ensurePlayerProfile`, `RoomService.joinRoom`/`leaveRoom`) is appropriate and minimal — used exactly where a real race exists (concurrent profile creation, concurrent joins for a room's last slot), not reflexively.
- **No Blaze-only service is required anywhere in the current codebase** — confirmed: no `firebase.functions`, no `httpsCallable`, no Cloud Functions/Cloud Run/paid Extension reference exists in any shipped file.
- F8 (no room cleanup) is a quota-*hygiene* observation, not an active Spark-limit risk today — Firestore's free-tier storage (1 GiB) would take a very long time to fill with small, abandoned room documents at this project's current stage.

## 11. Blaze Migration Readiness

**Good — the abstraction holds up.**

- **UI call sites remain unchanged if migrated:** Lobby calls `RoomService.createRoom(uid, roomName)`; nothing about that call site would need to change if `createRoom`'s *internals* moved to a Cloud Function tomorrow — this was the explicit design goal since `MigrationPlan.md` and `ServiceArchitecture.md` were written, and it holds up under this audit's direct re-reading of the actual code.
- **Services abstract Firebase correctly:** no UI file constructs a Firestore reference or an Auth call itself (per §6); every mutation goes through a named service method with a stable signature.
- **Business logic can move server-side later with minimal change:** `RoomService`'s transaction bodies (existence/closed/full checks, ownership-transfer-on-leave logic) are plain, portable JS with no browser-specific API dependency — they could be lifted into a Cloud Function's body largely as-is.
- **Nothing found that would require a major rewrite.** The one thing worth flagging for that future migration specifically: because the current `update` rule (F3) is permissive, a client today *could* write directly to fields a future Cloud Function would want to own exclusively (e.g., `creator`). Tightening F3 now is also, incidentally, good preparation for that later migration — the narrower the client's current write surface, the smaller the future "only the Cloud Function may write this" rule change will be.

## 12. Roadmap Validation

**Is Sprint 3.3 the correct next step? Conditionally yes — recommend a small, bundled hardening pass first, not a separate blocking sprint.**

The findings in this report (F2–F7) are real, but they share a common, efficient fix: a documentation-sync-plus-rules-tightening pass that touches exactly the files Sprint 3.3 (Ready state) is already going to need to open anyway (`SecurityArchitecture.md`'s rooms row, `firestore.rules`' rooms `update` rule — since adding a `ready` field means revisiting that same rule regardless). Doing this work as a standalone, separate checkpoint sprint first would mean touching `firestore.rules`' rooms block twice in quick succession instead of once; doing it *as part of* Sprint 3.3's own rules work is more efficient and no less safe, since none of these findings are exploitable in a way that matters before Ready state adds real gameplay-adjacent state to the same document.

**Recommended sequence:**
1. **Sprint 3.3 (Ready state)** — proceed as planned, but its own rules-update work should also fold in F3/F6/F7 (tighten `create`'s whitelist/count, add field-level restriction to `update`) rather than only adding `ready`-specific logic on top of today's permissive baseline.
2. **Alongside or immediately after 3.3** — a small documentation pass closing F2/F4/F5 (bring `SecurityArchitecture.md`, `ServiceArchitecture.md`, `RoomLifecycle.md` in line with reality, the same way `FirestoreSchema.md` already was in 3.2.1).
3. **Whenever Room Listing/browsing is scheduled** — revisit F8 (cleanup/TTL) and the blanket `read` rule's scalability note before that feature ships, not before Ready state.
4. **Whenever a Room screen or reconnect flow is built** — revisit F9 (`currentRoomId` staleness) at that point, since that's the first time anything would actually read the mirrored value for a real decision.

No other checkpoint or refactor sprint needs to happen before Sprint 3.3 for safety reasons — the recommendation above is about efficiency (bundling related rules work), not about blocking progress.

## 13. Recommended Next Sprint

**Sprint 3.3 (Ready state), with its rules-update task explicitly scoped to include the F3/F6/F7 tightening described above** — not a separate hotfix sprint, since none of these are Critical and bundling is more efficient than a third pass over the same rules block.

## 14. Go / No-Go Recommendation

No Critical blocker was found. Service boundaries are clean, UI isolation is intact (with one correct, deliberate exception), data flow has a single source of truth for everything actually in use today, and the codebase remains fully Spark-compatible with no Blaze-only dependency anywhere. The findings are real but are documentation drift and rules-tightening opportunities — exactly the class of "soft enforcement, revisit later" limitation this project has knowingly accepted at every prior stage, not a new or more severe category of risk.

**READY FOR SPRINT 3.3**

(with the recommendation, not a condition, that Sprint 3.3's rules work be scoped to also close F3/F6/F7, and that F2/F4/F5's documentation sync happen alongside or shortly after)
