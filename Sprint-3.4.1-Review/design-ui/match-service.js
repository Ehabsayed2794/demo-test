/* ════════════════════════════════════════════════════════════════════
   Estimation — MatchService
   Sprint 2.7: API-only skeleton (submitBid/playCard/etc. still stubs —
   bidding, estimation, and card play are explicitly out of scope
   through Sprint 3.4.1; see docs/implementation/MatchInitialization.md).
   Sprint 3.4 (Match Initialization & Game Start): activated createMatch,
   startMatch, loadMatch, subscribeToMatch.

   Sprint 3.4.1 (Match Start Consistency & Security Hotfix) — TWO
   changes to the public API, both explained in full in
   docs/implementation/MatchInitialization.md:

   1. createMatch() REMOVED from the public API. It bypassed every
      safety property startMatch() provides (no all-ready gate, no
      duplicate-start protection, no atomic room transition) — a
      production review correctly flagged it as an unsafe public
      method kept around only because it existed, not because anything
      legitimate called it (nothing did — startMatch() never called it
      either; the two were always independent, parallel
      implementations sharing only buildInitialMatchDoc()). RoomService
      and the UI must use startMatch() only. The Sprint 3.4.1-tightened
      firestore.rules below also now structurally reject the shape
      createMatch() used to produce (a match document created without
      the SAME-transaction room binding) — so even a future accidental
      reintroduction of this pattern would be denied server-side, not
      just discouraged by convention.

   2. currentMatchId is no longer written onto every room player's
      players/{uid} document. players/{uid}'s rules are (and remain)
      owner-only (isOwner(uid) — see firestore.rules, unchanged) — only
      the INITIATING player's own write could ever succeed; every other
      player's write was silently swallowed as "non-fatal" by the old
      syncCurrentMatchOnProfiles(), which is exactly the bug this
      hotfix closes. MatchService now only ever self-syncs the CALLING
      client's own currentMatchId, via SessionService.setCurrentMatchId()
      — a method that takes no uid parameter at all, so it is
      structurally impossible for this to target another player. The
      AUTHORITATIVE multiplayer source for "who is in which match" is
      now explicitly rooms/{roomId}.matchId and matches/{matchId}.players
      — every OTHER seated client discovers the match by polling its own
      room (RoomService.loadRoom(), new this sprint) and self-syncing
      the same way. players/{uid}.currentMatchId is a same-user
      convenience mirror only, never read as ground truth by any service
      in this codebase.

   Boundary with RoomService (read carefully before touching either
   file): RoomService owns EVERY room-state mutation except one. The one
   deliberate, narrow exception — documented here and in
   MatchInitialization.md, not hidden — is startMatch()'s single
   transaction, which must atomically touch BOTH rooms/{roomId} (status,
   matchId) AND matches/{matchId} (the new document) in one write,
   because that is the only way to guarantee "two players pressing Ready
   simultaneously cannot create two matches." No method here ever calls
   back into RoomService — the dependency is one-directional
   (RoomService → MatchService → SessionService), same shape as every
   other cross-service call in this codebase, no circular dependency
   introduced.

   No longer depends on PlayerService directly (Sprint 3.4.1 removed
   the last direct call) — only on SessionService, for self-only sync.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function notImplemented(methodName) {
    throw new Error("MatchService." + methodName + "() is not implemented yet — see docs/implementation/MatchInitialization.md.");
  }

  function db() { return global.Db || null; }
  function serverTimestamp() { return firebase.firestore.FieldValue.serverTimestamp(); }

  /** Builds the initial match document for a room. Pure — no I/O.
   *  The one place this document's shape is defined. (Through Sprint
   *  3.4 this was also shared by the now-removed createMatch() — see
   *  this file's header comment.)
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

  /** Sprint 3.4.1: self-only. Syncs currentMatchId onto ONLY the
   *  currently signed-in user's own profile, via SessionService's
   *  self-only setCurrentMatchId() — never PlayerService directly, and
   *  never with any other uid. Delegating to SessionService (rather
   *  than calling PlayerService.updatePlayerProfile(uid, ...) here with
   *  some explicit uid) is what makes cross-user writes structurally
   *  impossible, not just disciplined-by-convention: there is no uid
   *  parameter anywhere in this call chain for a bug to accidentally
   *  misuse. Never rejects: a failure here is logged and swallowed so
   *  it can't fail startMatch()'s own already-committed success. See
   *  this file's header comment and docs/implementation/
   *  MatchInitialization.md for why every OTHER seated player must
   *  instead discover the match via their own room polling
   *  (RoomService.loadRoom()) and call this same self-sync themselves. */
  function syncOwnCurrentMatchId(matchId) {
    if (!global.SessionService || typeof global.SessionService.setCurrentMatchId !== "function") return Promise.resolve();
    return global.SessionService.setCurrentMatchId(matchId).catch(function (err) {
      console.error("[MatchService] Failed to sync currentMatchId onto the signed-in player's own profile (non-fatal):", err);
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
      // Sprint 3.4.1: self-sync ONLY the calling client's own profile —
      // regardless of created true/false, since even the caller who
      // discovers "a match already exists" (the idempotent-return path)
      // still wants their own currentMatchId set. Every OTHER seated
      // player's own client is responsible for doing this same self-sync
      // for itself, after discovering matchId via RoomService.loadRoom()
      // — see this file's header comment.
      return syncOwnCurrentMatchId(result.matchId).then(function () {
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
    // createMatch REMOVED from the public API in Sprint 3.4.1 — it
    // bypassed the all-ready gate, duplicate-start protection, and
    // atomic room transition that startMatch() provides. Nothing in
    // this codebase ever called it (RoomService/UI always used
    // startMatch()); it existed only as an earlier, unsafe primitive.
    // See this file's header comment and docs/implementation/
    // MatchInitialization.md.
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
