# 2026-10-24 — A8.8 in the placement series, O-19 status, S1 delivery split

**Status: RECORDED (documentation only).** Source: the owner's confirmation of 2026-10-24 of the three
facts below, plus the implementation already present on the integrated line. No rule body, no code, no
`firestore.rules` and no Gradle file is changed by this record.

Labels: **[D]** owner-confirmed · **[G]** confirmed implementation gap · **[V]** assigned but **not
verified**.

**Evidence baseline.** Every code claim here was read on `8881fe05201dc519172fca7a0bf14378889e56cb`
(`agent/integration-s1-s2-s4`) — the integrated **S4 → S1 → S2** line (`5edfb34` Integrate S4, `0ae9864`
and `c14f4a4` Integrate S1 Core, `397c163` Integrate S1 fix, `8881fe0` Integrate S2). This record was
written on a branch cut from that commit, so the cited files and line numbers are the ones on that line.

Companion records: `docs/decisions/2026-10-23-final-owner-answers.md` (Q51–Q52, sprint scope);
`docs/decisions/OPEN_QUESTIONS.md` (register; O-19 and O-20 now in §B); `docs/rules/CANONICAL_RULES.md`
A8.8 (Sa'ayda extension count), which receives a pointer note from this change and no edit to its rule text.

## 1. The placement series follows A8.8, with no exemption [D]

The three-match placement series is **not exempt** from Sa'ayda / Super Call extension. A placement match
ends on the **effective ceiling**, never on `GameType.baseRounds`. This is the owner's ruling of
2026-10-24, and it is the same rule any other match already follows (A8.8 / Q48): placement is a consumer
of the match engine, not a special case inside it.

**Implementation evidence on `8881fe0`.**

- `native/app/src/main/java/com/estemshan/game/ui/placement/PlacementViewModel.kt:172` — the end-of-match
  predicate is `if (result.round >= quickMatch.maxRounds)`, and the record for the match is banked inside
  that branch (`:174`). The comment at `:167-171` states the reason: a Sa'ayda in the fast window raises the
  ceiling mid-match, so a scored round at `baseRounds` on an extended match is not the last round.
- `native/app/src/main/java/com/estemshan/game/ui/quickmatch/QuickMatchViewModel.kt` — the extension is
  applied **before** the round result is emitted: `computeRoundExtension` at `:263`, then the ceiling raise
  at `:267`, then `_roundResults.tryEmit(...)` at `:268`. The ceiling is observable, so no reader can see a
  stale value: `_maxRounds`, `maxRoundsFlow` and `maxRounds` at `:116-118`, seeded from `gameType.baseRounds`
  in `startMatch` at `:171`. The completion flag reads the same effective value (`:276`) and `nextRound` stops
  there (`:282`).
- `native/app/src/main/java/com/estemshan/game/ui/quickmatch/QuickMatchFlow.kt:163` — the standings screen
  collects `qvm.maxRoundsFlow`; the chime passes it to `sfx.roundScoredOrMatchWon(round, maxRounds)` at
  `:170` and the continue button is `enabled = round < maxRounds` at `:184`. Both read the effective ceiling,
  not the format's static one.

**Regression proof.**
`native/app/src/test/java/com/estemshan/game/ui/placement/PlacementViewModelTest.kt:328` —
`aScoredBaseRoundsRoundOnAnExtendedMatchDoesNotAdvanceTheSeries`. It starts placement match 1 (MINI, base
ceiling 10, asserted at `:332`), scores a fast-window Sa'ayda in round 6, asserts the ceiling moved to 11
and that the extending round is not complete (`:347-348`), then scores round 10 and asserts the series has
**not** advanced and no record was banked (`:357-364`), then plays round 11 and asserts the extended ceiling
banks exactly one record holding all eleven rounds (`:368-375`). The test's own KDoc (`:318-321`) records the
bug it replaces: the coordinator used to compare against `config.gameType.baseRounds` and truncated the match
a round early.

**Consequence [D].** A MINI placement match can run to 11 rounds and a FULL one to 19 or more; the series
advances only after the extended final round. No "placement suppression" flag exists, and none should be
added. Checked on `8881fe0`: the placement and quick-match sources contain no suppression switch, and
`PlacementMatchConfig` carries a plain `gameType` field
(`native/engine/src/commonMain/kotlin/com/estemshan/engine/Placement.kt:49`) that
`PlacementViewModel.kt:131` passes straight into `QuickMatchViewModel.startMatch`, so a placement match is
extended by exactly the same code path as any other match of that format.

**Not a ceiling (stated for accuracy).** The 11 and 19+ figures are what the base format plus one extension
produces, not limits. `GameType.maxExtensions` (MINI 1, FULL 5 — `Bidding.kt:132-133`) is **not enforced**:
the `computeRoundExtension` KDoc states the count cap was removed by Q2c and is enforced neither in the helper
nor by any caller, and A8.8 item 2(b) repeats the final-round extension until a winner. Any future claim that
a match cannot exceed a given round count would be a new decision, not this one.

## 2. O-19 — Option B persisted multiplier [D] authorized, [V] implementation unverified

- **AUTHORIZED / CLOSED for scope on 2026-10-23 [D].** Option B — persisting the current multiplier — is
  approved with a strict rules surface.
- **Source record:** `docs/decisions/2026-10-23-direction-multiplier-rounds-git.md` §1. **That file is not
  on this line.** It exists on `origin/docs/native-v1-implementation-plan-v2` at
  `33ca9bc5699f6db522e458ff0be755e06e3f905b` ("Docs(2026-10-23): close O-19 and O-20…") and is **not merged
  into `8881fe0`**: `git merge-base --is-ancestor 33ca9bc 8881fe0` is false here, and the file is absent from
  this worktree. The authority therefore traces to that commit on that branch, not to anything readable in
  this checkout.
- **Implementation assigned 2026-10-24 [V]** on branch `agent/s1b-option-b-impl`.
- **Nothing is verified [V].** No rules run, and no service, functions or app implementation of Option B is
  accepted. Consistent with that: the literal `currentMultiplier` field appears in no app, services, functions
  or engine source on `8881fe0`; a repo-wide search of the Kotlin, TypeScript and rules files returns nothing.
  What the integrated line does carry is S1 Core's **round-scoped** multiplier — `biddingMultiplier` and
  `scoreMultiplier` in `QuickMatchViewModel.kt:54-55`, `:144`, armed by `applyGeneralPass` at `:203-204` and
  surfaced per round by `MatchAdapter.kt:885` — not the persisted value, pass archive or cold-start continuity
  that S1b is to add.

## 3. S1 delivery split [D]

The owner's 2026-10-23 sprint directive (record §2 of `2026-10-23-final-owner-answers.md`) named S1 as one
sprint. It ships as two deliveries, and only the first is on the integrated line.

- **S1 Core — merged into the integrated line at `8881fe0`.** The engine General Pass ladder plus the
  round-advance wiring through app, services and functions.
  - Engine: `EmitResult.GeneralPass` (`native/engine/src/commonMain/kotlin/com/estemshan/engine/Bidding.kt:82`,
    emitted at `:607`), with the ladder under test in
    `native/engine/src/jvmTest/kotlin/com/estemshan/engine/BiddingTest.kt:594-643`
    (`generalPassDoublesMultiplier`, including the ×8 cap at `:607-615`, and
    `generalPassNormalMultiplierWalksTheLadderAdditively`).
  - App / services / functions wiring, per the integration commit `0ae9864` ("Integrate S1 Core: General
    Pass wiring across app, services and functions"). App: `BiddingViewModel.kt`, `OnlineMatchViewModel.kt`,
    `PlacementViewModel.kt`, `QuickMatchFlow.kt`, `QuickMatchViewModel.kt` (`applyGeneralPass` at `:190`).
    Functions (Kotlin/JS, under `native/functions/src/jsMain/kotlin/com/estemshan/functions/`):
    `Settlement.kt`, `TimeoutResolution.kt`. Services:
    `native/services/src/main/java/com/estemshan/services/MatchAdapter.kt`.
- **S1b — in progress on `agent/s1b-option-b-impl`, unverified [V].** Option B persistence: the
  `currentMultiplier` field, the pass-advance branch, the pass archive and cold-start continuity. None of
  that exists on `8881fe0`; the branch is the assignment, not an accepted delivery.

## 4. What this record does not do

- It does not change any rule body. A8.8's text is left verbatim and carries only a pointer note.
- It does not claim any Option B work is implemented, tested or verified, and it does not open or close a
  rules change.
- It does not report a test run. Nothing in this session executed Gradle, the emulator or a test suite; the
  code claims above are reads of `8881fe0`, and the regression test is cited as existing evidence on that
  line, not as observed passing here.
- It does not touch **O-12, O-13, O-15, O-16, O-17, O-18**. Those six owner questions stay open and
  untouched in `OPEN_QUESTIONS.md` §A.
- It does not modify `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` (owned by the S1b agent), any source, test,
  `firestore.rules`, Gradle or design file.
