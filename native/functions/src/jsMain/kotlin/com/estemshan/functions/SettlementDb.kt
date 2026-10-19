package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.RpMatchInput
import com.estemshan.functions.Settlement.ConvergedMatch
import com.estemshan.functions.Settlement.SettleDecision
import com.estemshan.engine.finalRpDelta
import com.estemshan.engine.placementOutcome
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder
import com.estemshan.engine.resolveRanked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * S50 settlement I/O + orchestration (RD12/RD13).
 *
 * The once-only contract: claim-check, recompute, correction write and
 * settlement record commit inside ONE Admin transaction on the claim doc
 * (`matches/{matchId}/settlement/settle`). Two racers read absent, one
 * commits, the other retries into the recorded outcome — correction
 * writes and the claim can never diverge, so a crash cannot leave a
 * claimed-but-uncorrected match. This single-transaction shape is why the
 * settlement does not compose S42's two-phase IdempotencyGuard (claim
 * then commit would leave exactly that window); the guard's contract —
 * first invocation executes, replays return the recorded outcome — is
 * what this guarantees instead.
 *
 * Ranked progression (RD1/RD3/RD12) is applied HERE, in this same
 * transaction, rather than by a separate guard-composed call as S51's note
 * first anticipated: the claim's once-only property is what makes an RP
 * award once-only, and applying it in a second transaction would reopen
 * exactly the claimed-but-unapplied window this shape exists to close.
 * Every seated profile is read before any write (Firestore forbids a read
 * after a write in one transaction), so a redelivery can neither re-award
 * nor settle against a stale pre-image.
 *
 * Ports keep every Firestore touch behind interfaces so the whole
 * orchestration (gate → recompute → write/replay) is unit-testable with
 * fakes; only AdminSettlementDb loads the SDK.
 */
interface SettlementDb {
  suspend fun readMatch(matchId: String): Map<String, Any?>?
  suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>>
  suspend fun <T> transact(block: suspend (SettlementTx) -> T): T

  /**
   * S62 RP audit ledger: best-effort append of one audit record. Abstract
   * so every port wires the ledger consciously; the Admin implementation
   * writes create-if-absent, once per [SettlementLedger.appendTargets] entry,
   * keyed by [SettlementLedger.ledgerDocId], and settleOnce calls this inside
   * a catch - an append failure is logged and never changes the settlement
   * outcome.
   */
  suspend fun appendLedger(record: Map<String, Any?>)

  /**
   * S62: the bounded, most-recent-first window of CORRECTIONS the repeat
   * detector reads ([SettlementLedger.DETECT_WINDOW] documents, drawn from
   * [SettlementLedger.COLLECTION_CORRECTIONS]). Called only after a correction
   * applied; the caller catches failures. Reading corrections alone, instead
   * of the whole ledger, is what stops R25 going blind as settlement volume
   * grows - and it is a write-time materialization precisely so this read
   * needs no composite index. See [AdminSettlementDb.readCorrectionWindow].
   */
  suspend fun readCorrectionWindow(): List<Map<String, Any?>>

  interface SettlementTx {
    suspend fun readClaim(matchId: String): Map<String, Any?>?

    /**
     * The seated player's own `players/{uid}` document, or null when it does
     * not exist. Read INSIDE the settlement transaction: the RP fold needs the
     * previous RP as of the commit, and reading it outside would let two
     * racers settle against the same pre-image.
     */
    suspend fun readProfile(uid: String): Map<String, Any?>?

    fun writeCorrection(matchId: String, scores: Map<String, Int>, winners: List<String>, version: Int)
    fun writeClaim(matchId: String, record: Map<String, Any?>)

    /**
     * Merge-write ONLY the ranked-progression fields of `players/{uid}`
     * (`rp`, `tier`, `division`). Anything else the document holds — the
     * nine statistics, highestRank, season fields — is untouched by
     * construction: the Admin SDK bypasses rules, so the field set is exactly
     * what the caller passes here.
     */
    fun writeProfile(uid: String, fields: Map<String, Any?>)
  }
}

private const val CLAIM_PATH = "settlement"
private const val CLAIM_DOC = "settle"

/** Callable response when the mode gate declines (ROOM/UNRANKED never settle). */
fun noOpResponse(mode: String): Map<String, Any?> = mapOf(
  "ok" to true,
  "settled" to false,
  "reason" to "NOT_RANKED",
  "mode" to mode,
)

