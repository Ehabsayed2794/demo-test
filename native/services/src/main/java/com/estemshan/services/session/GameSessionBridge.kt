package com.estemshan.services.session

import com.estemshan.engine.BiddingState
import com.estemshan.engine.ExtensionReason
import com.estemshan.engine.GameSession
import com.estemshan.engine.Suit
import com.estemshan.engine.TableState
import com.estemshan.services.model.RoundResultEntry

/**
 * Satisfies [GameSessionPort] by delegating to the authoritative
 * [GameSession] in :engine — the implementation this seam was written to
 * one day receive (see GameSessionPort's own header). Added here rather
 * than in :engine because the port lives in this module and the layering
 * forbids :engine depending on :services; the engine store owns the full
 * surface, this is the deliberately narrow consumer view of it.
 *
 * The port's methods this module actually consumes today are
 * getBiddingState/updateBiddingState, getPlayState/updatePlayState and
 * getTurn/setTurn — the sync replay path. The rest of the surface
 * (players/round/dealer/scores/winners) is carried through verbatim for
 * the UI and completion paths that consume this seam next.
 *
 * SEAM NOTE — getDealer()/setDealer(): the port's parameter is named
 * `uid`, but the engine's dealer is a SEAT id (engine/session.js's own
 * setDealer, and the seat space the dealer rotation walks), and the
 * rules' uid-space dealer is validated against the match document, not
 * this seam. This bridge therefore carries the value through unchanged
 * in the engine's seat space, exactly as the seam's own test double does
 * — translating uid→seat here would require a roster this seam does not
 * own, and a wrong translation would corrupt [GameSession.rotateDealer].
 */
class GameSessionBridge(private val session: GameSession) : GameSessionPort {

  override fun getPlayers(): List<String> {
    val seated = session.getPlayers().map { it.seatId }
    return if (seated.isNotEmpty()) seated else session.getRoom().seats
  }

  override fun getRoundNumber(): Int = session.getRound().number

  override fun getMaxRounds(): Int = session.getRound().maxRounds

  override fun getDealer(): String? = session.getDealer()

  override fun setDealer(uid: String?) {
    session.setDealer(uid)
  }

  override fun getTurn(): String? = session.getTurn()

  override fun setTurn(seatId: String?) {
    session.setTurn(seatId)
  }

  override fun nextRound(): Int = session.nextRound()

  override fun getPlayState(): TableState? = session.getPlayState()

  override fun updatePlayState(state: TableState) {
    session.updatePlayState(state)
  }

  override fun getBiddingState(): BiddingState? = session.getBiddingState()

  override fun updateBiddingState(state: BiddingState) {
    session.updateBiddingState(state)
  }

  override fun getMatchScores(): Map<String, Int> = session.getMatchScores()

  override fun setMatchScores(scores: Map<String, Int>) {
    session.setMatchScores(scores)
  }

  override fun recordRoundResult(entry: RoundResultEntry) {
    session.recordRoundResult(entry.toEngine())
  }

  override fun getLastRoundResult(): RoundResultEntry? =
    session.getLastRoundResult()?.toEntry()

  override fun setWinnerIds(ids: List<String>) {
    session.setWinnerIds(ids)
  }

  override fun getWinnerIds(): List<String> = session.getWinnerIds()

  override fun isMatchComplete(): Boolean = session.isMatchComplete()
}

/** Engine-side record, in engine-owned types. */
private fun RoundResultEntry.toEngine() = com.estemshan.engine.RoundResult(
  round = round,
  trump = trump?.let { runCatching { Suit.valueOf(it) }.getOrNull() },
  callerId = callerId,
  tricksWon = tricksWon,
  estimates = estimates,
  scoreDeltas = scoreDeltas,
  riskPlayerId = riskPlayerId,
  totalBids = totalBids,
  isOver = isOver,
  isSaayda = isSaayda,
  appliedMultiplier = appliedMultiplier,
  nextMultiplier = nextMultiplier,
  extensionReason = extensionReason,
)

/** Services-side record, in services-owned types. */
private fun com.estemshan.engine.RoundResult.toEntry() = RoundResultEntry(
  round = round,
  trump = trump?.name,
  callerId = callerId,
  tricksWon = tricksWon,
  estimates = estimates,
  scoreDeltas = scoreDeltas,
  riskPlayerId = riskPlayerId,
  totalBids = totalBids,
  isOver = isOver,
  isSaayda = isSaayda,
  appliedMultiplier = appliedMultiplier,
  nextMultiplier = nextMultiplier,
  extensionReason = extensionReason,
)
