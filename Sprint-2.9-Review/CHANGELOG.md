# Changelog — Sprint 2.9: Session Foundation

## Added
- `design-ui/session-service.js` — `SessionService`: `getCurrentUser()`, `getCurrentProfile()`, `isLoggedIn()`, `refresh()`, `clear()`, `subscribe()`. Owns the single Firebase Auth `onAuthStateChanged` listener for the app; internally calls `PlayerService.getPlayerProfile()` (read-only) to cache the signed-in user's profile.
- `docs/implementation/SessionService.md` — full implementation report.
- This QA package.

## Changed
- `design-ui/lobby/index.html` — added `firebase-auth-compat.js` + `session-service.js` script includes; Lobby's profile-enhancement step now calls `SessionService.subscribe(...)` instead of computing a `uid` from `GameState` and calling `PlayerService.getPlayerProfile()` directly (both removed). Same four bound elements (`#playerName`, `#playerAvatar`, `#playerRank`, `#playerCoins`, `#playerGems`), same `applyPlayerData()` helper, no markup added/moved/removed. Local `GameState`-based immediate paint is unchanged.

## Not changed
`design-ui/player-service.js` (per the brief: "keep PlayerService unchanged"), `design-ui/firebase-init.js`, `design-ui/login/index.html`, `GameState`/`GameSession`, and every file under `design-ui/engine/` — all untouched, verified via `git diff`.

## Not implemented (by design, this sprint)
No Rooms, Matchmaking, Presence, Inventory, or Shop code. No Cloud Functions. No new Firestore collections or rules changes. No UI elements added. `clear()` does not call Firebase's `signOut()` — see `SessionService.md`'s note on why that's a deliberate, narrow scope choice, not an oversight.
