package com.estemshan.services

import com.estemshan.services.model.Reasons
import com.estemshan.services.model.ServiceException
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.tasks.await

/**
 * Transaction plumbing shared by RoomService and MatchService.
 *
 * Why outcomes instead of exceptions: the emulator and the SDK have ZERO
 * transaction retry (docs/specs/03-transactions.md), and how
 * Task.await() re-throws a callback exception is version-dependent. So a
 * transaction body NEVER throws past the callback — it returns [Err] and
 * the reason code survives intact to the caller. Firestore's own failures
 * (contention, a rules denial) still surface as task failures and are
 * translated here, which is exactly where the "wrong transaction shape is
 * DENIED by the rules" negative tests need a typed reason.
 */
sealed class TxOutcome<out T> {
  data class Ok<T>(val value: T) : TxOutcome<T>()
  // Nothing is a subtype of every T, so a valueless Err is a valid
  // TxOutcome<T> for whichever T a given call site needs.
  data class Err(val error: ServiceException) : TxOutcome<Nothing>()
}

fun <T> TxOutcome<T>.unwrap(): T = when (this) {
  is TxOutcome.Ok -> value
  is TxOutcome.Err -> throw error
}

/** Runs [body] in a Firestore transaction and unwraps its outcome,
 *  translating any SDK-level failure into a typed ServiceException. */
suspend fun <T> runTx(
  db: FirebaseFirestore,
  body: (Transaction) -> TxOutcome<T>,
): T {
  val outcome = try {
    db.runTransaction { tx -> body(tx) }.await()
  } catch (t: Throwable) {
    throw t.toServiceException()
  }
  return outcome.unwrap()
}

/** Maps an SDK failure onto a reason code. A rules denial carries
 *  PERMISSION_DENIED; anything unrecognized becomes UNAVAILABLE rather
 *  than leaking a raw exception type callers cannot branch on. */
internal fun Throwable.toServiceException(): ServiceException = when {
  this is ServiceException -> this
  this is com.google.firebase.firestore.FirebaseFirestoreException -> when (code) {
    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
    com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED ->
      ServiceException(Reasons.PERMISSION_DENIED, message ?: "Permission denied.")
    com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND ->
      ServiceException(Reasons.MATCH_NOT_FOUND, message ?: "Not found.")
    com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE ->
      ServiceException(Reasons.UNAVAILABLE, message ?: "Unavailable.")
    else -> ServiceException(Reasons.UNAVAILABLE, message ?: "Transaction failed: $code")
  }
  else -> ServiceException(Reasons.UNAVAILABLE, message ?: "Transaction failed.")
}

/** Blocking read inside a transaction callback (always off the main
 *  thread). The SDK re-invokes the callback with a fresh read on
 *  contention, so every guard is re-evaluated against current state.
 *  Transaction.get is itself blocking in this SDK version — it returns
 *  the snapshot, not a Task. */
internal fun Transaction.getBlocking(ref: DocumentReference): DocumentSnapshot = get(ref)

/** Non-transactional blocking read for the pre-check passes that must see
 *  committed state before deciding whether to open a transaction at all. */
internal fun DocumentReference.getBlocking(): DocumentSnapshot = Tasks.await(get())
