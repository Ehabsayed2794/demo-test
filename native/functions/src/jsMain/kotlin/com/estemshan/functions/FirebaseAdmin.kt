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
 *   (FIREBASE_CONFIG) — no init code runs in the bundle;
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
}

external object FieldValue {
  fun serverTimestamp(): Any
}
