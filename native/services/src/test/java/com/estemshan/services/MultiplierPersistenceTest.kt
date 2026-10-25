package com.estemshan.services

import com.estemshan.engine.ScoringMode
import com.estemshan.services.model.AdvanceResult
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.CardLogEntry
import com.estemshan.services.model.GameState
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.RoundArchiveDoc
import com.estemshan.services.model.StoredCard
import com.estemshan.services.model.StoredRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-19 (Option B) — the persisted round multiplier, services side. Pure
 * unit tests: the creation writer, the parser's legacy default, the
 * General-Pass window proof, the owner-fixed ladder, and every idempotency
 * branch of the pass transition. The Firestore transaction shapes those
 * decisions drive are pinned by firestore.rules and the instrumented
 * suites; nothing here touches Firestore.
 */
class MultiplierPersistenceTest {

  private val sentinel = object {}

  private val seats = linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")

  private fun match(
    round: Int = 3,
    maxRounds: Int = 18,
    multiplier: Int = 1,
    scoringMode: ScoringMode = ScoringMode.NORMAL,
    status: String = MatchDoc.STATUS_STARTING,
    seatMap: Map<String, String> = seats,
    cardLog: List<CardLogEntry> = emptyList(),
    biddingLog: List<BiddingLogEntry> = emptyList(),
  ): MatchDoc = MatchDoc(
    roomId = "room-1",
    players = seatMap.values.toList(),
    status = status,
    currentRound = round,
    maxRounds = maxRounds,
    extendedRounds = emptyList(),
    dealer = seatMap.values.first(),
    turn = null,
    seats = seatMap,
    version = 7,
    biddingOpen = true,
    bids = emptyMap(),
    lastBidSeat = null,
    cardLog = cardLog,
    lastCardSeat = null,
    cardPhase = null,
    biddingLog = biddingLog,
    gameState = GameState.NOT_DEALT,
    scoringMode = scoringMode,
    currentMultiplier = multiplier,
  )

  private fun decliningDash(seat: String, round: Int) = BiddingLogEntry(
    seatId = seat,
    actionType = BiddingLogEntry.ACTION_DASH_CALL,
    declaredDashCall = false,
    round = round,
  )

  private fun pass(seat: String, round: Int) = BiddingLogEntry(
    seatId = seat,
    actionType = BiddingLogEntry.ACTION_AUCTION_BID,
    isPass = true,
    round = round,
  )

  /** S1's real window: N declining Dash decisions, then N auction passes. */
  private fun passWindow(
    seatIds: Collection<String>,
    round: Int,
    skipPassFor: String? = null,
    declareDashFor: String? = null,
  ): List<BiddingLogEntry> {
    val dash = seatIds.map { seat ->
      if (seat == declareDashFor) {
        BiddingLogEntry(
          seatId = seat,
          actionType = BiddingLogEntry.ACTION_DASH_CALL,
          declaredDashCall = true,
          round = round,
        )
      } else {
        decliningDash(seat, round)
      }
    }
    val passes = seatIds.mapNotNull { seat ->
      if (seat == skipPassFor || seat == declareDashFor) null else pass(seat, round)
    }
    return dash + passes
  }

  // ── the model ────────────────────────────────────────────────────────

  @Test
  fun matchDocParsesStoredMultiplier() {
    val doc = MatchDoc.fromFields(
      mapOf(
        "roomId" to "room-1",
        "dealer" to "u1",
        "currentMultiplier" to 4L,
        "seats" to seats,
        "players" to seats.values.toList(),
      ),
    )
    assertEquals(4, doc?.currentMultiplier)
  }

  @Test
  fun legacyMatchDocWithoutTheFieldReadsAsOne() {
    val doc = MatchDoc.fromFields(
      mapOf(
        "roomId" to "room-1",
        "dealer" to "u1",
        "currentRound" to 5L,
        "seats" to seats,
        "players" to seats.values.toList(),
      ),
    )
    assertEquals(1, doc?.currentMultiplier)
  }

  @Test
  fun roundArchiveCarriesThePassMarkerOnlyWhenTrue() {
    val ordinary = RoundArchiveDoc(3, "m", emptyList(), emptyList()).toFields()
    assertNull(ordinary["passRound"])
    val pass = RoundArchiveDoc(3, "m", emptyList(), emptyList(), passRound = true).toFields()
    assertEquals(true, pass["passRound"])
    assertEquals(3, pass["round"])
  }

