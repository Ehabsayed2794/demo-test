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

   Sprint 4.1 (Turn Authority & Remote Play Validation) — three more
   additive functions, none of them touching `bidding-engine.js` or any
   other engine file:
     - applyRemoteTurn(matchId, matchDoc) [Task 2]: keeps
       `GameSession`'s top-level `turnId` field (the one `getTurn()`/
       `setTurn()` manage — a Firestore-facing MIRROR, distinct from
       `GameSession.getBiddingState().turnId`, the bidding-phase-
       specific "who bids next" `bidding-engine.js`'s own reducer
       already tracks and persists correctly, unchanged since Sprint
       3.6) continuously synchronized with `matches/{matchId}.turn` —
       one-shot only through Sprint 3.9's `bootstrapGameSession()`,
       now ongoing. Gated by its OWN independent version registry
       (deliberately separate from `applyRemoteBid()`'s — see that
       function's own comment for why two independent gates, not one
       shared one, is correct here) plus a content-level idempotency
       check.
     - isLocalSeatsTurn(matchDoc, localSeat) / assertLocalTurn(matchDoc,
       localSeat) [Task 3]: the gate any FUTURE gameplay-write function
       (card play, once it exists) must call BEFORE attempting a write —
       "verify currentPlayer == localSeat... reject locally... do not
       send writes." Built and tested now, called by nothing yet, since
       no such write function exists — exactly the same "deliver the
       mechanism ahead of its first real caller" pattern Sprint 3.9's
       `bootstrapGameSession()` and Sprint 4.0's `applyRemoteBid()`
       already established successfully.
     - startTurnSync(matchId) [Task 1]: the turn-sync analog of Sprint
       4.0's `startBidSync()` — same reused, unmodified
       `MatchService.subscribeToMatch()`, same "no second listener"
       guarantee (Firestore ref-counts by matchId, not by which
       adapter-level function subscribed).

   Why Firestore never decides whose turn it is (restated here,
   verified in code, not just asserted): `applyRemoteTurn()` never
   computes, infers, or advances a turn value — it only ever COPIES
   whatever `matches/{matchId}.turn` already says into the local
   mirror, translated uid->seat. Nothing in this file, or in
   `MatchService`, ever decides "whose turn is next" — that remains
   entirely `bidding-engine.js`'s (and, once it exists, any future
   card-play engine's) job, computed offline and — for `bidding-engine.js`
   specifically — not even written back to `matches/{matchId}.turn` by
   any code in this codebase yet (see docs/architecture/EngineAdapter.md's
   Sprint 4.1 section for the honest account of this remaining gap).

   Sprint 4.2 (Online Card Synchronization: Engine Authority) — one
   more additive function, plus the pipeline wiring for it. Explicitly
   NOT trick resolution, NOT scoring, NOT winner detection — see this
   sprint's own stop list.
     - applyRemoteCard(matchId, matchDoc) [Task 2/4/5]: replays every
       NEW entry in `matches/{matchId}.cardLog` (an append-only log of
       `{seatId, card}` tuples — see match-service.js's submitCard())
       through `TableEngine.emit({type:"PlayCard", playerId, card})` —
       the ONLY `table-engine.js` action shape this sprint touches, and
       the ONLY call this function ever makes into that engine. Unlike
       `applyRemoteBid()` (which reacts to exactly one changed VALUE
       per accepted write), a card submission can legitimately deliver
       MULTIPLE new log entries in one snapshot (e.g. a late subscriber
       joining after several cards were already played, or a
       reconnect that missed a few deliveries) — so this function
       tracks its OWN "how many entries have I already replayed"
       counter (`lastAppliedCardCountByMatch`), in addition to (not
       instead of) the same document-`version` gate every other
       `applyRemote*` function already uses (its own independent
       registry, `lastAppliedCardVersionByMatch` — a THIRD gate,
       alongside bid's and turn's, none of them shared, for the same
       reason already documented at length in `applyRemoteTurn()`'s
       own comment above). `cardLog` entries are already SEAT-keyed
       (not uid-keyed) by the time they reach Firestore — the ONE
       uid->seat translation this sprint needs happens at WRITE time,
       inside `MatchService.submitCard()`, via a call into THIS file's
       own `uidToSeat()` (see that function's own comment in
       match-service.js) — so `applyRemoteCard()` itself needs no
       translation step at all beyond reading `entry.seatId` directly,
       exactly like `applyRemoteBid()` reads `matchDoc.lastBidSeat`
       directly. Never mutates Firestore; never calls any engine file
       other than `TableEngine`.
     - startCardSync(matchId) [pipeline wiring, same shape as
       startBidSync()/startTurnSync()]: reuses
       `MatchService.subscribeToMatch()` verbatim, pipes deliveries
       through `applyRemoteCard()`.
     - Task 3 ("Local Authority Validation... verify assertLocalTurn()")
       is explicitly NOT a new function this sprint — it is Sprint
       4.1's EXISTING `assertLocalTurn()`, reused verbatim, exactly as
       the brief names it by that exact identifier. No second turn-gate
       function was written. HONEST, PRE-EXISTING LIMITATION this
       reuse inherits, not introduced by this sprint: `assertLocalTurn()`
       checks `matches/{matchId}.turn` (or `GameSession.getTurn()`'s
       mirror of it) — the SAME top-level field Sprint 4.1 documented
       as not yet kept current during any phase past bidding, since
       nothing in this codebase writes a computed turn back into that
       field for EITHER the bidding phase's real turn-order OR
       table-engine.js's own (`GameSession.getPlayState().turnId`,
       a THIRD, still-separate turn-tracking field, analogous to
       `getBiddingState().turnId`, that `TableEngine.emit()` maintains
       correctly on its own but that nothing mirrors into the
       top-level field either). `assertLocalTurn()` works exactly as
       designed against whatever `matches/{matchId}.turn` says — the
       gap is that nothing populates it correctly during PLAY, same
       honest account as Sprint 4.1 gave for bidding, restated here
       rather than silently inherited. This is NOT a blocker for this
       sprint (Task 3 only asks that the EXISTING gate be consulted
       before a future write — it doesn't ask this sprint to fix that
       pre-existing gap, and doing so would mean writing a NEW turn-
       computation/write-back path, explicitly out of "DO NOT
       implement Turn Rotation"-adjacent scope).
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
    if (matchId) {
      delete lastAppliedVersionByMatch[matchId];
      delete lastAppliedTurnVersionByMatch[matchId];
      delete lastAppliedCardVersionByMatch[matchId];
      delete lastAppliedCardCountByMatch[matchId];
    } else {
      lastAppliedVersionByMatch = {};
      lastAppliedTurnVersionByMatch = {};
      lastAppliedCardVersionByMatch = {};
      lastAppliedCardCountByMatch = {};
    }
  }

  // ── Sprint 4.1, Task 2/3/4: Remote Turn Application ─────────────
  // matchId -> the highest matchDoc.version this adapter has ever
  // successfully translated INTO A TURN UPDATE for that match. This is
  // a SEPARATE, independent registry from `lastAppliedVersionByMatch`
  // above — deliberately not shared. Both `applyRemoteBid()` and
  // `applyRemoteTurn()` gate against the SAME underlying document
  // field (`matchDoc.version`), but they gate DIFFERENT engine effects
  // (a bid application vs. a turn-mirror update). A single shared
  // registry would mean whichever function ran first for a given
  // version silently blocks the other from ever seeing that version at
  // all — e.g. a snapshot that both advances the turn AND carries a new
  // bid would have only one of those two effects applied. Two
  // independent gates, each scoped to what it actually protects, is
  // the correct design here — see this file's own header comment.
  var lastAppliedTurnVersionByMatch = {};

  /** Task 2 (Remote Turn Application): keeps `GameSession`'s top-level
   *  `turnId` (the field `getTurn()`/`setTurn()` manage — NOT
   *  `GameSession.getBiddingState().turnId`, a completely separate,
   *  bidding-phase-specific field `bidding-engine.js`'s own reducer
   *  already owns and correctly maintains, untouched by this function)
   *  synchronized with whatever `matches/{matchId}.turn` (a uid)
   *  currently says. This function NEVER computes, infers, guesses, or
   *  advances a turn value of its own — it only ever COPIES the value
   *  Firestore already holds, translated uid -> seat. "Whose turn is
   *  next" remains entirely the gameplay engine's decision, computed
   *  offline, exactly as this file's header comment states.
   *
   *  Never mutates Firestore (no `db()`/`MatchService` write-path
   *  reference anywhere in this function) and never touches
   *  `bidding-engine.js` or any other engine file — the ONLY engine
   *  call this function ever makes is `GameSession.setTurn()`, an
   *  existing, unmodified public setter (Sprint 3.9's
   *  `bootstrapGameSession()` already establishes this exact setter as
   *  safe to call from this file).
   *
   *  Returns a small, structured result object for every path,
   *  including every rejection, exactly like `applyRemoteBid()` does.
   *  Never throws for ordinary "nothing to do" cases. */
  function applyRemoteTurn(matchId, matchDoc) {
    // Reject malformed snapshots outright, before touching any version
    // bookkeeping or GameSession at all.
    if (!matchId) return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    if (!matchDoc || typeof matchDoc !== "object" || Array.isArray(matchDoc)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }
    if (typeof matchDoc.version !== "number" || !Number.isFinite(matchDoc.version)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }

    // Strict greater-than only — same rule as applyRemoteBid()'s own
    // version gate, applied here against this function's OWN
    // independent registry. Equal (duplicate) and lesser (stale/
    // rollback) versions are both rejected the same way: ignored,
    // never applied, never treated as an error.
    var lastVersion = Object.prototype.hasOwnProperty.call(lastAppliedTurnVersionByMatch, matchId)
      ? lastAppliedTurnVersionByMatch[matchId] : null;
    if (lastVersion != null && matchDoc.version <= lastVersion) {
      return { applied: false, reason: matchDoc.version === lastVersion ? "DUPLICATE_VERSION" : "STALE_VERSION" };
    }

    if (matchDoc.turn == null) {
      // A structurally valid snapshot with no turn established yet
      // (e.g. immediately after match creation, before bootstrap) is
      // not an error — there is simply nothing to translate yet.
      // Record the version so a future identical delivery of this same
      // "no turn yet" state is recognized as a duplicate, not silently
      // re-evaluated forever.
      lastAppliedTurnVersionByMatch[matchId] = matchDoc.version;
      return { applied: false, reason: "NO_TURN_TO_APPLY" };
    }

    var turnSeat = uidToSeat(matchDoc, matchDoc.turn);
    if (turnSeat == null) {
      // A `turn` uid that doesn't resolve to any seat in THIS match's
      // own seats map is malformed/corrupted input, not ordinary
      // "nothing to do yet" input — deliberately NOT recorded into the
      // version registry, so a subsequent, well-formed delivery of the
      // very same version (which cannot happen in practice, since
      // version is monotonic per write, but is exactly what Task 6's
      // "adapter corruption" tests probe for) is not silently treated
      // as already-seen.
      return { applied: false, reason: "UNKNOWN_TURN_SEAT" };
    }

    if (!global.GameSession || typeof global.GameSession.getTurn !== "function" ||
        typeof global.GameSession.setTurn !== "function") {
      return { applied: false, reason: "GAME_SESSION_UNAVAILABLE" };
    }
    var GameSession = global.GameSession;

    // Task 4 (Duplicate Protection), content-level layer: if the local
    // mirror already agrees with this seat, there is genuinely nothing
    // to do — covers the same "genuinely NEWER version, but nothing
    // actually changed" case applyRemoteBid()'s own content-level check
    // covers for bids. Record the version so this doesn't get
    // re-checked forever, but do not re-render/re-set anything.
    if (GameSession.getTurn() === turnSeat) {
      lastAppliedTurnVersionByMatch[matchId] = matchDoc.version;
      return { applied: false, reason: "ALREADY_CURRENT" };
    }

    // The ONLY GameSession mutation this function ever performs — a
    // plain mirror update via an existing, unmodified public setter.
    // No engine file is called, consulted, or re-implemented here.
    GameSession.setTurn(turnSeat);
    lastAppliedTurnVersionByMatch[matchId] = matchDoc.version;
    return { applied: true, turnSeat: turnSeat, version: matchDoc.version };
  }

  /** Test/diagnostic-only accessor for `applyRemoteTurn()`'s own,
   *  independent version-gate registry — the Sprint 4.1 analog of
   *  `getLastAppliedVersion()` above. Returns `null` if nothing has
   *  been applied yet for this matchId. */
  function getLastAppliedTurnVersion(matchId) {
    return Object.prototype.hasOwnProperty.call(lastAppliedTurnVersionByMatch, matchId) ? lastAppliedTurnVersionByMatch[matchId] : null;
  }

  // ── Sprint 4.1, Task 3: Local Authority Validation ──────────────

  /** Task 3 ("Before any future gameplay action: Verify currentPlayer
   *  == localSeat"). Reads the CURRENT whose-turn signal — preferring
   *  `matchDoc.turn` (translated uid -> seat) when a matchDoc is given,
   *  falling back to the local `GameSession` mirror
   *  `applyRemoteTurn()` itself keeps synchronized when it isn't (e.g.
   *  a caller that only has GameSession available, not a fresh
   *  matchDoc) — and compares it against `localSeat`. Deliberately
   *  does NOT consult `BiddingEngine.getState().waitingFor`: that field
   *  is bidding-phase-specific and becomes meaningless once bidding
   *  ends (e.g. during a future card-play phase this sprint does not
   *  implement) — using the general-purpose `matches/{matchId}.turn`
   *  mirror instead is what makes this gate reusable by ANY future
   *  gameplay-write function, not just a bidding one. Never throws;
   *  returns a plain boolean. Called by nothing in this codebase yet
   *  (no gameplay-write function beyond bidding exists) — delivered
   *  ahead of its first real caller, exactly like
   *  `bootstrapGameSession()` and `applyRemoteBid()` each were. */
  function isLocalSeatsTurn(matchDoc, localSeat) {
    if (!localSeat) return false;
    var turnSeat = null;
    if (matchDoc && typeof matchDoc === "object" && matchDoc.turn != null) {
      turnSeat = uidToSeat(matchDoc, matchDoc.turn);
    }
    if (turnSeat == null && global.GameSession && typeof global.GameSession.getTurn === "function") {
      turnSeat = global.GameSession.getTurn();
    }
    return turnSeat != null && turnSeat === localSeat;
  }

  /** Same check as `isLocalSeatsTurn()`, but as an assertion — "If
   *  false: reject locally. Do not send writes." A future gameplay-
   *  write function is expected to call this FIRST, before attempting
   *  any Firestore write, and let a thrown `NOT_LOCAL_TURN` short-
   *  circuit the write entirely rather than sending it and hoping
   *  `firestore.rules` catches it server-side — this is the CLIENT-side
   *  half of this project's established "neither layer trusts the
   *  other alone" defense-in-depth principle, not a replacement for a
   *  server-side rule. */
  function assertLocalTurn(matchDoc, localSeat) {
    if (!isLocalSeatsTurn(matchDoc, localSeat)) {
      throw adapterError("NOT_LOCAL_TURN", "assertLocalTurn: it is not seat '" + localSeat + "'s turn right now.");
    }
    return true;
  }

  // ── Sprint 4.2, Task 2/4/5: Remote Card Application ─────────────
  // matchId -> the highest matchDoc.version this adapter has ever
  // successfully processed FOR CARDS. A THIRD independent registry —
  // deliberately separate from both `lastAppliedVersionByMatch` (bids)
  // and `lastAppliedTurnVersionByMatch` (turn) — see this file's own
  // Sprint 4.2 header comment for why three independent gates, not one
  // shared one, is correct here (a single delivery can legitimately
  // carry a new bid AND a new turn AND new cards at once; a shared
  // gate would let whichever function's check ran first silently
  // consume the version for the others).
  var lastAppliedCardVersionByMatch = {};
  // matchId -> how many `cardLog` ENTRIES this adapter has already
  // replayed into the engine, ever. A card submission can legitimately
  // deliver MULTIPLE new entries in one accepted (newer-version)
  // snapshot — unlike a bid or a turn, which only ever change ONE
  // value per accepted write — so a per-VALUE gate alone is not
  // sufficient here; this second counter is what makes "replay every
  // new entry, in order, exactly once" correct even after a late
  // subscribe or a reconnect that missed several deliveries.
  var lastAppliedCardCountByMatch = {};

  /** Task 2 (Remote Card Application): replays every cardLog entry
   *  this adapter has not yet applied, IN ORDER, through
   *  `TableEngine.emit({type:"PlayCard", playerId, card})` — the ONLY
   *  call this function makes into any engine file. Never mutates
   *  Firestore (no `db()`/write-path reference anywhere in this
   *  function); every effect flows one way, into the local
   *  `GameSession`, and only ever THROUGH the real, unmodified
   *  `table-engine.js` reducer — this function never calls a
   *  `GameSession` setter directly for play data, exactly matching
   *  `applyRemoteBid()`'s own "never second-guess the engine" design.
   *
   *  Returns a small, structured result object — `{ applied: boolean,
   *  reason: string, appliedCount, results: [...] }` — covering every
   *  path, including every rejection. Never throws for ordinary
   *  "nothing to do" cases (a malformed snapshot, a stale version, an
   *  empty log) — those are expected, routine inputs in a live sync
   *  pipeline, not caller errors. */
  function applyRemoteCard(matchId, matchDoc) {
    // Task 5: reject malformed snapshots outright, before touching any
    // version bookkeeping or the engine at all.
    if (!matchId) return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    if (!matchDoc || typeof matchDoc !== "object" || Array.isArray(matchDoc)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }
    if (typeof matchDoc.version !== "number" || !Number.isFinite(matchDoc.version)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }
    if (!Array.isArray(matchDoc.cardLog)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }

    // Task 5: strict greater-than only, against this function's OWN
    // independent version registry. Equal (a duplicate delivery — Task
    // 4) and lesser (a stale/rolled-back delivery — "never rollback")
    // are both rejected the same way: ignored, never applied, never
    // treated as an error.
    var lastVersion = Object.prototype.hasOwnProperty.call(lastAppliedCardVersionByMatch, matchId)
      ? lastAppliedCardVersionByMatch[matchId] : null;
    if (lastVersion != null && matchDoc.version <= lastVersion) {
      return { applied: false, reason: matchDoc.version === lastVersion ? "DUPLICATE_VERSION" : "STALE_VERSION" };
    }

    var lastCount = lastAppliedCardCountByMatch[matchId] || 0;
    if (matchDoc.cardLog.length <= lastCount) {
      // A structurally newer version whose log has not actually grown
      // beyond what we've already replayed (e.g. a version bump caused
      // by a concurrent bid/turn write on the SAME document — Task 5's
      // "malformed" is not the right word for this; it is genuinely
      // new information for THIS field, just none of it about cards).
      lastAppliedCardVersionByMatch[matchId] = matchDoc.version;
      return { applied: false, reason: "NO_NEW_CARDS" };
    }

    if (!global.TableEngine || typeof global.TableEngine.emit !== "function" ||
        typeof global.TableEngine.getState !== "function") {
      return { applied: false, reason: "ENGINE_UNAVAILABLE" };
    }
    var TableEngine = global.TableEngine;

    var results = [];
    for (var i = lastCount; i < matchDoc.cardLog.length; i++) {
      var entry = matchDoc.cardLog[i];
      if (!entry || typeof entry !== "object" || !entry.seatId || !entry.card ||
          typeof entry.card !== "object" || typeof entry.card.suit !== "string" ||
          !entry.card.rank || typeof entry.card.rank.v !== "number") {
        // A malformed INDIVIDUAL entry (adapter corruption within an
        // otherwise well-formed log) is skipped, never thrown — the
        // count still advances past it so it isn't retried forever,
        // but nothing is emitted for it.
        results.push({ index: i, applied: false, reason: "MALFORMED_ENTRY" });
        continue;
      }
      var engineState = TableEngine.getState();
      // Task 4 ("local card" case): if the local engine's CURRENTLY
      // OPEN trick already has a play recorded for this seat — because
      // this adapter already applied it, or because the local player's
      // own UI already called `TableEngine.emit()` directly for their
      // OWN seat before this echo of their own write came back through
      // Firestore sync — never re-emit. Mirrors `applyRemoteBid()`'s
      // own "ALREADY_APPLIED_LOCALLY" content-level idempotency check,
      // adapted to this domain (see this file's own header comment for
      // why this check is scoped to the CURRENT trick only, and why
      // the count-based gate above is what makes correctness NOT
      // depend on that scoping).
      var alreadyInCurrentTrick = !!(engineState && Array.isArray(engineState.plays) &&
        engineState.plays.some(function (p) { return p.playerId === entry.seatId; }));
      if (alreadyInCurrentTrick) {
        results.push({ index: i, applied: false, reason: "ALREADY_APPLIED_LOCALLY", seatId: entry.seatId });
        continue;
      }
      // The ONLY call in this codebase's card-sync path into
      // table-engine.js. Every legality/ordering decision from here is
      // the real engine's, not this file's — this function only ever
      // reads the response.
      var engineResult = TableEngine.emit({
        type: "PlayCard", playerId: entry.seatId,
        card: { suit: entry.card.suit, rank: { v: entry.card.rank.v, s: entry.card.rank.s } }
      });
      if (!engineResult || engineResult.rejected) {
        results.push({ index: i, applied: false, reason: "ENGINE_REJECTED", seatId: entry.seatId, engineReason: engineResult && engineResult.reason });
      } else {
        results.push({ index: i, applied: true, seatId: entry.seatId });
      }
    }

    lastAppliedCardCountByMatch[matchId] = matchDoc.cardLog.length;
    lastAppliedCardVersionByMatch[matchId] = matchDoc.version;
    var appliedCount = results.filter(function (r) { return r.applied; }).length;
    return { applied: appliedCount > 0, appliedCount: appliedCount, version: matchDoc.version, results: results };
  }

  /** Test/diagnostic-only accessor for `applyRemoteCard()`'s own
   *  independent version-gate registry. Returns `null` if nothing has
   *  been applied yet for this matchId. */
  function getLastAppliedCardVersion(matchId) {
    return Object.prototype.hasOwnProperty.call(lastAppliedCardVersionByMatch, matchId) ? lastAppliedCardVersionByMatch[matchId] : null;
  }

  /** Test/diagnostic-only accessor for `applyRemoteCard()`'s own entry-
   *  count registry — how many `cardLog` entries have been replayed
   *  into the engine so far for this matchId. Returns `0` (never
   *  `null`) if nothing has been applied yet, matching the registry's
   *  own default-to-zero semantics. */
  function getLastAppliedCardCount(matchId) {
    return lastAppliedCardCountByMatch[matchId] || 0;
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

  // ── Sprint 4.1, Task 1: Turn Sync Pipeline ──────────────────────

  /** The turn-sync analog of `startBidSync()` above — subscribes
   *  through the SAME `MatchService.subscribeToMatch()` (no second
   *  listener: Firestore/`MatchService` ref-counts subscriptions by
   *  matchId, not by which adapter-level function called
   *  `subscribeToMatch()` — a page that calls both `startBidSync()`
   *  AND `startTurnSync()` for the same matchId still gets exactly one
   *  underlying listener) and pipes every delivery through
   *  `applyRemoteTurn()` instead of `applyRemoteBid()`. Fail-open on a
   *  delivery error, same as `startBidSync()`. Returns the same
   *  unsubscribe function `subscribeToMatch()` itself returns. Throws
   *  `MATCH_SERVICE_UNAVAILABLE` if `MatchService` isn't loaded. */
  function startTurnSync(matchId) {
    if (!matchId) throw adapterError("INVALID_ARGUMENT", "startTurnSync: matchId is required.");
    if (!global.MatchService || typeof global.MatchService.subscribeToMatch !== "function") {
      throw adapterError("MATCH_SERVICE_UNAVAILABLE", "startTurnSync: MatchService is not available on this page.");
    }
    return global.MatchService.subscribeToMatch(matchId, function (data, err) {
      if (err || !data) return;
      applyRemoteTurn(matchId, data);
    });
  }

  // ── Sprint 4.2: Card Sync Pipeline ──────────────────────────────

  /** The card-sync analog of `startBidSync()`/`startTurnSync()` —
   *  subscribes through the SAME `MatchService.subscribeToMatch()` (no
   *  second listener — Firestore/`MatchService` ref-counts by matchId,
   *  not by which adapter-level function subscribed; a page calling
   *  all three `start*Sync()` functions for the same matchId still
   *  gets exactly one underlying listener) and pipes every delivery
   *  through `applyRemoteCard()` instead. Fail-open on a delivery
   *  error, same as the other two. Throws `MATCH_SERVICE_UNAVAILABLE`
   *  if `MatchService` isn't loaded. */
  function startCardSync(matchId) {
    if (!matchId) throw adapterError("INVALID_ARGUMENT", "startCardSync: matchId is required.");
    if (!global.MatchService || typeof global.MatchService.subscribeToMatch !== "function") {
      throw adapterError("MATCH_SERVICE_UNAVAILABLE", "startCardSync: MatchService is not available on this page.");
    }
    return global.MatchService.subscribeToMatch(matchId, function (data, err) {
      if (err || !data) return;
      applyRemoteCard(matchId, data);
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
    applyRemoteTurn: applyRemoteTurn,
    isLocalSeatsTurn: isLocalSeatsTurn,
    assertLocalTurn: assertLocalTurn,
    startTurnSync: startTurnSync,
    getLastAppliedTurnVersion: getLastAppliedTurnVersion,
    applyRemoteCard: applyRemoteCard,
    startCardSync: startCardSync,
    getLastAppliedCardVersion: getLastAppliedCardVersion,
    getLastAppliedCardCount: getLastAppliedCardCount,
    resetSyncState: resetSyncState
  };
})(window);
