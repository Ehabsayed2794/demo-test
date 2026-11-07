@file:JsModule("firebase-admin/firestore")
@file:JsNonModule

package com.estemshan.functions

import kotlin.js.Promise

/**
 * Minimal Admin-SDK Firestore interop for settlement (S50). Same doctrine
 * as Firebase.kt: declare only what settlement uses, keep it typed so a
 * wrong call shape fails at compile time. The Admin SDK bypasses
 * firestore.rules — which is exactly why settlement (and only
 * settlement) may write the corrected result; the rules file stays
 * byte-identical.
 *
 * Firebase-side facts this encodes:
 * - `getFirestore()` with no args uses the runtime's default app
 *   (FIREBASE_CONFIG) — which firebase-admin never initializes itself, so the
 *   DBs call [ensureDefaultAdminApp] before they construct one;
 * - `runTransaction` serializes concurrent settles on the claim doc: two
 *   racers read absent, one commits, the other retries into the recorded
 *   outcome — exactly-once writes, replay-safe reads;
 * - `FieldValue.serverTimestamp()` stamps the settlement record with the
 *   server's commit time, never any clock.
 */

// ---- firebase-admin/firestore --------------------------------------------

external fun getFirestore(): FirestoreDb

external interface FirestoreDb {
  fun collection(path: String): CollectionRef
  fun runTransaction(updateFunction: (Transaction) -> Promise<Any?>): Promise<Any?>
  /**
   * S59: the season reset's profile migration commits one batch per chunk.
   * A batch is atomic but NOT transactional - it cannot read - which is
   * exactly why it is only used for the idempotent field-level progression
   * merge, never for the season claim.
   */
  fun batch(): WriteBatch
}

external interface CollectionRef {
  fun doc(id: String): DocRef
  /**
   * S56: the Admin SDK's own auto-id — `collection.doc()` with no argument
   * mints a fresh, unguessable document id. The Ranked resolver mints the
   * table's id this way, so a client that could predict it could create that
   * room first and take it over.
   */
  fun doc(): DocRef
  fun get(): Promise<QuerySnapshot>
  /**
   * S62: a bounded, recency-ordered window for the RP audit ledger's repeat
   * detector. The runtime object is a CollectionReference, which is a Query -
   * both methods exist on it. Only `get()` is called on the result, so typing
   * them as CollectionRef keeps this file's minimal-surface doctrine.
   *
   * `orderBy` on ONE field is deliberate: Firestore's automatic single-field
   * indexes serve it in both directions. Pairing an equality filter with an
   * orderBy on a different field would need a composite index, i.e. an
   * indexes artifact and a deployment - so the detector's window is a
   * corrections-only collection, materialized at write time by
   * [SettlementLedger.appendTargets], and this read filters nothing.
   */
  fun orderBy(fieldPath: String, directionStr: String): CollectionRef
  fun limit(count: Int): CollectionRef
  /**
   * S64: the seasonal leaderboard's one read - an equality filter on ONE
   * top-level field of `players`. Firestore indexes every single field
   * automatically, so this needs no composite index, no `firestore.indexes.json`
   * artifact and no new collection, which is what keeps `firestore.rules` and
   * its SHA pin untouched for the whole story. The runtime object is a Query,
   * not a CollectionReference; only `get()` is called on the result, so typing
   * it as CollectionRef keeps this file's minimal-surface doctrine - the same
   * narrowing [orderBy] and [limit] accept.
   *
   * The three-argument shape is the SDK's, not a style choice: `where` is the
   * only filter method firebase-admin 13.10.0 exposes (verified against the
   * installed package - `Query.prototype.where` is a function of arity 3,
   * `whereEqualTo` is undefined). A two-argument `whereEqualTo` declaration
   * compiles, because external declarations are never checked against the real
   * object, and then dies at runtime as `db.whereEqualTo is not a function` on
   * the first request. [SeasonLeaderboardEmulatorTest] pins that.
   */
  fun where(fieldPath: String, opStr: String, value: Any?): CollectionRef
}

external interface DocRef {
  fun collection(path: String): CollectionRef
  fun get(): Promise<DocSnapshot>
  /** S56: the document id the reference points at. */
  val id: String
}

external interface DocSnapshot {
  val exists: Boolean
  fun data(): Any?
}

external interface QuerySnapshot {
  val docs: Array<QueryDocSnapshot>
}

external interface QueryDocSnapshot {
  val id: String
  fun data(): Any?
  /**
   * S59: the commit version of this document at read time. The reset carries it
   * into a `lastUpdateTime` precondition on the profile batch, so a settlement
   * that commits between the read and the write makes the batch fail instead of
   * being overwritten by a stale reset.
   */
  val updateTime: Any?
}

external interface Transaction {
  fun get(ref: DocRef): Promise<DocSnapshot>
  fun set(ref: DocRef, data: Any?)
  fun update(ref: DocRef, data: Any?)
  /**
   * S74: the disconnect flow's ballots are removed the moment their vote ends
   * (close, settle, or a reconnect), so a transaction must be able to delete.
   */
  fun delete(ref: DocRef)
}

external interface WriteBatch {
  /**
   * Field-level update of one document - never `set`, so the season reset can
   * merge six progression fields without erasing career statistics. The Admin
   * SDK caps one batch at 500 writes; callers chunk well below that.
   */
  fun update(ref: DocRef, data: Any?)

  /**
   * S59: the same field-level update with an Admin-SDK precondition. The reset
   * passes `{ lastUpdateTime: <the profile's updateTime at read> }`; if a
   * settlement or any other writer commits in between, the whole batch fails
   * with FAILED_PRECONDITION and no profile in it is written.
   */
  fun update(ref: DocRef, data: Any?, precondition: Any?)
  fun commit(): Promise<Any?>
}

external object FieldValue {
  fun serverTimestamp(): Any
  /**
   * S61 Stage 1: the decision writes append to the round's append-only logs
   * (biddingLog/cardLog) the same way MatchService does — never a full-array
   * rewrite.
   */
  fun arrayUnion(vararg elements: Any?): Any
}

/**
 * S59: the season document's next `endsAt` is a computed instant (now + 90
 * days), not a server-sentinel. `fromMillis` takes epoch millis as a Double —
 * keeping every millis value this module writes one exact number well below
 * 2^53, the same range [toPlain] relies on.
 */
external object Timestamp {
  fun fromMillis(milliseconds: Double): Any
}
