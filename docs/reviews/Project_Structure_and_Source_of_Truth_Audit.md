# Project Structure and Source-of-Truth Audit

**Type:** Read-only audit. No files were modified, created (other than this report), deleted, or refactored. No branch was switched, checked out, pulled, merged, committed, or pushed. Every command run to produce this report was inspection-only (`git status`, `git log`, `git diff --stat`, `find`, `grep`, `cat`, `wc`, `diff`).

---

## 1. Executive Summary

This repository contains **one active, coherent backend/engine implementation** (the vanilla-JS Firestore-synced Estimation game engine under `design-ui/`) and **one unrelated, dormant, parallel implementation** (a React/TypeScript scoring scaffold under `src/`, left over from the repo's original Vite template and never touched by any of the 30+ "Sprint" commits that built the real system). All Sprint work — the actual production history — lives on branch `claude/busy-bohr-ez5rz3`; the `main` branch contains only the original, empty Vite scaffold (a single "Initial commit").

**The single most important finding**: the interactive Bidding/Game-Table **rendering** layer does not exist anywhere in this repository. `design-ui/engine/table-engine.js` and `design-ui/engine/bidding-engine.js` call UI functions (`render()`, `bindStatic()`, `showEscalationBanner()`, `flashReject()`, `showRoundDone()`) that are **never defined in any file in this repo**. The one real Match screen that does exist (`design-ui/match/index.html`) is an explicit placeholder that does not even load these engine files. This repo is fully self-contained for the **backend/sync/rules/test** layer, but the actual visual gameplay screens referenced in this project's history live outside it (or were never committed here).

There is no Firebase emulator configuration, no `firebase.json`, no `.firebaserc` anywhere in the repo — the app talks to a real, hardcoded, live Firebase project (`made---estimation-card-game`) via CDN-loaded Firebase compat scripts. No test in this project's 954-test suite has ever run against real Firestore or an emulator (confirmed directly in-repo, in the tests' own header comments).

---

## 2. Current Workspace

| Item | Value |
|---|---|
| Current working directory | `/home/user/demo-test` |
| Git repository root | `/home/user/demo-test` (same as cwd) |
| Git remote(s) | `origin` → `https://github.com/Ehabsayed2794/demo-test` (fetch + push) |
| Current branch | `claude/busy-bohr-ez5rz3` |
| Tracking | `origin/claude/busy-bohr-ez5rz3`, up to date |
| Working tree | **Clean** — `nothing to commit, working tree clean` |
| Submodules | **None** — no `.gitmodules` file |
| Nested Git repositories | **None found** — no `.git` directory anywhere below the root other than the top-level one |
| Ignored directories with real project content | **`dist/`** (a built Vite bundle — reproducible, not source) and **`node_modules/`** (installed dependencies — reproducible via `npm install`). No other ignored paths contain anything unique or unrecoverable. |

`.gitignore` only excludes: log files, `node_modules`, `dist`/`dist-ssr`, `*.local`, editor directories (`.vscode/*` except `extensions.json`, `.idea`), and OS/editor cruft. Nothing suggests a hidden, ignored source directory exists.

---

## 3. Repository Structure

