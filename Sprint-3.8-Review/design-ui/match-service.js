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
   the last one unsubscribes — no duplicated listeners, no leak), a
   duplicate-content guard (an identical re-delivery is never
   re-published), and reconnect-with-backoff on error, all while still
   delivering the last known good state alongside any error (never
   `null`) — the local game stays alive through a disconnect. This
   sprint is synchronization only: no gameplay method below changed,
   and no gameplay write path was added — see MatchSynchronization.md's
   "Known Limitation" note on why. An ordering guard keyed on a
   `version` field also exists (see attachListener() below), but **no
   write path in this codebase sets `version` today** — see Sprint
   3.7.1's note there and in MatchSynchronization.md's Task 2 section
   before assuming ordering is actually enforced for anything real.

   Sprint 3.7.1 (Synchronization Hardening & Identity Foundation) — an
   independent architecture review found the Sprint 3.7 reconnect logic
   retried EVERY onSnapshot error forever, including permanent ones
   (e.g. permission-denied), and that Sprint 3.7's own documentation
   overstated the ordering guard as active protection when nothing
   writes the `version` field it depends on. Both fixed this sprint,
   documented in full in docs/architecture/MatchSynchronization.md's
   "Sprint 3.7.1" section:
   1. onSnapshot errors are now classified via Firestore's own `.code`
      before deciding whether to retry — see RETRYABLE_CODES/
      NON_RETRYABLE_CODES below. A non-retryable error (or one with an
      unrecognized/missing code — treated as non-retryable, the safer
      default) stops the reconnect loop immediately and is recorded as
      that subscription's terminal error; a retryable error keeps the
      existing exponential-backoff reconnect behavior. The error is
      always exposed to every subscriber either way — this sprint never
      changed the "never crash, never silently give up" contract, only
      WHEN it keeps trying versus WHEN it correctly gives up.
   2. No behavior change to the ordering/duplicate-content guards
      themselves — only the comments and docs describing them were
      corrected to stop overclaiming. See Task 2 in
      MatchSynchronization.md for the full "where does `version` come
      from" review.

   Sprint 3.8 (Gameplay Synchronization: Bidding Authority) — the FIRST
   sprint that gives this file a real gameplay write path. Three things,
   documented in full in docs/architecture/MatchSynchronization.md's
   Sprint 3.8 section and docs/architecture/SeatIdentityModel.md
   (finally implemented, not just designed):
   1. buildInitialMatchDoc() now also establishes `seats` (Task 1 —
      SeatIdentityModel.md's design, implemented for real: a positional
      players[]->{p1..p4} map, the ONLY authority for "which uid owns
      which seat" from here on), `version` (Task 2 — starts at 1),
      and the minimal bidding-sync sub-state `biddingOpen`/`bids`/
      `lastBidSeat` (Task 3).
   2. submitBid(matchId, seatId, bid) is implemented for real (was a
      Not-implemented stub since Sprint 2.7) — see its own doc comment
      below for the full contract. This is the ONLY gameplay write this
      sprint implements; every other gameplay method below remains an
      unimplemented stub, unchanged, on purpose ("only synchronize
      bidding, nothing else").
   3. Sprint 3.7/3.7.1's ordering guard inside subscribeToMatch() (the
      one keyed on a numeric `version` field) — dormant since it was
      written, because nothing ever wrote `version` — is activated by
      this sprint's write path, with NO code change to subscribeToMatch()
      itself. Realtime delivery of a submitted bid uses that SAME,
      unmodified subscription — no second listener was created.
   Firestore rules gained a real `matches/{matchId}` update rule this
   sprint (`isValidBidSubmission()`) — the FIRST write path a client can
   legitimately use against a match document after creation. See
   firestore.rules' own inline comments and
   docs/architecture/SecurityArchitecture.md's Sprint 3.8 section.
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

  // Sprint 3.7.1, Task 1 (Retry Policy Hardening): Firestore's SDK
  // attaches a `.code` to every onSnapshot error (the same codes gRPC
  // status uses). Classified per the brief's exact lists. A code NOT in
  // either list (including a missing `.code`, e.g. a plain JS Error —
  // which is exactly what every test in this codebase, and any
  // non-Firestore failure, produces) is treated as NON-retryable — the
  // safer default, since retrying something we can't positively confirm
  // is transient is exactly the "retry forever" behavior this task
  // exists to remove. This is a deliberate, documented choice, not an
  // oversight — see docs/architecture/MatchSynchronization.md's Task 1
  // section for the full reasoning.
  var RETRYABLE_CODES = ["unavailable", "deadline-exceeded", "internal", "unknown", "resource-exhausted"];
  var NON_RETRYABLE_CODES = ["permission-denied", "unauthenticated", "invalid-argument", "failed-precondition", "not-found"];
  /** Returns one of "retryable" / "non-retryable" / "unrecognized" —
   *  used both for the actual retry decision (isRetryable() below) and
   *  to make the console.warn on a stopped subscription say WHICH of
   *  the three this was, satisfying "document every retry decision" at
   *  runtime, not just in code comments. */
  function classifyError(err) {
    var code = err && err.code;
    if (RETRYABLE_CODES.indexOf(code) !== -1) return "retryable";
    if (NON_RETRYABLE_CODES.indexOf(code) !== -1) return "non-retryable";
    return "unrecognized";
  }
  function isRetryable(err) {
    return classifyError(err) === "retryable";
  }

  // matchId -> { listeners, unsubscribeFirestore, hasPublished,
  //              lastPublishedData, lastVersion, reconnectAttempt,
  //              reconnectTimer, terminalError }
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

        // Ordering guard code path — see Sprint 3.7.1, Task 2 (this
        // file's header comment and MatchSynchronization.md's Task 2
        // section) before treating this as active protection: it only
        // ever runs if the document has a numeric `version` field, and
        // as of this sprint NOTHING in this codebase ever writes one —
        // not startMatch()'s buildInitialMatchDoc(), not any other
        // write path (there is no other write path yet — every
        // gameplay method below is still an unimplemented stub, and
        // firestore.rules denies matches/{matchId} update outright).
        // This code exists so a FUTURE write path only has to start
        // writing `version` to get ordering protection "for free," with
        // no change needed here — but until that happens, no snapshot
        // this codebase actually receives will ever have this field,
        // and this block is simply never entered. Ordering is NOT
        // currently guaranteed for any real update.
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
        // error, not replaced by it, REGARDLESS of whether this error
        // turns out to be retryable below. The error is always exposed
        // to every subscriber either way.
        var deliveredData = entry.hasPublished ? entry.lastPublishedData : null;
        entry.listeners.slice().forEach(function (cb) { safeInvoke(cb, deliveredData, err); });

        // Sprint 3.7.1, Task 1: only a retryable error schedules a
        // reconnect attempt. A non-retryable error (permission-denied,
        // unauthenticated, invalid-argument, failed-precondition,
        // not-found — or any code this list doesn't recognize, treated
        // the same way by design, see isRetryable()'s comment above)
        // stops trying immediately and is recorded as this
        // subscription's terminal error, so a LATE joiner (a
        // subscribeToMatch() call for this matchId after the fact)
        // still learns about it instead of waiting forever for a
        // reconnect that will never be attempted.
        if (isRetryable(err)) {
          scheduleReconnect(matchId, entry);
        } else {
          entry.terminalError = err;
          console.warn("[MatchService] subscribeToMatch(" + matchId + "): " + classifyError(err) + " error (code: " +
            (err && err.code || "none") + ") — reconnect attempts stopped permanently for this subscription.");
        }
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

  // Sprint 3.8, Task 1 (Seat Identity Implementation): the canonical
  // seat ids, matching design-ui/engine/session.js's own hardcoded
  // CANONICAL_ORDER exactly (kept as an independent local constant, not
  // a cross-file require/global lookup — MatchService still has ZERO
  // dependency on the engine layer, in either direction, and this
  // sprint does not change that). This is the ONLY place seat ids are
  // assigned to real players — see buildSeatMap() below and
  // docs/architecture/SeatIdentityModel.md, which this function
  // finally implements (that document was documentation-only through
  // Sprint 3.7.1).
  var SEAT_IDS = ["p1", "p2", "p3", "p4"];

  /** Assigns seats POSITIONALLY from players[] — players[0] -> "p1",
   *  players[1] -> "p2", and so on — per SeatIdentityModel.md's
   *  "Creation" section. Never infers, never guesses, never reorders:
   *  the mapping is a pure, deterministic function of players[]'s own
   *  existing join order (already order-preserving — see
   *  RoomLifecycle.md), computed once, here, at match creation, and
   *  never recomputed anywhere else.
   *
   *  Honest scope note: a real 4-player match gets all four seats.
   *  This project's room system does not enforce a MINIMUM room size
   *  of 4 (RoomService.MAX_PLAYERS caps at 4, but setReady()/startMatch()
   *  accept any players.length > 0 — several of this project's own
   *  existing tests exercise 2-player matches). Rather than fabricate a
   *  seat for a player who was never actually in the room — which
   *  would mean inventing an AI/placeholder identity, explicitly out of
   *  this sprint's scope ("DO NOT implement AI") — this function maps
   *  only the seats that have a REAL player. A 2-player match gets
   *  exactly seats p1/p2; p3/p4 simply do not exist in the map. Every
   *  seat-owning check elsewhere in this file (submitBid()) already
   *  reads seat ids from THIS map's own keys, never from a hardcoded
   *  four-seat assumption, so this generalizes correctly. */
  function buildSeatMap(players) {
    var seats = {};
    players.forEach(function (uid, i) {
      if (i < SEAT_IDS.length) seats[SEAT_IDS[i]] = uid;
    });
    return seats;
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
   *  instead. See docs/implementation/MatchInitialization.md.
   *
   *  Sprint 3.8, Tasks 1-3: also establishes `seats` (Task 1), `version`
   *  (Task 2 — starts at 1, meaning "the document as originally
   *  created"; every accepted gameplay write increments it by exactly
   *  1), and the minimal bidding-sync sub-state `biddingOpen`/`bids`/
   *  `lastBidSeat` (Task 3) — one null-valued bid slot per real seat.
   *  This does NOT implement bidding RULES (legal bid values, auction
   *  resolution, trump declaration, Dash/With/estimation semantics —
   *  all of that remains bidding-engine.js's job, untouched this
   *  sprint) — only the transport/sync shape for "one opaque bid value
   *  per seat, submitted at most once, while bidding is open." See
   *  docs/architecture/MatchSynchronization.md and this file's
   *  submitBid() below. */
  function buildInitialMatchDoc(roomId, room) {
    var players = (room.players || []).slice();
    var dealerUid = (room.creator && players.indexOf(room.creator) !== -1) ? room.creator : players[0];
    var seats = buildSeatMap(players);
    var bids = {};
    Object.keys(seats).forEach(function (seatId) { bids[seatId] = null; });
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
      seats: seats,
      version: 1,
      biddingOpen: true,
      bids: bids,
      lastBidSeat: null,
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

  /** Sprint 3.8, Task 7: a `.reason` machine-checkable code alongside
   *  the human-readable `.message` — a "structured error," not just
   *  free text callers would have to regex-match (the older
   *  established pattern elsewhere in this codebase's tests). */
  function bidError(reason, message) {
    var err = new Error(message);
    err.reason = reason;
    return err;
  }

  function currentUid() {
    return (global.SessionService && typeof global.SessionService.getCurrentUser === "function" && global.SessionService.getCurrentUser())
      ? global.SessionService.getCurrentUser().uid
      : null;
  }

  /** Sprint 3.8, Task 3 (Submit Bid): the ONE public API for submitting
   *  a bid, exactly as the brief specifies — `submitBid(matchId, seatId,
   *  bid)`. Deliberately NOT `submitBid(matchId, uid, bid)` (the
   *  original Sprint 2.7 speculative stub's signature, never
   *  implemented until now) — `uid` is never a parameter a caller
   *  supplies, for the same reason `SessionService.setCurrentMatchId()`
   *  takes no `uid` parameter (see this file's Sprint 3.4.1 note):
   *  there is no argument anywhere in this call for a caller (buggy or
   *  malicious) to misuse to submit AS another player. The calling
   *  client's own uid is looked up internally via SessionService and
   *  compared against `seats[seatId]` — the SAME authority
   *  `firestore.rules`' `isValidBidSubmission()` independently
   *  re-checks server-side (see firestore.rules and
   *  docs/architecture/SecurityArchitecture.md's Sprint 3.8 section) —
   *  neither layer trusts the other alone, this project's established
   *  principle since Sprint 3.4.1.
   *
   *  `bid` is stored exactly as passed — an OPAQUE payload. This
   *  function does not validate bid legality (a legal trick count, a
   *  legal Dash/With/estimation shape, whose turn it actually is
   *  within an auction) — none of that is implemented here, per this
   *  sprint's explicit "only synchronize bidding, do not modify
   *  Estimation rules" scope. It only enforces WHO may write WHERE and
   *  WHEN — see the ordered checks below, each one independently
   *  tested (see tests/submit-bid.test.cjs).
   *
   *  Runs inside a real Firestore transaction — Task 2's optimistic
   *  concurrency (an app-level `version` field, incremented by exactly
   *  1 per accepted write, independently re-checked by
   *  firestore.rules) layers on TOP of Firestore's own transaction
   *  retry-on-conflict, not instead of it: if two calls race for the
   *  same document, Firestore itself serializes them by re-running
   *  whichever one loses the race with a fresh read — this function's
   *  own checks (already-bid, seat ownership, bidding-open) are
   *  re-evaluated against that fresh read on every retry, so a losing
   *  racer for the SAME seat correctly fails with ALREADY_BID rather
   *  than silently overwriting the winner. See Task 6 in
   *  docs/reviews/SynchronizationReport_3.8.md for the full account of
   *  both the same-seat and different-seat concurrent cases. */
  function submitBid(matchId, seatId, bid) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "submitBid: matchId is required."));
    if (!seatId) return Promise.reject(bidError("INVALID_ARGUMENT", "submitBid: seatId is required."));
    if (bid === undefined) return Promise.reject(bidError("INVALID_ARGUMENT", "submitBid: bid is required."));
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "submitBid: no signed-in user."));
    var matchRef = db().collection("matches").doc(matchId);
    return db().runTransaction(function (tx) {
      return tx.get(matchRef).then(function (snap) {
        if (!snap.exists) throw bidError("MATCH_NOT_FOUND", "submitBid: match '" + matchId + "' was not found.");
        var match = snap.data();
        var seats = match.seats || {};
        if (!Object.prototype.hasOwnProperty.call(seats, seatId)) {
          throw bidError("UNKNOWN_SEAT", "submitBid: seat '" + seatId + "' does not exist in this match.");
        }
        if (seats[seatId] !== callingUid) {
          throw bidError("PERMISSION_DENIED", "submitBid: you do not own seat '" + seatId + "'.");
        }
        if (match.biddingOpen !== true) {
          throw bidError("BIDDING_CLOSED", "submitBid: bidding is closed for this match.");
        }
        var bids = Object.assign({}, match.bids);
        if (bids[seatId] != null) {
          throw bidError("ALREADY_BID", "submitBid: seat '" + seatId + "' has already submitted a bid.");
        }
        bids[seatId] = bid;
        var seatIds = Object.keys(seats);
        var allSubmitted = seatIds.length > 0 && seatIds.every(function (s) { return bids[s] != null; });
        var nextVersion = (match.version || 0) + 1;
        var patch = {
          bids: bids,
          biddingOpen: !allSubmitted,
          version: nextVersion,
          lastBidSeat: seatId,
          updatedAt: serverTimestamp()
        };
        tx.update(matchRef, patch);
        return { matchId: matchId, seatId: seatId, bid: bid, version: nextVersion, biddingOpen: !allSubmitted, allSubmitted: allSubmitted };
      });
    });
  }

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
        lastPublishedData: null, lastVersion: null, reconnectAttempt: 0, reconnectTimer: null,
        terminalError: null
      };
      attachListener(matchId, entry);
    } else {
      entry.listeners.push(callback);
      // A late joiner (e.g. a second local caller, or the SAME caller
      // subscribing again) gets the current known state immediately —
      // matching the original per-call "immediate snapshot" contract —
      // instead of waiting on a Firestore round trip it doesn't need.
      // If this subscription already hit a non-retryable error, the
      // late joiner learns that immediately too, instead of silently
      // waiting on a reconnect that will never be attempted.
      if (entry.terminalError) safeInvoke(callback, entry.hasPublished ? entry.lastPublishedData : null, entry.terminalError);
      else if (entry.hasPublished) safeInvoke(callback, entry.lastPublishedData, null);
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