private fun executedResponse(
  recomputed: Settlement.RecomputedMatch,
  matchId: String,
): Map<String, Any?> = mapOf(
  "ok" to true,
  "settled" to true,
  "replayed" to false,
  "matchId" to matchId,
  "corrected" to recomputed.corrected,
  "finalScores" to recomputed.finalScores,
  "winnerIds" to recomputed.winnerIds,
  "claimedScores" to recomputed.claimedScores,
  "claimedWinners" to recomputed.claimedWinners,
  "rounds" to recomputed.rounds,
)

private fun claimRecord(
  uid: String,
  response: Map<String, Any?>,
  movements: Map<String, Map<String, Any?>>,
): Map<String, Any?> = mapOf(
  "settledBy" to uid,
  "settledAt" to FieldValue.serverTimestamp(),
  // Caller-neutral by construction: `you` is the CALLER's slice of
  // [movements], added on the way out and never stored here (RD13 — one
  // claim document is replayed by four different clients, so a stored `you`
  // would be a stored leak).
  "outcome" to response,
  // {uid: {previousRp, rp, delta}} for every seated player. Admin-only: this
  // document lives under matches/{id}/settlement, which no client may read,
  // and it mirrors players/{uid}, whose documents are owner-`get`-only.
  "movements" to movements,
)

/** One seat's progression inputs, all read before any write is issued. */
private data class SeatInput(val uid: String, val seat: String, val previousRp: Int)

/**
 * RD30: an account that carries no `rp` — the key absent, or the JSON null a
 * client can write — is unplaced, and Bronze III starts at 0 RP, so it reads as
 * 0. A missing DOCUMENT is not a 0 at all — see the fail-closed rule in
 * [settleOnce].
 *
 * Something in the field that is not a number is corrupt, not unplaced: `"700"`
 * read as 0 would award a Gold player the +30 of a first match, which is the
 * same fabrication the missing-document rule refuses, one level down. Null here
 * means "malformed" and the caller fails the settlement closed.
 */
private fun previousRpOf(profile: Map<String, Any?>): Int? = when (val rp = profile["rp"]) {
  null -> 0
  is Number -> rp.toInt()
  else -> null
}

/** The ladder ordinal [previousRp] sits on now (RD4 input 1). */
private fun ladderOrdinal(previousRp: Int): Int = rankLadder.indexOf(rankAt(previousRp))

/**
 * The caller's own movement, sliced out of a claim's `movements` (RD13: a
 * client reads its own progression and nobody else's). Null means "no `you`
 * to report" — a claim written before progression existed, or a caller with
 * no seat in the claim — and is returned as an absent key, never as a
 * fabricated or another seat's number.
 */
private fun youOf(movements: Any?, uid: String): Map<String, Any?>? {
  val mine = (movements as? Map<*, *>)?.get(uid) as? Map<*, *> ?: return null
  val previous = mine["previousRp"] as? Number ?: return null
  val rp = mine["rp"] as? Number ?: return null
  val delta = mine["delta"] as? Number ?: return null
  return linkedMapOf(
    "previousRp" to previous.toInt(),
    "rp" to rp.toInt(),
    "delta" to delta.toInt(),
  )
}

/**
 * Reads every seated profile in the settlement transaction — the RP fold's
 * reads, all of them, before any write is issued (Firestore forbids a read
 * after a write in one transaction).
 *
 * Seat → uid comes from the document's own `seats` map, never from
 * [ConvergedMatch.players] index order: `finalScores` is SEAT-keyed (p1..p4)
 * while progression is written per uid, so a uid holds a seat's placement only
 * through that map (the same authority `parseMatch` uses for the dealer
 * marker). Index-correlating the two lists would hand one seat's award to
 * another player.
 *
 * FAILS CLOSED as a whole settlement when a seated uid has no `players/{uid}`
 * document, or when that document's `rp` is not a number: the settlement cannot
 * tell whether that account is Bronze III or King, and a missing RP would have
 * to be invented. Fabricating one profile while the other three settle is the
 * same bug one level down — a Ranked table either applies four awards in this
 * transaction or applies none. Refusing here leaves the claim absent, so the
 * match stays settleable once the profile is repaired; silently skipping the
 * seat would strand that player's progression at nothing, permanently, because
 * the claim then forbids any second execution.
 */
