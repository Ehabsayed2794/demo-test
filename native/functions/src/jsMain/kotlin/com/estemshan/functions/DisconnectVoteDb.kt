package com.estemshan.functions

import com.estemshan.engine.DisconnectVoteChoice
import com.estemshan.engine.DisconnectVoteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * S74 runtime — the disconnect flow's I/O + orchestration.
 *
 * The contract mirrors the decision timer's: the pure decision
 * ([DisconnectVoteFlow.step] and friends) is unit-testable with no Firestore,
 * and this file owns the reads, the transaction and the response maps. Every
 * write rides ONE transaction: the public marker, the flow's provenance list,
 * the private ballots and `version`/`updatedAt` commit atomically or not at
 * all, so no client can ever observe a seat whose phase and whose ballots
 * disagree. Everything is re-derived from the in-transaction read, so a racer
 * at any point re-reads and finds the step already taken — the same
 * idempotency-by-re-derivation the decision resolver documents, which is what
 * makes the client's bounded poll safe to retry.
 *
 * **THE ZERO-RULES PATH, again.** `matches/{id}.disconnectVote` and
 * `.disconnectReplacedUids` are written here and named by no client-facing
 * validator in `firestore.rules`: the match update dispatch falls through to
 * `false` for them, while the match's whole-document `allow get` still lets
 * every seat read the marker. `matches/{id}/disconnectVotes/{uid}` has NO
 * rules block at all, which makes every ballot admin-only by construction —
 * the same shape `timeoutCounts`, `inactivity` and `inactiveTakeovers`
 * established. `firestore.rules` is byte-identical for S74.
 *
 * **The clock.** [DecisionClock] is the decision timer's, unchanged: committed
 * server timestamps are read and the server's own `Date.now()` is the present,
 * never a client's. Injectable so the jsTests can drive the windows without
 * waiting minutes.
 *
 * **`updatedAt` and the pause.** Transitions re-stamp `updatedAt` because it
 * is the decision machine's only deadline anchor (S37/S61): the replacement
 * that ends a granted `WAIT` therefore re-anchors the pending decision's
 * window on the same write — \"the game resumes\" with a complete window, the
 * exact thing the S73 return documents for its own reason. The resolver's
 * pause gate and [TimeoutResolution.disconnectPauseRemaining] are the other
 * half: while a pause is running no decision resolves at all.
 */

/** The reads/writes one disconnect step needs. Injectable for the jsTests. */
interface DisconnectVoteDb {

  suspend fun readMatch(matchId: String): Map<String, Any?>?

  suspend fun <T> transact(block: suspend (Tx) -> T): T

  interface Tx {
    suspend fun readMatch(matchId: String): Map<String, Any?>?

    /**
     * `players/{uid}.lastSeenAt` in server-clock millis, or null when the
     * profile (or the field) does not exist. Admin-only: R7 keeps this field
     * unreadable to every client, which is exactly why detection is computed
     * here. Missing means no signal — [DisconnectVoteState.detectionDue]
     * never detects it.
     */
    suspend fun readLastSeenMillis(uid: String): Long?

    /** One ballot's committed fields, normalized, or null when absent. */
    suspend fun readBallot(matchId: String, uid: String): Map<String, Any?>?

    fun updateMatch(matchId: String, updates: Map<String, Any?>)

    /**
     * `matches/{matchId}/disconnectVotes/{uid}` — the ballot, keyed by VOTER
     * like `timeoutCounts`/`inactivity` and for the same reason: how a named
     * player answered is not their opponents' business. A replacement write,
     * never a merge — the three fields are the ballot's complete state.
     */
    fun writeBallot(matchId: String, uid: String, fields: Map<String, Any?>)

    /**
     * Removes a ballot the moment its vote ends (close, settle or a
     * reconnect): the next flow's answers start from empty, and no stale
     * answer can ever ride into another seat's count.
     */
    fun deleteBallot(matchId: String, uid: String)
  }
}

// ── the resolver: the client's bounded poll ──────────────────────────────

