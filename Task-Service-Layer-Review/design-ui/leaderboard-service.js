/* ════════════════════════════════════════════════════════════════════
   Estimation — LeaderboardService (Service Layer skeleton — Sprint 2.7)
   API-ONLY STUB. No Firestore logic, no business logic, no reads/writes.
   See docs/architecture/ServiceArchitecture.md's LeaderboardService
   section and docs/architecture/MigrationPlan.md's note on why
   submitRankedResult's real implementation is a Cloud Functions
   migration candidate, not just a Firestore write.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function notImplemented(methodName) {
    throw new Error("LeaderboardService." + methodName + "() is not implemented yet — see docs/architecture/FirestoreSchema.md (leaderboards/{seasonId}).");
  }

  function getTopN(seasonId, n) { return notImplemented("getTopN"); }
  function getMyRank(seasonId, uid) { return notImplemented("getMyRank"); }
  function submitRankedResult(seasonId, uid, delta) { return notImplemented("submitRankedResult"); }

  global.LeaderboardService = {
    getTopN: getTopN,
    getMyRank: getMyRank,
    submitRankedResult: submitRankedResult
  };
})(window);
