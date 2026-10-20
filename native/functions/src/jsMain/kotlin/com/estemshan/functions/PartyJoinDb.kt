package com.estemshan.functions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * S70 — the Firestore seam the Rank-down join gate runs on.
 *
 * Three touches and no more: read the room, read a profile, write the room's
 * `players`. That narrow surface is the point — RD29 says the eligibility
 * check happens "server-side only", so the client must not need a fourth
 * operation, and the gate's one write is the append itself. A room's `mode`,
 * `rankDown`, `creator` and `status` are read but never written here; the
 * callable that gates a join has no business reconfiguring the room.
 *
 * Everything runs inside one [transact]: the capacity check and the append must
 * be atomic or two callers racing for the last seat both pass the check and the
 * room ends up with five players. The two profiles are read in the same
 * transaction for the weaker but still real reason that a rank which moved
 * between the read and the write makes the decision about a player who is not
 * the one in front of the gate.
 *
 * The interface exists so [joinOnce] is unit-testable with a fake — this repo
 * ships no Firestore fake by design, so the seam IS the test hook — and only
 * [AdminPartyJoinDb] loads the Admin SDK.
 */
interface PartyJoinDb {
  suspend fun <T> transact(block: suspend (Tx) -> T): T

  interface Tx {
    /** `rooms/{roomId}`, plus `"roomId"` carrying the document id the read used. */
    suspend fun readRoom(roomId: String): Map<String, Any?>?

    /** `players/{uid}`, or null when the account has no profile document. */
    suspend fun readProfile(uid: String): Map<String, Any?>?

    /**
     * Replace `rooms/{roomId}`'s `players` with [players] and stamp
     * `updatedAt` with a server timestamp.
     *
     * A replacement rather than an array-union because the caller list is
     * derived from the list this same transaction read, so it is already the
     * union — and a union of a list that raced past capacity would be the bug
     * the transaction exists to prevent.
     */
    fun writePlayers(roomId: String, players: List<String>)
  }
}

private const val COLLECTION_ROOMS = "rooms"
private const val COLLECTION_PLAYERS = "players"

private const val FIELD_PLAYERS = "players"
private const val FIELD_UPDATED_AT = "updatedAt"
private const val FIELD_ROOM_ID = "roomId"

/**
 * Admin-SDK implementation.
 *
 * `rooms/{id}` and `players/{uid}` are both unwritable for a client under the
 * current rules in the ways this gate needs — a room's `players` array IS
 * writable by any authenticated client (see the bypass note on [joinOnce]),
 * which is exactly why the gate cannot be a client check, and a profile is only
 * writable by its own owner. Admin reads and writes bypass rules, and the
 * caller's uid comes from the verified callable identity rather than from any
 * document, so a request cannot name a player other than itself.
 */
class AdminPartyJoinDb : PartyJoinDb {
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  private fun roomRef(roomId: String): DocRef = db.collection(COLLECTION_ROOMS).doc(roomId)

  private fun playerRef(uid: String): DocRef = db.collection(COLLECTION_PLAYERS).doc(uid)

  override suspend fun <T> transact(block: suspend (PartyJoinDb.Tx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  private inner class AdminTx(private val tx: Transaction) : PartyJoinDb.Tx {
    override suspend fun readRoom(roomId: String): Map<String, Any?>? {
      val snap = tx.get(roomRef(roomId)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      val fields = dynamicToKotlin(snap.data()) as? Map<String, Any?> ?: return null
      // The id the read used, re-attached: the projection must answer for the
      // document that exists, and a room created under an auto-ID has an id
      // that is nowhere in its own fields.
      return LinkedHashMap(fields).apply { put(FIELD_ROOM_ID, roomId) }
    }

    override suspend fun readProfile(uid: String): Map<String, Any?>? {
      val snap = tx.get(playerRef(uid)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override fun writePlayers(roomId: String, players: List<String>) {
      tx.update(
        roomRef(roomId),
        toPlain(
          linkedMapOf<String, Any?>(
            FIELD_PLAYERS to players,
            FIELD_UPDATED_AT to FieldValue.serverTimestamp(),
          ),
        ),
      )
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