/**
 * Runs one resolver pass: auth → read → membership → transacted re-derive →
 * write. Any seated client may call it (host-less by design, like the decision
 * resolver: the seat that dropped cannot poll for itself, and the poll is what
 * drives EVERY transition — the emulator manifest exports no scheduled
 * triggers).
 *
 * The answer vocabulary: `changed` says whether anything was written;
 * `action` names the transition ([DisconnectVoteFlow.Action]); `seatId` is the
 * DOCUMENT's seat, never an echo of the request; and `retryAfterMillis` is the
 * server's own next-due instant — a live window's close, the pause's end, or
 * the earliest heartbeat that will cross the staleness threshold. `lastSeenAt`
 * is not readable by clients, so the server owns the poll cadence; a client
 * re-arms on this number instead of guessing.
 *
 * NOT_APPLICABLE (RANKED, or a mode the document does not name) is terminal
 * and carries no retry: the question is never asked there, and a client that
 * keeps polling a Ranked match is calling a callable that will always say no.
 */
suspend fun resolveDisconnectOnce(
  db: DisconnectVoteDb,
  request: CallableRequest,
  clock: DecisionClock,
): Map<String, Any?> {
  // The runtime delivers request.data as a PLAIN JS OBJECT, not a Kotlin map —
  // `as? Map` returns null on one (JsBridging's own documented trap) — so
  // convert at the boundary the way every Admin read does.
  val data = dynamicToKotlin(request.data) as? Map<*, *>
  val matchId = data?.get("matchId") as? String
  if (matchId.isNullOrEmpty()) {
    throw HttpsError("invalid-argument", "resolveDisconnectVote: matchId is required.")
  }
  val uid = CallableAuth.requireUid(request)

  val fields = db.readMatch(matchId)
    ?: throw HttpsError("not-found", "resolveDisconnectVote: match '$matchId' was not found.")
  val players = TimeoutResolution.parsePlayers(fields)
    ?: throw HttpsError(
      "failed-precondition",
      "resolveDisconnectVote: match '$matchId' is not a match document.",
    )
  if (uid !in players) {
    throw HttpsError(
      "permission-denied",
      "resolveDisconnectVote: you are not a player in match '$matchId'.",
    )
  }

  return db.transact { tx ->
    val fresh = tx.readMatch(matchId)
      ?: throw HttpsError("not-found", "resolveDisconnectVote: match '$matchId' was not found.")
    // The mode gate BEFORE any heartbeat read: in RANKED the question is never
    // asked, so a poll there costs one document read and touches nothing else.
    // The same answer the pure step gives, placed here so the refusal costs no
    // per-seat reads at all.
    if (!DisconnectVoteFlow.modeEligible(fresh)) {
      return@transact disconnectNoWrite(
        DisconnectVoteFlow.Reason.NOT_APPLICABLE,
        "resolveDisconnectVote: this mode never asks the question.",
        null,
      )
    }
    val now = clock.nowMillis()

    // Every read before every write, as Firestore requires: the candidate
    // heartbeats first, then — only when a close is actually due — the
    // electorate's ballots.
    val hearts = LinkedHashMap<String, Long?>()
    for (candidate in DisconnectVoteFlow.candidates(fresh)) {
      hearts[candidate.uid] = tx.readLastSeenMillis(candidate.uid)
    }
    val due = DisconnectVoteFlow.dueBallot(fresh, now)
    val choices = LinkedHashMap<String, DisconnectVoteChoice>()
    if (due != null) {
      for (voter in DisconnectVoteFlow.electorateUids(fresh, due.seatId)) {
        val ballot = tx.readBallot(matchId, voter) ?: continue
        DisconnectVoteFlow.ballotChoice(ballot, due.seatId)?.let { choices[voter] = it }
      }
    }

    when (val step = DisconnectVoteFlow.step(fresh, now, hearts, choices)) {
      is DisconnectVoteFlow.Step.NoWrite ->
        disconnectNoWrite(step.reason, step.message, step.retryAfterMillis)

      is DisconnectVoteFlow.Step.Applied -> {
        val updates = LinkedHashMap<String, Any?>()
        updates[DisconnectVoteState.VOTE_FIELD] = step.marker
        if (step.replacedUids.isNotEmpty()) {
          // The flow's OWN provenance list, keyed by uid: appended like the
          // 15-timeout removal, never Vote Kick's `removedUids`. The charge
          // table (`DisconnectVote.permanentCharge`) is applied downstream;
          // the runtime records the fact, not the penalty.
          updates[DisconnectVoteState.REPLACED_UIDS_FIELD] =
            (replacedUids(fresh) + step.replacedUids).distinct()
        }
        updates["version"] = ((fresh["version"] as? Number)?.toInt() ?: 0) + 1
        updates["updatedAt"] = FieldValue.serverTimestamp()
        tx.updateMatch(matchId, updates)
        for (voter in step.ballotDeletes) {
          tx.deleteBallot(matchId, voter)
        }
        disconnectApplied(matchId, step)
      }
    }
  }
}

