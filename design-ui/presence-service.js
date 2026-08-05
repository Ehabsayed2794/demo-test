/* ════════════════════════════════════════════════════════════════════
   Estimation — PresenceService (Service Layer skeleton — Sprint 2.7)
   API-ONLY STUB. No Firestore logic, no listeners, no writes.

   NOTE ON SCOPE: docs/architecture/RoomLifecycle.md originally described
   presence as a `lastSeenAt` heartbeat field owned by PlayerService, not
   a standalone service. This sprint's brief asks for a dedicated
   PresenceService module, so this file exists as that future home —
   it does not yet duplicate or contradict PlayerService (which still
   owns `lastSeenAt` today, per PlayerService's existing implementation);
   see docs/implementation/ServiceLayer.md for the full note.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function notImplemented(methodName) {
    throw new Error("PresenceService." + methodName + "() is not implemented yet — see docs/architecture/RoomLifecycle.md.");
  }

  function updateHeartbeat(uid) { return notImplemented("updateHeartbeat"); }
  function isOnline(uid) { return notImplemented("isOnline"); }

  function subscribeToPresence(uid, callback) {
    console.warn("PresenceService.subscribeToPresence() is not implemented yet — no updates will be delivered.");
    return function unsubscribe() {};
  }

  global.PresenceService = {
    updateHeartbeat: updateHeartbeat,
    isOnline: isOnline,
    subscribeToPresence: subscribeToPresence
  };
})(window);
