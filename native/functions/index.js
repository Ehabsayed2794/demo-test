// The deploy manifest for the Kotlin/JS authority layer.
//
// The Firebase CLI discovers callables by requiring this module and walking its
// ENUMERABLE exports (firebase-tools' extractTriggers.js: Object.keys(mod)),
// keeping any member that is a function carrying a __trigger. Kotlin/JS's
// @JsExport instead defines each export as a NON-enumerable getter (kotlin's
// defineProp is configurable-only, so enumerable defaults to false), which
// makes Object.keys return an empty key set — the CLI then registers nothing
// and every call 404s with functions/not-found. This file re-publishes each
// callable as a plain enumerable property, which is the shape the CLI needs.
//
// Keep this in sync whenever any @JsName export is added to or removed from
// any file under native/functions/src/jsMain/kotlin/ (Functions.kt and the
// *Exports.kt files all count — every @JsName("...") value must appear here).
// The guard below
// makes drift fail LOUDLY at emulator start — a callable listed here but not
// exported by the bundle, or one that lost its trigger metadata — instead of
// silently deploying an empty module.

const BUNDLE = "./build/compileSync/js/main/productionExecutable/kotlin/functions.js";
const kotlin = require(BUNDLE);

const exported = {
  engineSmoke: kotlin.engineSmoke,
  settleMatch: kotlin.settleMatch,
  resolveDecisionTimeout: kotlin.resolveDecisionTimeout,
  reclaimInactiveSeat: kotlin.reclaimInactiveSeat,
  resolveDisconnectVote: kotlin.resolveDisconnectVote,
  castDisconnectVote: kotlin.castDisconnectVote,
  restoreDisconnectedSeat: kotlin.restoreDisconnectedSeat,
  getMyRankedProfile: kotlin.getMyRankedProfile,
  getRankedLeaderboard: kotlin.getRankedLeaderboard,
  scheduledSeasonReset: kotlin.scheduledSeasonReset,
  joinRankDownRoom: kotlin.joinRankDownRoom,
  operatorSeasonResetDryRun: kotlin.operatorSeasonResetDryRun,
};

for (const [name, fn] of Object.entries(exported)) {
  if (typeof fn !== "function") {
    throw new Error(
      `functions manifest: "${name}" is not exported by the Kotlin bundle — ` +
      `build it first (gradlew :functions:jsProductionExecutableCompileSync) ` +
      `or add it to native/functions/src/jsMain/kotlin/Functions.kt.`,
    );
  }
  // Callables carry __trigger; scheduled functions carry __endpoint. Either
  // marker means the CLI will register it; neither means the member is not a
  // Firebase function at all.
  if (!fn.__trigger && !fn.__endpoint) {
    throw new Error(
      `functions manifest: "${name}" has neither __trigger nor __endpoint — ` +
      `it is not a Firebase function. Declare it with onCall(...) or ` +
      `onSchedule(...).`,
    );
  }
}

module.exports = exported;