private suspend fun readSeatInputs(
  tx: SettlementDb.SettlementTx,
  fields: Map<String, Any?>,
  order: List<String>,
  matchId: String,
): List<SeatInput> {
  val seats = Settlement.parseSeats(fields)
  return order.map { seat ->
    val uid = seats?.get(seat) ?: throw HttpsError(
      "failed-precondition",
      "settleMatch: seat '$seat' of match '$matchId' is not seated to a uid.",
    )
    val profile = tx.readProfile(uid) ?: throw HttpsError(
      "failed-precondition",
      "settleMatch: seated player '$uid' of match '$matchId' has no players/ profile — " +
        "nothing wrote, the match stays settleable once that profile exists.",
    )
    val previousRp = previousRpOf(profile) ?: throw HttpsError(
      "failed-precondition",
      "settleMatch: seated player '$uid' of match '$matchId' has a non-numeric players/rp " +
        "(${profile["rp"]}) — nothing wrote; it is not read as 0 RP, which would award " +
        "the seat a first match's progression.",
    )
    SeatInput(uid, seat, previousRp)
  }
}

/**
 * What one [foldRankedProgression] pass produces, named so the two maps can
 * never be bound to the wrong side at the call site: [movements] is what the
 * claim records and what `you` is sliced from, [profileWrites] is what lands on
 * `players/{uid}`.
 */
private data class RankedProgression(
  val movements: Map<String, Map<String, Any?>>,
  val profileWrites: Map<String, Map<String, Any?>>,
)

/**
 * One pass of the ranked progression fold: the engine's own chain, per seat —
 * placement (RD8/RD17) → dynamic delta (RD4/RD5/RD6, GM6's Mini ×0.5) →
 * floor-clamped resolution (RD3) → the ladder rung it lands on (RD26).
 *
 * `rankDown` is false and stays that way by choice, not by absence: S43 keeps
 * the flag on `rooms/{roomId}` (`MatchModels`: "Matches never carry rankDown:
 * that flag lives on rooms"), and settlement reads only the match document —
 * one callable, one document, no room read. Reading it would mean a second
 * document inside this transaction. At the current rates the flag only caps a
 * loss at ×2, and RD29 bounds a Ranked table to one tier apart, so it changes
 * nothing today either way (RpEngine's own cap note says the same).
 *
 * Opponent strength is the mean of the OTHER seats' ordinals; a table of one
 * seat has no opponents and reads as strength 0.
 */
private fun foldRankedProgression(
  seated: List<SeatInput>,
  finalScores: Map<String, Int>,
  gameType: GameType,
): RankedProgression {
  val ordinals = seated.map { ladderOrdinal(it.previousRp) }
  val movements = LinkedHashMap<String, Map<String, Any?>>()
  val writes = LinkedHashMap<String, Map<String, Any?>>()
  seated.forEachIndexed { index, seat ->
    val others = ordinals.filterIndexed { i, _ -> i != index }
    val opponentStrength = if (others.isEmpty()) 0 else others.sum() / others.size
    val resolution = resolveRanked(
      seat.previousRp,
      finalRpDelta(
        RpMatchInput(
          outcome = placementOutcome(finalScores, seat.seat),
          playerOrdinal = ordinals[index],
          opponentStrength = opponentStrength,
          gameType = gameType,
          rankDown = false,
        ),
      ),
    )
    movements[seat.uid] = linkedMapOf(
      "previousRp" to resolution.previousRP,
      "rp" to resolution.rp,
      "delta" to resolution.delta,
    )
    writes[seat.uid] = linkedMapOf(
      "rp" to resolution.rp,
      "tier" to resolution.next.tier,
      "division" to resolution.next.division,
    )
  }
  return RankedProgression(movements, writes)
}

/**
 * Runs one settlement: auth → read → gate → recompute → transacted
 * claim/write. Throws HttpsError on every denial (the client switches on
 * code). Returns Kotlin maps throughout so the orchestration stays
 * unit-testable — the callable handler applies toPlain() once, at the
 * serialization boundary (plain JS objects fail Kotlin Map reads).
 */
