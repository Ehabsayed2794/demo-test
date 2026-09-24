package com.estemshan.engine.bot

import com.estemshan.engine.Card
import com.estemshan.engine.Play
import com.estemshan.engine.RANKS
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.Suit.CLUBS
import com.estemshan.engine.Suit.DIAMONDS
import com.estemshan.engine.Suit.HEARTS
import com.estemshan.engine.Suit.SPADES
import com.estemshan.engine.initTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens for [SpoilerStrategy] (S8) — the defensive-play module that takes over
 * [PlayBrain] once a seat's own contract is already lost. S8 shipped without its
 * own file; these are the "spoiler gating" tests the plan's S10 row asks for.
 *
 * Every expectation is hand-computed from the three gates in [SpoilerStrategy.choose]
 * — tier, busted, and target value — and from [com.estemshan.engine.cardValue]
 * (`rank.v + (trump ? 1000 : follows ? 100 : 0)`), with the arithmetic left in the
 * comments so a failing test names the branch that moved.
 *
 * States are mid-round snapshots built with `copy()` rather than driven, matching
 * [PlayBrainTest]: the engine never audits deck accounting (`legalCards` and
 * `cardValue` read only what [com.estemshan.engine.TableState] holds), so hand
 * sizes are whatever the bid arithmetic needs and the played cards in a snapshot
 * trick are not required to have left anyone's hand. What has to be exact is the
 * *bid* arithmetic — `remaining = hands[seat].size` and `won = tricksWon[seat]` —
 * because that is what `isBusted` and `classify` read.
 */
class SpoilerStrategyTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun c(suit: Suit, value: Int): Card = Card(suit, RANKS.first { it.v == value })

  /** [size] identical filler cards — only the SIZE feeds the bid arithmetic, and
   *  the engine never audits duplicates in a test snapshot. */
  private fun fill(size: Int): List<Card> = List(size) { c(CLUBS, 2) }

  /**
   * A mid-round table whose turn is [leader]. [hands] supplies every seat's cards;
   * the estimates and tricks won are exactly what `isBusted`/`classify` read.
   */
  private fun table(
    estimates: Map<String, Int>,
    tricksWon: Map<String, Int>,
    hands: Map<String, List<Card>>,
    trump: Suit = SPADES,
    callerId: String? = "p2",
    dashCallers: List<String> = emptyList(),
    leader: String = "p1",
    trickNo: Int = 2,
    plays: List<Play> = emptyList(),
  ): TableState {
    val fresh = initTable(
      RoundCfg(
        round = 1,
        trump = trump,
        callerId = callerId,
        withPlayers = emptyList(),
        estimates = estimates,
        dashCallers = dashCallers,
        leaderId = leader,
        riskId = null,
        multiplier = 1,
        hands = hands,
      ),
      seats,
    )
    return fresh.copy(
      trickNo = trickNo,
      tricksWon = seats.associateWith { tricksWon[it] ?: 0 },
      plays = plays,
      ledSuit = plays.firstOrNull()?.card?.suit,
    )
  }

  // ==========================================================================
  //  Gating — when does defensive play engage at all?
  // ==========================================================================

  /** p1 bid 2 and already won 5 (busted); p2 the caller still needs tricks. */
  private fun bustedTable(
    estimates: Map<String, Int> = mapOf("p1" to 2, "p2" to 1, "p3" to 7, "p4" to 3),
    callerId: String? = "p2",
    p1: List<Card> = listOf(c(SPADES, 5), c(HEARTS, 3), c(CLUBS, 2), c(DIAMONDS, 9)),
    tricksWon: Map<String, Int> = mapOf("p1" to 5),
    sizes: Map<String, Int> = mapOf("p2" to 6, "p3" to 9, "p4" to 4),
  ): TableState = table(
    estimates = estimates,
    tricksWon = tricksWon,
    hands = mapOf("p1" to p1) + sizes.mapValues { (seat, n) -> fill(n) },
    callerId = callerId,
  )

  @Test
  fun anEasyTierNeverSpoilsEvenWhenBusted() {
    // Beginners don't think defensively, no matter how lost their own bid is.
    val state = bustedTable()

    assertNull(SpoilerStrategy.choose(state, "p1", BotTier.EASY))
  }

  @Test
  fun aSeatStillAbleToMakeItsBidNeverSpoils() {
    // p1 bid 3, won 1, holds 6: 1 > 3 is false and 1 + 6 >= 3, so there is
    // everything left to lose — the spoiler must stand down at every tier.
    val state = table(
      estimates = mapOf("p1" to 3, "p2" to 1, "p3" to 7, "p4" to 3),
      tricksWon = mapOf("p1" to 1),
      hands = mapOf("p1" to fill(6), "p2" to fill(6), "p3" to fill(9), "p4" to fill(6)),
    )

    BotTier.entries.forEach { tier ->
      assertNull("$tier must not spoil a makeable bid", SpoilerStrategy.choose(state, "p1", tier))
    }
  }

  // ==========================================================================
  //  Target choice — who gets spoiled, at which tier
  // ==========================================================================

  @Test
  fun hardAndExpertSpoilTheTopValueRankedTarget() {
    // p3 (bid 7, an ordinary seat) is worth 10 + 7 = 17; p2 (the caller, bid 1)
    // is worth 15 + 1 = 16; p4 (bid 3) is worth 13. Full pattern recognition
    // takes the juiciest: p3, even though it is NOT an obvious target.
    val state = bustedTable()

    val expert = SpoilerStrategy.choose(state, "p1", BotTier.EXPERT)
    val hard = SpoilerStrategy.choose(state, "p1", BotTier.HARD)

    assertNotNull(hard)
    assertNotNull(expert)
    assertTrue("EXPERT must target p3 (17), not the caller p2 (16): ${expert!!.reasoning}",
      expert.reasoning.contains("p3"))
    assertTrue("HARD must target p3 (17), not the caller p2 (16): ${hard!!.reasoning}",
      hard.reasoning.contains("p3"))
    // Leading + DENY grabs the trick with the highest card in hand: the 9 ♦ (9)
    // beats the 5 ♠, 3 ♥ and 2 ♣.
    assertEquals(c(DIAMONDS, 9), expert.card)
    assertEquals(c(DIAMONDS, 9), hard.card)
  }

  @Test
  fun mediumSpoilsOnlyAnObviousTarget() {
    // Same table: MEDIUM lacks the value-ranked pattern, so it sees only the
    // OBVIOUS target — p2, the known Caller (16), missing the higher-value p3.
    val state = bustedTable()

    val medium = SpoilerStrategy.choose(state, "p1", BotTier.MEDIUM)

    assertNotNull(medium)
    assertTrue("MEDIUM must fall back to the obvious caller p2: ${medium!!.reasoning}",
      medium.reasoning.contains("p2") && !medium.reasoning.contains("p3"))
  }

  @Test
  fun mediumFallsThroughToTheHeuristicWhenNoTargetIsObvious() {
    // A callerless round where nobody is obvious (no dash caller, no bid 0, no
    // bid >= 8, no caller): p2 = 14, p3 = 15, p4 = 16. MEDIUM has nothing
    // staring at it and returns null; EXPERT still picks the top, p4.
    val state = bustedTable(
      estimates = mapOf("p1" to 2, "p2" to 4, "p3" to 5, "p4" to 6),
      callerId = null,
      sizes = mapOf("p2" to 6, "p3" to 6, "p4" to 6),
    )

    assertNull(SpoilerStrategy.choose(state, "p1", BotTier.MEDIUM))

    val expert = SpoilerStrategy.choose(state, "p1", BotTier.EXPERT)
    assertNotNull(expert)
    assertTrue("EXPERT must still take the top target p4 (16): ${expert!!.reasoning}",
      expert.reasoning.contains("p4"))
  }

  // ==========================================================================
  //  Direction — DENY vs FORCE card choices
  // ==========================================================================

  /** p1 busted (bid 2, won 5) follows p2's ♥K; the caller still needs tricks. */
  private fun followingTable(p2Bid: Int, p2Won: Int, p1: List<Card>): TableState = table(
    estimates = mapOf("p1" to 2, "p2" to p2Bid, "p3" to 3, "p4" to 4),
    tricksWon = mapOf("p1" to 5, "p2" to p2Won, "p3" to p2Won, "p4" to p2Won),
    hands = mapOf("p1" to p1, "p2" to fill(5), "p3" to fill(4), "p4" to fill(4)),
    plays = listOf(Play("p2", c(HEARTS, 13))), // ♥K led: 13 + 100 = 113, p2 winning
  )

  @Test
  fun denyStealsTheTrickFromTheWinningTarget() {
    // p2 bid 2, won 0, holds 5 → needs 2, DENY. p2 is on top with the ♥K (113);
    // the only card beating it is the ♥A (14 + 100 = 114) — the cheapest winner.
    val state = followingTable(p2Bid = 2, p2Won = 0, p1 = listOf(c(HEARTS, 14), c(HEARTS, 2)))

    val choice = SpoilerStrategy.choose(state, "p1", BotTier.EXPERT)

    assertNotNull(choice)
    assertEquals("must steal with the cheapest winner, the A ♥", c(HEARTS, 14), choice!!.card)
    assertTrue("must name the steal: ${choice.reasoning}", choice.reasoning.contains("steal"))
  }

  @Test
  fun forceLetsTheWinningTargetKeepTheTrickAndShedsHigh() {
    // p2 bid 1 and already won it (needs 0) → FORCE: push one MORE trick onto
    // them. They are winning the ♥K; don't overtake — shed the highest card that
    // still loses, the ♥Q (12 + 100 = 112 <= 113), keeping the ♥2 in reserve.
    val state = followingTable(p2Bid = 1, p2Won = 1, p1 = listOf(c(HEARTS, 12), c(HEARTS, 2)))

    val choice = SpoilerStrategy.choose(state, "p1", BotTier.EXPERT)

    assertNotNull(choice)
    assertEquals("must shed the Q ♥, not the 2 ♥", c(HEARTS, 12), choice!!.card)
    assertTrue("must let them keep it: ${choice.reasoning}", choice.reasoning.contains("let them keep"))
  }

  @Test
  fun forceLeadsLowToPushATrickOntoTheTarget() {
    // Leading against a FORCE target (p2 bid 1, won 1): bait them into winning
    // by leading the lowest NON-trump, so the trick stays cheap to lose — the
    // 2 ♣ (2), not the 3 ♥ (3), the 10 ♥ (10) or the trump 5 ♠.
    val state = bustedTable(
      estimates = mapOf("p1" to 2, "p2" to 1, "p3" to 3, "p4" to 4),
      tricksWon = mapOf("p1" to 5, "p2" to 1, "p3" to 1, "p4" to 1),
      p1 = listOf(c(SPADES, 5), c(HEARTS, 3), c(HEARTS, 10), c(CLUBS, 2)),
      sizes = mapOf("p2" to 5, "p3" to 4, "p4" to 4),
    )

    val choice = SpoilerStrategy.choose(state, "p1", BotTier.EXPERT)

    assertNotNull(choice)
    assertEquals("must lead the lowest non-trump, the 2 ♣", c(CLUBS, 2), choice!!.card)
    assertTrue("must push a trick onto them: ${choice.reasoning}", choice.reasoning.contains("push a trick"))
  }

  // ==========================================================================
  //  Routing — PlayBrain actually dispatches through the spoiler
  // ==========================================================================

  @Test
  fun playBrainPlaysTheSpoilerCardForABustedSeat() {
    // The S7/S8 seam: a busted EXPERT seat's card comes from the spoiler, not
    // the need/slack heuristic. p1 is busted following p2's winning ♥K, and the
    // only winner in hand is the ♥A — the DENY steal. EXPERT has mistakeRate
    // 0.00, so the mistake gate before the spoiler can never fire.
    val state = followingTable(p2Bid = 2, p2Won = 0, p1 = listOf(c(HEARTS, 14), c(HEARTS, 2)))

    val played = PlayBrain.decide(state, "p1", BotTier.EXPERT)

    assertEquals(c(HEARTS, 14), played.card)
  }
}
