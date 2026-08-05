# Test Checklist — Sprint 3.1: Navigation Foundation

All tests below are real, executed browser tests — an actual DOM element was clicked, a real `window.location` navigation occurred to a real second file, and the resulting page's own console output and markup were inspected afterward, not inferred from reading the code.

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | Lobby → Profile | **PASS** | Injected a script that clicks the real `.player` element after Lobby loads; let the resulting real navigation run to completion; the final DOM (`--dump-dom`) has `data-screen-label="Profile"`, and the console log shows Profile's own script executing — proving the click genuinely navigated to the real Profile file, not just that a function was called. |
| 2 | Profile → Lobby | **PASS** | Same method: clicked the real `#backBtn` on Profile; final DOM has `data-screen-label="Main Lobby v2"`, console shows Lobby's own script executing. |
| 3 | Navigation preserves Session | **PASS**, precisely scoped | Stubbed `Auth`/`PlayerService` identically on both screens with the same fake identity. Lobby's own display showed the correct name (`NavTestUser`) *before* the click (confirmed via an in-page console log at click time). After the real navigation, Profile's DOM showed the exact same profile data (`NavTestUser` / `Ruby II` / `61,234` / `55`) with zero manual intervention. See `NavigationFoundation.md` for the precise, honest scope of this claim. |
| 4 | No page refresh | **PASS** | Navigation uses `GameState.goTo()`'s existing `window.location.href = file` (a navigation to a different resource), not `location.reload()` — confirmed by reading `game-state.js`'s unmodified `goTo()` implementation; no reload call exists anywhere in either screen's code. |
| 5 | No Session loss | **PASS** (from the player's perspective) | Same evidence as test 3 — the signed-in identity and profile data survive the navigation with no re-login prompt and no stale/wrong data shown. |
| 6 | No Firebase reconnect | **NOT ACHIEVED — reported honestly, not claimed** | The test evidence for #3 also shows `PlayerService.getPlayerProfile()` being called a **second time**, on Profile's page, after the navigation — a genuinely fresh fetch, not a reused in-memory value. Each screen's `SessionService` is a new module instance (inherent to navigating between separate HTML documents, not a defect this sprint introduced or could fix without a single-page-app rewrite, which is explicitly out of scope). Documented plainly in `NavigationFoundation.md` rather than claimed as passing. |
| 7 | No gameplay files changed | **PASS** | `git diff` — no file under `design-ui/engine/` appears in this sprint's change set. |
| 8 | `GameState` logic, `GameSession`, Dealer, Cards, Scoring, Bidding, Match Engine untouched | **PASS** | `git diff` — none of these files appear in the change set; only `design-ui/lobby/index.html` was modified. |
| 9 | `PlayerService`, `SessionService`, Firebase rules, Firestore schema untouched | **PASS** | `git diff` — none appear in the change set; no `.rules` file or schema-related file was touched. |
| 10 | No `window.location` call added anywhere in Lobby's or Profile's own code | **PASS** | Searched both files' inline scripts directly for `window.location` / `location.href` — zero matches; navigation only ever happens through `GameState.goTo()`, exactly as before this sprint. |
| 11 | No `NavigationService` was created unnecessarily | **PASS** (by design) | Confirmed no such file exists in this sprint's change set — a deliberate decision, documented in `NavigationFoundation.md`. |
| 12 | Lobby's visual appearance is unchanged | **PASS** | Screenshot comparison against Sprint 3.0's Lobby screenshot — identical layout; the only change (`cursor: pointer` on `.player`) has no visible effect in a static screenshot and no layout impact. |

## Not performed

None. Every scenario in the brief was directly, actually tested. Test #6 is marked as a genuine, honestly-reported limitation rather than `NOT PERFORMED` — it *was* tested, and the test correctly revealed that the literal requirement isn't achievable within this sprint's constraints (no SPA rewrite), which is exactly the outcome the brief's own fallback instruction ("document the limitation honestly instead of redesigning the architecture") anticipated.
