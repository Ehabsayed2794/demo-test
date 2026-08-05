# Player Profile Integration — Sprint 2.8 Implementation Report

**Scope actually implemented:** Lobby's existing UI is now populated from the real Firestore player profile via `PlayerService`, on top of (never instead of) the local `GameState` data it already used. Pure UI integration — no gameplay, multiplayer, rooms, matches, inventory, leaderboard, shop, presence, or Cloud Functions code was added.

## Files modified

| File | Change |
|---|---|
| `design-ui/lobby/index.html` | Added `firebase-app-compat.js` + `firebase-firestore-compat.js` + `firebase-init.js` + `player-service.js` script includes. Rewrote the existing topbar-wiring script into an `applyPlayerData()` helper (same fields, same elements, no markup change) called twice: once immediately from local `GameState` data (unchanged behavior), once again if/when `PlayerService.getPlayerProfile()` resolves with real data. |
| `design-ui/firebase-init.js` | One defensive fix: `window.Auth = firebase.auth()` was unconditional, which would throw if a page (like Lobby, deliberately) never loads the Auth compat script. Now guarded exactly like the existing `window.Db` check (`typeof firebase.auth === "function" ? firebase.auth() : null`). Login's behavior is unchanged — it still loads both SDKs, so `window.Auth` is still set there exactly as before. This was a necessary fix, not a redesign: without it, Lobby loading only the Firestore SDK would have crashed `firebase-init.js` entirely (breaking `window.Db` too, since both lines ran in the same function). |

No other file was touched — see Tests below for how that was verified.

## Why Lobby needs no Auth SDK at all

Per this sprint's rule ("never access Auth directly from Lobby"), Lobby doesn't just avoid *calling* Auth methods — it doesn't even load `firebase-auth-compat.js`. The player's `uid` (needed to call `PlayerService.getPlayerProfile(uid)`) comes from `GameState.getData().account.uid`, which Login already writes there today as part of its existing hand-off data. Lobby reads that the same way it already reads `GameState.getData().player` — this isn't a new kind of access, just a second field off the same existing object.

## What was bound

Per "only bind existing UI labels, do not redesign, do not move components" — exactly the four fields that already had a bound DOM element from Sprint 1's wiring:

- **Display Name** → `#playerName` + derives the avatar initial for `#playerAvatar`
- **Rank** → `#playerRank`
- **Coins** → `#playerCoins`
- **Gems** → `#playerGems`

**RP and Level are NOT bound — stated honestly, not glossed over.** The objective asked for both, but Lobby's current markup has no existing element for either one anywhere on screen (confirmed by searching the full file — the visible "4 levels" text belongs to the Play vs AI card, unrelated to player level; "Tier 18" belongs to the Season widget, unrelated to RP). Adding new elements to display them would have violated "do not redesign Lobby, do not move components" more directly than leaving a documented gap. This is flagged here and in `TEST_CHECKLIST.md` as `NOT PERFORMED`, with this exact reason, rather than either fabricating markup or silently skipping the requirement without saying so.

## Loading behavior

1. **Immediate paint** — exactly as Sprint 1 already worked: `GameState.getData().player` populates the four fields synchronously, before any network activity. Lobby's first paint is never blocked on Firestore.
2. **Enhancement** — if `GameState.getData().account.uid` exists and `PlayerService` loaded successfully, `getPlayerProfile(uid)` is called. If it resolves with a real profile, the same four fields are re-applied with the authoritative Firestore values (a player's real Rank/Coins/Gems from their profile document, not just whatever Login happened to pass through).
3. **Every failure mode resolves to "keep what's already on screen"**: no `uid` available, `PlayerService` not loaded, no profile document yet (`null`), or any Firestore error (offline, permission-denied, etc.) — all four are handled distinctly in code but produce the identical visible outcome: nothing changes, nothing crashes, Lobby stays exactly as usable as it was before this sprint.

## Tests performed

See `TEST_CHECKLIST.md` in the QA package for full detail. Summary: real, executed browser tests (not simulated) using dependency-injected `PlayerService` stubs to control the profile-fetch outcome deterministically, plus one test against the actual unmodified shipped code + real `firebase-init.js`/`player-service.js` (which reproduced the same known sandbox CDN limitation from Sprints 2.6/2.7, exercised end-to-end and confirmed to fail open cleanly). A real live-Firestore success case (a genuine profile document actually being fetched from the real project) is `NOT PERFORMED` — same reason as Sprint 2.6/2.7: `firestore.rules` from Sprint 2.6 is still not deployed to the live project, so any real fetch attempt against it currently returns permission-denied rather than data, regardless of this sprint's code being correct.

## Known limitation carried forward, not introduced

If Lobby is opened by directly double-clicking its HTML file rather than navigating there from Login (as established in Sprint 1's testing), `GameState.getData().account.uid` won't be present — each `file://` path is a separate browser security origin, so `sessionStorage` never carries over between screens that way. This sprint's code treats a missing `uid` as exactly the same safe "keep local defaults" case as every other failure mode — it doesn't newly break anything, it just can't fetch a real profile in that specific test scenario. Under real hosting (one shared `http(s)://` origin for every screen), this isn't a factor at all.
