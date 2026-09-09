# Post-R1 / Golden Path Audit + Roadmap — Actual State, Evidence-Backed

**Date:** 2026-09-08 · **Branch:** `claude/r1-verification-ci-audit-t2a0xz` @ `e31ebbb` · **Method:** code reads only, zero code modifications, zero pushes
**Starting point (taken as given per brief, NOT independently re-verified):** PR #8 open/unmerged with 6 commits (`8ca8e74..e31ebbb`); last real emulator run #9 @ `e31ebbb` = 1138 passed / 0 failed, match ran deal→rematch with K.1–K.4, L.1–L.3 PASS; breaking `roundArchive` schema change documented in PR description; untracked files (`docs/postmortem/`, `golden-prod-evidence/`, some tests) not in PR; `INVESTIGATION_CLOSEOUT.md` closed STALE_GAME_STATE + Round-16 race as emulator-only.
**Local verification actually performed:** `git log` (confirms 6-commit chain ending `e31ebbb`), `git status --porcelain` (untracked list), `git diff --stat main...HEAD` (40 files), full reads of `firestore.rules` (all sections incl. 1011–1037, 1202–1213, 1539–1604), `scripts/golden-path.mjs` gate labels, `scripts/run-tests.mjs:44-46`, all 4 workflows, `design-ui/match-service.js:1475-1674,1790-1897`, `design-ui/presence-service.js:1-30`, `docs/architecture/SecurityArchitecture.md:50-93`, `docs/postmortem/2026-08-26-deal-denial.md:1-70` (V-FINAL + proposals), `INVESTIGATION_CLOSEOUT.md` (full), `golden-prod-evidence/findings.json`, `verify-sprint-c-reconnect.cjs:1-35`, commit bodies of `e31ebbb/13dd7bd/4a09081/7d9895d/31b1b28`, `Get-FileHash firestore.rules` = `EAB4F9FA…031679`. No emulator was re-run locally; no CI logs were opened. Anything depending on run #9 internals is marked **per brief (unverified locally)**.

## 0. Glossary (canonical terms used below)

- **R1 dealer-gate:** the production incident fix target — only the legitimate dealer path may `dealRound`; origin `docs/postmortem/2026-08-26-deal-denial.md` V-FINAL (p4 lost first-deal race via ungated `maybeDealRound()`, `match-adapter.js:2077-2086` at the time).
- **Golden Path:** `scripts/golden-path.mjs` — 4 real browser clients vs real Firestore Rules Emulator, full match deal→rematch. Its gate labels (`0.0…L.3b`) are the ONLY gate vocabulary that exists in code.
- **roundArchive:** `matches/{matchId}/roundArchive/{round}` subcollection (id = `String(round)`), one doc per completed round with exactly 52 `cardLog` plays + that round's `biddingLog`, written atomically with the advance/completion (`match-service.js:1615,1871`; rules `firestore.rules:1539-1604`).
- **Per-round window:** post-PR-#8 invariant — parent `cardLog`/`biddingLog` hold ONLY the current round (reset to `[]` on every advance/completion: `match-service.js:1653-1654,1886-1887`; rules require exactly-empty: `firestore.rules:1027-1028,1212-1213`).
- **Ranked-only MVP limitation:** the documented client-authoritative trust boundary (`SecurityArchitecture.md:57-60,68,74-76,86-87`) — acceptable for friends soft-launch, blocker for ranked.

## 1. Gate-by-gate status — GP-01…GP-14 format does NOT exist, harness labels used instead

`grep` for `GP-[0-9]` across the repo returns hits ONLY in a local untracked audit draft, zero in any source/doc/test/workflow. There is no legacy GP-01…GP-14 table to update, so inventing one would violate the brief. The table below uses the harness's own `check()` labels (`scripts/golden-path.mjs`), each verified present at the cited line. Status **PASS (per brief)** = label exists in code + brief states run #9 passed it; local re-execution was out of scope so runtime behavior is explicitly marked as brief-sourced.

### Phase 0–3: auth / room / match-start / seats / deal

