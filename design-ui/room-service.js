/* ════════════════════════════════════════════════════════════════════
   Estimation — RoomService
   Sprint 3.2: activated createRoom / joinRoom / leaveRoom.
   Sprint 3.2.1: security-rules hotfix (no code change here).
   Sprint 3.3 (Ready State Foundation): activated setReady. transferHost
   / closeRoom remain NOT IMPLEMENTED as standalone public operations —
   explicit host controls beyond leave-triggered transfer are still out
   of scope (see docs/implementation/ReadyStateFoundation.md). leaveRoom
   implements ownership-transfer/cleanup inline, without calling those
   stubs, and now also keeps `readyPlayers` consistent on departure.

   Schema note: field shape (`creator`, `players: []`, `readyPlayers: []`
   as of Sprint 3.3) follows the actual, shipped implementation — see
   docs/architecture/FirestoreSchema.md, which Sprint 3.3 re-synced to
   match this file exactly (closing the drift flagged in the Sprint
   3.2.5 Architecture Audit).

   Firestore Security Rules: Sprint 3.3 tightened `rooms/{roomId}`'s
   rules per the Architecture Audit's recommendations (field whitelist,
   self-only array-membership changes, immutable-field protection) — see
   docs/implementation/ReadyStateFoundation.md and firestore.rules
   itself. Still not deployed to the live project — same pending manual
   publish step as every prior sprint's rules work.

   Sprint 3.4 (Match Initialization & Game Start): setReady now DETECTS
   "every player in the room is ready" and, if so, fires
   MatchService.startMatch(roomId) — a new, one-directional dependency
   (RoomService → MatchService). RoomService never writes a match
   document itself and MatchService never calls back into RoomService,
   so this introduces no circular dependency. See match-service.js's
   header comment for the one narrow exception to "RoomService owns all
   room-state mutation": startMatch()'s own transaction is what
   atomically flips room.status/matchId alongside creating the match,
   because only one atomic write can guarantee no duplicate match is
   created when two players ready up concurrently.

   Sprint 3.4.1 (Match Start Consistency & Security Hotfix):
   - Added loadRoom(roomId) — a plain, read-only fetch (mirrors
     MatchService.loadMatch()'s established pattern exactly: resolves
     null, not a rejection, if the room doesn't exist). This is the
     "room polling" primitive Lobby now uses to discover
     rooms/{roomId}.matchId once a match has started — see
     docs/implementation/MatchInitialization.md's Sprint 3.4.1 section
     on why players/{uid}.currentMatchId is no longer written by
     MatchService for every room member, only self-synced by each
     client after observing the room. This is NOT subscribeToRoom (that
     remains an intentional stub — no live listener, no reconnect
     machinery was added).
   - setReady()'s previously fire-and-forget maybeStartMatch() call is
     now AWAITED and its outcome attached to the resolved room as
     `room.matchStart = { allReady, started, matchId, error }`. This
     does not add retry/reconnect logic — it makes an already-happening
     failure OBSERVABLE to the caller (see maybeStartMatch() below)
     instead of only ever reaching a console.error a caller has no way
     to react to. setReady() itself still never rejects because of a
     match-start failure — the ready-toggle it was asked to perform
     already succeeded independently.

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

  /** Creates a new room document. Resolves the new roomId.
   *  readyPlayers starts empty — creating a room does not imply being
   *  ready; that's a separate, explicit action (see setReady below). */
  function createRoom(playerId, roomName) {
    if (!playerId) return Promise.reject(new Error("createRoom: playerId is required."));
    if (!db()) return Promise.reject(new Error("RoomService: Firestore is not initialized on this page."));
    var ref = db().collection("rooms").doc();
    var room = {
      name: roomName || null,
      status: "waiting",
      creator: playerId,
      players: [playerId],
      readyPlayers: [],
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
        // Sprint 3.3: a departing player must not remain marked "ready" —
        // keep readyPlayers consistent with players on every leave, not
        // just at read time.
        var readyPlayers = (room.readyPlayers || []).filter(function (id) { return id !== playerId; });
        var patch = { players: players, readyPlayers: readyPlayers, updatedAt: serverTimestamp() };
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

  /** Sets (or clears) exactly one player's own ready state. Requires the
   *  player to already be a room member — a non-member cannot mark
   *  themselves ready in a room they haven't joined. Idempotent: setting
   *  the same value twice performs no write the second time (Spark-
   *  conscious — see docs/implementation/ReadyStateFoundation.md's Spark
   *  Compatibility note). Never touches `players`, `creator`, `status`,
   *  or `name` — only `readyPlayers`/`updatedAt`, enforced both here and
   *  independently by firestore.rules' onlyAllowedRoomFieldsChanged(). */
  function setReady(roomId, playerId, ready) {
    if (!roomId || !playerId) return Promise.reject(new Error("setReady: roomId and playerId are both required."));
    if (!db()) return Promise.reject(new Error("RoomService: Firestore is not initialized on this page."));
    var ref = db().collection("rooms").doc(roomId);
    return db().runTransaction(function (tx) {
      return tx.get(ref).then(function (snap) {
        if (!snap.exists) throw new Error("Room not found.");
        var room = snap.data();
        if (room.status === "closed") throw new Error("This room is closed.");
        var players = room.players || [];
        if (players.indexOf(playerId) === -1) throw new Error("You are not a member of this room.");
        var readyPlayers = room.readyPlayers || [];
        var isReady = readyPlayers.indexOf(playerId) !== -1;
        var wantsReady = !!ready;
        if (isReady === wantsReady) return Object.assign({}, room); // already in the desired state — no write
        var nextReadyPlayers = wantsReady
          ? readyPlayers.concat([playerId])
          : readyPlayers.filter(function (id) { return id !== playerId; });
        tx.update(ref, { readyPlayers: nextReadyPlayers, updatedAt: serverTimestamp() });
        return Object.assign({}, room, { readyPlayers: nextReadyPlayers });
      });
    }).then(function (room) {
      // Sprint 3.4 (Game Start): RoomService is the one that DETECTS
      // "everyone is now ready" — MatchService.startMatch() is the one
      // that actually creates the match, atomically, and is what
      // guarantees no duplicate match gets created if this fires from
      // two concurrent setReady calls (see match-service.js's header
      // comment). Sprint 3.4.1: the outcome is now AWAITED (was fire-
      // and-forget) and attached to the resolved room as
      // `room.matchStart` so a caller can actually observe success/
      // failure — see maybeStartMatch() below and this file's header
      // comment. setReady() itself still never rejects because of this:
      // the ready-toggle it was asked to perform already succeeded
      // independently of whether a match could also be started.
      return maybeStartMatch(roomId, room).then(function (matchStart) {
        room.matchStart = matchStart;
        return room;
      });
    });
  }

  /** Resolves a structured result describing whether a match-start was
   *  attempted for this room and what happened — never rejects.
   *    { allReady: boolean,   // was every player actually ready?
   *      started: boolean,    // does a match now exist for this room?
   *      matchId: string|null,
   *      error: Error|null }  // set only if an attempt was made and failed
   *  This is Sprint 3.4.1's "smallest valid improvement" for match-start
   *  observability (see this file's header comment) — not a retry
   *  system, not reconnect: one attempt, its outcome reported plainly. */
  function maybeStartMatch(roomId, room) {
    if (!room || room.status !== "waiting") {
      return Promise.resolve({ allReady: false, started: false, matchId: null, error: null });
    }
    var players = room.players || [];
    var readyPlayers = room.readyPlayers || [];
    var allReady = players.length > 0 && players.every(function (uid) { return readyPlayers.indexOf(uid) !== -1; });
    if (!allReady) {
      return Promise.resolve({ allReady: false, started: false, matchId: null, error: null });
    }
    if (!global.MatchService || typeof global.MatchService.startMatch !== "function") {
      var unavailableErr = new Error("MatchService is not available — match was not started.");
      console.warn("[RoomService] All players ready, but MatchService is not available — match was not started.");
      return Promise.resolve({ allReady: true, started: false, matchId: null, error: unavailableErr });
    }
    return global.MatchService.startMatch(roomId).then(function (matchId) {
      return { allReady: true, started: true, matchId: matchId, error: null };
    }).catch(function (err) {
      console.error("[RoomService] MatchService.startMatch failed (non-fatal to setReady itself — see room.matchStart.error):", err);
      return { allReady: true, started: false, matchId: null, error: err };
    });
  }

  /** Read-only fetch of a room document. Resolves null if it doesn't
   *  exist (not an error) — mirrors MatchService.loadMatch()'s
   *  established pattern exactly. Sprint 3.4.1: this is the "room
   *  polling" primitive a client uses to discover a room's matchId
   *  once a match has started, since MatchService no longer writes
   *  currentMatchId onto every room member's own profile (see this
   *  file's header comment and docs/implementation/
   *  MatchInitialization.md). Deliberately NOT a live listener —
   *  subscribeToRoom() below remains the unimplemented stub it always
   *  was; polling is the documented, honest limitation here. */
  function loadRoom(roomId) {
    if (!roomId) return Promise.reject(new Error("loadRoom: roomId is required."));
    if (!db()) return Promise.reject(new Error("RoomService: Firestore is not initialized on this page."));
    return db().collection("rooms").doc(roomId).get().then(function (snap) {
      return snap.exists ? snap.data() : null;
    });
  }

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
    loadRoom: loadRoom,
    transferHost: transferHost,
    closeRoom: closeRoom,
    subscribeToRoom: subscribeToRoom
  };
})(window);
