package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.DisconnectVoteState
import com.estemshan.engine.InactiveTakeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import com.estemshan.functions.TimeoutResolution.COUNT_FIELD

/** Admin-SDK implementation. Reads normalize through dynamicToKotlin;
 *  writes normalize through translate + toPlain. */
class AdminTimeoutDb : TimeoutDb {
  // Before getFirestore() resolves the default app — see ensureDefaultAdminApp.
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  private fun matchRef(matchId: String): DocRef = db.collection("matches").doc(matchId)

  override suspend fun readMatch(matchId: String): Map<String, Any?>? {
    val snap = matchRef(matchId).get().await()
    if (!snap.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snap.data()) as? Map<String, Any?>
  }

  /**
   * The round's dealt hands, read with the admin SDK. firestore.rules keeps
   * `hands/{seatId}` owner-read-only (`allow get: if ownsSeat()`), which is
   * exactly why the decision is computed here rather than on the acting
   * client: the admin read never exposes a hand to any player, and the card
   * this produces is the only thing written back.
   */
  override suspend fun readHands(
    matchId: String,
    round: Int,
    seats: List<String>,
  ): Map<String, List<Card>> {
    val out = LinkedHashMap<String, List<Card>>()
    for (seat in seats) {
      val snap = matchRef(matchId).collection("hands").doc(seat).get().await()
      if (!snap.exists) continue
      @Suppress("UNCHECKED_CAST")
      val fields = dynamicToKotlin(snap.data()) as? Map<String, Any?> ?: continue
      val handRound = (fields["round"] as? Number)?.toInt() ?: continue
      if (handRound != round) continue
      val cards = (fields["cards"] as? List<*>).orEmpty().mapNotNull { parseStoredCard(it) }
      out[seat] = cards
    }
    return out
  }

  override suspend fun <T> transact(block: suspend (TimeoutDb.TimeoutTx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  private inner class AdminTx(private val tx: Transaction) : TimeoutDb.TimeoutTx {
    override suspend fun readMatch(matchId: String): Map<String, Any?>? {
      val snap = tx.get(matchRef(matchId)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override fun updateMatch(matchId: String, updates: Map<String, Any?>) {
      // updates["updatedAt"] is the core's ServerTimestampSentinel, and the
      // log fields are ArrayUnionSentinel — resolved here, at the commit
      // boundary (FieldValue.serverTimestamp() / arrayUnion).
      tx.update(matchRef(matchId), toPlain(Sentinels.translate(updates)))
    }

    override suspend fun readTimeoutCount(matchId: String, uid: String): Any? {
      val snap = tx.get(countRef(matchId, uid)).await()
      if (!snap.exists) return null
      return dynamicToKotlin(snap.data())?.let { it as? Map<*, *> }?.get(COUNT_FIELD)
    }

    override suspend fun timeoutCountExists(matchId: String, uid: String): Boolean =
      tx.get(countRef(matchId, uid)).await().exists

    override fun writeTimeoutCount(matchId: String, uid: String, count: Int) {
      tx.set(countRef(matchId, uid), toPlain(mapOf(COUNT_FIELD to count)))
    }

    /**
     * S73 (RD19): the whole private document, normalized exactly like the match
     * read, so a JS object handed to [InactivityClock.fromStored]'s validators
     * behaves the same as one built in a test. Missing doc ⇒ null ⇒ "never
     * recorded", which is the trusted first-window state.
     */
    override suspend fun readInactivity(matchId: String, uid: String): Map<String, Any?>? {
      val snap = tx.get(inactivityRef(matchId, uid)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    /**
     * Replacement write, the counter's own idiom: the three fields ARE this
     * mechanism's complete state for the player, so merging could only leave a
     * stale cursor or an accumulation the takeover was supposed to zero.
     */
    override fun writeInactivity(matchId: String, uid: String, fields: Map<String, Any?>) {
      tx.set(inactivityRef(matchId, uid), toPlain(Sentinels.translate(fields)))
    }

    /**
     * S7 (A8.2): the ballot document's tombstone, against the same
     * admin-only-by-construction path `DisconnectVoteDb` uses — the
     * subcollection has no rules block at all, so the delete is an admin
     * action by construction and `firestore.rules` stays byte-identical.
     */
    override suspend fun deleteBallot(matchId: String, uid: String) {
      tx.delete(ballotRef(matchId, uid))
    }
  }

  /** `matches/{matchId}/timeoutCounts/{uid}` — owner-read-only by the rules. */
  private fun countRef(matchId: String, uid: String): DocRef =
    matchRef(matchId).collection("timeoutCounts").doc(uid)

  /**
   * `matches/{matchId}/inactivity/{uid}` — RD19's private accumulator, keyed by
   * PLAYER like the counter and for the same reason: how long someone has been
   * ignoring the table is not their opponents' business. It has NO rules block
   * at all, which is what makes it admin-only: an admin-written field no
   * validator names is unwritable by every client (the update dispatch falls
   * through to `false`) while the match's own whole-document `allow get` still
   * lets every seat read the marker on the match. `firestore.rules` is
   * therefore byte-identical for S73 — the same zero-rules path
   * `timeoutRemovedUids` proved.
   */
  private fun inactivityRef(matchId: String, uid: String): DocRef =
    matchRef(matchId).collection(InactiveTakeover.INACTIVITY_COLLECTION).doc(uid)

  /** `matches/{matchId}/disconnectVotes/{uid}` — S7's ballot cancellation target. */
  private fun ballotRef(matchId: String, uid: String): DocRef =
    matchRef(matchId).collection(DisconnectVoteState.BALLOT_COLLECTION).doc(uid)

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
