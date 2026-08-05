/* ════════════════════════════════════════════════════════════════════
   Estimation — MatchService
   Sprint 2.7: API-only skeleton (submitBid/playCard/etc. still stubs —
   bidding, estimation, and card play are explicitly out of scope
   through Sprint 3.4; see docs/implementation/MatchInitialization.md).
   Sprint 3.4 (Match Initialization & Game Start): activated createMatch,
   startMatch, loadMatch, subscribeToMatch.

   Boundary with RoomService (read carefully before touching either
   file): RoomService owns EVERY room-state mutation except one. The one
   deliberate, narrow exception — documented here and in
   MatchInitialization.md, not hidden — is startMatch()'s single
   transaction, which must atomically touch BOTH rooms/{roomId} (status,
   matchId) AND matches/{matchId} (the new document) in one write,
   because that is the only way to guarantee "two players pressing Ready
   simultaneously cannot create two matches." createMatch() (the lower-
   level primitive) only ever READS rooms/{roomId} — it never writes it.
   No method here ever calls back into RoomService — the dependency is
   one-directional (RoomService → MatchService → {PlayerService,
   SessionService}), same shape as every other cross-service call in
   this codebase, no circular dependency introduced.

   Still calls PlayerService/SessionService only through their EXISTING
   public APIs (updatePlayerProfile / refresh) — neither was modified.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function notImplemented(methodName) {
    throw new Error("MatchService." + methodName + "() is not implemented yet — see docs/implementation/MatchInitialization.md.");
  }

  function db() { return global.Db || null; }
  function serverTimestamp() { return firebase.firestore.FieldValue.serverTimestamp(); }

  /** Builds the initial match document for a room. Pure — no I/O.
   *  Shared by createMatch() and startMatch() so the shape is defined
   *  in exactly one place.
   *
   *  gameState is deliberately a TODO placeholder, not a real dealt
   *  hand: Dealer.dealHands() (design-ui/engine/dealer.js) depends on a
   *  global `Deck` object that does not exist anywhere in this
   *  repository — deck.js was never delivered; only referenced in
   *  dealer.js's own header comment. Calling Dealer.dealHands() as-is
   *  would throw ReferenceError: Deck is not defined. Writing a Deck
   *  module here would mean authoring new engine code, exceeding this
   *  sprint's "only integrate, never duplicate" scope — so per the
   *  brief's own fallback instruction, this is left as an explicit TODO
   *  instead. See docs/implementation/MatchInitialization.md. */
  function buildInitialMatchDoc(roomId, room) {
    var players = (room.players || []).slice();
    var dealerUid = (room.creator && players.indexOf(room.creator) !== -1) ? room.creator : players[0];
    return {
      roomId: roomId,
      players: players,
      status: "starting",
      createdAt: serverTimestamp(),
      currentRound: 1,
      dealer: dealerUid,
      // "turn" has no real meaning yet — bidding/estimation (which
      // determines whose actual turn it is) is out of scope through
      // this sprint. Defaults to the dealer, matching the existing
      // engine's convention that the dealer/opening player acts first
      // in a normal round — a placeholder, not a claim that turn order
      // is implemented.
      turn: dealerUid,
      gameState: {
        initialized: false,
        todo: "Dealer.dealHands() cannot be called yet — it depends on a global Deck module that does not exist in this repository. See docs/implementation/MatchInitialization.md before implementing this."
      }
    };
  }

  /** Mirrors currentMatchId onto every player's profile via
   *  PlayerService's EXISTING public API, then refreshes SessionService
   *  — same pattern RoomService already established for currentRoomId.
   *  Never rejects: a failure here is logged and swallowed so it can't
   *  fail startMatch()'s own already-committed success. */
  function syncCurrentMatchOnProfiles(players, matchId) {
    if (!global.PlayerService) return Promise.resolve();
    return Promise.all((players || []).map(function (uid) {
      return global.PlayerService.updatePlayerProfile(uid, { currentMatchId: matchId }).catch(function (err) {
        console.error("[MatchService] Failed to sync currentMatchId onto profile " + uid + " (non-fatal):", err);
      });
    })).then(function () {
      if (global.SessionService && typeof global.SessionService.refresh === "function") {
        return global.SessionService.refresh();
      }
    });
  }

  /** The lower-level primitive: creates a new match document for a
   *  room, unconditionally. No "all ready" gate, no duplicate-start
   *  protection, no room-document write. Reads rooms/{roomId} (read-
   *  only) to know who's playing and who deals first. Calling this
   *  directly twice creates two match documents — that guarantee is
   *  deliberately startMatch()'s job, not this one's. */
  function createMatch(roomId) {
    if (!roomId) return Promise.reject(new Error("createMatch: roomId is required."));
    if (!db()) return Promise.reject(new Error("MatchService: Firestore is not initialized on this page."));
    var roomRef = db().collection("rooms").doc(roomId);
    return roomRef.get().then(function (snap) {
      if (!snap.exists) throw new Error("Room not found.");
      var room = snap.data();
      var matchRef = db().collection("matches").doc();
      var doc = buildInitialMatchDoc(roomId, room);
      return matchRef.set(doc).then(function () { return matchRef.id; });
    });
  }

  /** The safe, orchestrated entry point RoomService calls once it
   *  detects every player in a room is ready. Single transaction
   *  spanning BOTH rooms/{roomId} and matches/{matchId} — see this
   *  file's header comment for why that atomicity has to live here.
   *  Idempotent: if the room already has a matchId (a match already
   *  exists), returns that matchId rather than creating a second one —
   *  this is the concrete mechanism behind "two players pressing Ready
   *  simultaneously cannot create two matches." Rejects if the room
   *  doesn't exist or isn't actually all-ready (defense in depth — this
   *  is re-checked here even though RoomService already checks it,
   *  the same "neither layer trusts the other alone" principle already
   *  established for PlayerService's protected fields). */
  function startMatch(roomId) {
    if (!roomId) return Promise.reject(new Error("startMatch: roomId is required."));
    if (!db()) return Promise.reject(new Error("MatchService: Firestore is not initialized on this page."));
    var roomRef = db().collection("rooms").doc(roomId);
    var newMatchRef = db().collection("matches").doc();
    return db().runTransaction(function (tx) {
      return tx.get(roomRef).then(function (snap) {
        if (!snap.exists) throw new Error("Room not found.");
        var room = snap.data();
        if (room.matchId) {
          return { matchId: room.matchId, created: false, players: room.players || [] };
        }
        var players = room.players || [];
        var readyPlayers = room.readyPlayers || [];
        var allReady = players.length > 0 && players.every(function (uid) { return readyPlayers.indexOf(uid) !== -1; });
        if (!allReady) throw new Error("Not all players are ready.");
        var doc = buildInitialMatchDoc(roomId, room);
        tx.set(newMatchRef, doc);
        tx.update(roomRef, { status: "in_game", matchId: newMatchRef.id, updatedAt: serverTimestamp() });
        return { matchId: newMatchRef.id, created: true, players: players };
      });
    }).then(function (result) {
      if (!result.created) return result.matchId;
      return syncCurrentMatchOnProfiles(result.players, result.matchId).then(function () {
        return result.matchId;
      });
    });
  }

  /** Read-only fetch of a match document. Resolves null if it doesn't
   *  exist (not an error) — mirrors PlayerService.getPlayerProfile's
   *  established pattern exactly. */
  function loadMatch(matchId) {
    if (!matchId) return Promise.reject(new Error("loadMatch: matchId is required."));
    if (!db()) return Promise.reject(new Error("MatchService: Firestore is not initialized on this page."));
    return db().collection("matches").doc(matchId).get().then(function (snap) {
      return snap.exists ? snap.data() : null;
    });
  }

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

  /** Live-subscribe to a match document. Returns an unsubscribe
   *  function. Failures are delivered to callback(null, err) rather
   *  than thrown — mirrors PlayerService.subscribeToPlayerProfile's
   *  established pattern exactly. Implemented for real this sprint
   *  (was a no-op stub through Sprint 2.7-3.3). */
  function subscribeToMatch(matchId, callback) {
    if (!db()) {
      callback(null, new Error("MatchService: Firestore is not initialized on this page."));
      return function unsubscribe() {};
    }
    return db().collection("matches").doc(matchId).onSnapshot(
      function (snap) { callback(snap.exists ? snap.data() : null, null); },
      function (err) { callback(null, err); }
    );
  }

  global.MatchService = {
    createMatch: createMatch,
    startMatch: startMatch,
    loadMatch: loadMatch,
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
