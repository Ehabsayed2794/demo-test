package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.Dealer
import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.functions.TimeoutResolution.BotProfile
import com.estemshan.functions.TimeoutResolution.Resolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S73 Stage 2, C7 — WHICH bot plays an expired decision.
 *
 * Fork 1's amendment, in one sentence: a seat the takeover BORROWS is played at
 * the table's configured difficulty, and everything else keeps RD20's pin at
 * MEDIUM. These tests cover both halves, because the half that must not change is
 * the load-bearing one —
 *  - a marker-absent seat is played by exactly the profile Stage 1 used, and the
 *    room's configured values cannot reach it (the whole-resolution comparison
 *    below, not just the echo);
 *  - a permanently bot-controlled seat (a room bot, a 15-timeout removal) is not
 *    re-profiled either, which is what keeps S61's removal policy intact;
 *  - a marker present reads the configured pair through the same fallbacks the
 *    client's `RoomDoc` uses, so a pre-trio document, an unwritable name, or a
 *    wrong-case name all resolve to MEDIUM/BALANCED rather than throwing;
 *  - a corrupt marker entry is "not borrowed", so garbage never promotes a bot to
 *    a seat's owner's difficulty and never strands the owner.
 *
 * The behavioural half asserts what the response actually carries
 * (`decided["botTier"]`) plus one proof that the personality is not decoration:
 * across a run of real deals, an AGGRESSIVE borrowed seat never bids below the
 * same seat at BALANCED and outbids it somewhere. That is the direction-only
 * assertion idiom the engine's own personality goldens (S9) use, because a bias
 * smaller than the distance to the next integer is a genuine no-op on a given
 * hand. [BotPersonality.DEFAULT] IS BALANCED, so the pin and the fallback
 * coincide by construction — asserted below rather than assumed.
 *
 * Deliberately NOT here: the marker's WRITE and its clock (C3), the reclaim
 * (C4/:services), the seat chip (:app), and the tier's own play strength (the
 * brains' golden suites) — this file is about which profile is handed to them.
 */
class BotProfileTest {

  // ── fixtures ─────────────────────────────────────────────────────────

  private val order = listOf("p1", "p2", "p3", "p4")
  private val seats: Map<String, String> =
    linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")
  private val players = listOf("u1", "u2", "u3", "u4")
  private val anchorMillis = 1_000_000L
  private val currentRound = 14

  private fun gameState(round: Int): Map<String, Any?> =
    mapOf("initialized" to true, "dealtRound" to round)

  private fun takeoverEntry(): Map<String, Any?> = linkedMapOf(
    InactiveTakeover.TAKEN_OVER_AT_FIELD to 5_000L,
    InactiveTakeover.ROUND_AT_TAKEOVER_FIELD to currentRound.toLong(),
    InactiveTakeover.TRICK_AT_TAKEOVER_FIELD to 0L,
  )

  /**
   * A dealt round-14 FULL match mid-estimate, waiting on p1 (the dealer) — the
   * document every other suite here resolves. [botTier]/[botPersonality] are
   * `null` for "the key is not on the document at all", which is the pre-trio
   * case, not a fifth difficulty.
   */
  private fun fastRoundDoc(
    botTier: String? = "MEDIUM",
    botPersonality: String? = "BALANCED",
    takeovers: Map<String, Any?> = emptyMap(),
    botSeats: List<String> = emptyList(),
    timeoutRemovedUids: List<String> = emptyList(),
    biddingOpen: Boolean = true,
    cardPhase: String? = null,
    turn: String? = null,
    bids: Map<String, Int?> = emptyMap(),
  ): Map<String, Any?> = mutableMapOf(
    "roomId" to "room-1",
    "players" to players,
    "status" to "starting",
    "currentRound" to currentRound,
    "maxRounds" to 18,
    "completedRound" to 0,
    "extendedRounds" to emptyList<Int>(),
    "dealer" to seats.getValue("p1"),
    "turn" to turn,
    "seats" to seats,
    "version" to 1,
    "biddingOpen" to biddingOpen,
    "bids" to linkedMapOf<String, Int?>(*order.map { it to bids[it] }.toTypedArray()),
    "lastBidSeat" to bids.keys.lastOrNull(),
    "cardLog" to emptyList<Map<String, Any?>>(),
    "lastCardSeat" to null,
    "cardPhase" to cardPhase,
    "biddingLog" to emptyList<Map<String, Any?>>(),
    "gameState" to gameState(currentRound),
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "mode" to "RANKED",
    "botTier" to botTier,
    "botPersonality" to botPersonality,
    "decisionTimerSeconds" to 15,
    "updatedAt" to anchorMillis,
    InactiveTakeover.TAKEOVER_FIELD to takeovers,
    "botSeats" to botSeats,
    "timeoutRemovedUids" to timeoutRemovedUids,
  )

