# 2026-10-23 — Owner direction: persisted multiplier (Option B), 18-round base, S2 ownership, Git policy, migration and cleanup

**Status: RECORDED — O-19 and O-20 CLOSED 2026-10-23; archive deletion EXECUTED; everything else is
documentation or planned work.** Source: the owner's message of 2026-10-23 (seven sections) and the
follow-up decision message of 2026-10-23 authorizing O-19 and ratifying O-20, following
`docs/decisions/2026-10-23-final-owner-answers.md` (Q51/Q52). Nothing in this record claims a code change,
a rules change, a move, a deletion or a test run unless that section says **EXECUTED** and names the
verification.

Labels: **[D]** owner-confirmed · **[R]** recommendation, not approved · **[O]** open question ·
**[V]** assumption that needs code verification · **[G]** confirmed implementation gap ·
**[X]** executed and verified · **APPROVED — NOT EXECUTED** · **VERIFIED — NOT EXECUTED**.

Companions: `docs/decisions/2026-10-23-final-owner-answers.md` (Q51–Q52);
`docs/decisions/2026-10-22-final-decision-sync.md` (Q35–Q50); `docs/decisions/OPEN_QUESTIONS.md`;
`docs/rules/CANONICAL_RULES.md` A8.7/A8.8; `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.10.

---

## 1. Online General Pass — Option B: persist the multiplier [D]

### 1.1 Decision

The owner adopted **Option B**: the next-round multiplier is a **persistent field in the saved match
state**, not a value reconstructed by each client from a transient sync message. The purpose is continuity
across **redeal, reconnect, app reload and cold start**, for both game types:

- **Classic:** a General Pass consumes the round with **no multiplier** (always ×1).
- **Normal:** a General Pass opens/continues the ladder **×2 → ×4 → ×6 → ×8**, capped ×8 (A8.7, Q1b).
- The Sa'ayda ladder and the pass ladder are the **same carried value**; a pass after a Sa'ayda continues
  from the carried multiplier, and a Sa'ayda after a pass continues from the pass's value.

The field is **not** a substitute for the rule work: the pass must still advance the round number, rotate
the dealer, deal fresh cards, and complete the match when the pass consumes the planned final round
(A8.7). It is the persistence half of S1.

### 1.2 Where the field belongs — candidates

| Option | Where | Verdict |
|---|---|---|
| **A (recommended)** | Root field `currentMultiplier: Int` on `matches/{matchId}` | **Recommended.** One value, written in the same transaction that advances the round; read by every client on bootstrap; no extra query; survives reconnect/reload/cold start. |
| B | `roundArchive/{round}.multiplier` | Rejected. The archive is written **after** a round; the next round needs the value **before** it starts. Reading it on cold start needs an extra query and the newest archive. A General Pass round has no 52-card archive today. |
| C | Dedicated `matchState/multiplier` document | Rejected. An extra document, an extra read on every cold start and an extra transaction, for one integer. Adds failure modes without a benefit over A. |
| D | Derive it by replaying every `roundArchive` | Rejected. O(rounds) reads on every cold start, duplicated scoring logic in every client, and a pass round is not currently archivable. |

**Recommended name and meaning.** `currentMultiplier` = **the multiplier at which the current round will be
scored**. It is written when the match is created (1), when a scored round advances (the engine's
`RoundScoreResult.nextMultiplier`), and when a General Pass advances (the engine's
`EmitResult.GeneralPass.doubledMultiplier`). It must not be confused with:

- `BiddingState.roundMultiplier` — the multiplier of the round **being bid**, which describes the round
  just cancelled during a pass (Agent 1's uncommitted engine keeps this value unchanged).
- `RoundScoreResult.nextMultiplier` — a transient computed value; Option B persists it.
- `BidSyncDoc.generalPassMultiplier` — Agent 3's **uncommitted transport hint**. It is not persisted match
  state and cannot be the source of truth; it may remain as an optimisation after Option B lands.

### 1.3 Source of truth and the full reader/writer inventory

**Source of truth:** the match document's `currentMultiplier`, mirroring the engine's
`RoundState.multiplier`. The engine remains the scoring authority; the document is the **persistence and
bootstrap authority**. No component may reset the value to 1 on its own when opening a match.

Every site that reads, writes or persists the multiplier (verified in the baseline tree at `e21b515`;
Agent branches are uncommitted and not accepted):

| Layer | Site | Today | Required with Option B |
|---|---|---|---|
| Engine | `Bidding.kt:493-508` (baseline) | Always doubles, no game type | Emits the pass value; Agent 1's uncommitted diff adds `ScoringMode` and the ×2→×4→×6→×8 ladder |
| Engine | `Session.kt:480-490` `completeRound` | Sets `round.multiplier = nextMultiplier` | Unchanged; the caller persists the same value |
| Engine | `Session.kt:337-352` `nextRound()` | Preserves `multiplier` | Unchanged; the pass caller must set the new value explicitly |
| Engine | `Session.kt:67-76` `RoundState` | `multiplier = 1`, `maxRounds = 18` | Unchanged |
| Services | `MatchShapes.kt:100-110` `buildMatchFields` | No multiplier field | Write `currentMultiplier = 1` at creation |
| Services | `MatchModels.kt:206` `MatchDoc` | No multiplier field | Add `currentMultiplier` with a default of 1 for old docs |
| Services | `MatchService.kt:631` `advanceToNextRound` | Writes `currentRound + 1`; no multiplier | Write the scored round's `nextMultiplier` atomically |
| Services | `MatchService.kt` (no method today) | No pass advance | New `advanceAfterGeneralPass` transaction (see §1.5) |
| Services | `MatchAdapter.kt:168-171`, `:820-850` (baseline) | Pass treated as an ordinary applied bid | Detect `GeneralPass`, carry the value, trigger the pass advance |
| App (offline) | `QuickMatchViewModel.kt:222` | Re-deals the same round, always doubles | Advance the round and set the pass value (Agent 3's uncommitted work) |
| App (online) | `OnlineMatchViewModel.kt:544-560` `bootstrap` | `RoundState(number, maxRounds)` ⇒ multiplier **1** on cold start | Seed `currentMultiplier` from the doc |
| App (online) | `OnlineMatchViewModel.kt:568-590` `alignToDoc` | `nextRound()` preserves the local value; a missed pass leaves it stale | Reconcile from the doc before/after advancing |
| App (online) | `OnlineMatchViewModel.kt:593-614` `initAuction` | Reads `session.getRound().multiplier` | Reads the value seeded from the doc |
| App (online) | `OnlineMatchViewModel.kt:692-696` (Agent 3, uncommitted) | Patches the multiplier from a sync hint | The hint is no longer the authority; read the doc |
| App (online) | `OnlineMatchViewModel.kt:846-885` `scoreAndAdvance` | Uses `cfg.multiplier`; writes no multiplier | Persist `result.nextMultiplier` with the advance |
| Functions | `Settlement.kt:517-524` (baseline) | Re-inits the same round with the doubled value | Read the doc value; reject a mismatch |
| Functions | `TimeoutResolution.kt:979-984` (baseline) | Re-inits the same round with `ROUND_MULTIPLIER_DEFAULT` | Read the doc value |
| Rules | `firestore.rules` create allowlists (`:605`, `:1573`) | No multiplier key allowed | Add `currentMultiplier` |
| Rules | `firestore.rules:1180-1181` round advance allowlist | No multiplier key allowed | Add it and validate the range |
| Rules | `firestore.rules` (no pass branch today) | A pass cannot advance the round | New branch (§1.5) |
| Tests | engine/app/services/functions/rules suites | Encode the same-round model and the cold-start reset | New continuity and rules-denial tests (§1.7) |

### 1.4 Continuity matrix

| Scenario | Behaviour today | Required with Option B |
|---|---|---|
| Normal pass, the four clients are connected | Offline advances; online ignores the pass | All clients read `currentMultiplier` written with the advance |
| Classic pass | Offline doubles (defect); online ignores | `currentMultiplier = 1` everywhere |
| Reconnect in the middle of the next round | Online `bootstrap` starts at ×1 | Bootstraps at the doc value |
| App reload (process restart) | Same cold-start reset | Same doc value |
| Cold start after several passes | ×1 | The persisted value (×2/×4/×6/×8) |
| Cold start after a Sa'ayda extension | ×1 | The persisted Sa'ayda value |
| Server settlement/replay | `ROUND_MULTIPLIER_DEFAULT` | The doc value; a mismatch is CORRUPT/INCOMPLETE, never silently ×1 |

### 1.5 Firestore Rules impact — **AUTHORIZED 2026-10-23** [O-19 CLOSED]

**Owner decision (2026-10-23): APPROVED, with strict scope limits.** The owner authorized the minimal
rules and online match-flow change required by Option B. The source of truth is confirmed as
`matches/{matchId}.currentMultiplier`, mirrored by `RoundState.multiplier`. This is an authorization to
implement on a dedicated feature branch; **no rules edit has been made yet** (status: NOT STARTED, §9).

Option B **cannot** be implemented under the current rules without a rules change. This is a hard finding,
not an assumption:

1. The round-advance update allowlist (`firestore.rules:1180-1181`) has no multiplier field, so an advance
   that carries `currentMultiplier` is denied.
2. The match-create allowlists (`:605`, `:1573`) have no multiplier key, so a client-created match cannot
   seed it.
3. `advanceToNextRound` (`MatchService.kt:604-640`) requires `roundCardCount == 52`, and the
   `roundArchive` create rule (`firestore.rules:1862-1891`) requires `cardLog.size() == 52`. A General
   Pass round has **no card plays**, so it can neither use the existing advance transaction nor create a
   normal archive.

**Authorized minimal rules surface (binding implementation scope):**

- Add `currentMultiplier` to both match-create allowlists and to the round-advance allowlist.
- Validate `newData.currentMultiplier is int`, `>= 1`, `<= 8`; Classic (`scoringMode == 'CLASSIC'`)
  requires exactly 1.
- Add a **General Pass advance branch** that requires: the caller is a player; the match is not complete;
  `oldData.currentRound == the request's completed round`; `oldData.biddingLog.size() == 4` with exactly
  four distinct seats, every entry `isPass == true` and `round == oldData.currentRound`; `version + 1`;
  `currentRound + 1`; the same dealer rotation and log/bid resets as a normal advance; and
  `currentMultiplier` equal to the rules-computed ladder (Classic 1; Normal 1→2, else `min(old + 2, 8)`).
