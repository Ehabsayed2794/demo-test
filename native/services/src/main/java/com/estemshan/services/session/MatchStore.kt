package com.estemshan.services.session

import com.estemshan.engine.Card
import com.estemshan.services.model.AdvanceResult
import com.estemshan.services.model.BiddingActionInput
import com.estemshan.services.model.DealResult
import com.estemshan.services.model.EndMatchResult
import com.estemshan.services.model.ExtendResult
import com.estemshan.services.model.HandDoc
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.SubmitBidResult
import com.estemshan.services.model.SubmitBiddingActionResult
import com.estemshan.services.model.SubmitCardResult

/**
 * The online match's read/write seam — the exact surface an online screen
 * needs from the services layer, and deliberately the FULL surface of
 * [com.estemshan.services.MatchService]'s public match API.
 *
 * Why this exists instead of the VM taking MatchService directly:
 * [com.estemshan.services.MatchService] is a concrete class over a real
 * [com.google.firebase.firestore.FirebaseFirestore], and this repo ships no
 * Firestore fake BY DESIGN (RoomPort's own header says so). An online
 * ViewModel that held MatchService could never be unit-tested on the JVM,
 * and the two-client convergence property that makes S17 trustworthy is a
 * JVM property. This interface is what a hermetic in-memory store
 * implements; production wires the real MatchService, which implements it.
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   UI → MatchStore/MatchAdapter → MatchService → Firestore
 * Nothing here touches the SDK; every method is the suspend shape the
 * caller already awaits.
 */
interface MatchStore {

  /** The room↔match atomic start; returns the matchId every seat converges on. */
  suspend fun startMatch(roomId: String): String

  /** Read-only fetch; null (never an error) when the match is gone. */
  suspend fun loadMatch(matchId: String): MatchDoc?

  /**
   * The local seat's authoritative hand for [round], or null when the
   * round is not dealt yet. `hands/{matchId}/hands/{seatId}` is readable
   * only by that seat's uid — a caller never learns another seat's cards.
   */
  suspend fun loadHand(matchId: String, seatId: String, round: Int): HandDoc?

  suspend fun submitBid(matchId: String, seatId: String, bid: Int): SubmitBidResult

  suspend fun submitBiddingAction(matchId: String, action: BiddingActionInput): SubmitBiddingActionResult

  suspend fun submitCard(matchId: String, card: Card): SubmitCardResult

  suspend fun dealRound(matchId: String, roundNumber: Int): DealResult

  suspend fun advanceToNextRound(matchId: String, completedRound: Int): AdvanceResult

  suspend fun extendMatchRounds(matchId: String, completedRound: Int, reason: String): ExtendResult

  suspend fun endMatch(
    matchId: String,
    completedRound: Int,
    finalScores: Map<String, Int>,
    winnerIds: List<String>,
  ): EndMatchResult
}
