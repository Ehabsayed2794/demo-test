#!/usr/bin/env node
/* Hosting Merge (Option 3, Task B) — assembles the SINGLE static folder
 * `hosting-dist/` that `firebase.json`'s `hosting.public` points at.
 * Pure file-copy plumbing: no game logic, no engine code, no service
 * code is read or modified by this script. It assumes `vite build` has
 * already produced `dist/` (see the `build:hosting` npm script, which
 * runs `tsc -b && vite build --base=/estemshan/` first) — `--base` is a
 * CLI-only override for this one invocation, so `vite.config.ts` itself
 * is untouched and a plain `npm run build`/`npm run dev` still behaves
 * exactly as before this sprint.
 *
 * Result:
 *   hosting-dist/            <- design-ui/'s own files, copied as-is
 *     login/index.html       <- what firebase.json's "/" rewrite serves
 *     lobby/…  match/…  profile/…  engine/…  *.js
 *   hosting-dist/estemshan/  <- the built Estemshan score tracker
 *     index.html  assets/…
 */
import { existsSync, rmSync, mkdirSync, cpSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const DESIGN_UI = path.join(ROOT, "design-ui");
const VITE_DIST = path.join(ROOT, "dist");
const OUT = path.join(ROOT, "hosting-dist");
const OUT_ESTEMSHAN = path.join(OUT, "estemshan");

function fail(msg) {
  console.error("[build-hosting] FAILED: " + msg);
  process.exit(1);
}

if (!existsSync(DESIGN_UI)) fail("design-ui/ not found at " + DESIGN_UI);
if (!existsSync(VITE_DIST)) {
  fail(
    "dist/ not found at " + VITE_DIST + " — run `npm run build:hosting` " +
    "(not this script directly), which builds Vite first."
  );
}

console.log("[build-hosting] Clearing " + OUT);
rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

console.log("[build-hosting] Copying design-ui/ -> hosting-dist/ (root)");
cpSync(DESIGN_UI, OUT, { recursive: true });

console.log("[build-hosting] Copying dist/ -> hosting-dist/estemshan/");
mkdirSync(OUT_ESTEMSHAN, { recursive: true });
cpSync(VITE_DIST, OUT_ESTEMSHAN, { recursive: true });

console.log("[build-hosting] Done. hosting-dist/ is ready for `firebase deploy --only hosting`.");
