# Changelog — Sprint 2.6: Firebase Player Foundation

## Added
- `design-ui/player-service.js` — new `PlayerService` module: `ensurePlayerProfile`, `getPlayerProfile`, `updatePlayerProfile`, `subscribeToPlayerProfile`, `mapAuthUserToProfileDefaults`. The only code path that reads/writes `players/{uid}`.
- `firestore.rules` — rules scoped to `players/{uid}` only; deny-by-default preserved for every other collection. **Not yet deployed to the live project** — see `FirebasePlayerFoundation.md`'s Deployment section.
- `docs/implementation/FirebasePlayerFoundation.md` — full implementation report.
- This QA package.

## Changed
- `design-ui/firebase-init.js` — now also initializes `window.Db = firebase.firestore()` (guarded — pages without the Firestore compat script simply get `null`, no error).
- `design-ui/login/index.html`:
  - Added `firebase-firestore-compat.js` and `player-service.js` script includes.
  - Added `bootstrapProfile(user)`, wired into all five auth success paths: returning-session check (`onAuthStateChanged`), Create Account, Sign In, Continue with Google, Continue as Guest.
  - Fixed a pre-existing gap: anonymous (Guest) sign-in never set `displayName` on the Firebase Auth user, unlike every other sign-in path — `PlayerService` had nothing to read a name from for guests. One line added to match the existing Create/Google pattern.

## Not changed
- No file under `design-ui/engine/` was touched.
- No gameplay rule or scoring logic was touched.
- No Cloud Functions, Cloud Run, paid Extensions, Scheduled Functions, or real-money IAP were implemented or deployed.
- No billing was enabled; the project remains on the Spark plan.
- `design-ui/lobby/index.html` was not touched (it doesn't reference Firebase at all, so nothing here affects it).
