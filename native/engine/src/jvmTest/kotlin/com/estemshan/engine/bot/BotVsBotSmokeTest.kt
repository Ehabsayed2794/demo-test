package com.estemshan.engine.bot

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.Dealer
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameSession
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.RoundScoreResult
import com.estemshan.engine.RoundResult
import com.estemshan.engine.SessionPlayer
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.accumulateMatchScores
import com.estemshan.engine.calculateRoundScore
import com.estemshan.engine.computeWinner
import com.estemshan.engine.emit
import com.estemshan.engine.emitPlay
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.isFastRound
import com.estemshan.engine.resolveTrick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bot-vs-bot smoke test (S10) — the plan's "single best regression guard for
 * engine + bots". Four bot seats play a complete 18-round match headlessly
 * through the REAL [GameSession], the bidding reducer and the play reducer: the
 * same `emit`/`emitPlay` path a human tap takes, with [BidBrain] and [PlayBrain]
 * supplying every decision.
 *
 * **This is the first test that exercises a whole match rather than one
 * decision**, so it catches exactly what the per-decision goldens cannot: a
 * round-to-round integration bug, a scoring mismatch, a turn-order break, or a
 * brain that is legal in isolation but illegal in sequence.
 *
 * **Reproducibility.** The brains contain no randomness at all — the TS source's
 * `Math.random` sites are replaced by [seatHash] and [decisionDraw] — so the
 * DEAL is the only stochastic input, and it is seeded per round. One seed
 * reproduces an entire match, which is what makes every assertion below a golden
 * rather than a lottery.
 *
 * The harness deliberately does NOT arm Super Call / Sa'ayda round extensions
 * (rounds 19+): those are owned by the online match service, and the offline
 * quick-match path does not arm them either, so the match is exactly 18 rounds.
 */
class BotVsBotSmokeTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  /**
   * Every seat is a different tier AND a different personality, so one match
   * exercises all four skill levels and all four styles — including EASY, whose
   * mistake gate and ±1.20 bid jitter are the widest departure from EXPERT.
   */
  private val mixedRoster: Map<String, Pair<BotTier, BotPersonality>> = mapOf(
    "p1" to (BotTier.EASY to BotPersonality.AGGRESSIVE),
    "p2" to (BotTier.MEDIUM to BotPersonality.BALANCED),
    "p3" to (BotTier.HARD to BotPersonality.CONSERVATIVE),
    "p4" to (BotTier.EXPERT to BotPersonality.TRICKSTER),
  )

  /** A finished match: the session, plus the per-round dealer, which the round
   *  history itself does not carry. */
  private data class PlayedMatch(val session: GameSession, val dealers: List<String>)

  /**
   * Play a full 18-round match between the bot seats in [roster] and return the
   * finished session. Fails loud on any illegal brain output — that contract is
   * the thing under test.
   */
  private fun playMatch(
    seed: Long,
    roster: Map<String, Pair<BotTier, BotPersonality>> = mixedRoster,
  ): PlayedMatch {
    val tier = { seat: String -> roster.getValue(seat).first }
    val personality = { seat: String -> roster.getValue(seat).second }

    var tick = 0L
    val session = GameSession(clock = { ++tick })
    session.init("ai", force = true)
    session.setPlayers(seats.map {
      SessionPlayer(seatId = it, uid = null, displayName = "Bot $it", isAI = true)
    })

    val dealers = ArrayList<String>(18)
    var redealCount = 0

    // Rounds 1..18. The engine's completion contract — pinned by
    // GameSessionTest.matchComplete_atOrPastMaxRounds as "round.number has
    // reached the ceiling" — means the check belongs AFTER a round is banked
    // and BEFORE the advance. Testing it at the top of the loop would see
    // round.number == 18 and skip the last round, ending the match at 17.
    // The services layer applies the same rule through endMatch's
    // `completedRound + 1 > maxRounds` gate: round 18 is always played.
    val maxRounds = session.getRound().maxRounds
    while (session.getRound().number <= maxRounds) {
      val round = session.getRound().number
      var multiplier = session.getRound().multiplier
      val dealer = session.getDealer() ?: error("no dealer in round $round")
      dealers += dealer

      // The round's deal — the ONLY randomness in the match.
      var dealSeed = seed * 1_000_000L + round
      var hands = Dealer.dealHands(dealSeed)

      var bidding = session.initializeBiddingState(
        if (isFastRound(round)) initFastRound(round, dealer, seats, multiplier)
        else initNormalRound(round, dealer, seats, multiplier),
      )

      // ── Bidding: feed intents to the reducer until the auction completes. ──
      var outcome: BiddingOutcome? = null
      var guard = 0
      while (bidding.subPhase != BiddingPhase.DONE && guard++ < 400) {
        val seat = bidding.waitingFor ?: error("round $round: no seat waiting")
        val intent = BidBrain.decide(bidding, hands.getValue(seat), seat, tier(seat), personality(seat))
        bidding = when (val result = emit(bidding, intent)) {
          is EmitResult.Applied -> result.state
          is EmitResult.Completed -> { outcome = result.outcome; result.state }
          is EmitResult.GeneralPass -> {
            // All four passed: the same round redeals at the doubled multiplier,
            // exactly what the quick-match UI does on a general pass.
            check(++redealCount <= 40) { "round $round redealt 40 times — auction cannot close" }
            multiplier = result.doubledMultiplier
            dealSeed += 500L
            hands = Dealer.dealHands(dealSeed)
            session.initializeBiddingState(
              if (isFastRound(round)) initFastRound(round, dealer, seats, multiplier)
              else initNormalRound(round, dealer, seats, multiplier),
            )
          }
          is EmitResult.Rejected -> error(
            "BidBrain produced an illegal intent in round $round for $seat: ${result.reason}",
          )
        }
        session.updateBiddingState(bidding)
      }
      val resolved = outcome ?: error("round $round bidding ended without an outcome")

      // ── Commit the auction, then build the table from the outcome + deal. ──
      session.completeBidding(resolved)
      val cfg = RoundCfg(
        round = round,
        trump = resolved.trump,
        callerId = resolved.callerId,
        withPlayers = resolved.withPlayers,
        estimates = resolved.estimates,
        dashCallers = resolved.dashCallers,
        leaderId = resolved.leaderId,
        riskId = resolved.riskPlayerId,
        multiplier = multiplier,
        hands = hands,
      )

      // ── Play: 13 tricks, resolving each completed trick as it lands. ──
      var table = session.initializePlayState(cfg)
      var trickGuard = 0
      while (table.phase != TablePhase.DONE && trickGuard++ < 400) {
        val seat = table.turn ?: error("round $round trick ${table.trickNo}: no turn")
        val intent = PlayBrain.decide(table, seat, tier(seat))
        table = when (val result = emitPlay(table, intent)) {
          is PlayEmit.Applied ->
            if (result.state.phase == TablePhase.RESOLVING) resolveTrick(result.state) else result.state
          is PlayEmit.Rejected -> error(
            "PlayBrain produced an illegal card in round $round trick ${table.trickNo} for $seat: ${result.reason}",
          )
        }
        session.updatePlayState(table)
      }
      check(table.phase == TablePhase.DONE) { "round $round play never reached DONE" }

      // ── Score, mirroring QuickMatchViewModel.onTableDone exactly. ──
      val score = calculateRoundScore(
        RoundScoreInput(
          round = round,
          order = seats,
          bids = bidsFromRound(cfg),
          tricksWon = table.tricksWon,
          callerId = cfg.callerId,
          withPlayers = cfg.withPlayers,
          multiplier = multiplier,
          riskPlayerId = cfg.riskId,
        ),
      )
      session.setMatchScores(accumulateMatchScores(session.getMatchScores(), score.deltas))
      session.completeRound(table.tricksWon, score.nextMultiplier)
      session.recordRoundResult(roundResult(cfg, table, score))

      // The round just banked was the last one — stop rather than advancing
      // into a round 19 that nothing would ever play.
      if (session.isMatchComplete()) break
      session.nextRound()
    }

    return PlayedMatch(session, dealers)
  }

  /**
   * Scoring bids from a resolved round — the derivation `QuickMatchViewModel`'s
   * `bidsFromOutcome` performs: a Dash-Call seat keeps its type (estimates alone
   * cannot carry it), a 0 estimate is a Normal Dash, else TRICKS.
   */
  private fun bidsFromRound(cfg: RoundCfg): Map<String, Bid> =
    cfg.estimates.keys.union(cfg.dashCallers).associateWith { seat ->
      when {
        cfg.dashCallers.contains(seat) -> Bid(BidType.DASHCALL, 0)
        (cfg.estimates[seat] ?: 0) == 0 -> Bid(BidType.DASH, 0)
        else -> Bid(BidType.TRICKS, cfg.estimates.getValue(seat))
      }
    }

  private fun roundResult(cfg: RoundCfg, table: TableState, score: RoundScoreResult): RoundResult =
    RoundResult(
      round = cfg.round,
      trump = cfg.trump,
      callerId = cfg.callerId,
      tricksWon = table.tricksWon,
      estimates = cfg.estimates,
      scoreDeltas = score.deltas,
      riskPlayerId = score.riskPlayerId,
      totalBids = score.totalBids,
      isOver = score.isOver,
      isSaayda = score.isSaayda,
      appliedMultiplier = score.appliedMultiplier,
      nextMultiplier = score.nextMultiplier,
      // Extensions (rounds 19+) are armed by the online match service, not this
      // harness — the offline path does not raise maxRounds either.
      extensionReason = null,
    )

  // ==========================================================================
  //  The match completes
  // ==========================================================================

  @Test
  fun aFullEighteenRoundMatchCompletesBetweenFourBots() {
    val match = playMatch(seed = 42L)
    val session = match.session
    val history = session.getRoundHistory()

    assertTrue("the match must be complete", session.isMatchComplete())
    assertEquals("a match is 18 rounds", 18, history.size)
    assertEquals(
      "round numbers run 1..18 with no gaps",
      (1..18).toList(),
      history.map { it.round },
    )
    // Every seat walked away with a score.
    seats.forEach {
      assertTrue("seat $it has no final score", session.getMatchScores().containsKey(it))
    }
  }

  @Test
  fun theDealerRotatesEveryRoundOfTheMatch() {
    val match = playMatch(seed = 42L)

    // p1 opens and the dealer advances one seat per round — the property the
    // auction-leader fix restored, exercised here for the first time over a
    // whole match rather than a single constructed round.
    assertEquals((1..18).map { seats[(it - 1) % seats.size] }, match.dealers)
  }

  // ==========================================================================
  //  Per-round invariants
  // ==========================================================================

  @Test
  fun everyRoundAccountsForAllThirteenTricksAndNeverTotalsThirteen() {
    val history = playMatch(seed = 42L).session.getRoundHistory()

    history.forEach { round ->
      assertEquals(
        "round ${round.round} must account for all 13 tricks",
        13,
        round.tricksWon.values.sum(),
      )
      // The forbidden-13 rule, at match scale through the real reducer: the last
      // seat to declare can never pick the number that makes the round total
      // exactly 13, so no completed round ever lands there.
      assertTrue("round ${round.round} totalled exactly 13 bids", round.totalBids != 13)
    }
  }

  // ==========================================================================
  //  Scores are consistent end to end
  // ==========================================================================

  @Test
  fun theFinalTotalsAreExactlyTheAccumulatedRoundDeltas() {
    val session = playMatch(seed = 42L).session

    val summed = session.getRoundHistory()
      .flatMap { it.scoreDeltas.entries }
      .groupBy({ it.key }, { it.value })
      .mapValues { (_, deltas) -> deltas.sum() }

    assertEquals(
      "final standings must be the sum of the per-round deltas — nothing invented or lost",
      summed,
      session.getMatchScores(),
    )
  }

  @Test
  fun theSameSeedReproducesTheMatchAndTheWinnersAreTheTopScorers() {
    val first = playMatch(seed = 42L).session
    val again = playMatch(seed = 42L).session

    assertEquals("the same seed must reproduce every round", first.getRoundHistory(), again.getRoundHistory())
    assertEquals("the same seed must reproduce the final totals", first.getMatchScores(), again.getMatchScores())

    // House rules: every seat tied at the highest score is a King, with no
    // tie-breaker — so the winner list is exactly the top-scoring seats.
    val winners = computeWinner(first.getMatchScores())
    assertTrue("a finished match must have at least one winner", winners.isNotEmpty())
    val top = first.getMatchScores().values.max()
    winners.forEach { seat ->
      assertEquals("winner $seat must be tied at the top", top, first.getMatchScores().getValue(seat))
    }
    // And every seat NOT in the list scored strictly less.
    first.getMatchScores().forEach { (seat, score) ->
      if (seat !in winners) assertTrue("non-winner $seat scored $score, equal to the top $top", score < top)
    }
  }

  // ==========================================================================
  //  The tiers are load-bearing
  // ==========================================================================

  @Test
  fun theTiersActuallyChangeTheMatch() {
    // Same seed, same deals, a table of four EXPERTs instead of the mixed roster.
    // If the tier config were inert the two matches would be identical — this is
    // the plan's "all 4 tiers observably differ" property, tested differentially
    // rather than by hoping four random deals happen to diverge.
    val mixed = playMatch(seed = 42L).session
    val allExpert = playMatch(
      seed = 42L,
      roster = seats.associateWith { BotTier.EXPERT to BotPersonality.BALANCED },
    ).session

    assertNotEquals(
      "an all-EXPERT table must not play the same match as the mixed roster",
      mixed.getRoundHistory(),
      allExpert.getRoundHistory(),
    )
  }
}
