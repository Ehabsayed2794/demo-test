package com.estemshan.functions

import com.estemshan.engine.MatchMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * S56 — the Firestore seam the Ranked queue runs on.
 *
 * **THE ZERO-RULES PATH.** `rankedSearches/{uid}` has NO `firestore.rules`
 * block at all, so the catch-all deny makes every client write to it
 * impossible while the Admin SDK writes it freely — the same shape
 * `timeoutCounts`, `inactivity`, `inactiveTakeovers` and `disconnectVotes`
 * already established. Clients observe the queue only through the callables,
 * so `firestore.rules` stays byte-identical for S56.
 *
 * The surface is deliberately small, because the queue's whole design is that
 * it is server-only: read one search, list the queue, read the caller's own
 * profile (the tier is derived from it), write one search, renew its lease,
 * resolve it, delete it, and create the table a pair forms. Nothing else
 * touches Firestore, and nothing else may.
 *
 * The seam exists so the resolver is unit-testable with a fake — this repo
 * ships no Firestore fake by design, so the seam IS the test hook — and only
 * [AdminMatchmakingSearchDb] loads the Admin SDK.
 */
interface MatchmakingSearchDb {

  /** `rankedSearches/{uid}` — the caller's own search document, or null. */
  suspend fun readSearch(uid: String): Map<String, Any?>?

  /**
   * The queue's WAITING documents.
   *
   * The status filter belongs to the QUERY, not to the resolver: a resolved
   * search stays in the collection for ever (its outcome pointer is what a
   * re-poll reads), so a query that did not exclude it would spend its whole
   * window on documents that are no longer candidates.
   *
   * An equality filter on ONE field needs no composite index, and
   * `firebase.json` declares no indexes artifact at all — so the queue is
   * readable with the rules file, the indexes and the rules' SHA pin
   * untouched. The order the documents come back in is Firestore's, not ours:
   * the resolver re-sorts them by `(enqueuedAt, uid)` itself, because the
   * resolver's order must be deterministic and an equality query promises
   * nothing about the order of its hits.
   */
  suspend fun listWaitingSearches(limit: Int): List<SearchDocument>

  /**
   * The open UNRANKED rooms — the tables whose free seats the shared pool
   * may fill (S71).
   *
   * One equality filter on the mode, so Firestore's automatic single-field
   * index answers it and no composite index is needed — `firebase.json`
   * declares no indexes artifact at all. The status filter is applied by the
   * caller in memory, because a second equality filter on another field is
   * exactly what would need one.
   */
  suspend fun listUnrankedRooms(limit: Int): List<RoomDocument>

  /**
   * A fresh, unguessable id for the table a pair forms.
   *
   * The Admin SDK's own auto-id, for one reason: a client that could predict
   * the id could create that room first and take it over. The server's table
   * ids are therefore not a client-computable value.
   */
  fun newRoomId(): String

  /**
   * A fresh, unguessable id for the match the server creates (S71).
   *
   * The Admin SDK's own auto-id, for the same reason as [newRoomId]: the
   * match a bot-filled table becomes is written by the server alone, and an
   * id a client could predict is an id a client could pre-create.
   */
  fun newMatchId(): String

  suspend fun <T> transact(block: suspend (Tx) -> T): T

  interface Tx {

    /** `rankedSearches/{uid}` — null when the caller has no live search. */
    suspend fun readSearch(uid: String): Map<String, Any?>?

    /** `players/{uid}` — the profile the eligible tier is derived from. */
    suspend fun readProfile(uid: String): Map<String, Any?>?

    /**
     * `rooms/{roomId}` — the room's own fields.
     *
     * Read INSIDE the transaction, so a room that changed between the scan
     * and the write is judged on what it is now, not on what it was.
     */
    suspend fun readRoom(roomId: String): Map<String, Any?>?

    /**
     * Writes the search document's COMPLETE state.
     *
     * A whole-document write, not a merge: the queue document's fields are
     * exactly the ones this queue writes, so a replacement can never leave a
     * stale field behind — and a replacement also creates the document when
     * the caller has no search yet, which a field-level update could not.
     */
    fun putSearch(uid: String, fields: Map<String, Any?>)

    /**
     * Renews the search's lease — the heartbeat that keeps it a candidate.
     *
     * A field-level update, never a replacement: the renewal is about the
     * lease and must not disturb the fields it is not about.
     */
    fun renewSearch(uid: String, heartbeatMillis: Long)

