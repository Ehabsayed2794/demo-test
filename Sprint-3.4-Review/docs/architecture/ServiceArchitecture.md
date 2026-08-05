# Service Layer Design

Design only — interfaces and responsibilities, no implementation. Every method below is written as a contract (name, inputs, outputs, and what it's responsible for) precisely so that the *implementation* behind each one can move from "client function that writes directly to Firestore" to "callable Cloud Function" later, per `MigrationPlan.md`, without any caller needing to change. This is the concrete form of the "design for server authority now, implement client-authoritative now" decision made earlier in this project's architecture discussion.

Each service should be the **only** code path that touches its corresponding Firestore collection(s) — exactly the discipline `session.js` already enforces today ("every accepted game action must go through these APIs — it must never touch sessionStorage directly"). That comment, already written into the existing codebase, is this document's design principle stated a year early.

## AuthService

**Responsibility:** wraps Firebase Auth; the only place `firebase.auth()` is called directly.

- `signUp(email, password, displayName) → uid`
- `signIn(email, password) → uid`
- `signInAnonymously(displayName) → uid`
- `signInWithGoogle() → uid`
- `signOut()`
- `upgradeGuestAccount(credential) → uid` (see `PlayerLifecycle.md` — must preserve the existing `uid` via account linking, not create a new one)
- `getCurrentUser() → { uid, isAnonymous, email, displayName } | null`
- `onAuthStateChanged(callback)`

## PlayerService

**Responsibility:** owns `players/{uid}` and `inventory/{uid}`; the only place that reads/writes a player's profile, currency, or stats.

- `ensureProfileExists(uid, authProfile) → PlayerProfile` (the self-healing "create if missing" operation from `PlayerLifecycle.md`)
- `getProfile(uid) → PlayerProfile`
- `getProfiles(uids[]) → PlayerProfile[]` (batched read for rendering a room/match roster in one call, not N individual reads)
- `updateDisplayName(uid, name)`
- `updateHeartbeat(uid)` (the presence mechanism from `RoomLifecycle.md` — called on an interval by whatever screen is open)
- `applyMatchResult(uid, delta: { coinsDelta, gemsDelta, rpDelta, winIncrement })` — **this is the one PlayerService method that is explicitly a placeholder for the future Cloud Function boundary.** Today it runs client-side, writing the acting player's own delta under the soft rules-clamp from `SecurityArchitecture.md`. Its signature is deliberately shaped as "here is a computed delta, please apply it" rather than "here is a match ID, go figure out the delta yourself" — so that when this moves server-side, the Cloud Function can own the *computation* (re-deriving the delta from the match document itself, not trusting the client's claimed delta) while the surrounding call site doesn't change at all.

## RoomService

**Responsibility:** owns `rooms/{roomId}` — see `RoomLifecycle.md` for the actual, current state machine this implements.

**Synced to the real implementation in Sprint 3.3** (closing the drift the Sprint 3.2.5 Architecture Audit found — the method list below previously described a speculative design that was never built; see `docs/implementation/ReadyStateFoundation.md`).