// ── casting one ballot ───────────────────────────────────────────────────

/**
 * Records one eligible human's answer. The seat a ballot is about comes from
 * the DOCUMENT's open vote; the caller's eligibility comes from the
 * document's own seats map minus the flow's exclusions — a client names
 * neither. First answer stands ([DisconnectVoteFlow.cast]): a retried call is
 * `already` and rewrites nothing, so \"2 of 3\" can never double-count.
 * Nothing here settles the ballot — the majority is the resolver's close.
 */
suspend fun castDisconnectOnce(
  db: DisconnectVoteDb,
  request: CallableRequest,
  clock: DecisionClock,
): Map<String, Any?> {
  // The runtime delivers request.data as a PLAIN JS OBJECT, not a Kotlin map —
  // `as? Map` returns null on one (JsBridging's own documented trap) — so
  // convert at the boundary the way every Admin read does.
  val data = dynamicToKotlin(request.data) as? Map<*, *>
  val matchId = data?.get("matchId") as? String
  if (matchId.isNullOrEmpty()) {
    throw HttpsError("invalid-argument", "castDisconnectVote: matchId is required.")
  }
  val choiceName = data?.get("choice") as? String
  val choice = DisconnectVoteChoice.entries.firstOrNull { it.name == choiceName }
    ?: throw HttpsError(
      "invalid-argument",
      "castDisconnectVote: choice must be ${DisconnectVoteChoice.entries.joinToString(" or ") { it.name }}.",
    )
  val uid = CallableAuth.requireUid(request)

  val fields = db.readMatch(matchId)
    ?: throw HttpsError("not-found", "castDisconnectVote: match '$matchId' was not found.")
  val players = TimeoutResolution.parsePlayers(fields)
    ?: throw HttpsError(
      "failed-precondition",
      "castDisconnectVote: match '$matchId' is not a match document.",
    )
  if (uid !in players) {
    throw HttpsError(
      "permission-denied",
      "castDisconnectVote: you are not a player in match '$matchId'.",
    )
  }

  return db.transact { tx ->
    val fresh = tx.readMatch(matchId)
      ?: throw HttpsError("not-found", "castDisconnectVote: match '$matchId' was not found.")
    val now = clock.nowMillis()

    // The two reads before the writes: the caller's own ballot (the
    // idempotency check) and nothing else — eligibility is derived from the
    // document, and the answer is written blind otherwise.
    val liveSeat = DisconnectVoteState.liveBallot(fresh[DisconnectVoteState.VOTE_FIELD])?.seatId
    val existing = if (liveSeat == null) null else tx.readBallot(matchId, uid)
    val hasExistingBallot =
      liveSeat != null && DisconnectVoteFlow.ballotChoice(existing, liveSeat) != null

    when (val cast = DisconnectVoteFlow.cast(fresh, uid, choice, now, hasExistingBallot)) {
      is DisconnectVoteFlow.Cast.NoWrite -> castNoWrite(cast.reason, cast.message)

      is DisconnectVoteFlow.Cast.AlreadyRecorded ->
        castRecorded(matchId, cast.seatId, already = true)

      is DisconnectVoteFlow.Cast.Recorded -> {
        tx.writeBallot(
          matchId,
          uid,
          DisconnectVoteState.ballotFields(cast.seatId, cast.choice, cast.castAtMillis),
        )
        val updates = LinkedHashMap<String, Any?>()
        updates[DisconnectVoteState.VOTE_FIELD] = cast.marker
        updates["version"] = ((fresh["version"] as? Number)?.toInt() ?: 0) + 1
        updates["updatedAt"] = FieldValue.serverTimestamp()
        tx.updateMatch(matchId, updates)
        castRecorded(matchId, cast.seatId, already = false)
      }
    }
  }
}

