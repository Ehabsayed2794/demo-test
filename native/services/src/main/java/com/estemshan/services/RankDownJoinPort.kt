package com.estemshan.services

import com.estemshan.services.model.Reasons
import com.estemshan.services.model.RoomDoc
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/**
 * S70 (RD29/RD30) — the outcome of one Rank-down join, in the client's own
 * vocabulary.
 *
 * [Joined] carries the room as the server wrote it, and NOTHING else: RD29's
 * privacy rule is that the client never reads another player's rank, so the
 * callable's projection has no rank field to parse even if a caller wanted one
 * (see `PartyJoin.kt` in `:functions`, which builds that projection). [Denied]
 * carries the stable machine [reason] the screen switches on and a plain-language
 * [message] — never a tier, division, RP value, or anyone's uid.
 */
sealed interface RankDownJoin {

  /** The join was permitted; [room] is the server's post-write room state. */
  data class Joined(val room: RoomDoc) : RankDownJoin

  /**
   * The join did not happen. [reason] is always one of the [Reasons] codes this
   * build knows — an unknown reason from a newer server is rewritten to
   * [Reasons.RANK_DOWN_UNAVAILABLE] rather than passed through, so a screen can
   * never be handed a token it cannot explain.
   */
  data class Denied(val reason: String, val message: String) : RankDownJoin
}

/**
 * The seam over the `joinRankDownRoom` callable.
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   UI → RoomPort → RoomService → RankDownJoinPort → (Cloud Functions gate) → Firestore
 *
 * WHY THE SEAM — RD29's eligibility question needs BOTH players' ranks, and
 * `firestore.rules` keeps `players/{uid}` owner-readable, so no client can ask
 * the question it is answering: only an Admin-SDK read inside the functions
 * module can see the creator's profile at all. This port is therefore not a
 * convenience wrapper over a client capability; it is the only door, the same
 * reason [SeatReclaimPort] exists.
 *
 * WHY A RETURNED OUTCOME AND NOT AN EXCEPTION — [RoomService.joinRoom] throws
 * [com.estemshan.services.model.ServiceException] for the room-shape denials it
 * can still decide locally, and RD29's two eligibility denials are not
 * exceptional: "this friend is two tiers away" is the expected answer to the
 * question the host asked, and the flow that receives it offers Unranked. A
 * sealed return forces the caller to handle that answer instead of letting a
 * catch block flatten it into "something went wrong".
 */
interface RankDownJoinPort {

  /**
   * Asks the server to add the CALLER to the Rank-down room at [roomId].
   *
   * Idempotent: a caller already in `players` gets [Joined] with no second
   * write, so a retry after a dropped response cannot double-seat anyone.
   * Never throws for a denial the server answered — every eligibility and
   * room-shape refusal arrives as [RankDownJoin.Denied].
   */
  suspend fun join(roomId: String): RankDownJoin
}

/**
 * The production [RankDownJoinPort] — a thin typed read over the callable's
 * response, with no client-side eligibility guessing of any kind.
 *
 * Deliberately NO pre-read of the room and no local rank comparison: the
 * client cannot lawfully read the creator's profile, and a check built on a
 * stale snapshot would be asking the stale copy whether the server's live
 * document was joinable — the one question it cannot answer. [RoomService]
 * does read the room first, but only to recognise the already-a-member case it
 * can serve without spending a call.
 */
class CallableRankDownJoin(
  private val functions: FirebaseFunctions,
) : RankDownJoinPort {

  private val callable = functions.getHttpsCallable(NAME)

  override suspend fun join(roomId: String): RankDownJoin = try {
    RankDownJoinResponse.forData(callable.call(mapOf(ROOM_ID to roomId)).await().getData())
  } catch (e: FirebaseFunctionsException) {
    RankDownJoinResponse.forException(e.code.name, e.details)
  } catch (e: Exception) {
    // Transport-level: no network, an interrupted await, or the callable could
    // not be reached at all. Never read as "you may join" and never as an
    // eligibility verdict — the caller shows its own retry, and the room stays
    // exactly as it is.
    RankDownJoin.Denied(Reasons.UNAVAILABLE, MESSAGE_UNAVAILABLE)
  }

  private companion object {
    const val NAME = "joinRankDownRoom"
    const val ROOM_ID = "roomId"
  }
}