  /** A called round whose first card is still to be led — the CARD-phase twin of
   *  [fastRoundDoc], built the way S61's suite builds it. */
  private fun cardDoc(
    botTier: String? = "MEDIUM",
    takeovers: Map<String, Any?> = emptyMap(),
  ): Map<String, Any?> = fastRoundDoc(
      botTier = botTier,
      takeovers = takeovers,
      biddingOpen = false,
      bids = order.associateWith { 3 },
    )

  private fun dealtHands(seed: Long): Map<String, List<Card>> = Dealer.dealHands(seed)

  /** The doc's own expired decision, whatever phase the doc is in. */
  private fun pendingOf(doc: Map<String, Any?>) = TimeoutResolution.pendingDecision(doc)!!

  private fun decidedOf(resolution: Resolution): Map<String, Any?> =
    (resolution as Resolution.Applied).write.decided

  private fun tier(resolution: Resolution): String = decidedOf(resolution)["botTier"] as String

  // ── the pure read: which profile, for which seat ─────────────────────

  @Test
  fun aSeatWithNoMarkerIsPlayedAtTheStageOnePin() {
    val doc = fastRoundDoc(botTier = "EXPERT", botPersonality = "TRICKSTER")

    // The room is configured EXPERT/TRICKSTER and the seat is a present human's:
    // RD20's pin holds, because only the takeover's own marker can re-profile.
    assertEquals(
      BotProfile(BotTier.MEDIUM, BotPersonality.DEFAULT),
      TimeoutResolution.botProfileFor(doc, "p1"),
    )
  }

  @Test
  fun aBorrowedSeatIsPlayedAtTheConfiguredPair() {
    val doc = fastRoundDoc(
      botTier = "HARD",
      botPersonality = "AGGRESSIVE",
      takeovers = mapOf("p1" to takeoverEntry()),
    )

    assertEquals(
      BotProfile(BotTier.HARD, BotPersonality.AGGRESSIVE),
      TimeoutResolution.botProfileFor(doc, "p1"),
    )
  }

  @Test
  fun aBorrowedSeatOnAPreTrioDocumentFallsBackToMediumBalanced() {
    val doc = fastRoundDoc(
      botTier = null,
      botPersonality = null,
      takeovers = mapOf("p1" to takeoverEntry()),
    )

    // Absent keys are a match created before the trio's persistence, not a
    // difficulty nobody chose: the same pair RoomDoc hands the lobby.
    assertEquals(
      BotProfile(BotTier.MEDIUM, BotPersonality.BALANCED),
      TimeoutResolution.botProfileFor(doc, "p1"),
    )
  }

  @Test
  fun aBorrowedSeatNeverPlaysANameTheRulesRefuseToWrite() {
    val bogus = fastRoundDoc(
      botTier = "NIGHTMARE",
      botPersonality = "CHAOTIC",
      takeovers = mapOf("p1" to takeoverEntry()),
    )
    val wrongCase = fastRoundDoc(
      botTier = "expert",
      botPersonality = "aggressive",
      takeovers = mapOf("p1" to takeoverEntry()),
    )

    // Unrecognised reads as missing — never throws, never half-matches. Case
    // matters: the only producer of these strings is an enum name.
    assertEquals(
      BotProfile(BotTier.MEDIUM, BotPersonality.BALANCED),
      TimeoutResolution.botProfileFor(bogus, "p1"),
    )
    assertEquals(
      BotProfile(BotTier.MEDIUM, BotPersonality.BALANCED),
      TimeoutResolution.botProfileFor(wrongCase, "p1"),
    )
  }