// ── taking the seat back ─────────────────────────────────────────────────

/**
 * The owner's reconnect. The seat is derived from the caller's verified uid
 * through the document's own seats map — a restore CANNOT name a seat, so it
 * can return nothing but its own. Reclaimable exactly while the table waits
 * ([DisconnectVoteFlow.restore]): through the whole ballot and any granted
 * pause. No charge and no counter is touched — a borrowal is not a departure
 * (A5.3), and the return writes only the marker (seat entry removed), the
 * ended ballots, `version` and `updatedAt`. A seat whose waiting already
 * ended is answered `REPLACED` — terminal, no write, and no pretending
 * otherwise.
 */
suspend fun restoreDisconnectOnce(
  db: DisconnectVoteDb,
  request: CallableRequest,
): Map<String, Any?> {
  // The runtime delivers request.data as a PLAIN JS OBJECT, not a Kotlin map —
  // `as? Map` returns null on one (JsBridging's own documented trap) — so
  // convert at the boundary the way every Admin read does.
  val data = dynamicToKotlin(request.data) as? Map<*, *>
  val matchId = data?.get("matchId") as? String
  if (matchId.isNullOrEmpty()) {
    throw HttpsError("invalid-argument", "restoreDisconnectedSeat: matchId is required.")
  }
  val uid = CallableAuth.requireUid(request)

  val fields = db.readMatch(matchId)
    ?: throw HttpsError("not-found", "restoreDisconnectedSeat: match '$matchId' was not found.")
  val players = TimeoutResolution.parsePlayers(fields)
    ?: throw HttpsError(
      "failed-precondition",
      "restoreDisconnectedSeat: match '$matchId' is not a match document.",
    )
  if (uid !in players) {
    throw HttpsError(
      "permission-denied",
      "restoreDisconnectedSeat: you are not a player in match '$matchId'.",
    )
  }

  return db.transact { tx ->
    val fresh = tx.readMatch(matchId)
      ?: throw HttpsError("not-found", "restoreDisconnectedSeat: match '$matchId' was not found.")

    when (val restore = DisconnectVoteFlow.restore(fresh, uid)) {
      is DisconnectVoteFlow.Restore.NothingHeld ->
        restoreNoWrite(DisconnectVoteFlow.Reason.NOT_HELD, "restoreDisconnectedSeat: no seat of yours is held.")

      is DisconnectVoteFlow.Restore.Replaced ->
        restoreNoWrite(
          DisconnectVoteFlow.Reason.REPLACED,
          "restoreDisconnectedSeat: the waiting ended — the seat is the bot's for the rest of the match.",
        )

      is DisconnectVoteFlow.Restore.Restored -> {
        val updates = LinkedHashMap<String, Any?>()
        updates[DisconnectVoteState.VOTE_FIELD] = restore.marker
        updates["version"] = ((fresh["version"] as? Number)?.toInt() ?: 0) + 1
        updates["updatedAt"] = FieldValue.serverTimestamp()
        tx.updateMatch(matchId, updates)
        for (voter in restore.ballotDeletes) {
          tx.deleteBallot(matchId, voter)
        }
        restoreDone(matchId, restore.seatId)
      }
    }
  }
}

/** The flow's own replaced-uid list, read from the document's own field. */
private fun replacedUids(fields: Map<String, Any?>): List<String> =
  ((fields[DisconnectVoteState.REPLACED_UIDS_FIELD] as? List<*>)
    ?.mapNotNull { it as? String }).orEmpty()

/**
 * A no-write answer. `retryAfterMillis` is present only when the server has a
 * next-due instant to hand out — never on NOT_APPLICABLE, whose poll is over.
 */
