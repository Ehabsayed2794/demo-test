/* ════════════════════════════════════════════════════════════════════
   Estimation — MatchAdapter (Sprint 3.9: Engine Adapter Layer)

   The ONLY file in this codebase permitted to know BOTH the Firestore
   match-document schema (docs/architecture/FirestoreSchema.md) AND the
   gameplay engine's schema (docs/architecture/GameEngine.md /
   design-ui/engine/session.js's GameSession). Every other file must
   stay on its own side of that line:
     - design-ui/match-service.js knows Firestore. It has never
       required/referenced GameSession or any engine file, and this
       sprint does not change that.
     - design-ui/engine/session.js (GameSession) and every other engine
       file (Dealer, Deck, Cards, bidding-engine.js, table-engine.js,
       scoring-engine.js) know the engine's own seat-id (`p1`..`p4`)
       world. This sprint does not modify any of them.
   This file is the seam. It translates identities — nothing else.

   HONEST PRE-EXISTING EXCEPTION, recorded rather than hidden: Sprint
   3.7 already added `GameSession.subscribeToRemoteMatch()`, which
   calls `global.MatchService.subscribeToMatch()` directly — GameSession
   referencing MatchService's global predates this adapter and this
   sprint's explicit "DO NOT rewrite GameSession" instruction means it
   is not undone here. What Sprint 3.7 did NOT do is interpret the
   Firestore document's shape — `GameSession.getRemoteMatch()` returns
   it completely raw, uid-keyed, uninterpreted. This adapter is the
   first and only code that actually INTERPRETS that shape (seats,
   dealer/turn-as-uid, bidding sub-state) — see
   docs/architecture/EngineAdapter.md's "Data ownership" section for
   the full account of this distinction.

   See docs/architecture/EngineAdapter.md for the full design: which
   functions are pure (Task 4 — matchDocToEngineSnapshot /
   engineSnapshotToMatchPatch), which one is the deliberate, documented
   exception with real side effects (Task 3 — bootstrapGameSession),
   and what this file explicitly does NOT do (no dealing, no scoring,
   no bid/card/trick synchronization, no turn authority — see this
   sprint's own stop list).
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  // Mirrors design-ui/engine/session.js's own CANONICAL_ORDER and
  // design-ui/match-service.js's own SEAT_IDS exactly — kept as an
  // independent local constant, not a shared import, for the same
  // "zero hard dependency between files" reason those two already
  // each keep their own copy. This is the one piece of "engine
  // knowledge" (there are 4 canonical seats, named this way) this
  // adapter needs merely to produce a DETERMINISTIC iteration order —
  // it is not gameplay logic.
  var SEAT_ORDER = ["p1", "p2", "p3", "p4"];

  /** Structured error — same shape as match-service.js's bidError(),
   *  reused here for consistency across this codebase's newer files:
   *  a real Error with a machine-checkable `.reason`. */
  function adapterError(reason, message) {
    var err = new Error(message);
    err.reason = reason;
    return err;
  }

  /** Deterministic seat-key ordering: canonical seats first (p1..p4,
   *  in that order), then any non-canonical key (should never occur
   *  given firestore.rules' isValidSeatMap() — see
   *  docs/architecture/SeatIdentityModel.md — but handled without
   *  throwing, since this file never trusts its input blindly) sorted
   *  alphabetically after them. This is what makes uidToSeat()'s
   *  behavior well-defined even against malformed, duplicate-uid data
   *  (see that function's own comment) instead of depending on
   *  whatever arbitrary order `Object.keys()` happens to produce. */
  function sortedSeatKeys(seats) {
    return Object.keys(seats || {}).sort(function (a, b) {
      var ia = SEAT_ORDER.indexOf(a), ib = SEAT_ORDER.indexOf(b);
      if (ia === -1 && ib === -1) return a < b ? -1 : (a > b ? 1 : 0);
      if (ia === -1) return 1;
      if (ib === -1) return -1;
      return ia - ib;
    });
  }

  // ── Task 2: Seat Resolution ─────────────────────────────────────
  // Every function below reads ONLY matchDoc.seats — never a cached
  // copy, never a second map maintained anywhere else in this file or
  // any other. Call these again with a fresher matchDoc to get a
  // fresher answer; nothing here is memoized across calls.

  /** uid -> seat. Returns null for an unknown uid (never throws for a
   *  normal lookup miss — an unrecognized uid is expected, ordinary
   *  input, not an error condition). If (only possible via malformed/
   *  tampered data — firestore.rules' isValidSeatMap() prevents this
   *  from ever happening through a legitimate write) more than one
   *  seat maps to the same uid, returns whichever seat sorts first per
   *  sortedSeatKeys() — deterministic, not arbitrary, and documented
   *  here rather than left as an implicit `Object.keys()` ordering
   *  accident. */
  function uidToSeat(matchDoc, uid) {
    if (!matchDoc || !matchDoc.seats || uid == null) return null;
    var keys = sortedSeatKeys(matchDoc.seats);
    for (var i = 0; i < keys.length; i++) {
      if (matchDoc.seats[keys[i]] === uid) return keys[i];
    }
    return null;
  }

  /** seat -> uid. Returns null for a seat name that doesn't exist in
   *  THIS match's own seats map (e.g. "p3"/"p4" for a 2-player match
   *  — see docs/architecture/SeatIdentityModel.md's "Creation"
   *  section — or any name outside p1..p4 entirely). Never throws. */
  function seatToUid(matchDoc, seatId) {
    if (!matchDoc || !matchDoc.seats || !seatId) return null;
    return Object.prototype.hasOwnProperty.call(matchDoc.seats, seatId) ? matchDoc.seats[seatId] : null;
  }

  /** seat -> a MINIMAL player identity descriptor: `{ seatId, uid }`.
   *  Deliberately NOT the engine's rich mock-player shape (name,
   *  isAI, rank, coins, ... — see session.js's mockPlayers()) — that
   *  data does not exist on the Firestore match document at all (it
   *  lives in players/{uid}, a separate collection PlayerService
   *  owns) and fetching it would mean a new PlayerService dependency,
   *  outside this sprint's "translate identities only" scope. See
   *  docs/architecture/EngineAdapter.md's "Future extension points"
   *  for where real profile enrichment would plug in later. Returns
   *  null if the seat doesn't exist for this match. */
  function seatToPlayer(matchDoc, seatId) {
    var uid = seatToUid(matchDoc, seatId);
    if (uid == null) return null;
    return { seatId: seatId, uid: uid };
  }

  /** The reverse of seatToPlayer() — accepts either a plain uid
   *  string, or a player-shaped object with a `.uid` or `.id` field
   *  (so a caller can pass either a raw uid or something this
   *  adapter's own seatToPlayer() just returned). Returns null if the
   *  input names no seat in this match. */
  function playerToSeat(matchDoc, player) {
    var uid = null;
    if (typeof player === "string") uid = player;
    else if (player && typeof player === "object") uid = player.uid != null ? player.uid : player.id;
    if (uid == null) return null;
    return uidToSeat(matchDoc, uid);
  }

  // ── Task 4: State Translation (PURE — no I/O, no GameSession/
  // MatchService calls, no mutation of the input, no side effects of
  // any kind). Each function only ever reads its own argument and
  // returns a brand-new plain object. ─────────────────────────────

  /** Firestore match document -> engine-facing snapshot. Translates
   *  every uid-keyed identity field this adapter understands into its
   *  seat-keyed equivalent, and carries every other field this
   *  adapter is responsible for straight through, unmodified. Throws
   *  `INVALID_MATCH_DOC` for a non-object input — everything else
   *  (a missing `seats`, a `dealer` uid that isn't in `seats`, etc.)
   *  degrades to `null` fields rather than throwing, since a match
   *  document with gaps (e.g. seats not established yet) is normal,
   *  expected input, not a caller error. Deliberately does NOT touch
   *  `bidding-engine.js`'s own richer `biddingState` shape, and does
   *  NOT decide what a null `dealerSeat`/`turnSeat` MEANS gameplay-wise
   *  — it only reports what it could and couldn't resolve. See
   *  docs/architecture/EngineAdapter.md for the full field-by-field
   *  rationale. */
  function matchDocToEngineSnapshot(matchDoc) {
    if (!matchDoc || typeof matchDoc !== "object") {
      throw adapterError("INVALID_MATCH_DOC", "matchDocToEngineSnapshot: matchDoc must be a plain object.");
    }
    var seats = matchDoc.seats || {};
    var bids = matchDoc.bids || {};
    var bidsBySeat = {};
    Object.keys(bids).forEach(function (seatId) { bidsBySeat[seatId] = bids[seatId]; });
    return {
      players: (matchDoc.players || []).slice(),
      seats: Object.assign({}, seats),
      dealerSeat: matchDoc.dealer != null ? uidToSeat(matchDoc, matchDoc.dealer) : null,
      turnSeat: matchDoc.turn != null ? uidToSeat(matchDoc, matchDoc.turn) : null,
      roundNumber: typeof matchDoc.currentRound === "number" ? matchDoc.currentRound : null,
      version: typeof matchDoc.version === "number" ? matchDoc.version : null,
      biddingOpen: typeof matchDoc.biddingOpen === "boolean" ? matchDoc.biddingOpen : null,
      bidsBySeat: bidsBySeat,
      lastBidSeat: matchDoc.lastBidSeat != null ? matchDoc.lastBidSeat : null
    };
  }

  /** The exact reverse of matchDocToEngineSnapshot() — engine-facing
   *  snapshot -> a Firestore-compatible patch (uid-keyed again).
   *  `seats` is carried through from the snapshot itself (never
   *  re-derived, never guessed) so this function needs no separate
   *  matchDoc argument to reverse `dealerSeat`/`turnSeat` back into
   *  uids — this is what makes the round trip exact and independently
   *  testable (see Task 6 / tests/match-adapter.test.cjs). Throws
   *  `INVALID_SNAPSHOT` for a non-object input; every other field
   *  degrades to `null` exactly like the forward direction does. */
  function engineSnapshotToMatchPatch(snapshot) {
    if (!snapshot || typeof snapshot !== "object") {
      throw adapterError("INVALID_SNAPSHOT", "engineSnapshotToMatchPatch: snapshot must be a plain object.");
    }
    var seats = snapshot.seats || {};
    return {
      players: (snapshot.players || []).slice(),
      seats: Object.assign({}, seats),
      dealer: snapshot.dealerSeat != null && seats[snapshot.dealerSeat] != null ? seats[snapshot.dealerSeat] : null,
      turn: snapshot.turnSeat != null && seats[snapshot.turnSeat] != null ? seats[snapshot.turnSeat] : null,
      currentRound: snapshot.roundNumber != null ? snapshot.roundNumber : null,
      version: snapshot.version != null ? snapshot.version : null,
      biddingOpen: snapshot.biddingOpen != null ? snapshot.biddingOpen : null,
      bids: Object.assign({}, snapshot.bidsBySeat || {}),
      lastBidSeat: snapshot.lastBidSeat != null ? snapshot.lastBidSeat : null
    };
  }

  // ── Task 3: Engine Bootstrap (the ONE function in this file with
  // documented side effects — everything above is pure). ──────────

  /** Input: a Firestore match document. Output: the same document,
   *  translated (via matchDocToEngineSnapshot(), reused rather than
   *  duplicated) and applied to the LOCAL GameSession using ONLY
   *  GameSession's existing, unmodified public setters
   *  (`setRound`/`setDealer`/`setTurn`) — no new GameSession field, no
   *  new sessionStorage key, nothing GameSession didn't already
   *  expose before this sprint. Returns the translated snapshot (the
   *  same shape matchDocToEngineSnapshot() produces) so a caller can
   *  inspect exactly what was (and wasn't) resolved, rather than
   *  guessing from GameSession's own state afterward.
   *
   *  Deliberately conservative about WHAT it writes into GameSession —
   *  see docs/architecture/EngineAdapter.md's "Non-responsibilities"
   *  section for the full reasoning, summarized here: `dealerSeat`/
   *  `turnSeat`/`roundNumber` are plain primitives with no pre-existing
   *  rich shape to violate, so translating and setting them is safe.
   *  `players` (Firestore's flat uid array) and the bidding sub-state
   *  (`biddingOpen`/`bidsBySeat`/`lastBidSeat`) are NOT written into
   *  GameSession at all — `session.players` has an established rich
   *  shape (name/isAI/rank/...) this adapter cannot correctly populate
   *  without inventing data that doesn't exist on the match document,
   *  and `session.biddingState` has an established shape OWNED by
   *  `bidding-engine.js`'s own reducer that this adapter's raw,
   *  differently-shaped Firestore values would corrupt if written
   *  into it directly. Both remain available on the RETURNED snapshot
   *  for a future, correctly-scoped integration to consume — this is
   *  "load bidding state" (a one-time read, satisfied), not
   *  "synchronize bids" (an ongoing two-way sync, explicitly out of
   *  this sprint's stop list).
   *
   *  Throws `GAME_SESSION_UNAVAILABLE` if `GameSession` isn't loaded
   *  on this page — never silently no-ops, since a caller invoking
   *  bootstrap on purpose needs to know it didn't happen. Propagates
   *  `INVALID_MATCH_DOC` from matchDocToEngineSnapshot() unchanged for
   *  a malformed `matchDoc`. Does NOT deal cards, does NOT resolve
   *  bids, does NOT run any gameplay logic — see this file's own
   *  header comment. */
  function bootstrapGameSession(matchDoc) {
    var snapshot = matchDocToEngineSnapshot(matchDoc);
    if (!global.GameSession || typeof global.GameSession.setRound !== "function" ||
        typeof global.GameSession.setDealer !== "function" || typeof global.GameSession.setTurn !== "function") {
      throw adapterError("GAME_SESSION_UNAVAILABLE", "bootstrapGameSession: GameSession is not available on this page.");
    }
    var GameSession = global.GameSession;
    if (snapshot.roundNumber != null) GameSession.setRound({ number: snapshot.roundNumber });
    if (snapshot.dealerSeat != null) GameSession.setDealer(snapshot.dealerSeat);
    if (snapshot.turnSeat != null) GameSession.setTurn(snapshot.turnSeat);
    return snapshot;
  }

  global.MatchAdapter = {
    uidToSeat: uidToSeat,
    seatToUid: seatToUid,
    seatToPlayer: seatToPlayer,
    playerToSeat: playerToSeat,
    matchDocToEngineSnapshot: matchDocToEngineSnapshot,
    engineSnapshotToMatchPatch: engineSnapshotToMatchPatch,
    bootstrapGameSession: bootstrapGameSession
  };
})(window);
