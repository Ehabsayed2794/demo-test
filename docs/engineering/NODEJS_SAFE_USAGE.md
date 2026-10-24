# Safe Node.js Usage Policy (Windows Work Computer)

> **Mandatory entry rule: before using Node.js for any task in this repository,
> read this entire document and follow it. No step below is optional.**

This is the single canonical Node.js safety policy for this repository.
Project instruction entry points (currently `AGENTS.md`) link here rather
than duplicating these rules, so there is exactly one source of truth.

No method described here is completely risk-free. The goal is to minimize
risk and prevent unauthorized actions through explicit, persistent rules.

Scope: every AI agent and human contributor working on this repository on the
owner's Windows work computer. The owner is a non-technical project owner
whose priorities are: (1) protect the work computer and company data,
(2) avoid administrator privileges and system-wide modifications,
(3) avoid unnecessary installations, downloads, network access, and
third-party packages, (4) allow legitimate local validation tests when they
genuinely need Node.js, (5) never claim any method is completely risk-free.

Nothing in this document authorizes violating company IT policy. If company
IT policy forbids a portable Node.js installation or any action below, the
stricter company policy wins: stop and report the blocker.

## A. Installation and privileges

1. Never install Node.js, change system configuration, or modify
   machine-wide settings without the owner's explicit approval for that
   specific action.
2. Never request administrator elevation (UAC / "Run as Administrator")
   merely to run a local test. A validation test that appears to require
   elevation is a stop condition (see §G) — report it instead.
3. When Node.js is necessary and permitted by company IT policy, prefer an
   official, verified, per-user portable Node.js ZIP installation (e.g. the
   official `node-*-win-x64.zip` distribution extracted to a per-user
   folder). Do not use a system-wide installer unless the owner explicitly
   approves it.
4. Use only a compatible, project-approved Node.js version. Before running
   anything, check in this order: repository version files (`.nvmrc`,
   `.node-version`, `volta`/`engines` fields in `package.json`), then
   `package.json`, lockfiles (`package-lock.json`), then CI configuration
   (currently `.github/workflows/test.yml` pins `node-version: 22` via
   `actions/setup-node`). As of 2026-10-09 the project-approved version is
   **Node.js 22** per CI; there is no `.nvmrc`/`.node-version`/`engines`
   pin in the repo.
5. Do not assume the latest Node.js version is automatically the correct
   version. A newer major version can break the build or validation scripts.
6. Never modify the Windows system PATH, Windows Registry, system services,
   scheduled tasks, or machine-wide environment variables.
7. Do not install version managers (nvm, fnm, Volta, etc.) or package
   managers just to run a simple validation script.
