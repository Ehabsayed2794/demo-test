# Changelog — Sprint 2.8: Player Profile Integration

## Changed
- `design-ui/lobby/index.html` — added Firebase Firestore compat + `firebase-init.js` + `player-service.js` script includes; rewrote the existing topbar-wiring script into an `applyPlayerData()` helper called first from local `GameState` data (unchanged behavior, immediate paint), then again if `PlayerService.getPlayerProfile()` resolves with a real profile (new). Same four bound elements as before (`#playerName`, `#playerAvatar`, `#playerRank`, `#playerCoins`, `#playerGems`) — no markup added, moved, or removed.
- `design-ui/firebase-init.js` — guarded `window.Auth = firebase.auth()` the same way `window.Db` was already guarded, so a page that intentionally never loads the Auth SDK (Lobby, per this sprint's "never access Auth directly" rule) doesn't crash the whole init script. Login's behavior is unchanged.

## Added
- `docs/implementation/PlayerProfileIntegration.md` — full implementation report, including an honest note that RP/Level have no existing bindable UI element in Lobby's current markup.
- This QA package.

## Not changed
- `design-ui/player-service.js`, `design-ui/login/index.html`, `design-ui/login/game-state.js`, `design-ui/lobby/game-state.js`, and every file under `design-ui/engine/` — untouched, verified via `git diff`.
- No new Firestore collections, no rules changes, no Cloud Functions, Rooms, Matches, Inventory, Leaderboard, Shop, Presence, or realtime listeners were added.
