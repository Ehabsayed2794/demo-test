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
}

external interface CollectionRef {
  fun doc(id: String): DocRef
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

external object FieldValue {
  fun serverTimestamp(): Any
  /**
   * S61 Stage 1: the decision writes append to the round's append-only logs
   * (biddingLog/cardLog) the same way MatchService does — never a full-array
   * rewrite.
   */
  fun arrayUnion(vararg elements: Any?): Any
}
