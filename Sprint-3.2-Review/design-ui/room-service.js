/* ════════════════════════════════════════════════════════════════════
   Estimation — RoomService (Sprint 3.2: Room Foundation)
   Activates createRoom / joinRoom / leaveRoom against Firestore's
   `rooms` collection. setReady / transferHost / closeRoom remain
   NOT IMPLEMENTED as standalone public operations this sprint — Ready
   state and explicit host controls are explicitly out of scope (see
   docs/implementation/RoomFoundation.md). leaveRoom implements the
   minimal necessary cleanup/ownership-transfer inline, without calling
   those stubs.

   Schema note — read before touching this file: the field shape below
   (`creator`, `players: []`) is a deliberate, documented deviation from
   the earlier speculative `rooms/{roomId}` design in
   docs/architecture/FirestoreSchema.md (`hostUid`, fixed `seats[]`),
   because this sprint's literal spec calls for the simpler shape. See
   docs/implementation/RoomFoundation.md's "Schema reconciliation"
   section — this is a documented decision, not an oversight.

   IMPORTANT: Firestore Security Rules were NOT touched this sprint
   (explicitly out of scope). The live project's rules do not yet grant
   any access to the `rooms` collection, so every method below will
   correctly fail with a permission-denied error against the real
   project until a future sprint is authorized to update the rules.
   This is expected — see docs/implementation/RoomFoundation.md.

   Still calls PlayerService/SessionService only through their EXISTING
   public APIs (updatePlayerProfile / refresh) — neither was modified.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  // Reasonable interim cap matching Estemshan's 4-seat table — not part
  // of any previously-agreed schema, a plain implementation constant.
  var MAX_PLAYERS = 4;

  function notImplemented(methodName) {
    throw new Error("RoomService." + methodName + "() is not implemented yet — see docs/implementation/RoomFoundation.md.");
  }

  function db() { return global.Db || null; }
  function serverTimestamp() { return firebase.firestore.FieldValue.serverTimestamp(); }

  /** Best-effort: mirror the player's current room onto their own
   *  players/{uid} profile (an already-approved, already-client-writable
   *  field — see design-ui/player-service.js's ALLOWED_UPDATE_FIELDS),
   *  then refresh SessionService's cache so it picks up the change. This
   *  is how "SessionService becomes aware of the current roomId" is
   *  satisfied WITHOUT modifying either service's own code — both are
   *  used purely through their existing public methods. Never rejects:
   *  a failure here is logged and swallowed so it can't fail the room
   *  action that already succeeded. */
  function syncCurrentRoomOnProfile(playerId, roomId) {
    if (!global.PlayerService) return Promise.resolve();
    return global.PlayerService.updatePlayerProfile(playerId, { currentRoomId: roomId })
      .then(function () {
        if (global.SessionService && typeof global.SessionService.refresh === "function") {
          return global.SessionService.refresh();
        }
      })
      .catch(function (err) {
        console.error("[RoomService] Failed to sync currentRoomId onto the player profile (non-fatal — the room action itself already succeeded):", err);
      });
  }

  /** Creates a new room document. Resolves the new roomId. */
  function createRoom(playerId, roomName) {
    if (!playerId) return Promise.reject(new Error("createRoom: playerId is required."));
    if (!db()) return Promise.reject(new Error("RoomService: Firestore is not initialized on this page."));
    var ref = db().collection("rooms").doc();
    var room = {
      name: roomName || null,
      status: "waiting",
      creator: playerId,
      players: [playerId],
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    };
    return ref.set(room).then(function () {
      return syncCurrentRoomOnProfile(playerId, ref.id);
    }).then(function () {
      return ref.id;
    });
  }

  /** Joins an existing room. Validates existence and open/not-full
   *  state inside a transaction — this is the guard against the "rapid
   *  actions" race the brief calls out (two joins for the last open
   *  slot racing each other). Idempotent: joining a room you're already
   *  in is a safe no-op, not a duplicate entry or an error. */
  function joinRoom(roomId, playerId) {
    if (!roomId || !playerId) return Promise.reject(new Error("joinRoom: roomId and playerId are both required."));
    if (!db()) return Promise.reject(new Error("RoomService: Firestore is not initialized on this page."));
    var ref = db().collection("rooms").doc(roomId);
    return db().runTransaction(function (tx) {
      return tx.get(ref).then(function (snap) {
        if (!snap.exists) throw new Error("Room not found.");
        var room = snap.data();
        if (room.status === "closed") throw new Error("This room is closed.");
        var players = room.players || [];
        if (players.indexOf(playerId) !== -1) return room; // already a member — no-op
        if (players.length >= MAX_PLAYERS) throw new Error("This room is full.");
        var updated = players.concat([playerId]);
        tx.update(ref, { players: updated, updatedAt: serverTimestamp() });
        return Object.assign({}, room, { players: updated });
      });
    }).then(function (room) {
      return syncCurrentRoomOnProfile(playerId, roomId).then(function () { return room; });
    });
  }

  /** Leaves a room. If the departing player was the last one, the room
   *  closes (simplest valid cleanup). If the departing player was the
   *  creator and others remain, ownership transfers to the next
   *  remaining player in array order (simplest valid strategy) — this
   *  happens inline here, not by calling the still-unimplemented
   *  transferHost()/closeRoom() stubs. Idempotent: leaving a room you're
   *  not in, or that no longer exists, is a safe no-op. */
  function leaveRoom(roomId, playerId) {
    if (!roomId || !playerId) return Promise.reject(new Error("leaveRoom: roomId and playerId are both required."));
    if (!db()) return Promise.reject(new Error("RoomService: Firestore is not initialized on this page."));
    var ref = db().collection("rooms").doc(roomId);
    return db().runTransaction(function (tx) {
      return tx.get(ref).then(function (snap) {
        if (!snap.exists) return null; // already gone — no-op
        var room = snap.data();
        var players = (room.players || []).filter(function (id) { return id !== playerId; });
        var patch = { players: players, updatedAt: serverTimestamp() };
        if (players.length === 0) {
          patch.status = "closed";
        } else if (room.creator === playerId) {
          patch.creator = players[0];
        }
        tx.update(ref, patch);
        return Object.assign({}, room, patch);
      });
    }).then(function (room) {
      return syncCurrentRoomOnProfile(playerId, null).then(function () { return room; });
    });
  }

  function setReady(roomId, uid, ready) { return notImplemented("setReady"); }
  function transferHost(roomId, newHostUid) { return notImplemented("transferHost"); }
  function closeRoom(roomId) { return notImplemented("closeRoom"); }

  function subscribeToRoom(roomId, callback) {
    console.warn("RoomService.subscribeToRoom() is not implemented yet — no updates will be delivered.");
    return function unsubscribe() {};
  }

  global.RoomService = {
    createRoom: createRoom,
    joinRoom: joinRoom,
    leaveRoom: leaveRoom,
    setReady: setReady,
    transferHost: transferHost,
    closeRoom: closeRoom,
    subscribeToRoom: subscribeToRoom
  };
})(window);
