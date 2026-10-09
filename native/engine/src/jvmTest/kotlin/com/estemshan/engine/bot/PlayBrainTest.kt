package com.estemshan.engine.bot

import com.estemshan.engine.Card
import com.estemshan.engine.Dealer
import com.estemshan.engine.PlayCard
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RANKS
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.emitPlay
import com.estemshan.engine.initTable
import com.estemshan.engine.resolveTrick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens for [PlayBrain] (S7), ported-and-pinned from `AI Bots/botPlay.ts`'s
 * `selectBotCard`. Every expectation below is hand-computed from
 * [com.estemshan.engine.cardValue] — `rank.v + (trump ? 1000 : follows ? 100 : 0)`
 * — and from the branch order in [PlayBrain.choose], with the arithmetic left in
 * the comments so a regression names the branch that moved.
 *
 * Behavioural tests run EXPERT (mistakeRate 0.00) so the deterministic mistake
 * draw can never perturb an expectation. The mistake path gets its own test,
 * [aWeakTierMisleadsOnADrawABetterTierDeclines], which pins the exact
 * [decisionDraw] value that fires it.
 *
 * Hands are synthetic mid-round snapshots of 4 cards. The engine never audits
 * deck accounting (`legalCards`/`cardValue` read only what [TableState] holds),
 * so a 4-card hand at trick 2 is a legal state even though a real round would
 * be holding 12 — noted inline only where the snapshot is the point.
 */
class PlayBrainTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun c(suit: Suit, value: Int): Card = Card(suit, RANKS.first { it.v == value })

  /** A fresh PLAY state whose turn is [leader], with the given per-seat hands. */
  private fun table(
    hands: Map<String, List<Card>>,
    estimates: Map<String, Int> = seats.associateWith { 3 },
    trump: Suit = Suit.SPADES,
    leader: String = "p1",
    trickNo: Int = 1,
    seenCards: List<Card> = emptyList(),
    tricksWon: Map<String, Int> = emptyMap(),
  ): TableState {
    val fresh = initTable(
      RoundCfg(
        round = 1,
        trump = trump,
        callerId = leader,
        withPlayers = emptyList(),
        estimates = estimates,
        dashCallers = emptyList(),
        leaderId = leader,
        riskId = "p4",
        multiplier = 1,
        hands = hands,
      ),
      seats,
    )
    // A mid-round snapshot: trickNo > 1 or a populated seenCards implies a
    // leader who already won one and cards that already left the table. Built
    // with copy() rather than driven, because driving would fix the hand to 13.
    return if (trickNo == 1 && seenCards.isEmpty() && tricksWon.isEmpty()) fresh
    else fresh.copy(
      trickNo = trickNo,
      seenCards = seenCards,
      tricksWon = seats.associateWith { tricksWon[it] ?: 0 },
    )
  }

  private fun applied(result: PlayEmit): TableState = when (result) {
    is PlayEmit.Applied -> result.state
    is PlayEmit.Rejected -> error("expected Applied, got Rejected(${result.reason})")
  }

  /** Drives one legal play through the engine, failing loud if it is refused. */
  private fun play(s: TableState, seat: String, card: Card): TableState =
    applied(emitPlay(s, PlayCard(seat, card)))

  private fun decide(s: TableState, seat: String, tier: BotTier = BotTier.EXPERT): Card =
    PlayBrain.decide(s, seat, tier).card

  // ── Following: the engine's legality check is the single authority ──────────

  @Test
  fun followsSuitWhenAHeartIsLedAndAHeartIsHeld() {
    // ledSuit = HEARTS (p1's ♥5), so legalCards() returns only the two hearts
    // in p2's hand; the brain may not reach for a club no matter what its
    // branch wants.
    var s = table(
      hands = mapOf(
        "p1" to listOf(c(Suit.HEARTS, 5), c(Suit.CLUBS, 2)),
        "p2" to listOf(c(Suit.HEARTS, 13), c(Suit.HEARTS, 3), c(Suit.CLUBS, 2), c(Suit.CLUBS, 3)),
      ),
    )
    s = play(s, "p1", c(Suit.HEARTS, 5))

    assertEquals("must follow the led suit", Suit.HEARTS, decide(s, "p2").suit)
  }

  @Test
  fun winsCheaplyWhenTricksAreStillNeeded() {
    // trump ♠, led ♥. cardValue(♥5) = 5 + 100 = 105 is the card to beat.
    // p2: estimate 3, tricksWon 0 → need 3; 4 cards → slack 1.
    // ♥K = 13 + 100 = 113 > 105 → winner;  ♥3 = 3 + 100 = 103 → loser.
    // Control-retention does not engage: bossesInHand = 0 (♥A and ♥Q are all
    // still unaccounted, so nothing in hand is a confirmed boss) < need 3.
    // Cheapest winner = ♥K.
    var s = table(
      hands = mapOf(
        "p1" to listOf(c(Suit.HEARTS, 5), c(Suit.CLUBS, 2)),
        "p2" to listOf(c(Suit.HEARTS, 13), c(Suit.HEARTS, 3), c(Suit.CLUBS, 2), c(Suit.CLUBS, 3)),
      ),
    )
    s = play(s, "p1", c(Suit.HEARTS, 5))

    assertEquals(c(Suit.HEARTS, 13), decide(s, "p2"))
  }

  @Test
  fun ducksLowWhenItCannotWinTheTrick() {
    // p1 leads ♥A = 14 + 100 = 114 — nothing in p2's hand beats it
    // (♥10 = 110, ♥4 = 104). need 3 > 0 but winners is empty, so the cheapest
    // card it owns is the answer: ♥4, keeping the 10 for a trick it can take.
    var s = table(
      hands = mapOf(
        "p1" to listOf(c(Suit.HEARTS, 14), c(Suit.CLUBS, 2)),
        "p2" to listOf(c(Suit.HEARTS, 10), c(Suit.HEARTS, 4), c(Suit.DIAMONDS, 2), c(Suit.DIAMONDS, 3)),
      ),
    )
    s = play(s, "p1", c(Suit.HEARTS, 14))

    assertEquals(c(Suit.HEARTS, 4), decide(s, "p2"))
  }

  @Test
  fun shedsHighWhenTheBidIsAlreadyMade() {
    // p2 bid 1 and already won 1 → need 0, so taking more tricks is a cost.
    // p1's ♥A = 114 means p2's ♥Q = 112 and ♥J = 111 are both LOSERS, so the
    // avoid branch dumps the highest non-trump loser: ♥Q. This is the branch
    // that separates "avoid winning" from "cannot win" — the duck-low path
    // would have played ♥J.
    var s = table(
      hands = mapOf(
        "p1" to listOf(c(Suit.HEARTS, 14), c(Suit.CLUBS, 2)),
        "p2" to listOf(c(Suit.HEARTS, 12), c(Suit.HEARTS, 11), c(Suit.DIAMONDS, 5), c(Suit.DIAMONDS, 6)),
      ),
      estimates = mapOf("p1" to 3, "p2" to 1, "p3" to 3, "p4" to 3),
      tricksWon = mapOf("p2" to 1),
    )
    s = play(s, "p1", c(Suit.HEARTS, 14))

    assertEquals(c(Suit.HEARTS, 12), decide(s, "p2"))
  }

  // ── Leading: pacing vs. must-win ─────────────────────────────────────────────

  @Test
  fun pacesByLeadingLowWhileSlackRemains() {
    // p1 leads an empty trick. estimate 3, tricksWon 0 → need 3 of 4 cards,
    // slack 1 > 0 → pacing, not must-win: lead the lowest NON-trump. ♣2 (2)
    // under ♥9 (9) and ♣10 (10); the trump ♠4 is deliberately held back.
    val s = table(
      hands = mapOf(
        "p1" to listOf(c(Suit.CLUBS, 2), c(Suit.CLUBS, 10), c(Suit.HEARTS, 9), c(Suit.SPADES, 4)),
      ),
    )

    assertEquals(c(Suit.CLUBS, 2), decide(s, "p1"))
  }

  @Test
  fun leadsTheTopTrumpWhenEveryTrickMustBeWon() {
    // need 4 with 4 cards → slack 0 → must-win. No side-suit boss is
    // counting-confirmed (♥3 and ♣5 both have unaccounted higher cards), so
    // the branch falls past the boss cash to the top trump in hand:
    // ♠Q (12) over ♠8 (8).
    val s = table(
      hands = mapOf(
        "p1" to listOf(c(Suit.SPADES, 12), c(Suit.SPADES, 8), c(Suit.HEARTS, 3), c(Suit.CLUBS, 5)),
      ),
      estimates = mapOf("p1" to 4, "p2" to 3, "p3" to 3, "p4" to 3),
    )

    assertEquals(c(Suit.SPADES, 12), decide(s, "p1"))
  }

  // ── Card counting — the whole reason seenCards exists ────────────────────────

  @Test
  fun seenCardsPromoteAQueenToAGuaranteedWinner() {
    // ♥A (14) and ♥K (13) have already been played this round, so p1's ♥Q is
    // now the top heart left in the game — isBoss(♥Q) is true ONLY because
    // those two ids are in seenCards. must-win (need 4 of 4) cashes the
    // confirmed boss over the higher raw-value trump.
    //
    // The second state is the same hand with an EMPTY accumulator: ♥Q is no
    // longer confirmed (♥K is "still out"), the boss list empties, and the
    // branch falls through to the top trump — ♠5 over ♠3. The two assertions
    // back-to-back are what pins the accumulator to the decision.
    val hand = listOf(c(Suit.HEARTS, 12), c(Suit.HEARTS, 2), c(Suit.SPADES, 3), c(Suit.SPADES, 5))
    val estimates = mapOf("p1" to 4, "p2" to 3, "p3" to 3, "p4" to 3)

    val withHistory = table(
      hands = mapOf("p1" to hand),
      estimates = estimates,
      trickNo = 2,
      seenCards = listOf(c(Suit.HEARTS, 14), c(Suit.HEARTS, 13)),
    )
    assertEquals(c(Suit.HEARTS, 12), decide(withHistory, "p1"))

    val noHistory = table(hands = mapOf("p1" to hand), estimates = estimates)
    assertEquals("without the accumulator the boss is unconfirmed → top trump", c(Suit.SPADES, 5), decide(noHistory, "p1"))
  }

  @Test
  fun resolveTrickKeepsEveryPlayedCardInSeenCards() {
    // The accumulator's contract: plays holds only the in-progress trick and
    // resolveTrick clears it, so the resolved cards must migrate to seenCards
    // or the round's history is gone the moment the trick completes.
    // All clubs, trump ♠ → cardValue = rank + 100 for every play, so the ace
    // wins: 114 > 113 (K) > 105 (5) > 102 (2). Winner p4 leads trick 2.
    var s = table(
      hands = mapOf(
        "p1" to listOf(c(Suit.CLUBS, 2), c(Suit.CLUBS, 10)),
        "p2" to listOf(c(Suit.CLUBS, 13), c(Suit.CLUBS, 9)),
        "p3" to listOf(c(Suit.CLUBS, 5), c(Suit.CLUBS, 4)),
        "p4" to listOf(c(Suit.CLUBS, 14), c(Suit.CLUBS, 3)),
      ),
    )
    assertTrue("starts empty", s.seenCards.isEmpty())

    s = play(s, "p1", c(Suit.CLUBS, 2))
    s = play(s, "p2", c(Suit.CLUBS, 13))
    s = play(s, "p3", c(Suit.CLUBS, 5))
    s = play(s, "p4", c(Suit.CLUBS, 14))
    assertEquals("trick complete → RESOLVING", TablePhase.RESOLVING, s.phase)
    assertTrue("not yet resolved → still empty", s.seenCards.isEmpty())

    s = resolveTrick(s)
    assertEquals(TablePhase.PLAY, s.phase)
    assertEquals(2, s.trickNo)
    assertEquals("p4", s.leaderId)
    assertEquals("p4", s.turn)
    assertEquals("plays cleared for the new trick", emptyList<Any>(), s.plays)
    assertEquals(
      "all four cards, in play order",
      listOf("CLUBS-2", "CLUBS-13", "CLUBS-5", "CLUBS-14"),
      s.seenCards.map { it.id },
    )
    assertTrue("migrated cards carry the played flag", s.seenCards.all { it.played })
    assertEquals("p4 won the trick", 1, s.tricksWon["p4"])
  }

  // ── Determinism: no Math.random anywhere in the play path (risk R6) ─────────

  @Test
  fun theSameStateAlwaysYieldsTheSameCard() {
    // decide() must be a pure function of (state, seat, tier). Two calls on an
    // equal state agree, and the first call leaves the state untouched — no
    // hidden mutation, no consumed iterator.
    val s = table(
      hands = mapOf(
        "p1" to listOf(c(Suit.CLUBS, 2), c(Suit.CLUBS, 10), c(Suit.HEARTS, 9), c(Suit.SPADES, 4)),
      ),
    )

    assertEquals(decide(s, "p1"), decide(s, "p1"))
    assertEquals("state untouched by deciding", 4, s.cfg.hands["p1"]?.size)
  }

  @Test
  fun aWeakTierMisleadsOnADrawABetterTierDeclines() {
    // The mistake gate: decisionDraw(playerId, round, trickNo, decisionIndex)
    // where decisionIndex = state.plays.size (0 — p2 is leading). For key
    // "p2:1:1:0" the 31-multiplier polynomial hash folds to 463775140, so the
    // draw is |−140|/1000 = 0.140; the salted pick ("p2:1:1:0:1") is 0.139.
    //
    //   EASY   mistakeRate 0.25 → 0.140 < 0.25 FIRES. pick 0.139 × 4 legal cards
    //                                  = 0.556 → index 0 → legal[0] = ♣10.
    //   MEDIUM mistakeRate 0.10 → 0.140 ≥ 0.10 declines.
    //   HARD   mistakeRate 0.03 → declines.
    //   EXPERT mistakeRate 0.00 → declines by construction.
    //
    // The three better tiers all take the heuristic lead: pacing (need 1 of 4,
    // slack 3) leads the lowest non-trump = ♣5. Only EASY diverges, and it
    // diverges to a specific, reproducible card rather than a random one.
    val hand = listOf(c(Suit.CLUBS, 10), c(Suit.CLUBS, 5), c(Suit.HEARTS, 9), c(Suit.SPADES, 2))
    val s = table(
      hands = mapOf("p2" to hand),
      estimates = mapOf("p1" to 3, "p2" to 1, "p3" to 3, "p4" to 3),
      leader = "p2",
    )

    assertEquals("EXPERT declines", c(Suit.CLUBS, 5), decide(s, "p2", BotTier.EXPERT))
    assertEquals("HARD declines", c(Suit.CLUBS, 5), decide(s, "p2", BotTier.HARD))
    assertEquals("MEDIUM declines (0.140 ≥ 0.10)", c(Suit.CLUBS, 5), decide(s, "p2", BotTier.MEDIUM))
    assertEquals("EASY misleads to legal[0]", c(Suit.CLUBS, 10), decide(s, "p2", BotTier.EASY))
  }

  // ── Contract guards: fail loud rather than emit a bad intent ─────────────────

  @Test
  fun refusesToDecideOutsideThePlayPhase() {
    // A DONE table has no card to pick; returning one would hand BotDriver an
    // intent that emitPlay is guaranteed to reject. IllegalArgumentException, not
    // a silent null.
    val done = table(hands = mapOf("p1" to listOf(c(Suit.CLUBS, 2)))).copy(phase = TablePhase.DONE)

    assertThrows(IllegalArgumentException::class.java) {
      PlayBrain.decide(done, "p1", BotTier.EXPERT)
    }
  }

  @Test
  fun refusesToDecideForASeatWhoseTurnItIsNot() {
    // turn is p1 (the leader); asking for p2's card is a caller/turn-order bug
    // and must surface here, not as a rejected emit downstream.
    val s = table(hands = mapOf("p1" to listOf(c(Suit.CLUBS, 2)), "p2" to listOf(c(Suit.HEARTS, 3))))

    assertThrows(IllegalArgumentException::class.java) {
      PlayBrain.decide(s, "p2", BotTier.EXPERT)
    }
  }

  @Test
  fun refusesToDecideForASeatWithNoHand() {
    // The reconnect path: a genuinely missing hand means "nothing is legal",
    // and the brain must not synthesise a card above that (the failsafe inside
    // choose() is for an empty LEGAL list off a real hand, not a missing one).
    val s = table(hands = emptyMap())

    assertThrows(IllegalArgumentException::class.java) {
      PlayBrain.decide(s, "p1", BotTier.EXPERT)
    }
  }

  // ── End to end: four brains drive a whole round through the real engine ─────

  /**
   * Plays a seeded deal out to DONE with every seat on [tier], asserting only
   * invariants that hold for any deal: the engine accepts every brain intent
   * (the legality check never disagrees with the brain), all 52 cards land in
   * [TableState.seenCards] exactly once, and exactly 13 tricks are awarded.
   */
  private fun driveRound(seed: Long, tier: BotTier): TableState {
    var s = initTable(
      RoundCfg(
        round = 1,
        trump = Suit.SPADES,
        callerId = "p1",
        withPlayers = emptyList(),
        estimates = seats.associateWith { 3 },
        dashCallers = emptyList(),
        leaderId = "p1",
        riskId = "p4",
        multiplier = 1,
        hands = Dealer.dealHands(seed),
      ),
      seats,
    )
    var guard = 0
    while (s.phase != TablePhase.DONE) {
      check(guard++ < 10_000) { "round did not terminate at trick ${s.trickNo}" }
      if (s.phase == TablePhase.PLAY) {
        val seat = s.turn ?: error("PLAY phase with no turn at trick ${s.trickNo}")
        // The contract in one line: the brain's intent goes straight through
        // the same emitPlay() a human tap takes, with no retry layer.
        s = applied(emitPlay(s, PlayBrain.decide(s, seat, tier)))
      } else {
        s = resolveTrick(s)
      }
    }
    return s
  }

  @Test
  fun fourExpertBotsCompleteARoundWithNoRejectedIntent() {
    val s = driveRound(seed = 11L, BotTier.EXPERT)

    assertEquals(TablePhase.DONE, s.phase)
    assertEquals(13, s.trickNo)
    // 13 tricks × 4 seats = 52 cards, every one accounted for exactly once.
    assertEquals("all cards survived resolveTrick's clear", 52, s.seenCards.size)
    assertEquals("no duplicates in history", 52, s.seenCards.map { it.id }.toSet().size)
    // Every trick has exactly one winner, so 13 tricks are distributed among
    // the four seats.
    assertEquals(13, s.tricksWon.values.sum())
    assertTrue("all hands empty at DONE", s.cfg.hands.values.all { it.isEmpty() })
  }

  @Test
  fun aBotRoundIsReproducibleAcrossRuns() {
    // EXPERT never draws a mistake, but the claim is broader: nothing in the
    // play path may touch Random. Two independent drives of the same seed must
    // produce identical histories — this is the property S10's bot-vs-bot
    // 18-round smoke test depends on.
    val a = driveRound(seed = 7L, BotTier.EXPERT)
    val b = driveRound(seed = 7L, BotTier.EXPERT)

    assertEquals(a.tricksWon, b.tricksWon)
    assertEquals(a.seenCards.map { it.id }, b.seenCards.map { it.id })
  }
}
