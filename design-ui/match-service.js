/* ════════════════════════════════════════════════════════════════════
   Estimation — MatchService (Service Layer skeleton — Sprint 2.7)
   API-ONLY STUB. No Firestore logic, no business logic, no gameplay
   logic, no listeners, no transactions, no reads, no writes.

   This is the future boundary between the existing, untouched
   BiddingEngine/TableEngine/ScoringEngine and Firestore — see
   docs/architecture/ServiceArchitecture.md's MatchService section and
   docs/architecture/MatchLifecycle.md. Every mutating method mirrors an
   existing GameSession "record" or "complete" call's shape on purpose,
   so a future implementation can call straight into the untouched
   engines without changing this file's public signatures.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function notImplemented(methodName) {
    throw new Error("MatchService." + methodName + "() is not implemented yet — see docs/architecture/MatchLifecycle.md.");
  }

  function createMatch(roomId) { return notImplemented("createMatch"); }
  function submitDashCall(matchId, uid, decision) { return notImplemented("submitDashCall"); }
  function submitBid(matchId, uid, bid) { return notImplemented("submitBid"); }
  function submitPass(matchId, uid) { return notImplemented("submitPass"); }
  function declareTrump(matchId, uid, suit) { return notImplemented("declareTrump"); }
  function submitEstimate(matchId, uid, tricks) { return notImplemented("submitEstimate"); }
  function playCard(matchId, uid, cardId) { return notImplemented("playCard"); }
  function resolveTrick(matchId) { return notImplemented("resolveTrick"); }
  function completeRound(matchId) { return notImplemented("completeRound"); }
  function advanceToNextRound(matchId) { return notImplemented("advanceToNextRound"); }
  function endMatch(matchId, winnerId) { return notImplemented("endMatch"); }

  function subscribeToMatch(matchId, callback) {
    console.warn("MatchService.subscribeToMatch() is not implemented yet — no updates will be delivered.");
    return function unsubscribe() {};
  }

  global.MatchService = {
    createMatch: createMatch,
    submitDashCall: submitDashCall,
    submitBid: submitBid,
    submitPass: submitPass,
    declareTrump: declareTrump,
    submitEstimate: submitEstimate,
    playCard: playCard,
    resolveTrick: resolveTrick,
    completeRound: completeRound,
    advanceToNextRound: advanceToNextRound,
    endMatch: endMatch,
    subscribeToMatch: subscribeToMatch
  };
})(window);