8. If Node.js is unavailable and the task requires it, report the blocker
   and provide the exact minimum requirement (e.g. "Node.js 22.x portable,
   per-user, pending owner + IT approval"). Do not install anything on the
   owner's behalf.

## B. Portable execution

If an approved portable Node.js version already exists on the machine:

1. Use its explicit executable path when possible, e.g.:
   `& "<portable-node-dir>\node.exe" <script>`.
2. If PATH manipulation is needed, modify `$env:Path` **only within the
   current PowerShell session** (e.g.
   `$env:Path = "<portable-node-dir>;$env:Path"`), never persistently.
3. Do not permanently change the user's PATH or environment variables
   (no `setx`, no Settings → Environment Variables edits, no profile edits
   for this purpose).
4. Do not create persistent startup scripts, profile auto-loads, scheduled
   tasks, or background processes to make Node.js available.
5. Never run Node.js as Administrator. If the only way a command works is
   elevated, stop per §G.

## C. The least-privilege rule

Before running **any** Node.js command:

1. Identify the exact script and its purpose in one sentence.
2. Inspect the relevant script and any local source files it executes or
   loads (`Read` them; do not summarize from memory). Note `require` /
   `import` / `vm.runInContext` / `child_process` / `fs` / `fetch` /
   `http(s)` usage.
3. Determine whether the script accesses the network, modifies files,
   starts child processes, or performs other side effects. Record the
   answer in the task report.
4. Use the narrowest command that accomplishes the requested validation
   (e.g. `node <one-script>` rather than `npm run <broad-suite>`; pass
   explicit file paths; avoid wildcards and recursive runners unless the
   task requires them).
5. Run it from the correct repository directory, with no unnecessary
   privileges or unrelated secrets in scope (no admin shell, no extra
   credential environment variables).
6. Stop and report any unexpected behavior (new prompts, new downloads,
   new file writes, non-zero exits with unfamiliar output) instead of
   improvising a broader command.

Remember: portable Node.js is **not** a security sandbox. Code executed by
Node.js runs with the current user's privileges and can still read, write,
or transmit anything that user can access. "Portable" means "no installer",
not "isolated".

## D. Package installation and network access

1. Do not run `npm install`, `npm ci`, `pnpm install`, or `yarn install`
   unless the task genuinely requires project dependencies **and** the
   action is authorized. Prefer the existing authorized CI environment
   (see §G) over local installs.
2. Never install global packages (`npm i -g …`) merely for one test.
3. Never use `npx` or `npm exec` in a way that silently downloads and
   executes an unreviewed package. Treat every `npx <pkg>` as "download and
   run third-party code" unless the package is already in the lockfile and
   its version is pinned.
4. Do not execute remote scripts piped directly into PowerShell or a shell
   (e.g. `irm <url> | iex`, `curl … | node`). No exceptions without owner
   approval after reviewing the exact script content.
5. Do not download executables or packages from unofficial sources. Official
   sources only (nodejs.org distributions, the configured npm registry for
   pinned lockfile entries), and only when authorized.
6. Before running package lifecycle scripts (`preinstall`, `postinstall`,
   …) or project build scripts (`npm run build`, `build:hosting`, …),
   inspect `package.json` scripts and every script file they invoke, and
   assess side effects (file writes outside the repo, network calls,
   credential use). Report what you found before running.
7. Do not disable security controls merely to make a build pass (no
   `--no-verify`, no disabling signature verification, antivirus, AppLocker,
   execution policy bypasses beyond the minimum session-scoped need, no
   `--ignore-scripts` removal as a "fix" without understanding why).
8. If a test can run using Node.js built-in modules only (`node:fs`,
   `node:path`, `node:assert`, `node:vm`, …), prefer that approach and
   avoid dependency installation entirely.

## E. Project-specific rule: Ranked Results validation

For the existing standalone validation script:

`design-ui/standings-ranked-result/validate.cjs`

1. First confirm the file exists in the current checkout
   (`Test-Path` / `Glob`) and inspect the current version (`Read` it in
   full) — do not run a stale memory of it.
2. Verify the script and the files it loads are the expected project files.
   As of 2026-10-09 it loads `ranked-result.js`, `standings-render.js`,
   and `standings-engine.js` from the same directory via `vm.runInContext`,
   plus `table-system.css` and `standings-engine.js` text assertions, and
   uses only built-in modules (`node:fs`, `node:vm`, `node:assert/strict`,
   `node:path`). If the file or its dependencies have changed (new
   `require`/`import`, new files loaded, network or `child_process` usage),
   reassess the requirements before execution — fall back to §§C–D.
3. If the script still uses only Node.js built-in modules, run it directly
   with the approved Node.js executable from the repository directory, e.g.:
   `& "<portable-node-dir>\node.exe" design-ui/standings-ranked-result/validate.cjs`.
4. Do **not** run `npm install` or download additional packages for this
   validation. It needs none.
5. Report the exact command and the actual PASS/FAIL output verbatim,
   including the exit status.
6. Do not confuse this JavaScript validation with Kotlin/Gradle tests; they
   are separate test suites with separate runners and separate evidence.

## F. Testing and evidence

For every Node.js validation task, report all of the following:

1. Node.js version and executable source (`node --version` output plus the
   exact path or "system PATH" / "portable <dir>" / "CI setup-node").
2. Exact command executed (copy-pasteable, including working directory).
3. Whether dependencies were installed or network access was needed
   (ideally: "none — built-in modules only").
4. Actual test output and exit status (verbatim log excerpt; never
   paraphrase a pass).
5. Any files or other resources modified (or "none" if read-only).
6. Any unresolved limitations (e.g. "only the Ranked Results script was
   validated; full `npm run test:ci` suite was not run locally").

Never claim that a test passed if it was not actually executed. A test you
did not run is "not run", not "passing".

## G. Stop conditions

Stop without proceeding and ask the owner for approval if any of the
following applies:

1. Administrator privileges are requested or appear necessary.
2. System-wide settings must change (PATH, Registry, services, scheduled
   tasks, machine environment).
3. A test requires downloading an unfamiliar executable or running
   unreviewed remote code.
4. Dependency installation introduces unclear lifecycle scripts or material
   security concerns.
5. A command may modify company data, credentials, unrelated project
   files, or machine settings.
6. The requested action exceeds the permissions or security policy of the
   work computer (including company IT policy).

When local execution would require unnecessary changes, prefer running the
checks in the existing authorized CI environment instead: the
`test.yml` workflow (`npm ci` + `node tests/round-lifecycle.test.cjs` +
`npm run test:ci` on `ubuntu-latest` with Node 22) and the
`r1-golden-verification.yml` workflow. Say which workflow covers the check
and why local execution was deferred.