  // ── creation ─────────────────────────────────────────────────────────

  @Test
  fun freshAndRematchDocumentsSeedTheMultiplierAtOne() {
    val fresh = buildInitialMatchFields(
      roomId = "ROOM01",
      players = listOf("u1", "u2"),
      creator = "u1",
      serverTimestamp = sentinel,
    )
    assertEquals(1, fresh["currentMultiplier"])

    val classic = buildInitialMatchFields(
      roomId = "ROOM01",
      players = listOf("u1", "u2"),
      creator = "u1",
      serverTimestamp = sentinel,
      scoringMode = ScoringMode.CLASSIC,
    )
    assertEquals(1, classic["currentMultiplier"])

    val rematch = buildRematchMatchFields(
      roomId = "ROOM01",
      seats = seats,
      oldDealerFallback = "u1",
      serverTimestamp = sentinel,
      rematchOfMatchId = "m0",
      scoringMode = ScoringMode.CLASSIC,
    )
    assertEquals(1, rematch["currentMultiplier"])
  }

  // ── the ladder ───────────────────────────────────────────────────────

  @Test
  fun generalPassLadderIsTwoFourSixEightThenCapped() {
    assertEquals(2, generalPassMultiplier(1, ScoringMode.NORMAL))
    assertEquals(4, generalPassMultiplier(2, ScoringMode.NORMAL))
    assertEquals(6, generalPassMultiplier(4, ScoringMode.NORMAL))
    assertEquals(8, generalPassMultiplier(6, ScoringMode.NORMAL))
    assertEquals(8, generalPassMultiplier(7, ScoringMode.NORMAL))
    assertEquals(8, generalPassMultiplier(8, ScoringMode.NORMAL))
  }

  @Test
  fun classicGeneralPassNeverLeavesOne() {
    assertEquals(1, generalPassMultiplier(1, ScoringMode.CLASSIC))
  }

  @Test
  fun storedMultiplierBoundsAreOneThroughEight() {
    assertFalse(isValidRoundMultiplier(0))
    assertTrue(isValidRoundMultiplier(1))
    assertTrue(isValidRoundMultiplier(8))
    assertFalse(isValidRoundMultiplier(9))
  }

  // ── the pass-window proof ────────────────────────────────────────────

  @Test
  fun canonicalFourSeatWindowIsAccepted() {
    val window = passWindow(seats.keys, round = 3)
    assertTrue(isCanonicalGeneralPassWindow(match(biddingLog = window), 3))
  }

  @Test
  fun underFourSeatWindowsAreAccepted() {
    val three = linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3")
    val two = linkedMapOf("p1" to "u1", "p2" to "u2")
    assertTrue(
      isCanonicalGeneralPassWindow(
        match(seatMap = three, biddingLog = passWindow(three.keys, round = 3)), 3,
      ),
    )
    assertTrue(
      isCanonicalGeneralPassWindow(
        match(seatMap = two, biddingLog = passWindow(two.keys, round = 3)), 3,
      ),
    )
  }

  @Test
  fun aSingleDeclaredDashIsNowAProvableWindow() {
    // F1 (owner ruling 2026-10-24): one declared Dash + passes from every
    // remaining seat IS a valid General Pass (2N-1 entries).
    val window = passWindow(seats.keys, round = 3, declareDashFor = "p3")
    assertEquals(7, window.size)
    assertTrue(isCanonicalGeneralPassWindow(match(biddingLog = window), 3))
  }

  @Test
  fun duplicatePassSeatIsRefused() {
    val window = passWindow(seats.keys, round = 3)
    val tampered = window.dropLast(1) + pass("p1", 3)
    assertEquals(8, tampered.size)
    assertFalse(isCanonicalGeneralPassWindow(match(biddingLog = tampered), 3))
  }

  @Test
  fun missingPassIsRefused() {
    val window = passWindow(seats.keys, round = 3, skipPassFor = "p2")
    assertEquals(7, window.size)
    assertFalse(isCanonicalGeneralPassWindow(match(biddingLog = window), 3))
  }

  @Test
  fun passWindowTaggedWithAnotherRoundIsRefused() {
    val window = passWindow(seats.keys, round = 2)
    assertFalse(isCanonicalGeneralPassWindow(match(round = 3, biddingLog = window), 3))
  }