```
/home/user/demo-test
├── design-ui/                    ← THE ACTIVE PROJECT (backend + partial UI)
│   ├── engine/                   ← pure game-logic engine (vanilla JS, headless-safe)
│   │   ├── cards.js              suit/rank model
│   │   ├── deck.js               shuffle + deal
│   │   ├── dealer.js             hand assembly (uses Deck)
│   │   ├── bidding-engine.js     Dash/Auction/Confirm/Estimates reducer
│   │   ├── table-engine.js       trick-taking reducer (follow-suit, trump, resolveTrick)
│   │   ├── scoring-engine.js     round-score calculation (docx-grounded rules)
│   │   └── session.js            GameSession — mock-data + remote-match mirror
│   ├── match-service.js          Firestore read/write boundary (matches/{matchId})
│   ├── match-adapter.js          Firestore ⇄ engine translation + sync (bid/turn/card/trick)
│   ├── room-service.js           Firestore rooms/{roomId}
│   ├── session-service.js        players/{uid}.currentMatchId self-mirror
│   ├── player-service.js         players/{uid} profile
│   ├── presence-service.js, inventory-service.js, leaderboard-service.js,
│   │   shop-service.js, analytics-service.js   (service-layer stubs/skeletons)
│   ├── firebase-init.js          hardcoded Firebase project config (real project)
│   ├── login/, lobby/, match/, profile/        ← 4 static HTML screens, each self-contained
│   │   (each carries its OWN copy of game-state.js — see §5)
│   └── SHARED_COMPONENTS.md
├── tests/                        ← 15 files, 954 checks, all MOCKED or SIMULATED, run via plain `node`
├── docs/
│   ├── architecture/             ← 14 living design docs (re-synced every sprint)
│   └── reviews/                  ← 16 per-sprint Implementation Reports (historical record)
├── firestore.rules               ← the ONLY Firebase config file that exists
├── src/                          ← UNRELATED React/TS scaffold — see §5, Finding D
│   ├── App.tsx, utils.ts, types.ts, main.tsx, App.css, index.css
│   └── assets/ (react.svg, vite.svg, hero.png)
├── index.html                    ← Vite entry point for src/ (React app), NOT for design-ui/
├── package.json / package-lock.json / vite.config.ts / tsconfig*.json / tailwind.config.js / postcss.config.js
├── public/                       ← Vite static assets folder (for src/, near-empty)
├── dist/                         ← built output of `vite build` (gitignored, present from a prior local build)
├── node_modules/                 ← installed (gitignored)
├── Sprint-2.8-Review/ … Sprint-4.3-Review/  ← 26 QA-package snapshot directories + matching .zip files (historical, see §5)
├── Task-Firebase-Player-Foundation-Review/, Task-Service-Layer-Review/  ← 2 more, older-format QA snapshots
└── .claude/                      ← Claude Code session config (not project source)
```

