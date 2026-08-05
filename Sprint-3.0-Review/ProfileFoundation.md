# Profile Foundation — Sprint 3.0 Implementation Report

**Scope actually implemented:** one new screen, `design-ui/profile/index.html`, consuming `SessionService` exactly as Lobby does. No new service was created. No existing file was modified.

## Decision: no `ProfileService` was created

The brief explicitly asked to create one only if `SessionService` doesn't already provide everything required. It does: `getCurrentProfile()` and `subscribe()` are exactly what a read-only display screen needs, and Profile's data requirements (Display Name, Rank, Coins, Gems, Avatar Initial) are a strict subset of what `SessionService` already caches. Adding a `ProfileService` here would have meant either (a) it wraps `SessionService` and adds nothing, or (b) it duplicates profile-loading logic — both explicitly forbidden by this sprint's own rules ("no duplicated profile-loading logic," "never duplicate PlayerService logic"). Not creating it is the correct application of "reuse existing architecture," not a shortcut.

## What was built

`design-ui/profile/index.html` — a new screen, visually consistent with the existing Login/Lobby design language (same fonts, color tokens, panel style), showing:

- **Avatar Initial** — derived from Display Name, same convention as Lobby's topbar
- **Display Name**
- **Rank**
- **Coins**
- **Gems**

It subscribes to `SessionService` on load and re-paints these five fields every time `SessionService` emits a new state — signed-in-with-profile, signed-in-without-profile, or signed-out are all handled, and none of them can crash the screen (see Failure Policy below).

## RP and Level — documented honestly, not invented

Per the brief's explicit instruction, these are **not** displayed with fabricated values. There is no existing UI element or design anywhere in this project for either field — the same honest gap already recorded for Lobby in `docs/implementation/PlayerProfileIntegration.md`. Rather than leave this unstated, Profile includes one static, clearly-labeled caption — "RP & Level — coming soon" — so it reads as an intentional, acknowledged gap to anyone using the screen, not a bug. No RP/Level *data* is read, computed, or displayed anywhere in this file.

## Architecture — how it stays inside the rules

- **Profile talks to `SessionService` only.** It never calls `PlayerService`, never calls `firebase.firestore()`/`window.Db`, never calls `firebase.auth()`/`window.Auth` from its own inline script — verified by direct search (see Tests). The Firebase Auth/Firestore compat scripts are present on the page only because `SessionService` needs them internally, exactly the same reasoning already established for Lobby in Sprint 2.9.
- **No duplicated profile-loading logic.** Profile does not compute a `uid`, does not call `PlayerService.getPlayerProfile()` itself, and does not re-implement any part of `SessionService`'s caching — it is a pure `subscribe()` consumer, nothing more.
- **`PlayerService` and `SessionService` are both completely unmodified this sprint** — verified via `git diff`.

## `GameState` — used, not modified

`STATES.PROFILE` and the `Lobby ↔ Profile` transitions already existed in `game-state.js` before this sprint (evidently anticipated). Since `game-state.js` is explicitly forbidden to touch this sprint, **`STATE_SCREEN.Profile` remains `null`**, unchanged — meaning `GameState.goTo(GameState.STATES.PROFILE)` still won't navigate anywhere from another screen yet, and nothing currently links to Profile from Lobby's UI (adding such a link would mean modifying Lobby's markup, also out of scope: "do not redesign any UI"). Profile is reachable today by direct navigation only. This is a known, explicit, deliberate limitation — not an oversight — and is a natural, small follow-up for whichever future sprint is allowed to touch both `game-state.js` and Lobby's markup. `design-ui/profile/game-state.js` is a byte-identical copy of the existing file (confirmed via `diff`), following the exact same per-screen-folder convention Login and Lobby already use — copying an unmodified file is not the same as modifying it.

## Failure policy (fail-open, verified — see Tests)

- No `SessionService` loaded at all: logs an error, screen shows its neutral placeholder text ("—"), never crashes.
- Signed out (`session.profile` is `null`): same — placeholders stay, no crash.
- Missing profile document (a real `uid` but no Firestore document): same — `SessionService` already reduces this to `profile: null` (per Sprint 2.9's design), so Profile handles it identically to "signed out," without needing to know the difference.
- Firestore unavailable: same again — `SessionService` already absorbs this into `profile: null` internally; Profile never needs its own separate Firestore-error handling because the layer below it already normalized that case away.
- A later `SessionService` emission with real data (e.g. after `SessionService.refresh()`, or the initial Auth-state resolution completing after the page's first paint): the screen re-renders correctly — verified directly (see Tests).

## Tests performed

See `TEST_CHECKLIST.md` in the QA package. Summary: real browser tests, not simulated — one against the actual unmodified shipped file with dependency-injected `Auth`/`PlayerService` stubs proving the full `SessionService → Profile` pipeline renders real data correctly; one with a direct `SessionService` stub emitting `null` then real data in sequence, with timing-independent in-page assertions (not screenshot timing) proving both the missing-profile safe state and the "updates after refresh" reactivity; one against the real unmodified stack in this sandbox's CDN-constrained environment, confirming the same fail-open behavior already established in Sprints 2.6–2.9 reproduces here too, cleanly. `git diff` confirms zero files outside `design-ui/profile/` (new) were touched — every forbidden file (`GameState`, `GameSession`, Dealer, Cards, Scoring, Bidding, Match Engine, `RoomService`, `MatchService`, `PresenceService`, `InventoryService`, `LeaderboardService`, `ShopService`, `AnalyticsService`, and `PlayerService`/`SessionService` themselves) is unmodified.
