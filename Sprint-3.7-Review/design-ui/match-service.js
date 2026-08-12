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

   Sprint 3.7 (Real-Time Match Synchronization): subscribeToMatch() is
   now MatchService's production-ready real-time sync primitive — this
   is the brief's requested `subscribe(matchId)`, kept under the
   existing name for the exact same naming-consistency reason already
   recorded in the Sprint 3.4 note above and in
   docs/architecture/ServiceArchitecture.md. See
   docs/architecture/MatchSynchronization.md for the full design. In
   one sentence: one shared Firestore listener per matchId no matter
   how many local callers subscribe (ref-counted, torn down the moment
   the last one unsubscribes — no duplicated listeners, no leak), an
   ordering guard (an out-of-order/stale snapshot can never overwrite
   newer state), a duplicate-content guard (an identical re-delivery is
   never re-published, which is what makes this loop-safe by
   construction), and automatic reconnect-with-backoff on error, all
   while still delivering the last known good state alongside any error
   (never `null`) — the local game stays alive through a disconnect.
   This sprint is synchronization only: no gameplay method below
   changed, and no gameplay write path was added — see
   MatchSynchronization.md's "Known Limitation" note on why.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function notImplemented(methodName) {
    throw new Error("MatchService." + methodName + "() is not implemented yet — see docs/implementation/MatchInitialization.md.");
  }

  function db() { return global.Db || null; }
  function serverTimestamp() { return firebase.firestore.FieldValue.serverTimestamp(); }

  // ── real-time sync internals (Sprint 3.7) ──────────────────────────
  // Everything below this comment, through attachListener/
  // scheduleReconnect, is new this sprint and purely additive — no
  // existing function above was changed to make room for it.
  var RECONNECT_BASE_MS = 250;
  var RECONNECT_MAX_MS = 4000;

  // matchId -> { listeners, unsubscribeFirestore, hasPublished,
  //              lastPublishedData, lastVersion, reconnectAttempt,
  //              reconnectTimer }
  // One entry per matchId with at least one active local subscriber —
  // this IS the "no duplicated listeners" mechanism: subscribeToMatch()
  // only ever calls the real Firestore onSnapshot() when an entry does
  // not already exist for that matchId.
  var matchSubscriptions = {};

  /** Structural equality for plain, JSON-shaped Firestore document data
   *  (no functions, no cyclic references — matches what every document
   *  in this codebase actually looks like). Used only to detect an
   *  identical re-delivery of a snapshot we already published — never
   *  used for anything gameplay-related. */
  function deepEqual(a, b) {
    if (a === b) return true;
    if (a == null || b == null) return a === b;
    if (typeof a !== typeof b) return false;
    if (typeof a !== "object") return false;
    var aKeys = Object.keys(a), bKeys = Object.keys(b);
    if (aKeys.length !== bKeys.length) return false;
    return aKeys.every(function (k) {
      return Object.prototype.hasOwnProperty.call(b, k) && deepEqual(a[k], b[k]);
    });
  }

  function safeInvoke(cb, data, err) {
    try { cb(data, err); } catch (e) { console.error("[MatchService] a subscribeToMatch callback threw:", e); }
  }

  /** (Re)attaches the one real Firestore listener for this matchId's
   *  subscription entry. Called once when the entry is first created,
   *  and again — reusing the SAME entry/listeners, never creating a
   *  second one — every time scheduleReconnect()'s backoff timer fires
   *  after a disconnect. */
  function attachListener(matchId, entry) {
    entry.unsubscribeFirestore = db().collection("matches").doc(matchId).onSnapshot(
      function (snap) {
        entry.reconnectAttempt = 0; // a successful snapshot means we're connected again — reset backoff
        var data = snap.exists ? snap.data() : null;

        // Ordering guard: a document written with a numeric `version`
        // field is only published if it is strictly newer than the
        // last one seen for THIS matchId. No shipped write path sets
        // `version` yet (see MatchSynchronization.md), so this guard
        // is armed but dormant today — it activates automatically the
        // moment a future write path adds the field, with no change
        // needed here. An old/out-of-order snapshot can never
        // overwrite newer local state.
        if (data && typeof data.version === "number") {
          if (entry.lastVersion != null && data.version <= entry.lastVersion) return; // stale — ignored
          entry.lastVersion = data.version;
        }

        // Duplicate-content guard: an identical re-delivery (e.g. a
        // benign metadata-only refresh) is never re-published — this
        // is what prevents "ignore local duplicate updates" from ever
        // becoming an infinite update loop, even if a future write
        // path reacts to every publish.
        if (entry.hasPublished && deepEqual(data, entry.lastPublishedData)) return;

        entry.hasPublished = true;
        entry.lastPublishedData = data;
        entry.listeners.slice().forEach(function (cb) { safeInvoke(cb, data, null); });
      },
      function (err) {
        // Fail-open: never crash, never throw the error at the caller,
        // and never null out state the local game still needs — the
        // last known good data (if any) is delivered ALONGSIDE the
        // error, not replaced by it. Requirement #6: "keep local game
        // alive... reconnect automatically... never crash."
        var deliveredData = entry.hasPublished ? entry.lastPublishedData : null;
        entry.listeners.slice().forEach(function (cb) { safeInvoke(cb, deliveredData, err); });
        scheduleReconnect(matchId, entry);
      }
    );
  }

  /** Schedules exactly one pending resubscribe attempt (never stacks a
   *  second one), with exponential backoff that resets to the base
   *  delay the moment a snapshot succeeds again. Never resubscribes for
   *  a matchId nobody is listening to anymore. */
  function scheduleReconnect(matchId, entry) {
    if (entry.reconnectTimer) return;
    if (entry.listeners.length === 0) return;
    var attempt = entry.reconnectAttempt || 0;
    var delay = Math.min(RECONNECT_BASE_MS * Math.pow(2, attempt), RECONNECT_MAX_MS);
    entry.reconnectAttempt = attempt + 1;
    entry.reconnectTimer = setTimeout(function () {
      entry.reconnectTimer = null;
      if (entry.listeners.length === 0) return; // everyone unsubscribed while we were waiting
      if (typeof entry.unsubscribeFirestore === "function") entry.unsubscribeFirestore();
      attachListener(matchId, entry);
    }, delay);
  }

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
   *  established pattern exactly. Implemented for real in Sprint 3.4;
   *  made production-ready (ref-counted single listener per matchId,
   *  ordering/duplicate guards, automatic reconnect) in Sprint 3.7 —
   *  see this file's header comment and
   *  docs/architecture/MatchSynchronization.md. A second (or third...)
   *  call for the SAME matchId never creates a second Firestore
   *  listener — it registers an additional local callback against the
   *  one already-active entry and receives the current known state
   *  immediately, exactly like the very first subscriber did. */
  function subscribeToMatch(matchId, callback) {
    if (!db()) {
      callback(null, new Error("MatchService: Firestore is not initialized on this page."));
      return function unsubscribe() {};
    }
    var entry = matchSubscriptions[matchId];
    if (!entry) {
      entry = matchSubscriptions[matchId] = {
        listeners: [callback], unsubscribeFirestore: null, hasPublished: false,
        lastPublishedData: null, lastVersion: null, reconnectAttempt: 0, reconnectTimer: null
      };
      attachListener(matchId, entry);
    } else {
      entry.listeners.push(callback);
      // A late joiner (e.g. a second local caller, or the SAME caller
      // subscribing again) gets the current known state immediately —
      // matching the original per-call "immediate snapshot" contract —
      // instead of waiting on a Firestore round trip it doesn't need.
      if (entry.hasPublished) safeInvoke(callback, entry.lastPublishedData, null);
    }
    return function unsubscribe() {
      var idx = entry.listeners.indexOf(callback);
      if (idx !== -1) entry.listeners.splice(idx, 1);
      if (entry.listeners.length === 0) {
        if (entry.reconnectTimer) { clearTimeout(entry.reconnectTimer); entry.reconnectTimer = null; }
        if (typeof entry.unsubscribeFirestore === "function") entry.unsubscribeFirestore();
        delete matchSubscriptions[matchId];
      }
    };
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
