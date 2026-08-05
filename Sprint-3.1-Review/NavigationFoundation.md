# Navigation Foundation — Sprint 3.1 Implementation Report

**Scope actually implemented:** one small change to `design-ui/lobby/index.html` — a click handler on the existing player-info area that calls `GameState.goTo()`. No new service, no file modified besides Lobby, no gameplay/Firebase/GameState-logic code touched.

## Decision: no `NavigationService` was created

`GameState.goTo()` already validates the transition, persists state, and navigates — exactly what "Lobby → Profile" and "Profile → Lobby" need. A `NavigationService` wrapping it would either add nothing or duplicate `GameState`'s own logic, both explicitly against this sprint's rules ("no new services unless absolutely necessary," "do not duplicate GameState"). Both screens call `GameState.goTo()` directly — the same pattern Profile's "Back to Lobby" button already used since Sprint 3.0, now extended to Lobby's new forward direction.

## What was built

**Lobby → Profile:** the existing `.player` topbar element (avatar + name + rank — already on screen, unchanged visually except `cursor: pointer`) now has a click handler:
```js
document.querySelector(".player").addEventListener("click", function () {
  GameState.goTo(GameState.STATES.PROFILE, { file: "../profile/index.html" });
});
```
**Profile → Lobby:** unchanged from Sprint 3.0 — `GameState.goTo(GameState.STATES.LOBBY)`, already working, re-verified this sprint (see Tests).

No `window.location` call appears anywhere in either screen's own code — `GameState.goTo()` is the only thing that ever touches `window.location`, and that was already true before this sprint (it's `GameState`'s existing, unmodified internal implementation).

## The honest limitation: `STATE_SCREEN.Profile` is still `null`

`game-state.js` is explicitly out of scope this sprint, so it was not edited. `STATE_SCREEN.Profile` remains `null`, exactly as it was left in Sprint 3.0. This works anyway because `GameState.goTo()` **already has an existing extensibility point for exactly this case** — the `opts.file` parameter, originally documented in `game-state.js`'s own comment ("used for states with more than one possible screen") but equally usable to supply a file when the table simply doesn't have one yet. Lobby's new click handler uses that existing, unmodified capability rather than requiring any change to `game-state.js` at all.

This is not a full fix — `STATE_SCREEN.Profile` being `null` means any *other* future call site that does `GameState.goTo(GameState.STATES.PROFILE)` without also passing `{ file: ... }` still won't navigate anywhere. Per this sprint's own instruction ("document the limitation honestly instead of redesigning the architecture"), that's recorded here rather than fixed — properly setting `STATE_SCREEN.Profile = "../profile/index.html"` is a one-line follow-up for whichever future sprint is allowed to touch `game-state.js`.

## What "navigation preserves session" actually means here — stated precisely

This project's screens are separate HTML files navigated via a real `window.location.href` change (`GameState.goTo()`'s existing behavior, not something this sprint altered) — not a single-page app. That has a direct, honest consequence worth stating plainly rather than glossing over:

- **What IS preserved, verified by real test:** the same signed-in identity and the same profile data. A real navigation from Lobby to Profile was tested with a simulated signed-in session; Profile's page, after a genuine file-to-file navigation, displayed the exact same name/rank/coins/gems Lobby had shown before the click. From the player's perspective, nothing is lost — they don't see a login prompt, don't see wrong or stale data, and don't have to do anything extra.
- **What is NOT preserved, and structurally cannot be without a SPA rewrite (explicitly out of scope):** each screen's `SessionService` is a fresh module instance created by that page's fresh script execution. The test evidence shows `PlayerService.getPlayerProfile()` being called again on Profile's page after the navigation — a second, genuine fetch, not a reused cached value carried over in memory. This is not a defect introduced by this sprint; it is an inherent property of navigating between separate documents, and it was already true of every previous cross-screen transition in this project (Login → Lobby has always worked this way too).
- **Practical effect:** Firebase Auth's own persistence (already relied on since Sprint 1) is what makes this invisible to the player — the browser's persisted credential lets the new page's Auth SDK resolve the same signed-in user quickly, without asking them to log in again. But "quickly and automatically" is not the same as "no reconnect at all," and this document says so rather than claiming a stronger guarantee than what was actually built.

## Tests performed

See `TEST_CHECKLIST.md` in the QA package. Summary: real, executed browser tests that actually clicked the real DOM elements and let real file-to-file navigation occur (verified by inspecting which screen's markup/console output ended up on screen afterward, not inferred) — Lobby → Profile, Profile → Lobby, and a combined test proving profile-data continuity across a real navigation with a simulated session. `git diff` confirms only `design-ui/lobby/index.html` was modified.
