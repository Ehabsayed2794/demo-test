# Changelog — Sprint 3.1: Navigation Foundation

## Changed
- `design-ui/lobby/index.html`:
  - Added `cursor: pointer` to the existing `.player` topbar element (no layout/visual change otherwise).
  - Added a click handler on `.player` calling `GameState.goTo(GameState.STATES.PROFILE, { file: "../profile/index.html" })` — Lobby → Profile navigation, via `GameState` only, using its existing `opts.file` override (no `GameState` code modified).

## Added
- `docs/implementation/NavigationFoundation.md` — full implementation report, including an honest, precise statement of what "navigation preserves session" does and doesn't mean given this project's multi-page architecture.
- This QA package.

## Not changed
`design-ui/profile/index.html` (its "Back to Lobby" button already called `GameState.goTo(LOBBY)` since Sprint 3.0 — re-verified this sprint, not modified), `game-state.js` (all copies), `GameSession`, Dealer, Cards, Scoring, Bidding, Match Engine, `PlayerService`, `SessionService`, Firebase rules, Firestore schema, and all seven Sprint 2.7 service stubs. Verified via `git diff`.

## Explicitly not created
`NavigationService` — `GameState.goTo()` already provides everything needed; both screens call it directly. See `NavigationFoundation.md`'s "Decision" section.

## Known limitation, documented not fixed
`game-state.js`'s `STATE_SCREEN.Profile` remains `null` — Lobby's new click handler works around this using `GameState.goTo()`'s existing `opts.file` parameter, but any *other* future call site that navigates to `PROFILE` without also passing `{ file: ... }` still won't go anywhere until that table entry is filled in (a one-line change, out of scope this sprint since `game-state.js` is forbidden).