| Gate (code label) | Meaning | Status | Evidence |
|---|---|---|---|
| `0.0` All 4 SDKs loaded | 4 real pages load firebase compat | PASS (per brief) | `scripts/golden-path.mjs:501` |
| `0.1` 4 distinct Auth uids | real emulator signup | PASS (per brief) | `scripts/golden-path.mjs:510` |
| `1.1` P1 creates room | `RoomService.createRoom` | PASS (per brief) | `scripts/golden-path.mjs:518` |
| `1.2` identical membership | 4 clients see same `players[4]` | PASS (per brief) | `scripts/golden-path.mjs:525-527` |
| `2.1` match started via all-ready | `setReady`×4 → `maybeStartMatch`, creator last by design | PASS (per brief) | `scripts/golden-path.mjs:537-545` |
| `2.2` correct seat p1..p4 | no cross-assignment | PASS (per brief) | `scripts/golden-path.mjs:585-587` |
| `2.3` agree same matchId | same matchId on all 4 | PASS (per brief) | `scripts/golden-path.mjs:588-589` |
| `3.1` hand-authority firestore | `getHandAuthorityMode()==="firestore"` | PASS (per brief) | `scripts/golden-path.mjs:607-608` |
| `3.2` 13-card authoritative hand | 13 cards per client, STOP gate on fail | PASS (per brief) | `scripts/golden-path.mjs:609-610` + stop `:615-622` |
| `3.3` 52 unique, no leak | one real shuffle | PASS (per brief) **with documented prior FAIL**: prod run 2026-08-26 stopped here `unique=41/52` | `scripts/golden-path.mjs:613` + `golden-prod-evidence/findings.json:3-4` + `golden-prod-evidence/evidence.jsonl` STOP line |
| `3.4` P1 cannot read P2 hand | direct `hands/p2` get denied by rules | PASS (per brief) | `scripts/golden-path.mjs:633-634` |
| R1 dealer-gate (no dedicated label) | deal path is dealer-gated + idempotent `ALREADY_DEALT` | **NAMING GAP, behavior covered implicitly via 3.1–3.3** — no `R1`/`dealer` label exists in harness (verified by grep); incident root is ungated `maybeDealRound()` per `docs/postmortem/2026-08-26-deal-denial.md:12-20` | gap → P1-2 |

### Phase 4–5: bidding + tricks + rounds

| Gate | Status | Evidence / constraint |
|---|---|---|
| `4.1` Round-1 bidding via real path | PASS (per brief) | `scripts/golden-path.mjs:642` via `driveBidding` (`:275-307`) accepting only `BiddingEngine.canSubmit().legal` (`:244-245`) — Dash/Auction/Confirm/Forbidden-13/Call-Cap covered **implicitly through the oracle, no named per-rule assertions** |
| `4.2` Round-1 13 tricks via real path | PASS (per brief) | `scripts/golden-path.mjs:649` via `driveRoundCardPlay` (`:338-447`) accepting only `TableEngine.canPlayCard().legal` (`:320-321`) — follow-suit covered **implicitly, not explicitly** |
| `4.3` converge Round 2 | PASS (per brief) | `scripts/golden-path.mjs:660-661` |
| `4.4/4.5` Round-2 real hands + 52 unique | PASS (per brief) | `scripts/golden-path.mjs:672-676` |
| Per-trick `next turn owner is correct` | PASS (per brief) **except trick 13, whose meaning changed in `e31ebbb`** | `scripts/golden-path.mjs:433-434`; trick 13 bypasses the check (`:428`) and accepts advance-as-settlement (`:140-158`, `TRICK_SETTLEMENT_VIA_ADVANCE :156`) because `advanceToNextRound` verified 52/52 pre-reset (`match-service.js:1599-1604`) |
| Per-trick `Firestore/local agrees` | PASS (per brief) | `scripts/golden-path.mjs:435-436` |
| `Round NN — 13 tricks` + `NN→NN+1 convergence (live maxRounds=…)` | PASS (per brief) to completion | loop `rn=2..30` (`:705`), safety cap 30 (`:701`, via `8ca8e74`), live `maxRounds` re-read each boundary (`:737`); covers extensions 14–18 |
| `ROUND_WINDOW_BASELINE` / `TRICK_SETTLEMENT_DIAGNOSTIC` / `ACTOR_PAGE_NOT_CONVERGED` | Log-only diagnostics, no verdict change | `scripts/golden-path.mjs:347-353,161-177,378-395`; commit `13dd7bd` body states "log-only, no behavior change" |

