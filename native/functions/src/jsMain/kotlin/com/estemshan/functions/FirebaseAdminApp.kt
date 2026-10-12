@file:JsModule("firebase-admin")
@file:JsNonModule

package com.estemshan.functions

/**
 * Creates firebase-admin's DEFAULT app. The runtime (real or emulated) supplies
 * the credentials through `FIREBASE_CONFIG`, so the call takes no arguments.
 * See [ensureDefaultAdminApp] — this is deliberately bare; the guard lives with
 * the rest of the admin facade.
 */
external fun initializeApp(): Any?