No Kotlin, no Android, no Gradle, no `.kt`/`.kts` files exist anywhere in this repository (0 matches for `*.kt`). Earlier work referencing a Kotlin port or an Android companion app (visible in this session's own prior conversation history) is **not present in this repository at all** — if that code still exists, it lives in a different repository or workspace this session has no access to.

---

## 4. Major Components

| Component | Location | Status |
|---|---|---|
| Card model | `design-ui/engine/cards.js` | Active |
| Deck | `design-ui/engine/deck.js` | Active (added Sprint 3.5) |
| Dealer | `design-ui/engine/dealer.js` | Active |
| Bidding engine | `design-ui/engine/bidding-engine.js` | Active — headless reducer; calls undefined UI hooks if ever loaded in a real browser page (see §11) |
| Table/trick engine | `design-ui/engine/table-engine.js` | Active — same headless/UI-hook caveat |
| Scoring engine | `design-ui/engine/scoring-engine.js` | Active |
| GameSession | `design-ui/engine/session.js` | Active — the current, canonical copy |
| GameSession (stale duplicate) | `design-ui/lobby/session.js` | **Legacy/divergent** — see §5, Finding A |
| MatchService (Firestore) | `design-ui/match-service.js` | Active — the only file that writes `matches/{matchId}` |
| MatchAdapter (sync) | `design-ui/match-adapter.js` | Active — bid/turn/card/trick sync, engine ⇄ Firestore |
| RoomService | `design-ui/room-service.js` | Active |
| SessionService | `design-ui/session-service.js` | Active |
| PlayerService | `design-ui/player-service.js` | Active |
| Firebase init | `design-ui/firebase-init.js` | Active — real, hardcoded prod config |
| Firestore rules | `firestore.rules` (repo root) | Active — the only deployable Firebase artifact in the repo |
| UI screens (Login/Lobby/Match/Profile) | `design-ui/{login,lobby,match,profile}/index.html` | Active, but **Match is an explicit placeholder** (no bidding/gameplay UI) |
| Bidding/Gameplay render layer | **Not found anywhere in this repository** | **Missing** — see §11 |
| Tests | `tests/*.test.cjs`, `tests/rules-simulation.test.js` | Active, 954 checks, run via `node tests/<file>` |
| Architecture docs | `docs/architecture/*.md` | Active, kept current every sprint |
| Sprint reports | `docs/reviews/*.md` | Historical record, one per sprint |
| QA package snapshots | `Sprint-*-Review/`, `Task-*-Review/` (+ `.zip`) | Historical archives — see §5, Finding B |
| React/TS scaffold | `src/*.tsx`, `src/*.ts` | **Dormant, unrelated** — see §5, Finding D |

---

## 5. Duplicate / Parallel Implementations

### Finding A — `GameSession`: two divergent copies

| File | Language | Purpose | Active? | Legacy? | Duplicates? |
|---|---|---|---|---|---|
| `design-ui/engine/session.js` (570 lines) | JS | Canonical GameSession, includes Sprint 3.7+ remote-match mirror (`subscribeToRemoteMatch`, `onRemoteMatchUpdate`, play-state persistence) | **Yes — current** | No | — |
| `design-ui/lobby/session.js` (470 lines) | JS | An **earlier, stale copy** of the same file — missing every Sprint 3.7+ addition (confirmed via `diff`: the entire remote-match-mirror block, ~100 lines, is absent) | **Yes — actually loaded by `design-ui/lobby/index.html`** | **Yes, in content** (frozen pre-Sprint-3.7) | **Yes, duplicates `engine/session.js`** |

**Consequence**: the Lobby screen runs on an outdated `GameSession` that has never received the remote-match-sync capabilities every later sprint built. This is a real, live divergence — not just an archive — since `lobby/index.html` actively `<script src="session.js">`s this stale copy today.

### Finding B — Per-sprint QA snapshot directories (not parallel implementations, but easy to mistake for one)

`Sprint-2.8-Review/` through `Sprint-4.3-Review/`, plus `Task-Firebase-Player-Foundation-Review/` and `Task-Service-Layer-Review/` (28 directories total, most with a matching `.zip`), each contain a **frozen copy** of whichever files that sprint touched (e.g. `Sprint-4.3-Review/design-ui/match-adapter.js`). These are **deliverable archives created at the end of each sprint for review purposes** — they are committed to git, but are not imported, required, or referenced by any live code path. A `grep` for `BiddingEngine`/`MatchService`/etc. across the repo surfaces these paths alongside the real ones; they must be excluded when searching for "the" implementation of anything. None of them is the source of truth.

### Finding C — `game-state.js`: 4 byte-identical copies (by design, not drift)

`design-ui/{lobby,login,match,profile}/game-state.js` are **byte-for-byte identical** (`diff` confirms no differences across all 4). This is a deliberate consequence of each screen being a standalone static HTML page with no shared bundler/module system — each screen's own directory carries its own copy so the page works when opened via `file://` as well as `http://` (per `firebase-init.js`'s own comment). Not currently a divergence risk, but a **maintenance risk**: a future edit to one copy that isn't propagated to the other 3 would silently reintroduce the SAME class of drift Finding A already shows happened to `session.js`.

### Finding D — `src/` (root-level React/TypeScript scaffold): a fully separate, dormant implementation

| File | Language | Purpose | Active? | Legacy? | Duplicates? |
|---|---|---|---|---|---|
| `src/App.tsx` (473 lines), `src/utils.ts` (205 lines), `src/types.ts` (48 lines) | TypeScript/React (JSX) | A **standalone scoring/round-tracker app** — its own `GameState` type, its own `calculateRoundScores`/scoring-role logic (`NORMAL`, `CALLER`, `WIZZ`, `RISK`, `SUPER_CALL`, `DASH_CALL`, etc.) | **Dormant** — not referenced by, imported by, or referencing anything in `design-ui/` | **Effectively legacy relative to the real project** — it predates and was never updated alongside any of the 30+ Sprint commits | **Yes — duplicates scoring-role concepts also implemented in `design-ui/engine/scoring-engine.js`, independently, in a different language, with different naming** |