private fun disconnectNoWrite(
  reason: String,
  message: String,
  retryAfterMillis: Long?,
): Map<String, Any?> = buildMap {
  put("ok", true)
  put("changed", false)
  put("reason", reason)
  put("message", message)
  if (retryAfterMillis != null) put("retryAfterMillis", retryAfterMillis)
}

/** A transition was committed. The seat is the DOCUMENT's, never an echo. */
private fun disconnectApplied(
  matchId: String,
  step: DisconnectVoteFlow.Step.Applied,
): Map<String, Any?> = buildMap {
  put("ok", true)
  put("changed", true)
  put("matchId", matchId)
  put("action", step.action)
  put("seatId", step.seatId)
  put("retryAfterMillis", step.retryAfterMillis)
}

/** A no-write answer to a cast: `recorded` false, with the reason it was not. */
private fun castNoWrite(reason: String, message: String): Map<String, Any?> = mapOf(
  "ok" to true,
  "recorded" to false,
  "reason" to reason,
  "message" to message,
)

/** The answer is on record (this call or a prior one). */
private fun castRecorded(matchId: String, seatId: String, already: Boolean): Map<String, Any?> =
  buildMap {
    put("ok", true)
    put("recorded", true)
    put("matchId", matchId)
    put("seatId", seatId)
    if (already) put("already", true)
  }

/** A no-write answer to a restore. */
private fun restoreNoWrite(reason: String, message: String): Map<String, Any?> = mapOf(
  "ok" to true,
  "restored" to false,
  "reason" to reason,
  "message" to message,
)

/** The seat is back. */
private fun restoreDone(matchId: String, seatId: String): Map<String, Any?> = mapOf(
  "ok" to true,
  "restored" to true,
  "matchId" to matchId,
  "seatId" to seatId,
)

/** Admin-SDK implementation. Reads normalize through dynamicToKotlin;
 *  writes normalize through toPlain. */
class AdminDisconnectVoteDb : DisconnectVoteDb {
  // Before getFirestore() resolves the default app — see ensureDefaultAdminApp.
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  private fun matchRef(matchId: String): DocRef = db.collection("matches").doc(matchId)

  /** `players/{uid}` — the heartbeat's document, top-level and admin-read. */
  private fun playerRef(uid: String): DocRef = db.collection("players").doc(uid)

  /**
   * `matches/{matchId}/disconnectVotes/{uid}` — the private ballot. It has NO
   * rules block at all, which is what makes it admin-only: an admin-written
   * document no validator names is unwritable by every client, while the
   * match's own whole-document `allow get` still lets every seat read the
   * public marker. `firestore.rules` is therefore byte-identical for S74.
   */
  private fun ballotRef(matchId: String, uid: String): DocRef =
    matchRef(matchId).collection(DisconnectVoteState.BALLOT_COLLECTION).doc(uid)

  override suspend fun readMatch(matchId: String): Map<String, Any?>? {
    val snap = matchRef(matchId).get().await()
    if (!snap.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snap.data()) as? Map<String, Any?>
  }

  override suspend fun <T> transact(block: suspend (DisconnectVoteDb.Tx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  private inner class AdminTx(private val tx: Transaction) : DisconnectVoteDb.Tx {
    override suspend fun readMatch(matchId: String): Map<String, Any?>? {
      val snap = tx.get(matchRef(matchId)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override suspend fun readLastSeenMillis(uid: String): Long? {
      val snap = tx.get(playerRef(uid)).await()
      if (!snap.exists) return null
      val fields = dynamicToKotlin(snap.data()) as? Map<*, *> ?: return null
      return timestampToMillis(fields["lastSeenAt"])
    }

    override suspend fun readBallot(matchId: String, uid: String): Map<String, Any?>? {
      val snap = tx.get(ballotRef(matchId, uid)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override fun updateMatch(matchId: String, updates: Map<String, Any?>) {
      tx.update(matchRef(matchId), toPlain(updates))
    }

    override fun writeBallot(matchId: String, uid: String, fields: Map<String, Any?>) {
      tx.set(ballotRef(matchId, uid), toPlain(fields))
    }

    override fun deleteBallot(matchId: String, uid: String) {
      tx.delete(ballotRef(matchId, uid))
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
