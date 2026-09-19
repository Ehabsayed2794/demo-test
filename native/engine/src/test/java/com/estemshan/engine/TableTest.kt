package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit mirror of the pinned JS table cases:
 * tests/table-engine-foundation-fix.test.cjs (fresh config per initState,
 * full 13-trick drive to DONE) and the follow-suit/trump/void rules of
 * design-ui/engine/table-engine.js. Same numbers, Kotlin implementation.
 */
class TableTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun cfg(
    trump: Suit = Suit.SPADES,
    caller: String? = "p1",
    leader: String = "p1",
    round: Int = 1,
    hands: Map<String, List<Card>> = Dealer.dealHands(7),
    estimates: Map<String, Int> = mapOf("p1" to 3, "p2" to 4, "p3" to 3, "p4" to 3),
  ): RoundCfg = RoundCfg(
    round = round,
    trump = trump,
    callerId = caller,
    withPlayers = emptyList(),
    estimates = estimates,
    dashCallers = emptyList(),
    leaderId = leader,
    riskId = "p4",
    multiplier = 1,
    hands = hands,
  )

  private fun applied(result: PlayEmit): TableState = when (result) {
    is PlayEmit.Applied -> result.state
    is PlayEmit.Rejected -> throw AssertionError("expected Applied, got Rejected(${result.reason})")
  }

  private fun play(s: TableState, seat: String, card: Card): TableState {
    val legality = canPlayCard(s, seat, card)
    assertTrue("play $seat ${card.displayName} legal: ${legality.reason}", legality.legal)
    return applied(emitPlay(s, PlayCard(seat, card)))
  }

  // ── Caller leads trick 1; config is read fresh at every init ──
  @Test
  fun callerLeadsTrick1() {
    val s = initTable(cfg(caller = "p1", leader = "p1"), seats)
    assertEquals(TablePhase.PLAY, s.phase)
    assertEquals(1, s.trickNo)
    assertEquals("p1", s.leaderId)
    assertEquals("p1", s.turn)
    assertEquals(mapOf("p1" to 0, "p2" to 0, "p3" to 0, "p4" to 0), s.tricksWon)
  }

  @Test
  fun freshConfigPerInit() {
    // Foundation Fix pin: no frozen snapshot — each init reflects its own config.
    val r1 = initTable(cfg(trump = Suit.HEARTS, caller = "p1", leader = "p1", round = 1), seats)
    val r2 = initTable(cfg(trump = Suit.CLUBS, caller = "p3", leader = "p3", round = 2), seats)
    assertEquals(Suit.HEARTS, r1.cfg.trump)
    assertEquals("p1", r1.cfg.callerId)
    assertEquals("p1", r1.turn)
    assertEquals(1, r1.cfg.round)
    assertEquals(Suit.CLUBS, r2.cfg.trump)
    assertEquals("p3", r2.cfg.callerId)
    assertEquals("p3", r2.turn)
    assertEquals(2, r2.cfg.round)
    // Re-init of round 1 is identical — nothing bled from round 2.
    val r1Again = initTable(cfg(trump = Suit.HEARTS, caller = "p1", leader = "p1", round = 1), seats)
    assertEquals(r1.cfg.trump, r1Again.cfg.trump)
    assertEquals(r1.turn, r1Again.turn)
  }

  // ── Follow-suit enforced; rejection names the led suit ──
  @Test
  fun followSuitEnforced() {
    val hands = mapOf(
      "p1" to listOf(Card(Suit.HEARTS, Rank(14, "A"))),
      "p2" to listOf(Card(Suit.HEARTS, Rank(13, "K")), Card(Suit.DIAMONDS, Rank(2, "2"))),
      "p3" to listOf(Card(Suit.CLUBS, Rank(3, "3"))),
      "p4" to listOf(Card(Suit.CLUBS, Rank(4, "4"))),
    )
    var s = initTable(cfg(hands = hands), seats)
    s = play(s, "p1", hands.getValue("p1")[0])
    assertEquals(Suit.HEARTS, s.ledSuit)
    assertEquals("p2", s.turn)

    val offSuit = hands.getValue("p2")[1]
    val illegal = canPlayCard(s, "p2", offSuit)
    assertTrue(!illegal.legal)
    assertEquals("ILLEGAL_CARD", illegal.reason)
    when (val r = emitPlay(s, PlayCard("p2", offSuit))) {
      is PlayEmit.Rejected -> assertEquals("Follow HEARTS", r.reason)
      else -> throw AssertionError("off-suit play must be rejected")
    }

    val wrongTurn = canPlayCard(s, "p4", hands.getValue("p4")[0])
    assertEquals("NOT_THIS_SEATS_TURN", wrongTurn.reason)

    s = play(s, "p2", hands.getValue("p2")[0])
    assertEquals("p3", s.turn)
  }

  // ── Void reveal + trump resolution + winner leads next ──
  @Test
  fun voidRevealAndWinnerLeadsNext() {
    val hands = mapOf(
      "p1" to listOf(Card(Suit.HEARTS, Rank(14, "A"))),
      "p2" to listOf(Card(Suit.HEARTS, Rank(13, "K"))),
      // p3/p4 hold no hearts — both reveal the void, then dump.
      "p3" to listOf(Card(Suit.CLUBS, Rank(3, "3"))),
      "p4" to listOf(Card(Suit.CLUBS, Rank(4, "4"))),
    )
    var s = initTable(cfg(hands = hands), seats)
    s = play(s, "p1", hands.getValue("p1")[0])
    s = play(s, "p2", hands.getValue("p2")[0])
    s = play(s, "p3", hands.getValue("p3")[0])
    assertEquals(listOf(Suit.HEARTS), s.voids.getValue("p3"))
    s = play(s, "p4", hands.getValue("p4")[0])
    assertEquals(TablePhase.RESOLVING, s.phase)
    assertNull(s.turn)

    s = resolveTrick(s)
    assertEquals(TablePhase.PLAY, s.phase)
    assertEquals(2, s.trickNo)
    // No trump played — led A♥ takes it for p1, who leads again.
    assertEquals("p1", s.lastTrick?.winnerId)
    assertEquals(1, s.tricksWon.getValue("p1"))
    assertEquals("p1", s.leaderId)
    assertEquals("p1", s.turn)
    assertTrue(s.plays.isEmpty())
    assertNull(s.ledSuit)
  }

  // ── Trick-winner pins: trump > led > off-suit; SANS gives no bonus ──
  @Test
  fun trickWinnerPins() {
    val aceHearts = Card(Suit.HEARTS, Rank(14, "A"))
    val twoSpades = Card(Suit.SPADES, Rank(2, "2"))
    // Trump (even a deuce) beats the led ace.
    assertEquals(
      "p2",
      trickWinner(
        Suit.SPADES, Suit.HEARTS,
        listOf(Play("p1", aceHearts), Play("p2", twoSpades)),
      ),
    )
    // No trump played — higher led card wins.
    assertEquals(
      "p2",
      trickWinner(
        Suit.SPADES, Suit.HEARTS,
        listOf(
          Play("p1", Card(Suit.HEARTS, Rank(13, "K"))),
          Play("p2", aceHearts),
        ),
      ),
    )
    // Off-suit, non-trump never wins, however high.
    assertEquals(
      "p1",
      trickWinner(
        Suit.SPADES, Suit.CLUBS,
        listOf(
          Play("p1", Card(Suit.CLUBS, Rank(5, "5"))),
          Play("p2", Card(Suit.DIAMONDS, Rank(14, "A"))),
        ),
      ),
    )
    // SANS trump behaves as no-trump: led suit decides.
    assertEquals(
      "p1",
      trickWinner(
        Suit.SANS, Suit.CLUBS,
        listOf(
          Play("p1", Card(Suit.CLUBS, Rank(14, "A"))),
          Play("p2", Card(Suit.DIAMONDS, Rank(14, "A"))),
        ),
      ),
    )
  }

  // ── Full 13-trick drive to DONE (integration pattern: first legal card) ──
  @Test
  fun fullRoundToDone() {
    var s = initTable(cfg(), seats)
    fun legalCardFor(seat: String): Card {
      val hand = s.cfg.hands.getValue(seat)
      val led = s.ledSuit ?: return hand[0]
      return hand.firstOrNull { it.suit == led } ?: hand[0]
    }
    var guard = 0
    while (s.phase != TablePhase.DONE && guard < 4000) {
      guard++
      if (s.phase == TablePhase.PLAY) {
        val seat = s.turn ?: throw AssertionError("turn null in PLAY")
        s = play(s, seat, legalCardFor(seat))
      } else {
        s = resolveTrick(s)
      }
    }
    assertEquals(TablePhase.DONE, s.phase)
    assertEquals(13, s.trickNo)
    assertEquals(13, s.tricksWon.values.sum())
    assertTrue(s.cfg.hands.values.all { it.isEmpty() })
    assertTrue(s.lastTrick != null)
    assertTrue(s.tricksWon.getValue(s.lastTrick!!.winnerId) >= 1)
    assertNull(s.turn)
  }

  // ── previewPlay mirrors emitPlay's turn/phase arithmetic exactly ──
  @Test
  fun previewMirrorsEmit() {
    var s = initTable(cfg(), seats)
    val first = s.cfg.hands.getValue("p1")[0]
    val preview = previewPlay(s, "p1", first)
    assertTrue(preview.legal)
    assertEquals("p2", preview.nextTurnSeat)
    assertEquals(TablePhase.PLAY, preview.nextPhase)
    // State untouched by the preview.
    assertEquals("p1", s.turn)
    assertTrue(s.plays.isEmpty())

    // Play three, then preview the 4th — RESOLVING, no next turn.
    s = play(s, "p1", first)
    var cur = s
    repeat(2) {
      val seat = cur.turn ?: throw AssertionError("turn null")
      val hand = cur.cfg.hands.getValue(seat)
      val led = cur.ledSuit
      val card = if (led == null) hand[0] else hand.firstOrNull { it.suit == led } ?: hand[0]
      cur = play(cur, seat, card)
    }
    val lastSeat = cur.turn ?: throw AssertionError("turn null")
    val lastHand = cur.cfg.hands.getValue(lastSeat)
    val lastLed = cur.ledSuit
    val lastCard = if (lastLed == null) lastHand[0] else lastHand.firstOrNull { it.suit == lastLed } ?: lastHand[0]
    val lastPreview = previewPlay(cur, lastSeat, lastCard)
    assertTrue(lastPreview.legal)
    assertNull(lastPreview.nextTurnSeat)
    assertEquals(TablePhase.RESOLVING, lastPreview.nextPhase)

    val illegalPreview = previewPlay(cur, "p1", lastCard)
    assertTrue(!illegalPreview.legal)
    assertEquals("NOT_THIS_SEATS_TURN", illegalPreview.reason)
  }

  // ── restoreHand: missing seat re-seeds; everything else refuses ──
  @Test
  fun restoreHandPaths() {
    val full = Dealer.dealHands(11)
    val withoutP2 = full - "p2"
    var s = initTable(cfg(hands = withoutP2), seats)
    assertTrue(legalCards(s, "p2").isEmpty())

    val ok = restoreHand(s, "p2", full.getValue("p2"), 1)
    assertTrue(ok.restored)
    assertEquals(13, ok.count)
    assertNull(ok.reason)
    s = ok.state
    assertEquals(13, legalCards(s, "p2").size)

    val again = restoreHand(s, "p2", full.getValue("p2"), 1)
    assertTrue(!again.restored)
    assertEquals("ALREADY_PRESENT", again.reason)

    val wrongRound = restoreHand(s, "p2", full.getValue("p2"), 2)
    assertEquals("ROUND_MISMATCH", wrongRound.reason)

    val badSeat = restoreHand(s, "", full.getValue("p2"), 1)
    assertEquals("INVALID_ARGUMENT", badSeat.reason)
  }

  // ── resolveTrick outside RESOLVING is a safe no-op ──
  @Test
  fun resolveOutsideResolvingIsNoOp() {
    val s = initTable(cfg(), seats)
    assertEquals(s, resolveTrick(s))
  }

  // ── canPlayCard phase guard ──
  @Test
  fun phaseGuards() {
    val s = initTable(cfg(), seats).copy(phase = TablePhase.DONE, turn = null)
    val verdict = canPlayCard(s, "p1", Card(Suit.SPADES, Rank(14, "A")))
    assertEquals("NOT_PLAY_PHASE", verdict.reason)
    assertTrue(emitPlay(s, PlayCard("p1", Card(Suit.SPADES, Rank(14, "A")))) is PlayEmit.Rejected)
  }
}
