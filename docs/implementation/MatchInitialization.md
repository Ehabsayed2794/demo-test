# Match Initialization & Game Start — Sprint 3.4 Implementation Report

**Updated in Sprint 3.4.1 (Match Start Consistency & Security Hotfix)** — see the dedicated section near the top of this document for what changed and why. Everything below that section is the original Sprint 3.4 report, left in place except where a specific paragraph is explicitly marked superseded, matching this project's established "document deviations, don't hide them" culture.

**Scope actually implemented (Sprint 3.4):** `MatchService.createMatch()`/`startMatch()`/`loadMatch()`/`subscribeToMatch()`; `RoomService.setReady()` extended to detect "everyone is ready" and trigger `MatchService.startMatch()`; a `matches/{matchId}` block added to `firestore.rules` alongside a `matchId` field on `rooms/{roomId}`'s update rule; a placeholder Match screen and Lobby-to-Match navigation. No gameplay engine (`bidding-engine.js`/`scoring-engine.js`/`dealer.js`/`cards.js`/`table-engine.js`) was modified — only integrated (partially, per the `Deck` finding below). No bidding, estimation, or card-play was implemented.

---

## Sprint 3.4.1 — Match Start Consistency & Security Hotfix

A production review of the actual Sprint 3.4 files found two real issues, both closed this sprint. **Do NOT add gameplay, redesign UI, touch gameplay engines, or implement reconnect/matchmaking/chat/spectators/bidding/dealing/card-play/scoring** — none of that changed here; Spark compatibility (no Cloud Functions, no Blaze) is likewise unchanged.

### Issue 1 — `currentMatchId` propagation was silently broken for every player except the initiator

**The bug:** Sprint 3.4's `MatchService.startMatch()` called `syncCurrentMatchOnProfiles(players, matchId)`, which looped over **every** room player and called `PlayerService.updatePlayerProfile(uid, { currentMatchId: matchId })` for each one. But `players/{uid}`'s Firestore rules are (and have always been) **owner-only** — `allow update: if isOwner(uid) && onlyAllowedFieldsChanged();`. A write to `players/{uid}` only succeeds when `request.auth.uid == uid`. Since a single client is only ever authenticated as ONE uid, this loop's write could only ever succeed for whichever player's own browser happened to call `startMatch()` — every other room player's write was a guaranteed `permission-denied`, caught by the existing `.catch()` and logged as "non-fatal." In practice: only the player who triggered the match start ever got `currentMatchId` on their own profile; everyone else's profile never updated at all.

**The fix — smallest Spark-compatible design, chosen over alternatives considered below:**

- **Authoritative source, now documented explicitly:** `rooms/{roomId}.matchId` and `matches/{matchId}.players` are the authoritative multiplayer state for "who is in which match." `players/{uid}.currentMatchId` is now explicitly a **same-user convenience mirror only** — no service in this codebase reads it as ground truth for anything.
- **`MatchService` never attempts a cross-user profile write again.** `syncCurrentMatchOnProfiles()` (the all-players loop) is deleted entirely. In its place, `syncOwnCurrentMatchId(matchId)` calls `SessionService.setCurrentMatchId(matchId)` — a brand-new method with **no `uid` parameter at all**. It always targets `currentUser.uid`, the signed-in user `SessionService` already tracks internally. This makes cross-user writes not just disciplined-by-convention but **structurally impossible** — there is no argument anywhere in this call chain a bug could misuse to name another player.
- **Each OTHER seated client discovers the match by asking about the room, not by receiving a push from someone else's write.** `RoomService.loadRoom(roomId)` (new — a plain read-only fetch, mirroring `MatchService.loadMatch()`'s established null-if-missing pattern exactly) is what Lobby now polls. Once a client observes `room.matchId`, it calls the same self-only `SessionService.setCurrentMatchId()` for **itself**.
- **`PlayerService`'s owner-only rule was not touched** — the fix moved responsibility to wherever the write could actually succeed, rather than trying to loosen the security boundary that correctly caught this bug in the first place.

