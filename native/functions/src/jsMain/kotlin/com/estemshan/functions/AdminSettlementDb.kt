package com.estemshan.functions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

private const val CLAIM_PATH = "settlement"
private const val CLAIM_DOC = "settle"

/** Admin-SDK implementation. Reads normalize through dynamicToKotlin;
 *  writes normalize through translate + toPlain. */
class AdminSettlementDb : SettlementDb {
  // Before getFirestore() resolves the default app — see ensureDefaultAdminApp.
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  private fun matchRef(matchId: String): DocRef =
    db.collection("matches").doc(matchId)

  override suspend fun readMatch(matchId: String): Map<String, Any?>? {
    val snap = matchRef(matchId).get().await()
    if (!snap.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snap.data()) as? Map<String, Any?>
  }

  override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> {
    val snapshot = matchRef(matchId).collection("roundArchive").get().await()
    val out = LinkedHashMap<String, Map<String, Any?>>()
    for (doc in snapshot.docs) {
      @Suppress("UNCHECKED_CAST")
      val fields = dynamicToKotlin(doc.data()) as? Map<String, Any?> ?: continue
      out[doc.id] = fields
    }
    return out
  }

  override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  private inner class AdminTx(private val tx: Transaction) : SettlementDb.SettlementTx {
    override suspend fun readClaim(matchId: String): Map<String, Any?>? {
      val snap = tx.get(matchRef(matchId).collection(CLAIM_PATH).doc(CLAIM_DOC)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      // A present-but-unparseable claim must fail closed — returning null
      // here would let a second settlement double-apply (same fix as the DO
      // adapter).
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
        ?: throw AuthorityError(
          "failed-precondition",
          "settleMatch: settlement record for '$matchId' is malformed.",
        )
    }

    override fun writeCorrection(
      matchId: String,
      scores: Map<String, Int>,
      winners: List<String>,
      version: Int,
    ) {
      tx.update(
        matchRef(matchId),
        toPlain(
          Sentinels.translate(
            mapOf(
              "finalScores" to scores,
              "winnerIds" to winners,
              "version" to version,
            ),
          ),
        ),
      )
    }

    override fun writeClaim(matchId: String, record: Map<String, Any?>) {
      // record["settledAt"] is the core's ServerTimestampSentinel — resolved
      // here, at the commit boundary.
      tx.set(
        matchRef(matchId).collection(CLAIM_PATH).doc(CLAIM_DOC),
        toPlain(Sentinels.translate(record)),
      )
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
