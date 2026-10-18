@file:JsModule("firebase-admin/app")
@file:JsNonModule

package com.estemshan.functions

/**
 * Creates firebase-admin's DEFAULT app. The runtime (real or emulated) supplies
 * the credentials through `FIREBASE_CONFIG`, so the call takes no arguments.
 * See [ensureDefaultAdminApp] — this is deliberately bare; the guard lives with
 * the rest of the admin facade.
 *
 * Bound to the modular `firebase-admin/app` entry, not to the root
 * `firebase-admin` namespace, and that choice is load-bearing. A top-level
 * external fun compiles to a HOISTED import
 * (`var initializeApp = $module$firebase_admin.initializeApp;`, emitted exactly
 * like that in the dev bundle AND in the production bundle `index.js` requires),
 * and the root namespace's `initializeApp` is a prototype method that reads
 * `this.INTERNAL`. Called without its receiver it dies with
 * `TypeError: Cannot read properties of undefined (reading 'INTERNAL')` before
 * any Admin callable can construct its DB. `lifecycle.js`'s `initializeApp` is a
 * standalone function over the same `defaultAppStore` that `getFirestore()`
 * reads, so it is the same app, reached receiver-free, and it is the subpath
 * convention `FirebaseAdmin.kt` already follows with `firebase-admin/firestore`.
 */
external fun initializeApp(): Any?
