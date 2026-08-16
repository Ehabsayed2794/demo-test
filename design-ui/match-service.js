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

   Sprint 3.8.1 (Bidding Validation & Rules Hardening) — a small,
   isolated hardening pass. Sprint 3.8's submitBid() validated WHO could
   write and WHEN, but never looked at the bid VALUE itself — a client
   could submit `bid: -5`, `bid: "four"`, `bid: NaN`, or `bid: 999` and
   it would sail through untouched (still opaque, still "gameplay
   legality is bidding-engine's job"). This sprint closes exactly that
   gap with GENERIC validation only — see isValidGenericBidValue()'s own
   comment and docs/architecture/BidValidation.md for the precise,
   deliberate line between "is this a well-formed trick-count-shaped
   number" (this sprint) and "is this a LEGAL bid for this seat in this
   auction" (still bidding-engine.js's job, still untouched, still not
   consulted anywhere in this file). firestore.rules' isValidBidSubmission()
   gained the identical check, independently — neither layer trusts the
   other alone, same as every other check in this file.

   Sprint 4.2 (Online Card Synchronization: Engine Authority) — this
   sprint's ONLY change to this file: submitCard(matchId, card) (Task
   1), a second real gameplay write, following submitBid()'s exact
   pattern with one deliberate difference. submitBid() takes an
   explicit seatId parameter (the caller already knows/claims their own
   seat) and verifies ownership after the fact; submitCard() takes ONLY
   `card` — no seatId parameter at all — and instead resolves the
   acting seat itself, from the calling uid, via
   `global.MatchAdapter.uidToSeat(match, callingUid)`. This is a
   DELIBERATE, NEW, documented dependency edge — MatchService now has a
   soft (typeof-guarded, no require()/import) reference to MatchAdapter
   for this ONE purpose — introduced because Sprint 4.2's own Task 1
   explicitly says "Calls MatchAdapter only," not because this file
   needed a new capability it invented for itself. It remains
   READ-ONLY, translation-only (uidToSeat() only ever reads
   `match.seats`, a plain object this transaction already has in hand —
   it makes no Firestore call, no engine call, no GameSession call) —
   MatchService still has ZERO dependency on GameSession, BiddingEngine,
   or TableEngine, in any direction, confirmed by this sprint's own
   forbidden-scope sweep. See docs/architecture/EngineAdapter.md's
   Sprint 4.2 section for the full account of why this one new edge is
   correct and why it does not reintroduce the "MatchService knows the
   engine" coupling this project has structurally avoided since Sprint
   3.4.

   `card` is stored exactly as passed — an OPAQUE, generically-shaped
   payload (a suit key + a rank value, nothing else — see
   isValidGenericCardValue() below). This function does not validate
   card LEGALITY (is this card actually in the player's hand, does it
   follow suit, is it even this seat's turn to play) — none of that is
   implemented here, per this sprint's explicit "only synchronize card
   plays, never duplicate table-engine.js's own rules" scope. It only
   enforces WHO may write WHERE, exactly analogous to submitBid().
   firestore.rules gained the identical generic-shape + seat-ownership +
   version-increment check, independently, as isValidCardSubmission()
   — neither layer trusts the other alone, same as every prior sprint.
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
   *  hand. UPDATED (Match Screen Engine Wiring sprint) — the claim this
   *  comment made through Sprint 3.4.x, that `Deck` doesn't exist, is
   *  STALE and was corrected here: `design-ui/engine/deck.js` has
   *  existed and been correctly integrated into `dealer.js` since
   *  Sprint 3.5 (see docs/architecture/GameEngine.md) — calling
   *  `Dealer.dealHands()` today does NOT throw. The reason this field
   *  is still a placeholder is different: `MatchService` is documented
   *  to remain Firestore-only and must never call into the engine
   *  directly (see this file's header comment) — dealing real hands
   *  into `matches/{matchId}` would mean either (a) `MatchService`
   *  itself calling `Dealer.dealHands()`, breaking that boundary, or
   *  (b) a schema/authority design for server-dealt (or per-client-
   *  dealt-and-synced) hands that has not been designed yet. Until one
   *  of those is deliberately decided, this remains an explicit TODO —
   *  see docs/implementation/MatchInitialization.md and
   *  docs/architecture/MatchLifecycle.md's own DEALING-phase note.
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
      // Match Completion sprint: the AUTHORITATIVE round ceiling. Starts
      // at 18 (rules §5, normal match length) and is incremented by
      // exactly 1 per qualifying Rapid-Round (14-18) event — see
      // extendMatchRounds() below. `currentRound >= maxRounds` (never a
      // hardcoded 18) is the ONLY match-completion condition — see
      // endMatch()'s own comment.
      maxRounds: 18,
      // Structural idempotency guard for extendMatchRounds(): each
      // completed round number may extend the match AT MOST once, no
      // matter how many clients independently detect the same
      // qualifying event and call extendMatchRounds() for it.
      extendedRounds: [],
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
      // Sprint 4.2 (Online Card Synchronization): the same "opaque,
      // generically-shaped sync primitive" treatment bids got in
      // Sprint 3.8, adapted for MANY sequential values per seat
      // instead of one. `cardLog` is an append-only history of every
      // card played this match so far ({seatId, card} tuples, in
      // submission order) — never rewritten, never cleared by this
      // sprint (trick/round-boundary clearing is trick-resolution
      // territory, explicitly out of scope — see submitCard()'s own
      // comment). `lastCardSeat` mirrors `lastBidSeat`'s role: which
      // seat the MOST RECENT entry belongs to, for a quick read
      // without inspecting the log's tail.
      cardLog: [],
      lastCardSeat: null,
      // Sprint 3.7 (Online Bidding Synchronization Contract): the SAME
      // "opaque, append-only action log" treatment `cardLog` got in
      // Sprint 4.2, applied to the three bidding sub-phases `bids` (the
      // Sprint 3.8 exactly-once-per-seat field, unchanged, still owns
      // Final Estimate only) cannot represent: Dash Call, Auction Bid,
      // and Confirm Call. Unlike a Final Estimate (exactly one value
      // per seat, ever), each of these three is a repeatable, ordered
      // ACTION a seat may take multiple times across an auction (e.g.
      // several raises before being eliminated) — structurally the same
      // shape problem `cardLog` already solved for repeatable card
      // plays, not a new pattern. See match-adapter.js's own Sprint 3.7
      // header section and docs/reviews/Sprint_3.7_Online_Bidding_Synchronization_Report.md
      // for the full schema rationale. Every entry is `{seatId,
      // actionType, ...opaque per-type fields}` — this file never
      // interprets what a legal Dash/Auction/Confirm action IS; that
      // remains bidding-engine.js's exclusive, untouched job.
      biddingLog: [],
      // Sprint 4.2.2 (Atomic Card Turn Progression & Card-Log Desync
      // Hardening), Task 2: the ONE minimal new field this sprint's
      // own "if the schema lacks a safe field for play phase, document
      // the minimum required change and implement only that minimum"
      // instruction calls for. No EXISTING field could safely double
      // for this: `biddingOpen` is bidding-specific (already owned by
      // Sprint 3.8's own semantics), and `gameState` is an untouched
      // placeholder object nothing in this codebase writes into yet.
      // `cardPhase` mirrors `TableEngine`'s own internal `state.phase`
      // values exactly (`"PLAY"` | `"RESOLVING"`) — `null` here means
      // "no card has been played yet" (bidding hasn't necessarily even
      // finished), set for real, atomically, alongside every accepted
      // `submitCard()` write (see that function's own comment).
      cardPhase: null,
      // Player Hand Synchronization sprint: `dealtRound` is the
      // authoritative "which round's hands are currently committed to
      // matches/{matchId}/hands/{seatId}" marker — 0 means "no round
      // has been dealt yet," matching `currentRound`'s own 1-based
      // start (round 1 is never dealtRound 1 until dealRound() actually
      // commits it). `initialized` flips true the instant Round 1's
      // deal commits — kept as its own field, not derived from
      // `dealtRound > 0`, so a future "match fully set up" signal isn't
      // forced to mean exactly the same thing as "round 1 is dealt"
      // forever. See docs/reviews/Player_Hand_Synchronization_Architecture_Report.md.
      gameState: {
        initialized: false,
        dealtRound: 0
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

  // Sprint 3.8.1 (Bidding Validation & Rules Hardening), Task 1.
  var MAX_BID_VALUE = 13; // the maximum possible trick count in a 13-card round — a structural fact, not a gameplay rule (see this file's header comment and docs/architecture/BidValidation.md's "Generic vs. Gameplay Validation" section for the exact line being drawn here).

  /** GENERIC bid-value validation — NOT gameplay validation. This
   *  checks only that `bid` is a well-formed number that could
   *  possibly represent a trick count in ANY round of this game: a
   *  finite integer from 0 to `MAX_BID_VALUE`. It does NOT know or
   *  care whether 13 is legal for THIS particular seat in THIS
   *  particular auction, whether it's this seat's turn, whether a
   *  Dash/With/Sa'ayda shape applies, or anything else `bidding-engine.js`
   *  alone is responsible for (untouched, unconsulted, unconnected —
   *  see docs/architecture/BidValidation.md). Deliberately rejects,
   *  by name, every value Task 1 lists: `null`/`undefined` (fail the
   *  `typeof bid === "number"` check outright), `NaN`/`Infinity`/
   *  `-Infinity` (fail `Number.isFinite`), non-integers (fail
   *  `Number.isInteger` — a partial trick is not a value this generic
   *  layer will ever accept, though this project's engine doesn't
   *  define one either), negative values, and anything above 13.
   *  Strings/objects/arrays/booleans all fail the initial `typeof`
   *  check, regardless of whether they'd coerce to a valid-looking
   *  number (`"4"` is rejected — this function never coerces). */
  function isValidGenericBidValue(bid) {
    return typeof bid === "number" && Number.isFinite(bid) && Number.isInteger(bid) && bid >= 0 && bid <= MAX_BID_VALUE;
  }

  /** A short, readable description of a rejected bid value for error
   *  messages — never used for the validation decision itself, only
   *  for a clearer `.message` (the `.reason` code is what callers
   *  should actually branch on — see bidError()). */
  function describeBidValue(bid) {
    if (bid === null) return "null";
    if (bid === undefined) return "undefined";
    if (typeof bid === "number" && Number.isNaN(bid)) return "NaN";
    if (typeof bid === "number" && !Number.isFinite(bid)) return String(bid); // Infinity / -Infinity
    if (typeof bid === "string") return "the string " + JSON.stringify(bid);
    if (typeof bid === "object") return "an object/array";
    return String(bid);
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
    // Sprint 3.8.1, Task 1: GENERIC bid-value validation, checked before
    // any Firestore access at all — fail fast, no wasted read/transaction
    // for a value that could never be valid. See isValidGenericBidValue()'s
    // own comment for exactly what this does and does not check.
    if (!isValidGenericBidValue(bid)) {
      return Promise.reject(bidError("INVALID_BID_VALUE",
        "submitBid: bid must be a finite integer between 0 and " + MAX_BID_VALUE + " (received " + describeBidValue(bid) + ")."));
    }
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
        // Sprint J.3 (Hardened Round-Start Turn Authority): this
        // Estimates-phase write is the REAL bidding-completion edge for
        // the dominant (someone-called) case — the ONLY thing
        // `advanceToNextRound()` ever wrote for `turn`/`cardPhase` was
        // `null`/`null`, with no write path back to a real value (see
        // Sprint J/J.1/J.2's forensic report and architecture review).
        // When THIS write is the one that makes every real seat's bid
        // present (`allSubmitted`), it ALSO establishes the real
        // first-trick leader — reusing the EXACT SAME formula
        // table-engine.js's own buildRoundCfg() already uses
        // (round callerId, else current turn, else dealer -- see
        // MatchAdapter.computeRoundStartLeaderUid()'s own comment for
        // the precise formula) rather than inventing a second source
        // of truth. By the time Estimates begins, a real Confirm has
        // always already happened
        // (Estimates-phase entry requires a caller — see bidding-engine.js;
        // the only way to reach subPhase DONE with no caller is the
        // DASH-phase's own direct-to-DONE branch, which never reaches
        // this function at all), so `round.callerId` is expected to
        // already be set correctly and truthfully — the dealer/turn
        // fallback exists only as the same defensive belt-and-braces
        // buildRoundCfg() itself already applies, never assumed to be
        // the common case here.
        // This file stays a pure Firestore-facing service with ZERO
        // direct GameSession/engine reference (an established layering
        // this project enforces via tests/turn-sync.test.cjs's own
        // "adapter isolation" check) — the actual leaderId computation
        // is brokered through MatchAdapter.computeRoundStartLeaderUid(),
        // exactly like every other engine-state question this file
        // already asks MatchAdapter/TableEngine instead of answering
        // itself (see submitCard()'s own uidToSeat()/seatToUid()/
        // TableEngine.previewPlay() calls for the same pattern).
        // Sprint J.7 (Post-Implementation Review fix): the round-start
        // guard in firestore.rules only ever allows this branch when
        // `oldData.turn == null && oldData.cardPhase == null` — i.e.
        // strictly the post-advanceToNextRound() state, never Round 1
        // (whose `turn` is a REAL dealer uid from buildInitialMatchDoc(),
        // never null). Before this fix, `allSubmitted` could never
        // become true for Round 1 anyway (the Caller's bid was never
        // persisted — see Sprint J.4/J.5.2), so this branch's own missing
        // `match.turn == null` guard was dormant, dead code. Fixing THAT
        // gap (this sprint) makes `allSubmitted` reachable for Round 1
        // too, which immediately exposed this pre-existing omission: a
        // real 4-client E2E run discovered Round 1's own completing
        // estimate now attempts a `turn`/`cardPhase` write Rules
        // correctly reject (since Round 1 never satisfies `oldData.turn
        // == null`) — a real regression, not a separate issue, fixed
        // here rather than deferred.
        if (allSubmitted && match.turn == null && match.cardPhase == null &&
            global.MatchAdapter && typeof global.MatchAdapter.computeRoundStartLeaderUid === "function") {
          // Sprint J.11 (post-review fix): `match` is the pre-write snapshot —
          // it does NOT yet contain this seat's own just-submitted bid (only
          // the local `bids` copy above does, via `patch.bids`). The fast-round
          // leader formula reads `matchDoc.bids` directly (unlike the old
          // GameSession-based formula, which never did), so passing bare
          // `match` here would hide exactly the completing bid — most acutely
          // when THIS bid is the round's Super Call. Pass the merged view.
          var leaderUid = global.MatchAdapter.computeRoundStartLeaderUid(Object.assign({}, match, { bids: bids }));
          if (leaderUid) {
            patch.turn = leaderUid;
            patch.cardPhase = "PLAY";
          }
        }
        tx.update(matchRef, patch);
        return { matchId: matchId, seatId: seatId, bid: bid, version: nextVersion, biddingOpen: !allSubmitted, allSubmitted: allSubmitted };
      });
    });
  }

  function submitPass(matchId, uid) { return notImplemented("submitPass"); }
  function declareTrump(matchId, uid, suit) { return notImplemented("declareTrump"); }
  function submitEstimate(matchId, uid, tricks) { return notImplemented("submitEstimate"); }

  // Sprint 4.2, Task 1 (Card Submission). Mirrors design-ui/engine/
  // cards.js's own createCard() shape, restricted to the two fields
  // this generic, gameplay-unaware validator can meaningfully check —
  // `id`/`displayName`/`value`/`owner`/`played` are all either
  // derivable from suit+rank or engine-internal bookkeeping this layer
  // has no business inventing or storing. See docs/architecture/
  // CardValidation.md-equivalent note in EngineAdapter.md's Sprint 4.2
  // section for the same "generic vs. gameplay" line Sprint 3.8.1 drew
  // for bids.
  var VALID_CARD_SUITS = ["SANS", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"];
  var MIN_RANK_VALUE = 2, MAX_RANK_VALUE = 14; // the real range design-ui/engine/cards.js's RANKS defines — a structural fact, not a gameplay rule

  /** GENERIC card-shape validation — NOT card legality. Checks only
   *  that `card` is a plain object naming a real suit key and a real
   *  rank value — nothing about whether this specific card is in this
   *  player's hand, whether it follows the currently-led suit, or
   *  whether it's even this seat's turn. All of that remains
   *  table-engine.js's exclusive, untouched job — see this function's
   *  own header comment. */
  function isValidGenericCardValue(card) {
    return !!card && typeof card === "object" && !Array.isArray(card)
      && typeof card.suit === "string" && VALID_CARD_SUITS.indexOf(card.suit) !== -1
      && !!card.rank && typeof card.rank === "object"
      && typeof card.rank.v === "number" && Number.isFinite(card.rank.v) && Number.isInteger(card.rank.v)
      && card.rank.v >= MIN_RANK_VALUE && card.rank.v <= MAX_RANK_VALUE;
  }

  /** Sprint 4.2, Task 1 (Card Submission), HARDENED in Sprint 4.2.1
   *  (Pre-Write Card Authority & Desync Safety) — the ONE public API
   *  for submitting a played card. Deliberately `(matchId, card)` — no
   *  `seatId` parameter at all, unlike submitBid()'s `(matchId, seatId,
   *  bid)` — see this file's header comment for exactly why: the
   *  acting seat is resolved INTERNALLY, from the calling uid, via
   *  `MatchAdapter.uidToSeat()` (Task 1's own "Calls MatchAdapter
   *  only"), never trusted as a client-supplied claim at all.
   *
   *  Sprint 4.2.1 CLOSED TWO CRITICAL DEFECTS a direct review found in
   *  Sprint 4.2's original version of this function:
   *
   *  1. This function never called ANY turn-authority check at all —
   *     any authenticated seat-owner could write a card regardless of
   *     whose turn it actually was. Fixed: `resolveSeatAndAuthorize()`
   *     below calls `MatchAdapter.assertLocalTurn()` — Sprint 4.1's
   *     EXISTING authority gate, called verbatim, never reimplemented
   *     here — BEFORE `runTransaction()` is ever invoked. A wrong-turn
   *     caller is rejected `NOT_YOUR_TURN` with ZERO Firestore writes
   *     attempted, verified directly in `tests/submit-card.test.cjs`.
   *  2. Card legality was checked only by the RECEIVING clients, after
   *     the card was already durably appended to `cardLog` — an
   *     engine-rejected card (wrong suit, not in hand) stayed in
   *     Firestore forever once written, since nothing here asked the
   *     engine BEFORE persisting. Fixed: this function now asks the
   *     REAL, existing `TableEngine.canPlayCard()` (a new, purely
   *     additive, non-mutating export — see table-engine.js's own
   *     Sprint 4.2.1 comment) whether the play would be accepted,
   *     BEFORE ever calling `runTransaction()`. An illegal card is
   *     rejected `ILLEGAL_CARD` with ZERO Firestore writes attempted —
   *     `cardLog` can no longer contain an entry the real engine would
   *     reject.
   *
   *  Still does NOT duplicate any gameplay rule — `canPlayCard()` is a
   *  pure query against `table-engine.js`'s own, unmodified internal
   *  `isLegal()`/`state.turn`/`state.phase` — this function never
   *  computes follow-suit or turn-order itself, it only reads the real
   *  engine's own answer. `card` is still stored as an OPAQUE payload
   *  (a generic shape check only, same as Sprint 4.2) — this function
   *  still doesn't know or invent WHY a card is legal, only whether the
   *  real engine says it is.
   *
   *  HONEST LIMITATION, stated here rather than hidden: `canPlayCard()`
   *  is checked against the LOCAL browser's own `TableEngine` instance
   *  ONCE, before the Firestore transaction begins — it is not re-
   *  checked on every transaction retry (unlike the turn-authority
   *  check, which IS re-verified against a fresh document read inside
   *  the transaction — see `resolveSeatAndAuthorize()`). This is
   *  correct for what a version conflict on THIS document actually
   *  represents (a concurrent Firestore write, not a change to this
   *  client's own local hand) — but it does mean a genuinely
   *  concurrent LOCAL engine mutation (e.g. a remote card arriving via
   *  `applyRemoteCard()` between this validation and the transaction's
   *  commit, changing the led suit or advancing the turn away from
   *  this seat) is a residual race this Spark-only, client-authoritative
   *  design cannot fully close without a Cloud Function serializing
   *  both the authority AND the persistence in one atomic server-side
   *  step — explicitly out of this hotfix's scope ("Spark only," "Do
   *  not add Cloud Functions"). Recorded here per this project's
   *  "state a real gap plainly" convention, not claimed to be solved. */
  /** Sprint 4.2.2 (Atomic Card Turn Progression & Card-Log Desync
   *  Hardening) — THIRD hardening pass over this function, closing the
   *  gap a direct review found in Sprint 4.2.1's own version: this
   *  function appended a card but NEVER updated `matches/{matchId}.turn`
   *  — meaning the NEXT real player was rejected `NOT_YOUR_TURN`
   *  against a Firestore document that still named the PREVIOUS
   *  player as active, and every prior test hid this by manually
   *  calling a test-only `setTurn()` helper between submissions. No
   *  equivalent production write ever existed. Fixed by:
   *
   *  1. (Task 1) `TableEngine.previewPlay(seatId, card)` — a new, pure,
   *     non-mutating export (see table-engine.js's own Sprint 4.2.2
   *     comment) that answers, WITHOUT calling `emit()`: is this card
   *     legal, and if so, what seat plays next (or `null` if this is
   *     the 4th card of the trick) and what phase follows (`"PLAY"` or
   *     `"RESOLVING"`). Reuses `canPlayCard()`'s own legality answer
   *     and the exact same `state.plays.length` / `nextCCW()`
   *     arithmetic `emit()` already performs — no new rule.
   *  2. (Task 2) The SAME Firestore transaction that appends the card
   *     ALSO writes the next turn (translated seat -> uid via
   *     `MatchAdapter.seatToUid()`, or `null` at the resolving
   *     boundary) and a new, minimal schema field, `cardPhase`
   *     (`"PLAY"` | `"RESOLVING"` | `null` before any card is played)
   *     — the SMALLEST schema addition that could carry
   *     `previewPlay()`'s `nextPhase` answer; no existing field could
   *     safely double for this (`biddingOpen` is bidding-specific;
   *     `gameState` is an untouched placeholder object). One write, one
   *     transaction, no second "move the turn" call anywhere.
   *  3. (Task 3) Because the preview is computed from the LOCAL
   *     browser's own `TableEngine` state, BEFORE the transaction even
   *     opens, this function captures the pre-check document's
   *     `version` and refuses to trust that preview if the FRESH,
   *     in-transaction read shows a DIFFERENT version — including on a
   *     transaction retry the Firestore SDK itself may trigger
   *     automatically on a write conflict. A version mismatch means
   *     the world this preview was computed against no longer exists;
   *     rather than silently recomputing or reusing a stale answer,
   *     this function rejects `STALE_GAME_STATE` and writes nothing —
   *     "let the client receive the latest snapshot and retry
   *     manually," per this sprint's own explicit instruction. This is
   *     a DELIBERATE, STRICTER departure from `submitBid()`'s own
   *     optimistic-retry pattern (which re-validates and proceeds on a
   *     version conflict) — for cards specifically, the thing being
   *     validated (engine legality/turn-order) was computed OUTSIDE
   *     Firestore's own transaction machinery, so re-running the
   *     transaction callback alone cannot safely re-validate it; only
   *     a fresh `previewPlay()` call against fresh engine state could,
   *     and this function deliberately does not do that automatically. */
  // ── Sprint J.10.9 (Bounded Server-Sourced Reconciliation) ─────────
  // Root cause (J.10.5/J.10.6/J.10.7/J.10.8, SNAPSHOT_ORDERING): the
  // pre-transaction check below (resolveSeatAndAuthorize()) used to be
  // a TERMINAL rejection — if this client's own listener-fed local
  // state (matchDoc.turn read from a cached-or-default get(), and/or
  // TableEngine's own state) lagged the true server state, submitCard()
  // returned NOT_YOUR_TURN and NEVER reached db().runTransaction() at
  // all, even though that transaction's own tx.get() is unconditionally
  // server-fresh and would have correctly authorized a genuinely
  // legitimate action.
  //
  // Fix (approved architecture, J.10.6, empirically validated J.10.7/
  // J.10.8): on a local turn rejection, perform exactly ONE bounded,
  // single-flighted, server-sourced refresh + reconciliation pass
  // through the EXISTING applyRemoteCard()/applyRemoteTrick() functions
  // (the same two functions startTrickSync() already alternates,
  // proven exactly-once/idempotent/order-safe by J.10.8's real-engine
  // tests) before retrying the local check ONCE. This is deliberately
  // NOT a listener re-registration (J.10.5 already proved that can be
  // inert and can leak callbacks) and deliberately does NOT reconcile
  // from inside a Firestore transaction callback (J.10.8's debugger
  // review: a transaction's own callback may be silently re-invoked by
  // the SDK on write-conflict retry, and applyRemoteCard()/
  // applyRemoteTrick() have global-singleton side effects unsafe to
  // replay unguarded on such a retry — this reconciliation only ever
  // runs OUTSIDE any transaction, exactly once per triggering call).
  //
  // J.10.8's empirical proof gate found a genuine, reproducible
  // near-zero-delay race: a client with an ALREADY-ACTIVE listener on
  // the same document, calling get({source:"server"}) immediately
  // after another client's concurrent write, returned STALE data in
  // 4 of 6 trials — closing entirely with a modest ~20ms delay. The
  // mandatory minimum delay below (applied BEFORE the forced read AND
  // BEFORE the retry) is the required mitigation for that exact race —
  // not an arbitrary tuning knob.
  var SERVER_REFRESH_MIN_DELAY_MS = 25;

  function delayMs(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  // Per-matchId single-flight map: N concurrent triggers for the SAME
  // match collapse into ONE actual server read + reconciliation pass,
  // all sharing the same Promise. The map entry is deleted the moment
  // the flight settles (success OR failure) — never permanently
  // retained, so a LATER, independent trigger always gets a fresh
  // attempt rather than being starved forever. Never keyed globally
  // across matches — a stale/slow match must never block another.
  var pendingServerRefreshByMatch = {};

  /** Performs the bounded reconciliation pass described above for one
   *  matchId, sharing an in-flight attempt across concurrent callers.
   *  Resolves with the fresh, server-sourced matchDoc data. Never
   *  touches any listener/subscription; never runs inside a Firestore
   *  transaction; reuses the EXISTING applyRemoteCard()/
   *  applyRemoteTrick() functions and their own existing version/count/
   *  trick-number registries verbatim — no second version registry,
   *  no new state-mutation path. */
  function refreshFromServerAndReconcile(matchId, matchRef) {
    if (pendingServerRefreshByMatch[matchId]) return pendingServerRefreshByMatch[matchId];

    var flight = delayMs(SERVER_REFRESH_MIN_DELAY_MS)
      .then(function () { return matchRef.get({ source: "server" }); })
      .then(function (freshSnap) {
        if (!freshSnap.exists) return null;
        var freshMatch = freshSnap.data();
        // Mirrors startTrickSync()'s own bounded, exactly-once-proven
        // alternation (design-ui/match-adapter.js) — capped at 13
        // iterations, the maximum possible tricks in a single round,
        // never re-invoked here as a listener, only as a one-shot
        // catch-up pass against this ONE freshly-read document.
        if (global.MatchAdapter && typeof global.MatchAdapter.applyRemoteCard === "function" &&
            typeof global.MatchAdapter.applyRemoteTrick === "function") {
          for (var i = 0; i < 13; i++) {
            global.MatchAdapter.applyRemoteCard(matchId, freshMatch);
            var trickResult = global.MatchAdapter.applyRemoteTrick(matchId, freshMatch);
            if (!trickResult || !trickResult.applied) break;
          }
        }
        return freshMatch;
      })
      .then(function (freshMatch) {
        return delayMs(SERVER_REFRESH_MIN_DELAY_MS).then(function () { return freshMatch; });
      })
      .finally(function () { delete pendingServerRefreshByMatch[matchId]; });

    pendingServerRefreshByMatch[matchId] = flight;
    return flight;
  }

  function submitCard(matchId, card) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "submitCard: matchId is required."));
    if (!isValidGenericCardValue(card)) {
      return Promise.reject(bidError("INVALID_CARD_VALUE",
        "submitCard: card must be a plain object with a real suit key and a rank.v between " + MIN_RANK_VALUE + " and " + MAX_RANK_VALUE + "."));
    }
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "submitCard: no signed-in user."));
    if (!global.MatchAdapter || typeof global.MatchAdapter.uidToSeat !== "function" ||
        typeof global.MatchAdapter.seatToUid !== "function" || typeof global.MatchAdapter.assertLocalTurn !== "function") {
      return Promise.reject(bidError("MATCH_ADAPTER_UNAVAILABLE", "submitCard: MatchAdapter is not available on this page."));
    }
    // Task 1/2: the real engine must be reachable to preview BEFORE any
    // write is attempted — no fallback, no silent skip. If the real
    // engine can't be asked, this function refuses to write blind.
    if (!global.TableEngine || typeof global.TableEngine.previewPlay !== "function") {
      return Promise.reject(bidError("ENGINE_UNAVAILABLE", "submitCard: TableEngine is not available on this page — cannot validate this card before writing it."));
    }

    var matchRef = db().collection("matches").doc(matchId);

    /** Task 1/3's gate, factored so it can run BOTH as the upfront,
     *  pre-transaction check ("reject before transaction/write") AND
     *  again inside the transaction against a freshly-read document
     *  (defense in depth against a race between the two reads) — the
     *  SAME two calls, never two different implementations. Resolves
     *  the seat via `MatchAdapter.uidToSeat()`, then calls the
     *  EXISTING `MatchAdapter.assertLocalTurn()` authority gate — never
     *  reimplements turn logic itself, per Task 1's explicit
     *  instruction. Throws a `bidError()`-shaped structured error
     *  (this file's own established convention) rather than exposing
     *  `assertLocalTurn()`'s own raw `NOT_LOCAL_TURN` reason. */
    function resolveSeatAndAuthorize(match) {
      var seatId = global.MatchAdapter.uidToSeat(match, callingUid);
      if (!seatId) {
        throw bidError("PERMISSION_DENIED", "submitCard: you do not own a seat in this match.");
      }
      try {
        global.MatchAdapter.assertLocalTurn(match, seatId);
      } catch (e) {
        throw bidError("NOT_YOUR_TURN", "submitCard: it is not seat '" + seatId + "'s turn right now.");
      }
      return seatId;
    }

    // Sprint J.10.9: the shared "we now have a seatId + a legal preview
    // + a matchDoc to use as expectedVersion — proceed into the write"
    // tail, factored so both the immediate-success path and the
    // post-reconciliation path funnel through the exact same logic
    // (never two different implementations of "how to write a card").
    function finishWithPreview(seatId, match) {
      // Task 1/2: asks the REAL, existing TableEngine whether THIS
      // exact play would be accepted, and — if so — what turn/phase
      // follows. Never mutates anything, never calls emit(), never
      // duplicates isLegal()'s own rule. Rejects here — still BEFORE
      // runTransaction() — for an illegal card. Zero writes attempted;
      // `cardLog` is never touched. CARD LEGALITY IS NEVER BYPASSED:
      // this is the ONE place that decision is made, on every path,
      // including the post-reconciliation retry path below.
      var preview = global.TableEngine.previewPlay(seatId, card);
      if (!preview || !preview.legal) {
        throw bidError("ILLEGAL_CARD", "submitCard: table-engine.js rejected this card (" + (preview && preview.reason) + ") — not written.");
      }
      // Translate the preview's engine-space next SEAT into a
      // Firestore-space next UID via MatchAdapter — the SAME
      // translation direction `uidToSeat()` already establishes this
      // file is allowed to use, just inverted.
      //
      // Sprint I.2 (Turn Authority / Trick-Boundary Fix): `preview.
      // nextTurnSeat` is no longer ever `null` at the resolving
      // boundary (the 4th card of a trick) — `TableEngine.previewPlay()`
      // now returns the REAL trick winner's seat there (see that
      // function's own comment for how, and why this closes the
      // permanent `oldData.turn == request.auth.uid` deadlock Sprint I's
      // forensic report identified: writing `turn: null` left NO write
      // path that could ever set it back to a real uid). The `!= null`
      // guard and the `null` initial value below are kept as-is —
      // defensive, not dead code — so this function still degrades
      // safely (writes `turn: null`, matching this schema's own
      // pre-first-card convention) if a future engine change ever
      // legitimately has no next-seat answer to give.
      var nextTurnUid = null;
      if (preview.nextTurnSeat != null) {
        nextTurnUid = global.MatchAdapter.seatToUid(match, preview.nextTurnSeat);
        if (!nextTurnUid) {
          // Defensive: the engine named a seat this match's own seats
          // map doesn't recognize — should never happen against a
          // real, correctly-seated match, but this function never
          // trusts an engine answer blindly into a write either.
          throw bidError("UNKNOWN_NEXT_SEAT", "submitCard: table-engine.js's next seat ('" + preview.nextTurnSeat + "') is not a real seat in this match.");
        }
      }
      var expectedVersion = match.version;

      return db().runTransaction(function (tx) {
        return tx.get(matchRef).then(function (freshSnap) {
          if (!freshSnap.exists) throw bidError("MATCH_NOT_FOUND", "submitCard: match '" + matchId + "' was not found.");
          var freshMatch = freshSnap.data();
          // Task 3: the preview above was computed against the LOCAL
          // engine's own state, entirely OUTSIDE this transaction —
          // if the document has changed AT ALL since the pre-check
          // read (whether from a genuine conflicting write, or from
          // the Firestore SDK's own automatic transaction retry on a
          // write conflict re-invoking this very callback), that
          // preview no longer describes the current world and MUST
          // NOT be trusted silently. Reject and stop — never
          // recompute a new preview here, never retry this gameplay
          // action automatically; the caller decides whether to
          // re-fetch and resubmit as a genuinely new attempt.
          if (freshMatch.version !== expectedVersion) {
            throw bidError("STALE_GAME_STATE", "submitCard: the match document changed since this card was validated (expected version " + expectedVersion + ", found " + freshMatch.version + ") — not written; re-fetch and retry.");
          }
          // Re-verify the SAME authority gate against the FRESH read —
          // "neither layer trusts the other alone," applied here for
          // the first time to a check that must happen BEFORE, not
          // merely alongside, the write. (Version-equal implies this
          // should already hold — re-checked anyway, defensively.)
          var freshSeatId = resolveSeatAndAuthorize(freshMatch);
          var cardLog = (freshMatch.cardLog || []).slice();
          // Round Lifecycle sprint: same round-stamp as buildBiddingLogEntry()
          // above, for the identical reason — `cardLog` is the other
          // never-cleared, append-only log this schema decision applies
          // to. Read from the FRESH in-transaction document's own
          // `currentRound`, never the caller's local round number.
          cardLog.push({ seatId: freshSeatId, card: { suit: card.suit, rank: { v: card.rank.v, s: card.rank.s } }, round: freshMatch.currentRound });
          var nextVersion = expectedVersion + 1;
          var patch = {
            cardLog: cardLog,
            lastCardSeat: freshSeatId,
            turn: nextTurnUid,
            cardPhase: preview.nextPhase,
            version: nextVersion,
            updatedAt: serverTimestamp()
          };
          tx.update(matchRef, patch);
          return {
            matchId: matchId, seatId: freshSeatId, card: card, version: nextVersion, cardCount: cardLog.length,
            nextTurnSeat: preview.nextTurnSeat, cardPhase: preview.nextPhase
          };
        });
      });
    }

    return matchRef.get().then(function (snap) {
      if (!snap.exists) throw bidError("MATCH_NOT_FOUND", "submitCard: match '" + matchId + "' was not found.");
      var match = snap.data();

      // Task 1: the ORIGINAL local pre-check — BEFORE runTransaction()
      // is ever called. Sprint J.10.9: this check is no longer
      // unconditionally terminal on a turn-authority failure — see
      // below. A seat-ownership failure (PERMISSION_DENIED — this uid
      // holds no seat in this match at all) is NOT a staleness
      // condition and remains immediately terminal, unchanged.
      var seatId, localAuthError = null;
      try {
        seatId = resolveSeatAndAuthorize(match);
      } catch (e) {
        localAuthError = e;
      }

      if (!localAuthError) {
        return finishWithPreview(seatId, match);
      }
      if (localAuthError.reason !== "NOT_YOUR_TURN") {
        throw localAuthError;
      }

      // Sprint J.10.9: the local pre-check reports a turn-authority
      // conflict. Per the approved architecture, this is now ADVISORY,
      // not terminal — attempt exactly ONE bounded, single-flighted,
      // server-sourced reconciliation pass, then retry once.
      return refreshFromServerAndReconcile(matchId, matchRef).then(function (freshMatch) {
        if (!freshMatch) {
          // The server-sourced refresh found the document gone —
          // genuinely terminal, not a staleness condition.
          throw bidError("MATCH_NOT_FOUND", "submitCard: match '" + matchId + "' was not found.");
        }

        // Sprint J.10.9 code-review finding (CRITICAL, fixed before
        // shipping): refreshFromServerAndReconcile()'s bounded
        // applyRemoteCard()/applyRemoteTrick() alternation deliberately
        // mirrors ONLY startTrickSync()'s own per-trick catch-up loop —
        // it never calls maybeAdvanceRound(), the SEPARATE mechanism
        // that actually performs a round transition (bidding-engine
        // re-init, a freshly dealt hand). If this client's local
        // TableEngine was stale by a FULL ROUND (not just a trick/turn
        // boundary), applyRemoteCard()/applyRemoteTrick() correctly
        // DEFER (AWAITING_ROUND_TRANSITION — see
        // tests/j109-bounded-reconciliation.test.cjs Test I) rather
        // than converging — meaning `freshMatch` here can be genuinely
        // fresh (the correct, new-round document) while the LOCAL
        // engine's own hand/trick-in-progress state (including which
        // suit currently leads the trick) still reflects the PREVIOUS
        // round. `resolveSeatAndAuthorize()`
        // only checks `freshMatch.turn` (a Firestore field, unaffected
        // by this), so it could WRONGLY appear to pass even though
        // `previewPlay()` would validate legality against the stale,
        // wrong-round engine state — the exact "card legality bypassed"
        // outcome this sprint's own non-negotiable rule forbids. Detect
        // this explicitly and refuse to trust ANY reconciliation-based
        // decision (turn OR legality) when the engine's own round
        // hasn't actually converged to the fresh document's round —
        // this is not a staleness this bounded, single-round-scoped
        // reconciliation can resolve safely, so it is NOT retried
        // further here; the ORIGINAL local rejection is preserved.
        if (global.TableEngine && typeof global.TableEngine.getState === "function") {
          var engineState = global.TableEngine.getState();
          if (engineState && engineState.round != null && freshMatch.currentRound != null &&
              engineState.round !== freshMatch.currentRound) {
            throw localAuthError;
          }
        }

        // Card legality (via previewPlay(), which also encodes
        // TableEngine's OWN deterministic turn belief — see
        // canPlayCard()) is re-evaluated against the RECONCILED local
        // engine and is NEVER bypassed, on any path. This is resolved
        // via this client's OWN seat, independent of whichever seatId
        // the original (pre-reconciliation) authorization attempt used.
        var ownSeatId = global.MatchAdapter.uidToSeat(freshMatch, callingUid);
        if (!ownSeatId) {
          throw bidError("PERMISSION_DENIED", "submitCard: you do not own a seat in this match.");
        }

        var retryAuthError = null;
        try {
          resolveSeatAndAuthorize(freshMatch);
        } catch (e) {
          retryAuthError = e;
        }

        if (!retryAuthError) {
          // Reconciliation resolved the staleness — the fresh doc's
          // OWN turn field now agrees this seat may act. Proceed
          // normally, using the confirmed-fresh document.
          return finishWithPreview(ownSeatId, freshMatch);
        }

        // The Firestore-field turn check STILL disagrees even after
        // reconciliation. Before deciding whether this is the
        // documented "wrong-but-real-seat" edge (matchDoc.turn — a
        // value some OTHER client's own completing write computed and
        // persisted, see docs/architecture/SecurityArchitecture.md) or
        // a genuine, confirmed wrong-turn attempt, consult card
        // legality (previewPlay(), which also encodes TableEngine's
        // OWN deterministic turn belief — see canPlayCard()) FIRST.
        // This never bypasses legality: it is checked here, directly,
        // before any decision about the transaction is made.
        var reconciledPreview = global.TableEngine.previewPlay(ownSeatId, card);
        if (!reconciledPreview || !reconciledPreview.legal) {
          // Both independent signals (the Firestore turn field AND the
          // freshly-reconciled local engine) agree this is not a
          // legitimate action. Preserve the ORIGINAL, more specific
          // error (typically NOT_YOUR_TURN) rather than relabeling a
          // confirmed turn-authority rejection as a generic
          // ILLEGAL_CARD — this keeps the error taxonomy this
          // codebase's tests/UI already depend on unchanged for the
          // ordinary (non-stale) wrong-turn case.
          throw retryAuthError;
        }

        // The documented "wrong-but-real-seat" edge: the RECONCILED
        // LOCAL ENGINE independently confirms this exact play is
        // legal, but the separate Firestore `turn` field still
        // disagrees. Let the transaction's own tx.get() +
        // resolveSeatAndAuthorize(freshMatch) be the TRUE terminal
        // authority (unchanged, below) — never bypassing card
        // legality, which was just independently reconfirmed above.
        return finishWithPreview(ownSeatId, freshMatch);
      });
    });
  }

  // ── Sprint 3.7 (Online Bidding Synchronization Contract) ─────────
  // Dash Call, Auction Bid, and Confirm Call synchronization. `bids`/
  // `submitBid()`/`biddingOpen` (Sprint 3.8, unchanged, untouched by
  // this sprint) remain the ONLY mechanism for Final Estimate — that
  // shape (exactly one value per seat, ever) was always correct for
  // Final Estimate and stays exactly as-is. Dash/Auction/Confirm are
  // structurally different: a seat may act on the SAME sub-phase
  // multiple times (e.g. several raises during one auction before
  // being eliminated), which a "one value per seat" field cannot
  // represent without data loss. This mirrors `cardLog`'s own
  // Sprint 4.2 "opaque, append-only action log" solution to the exact
  // same shape problem, applied here to bidding instead of card play.
  var VALID_BIDDING_ACTION_TYPES = ["SubmitDashCallDecision", "SubmitAuctionBid", "SubmitConfirmCall"];
  var VALID_BIDDING_SUITS = ["SANS", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"];

  /** GENERIC bidding-action shape validation — NOT bidding legality.
   *  Mirrors isValidGenericBidValue()/isValidGenericCardValue()'s own
   *  established line exactly: checks only that `action` is a
   *  well-formed object naming one of the three known action types,
   *  with the right TYPES (and structurally-generic RANGES) for
   *  whichever fields that type carries. It does NOT know or care
   *  whether this action is legal for this seat right now, whose turn
   *  it is, suit-strength, Dash limits, Forbidden-13, With-floor, or
   *  the Caller's cap — every one of those remains bidding-engine.js's
   *  exclusive, unconsulted, untouched job (see
   *  BiddingEngine.canSubmit(), called separately, BEFORE any write is
   *  attempted — see submitBiddingAction() below). */
  function isValidGenericBiddingAction(action) {
    if (!action || typeof action !== "object" || Array.isArray(action)) return false;
    if (VALID_BIDDING_ACTION_TYPES.indexOf(action.actionType) === -1) return false;
    if (action.declaredDashCall !== undefined && typeof action.declaredDashCall !== "boolean") return false;
    if (action.isPass !== undefined && typeof action.isPass !== "boolean") return false;
    if (action.tricks !== undefined && action.tricks !== null) {
      if (typeof action.tricks !== "number" || !Number.isFinite(action.tricks) || !Number.isInteger(action.tricks)) return false;
      if (action.tricks < 0 || action.tricks > MAX_BID_VALUE) return false;
    }
    if (action.suit !== undefined && action.suit !== null && VALID_BIDDING_SUITS.indexOf(action.suit) === -1) return false;
    // Per-actionType REQUIRED-field presence — still a generic shape
    // check (is the right field even THERE), never a legality
    // decision about its VALUE.
    if (action.actionType === "SubmitDashCallDecision" && typeof action.declaredDashCall !== "boolean") return false;
    if (action.actionType === "SubmitAuctionBid") {
      if (typeof action.isPass !== "boolean") return false;
      if (!action.isPass && (action.tricks == null || action.suit == null)) return false;
    }
    if (action.actionType === "SubmitConfirmCall" && (action.tricks == null || action.suit == null)) return false;
    return true;
  }

  /** Builds the CANONICAL, minimal `biddingLog` entry for one accepted
   *  action — only the fields that action type actually carries, never
   *  whatever extra fields a caller's `action` object happened to
   *  include (mirrors submitCard()'s own "card is stored as an OPAQUE,
   *  reconstructed payload, never the raw caller object" convention). */
  function buildBiddingLogEntry(seatId, action, round) {
    var entry = { seatId: seatId, actionType: action.actionType };
    if (action.actionType === "SubmitDashCallDecision") {
      entry.declaredDashCall = action.declaredDashCall;
    } else if (action.actionType === "SubmitAuctionBid") {
      entry.isPass = !!action.isPass;
      if (!entry.isPass) { entry.tricks = action.tricks; entry.suit = action.suit; }
    } else if (action.actionType === "SubmitConfirmCall") {
      entry.tricks = action.tricks;
      entry.suit = action.suit;
    }
    // Round Lifecycle sprint: stamp EVERY new entry with the round it
    // was written for — read from the FRESH, in-transaction document's
    // OWN `currentRound` field (never the caller's local, possibly
    // stale, GameSession round number) — the same "server decides, never
    // trust the client's claim" principle `version` already establishes.
    // This is the schema addition (Option A, see the Round Lifecycle
    // architecture report) that lets a single, never-cleared,
    // append-only `biddingLog` safely carry multiple rounds' worth of
    // entries: `MatchAdapter`'s catch-up loop can now tell "is this
    // entry for the round I'm currently on" without needing a second
    // array, a subcollection, or a destructive reset at the round
    // boundary — see match-adapter.js's own Round Lifecycle section for
    // the read side of this contract.
    entry.round = round;
    return entry;
  }

  /** Translates a `biddingLog` entry into the exact `BiddingEngine`
   *  intent shape `emit()`/`canSubmit()` both already accept —
   *  `actionType` IS the engine's own `intent.type` string, reused
   *  verbatim (not a second, parallel vocabulary to keep in sync) —
   *  see docs/reviews/Sprint_3.7_Online_Bidding_Synchronization_Report.md
   *  §4 for why. */
  function biddingActionToIntent(seatId, action) {
    var intent = { type: action.actionType, playerId: seatId };
    if (action.actionType === "SubmitDashCallDecision") {
      intent.declaredDashCall = action.declaredDashCall;
    } else if (action.actionType === "SubmitAuctionBid") {
      intent.isPass = !!action.isPass;
      if (!intent.isPass) { intent.tricks = action.tricks; intent.suit = action.suit; }
    } else if (action.actionType === "SubmitConfirmCall") {
      intent.tricks = action.tricks;
      intent.suit = action.suit;
    }
    return intent;
  }

  /** Sprint 3.7 (Online Bidding Synchronization Contract): the ONE
   *  public API for submitting a Dash Call, Auction Bid, or Confirm
   *  Call. Deliberately `(matchId, action)` — no `seatId` parameter,
   *  mirroring `submitCard(matchId, card)`'s exact precedent (Sprint
   *  4.2.1's own established pattern, not a new one): the acting seat
   *  is resolved INTERNALLY from the calling uid via
   *  `MatchAdapter.uidToSeat()`, never trusted as a client-supplied
   *  claim.
   *
   *  Pre-write authority AND legality gate, in one call: this function
   *  asks the REAL, existing, unmodified `BiddingEngine.canSubmit()`
   *  (Sprint 3.6.1) whether this exact action would be accepted RIGHT
   *  NOW — BEFORE `runTransaction()` is ever invoked — exactly
   *  mirroring `submitCard()`'s own "ask `TableEngine.previewPlay()`
   *  before writing" precedent (Sprint 4.2.1), applied here to bidding.
   *  `canSubmit()` already checks BOTH turn ("Not this seat's turn")
   *  AND phase ("Not the ... phase") AND every content rule (Dash
   *  limits, suit strength, auction comparison, Caller cap, With-floor,
   *  Forbidden-13) internally — a SEPARATE `MatchAdapter.assertLocalTurn()`
   *  call (the gate `submitCard()` uses IN ADDITION to
   *  `previewPlay()`) is deliberately NOT used here: `assertLocalTurn()`
   *  checks `matches/{matchId}.turn`, a field nothing in this codebase
   *  advances through the Dash/Auction/Confirm sub-phases (a
   *  pre-existing, honestly-documented gap — see
   *  match-adapter.js's own header comment) — using it here would
   *  incorrectly reject every action past the very first one. This is
   *  not a shortcut; `canSubmit()` already unifies what `assertLocalTurn()`
   *  + `previewPlay()` had to do separately for cards, because Sprint
   *  3.6.1 built it as one combined turn+legality gate from the start.
   *
   *  An illegal or out-of-turn action is rejected — `ILLEGAL_BIDDING_ACTION`
   *  — with ZERO Firestore writes attempted; `biddingLog` can never
   *  contain an entry the real engine would reject. `action` is stored
   *  as an OPAQUE, reconstructed payload (`buildBiddingLogEntry()`) —
   *  this function still doesn't know or invent WHY an action is legal,
   *  only whether the real engine says it is.
   *
   *  Runs inside a real Firestore transaction, re-verifying the SAME
   *  version-conflict guard `submitCard()` established (Sprint 4.2.2,
   *  Task 3): the `canSubmit()` answer above was computed against the
   *  LOCAL browser's OWN engine state, entirely OUTSIDE this
   *  transaction — if the document has changed AT ALL since the
   *  pre-check read, that answer no longer describes the current world
   *  and must not be trusted. Rejects `STALE_GAME_STATE` and writes
   *  nothing rather than silently recomputing or reusing a stale
   *  answer — the same deliberate, stricter-than-`submitBid()` choice
   *  `submitCard()` already made, for the identical reason (the thing
   *  being validated lives in local engine state, not inside
   *  Firestore's own transaction machinery, so re-running the
   *  transaction callback alone cannot safely re-validate it). */
  function submitBiddingAction(matchId, action) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "submitBiddingAction: matchId is required."));
    if (!isValidGenericBiddingAction(action)) {
      return Promise.reject(bidError("INVALID_BIDDING_ACTION_VALUE",
        "submitBiddingAction: action must be a well-formed {actionType,...} object — see isValidGenericBiddingAction()."));
    }
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "submitBiddingAction: no signed-in user."));
    if (!global.MatchAdapter || typeof global.MatchAdapter.uidToSeat !== "function") {
      return Promise.reject(bidError("MATCH_ADAPTER_UNAVAILABLE", "submitBiddingAction: MatchAdapter is not available on this page."));
    }
    // Task 1/2: the real engine must be reachable to pre-check BEFORE
    // any write is attempted — no fallback, no silent skip.
    if (!global.BiddingEngine || typeof global.BiddingEngine.canSubmit !== "function") {
      return Promise.reject(bidError("ENGINE_UNAVAILABLE", "submitBiddingAction: BiddingEngine is not available on this page — cannot validate this action before writing it."));
    }

    var matchRef = db().collection("matches").doc(matchId);

    /** Resolves the seat via `MatchAdapter.uidToSeat()` — factored so
     *  it can run BOTH as the upfront, pre-transaction lookup AND again
     *  inside the transaction against a freshly-read document (the SAME
     *  call, never two different implementations), exactly mirroring
     *  `submitCard()`'s own `resolveSeatAndAuthorize()` factoring. */
    function resolveSeat(match) {
      var seatId = global.MatchAdapter.uidToSeat(match, callingUid);
      if (!seatId) {
        throw bidError("PERMISSION_DENIED", "submitBiddingAction: you do not own a seat in this match.");
      }
      return seatId;
    }

    return matchRef.get().then(function (snap) {
      if (!snap.exists) throw bidError("MATCH_NOT_FOUND", "submitBiddingAction: match '" + matchId + "' was not found.");
      var match = snap.data();
      var seatId = resolveSeat(match);

      // Ask the REAL, existing BiddingEngine whether THIS exact action
      // would be accepted right now. Never mutates anything, never
      // calls emit(), never duplicates canSubmit()'s own rules. Rejects
      // here — still BEFORE runTransaction() — for an illegal or
      // out-of-turn action. Zero writes attempted; biddingLog is never
      // touched.
      var intent = biddingActionToIntent(seatId, action);
      var verdict = global.BiddingEngine.canSubmit(intent);
      if (!verdict || !verdict.legal) {
        throw bidError("ILLEGAL_BIDDING_ACTION", "submitBiddingAction: bidding-engine.js rejected this action (" + (verdict && verdict.reason) + ") — not written.");
      }
      var expectedVersion = match.version;

      return db().runTransaction(function (tx) {
        return tx.get(matchRef).then(function (freshSnap) {
          if (!freshSnap.exists) throw bidError("MATCH_NOT_FOUND", "submitBiddingAction: match '" + matchId + "' was not found.");
          var freshMatch = freshSnap.data();
          // Task 3 (mirrors submitCard()'s own identical guard): the
          // canSubmit() verdict above was computed against the LOCAL
          // engine's own state, entirely OUTSIDE this transaction — a
          // changed document since then means that verdict no longer
          // describes the current world and MUST NOT be trusted.
          if (freshMatch.version !== expectedVersion) {
            throw bidError("STALE_GAME_STATE", "submitBiddingAction: the match document changed since this action was validated (expected version " + expectedVersion + ", found " + freshMatch.version + ") — not written; re-fetch and retry.");
          }
          var freshSeatId = resolveSeat(freshMatch);
          var biddingLog = (freshMatch.biddingLog || []).slice();
          biddingLog.push(buildBiddingLogEntry(freshSeatId, action, freshMatch.currentRound));
          var nextVersion = expectedVersion + 1;
          var patch = {
            biddingLog: biddingLog,
            version: nextVersion,
            updatedAt: serverTimestamp()
          };
          // Sprint J.7 (Unified Bidding Completion): a SubmitConfirmCall
          // is the ONE moment the Caller's own confirmed trick count
          // becomes known — but until now nothing ever mirrored it into
          // the Firestore `bids` map (bidding-engine.js's own ESTIMATES
          // routing deliberately SKIPS the Caller, since their bid
          // already exists in LOCAL engine state — see
          // SubmitConfirmCall's own handler). That gap is exactly why
          // `allSeatsNowHaveBids` (Sprint J.3) could never become true
          // for the dominant, real-caller path — see Sprint J.4/J.5.2's
          // forensic reports. Fixed at the SOURCE: write the SAME value
          // already being appended to `biddingLog` into this seat's OWN
          // `bids` slot, in the SAME transaction — reusing
          // `submitBid()`'s existing schema (a plain int 0-13) and
          // seat-ownership model exactly, never a second representation
          // of "the Caller's bid." Deliberately does NOT introduce a
          // `callerId` field (Sprint J.6 adversarial review rejected
          // that: a durable authority field derived from THIS write path
          // is forgeable, since isValidBiddingActionSubmission() has no
          // subPhase/turn check by design — see that review's Attack C).
          if (action.actionType === "SubmitConfirmCall" &&
              (!(freshSeatId in freshMatch.bids) || freshMatch.bids[freshSeatId] == null)) {
            var bids = Object.assign({}, freshMatch.bids);
            bids[freshSeatId] = action.tricks;
            patch.bids = bids;
            var seatIds = Object.keys(freshMatch.seats || {});
            var allSubmitted = seatIds.length > 0 && seatIds.every(function (s) { return bids[s] != null; });
            patch.biddingOpen = !allSubmitted;
            // Mirrors submitBid()'s own round-start completion write
            // VERBATIM (not a second implementation) for the rare/
            // adversarial case where THIS write happens to be the one
            // that completes bidding — e.g. an out-of-order Estimate
            // landing before Confirm, or a fast-round Super Call where
            // every seat already had a bid before Confirm. See
            // MatchService.submitBid()'s own identical block for the
            // full rationale and its own honestly-documented limitation
            // (structural-seat-only turn verification; a fast round's
            // TRUE leader may not yet be resolvable from local
            // GameSession state at this exact write instant — a
            // pre-existing, separately-tracked gap, not introduced or
            // fixed by this sprint).
            // Sprint J.7 (Post-Implementation Review fix): same
            // `match.turn == null` guard added to submitBid()'s own
            // block above, for the identical reason — see that block's
            // comment for the full account.
            if (allSubmitted && freshMatch.turn == null && freshMatch.cardPhase == null &&
                global.MatchAdapter && typeof global.MatchAdapter.computeRoundStartLeaderUid === "function") {
              // Sprint J.11 (post-review fix): same stale-snapshot gap as
              // submitBid()'s identical block above — `freshMatch.bids` is
              // missing this seat's own just-merged bid (see the local
              // `bids` copy a few lines up, merged into `patch.bids` but
              // never back into `freshMatch` itself). Pass the merged view
              // so the fast-round formula sees the completing bid.
              var leaderUid = global.MatchAdapter.computeRoundStartLeaderUid(Object.assign({}, freshMatch, { bids: bids }));
              if (leaderUid) {
                patch.turn = leaderUid;
                patch.cardPhase = "PLAY";
              }
            }
          }
          tx.update(matchRef, patch);
          return {
            matchId: matchId, seatId: freshSeatId, actionType: action.actionType,
            version: nextVersion, logLength: biddingLog.length
          };
        });
      });
    });
  }

  function playCard(matchId, uid, cardId) { return notImplemented("playCard"); }
  function resolveTrick(matchId) { return notImplemented("resolveTrick"); }
  /** Round Lifecycle sprint: deliberately LEFT as a stub, not
   *  implemented as a separate "mark the round complete, but don't
   *  advance yet" step. A round that is observably "complete" but not
   *  yet "advanced" is exactly the kind of half-transition state this
   *  sprint's own brief warns against (a client could read `currentRound
   *  === N` with the round's 13th trick already resolved, and would
   *  have to guess whether it is safe to start Round N+1's bidding).
   *  `advanceToNextRound()` below performs completion-verification AND
   *  advancement atomically, in ONE transaction — there is no
   *  observable state in between. See docs/reviews/Sprint_RoundLifecycle_Architecture_Report.md
   *  §2 ("one transaction" chosen over "two explicit phases"). */
  function completeRound(matchId) { return notImplemented("completeRound"); }
  /** Round Lifecycle sprint — the ONE real, authoritative round
   *  transition this file exposes. Atomically verifies `completedRound`
   *  is genuinely finished (structurally — see below) and advances
   *  `currentRound` to `completedRound + 1`, in a single Firestore
   *  transaction. Idempotent and safe to call from MULTIPLE clients at
   *  once (see docs/reviews/Sprint_RoundLifecycle_Architecture_Report.md
   *  §2's "who advances the round" analysis — the answer is "any
   *  client may attempt it; the transaction is what makes that safe,"
   *  not a designated host/caller):
   *
   *  - If, by the time this transaction actually reads the document,
   *    `currentRound` is no longer `completedRound` (another client's
   *    call already won the race, or this round was already advanced
   *    earlier), this is a NO-OP — resolves successfully with
   *    `{advanced:false, reason:"ALREADY_ADVANCED", currentRound:...}`,
   *    never an error. Two, three, or a hundred simultaneous callers
   *    converge on exactly one real advancement.
   *  - "Genuinely finished" is checked STRUCTURALLY, not by
   *    recomputing a score: exactly 52 `cardLog` entries tagged
   *    `round === completedRound` must exist (13 tricks * 4 seats).
   *    This deliberately does NOT re-run `ScoringEngine`'s rules here —
   *    scoring is already computed once, deterministically, on every
   *    synchronized client, by the SAME replayed log (see
   *    `TableEngine.resolveTrick()` -> `ScoringEngine.applyRoundResult()`
   *    -> GameSession's own `recordRoundResult()`, all unmodified, all
   *    untouched by this function) — duplicating that here would be
   *    exactly the "duplicate scoring logic" this sprint's own brief
   *    forbids. If a caller's local engine has NOT genuinely reached
   *    round completion, this structural check still catches it
   *    (fewer than 52 tagged entries exist) without knowing anything
   *    about WHY a round completes.
   *  - The reset patch only ever touches `currentRound`, `version`,
   *    and the legacy/derived bookkeeping fields (`biddingOpen`,
   *    `bids`, `lastBidSeat`, `cardPhase`, `turn`) that a fresh round's
   *    bidding phase starts from — `biddingLog`/`cardLog` themselves
   *    are NEVER cleared, rewritten, or reset (append-only, prefix-
   *    immutable, exactly as established since Sprint 4.2.1) — Round 1's
   *    entries remain in the log forever, simply superseded by Round 2's
   *    higher `round` tag going forward. */
  function advanceToNextRound(matchId, completedRound) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "advanceToNextRound: matchId is required."));
    if (typeof completedRound !== "number" || !Number.isFinite(completedRound) || !Number.isInteger(completedRound) || completedRound < 1) {
      return Promise.reject(bidError("INVALID_ARGUMENT", "advanceToNextRound: completedRound must be a positive integer."));
    }
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "advanceToNextRound: no signed-in user."));

    var matchRef = db().collection("matches").doc(matchId);
    return db().runTransaction(function (tx) {
      return tx.get(matchRef).then(function (snap) {
        if (!snap.exists) throw bidError("MATCH_NOT_FOUND", "advanceToNextRound: match '" + matchId + "' was not found.");
        var match = snap.data();
        if (!Array.isArray(match.players) || match.players.indexOf(callingUid) === -1) {
          throw bidError("PERMISSION_DENIED", "advanceToNextRound: you are not a player in this match.");
        }
        // Match Completion sprint: a match that has ALREADY completed
        // (status:"complete", written by endMatch()) must never be
        // advanced again — completion is terminal. Without this guard,
        // a client's advanceToNextRound() call racing an in-flight
        // endMatch() call for the SAME completedRound could win the
        // race (this function has no other way to know the match just
        // ended, since it does not touch `status` at all) and silently
        // bump `currentRound` past a document that is simultaneously
        // claiming to be finished — corrupting the completed state
        // (found via this sprint's own real-browser QA, Phase 4
        // Scenario N; not caught by any Node-level test because those
        // exercise each function in isolation, never a genuine
        // same-tick race between the two). Idempotent no-op, never an
        // error — exactly the same shape as the ALREADY_ADVANCED case
        // below, since both mean "there is nothing left for this call
        // to legitimately do."
        if (match.status === "complete") {
          return { advanced: false, reason: "MATCH_ALREADY_COMPLETE", matchId: matchId, currentRound: match.currentRound };
        }
        // Idempotent no-op: someone else already advanced this round
        // (or this round was never at completedRound to begin with) —
        // never an error, never a duplicate advance.
        if (match.currentRound !== completedRound) {
          return { advanced: false, reason: "ALREADY_ADVANCED", matchId: matchId, currentRound: match.currentRound };
        }
        // Structural completion check — see this function's own doc
        // comment for why this counts entries rather than re-deriving a
        // score. `52` is fixed (13 tricks * 4 seats) — this project has
        // no variable-player-count mode.
        var cardLog = Array.isArray(match.cardLog) ? match.cardLog : [];
        var roundCardCount = cardLog.filter(function (entry) { return entry && entry.round === completedRound; }).length;
        if (roundCardCount !== 52) {
          throw bidError("ROUND_NOT_COMPLETE",
            "advanceToNextRound: round " + completedRound + " has only " + roundCardCount + "/52 recorded card plays — not advancing.");
        }
        var seats = match.seats || {};
        var resetBids = {};
        Object.keys(seats).forEach(function (seatId) { resetBids[seatId] = null; });
        var nextVersion = match.version + 1;
        var patch = {
          currentRound: completedRound + 1,
          version: nextVersion,
          biddingOpen: true,
          bids: resetBids,
          lastBidSeat: null,
          // `turn`/`cardPhase` have no defined meaning until the new
          // round's real bidding (then card play) resumes — reset to
          // the SAME "no value yet" convention `cardPhase` already uses
          // before the first card of a match is ever played (Sprint
          // 4.2's own `buildInitialMatchDoc()` precedent), rather than
          // leaving Round N's final PLAY-phase values stale and
          // misleading for Round N+1's bidding.
          turn: null,
          cardPhase: null,
          updatedAt: serverTimestamp()
        };
        tx.update(matchRef, patch);
        return {
          advanced: true, matchId: matchId,
          previousRound: completedRound, currentRound: completedRound + 1,
          version: nextVersion
        };
      });
    });
  }
  // Rapid Rounds window (rules §5) — the ONLY physical round numbers
  // eligible to extend `maxRounds`, mirroring the SAME fixed 14-18 band
  // ScoringEngine.computeRoundExtension() already enforces client-side.
  // Kept as an independent local constant (not a cross-file lookup) for
  // the same "MatchService stays engine-independent" reason SEAT_IDS is
  // — this is a structural fact about round numbering, not a
  // gameplay/engine dependency.
  var RAPID_ROUND_MIN = 14, RAPID_ROUND_MAX = 18;
  var VALID_EXTENSION_REASONS = ["SUPER_CALL", "SAAYDA"];

  /** Match Completion sprint: the ONE authoritative place `maxRounds`
   *  is ever incremented. Atomic + idempotent, mirroring
   *  `advanceToNextRound()`'s exact shape and the same "any client may
   *  attempt it" design: safe to call from MULTIPLE clients (or the
   *  SAME client more than once) for the SAME `completedRound` — only
   *  the first to actually win the transaction increments `maxRounds`;
   *  every other caller (or retry) converges on a no-op.
   *
   *  Idempotency key is `extendedRounds` (an array of round numbers
   *  already used to extend this match) rather than `currentRound`
   *  equality (advanceToNextRound()'s own key) — a round's extension
   *  and its advancement are two INDEPENDENT events that both reference
   *  the same `completedRound`, so they need two independent guards;
   *  reusing `currentRound` here would incorrectly refuse a legitimate
   *  extension attempt that happens to race with (or follow) an
   *  advancement already applied for the same round.
   *
   *  HONEST LIMITATION (see this file's header comment, "reason" param
   *  below, and this sprint's own Final Report): this function does
   *  NOT — and structurally CANNOT, without duplicating
   *  BiddingEngine/ScoringEngine's rules here — verify that a qualifying
   *  Super Call or Sa'ayda actually occurred in `completedRound`. It
   *  only verifies the STRUCTURAL facts firestore.rules can also verify
   *  independently: `completedRound` is a real Rapid Round (14-18),
   *  `reason` is one of the two defined values, and this round has never
   *  been used to extend before. The caller (MatchAdapter) is trusted to
   *  have already computed `reason` from the SAME real engine facts
   *  every local client independently derives — exactly the same
   *  trust boundary `advanceToNextRound()`'s own structural-only
   *  completion check already documents and accepts. */
  function extendMatchRounds(matchId, completedRound, reason) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "extendMatchRounds: matchId is required."));
    if (typeof completedRound !== "number" || !Number.isFinite(completedRound) || !Number.isInteger(completedRound) ||
        completedRound < RAPID_ROUND_MIN || completedRound > RAPID_ROUND_MAX) {
      return Promise.reject(bidError("INVALID_ARGUMENT",
        "extendMatchRounds: completedRound must be an integer between " + RAPID_ROUND_MIN + " and " + RAPID_ROUND_MAX + "."));
    }
    if (VALID_EXTENSION_REASONS.indexOf(reason) === -1) {
      return Promise.reject(bidError("INVALID_ARGUMENT", "extendMatchRounds: reason must be one of " + VALID_EXTENSION_REASONS.join("/") + "."));
    }
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "extendMatchRounds: no signed-in user."));

    var matchRef = db().collection("matches").doc(matchId);
    return db().runTransaction(function (tx) {
      return tx.get(matchRef).then(function (snap) {
        if (!snap.exists) throw bidError("MATCH_NOT_FOUND", "extendMatchRounds: match '" + matchId + "' was not found.");
        var match = snap.data();
        if (!Array.isArray(match.players) || match.players.indexOf(callingUid) === -1) {
          throw bidError("PERMISSION_DENIED", "extendMatchRounds: you are not a player in this match.");
        }
        // Match Completion sprint: same terminal-state guard as
        // advanceToNextRound() — a match that has ALREADY completed
        // must never have its maxRounds bumped again (there is nothing
        // left to extend). Idempotent no-op, never an error.
        if (match.status === "complete") {
          return { extended: false, reason: "MATCH_ALREADY_COMPLETE", matchId: matchId, maxRounds: match.maxRounds };
        }
        var extendedRounds = Array.isArray(match.extendedRounds) ? match.extendedRounds : [];
        // Idempotent no-op: this round already extended the match once
        // (by this client's earlier attempt, or another client's) —
        // never a second increment for the same qualifying event.
        if (extendedRounds.indexOf(completedRound) !== -1) {
          return { extended: false, reason: "ALREADY_EXTENDED", matchId: matchId, maxRounds: match.maxRounds };
        }
        var nextVersion = match.version + 1;
        var nextMaxRounds = (match.maxRounds || 18) + 1;
        tx.update(matchRef, {
          maxRounds: nextMaxRounds,
          extendedRounds: extendedRounds.concat([completedRound]),
          version: nextVersion,
          updatedAt: serverTimestamp()
        });
        return { extended: true, matchId: matchId, completedRound: completedRound, reason: reason, maxRounds: nextMaxRounds, version: nextVersion };
      });
    });
  }

  /** Match Completion sprint: structural self-consistency check for a
   *  client-supplied `finalScores`/`winnerIds` pair — the highest-score
   *  seat(s) in `finalScores` must be EXACTLY the seats listed in
   *  `winnerIds`, no more, no fewer. This is the one piece of "is this
   *  claim internally honest" verification `endMatch()` CAN do without
   *  re-deriving the actual game score (which would mean duplicating
   *  the entire bidding/scoring pipeline here — explicitly out of
   *  scope, see this file's header comment and
   *  ScoringEngine.computeWinner(), whose exact tie-all-highest-scores
   *  rule this mirrors). It does NOT (and cannot) verify `finalScores`
   *  itself is the TRUE outcome of the match — see endMatch()'s own
   *  "HONEST LIMITATION" note. */
  function winnerIdsMatchFinalScores(finalScores, winnerIds, seats) {
    if (!finalScores || typeof finalScores !== "object") return false;
    if (!Array.isArray(winnerIds) || winnerIds.length === 0) return false;
    var seatIds = Object.keys(seats);
    if (seatIds.length === 0) return false;
    if (seatIds.sort().join(",") !== Object.keys(finalScores).sort().join(",")) return false;
    var maxScore = -Infinity;
    seatIds.forEach(function (id) {
      if (typeof finalScores[id] !== "number" || !Number.isFinite(finalScores[id])) return;
      if (finalScores[id] > maxScore) maxScore = finalScores[id];
    });
    var expectedWinners = seatIds.filter(function (id) { return finalScores[id] === maxScore; });
    return expectedWinners.slice().sort().join(",") === winnerIds.slice().sort().join(",");
  }

  /** Match Completion sprint: the ONE authoritative match-completion
   *  transition. Atomic + idempotent, mirroring `advanceToNextRound()`'s
   *  exact shape: any client may attempt it once its local engine
   *  determines `currentRound >= maxRounds` after the round's final
   *  trick resolves; Firestore's transaction semantics ensure exactly
   *  one call actually transitions `status` to `"complete"`, and every
   *  other simultaneous (or later, retried) call converges on the SAME
   *  already-complete result — never a second completion, never an
   *  error.
   *
   *  `finalScores`/`winnerIds` are supplied by the calling client
   *  (computed locally via the engine's own match-score/winner
   *  derivation — MatchAdapter's job, never this file's) and
   *  structurally verified here
   *  (see `winnerIdsMatchFinalScores()`) — but see this function's own
   *  HONEST LIMITATION note: this transaction cannot independently
   *  recompute the TRUE final score from `biddingLog`/`cardLog` without
   *  duplicating the entire bidding/scoring engine server-side, which
   *  is explicitly out of scope for this sprint (same boundary
   *  `advanceToNextRound()`'s own structural-only completion check
   *  already documents). A malicious or buggy client could theoretically
   *  submit a self-consistent but WRONG `finalScores` map; nothing in
   *  this codebase can catch that today. This is documented here, in
   *  the Final Report, and in firestore.rules' own comment, rather than
   *  silently assumed away. */
  function endMatch(matchId, completedRound, finalScores, winnerIds) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "endMatch: matchId is required."));
    if (typeof completedRound !== "number" || !Number.isFinite(completedRound) || !Number.isInteger(completedRound) || completedRound < 1) {
      return Promise.reject(bidError("INVALID_ARGUMENT", "endMatch: completedRound must be a positive integer."));
    }
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "endMatch: no signed-in user."));

    var matchRef = db().collection("matches").doc(matchId);
    return db().runTransaction(function (tx) {
      return tx.get(matchRef).then(function (snap) {
        if (!snap.exists) throw bidError("MATCH_NOT_FOUND", "endMatch: match '" + matchId + "' was not found.");
        var match = snap.data();
        if (!Array.isArray(match.players) || match.players.indexOf(callingUid) === -1) {
          throw bidError("PERMISSION_DENIED", "endMatch: you are not a player in this match.");
        }
        // Idempotent no-op: the match is already complete (by this
        // client's earlier attempt, or another client's) — never a
        // second completion, and status never moves complete -> anything.
        if (match.status === "complete") {
          return {
            complete: false, reason: "ALREADY_COMPLETE", matchId: matchId,
            winnerIds: match.winnerIds || [], finalScores: match.finalScores || {}
          };
        }
        if (match.currentRound !== completedRound) {
          return { complete: false, reason: "ALREADY_ADVANCED", matchId: matchId, currentRound: match.currentRound };
        }
        // Structural completion check — same 52-entries-per-round
        // technique advanceToNextRound() already uses, reused (not
        // duplicated logic — the SAME filter expression) rather than
        // inventing a second way to confirm a round genuinely finished.
        var cardLog = Array.isArray(match.cardLog) ? match.cardLog : [];
        var roundCardCount = cardLog.filter(function (entry) { return entry && entry.round === completedRound; }).length;
        if (roundCardCount !== 52) {
          throw bidError("ROUND_NOT_COMPLETE",
            "endMatch: round " + completedRound + " has only " + roundCardCount + "/52 recorded card plays — not completing.");
        }
        // The match may only actually end once the round reached is at
        // or past the CURRENT authoritative maxRounds — never a
        // hardcoded 18. If a qualifying extension for THIS round hasn't
        // been recorded yet, maxRounds here is stale relative to what
        // the round actually earned — the caller (MatchAdapter) is
        // responsible for calling extendMatchRounds() BEFORE endMatch()
        // when applicable (see MatchAdapter.maybeExtendOrCompleteMatch()).
        var maxRounds = match.maxRounds || 18;
        if (completedRound + 1 <= maxRounds) {
          return { complete: false, reason: "MATCH_NOT_OVER", matchId: matchId, currentRound: match.currentRound, maxRounds: maxRounds };
        }
        if (!winnerIdsMatchFinalScores(finalScores, winnerIds, match.seats || {})) {
          throw bidError("INVALID_RESULT", "endMatch: winnerIds does not match the highest score(s) in finalScores.");
        }
        var nextVersion = match.version + 1;
        tx.update(matchRef, {
          status: "complete",
          winnerIds: winnerIds.slice(),
          finalScores: Object.assign({}, finalScores),
          completedRound: completedRound,
          version: nextVersion,
          updatedAt: serverTimestamp()
        });
        return {
          complete: true, matchId: matchId, completedRound: completedRound,
          winnerIds: winnerIds.slice(), finalScores: Object.assign({}, finalScores), version: nextVersion
        };
      });
    });
  }

  // ══════════════════════════════════════════════════════════════════
  // Player Hand Synchronization sprint (Architecture Gate-approved
  // Option A): the ONE authoritative dealing transaction. Any seated
  // client may attempt it — for the round it locally believes needs
  // dealing (match load, or a round transition it just detected) —
  // Firestore's transaction semantics guarantee exactly one attempt
  // actually commits a deal per round; every other attempt (concurrent
  // or later/retried) observes the already-committed `dealtRound` and
  // no-ops, the SAME "first commit wins" idiom already proven in this
  // file by startMatch()/createRematchVote()/advanceToNextRound()/
  // createRematchMatch(). Dealer.dealHands() itself is UNCHANGED — this
  // only relocates its CALLER from a client screen's own
  // ensureHandsDealt() into this one committing transaction attempt.
  //
  // Hidden information: card CONTENT is never supplied by, or trusted
  // from, any client — Dealer.dealHands() runs inside the transaction
  // callback and its result is written split by seat into
  // matches/{matchId}/hands/{seatId}, each doc readable only by its own
  // seat's uid (see firestore.rules' new `hands/{seatId}` block). See
  // docs/reviews/Player_Hand_Synchronization_Architecture_Report.md for
  // the full design rationale — this is a direct implementation of
  // that report's §7/§10, refined by the Architecture Gate's own
  // findings (the `gameState` shape-lock requirement in particular).
  /** Deals ONE round, exactly once, for `matchId`/`roundNumber`.
   *  Idempotent: if `gameState.dealtRound >= roundNumber` already (this
   *  client's own earlier attempt, or another client's), returns a
   *  no-op — never a second deal, never an error. Requires
   *  `global.Dealer` (design-ui/engine/dealer.js) to be loaded on this
   *  page — the exact same, unmodified dealing algorithm every prior
   *  sprint has used, just called from here instead of from the
   *  engine's own ensureHandsDealt() function. */
  function dealRound(matchId, roundNumber) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "dealRound: matchId is required."));
    if (typeof roundNumber !== "number" || !Number.isFinite(roundNumber) || !Number.isInteger(roundNumber) || roundNumber < 1) {
      return Promise.reject(bidError("INVALID_ARGUMENT", "dealRound: roundNumber must be a positive integer."));
    }
    if (!global.Dealer || typeof global.Dealer.dealHands !== "function") {
      return Promise.reject(bidError("ENGINE_UNAVAILABLE", "dealRound: Dealer is not available on this page."));
    }
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "dealRound: no signed-in user."));

    var matchRef = db().collection("matches").doc(matchId);
    return db().runTransaction(function (tx) {
      return tx.get(matchRef).then(function (snap) {
        if (!snap.exists) throw bidError("MATCH_NOT_FOUND", "dealRound: match '" + matchId + "' was not found.");
        var match = snap.data();
        if (!Array.isArray(match.players) || match.players.indexOf(callingUid) === -1) {
          throw bidError("PERMISSION_DENIED", "dealRound: you are not a player in this match.");
        }
        var gameState = match.gameState || { initialized: false, dealtRound: 0 };
        // Idempotent no-op: this round (or a later one) is already
        // dealt — never a second deal, never an error. This is what
        // makes concurrent dealRound() attempts safe: whichever
        // transaction actually commits first wins; every loser's
        // attempt just discovers the work is already done.
        if ((gameState.dealtRound || 0) >= roundNumber) {
          return { dealt: false, reason: "ALREADY_DEALT", matchId: matchId, dealtRound: gameState.dealtRound || 0 };
        }
        var seats = match.seats || {};
        var seatIds = SEAT_IDS.filter(function (s) { return seats[s]; });
        var hands = global.Dealer.dealHands(seatIds.length ? seatIds : undefined);
        var handRefs = {};
        seatIds.forEach(function (seatId) {
          var cards = (hands[seatId] || []).map(function (c) {
            // Opaque, generically-shaped — mirrors submitCard()'s own
            // isValidGenericCardValue() shape exactly (suit + rank.v/s
            // only). `id`/`owner`/`played`/`displayName`/`value` are
            // engine-internal, derived client-side, never stored
            // server-side — the same "generic vs. gameplay" line this
            // codebase has drawn for every other card-shaped field.
            return { suit: c.suit, rank: { v: c.rank.v, s: c.rank.s } };
          });
          handRefs[seatId] = { ref: matchRef.collection("hands").doc(seatId), cards: cards };
        });
        Object.keys(handRefs).forEach(function (seatId) {
          tx.set(handRefs[seatId].ref, {
            seatId: seatId,
            round: roundNumber,
            cards: handRefs[seatId].cards,
            // `version` == the round it belongs to, deliberately, not a
            // separately-tracked increment-by-1 counter: this doc is
            // OVERWRITTEN (not appended) each round — current-hand-only
            // storage, no history retained, per the Architecture Gate's
            // Decision 4 — and reading each hand doc's own prior version
            // inside this transaction (just to compute version+1) would
            // be 4 extra reads for no real benefit, since `round` is
            // already a strictly-increasing, per-match-unique stamp on
            // its own. firestore.rules' isValidHandRedeal() enforces
            // this same invariant independently (newData.round >
            // oldData.round).
            version: roundNumber
          });
        });
        tx.update(matchRef, {
          gameState: { initialized: true, dealtRound: roundNumber },
          updatedAt: serverTimestamp()
        });
        return { dealt: true, matchId: matchId, dealtRound: roundNumber, seats: seatIds.slice() };
      });
    });
  }

  // Player Hand Synchronization sprint's own ref-counted subscription
  // registry for the hands subcollection — an independent registry
  // from `matchSubscriptions`/`rematchVoteSubscriptions` above (a
  // different document path per matchId+seatId), but the SAME shape:
  // one real onSnapshot listener no matter how many local callers
  // subscribe, automatic reconnect with the same backoff constants,
  // fail-open error delivery. Keyed on "matchId/seatId" — a client only
  // ever subscribes to its OWN seat's hand, never any other seat's.
  var handSubscriptions = {};

  function attachHandListener(key, matchId, seatId, entry) {
    entry.unsubscribeFirestore = db().collection("matches").doc(matchId).collection("hands").doc(seatId).onSnapshot(
      function (snap) {
        entry.reconnectAttempt = 0;
        var data = snap.exists ? snap.data() : null;
        if (data && typeof data.version === "number") {
          if (entry.lastVersion != null && data.version <= entry.lastVersion) return;
          entry.lastVersion = data.version;
        }
        if (entry.hasPublished && deepEqual(data, entry.lastPublishedData)) return;
        entry.hasPublished = true;
        entry.lastPublishedData = data;
        entry.listeners.slice().forEach(function (cb) { safeInvoke(cb, data, null); });
      },
      function (err) {
        var deliveredData = entry.hasPublished ? entry.lastPublishedData : null;
        entry.listeners.slice().forEach(function (cb) { safeInvoke(cb, deliveredData, err); });
        if (isRetryable(err)) {
          scheduleHandReconnect(key, matchId, seatId, entry);
        } else {
          entry.terminalError = err;
          console.warn("[MatchService] subscribeToHand(" + matchId + "/" + seatId + "): " + classifyError(err) + " error (code: " +
            (err && err.code || "none") + ") — reconnect attempts stopped permanently for this subscription.");
        }
      }
    );
  }

  function scheduleHandReconnect(key, matchId, seatId, entry) {
    if (entry.reconnectTimer) return;
    if (entry.listeners.length === 0) return;
    var attempt = entry.reconnectAttempt || 0;
    var delay = Math.min(RECONNECT_BASE_MS * Math.pow(2, attempt), RECONNECT_MAX_MS);
    entry.reconnectAttempt = attempt + 1;
    entry.reconnectTimer = setTimeout(function () {
      entry.reconnectTimer = null;
      if (entry.listeners.length === 0) return;
      if (typeof entry.unsubscribeFirestore === "function") entry.unsubscribeFirestore();
      attachHandListener(key, matchId, seatId, entry);
    }, delay);
  }

  /** Live-subscribe to ONE seat's own hand document. Same ref-counted,
   *  reconnecting, fail-open shape as subscribeToRematchVote() above,
   *  targeting matches/{matchId}/hands/{seatId}. Delivers `null` (not
   *  an error) if no deal has committed yet for any round — a normal,
   *  expected state right after match creation, before dealRound()'s
   *  first commit. This is the ONE genuinely new consumption path this
   *  sprint introduces (see the Architecture Report's §11) — every
   *  other signal (e.g. "has dealing happened") still rides on the
   *  existing subscribeToMatch() listener via `gameState.dealtRound`. */
  function subscribeToHand(matchId, seatId, callback) {
    if (!db()) {
      callback(null, new Error("MatchService: Firestore is not initialized on this page."));
      return function unsubscribe() {};
    }
    var key = matchId + "/" + seatId;
    var entry = handSubscriptions[key];
    if (!entry) {
      entry = handSubscriptions[key] = {
        listeners: [callback], unsubscribeFirestore: null, hasPublished: false,
        lastPublishedData: null, lastVersion: null, reconnectAttempt: 0, reconnectTimer: null,
        terminalError: null
      };
      attachHandListener(key, matchId, seatId, entry);
    } else {
      entry.listeners.push(callback);
      if (entry.terminalError) safeInvoke(callback, entry.hasPublished ? entry.lastPublishedData : null, entry.terminalError);
      else if (entry.hasPublished) safeInvoke(callback, entry.lastPublishedData, null);
    }
    return function unsubscribe() {
      var idx = entry.listeners.indexOf(callback);
      if (idx !== -1) entry.listeners.splice(idx, 1);
      if (entry.listeners.length === 0) {
        if (entry.reconnectTimer) { clearTimeout(entry.reconnectTimer); entry.reconnectTimer = null; }
        if (typeof entry.unsubscribeFirestore === "function") entry.unsubscribeFirestore();
        delete handSubscriptions[key];
      }
    };
  }
  // ══════════════════════════════════════════════════════════════════

  // ══════════════════════════════════════════════════════════════════
  // Post-Match Rematch Vote sprint. A dedicated subcollection document,
  // matches/{matchId}/rematchVote/current — deliberately NOT a field on
  // the completed match document itself, so nothing here ever needs
  // write access to that document's already-terminal, protected fields
  // (status/winnerIds/finalScores/completedRound — endMatch() above
  // remains the only writer of those, unchanged). All four functions
  // below follow this file's own established shape for every gameplay
  // write: one Firestore transaction, an idempotent no-op return (never
  // an error) for "someone already did this," version+1 optimistic
  // concurrency, and independent server-side re-verification in
  // firestore.rules — neither layer trusts the other alone.
  //
  // TIMER AUTHORITY (read before touching this section): this project
  // is Spark-only — no Cloud Functions exist or are planned (see
  // CardAuthorityHotfix_4.2.1.md / CHANGELOG.md's repeated "Spark only"
  // scope boundary). There is no scheduled function that can fire at
  // T+30s server-side. Instead, `createdAt` is written as a REAL
  // serverTimestamp() (resolved to the actual server commit time, never
  // any client's clock), and the 30-second deadline is DERIVED, never
  // stored as its own field: `createdAt + 30s`. Any client MAY attempt
  // the timeout transition once its own local clock suggests the
  // deadline has passed (an optimization, not an authority) — but
  // firestore.rules independently re-derives the SAME deadline from
  // `createdAt` and compares it against `request.time` (Firestore
  // Rules' own server-clock primitive) before ever allowing that write.
  // A malicious client claiming "the timer expired" when the real
  // server clock disagrees is rejected by the rule, not by any code in
  // this file. The UI's own countdown (match/index.html) is REQUIRED to
  // be presentational only — it renders `createdAt + 30s` for display,
  // it never gates a write.
  var REMATCH_VOTE_DURATION_SECONDS = 30;
  var REMATCH_VOTE_VALUES = ["YES", "NO"];

  /** Creates the vote document for a completed match. Idempotent: if
   *  the vote already exists (another client's earlier, possibly
   *  concurrent, call already created it), returns the EXISTING vote
   *  rather than erroring or overwriting it — exactly the same
   *  "idempotent no-op, never a second write" shape startMatch() uses
   *  for `room.matchId` already being set. `seats`/`votes` are copied
   *  VERBATIM from the parent match's own, already-immutable `seats`
   *  map — never derived from anything a client supplies — closing the
   *  "arbitrary UID injection" risk at the one place seats first appear
   *  on this new document. */
  function createRematchVote(matchId) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "createRematchVote: matchId is required."));
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "createRematchVote: no signed-in user."));

    var matchRef = db().collection("matches").doc(matchId);
    var voteRef = matchRef.collection("rematchVote").doc("current");
    return db().runTransaction(function (tx) {
      return tx.get(matchRef).then(function (matchSnap) {
        if (!matchSnap.exists) throw bidError("MATCH_NOT_FOUND", "createRematchVote: match '" + matchId + "' was not found.");
        var match = matchSnap.data();
        if (!Array.isArray(match.players) || match.players.indexOf(callingUid) === -1) {
          throw bidError("PERMISSION_DENIED", "createRematchVote: you are not a player in this match.");
        }
        if (match.status !== "complete") {
          throw bidError("MATCH_NOT_COMPLETE", "createRematchVote: match '" + matchId + "' has not completed yet.");
        }
        return tx.get(voteRef).then(function (voteSnap) {
          if (voteSnap.exists) {
            // Idempotent no-op — never a second create, never an error.
            return { created: false, matchId: matchId, vote: voteSnap.data() };
          }
          var seats = match.seats || {};
          var votes = {};
          Object.keys(seats).forEach(function (seatId) { votes[seatId] = null; });
          var voteDoc = {
            matchId: matchId,
            seats: Object.assign({}, seats),
            votes: votes,
            status: "OPEN",
            newMatchId: null,
            createdAt: serverTimestamp(),
            version: 1
          };
          tx.set(voteRef, voteDoc);
          return { created: true, matchId: matchId, vote: voteDoc };
        });
      });
    });
  }

  /** Casts exactly one seat's vote. LOCKED once cast — per this
   *  sprint's own final product decision, a vote can never flip value.
   *  A duplicate submission of the SAME value is an idempotent no-op
   *  (no write at all — nothing to change); a conflicting SECOND value
   *  for an already-cast seat is rejected, never silently applied.
   *  A "NO" vote fails the whole rematch IMMEDIATELY, in this SAME
   *  transaction (no separate resolve step needed for that case) — per
   *  the product decision "do not wait for the remaining players."
   *  A "YES" vote that happens to complete all real seats' votes moves
   *  status to "ALL_YES" in this same write, but does NOT itself create
   *  the new match — see createRematchMatch() below; keeping "record
   *  a vote" and "create the next match" as separate, independently-
   *  callable, idempotent operations is what lets ANY seated client
   *  safely attempt either step, with no host/master role anywhere. */
  function submitRematchVote(matchId, choice) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "submitRematchVote: matchId is required."));
    if (REMATCH_VOTE_VALUES.indexOf(choice) === -1) {
      return Promise.reject(bidError("INVALID_ARGUMENT", "submitRematchVote: choice must be one of " + REMATCH_VOTE_VALUES.join("/") + "."));
    }
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "submitRematchVote: no signed-in user."));

    var voteRef = db().collection("matches").doc(matchId).collection("rematchVote").doc("current");
    return db().runTransaction(function (tx) {
      return tx.get(voteRef).then(function (snap) {
        if (!snap.exists) throw bidError("VOTE_NOT_FOUND", "submitRematchVote: no rematch vote exists for match '" + matchId + "' — call createRematchVote() first.");
        var vote = snap.data();
        // Resolve the acting uid's OWN seat from the vote's own,
        // parent-derived `seats` map — never trust a client-supplied
        // seat id, mirroring resolveSeatAndAuthorize()'s established
        // convention elsewhere in this file.
        var seats = vote.seats || {};
        var actingSeat = Object.keys(seats).filter(function (s) { return seats[s] === callingUid; })[0] || null;
        if (!actingSeat) {
          throw bidError("PERMISSION_DENIED", "submitRematchVote: you do not own a seat in this match's rematch vote.");
        }
        if (vote.status !== "OPEN") {
          // Idempotent no-op — the vote is already decided (by this
          // seat's own earlier attempt, or by the timeout/any-NO path
          // racing ahead of this write); never a second transition.
          return { accepted: false, reason: "VOTE_CLOSED", matchId: matchId, status: vote.status, seatId: actingSeat };
        }
        // Independent client-side deadline pre-check — this file's own
        // established "neither layer trusts the other alone" convention
        // (see this section's header comment on timer authority),
        // mirrored here for a FAST, honest rejection rather than
        // relying solely on firestore.rules to catch a late vote. Not
        // the authority (that remains request.time in the rule) — only
        // a fast pre-check using the SAME real serverTimestamp-derived
        // `createdAt` the rule itself compares against.
        var createdAtMsForCast = vote.createdAt && typeof vote.createdAt.toMillis === "function" ? vote.createdAt.toMillis() : null;
        if (createdAtMsForCast != null && Date.now() > createdAtMsForCast + REMATCH_VOTE_DURATION_SECONDS * 1000) {
          return { accepted: false, reason: "VOTE_EXPIRED", matchId: matchId, seatId: actingSeat };
        }
        var existing = vote.votes[actingSeat];
        if (existing != null) {
          if (existing === choice) {
            // Duplicate of the SAME value — idempotent no-op, no write.
            return { accepted: true, reason: "ALREADY_VOTED", matchId: matchId, seatId: actingSeat, choice: choice };
          }
          // A conflicting second value for an already-locked vote —
          // rejected, never silently applied. Not an error: this is a
          // legitimate outcome a client should handle gracefully (its
          // own earlier vote already won), not a thrown exception.
          return { accepted: false, reason: "VOTE_LOCKED", matchId: matchId, seatId: actingSeat, existing: existing };
        }
        var newVotes = Object.assign({}, vote.votes);
        newVotes[actingSeat] = choice;
        var newStatus = vote.status; // stays "OPEN" unless one of the two structural transitions below fires
        if (choice === "NO") {
          newStatus = "FAILED_NO";
        } else {
          // choice === "YES": ALL_YES only if EVERY real seat (per
          // `seats`, never a hardcoded 4) now has a "YES" vote — a
          // pure count of what THIS document's own seats/votes
          // actually contain, never a re-derivation of who's "supposed"
          // to be seated.
          var allYes = Object.keys(seats).every(function (s) { return newVotes[s] === "YES"; });
          if (allYes) newStatus = "ALL_YES";
        }
        var nextVersion = vote.version + 1;
        tx.update(voteRef, { votes: newVotes, status: newStatus, version: nextVersion });
        return { accepted: true, reason: "RECORDED", matchId: matchId, seatId: actingSeat, choice: choice, status: newStatus, version: nextVersion };
      });
    });
  }

  /** Any seated client may safely attempt this once its OWN local
   *  clock suggests the 30-second window has passed — that local
   *  judgment is only ever an optimization for WHEN to try, never the
   *  authority for WHETHER the transition is valid (see this section's
   *  own header comment on timer authority). The transaction re-reads
   *  `createdAt` and compares against `Date.now()` here as a fast,
   *  honest client-side mirror of the SAME check firestore.rules
   *  independently re-derives from `request.time` — this file's own
   *  established "neither layer trusts the other alone" convention,
   *  applied to time instead of to a value. Idempotent: a vote that's
   *  already terminal (by the time this transaction runs) is a no-op,
   *  never a second write. */
  function resolveRematchVoteTimeout(matchId) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "resolveRematchVoteTimeout: matchId is required."));
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "resolveRematchVoteTimeout: no signed-in user."));

    var voteRef = db().collection("matches").doc(matchId).collection("rematchVote").doc("current");
    return db().runTransaction(function (tx) {
      return tx.get(voteRef).then(function (snap) {
        if (!snap.exists) throw bidError("VOTE_NOT_FOUND", "resolveRematchVoteTimeout: no rematch vote exists for match '" + matchId + "'.");
        var vote = snap.data();
        if (vote.status !== "OPEN") {
          return { resolved: false, reason: "ALREADY_RESOLVED", matchId: matchId, status: vote.status };
        }
        var createdAtMs = vote.createdAt && typeof vote.createdAt.toMillis === "function" ? vote.createdAt.toMillis() : null;
        if (createdAtMs == null) {
          // createdAt hasn't round-tripped through the server yet
          // (the sentinel is still pending on this client's own local
          // cache) — never guess a deadline; wait for a real read.
          return { resolved: false, reason: "DEADLINE_UNKNOWN", matchId: matchId };
        }
        var deadlineMs = createdAtMs + REMATCH_VOTE_DURATION_SECONDS * 1000;
        if (Date.now() < deadlineMs) {
          return { resolved: false, reason: "NOT_YET_EXPIRED", matchId: matchId, deadlineMs: deadlineMs };
        }
        var nextVersion = vote.version + 1;
        tx.update(voteRef, { status: "FAILED_TIMEOUT", version: nextVersion });
        return { resolved: true, matchId: matchId, status: "FAILED_TIMEOUT", version: nextVersion };
      });
    });
  }

  /** Any seated client may safely attempt this once it observes
   *  `status:"ALL_YES"` — race-safe by construction: whichever
   *  transaction actually commits first creates the new match AND
   *  links it (`newMatchId`) in ONE atomic transaction (Firestore
   *  fully supports creating one document and updating another in the
   *  same transaction — this file's own startMatch() already does
   *  exactly this, for rooms/{roomId} + matches/{matchId}); every other
   *  simultaneous attempt re-reads, observes `newMatchId` already set,
   *  and idempotently returns the EXISTING new match id rather than
   *  creating a second one. `seats`/`players` for the new match are
   *  copied VERBATIM from the vote document's own `seats` (itself
   *  copied verbatim from the ORIGINAL match at vote-creation time) —
   *  never from any client-supplied list — so "same four players, same
   *  seat assignments" is a structural guarantee, not a trust
   *  assumption. The OLD match document is never read for writing and
   *  never touched by this function — only `get()` for eligibility. */
  function createRematchMatch(matchId) {
    if (!matchId) return Promise.reject(bidError("INVALID_ARGUMENT", "createRematchMatch: matchId is required."));
    if (!db()) return Promise.reject(bidError("UNAVAILABLE", "MatchService: Firestore is not initialized on this page."));
    var callingUid = currentUid();
    if (!callingUid) return Promise.reject(bidError("UNAUTHENTICATED", "createRematchMatch: no signed-in user."));

    var oldMatchRef = db().collection("matches").doc(matchId);
    var voteRef = oldMatchRef.collection("rematchVote").doc("current");
    return db().runTransaction(function (tx) {
      return tx.get(voteRef).then(function (voteSnap) {
        if (!voteSnap.exists) throw bidError("VOTE_NOT_FOUND", "createRematchMatch: no rematch vote exists for match '" + matchId + "'.");
        var vote = voteSnap.data();
        if (vote.newMatchId) {
          // Idempotent no-op — a rematch match already exists for this
          // vote (created by this call, or by a simultaneous one that
          // won the race first).
          return { created: false, matchId: matchId, newMatchId: vote.newMatchId };
        }
        if (vote.status !== "ALL_YES") {
          return { created: false, reason: "NOT_ALL_YES", matchId: matchId, status: vote.status };
        }
        return tx.get(oldMatchRef).then(function (oldMatchSnap) {
          if (!oldMatchSnap.exists) throw bidError("MATCH_NOT_FOUND", "createRematchMatch: original match '" + matchId + "' was not found.");
          var oldMatch = oldMatchSnap.data();
          if (oldMatch.status !== "complete") {
            throw bidError("MATCH_NOT_COMPLETE", "createRematchMatch: original match '" + matchId + "' is not complete.");
          }
          var seats = vote.seats || {};
          // Deterministic seat-order player list — never re-derived
          // from a client, purely a projection of THIS vote's own,
          // already-authoritative seats map.
          var players = SEAT_IDS.filter(function (s) { return seats[s]; }).map(function (s) { return seats[s]; });
          var bids = {};
          Object.keys(seats).forEach(function (seatId) { bids[seatId] = null; });
          var newMatchRef = db().collection("matches").doc();
          var newMatchDoc = {
            roomId: oldMatch.roomId,
            rematchOfMatchId: matchId,
            players: players,
            status: "starting",
            createdAt: serverTimestamp(),
            currentRound: 1,
            maxRounds: 18,
            extendedRounds: [],
            dealer: players[0],
            turn: players[0],
            // Player Hand Synchronization sprint: a rematch is a
            // genuinely new deal — never a copy/reuse of the old
            // match's hands (see the Architecture Report's §10
            // "Rematch" note) — so this starts at 0 exactly like a
            // fresh match's own gameState.
            gameState: { initialized: false, dealtRound: 0 },
            seats: Object.assign({}, seats),
            version: 1,
            biddingOpen: true,
            bids: bids,
            lastBidSeat: null,
            cardLog: [],
            lastCardSeat: null,
            cardPhase: null,
            biddingLog: []
          };
          tx.set(newMatchRef, newMatchDoc);
          var nextVersion = vote.version + 1;
          tx.update(voteRef, { status: "NEW_MATCH_CREATED", newMatchId: newMatchRef.id, version: nextVersion });
          return { created: true, matchId: matchId, newMatchId: newMatchRef.id };
        });
      });
    });
  }

  // Sprint's own ref-counted subscription registry for the rematch
  // vote subcollection — an independent registry from `matchSubscriptions`
  // above (different document path entirely), but the SAME shape:
  // one real onSnapshot listener per matchId no matter how many local
  // callers subscribe, automatic reconnect with the same backoff
  // constants, fail-open error delivery. A second (or third) call for
  // the same matchId never creates a second Firestore listener.
  var rematchVoteSubscriptions = {};

  function attachRematchVoteListener(matchId, entry) {
    entry.unsubscribeFirestore = db().collection("matches").doc(matchId).collection("rematchVote").doc("current").onSnapshot(
      function (snap) {
        entry.reconnectAttempt = 0;
        var data = snap.exists ? snap.data() : null;
        if (data && typeof data.version === "number") {
          if (entry.lastVersion != null && data.version <= entry.lastVersion) return;
          entry.lastVersion = data.version;
        }
        if (entry.hasPublished && deepEqual(data, entry.lastPublishedData)) return;
        entry.hasPublished = true;
        entry.lastPublishedData = data;
        entry.listeners.slice().forEach(function (cb) { safeInvoke(cb, data, null); });
      },
      function (err) {
        var deliveredData = entry.hasPublished ? entry.lastPublishedData : null;
        entry.listeners.slice().forEach(function (cb) { safeInvoke(cb, deliveredData, err); });
        if (isRetryable(err)) {
          scheduleRematchVoteReconnect(matchId, entry);
        } else {
          entry.terminalError = err;
          console.warn("[MatchService] subscribeToRematchVote(" + matchId + "): " + classifyError(err) + " error (code: " +
            (err && err.code || "none") + ") — reconnect attempts stopped permanently for this subscription.");
        }
      }
    );
  }

  function scheduleRematchVoteReconnect(matchId, entry) {
    if (entry.reconnectTimer) return;
    if (entry.listeners.length === 0) return;
    var attempt = entry.reconnectAttempt || 0;
    var delay = Math.min(RECONNECT_BASE_MS * Math.pow(2, attempt), RECONNECT_MAX_MS);
    entry.reconnectAttempt = attempt + 1;
    entry.reconnectTimer = setTimeout(function () {
      entry.reconnectTimer = null;
      if (entry.listeners.length === 0) return;
      if (typeof entry.unsubscribeFirestore === "function") entry.unsubscribeFirestore();
      attachRematchVoteListener(matchId, entry);
    }, delay);
  }

  /** Live-subscribe to the rematch vote subcollection document for a
   *  match. Same ref-counted, reconnecting, fail-open shape as
   *  subscribeToMatch() above, targeting a different document path.
   *  Delivers `null` (not an error) if no vote has been created yet —
   *  a perfectly normal state (the match may not even be complete),
   *  never treated as a failure. */
  function subscribeToRematchVote(matchId, callback) {
    if (!db()) {
      callback(null, new Error("MatchService: Firestore is not initialized on this page."));
      return function unsubscribe() {};
    }
    var entry = rematchVoteSubscriptions[matchId];
    if (!entry) {
      entry = rematchVoteSubscriptions[matchId] = {
        listeners: [callback], unsubscribeFirestore: null, hasPublished: false,
        lastPublishedData: null, lastVersion: null, reconnectAttempt: 0, reconnectTimer: null,
        terminalError: null
      };
      attachRematchVoteListener(matchId, entry);
    } else {
      entry.listeners.push(callback);
      if (entry.terminalError) safeInvoke(callback, entry.hasPublished ? entry.lastPublishedData : null, entry.terminalError);
      else if (entry.hasPublished) safeInvoke(callback, entry.lastPublishedData, null);
    }
    return function unsubscribe() {
      var idx = entry.listeners.indexOf(callback);
      if (idx !== -1) entry.listeners.splice(idx, 1);
      if (entry.listeners.length === 0) {
        if (entry.reconnectTimer) { clearTimeout(entry.reconnectTimer); entry.reconnectTimer = null; }
        if (typeof entry.unsubscribeFirestore === "function") entry.unsubscribeFirestore();
        delete rematchVoteSubscriptions[matchId];
      }
    };
  }
  // ══════════════════════════════════════════════════════════════════

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
    // Sprint 4.2, Task 1: the new, real card-submission API — see this
    // file's header comment for why it's a NEW name (submitCard), not
    // a reimplementation of the OLD, never-adopted playCard(matchId,
    // uid, cardId) stub signature below, which remains exactly that: an
    // unimplemented stub, unchanged, since nothing in this codebase
    // (or this sprint's own brief) ever calls it.
    submitCard: submitCard,
    // Sprint 3.7 (Online Bidding Synchronization Contract): Dash Call /
    // Auction Bid / Confirm Call. Final Estimate remains submitBid()'s
    // job, unchanged — see submitBiddingAction()'s own header comment
    // for why these are structurally different shapes.
    submitBiddingAction: submitBiddingAction,
    playCard: playCard,
    resolveTrick: resolveTrick,
    completeRound: completeRound,
    advanceToNextRound: advanceToNextRound,
    extendMatchRounds: extendMatchRounds,
    endMatch: endMatch,
    subscribeToMatch: subscribeToMatch,
    // Player Hand Synchronization sprint.
    dealRound: dealRound,
    subscribeToHand: subscribeToHand,
    // Post-Match Rematch Vote sprint.
    createRematchVote: createRematchVote,
    submitRematchVote: submitRematchVote,
    resolveRematchVoteTimeout: resolveRematchVoteTimeout,
    createRematchMatch: createRematchMatch,
    subscribeToRematchVote: subscribeToRematchVote
  };
})(window);