### Phase 6–7: completion + rematch (K/L)

| Gate | Status | Evidence |
|---|---|---|
| `K.1` status complete authoritative | PASS (per brief) | `scripts/golden-path.mjs:779-780` |
| `K.2` same final scores on all 4 | PASS (per brief) — **consistency only, NOT correctness** (string-compared across clients; `endMatch` checks internal consistency `winnerIdsMatchFinalScores` `match-service.js:1859`, rule checks winner==max only `firestore.rules:1188-1225`; wrong-but-consistent scores undetectable per `match-service.js:1794-1808`) | `scripts/golden-path.mjs:790-792` |
| `K.3` same winner(s) | PASS (per brief) — same constraint as K.2 | `scripts/golden-path.mjs:793-795` |
| `K.4` post-completion write rejected | PASS (per brief) — **partial**: single `submitCard` attempt from p1 only, gated client-side (`phase!=="PLAY"`) before rules | `scripts/golden-path.mjs:798-817` incl. comment `:799-805` |
| `L.1` createRematchVote via real path | PASS (per brief) | `scripts/golden-path.mjs:828` |
| `L.2` four real YES votes | PASS (per brief) | `scripts/golden-path.mjs:839` |
| `L.3` NEW matchId via live watcher (no manual `createRematchMatch`) | PASS (per brief) | `scripts/golden-path.mjs:858-859` |
| `L.3b` fallback (`NEW_MATCH_CREATED` + `newMatchId`) | **UNVERIFIED** — code exists (`:874-875`) but only fires if L.3 fails; no run evidence it ever executed | `scripts/golden-path.mjs:874-875` |
| Negative guard `Phases 6/7 NOT REACHED, do not fake unreached phases` | Methodologically sound, but hides K/L entirely on early-stop runs instead of failing them | `scripts/golden-path.mjs:879` |

## 2. Coverage gaps outside the Golden Path — `ranked-only` classification STILL HOLDS