- **Pass archive:** the owner resolved the open variant — update `roundArchive` validation to accept a
  legitimate pass-terminated round (empty `cardLog`, four all-pass `biddingLog` entries, a pass marker)
  **without weakening validation for ordinary rounds**. The ordinary 52-card invariant stays mandatory.
  Option (b), "no archive for a pass round", is rejected: it would lose replay evidence.

**Owner-imposed work requirements (acceptance gates):**

1. Field allowlists and validations for `currentMultiplier`.
2. A narrowly scoped General Pass advance path that does not require 52 cards for a cancelled pass round.
3. Round-archive validation accepts a legitimate pass round without weakening ordinary-round validation.
4. Classic stays ×1; Normal keeps the Double after a General Pass.
5. Legacy match documents without `currentMultiplier` fall back compatibly, with explicit tests proving
   the fallback.
6. Persistence proven through reload, reconnect and cold start, including the Sa'ayda continuity case.
7. Firestore Emulator tests covering accepted and rejected cases: invalid multiplier values, unauthorized
   fields, ordinary 52-card rounds, and valid pass-round archives.

**Execution constraints:** dedicated feature branch; no weakening of unrelated security rules; report the
exact diff, rules tests, engine/service tests, failures and skipped tests; no acceptance claim without
evidence. **Agent 4's S2 scope is unchanged** — the multiplier implementation is not added to it.

