# Changelog — Sprint 2.7: Service Layer Skeleton

## Added
- `design-ui/room-service.js` — `RoomService` stub: `createRoom`, `joinRoom`, `setReady`, `leaveRoom`, `transferHost`, `closeRoom` (all throw `Not implemented`); `subscribeToRoom` (returns a no-op unsubscribe function).
- `design-ui/match-service.js` — `MatchService` stub: `createMatch`, `submitDashCall`, `submitBid`, `submitPass`, `declareTrump`, `submitEstimate`, `playCard`, `resolveTrick`, `completeRound`, `advanceToNextRound`, `endMatch` (all throw); `subscribeToMatch` (no-op unsubscribe).
- `design-ui/presence-service.js` — `PresenceService` stub: `updateHeartbeat`, `isOnline` (throw); `subscribeToPresence` (no-op unsubscribe).
- `design-ui/inventory-service.js` — `InventoryService` stub: `getInventory`, `purchaseWithSoftCurrency`, `equipItem` (all throw).
- `design-ui/leaderboard-service.js` — `LeaderboardService` stub: `getTopN`, `getMyRank`, `submitRankedResult` (all throw).
- `design-ui/shop-service.js` — `ShopService` stub: `getCatalog` (resolves `[]`), `getItem` (resolves `null`) — deliberately non-throwing, see `ServiceLayer.md`.
- `design-ui/analytics-service.js` — `AnalyticsService` stub: `logEvent`, `setUserProperties` — deliberately non-throwing no-ops, see `ServiceLayer.md`.
- `docs/implementation/ServiceLayer.md` — full implementation report.
- This QA package.

## Changed
Nothing. This sprint modifies zero existing files.

## Explicitly not touched (per this sprint's constraints)
`design-ui/player-service.js`, `design-ui/firebase-init.js`, `design-ui/login/index.html`, `design-ui/lobby/index.html`, `design-ui/login/game-state.js`, `design-ui/lobby/game-state.js`, and every file under `design-ui/engine/` (`session.js`, `bidding-engine.js`, `scoring-engine.js`, `dealer.js`, `cards.js`, `table-engine.js`).

## Not implemented (by design, this sprint)
No Firestore collections beyond `players` exist. No rules were deployed or modified. No Cloud Functions, Cloud Run, paid Extensions, or Blaze-only features of any kind were used.