This is the **original content of the very first commit** on `main` (a generic `npm create vite` React+TS template that was apparently seeded with a scoring prototype before the real `design-ui/` project began). It is wired to `index.html`/`vite.config.ts`/`package.json`'s `dev`/`build` scripts — **running `npm run dev` in this repo launches THIS app, not the Estimation game.** It has zero code-level relationship to `design-ui/`'s engine. No Sprint ever touched it (confirmed: `git diff --stat main...HEAD` shows 379 files changed, 86504 insertions — all of it net-new `design-ui/`, `tests/`, `docs/`, `firestore.rules`, and QA-package content; **not one line inside `src/` was ever modified**).

### Other terms searched, no duplication found

`Deck`, `Dealer`, `BiddingEngine` (as a global), `TableEngine`, `MatchService`, `RoomService`, `SessionService` each have exactly **one live implementation** in `design-ui/`, referenced consistently from the files that need them (plus their inert appearances inside the `Sprint-*-Review`/`Task-*-Review` archive copies, per Finding B).

---

## 6. Production Source-of-Truth Assessment

**A. Production/current implementation:**
- `design-ui/engine/{cards,deck,dealer,bidding-engine,table-engine,scoring-engine,session}.js`
- `design-ui/{match-service,match-adapter,room-service,session-service,player-service,firebase-init}.js`
- `firestore.rules`
- `design-ui/{login,lobby,profile}/index.html` and their (identical) `game-state.js` copies
- `design-ui/match/index.html` — production, but an **explicit, documented placeholder** (no bidding/dealing/gameplay UI yet)

**B. Simulator/test implementation:**
- `tests/*.test.cjs` and `tests/rules-simulation.test.js` — all labeled MOCKED (hand-written fake Firestore + real engine code) or SIMULATED (a 1:1 JS re-implementation of `firestore.rules`' CEL logic, used only to check logical intent — explicitly documented as NOT proof the real rules file compiles against Firebase's actual Rules engine).

**C. Legacy implementation:**
- `design-ui/lobby/session.js` (Finding A — stale, still loaded, not source of truth for anything past Sprint 3.6)
- Everything under `Sprint-*-Review/` and `Task-*-Review/` (Finding B — frozen historical snapshots)

**D. Documentation/reference implementation:**
- `docs/architecture/*.md` (living design docs, re-synced every sprint — trustworthy as of the latest sprint touching each doc)
- `docs/reviews/*.md` (point-in-time sprint reports — trustworthy as history, not as current state; several contain explicit "UPDATE — Sprint X" banners pointing to later corrections)

**E. Unknown/unverified implementation:**
- `src/{App.tsx,utils.ts,types.ts}` (Finding D) — not documented anywhere as part of this project, not referenced by any sprint's docs, and not exercised by any test in `tests/`. Its provenance/intent is unverified by anything in this repository.
- **The Bidding/Gameplay rendering layer** (`render()`, `bindStatic()`, etc. called from `table-engine.js`/`bidding-engine.js`) — **does not exist in this repository at all**. Its existence, location, or even whether it was ever fully built cannot be determined from this repo. See §11.

---

## 7. Git / Branch Status

- **Current branch:** `claude/busy-bohr-ez5rz3`
- **Local branches:** `claude/busy-bohr-ez5rz3` (current), `main`
- **Remote branches:** `origin/claude/busy-bohr-ez5rz3`, `origin/main`
- **`main`:** exactly one commit (`9470422 Initial commit`) — the bare Vite/React/TS scaffold described in Finding D. **None of the Sprint work exists on `main`.**
- **`claude/busy-bohr-ez5rz3`:** contains `main`'s one commit as its root, plus every Sprint commit from `Sprint 2.6` (implied, pre-dating the visible log window) through `Sprint 4.3` — this is genuinely the ONLY branch with any of the real project on it.
- **Is recent Sprint work on a branch only?** **Yes — all of it.** `main` is 379 files and ~86,500 lines behind `claude/busy-bohr-ez5rz3`. If `main` were cloned instead of this branch, you would get the empty Vite template and nothing else.