  @Test
  fun aPlayedCardDisqualifiesTheWindow() {
    val window = passWindow(seats.keys, round = 3)
    val played = listOf(
      CardLogEntry(
        seatId = "p1",
        card = StoredCard(suit = "HEARTS", rank = StoredRank(v = 14, s = "A")),
        round = 3,
      ),
    )
    assertFalse(isCanonicalGeneralPassWindow(match(biddingLog = window, cardLog = played), 3))
  }

  // ── the transition decision (incl. idempotency) ──────────────────────

  @Test
  fun aPassAdvancesAndArmsTheLadderMultiplier() {
    val decision = decideGeneralPassAdvance(
      match(round = 3, multiplier = 4, biddingLog = passWindow(seats.keys, 3)),
      closedRound = 3,
      finalScores = mapOf("p1" to 10, "p2" to 20, "p3" to 30, "p4" to 40),
      winnerIds = listOf("p4"),
    )
    assertEquals(GeneralPassAdvance.Advance(nextRound = 4, nextMultiplier = 6), decision)
  }

  @Test
  fun aClassicPassAdvancesAtOne() {
    val decision = decideGeneralPassAdvance(
      match(
        round = 3, scoringMode = ScoringMode.CLASSIC,
        biddingLog = passWindow(seats.keys, 3),
      ),
      closedRound = 3,
      finalScores = emptyMap(),
      winnerIds = emptyList(),
    )
    assertEquals(GeneralPassAdvance.Advance(nextRound = 4, nextMultiplier = 1), decision)
  }

  @Test
  fun aFinalRoundPassCompletesWithTheRunningTotals() {
    val scores = mapOf("p1" to 10, "p2" to 20, "p3" to 30, "p4" to 40)
    val decision = decideGeneralPassAdvance(
      match(round = 18, maxRounds = 18, biddingLog = passWindow(seats.keys, 18)),
      closedRound = 18,
      finalScores = scores,
      winnerIds = listOf("p4"),
    )
    assertEquals(GeneralPassAdvance.Complete(scores, listOf("p4")), decision)
  }

  @Test
  fun aFinalRoundPassWithInconsistentWinnersIsRefused() {
    val decision = decideGeneralPassAdvance(
      match(round = 18, maxRounds = 18, biddingLog = passWindow(seats.keys, 18)),
      closedRound = 18,
      finalScores = mapOf("p1" to 10, "p2" to 20, "p3" to 30, "p4" to 40),
      winnerIds = listOf("p1"),
    )
    assertEquals(GeneralPassAdvance.InvalidResult, decision)
  }

  @Test
  fun aSecondCallAfterTheAdvanceIsAnIdempotentNoOp() {
    val advanced = match(round = 4, multiplier = 6, biddingLog = passWindow(seats.keys, 4))
    val decision = decideGeneralPassAdvance(
      advanced, closedRound = 3, finalScores = emptyMap(), winnerIds = emptyList(),
    )
    assertEquals(GeneralPassAdvance.AlreadyAdvanced(4), decision)
  }

  @Test
  fun aPassAgainstACompletedMatchIsAnIdempotentNoOp() {
    val decision = decideGeneralPassAdvance(
      match(status = MatchDoc.STATUS_COMPLETE, biddingLog = passWindow(seats.keys, 3)),
      closedRound = 3,
      finalScores = emptyMap(),
      winnerIds = emptyList(),
    )
    assertEquals(GeneralPassAdvance.AlreadyComplete, decision)
  }

  @Test
  fun aPassWithASingleDeclaredDashAdvances() {
    // F1: the k=1 mixed window advances — the shape the pre-F1 proof
    // refused is the feature now.
    val decision = decideGeneralPassAdvance(
      match(biddingLog = passWindow(seats.keys, 3, declareDashFor = "p1")),
      closedRound = 3,
      finalScores = emptyMap(),
      winnerIds = emptyList(),
    )
    assertEquals(GeneralPassAdvance.Advance(nextRound = 4, nextMultiplier = 2), decision)
  }

  @Test
  fun idempotentAdvanceResultsCarryTheirReasonAndNoMultiplier() {
    val already = AdvanceResult.alreadyAdvanced("m", currentRound = 4)
    assertFalse(already.advanced)
    assertEquals(Reasons.ALREADY_ADVANCED, already.reason)
    assertNull(already.currentMultiplier)

    val complete = AdvanceResult.alreadyComplete("m", currentRound = 18)
    assertFalse(complete.advanced)
    assertEquals(Reasons.MATCH_ALREADY_COMPLETE, complete.reason)
    assertNull(complete.currentMultiplier)
  }
}
