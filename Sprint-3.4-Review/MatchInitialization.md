# Match Initialization & Game Start — Sprint 3.4 Implementation Report

**Scope actually implemented:** `MatchService.createMatch()`/`startMatch()`/`loadMatch()`/`subscribeToMatch()`; `RoomService.setReady()` extended to detect "everyone is ready" and trigger `MatchService.startMatch()`; a `matches/{matchId}` block added to `firestore.rules` alongside a `matchId` field on `rooms/{roomId}`'s update rule; a placeholder Match screen and Lobby-to-Match navigation. No gameplay engine (`bidding-engine.js`/`scoring-engine.js`/`dealer.js`/`cards.js`/`table-engine.js`) was modified — only integrated (partially, per the `Deck` finding below). No bidding, estimation, or card-play was implemented.

## `MatchService`

Previously (Sprint 2.7) an API-only skeleton — every method, including `createMatch`, threw `Not implemented`. This sprint activates four methods for real:

- **`createMatch(roomId) → matchId`** — the lower-level primitive. Reads `rooms/{roomId}` (read-only — never writes the room), builds the initial match document, creates a new `matches/{matchId}` document. No "everyone ready" gate, no duplicate-prevention. Calling it twice creates two documents — that guarantee is deliberately `startMatch()`'s job, not this one's.
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

## Navigation: Session → Room → Match → Screen

Per the brief ("Placeholder screen is acceptable... the important part is Session → Room → Match → Screen"), a new placeholder screen was added at `design-ui/match/index.html` (with its own `game-state.js` copy, byte-identical to Lobby's/Profile's/Login's, per this project's established per-screen-folder pattern), following the exact visual pattern already established for `design-ui/profile/index.html` (Sprint 3.0): same visual token system (`--accent`/`--panel`/`--ink`), no new styling system introduced. It loads `MatchService` and calls `subscribeToMatch(matchId, ...)` to display the match's live `status`/`currentRound`/`dealer`/`players`, and shows a plain, honest note that bidding/dealing/gameplay aren't implemented yet — no fabricated game table.

**Detection is entirely through `SessionService` — no new `RoomService`/`MatchService` subscription method was added for this.** When `MatchService.startMatch()` succeeds, `syncCurrentMatchOnProfiles()` already mirrors `currentMatchId` onto every player's own profile and calls `SessionService.refresh()`. Lobby's existing `SessionService.subscribe()` callback (already there since Sprint 2.9, unmodified in shape) now also checks the incoming profile for `currentMatchId` and — only if `lastRoomId` is also known in this tab (see below for why) — calls `GameState.goTo(GameState.STATES.GAMEPLAY, { file: "../match/index.html", force: true, data: { match: { id, roomId } } })`.

Two deliberate choices worth calling out:
- **Why `force: true`:** `game-state.js`'s `TRANSITIONS` graph doesn't list `Gameplay` as reachable directly from `Lobby` (it models the placeholder `CreateRoom`/`WaitingRoom`/`Bidding` screens, none of which this sprint builds or routes through). `goTo()` already has a `force` option designed for exactly this case ("validates the transition... unless `opts.force` is set"). Using it means **`game-state.js` itself was never touched** — the same precedent Sprint 3.1 set for Profile navigation (which didn't need `force` only because `Profile` already happened to be a listed transition).
- **Why gate on `lastRoomId`, not just `profile.currentMatchId` alone:** a signed-in profile can carry a `currentMatchId` left over from an earlier session (e.g. a tab reloaded mid-match). Auto-redirecting purely on that field would be a rudimentary form of reconnect — explicitly out of scope. Requiring `lastRoomId` (this tab's own in-memory reference, set only when *this tab* just created/joined a room) keeps the trigger scoped to exactly the in-session "I just readied up and a match was created" flow the brief describes.

A light `setInterval`-based poll (`SessionService.refresh()` every 4s, only while `lastRoomId` is set and no navigation has happened yet) covers the player who *isn't* the one whose `setReady()` call happened to be the one that crossed the "everyone ready" threshold — their own tab wouldn't otherwise learn the match exists until something asks Firestore again. This is an honest, documented limitation (a poll, not a live listener) — `RoomService.subscribeToRoom`/`MatchService.subscribeToMatch` were both considered for this and rejected as more than a placeholder-screen requirement justifies; see the inline comments in `lobby/index.html`.

No visual redesign was made to Lobby beyond the minimum needed to detect and navigate on match start.

## Tests performed

See `TEST_CHECKLIST.md` in the QA package. Summary: 50 automated tests in `tests/match-service.test.cjs` (createMatch document shape, startMatch happy path + room/profile side effects, not-all-ready rejection, nonexistent-room rejection, duplicate-start idempotency, two-concurrent-startMatch-calls-produce-one-match, loadMatch found/not-found, subscribeToMatch immediate-snapshot/update/unsubscribe/not-found/offline, Firestore-unavailable handling for every method, all ten still-stubbed gameplay methods, and a dedicated cross-service integration section proving `RoomService.setReady()` really does trigger `MatchService.startMatch()` end-to-end — including the two-players-readying-concurrently race — through the same fake Firestore instance, not just `MatchService` called directly); 16 new rules-simulation tests in `tests/rules-simulation.test.js` (45 total with the preserved Sprint 3.2.1/3.3 history) covering the `matchId`/`in_game` transition rules and the new `matches/{matchId}` create/get rules; zero regressions in the pre-existing 22 `tests/room-service.test.cjs` tests; real click-driven browser tests proving Lobby navigates to the Match placeholder screen once a room's `matchId` appears.