suspend fun settleOnce(db: SettlementDb, request: CallableRequest): Map<String, Any?> {
  // The runtime delivers request.data as a PLAIN JS OBJECT, not a Kotlin map —
  // `as? Map` returns null on one, silently rejecting every real call with
  // "matchId is required". Convert at the boundary, as below.
  val data = dynamicToKotlin(request.data) as? Map<*, *>
  val matchId = data?.get("matchId") as? String
  if (matchId.isNullOrEmpty()) {
    throw HttpsError("invalid-argument", "settleMatch: matchId is required.")
  }
  val uid = CallableAuth.requireUid(request)

  val fields = db.readMatch(matchId)
    ?: throw HttpsError("not-found", "settleMatch: match '$matchId' was not found.")
  val parsed = Settlement.parseMatch(matchId, fields)
    ?: throw HttpsError(
      "failed-precondition",
      "settleMatch: match '$matchId' is not a match document.",
    )
  when (val gate = Settlement.decide(uid, parsed)) {
    is SettleDecision.NoOp -> {
      // S62 decision: ROOM/UNRANKED no-ops are console-only - no ledger
      // document (not a settlement outcome; the ledger stays free of
      // routine traffic), cheap structured line for retry forensics.
      console.log(SettlementLedger.noOpLine(matchId, gate.mode.name))
      return noOpResponse(gate.mode.name)
    }
    is SettleDecision.Failed -> {
      // S62 decision: a denied attempt is console-only (no write - an
      // authenticated prober must not be able to amplify documents).
      console.log(SettlementLedger.deniedLine(matchId, uid, gate.reason))
      throw HttpsError(
        if (gate.reason == Settlement.Reason.NOT_MEMBER) "permission-denied" else "failed-precondition",
        gate.message,
      )
    }
    SettleDecision.Proceed -> Unit
    else -> throw HttpsError("internal", "settleMatch: unreachable gate.")
  }

  val parsedArchives = LinkedHashMap<String, Settlement.ArchivedRound>()
  for ((docId, archiveFields) in db.readArchives(matchId)) {
    val archive = Settlement.parseArchive(docId, archiveFields)
    if (archive == null) {
      val message = "settleMatch: roundArchive/$docId is malformed."
      // S62: a malformed archive is corrupt history - the same FAILED
      // ledger record and console line as a closed recompute.
      SettlementLedger.reportFailure(db, parsed, uid, Settlement.Reason.HISTORY_CORRUPT, message)
      throw HttpsError("failed-precondition", message)
    }
    parsedArchives[docId] = archive
  }
  val withHistory = parsed.copy(archives = parsedArchives.values.toList())

  val decision = Settlement.recompute(withHistory)
  val recomputed = when (decision) {
    is SettleDecision.Agreed -> decision.recomputed
    is SettleDecision.Corrected -> decision.recomputed
    is SettleDecision.Failed -> {
      // S62: recompute failures are ledger-recorded (FAILED) before the
      // denial is thrown; reporting is best-effort and never replaces it.
      SettlementLedger.reportFailure(db, withHistory, uid, decision.reason, decision.message)
      throw HttpsError("failed-precondition", decision.message)
    }
    else -> throw HttpsError("internal", "settleMatch: unreachable recompute.")
  }

  val settled = db.transact { tx ->
    val existing = tx.readClaim(matchId)
    if (existing != null) {
      @Suppress("UNCHECKED_CAST")
      val recorded = existing["outcome"] as? Map<String, Any?>
        ?: throw HttpsError(
          "failed-precondition",
          "settleMatch: settlement record for '$matchId' is malformed.",
        )
      val replayed = recorded.toMutableMap()
      replayed["replayed"] = true
      // A replay re-awards nothing (no profile read, no profile write) and
      // slices THIS caller's movement out of the claimed movements: one claim
      // is replayed by four different clients, and each sees only its own
      // numbers (RD13). A claim recorded before progression existed has no
      // `movements` — then the replay answers exactly as it always did, with
      // no `you` and no crash.
      youOf(existing["movements"], uid)?.let { replayed["you"] = it }
      return@transact replayed
    }

    // Reads before writes, all four seats, inside this transaction: two racers
    // that both read absent cannot both write, and the profile a racer reads
    // is the one its claim commits against.
    val seated = readSeatInputs(tx, fields, parsed.order, matchId)
    val progression = foldRankedProgression(
      seated,
      recomputed.finalScores,
      parsed.gameType,
    )

    if (recomputed.corrected) {
      val version = (fields["version"] as? Number)?.toInt() ?: 0
      tx.writeCorrection(matchId, recomputed.finalScores, recomputed.winnerIds, version + 1)
    }
    val response = executedResponse(recomputed, matchId)
    // The claim and every seat's progression write commit as ONE transaction —
    // which is what makes an RP award exactly-once rather than once-per-retry.
    progression.profileWrites.forEach { (seatUid, profileFields) ->
      tx.writeProfile(seatUid, profileFields)
    }
    tx.writeClaim(matchId, claimRecord(uid, response, progression.movements))
    val you = youOf(progression.movements, uid)
    if (you == null) response else response + ("you" to you)
  }

  // S62: only the invocation that applied the settlement appends to the
  // ledger - the replay path (replayed=true) is a read-only no-op for it,
  // which makes the ledger append exactly-once across retries.
  if (settled["settled"] == true && settled["replayed"] != true) {
    SettlementLedger.recordSettlement(db, parsed, recomputed, uid)
  }
  return settled
}