/**
 * The callable's answer → [RankDownJoin] mapping, split out of
 * [CallableRankDownJoin] so both halves of this story's contract are pinned in
 * the hermetic [RankDownJoinResponseTest] rather than only against a Functions
 * emulator — the same reason [SeatReclaimResponse] exists. Two things are
 * proven here and nowhere else: which keys of the response the client is
 * allowed to know, and what a code or reason this build has never seen turns
 * into.
 *
 * Both live in this file, beside the only caller, so the transport and its
 * reader cannot drift apart.
 */
object RankDownJoinResponse {

  /**
   * A permitted join becomes [RankDownJoin.Joined] over the projected room; a
   * refusal becomes a [RankDownJoin.Denied]. Nothing here invents a join: a
   * payload that is not a map, that omits `joined`, that says `joined: true`
   * without a parsable `room`, or whose `joined` is not a Boolean at all is the
   * version-skew denial — the fail-closed direction, because the alternative
   * reading would put a player into a room the server never confirmed.
   */
  @Suppress("UNCHECKED_CAST")
  fun forData(data: Any?): RankDownJoin {
    val fields = data as? Map<*, *> ?: return skew()
    return when (fields[JOINED]) {
      true -> {
        val roomFields = fields[ROOM] as? Map<String, Any?> ?: return skew()
        val room = RoomDoc.fromFields(roomFields) ?: return skew()
        RankDownJoin.Joined(room)
      }
      false -> {
        val reason = fields[REASON] as? String
        val message = fields[MESSAGE] as? String
        if (reason != null && reason in ELIGIBILITY_REASONS &&
          message != null && message.isNotBlank()
        ) {
          // The server's own rank-free sentence, surfaced verbatim: RD29's
          // escape hatch lives in it ("the host can switch this game to
          // Unranked"), so rewriting it would delete the only way out.
          RankDownJoin.Denied(reason, message)
        } else {
          skew()
        }
      }
      else -> skew()
    }
  }

  /**
   * A thrown [HttpsError] → [RankDownJoin.Denied], reading the server's stable
   * `details.reason` first and the transport code only when there is no reason
   * this build knows.
   *
   * The order matters. `details.reason` is the callable's own vocabulary and
   * survives a code that says nothing on its own — a `failed-precondition` is
   * "the room is full", "this is not a Rank-down room", or "I could not read
   * that profile", and the three need three different sentences. The
   * by-code table is the fallback for an older server, a proxy that stripped
   * the details, or a reason token from a future build: coarser, but never a
   * guess at an eligibility verdict.
   *
   * Takes the code's [String] name because
   * `FirebaseFunctionsException.Code` cannot be initialized on a plain JVM at
   * all — [SeatReclaimResponse]'s exact constraint, for the same reason.
   */
  fun forException(codeName: String, details: Any?): RankDownJoin.Denied {
    val serverReason = (details as? Map<*, *>)?.get(REASON) as? String
    val reason = when (serverReason) {
      REASON_ROOM_NOT_FOUND -> Reasons.ROOM_NOT_FOUND
      REASON_ROOM_CLOSED -> Reasons.ROOM_CLOSED
      REASON_ROOM_FULL -> Reasons.ROOM_FULL
      REASON_NOT_RANK_DOWN, REASON_MALFORMED_RESPONSE -> Reasons.RANK_DOWN_UNAVAILABLE
      REASON_UNAVAILABLE, REASON_DEADLINE_EXCEEDED, REASON_INTERNAL -> Reasons.UNAVAILABLE
      // Absent, unreadable, or a token this build has never seen — including
      // RANK_INCOMPATIBLE, which is a returned denial and never an HttpsError,
      // so one arriving here is skew and is not reinterpreted as a verdict.
      else -> forCode(codeName)
    }
    return RankDownJoin.Denied(reason, messageOf(reason))
  }