    /**
     * Resolves the search: the terminal outcome pointer and the status that
     * says so. Nothing else moves — the arrival stamp and the tier stay, so
     * the document still says who searched, when and at what tier.
     */
    fun resolveSearch(uid: String, tableId: String)

    /**
     * Removes a WAITING search from the queue — the cancellation, and the
     * lease's reclaim. A RESOLVED search is never deleted: a table that
     * formed cannot be un-formed.
     */
    fun deleteSearch(uid: String)

    /**
     * Creates the table a pair forms: `rooms/{roomId}`, in the exact field
     * shape `RoomService.createRoom` writes, so the frozen room schema, the
     * client's own room parser and `MatchService.startMatch` all accept it
     * unchanged. The MATCH is never created here — that existing path creates
     * it, once the table is ready.
     */
    fun createRoom(roomId: String, fields: Map<String, Any?>)

    /**
     * Creates the match a bot-filled Unranked table becomes (S71).
     *
     * The Admin SDK is the ONLY writer that may do this: a bot seat is a seat
     * no client may write (`isValidSeatMap` demands every seat's uid to be
     * one of the `players` at creation), so the configured bots can only ever
     * take a seat through a writer the rules do not bind.
     *
     * The fields are the match's own shape, field for field
     * (`buildInitialMatchFields`), with the two the mode's own: `players` are
     * the humans alone, and `botSeats` names the seats the bots took.
     */
    fun createMatch(matchId: String, fields: Map<String, Any?>)

    /**
     * Binds the room to the match it became: `status` → `in_game` and
     * `matchId` → the new match.
     *
     * The same two writes `MatchService.startMatch` makes, in the same
     * transaction as the match's own creation — so a room that has a match
     * can never be a room that is still waiting, and a second pass finds
     * the binding already there and writes nothing.
     */
    fun bindRoomToMatch(roomId: String, matchId: String)

    /**
     * Seats a player into an open Unranked room (S71).
     *
     * The room's WHOLE `players` list is written, never an array-union: the
     * server owns the roster, so it decides who is seated and writes the
     * list it decided on. A client could do this too — the room rules let a
     * member add itself — but then two clients racing for the last seat
     * would each believe they won it.
     */
    fun seatPlayers(roomId: String, players: List<String>)
  }
}

/** One queue document: its id and its fields. */
data class SearchDocument(val id: String, val fields: Map<String, Any?>)

/** One room document: its id and its fields. */
data class RoomDocument(val id: String, val fields: Map<String, Any?>)

/**
 * The queue's collection.
 *
 * Top-level and camelCase-plural, like `rooms`, `matches` and `players`; and
 * — the reason it matters — it has no `firestore.rules` block, so the
 * catch-all deny makes it admin-only by construction.
 */
internal const val SEARCH_COLLECTION = "rankedSearches"

/** The rooms collection — the tables the resolver forms and fills. */
private const val ROOMS_COLLECTION = "rooms"

/** The matches collection — the match a bot-filled table becomes (S71). */
private const val MATCHES_COLLECTION = "matches"

/** Admin-SDK implementation. Reads normalize through dynamicToKotlin; writes
 *  normalize through toPlain. */
class AdminMatchmakingSearchDb : MatchmakingSearchDb {
  // Before getFirestore() resolves the default app — see ensureDefaultAdminApp.
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  private fun searchRef(uid: String): DocRef = db.collection(SEARCH_COLLECTION).doc(uid)

  /** `players/{uid}` — the profile the tier is derived from, top-level and admin-read. */
  private fun playerRef(uid: String): DocRef = db.collection("players").doc(uid)

  override suspend fun readSearch(uid: String): Map<String, Any?>? {
    val snap = searchRef(uid).get().await()
    if (!snap.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snap.data()) as? Map<String, Any?>
  }

  override suspend fun listWaitingSearches(limit: Int): List<SearchDocument> {
    val snap = db.collection(SEARCH_COLLECTION)
      .where(QUEUE_FIELD_STATUS, "==", QUEUE_STATUS_WAITING)
      .limit(limit)
      .get()
      .await()
    return snap.docs.map { doc ->
      @Suppress("UNCHECKED_CAST")
      SearchDocument(doc.id, dynamicToKotlin(doc.data()) as? Map<String, Any?> ?: emptyMap())
    }
  }

  override suspend fun listUnrankedRooms(limit: Int): List<RoomDocument> {
    val snap = db.collection(ROOMS_COLLECTION)
      .where(ROOM_FIELD_MODE, "==", MatchMode.UNRANKED.name)
      .limit(limit)
      .get()
      .await()
    return snap.docs.map { doc ->
      @Suppress("UNCHECKED_CAST")
      RoomDocument(doc.id, dynamicToKotlin(doc.data()) as? Map<String, Any?> ?: emptyMap())
    }
  }

