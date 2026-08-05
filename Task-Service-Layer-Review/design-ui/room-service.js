/* ════════════════════════════════════════════════════════════════════
   Estimation — RoomService (Service Layer skeleton — Sprint 2.7)
   API-ONLY STUB. No Firestore logic, no business logic, no gameplay
   logic, no listeners, no transactions, no reads, no writes.

   This is the abstraction boundary described in
   docs/architecture/ServiceArchitecture.md: future code calls
   RoomService's methods, never Firestore directly. When the real
   room-lifecycle implementation lands (Sprint 3+), only the INSIDE of
   these functions changes — callers do not.

   Every method here either throws "Not implemented" (the default) or
   returns a safe placeholder — see docs/implementation/ServiceLayer.md
   for exactly which methods do which, and why.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function notImplemented(methodName) {
    throw new Error("RoomService." + methodName + "() is not implemented yet — see docs/architecture/RoomLifecycle.md.");
  }

  function createRoom(hostUid) { return notImplemented("createRoom"); }
  function joinRoom(roomId, uid) { return notImplemented("joinRoom"); }
  function setReady(roomId, uid, ready) { return notImplemented("setReady"); }
  function leaveRoom(roomId, uid) { return notImplemented("leaveRoom"); }
  function transferHost(roomId, newHostUid) { return notImplemented("transferHost"); }
  function closeRoom(roomId) { return notImplemented("closeRoom"); }

  /** Subscription methods return a safe no-op unsubscribe function rather
   *  than throwing — a caller that does `var unsub = subscribeToRoom(...)`
   *  and later calls `unsub()` on cleanup should never crash just because
   *  the underlying feature isn't built yet. */
  function subscribeToRoom(roomId, callback) {
    console.warn("RoomService.subscribeToRoom() is not implemented yet — no updates will be delivered.");
    return function unsubscribe() {};
  }

  global.RoomService = {
    createRoom: createRoom,
    joinRoom: joinRoom,
    setReady: setReady,
    leaveRoom: leaveRoom,
    transferHost: transferHost,
    closeRoom: closeRoom,
    subscribeToRoom: subscribeToRoom
  };
})(window);
