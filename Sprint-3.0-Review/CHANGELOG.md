# Changelog — Sprint 3.0: Profile Foundation

## Added
- `design-ui/profile/index.html` — new Profile screen, consuming `SessionService` (via `subscribe()`) exactly as Lobby does. Displays Display Name, Rank, Coins, Gems, and a derived Avatar Initial. Includes a "← Back to Lobby" control calling the existing `GameState.goTo(LOBBY)` (an already-allowed transition, unchanged).
- `design-ui/profile/game-state.js` — byte-identical copy of the existing `game-state.js` (confirmed via `diff`), following the same per-screen-folder convention Login and Lobby already use. Not modified in any way.
- `docs/implementation/ProfileFoundation.md` — full implementation report, including the deliberate decision not to create a `ProfileService`.
- This QA package.

## Changed
Nothing. This sprint modifies zero existing files.

## Explicitly not created
`ProfileService` — `SessionService` already provides everything this screen needs (`getCurrentProfile()`, `subscribe()`); creating a wrapper service would have either added nothing or duplicated profile-loading logic, both against this sprint's own rules. See `ProfileFoundation.md`'s "Decision" section.

## Explicitly not touched (per this sprint's constraints)
`GameState` (all copies), `GameSession`, Dealer, Cards, Scoring, Bidding, Match Engine, `PlayerService`, `SessionService`, `RoomService`, `MatchService`, `PresenceService`, `InventoryService`, `LeaderboardService`, `ShopService`, `AnalyticsService`, Login, and Lobby. Verified via `git diff`.

## Known limitation, documented not fixed
`game-state.js`'s `STATE_SCREEN.Profile` remains `null` (unchanged, since `game-state.js` is forbidden this sprint) — Profile has no in-app navigation link pointing to it yet and is reachable only by direct URL. RP and Level are not displayed anywhere — no existing UI element or data source for either exists yet; a static "coming soon" caption is shown instead of fabricated values.