  override fun newRoomId(): String = db.collection("rooms").doc().id

  override fun newMatchId(): String = db.collection(MATCHES_COLLECTION).doc().id

  override suspend fun <T> transact(block: suspend (MatchmakingSearchDb.Tx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  private inner class AdminTx(private val tx: Transaction) : MatchmakingSearchDb.Tx {
    override suspend fun readSearch(uid: String): Map<String, Any?>? {
      val snap = tx.get(searchRef(uid)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override suspend fun readProfile(uid: String): Map<String, Any?>? {
      val snap = tx.get(playerRef(uid)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override suspend fun readRoom(roomId: String): Map<String, Any?>? {
      val snap = tx.get(db.collection(ROOMS_COLLECTION).doc(roomId)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override fun putSearch(uid: String, fields: Map<String, Any?>) {
      tx.set(searchRef(uid), toPlain(stamped(fields)))
    }

    override fun renewSearch(uid: String, heartbeatMillis: Long) {
      tx.update(
        searchRef(uid),
        toPlain(stamped(linkedMapOf<String, Any?>(QUEUE_FIELD_HEARTBEAT_AT to heartbeatMillis))),
      )
    }

    override fun resolveSearch(uid: String, tableId: String) {
      tx.update(
        searchRef(uid),
        toPlain(
          linkedMapOf<String, Any?>(
            QUEUE_FIELD_STATUS to QUEUE_STATUS_RESOLVED,
            QUEUE_FIELD_TABLE_ID to tableId,
          ),
        ),
      )
    }

    override fun deleteSearch(uid: String) {
      tx.delete(searchRef(uid))
    }

    override fun createRoom(roomId: String, fields: Map<String, Any?>) {
      // The room's own timestamps are the Admin SDK's server timestamps — the
      // server's clock, never a client's, exactly as `RoomService.createRoom`
      // stamps them.
      val room = LinkedHashMap(fields)
      room["createdAt"] = FieldValue.serverTimestamp()
      room["updatedAt"] = FieldValue.serverTimestamp()
      tx.set(db.collection(ROOMS_COLLECTION).doc(roomId), toPlain(stamped(room)))
    }

    override fun seatPlayers(roomId: String, players: List<String>) {
      tx.update(
        db.collection(ROOMS_COLLECTION).doc(roomId),
        toPlain(
          linkedMapOf<String, Any?>(
            "players" to players,
            "updatedAt" to FieldValue.serverTimestamp(),
          ),
        ),
      )
    }

    override fun createMatch(matchId: String, fields: Map<String, Any?>) {
      // The match's own creation stamp is the Admin SDK's server timestamp —
      // the server's clock, never a client's, exactly as
      // `MatchService.startMatch` stamps the match it creates.
      val match = LinkedHashMap(fields)
      match["createdAt"] = FieldValue.serverTimestamp()
      tx.set(db.collection(MATCHES_COLLECTION).doc(matchId), toPlain(match))
    }

    override fun bindRoomToMatch(roomId: String, matchId: String) {
      tx.update(
        db.collection(ROOMS_COLLECTION).doc(roomId),
        toPlain(
          linkedMapOf<String, Any?>(
            "status" to ROOM_STATUS_IN_GAME,
            "matchId" to matchId,
            "updatedAt" to FieldValue.serverTimestamp(),
          ),
        ),
      )
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}

/**
 * The queue's two time fields are epoch millis on the seam and Firestore
 * Timestamps in the document.
 *
 * A real timestamp is what an index orders and what `timestampToMillis` reads
 * back, and the millis on the seam keep the resolver's lease arithmetic
 * exact — the same number the writer stamped and the reader compares. The
 * Unranked room's pool stamp travels the same way: it is a window anchor,
 * and an anchor read back as a Firestore Timestamp is the only shape the
 * reader can compare against a server clock.
 */
private fun stamped(fields: Map<String, Any?>): Map<String, Any?> {
  val out = LinkedHashMap<String, Any?>(fields)
  for (key in listOf(QUEUE_FIELD_ENQUEUED_AT, QUEUE_FIELD_HEARTBEAT_AT, ROOM_FIELD_POOL_OPENED_AT)) {
    val millis = out[key] as? Long ?: continue
    out[key] = Timestamp.fromMillis(millis.toDouble())
  }
  return out
}
