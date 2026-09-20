# TASK T-002: Integrate design-ui/ as Root Build/Deployment Artifact

## TASK ID
T-002

## PRIORITY
P0 — Critical (selected product unreachable to users)

## OBJECTIVE
Make `design-ui/` the actual artifact produced by the root build/deployment pipeline (`npm run build:hosting` → `firebase deploy --only hosting`), replacing the current output which only builds `src/` (legacy score tracker).

## BACKGROUND
**Product Decision PD-001 (2026-08-24):** `design-ui/` is the primary production multiplayer game. `src/` is legacy/transitional.

**Current State:**
- `package.json:build:hosting` builds `src/` with `--base=/estemshan/` and copies `design-ui/` via `scripts/build-hosting.mjs`
- `firebase.json` points `hosting.public` to `hosting-dist/`
- `design-ui/` has NO build step — it's vanilla HTML/JS modules served directly
- `design-ui/` screens: `login/`, `lobby/`, `match/`, `profile/` — each with own `index.html`
- **Gap:** No verified change makes `design-ui/` the root entry point at `/` (currently `/login/` → `/lobby/` → `/match/`)

**Evidence from Audit:**
- `PROJECT_STATUS_AND_MASTER_PLAN.md §21`: "Still the critical path: no verified change making design-ui/ the root build artifact"
- `README.md §14-15`: `/` → `/login/` is production game; `/estemshan/` is legacy
- `scripts/build-hosting.mjs` assembles `hosting-dist/` but routing/entry point untested

## SCOPE
1. **Define entry points:** `design-ui/login/index.html` → root `/` (with redirect), other screens at `/lobby/`, `/match/`, `/profile/`, `/engine/`
2. **Asset/Firebase config:** Ensure `design-ui/firebase-init.js` works from hosting root (not `/design-ui/`)
3. **Build pipeline:** Modify `scripts/build-hosting.mjs` to produce correct `hosting-dist/` structure
4. **Routing:** Handle SPA-like navigation for `design-ui/` screens (no hash routing currently)
5. **Smoke test:** Clean checkout → `npm ci && npm run build:hosting` → verify `hosting-dist/` structure → `firebase serve` locally → navigate full flow

## NON-GOALS
- Do NOT convert `design-ui/` to a Vite/React build — it must remain vanilla HTML/JS modules
- Do NOT modify `design-ui/` source files unless absolutely necessary for path resolution
- Do NOT touch `src/` build — it stays at `/estemshan/`
- Do NOT implement missing screens (Ranked, AI, Settings, Shop) — those are separate tasks

## RELEVANT FILES
| File | Role |
|------|------|
| `package.json:9` | `build:hosting` script |
| `scripts/build-hosting.mjs` | Assembles `hosting-dist/` |
| `firebase.json` | Hosting config (`public: hosting-dist`) |
| `design-ui/firebase-init.js` | Firebase config (must work from `/`) |
| `design-ui/login/index.html` | Entry point (should be at `/`) |
| `design-ui/lobby/index.html` | Lobby screen |
| `design-ui/match/index.html` | Game screen |
| `design-ui/profile/index.html` | Profile screen |
| `design-ui/engine/*.js` | Engine modules (loaded via `<script type="module">`) |
| `vite.config.ts` | Only affects `src/` build |

## ACCEPTANCE CRITERIA
1. `npm run build:hosting` completes without error
2. `hosting-dist/` structure:
   ```
   hosting-dist/
     index.html          → redirects to /login/ (or IS login page)
     login/index.html    → login screen
     lobby/index.html    → lobby screen
     match/index.html    → match screen
     profile/index.html  → profile screen
     engine/*.js         → engine modules
     *.js                → shared services (match-service.js, etc.)
     firebase-init.js    → works from root
   estemshan/            → legacy src/ build (unchanged)
   ```
3. `firebase serve` (or `npx serve hosting-dist`) allows full navigation: `/` → `/login/` → `/lobby/` → `/match/` → `/profile/`
4. Firebase Auth works (sign in, create account)
5. Room create/join works against local emulator
6. No console errors for module loading (all `<script type="module">` src paths resolve)

## TEST REQUIREMENTS
1. **Clean build test:** Fresh checkout → `npm ci && npm run build:hosting` → verify `hosting-dist/`
2. **Local serve test:** `npx serve hosting-dist` → manual navigation through full auth → lobby → match flow
3. **Emulator integration:** `firebase emulators:start --only firestore,auth` + `npx serve hosting-dist` → create room, start match
4. **Regression:** `src/` build still works at `/estemshan/` (run `npm run dev`)

## VERIFICATION COMMANDS
```bash
# 1. Clean build
git stash --include-untracked  # or fresh clone
npm ci
npm run build:hosting

# 2. Verify structure
ls -la hosting-dist/
ls -la hosting-dist/login/
ls -la hosting-dist/engine/

# 3. Local serve + emulator
npx firebase-tools emulators:start --only firestore,auth --project demo-test-ci &
npx serve hosting-dist -p 5000
# Open http://localhost:5000/ → should redirect to /login/
# Sign in → /lobby/ → create room → ready → start match → /match/

# 4. Verify estemshan/ still works
npm run dev  # should serve src/ at localhost:5173
```

## EXPECTED EVIDENCE
- `hosting-dist/` directory tree listing
- Screenshots or logs of successful local navigation through auth → lobby → match
- No 404s for module imports (check browser devtools Network tab)
- Firebase Auth operations succeed against emulator

## RISKS
| Risk | Mitigation |
|------|------------|
| Module paths break when served from `/` vs `/design-ui/` | All `<script src>` in HTML must use relative paths or absolute from root |
| `firebase-init.js` reads `firebase.json` config incorrectly | Verify `firebase.initializeApp()` config matches hosting project |
| Routing: direct URL access to `/match/` fails without auth state | Implement auth guard redirect in each screen's JS |
| `estemshan/` sub-path build regresses | Run `npm run dev` verification |

## DEPENDENCIES
- T-001 (dealer test) should be resolved first for clean CI baseline
- Firebase project `demo-test-ci` must exist for emulator

## ROLLBACK / SAFETY NOTES
- Changes only to `scripts/build-hosting.mjs`, `firebase.json`, and possibly `design-ui/firebase-init.js`
- `design-ui/` source files should NOT be modified — if path fixes needed, do it in build script
- Can revert by restoring `scripts/build-hosting.mjs` and `firebase.json`
- `src/` build is unaffected