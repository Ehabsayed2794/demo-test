package com.estemshan.services.session

import com.estemshan.services.model.MatchDoc

/**
 * MatchAdapter's write-side seam — the identity/authority translations
 * submitCard()/submitBiddingAction() must route through rather than
 * reimplement (design-ui/match-adapter.js uidToSeat/seatToUid/
 * assertLocalTurn). THE reference is docs/specs/02-engine-api.md §6
 * layering: services talk to the adapter, never to raw seats.
 *
 * Pure — an implementation never touches Firestore and signals a wrong
 * turn by throwing [NotLocalTurn] rather than returning a boolean the
 * caller could ignore.
 */
interface MatchAdapterPort {

  /** uid -> seatId in THIS match, or null for an unseated uid. */
  fun uidToSeat(match: MatchDoc, uid: String): String?

  /** seatId -> uid, or null for a seat this match doesn't have. */
  fun seatToUid(match: MatchDoc, seatId: String): String?

  /**
   * Turn authority gate. Throws [NotLocalTurn] when [seatId] does not own
   * the current turn — MatchService converts that to NOT_YOUR_TURN and
   * writes nothing, exactly as match-service.js's resolveSeatAndAuthorize()
   * converts MatchAdapter.assertLocalTurn()'s own raw NOT_LOCAL_TURN.
   * Never returns silently for a wrong seat.
   */
  fun assertLocalTurn(match: MatchDoc, seatId: String)
}

/**
 * match-adapter.js's NOT_LOCAL_TURN — a signal, not an error message:
 * the caller converts it to a typed reason code at the service boundary.
 */
class NotLocalTurn(message: String) : RuntimeException(message)