- `createRoom(playerId, roomName) → roomId` — `roomId` is an auto-generated Firestore document ID, **not** a human-typeable join code as originally speculated. The caller shares it out-of-band (today: an `alert()` in Lobby) for others to join by ID.
- `joinRoom(roomId, playerId) → room` — resolves the full (plain object) room document, **not** a seat index — there is no seat concept in the shipped schema. Transaction-guarded (existence/closed/full checks) — see `RoomLifecycle.md`'s race-condition note, still accurate.
- `setReady(roomId, playerId, ready: boolean) → room` — **implemented in Sprint 3.3.** Sets/clears exactly one player's own entry in `readyPlayers`; requires the player to already be a room member; idempotent (a no-op call performs no write). See `RoomLifecycle.md`'s Ready State section. **Sprint 3.4:** after its own transaction commits, also *detects* "every player in the room is now ready" and, if so, fires `MatchService.startMatch(roomId)` as a non-blocking, fail-open follow-up (never blocks or fails `setReady`'s own already-committed success). This is the new `RoomService → MatchService` edge — one-directional, no circular dependency (see `MatchService`'s note below).
- `leaveRoom(roomId, playerId) → room | null` — removes the player from both `players` and `readyPlayers`; closes the room if now empty; transfers `creator` to the next remaining member if the creator left. `null` if the room was already gone (idempotent no-op).
- `transferHost(roomId, newHostUid)` — **still not implemented as a standalone operation.** `leaveRoom` performs the one supported ownership-transfer case inline; a player-initiated "make someone else the host while I stay" action does not exist yet.
- `closeRoom(roomId)` — **still not implemented as a standalone operation.** A room only closes as a side effect of its last member leaving via `leaveRoom`.
- `subscribeToRoom(roomId, callback) → unsubscribe` — **still not implemented.** Returns a no-op unsubscribe function; no live updates are delivered. Nothing in the current UI calls this yet.

## MatchService

**Responsibility:** owns `matches/{matchId}` (and, if the split-hands design is adopted, `matches/{matchId}/hands/{uid}`) — the single largest and most sensitive service in this system, since it's the boundary where the existing `BiddingEngine`/`TableEngine`/`ScoringEngine` connect to Firestore.

