package com.estemshan.game.ui.placement

import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.GameType
import com.estemshan.engine.PlacementState
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.bot.BidBrain
import com.estemshan.engine.bot.BotTier
import com.estemshan.engine.bot.PlayBrain
import com.estemshan.engine.placementMatch
import com.estemshan.engine.placementMatches
import com.estemshan.engine.placementOutcome
import com.estemshan.engine.placementRank
import com.estemshan.game.ui.bot.BotSeat
import com.estemshan.game.ui.bidding.BiddingViewModel
import com.estemshan.game.ui.quickmatch.QuickMatchViewModel
import com.estemshan.game.ui.table.TableViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S53 — the placement series driver. The state-machine tests pin the scripted
 * table RD10 closes and RD10/RD30's "never repeat" rule; the series test drives
 * all three matches through the real quick-match view models with bots armed
 * and the player's seat played by an EXPERT stand-in, exactly as
 * [com.estemshan.game.ui.quickmatch.QuickMatchBotIntegrationTest] drives a
 * single round — proving the coordinator seats each scripted table, records
 * every round's signal, and hands a complete three-match record set to S54.
 *
 * Assertions are invariants rather than golden snapshots: a deal that breaks
 * any of them still fails loudly, but a merely unusual deal does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlacementViewModelTest {

  private val seats = DEFAULT_SEATS
  private val human: String get() = seats.first()
  private val season = "2026Q4"

  private lateinit var qvm: QuickMatchViewModel
  private lateinit var bvm: BiddingViewModel
  private lateinit var tvm: TableViewModel
  private lateinit var pvm: PlacementViewModel

  companion object {
    /** A fixed deal so a flake reproduces instead of re-rolling each run. */
    private const val MATCH_SEED = 42_000_000L
  }

  @Before
  fun setUp() {
    qvm = QuickMatchViewModel()
    bvm = BiddingViewModel()
    tvm = TableViewModel()
    pvm = PlacementViewModel(qvm, seedSource = { MATCH_SEED })
  }

  @After
  fun tearDown() {
    qvm.detachBots()
    Dispatchers.resetMain()
  }

  /**
   * Arm the driver on the test scheduler so `advanceUntilIdle` advances the
   * driver's coroutines with the test's own; the driver runs on Main in
   * production. Null auction/table states are something the driver already
   * falls through on, so arming before any round exists is safe.
   */
  private fun TestScope.armDriver() {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    qvm.attachBots(bvm, tvm)
  }

  // ==========================================================================
  //  The scripted table and the series' rules
  // ==========================================================================

  @Test
  fun aFreshSeriesIsIdleWithNoRecords() {
    assertTrue("nothing has started", pvm.state.value is PlacementUiState.Idle)
    assertTrue("no match has been recorded", pvm.records.isEmpty())
  }

  @Test
  fun startPlacementSeatsTheFirstScriptedTable() = runTest {
    armDriver()
    pvm.startPlacement(season)
    advanceUntilIdle()

    val state = pvm.state.value
    assertTrue("the series is underway", state is PlacementUiState.MatchInProgress)
    assertEquals("RD10 shows only the match number — 1 of 3", 1, (state as PlacementUiState.MatchInProgress).progress)

    // The system's first table, never the player's choice (RD10): MINI (owner
    // decision), NORMAL scoring (GM7), and Easy+Medium across the three seats
    // opposite the player.
    assertEquals(GameType.MINI, qvm.gameType.value)
    assertEquals(ScoringMode.NORMAL, qvm.scoringMode.value)
    assertSeatsTheScriptedTable(placementMatch(0))

    // beginPlacement put the account IN_PROGRESS, which is what gates the
    // stats accumulator off these matches (RD10).
    assertEquals(PlacementState.IN_PROGRESS, pvm.profile.value?.placementState)
  }

  @Test
  fun startPlacementRefusesToRestartASeriesUnderway() = runTest {
    armDriver()
    pvm.startPlacement(season)
    advanceUntilIdle()

    // RD10: once per account, never per season — the series never repeats.
    assertThrows(IllegalStateException::class.java) { pvm.startPlacement(season) }
  }

  @Test
  fun startPlacementRefusesToRestartAFinishedSeries() = runTest {
    armDriver()
    pvm.startPlacement(season)
    playSeries()

    assertThrows(IllegalStateException::class.java) { pvm.startPlacement(season) }
  }

  // ==========================================================================
  //  The series, end to end
  // ==========================================================================

  @Test
  fun theSeriesPlaysAllThreeMatchesAndHandsARecordSetToS54() = runTest {
    armDriver()
    pvm.startPlacement(season)

    val observedFormats = mutableListOf<GameType>()
    val observedTables = mutableListOf<List<BotSeat?>>()
    val matchRuns = mutableListOf<MatchRun>()

    while (pvm.state.value !is PlacementUiState.Calculating) {
      observedFormats += qvm.gameType.value
      observedTables += seats.drop(1).map { qvm.roster.value.config(it) }
      matchRuns += playMatch()

      advanceUntilIdle()
    }

    // The escalation the owner decided: two MINI matches, then a FULL decider.
    assertEquals(listOf(GameType.MINI, GameType.MINI, GameType.FULL), observedFormats)
    // Each match seated the table RD10 closes for it — tiers and the fixed
    // system personality spread, never a player choice.
    placementMatches.forEachIndexed { i, config ->
      assertSeatsTheScriptedTable(config, observedTables[i], matchNumber = i + 1)
    }

    val records = (pvm.state.value as PlacementUiState.Calculating).records
    assertEquals("three matches, three records", placementMatches.size, records.size)
    assertEquals("records are in series order", listOf(0, 1, 2), records.map { it.matchIndex })
    assertEquals("the handoff carries exactly the recorded set", pvm.records, records)
    // Fix round (2026-10-23): a normally scored final round advances the
    // series exactly once — roundResults banks it and matchComplete's
    // once-per-match guard must never double-record a match.
    assertEquals(
      "the scored-final advance recorded each match exactly once (no double-advance)",
      listOf(1, 1, 1),
      records.groupBy { it.matchIndex }.values.map { it.size },
    )

    placementMatches.forEachIndexed { i, config ->
      val record = records[i]
      val run = matchRuns[i]
      val totals = run.totals
      // S1 (A8.7 / Q51): a general pass consumes the round it sits on, so the
      // record carries one signal per SCORED round — the format's round count
      // minus the rounds the match's own passes consumed — in played order,
      // exactly the format's numbers minus its passed ones.
      assertEquals(
        "a ${config.gameType} match records one signal per scored round",
        config.gameType.baseRounds - run.passedRounds.size,
        record.rounds.size,
      )
      assertEquals(
        "recorded rounds are exactly the format's numbers minus its passed ones",
        (1..config.gameType.baseRounds).filter { it !in run.passedRounds },
        record.rounds.map { it.round },
      )
      // Every signal the record derives is consistent with the totals the
      // match actually banked, computed through the same pure derivations
      // S54 will call.
      assertEquals(placementRank(totals, human), record.placement)
      assertEquals(placementOutcome(totals, human), record.outcome)
      assertEquals(totals.getValue(human), record.score)
      record.rounds.forEach { round ->
        assertTrue("an estimate is a trick count 0..13", round.estimate in 0..13)
        assertTrue("a trick count is 0..13", round.actual in 0..13)
      }
      assertTrue("a placement is a four-seat rank 1..4", record.placement in 1..4)
    }
  }

  @Test
  fun aGeneralPassOnThePlannedFinalRoundStillAdvancesTheSeries() = runTest {
    armDriver()
    pvm.startPlacement(season)

    // Match 1 (MINI, baseRounds 10): the deterministic deal all-passes
    // round 9 — legitimate play, since every offline round runs a normal
    // auction — and playMatch(passFinalRound) then passes the planned final
    // round itself, whatever number the early pass left it at. The
    // coordinator's matchComplete arm must advance the series where the old
    // roundResults-only observer would have stalled forever.
    val run1 = playMatch(passFinalRound = true)
    advanceUntilIdle()

    assertTrue(
      "the pass-completed match must hand the series to the next match" +
        " (state=${pvm.state.value}, matchComplete=${qvm.matchComplete.value}," +
        " records=[${pvm.records.joinToString { "m" + it.matchIndex + ":" + it.rounds.size + "r" }}])",
      (pvm.state.value as? PlacementUiState.MatchInProgress)?.progress == 2,
    )
    assertFalse(
      "the next match reset the completion flag",
      qvm.matchComplete.value,
    )
    assertTrue("the pass-completed match banked its record", pvm.records.isNotEmpty())
    val record1 = pvm.records[0]
    // A pass consumes its own round and contributes NO placement signal
    // (nobody bid, no tricks fell), so the record carries exactly the
    // format's numbers minus EVERY pass the match consumed — including any
    // legitimate below-ceiling all-pass the deterministic deal produced.
    assertEquals(
      "a passed planned final round records exactly the format's scored rounds",
      (1..GameType.MINI.baseRounds).filter { it !in run1.passedRounds },
      record1.rounds.map { it.round },
    )
    assertEquals(
      "the match consumed every format round exactly once — scored or passed",
      (1..GameType.MINI.baseRounds).toSet(),
      record1.rounds.map { it.round }.toSet() + run1.passedRounds,
    )
    assertTrue(
      "the fixture consumed the planned final round by a pass",
      GameType.MINI.baseRounds in run1.passedRounds,
    )
    assertEquals(
      "the pass banked no delta (GameType.MINI minus its passed rounds)",
      GameType.MINI.baseRounds - run1.passedRounds.size,
      record1.rounds.size,
    )
    assertEquals(placementRank(run1.totals, human), record1.placement)
    assertEquals(placementOutcome(run1.totals, human), record1.outcome)
    assertEquals(run1.totals.getValue(human), record1.score)

    // Matches 2 and 3 play out normally (their finals are scored) to the
    // series handoff — rounding out the same once-per-match guards.
    val matchRuns = mutableListOf(run1)
    while (pvm.state.value !is PlacementUiState.Calculating) {
      matchRuns += playMatch()
      advanceUntilIdle()
    }

    val records = (pvm.state.value as PlacementUiState.Calculating).records
    assertEquals("three matches, three records", placementMatches.size, records.size)
    assertEquals("records are in series order", listOf(0, 1, 2), records.map { it.matchIndex })
    assertEquals(
      "each match recorded exactly once — neither the pass path nor the scored path double-advanced",
      listOf(1, 1, 1),
      records.groupBy { it.matchIndex }.values.map { it.size },
    )
    placementMatches.forEachIndexed { i, config ->
      val record = records[i]
      val run = matchRuns[i]
      assertEquals(
        "a ${config.gameType} match records one signal per scored round",
        config.gameType.baseRounds - run.passedRounds.size,
        record.rounds.size,
      )
      assertEquals(
        "recorded rounds are the format's numbers minus its passed ones",
        (1..config.gameType.baseRounds).filter { it !in run.passedRounds },
        record.rounds.map { it.round },
      )
    }
  }

  // ==========================================================================
  //  Assertions
  // ==========================================================================

  /** The table [config] scripted, seated opposite the player. */
  private fun assertSeatsTheScriptedTable(config: com.estemshan.engine.PlacementMatchConfig) {
    assertSeatsTheScriptedTable(config, seats.drop(1).map { qvm.roster.value.config(it) }, matchNumber = config.index + 1)
  }

  private fun assertSeatsTheScriptedTable(
    config: com.estemshan.engine.PlacementMatchConfig,
    observed: List<BotSeat?>,
    matchNumber: Int,
  ) {
    val expected: List<BotSeat?> = config.opponents.map { BotSeat(it.tier, it.personality) }
    assertEquals(
      "placement match $matchNumber seats exactly its scripted opponents",
      expected,
      observed,
    )
    assertEquals(
      "placement match $matchNumber seats the player opposite three bots",
      seats.drop(1).toSet(),
      qvm.roster.value.botSeats,
    )
  }

  // ==========================================================================
  //  Harness: the quick-match flow's composable glue, re-implemented headless
  // ==========================================================================

  /** Drive every match until the series hands off to Calculating. */
  private suspend fun TestScope.playSeries() {
    while (pvm.state.value !is PlacementUiState.Calculating) {
      playMatch()
      advanceUntilIdle()
    }
  }

  /**
   * Play one scripted match, the way the quick-match screens do: start the
   * auction, close it (the general-pass handling the Bidding screen runs in a
   * `LaunchedEffect`), score the round through
   * [QuickMatchViewModel.onTableDone], and advance with `nextRound` — until
   * the coordinator, on the match's end, advances the series itself. Returns
   * the totals the match banked along with the round numbers the match's own
   * general passes consumed (S1: a pass IS a round under S1's production
   * transition, so scores + passed rounds == the format's round count).
   */
  private suspend fun TestScope.playMatch(passFinalRound: Boolean = false): MatchRun {
    val matchProgress = (pvm.state.value as PlacementUiState.MatchInProgress).progress
    var totals = qvm.totals.value
    val passedRounds = mutableSetOf<Int>()

    while (stillInMatch(matchProgress)) {
      // Fix-round fixture (2026-10-24): the planned final round is
      // general-passed, even when a legitimate below-ceiling all-pass already
      // consumed its round first (closeAuction applies that second pass).
      // The auction mechanics themselves are pinned in QuickMatchViewModelTest;
      // this hook performs the production Bidding-screen transition directly
      // at the ceiling.
      if (passFinalRound && qvm.round.value >= qvm.maxRounds) {
        assertFalse("the match is not yet complete", qvm.matchComplete.value)
        passedRounds += qvm.round.value
        qvm.applyGeneralPass(2)
        advanceUntilIdle()
        break
      }
      // S1: a pass consumes the round it sits on, so a match's rounds are
      // scores + passed rounds — the loop keys off the coordinator's own
      // state, not a scored-round count.
      bvm.startNormalRound(
        qvm.round.value,
        qvm.dealer.value,
        qvm.seats,
        qvm.biddingMultiplier.value,
        qvm.scoringMode.value,
      )
      val outcome = closeAuction(passedRounds, passFinalRound)
      if (outcome == null) {
        // A general pass consumed the planned final round: the coordinator
        // (through matchComplete) banked the record and advanced the series
        // — the cancelled round is already accounted for in passedRounds.
        return MatchRun(totals, passedRounds)
      }
      val cfg = qvm.onBiddingComplete(outcome)
      tvm.startRound(cfg, qvm.seats)
      val table = playOutRound()
      qvm.onTableDone(cfg, table.tricksWon)
      totals = qvm.totals.value
      advanceUntilIdle()
      // The coordinator advances the series on the last round; only then does
      // this round's `nextRound` become the next match's, so it is skipped
      // the moment the match the harness is driving has moved on.
      if (stillInMatch(matchProgress)) {
        qvm.nextRound()
        advanceUntilIdle()
      }
    }
    return MatchRun(totals, passedRounds)
  }

  private data class MatchRun(val totals: Map<String, Int>, val passedRounds: Set<Int>)

  /** True while the coordinator is still on the match this harness started in. */
  private fun stillInMatch(matchProgress: Int): Boolean {
    val state = pvm.state.value
    return state is PlacementUiState.MatchInProgress && state.progress == matchProgress
  }

  /**
   * Advance until the auction closes, applying the Bidding screen's general
   * pass handling and playing the player's own turns with an EXPERT stand-in
   * — exactly as the screen surfaces a tap. A bot seat holding the turn is a
   * failure: the driver should have moved it. Bounded so a pathological deal
   * fails the test instead of hanging it. Returns null when a general pass
   * consumed the planned final round — either the auction's own all-pass at
   * the ceiling, or, with [passFinalRound], the pass applied after a
   * below-ceiling all-pass has consumed its round: the quick match has
   * completed itself and the coordinator's matchComplete observer banks the
   * record — no auction outcome exists for the cancelled round.
   */
  private suspend fun TestScope.closeAuction(
    passedRounds: MutableSet<Int>,
    passFinalRound: Boolean,
  ): BiddingOutcome? {
    var passes = 0
    while (bvm.outcome.value == null) {
      advanceUntilIdle()
      val doubled = bvm.generalPass.value
      if (doubled != null) {
        check(++passes < 40) { "a placement auction general-passed 40 times without closing" }
        passedRounds += qvm.round.value
        qvm.applyGeneralPass(doubled)
        if (qvm.matchComplete.value) {
          // The pass was the planned final round's: the match is complete,
          // the coordinator has advanced the series, and no auction outcome
          // exists — the cancelled round waits on nobody.
          advanceUntilIdle()
          return null
        }
        if (passFinalRound && qvm.round.value >= qvm.maxRounds) {
          // The below-ceiling pass consumed its round and dealt the planned
          // final round. Apply the final pass here too, exactly as the
          // Bidding screen would after this auction all-passed, instead of
          // opening another auction at the ceiling.
          passedRounds += qvm.round.value
          qvm.applyGeneralPass(doubled)
          check(qvm.matchComplete.value) {
            "a pass on the planned final round must complete the match"
          }
          advanceUntilIdle()
          return null
        }
        bvm.startNormalRound(
          qvm.round.value,
          qvm.dealer.value,
          qvm.seats,
          qvm.biddingMultiplier.value,
          qvm.scoringMode.value,
        )
      } else {
        bvm.outcome.value?.let { return it }
        val seat = bvm.state.value?.waitingFor
          ?: error("the auction is idle but no seat is waiting on it")
        if (qvm.roster.value.isBot(seat)) {
          error("the auction stalled on bot seat $seat, which the driver should have moved")
        }
        val held = bvm.state.value!!
        bvm.submit(BidBrain.decide(held, qvm.handFor(seat)!!, seat, BotTier.EXPERT))
        check(bvm.state.value !== held) { "the player's stand-in bid was rejected: ${bvm.rejection.value}" }
      }
    }
    return bvm.outcome.value!!
  }

  /**
   * Advance until the round ends, resolving each completed trick as the Table
   * screen does after its highlight, and playing the player's turns with an
   * EXPERT stand-in — the driver only moves bot seats, so without it a human
   * turn would stall the round forever.
   */
  private suspend fun TestScope.playOutRound(): TableState {
    var tricks = 0
    while (tvm.state.value?.phase != TablePhase.DONE) {
      advanceUntilIdle()
      val s = tvm.state.value ?: error("the table never started")
      when (s.phase) {
        TablePhase.RESOLVING -> {
          check(++tricks <= 13) { "resolved more than 13 tricks in one placement round" }
          tvm.resolve()
        }
        TablePhase.PLAY -> {
          val seat = s.turn ?: error("the table is in PLAY with no seat to move")
          if (!qvm.roster.value.isBot(seat)) {
            tvm.play(seat, PlayBrain.decide(s, seat, BotTier.EXPERT).card)
          }
          // A bot seat: the driver moves it on the next advance.
        }
        else -> error("unexpected table phase ${s.phase} in a placement round")
      }
    }
    return tvm.state.value!!
  }
}