  @Test
  fun onlyTheBorrowedSeatIsReprofiled() {
    val doc = fastRoundDoc(
      botTier = "EXPERT",
      botPersonality = "TRICKSTER",
      takeovers = mapOf("p2" to takeoverEntry()),
      botSeats = listOf("p3"),
      timeoutRemovedUids = listOf("u4"),
    )

    // p2 is borrowed → the configured pair. p1 is a present human → the pin.
    // p3 is a room bot and p4 was removed at 15 timeouts: both are played by the
    // brain the room made, never through this read, and neither is a departure.
    // Fork 1 is "borrowed seats only", and this is the whole of it.
    assertEquals(
      BotProfile(BotTier.MEDIUM, BotPersonality.DEFAULT),
      TimeoutResolution.botProfileFor(doc, "p1"),
    )
    assertEquals(
      BotProfile(BotTier.EXPERT, BotPersonality.TRICKSTER),
      TimeoutResolution.botProfileFor(doc, "p2"),
    )
    assertEquals(
      BotProfile(BotTier.MEDIUM, BotPersonality.DEFAULT),
      TimeoutResolution.botProfileFor(doc, "p3"),
    )
    assertEquals(
      BotProfile(BotTier.MEDIUM, BotPersonality.DEFAULT),
      TimeoutResolution.botProfileFor(doc, "p4"),
    )
  }

  @Test
  fun aGarbageMarkerEntryIsNotBorrowed() {
    val doc = fastRoundDoc(
      botTier = "EXPERT",
      botPersonality = "AGGRESSIVE",
      takeovers = mapOf("p1" to "borrowed"),
    )

    // The fail-closed direction the C3 read side already pins: a marker this
    // process cannot parse promotes nothing. An owner's seat is not played at
    // their table's difficulty by a value that is not a marker.
    assertEquals(setOf<String>(), TimeoutResolution.inactiveTakeoverSeats(doc))
    assertEquals(
      BotProfile(BotTier.MEDIUM, BotPersonality.DEFAULT),
      TimeoutResolution.botProfileFor(doc, "p1"),
    )
  }

  @Test
  fun theFallbackAndThePinAreTheSameProfileBecauseDefaultIsBalanced() {
    val doc = fastRoundDoc(takeovers = mapOf("p1" to takeoverEntry()))

    // A configured MEDIUM/BALANCED table — which is what a pre-trio document
    // falls back TO — must be indistinguishable from the marker-absent pin.
    // This holds only because BotPersonality.DEFAULT *is* BALANCED; if that
    // ever changes, this test fails instead of a borrowed seat silently
    // bidding with a different style than RD20's timeout bot.
    val borrowed = TimeoutResolution.botProfileFor(doc, "p1")
    val pinned = TimeoutResolution.botProfileFor(fastRoundDoc(), "p1")
    assertEquals(pinned, borrowed)
    assertEquals(BotPersonality.BALANCED, BotPersonality.DEFAULT)
  }

  @Test
  fun parseBotTierMapsEveryRealNameAndNothingElse() {
    BotTier.values().forEach { assertEquals(it, TimeoutResolution.parseBotTier(it.name)) }
    assertEquals(BotTier.MEDIUM, TimeoutResolution.parseBotTier(null))
    assertEquals(BotTier.MEDIUM, TimeoutResolution.parseBotTier(7))
    assertEquals(BotTier.MEDIUM, TimeoutResolution.parseBotTier("NOPE"))
  }

  @Test
  fun parseBotPersonalityMapsEveryRealNameAndNothingElse() {
    BotPersonality.values().forEach {
      assertEquals(it, TimeoutResolution.parseBotPersonality(it.name))
    }
    assertEquals(BotPersonality.BALANCED, TimeoutResolution.parseBotPersonality(null))
    assertEquals(BotPersonality.BALANCED, TimeoutResolution.parseBotPersonality(listOf("A")))
    assertEquals(BotPersonality.BALANCED, TimeoutResolution.parseBotPersonality("balanced"))
  }

  // ── the decision the resolver actually writes ────────────────────────

  @Test
  fun aBorrowedSeatEchoesTheConfiguredTier() {
    val doc = fastRoundDoc(
      botTier = "HARD",
      takeovers = mapOf("p1" to takeoverEntry()),
    )

    val resolution = TimeoutResolution.resolveExpired(doc, dealtHands(11L), pendingOf(doc))
    val decided = decidedOf(resolution)

    assertEquals("HARD", tier(resolution))
    // The echo's shape is S61's and is asserted by size elsewhere: a fifth key
    // would be a new public field on the response, which C7 does not own.
    assertEquals(setOf("phase", "seatId", "estimate", "botTier"), decided.keys)
    val estimate = decided["estimate"] as Int
    assertTrue(estimate in 0..13, "an estimate is always 0..13, whatever the tier")
  }

  @Test
  fun aRoomBotSeatEchoesMediumEvenThoughTheRoomIsConfiguredExpert() {
    val doc = fastRoundDoc(botTier = "EXPERT", botSeats = listOf("p1"))

    val resolution = TimeoutResolution.resolveExpired(doc, dealtHands(11L), pendingOf(doc))

    // The room's own bot plays with the profile the room made it, and the
    // timeout path never restates the configured difficulty for it.
    assertEquals("MEDIUM", tier(resolution))
  }