### 1.6 Scope and risks

**In scope:** the field, the pass advance, the readers/writers above, the rules branch, the tests below.
**Out of scope:** changing the scoring formula, the Sa'ayda formula, the ×8 cap, Classic's lack of a
multiplier, or any other match field.

Risks:

1. **Trust boundary:** rules cannot re-verify a *scored* round's Sa'ayda status without replaying cards, so
   the scored-round advance can only bound the value (1–8, Classic 1). The pass branch can fully verify the
   ladder from the four PASS entries. State this in the rules comments.
2. **Archive invariant:** a pass round with zero cards breaks the current "every archive has 52 cards"
   assumption if the archive variant is chosen. Readers (`Settlement`, `TimeoutResolution`, any archive
   audit) must be checked for a 52-card assumption.
3. **Dealer rotation on a pass:** the rules text does not state it; the offline S1 implementation rotates
   the dealer because the pass starts a new round. This is recorded as **[V]** and must be tested in both
   paths; if the owner wants the same dealer, that is a rule change, not an implementation detail.
4. **Final-round pass:** a pass on the planned final round completes the match (A8.7). The pass handler must
   call the completion path, not just advance; otherwise the match sits at `currentRound = maxRounds + 1`
   without a result.
5. **Agent 3's uncommitted transport hint** (`generalPassMultiplier`) can mask the cold-start defect in a
   test that never restarts the VM. The acceptance test must restart from the document, not reuse the VM.