---

## 8. Sprint 3.4.1 Location

Verified directly via `git log --oneline --all`:

```
25934d7 Sprint 3.4.1: Match Start Consistency & Security Hotfix
```

- **Present in current branch:** **Yes** — this commit is an ancestor of `HEAD` on `claude/busy-bohr-ez5rz3` (confirmed by its presence in `git log --oneline` for this branch, between `08dce31 Sprint 3.4` and `cd406b6 Sprint 3.5`).
- **Present on another branch:** No other branch besides `claude/busy-bohr-ez5rz3` contains it (`main` has only the initial commit).
- **Committed or uncommitted:** Fully committed — the working tree is clean, so there are no uncommitted Sprint 3.4.1 changes sitting on top of it.
- **Exact files the hotfix changed:** available via `git show --stat 25934d7` (not run here, to stay strictly read-only about diffing individual historical commits beyond what's needed for this report) — its own archived deliverable, `Sprint-3.4.1-Review/`, is present in the working tree today and documents its own file list directly (`Sprint-3.4.1-Review/CHANGELOG.md`).
- **Sprint 3.5 also present:** **Yes** — `cd406b6 Sprint 3.5: Deck Implementation & Engine Integration` is the very next commit after Sprint 3.4.1 on this same branch, and its own archived deliverable `Sprint-3.5-Review/` is present in the working tree.

---

## 9. Firebase Configuration

| File | Present? | Location |
|---|---|---|
| `firebase.json` | **No** | — |
| `.firebaserc` | **No** | — |
| `firestore.rules` | **Yes** | repo root |
| `firestore.indexes.json` | **No** | — |
| Emulator configuration | **No** | — |
| Firebase initialization | **Yes** | `design-ui/firebase-init.js` — hardcoded real project config (`projectId: "made---estimation-card-game"`) |
| Auth service | Partial | `firebase-init.js` exposes `window.Auth`; no dedicated `AuthService` file exists yet (design-only, per `docs/architecture/ServiceArchitecture.md`) |
| Firestore-related tests | **Yes** | Every file in `tests/` — all MOCKED (hand-written fake Firestore) or SIMULATED (rules logic re-implemented in JS) |

**Is the repo configured for the Firebase Emulator Suite?** **No.** There is no `firebase.json` (which is what declares emulator ports/config), and every test file's own header comment states explicitly that the Firebase Rules Unit Testing library / Firebase CLI / Java-backed local emulator were never available in the environment these tests were written in, and that no test has ever run against a real emulator or real Firestore. The Firebase SDK is loaded via CDN (`https://www.gstatic.com/firebasejs/10.12.2/...`) directly in each HTML screen — there is no npm-installed `firebase` package in `package.json` at all (confirmed: `firebase` is not listed under `dependencies` or `devDependencies`).

Per the task's own instruction, the emulator was **not started** and Firebase configuration was **not modified**.

---

## 10. Local Development Requirements

### REQUIRED (evidenced directly by repo contents)
- **Git** — to clone the repo and check out `claude/busy-bohr-ez5rz3` (NOT `main` — see §7).
- **Node.js** — this environment runs **v22.22.2**; `package.json` sets `"type": "module"` and the `tests/*.test.cjs` files rely on the `.cjs` extension to force CommonJS regardless — any reasonably recent Node LTS should work, but match this major version if reproducing exactly matters.
- **npm** — this environment runs **10.9.7**, to install `node_modules` from `package-lock.json` for the `src/` Vite app's own toolchain (`vite`, `typescript`, `tailwindcss`, `oxlint`, `playwright`, etc.).
- **A modern browser** — to open any `design-ui/*/index.html` screen directly (they are plain static HTML/CSS/JS with no build step of their own).
- **Internet access at runtime** for any `design-ui/` screen — the Firebase SDK is loaded live from `gstatic.com`, and `firebase-init.js` points at a real, live Firebase project. Without network access, `window.Auth`/`window.Db` will be unavailable and every screen's Firebase-dependent behavior will fail (though most already fail open/gracefully, per this project's own established convention).

