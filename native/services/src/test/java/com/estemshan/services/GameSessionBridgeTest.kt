package com.estemshan.services

import com.estemshan.engine.BiddingState
import com.estemshan.engine.GameSession
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.TableState
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.initTable
import com.estemshan.services.model.RoundResultEntry
import com.estemshan.services.session.GameSessionBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves the authoritative GameSession in :engine satisfies the seam this
 * module's MatchAdapter/MatchService consume (GameSessionPort) through the
 * bridge — no FakeGameSession in play here. The methods the sync replay
 * path actually uses (get/update Bidding|Play state, get/setTurn) are the
 * ones that must round-trip exactly; the rest of the seam is carried
 * through for the UI and completion paths that consume it next.
 */
class GameSessionBridgeTest {

  private val seats: List<String> = listOf("p1", "p2", "p3", "p4")

  private fun roundOneCfg(): RoundCfg = RoundCfg(
    round = 1,
    trump = Suit.SPADES,
    callerId = "p1",
    withPlayers = emptyList(),
    estimates = seats.associateWith { 2 },
    dashCallers = emptyList(),
    leaderId = "p1",
    riskId = null,
    hands = emptyMap(),
  )

  @Test
  fun bridgeDelegatesIdentityPlayersAndDealerThroughTheSeam() {
    val session = GameSession()
    session.init("ranked", force = true)
    val bridge = GameSessionBridge(session)

    assertEquals(seats, bridge.getPlayers())
    assertEquals(1, bridge.getRoundNumber())
    assertEquals(18, bridge.getMaxRounds())

    // The engine has no roster loaded yet, so the seat list backs the
    // view — never an invented name.
    assertEquals("p1", bridge.getDealer())
    bridge.setDealer("p3")
    assertEquals("p3", bridge.getDealer())
  }

  @Test
  fun bridgeRoundTripsTheEngineStatesTheSyncReplayDependsOn() {
    val bridge = GameSessionBridge(GameSession().also { it.init(force = true) })

    assertNull(bridge.getBiddingState())
    val bidding: BiddingState = initNormalRound(round = 1, dealer = "p1")
    bridge.updateBiddingState(bidding)
    assertEquals(bidding, bridge.getBiddingState())

    assertNull(bridge.getPlayState())
    val table: TableState = initTable(roundOneCfg(), seats)
    bridge.updatePlayState(table)
    assertEquals(table, bridge.getPlayState())

    bridge.setTurn("p2")
    assertEquals("p2", bridge.getTurn())
  }

  @Test
  fun bridgeRoundTripsScoresRoundResultsWinnersAndCompletion() {
    val bridge = GameSessionBridge(GameSession().also { it.init(force = true) })

    bridge.setMatchScores(mapOf("p1" to 10, "p2" to 5))
    assertEquals(mapOf("p1" to 10, "p2" to 5), bridge.getMatchScores())

    val entry = RoundResultEntry(
      round = 1,
      trump = "SPADES",
      callerId = "p1",
      tricksWon = seats.associateWith { 3 } + ("p1" to 4),
      estimates = seats.associateWith { 2 },
      scoreDeltas = mapOf("p1" to 18, "p2" to -4),
      riskPlayerId = "p4",
      totalBids = 8,
      isOver = false,
      isSaayda = false,
      appliedMultiplier = 1,
      nextMultiplier = 2,
      extensionReason = null,
    )
    bridge.recordRoundResult(entry)
    // The engine-side record survives the two-way conversion losslessly.
    assertEquals(entry, bridge.getLastRoundResult())

    bridge.setWinnerIds(listOf("p1", "p2"))
    assertEquals(listOf("p1", "p2"), bridge.getWinnerIds())

    assertFalse(bridge.isMatchComplete())
    assertEquals(2, bridge.nextRound())
    assertEquals(2, bridge.getRoundNumber())
    // nextRound cleared the engine states the seam exposes.
    assertNull(bridge.getPlayState())
    assertNull(bridge.getBiddingState())
    // Winners are cross-match state — a round advance does not drop them.
    assertEquals(listOf("p1", "p2"), bridge.getWinnerIds())
  }
}