- **cardLog legality (follow-suit, Forbidden-13, …): still client-authoritative by design; PR #8 did not close it.** Client pre-write gates are real (`assertLocalTurn` `match-service.js:953`; `TableEngine.canPlayCard()` `:1086`; `BiddingEngine.canSubmit()` `:1379` + `STALE_GAME_STATE` `:1395`). Rules check shape/owner/`version+1`/`+1 growth` only, with explicit honest limitations: no re-verification of earlier entries (`firestore.rules:669-695`), no correct-next-turn check (`:778-788`), no bidding turn-ownership at all (`:884-902` + prefix note `:872-882`). Classification `client-authoritative, MVP-only — not suitable for ranked` (`SecurityArchitecture.md:57-60`, traceable to `CardAuthorityHotfix_4.2.1.md:48` pattern) is **accurate today**. Golden bots pick the first oracle-legal candidate (`golden-path.mjs:244-245,320-321`) — proves "honest play completes", NOT "cheating writes are impossible".
- **Score/hand content: still ranked-only limitations.** `endMatch` consistency-only (`match-service.js:1794-1808` states wrong-but-consistent scores undetectable); deal rule checks shape/round/pairing but hand CONTENT is unverifiable by design (`SecurityArchitecture.md:72-76` — any seated member can choose shape-valid hands for all seats); turn check `oldData.turn==uid` is inert for the first card after every trick past the first (`turn==null`; documented `SecurityArchitecture.md:68`); K.2/K.3 compare clients to each other, never to ground truth.
- **roundArchive has ZERO production consumers — purely theoretical today; DO NOT build one now.** Writers only: `match-service.js:1615,1871` + rules `1539-1604`; zero production readers (verified by repo-wide `roundArchive` grep; tests `round-lifecycle.test.cjs:311-326`, `match-completion.test.cjs:341-343` and `rules-simulation.test.js:2096-2144` are test-only). Code self-documents: "TODAY nothing reads it … forensics + future review screen" (`match-service.js:1523-1526`; `firestore.rules:1557-1562`). No "review rounds" screen/route/UI exists. Recommendation: **postpone** (P2-1) — archive is a size pressure-valve (proven by 2 CI runs per `7d9895d` body), not a product feature; a review screen adds read/privacy surface (any seated player may `get` any archive, `firestore.rules:1599`) with no product request and an open privacy question (may opponents see old rounds' cards?).
- **CI gap is REAL and merge-relevant (not cosmetic).** (1) `test.yml:17-20` triggers only on `claude/busy-bohr-ez5rz3` + `main` — current PR branch gets NO CI on push. (2) Even when triggered, the runner collects `tests/*.cjs|*.test.js` only (`run-tests.mjs:44-46`) — `scripts/golden-path.mjs` is never discovered, and `test.yml:47-54` has no golden step. (3) `r1-golden-verification.yml:11` is `workflow_dispatch` manual-only — no merge protection. The 1138/0 figure therefore comes from a manual/local run (run #9 per brief), never from an automated merge gate. PR #8's "far from Golden Path coverage" description is **confirmed code-accurate**.

## 3. Residual risks

- **R-A. Honest limitation: advance WITHOUT archive is allowed (`firestore.rules:1570-1578`).** Reverse direction unenforced — a client can advance with empty logs, skipping history (gameplay unaffected; engines score locally per round). **Likelihood: low-medium** — requires deliberate raw-SDK write by a seated member; normal UI/adapter always archives (`match-service.js:1657,1879`). **Impact: low on play, high on audit/disputes** (missing round can never re-derive trick winners; future review screen would show gaps). **Verdict:** acceptable for friends-MVP, must be recorded as a ranked-blocker with the rest of the trust boundary; no Cloud Function warranted (same Spark rationale as `SecurityArchitecture.md:75`).
- **R-B. Other races from commit bodies — emulator-only vs production:**

| Race | Verdict | Evidence |
|---|---|---|
| STALE_GAME_STATE ~Round 9 (closed track) | Emulator-only — **stays closed**, no prod fix | `INVESTIGATION_CLOSEOUT.md:9-17` (real-Firestore 0ms convergence 3/3); harness retry (`golden-path.mjs:401-406`) is harness-only, consistent not contradictory |
| Round-16 `extendMatchRounds` 4-client race (closed track) | Emulator-only — **stays closed** | `INVESTIGATION_CLOSEOUT.md:21-29` (real-Firestore 12/12, zero denials); PR #8 never touched `extendMatchRounds` — consistent |
| archive+advance contention at every round boundary (4 clients) | **PRODUCTION-REAL; `4a09081` is a production fix**, not emulator-only | body of `4a09081` (denials reproduced 3/3 locally, single winner, `{round,version}` retry guard `match-adapter.js:1869-1888` per body); "any client may attempt" design (`match-service.js:1450-1454` pattern) guarantees contention every boundary on any backend; bounded retry prevents deadlock but the window remains |
| trick-13 settlement race (advance beats settlement poll) | **PRODUCTION-REAL timing window** | `e31ebbb` body + `golden-path.mjs:140-158`: parent jumps 51→0 on racing advance; new acceptance is justified (advance verified 52/52, `match-service.js:1599-1604`) but **weakens assertion independence** (same writer attests for itself) — fine for harness, must not be sold as double-verification |
| double-deal race (production incident root) | Production-real, verified — **current mitigation is partial, structural window remains** | postmortem V-FINAL §§V/1-5 (P1 won, P4 correct-denial ~95%); proposed P0-1 dealer-gate for `maybeDealRound` (`:48`) was **NOT implemented** in PR #8; reliance is on idempotency + `ALREADY_DEALT` |
- **R-C. Areas needing Golden-Path-grade rigor but lacking it:** **Reconnect mid-match — NOT covered.** `verify-sprint-c-reconnect.cjs:1-30` is effectively abandoned (hard-coded `ROOT=/home/user/demo-test`, `PROJECT_ID=demo-test-sprintc`, `CDN=/tmp/fb-cdn-cache` `:21-27` — broken on clean checkout; outside CI; deliberately single-client deal "to avoid unresolved emulator behavior" `:10-14`). Adapter's registry-rebase-on-shrink (`match-service.js:1516-1519`) has no updated E2E proof after the window change. **Rematch: covered** (L.1–L.3) except `L.3b` fallback (unverified) and `verify-rematch-vote*.cjs` Playwright files outside CI. **Presence/abandonment: zero coverage** — `presence-service.js` is 100% stub (`notImplemented` for every method, `:14-24`). **Post-completion trust:** K.4 is one client-side attempt; no rules-level raw-REST post-`complete` test, no wrong-but-consistent score-forgery test (accepted-today by design).

## 4. Untracked files — keep vs delete (`git status --porcelain`, 9 `??` entries + evidence dir)

| Path | Verdict | Reason |
|---|---|---|
| `docs/postmortem/2026-08-26-deal-denial.md` (~27K) | **Separate docs-only PR — KEEP** | Sole closed record of prod incident: V-FINAL + P0-1..P3-7 proposals (`:44-54`), self-marks superseded sections (`:65-66`) |
| `docs/postmortem/h-matrix-real-backend-runbook.md` (~2.5K) | **Same PR as above — KEEP** | Runbook for decisive Cell-C experiment; valueless alone, completes context together |
| `tests/h-matrix.real-backend.cjs` (~19K) | **Same docs PR with guard — KEEP** | Hard-guards game project + no-ops without config; safe, useful for future precedence re-measurement |
| `tests/repro-deal-denial.rules-emulator.test.cjs` (~30K) | **Separate tests PR — KEEP conditionally** | H2b/H2c emulator-ordering matrix prevents regression; condition: clearly labeled emulator-only (does so) and proven to run under `test:ci` without emitting `SKIPPED` (runner hard-fails on that word, `run-tests.mjs:71`) |
| `tests/dealer-derivation.determinism.test.cjs` (~9K) | **With tests PR — KEEP** | Lightweight mocked determinism vs `uidToSeat`; permanently closes non-deterministic-dealer question |
| `scripts/concurrency-probe.mjs` (~4K) | **MOVE to `tests/` or DELETE — must not stay in `scripts/`** | Deliberately isolated from golden; in `scripts/` CI never discovers it; as `tests/*.cjs` it becomes automatic; if no permanent gate wanted, delete (idea preserved in closeout) |
| `golden-prod-evidence/evidence.jsonl` (~16K/28 lines) | **Separate evidence PR (or LFS) — KEEP, never delete** | Sole raw incident trace (START 06:14:07 → STOP unique=41); postmortem cites its lines |
| `golden-prod-evidence/findings.json` (161B) | **With evidence PR — KEEP** | One-line FAIL fixture (`3.3 unique=41`); trivial size, high value |
| `golden-prod-evidence/lobby-p1..p4.png` (4 images) | **DELETE from git, keep externally** | Ordinary lobby screenshots, zero forensic content (incident was deal-phase); binaries don't belong in history; CI already uploads artifacts (`r1-golden-verification.yml:51-58`) |
| `docs/postmortem/live-rules.today.txt` (115K) + `rules.yesterday.txt` (114K) | **DELETE, replace with hashes** | Full duplicate rules copies for one byte-diff; keep SHA256 + disputed lines inside postmortem instead of ~230K duplication |
| `predicate-evaluation.json` / `round16-rule-audit.json` / `rule-reproducer.js` | **Out of scope** — already tracked, per `INVESTIGATION_CLOSEOUT.md:47`; untouched here | Provenance tooling for closed tracks |
| **Rule for all follow-up PRs:** never mix evidence/docs with `firestore.rules` + `match-service.js` (breaking schema lives in PR #8 alone); each PR: its files only + `node --check` + local `test:ci`. |

## 5. Contradictions requiring OWNER decision (flagged, NOT resolved here)

1. **`e31ebbb` body "Not yet re-verified against a live emulator run … recommend re-running" vs brief's run #9 1138/0 on the SAME SHA.** Both cannot hold with the same meaning. Likely: message written pre-run, run came after. **Decision needed:** pin run URL + evidence artifact proving 1138/0 @ `e31ebbb` before merge; else re-run `r1-golden-verification.yml` manually on `e31ebbb` and attach fresh evidence.
2. **`cardLog` "append-only NEVER cleared" in 5+ places vs new `cardLog: []` per-round reset.** Stale: `match-adapter.js:129,261,1503,1774`, `match-service.js:469-487`, `Sprint_RoundLifecycle_Architecture_Report.md:30`, premise of `SecurityArchitecture.md:57-60`. New behavior: `match-service.js:1653-1654,1886-1887` + rules `1027-1028,1212-1213`. **Decision:** update all stale spots to "per-round window + archive" (recommended) or revert invariant (reintroduces 500s) — no safe middle.
3. **`INVESTIGATION_CLOSEOUT.md:35-41` "nothing was modified" vs PR #8 modifying the same files.** Not a true contradiction (closeout scopes STALE/Round-16 only) but will mislead future readers. **Decision:** one clarifying line in closeout or PR description: "this PR does not reopen the two closed tracks; it fixes stored-array-size + advance-guard + trick-13-harness".
4. **Frozen `deploy-production.yml:29` SHA vs any rules change.** Live case: current `firestore.rules` hashes `EAB4F9FA…031679` (measured) ≠ expected `3ceb49…f557b` — post-merge deploy FAILS by design until updated. **Decision:** keep manual SHA bump per change (status quo) or adopt release-hash assertion (postmortem P2-6 `:52`) — decide before merge.
5. **Out-of-scope engine notes (no action here, recorded so they aren't conflated):** fast-round Caller/With + Golden Super Call reset + `src/` scoring divergence (`PROJECT_STATUS_AND_MASTER_PLAN.md:60,108-110,59,115-116`) — full-match 1138/0 evidence was produced WITH current engine behavior; touching the engine now invalidates that evidence. Separate engine track, never mixed into PR #8.

## 6. Roadmap (evidence-first; sizes S/M/L, no timeframes per constraints)

### P0 — blocks correct merge/deploy (before merging PR #8)
- **P0-1. Pin run #9 evidence + settle contradiction §5.1 — S.** Steps: (1) fetch workflow run URL + `r1-golden-evidence` artifact (`evidence.jsonl`+`findings.json` per `r1-golden-verification.yml:51-58`), confirm `passed==1138 && failed==0` at HEAD `e31ebbb`; (2) paste link+SHA into PR #8 description; (3) if unobtainable, `workflow_dispatch` on the branch and attach the new run; (4) no history rewrite — PR comment only.
- **P0-2. SHA guard + rules-deploy decision — S.** Steps: (1) post-merge, hash `main`'s `firestore.rules`; (2) update `expected=` (`deploy-production.yml:29`) in a tiny follow-up PR; (3) actual publish exclusively via owner Console flow (required by `firestore.rules:14-17`); (4) verify live==repo (same R1c byte-diff procedure, postmortem `:81`).
- **P0-3. CI branch + golden-path merge protection — S.** Steps: (1) add `claude/r1-verification-ci-audit-t2a0xz` (or `claude/**`) to `test.yml:17-20`; (2) keep long golden OUT of `test.yml` (keep `test:ci` fast) — instead add a `pull_request` trigger (optionally `paths: [firestore.rules, design-ui/**, scripts/golden-path.mjs]`) to `r1-golden-verification.yml`; (3) prove with one trial push showing a real red/green signal.

### P1 — correctness/safety/coverage (immediately after merge)
- **P1-1. Fix append-only→window doc drift — S.** Steps: (1) rewrite the §5.2 stale spots to per-round-window + limits (≤52 + dozens ≈10KB, `match-service.js:1497-1500`); (2) update `SecurityArchitecture.md:57-60` to scope prefix-integrity gap to within-window (full-round rewrite still theoretically possible; archive itself is write-once, `firestore.rules:1601-1603`); (3) final `grep append-only` sweep.
- **P1-2. Name the R1 dealer-gate in the harness — S.** Steps: (1) add explicit `check("R1 …")` after `3.3` asserting `dealtRound==1` + 4 `hands` docs + existing `3.4` denial (rename to R1.x); behavior-neutral, naming-only so reports match the mission.
- **P1-3. Resurrect reconnect E2E for the window world — M.** Steps: (1) derive `ROOT`/`PROJECT_ID`/`CDN` in `verify-sprint-c-reconnect.cjs` from repo/env like golden (`__REPO_ROOT__`, `EVIDENCE_DIR`, `golden-path.mjs:9-25`) instead of hard-coded `:21-27`; (2) scenarios: drop mid-round after trick 6 + return mid-round, and return post-advance (exercises registry rebase `match-service.js:1516-1519`); (3) move into `tests/` or wire to manual workflow; (4) strict `check()` PASS/FAIL like golden.
- **P1-4. Narrow the every-boundary advance contention — M.** Steps: (1) evaluate dealer-only advance (mirrors deal P0-1, postmortem `:48`) vs keep-any-client + current retry (`match-adapter.js:1869-1888` per `4a09081` body); (2) document choice at the "any client may attempt" site; (3) add a true 4-client exactly-once-archive race test (existing `round-lifecycle.test.cjs:311-326` covers sequence, not a real race); (4) no Cloud Function (Spark constraint).
- **P1-5. Land high-value untracked files as 3 small PRs — S each.** PR-A docs (postmortem+runbook+h-matrix, §4); PR-B tests (repro+determinism, prove `test:ci` with zero `SKIPPED`); PR-C evidence (`evidence.jsonl`+`findings.json`) + delete images + rules snapshots (§4).

### P2 — hardening, explicitly deferrable
- **P2-1. "Review rounds" archive screen — L, DEFER.** No product request + unresolved opponent-visibility privacy question (§2). If ever requested: read-only + pagination + privacy ruling first, code second.
- **P2-2. Negative rules-level tests (K.4++, score-forgery, advance-without-archive) — M, after P1.** Raw-REST post-`complete` write (expect rules DENY, not just client-side); consistent-but-wrong `finalScores` (expect ACCEPT today — document, don't fix); advance-without-archive (expect ACCEPT today per R-A).
- **P2-3. Automate SHA assertion (release-hash) — S, when deploy breaks repeat.** Proposal exists (postmortem `:52` P2-6); not now.
- **P2-4. Real presence/abandonment — L, explicit defer.** `presence-service.js` is full stub; build heartbeat/timeout only on real product complaint, never speculatively.

## 7. What stays as-is (positive confirmations)

- STALE_GAME_STATE and Round-16 race **remain closed** (`INVESTIGATION_CLOSEOUT.md:5,49-54`); PR #8 does not reopen them (R-B table). No action.
- `src/` legacy scoring divergence and fast-round engine items are **out of scope** here by design (would invalidate the 1138/0 evidence). Separate track.

## 8. DoD for this audit (per task constraints)

- [x] Zero code edits, zero pushes (reads + `git status`/`git log`/hash measurements only)
- [x] Every claim cites evidence (file:line, commit hash, or measured value); unprovable items labeled **UNVERIFIED** (L.3b, run #9 internals)
- [x] Contradictions presented as owner decisions (§5), never unilaterally resolved
- [x] No invented GP-01…GP-14 scheme — absence proven, harness labels used as basis
- [x] Delivered as a markdown file with no commit/push (this file)

## 9. Open questions for the owner (grilling frontier — decisions, not facts)

1. Merge PR #8 as-is once P0-1 evidence is pinned, or require P0-3 CI protection to go green first? (Recommended: pin evidence + land P0-3 trigger in the same merge window.)
2. Accept advance-without-archive (R-A) as a permanent ranked-blocker, or schedule P2-2 negative-test documentation now? (Recommended: accept + document.)
3. Dealer-only advance vs any-client+retry (P1-4) — which authority model do you want permanently? (Recommended: decide on paper before any code.)
4. Review-rounds screen: build ever, or kill explicitly? (Recommended: kill for now, revisit on product request.)

*End of report.*