### 1.7 Required tests

| Test | What it proves |
|---|---|
| Engine pass ladder | Normal ×1→×2, ×2→×4, ×4→×6, ×6→×8, ×8→×8; Classic always 1 |
| Engine pass at the planned final round | The round is consumed and the match completes |
| Engine `nextRound()` after a pass | The new round carries the pass value, fresh hands, rotated dealer |
| Services `MatchDoc` round-trip | `currentMultiplier` survives read/write; an old doc without the field defaults to 1 |
| Services advance idempotency | A repeated advance does not write the multiplier twice |
| Rules emulator — legal pass advance | Four PASS entries + the ladder value are accepted |
| Rules emulator — tampering | A client that raises ×1 to ×8, skips a round, or writes Classic ≠ 1 is denied |
| Rules emulator — non-player / complete match | Denied |
| App online cold start | A new VM on a doc at ×4 starts at ×4 and scores the next round at ×4 |
| App online reconnect/reload | Same, with totals preserved |
| App online missed pass | `alignToDoc` reconciles from the doc, not from a local ×1 |
| Functions settlement/replay | A pass round replays at the doc value; a mismatch is reported, not reset |
| `MatchAdapterReloadReplayTest` | The pass value survives a reload/replay cycle |
| Full online pass flow (instrumented where required) | All four clients converge on the same round, deal and multiplier |

**Acceptance is not "the field exists".** It is: every row of §1.3 is updated, every row of §1.4 is proven,
and the rules denial tests are green.

---

## 2. Round count — **18 base rounds**; 14 is the fast-window start [D]

### 2.1 The number

The owner confirmed on 2026-10-23: **the match is 18 rounds, not 14.** This is already the code's base:
`GameType.FULL(baseRounds = 18, firstFastRound = 14, maxExtensions = 5)` (`Bidding.kt:125`) and
`MINI(baseRounds = 10, firstFastRound = 6, maxExtensions = 1)` (`:126`). **14 is `firstFastRound` — the
first quick round — never the match length.** Every "14–18" in the rules documents is the **fast window**,
not the base.

The full reading adopted here:

- **Base ceiling:** 18 (FULL) / 10 (MINI).
- **Fast window:** rounds 14–18 (FULL) / 6–10 (MINI).
- **Extended ceiling:** `maxRounds = base + N` (18 + N / 10 + N), where N counts qualifying Sa'ayda
  extensions (A8.8) and, where applicable, Super Call extensions. A **live** ceiling, not a constant.
- **Completion:** `currentRound >= maxRounds` on the live value (`Session.kt:568`).

### 2.2 Source of truth

| Layer | Field / read | Status |
|---|---|---|
| Engine | `RoundState.maxRounds` (default 18) + `isMatchComplete()` against it | **Correct source of truth** |
| Engine | `GameType.baseRounds` | Initial ceiling only |
| Services | `MatchDoc.maxRounds` (written at creation, `+1` in `extendMatchRounds`, `MatchService.kt:715`) | **Correct live source of truth** for online |
| Services | `endMatch` gate `completedRound + 1 > match.maxRounds` (`:774`) | Correct |
| Functions | `Settlement` gate `completedRound + 1 <= match.maxRounds` (`:370`) | Correct |
| Offline app | `QuickMatchViewModel.maxRounds` = `_gameType.value.baseRounds` (`:107`) | **Wrong for an extended match** — a constant, not the live ceiling |
| Offline app | `QuickMatchFlow.kt:152` sound uses `gameType.baseRounds`; `:166` button `round < gameType.baseRounds` | **Wrong for an extended match** — MATCH_WIN/disable at 18 while the ceiling is 19+ |
| Offline app | `PlacementViewModel.kt:135` `result.round >= config.gameType.baseRounds` | **Wrong** for extensions; also stalls on a final-round pass because a pass emits no `RoundResult` |
| Design reference | `design-ui/engine/session.js:102` default 18; `match-adapter.js:2031-2043` syncs the live value | Correct |

### 2.3 What is actually wrong [G]

The defect is **not** a 14-round match. It is the use of the **base constant** where the **live ceiling** is
required:

1. `QuickMatchFlow.kt:152` passes `gameType.baseRounds` to `roundScoredOrMatchWon`, so the match-win sound
   fires at round 18 even when a Sa'ayda raised the ceiling to 19+. The sound must receive the live
   `maxRounds` (18 → 19 → …).
