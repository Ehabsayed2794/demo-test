# Hosting Merge — Option 3 ("Safe Routing") — Implementation Report

Executes the R1 product decision (`design-ui/` is now the production
multiplayer game) via pure configuration/routing plumbing, per the
approved Feasibility & Strategy Review. **Zero code changes inside
`src/`. Zero code changes inside `design-ui/`'s game-logic/service/engine
files.** The two exceptions are Task D's explicitly-requested, narrowly
scoped, disclosed cosmetic changes (below) — not a contradiction of the
"zero changes" framing so much as an explicitly pre-approved exception to
it, called out separately in the same request.

## Task A — `firebase.json`

Added a `hosting` block:
```json
"hosting": {
  "public": "hosting-dist",
  "ignore": ["firebase.json", "**/.*", "**/node_modules/**"],
  "rewrites": [{ "source": "/", "destination": "/login/index.html" }]
}
```
No `functions` or `run` key anywhere in the file — confirmed by direct
read, not assumed. The single rewrite is a static source→destination
mapping (Firebase Hosting's documented static-rewrite feature), not a
Cloud Functions/Cloud Run rewrite (those use a `function`/`run` key
instead of `destination` — neither appears here). This is what makes `/`
resolve to `design-ui/login/index.html` instead of 404ing, since
`design-ui/` has no root-level `index.html` of its own.

## Task B — Build pipeline

- **`scripts/build-hosting.mjs`** (new): pure file-copy assembly. Clears
  and recreates `hosting-dist/`, copies `design-ui/` to its root, copies
  the Vite build output (`dist/`) to `hosting-dist/estemshan/`. Reads no
  game logic, writes no game logic.
- **`package.json`**: added one script,
  `"build:hosting": "tsc -b && vite build --base=/estemshan/ && node scripts/build-hosting.mjs"`.
  `--base=/estemshan/` is a **CLI-only** override for this one invocation
  — `vite.config.ts` itself was NOT touched, so a plain `npm run build` or
  `npm run dev` behaves exactly as before this change. This was the one
  necessary piece of new plumbing: without it, the built `index.html`'s
  asset tags would reference `/assets/...` (site root) instead of
  `/estemshan/assets/...`, 404ing once served from a sub-path.
- **`.gitignore`**: added `hosting-dist` (generated output, same
  treatment as the pre-existing `dist`/`dist-ssr` entries — not committed).

## Task C — `README.md`

Rewritten from the unedited Vite template default to state design-ui/ is
the Production Multiplayer Game, src/ is the Legacy Score Calculator at
`/estemshan/`, with build/deploy/dev/test instructions.

## Task D — optional items (both done, both disclosed)

1. **Disabled the three dead-link controls** named in the request
   (Lobby → Ranked Match CTA, Play vs AI CTA, Settings gear icon):
   `onclick` removed, `disabled` attribute added, label changed to
   "Coming Soon" (CTAs) with a matching dimmed `:disabled` CSS rule reusing
   the exact `opacity:.45;cursor:not-allowed` convention already
   established in `match/index.html`. No `GameState`/navigation/service
   code touched — only the two `<button>` elements and one new CSS rule
   each. **Not touched**: the Shop nav button ("◇"), which is equally a
   dead link per the audit but wasn't named in the request — left alone to
   keep this change scoped to exactly what was asked, flagged here rather
   than silently expanded.
2. **`firestore.rules` header**: added the exact requested
   `⚠️ MUST DEPLOY MANUALLY TO FIREBASE CONSOLE BEFORE GO-LIVE ⚠️` line.
   Comment-only — confirmed via `git diff firestore.rules` showing no line
   outside the new comment block changed.

## Spark Plan verification

Grepped the full repo (already done in the prior feasibility review,
re-confirmed here): zero occurrences of `functions.https`, `onCall`,
`onRequest`, `pubsub`, or any Cloud Functions/Cloud Run construct, in
either `design-ui/` or this merge's new files. `firebase.json`'s new
`hosting` block uses only `public`/`ignore`/`rewrites` with a static
`destination` — no `function`/`run` key.

**Confirmed: No Blaze features used. Hosting config uses only static file
serving.**

## Build verification (real, not claimed)

```
npm run build:hosting
```
ran clean: `tsc -b` (typecheck) → `vite build --base=/estemshan/` (17
modules, `dist/index.html` + `assets/*.js`/`*.css`, asset tags correctly
rewritten to `/estemshan/assets/...`, confirmed by reading the built file
directly) → `node scripts/build-hosting.mjs` (copies confirmed via `ls -R`
below). Full log: see the commit's accompanying build log
(`docs/implementation/HostingMergeOption3-buildlog.txt`).

Resulting `hosting-dist/` structure (confirmed via `find -maxdepth 2`, not
assumed):
```
hosting-dist/
  login/index.html            <- served at "/" via the rewrite
  lobby/  match/  profile/  engine/  *.js
  estemshan/
    index.html
    assets/
    favicon.svg  icons.svg
```

Smoke-tested every real file with a plain static HTTP server
(`python3 -m http.server` inside `hosting-dist/`) — `login/index.html`,
`lobby/index.html`, `match/index.html`, `estemshan/index.html`, and
`estemshan/assets/index-*.js` all returned `200`.

**One check I could NOT complete, disclosed rather than faked**: actually
exercising Firebase Hosting's `/` → `/login/index.html` rewrite live via
`firebase serve --only hosting` failed in this sandbox with
`Error: Failed to authenticate, have you run firebase login?` — there is
no Firebase CLI session available here. The rewrite's `source`/
`destination` syntax matches Firebase Hosting's documented static-rewrite
format exactly (the same construct already used by countless production
Firebase sites for a custom root document), and every individual file it
would resolve to is confirmed present and servable — but the live
CLI-driven rewrite itself is unverified in this environment. Recommend
verifying this one specific behavior with `firebase serve --only hosting`
after `firebase login` on a machine with real credentials, before or
immediately after the first real deploy.

## Scope confirmation

`git diff --name-only`: `.gitignore`, `design-ui/lobby/index.html`,
`firebase.json`, `firestore.rules`, `package.json` — plus new files
`scripts/build-hosting.mjs`, `README.md` (rewritten), this report. No file
under `src/*.tsx`/`*.ts`, no `design-ui/engine/*.js`, no
`design-ui/match-service.js`/`match-adapter.js`/`*-service.js`,
`vite.config.ts` — none of them appear in the diff.