  /** The by-code fallback: the transport alone, with no server vocabulary. */
  private fun forCode(codeName: String): String = when (codeName) {
    "NOT_FOUND" -> Reasons.ROOM_NOT_FOUND
    "FAILED_PRECONDITION" -> Reasons.RANK_DOWN_UNAVAILABLE
    "RESOURCE_EXHAUSTED" -> Reasons.ROOM_FULL
    "INVALID_ARGUMENT" -> Reasons.INVALID_ARGUMENT
    "UNAUTHENTICATED", "PERMISSION_DENIED" -> Reasons.UNAUTHENTICATED
    else -> Reasons.UNAVAILABLE
  }

  /**
   * The one sentence per client reason, all of them rank-free by construction
   * and pinned as much by [RankDownJoinResponseTest] as by the words here. A
   * denial the player cannot read is a denial that gets retried, so every
   * branch has to say something in ordinary language — and only the genuinely
   * transient one may invite a retry.
   */
  private fun messageOf(reason: String): String = when (reason) {
    Reasons.ROOM_NOT_FOUND -> "That room no longer exists."
    Reasons.ROOM_CLOSED -> "This room is closed."
    Reasons.ROOM_FULL -> "This room is full."
    Reasons.INVALID_ARGUMENT -> "That room code is not valid."
    Reasons.UNAUTHENTICATED -> "This action requires you to be signed in."
    Reasons.UNAVAILABLE -> MESSAGE_UNAVAILABLE
    else -> "Rank-down isn't available for this room."
  }

  private fun skew(): RankDownJoin.Denied = RankDownJoin.Denied(
    Reasons.RANK_DOWN_UNAVAILABLE,
    messageOf(Reasons.RANK_DOWN_UNAVAILABLE),
  )

  private const val JOINED = "joined"
  private const val ROOM = "room"
  private const val REASON = "reason"
  private const val MESSAGE = "message"

  /**
   * The two reasons the callable may answer `joined: false` with — RD29's
   * eligibility verdict and its fail-closed counterpart. Spelled as literals
   * because `:services` cannot see `:functions`; the mirror of the engine's
   * [com.estemshan.engine.PARTY_DENIAL_REASON] and of PartyGate's
   * `PROFILE_UNAVAILABLE`, with drift caught by the emulator suites on both
   * sides.
   */
  private val ELIGIBILITY_REASONS = setOf(
    Reasons.RANK_INCOMPATIBLE,
    Reasons.PROFILE_UNAVAILABLE,
  )

  // The callable's `details.reason` vocabulary, likewise as literals.
  private const val REASON_ROOM_NOT_FOUND = "ROOM_NOT_FOUND"
  private const val REASON_ROOM_CLOSED = "ROOM_CLOSED"
  private const val REASON_ROOM_FULL = "ROOM_FULL"
  private const val REASON_NOT_RANK_DOWN = "NOT_RANK_DOWN"
  private const val REASON_MALFORMED_RESPONSE = "MALFORMED_RESPONSE"

  // Transport code names that can also arrive as a server reason token.
  private const val REASON_UNAVAILABLE = "UNAVAILABLE"
  private const val REASON_DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
  private const val REASON_INTERNAL = "INTERNAL"
}

/**
 * The one retryable sentence, shared by [CallableRankDownJoin]'s transport
 * catch and [RankDownJoinResponse]'s [Reasons.UNAVAILABLE] branch: the two
 * paths mean the same thing — the function never answered — and must not say it
 * two ways. File-private so both can read it without either owning it.
 */
private const val MESSAGE_UNAVAILABLE = "Rank-down is unavailable right now. Please try again."
