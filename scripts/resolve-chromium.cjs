// Sprint 5.0 follow-up (see Sprint-5.0-Review/CHANGELOG.md's "CI hang"
// postmortem): the two Playwright test files hardcoded a path that only
// exists in this project's own dev sandbox
// (/opt/pw-browsers/chromium-1194/chrome-linux/chrome). On a real GitHub
// Actions runner that path doesn't exist, and chromium.launch({
// executablePath: <nonexistent> }) does not fail fast -- it hung the CI
// job for 58 minutes until GitHub's own job cancellation killed it,
// confirmed directly from the real job log.
//
// This resolves to the sandbox path when it exists (unchanged behavior
// here and in any other sandbox with the same image), and to `undefined`
// otherwise -- `chromium.launch({ executablePath: undefined })` is
// equivalent to omitting the option, so Playwright falls back to its own
// normal managed-browser resolution (the one `npx playwright install
// chromium` puts in place, which the CI workflow now runs first).
const fs = require("fs");

const SANDBOX_CHROMIUM_PATH = "/opt/pw-browsers/chromium-1194/chrome-linux/chrome";

function resolveChromiumExecutablePath() {
  try {
    if (fs.existsSync(SANDBOX_CHROMIUM_PATH)) return SANDBOX_CHROMIUM_PATH;
  } catch (e) {
    // fall through to the CI/default path below
  }
  return undefined;
}

module.exports = { resolveChromiumExecutablePath, SANDBOX_CHROMIUM_PATH };
