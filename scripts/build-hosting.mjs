#!/usr/bin/env node
/* Hosting Merge (Option 3, Task B) — assembles the SINGLE static folder
 * `hosting-dist/` that `firebase.json`'s `hosting.public` points at.
 * Pure file-copy plumbing: no game logic, no engine code, no service
 * code is read or modified by this script. It assumes `dist/` has already
 * been produced by the `build:hosting` npm script.
 *
 * Result:
 *   hosting-dist/            <- design-ui/'s own files, copied as-is
 *     login/index.html       <- what firebase.json's "/" rewrite serves
 *     lobby/…  match/…  profile/…  engine/…  *.js
 *   hosting-dist/estemshan/  <- the built Estemshan score tracker
 *     index.html  assets/…
 *
 * The final assembly also injects a build-time BUILD_INFO object and a small
 * visible footer into every generated HTML page. Stable local asset URLs are
 * given a commit-derived query parameter so browsers cannot reuse an older
 * response after a deployment. Source HTML remains untouched.
 */
import { existsSync, rmSync, mkdirSync, cpSync, writeFileSync, readFileSync, readdirSync } from "node:fs";
import { execFileSync } from "node:child_process";
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

function currentCommitSha() {
  if (process.env.GITHUB_SHA) return process.env.GITHUB_SHA;
  try {
    return execFileSync("git", ["rev-parse", "HEAD"], { cwd: ROOT, encoding: "utf8" }).trim();
  } catch (error) {
    fail("could not determine the build commit SHA: " + error.message);
  }
}

function htmlFilesUnder(dir) {
  const files = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const absolute = path.join(dir, entry.name);
    if (entry.isDirectory()) files.push(...htmlFilesUnder(absolute));
    else if (entry.isFile() && entry.name.endsWith(".html")) files.push(absolute);
  }
  return files;
}

function cacheBustLocalAssets(html, buildVersion) {
  return html.replace(
    /(<(?:script|link)\b[^>]+(?:src|href)\s*=\s*["'])(?!https?:\/\/|\/\/|data:|#)([^"']+)(["'])/gi,
    (match, prefix, asset, suffix) => {
      if (/[?&]v=/.test(asset)) return match;
      return prefix + asset + (asset.includes("?") ? "&" : "?") + "v=" + encodeURIComponent(buildVersion) + suffix;
    }
  );
}

function injectBuildStamp() {
  const commitSha = currentCommitSha();
  const buildTime = new Date().toISOString();
  const buildVersion = commitSha.slice(0, 12);
  const buildInfo = { commitSha, buildTime, buildVersion };
  const buildInfoScript = `<script>window.BUILD_INFO=${JSON.stringify(buildInfo)};</script>`;
  const buildFooter = `<footer data-build-info style="position:fixed;right:10px;bottom:8px;z-index:9999;opacity:.55;font:10px/1.2 monospace;pointer-events:none">build ${buildVersion} · ${buildTime}</footer>`;

  const htmlFiles = htmlFilesUnder(OUT);
  if (!htmlFiles.length) fail("no HTML files found for build stamp injection");
  for (const htmlPath of htmlFiles) {
    const html = readFileSync(htmlPath, "utf8");
    const versioned = cacheBustLocalAssets(html, buildVersion);
    const withStamp = versioned.replace("</head>", buildInfoScript + "</head>");
    if (withStamp === versioned) fail("cannot inject BUILD_INFO into " + htmlPath);
    const withFooter = withStamp.replace("</body>", buildFooter + "</body>");
    if (withFooter === withStamp) fail("cannot inject build footer into " + htmlPath);
    writeFileSync(htmlPath, withFooter);
  }

  writeFileSync(path.join(OUT, "build-info.json"), JSON.stringify(buildInfo, null, 2) + "\n");

  // Build-output regression: fail the build if generated public HTML does not
  // expose the exact commit/time stamp and at least one cache-busted local URL.
  const stampedPages = htmlFiles.map((htmlPath) => readFileSync(htmlPath, "utf8"));
  const stampPresent = stampedPages.every((html) =>
    html.includes("window.BUILD_INFO") && html.includes(commitSha) && html.includes(buildTime)
  );
  if (!stampPresent) fail("generated HTML is missing the BUILD_INFO commit/time stamp");
  const cacheBustPresent = stampedPages.some((html) =>
    html.includes("?v=" + buildVersion) || html.includes("&v=" + buildVersion)
  );
  if (!cacheBustPresent) fail("generated HTML is missing the cache-busting build version");
  console.log(`[build-hosting] BUILD_INFO verified: ${commitSha} @ ${buildTime}`);
  console.log(`[build-hosting] Cache-busting verified: ?v=${buildVersion}`);
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

// W4 Arabic slice: load the additive dictionary after the existing Login and
// Lobby scripts in the generated artifact only. No protected design-ui HTML
// source file is edited; Firebase and Capacitor both consume this assembled
// copy. The module uses a single DOMContentLoaded/text-node pass.
for (const screen of ["login", "lobby"]) {
  const screenPath = path.join(OUT, screen, "index.html");
  if (!existsSync(screenPath)) fail(`required ${screen}/index.html is missing`);
  const html = readFileSync(screenPath, "utf8");
  const loader = '<script src="../shared-i18n.js"></script>\n';
  const updated = html.includes('src="../shared-i18n.js"')
    ? html
    : html.replace("</body>", loader + "</body>");
  writeFileSync(screenPath, updated);
}

console.log("[build-hosting] Copying dist/ -> hosting-dist/estemshan/");
mkdirSync(OUT_ESTEMSHAN, { recursive: true });
cpSync(VITE_DIST, OUT_ESTEMSHAN, { recursive: true });

// Capacitor requires the configured webDir itself to contain an index.html.
// Firebase still rewrites `/` directly to `/login/index.html`; this small
// generated wrapper exists only for the native shell and preserves the full
// multi-page hosting layout under the same artifact directory.
writeFileSync(path.join(OUT, "index.html"), `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=login/index.html"><title>Estimation</title></head><body><script>location.replace("login/index.html");</script><noscript><a href="login/index.html">Continue to Estimation</a></noscript></body></html>\n`);

injectBuildStamp();

console.log("[build-hosting] Done. hosting-dist/ is ready for `firebase deploy --only hosting` and Capacitor.");