> **Re-synced in Sprint 3.4** (previous state: Sprint 2.7 speculative skeleton, all methods throwing `Not implemented`). See `docs/implementation/MatchInitialization.md` for the full implementation report. Depends on `PlayerService`/`SessionService` through their existing public APIs only; is depended on by `RoomService` (a new, one-directional `RoomService → MatchService` edge — see `room-service.js`'s header comment — introduces no circular dependency, since `MatchService` never calls back into `RoomService`).

**Implemented (Sprint 3.4):**
- `createMatch(roomId) → matchId` — the lower-level primitive: reads `rooms/{roomId}` (read-only), builds and creates a new `matches/{matchId}` document. No "all ready" gate, no duplicate-start protection — that guarantee is deliberately `startMatch()`'s job, not this one's.
- `startMatch(roomId) → matchId` — the orchestrated entry point `RoomService` calls once every player in a room is ready. A single Firestore transaction spans **both** `rooms/{roomId}` (status → `"in_game"`, sets `matchId`) and the new `matches/{matchId}` document — the one deliberate, narrow exception to "RoomService owns all room-state mutation" (documented in both files' header comments). Idempotent: if the room already has a `matchId`, returns that matchId instead of creating a second one — the concrete mechanism behind "two players pressing Ready simultaneously cannot create two matches." Re-validates all-ready itself (defense in depth) even though `RoomService` already checked it.
- `loadMatch(matchId) → matchDataOrNull` — read-only fetch; resolves `null` (not a rejection) if the match doesn't exist, mirroring `PlayerService.getPlayerProfile`'s established pattern.
- `subscribeToMatch(matchId, callback) → unsubscribe` — live `onSnapshot` listener; delivers `(data, null)` or `(null, err)` to the callback rather than throwing, mirroring `PlayerService.subscribeToPlayerProfile`'s established pattern. (Named `subscribeToMatch`, not the brief's literal `subscribe(matchId)`, for naming consistency with every other service's subscribe method in this codebase.)

**Match document shape actually shipped** (deliberately minimal — see `docs/architecture/FirestoreSchema.md`'s re-synced `matches/{matchId}` section for the full field list and how it differs from the richer speculative draft below): `roomId`, `players`, `status: "starting"`, `createdAt`, `currentRound: 1`, `dealer`, `turn`, `gameState: { initialized: false, todo: "..." }`. `gameState` is an explicit TODO placeholder, not a real dealt hand — `Dealer.dealHands()` (`design-ui/engine/dealer.js`) depends on a global `Deck` object that does not exist anywhere in this repository (`deck.js` was never delivered). See `MatchInitialization.md` for the full finding.

**Not yet implemented — bidding, estimation, and card-play are explicitly out of scope through Sprint 3.4** (all still throw `Not implemented`, matching every other unshipped method in this codebase):
- `submitDashCall(matchId, uid, decision)` — mirrors `GameSession.recordBidAction`
- `submitBid(matchId, uid, bid)` — mirrors `GameSession.recordBidAction`
- `submitPass(matchId, uid)` — mirrors `GameSession.recordPassAction`
- `declareTrump(matchId, uid, suit)` — mirrors `GameSession.setAuctionWinner` + the Confirmation-phase logic in `bidding-engine.js`
- `submitEstimate(matchId, uid, tricks)` — mirrors `GameSession.recordEstimate`
- `playCard(matchId, uid, cardId)` — mirrors `GameSession.recordCardPlay`
- `resolveTrick(matchId)` — mirrors `GameSession.recordResolvedTrick` (note: this one is a strong future-Cloud-Function candidate even before the others, since "who won the trick" is exactly the kind of computation that shouldn't be trusted from whichever client happens to call it first — see `MigrationPlan.md`)
- `completeRound(matchId)` — mirrors `GameSession.completeRound`, invokes `ScoringEngine.calculateRoundScore`
- `advanceToNextRound(matchId)` — mirrors `GameSession.nextRound`, must be a single transaction (see `MatchLifecycle.md`'s note on batched transitions)
- `endMatch(matchId, winnerId)` — commits final scores, triggers `PlayerService.applyMatchResult` for each participant, schedules archival (TTL field, per `MatchLifecycle.md`)

**Every method above that mutates match state takes the exact same shape**: `(matchId, actingUid, ...payload) → updated state`, mirroring the existing `GameSession.record*`/`GameSession.complete*` calling convention precisely. This uniformity is what makes the later Cloud Functions migration mechanical rather than a redesign. This design intent is preserved as forward-looking — nothing about it was implemented or invalidated this sprint.

## InventoryService

**Responsibility:** owns purchases/ownership, separate from `PlayerService`'s currency-balance concern.

- `getInventory(uid) → Item[]`
- `purchaseWithSoftCurrency(uid, itemId)` — validates balance client-side today (soft-enforced, see `SecurityArchitecture.md`); the real-money equivalent (`purchaseWithRealMoney`) is intentionally **not** defined yet — see `MigrationPlan.md`'s note that IAP is the one feature this whole design explicitly defers past the Blaze migration, not just past Spark.
- `equipItem(uid, slot, itemId)`

## LeaderboardService

**Responsibility:** owns `leaderboards/{seasonId}/entries/{uid}`.

- `getTopN(seasonId, n) → Entry[]`
- `getMyRank(seasonId, uid) → Entry`
- `submitRankedResult(seasonId, uid, delta)` — same "here is a computed delta" shape as `PlayerService.applyMatchResult`, same future-Cloud-Function boundary, same honest soft-enforcement note from `SecurityArchitecture.md`.

## ShopService

**Responsibility:** read-only access to `shop/{itemId}` catalog data (writes are admin-only, out of normal service scope).

- `getCatalog() → Item[]`
- `getItem(itemId) → Item`

## NotificationService

**Responsibility:** owns `notifications/{uid}/items/{notificationId}`.

- `subscribeToNotifications(uid, callback) → unsubscribe`
- `markRead(uid, notificationId)`
- `notify(recipientUid, type, payload)` — the "sender writes into the recipient's subcollection" case flagged in `SecurityArchitecture.md`; explicitly the first candidate to become a Cloud Function *fan-out trigger* later (e.g. triggered off `friendRequests` writes) rather than a direct client call, since that's the cleaner long-term shape for anything resembling a notification system.

## What this document deliberately does not include

Implementation details (does a service use a class, a module with closures like the existing engines, a set of exported functions — matching the existing `design-ui/engine/*.js` pattern is the obvious default but is a Sprint 3 decision, not an architecture one), error-handling conventions, and retry/offline-queue behavior are all out of scope for this design pass — they belong with the actual implementation, not the architecture document.