/** Admin-SDK implementation. Reads normalize through dynamicToKotlin;
 *  writes normalize through toPlain. */
class AdminSettlementDb : SettlementDb {
  // Before getFirestore() resolves the default app — see ensureDefaultAdminApp.
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  private fun matchRef(matchId: String): DocRef =
    db.collection("matches").doc(matchId)

  /** The Ranked progression document — the only one settlement writes to. */
  private fun playerRef(uid: String): DocRef = db.collection("players").doc(uid)

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

  override suspend fun appendLedger(record: Map<String, Any?>) {
    // S62: create-if-absent in its own Admin transaction, keyed by
    // SettlementLedger.ledgerDocId - the append is idempotent even if the
    // same event is ever delivered twice, and a failure here cannot touch
    // the settlement (that transaction has already committed).
    // appendTargets decides which collections the row lands in, and they all
    // commit together: the corrections window can never hold a row the ledger
    // lacks, or miss one it should mirror.
    val docId = SettlementLedger.ledgerDocId(record) ?: return
    val refs = SettlementLedger.appendTargets(record).map { collection ->
      db.collection(collection).doc(docId)
    }
    db.runTransaction { tx ->
      TxScope.promise {
        // Firestore requires EVERY read in a transaction to precede EVERY
        // write (the SDK throws on a get after a set), so the create-if-absent
        // test is done for all targets first and only then written.
        val existed = refs.map { tx.get(it).await().exists }
        val plain = toPlain(record)
        refs.forEachIndexed { index, ref ->
          if (!existed[index]) tx.set(ref, plain)
        }
      }
    }.await()
  }

  override suspend fun readCorrectionWindow(): List<Map<String, Any?>> {
    // S62: newest-first over ONE field of the corrections collection, so the
    // window is genuinely "the last DETECT_WINDOW corrections" and includes
    // the record the correction just wrote. An un-ordered equality query on
    // the ledger instead would return index order, not recency, and would
    // freeze on the oldest documents once the collection grows - silently
    // blinding the detector. Mirror the correction at write time and the read
    // stays on the automatic single-field index: no composite index, no
    // indexes artifact, no deployment.
    val snapshot = db.collection(SettlementLedger.COLLECTION_CORRECTIONS)
      .orderBy("appendedAt", "desc")
      .limit(SettlementLedger.DETECT_WINDOW)
      .get().await()
    return snapshot.docs.mapNotNull { doc ->
      @Suppress("UNCHECKED_CAST")
      dynamicToKotlin(doc.data()) as? Map<String, Any?>
    }
  }

  private inner class AdminTx(private val tx: Transaction) : SettlementDb.SettlementTx {
    override suspend fun readClaim(matchId: String): Map<String, Any?>? {
      val snap = tx.get(matchRef(matchId).collection(CLAIM_PATH).doc(CLAIM_DOC)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override suspend fun readProfile(uid: String): Map<String, Any?>? {
      val snap = tx.get(playerRef(uid)).await()
      // Absent, not empty: a missing profile document is a fail-closed
      // condition for the whole settlement, while an existing document with no
      // `rp` field is a different thing (an unplaced account, RD30).
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
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
          mapOf(
            "finalScores" to scores,
            "winnerIds" to winners,
            "version" to version,
          ),
        ),
      )
    }

    override fun writeProfile(uid: String, fields: Map<String, Any?>) {
      // update(), not set(): it merges these fields into the profile and
      // leaves everything else the account holds (name, avatar, room/match
      // pointers, and every field this path does not own) exactly as it is.
      tx.update(playerRef(uid), toPlain(fields))
    }

    override fun writeClaim(matchId: String, record: Map<String, Any?>) {
      tx.set(matchRef(matchId).collection(CLAIM_PATH).doc(CLAIM_DOC), toPlain(record))
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
