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

   Sprint 4.0 (Online Bidding Synchronization: Authority Layer) — this
   sprint activates the pipeline Sprint 3.9 only prepared:
     Player -> submitBid() -> Firestore -> MatchService listener ->
     Engine Adapter -> bidding-engine.js -> GameSession -> UI
   Two new functions, both additive:
     - applyRemoteBid(matchId, matchDoc) [Task 2/3/4]: version-gated
       (strictly greater than, no equality, no rollback — Task 3),
       duplicate/stale/malformed-rejecting (Task 2/4), translation from
       a raw Firestore bid value into EXACTLY ONE bidding-engine.js
       action shape: `SubmitFinalEstimate`. This is a deliberate,
       documented scope boundary, not an oversight — see this
       function's own comment and docs/architecture/EngineAdapter.md's
       Sprint 4.0 section for why DASH/AUCTION/CONFIRM actions (which
       need a suit, an isPass flag, a decision type — shapes Firestore's
       existing `bids: {seatId: rawNumber}` schema cannot represent
       without a schema change explicitly out of this sprint's "do not
       modify Firestore Rules / do not duplicate bidding rules" scope)
       are NOT wired here. `bidding-engine.js` is called, never
       rewritten, never consulted for anything this file decides for
       itself — every legality/ordering/phase decision is made BY
       calling into the real, unmodified engine and reading its own
       response, never re-implemented here.
     - startBidSync(matchId) [Task 1]: the one-call wiring of the FULL
       pipeline above — subscribes via the EXISTING, unmodified
       `MatchService.subscribeToMatch()` (reusing its own ref-counted
       listener, ordering guard, and reconnect logic verbatim — no
       second listener, no duplicated sync logic, per Task 1's "no
       duplicate logic") and pipes every delivery through
       applyRemoteBid(). This is the ONLY function in this codebase
       that connects Firestore's live sync to `bidding-engine.js` —
       see Task 5 / "Engine Isolation" below.

   Engine Isolation (Task 5): `design-ui/match-service.js` still never
   references `GameSession` or any engine file (unchanged, confirmed by
   this sprint's own forbidden-scope sweep) — it owns persistence only,
   exactly as the brief's Architecture Rules require. This file is the
   ONLY code that calls `global.BiddingEngine.emit()` on behalf of a
   remote update. Nothing added this sprint moves bid legality, bid
   order, bid validation, or auction state out of `bidding-engine.js` —
   every one of those decisions still lives there, unchanged, and this
   file only ever reads its response.
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

  // ── Task 2/3/4 (Sprint 4.0): Remote Bid Application ─────────────
  // matchId -> the highest matchDoc.version this adapter has ever
  // successfully translated for that match. This is the adapter's OWN
  // version gate — a deliberate, documented instance of "defense in
  // depth" (this project's established principle since Sprint 3.4.1),
  // NOT a duplication of MatchService.subscribeToMatch()'s own,
  // separate ordering guard (Sprint 3.7, activated Sprint 3.8): that
  // guard protects Firestore DELIVERY (does this client even get told
  // about a stale/duplicate snapshot at all); THIS one protects the
  // ENGINE specifically (given a delivery that already passed
  // MatchService's guard — or, in a test, one that didn't — should the
  // engine be re-driven right now). Two different concerns, two
  // independent checks, same principle. See
  // docs/architecture/EngineAdapter.md's Sprint 4.0 section.
  var lastAppliedVersionByMatch = {};

  /** Task 2 (Remote Bid Application): translates the latest accepted
   *  bid on a Firestore match document into exactly one
   *  `bidding-engine.js` action — `SubmitFinalEstimate` — and nothing
   *  else. Never mutates Firestore (this function never references
   *  `db()`/`MatchService`'s write path at all); every effect flows
   *  ONE way, into the local `GameSession`, and only ever THROUGH the
   *  real, unmodified `bidding-engine.js` reducer — this file never
   *  calls a `GameSession` setter directly for bid data, unlike
   *  `bootstrapGameSession()` above, which is deliberately different
   *  (see that function's own comment).
   *
   *  Returns a small, structured result object — `{ applied: boolean,
   *  reason: string, ...}` — for every path, including every rejection,
   *  so a caller (or a test) never has to guess why nothing happened.
   *  Never throws for ordinary "nothing to do" cases (a malformed
   *  snapshot, a stale version, a phase mismatch) — those are expected,
   *  routine inputs in a live sync pipeline, not caller errors.
   *
   *  WHY ONLY `SubmitFinalEstimate` (Task 1's "no duplicate logic," Task
   *  5's "no gameplay rules encoded here"): `MatchService.submitBid()`'s
   *  Firestore schema (Sprint 3.8, hardened 3.8.1) stores exactly one
   *  opaque, generically-range-validated integer (0-13) per seat — the
   *  same shape as a final trick estimate, and ONLY that shape.
   *  `bidding-engine.js`'s DASH/AUCTION/CONFIRM actions need a suit, an
   *  isPass flag, or a decision boolean — none of which this schema can
   *  represent without a schema change, which is explicitly out of this
   *  sprint's "do not modify Firestore Rules / do not duplicate bidding
   *  rules" scope. Translating a bare integer into one of THOSE action
   *  shapes would mean this file GUESSING what the number means
   *  (inventing a suit? assuming it's never a pass?) — exactly the
   *  "duplicated/invented gameplay rule" this sprint forbids. Wiring
   *  those phases is future work, once the schema question is
   *  deliberately, separately solved — see
   *  docs/architecture/EngineAdapter.md's "Future extension points." */
  function applyRemoteBid(matchId, matchDoc) {
    // Task 2: reject malformed snapshots outright, before touching
    // any version bookkeeping or the engine at all.
    if (!matchId) return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    if (!matchDoc || typeof matchDoc !== "object" || Array.isArray(matchDoc)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }
    if (typeof matchDoc.version !== "number" || !Number.isFinite(matchDoc.version)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }

    // Task 3: strict greater-than only. Equal (a duplicate delivery of
    // the exact same accepted state — Task 4) and lesser (a stale or
    // rolled-back delivery) are both rejected the same way: ignored,
    // never applied, never treated as an error.
    var lastVersion = Object.prototype.hasOwnProperty.call(lastAppliedVersionByMatch, matchId)
      ? lastAppliedVersionByMatch[matchId] : null;
    if (lastVersion != null && matchDoc.version <= lastVersion) {
      return { applied: false, reason: matchDoc.version === lastVersion ? "DUPLICATE_VERSION" : "STALE_VERSION" };
    }

    var seatId = matchDoc.lastBidSeat;
    if (!seatId || !matchDoc.bids || typeof matchDoc.bids !== "object") {
      // A structurally valid-but-empty snapshot (e.g. the document
      // immediately after creation, before any bid exists yet) is not
      // an error — there is simply nothing to translate. Record the
      // version so an identical future delivery of this same "nothing
      // to do yet" state is correctly recognized as a duplicate, not
      // silently re-evaluated forever.
      lastAppliedVersionByMatch[matchId] = matchDoc.version;
      return { applied: false, reason: "NO_BID_TO_APPLY" };
    }
    var bidValue = matchDoc.bids[seatId];
    if (bidValue == null) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }

    if (!global.BiddingEngine || typeof global.BiddingEngine.emit !== "function" ||
        typeof global.BiddingEngine.getState !== "function") {
      return { applied: false, reason: "ENGINE_UNAVAILABLE" };
    }
    var engineState = global.BiddingEngine.getState();
    if (!engineState || engineState.subPhase !== "ESTIMATES") {
      // Not a rejection of the bid — this update simply isn't (yet, or
      // ever, for this schema) something this adapter's one supported
      // translation applies to. See this function's own header comment.
      lastAppliedVersionByMatch[matchId] = matchDoc.version;
      return { applied: false, reason: "PHASE_MISMATCH" };
    }
    if (engineState.waitingFor !== seatId) {
      lastAppliedVersionByMatch[matchId] = matchDoc.version;
      return { applied: false, reason: "NOT_THIS_SEATS_TURN" };
    }
    // Task 4 (Duplicate Protection) / "local bid" case: if the local
    // engine already has ANY bid recorded for this seat — whether
    // because THIS adapter already applied it on a previous call, or
    // because the local player's own UI already called
    // `BiddingEngine.emit()` directly for their OWN seat before this
    // echo of their own write came back through Firestore sync — never
    // re-emit. This is what makes "receiving the same snapshot twice
    // must not... replay engine state" true even across a full
    // subscribe/unsubscribe/resubscribe cycle (the version gate above
    // alone would not catch this specific case: a genuinely NEWER
    // version whose bid the engine already independently knows about).
    if (engineState.bids && engineState.bids[seatId] != null) {
      lastAppliedVersionByMatch[matchId] = matchDoc.version;
      return { applied: false, reason: "ALREADY_APPLIED_LOCALLY" };
    }

    // The ONLY call in this codebase's sync path into bidding-engine.js.
    // Every legality/ordering decision from here is the real engine's,
    // not this file's — this function only ever reads the response.
    var engineResult = global.BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: seatId, tricks: bidValue });
    lastAppliedVersionByMatch[matchId] = matchDoc.version;
    if (!engineResult || engineResult.rejected) {
      return { applied: false, reason: "ENGINE_REJECTED", engineReason: engineResult && engineResult.reason };
    }
    return { applied: true, seatId: seatId, tricks: bidValue, version: matchDoc.version };
  }

  /** Test/diagnostic-only accessor — never used by any production
   *  code path, only so a test can observe the adapter's own internal
   *  version-gate state without reaching into a closure variable it
   *  has no other way to see. Returns `null` if nothing has been
   *  applied yet for this matchId. */
  function getLastAppliedVersion(matchId) {
    return Object.prototype.hasOwnProperty.call(lastAppliedVersionByMatch, matchId) ? lastAppliedVersionByMatch[matchId] : null;
  }

  /** Test/diagnostic-only reset — clears this adapter's own version-
   *  gate bookkeeping for one matchId (or, with no argument, all of
   *  them). No production code path ever needs to call this — a real
   *  page load simply starts with a fresh, empty registry already.
   *  Exists purely so independent tests don't leak state into each
   *  other through this module's module-level closure. */
  function resetSyncState(matchId) {
    if (matchId) delete lastAppliedVersionByMatch[matchId];
    else lastAppliedVersionByMatch = {};
  }

  // ── Task 1: Bid Sync Pipeline (the full wiring, in one call) ─────

  /** Implements the complete pipeline the brief specifies:
   *    Player -> submitBid() -> Firestore -> MatchService listener ->
   *    Engine Adapter -> bidding-engine.js -> GameSession -> UI
   *  by subscribing through `MatchService.subscribeToMatch()` — the
   *  EXISTING, unmodified method (Sprint 3.7, hardened 3.7.1/3.8) —
   *  and piping every delivery through `applyRemoteBid()`. No second
   *  listener is created (`subscribeToMatch()`'s own ref-counted
   *  registry is reused exactly as any other caller would use it —
   *  see docs/architecture/MatchSynchronization.md); no sync logic is
   *  reimplemented here. Fail-open on a delivery error (consistent
   *  with every other `subscribeToMatch()` caller in this codebase —
   *  there is nothing to translate on an error-only delivery, and the
   *  last known good state, if any, is preserved by MatchService's
   *  own guarantees, unchanged).
   *
   *  Returns the SAME unsubscribe function `subscribeToMatch()` itself
   *  returns — calling it tears down the underlying Firestore listener
   *  exactly as it always has; this function adds no new cleanup
   *  surface of its own. Throws `MATCH_SERVICE_UNAVAILABLE` if
   *  `MatchService` isn't loaded on this page — never silently no-ops. */
  function startBidSync(matchId) {
    if (!matchId) throw adapterError("INVALID_ARGUMENT", "startBidSync: matchId is required.");
    if (!global.MatchService || typeof global.MatchService.subscribeToMatch !== "function") {
      throw adapterError("MATCH_SERVICE_UNAVAILABLE", "startBidSync: MatchService is not available on this page.");
    }
    return global.MatchService.subscribeToMatch(matchId, function (data, err) {
      if (err || !data) return;
      applyRemoteBid(matchId, data);
    });
  }

  global.MatchAdapter = {
    uidToSeat: uidToSeat,
    seatToUid: seatToUid,
    seatToPlayer: seatToPlayer,
    playerToSeat: playerToSeat,
    matchDocToEngineSnapshot: matchDocToEngineSnapshot,
    engineSnapshotToMatchPatch: engineSnapshotToMatchPatch,
    bootstrapGameSession: bootstrapGameSession,
    applyRemoteBid: applyRemoteBid,
    startBidSync: startBidSync,
    getLastAppliedVersion: getLastAppliedVersion,
    resetSyncState: resetSyncState
  };
})(window);