**Alternatives considered and rejected:**
1. *Loosen `players/{uid}`'s rules to let any room member write any other room member's `currentMatchId`.* Rejected outright — this would reopen exactly the "any guest could write any match" class of hole this project's very first Firestore security audit (Sprint 2.6) already closed once. The brief also explicitly requires preserving owner-only security unchanged.
2. *Move the multi-profile sync into a Cloud Function.* Rejected — Blaze/Cloud Functions are explicitly forbidden this sprint (and the whole project's current phase).
3. *Have the initiating client's browser somehow write on every other player's behalf via some elevated credential.* Not meaningfully different from option 1 from a security standpoint, and there is no such credential available under Spark without Cloud Functions.

The chosen design needs no rules change for `players/{uid}` at all, needs no elevated credential, and needs no Cloud Function — every write it performs was already legal under the existing, unmodified owner-only rule. This is proven directly in `tests/rules-simulation.test.js`'s new `isValidPlayerUpdate` tests (Requirement #1) and in `tests/match-service.test.cjs`'s regression guard (Requirement #2 — see "Tests performed" below).

### Issue 2 — a match could be fabricated independently of a legitimate room start

**The bug:** Sprint 3.4's `isValidNewMatch()` validated only the new match document's own internal shape (field whitelist, `status`, `dealer`/`turn` membership) — it never checked that a real room existed, that the caller belonged to it, that everyone was actually ready, or that the room was genuinely being transitioned to `"in_game"` in the same write. Symmetrically, `isValidMatchIdChange()` validated only that `matchId` went from `null` to a string alongside the status flip — it never checked readiness, membership, or that a real, correctly-bound match document actually existed. A sufficiently motivated client could therefore write `matches/{matchId}` directly with any `roomId` it wanted, or flip `rooms/{roomId}.status` to `"in_game"` with a fabricated `matchId` that points at nothing real.

**The fix:** both rule functions were extended with `get()`/`exists()`/`getAfter()` cross-checks — see `firestore.rules`' own inline comments on `isValidNewMatch()` and `isValidMatchIdChange()` for the exact CEL. In summary, for the one write that starts a match:
- `rooms/{roomId}` must exist (`exists()`), and the acting user must actually be listed in its `players[]` — checked from **both** sides (the match's create rule reads the room via `get()`; the room's own update rule re-checks membership independently).
- The room's status, as it existed **before** this write (`get()`, which reflects pre-transaction state), must have been `"waiting"`.
- Every one of the room's `players[]` must have been in `readyPlayers[]` **before** this write (`readyPlayers.hasAll(players)`) — re-checked from both sides.
- The new match's `roomId` must point back at this room, and the new match's `players[]` must exactly equal the room's `players[]` — re-checked from both sides.
- `rooms/{roomId}.matchId`, as it will exist **once this same transaction/batch commits** (`getAfter()`, which reflects post-commit state), must equal this match document's own id — and symmetrically, the match document that `getAfter()` finds at the room's new `matchId` must actually exist and actually point back at this room with this room's players.

Because Firestore evaluates each touched document's rules independently — even within one transaction — checking this from **both** documents' own rules (not just one) is what makes "the match and the room transition must be part of the same atomic write" an actual, rules-enforced requirement rather than a JS-layer convention a client could bypass by submitting the two writes separately. This is proven directly in `tests/rules-simulation.test.js`'s new `isValidNewMatchV2`/`isValidMatchIdChangeV2` tests (Requirements #4–#8).

**Honesty note, as the brief explicitly asks for:** these rules were authored carefully against Firestore's documented `get()`/`exists()`/`getAfter()` semantics and CEL's short-circuiting ternary/`let` evaluation order, but — same limitation as every prior sprint — there is **no Firebase Rules Unit Testing emulator available in this sandboxed session** (no Firebase CLI, no local Java-backed emulator). The JS-based simulation in `tests/rules-simulation.test.js` translates the rule logic by hand and models `get()`/`exists()`/`getAfter()` as explicit test parameters rather than by executing real Firestore read semantics (memoization behavior, the exact error-vs-deny handling of a missing document's field access, the ~20-call quota, etc.). This is a real, additional layer of approximation on top of the JS-simulation limitation already disclosed — **not** a claim that live Firestore Rules execution has been verified. `firestore.rules` remains an undeployed, reviewable artifact.

### Issue 3 (Task 3) — `createMatch()` was an unsafe public method

`createMatch()` bypassed every safety property `startMatch()` provides: no all-ready gate, no duplicate-start protection, no atomic room transition. A review confirmed nothing in this codebase ever legitimately called it — `RoomService` and the UI always used `startMatch()`. **Removed entirely from the public `MatchService` API** (not merely marked private — deleted, since it had no internal callers either; `buildInitialMatchDoc()` remains the one shared, pure doc-shape builder). The Sprint 3.4.1-tightened `firestore.rules` above also now structurally reject the write shape `createMatch()` used to produce (a match created without a same-transaction, correctly-bound room update) — so even a future accidental reintroduction of this pattern would be denied server-side, not merely discouraged by convention. Existing tests that exercised `createMatch()`'s document-shape assertions were **not preserved as-is** (per the brief's explicit instruction not to keep an unsafe method around just to keep old tests green) — those assertions now run against `startMatch()`'s created document instead, which shares the exact same shape (`buildInitialMatchDoc()`).

### Issue 4 (Task 4) — match-start failures were logged but unobservable, and could leave the flow silently stuck

**The gap:** `RoomService.setReady()`'s `maybeStartMatch()` call was fire-and-forget — if `MatchService.startMatch()` failed (a transient Firestore error, `MatchService` not loaded on the page, etc.), the only trace was a `console.error()` no caller could react to. Worse: nothing would ever retry, so a room that became fully ready during a transient failure could stay stuck in `"waiting"` forever.

**The fix — the smallest valid improvement, not a retry/reconnect system:**
- `maybeStartMatch()` now **returns a Promise** resolving a structured result — `{ allReady, started, matchId, error }` — instead of firing-and-forgetting. `setReady()` awaits it and attaches it to the resolved room as `room.matchStart`. `setReady()` itself still never rejects because of a match-start failure — the ready-toggle it was asked to perform already succeeded independently, and `setReady()`'s asynchronous shape (a Promise, not a synchronous return) means awaiting one more already-in-flight operation before resolving is not a behavior change worth flagging as a redesign.
- Lobby's existing `alert()`-based feedback (unchanged pattern, no new UI component) now reads `room.matchStart`: if this tab's own `setReady()` call is the one that started the match, it navigates immediately (no need to wait for the next poll tick); if a match-start was attempted and failed, the existing alert's text is extended to say so plainly.
- **Retry, the documented way:** `setReady(roomId, uid, sameValueAsBefore)` is already idempotent (Sprint 3.3) — calling it again performs **zero** additional write for the ready-toggle itself, but **does** re-run `maybeStartMatch()` as a side effect. Lobby's existing 4-second poll (see the Navigation section below) reuses exactly this: if it observes the room is still `"waiting"`, fully ready, and has no `matchId`, it retries by calling `setReady(lastRoomId, uid, true)` again with this client's own already-true ready value. This is "documented polling/retry behavior" from the brief's own suggested options — not a new retry/reconnect system, just an already-idempotent existing operation reused for retry.

This is proven directly in `tests/match-service.test.cjs`'s Task 4 section: a simulated transient `startMatch()` failure is observed via `room.matchStart.error`, the room is confirmed to remain in a genuinely retryable state, and a subsequent retry (once the simulated failure is removed) succeeds.

---

## Original Sprint 3.4 report (superseded paragraphs marked inline)

## `MatchService`

Previously (Sprint 2.7) an API-only skeleton — every method, including `createMatch`, threw `Not implemented`. This sprint activates four methods for real:

- ~~**`createMatch(roomId) → matchId`** — the lower-level primitive...~~ **SUPERSEDED in Sprint 3.4.1 — `createMatch()` was removed entirely from the public API.** See the Sprint 3.4.1 section above ("Issue 3"). This paragraph is kept, struck through, for historical context only — do not implement against it.
- **`startMatch(roomId) → matchId`** — the safe, orchestrated entry point `RoomService` calls. A **single Firestore transaction spans both `rooms/{roomId}` and `matches/{matchId}`** — see "The atomicity boundary" below for why this had to be one transaction, not two. Idempotent: if the room already has a `matchId` when the transaction reads it, returns that matchId instead of creating a second match. Re-validates "all players ready" itself even though `RoomService` already checked it — the same "neither layer trusts the other alone" principle already established for `PlayerService`'s protected fields.
- **`loadMatch(matchId) → matchDataOrNull`** — read-only; resolves `null` (not a rejection) if the match doesn't exist, mirroring `PlayerService.getPlayerProfile`'s established pattern exactly.
- **`subscribeToMatch(matchId, callback) → unsubscribe`** — now a real `onSnapshot` listener (was a no-op stub through Sprint 3.3). Delivers `(data, null)` or `(null, err)` to the callback rather than throwing, mirroring `PlayerService.subscribeToPlayerProfile`'s established pattern exactly.

**Kept as `Not implemented`, correctly, per the brief:** `submitDashCall`, `submitBid`, `submitPass`, `declareTrump`, `submitEstimate`, `playCard`, `resolveTrick`, `completeRound`, `advanceToNextRound`, `endMatch`. Bidding, estimation, and card-play are explicitly out of scope this sprint.

**Naming note:** the brief's requirements list literally says `subscribe(matchId)`. Implemented as `subscribeToMatch(matchId, callback)` instead — matching every other service's subscribe method in this codebase (`subscribeToRoom`, `subscribeToPlayerProfile`) — for naming consistency, not a deviation in behavior.

## The Match document's actual shape

```js
{
  roomId: "<room's document id>",
  players: ["<uid>", "<uid>", ...],       // copied from room.players at creation
  status: "starting",
  createdAt: <server timestamp>,
  currentRound: 1,
  dealer: "<uid>",                         // defaults to room.creator, else players[0]
  turn: "<uid>",                           // defaults to the same value as dealer
  gameState: {
    initialized: false,
    todo: "Dealer.dealHands() cannot be called yet — it depends on a global Deck module that does not exist in this repository. See docs/implementation/MatchInitialization.md before implementing this."
  }
}
```

This is deliberately much smaller than the full future-gameplay shape `docs/architecture/FirestoreSchema.md` originally speculated (`mode`, `hands`, `dealState`, `playState`, `biddingState`, `matchScores`, `roundHistory`, `winnerId`, ...). That document has been re-synced this sprint — the actual shape above is now what it describes as shipped, with the richer draft retained underneath, clearly labeled, as forward design only.

### A discovered issue: `Dealer.dealHands()` cannot be called — `Deck` does not exist

The brief's own instruction anticipated this possibility directly: *"Dealer may generate the initial game state only if already supported. Otherwise leave a TODO."*

While integrating the existing engine, `design-ui/engine/dealer.js` was read in full:

```js
function dealHands(seatOrder) {
  var order = (seatOrder && seatOrder.length) ? seatOrder : DEAL_ORDER;
  Deck.reset(); Deck.shuffle();
  var hands = {};
  order.forEach(function (id) { hands[id] = []; });
  for (var round = 0; round < 13; round++) {
    order.forEach(function (id) { var card = Deck.deal(1)[0]; card.owner = id; hands[id].push(card); });
  }
  ...
}
```

`Dealer.dealHands()` calls `Deck.reset()`, `Deck.shuffle()`, and `Deck.deal()` — but **no `Deck` object is defined anywhere in this repository.** A repository-wide search (`window.Deck`, `global.Deck =`, `Deck = {`) across `design-ui/` and `src/` returns zero matches. `deck.js` is referenced only in `dealer.js`'s own header comment ("depends on Deck") — it was apparently never delivered as an actual file. Calling `Dealer.dealHands()` as-is would throw `ReferenceError: Deck is not defined`.

Two options were considered:
1. **Write a `Deck` module.** Rejected — this would mean authoring new engine code, which exceeds this sprint's explicit "only integrate them, never rewrite/duplicate the gameplay engine" boundary. `Dealer`/`Cards`/etc. were to be integrated as-is, not completed.
2. **Leave `gameState` as an explicit TODO placeholder**, per the brief's own anticipated fallback. **Chosen.**

`gameState: { initialized: false, todo: "..." }` is the result — an honest placeholder, not fabricated dealt-hand data. This is recorded here, in `match-service.js`'s own header/function comments, in the automated test suite (`tests/match-service.test.cjs` explicitly asserts `gameState.initialized === false` and that the TODO message mentions `Deck`), and in the re-synced `FirestoreSchema.md`/`MatchLifecycle.md` — the same multi-location documentation discipline this project already applied to the Sprint 3.3 `creator` self-promotion gap.

`design-ui/engine/dealer.js` and `design-ui/engine/cards.js` were **not modified** — `cards.js` is self-contained (no missing dependency); `dealer.js`'s missing `Deck` dependency is a pre-existing gap in the repository, not something introduced or worsened this sprint.

## Game Start Flow — where "all ready" is detected, and where the match is actually created

Per the brief: *"RoomService detects all players ready. RoomService calls MatchService.startMatch()."* Implemented exactly that way — `RoomService` owns the detection, `MatchService` owns the atomic creation:

- `RoomService.setReady()`'s existing transaction (which only ever touches `rooms/{roomId}`) now has a `.then()` follow-up: `maybeStartMatch(roomId, room)`. This function checks `room.status === "waiting"` and that every entry in `players` is present in `readyPlayers`. If so, and if `MatchService.startMatch` is available, it calls it — as a **fire-and-forget, non-blocking** call. `setReady()`'s own promise resolves as soon as its own transaction commits; `startMatch()`'s success or failure is logged, never propagated back to `setReady()`'s caller. This matches the same fail-open shape already established for `syncCurrentRoomOnProfile`.
- If `MatchService` isn't loaded on the page (e.g. a screen that only includes `room-service.js`), `maybeStartMatch` logs a warning and returns — it never throws, and `setReady()` still succeeds normally.

### The atomicity boundary: why `startMatch()` has to write to `rooms/{roomId}` too

The brief's own architecture rules state "RoomService owns room lifecycle only; MatchService owns match lifecycle only." Read literally, this would mean `RoomService` should be the one to update `room.status`/`room.matchId`. But the brief also requires: *"Two users pressing Ready simultaneously cannot create two matches."*

Those two requirements are in tension. If `RoomService.setReady()`'s own transaction (touching only `rooms/{roomId}`) were the one deciding whether to create a match, and `MatchService.createMatch()` were a separate, later write to `matches/{matchId}`, there would be a window between the two writes where a second concurrent `setReady()` call could read the room *before* the first call's room update lands, see "not yet in_game," and also decide to create a match — producing two match documents for one room. Firestore transactions can only guarantee atomicity for documents they *themselves* read and write in one call; splitting the room-flip and the match-create into two separate transactions (one in each service) cannot close this race.

**Resolution:** `MatchService.startMatch()` opens **one transaction that reads and writes both `rooms/{roomId}` and `matches/{matchId}`.** The room's own `matchId` field is the atomic idempotency marker: the transaction reads the room, and if `matchId` is already set, it returns that value instead of creating a new match. Firestore's optimistic-concurrency retry (already relied on throughout this project's transactions) guarantees exactly one of two concurrent callers actually commits the "not yet set → now set" transition; the other retries, sees the now-set `matchId`, and returns it instead.

This is one deliberate, narrow, and explicitly documented exception to "RoomService owns room lifecycle only" — not a silent violation of it. `RoomService` still owns every *other* room mutation (`createRoom`/`joinRoom`/`leaveRoom`/`setReady`'s own `readyPlayers` write); `MatchService.startMatch()`'s two-field room write (`status`, `matchId`) is the one exception, and it exists purely to make the "no duplicate match" guarantee possible at all. This is recorded in both files' header comments, not hidden.

No circular dependency is introduced: `MatchService` never calls back into `RoomService` — it reads `rooms/{roomId}` directly from Firestore, not through `RoomService`'s API. The dependency graph remains one-directional: `RoomService → MatchService → {PlayerService, SessionService}`.

## `firestore.rules` — a required addition, not an optional one

While implementing `startMatch()`'s transaction, it became clear the existing `rooms/{roomId}` update rule (`isValidRoomUpdate()`, Sprint 3.3) would **deny** `startMatch()`'s own write: its field whitelist was `players`/`readyPlayers`/`status`/`creator`/`updatedAt` — `matchId` was not in it, and `status` only permitted `"waiting"`/`"closed"`, not `"in_game"`. Had these rules ever been deployed as-is, the entire Sprint 3.4 feature would fail with a permission-denied error the moment two players readied up. This isn't a hypothetical: it's the same "neither layer trusts the other alone" principle this project applies everywhere — the JS-layer atomicity guarantee in `startMatch()` and the rules-layer field/transition validation both have to independently allow the exact same write for it to work at all.

Fixed by:
- Extending `isValidStatusChange()` to permit `"in_game"`, only reachable from `"waiting"` (or from `"in_game"` itself, so a room already in-game isn't retroactively denied by a hypothetical future no-op update).
- Adding `isValidMatchIdChange()`: on the specific write that flips `status` from `"waiting"` to `"in_game"`, `matchId` **must** go from absent/`null` to a string in that same write (catches a status flip missing its paired matchId, or vice versa). On every other write, `matchId` must be completely unchanged — once set, it's immutable, so a room can never be silently re-pointed at a different match.
- Adding `matchId` to `isValidRoomUpdate()`'s field whitelist.
- Adding a brand-new `matches/{matchId}` block: `get` restricted to a player actually listed in the match's own `players[]` (not a global authenticated read, unlike `rooms/{roomId}` — a match is meaningfully private); `list` denied outright; `create` requires the acting user be one of the new document's own `players[]`, field-whitelisted to the actual shipped shape, with `status == "starting"`, `currentRound == 1`, and `dealer`/`turn` both actually in `players[]`; `update` **denied outright** — correct for the actually-shipped code, since no gameplay write path exists yet (see the re-synced `SecurityArchitecture.md`); `delete` denied.

This is a genuine rules change, not scope creep: the brief didn't explicitly forbid touching `firestore.rules` this sprint (unlike an earlier sprint's original room work), and leaving it unfixed would mean the shipped feature is unusable the moment the rules file is actually published — a far worse outcome than the small, tightly-scoped addition made here. `firestore.rules` remains an **undeployed, reviewable artifact**, per the pending-manual-publish state established since Sprint 2.6 — nothing here was pushed live to a Firebase project.

> **Sprint 3.4.1 tightened these same two functions further** — the Sprint 3.4 version above validated only the shape of the fields on each write in isolation; it never cross-checked the room and match documents against each other. See "Issue 2" in the Sprint 3.4.1 section near the top of this document for the full `get()`/`exists()`/`getAfter()` cross-check design.

## Navigation: Session → Room → Match → Screen

Per the brief ("Placeholder screen is acceptable... the important part is Session → Room → Match → Screen"), a placeholder screen exists at `design-ui/match/index.html` (with its own `game-state.js` copy, byte-identical to Lobby's/Profile's/Login's, per this project's established per-screen-folder pattern), following the exact visual pattern already established for `design-ui/profile/index.html` (Sprint 3.0): same visual token system (`--accent`/`--panel`/`--ink`), no new styling system introduced. It loads `MatchService` and calls `subscribeToMatch(matchId, ...)` to display the match's live `status`/`currentRound`/`dealer`/`players`, and shows a plain, honest note that bidding/dealing/gameplay aren't implemented yet — no fabricated game table. Unchanged in Sprint 3.4.1.

**SUPERSEDED in Sprint 3.4.1 — detection is no longer through `SessionService.subscribe()`/profile polling.** ~~When `MatchService.startMatch()` succeeds, `syncCurrentMatchOnProfiles()` already mirrors `currentMatchId` onto every player's own profile...~~ — this relied on exactly the broken cross-user write described in "Issue 1" above, which never worked for anyone but the initiating player. The current, real mechanism:

- `RoomService.loadRoom(roomId)` (new this hotfix — a plain read-only fetch, mirroring `MatchService.loadMatch()`'s pattern exactly) is what Lobby polls every 4 seconds, only while `lastRoomId` is known in this tab and no navigation has happened yet.
- Once a client observes `room.matchId` (via the poll, or immediately via its own `setReady()` call's returned `room.matchStart.matchId` — see Issue 4 above, no need to wait for the next poll tick if this tab was the trigger), it calls `SessionService.setCurrentMatchId(matchId)` (self-only — see Issue 1) and then navigates.
- The poll additionally retries `setReady(lastRoomId, uid, true)` (idempotent, safe) if the room is fully ready with no `matchId` yet — see Issue 4's retry mechanism above.

Two deliberate choices worth calling out (unchanged from Sprint 3.4, still accurate):
- **Why `force: true`:** `game-state.js`'s `TRANSITIONS` graph doesn't list `Gameplay` as reachable directly from `Lobby` (it models the placeholder `CreateRoom`/`WaitingRoom`/`Bidding` screens, none of which this sprint builds or routes through). `goTo()` already has a `force` option designed for exactly this case ("validates the transition... unless `opts.force` is set"). Using it means **`game-state.js` itself was never touched** — the same precedent Sprint 3.1 set for Profile navigation (which didn't need `force` only because `Profile` already happened to be a listed transition).
- **Why gate the poll on `lastRoomId`, not just any observed `matchId` alone:** a signed-in profile/room could in principle be observed outside of an in-session ready-up flow. Requiring `lastRoomId` (this tab's own in-memory reference, set only when *this tab* just created/joined a room) keeps the trigger scoped to exactly the in-session "I just readied up and a match was created" flow the brief describes — not a rudimentary reconnect feature, which stays explicitly out of scope.

A light `setInterval`-based poll (`RoomService.loadRoom()` every 4s — **not** `SessionService.refresh()`, superseded per Issue 1 above — only while `lastRoomId` is set and no navigation has happened yet) covers the player who *isn't* the one whose `setReady()` call happened to be the one that crossed the "everyone ready" threshold — their own tab wouldn't otherwise learn the match exists until something asks Firestore again. This is an honest, documented limitation (a poll, not a live listener) — `RoomService.subscribeToRoom`/`MatchService.subscribeToMatch` were both considered for this and rejected as more than a placeholder-screen requirement justifies; see the inline comments in `lobby/index.html`.

No visual redesign was made to Lobby beyond the minimum needed to detect and navigate on match start.

## Tests performed

See `TEST_CHECKLIST.md` in the QA package for the full, up-to-date list (Sprint 3.4.1). Summary: 59 automated tests in `tests/match-service.test.cjs` (startMatch happy path + full document shape, self-only `SessionService.setCurrentMatchId` sync with a regression guard proving `PlayerService.updatePlayerProfile` is never called with `currentMatchId`, not-all-ready rejection, nonexistent-room rejection, duplicate-start idempotency, two-concurrent-startMatch-calls-produce-one-match, `loadMatch`/`subscribeToMatch` coverage, Firestore-unavailable handling, all ten still-stubbed gameplay methods, confirmation `createMatch` is no longer public, a cross-service integration section proving `RoomService.setReady()` triggers `MatchService.startMatch()` end-to-end with the new awaited `room.matchStart` result, and a dedicated Task-4 section proving a simulated transient failure is observable and retryable); 31 tests in `tests/room-service.test.cjs` (regression-checked Sprint 3.2/3.3 suite plus new `loadRoom()` and `room.matchStart` coverage); 61 tests in `tests/rules-simulation.test.js` (45 preserved history + 16 new Sprint 3.4.1 tests covering `isValidNewMatchV2`/`isValidMatchIdChangeV2`'s room-binding cross-checks and the direct `players/{uid}` owner-only translation); real click-driven Playwright browser tests covering both the "this tab triggers the match" and "another player's ready-up is discovered via polling" navigation paths, plus the reconnect-avoidance regression guard, plus the pre-existing real-stack fail-open check.
