package com.estemshan.functions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * S71 — the Firestore seam the "Play Unranked" conversion runs on.
 *
 * Two touches and no more: read the room, write the room's mode and
 * credential. The write is a FIXED target — `mode: UNRANKED` and no
 * credential — so the seam cannot be used to write anything else: there is no
 * field map for a caller to fill in, and the only two fields that ever change
 * are named here.
 *
 * That narrowness is deliberate. The conversion is the one operation in the
 * product that changes a room's authority, so the seam that performs it must
 * not be able to perform a different one: a `writeFields(map)` seam would put
 * "which fields may a mode transition carry" back into the caller's hands.
 *
 * The interface exists so [playUnrankedOnce] is unit-testable with a fake —
 * this repo ships no Firestore fake by design, so the seam IS the test hook —
 * and only [AdminPlayUnrankedDb] loads the Admin SDK.
 */
interface PlayUnrankedDb {

  /** One transaction: the read, the decision and the write, atomically. */
  suspend fun <T> transact(block: suspend (Tx) -> T): T

  interface Tx {

    /** `rooms/{roomId}`, plus `"roomId"` carrying the document id the read used. */
    suspend fun readRoom(roomId: String): Map<String, Any?>?

    /**
     * Writes the conversion's whole effect: `mode` → `UNRANKED`, the access
     * credential cleared, `updatedAt` re-stamped, and the pool anchor that
     * starts the room's four-minute fill window.
     */
    fun writeUnranked(roomId: String)
  }
}

private const val COLLECTION_ROOMS = "rooms"

private const val FIELD_UPDATED_AT = "updatedAt"
private const val FIELD_ROOM_ID = "roomId"

/**
 * The conversion's WHOLE write, as a pure map.
 *
 * The mode transition is [unrankedWriteFields]' constant; this adds the two
 * server-owned stamps the transition also lands. Both are arguments rather
 * than values read from a clock here, so the payload is provable without a
 * Firestore fake and without a clock of its own.
 *
 * **The pool anchor is the point of the function.** `poolOpenedAt` is when the
 * room ENTERED the Unranked pool, and the four-minute bot-fill window runs
 * from it. A room converted an hour after it was created entered the pool a
 * minute ago, so the anchor can never be the room's `createdAt` — it is this
 * write's own stamp. A conversion that omitted it would silently inherit the
 * creation time and hand the table to the bots on the next resolver pass,
 * before the host ever waited.
 */
internal fun unrankedConversionWrite(
  updatedAt: Any,
  poolOpenedAt: Any,
): Map<String, Any?> = LinkedHashMap(unrankedWriteFields()).apply {
  put(FIELD_UPDATED_AT, updatedAt)
  put(ROOM_FIELD_POOL_OPENED_AT, poolOpenedAt)
}

/**
 * Admin-SDK implementation.
 *
 * The Admin SDK bypasses `firestore.rules`, which is exactly why this write
 * is legal here and illegal for a client: `isValidRoomUpdate`'s affected-keys
 * allowlist has no `mode`, and the rules are frozen. The caller's identity
 * comes from the verified callable token rather than from any document, so a
 * request cannot name a host other than itself.
 */
class AdminPlayUnrankedDb : PlayUnrankedDb {
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  private fun roomRef(roomId: String): DocRef = db.collection(COLLECTION_ROOMS).doc(roomId)

  override suspend fun <T> transact(block: suspend (PlayUnrankedDb.Tx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  private inner class AdminTx(private val tx: Transaction) : PlayUnrankedDb.Tx {
    override suspend fun readRoom(roomId: String): Map<String, Any?>? {
      val snap = tx.get(roomRef(roomId)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      val fields = dynamicToKotlin(snap.data()) as? Map<String, Any?> ?: return null
      // The id the read used, re-attached: a room created under an auto-ID has
      // an id that is nowhere in its own fields.
      return LinkedHashMap(fields).apply { put(FIELD_ROOM_ID, roomId) }
    }

    override fun writeUnranked(roomId: String) {
      // The payload is the conversion's own constant plus the two server
      // stamps, so this seam cannot write anything else.
      //
      // The room entered the Unranked pool the moment it was converted, so
      // that is the anchor of its four-minute fill window. A client cannot
      // stamp it (`isValidNewRoom`'s field allowlist has no `poolOpenedAt`),
      // which is why the anchor is written here and not by the converter's
      // caller. Re-converting an already-Unranked room re-anchors its window
      // — the only case where the conversion writes on an Unranked room.
      val payload = unrankedConversionWrite(
        updatedAt = FieldValue.serverTimestamp(),
        poolOpenedAt = FieldValue.serverTimestamp(),
      )
      tx.update(roomRef(roomId), toPlain(payload))
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
