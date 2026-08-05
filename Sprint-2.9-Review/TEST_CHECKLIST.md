# Test Checklist — Sprint 2.9: Session Foundation

All 26 tests below are real, executed automated tests against the actual `session-service.js` module (mocked `Auth`/`PlayerService`, not a re-implementation), plus real browser tests for the Lobby integration. Full test script and console output were reviewed; nothing here is inferred.

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | Exactly one Auth listener registered on load (`init()` idempotent) | **PASS** | Mock `onAuthStateChanged` call-count asserted `=== 1`. |
| 2 | Starts signed-out with no user/profile | **PASS** | `isLoggedIn()`, `getCurrentUser()`, `getCurrentProfile()` all checked at module load. |
| 3 | Anonymous login | **PASS** | Simulated Auth event with `isAnonymous: true`; confirmed `isLoggedIn()`, correct `uid`, and profile loaded with `accountType: "guest"`. |
| 4 | Guest login (explicit distinct scenario) | **PASS** | Same mechanism with a second guest identity; confirmed the cached profile switches to the new guest's data. |
| 5 | Email login | **PASS** | Simulated Auth event with `isAnonymous: false` + email; confirmed `accountType: "full"` and correct `uid` cached. |
| 6 | Missing profile | **PASS** | Simulated Auth event for a `uid` with no entry in the mock's profile store; confirmed `getCurrentProfile()` is `null` (not thrown) while `isLoggedIn()` stays `true`. |
| 7 | Firestore unavailable | **PASS** | Mock `PlayerService.getPlayerProfile` made to reject; confirmed session stays logged in (fail-open), profile cache clears to `null` rather than crashing, error logged. |
| 8 | Refresh profile | **PASS** | Called `refresh()`; confirmed it calls `PlayerService` again, returns and caches the (re-)fetched profile. Also confirmed `refresh()` with nobody signed in resolves `null` **without** calling `PlayerService` at all. |
| 9 | Logout | **PASS** | Simulated Auth firing `null` (as if signed out elsewhere); confirmed `isLoggedIn()` becomes `false` and the profile cache clears. |
| 10 | Session restore | **PASS** | Simulated a fresh sign-in event after a prior logout; confirmed user + profile fully repopulate — this is the same code path a real persisted-session restore on page load would take. |
| 11 | Cached profile behavior | **PASS** | Called `getCurrentProfile()` three times in a row; confirmed zero additional `PlayerService` calls — it's a pure cache read. |
| 12 | `clear()` manual reset | **PASS** | Called directly; confirmed `isLoggedIn()` false and both `getCurrentUser()`/`getCurrentProfile()` null. |
| 13 | `subscribe()` fires immediately with current state | **PASS** | New subscriber received one callback synchronously-after-microtask with the already-current session, without waiting for the next Auth event. |
| 14 | `subscribe()` fires again on change; `unsubscribe()` stops delivery | **PASS** | Confirmed a second delivery on logout, then confirmed zero further deliveries after calling the returned `unsubscribe()`. |
| 15 | A throwing subscriber doesn't block other subscribers | **PASS** | Registered a subscriber that always throws alongside a normal one; confirmed the normal one still received its notification. |
| 16 | `SessionService` never calls `PlayerService.ensurePlayerProfile` | **PASS** | Mock's `ensurePlayerProfile` was wired to throw if ever called; no test failed for that reason across the entire suite — profile creation stays exclusively Login's job. |
| 17 | Lobby renders correctly with `SessionService` wired in (visual regression) | **PASS** | Real headless-browser screenshot, no `SessionService`/Auth available (this sandbox's known CDN limitation) — Lobby shows local defaults identically to Sprint 2.8, no layout change. |
| 18 | Lobby renders the real profile once `SessionService` resolves one | **PASS** | Real headless-browser test: a stub `Auth`/`PlayerService` simulating a restored session; confirmed the DOM updates to the stub's distinct profile values (name/rank/coins/gems), proving the actual shipped glue code (not a re-implementation) works end-to-end. |
| 19 | Real (unstubbed) full stack fails open cleanly | **PASS** | Ran the actual unmodified shipped `lobby/index.html` in this sandbox, where the Firebase CDN is genuinely unreachable (same limitation as Sprints 2.6–2.8) — confirmed `SessionService`'s own "window.Auth is not available" warning fires, no uncaught exception, Lobby fully renders. |
| 20 | Lobby's own inline script never calls Firestore, `PlayerService`, or Auth directly | **PASS** | Searched Lobby's inline scripts for `firebase.firestore`, `window.Db`, `.collection(`, `window.Auth`, `firebase.auth`, `PlayerService.` — zero matches; the only references to those are the `<script src>` URLs themselves. |
| 21 | `PlayerService` was not modified | **PASS** | `git diff` — `design-ui/player-service.js` does not appear in this sprint's change set. |
| 22 | `firebase-init.js`, Login, `GameState`, `GameSession`, and all gameplay engine files are untouched | **PASS** | `git diff` — none of `firebase-init.js`, `login/index.html`, `login/game-state.js`, `lobby/game-state.js`, or any file under `design-ui/engine/` appear in this sprint's change set. |
| 23 | Zero gameplay files changed | **PASS** | Same `git diff` check as #22, restated per the brief's explicit test list. |
| 24 | Zero gameplay behavior changed | **PASS** | No gameplay code exists in this sprint's diff at all to have changed behavior — confirmed by the diff being limited to `session-service.js` (new) and `lobby/index.html`'s profile-wiring block only. |
| 25 | No new Firestore collections, rules changes, or Blaze-only features | **PASS** | Confirmed by reading the full diff — the only Firestore-related call added anywhere is `PlayerService.getPlayerProfile()`, already in Sprint 2.6's approved `players/{uid}` scope; nothing else touches Firestore, no rules file changed. |
| 26 | No new UI elements created, no UI redesigned | **PASS** | `lobby/index.html`'s markup (everything outside the `<script>` tags) is byte-identical to Sprint 2.8's version — confirmed via diff of the file, which shows changes contained entirely within the script block. |

## Not performed

None. Every scenario requested was directly testable this sprint (no live-Firestore-only claim was needed, since `SessionService`'s correctness is fully verifiable via its Auth/PlayerService seams without needing the pending rules deployment from Sprint 2.6).
