# Estimation Card Game

## What ships where (Hosting Merge, Option 3 — decided and executed)

Per the R1 product decision: **`design-ui/` is now the production multiplayer
game and the site's primary entry point.** `src/` (the Estemshan score
tracker) is preserved, unmodified, as a secondary utility at `/estemshan/`.
Nothing was deleted, refactored, or rewritten to make this happen — this was
a routing/hosting-config decision only. See
`docs/implementation/HostingMergeOption3.md` for the full report.

| Path | What it is | Source |
|---|---|---|
| `/` (→ `/login/`) | **Production Multiplayer Game.** Real-time Firestore-backed Estimation, with a genuine bidding/table/scoring engine, real Firebase Auth, and multiplayer sync. | `design-ui/` |
| `/estemshan/` | **Legacy Score Calculator.** A manual pen-and-paper-style round-entry/scoreboard tool. No backend, no multiplayer. | `src/` (React + Vite) |

Known, still-open gaps in the multiplayer game (see
`PROJECT_STATUS_AND_MASTER_PLAN.md` for the full audit) — not fixed by this
merge, listed here so they aren't mistaken for regressions:
- `firestore.rules` is not yet confirmed deployed to a live Firebase
  project — see the warning at the top of that file.
- AI opponents, Shop, Ranked Match, and Settings have no built screens yet;
  their Lobby buttons are disabled ("Coming Soon") rather than dead links.
- Presence/abandonment detection (does the game know an opponent left?) is
  not implemented.

## Building for deployment

```bash
npm install
npm run build:hosting
```

This runs `tsc -b && vite build --base=/estemshan/` (building the score
tracker with its asset paths rewritten for the `/estemshan/` sub-path,
without changing `vite.config.ts` or anything under `src/`), then
`scripts/build-hosting.mjs` assembles the single static folder
`hosting-dist/` that `firebase.json`'s `hosting.public` points at:

```
hosting-dist/
  login/  lobby/  match/  profile/  engine/  *.js   <- design-ui/, copied as-is
  estemshan/
    index.html
    assets/
```

Deploy with `firebase deploy --only hosting` (requires `firebase login`
first). This project uses **Firebase Hosting's static file serving only** —
no Cloud Functions, no Cloud Run, no Blaze-only feature — so it stays on the
free Spark plan.

## Local development

- Multiplayer game (`design-ui/`): open its HTML files directly, or serve
  the repo root with any static file server (e.g. `npx serve .`) and
  navigate to `/design-ui/login/index.html`. No build step.
- Score tracker (`src/`): `npm run dev` (Vite dev server, standard root
  `base`, unaffected by the `/estemshan/` hosting sub-path above).

## Tests

See `tests/` for the Node test suite (`node tests/<file>.cjs`) — most run
standalone; files named `*.rules-emulator.test.cjs` require the Firebase
Firestore Emulator running locally (`firebase emulators:start`) and are
skipped cleanly otherwise.
