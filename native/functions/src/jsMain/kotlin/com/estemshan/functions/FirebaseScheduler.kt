@file:JsModule("firebase-functions/v2/scheduler")
@file:JsNonModule

package com.estemshan.functions

/**
 * S59 — the second-generation scheduled-function binding (RD24).
 *
 * `onSchedule` is a top-level export of `firebase-functions/v2/scheduler`, not
 * a member of a namespace. Declared as a top-level external fun for the same
 * reason as [onCall]: a namespace form compiles to a member access on an object
 * the module does not export, which is `undefined` at runtime and only fails
 * when the first trigger fires.
 *
 * The schedule itself is passed as a plain options object from the export
 * (`{ schedule, timeZone }`); this file declares only the call shape, so a
 * malformed handler signature fails at compile time. The returned value is the
 * function object the CLI discovers through its `__endpoint` metadata, exported
 * from the package-less entry point exactly like the callables.
 */
external fun onSchedule(schedule: Any?, handler: (Any?) -> Any?): dynamic
