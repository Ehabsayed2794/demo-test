package com.estemshan.services.session

import com.estemshan.engine.BiddingState
import com.estemshan.engine.TableState
import com.estemshan.services.model.RoundResultEntry

/**
 * The GameSession seam. Phase 3 owns services + sync and CONSUMES this
 * surface; the authoritative implementation lives elsewhere (engine/
 * session.js's Kotlin successor — see ANDROID_MIGRATION_PLAN.md §9:
 * GameSession/RemoteStore was delegated to a second agent and was
 * unpushed when this module was written). Everything here is what
 * MatchAdapter's read-side interpreter needs to mirror remote state into
 * local engines — deliberately narrow, so an implementation cannot be
 * accidentally coupled to service internals.
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   Engine → GameSession → MatchAdapter → MatchService → UI
 * Engines are pure functions over state held HERE, never singletons.
 */
interface GameSessionPort {

  // ── identity / players ──────────────────────────────────────────────
  fun getPlayers(): List<String>

  /** Current 1-based round number. */
  fun getRoundNumber(): Int

  fun getMaxRounds(): Int

  // ── round-scoped primitives (bootstrap writes these only) ───────────
  fun getDealer(): String?
  fun setDealer(seatId: String?)

  /**
   * The current turn holder as a SEAT id (engine seats p1..p4 — matches
   * engine/session.js's own setTurn(leaderId/callerId)), or null while
   * between turns. MatchAdapter's resolve mirror writes seat ids here;
   * MatchService resolves a uid to a seat before comparing against it.
   */
  fun getTurn(): String?
  fun setTurn(seatId: String?)

  /** Advance to the next round; returns the new round number. */
  fun nextRound(): Int

  // ── engine state mirrors ────────────────────────────────────────────
  /** The local TableEngine state for the current round, or null if the
   *  table has not been entered yet this round. */
  fun getPlayState(): TableState?
  fun updatePlayState(state: TableState)

  /** The local BiddingEngine state for the current round, or null if
   *  bidding has not started this round. */
  fun getBiddingState(): BiddingState?
  fun updateBiddingState(state: BiddingState)

  // ── scores / completion ─────────────────────────────────────────────
  fun getMatchScores(): Map<String, Int>
  fun setMatchScores(scores: Map<String, Int>)

  fun recordRoundResult(entry: RoundResultEntry)
  fun getLastRoundResult(): RoundResultEntry?

  fun setWinnerIds(ids: List<String>)
  fun getWinnerIds(): List<String>
  fun isMatchComplete(): Boolean
}

/**
 * Current-user seam — MatchService resolves the calling uid from here
 * (never from a caller-supplied parameter) and compares it against the
 * match's seats map. Implemented against :app's AuthRepository at wiring
 * time.
 */
interface AuthPort {
  fun currentUid(): String?
}