2. `QuickMatchFlow.kt:166` disables the "next round" button at `round < baseRounds`, so an extended match
   cannot continue from standings. It must compare against the live ceiling.
3. `QuickMatchViewModel.maxRounds` is a constant getter; it must become observable state equal to the live
   ceiling (Agent 4's `_maxRounds`, once converted to Compose state — see §3).
4. `PlacementViewModel.kt:135` ends a best-of-series at the base 18 and can stall when the final round is a
   pass. This is **S1 wiring (Agent 3)**, not an S2 rule defect; it is listed here because it reads the same
   constant.

### 2.4 Conflict table

| Location | Text / code | Reading | Action |
|---|---|---|---|
| `Bidding.kt:125-126` | `FULL(18,14,5)`, `MINI(10,6,1)` | Correct | None |
| `Session.kt:67-76`, `:568` | `maxRounds = 18`; live completion | Correct | None |
| `MatchShapes.kt:103` | `maxRounds = gameType.baseRounds` | Correct at creation | None |
| `MatchService.kt:715`, `:774` | `+1` extension; live end gate | Correct | None |
| `Settlement.kt:370` | live gate | Correct | None |
| `QuickMatchViewModel.kt:107` | constant `baseRounds` | **Live-ceiling defect** | §2.3 item 3 |
| `QuickMatchFlow.kt:152`, `:166` | constant `baseRounds` | **Live-ceiling defect** | §2.3 items 1–2 |
| `PlacementViewModel.kt:135` | constant `baseRounds` | **Live-ceiling + pass-stall defect** | §2.3 item 4 |
| `docs/decisions/2026-10-23-final-owner-answers.md:50` | "fast rounds 14–18 inclusive" | Correct window, but reads as a match length in isolation | Pointer added |
| `docs/rules/CANONICAL_RULES.md` A8.8 / Current Rules §2 | "14–18", "18-round default" | Correct window and base | Pointer added |
| Tests `GameTypeTest:23/30`, `GameSessionTest:62/123/142`, `SoundManagerTest:39-96` | 18 / 10 | Correct base | Add an **18 → 19** extended case |
| `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` GM2 | "up to 5 extensions (18 → 23 maximum)" | Superseded by Q2c (no cap); the 18 base is correct | Historical plan; no rewrite |

**No file in the repository sets the match length to 14.** The 14–18 phrase is uniformly the fast window.
The correction is a **terminology pointer plus the live-ceiling fixes**, not a renumbering.

### 2.5 Required tests

- `SoundManagerTest`: `round = 18, maxRounds = 19` → no match-win sound; `round = 19, maxRounds = 19` →
  match-win sound; `round = 18, maxRounds = 18` → match-win sound (existing case preserved).
- `QuickMatchViewModelTest`: an extended match's `maxRounds` grows 18 → 19 and is observable.
- `QuickMatchFlow` UI test: the standings "next round" button is enabled at round 18 when the ceiling is 19.
- `PlacementViewModelTest`: a series with an extension plays 19 rounds; a final-round pass advances the
  series instead of stalling.
- Online: `scoreAndAdvance` already uses the live `doc.maxRounds`; add a regression test asserting
  `isLastRound` is false at 18/19 and true at 19/19.

---

## 3. S2 fix ownership — **Agent 4 is primary** [D]

Agent 4 owns the three S2 defects. Code changes alone are not evidence; each fix needs the tests below.

| # | Defect | Required fix | Required proof |
|---|---|---|---|
| 1 | `computeRoundExtension` gained a required `maxRounds`, breaking old-signature callers (including `S2SaaydaAcceptanceTest.kt:40` in the acceptance worktree) | Either a backward-compatible overload with the documented default, **or** update every call site consistently — decide once and apply it everywhere | A compile of the merged tree (not the isolated branch) + a test that calls the old signature and one that calls the new signature |
| 2 | `MATCH_WIN` / next-round logic uses `gameType.baseRounds` instead of the live ceiling (`QuickMatchFlow.kt:152`, `:166`) | Pass the live `maxRounds`; cover the final-round pass | The §2.5 sound/UI tests; an extended-match test at 18/19 and 19/19 |
| 3 | `_maxRounds` is a plain `var` (Agent 4 branch, line 109), so Compose does not recompose when the ceiling rises | Convert it to observable state (`MutableStateFlow<Int>` + `collectAsStateWithLifecycle`, or `mutableStateOf`) and expose a single live `maxRounds` | A Compose/UI test showing the standings button and round label update when `maxRounds` rises from 18 to 19 |

**Also required (S2 rule coverage):** the finality API must make the **final-round repeat** testable — a
Sa'ayda in the actual final round (`round == maxRounds`) adds one and repeats until a winner. A window-only
oracle is not acceptable (it can be satisfied by widening the window); add the non-final post-window
(`T-03`) case as well. The acceptance suite's S2 6/6 does **not** prove A8.8's finality.

**Coordination:** Agent 3's S1 wiring also touches `QuickMatchFlow.kt` and `QuickMatchViewModel.kt`. The
merge order is S1 wiring → S4 → S2; Agent 4 must rebase on the merged S1 result and must not revert Agent
3's `applyGeneralPass`. If both branches edit the same lines, the integrator resolves them before S2 is
accepted.

**Boundaries:** no `firestore.rules` edit in the S2 round; no scoring formula change; no round-count
renumbering. The live ceiling is read, never redefined.

---

## 4. Git and collaboration policy **[D] — RATIFIED 2026-10-23 (O-20 CLOSED)**

The owner directed that **no rule preventing push, pull request or merge may be assumed** and that no Git
permission limit may be invented. On 2026-10-23 the owner **approved** the policy below; it is no longer a
proposal. The earlier "no push, PR or merge" line in `2026-10-23-final-owner-answers.md` §5 was an
execution default, not a restriction, and is superseded by this section. The agent-facing summary lives in
`docs/agents/git-workflow.md`.

### 4.1 The hard fact about Git

**Git does not publish uncommitted changes from a local copy to other machines.** A commit is the unit that
travels; `push` publishes commits; `fetch`/`pull` receive them. Two separate worktrees — even on the same
disk, even sharing one object database — do **not** see each other's uncommitted files or index. A shared
folder is the only way two processes read the same uncommitted bytes, and the current agent setup uses
separate worktrees, so it does **not** provide real-time sharing before a commit. Cross-environment sharing
therefore requires **commit + push + fetch**; nothing else counts as publication.

### 4.2 Ratified mechanism

| Concern | Policy (approved) |
|---|---|
| Shared workspace | One repo, worktrees on `D:` (`D:\demo-test`, `D:\demo-test-<sprint>`). Worktrees share commits, not uncommitted state. |
| Integration branch | One shared integration branch — currently `main`. Every agent branch starts from its current tip and lands there only after review and green tests. |
| Agent branch | `agent/sN-<slug>` off the integration tip. One branch and one worktree per sprint; short-lived. |
| Commit cadence | Small, coherent commits at each green checkpoint, with the mandated identity. Commit before asking another agent to build on the work. |
| Push cadence | Push the agent branch to `origin` at each checkpoint and always when handing off, so other agents and environments can fetch it. This is the only cross-agent publication mechanism. |
| Seeing changes | `git fetch origin` → `git rebase origin/main` → re-read `git log`/`git status`. Never treat another worktree's uncommitted files as published. |
| Conflict prevention | One owner per shared file at a time (`QuickMatchFlow.kt`, `QuickMatchViewModel.kt`, `OnlineMatchViewModel.kt`, `MatchService.kt`, `firestore.rules`). Announce shared-file edits in the checkpoint; land the shared-file change first and rebase the others. No broad reformatting; no `git add -A`. |
| Tests before integration | Targeted suites for the sprint + the full engine/app/services/functions unit suites + the acceptance suite. No merge on red. Instrumented or emulator suites where the change crosses Firestore. |
| Branch vs PR vs direct merge | Pull requests and merges are **permitted**, not banned. Isolated, non-overlapping, fully green work may merge directly after review; overlapping or risky work (rules, settlement, schema) goes through a PR. `main` merges happen in the owner's web UI. |
| Merge discipline | Never merge unverified changes into the integration branch just to make them visible sooner. Visibility comes from pushing the agent branch, not from merging early. |
| Rollback | Never force-push a shared branch. Revert with a new commit; keep integration history linear where practical. |

### 4.3 What this policy does not do

It does not ban push, PR or merge, and it does not grant any permission the owner has not granted. The only
standing constraints are the owner's: PRs are opened in the web UI, `gh` is not installed, and commits use
the mandated identity. Ratification was **O-20**; it is now closed.

---

## 5. D: migration — **APPROVED — NOT EXECUTED**

The owner approved moving the main project and Gradle to `D:` with junctions at the old paths. The move is
**not executed**: the audit precondition is now recorded, but the worktrees are still active and the primary
checkout holds uncommitted owner work.

### 5.1 Audit (read-only, 2026-10-23)

`git worktree list` reports 27 worktrees. The C:-resident ones and their state:

| Worktree | Dirty | Untracked | Notes |
|---|---|---|---|
| `C:\Users\ehab.sayed\Desktop\demo-test` (primary) | 12 | 6 | 6 modified code/test files + `AGENTS.md`; untracked `docs/engineering/`, `docs/agents/…`, `CallableSettlement.kt`, `SettlementPort.kt`, `SettlementResponseTest.kt` |
| `C:\Users\ehab.sayed\Desktop\demo-test-agent2-settlement` | 3 | 1 | `SettlementDb.kt`, `RpApplicationTest.kt`, `docs/engineering/` |
| `C:\Users\ehab.sayed\Desktop\demo-test-agent3-profile-read` | 3 | 1 | `RankedProfileDb.kt`, `RankedProfileDbTest.kt`, `docs/engineering/` |
| `C:\Users\ehab.sayed\Desktop\demo-test-roadmap` | 3 | 0 | plan, roadmap, canonical rules |
| `C:\Users\ehab.sayed\Desktop\demo-test-s39` | 3 | 3 | S39 design assets |
| `C:\Users\ehab.sayed\Desktop\demo-test-s41` | 7 | 1 | Arabic retrofit |
| `C:\Users\ehab.sayed\Desktop\demo-test-s62` | 5 | 2 | settlement ledger |
| `C:\Users\ehab.sayed\Desktop\demo-test-s72-artboard` | 4 | 4 | S72 design assets |
| `C:\Users\ehab.sayed\.local\share\opencode\worktree\7ae766\s41-s42-disconnect-vote` | 4 | 4 | old agent worktree |

The `Temp\opencode\...` worktrees are clean. The `D:` worktrees are already migrated; the active ones hold
the S1/S2/S4 agent work (uncommitted, not accepted).

### 5.2 Preservation plan (before any move)

1. Record `git rev-parse HEAD` and a `git status --porcelain` snapshot for every worktree (done above).
2. Do not move or delete any worktree until every dirty worktree is either committed or copied aside. The
   primary's 12 + 6 entries are the owner's in-progress work; they are not ours to reset.
3. Move `C:\Users\ehab.sayed\Desktop\demo-test` → `D:\demo-test`; create a directory junction at the old
   path (`mklink /J`, no admin). The worktrees' `.git` files point into the primary's `.git/worktrees/…`;
   the junction keeps those absolute paths valid.
4. Move the Gradle user home (`C:\Users\ehab.sayed\.gradle`) → `D:\gradle-home`; junction the old path to
   `D:\gradle-home`. This avoids a system-wide environment change.
5. Do **not** move caches, outputs or the other worktrees in this step (out of scope).

### 5.3 Rollback and verification

- Rollback: remove the junction (`rmdir`, which does not delete the target), move the directory back. Git
  history is unchanged.
- After the move: `git -C D:\demo-test rev-parse HEAD` equals the recorded SHA; `git status --porcelain`
  matches the recorded snapshot; `gradlew.bat --version` runs with the junctioned Gradle home; one module's
  unit tests run. Only then is the migration reported as done.

### 5.4 What was not done

No directory was moved, no junction was created, no Gradle home was changed. The audit above is the only
executed part.

---

## 6. Deletion — Option A only: the 19 `*-Review.zip` archives — **VERIFIED — NOT EXECUTED**

The owner limited deletion approval to Option A and forbade expanding it to worktrees, outputs or caches.

### 6.1 The verified list (19)

`Sprint-2.8-Review.zip`, `Sprint-2.9-Review.zip`, `Sprint-3.0-Review.zip`, `Sprint-3.1-Review.zip`,
`Sprint-3.2-Review.zip`, `Sprint-3.2.1-Review.zip`, `Sprint-3.3-Review.zip`, `Sprint-3.4-Review.zip`,
`Sprint-3.4.1-Review.zip`, `Sprint-3.5-Review.zip`, `Sprint-3.6-Review.zip`, `Sprint-3.6.1-Review.zip`,
`Sprint-3.7-Review.zip`, `Sprint-3.7.1-Review.zip`, `Sprint-3.8-Review.zip`, `Sprint-3.8.1-Review.zip`,
`Sprint-3.9-Review.zip`, `Sprint-4.0-Review.zip`, `Task-Service-Layer-Review.zip`.

They are the 19 matching files in the primary checkout root; total size is under 1 MB.

### 6.2 Material finding

The 19 archives are **tracked in git**, last committed in `eb352dc` ("Sprint 4.0: Online Bidding
Synchronization (Authority Layer)"). They are therefore recoverable from history (`git show
eb352dc:Sprint-3.4-Review.zip`), and deleting them is a **repository-content commit**, not a disk cleanup.
Deleting them does not materially free C:; the disk pressure is Gradle caches and build outputs.

### 6.3 Result — **EXECUTED 2026-10-23**

The exact list was re-verified (19 tracked paths), then removed in a dedicated commit on
`docs/native-v1-implementation-plan-v2`: **`438736e`** — *"Chore(archives): delete the 19 approved
*-Review.zip snapshots"* (19 files changed, 0 insertions, 0 deletions). Verification after the commit:

- `git ls-files -- '*Review.zip' | wc -l` → **0**
- `git cat-file -e eb352dc:Sprint-2.8-Review.zip` and `eb352dc:Task-Service-Layer-Review.zip` → both
  resolve, so every archive is still recoverable from history.
- `git status` after the commit showed only Agent 1's three uncommitted engine files; no other file was
  included in the commit.
- No worktree, cache or other output was touched.

The archive contents were not individually listed (local listing produced no output), so recovery is
guaranteed by git history, not by a content inspection.

---

## 7. Questions closed on 2026-10-23

| ID | Question | Decision (2026-10-23) |
|---|---|---|
| O-19 | Does the owner authorize the Firestore rules change required by Option B? | **CLOSED — APPROVED with strict scope limits.** Minimal rules + online match-flow change only: `currentMultiplier` allowlists/validation, a narrowly scoped General Pass advance without the 52-card requirement, a pass-round archive variant that does not weaken ordinary-round validation, Classic ×1 / Normal Double preserved, legacy fallback with tests, reload/reconnect/cold-start and Sa'ayda continuity proven, emulator accepted/rejected tests. Dedicated feature branch; exact diff and test evidence required. — see §1.5 and §9. |
| O-20 | Does the owner ratify the Git collaboration policy? | **CLOSED — APPROVED.** One shared integration branch; one feature branch/worktree per agent; frequent coherent commits pushed promptly; no uncoordinated edits to the same files; integrate only after review and passing tests; PRs and merges permitted; never merge unverified work just for visibility. — see §4. |

The remaining register (O-12, O-13, O-15, O-16, O-17, O-18) stays open and is unchanged by this record.

---

## 8. What this record does not do

- It authorizes Option B but **does not implement it**: no rules edit, no engine/service/app change, and no
  claim that the online pass or multiplier continuity works.
- It does not renumber the match to 14 or to 18; it records the existing 18 base and the live ceiling.
- It does not accept S1, S2 or S4; the agent work is still uncommitted and the acceptance gaps stand.
- It does not move the project; the D: migration remains approved-but-not-executed pending dirty-worktree
  preservation.
- It does not answer O-12, O-13, O-15, O-16, O-17 or O-18.

---

## 9. O-19 authorized implementation — status

**Status: NOT STARTED.** No `firestore.rules` edit, no match-flow change and no test run has happened in
this round. The work must run on a dedicated feature branch, and **Agent 4's S2 scope is unchanged**.

| # | Owner-required work item | Status |
|---|---|---|
| 1 | `currentMultiplier` allowlists and validation | PLANNED |
| 2 | Narrow General Pass advance path without the 52-card requirement | PLANNED |
| 3 | Pass-round archive validation without weakening ordinary rounds | PLANNED |
| 4 | Classic ×1 / Normal Double preserved after a pass | PLANNED |
| 5 | Legacy documents without `currentMultiplier` fall back, with tests | PLANNED |
| 6 | Reload, reconnect, cold start and Sa'ayda continuity proven | PLANNED |
| 7 | Emulator accepted/rejected tests (invalid values, unauthorized fields, ordinary and pass rounds) | PLANNED |

**Gate:** the work is not accepted until the exact diff, rules-emulator results and engine/service test
counts (including failures and skipped) are reported and reviewed. No acceptance claim without evidence.
