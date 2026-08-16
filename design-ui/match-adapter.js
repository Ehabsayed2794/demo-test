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

   Sprint 4.3 (Trick Resolution Synchronization) — STRICT scope: online
   trick-winner synchronization ONLY. Not scoring, not next round, not
   match end. Two additive functions, neither touching `table-engine.js`,
   `bidding-engine.js`, `scoring-engine.js`, or `MatchService.submitCard()`.

   Task 1 (Architecture Verification, performed FIRST, before any code):
   does `table-engine.js` already expose enough to determine trick
   completion, trick winner, and next leader WITHOUT adding gameplay
   logic? **YES.** `TableEngine.getState().phase === "RESOLVING"` is the
   EXISTING, unmodified signal `emit()` itself already sets the instant
   the 4th card of a trick lands (Sprint 3.6/4.2.1's own `emit()` body,
   untouched). `TableEngine.resolveTrick()` — exported since Sprint 3.6,
   the EXACT SAME function the real offline turn loop already calls
   internally (`advance()` -> `sweepThenResolve()` -> `resolveTrick()`)
   — computes the winner via its own internal `trickWinner()`, records
   it into `state.lastTrick.winnerId`, increments `state.tricksWon`, and
   advances `state.leaderId`/`state.turn`/`state.trickNo`/`state.phase`
   to the NEXT trick (or to `"DONE"` at trick 13) — all EXISTING,
   unmodified engine behavior. `TableEngine.getState()` — also exported
   since Sprint 3.6 — exposes every one of those fields afterward. No
   engine change was needed; no Architecture Blocker applies.

   Task 2: `applyRemoteTrick(matchId, matchDoc)` — consumes "remote
   trick state" in the sense this project has always used that phrase
   for card sync: the REAL, already-synced `TableEngine` state, reached
   ONLY by replaying Firestore's own `cardLog` through the unmodified
   engine (via the EXISTING `applyRemoteCard()`, called separately —
   never duplicated, never reimplemented here). This function's OWN
   direct engine call is `TableEngine.resolveTrick()` — nothing else —
   and it computes the returned `winnerId` by READING
   `TableEngine.getState().lastTrick.winnerId` AFTERWARD, never by
   evaluating cards, comparing trump strength, or following suit itself.
   Guarded by the engine's own `phase === "RESOLVING"` precondition
   (ordinary "nothing to do yet" no-op otherwise — exactly like every
   other `applyRemote*()`'s "no new X" case) plus a small, dedicated
   `lastResolvedTrickNoByMatch` idempotency registry (this function's
   own "duplicate/stale delivery ignored" gate — see its own comment for
   why a `version`-number gate, correct for every OTHER `applyRemote*()`
   function, is NOT used here).

   NECESSARY COMPLETION, found while testing, documented rather than
   silently required: `matches/{matchId}.turn` is set to `null` at the
   resolving boundary (Sprint 4.2.2) and NOTHING writes the real next
   leader back into it once resolution happens (there is no Firestore
   field for that — Task 4/5's own "no new field" conclusion holds).
   Without ALSO mirroring the resolved trick's real next leader into
   `GameSession`'s own turn field, `assertLocalTurn()`'s EXISTING
   fallback behavior (Sprint 4.1, unmodified) would keep reporting a
   stale seat, blocking every subsequent trick's first submission —
   this sprint's own stated goal ("synchronize the trick winner")
   would otherwise be a dead end that never lets play continue past
   trick 1. `applyRemoteTrick()` therefore ALSO calls
   `GameSession.setTurn()` — an EXISTING, unmodified public setter
   this file already established as safe to call from here (Sprint
   3.9's `bootstrapGameSession()`, Sprint 4.1's `applyRemoteTurn()`) —
   with the engine's own post-resolution `state.turn`, and ONLY when
   there genuinely IS a next trick to lead (`phase === "PLAY"`). This
   is the EXACT SAME "mirror the engine's decision into GameSession,
   never compute one independently" pattern `applyRemoteTurn()`
   already established, applied to a new trigger (trick resolution
   instead of a Firestore turn-field change) — never a new rule, never
   a Firestore write, never gameplay logic. See Task 8's own honesty
   answer #8 in this sprint's Implementation Report for why this
   wasn't anticipated in the original Task 2 wording and was added
   only once end-to-end testing surfaced the gap.

   Task 3: `startTrickSync(matchId)` — reuses
   `MatchService.subscribeToMatch()` verbatim (confirmed, by reading
   that function's own doc comment: a second/third call for the SAME
   matchId registers an additional local callback against the ONE
   already-active ref-counted entry — it never creates a second
   Firestore listener), exactly like `startBidSync()`/`startTurnSync()`/
   `startCardSync()` already do. ONE documented, honest architectural
   necessity beyond a single `applyRemoteX()` call per delivery — see
   that function's own comment for the full account: `cardLog` is
   append-only and NEVER cleared across trick boundaries (Sprint 4.2's
   own documented design), so a single delivery (a late subscriber, or
   a reconnect that missed several deliveries) can legitimately carry
   MORE than one already-completed-but-not-yet-locally-resolved trick.
   `table-engine.js`'s own `emit()` correctly refuses a new card while
   `phase === "RESOLVING"` (existing, unmodified behavior) — so catching
   up on N backlogged tricks in one delivery requires alternating
   "replay what's now unblocked" and "resolve the trick that just
   completed" up to N times. `startTrickSync()`'s subscription callback
   is the ONE place this loop lives — it calls the EXISTING,
   unmodified `applyRemoteCard()` (safe to call again: fully
   idempotent) and the new `applyRemoteTrick()` alternately, capped at
   13 iterations (the maximum possible tricks in one round), until a
   full pass resolves nothing further. This is orchestration, not a
   new algorithm — no gameplay rule is duplicated by looping calls to
   two functions that already, independently, refuse to do anything
   incorrect on a redundant or premature call.

   Task 4 (MatchService): NOT MODIFIED. Justification: the trick winner,
   next leader, and updated `tricksWon` are ALL deterministically
   re-derivable, by EVERY client, from data ALREADY being synchronized
   (the existing `cardLog` + the existing, immutable `trump`/seat rules
   `table-engine.js` already enforces identically everywhere) — there is
   no new fact that needs a NEW Firestore write, because every client's
   own real engine, replaying the SAME already-synced cards through the
   SAME unmodified reducer, computes the IDENTICAL answer. This is
   "synchronization by determinism," not "synchronization by a
   broadcast write" — no new field, no new write path, so
   `MatchService` needed no change and none was made.

   Task 5 (firestore.rules): NOT MODIFIED, for the identical reason —
   no new field is ever written for trick resolution, so there is
   nothing new for a rule to permit or constrain. See
   docs/architecture/SecurityArchitecture.md's "Trick resolution
   authority" section for the full, restated account.
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

  // ── Sprint J.11 (Fast-Round Leader Authority) ─────────────────────
  // Root cause (established across J.8/J.10/J.10.5/J.11's own adversarial
  // review): for Round 14+ ("fast rounds" — bidding skips Dash/Auction
  // straight to Estimates), the ONLY function computing the round-start
  // leader (below, `computeRoundStartLeaderUid()`) read exclusively from
  // `global.GameSession` — local, listener-driven state. At the exact
  // synchronous instant the 4th Final Estimate's own `submitBid()`
  // transaction runs (the write that actually completes fast-round
  // bidding, for BOTH the Super Call and no-Super-Call cases — see
  // bidding-engine.js's `SubmitFinalEstimate` handler), NO client has
  // yet run its own local reducer for this round's completion (that
  // only happens later, via `applyRemoteBid()`'s echo of the write that
  // hasn't landed yet) — so `GameSession.getRound().callerId` is
  // structurally guaranteed to be stale/null, forcing the
  // `getTurn()||getDealer()` fallback, which is itself a leftover value
  // from a PREVIOUS round or the match's own creation-time dealer.
  //
  // Fix (J.11 approved architecture): for fast rounds only, derive the
  // leader ENTIRELY from the durable, already-fresh transaction document
  // — `matchDoc.dealer`, `matchDoc.currentRound`, `matchDoc.bids`,
  // `matchDoc.seats` — reproducing the EXACT SAME formula
  // bidding-engine.js's `SubmitFinalEstimate` handler already implements
  // (never a second, divergent game rule). Zero `GameSession` reference;
  // zero side effects; safe to call any number of times, including
  // inside a Firestore transaction the SDK may silently retry.
  //
  // Seat ordering is deliberately built from `sortedSeatKeys(matchDoc.
  // seats)` — the SAME seat-count-aware utility this file already uses
  // for `uidToSeat()`/`seatToUid()` — NOT `GameSession.getPlayers()`/
  // `TURN_ORDER` (which session.js's own `mockPlayers()` populates with
  // exactly 4 entries regardless of real seat count, and whose dealer
  // rotation cycles a FIXED 4-slot `CANONICAL_ORDER` irrelevant to a
  // real 2/3-player Firestore match's actual occupied seats). This is a
  // deliberate correctness choice, not an oversight: for the 4-player
  // case this file's own E2E harness actually exercises,
  // `sortedSeatKeys()` over a full 4-seat map returns exactly
  // `["p1","p2","p3","p4"]` — identical to the existing system's
  // behavior, zero regression risk — while for 2/3-player matches it is
  // the only choice that can ever produce a real, occupied seat as
  // leader at all (a fixed 4-slot rotation could otherwise land on a
  // seat that doesn't exist in `matchDoc.seats`).
  /** Pure, deterministic fast-round leader derivation. Takes ONLY
   *  `matchDoc` — no GameSession, no TableEngine, no local/UI/singleton
   *  state, no side effects. Returns the leader's SEAT id (not a uid —
   *  callers translate via `seatToUid()`, matching this file's own
   *  established seat/uid separation), or `null` if `matchDoc` lacks
   *  enough structure to compute one (caller treats this exactly like
   *  `computeRoundStartLeaderUid()`'s existing `null` contract). */
  function computeRoundStartLeaderFromPersistedState(matchDoc) {
    if (!matchDoc || !matchDoc.seats || typeof matchDoc.currentRound !== "number") return null;
    var seatOrder = sortedSeatKeys(matchDoc.seats);
    if (seatOrder.length === 0) return null;

    function nextSeatCCW(seat) {
      var i = seatOrder.indexOf(seat);
      if (i === -1) return seatOrder[0];
      return seatOrder[(i + 1) % seatOrder.length];
    }

    // The round's dealer: matchDoc.dealer is a UID, static since match
    // (or rematch) creation — translate to a seat, then rotate CCW
    // exactly (currentRound - 1) times, mirroring session.js's own
    // "rotateDealer() called unconditionally exactly once per
    // nextRound()" invariant (verified: the only two writers of
    // `dealer` are match/rematch creation; the only writer of
    // `currentRound` after creation is `advanceToNextRound()`,
    // incrementing by exactly 1 every time — no skip, no bulk jump).
    var creationDealerSeat = uidToSeat(matchDoc, matchDoc.dealer);
    if (creationDealerSeat == null) return null;
    var dealerSeat = creationDealerSeat;
    var rotations = matchDoc.currentRound - 1;
    for (var r = 0; r < rotations; r++) { dealerSeat = nextSeatCCW(dealerSeat); }

    // biddingOrder: CCW walk starting at the round's dealer, over the
    // SAME active-seat list — exactly mirroring bidding-engine.js's
    // `firstBidder = dealer` + `nextCCW()` walk (lines 594-595).
    var biddingOrder = [];
    { var seat = dealerSeat; for (var k = 0; k < seatOrder.length; k++) { biddingOrder.push(seat); seat = nextSeatCCW(seat); } }
    function orderIndex(s) { return biddingOrder.indexOf(s); }

    var bids = matchDoc.bids || {};
    // Super Call: any seat's final estimate >= 8. Ties broken by
    // (amount desc, then earliest in biddingOrder) — IDENTICAL formula
    // to the no-Super-Call branch below; this is ONE calculation, not
    // two competing algorithms, matching bidding-engine.js's own
    // structure (both branches share the exact same tie-break shape).
    var superCandidates = seatOrder
      .filter(function (s) { return typeof bids[s] === "number" && bids[s] >= 8; })
      .sort(function (a, b) { return (bids[b] - bids[a]) || (orderIndex(a) - orderIndex(b)); });
    if (superCandidates.length > 0) return superCandidates[0];

    // No Super Call: highest bid wins; ties broken the same way.
    var highestAmount = seatOrder.reduce(function (max, s) {
      return typeof bids[s] === "number" ? Math.max(max, bids[s]) : max;
    }, -1);
    var highestCandidates = seatOrder
      .filter(function (s) { return typeof bids[s] === "number" && bids[s] === highestAmount; })
      .sort(function (a, b) { return orderIndex(a) - orderIndex(b); });
    // Documented dealer fallback (mirrors bidding-engine.js line 647:
    // `leaderId: fastCallerId != null ? fastCallerId : state.firstBidder`)
    // — the pathological "no valid bids at all" edge, never expected in
    // real play but kept for parity with the real engine's own contract.
    return highestCandidates[0] || dealerSeat;
  }

  /** true for round >= 14 — the SAME threshold bidding-engine.js's own
   *  `isFastRound()` uses (round.js is not require()-able from here;
   *  this is a one-line, unconditional numeric constant, not a second
   *  game-rule implementation). */
  function isFastRoundNumber(roundNumber) {
    return typeof roundNumber === "number" && roundNumber >= 14;
  }

  /** Sprint J.3 (Hardened Round-Start Turn Authority): the ONE place
   *  that reads GameSession's own bidding-outcome state to compute the
   *  real first-trick leader for a round that is genuinely about to
   *  complete bidding — reuses the EXACT SAME formula table-engine.js's
   *  own buildRoundCfg() already uses (`r.callerId || GameSession.
   *  getTurn() || GameSession.getDealer()`), never a second, divergent
   *  source of truth. Deliberately lives HERE, not in match-service.js:
   *  this project's own established layering keeps match-service.js as
   *  a pure Firestore-facing service with ZERO direct GameSession
   *  reference (enforced by tests/turn-sync.test.cjs's own "adapter
   *  isolation" check) — any client-engine-state read match-service.js
   *  needs must be brokered through this adapter, exactly like
   *  `uidToSeat()`/`seatToUid()`/`assertLocalTurn()` already are for
   *  seat/turn-authority questions. Returns `null` (never throws) if
   *  GameSession is unavailable or no real leader seat can be
   *  determined — the caller (submitBid()) treats a `null` result as
   *  "do not attempt to establish turn/cardPhase on this write," the
   *  same safe default as before this sprint.
   *
   *  Sprint J.11: for a FAST round (currentRound >= 14), this function
   *  now DISPATCHES to `computeRoundStartLeaderFromPersistedState()`
   *  instead — a pure function of `matchDoc` alone, with zero
   *  GameSession dependency. Normal rounds (1-13) are completely
   *  UNCHANGED: they keep using the existing GameSession-based formula
   *  below, which J.9 already proved reliable (round.callerId is
   *  propagated early enough, at Confirm time, for the Normal Caller
   *  path — a materially different timing situation from fast rounds,
   *  see J.9's own report). This is one dispatcher choosing between two
   *  clearly-scoped branches for two different bidding lifecycles, not
   *  two competing sources of truth for the SAME round type. */
  function computeRoundStartLeaderUid(matchDoc) {
    if (isFastRoundNumber(matchDoc && matchDoc.currentRound)) {
      var fastLeaderSeat = computeRoundStartLeaderFromPersistedState(matchDoc);
      return fastLeaderSeat ? seatToUid(matchDoc, fastLeaderSeat) : null;
    }
    if (!global.GameSession) return null;
    var round = (typeof global.GameSession.getRound === "function") ? global.GameSession.getRound() : null;
    var leaderSeat = (round && round.callerId)
      || (typeof global.GameSession.getTurn === "function" ? global.GameSession.getTurn() : null)
      || (typeof global.GameSession.getDealer === "function" ? global.GameSession.getDealer() : null);
    return leaderSeat ? seatToUid(matchDoc, leaderSeat) : null;
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

  // ── Sprint 3.7 (Online Bidding Synchronization Contract): Remote
  // Bidding Action (Dash Call / Auction Bid / Confirm Call) Application
  // ─────────────────────────────────────────────────────────────────
  // matchId -> the highest matchDoc.version this adapter has ever
  // successfully processed FOR biddingLog. A FOURTH independent
  // registry — deliberately separate from bids' (`lastAppliedVersionByMatch`),
  // turn's, and cards' own — same reasoning as every prior sprint's
  // identical design choice (see applyRemoteTurn()'s own comment for
  // the full account): a single delivery can legitimately carry a new
  // Final Estimate bid AND a new biddingLog entry AND a new card at
  // once; a shared gate would let whichever function's check ran first
  // silently consume the version for the others.
  var lastAppliedBiddingActionVersionByMatch = {};
  // matchId -> how many `biddingLog` ENTRIES this adapter has already
  // replayed into the engine, ever. Mirrors `lastAppliedCardCountByMatch`
  // exactly, for the identical reason: a single accepted (newer-version)
  // snapshot can legitimately carry MULTIPLE new entries (a late
  // subscriber, or a reconnect that missed several deliveries) — a
  // per-VERSION gate alone is not sufficient, since Dash/Auction/Confirm
  // are (unlike a single Final Estimate bid) repeatable, ordered actions.
  var lastAppliedBiddingActionCountByMatch = {};

  /** Sprint 3.7: replays every `biddingLog` entry this adapter has not
   *  yet applied, IN ORDER, through `BiddingEngine.emit({type:
   *  entry.actionType, playerId: entry.seatId, ...})` — mirrors
   *  `applyRemoteCard()`'s exact "replay every new log entry" structure
   *  (same dual version+count gate, same "stop at the first problem,
   *  never look past it" contract), applied here to `biddingLog`
   *  instead of `cardLog`. Never mutates Firestore; every effect flows
   *  one way, into the local `GameSession` (via `BiddingEngine.emit()`
   *  — this function never calls a `GameSession` setter directly for
   *  bidding data), and only ever THROUGH the real, unmodified
   *  `bidding-engine.js` reducer — this function never decides
   *  legality itself, it only reads the engine's own response.
   *
   *  CRITICAL DIFFERENCE from `applyRemoteCard()`'s own echo-detection,
   *  explained here because it is the one genuinely new piece of logic
   *  this sprint adds (everything else is a direct structural mirror):
   *  `TableEngine`'s `state.plays` array lets `applyRemoteCard()` ask
   *  "does the local engine ALREADY have THIS seat's THIS card
   *  recorded for the CURRENT trick" directly. `bidding-engine.js` has
   *  no equivalent single field that works uniformly across all three
   *  action types. Instead, this function asks `BiddingEngine.canSubmit()`
   *  FIRST, for every entry, before ever calling `emit()` — exactly
   *  mirroring `MatchService.submitBiddingAction()`'s own pre-write
   *  gate, just applied here to a REPLAY instead of a fresh submission.
   *  If `canSubmit()` says illegal SPECIFICALLY because the phase/turn
   *  has already moved past where this entry would apply (reason ===
   *  "Not this seat's turn" or reason matches "Not the ... phase") —
   *  this is a BENIGN, EXPECTED case: either this exact action was
   *  already applied locally (e.g. this client's own action, now
   *  echoing back through Firestore) and the engine has already
   *  advanced past it, or a late-delivered entry for a sub-phase this
   *  engine has already resolved past. Skip it — advance the count,
   *  never treat it as a desync. A `canSubmit()` rejection for any
   *  OTHER reason (a genuine content-rule mismatch — the local engine
   *  and the remote Firestore log disagree about what's LEGAL, not
   *  merely about WHEN) is treated exactly like `applyRemoteCard()`'s
   *  own `ENGINE_REJECTED`: a real desync, stop immediately, never
   *  look at a later entry in this delivery.
   *
   *  Returns a small, structured result object — `{applied, reason,
   *  appliedCount, results}` (and `desync:true` on the stop path) —
   *  covering every path, including every rejection, exactly like every
   *  other `applyRemote*()` function in this file. Never throws for
   *  ordinary "nothing to do" cases. */
  function applyRemoteBiddingAction(matchId, matchDoc) {
    if (!matchId) return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    if (!matchDoc || typeof matchDoc !== "object" || Array.isArray(matchDoc)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }
    if (typeof matchDoc.version !== "number" || !Number.isFinite(matchDoc.version)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }
    if (!Array.isArray(matchDoc.biddingLog)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }

    var lastVersion = Object.prototype.hasOwnProperty.call(lastAppliedBiddingActionVersionByMatch, matchId)
      ? lastAppliedBiddingActionVersionByMatch[matchId] : null;
    if (lastVersion != null && matchDoc.version <= lastVersion) {
      return { applied: false, reason: matchDoc.version === lastVersion ? "DUPLICATE_VERSION" : "STALE_VERSION" };
    }

    var lastCount = lastAppliedBiddingActionCountByMatch[matchId] || 0;
    if (matchDoc.biddingLog.length <= lastCount) {
      // A structurally newer version whose log has not actually grown
      // beyond what we've already replayed (e.g. a version bump caused
      // by a concurrent bid/card write on the SAME document) — genuinely
      // new information for THIS field, just none of it about bidding.
      lastAppliedBiddingActionVersionByMatch[matchId] = matchDoc.version;
      return { applied: false, reason: "NO_NEW_BIDDING_ACTIONS" };
    }

    if (!global.BiddingEngine || typeof global.BiddingEngine.emit !== "function" ||
        typeof global.BiddingEngine.canSubmit !== "function" || typeof global.BiddingEngine.getState !== "function") {
      return { applied: false, reason: "ENGINE_UNAVAILABLE" };
    }
    var BiddingEngine = global.BiddingEngine;

    var results = [];
    for (var i = lastCount; i < matchDoc.biddingLog.length; i++) {
      var entry = matchDoc.biddingLog[i];
      if (!entry || typeof entry !== "object" || !entry.seatId || !entry.actionType ||
          ["SubmitDashCallDecision", "SubmitAuctionBid", "SubmitConfirmCall"].indexOf(entry.actionType) === -1) {
        // Mirrors applyRemoteCard()'s own MALFORMED_ENTRY treatment
        // exactly (Sprint 4.2.2, Task 4): stop immediately, advance the
        // count only up to (never past) this index, do NOT advance the
        // version registry at all, so a future delivery re-attempts
        // this SAME stuck index rather than treating this version as
        // fully handled.
        results.push({ index: i, applied: false, reason: "MALFORMED_ENTRY" });
        lastAppliedBiddingActionCountByMatch[matchId] = i;
        return {
          applied: false, desync: true, reason: "MALFORMED_ENTRY", matchId: matchId, index: i,
          appliedCount: results.filter(function (r) { return r.applied; }).length,
          version: matchDoc.version, results: results
        };
      }

      // Round Lifecycle sprint — CRITICAL: check this entry's round tag
      // against the LOCAL engine's own current round BEFORE ever asking
      // canSubmit()/emit() about it. Without this, a client that has
      // not yet locally transitioned to Round N+1 (see
      // applyRemoteRoundTransition()/startRoundSync() below) would have
      // a Round N+1 biddingLog entry rejected by canSubmit() with
      // "Bidding is already complete" (this engine is still on Round
      // N's DONE state) — and THAT reason is already, correctly,
      // classified as a benign phase/turn-mismatch skip by
      // isPhaseOrTurnMismatchReason() below, which ADVANCES the count
      // past it. Once advanced, this adapter's monotonic count-based
      // catch-up would NEVER revisit that index — even after this same
      // client later calls BiddingEngine.initState() for Round N+1 —
      // permanently losing that entry for this client. This is the
      // exact cross-round contamination this sprint's own brief warns
      // against ("Client A: GameSession.nextRound() while Client B:
      // still observes Round 1"). The fix: an entry whose round is
      // AHEAD of the local engine's own round is not a rejection at
      // all — it is "not yet ready for me" — so this stops the loop
      // WITHOUT touching either registry, leaving this exact index for
      // a future delivery (after this client's own round transition)
      // to re-attempt correctly.
      var localBiddingRound = (function () {
        try {
          var s = BiddingEngine.getState();
          return s ? s.round : null;
        } catch (e) { return null; }
      })();
      if (entry.round != null && localBiddingRound != null && entry.round > localBiddingRound) {
        results.push({ index: i, applied: false, reason: "AWAITING_ROUND_TRANSITION", seatId: entry.seatId, entryRound: entry.round, localRound: localBiddingRound });
        return {
          applied: false, desync: false, reason: "AWAITING_ROUND_TRANSITION", matchId: matchId, index: i,
          appliedCount: results.filter(function (r) { return r.applied; }).length,
          version: matchDoc.version, results: results
        };
      }
      // A defensive counterpart to the check above — should never
      // actually trigger given the "stop dead, never advance past an
      // AWAITING_ROUND_TRANSITION index" behavior just established (a
      // stale-round entry would always be caught BEFORE this client's
      // own count could ever move past it), but this file's own
      // established convention is "never trust a monotonic assumption
      // blindly" — an entry for a round strictly BEHIND the local
      // engine is simply already-superseded history; skip it exactly
      // like ALREADY_APPLIED_LOCALLY, never a desync.
      if (entry.round != null && localBiddingRound != null && entry.round < localBiddingRound) {
        results.push({ index: i, applied: false, reason: "STALE_ROUND", seatId: entry.seatId, entryRound: entry.round, localRound: localBiddingRound });
        continue;
      }

      var intent = biddingLogEntryToIntent(entry);
      // Sprint 3.7.x (Bidding Trust-Boundary Hardening): canSubmit()
      // itself is now hardened (bidding-engine.js) to never crash on a
      // malformed intent — but this replay path is exactly the kind of
      // place "neither layer trusts the other alone" applies most:
      // never let ANY unexpected engine exception (a malformed intent
      // this hardening pass didn't anticipate, or a future engine
      // change) escape uncaught into this Firestore snapshot callback.
      // Mirrors this exact same MALFORMED_ENTRY/ENGINE_REJECTED "stop
      // immediately, advance count only up to this index, never
      // advance the version registry" contract every other failure
      // path in this function already uses — an exception is treated
      // as a desync, never silently swallowed, never a fabricated
      // success, and never followed by a mutating emit() call.
      var verdict;
      try {
        verdict = BiddingEngine.canSubmit(intent);
      } catch (e) {
        results.push({ index: i, applied: false, reason: "ENGINE_THREW", seatId: entry.seatId, engineReason: e.message });
        lastAppliedBiddingActionCountByMatch[matchId] = i;
        return {
          applied: false, desync: true, reason: "ENGINE_THREW", matchId: matchId, index: i, seatId: entry.seatId,
          engineReason: e.message,
          appliedCount: results.filter(function (r) { return r.applied; }).length,
          version: matchDoc.version, results: results
        };
      }
      if (!verdict || !verdict.legal) {
        if (isPhaseOrTurnMismatchReason(verdict && verdict.reason)) {
          // Benign, expected: either this client's own action already
          // applied locally (this is its echo), or a late entry for a
          // sub-phase already resolved past. Never a desync — advance
          // past it and keep going, exactly like a genuine
          // ALREADY_APPLIED_LOCALLY skip elsewhere in this file.
          results.push({ index: i, applied: false, reason: "ALREADY_APPLIED_LOCALLY", seatId: entry.seatId });
          continue;
        }
        // A genuine content-rule mismatch — the real engine's rules and
        // what Firestore's log claims was accepted disagree. Treated
        // exactly like `applyRemoteCard()`'s own `ENGINE_REJECTED`:
        // stop immediately, never advance the version registry.
        results.push({ index: i, applied: false, reason: "ENGINE_REJECTED", seatId: entry.seatId, engineReason: verdict && verdict.reason });
        lastAppliedBiddingActionCountByMatch[matchId] = i;
        return {
          applied: false, desync: true, reason: "ENGINE_REJECTED", matchId: matchId, index: i, seatId: entry.seatId,
          engineReason: verdict && verdict.reason,
          appliedCount: results.filter(function (r) { return r.applied; }).length,
          version: matchDoc.version, results: results
        };
      }

      // The ONLY call in this codebase's bidding-action sync path into
      // bidding-engine.js's mutating reducer. Every legality/ordering
      // decision was already made by the canSubmit() check above —
      // this call's own result is re-checked defensively (mirrors
      // applyRemoteCard()'s own belt-and-suspenders re-check after a
      // successful preview) but should never disagree with `verdict`.
      var engineResult;
      try {
        engineResult = BiddingEngine.emit(intent);
      } catch (e) {
        results.push({ index: i, applied: false, reason: "ENGINE_THREW", seatId: entry.seatId, engineReason: e.message });
        lastAppliedBiddingActionCountByMatch[matchId] = i;
        return {
          applied: false, desync: true, reason: "ENGINE_THREW", matchId: matchId, index: i, seatId: entry.seatId,
          engineReason: e.message,
          appliedCount: results.filter(function (r) { return r.applied; }).length,
          version: matchDoc.version, results: results
        };
      }
      if (!engineResult || engineResult.rejected) {
        results.push({ index: i, applied: false, reason: "ENGINE_REJECTED", seatId: entry.seatId, engineReason: engineResult && engineResult.reason });
        lastAppliedBiddingActionCountByMatch[matchId] = i;
        return {
          applied: false, desync: true, reason: "ENGINE_REJECTED", matchId: matchId, index: i, seatId: entry.seatId,
          engineReason: engineResult && engineResult.reason,
          appliedCount: results.filter(function (r) { return r.applied; }).length,
          version: matchDoc.version, results: results
        };
      }
      results.push({ index: i, applied: true, seatId: entry.seatId, actionType: entry.actionType });
    }

    lastAppliedBiddingActionCountByMatch[matchId] = matchDoc.biddingLog.length;
    lastAppliedBiddingActionVersionByMatch[matchId] = matchDoc.version;
    var appliedCount = results.filter(function (r) { return r.applied; }).length;
    return { applied: appliedCount > 0, desync: false, appliedCount: appliedCount, version: matchDoc.version, results: results };
  }

  /** Translates a `biddingLog` entry into the exact `BiddingEngine`
   *  intent shape — the SAME translation `MatchService.submitBiddingAction()`
   *  performs in the opposite direction (its own `biddingActionToIntent()`),
   *  kept as an independent local copy since these are separate files
   *  with no shared module — `actionType` IS the engine's own
   *  `intent.type` string either way, so this is a direct field
   *  passthrough, never a re-derivation. */
  function biddingLogEntryToIntent(entry) {
    var intent = { type: entry.actionType, playerId: entry.seatId };
    if (entry.actionType === "SubmitDashCallDecision") {
      intent.declaredDashCall = entry.declaredDashCall;
    } else if (entry.actionType === "SubmitAuctionBid") {
      intent.isPass = !!entry.isPass;
      if (!intent.isPass) { intent.tricks = entry.tricks; intent.suit = entry.suit; }
    } else if (entry.actionType === "SubmitConfirmCall") {
      intent.tricks = entry.tricks;
      intent.suit = entry.suit;
    }
    return intent;
  }

  /** `true` iff a `canSubmit()` rejection reason is one of the 5
   *  phase/turn-guard strings `bidding-engine.js`'s `canSubmit()` (Sprint
   *  3.6.1) returns for EVERY intent type's first two checks — never a
   *  content-rule reason (Dash limits, suit strength, Caller cap,
   *  With-floor, Forbidden-13, auction comparison), which always use
   *  a DIFFERENT, distinct reason string. Matched against the EXACT,
   *  literal strings `canSubmit()`'s own source uses (bidding-engine.js)
   *  — not a heuristic/substring guess. */
  function isPhaseOrTurnMismatchReason(reason) {
    return reason === "Not this seat's turn" ||
      reason === "Not the Dash-Call phase" ||
      reason === "Not the Auction phase" ||
      reason === "Not the Confirmation phase" ||
      reason === "Not the Final Estimates phase" ||
      reason === "Bidding is already complete";
  }

  /** Test/diagnostic-only accessors for `applyRemoteBiddingAction()`'s
   *  own registries — the Sprint 3.7 analogs of `getLastAppliedVersion()`/
   *  `getLastAppliedCardVersion()`/`getLastAppliedCardCount()`. */
  function getLastAppliedBiddingActionVersion(matchId) {
    return Object.prototype.hasOwnProperty.call(lastAppliedBiddingActionVersionByMatch, matchId) ? lastAppliedBiddingActionVersionByMatch[matchId] : null;
  }
  function getLastAppliedBiddingActionCount(matchId) {
    return lastAppliedBiddingActionCountByMatch[matchId] || 0;
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
      delete lastResolvedTrickNoByMatch[matchId];
      delete lastAppliedBiddingActionVersionByMatch[matchId];
      delete lastAppliedBiddingActionCountByMatch[matchId];
      delete roundAdvanceAttemptedByMatch[matchId];
      delete matchCompletionAppliedByMatch[matchId];
      delete lastRematchVoteByMatch[matchId];
      if (rematchVoteTimeoutTimerByMatch[matchId]) { clearInterval(rematchVoteTimeoutTimerByMatch[matchId]); delete rematchVoteTimeoutTimerByMatch[matchId]; }
    } else {
      lastAppliedVersionByMatch = {};
      lastAppliedTurnVersionByMatch = {};
      lastAppliedCardVersionByMatch = {};
      lastAppliedCardCountByMatch = {};
      lastResolvedTrickNoByMatch = {};
      roundAdvanceAttemptedByMatch = {};
      matchCompletionAppliedByMatch = {};
      lastAppliedBiddingActionVersionByMatch = {};
      lastAppliedBiddingActionCountByMatch = {};
      lastRematchVoteByMatch = {};
      Object.keys(rematchVoteTimeoutTimerByMatch).forEach(function (k) { clearInterval(rematchVoteTimeoutTimerByMatch[k]); });
      rematchVoteTimeoutTimerByMatch = {};
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
        // Sprint 4.2.2, Task 4 — a THIRD Critical defect a direct
        // review found: this used to be treated as an ordinary,
        // skip-and-continue "adapter corruption" case, exactly like a
        // benign gap. It is not. A malformed entry in a REAL delivery
        // means Firestore's own history is unreadable at this index —
        // continuing past it (and, worse, the OLD code's later
        // unconditional registry advance past the WHOLE delivered log)
        // is indistinguishable from silently discarding a card that
        // may have genuinely been played. Fixed: treated EXACTLY like
        // `ENGINE_REJECTED` below — stop immediately, never look at a
        // later entry in this delivery, advance
        // `lastAppliedCardCountByMatch` only up to (never past) this
        // index, and do NOT advance `lastAppliedCardVersionByMatch` at
        // all, so a future delivery re-attempts this SAME stuck index
        // rather than treating this version as fully handled.
        results.push({ index: i, applied: false, reason: "MALFORMED_ENTRY" });
        lastAppliedCardCountByMatch[matchId] = i;
        return {
          applied: false,
          desync: true,
          reason: "MALFORMED_ENTRY",
          matchId: matchId,
          index: i,
          appliedCount: results.filter(function (r) { return r.applied; }).length,
          version: matchDoc.version,
          results: results
        };
      }

      // Round Lifecycle sprint: the identical round-tag guard
      // applyRemoteBiddingAction() applies above, for the identical
      // reason — a client that hasn't yet locally re-initialized
      // TableEngine for Round N+1 (see maybeEnterPlayPhase() in
      // match/index.html, unchanged, round-aware since this sprint)
      // still reports `TableEngine.getState().round === N`. A Round
      // N+1 cardLog entry arriving before that must be deferred, never
      // silently consumed by the count registry — see the identical
      // comment on applyRemoteBiddingAction()'s own check for the full
      // account of why a monotonic count-only gate cannot self-correct
      // once an entry is skipped past.
      var localTableRound = (function () {
        try {
          var s = TableEngine.getState();
          return s ? s.round : null;
        } catch (e) { return null; }
      })();
      if (entry.round != null && localTableRound != null && entry.round > localTableRound) {
        results.push({ index: i, applied: false, reason: "AWAITING_ROUND_TRANSITION", seatId: entry.seatId, entryRound: entry.round, localRound: localTableRound });
        return {
          applied: false,
          desync: false,
          reason: "AWAITING_ROUND_TRANSITION",
          matchId: matchId,
          index: i,
          appliedCount: results.filter(function (r) { return r.applied; }).length,
          version: matchDoc.version,
          results: results
        };
      }
      if (entry.round != null && localTableRound != null && entry.round < localTableRound) {
        results.push({ index: i, applied: false, reason: "STALE_ROUND", seatId: entry.seatId, entryRound: entry.round, localRound: localTableRound });
        continue;
      }

      var engineState = TableEngine.getState();
      // Sprint 4.2.1's own "local card" case, HARDENED in Sprint 4.2.2
      // Task 5 — a direct review found `ALREADY_APPLIED_LOCALLY`
      // checked only SEAT identity, never the CARD itself: a
      // DIFFERENT card from the same seat (a corrupted delivery, a
      // bug elsewhere, or a genuine desync) would be silently treated
      // as "this is just my own echo," discarding a real divergence
      // between what Firestore says was played and what the local
      // engine actually has recorded. Fixed: the local play for this
      // seat (if any) must match BOTH suit AND rank.v exactly for this
      // to be a legitimate echo skip.
      var localPlayForSeat = (engineState && Array.isArray(engineState.plays))
        ? engineState.plays.filter(function (p) { return p.playerId === entry.seatId; })[0]
        : null;
      if (localPlayForSeat) {
        var cardsMatch = localPlayForSeat.card && localPlayForSeat.card.suit === entry.card.suit &&
          localPlayForSeat.card.rank && localPlayForSeat.card.rank.v === entry.card.rank.v;
        if (cardsMatch) {
          // A genuine echo of this adapter's own (or this client's
          // own directly-applied) play — safe to skip, and safe to
          // CONTINUE (not a desync): the count advances past just
          // this one index, exactly like Sprint 4.2.1's original
          // behavior for a true echo.
          results.push({ index: i, applied: false, reason: "ALREADY_APPLIED_LOCALLY", seatId: entry.seatId });
          continue;
        }
        // Task 5: the seat matches, but the card does NOT — this is
        // NOT a benign echo. Firestore claims this seat played a
        // DIFFERENT card than what the local engine actually has
        // recorded for that seat's current-trick play. Treated
        // exactly like `MALFORMED_ENTRY`/`ENGINE_REJECTED`: stop
        // immediately, never advance past this index, never mark the
        // version as fully applied, never process a later entry.
        results.push({
          index: i, applied: false, reason: "LOCAL_ECHO_MISMATCH", seatId: entry.seatId,
          localCard: { suit: localPlayForSeat.card.suit, rank: localPlayForSeat.card.rank },
          remoteCard: { suit: entry.card.suit, rank: entry.card.rank }
        });
        lastAppliedCardCountByMatch[matchId] = i;
        return {
          applied: false,
          desync: true,
          reason: "LOCAL_ECHO_MISMATCH",
          matchId: matchId,
          index: i,
          seatId: entry.seatId,
          localCard: { suit: localPlayForSeat.card.suit, rank: localPlayForSeat.card.rank },
          remoteCard: { suit: entry.card.suit, rank: entry.card.rank },
          appliedCount: results.filter(function (r) { return r.applied; }).length,
          version: matchDoc.version,
          results: results
        };
      }
      // The ONLY call in this codebase's card-sync path into
      // table-engine.js. Every legality/ordering decision from here is
      // the real engine's, not this file's — this function only ever
      // reads the response.
      //
      // Sprint H (Remote Hand State fix): `trusted: true` — every entry
      // reaching this point already round-tripped through Firestore's
      // authoritative `cardLog`, which `firestore.rules` gates on
      // structure and turn order only (never follow-suit legality — see
      // isValidCardSubmission()); follow-suit legality for THIS entry
      // was already correctly checked exactly once, by the seat that
      // played it, against ITS OWN real hand, before the write (see
      // match-service.js's submitCard() -> TableEngine.canPlayCard()).
      // This client never holds that seat's private cards (Firestore
      // hand-privacy rules, unchanged), so re-deriving that same
      // legality decision here would require data this client
      // legitimately cannot have — `trusted` tells the engine to skip
      // only that redundant (and, without real opponent data, actively
      // incorrect) re-check, while every other gate (phase, whose turn)
      // still applies exactly as before.
      var engineResult = TableEngine.emit({
        type: "PlayCard", playerId: entry.seatId,
        card: { suit: entry.card.suit, rank: { v: entry.card.rank.v, s: entry.card.rank.s } },
        trusted: true
      });
      if (!engineResult || engineResult.rejected) {
        // Sprint 4.2.1, Task 3 — the SECOND Critical defect this
        // hotfix closes: Sprint 4.2's original version pushed this
        // rejection into `results` and kept looping, then
        // unconditionally advanced BOTH registries past the entire
        // delivered log at the end — meaning an engine-rejected entry
        // stayed in `cardLog` FOREVER while this adapter's own
        // bookkeeping claimed everything was successfully
        // synchronized. That is exactly how Firestore history and
        // local engine state diverge silently.
        //
        // Fixed: STOP processing immediately. Do NOT look at any later
        // entry in this delivery. Do NOT advance
        // `lastAppliedCardCountByMatch` past this rejected index (only
        // up to it — entries BEFORE this one in this SAME call were
        // genuinely, successfully applied by the real engine and must
        // never be re-emitted; this one, and everything after it,
        // remain unresolved). Do NOT advance
        // `lastAppliedCardVersionByMatch` AT ALL — leaving it exactly
        // where it was before this call is what makes the version
        // gate correctly let a FUTURE delivery (a retry, a reconnect,
        // or simply the next live update) re-attempt from this SAME
        // stuck index, rather than treating this version as "already
        // fully handled." This function never retries on its own —
        // "do not retry forever automatically" — it only returns a
        // structured result and leaves the decision (and the retry
        // trigger) entirely to whatever calls it next.
        results.push({ index: i, applied: false, reason: "ENGINE_REJECTED", seatId: entry.seatId, engineReason: engineResult && engineResult.reason });
        lastAppliedCardCountByMatch[matchId] = i;
        var appliedBeforeDesync = results.filter(function (r) { return r.applied; }).length;
        return {
          applied: false,
          desync: true,
          reason: "ENGINE_REJECTED",
          matchId: matchId,
          index: i,
          seatId: entry.seatId,
          engineReason: engineResult && engineResult.reason,
          appliedCount: appliedBeforeDesync,
          version: matchDoc.version,
          results: results
        };
      }
      results.push({ index: i, applied: true, seatId: entry.seatId });
    }

    lastAppliedCardCountByMatch[matchId] = matchDoc.cardLog.length;
    lastAppliedCardVersionByMatch[matchId] = matchDoc.version;
    var appliedCount = results.filter(function (r) { return r.applied; }).length;
    return { applied: appliedCount > 0, desync: false, appliedCount: appliedCount, version: matchDoc.version, results: results };
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

  // ── Sprint 4.3, Task 2: Remote Trick Resolution ─────────────────
  // matchId -> the highest `TableEngine.getState().trickNo` this
  // adapter has ever resolved for that match. Deliberately NOT gated by
  // `matchDoc.version`, unlike every OTHER `applyRemote*()` registry in
  // this file — a single Firestore delivery's `cardLog` can legitimately
  // span MULTIPLE already-completed tricks (a late subscriber, or a
  // reconnect that missed several deliveries — `cardLog` is append-only
  // and never cleared across trick boundaries, per Sprint 4.2's own
  // documented design), which means this function may need to run
  // MORE THAN ONCE for the exact same `matchDoc.version` (see
  // `startTrickSync()`'s own catch-up loop below) — a version-number
  // gate would incorrectly block every resolution after the first one
  // in that same delivery. `trickNo` itself, read fresh from the REAL
  // engine on every call, is the correct idempotency key instead: it
  // only ever advances by exactly 1 per genuine resolution, exactly
  // once per trick, regardless of how many Firestore deliveries or
  // catch-up passes were involved in getting there.
  var lastResolvedTrickNoByMatch = {};

  /** Task 2 (Remote Trick Resolution): resolves AT MOST ONE completed
   *  trick per call. "Remote trick state," for this function, means
   *  exactly what it has always meant for card sync in this file: the
   *  REAL `TableEngine` state, reached ONLY by replaying Firestore's own
   *  `cardLog` through the unmodified engine — via the EXISTING
   *  `applyRemoteCard()`, called separately (by `startTrickSync()`'s own
   *  catch-up loop below), never duplicated or reimplemented here. This
   *  function's OWN, and ONLY, direct engine call is
   *  `TableEngine.resolveTrick()` — the SAME, pre-existing, unmodified
   *  function the real offline turn loop already calls internally
   *  (`advance()` -> `sweepThenResolve()` -> `resolveTrick()`, exported
   *  since Sprint 3.6). It NEVER evaluates cards, compares trump
   *  strength, or applies follow-suit logic itself — the returned
   *  `winnerId` is read back from `TableEngine.getState().lastTrick`
   *  AFTER calling `resolveTrick()`, exactly mirroring how
   *  `applyRemoteCard()` reads `engineResult`/`engineState` back from
   *  `TableEngine.emit()` rather than computing anything independently.
   *
   *  Deliberately does NOT gate on `matchDoc.cardPhase` (the field
   *  `MatchService.submitCard()` writes atomically alongside each card,
   *  per Sprint 4.2.2): that field reflects the phase immediately after
   *  the LATEST card only — useful for turn-transition sync, but not a
   *  reliable cross-check for a HISTORICAL trick being caught up on
   *  during a multi-trick catch-up pass (see `startTrickSync()`'s own
   *  comment). Using it as a hard gate here would incorrectly flag a
   *  legitimate catch-up-in-progress resolution as a desync. This is a
   *  deliberate, documented design choice, not an oversight — see this
   *  file's own Sprint 4.3 header section and
   *  docs/architecture/MatchSynchronization.md's Sprint 4.3 section for
   *  the full account.
   *
   *  Returns a small, structured result object for every path,
   *  including every "nothing to do" case, exactly like every other
   *  `applyRemote*()` function in this file. Never throws for ordinary
   *  "nothing to do yet" cases (trick not complete, already resolved,
   *  malformed input, engine unavailable). */
  function applyRemoteTrick(matchId, matchDoc) {
    if (!matchId) return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    if (!matchDoc || typeof matchDoc !== "object" || Array.isArray(matchDoc)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }
    if (!global.TableEngine || typeof global.TableEngine.getState !== "function" ||
        typeof global.TableEngine.resolveTrick !== "function") {
      return { applied: false, reason: "ENGINE_UNAVAILABLE" };
    }
    var TableEngine = global.TableEngine;
    var engineState = TableEngine.getState();
    if (!engineState) {
      return { applied: false, reason: "ENGINE_UNAVAILABLE" };
    }

    if (engineState.phase !== "RESOLVING") {
      // The real, unmodified engine has not (or no longer) reached the
      // trick-complete boundary. This is the ordinary, expected outcome
      // for every delivery where the current trick simply isn't done
      // yet (1st-3rd card), where it was already resolved by an earlier
      // call, OR where an earlier `applyRemoteCard()` call in the SAME
      // sync pass already reported a genuine desync (MALFORMED_ENTRY /
      // LOCAL_ECHO_MISMATCH / ENGINE_REJECTED) and correctly refused to
      // apply the card that would have completed this trick. This
      // function never re-derives or second-guesses that detection —
      // it simply has nothing to resolve, exactly like every other
      // `applyRemote*()`'s own "no new X" no-op case (`NO_BID_TO_APPLY`,
      // `NO_TURN_TO_APPLY`, `NO_NEW_CARDS`).
      return { applied: false, reason: "NOT_RESOLVING" };
    }

    var trickNo = engineState.trickNo;
    if (lastResolvedTrickNoByMatch[matchId] === trickNo) {
      // Idempotent no-op: this exact trick has already been resolved by
      // an earlier call — a duplicate or stale snapshot redelivery, or
      // a repeat pass of `startTrickSync()`'s own multi-trick catch-up
      // loop re-examining state that a previous iteration already
      // advanced past.
      return { applied: false, reason: "ALREADY_RESOLVED", trickNo: trickNo };
    }

    // The ONLY call this function makes into TableEngine. Computes the
    // winner, updates `tricksWon`, and advances `trickNo`/`leaderId`/
    // `turn`/`phase` — entirely inside `table-engine.js`, entirely via
    // its own pre-existing, unmodified `resolveTrick()`.
    TableEngine.resolveTrick();
    var afterState = TableEngine.getState();
    var winnerId = (afterState && afterState.lastTrick) ? afterState.lastTrick.winnerId : null;
    lastResolvedTrickNoByMatch[matchId] = trickNo;

    // NECESSARY COMPLETION, documented rather than silently required:
    // `matches/{matchId}.turn` is set to `null` by `submitCard()` at the
    // resolving boundary (Sprint 4.2.2) and NOTHING writes the real
    // next leader back into it once resolution happens — no Firestore
    // field exists for that (Task 4/5's own "no new field" conclusion),
    // and `MatchService`/`table-engine.js` are both forbidden to touch
    // this sprint anyway. Without this mirror update,
    // `assertLocalTurn()`'s own existing fallback-to-`GameSession`
    // behavior (Sprint 4.1, unmodified) would keep reporting whichever
    // seat played the trick's 3rd card as "whose turn it is," blocking
    // every subsequent trick's first submission. This is the EXACT
    // SAME "mirror the engine's own decision into GameSession's turn
    // field via an existing, unmodified public setter" pattern
    // `applyRemoteTurn()` (Sprint 4.1) already established — never a
    // new rule, never a Firestore write, and only ever engaged when
    // there genuinely IS a next trick to lead (`afterState.phase ===
    // "PLAY"`; at trick 13, `resolveTrick()` itself does not update
    // `turn`, so there is nothing meaningful to mirror).
    if (afterState && afterState.phase === "PLAY" && afterState.turn != null &&
        global.GameSession && typeof global.GameSession.setTurn === "function") {
      global.GameSession.setTurn(afterState.turn);
    }

    return {
      applied: true,
      matchId: matchId,
      trickNo: trickNo,
      winnerId: winnerId,
      // Meaningful only when nextPhase === "PLAY" (the winner leads the
      // next trick) — at trick 13, table-engine.js's own resolveTrick()
      // does not update leaderId/turn (round is DONE; there is no next
      // trick to lead), so these simply reflect whatever the engine's
      // own, pre-existing fields already say in that case, unmodified
      // and unreinterpreted by this function.
      nextLeaderId: afterState ? afterState.leaderId : null,
      nextTurnSeat: afterState ? afterState.turn : null,
      nextPhase: afterState ? afterState.phase : null,
      tricksWon: (afterState && afterState.tricksWon) ? Object.assign({}, afterState.tricksWon) : null
    };
  }

  /** Test/diagnostic-only accessor for `applyRemoteTrick()`'s own
   *  idempotency registry. Returns `null` if no trick has been resolved
   *  yet for this matchId. */
  function getLastResolvedTrickNo(matchId) {
    return Object.prototype.hasOwnProperty.call(lastResolvedTrickNoByMatch, matchId) ? lastResolvedTrickNoByMatch[matchId] : null;
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

  // ── Sprint 3.7: Bidding Action Sync Pipeline ────────────────────

  /** The bidding-action-sync analog of `startBidSync()` — subscribes
   *  through the SAME `MatchService.subscribeToMatch()` (no second
   *  listener — Firestore/`MatchService` ref-counts by matchId, not by
   *  which adapter-level function subscribed) and pipes every delivery
   *  through `applyRemoteBiddingAction()` instead. Fail-open on a
   *  delivery error, same as every other `start*Sync()` function.
   *  Unlike `startTrickSync()`, no outer catch-up loop is needed:
   *  `applyRemoteBiddingAction()` already replays ALL new entries in
   *  ONE call (a for-loop over the new `biddingLog` indices, exactly
   *  like `applyRemoteCard()` does for `cardLog`) — there is no
   *  separate "resolution step" analogous to trick resolution that
   *  would require alternating two functions. Throws
   *  `MATCH_SERVICE_UNAVAILABLE` if `MatchService` isn't loaded. */
  function startBiddingActionSync(matchId) {
    if (!matchId) throw adapterError("INVALID_ARGUMENT", "startBiddingActionSync: matchId is required.");
    if (!global.MatchService || typeof global.MatchService.subscribeToMatch !== "function") {
      throw adapterError("MATCH_SERVICE_UNAVAILABLE", "startBiddingActionSync: MatchService is not available on this page.");
    }
    return global.MatchService.subscribeToMatch(matchId, function (data, err) {
      if (err || !data) return;
      applyRemoteBiddingAction(matchId, data);
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

  // ── Sprint 4.3, Task 3: Trick Sync Pipeline ─────────────────────

  /** The trick-sync analog of `startBidSync()`/`startTurnSync()`/
   *  `startCardSync()` — subscribes through the SAME
   *  `MatchService.subscribeToMatch()` (confirmed by that function's own
   *  doc comment: a second/third/fourth call for the SAME matchId
   *  registers an additional local callback against the ONE
   *  already-active ref-counted entry — it never creates a second
   *  Firestore listener). Fail-open on a delivery error, same as the
   *  other three.
   *
   *  ONE documented, honest deviation from "pipe every delivery through
   *  exactly one `applyRemoteX()` call," which is otherwise this
   *  project's established pattern for every `start*Sync()` function:
   *  this callback loops, alternating the EXISTING, unmodified
   *  `applyRemoteCard()` (safe to call again — fully idempotent, per
   *  its own version+count gate) and the new `applyRemoteTrick()`, up
   *  to 13 times (the maximum possible tricks in one round) per
   *  delivery, stopping the instant a pass resolves nothing further.
   *  This is REQUIRED, not a stylistic choice: `cardLog` is append-only
   *  and never cleared across trick boundaries (Sprint 4.2's own
   *  documented design), so ONE delivery (a late subscriber, or a
   *  reconnect that missed several deliveries) can legitimately carry
   *  MULTIPLE already-completed-but-not-yet-locally-resolved tricks —
   *  and `table-engine.js`'s own `emit()` correctly refuses a new card
   *  while `phase === "RESOLVING"` (existing, unmodified behavior), so
   *  catching up on N backlogged tricks requires N alternating
   *  "replay what's now unblocked, then resolve it" steps. This loop
   *  duplicates NO gameplay rule — it only re-invokes two functions
   *  that already, independently, refuse to do anything incorrect on a
   *  redundant or premature call (`applyRemoteCard()`'s own version/
   *  count gate; `applyRemoteTrick()`'s own `phase !== "RESOLVING"` /
   *  `ALREADY_RESOLVED` gates). See this file's own Sprint 4.3 header
   *  section and docs/architecture/MatchSynchronization.md's Sprint 4.3
   *  section for the full account, including why this is documented as
   *  a deliberate architecture decision rather than left implicit.
   *
   *  Returns the same unsubscribe function `subscribeToMatch()` itself
   *  returns. Throws `MATCH_SERVICE_UNAVAILABLE` if `MatchService`
   *  isn't loaded. */
  function startTrickSync(matchId) {
    if (!matchId) throw adapterError("INVALID_ARGUMENT", "startTrickSync: matchId is required.");
    if (!global.MatchService || typeof global.MatchService.subscribeToMatch !== "function") {
      throw adapterError("MATCH_SERVICE_UNAVAILABLE", "startTrickSync: MatchService is not available on this page.");
    }
    return global.MatchService.subscribeToMatch(matchId, function (data, err) {
      if (err || !data) return;
      for (var i = 0; i < 13; i++) {
        applyRemoteCard(matchId, data);
        var trickResult = applyRemoteTrick(matchId, data);
        if (!trickResult.applied) break;
      }
      maybeAdvanceRound(matchId, data);
    });
  }

  // ── Round Lifecycle sprint: Round Transition Sync ───────────────
  // matchId -> the highest round number this adapter has already
  // ATTEMPTED to advance PAST, via advanceToNextRound(). Deliberately
  // NOT a version/count-style gate (there is no log to count entries
  // in) — this only exists to stop a client from calling
  // MatchService.advanceToNextRound() again on every single delivery
  // once its own TableEngine reaches phase "DONE" (that call is itself
  // idempotent/safe to repeat — see MatchService's own doc comment —
  // but repeating it on every delivery forever would be needless
  // Firestore traffic for no benefit).
  var roundAdvanceAttemptedByMatch = {};

  /** Detects "MY local TableEngine just reached phase DONE for round
   *  R" and attempts EXACTLY ONE `MatchService.advanceToNextRound(matchId,
   *  R)` call — never a second, third, ... call for the SAME round from
   *  THIS client. Deliberately does NOT wait for confirmation that this
   *  particular call is the one that "wins" the transaction — per
   *  advanceToNextRound()'s own idempotent-any-client-may-attempt
   *  design (see docs/reviews/Sprint_RoundLifecycle_Architecture_Report.md
   *  §2), every client that independently reaches this same DONE state
   *  may safely make this same call; Firestore's transaction semantics
   *  ensure exactly one of them actually advances `currentRound`, and
   *  every other caller's attempt resolves harmlessly as
   *  `{advanced:false, reason:"ALREADY_ADVANCED"}` — never an error,
   *  never a duplicate round. A rejection for a genuine reason
   *  (`ROUND_NOT_COMPLETE` — this client's own local engine disagrees
   *  with the server about completion, which should never actually
   *  happen given both derive from the SAME replayed cardLog — or a
   *  network failure) is logged, never thrown into the caller's
   *  snapshot callback; a LATER delivery (or another client) gets
   *  another chance. */
  function maybeAdvanceRound(matchId, matchDoc) {
    if (!global.TableEngine || typeof global.TableEngine.getState !== "function") return;
    var state;
    try { state = global.TableEngine.getState(); } catch (e) { return; }
    if (!state || state.phase !== "DONE" || state.round == null) return;
    if (roundAdvanceAttemptedByMatch[matchId] === state.round) return;
    roundAdvanceAttemptedByMatch[matchId] = state.round;
    maybeExtendOrCompleteMatch(matchId, state.round);
  }

  // ── Match Completion sprint: Extension + Completion Orchestration ──
  /** Runs exactly once per round (gated by `maybeAdvanceRound()`'s own
   *  `roundAdvanceAttemptedByMatch` guard, above — this function is
   *  never a second entry point, just the continuation of the SAME
   *  "local TableEngine just reached DONE for round R" trigger).
   *
   *  Sequencing (mirrors the SAME "any client may attempt it; the
   *  transaction makes that safe" design as `advanceToNextRound()`):
   *   1. If round R's LOCAL result (GameSession.getLastRoundResult(),
   *      already computed and cached by ScoringEngine.applyRoundResult()
   *      — never re-derived here) qualifies for a maxRounds extension
   *      (`.roundExtension.extend`), attempt
   *      `MatchService.extendMatchRounds(matchId, R, reason)` FIRST —
   *      before deciding whether the match is over, since the
   *      extension can be exactly what keeps it alive.
   *   2. Whether or not step 1 ran, ask the LOCAL engine
   *      (`GameSession.isMatchComplete()`, which already reads the
   *      LOCAL `round.maxRounds` — bumped synchronously by
   *      `ScoringEngine.applyRoundResult()` the moment extension
   *      applied, with zero Firestore round-trip needed for THIS
   *      client's own decision) whether `R` was the match's last round.
   *   3. If complete: compute `finalScores`/`winnerIds` locally
   *      (`GameSession.getMatchScores()` / `ScoringEngine.computeWinner()`
   *      — the SAME authoritative functions every client independently
   *      runs against the SAME replicated score) and attempt
   *      `MatchService.endMatch(matchId, R, finalScores, winnerIds)`.
   *   4. Otherwise: attempt `MatchService.advanceToNextRound(matchId, R)`
   *      exactly as before this sprint.
   *  Every step is independently idempotent/safe-to-repeat (see each
   *  MatchService function's own doc comment) — a rejection here is
   *  logged, never thrown into the caller's snapshot callback; another
   *  client (or a later local retry) may still succeed. */
  function maybeExtendOrCompleteMatch(matchId, completedRound) {
    if (!global.MatchService) return;
    var lastResult = (global.GameSession && typeof global.GameSession.getLastRoundResult === "function")
      ? global.GameSession.getLastRoundResult() : null;
    var extension = (lastResult && lastResult.round === completedRound) ? lastResult.roundExtension : null;

    var extendStep = (extension && extension.extend && typeof global.MatchService.extendMatchRounds === "function")
      ? global.MatchService.extendMatchRounds(matchId, completedRound, extension.reason).catch(function (e) {
          console.error("[MatchAdapter] extendMatchRounds() attempt failed (non-fatal — another client, or a later delivery, may still succeed):", e);
        })
      : Promise.resolve();

    extendStep.then(function () {
      var complete = global.GameSession && typeof global.GameSession.isMatchComplete === "function" && global.GameSession.isMatchComplete();
      if (complete) {
        if (typeof global.MatchService.endMatch !== "function") return;
        var finalScores = global.GameSession.getMatchScores();
        var winnerIds = (global.ScoringEngine && typeof global.ScoringEngine.computeWinner === "function")
          ? global.ScoringEngine.computeWinner(finalScores) : [];
        global.MatchService.endMatch(matchId, completedRound, finalScores, winnerIds).then(function (result) {
          if (result && result.complete && typeof global.GameSession.setWinnerIds === "function") {
            global.GameSession.setWinnerIds(result.winnerIds);
          }
        }).catch(function (e) {
          console.error("[MatchAdapter] endMatch() attempt failed (non-fatal — another client, or a later delivery, may still succeed):", e);
        });
      } else {
        if (typeof global.MatchService.advanceToNextRound !== "function") return;
        global.MatchService.advanceToNextRound(matchId, completedRound).catch(function (e) {
          console.error("[MatchAdapter] advanceToNextRound() attempt failed (non-fatal — another client, or a later delivery, may still succeed):", e);
        });
      }
    });
  }

  /** Detects "the match document's own `currentRound` has moved past
   *  what THIS client's local GameSession knows about" and drives the
   *  LOCAL round-local reset — the read side of the schema decision in
   *  `MatchService.advanceToNextRound()`'s own doc comment. Uses ONLY
   *  existing, unmodified public APIs:
   *  - `GameSession.nextRound()` (pre-existing, already used by the
   *    single-player flow — never a new state store, exactly like this
   *    sprint's brief requires) — called once per round the local
   *    client is behind (a loop, not a single call, so a reconnecting
   *    client that missed MULTIPLE transitions catches up correctly in
   *    one delivery rather than needing one delivery per round).
   *  - `BiddingEngine.initState()` (pre-existing, already idempotent/
   *    safe-to-repeat — see bootstrapEngineOnce()'s own comment in
   *    match/index.html) — re-derives the new round's fresh config from
   *    GameSession, exactly like Round 1's own first call.
   *
   *  Deliberately does NOT touch `TableEngine` here — TableEngine's own
   *  re-initialization for the new round remains
   *  `maybeEnterPlayPhase()`'s job (match/index.html), triggered
   *  reactively once THIS round's real bidding ALSO reaches DONE,
   *  exactly mirroring Round 1's existing flow; this function's only
   *  job is making that flow possible for round 2+ by advancing
   *  BiddingEngine's own round number first.
   *
   *  CLOSED (Sprint F — Hand Synchronization on Reconnect). This
   *  comment used to document a real gap: calling `GameSession.
   *  nextRound()` clears the local `hands` map, and the next
   *  `ensureHandsDealt()` call (inside `BiddingEngine.initState()`)
   *  would deal a BRAND-NEW, independently-random hand for this
   *  client. Root cause (confirmed by direct source read, not
   *  assumed): `startHandSync()` — this file's own real, already
   *  fully-implemented and unit-tested (`tests/hand-sync.test.cjs`)
   *  per-seat hand sync — was simply never called anywhere in
   *  `design-ui/match/index.html`, so `GameSession`'s hand-authority
   *  mode stayed at its "local" default for the entire life of every
   *  real match, on every round, not just round transitions. Fixed by
   *  wiring `startHandSync(matchId, localSeatId)` into that page (plus
   *  an early `setHandAuthorityMode("firestore")` call before the
   *  first snapshot can arrive, closing a real ordering race between
   *  the diagnostic `ensureHandsDealt()` call in `bootstrapEngineOnce()`
   *  and this fix). Verified against a real Firestore/Auth Emulator
   *  and a real browser: reload, disconnect+reconnect, a round
   *  transition while disconnected, a late join, and a rematch all
   *  restore the exact authoritative hand — see
   *  `tests/hand-sync-reconnect.rules-emulator.test.cjs`. */
  function applyRemoteRoundTransition(matchId, matchDoc) {
    if (!matchId) return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    if (!matchDoc || typeof matchDoc !== "object" || Array.isArray(matchDoc)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }
    if (typeof matchDoc.currentRound !== "number" || !Number.isFinite(matchDoc.currentRound)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }
    if (!global.GameSession || typeof global.GameSession.getRound !== "function" ||
        typeof global.GameSession.nextRound !== "function") {
      return { applied: false, reason: "ENGINE_UNAVAILABLE" };
    }
    var GameSession = global.GameSession;
    var localRoundState = GameSession.getRound();
    var localNumber = localRoundState ? localRoundState.number : null;
    if (localNumber == null) return { applied: false, reason: "LOCAL_ROUND_UNKNOWN" };
    // Match Completion sprint: sync the AUTHORITATIVE maxRounds down to
    // this client whenever Firestore is ahead of the local value —
    // independent of whether currentRound itself also moved this
    // delivery. Every client's OWN engine independently arrives at the
    // same maxRounds the moment its own ScoringEngine.applyRoundResult()
    // processes the same qualifying round (see computeRoundExtension()),
    // but this keeps a client that hasn't caught up YET from making an
    // isMatchComplete() decision against a stale local ceiling in the
    // meantime — same "never trust a lagging local copy over the
    // synced document" principle as the round-number sync below.
    if (typeof matchDoc.maxRounds === "number" && Number.isFinite(matchDoc.maxRounds) &&
        matchDoc.maxRounds > (localRoundState.maxRounds || 18)) {
      GameSession.setRound({ maxRounds: matchDoc.maxRounds });
    }
    if (matchDoc.currentRound <= localNumber) {
      return { applied: false, reason: "NO_NEW_ROUND" };
    }
    var steps = matchDoc.currentRound - localNumber;
    for (var i = 0; i < steps; i++) GameSession.nextRound();
    if (global.BiddingEngine && typeof global.BiddingEngine.initState === "function") {
      try {
        global.BiddingEngine.initState();
      } catch (e) {
        console.error("[MatchAdapter] BiddingEngine.initState() threw during a round transition:", e);
        return { applied: false, reason: "ENGINE_THREW", matchId: matchId, error: e.message };
      }
    }
    return { applied: true, matchId: matchId, previousRound: localNumber, newRound: matchDoc.currentRound, steps: steps };
  }

  /** The round-transition-sync analog of `startBidSync()`/etc. —
   *  subscribes through the SAME `MatchService.subscribeToMatch()` (no
   *  second listener — same ref-counted registry every other
   *  `start*Sync()` function shares). Deliberately meant to be
   *  registered BEFORE `startBiddingActionSync()`/`startCardSync()`/
   *  `startTrickSync()` by the caller (match/index.html — mirrors the
   *  EXISTING documented ordering requirement for
   *  `startBiddingActionSync()`/`startBidSync()` relative to the render
   *  callback) so a delivery that carries BOTH a round bump AND that
   *  new round's first bidding entry applies the round transition
   *  first — though this is a latency optimization only, not a
   *  correctness requirement: `applyRemoteBiddingAction()`/
   *  `applyRemoteCard()`'s own AWAITING_ROUND_TRANSITION deferral
   *  (above) makes a one-delivery-late ordering self-correcting either
   *  way. Throws `MATCH_SERVICE_UNAVAILABLE` if `MatchService` isn't
   *  loaded. */
  function startRoundSync(matchId) {
    if (!matchId) throw adapterError("INVALID_ARGUMENT", "startRoundSync: matchId is required.");
    if (!global.MatchService || typeof global.MatchService.subscribeToMatch !== "function") {
      throw adapterError("MATCH_SERVICE_UNAVAILABLE", "startRoundSync: MatchService is not available on this page.");
    }
    return global.MatchService.subscribeToMatch(matchId, function (data, err) {
      if (err || !data) return;
      applyRemoteRoundTransition(matchId, data);
    });
  }

  // ── Match Completion sprint: Match Completion Sync ──────────────
  // matchId -> true once THIS adapter has already applied a remote
  // "status: complete" delivery — mirrors roundAdvanceAttemptedByMatch's
  // role but for the terminal, one-time-only completion event (there is
  // no "next" completion for the SAME client to catch, so a boolean is
  // enough — a round can advance/extend repeatedly across a match, but
  // a match can only ever complete once).
  var matchCompletionAppliedByMatch = {};

  /** Detects "the match document's own `status` is now `complete`" and
   *  drives the LOCAL read side: syncs `winnerIds` onto this client's
   *  `GameSession` even if THIS client's own `endMatch()` attempt lost
   *  the race (or was never attempted — e.g. a client that reconnects
   *  after the match already ended). Deliberately idempotent per
   *  matchId (see `matchCompletionAppliedByMatch` above) — a repeat
   *  delivery of the same completed document is a harmless no-op, never
   *  a second `setWinnerIds()` call. Uses ONLY the already-synced
   *  `matchDoc.winnerIds` — never recomputes them, never re-derives
   *  `finalScores` — this is a pure "trust the authoritative document"
   *  read, exactly like `applyRemoteRoundTransition()`'s own role for
   *  `currentRound`/`maxRounds`. */
  function applyRemoteMatchCompletion(matchId, matchDoc) {
    if (!matchId) return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    if (!matchDoc || typeof matchDoc !== "object" || Array.isArray(matchDoc)) {
      return { applied: false, reason: "MALFORMED_SNAPSHOT" };
    }
    if (matchDoc.status !== "complete") return { applied: false, reason: "NOT_COMPLETE" };
    if (matchCompletionAppliedByMatch[matchId]) return { applied: false, reason: "ALREADY_APPLIED" };
    if (!global.GameSession || typeof global.GameSession.setWinnerIds !== "function") {
      return { applied: false, reason: "ENGINE_UNAVAILABLE" };
    }
    matchCompletionAppliedByMatch[matchId] = true;
    var winnerIds = Array.isArray(matchDoc.winnerIds) ? matchDoc.winnerIds.slice() : [];
    global.GameSession.setWinnerIds(winnerIds);
    return { applied: true, matchId: matchId, winnerIds: winnerIds, finalScores: matchDoc.finalScores || {} };
  }

  /** The match-completion-sync analog of `startRoundSync()` — same
   *  single shared listener, no second subscription. Registered
   *  alongside `startRoundSync()` by match/index.html. */
  function startMatchCompletionSync(matchId) {
    if (!matchId) throw adapterError("INVALID_ARGUMENT", "startMatchCompletionSync: matchId is required.");
    if (!global.MatchService || typeof global.MatchService.subscribeToMatch !== "function") {
      throw adapterError("MATCH_SERVICE_UNAVAILABLE", "startMatchCompletionSync: MatchService is not available on this page.");
    }
    return global.MatchService.subscribeToMatch(matchId, function (data, err) {
      if (err || !data) return;
      applyRemoteMatchCompletion(matchId, data);
    });
  }

  // ══════════════════════════════════════════════════════════════════
  // Player Hand Synchronization sprint (Architecture Gate-approved
  // Option A). Two independent responsibilities, mirroring the split
  // already used everywhere else in this file:
  //  1. WATCH the already-active subscribeToMatch() listener (no
  //     second match-level listener) for `gameState.dealtRound` falling
  //     behind `currentRound`, and safely ATTEMPT
  //     `MatchService.dealRound()` — the exact same "any client may
  //     attempt it, the transaction makes it safe" shape
  //     maybeAdvanceRound()/maybeAdvanceRematchVote() already use.
  //  2. CONSUME this client's OWN seat's hand document (never any
  //     other seat's) via the new `MatchService.subscribeToHand()`,
  //     translating it INTO GameSession — the one genuinely new
  //     translation path this sprint adds.
  var dealAttemptedByMatch = {};

  /** Detects "this match's currentRound has no committed deal yet" and
   *  attempts EXACTLY ONE `MatchService.dealRound(matchId, currentRound)`
   *  call per round from THIS client — never a second call for the SAME
   *  round, mirroring `maybeAdvanceRound()`'s own
   *  `roundAdvanceAttemptedByMatch` guard exactly. Does not wait for
   *  confirmation that THIS call is the one that wins the transaction —
   *  every client that independently observes the same stale
   *  `dealtRound` may safely make this same call; Firestore's
   *  transaction semantics ensure exactly one attempt actually commits.
   *  A rejection (a genuine error, or another client's transaction
   *  already won) is swallowed here, never thrown into the caller's
   *  snapshot callback. */
  function maybeDealRound(matchId, matchDoc) {
    if (!global.MatchService || typeof global.MatchService.dealRound !== "function") return;
    if (typeof matchDoc.currentRound !== "number") return;
    var currentRound = matchDoc.currentRound;
    var gameState = matchDoc.gameState || { dealtRound: 0 };
    if ((gameState.dealtRound || 0) >= currentRound) return;
    if (dealAttemptedByMatch[matchId] === currentRound) return;
    dealAttemptedByMatch[matchId] = currentRound;
    global.MatchService.dealRound(matchId, currentRound).catch(function () {});
  }

  /** Reconstructs this seat's full, playable Card objects (id/
   *  displayName/value/owner/played — the EXACT shape Dealer.dealHands()
   *  already produces, via the SAME Cards.createCard()/Dealer.sortHand()
   *  calls, just given opaque {suit, rank} entries instead of a fresh
   *  shuffle) from the server-committed hand document, and pushes the
   *  result into GameSession as the CURRENT round's authoritative hand.
   *  Only ever called with THIS client's own seat/hand — never another
   *  seat's, since subscribeToHand() itself only ever delivers the one
   *  document this client subscribed to. */
  function applyRemoteHand(seatId, handDoc) {
    if (!handDoc || typeof handDoc !== "object") return { applied: false, reason: "NO_HAND_YET" };
    if (!Array.isArray(handDoc.cards) || typeof handDoc.round !== "number") {
      return { applied: false, reason: "MALFORMED_HAND" };
    }
    if (!global.GameSession || typeof global.GameSession.setAuthoritativeHand !== "function") {
      return { applied: false, reason: "ENGINE_UNAVAILABLE" };
    }
    if (!global.Cards || typeof global.Cards.createCard !== "function") {
      return { applied: false, reason: "ENGINE_UNAVAILABLE" };
    }
    var cards = handDoc.cards.map(function (c) { return global.Cards.createCard(c.suit, c.rank, seatId); });
    if (global.Dealer && typeof global.Dealer.sortHand === "function") cards = global.Dealer.sortHand(cards);
    global.GameSession.setAuthoritativeHand(seatId, cards, handDoc.round);
    return { applied: true, seatId: seatId, round: handDoc.round, count: cards.length };
  }

  /** The hand-sync analog of `startRoundSync()`/`startMatchCompletionSync()`
   *  — puts GameSession into "firestore" hand-authority mode (see
   *  session.js's own doc comment: `ensureHandsDealt()` never falls
   *  back to a local deal in this mode) before either subscription
   *  attaches, watches the ALREADY-ACTIVE `subscribeToMatch()` listener
   *  for the deal-needed signal (no second match-level listener), and
   *  separately subscribes to THIS seat's own hand document. Returns a
   *  combined unsubscribe tearing down both. Throws
   *  `MATCH_SERVICE_UNAVAILABLE` if `MatchService` isn't loaded;
   *  `INVALID_ARGUMENT` if `mySeatId` is missing (this sprint's whole
   *  point is that a client only ever learns its OWN seat's hand, so
   *  there is no sensible "sync all hands" call). */
  function startHandSync(matchId, mySeatId) {
    if (!matchId) throw adapterError("INVALID_ARGUMENT", "startHandSync: matchId is required.");
    if (!mySeatId) throw adapterError("INVALID_ARGUMENT", "startHandSync: mySeatId is required.");
    if (!global.MatchService || typeof global.MatchService.subscribeToMatch !== "function" ||
        typeof global.MatchService.subscribeToHand !== "function") {
      throw adapterError("MATCH_SERVICE_UNAVAILABLE", "startHandSync: MatchService is not available on this page.");
    }
    if (global.GameSession && typeof global.GameSession.setHandAuthorityMode === "function") {
      global.GameSession.setHandAuthorityMode("firestore");
    }
    var unsubscribeMatch = global.MatchService.subscribeToMatch(matchId, function (data, err) {
      if (err || !data) return;
      maybeDealRound(matchId, data);
    });
    var unsubscribeHand = global.MatchService.subscribeToHand(matchId, mySeatId, function (data, err) {
      if (err || !data) return;
      applyRemoteHand(mySeatId, data);
    });
    return function () {
      unsubscribeMatch();
      unsubscribeHand();
    };
  }
  // ══════════════════════════════════════════════════════════════════

  // ══════════════════════════════════════════════════════════════════
  // Post-Match Rematch Vote sprint. This subcollection carries ZERO
  // engine state (no bidding/card/trick concept applies to a vote) —
  // unlike every other sync function above, there is nothing here to
  // translate INTO BiddingEngine/TableEngine. This section's role is
  // instead the same one maybeExtendOrCompleteMatch() already plays
  // for round extension/completion: WATCH the synced document and, the
  // moment a structural condition becomes true, safely ATTEMPT the
  // next authoritative transaction — never itself deciding the outcome
  // (MatchService's own transaction + firestore.rules remain the sole
  // authority; a redundant/premature attempt from this watcher is
  // always a harmless, idempotent no-op on the server side). This is
  // exactly the mechanism that makes "no host — any seated client may
  // safely attempt timeout/all-YES/rematch-creation" true: EVERY
  // subscribed client's own copy of this watcher tries, and Firestore's
  // transaction semantics guarantee only one attempt ever actually
  // commits per transition.
  var lastRematchVoteByMatch = {};

  /** Diagnostic-only accessor — mirrors this file's established
   *  getLastAppliedVersion()-style convention. Returns the latest
   *  rematch-vote document this adapter has observed for a matchId, or
   *  null if none has been delivered yet. The UI renders directly from
   *  this — never recomputing vote outcome itself (see
   *  match/index.html's own renderRematchVote() comment). */
  function getRematchVoteState(matchId) {
    return lastRematchVoteByMatch[matchId] || null;
  }

  /** Watches the synced vote document and safely ATTEMPTS (never
   *  decides) the next authoritative step:
   *   - status "OPEN" past its own (locally-computed, non-authoritative)
   *     deadline -> attempt resolveRematchVoteTimeout()
   *   - status "ALL_YES" with no newMatchId yet -> attempt
   *     createRematchMatch()
   *  Both calls are transactional and idempotent on the MatchService
   *  side — calling either redundantly (this same watcher firing again
   *  on the next snapshot, or a DIFFERENT client's copy of this exact
   *  watcher racing this one) is always a safe no-op. Never throws —
   *  a rejected attempt (e.g. another client's transaction already
   *  won) is expected, normal traffic, not an error to surface. */
  function maybeAdvanceRematchVote(matchId, voteDoc) {
    if (!voteDoc || !global.MatchService) return;
    if (voteDoc.status === "OPEN") {
      var createdAtMs = voteDoc.createdAt && typeof voteDoc.createdAt.toMillis === "function" ? voteDoc.createdAt.toMillis() : null;
      if (createdAtMs != null && Date.now() >= createdAtMs + 30000) {
        global.MatchService.resolveRematchVoteTimeout(matchId).catch(function () {});
      }
      return;
    }
    if (voteDoc.status === "ALL_YES" && !voteDoc.newMatchId) {
      global.MatchService.createRematchMatch(matchId).catch(function () {});
    }
  }

  /** Registers the ref-counted rematch-vote subscription for a match
   *  and starts the watcher above. Shares MatchService's OWN ref-
   *  counting for this document path (see subscribeToRematchVote()'s
   *  own comment) — a second call for the same matchId never creates a
   *  second Firestore listener. */
  // Real production defect found and fixed during this sprint's own
  // browser QA: maybeAdvanceRematchVote()'s TIMEOUT branch can only
  // ever fire from INSIDE the subscription callback above — but if a
  // vote sits completely untouched (nobody votes at all) for the full
  // 30 seconds, NO new snapshot delivery ever arrives to re-invoke it,
  // so the timeout would never actually be attempted by anyone. A
  // periodic re-check timer closes this: while any local vote-doc
  // subscription is active, re-run maybeAdvanceRematchVote() against
  // the LAST DELIVERED (cached) doc every few seconds — this doesn't
  // need a new snapshot to notice real time has passed, since the
  // check itself is a pure function of the ALREADY-KNOWN `createdAt`
  // vs `Date.now()`. Cleared automatically once the vote reaches any
  // terminal status (nothing left to time out), and on unsubscribe.
  var rematchVoteTimeoutTimerByMatch = {};
  function startRematchVoteSync(matchId) {
    if (!matchId) throw adapterError("INVALID_ARGUMENT", "startRematchVoteSync: matchId is required.");
    if (!global.MatchService || typeof global.MatchService.subscribeToRematchVote !== "function") {
      throw adapterError("MATCH_SERVICE_UNAVAILABLE", "startRematchVoteSync: MatchService is not available on this page.");
    }
    if (!rematchVoteTimeoutTimerByMatch[matchId]) {
      rematchVoteTimeoutTimerByMatch[matchId] = setInterval(function () {
        var cached = lastRematchVoteByMatch[matchId];
        if (cached && cached.status === "OPEN") {
          maybeAdvanceRematchVote(matchId, cached);
        }
      }, 2000);
    }
    var unsubscribe = global.MatchService.subscribeToRematchVote(matchId, function (data, err) {
      if (err) return;
      lastRematchVoteByMatch[matchId] = data || null;
      maybeAdvanceRematchVote(matchId, data);
    });
    return function () {
      unsubscribe();
      if (rematchVoteTimeoutTimerByMatch[matchId]) {
        clearInterval(rematchVoteTimeoutTimerByMatch[matchId]);
        delete rematchVoteTimeoutTimerByMatch[matchId];
      }
    };
  }
  // ══════════════════════════════════════════════════════════════════

  global.MatchAdapter = {
    uidToSeat: uidToSeat,
    seatToUid: seatToUid,
    computeRoundStartLeaderUid: computeRoundStartLeaderUid,
    computeRoundStartLeaderFromPersistedState: computeRoundStartLeaderFromPersistedState,
    seatToPlayer: seatToPlayer,
    playerToSeat: playerToSeat,
    matchDocToEngineSnapshot: matchDocToEngineSnapshot,
    engineSnapshotToMatchPatch: engineSnapshotToMatchPatch,
    bootstrapGameSession: bootstrapGameSession,
    // Player Hand Synchronization sprint.
    applyRemoteHand: applyRemoteHand,
    startHandSync: startHandSync,
    applyRemoteBid: applyRemoteBid,
    startBidSync: startBidSync,
    getLastAppliedVersion: getLastAppliedVersion,
    // Sprint 3.7 (Online Bidding Synchronization Contract): Dash Call /
    // Auction Bid / Confirm Call. Final Estimate remains applyRemoteBid()'s
    // job, unchanged.
    applyRemoteBiddingAction: applyRemoteBiddingAction,
    startBiddingActionSync: startBiddingActionSync,
    getLastAppliedBiddingActionVersion: getLastAppliedBiddingActionVersion,
    getLastAppliedBiddingActionCount: getLastAppliedBiddingActionCount,
    applyRemoteTurn: applyRemoteTurn,
    isLocalSeatsTurn: isLocalSeatsTurn,
    assertLocalTurn: assertLocalTurn,
    startTurnSync: startTurnSync,
    getLastAppliedTurnVersion: getLastAppliedTurnVersion,
    applyRemoteCard: applyRemoteCard,
    startCardSync: startCardSync,
    getLastAppliedCardVersion: getLastAppliedCardVersion,
    getLastAppliedCardCount: getLastAppliedCardCount,
    applyRemoteTrick: applyRemoteTrick,
    startTrickSync: startTrickSync,
    getLastResolvedTrickNo: getLastResolvedTrickNo,
    // Round Lifecycle sprint: round-transition detection + the
    // one-attempt-per-round advance trigger. See each function's own
    // doc comment above.
    applyRemoteRoundTransition: applyRemoteRoundTransition,
    startRoundSync: startRoundSync,
    // Match Completion sprint: extension + completion orchestration and
    // the read-side sync for a remotely-completed match. See each
    // function's own doc comment above.
    maybeExtendOrCompleteMatch: maybeExtendOrCompleteMatch,
    applyRemoteMatchCompletion: applyRemoteMatchCompletion,
    startMatchCompletionSync: startMatchCompletionSync,
    // Post-Match Rematch Vote sprint.
    maybeAdvanceRematchVote: maybeAdvanceRematchVote,
    startRematchVoteSync: startRematchVoteSync,
    getRematchVoteState: getRematchVoteState,
    resetSyncState: resetSyncState
  };
})(window);