  @Test
  fun aMarkerlessResolutionIsIdenticalWhateverTheRoomConfigured() {
    val hands = dealtHands(11L)
    val configured = fastRoundDoc(botTier = "EXPERT", botPersonality = "TRICKSTER")
    val unconfigured = fastRoundDoc(botTier = null, botPersonality = null)

    val withValues = TimeoutResolution.resolveExpired(configured, hands, pendingOf(configured))
    val withoutKeys = TimeoutResolution.resolveExpired(unconfigured, hands, pendingOf(unconfigured))

    // Fork 1's boundary in one assertion: with no marker, the document's
    // configured pair changes NOTHING — not the write, not the echo. The only
    // key dropped is the server timestamp sentinel, which is a fresh object per
    // call and equal by nothing.
    assertEquals(decidedOf(withoutKeys), decidedOf(withValues))
    assertEquals(
      withoutKeys.updatesForComparison(),
      withValues.updatesForComparison(),
    )
  }

  @Test
  fun theConfiguredPersonalityReachesTheBrain() {
    var balanced = 0
    var aggressive = 0
    var strictlyHigher = 0
    for (seed in 1L..12L) {
      val hands = dealtHands(seed)
      val balancedDoc = fastRoundDoc(
        botPersonality = "BALANCED",
        takeovers = mapOf("p1" to takeoverEntry()),
      )
      val aggressiveDoc = fastRoundDoc(
        botPersonality = "AGGRESSIVE",
        takeovers = mapOf("p1" to takeoverEntry()),
      )
      val b = decidedOf(
        TimeoutResolution.resolveExpired(balancedDoc, hands, pendingOf(balancedDoc)),
      )["estimate"] as Int
      val a = decidedOf(
        TimeoutResolution.resolveExpired(aggressiveDoc, hands, pendingOf(aggressiveDoc)),
      )["estimate"] as Int
      assertTrue(
        a >= b,
        "AGGRESSIVE's +0.40 bid bias can never bid below the same seat at " +
          "BALANCED (seed $seed: $a < $b)",
      )
      if (a > b) strictlyHigher++
      balanced += b
      aggressive += a
    }

    // Direction-only, exactly like the engine's own personality goldens: a bias
    // smaller than the distance to the next integer is a genuine no-op on a
    // given hand, so the proof is that it is never backwards and does move
    // somewhere. If the personality were dropped on the floor, both totals
    // would be identical and this fails.
    assertTrue(strictlyHigher > 0, "AGGRESSIVE never bid higher than BALANCED in 12 deals")
    assertTrue(aggressive > balanced, "totals: aggressive=$aggressive balanced=$balanced")
  }

  @Test
  fun aBorrowedSeatPlaysItsCardAtTheConfiguredTier() {
    val opening = pendingOf(cardDoc())
    val doc = cardDoc(botTier = "EXPERT", takeovers = mapOf(opening.seatId to takeoverEntry()))
    val hand = dealtHands(11L).getValue(opening.seatId)

    val resolution = TimeoutResolution.resolveExpired(doc, dealtHands(11L), pendingOf(doc))

    // The card path takes the tier and no personality — PlayBrain.decide has no
    // personality parameter — so this is the second call site, pinned the same
    // way as the bidding one.
    assertEquals(TimeoutResolution.DecisionPhase.CARD, opening.phase)
    assertEquals("EXPERT", tier(resolution))
    assertEquals("CARD", decidedOf(resolution)["phase"])
    assertEquals(opening.seatId, decidedOf(resolution)["seatId"])
    val card = decidedOf(resolution)["card"] as Map<*, *>
    val suit = card["suit"] as String
    val rank = (card["rank"] as Map<*, *>)["v"]
    assertTrue(
      hand.any { it.suit.name == suit && it.rank.v == rank },
      "the bot played a card from the borrowed seat's own hand",
    )
  }

  @Test
  fun aMarkerlessCardDecisionStaysMedium() {
    val doc = cardDoc(botTier = "EXPERT")

    val resolution = TimeoutResolution.resolveExpired(doc, dealtHands(11L), pendingOf(doc))

    assertEquals("MEDIUM", tier(resolution))
  }

  /** Everything the resolver wrote except the per-call server-timestamp sentinel. */
  private fun Resolution.updatesForComparison(): Map<String, Any?> =
    (this as Resolution.Applied).write.updates.filterKeys { it != "updatedAt" }
}
