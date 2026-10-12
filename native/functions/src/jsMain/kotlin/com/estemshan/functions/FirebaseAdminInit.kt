package com.estemshan.functions

/**
 * firebase-admin's DEFAULT app, initialized once.
 *
 * `getFirestore()` resolves that app by name, and firebase-admin never creates
 * it on its own — not in production, and not under the emulator, where an
 * uninitialized default app reaches the callable as `FirebaseAppError: The
 * default Firebase app does not exist` and the request dies with an INTERNAL
 * before its first document read. The Functions runtime, real or emulated,
 * supplies the credentials through `FIREBASE_CONFIG`, so [initializeApp] needs
 * no arguments of its own; the bundle's only job is to call it.
 *
 * Guarded because [initializeApp] throws when the default app already exists,
 * and the admin DBs construct this guard's caller once per request. jsTest
 * constructs neither DB, so a test environment with no `FIREBASE_CONFIG` never
 * reaches this.
 *
 * Lives outside the `@JsModule` files on purpose: a file annotated
 * `@JsModule` may hold external declarations only.
 */
private var defaultAppInitialized = false

fun ensureDefaultAdminApp() {
  if (defaultAppInitialized) return
  initializeApp()
  defaultAppInitialized = true
}