### OPTIONAL
- **Firebase CLI** — only needed if you intend to actually deploy `firestore.rules` to the real project or eventually stand up an emulator (neither is configured today — you would be setting this up fresh, not reproducing something already configured here).
- **A local static file server** (e.g. `npx serve`, `python -m http.server`) — convenient for serving `design-ui/*/index.html` over `http://` instead of `file://`, though `firebase-init.js`'s own comment confirms `file://` was explicitly designed to work too.

### NOT NEEDED
- **Java/JDK** — no emulator config exists that would need it.
- **Android Studio / Gradle** — no Android/Kotlin code exists anywhere in this repository (see §3).
- **A bundler for `design-ui/`** — it is deliberately plain `<script>`-tag JS, no Vite/Webpack/etc. involved for that half of the repo (the `vite`/`tsc` toolchain in `package.json` only serves the unrelated `src/` app — see Finding D).
- **Any additional npm package beyond what's in `package-lock.json`** — nothing evidences a missing dependency.
- **Environment variables** — none are referenced anywhere in the codebase (`firebase-init.js`'s config is hardcoded inline, not read from `process.env` or `.env`).

---

## 11. Missing / External Components

1. **The Bidding/Gameplay rendering layer.** `design-ui/engine/table-engine.js` and `design-ui/engine/bidding-engine.js` both call `render()`, `bindStatic()`, and (table-engine only) `showEscalationBanner()`, `flashReject()`, `showRoundDone()`. **None of these functions is defined anywhere in this repository** (confirmed by an exhaustive `grep` for their definitions across every `.js`/`.html` file, excluding the inert `Sprint-*-Review` archive copies of the same two files, which have the identical gap). Consequently:
   - These two engine files are only safe to load **headlessly** (via Node `require()`, as every test in `tests/` does, with `window.addEventListener` stubbed to a no-op so the `DOMContentLoaded` listener that would call `bindStatic()`/`advance()` never fires).
   - If either file were ever loaded in a real browser page today, it would throw `ReferenceError: bindStatic is not defined` (or `render`, etc.) the instant the page's DOM finished loading.
   - `design-ui/match/index.html` — the one real Match screen that exists — does **not** load `table-engine.js`, `bidding-engine.js`, `dealer.js`, `deck.js`, `cards.js`, or `scoring-engine.js` at all. It is an explicit, self-documented placeholder ("Bidding, dealing, and gameplay are not implemented yet — this is a placeholder screen").
   - **Conclusion: the actual visual Bidding screen and Game Table screen do not exist in this repository, whether finished or unfinished.** If they exist at all, they live in a different project/workspace this session has no visibility into.

2. **`design-ui/lobby/session.js`'s missing Sprint 3.7+ features** (Finding A) — not "external," but effectively a gap: the Lobby screen is running against a `GameSession` that lacks remote-match-mirror capability every later Sprint assumed exists.

3. **Any Kotlin/Android artifact.** Zero `.kt` files exist in this repository. If a Kotlin port of the scoring/rules engine exists (as referenced in this session's own prior conversation), it is not part of this git repository.

4. **`src/`'s own provenance.** Nothing in `docs/` explains what `src/App.tsx`/`utils.ts` are for, when they were added, or whether they are meant to be developed further, retired, or removed. This cannot be resolved from repository evidence alone.

---

## 12. Risks

- **Cloning `main` instead of `claude/busy-bohr-ez5rz3` would silently produce an empty project.** `main` has none of the Estimation game's real code.
- **Running `npm run dev` launches the unrelated `src/` React scaffold**, not the Estimation game — someone unfamiliar with this repo's history could easily mistake `src/App.tsx` for "the app," since it's exactly what a fresh `npm create vite` + `npm run dev` surfaces.
- **`design-ui/lobby/session.js` silently diverges from `design-ui/engine/session.js`.** A future edit to the canonical `session.js` that isn't manually mirrored into `lobby/session.js` will NOT be caught by any test (no test in `tests/` loads `lobby/session.js` directly) and will NOT be caught by any lint/build step (no bundler ties these files together).
- **The 4 identical `game-state.js` copies are a manual-sync liability** — nothing enforces they stay identical; Finding A shows this class of drift has already happened once, to a different file, using the same "each screen carries its own copy" pattern.
- **No Firebase Emulator has ever validated `firestore.rules`.** Every SIMULATED rules test is a hand-written JS re-implementation of the CEL logic's intent, not a compiled/executed proof (this gap is explicitly, repeatedly documented in this project's own `SecurityArchitecture.md` — it is a known, accepted, stated limitation, not something this audit newly discovered, but worth restating for someone about to reproduce this locally who might assume the 954 passing tests mean the rules are proven correct against real Firestore).
- **`firebase-init.js` points at a real, live Firebase project with a real API key committed to source control.** Anyone who clones this repo can read valid (if client-side-scoped, as Firebase API keys are designed to be) credentials for the `made---estimation-card-game` project. This is normal for Firebase's own security model (rules, not key secrecy, are the real boundary) but worth being aware of before treating this key as sensitive or rotating it without understanding the implication.
- **The QA-package snapshot directories (`Sprint-*-Review/`, `Task-*-Review/`) will show up in any repo-wide search** for `MatchService`, `BiddingEngine`, etc., and could be mistaken for alternate live implementations if not filtered out (Finding B).

---

## 13. Recommended Next Step

1. Clone the repository and immediately checkout `claude/busy-bohr-ez5rz3` (never rely on `main` for anything beyond historical curiosity about the original scaffold).
2. Treat `design-ui/`, `tests/`, `docs/`, and `firestore.rules` as the ENTIRE reproducible project — ignore `src/`, `index.html` (root), `public/`, `vite.config.ts`, and the `tsconfig*.json` files unless you specifically intend to also work on the unrelated React scaffold.
3. Before writing any new UI for bidding/gameplay, confirm with whoever has access to it whether a separate "Claude Design" project or export (mentioned in this project's own prior session history but absent from this repository) is the intended source for that layer, or whether it needs to be built from scratch against the existing, real, unmodified `table-engine.js`/`bidding-engine.js` reducers.
4. Do not assume the 4 `game-state.js` copies or the two `session.js` copies are safe to edit independently — reconcile `design-ui/lobby/session.js` with `design-ui/engine/session.js` (or explicitly repoint `lobby/index.html` at the canonical file) before building further on top of the Lobby screen.
5. Run the full test suite locally (`for f in tests/*.test.cjs tests/*.test.js; do node "$f"; done`) immediately after cloning, before making any change, to establish your own local "954/954 passing" baseline.

---

## Final Determination

**SINGLE REPOSITORY — CLONE WITH CONDITIONS**

The repository, on the correct branch (`claude/busy-bohr-ez5rz3`, not `main`), is fully self-contained and reproducible for the backend engine, Firestore sync layer, Firestore rules, and test suite — no external repository, submodule, or hidden location is needed for any of that. The conditions are: (a) you must clone the feature branch, not `main`; (b) you must consciously ignore the unrelated, dormant `src/` React scaffold that a naive `npm run dev` would otherwise surface as "the app"; (c) you should not expect a working Bidding/Game-Table UI to be reproducible from this repo alone, since the rendering layer those two engine files depend on does not exist here — that is the one genuinely external/missing component this audit found, not a multi-repository split of the ACTIVE, working system.
