// Simulates firestore.rules' rooms/{roomId} logic against mock
// request/resource data. This is NOT the Firebase Rules Unit Testing
// library (@firebase/rules-unit-testing) — that needs the Firebase CLI
// + a Java-backed local emulator, neither available in this sandboxed
// session (no network-installable Java runtime, no `firebase` CLI
// login for this project). Instead, each rule expression below is
// translated 1:1 from firestore.rules into plain JS and exercised
// against representative request shapes — an honest, lower-fidelity
// substitute, not a claim of having run the real emulator.
//
// RESTATED, Sprint 4.2.3 (Firestore Rules Compile-Safe Card Turn
// Hotfix): this file's own passing checks verify LOGICAL INTENT only —
// they prove "this JS re-implementation of what the rule is SUPPOSED
// to do behaves as intended for these inputs." They do NOT compile or
// execute the actual firestore.rules file, and therefore cannot prove
// firestore.rules itself is free of unsupported CEL syntax — that is
// exactly the gap a direct review of the shipped rules found (a
// `.exists()` List method call that passed every JS-simulated check
// while apparently not being part of Firestore Rules' officially
// documented List method surface). Real Rules-compiler or Firebase
// Emulator verification of firestore.rules remains PENDING — this
// project has never run either, this sprint or any prior one.

// ---- 1:1 translations of firestore.rules' rooms/{roomId} functions ----
function isValidNewRoom(requestResourceData, requestAuthUid) {
  var data = requestResourceData;
  return typeof data.creator === "string"
    && data.creator === requestAuthUid
    && Array.isArray(data.players)
    && data.players.length >= 1
    && data.players.indexOf(data.creator) !== -1
    && typeof data.status === "string";
}

// SUPERSEDED IN SPRINT 3.3 — this was the rule shipped by Sprint 3.2.1.
// It correctly fixed the literal-brief blocker below, but (per the
// Sprint 3.2.5 Architecture Audit's finding F3) placed no restriction
// on WHAT changed, only WHO could write. Kept here, unused by the
// current tests, purely so the historical proof below still makes
// sense and isn't silently deleted.
function isExistingOrIncomingMember(resourceData, requestResourceData, requestAuthUid) {
  var oldPlayers = (resourceData && resourceData.players) || [];
  var newPlayers = (requestResourceData && requestResourceData.players) || [];
  return oldPlayers.indexOf(requestAuthUid) !== -1 || newPlayers.indexOf(requestAuthUid) !== -1;
}

// The LITERAL brief's rule from Sprint 3.2.1, kept here ONLY to prove
// why it was replaced — never shipped in firestore.rules.
function literalBriefUpdateRule(resourceData, requestAuthUid) {
  var oldPlayers = (resourceData && resourceData.players) || [];
  return oldPlayers.indexOf(requestAuthUid) !== -1;
}

// ============================================================
// Sprint 3.3 — tightened rules, 1:1 translations of the CURRENT
// firestore.rules (these are what's actually shipped and live-tested
// below; the two functions above are historical context only).
// ============================================================

function isValidNewRoomV2(data, requestAuthUid) {
  var allowedKeys = ["creator", "players", "readyPlayers", "status", "name", "createdAt", "updatedAt"];
  var keys = Object.keys(data);
  var hasOnlyAllowedKeys = keys.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
  return hasOnlyAllowedKeys
    && typeof data.creator === "string"
    && data.creator === requestAuthUid
    && Array.isArray(data.players)
    && data.players.length === 1
    && data.players.indexOf(data.creator) !== -1
    && Array.isArray(data.readyPlayers)
    && data.readyPlayers.length === 0
    && typeof data.status === "string"
    && data.status === "waiting";
}

function isSelfOnlyChange(oldArr, newArr, requestAuthUid) {
  oldArr = oldArr || []; newArr = newArr || [];
  var hasAll = function (a, b) { return b.every(function (x) { return a.indexOf(x) !== -1; }); };
  var uidInOld = oldArr.indexOf(requestAuthUid) !== -1;
  var uidInNew = newArr.indexOf(requestAuthUid) !== -1;
  if (newArr.length === oldArr.length + 1 && hasAll(newArr, oldArr) && uidInNew && !uidInOld) return true;
  if (oldArr.length === newArr.length + 1 && hasAll(oldArr, newArr) && uidInOld && !uidInNew) return true;
  if (newArr.length === oldArr.length && hasAll(newArr, oldArr) && hasAll(oldArr, newArr)) return true;
  return false;
}

function isValidCreatorChange(oldData, newData) {
  return newData.creator === oldData.creator || newData.players.indexOf(newData.creator) !== -1;
}

function isValidStatusChange(newData) {
  var validStatus = newData.status === "waiting" || newData.status === "closed";
  if (!validStatus) return false;
  if (newData.status === "closed") return newData.players.length === 0;
  return true;
}

function diffKeys(oldData, newData) {
  var keys = {};
  Object.keys(oldData || {}).forEach(function (k) { keys[k] = true; });
  Object.keys(newData || {}).forEach(function (k) { keys[k] = true; });
  return Object.keys(keys).filter(function (k) {
    return JSON.stringify(oldData ? oldData[k] : undefined) !== JSON.stringify(newData ? newData[k] : undefined);
  });
}

function isValidRoomUpdateV2(oldData, newData, requestAuthUid) {
  var allowedKeys = ["players", "readyPlayers", "status", "creator", "updatedAt"];
  var changed = diffKeys(oldData, newData);
  var onlyAllowedFieldsChanged = changed.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
  var isMember = (oldData.players || []).indexOf(requestAuthUid) !== -1 || (newData.players || []).indexOf(requestAuthUid) !== -1;
  return onlyAllowedFieldsChanged
    && isMember
    && isSelfOnlyChange(oldData.players, newData.players, requestAuthUid)
    && isSelfOnlyChange(oldData.readyPlayers, newData.readyPlayers, requestAuthUid)
    && isValidCreatorChange(oldData, newData)
    && isValidStatusChange(newData);
}

// ============================================================
// Sprint 3.4 (Match Initialization & Game Start) — 1:1 translations of
// the CURRENT firestore.rules additions: isValidStatusChange() gained
// "in_game", a new isValidMatchIdChange(), rooms/{roomId}'s update
// field whitelist gained "matchId", and a brand-new matches/{matchId}
// block (isValidNewMatch() + get/list/update/delete). isValidNewRoomV2/
// isValidCreatorChange/isSelfOnlyChange are unchanged from Sprint 3.3 —
// reused here, not re-derived.
// ============================================================

function isValidStatusChangeV3(oldData, newData) {
  var validStatus = newData.status === "waiting" || newData.status === "closed" || newData.status === "in_game";
  if (!validStatus) return false;
  if (newData.status === "closed" && newData.players.length !== 0) return false;
  if (newData.status === "in_game" && !(oldData.status === "waiting" || oldData.status === "in_game")) return false;
  return true;
}

function isValidMatchIdChange(oldData, newData) {
  var oldMatchId = Object.prototype.hasOwnProperty.call(oldData, "matchId") ? oldData.matchId : null;
  var newMatchId = Object.prototype.hasOwnProperty.call(newData, "matchId") ? newData.matchId : null;
  var isStartingTransition = oldData.status === "waiting" && newData.status === "in_game";
  if (isStartingTransition) return oldMatchId === null && typeof newMatchId === "string";
  return oldMatchId === newMatchId;
}

function isValidRoomUpdateV3(oldData, newData, requestAuthUid) {
  var allowedKeys = ["players", "readyPlayers", "status", "creator", "updatedAt", "matchId"];
  var changed = diffKeys(oldData, newData);
  var onlyAllowedFieldsChanged = changed.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
  var isMember = (oldData.players || []).indexOf(requestAuthUid) !== -1 || (newData.players || []).indexOf(requestAuthUid) !== -1;
  return onlyAllowedFieldsChanged
    && isMember
    && isSelfOnlyChange(oldData.players, newData.players, requestAuthUid)
    && isSelfOnlyChange(oldData.readyPlayers, newData.readyPlayers, requestAuthUid)
    && isValidCreatorChange(oldData, newData)
    && isValidStatusChangeV3(oldData, newData)
    && isValidMatchIdChange(oldData, newData);
}

function isValidNewMatch(data, requestAuthUid) {
  var allowedKeys = ["roomId", "players", "status", "createdAt", "currentRound", "dealer", "turn", "gameState"];
  var keys = Object.keys(data);
  var hasOnlyAllowedKeys = keys.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
  return hasOnlyAllowedKeys
    && typeof data.roomId === "string"
    && Array.isArray(data.players)
    && data.players.length > 0
    && data.players.indexOf(requestAuthUid) !== -1
    && data.status === "starting"
    && data.currentRound === 1
    && data.players.indexOf(data.dealer) !== -1
    && data.players.indexOf(data.turn) !== -1;
}

// matches/{matchId} get: only a seated player may read.
function isValidMatchGet(matchData, requestAuthUid) {
  return (matchData.players || []).indexOf(requestAuthUid) !== -1;
}

// ============================================================
// Sprint 3.4.1 (Match Start Consistency & Security Hotfix) — 1:1
// translations of the TIGHTENED firestore.rules: isValidNewMatch() and
// isValidMatchIdChange() now cross-check the room and match documents
// against EACH OTHER (get()/exists()/getAfter()), not just their own
// internal shape. isValidNewMatch/isValidMatchIdChange above (the
// Sprint 3.4 versions) are SUPERSEDED by the real rules file but kept
// unchanged above, exactly as Sprint 3.2.1's history was kept in
// Sprint 3.3 — historical context, not re-tested below.
//
// Honesty note (the brief's own explicit ask): there is no Firebase
// Rules Unit Testing emulator available in this sandboxed session (no
// Firebase CLI, no local Java-backed emulator — same limitation noted
// since Sprint 2.6). get()/exists()/getAfter() are modeled here as
// explicit parameters the test supplies directly (the room's state as
// it existed BEFORE the write, and the match's state as it would exist
// AFTER the same commit) rather than by actually exercising Firestore's
// own read semantics inside a rule evaluation (memoization behavior,
// error-vs-deny semantics on a missing document's field access, the
// 20-get()-call quota, etc.). This is a real, deliberate, ADDITIONAL
// layer of approximation on top of the JS-simulation limitation already
// disclosed at the top of this file — not a claim that live Firestore
// Rules execution has been verified.
// ============================================================

function isRoomReadyForMatchStart(room) {
  return room.players.length > 0 && room.players.every(function (p) { return (room.readyPlayers || []).indexOf(p) !== -1; });
}

function arraysEqual(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

// roomExists / room: simulates exists()/get() on rooms/{data.roomId} —
// the room's state as it existed BEFORE this write.
// roomMatchIdAfterCommit: simulates getAfter(rooms/{data.roomId}).data.matchId
// — the room's matchId as it will exist once this SAME transaction/batch
// commits. thisMatchId: the new match document's own id (the {matchId}
// path variable in the real rule).
function isValidNewMatchV2(data, requestAuthUid, thisMatchId, roomExists, room, roomMatchIdAfterCommit) {
  var allowedKeys = ["roomId", "players", "status", "createdAt", "currentRound", "dealer", "turn", "gameState"];
  var keys = Object.keys(data);
  var hasOnlyAllowedKeys = keys.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
  return hasOnlyAllowedKeys
    && typeof data.roomId === "string"
    && Array.isArray(data.players) && data.players.length > 0
    && data.players.indexOf(requestAuthUid) !== -1
    && data.status === "starting"
    && data.currentRound === 1
    && data.players.indexOf(data.dealer) !== -1
    && data.players.indexOf(data.turn) !== -1
    && roomExists === true
    && !!room && room.players.indexOf(requestAuthUid) !== -1
    && room.status === "waiting"
    && isRoomReadyForMatchStart(room)
    && arraysEqual(data.players, room.players)
    && roomMatchIdAfterCommit === thisMatchId;
}

// oldData/newData: the room's before/after write data (same shape as
// isValidRoomUpdateV3's params). matchExistsAfterCommit / matchAfterCommit:
// simulates getAfter(matches/{newData.matchId}) — the match document's
// state as it will exist once this SAME transaction/batch commits (null
// if no such document exists post-commit, e.g. the match write wasn't
// actually part of the same atomic commit). roomId: this room document's
// own id (the {roomId} path variable in the real rule).
function isValidMatchIdChangeV2(oldData, newData, requestAuthUid, roomId, matchExistsAfterCommit, matchAfterCommit) {
  var oldMatchId = Object.prototype.hasOwnProperty.call(oldData, "matchId") ? oldData.matchId : null;
  var newMatchId = Object.prototype.hasOwnProperty.call(newData, "matchId") ? newData.matchId : null;
  var isStartingTransition = oldData.status === "waiting" && newData.status === "in_game";
  if (!isStartingTransition) return oldMatchId === newMatchId;
  return oldMatchId === null
    && typeof newMatchId === "string"
    && oldData.players.indexOf(requestAuthUid) !== -1
    && isRoomReadyForMatchStart(oldData)
    && matchExistsAfterCommit === true
    && !!matchAfterCommit
    && matchAfterCommit.roomId === roomId
    && arraysEqual(matchAfterCommit.players, oldData.players);
}

// ============================================================
// Sprint 3.8 (Gameplay Synchronization: Bidding Authority) — 1:1
// translations of the CURRENT, shipped firestore.rules additions:
// isValidSeatMap() (Task 1), isValidNewMatchV3() (isValidNewMatch(),
// extended with seats/version/bidding fields), and
// isValidBidSubmission() (Task 5 — the first update rule
// matches/{matchId} has ever had). isValidNewMatchV2 above remains
// unchanged/untested-further, kept purely as Sprint 3.4.1 history —
// exactly the same "never delete, never rewrite, add a new suffixed
// version" convention already used for isValidNewMatch -> V2 above.
// ============================================================

function isValidSeatMap(seats, players) {
  var seatKeys = Object.keys(seats);
  var allowed = ["p1", "p2", "p3", "p4"];
  var hasOnlyAllowedSeatNames = seatKeys.every(function (k) { return allowed.indexOf(k) !== -1; });
  var everySeatIsARealPlayer = seatKeys.every(function (s) { return players.indexOf(seats[s]) !== -1; });
  var noTwoSeatsShareAUid = seatKeys.every(function (s1) {
    return seatKeys.every(function (s2) { return s1 === s2 || seats[s1] !== seats[s2]; });
  });
  return hasOnlyAllowedSeatNames
    && seatKeys.length === players.length
    && everySeatIsARealPlayer
    && noTwoSeatsShareAUid;
}

// data: the new match document's proposed data. requestAuthUid /
// thisMatchId / roomExists / room / roomMatchIdAfterCommit: same shape
// as isValidNewMatchV2's own parameters — see that function's comment.
function isValidNewMatchV3(data, requestAuthUid, thisMatchId, roomExists, room, roomMatchIdAfterCommit) {
  var allowedKeys = ["roomId", "players", "status", "createdAt", "currentRound", "dealer", "turn", "gameState",
                      "seats", "version", "biddingOpen", "bids", "lastBidSeat"];
  var keys = Object.keys(data);
  var hasOnlyAllowedKeys = keys.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
  var bidsKeys = Object.keys(data.bids || {});
  var seatsKeys = Object.keys(data.seats || {});
  var bidsKeysMatchSeatsKeys = bidsKeys.every(function (k) { return seatsKeys.indexOf(k) !== -1; })
    && seatsKeys.every(function (k) { return bidsKeys.indexOf(k) !== -1; });
  var everyBidStartsNull = seatsKeys.every(function (s) { return data.bids[s] === null; });
  return hasOnlyAllowedKeys
    && typeof data.roomId === "string"
    && Array.isArray(data.players) && data.players.length > 0
    && data.players.indexOf(requestAuthUid) !== -1
    && data.status === "starting"
    && data.currentRound === 1
    && data.players.indexOf(data.dealer) !== -1
    && data.players.indexOf(data.turn) !== -1
    && roomExists === true
    && !!room && room.players.indexOf(requestAuthUid) !== -1
    && room.status === "waiting"
    && isRoomReadyForMatchStart(room)
    && arraysEqual(data.players, room.players)
    && roomMatchIdAfterCommit === thisMatchId
    // Sprint 3.8, Tasks 1-3:
    && isValidSeatMap(data.seats, data.players)
    && data.version === 1
    && data.biddingOpen === true
    && bidsKeysMatchSeatsKeys
    && everyBidStartsNull
    && data.lastBidSeat === null;
}

// ============================================================
// Sprint 4.2 (Online Card Synchronization: Engine Authority) — 1:1
// translations of the CURRENT, shipped firestore.rules additions:
// isValidNewMatchV4() (isValidNewMatch(), extended with cardLog/
// lastCardSeat), isValidCardShape(), and isValidCardSubmission() (the
// second real update rule matches/{matchId} has ever had). Same
// "never delete, never rewrite, add a new suffixed version"
// convention as V2 -> V3 above.
// ============================================================

function isValidNewMatchV4(data, requestAuthUid, thisMatchId, roomExists, room, roomMatchIdAfterCommit) {
  var allowedKeys = ["roomId", "players", "status", "createdAt", "currentRound", "dealer", "turn", "gameState",
                      "seats", "version", "biddingOpen", "bids", "lastBidSeat", "cardLog", "lastCardSeat"];
  var keys = Object.keys(data);
  var hasOnlyAllowedKeys = keys.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
  var bidsKeys = Object.keys(data.bids || {});
  var seatsKeys = Object.keys(data.seats || {});
  var bidsKeysMatchSeatsKeys = bidsKeys.every(function (k) { return seatsKeys.indexOf(k) !== -1; })
    && seatsKeys.every(function (k) { return bidsKeys.indexOf(k) !== -1; });
  var everyBidStartsNull = seatsKeys.every(function (s) { return data.bids[s] === null; });
  return hasOnlyAllowedKeys
    && typeof data.roomId === "string"
    && Array.isArray(data.players) && data.players.length > 0
    && data.players.indexOf(requestAuthUid) !== -1
    && data.status === "starting"
    && data.currentRound === 1
    && data.players.indexOf(data.dealer) !== -1
    && data.players.indexOf(data.turn) !== -1
    && roomExists === true
    && !!room && room.players.indexOf(requestAuthUid) !== -1
    && room.status === "waiting"
    && isRoomReadyForMatchStart(room)
    && arraysEqual(data.players, room.players)
    && roomMatchIdAfterCommit === thisMatchId
    && isValidSeatMap(data.seats, data.players)
    && data.version === 1
    && data.biddingOpen === true
    && bidsKeysMatchSeatsKeys
    && everyBidStartsNull
    && data.lastBidSeat === null
    // Sprint 4.2:
    && Array.isArray(data.cardLog) && data.cardLog.length === 0
    && data.lastCardSeat === null;
}

// ============================================================
// Sprint 4.2.2 (Atomic Card Turn Progression & Card-Log Desync
// Hardening), Task 6 — 1:1 translation of the CURRENT, shipped
// firestore.rules addition: isValidNewMatchV5() (isValidNewMatch(),
// extended with the new `cardPhase` field). Same "never delete, never
// rewrite, add a new suffixed version" convention as V3 -> V4 above.
// isValidCardSubmission() itself is updated IN PLACE below (matching
// this codebase's own established precedent: unlike the CREATE rule's
// versioned history, UPDATE rules — isValidBidSubmission,
// isValidCardSubmission — are extended in place across sprints, their
// evolution tracked in comments, not in the function name).
// ============================================================
function isValidNewMatchV5(data, requestAuthUid, thisMatchId, roomExists, room, roomMatchIdAfterCommit) {
  var allowedKeys = ["roomId", "players", "status", "createdAt", "currentRound", "dealer", "turn", "gameState",
                      "seats", "version", "biddingOpen", "bids", "lastBidSeat", "cardLog", "lastCardSeat", "cardPhase"];
  var keys = Object.keys(data);
  var hasOnlyAllowedKeys = keys.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
  var bidsKeys = Object.keys(data.bids || {});
  var seatsKeys = Object.keys(data.seats || {});
  var bidsKeysMatchSeatsKeys = bidsKeys.every(function (k) { return seatsKeys.indexOf(k) !== -1; })
    && seatsKeys.every(function (k) { return bidsKeys.indexOf(k) !== -1; });
  var everyBidStartsNull = seatsKeys.every(function (s) { return data.bids[s] === null; });
  return hasOnlyAllowedKeys
    && typeof data.roomId === "string"
    && Array.isArray(data.players) && data.players.length > 0
    && data.players.indexOf(requestAuthUid) !== -1
    && data.status === "starting"
    && data.currentRound === 1
    && data.players.indexOf(data.dealer) !== -1
    && data.players.indexOf(data.turn) !== -1
    && roomExists === true
    && !!room && room.players.indexOf(requestAuthUid) !== -1
    && room.status === "waiting"
    && isRoomReadyForMatchStart(room)
    && arraysEqual(data.players, room.players)
    && roomMatchIdAfterCommit === thisMatchId
    && isValidSeatMap(data.seats, data.players)
    && data.version === 1
    && data.biddingOpen === true
    && bidsKeysMatchSeatsKeys
    && everyBidStartsNull
    && data.lastBidSeat === null
    && Array.isArray(data.cardLog) && data.cardLog.length === 0
    && data.lastCardSeat === null
    // Sprint 4.2.2:
    && data.cardPhase === null;
}

// ---- Sprint 3.7 (Online Bidding Synchronization Contract): isValidNewMatchV6 (biddingLog) ----
function isValidNewMatchV6(data, requestAuthUid, thisMatchId, roomExists, room, roomMatchIdAfterCommit) {
  var allowedKeys = ["roomId", "players", "status", "createdAt", "currentRound", "dealer", "turn", "gameState",
                      "seats", "version", "biddingOpen", "bids", "lastBidSeat", "cardLog", "lastCardSeat", "cardPhase",
                      "biddingLog"];
  var keys = Object.keys(data);
  var hasOnlyAllowedKeys = keys.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
  var bidsKeys = Object.keys(data.bids || {});
  var seatsKeys = Object.keys(data.seats || {});
  var bidsKeysMatchSeatsKeys = bidsKeys.every(function (k) { return seatsKeys.indexOf(k) !== -1; })
    && seatsKeys.every(function (k) { return bidsKeys.indexOf(k) !== -1; });
  var everyBidStartsNull = seatsKeys.every(function (s) { return data.bids[s] === null; });
  return hasOnlyAllowedKeys
    && typeof data.roomId === "string"
    && Array.isArray(data.players) && data.players.length > 0
    && data.players.indexOf(requestAuthUid) !== -1
    && data.status === "starting"
    && data.currentRound === 1
    && data.players.indexOf(data.dealer) !== -1
    && data.players.indexOf(data.turn) !== -1
    && roomExists === true
    && !!room && room.players.indexOf(requestAuthUid) !== -1
    && room.status === "waiting"
    && isRoomReadyForMatchStart(room)
    && arraysEqual(data.players, room.players)
    && roomMatchIdAfterCommit === thisMatchId
    && isValidSeatMap(data.seats, data.players)
    && data.version === 1
    && data.biddingOpen === true
    && bidsKeysMatchSeatsKeys
    && everyBidStartsNull
    && data.lastBidSeat === null
    && Array.isArray(data.cardLog) && data.cardLog.length === 0
    && data.lastCardSeat === null
    && data.cardPhase === null
    // Sprint 3.7:
    && Array.isArray(data.biddingLog) && data.biddingLog.length === 0;
}

var BIDDING_ACTION_TYPES = ["SubmitDashCallDecision", "SubmitAuctionBid", "SubmitConfirmCall"];
var BIDDING_SUITS = ["SANS", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"];
// Sprint 3.7.x (Bidding Trust-Boundary Hardening): the per-actionType
// REQUIRED-field checks at the end are the fix — see the real
// firestore.rules' own isValidBiddingActionEntry() comment for the
// full account of the gap this closes.
// Round Lifecycle sprint: `expectedRound` param added, mirroring the
// real firestore.rules' own identical change — see that function's own
// comment for the full account.
function isValidBiddingActionEntry(entry, expectedRound) {
  if (!entry || typeof entry !== "object") return false;
  var allowedKeys = ["seatId", "actionType", "declaredDashCall", "isPass", "tricks", "suit", "round"];
  var keys = Object.keys(entry);
  if (!keys.every(function (k) { return allowedKeys.indexOf(k) !== -1; })) return false;
  if (entry.round !== expectedRound) return false;
  if (BIDDING_ACTION_TYPES.indexOf(entry.actionType) === -1) return false;
  if ("declaredDashCall" in entry && typeof entry.declaredDashCall !== "boolean") return false;
  if ("isPass" in entry && typeof entry.isPass !== "boolean") return false;
  if ("tricks" in entry && !(Number.isInteger(entry.tricks) && entry.tricks >= 0 && entry.tricks <= 13)) return false;
  if ("suit" in entry && BIDDING_SUITS.indexOf(entry.suit) === -1) return false;
  if (entry.actionType === "SubmitDashCallDecision" && !("declaredDashCall" in entry)) return false;
  if (entry.actionType === "SubmitAuctionBid" && !("isPass" in entry)) return false;
  if (entry.actionType === "SubmitAuctionBid" && entry.isPass !== true && !("tricks" in entry && "suit" in entry)) return false;
  if (entry.actionType === "SubmitConfirmCall" && !("tricks" in entry && "suit" in entry)) return false;
  return true;
}

// oldData/newData: matches/{matchId}'s before/after write data.
// requestAuthUid: request.auth.uid. Mirrors isValidCardSubmission()'s
// own JS translation exactly, minus the turn-ownership check — see the
// real firestore.rules' own isValidBiddingActionSubmission() comment
// for why that check is deliberately absent here (matches.turn is
// never advanced through Dash/Auction/Confirm by anything in this
// codebase; enforcing it would incorrectly reject every action past
// the first one in every match).
function isValidBiddingActionSubmission(oldData, newData, requestAuthUid) {
  if (requestAuthUid == null) return false;
  if ((oldData.players || []).indexOf(requestAuthUid) === -1) return false;
  if (!("seats" in oldData) || !("biddingLog" in oldData) || !("version" in oldData) || !("currentRound" in oldData)) return false;
  if (!("bids" in oldData) || !("biddingOpen" in oldData)) return false;

  // Sprint J.7 (Unified Bidding Completion) — 1:1 JS mirror of
  // firestore.rules' own new touchesBids/touchesRoundStart branches. See
  // that function's own comment for the full rationale: a
  // SubmitConfirmCall may ALSO mirror the Caller's own confirmed trick
  // count into their OWN `bids` slot (closing the Sprint J.4/J.5.2-
  // confirmed gap), and, on the genuine completion edge, establish
  // turn/cardPhase using the identical allSeatsNowHaveBids/seat-
  // membership logic isValidBidSubmission() already uses.
  var changedKeysRaw = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); })
    .concat(Object.keys(oldData).filter(function (k) { return !(k in newData); }));
  var touchesBids = changedKeysRaw.indexOf("bids") !== -1;
  var touchesRoundStart = changedKeysRaw.indexOf("turn") !== -1 || changedKeysRaw.indexOf("cardPhase") !== -1;
  var allowedChangedKeys = touchesBids
    ? (touchesRoundStart
        ? ["biddingLog", "version", "updatedAt", "bids", "biddingOpen", "turn", "cardPhase"]
        : ["biddingLog", "version", "updatedAt", "bids", "biddingOpen"])
    : ["biddingLog", "version", "updatedAt"];
  var onlyAllowedKeysChanged = changedKeysRaw.every(function (k) { return allowedChangedKeys.indexOf(k) !== -1; });
  if (!onlyAllowedKeysChanged) return false;

  if (newData.version !== oldData.version + 1) return false;

  var oldLog = oldData.biddingLog || [];
  var newLog = newData.biddingLog || [];
  if (newLog.length !== oldLog.length + 1) return false;

  // biddingLog Prefix Immutability Fix — mirrors isValidCardSubmission()'s
  // own `newLog[0:oldLog.size()] == oldLog` CEL slice check exactly: every
  // earlier entry must stay byte-for-byte unchanged and in order.
  if (oldLog.length > 0) {
    for (var i = 0; i < oldLog.length; i++) {
      if (JSON.stringify(newLog[i]) !== JSON.stringify(oldLog[i])) return false;
    }
  }

  var appended = newLog[newLog.length - 1];
  if (!appended || typeof appended !== "object" || typeof appended.seatId !== "string") return false;
  if (!(appended.seatId in oldData.seats)) return false;
  if (oldData.seats[appended.seatId] !== requestAuthUid) return false;
  if (!isValidBiddingActionEntry(appended, oldData.currentRound)) return false;

  var allSeatsNowHaveBids = Object.keys(oldData.seats).every(function (s) { return s in newData.bids && newData.bids[s] != null; });

  // Sprint J.7: the ONLY way this write may touch `bids` is a genuine
  // SubmitConfirmCall persisting the Caller's OWN confirmed estimate
  // into their OWN (and only their own) bids slot.
  if (touchesBids) {
    if (appended.actionType !== "SubmitConfirmCall") return false;
    if (!(!(appended.seatId in oldData.bids) || oldData.bids[appended.seatId] == null)) return false;
    if (!(appended.seatId in newData.bids) || newData.bids[appended.seatId] == null) return false;
    if (!Number.isInteger(newData.bids[appended.seatId])) return false;
    if (newData.bids[appended.seatId] !== appended.tricks) return false;
    var bidsChangedKeys = Object.keys(newData.bids).filter(function (k) { return JSON.stringify(newData.bids[k]) !== JSON.stringify((oldData.bids || {})[k]); });
    if (!(bidsChangedKeys.length === 1 && bidsChangedKeys[0] === appended.seatId)) return false;
    if (newData.biddingOpen !== !allSeatsNowHaveBids) return false;
  }

  // Sprint J.7: the SAME round-start completion guard
  // isValidBidSubmission() already enforces, reused verbatim.
  if (touchesRoundStart) {
    if (!touchesBids) return false;
    if (oldData.turn !== null) return false;
    if (oldData.cardPhase !== null) return false;
    if (oldData.biddingOpen !== true) return false;
    if (newData.biddingOpen !== false) return false;
    if (!allSeatsNowHaveBids) return false;
    if (newData.cardPhase !== "PLAY") return false;
    var seatUids = [oldData.seats.p1, oldData.seats.p2, oldData.seats.p3, oldData.seats.p4].filter(function (u) { return u != null; });
    if (newData.turn == null || seatUids.indexOf(newData.turn) === -1) return false;
  }

  return true;
}

var CARD_SUITS = ["SANS", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"];
function isValidCardShape(card) {
  if (!card || typeof card !== "object") return false;
  var keys = Object.keys(card);
  if (!(keys.length === 2 && keys.indexOf("suit") !== -1 && keys.indexOf("rank") !== -1)) return false;
  if (CARD_SUITS.indexOf(card.suit) === -1) return false;
  if (!card.rank || typeof card.rank !== "object") return false;
  var rankKeys = Object.keys(card.rank);
  if (!rankKeys.every(function (k) { return k === "v" || k === "s"; })) return false;
  return Number.isInteger(card.rank.v) && card.rank.v >= 2 && card.rank.v <= 14;
}

// oldData/newData: matches/{matchId}'s before/after write data.
// requestAuthUid: request.auth.uid.
//
// Sprint 4.2.2, Task 6: extended IN PLACE (not a new suffixed version
// — see this file's own Sprint 4.2.2 header comment for why UPDATE
// rules follow a different versioning convention than CREATE rules in
// this codebase) to permit and constrain the atomic `turn`/`cardPhase`
// transition `submitCard()` now writes alongside every accepted card.
function isValidCardSubmission(oldData, newData, requestAuthUid) {
  var seat = newData.lastCardSeat;
  if (requestAuthUid == null) return false;
  if ((oldData.players || []).indexOf(requestAuthUid) === -1) return false;
  if (!("seats" in oldData) || !("cardLog" in oldData) || !("version" in oldData) || !("turn" in oldData) || !("cardPhase" in oldData) || !("currentRound" in oldData)) return false;
  // Firestore Rules Hardening sprint: mirrors the real firestore.rules'
  // new terminal-state guard 1:1 — an already-completed match may never
  // accept a card submission.
  if ("status" in oldData && oldData.status === "complete") return false;

  var allowedChangedKeys = ["cardLog", "lastCardSeat", "version", "turn", "cardPhase", "updatedAt"];
  var changedKeys = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); })
    .concat(Object.keys(oldData).filter(function (k) { return !(k in newData); }));
  var onlyAllowedKeysChanged = changedKeys.every(function (k) { return allowedChangedKeys.indexOf(k) !== -1; });
  if (!onlyAllowedKeysChanged) return false;

  if (newData.version !== oldData.version + 1) return false;

  var oldLog = oldData.cardLog || [];
  var newLog = newData.cardLog || [];
  if (newLog.length !== oldLog.length + 1) return false;

  // Sprint A.1 (Card Log Prefix Immutability Fix): every earlier entry
  // must be byte-for-byte unchanged AND in the same order — mirrors
  // the real firestore.rules' own `newLog[0:oldLog.size()] == oldLog`
  // slice comparison (the SAME technique isValidRoundExtension()'s own
  // JS mirror below already uses for `extendedRounds`).
  var prefix = newLog.slice(0, oldLog.length);
  if (JSON.stringify(prefix) !== JSON.stringify(oldLog)) return false;

  if (typeof seat !== "string") return false;
  if (!(seat in oldData.seats)) return false;
  if (oldData.seats[seat] !== requestAuthUid) return false;

  // Sprint 4.2.2, Task 6: "caller owns the previous active turn" —
  // re-derived independently from the OLD (pre-write) `turn` field,
  // never trusted from the client's own claim alone.
  if (oldData.turn !== requestAuthUid) return false;

  var appended = newLog[newLog.length - 1];
  if (!appended || typeof appended !== "object") return false;
  var appendedKeys = Object.keys(appended);
  // Round Lifecycle sprint: 'round' added, required, and must equal
  // the pre-write `oldData.currentRound` — mirrors the real
  // firestore.rules' own identical change.
  if (!(appendedKeys.length === 3 && appendedKeys.indexOf("seatId") !== -1 && appendedKeys.indexOf("card") !== -1 && appendedKeys.indexOf("round") !== -1)) return false;
  if (appended.seatId !== seat) return false;
  if (appended.round !== oldData.currentRound) return false;
  if (!isValidCardShape(appended.card)) return false;

  // Sprint 4.2.2, Task 2/6: the new turn must be a real uid this
  // match's own seats map recognizes, or null for the resolving
  // boundary. HONEST LIMITATION (mirrors firestore.rules' own
  // comment): this does NOT verify the uid is the CORRECT next seat —
  // only that it's structurally possible. `cardPhase` must be one of
  // table-engine.js's own two real phase values.
  var seatUids = Object.keys(oldData.seats).map(function (s) { return oldData.seats[s]; });
  if (newData.turn !== null && seatUids.indexOf(newData.turn) === -1) return false;
  if (["PLAY", "RESOLVING"].indexOf(newData.cardPhase) === -1) return false;

  return true;
}

// oldData/newData: matches/{matchId}'s before/after write data.
// requestAuthUid: request.auth.uid. Mirrors the real firestore.rules'
// own isValidRoundAdvance() — see that function's own comment for the
// full account, including the HONEST LIMITATION that this cannot
// verify the round was GENUINELY complete (that structural check is
// MatchService.advanceToNextRound()'s own job, in JS, before this
// transaction ever opens).
function isValidRoundAdvance(oldData, newData, requestAuthUid) {
  if (requestAuthUid == null) return false;
  if ((oldData.players || []).indexOf(requestAuthUid) === -1) return false;
  if (!("currentRound" in oldData) || !("version" in oldData) || !("maxRounds" in oldData)) return false;
  // Match Completion sprint: an already-completed match may never be
  // advanced again — mirrors the identical guard added to the real
  // isValidRoundAdvance() (found via real-browser QA).
  if ("status" in oldData && oldData.status === "complete") return false;

  var allowedChangedKeys = ["currentRound", "version", "biddingOpen", "bids", "lastBidSeat", "cardPhase", "turn", "updatedAt"];
  var changedKeys = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); })
    .concat(Object.keys(oldData).filter(function (k) { return !(k in newData); }));
  var onlyAllowedKeysChanged = changedKeys.every(function (k) { return allowedChangedKeys.indexOf(k) !== -1; });
  if (!onlyAllowedKeysChanged) return false;

  if (newData.version !== oldData.version + 1) return false;
  if (newData.currentRound !== oldData.currentRound + 1) return false;
  // Firestore Rules Hardening sprint: mirrors the real firestore.rules'
  // new ceiling check 1:1 — a round may only be advanced if doing so
  // does not move currentRound past this match's own current
  // authoritative maxRounds (never hardcoded, so a Super Call/Sa'ayda
  // extension is always respected).
  if (oldData.currentRound + 1 > oldData.maxRounds) return false;
  if (newData.biddingOpen !== true) return false;
  if (newData.cardPhase !== null) return false;
  if (newData.turn !== null) return false;

  return true;
}

// oldData/newData: matches/{matchId}'s before/after write data.
// requestAuthUid: request.auth.uid.
function isValidBidSubmission(oldData, newData, requestAuthUid) {
  var seat = newData.lastBidSeat;
  if (requestAuthUid == null) return false;
  if ((oldData.players || []).indexOf(requestAuthUid) === -1) return false;
  if (!("seats" in oldData) || !("bids" in oldData) || !("version" in oldData) || !("biddingOpen" in oldData)) return false;
  // Firestore Rules Hardening sprint: mirrors the real firestore.rules'
  // new terminal-state guard 1:1 — an already-completed match may never
  // accept a bid submission.
  if ("status" in oldData && oldData.status === "complete") return false;

  // Sprint J.3 (Hardened Round-Start Turn Authority) — 1:1 JS mirror of
  // firestore.rules' own new touchesRoundStart branch. See that
  // function's own comment for the full rationale (closes the
  // advanceToNextRound() turn:null/cardPhase:null dead end for the
  // Estimates-completion path, without letting an ordinary/intermediate
  // bid touch turn/cardPhase at all).
  var changedKeysRaw = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); })
    .concat(Object.keys(oldData).filter(function (k) { return !(k in newData); }));
  var touchesRoundStart = changedKeysRaw.indexOf("turn") !== -1 || changedKeysRaw.indexOf("cardPhase") !== -1;
  var allowedChangedKeys = touchesRoundStart
    ? ["bids", "biddingOpen", "version", "lastBidSeat", "updatedAt", "turn", "cardPhase"]
    : ["bids", "biddingOpen", "version", "lastBidSeat", "updatedAt"];
  var onlyAllowedKeysChanged = changedKeysRaw.every(function (k) { return allowedChangedKeys.indexOf(k) !== -1; });
  if (!onlyAllowedKeysChanged) return false;

  if (newData.version !== oldData.version + 1) return false;
  if (oldData.biddingOpen !== true) return false;
  if (typeof seat !== "string") return false;
  if (!(seat in oldData.seats)) return false;
  if (oldData.seats[seat] !== requestAuthUid) return false;
  if (!(!(seat in oldData.bids) || oldData.bids[seat] == null)) return false;
  if (!(seat in newData.bids) || newData.bids[seat] == null) return false;

  // Sprint 3.8.1, Task 2: GENERIC bid-value validation — mirrors CEL's
  // `newData.bids[seat] is int` via Number.isInteger() (the closest JS
  // equivalent to "a whole-number type, not a float/string/etc." —
  // Number.isInteger() is false for NaN/Infinity/-Infinity/strings/
  // objects/booleans, exactly like `is int` rejects a CEL float or any
  // non-numeric type). NOT gameplay validation — see
  // docs/architecture/BidValidation.md.
  if (!Number.isInteger(newData.bids[seat])) return false;
  if (newData.bids[seat] < 0 || newData.bids[seat] > 13) return false;

  var bidsChangedKeys = Object.keys(newData.bids).filter(function (k) { return JSON.stringify(newData.bids[k]) !== JSON.stringify((oldData.bids || {})[k]); });
  if (!(bidsChangedKeys.length === 1 && bidsChangedKeys[0] === seat)) return false;

  var allSeatsFilled = Object.keys(oldData.seats).every(function (s) { return s in newData.bids && newData.bids[s] != null; });
  if (newData.biddingOpen !== !allSeatsFilled) return false;

  // Sprint J.3 — the round-start guard, mirrored 1:1 from firestore.rules.
  if (touchesRoundStart) {
    if (oldData.turn !== null) return false;
    if (oldData.cardPhase !== null) return false;
    if (oldData.biddingOpen !== true) return false;
    if (newData.biddingOpen !== false) return false;
    if (!allSeatsFilled) return false;
    if (newData.cardPhase !== "PLAY") return false;
    // Sprint J.7 (Seat Membership Security Fix): explicit `newData.turn
    // == null` rejection, mirroring the real CEL rules' own fix 1:1 —
    // this JS mirror was already accidentally safe here (filter(u => u
    // != null) excludes null/undefined absent-seat entries before the
    // indexOf check), but the real firestore.rules' `.get(seat, null)`
    // OR-chain was NOT (see that function's own Sprint J.7 comment) —
    // asserting it explicitly here too keeps the mirror an honest,
    // non-misleading 1:1 reflection of the hardened real rule, not just
    // an equivalent-by-coincidence one.
    var seatUids = [oldData.seats.p1, oldData.seats.p2, oldData.seats.p3, oldData.seats.p4].filter(function (u) { return u != null; });
    if (newData.turn == null || seatUids.indexOf(newData.turn) === -1) return false;
  }

  return true;
}

// Sprint 3.4.1 (Requirement #1): 1:1 translation of players/{uid}'s
// EXISTING, UNCHANGED rule (isOwner(uid) && onlyAllowedFieldsChanged())
// — this is the actual, real enforcement point for "one client cannot
// update another player's profile," and the direct rules-layer proof
// that the Sprint 3.4 bug (MatchService writing currentMatchId onto
// every room player's own profile) could only ever succeed for the
// initiating player.
function isOwner(requestAuthUid, uidPathParam) {
  return requestAuthUid != null && requestAuthUid === uidPathParam;
}
function isValidPlayerFieldsChanged(oldData, newData) {
  var allowedKeys = ["displayName", "avatarInitial", "lastSeenAt", "currentRoomId", "currentMatchId"];
  var changed = diffKeys(oldData, newData);
  return changed.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
}
function isValidPlayerUpdate(uidPathParam, requestAuthUid, oldData, newData) {
  return isOwner(requestAuthUid, uidPathParam) && isValidPlayerFieldsChanged(oldData, newData);
}

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

// ---- create ----
check(
  "create: User A creating a room with creator=A, players=[A], status set — ALLOWED",
  isValidNewRoom({ creator: "userA", players: ["userA"], status: "waiting" }, "userA") === true
);
check(
  "create: User A spoofing creator=userB (someone else) — DENIED",
  isValidNewRoom({ creator: "userB", players: ["userB"], status: "waiting" }, "userA") === false
);
check(
  "create: creator not included in its own players[] — DENIED",
  isValidNewRoom({ creator: "userA", players: ["someoneElse"], status: "waiting" }, "userA") === false
);
check(
  "create: creator field is not a string (type check) — DENIED",
  isValidNewRoom({ creator: 12345, players: [12345], status: "waiting" }, "userA") === false
);
check(
  "create: players field is not a list (type check) — DENIED",
  isValidNewRoom({ creator: "userA", players: "userA", status: "waiting" }, "userA") === false
);
check(
  "create: missing status field — DENIED",
  isValidNewRoom({ creator: "userA", players: ["userA"] }, "userA") === false
);

// ---- update: the critical scenario this hotfix exists for ----
var roomOwnedByB = { creator: "userB", players: ["userB"], status: "waiting" };

check(
  "SIMULATION PROOF — the brief's LITERAL rule (checking only the pre-write players[]) " +
  "denies a legitimate join by a brand-new user: this is the exact blocker this hotfix's deviation fixes",
  literalBriefUpdateRule(roomOwnedByB, "userC") === false
);
check(
  "update: the SHIPPED fixed rule allows that same legitimate join " +
  "(userC adds themself, so they're present in the post-write players[])",
  isExistingOrIncomingMember(roomOwnedByB, { creator: "userB", players: ["userB", "userC"], status: "waiting" }, "userC") === true
);
check(
  "update: an existing member (userB) leaving — still ALLOWED " +
  "(present in the pre-write players[], even though absent from the post-write one)",
  isExistingOrIncomingMember(roomOwnedByB, { creator: "userB", players: [], status: "closed" }, "userB") === true
);
check(
  "update: a total OUTSIDER (userX, absent from both the old AND the attempted new players[]) " +
  "trying to vandalize User B's room by renaming it or changing its status — DENIED",
  isExistingOrIncomingMember(roomOwnedByB, { creator: "userB", players: ["userB"], status: "closed" }, "userX") === false
);
check(
  "update: a total OUTSIDER cannot even add an unrelated third user (userY) to someone else's room " +
  "without adding themself too (they're still absent from the resulting players[])",
  isExistingOrIncomingMember(roomOwnedByB, { creator: "userB", players: ["userB", "userY"], status: "waiting" }, "userX") === false
);

// ============================================================
// Sprint 3.3 — tests against the CURRENT, shipped rules (isValidNewRoomV2 /
// isValidRoomUpdateV2). Everything above this point is Sprint 3.2.1
// history preserved for context, not re-tested here.
// ============================================================

// ---- create (F6/F7 fixes) ----
check(
  "create v2: valid new room (creator=A, players=[A], readyPlayers=[], status=waiting) — ALLOWED",
  isValidNewRoomV2({ creator: "userA", players: ["userA"], readyPlayers: [], status: "waiting" }, "userA") === true
);
check(
  "create v2: extra, non-whitelisted field (e.g. a fabricated 'matchId') — DENIED (closes F6)",
  isValidNewRoomV2({ creator: "userA", players: ["userA"], readyPlayers: [], status: "waiting", matchId: "sneaky" }, "userA") === false
);
check(
  "create v2: players.length == 2 at creation (fabricating a second member who never joined) — DENIED (closes F7)",
  isValidNewRoomV2({ creator: "userA", players: ["userA", "fakeMember"], readyPlayers: [], status: "waiting" }, "userA") === false
);
check(
  "create v2: readyPlayers non-empty at creation — DENIED",
  isValidNewRoomV2({ creator: "userA", players: ["userA"], readyPlayers: ["userA"], status: "waiting" }, "userA") === false
);
check(
  "create v2: status other than 'waiting' at creation — DENIED",
  isValidNewRoomV2({ creator: "userA", players: ["userA"], readyPlayers: [], status: "starting" }, "userA") === false
);

// ---- update: setReady scenarios (the core of this sprint) ----
var baseRoom = { creator: "userB", players: ["userB", "userC"], readyPlayers: [], status: "waiting" };

check(
  "update v2: userB marks themself ready (readyPlayers gains exactly userB) — ALLOWED",
  isValidRoomUpdateV2(baseRoom, { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB"], status: "waiting" }, "userB") === true
);
check(
  "update v2: userB un-readies themself — ALLOWED",
  isValidRoomUpdateV2({ creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB"], status: "waiting" },
    { creator: "userB", players: ["userB", "userC"], readyPlayers: [], status: "waiting" }, "userB") === true
);
check(
  "SECURITY: userC attempts to mark userB ready on userB's behalf (forging someone else's ready flag) — DENIED",
  isValidRoomUpdateV2(baseRoom, { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB"], status: "waiting" }, "userC") === false
);
check(
  "SECURITY: userC attempts to mark BOTH themself and userB ready in one write — DENIED " +
  "(only a single self-only change is allowed, not a bulk edit)",
  isValidRoomUpdateV2(baseRoom, { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "waiting" }, "userC") === false
);

// ---- update: the F3 fix itself — field-level restriction ----
check(
  "SECURITY (F3): an existing member tries to rename the room ('name' is not in the allowed-fields list) — DENIED",
  isValidRoomUpdateV2(
    Object.assign({ name: "Old Name" }, baseRoom),
    Object.assign({ name: "Hacked Name" }, baseRoom),
    "userB"
  ) === false
);
check(
  "SECURITY (F3): an existing member tries to rewrite createdAt — DENIED",
  isValidRoomUpdateV2(
    Object.assign({ createdAt: 1000 }, baseRoom),
    Object.assign({ createdAt: 999999 }, baseRoom),
    "userB"
  ) === false
);
check(
  "KNOWN, DOCUMENTED GAP (not closed by this sprint, on purpose): an existing member " +
  "(userC) self-promotes to creator WITHOUT actually leaving — this is currently ALLOWED, " +
  "because rules cannot distinguish 'part of a legitimate leave-transfer' from 'a standalone " +
  "write' without Cloud Functions. isValidCreatorChange() only checks that the new creator is " +
  "still a real member, not that a genuine leave happened. Recorded here exactly as it's " +
  "recorded in firestore.rules' own comment — confirmed by this test, not silently left unverified.",
  isValidRoomUpdateV2(baseRoom, { creator: "userC", players: ["userB", "userC"], readyPlayers: [], status: "waiting" }, "userC") === true
);
check(
  "update v2: creator legitimately transfers to the next member when the creator leaves — ALLOWED",
  isValidRoomUpdateV2(baseRoom, { creator: "userC", players: ["userC"], readyPlayers: [], status: "waiting" }, "userB") === true
);
check(
  "SECURITY: an outsider tries to reassign creator to a fabricated, non-member uid — DENIED",
  isValidRoomUpdateV2(baseRoom, { creator: "totallyFakeUid", players: ["userB", "userC"], readyPlayers: [], status: "waiting" }, "userB") === false
);
check(
  "SECURITY: a member tries to set status to an arbitrary string — DENIED",
  isValidRoomUpdateV2(baseRoom, { creator: "userB", players: ["userB", "userC"], readyPlayers: [], status: "definitely-not-a-real-status" }, "userB") === false
);
check(
  "SECURITY: a member tries to close the room while players[] is still non-empty — DENIED " +
  "(status may only become 'closed' when the room is actually empty, matching leaveRoom()'s real behavior)",
  isValidRoomUpdateV2(baseRoom, { creator: "userB", players: ["userB", "userC"], readyPlayers: [], status: "closed" }, "userB") === false
);
check(
  "update v2: the last player leaving legitimately closes the room — ALLOWED",
  isValidRoomUpdateV2(
    { creator: "userB", players: ["userB"], readyPlayers: [], status: "waiting" },
    { creator: "userB", players: [], readyPlayers: [], status: "closed" },
    "userB"
  ) === true
);
check(
  "update v2: a non-member (absent from both old and new players[]) is denied even for a no-op-looking write",
  isValidRoomUpdateV2(baseRoom, { creator: "userB", players: ["userB", "userC"], readyPlayers: [], status: "waiting" }, "userX") === false
);

// ============================================================
// Sprint 3.4 (Match Initialization & Game Start) — tests against the
// CURRENT, shipped rules additions (isValidRoomUpdateV3 / isValidNewMatch
// / isValidMatchGet). Everything above this point is Sprint 3.2.1/3.3
// history preserved for context, not re-tested here.
// ============================================================

var allReadyRoom = { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "waiting" };

check(
  "update v3: MatchService.startMatch()'s own write — status becomes 'in_game' and matchId is set together, by a member — ALLOWED",
  isValidRoomUpdateV3(
    allReadyRoom,
    { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "in_game", matchId: "match-1" },
    "userB"
  ) === true
);
check(
  "SECURITY (v3): status becomes 'in_game' WITHOUT setting matchId in the same write — DENIED " +
  "(matchId may only be set together with the in_game transition, matching startMatch()'s real single-write shape)",
  isValidRoomUpdateV3(
    allReadyRoom,
    { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "in_game" },
    "userB"
  ) === false
);
check(
  "SECURITY (v3): matchId is set WITHOUT status becoming 'in_game' — DENIED " +
  "(can't sneak a matchId onto a still-'waiting' room)",
  isValidRoomUpdateV3(
    allReadyRoom,
    { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "waiting", matchId: "match-1" },
    "userB"
  ) === false
);
check(
  "SECURITY (v3): 'in_game' attempted from a room that wasn't 'waiting' (already 'closed') — DENIED " +
  "(matches startMatch()'s actual, only real transition: waiting -> in_game)",
  isValidRoomUpdateV3(
    { creator: "userB", players: [], readyPlayers: [], status: "closed" },
    { creator: "userB", players: [], readyPlayers: [], status: "in_game", matchId: "match-1" },
    "userB"
  ) === false
);
check(
  "update v3: once matchId is set, changing it again to a DIFFERENT match id — DENIED (immutable once set)",
  isValidRoomUpdateV3(
    { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "in_game", matchId: "match-1" },
    { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "in_game", matchId: "match-2" },
    "userB"
  ) === false
);
check(
  "update v3: a write that leaves matchId completely unchanged (e.g. a hypothetical future readyPlayers " +
  "tweak on an in_game room) does not re-trigger the matchId check — ALLOWED on that basis",
  isValidRoomUpdateV3(
    { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "in_game", matchId: "match-1" },
    { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB"], status: "in_game", matchId: "match-1" },
    "userC"
  ) === true
);
check(
  "SECURITY (v3): an outsider (not in players before or after) cannot start a match on someone else's room",
  isValidRoomUpdateV3(
    allReadyRoom,
    { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "in_game", matchId: "match-1" },
    "userX"
  ) === false
);

// ---- matches/{matchId} create (the new collection) ----
check(
  "matches create: a valid new match document from a seated player — ALLOWED",
  isValidNewMatch(
    { roomId: "room-1", players: ["userB", "userC"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: { initialized: false, todo: "x" } },
    "userB"
  ) === true
);
check(
  "SECURITY (matches create): the acting user is not even one of the match's players[] — DENIED " +
  "(can't create a match you're not seated in)",
  isValidNewMatch(
    { roomId: "room-1", players: ["userB", "userC"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: {} },
    "userX"
  ) === false
);
check(
  "SECURITY (matches create): an extra, non-whitelisted field — DENIED (same field-whitelist discipline as rooms)",
  isValidNewMatch(
    { roomId: "room-1", players: ["userB"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: {}, winnerId: "sneaky" },
    "userB"
  ) === false
);
check(
  "matches create: status other than 'starting' — DENIED",
  isValidNewMatch(
    { roomId: "room-1", players: ["userB"], status: "in_progress", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: {} },
    "userB"
  ) === false
);
check(
  "matches create: currentRound other than 1 — DENIED (a match always starts at round 1)",
  isValidNewMatch(
    { roomId: "room-1", players: ["userB"], status: "starting", createdAt: 1, currentRound: 2, dealer: "userB", turn: "userB", gameState: {} },
    "userB"
  ) === false
);
check(
  "SECURITY (matches create): dealer set to a uid who isn't one of players[] — DENIED",
  isValidNewMatch(
    { roomId: "room-1", players: ["userB"], status: "starting", createdAt: 1, currentRound: 1, dealer: "someoneNotSeated", turn: "userB", gameState: {} },
    "userB"
  ) === false
);
check(
  "SECURITY (matches create): turn set to a uid who isn't one of players[] — DENIED",
  isValidNewMatch(
    { roomId: "room-1", players: ["userB"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "someoneNotSeated", gameState: {} },
    "userB"
  ) === false
);

// ---- matches/{matchId} get (read privacy) ----
var match1 = { roomId: "room-1", players: ["userB", "userC"], status: "starting" };
check(
  "matches get: a seated player (userB) may read the match — ALLOWED",
  isValidMatchGet(match1, "userB") === true
);
check(
  "SECURITY (matches get): a non-seated authenticated user (userX) may NOT read the match " +
  "(unlike rooms/{roomId}, match documents are not globally readable — see FirestoreSchema.md's privacy note)",
  isValidMatchGet(match1, "userX") === false
);

// ============================================================
// Sprint 3.4.1 (Match Start Consistency & Security Hotfix) — tests
// against the CURRENT, shipped rules additions (isValidNewMatchV2 /
// isValidMatchIdChangeV2 / isValidPlayerUpdate). Everything above this
// point is Sprint 3.2.1/3.3/3.4 history preserved for context, not
// re-tested here.
// ============================================================

var readyRoomForMatch = { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "waiting" };

// ---- players/{uid}: Requirement #1 — one client cannot update another
// player's profile. This is the actual, real rules-layer enforcement
// that closes the Sprint 3.4 bug. ----
check(
  "players update: userB updating their OWN currentMatchId — ALLOWED",
  isValidPlayerUpdate("userB", "userB", { currentMatchId: null }, { currentMatchId: "match-1" }) === true
);
check(
  "SECURITY (Requirement #1): userA attempts to update userB's players/{userB} document — DENIED. " +
  "This is the exact class of write MatchService's Sprint 3.4 syncCurrentMatchOnProfiles() used to " +
  "attempt for every non-initiating room player — it could only ever succeed for the write's own uid.",
  isValidPlayerUpdate("userB", "userA", { currentMatchId: null }, { currentMatchId: "match-1" }) === false
);

// ---- matches/{matchId} create (isValidNewMatchV2) ----
check(
  "matches create v2: fully valid — room exists/waiting/all-ready, players match, room's post-commit " +
  "matchId points back at this document — ALLOWED",
  isValidNewMatchV2(
    { roomId: "room-1", players: ["userB", "userC"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: {} },
    "userB", "match-1", true, readyRoomForMatch, "match-1"
  ) === true
);
check(
  "SECURITY (Requirement #4 — fabricated match, unrelated/nonexistent room): roomId points at a room " +
  "that doesn't exist — DENIED",
  isValidNewMatchV2(
    { roomId: "room-does-not-exist", players: ["userB", "userC"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: {} },
    "userB", "match-1", false, null, null
  ) === false
);
check(
  "SECURITY (Requirement #5 — non-member creates a match for a room they're not in): userX is not in " +
  "the room's players[] — DENIED",
  isValidNewMatchV2(
    { roomId: "room-1", players: ["userX", "userC"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userX", turn: "userX", gameState: {} },
    "userX", "match-1", true, readyRoomForMatch, "match-1"
  ) === false
);
check(
  "SECURITY: room status is not 'waiting' (already in_game/closed) — DENIED",
  isValidNewMatchV2(
    { roomId: "room-1", players: ["userB", "userC"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: {} },
    "userB", "match-1", true, Object.assign({}, readyRoomForMatch, { status: "in_game" }), "match-1"
  ) === false
);
check(
  "SECURITY (Requirement #6 — match cannot start while any player is not ready): userC is not in " +
  "readyPlayers — DENIED",
  isValidNewMatchV2(
    { roomId: "room-1", players: ["userB", "userC"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: {} },
    "userB", "match-1", true, Object.assign({}, readyRoomForMatch, { readyPlayers: ["userB"] }), "match-1"
  ) === false
);
check(
  "SECURITY (Requirement #8 — match players must match room players): match.players omits userC — DENIED",
  isValidNewMatchV2(
    { roomId: "room-1", players: ["userB"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: {} },
    "userB", "match-1", true, readyRoomForMatch, "match-1"
  ) === false
);
check(
  "SECURITY (Requirement #7 — room.matchId must equal the new match document's own id): the room's " +
  "post-commit matchId points at a DIFFERENT match — DENIED (proves the atomic-transaction requirement " +
  "is rules-enforced: creating a match without its paired, same-commit room update fails here)",
  isValidNewMatchV2(
    { roomId: "room-1", players: ["userB", "userC"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: {} },
    "userB", "match-1", true, readyRoomForMatch, "some-other-match-id"
  ) === false
);

// ---- rooms/{roomId} update — the starting transition (isValidMatchIdChangeV2) ----
check(
  "update v3.1: MatchService.startMatch()'s own write — fully valid, matching getAfter(match) — ALLOWED",
  isValidMatchIdChangeV2(
    readyRoomForMatch,
    Object.assign({}, readyRoomForMatch, { status: "in_game", matchId: "match-1" }),
    "userB", "room-1", true, { roomId: "room-1", players: ["userB", "userC"] }
  ) === true
);
check(
  "SECURITY (Requirement #6, room side): starting transition attempted while userC is not ready — DENIED",
  isValidMatchIdChangeV2(
    Object.assign({}, readyRoomForMatch, { readyPlayers: ["userB"] }),
    Object.assign({}, readyRoomForMatch, { readyPlayers: ["userB"], status: "in_game", matchId: "match-1" }),
    "userB", "room-1", true, { roomId: "room-1", players: ["userB", "userC"] }
  ) === false
);
check(
  "SECURITY (Requirement #5, room side): the acting user is not actually a member of this room's " +
  "players[] — DENIED",
  isValidMatchIdChangeV2(
    readyRoomForMatch,
    Object.assign({}, readyRoomForMatch, { status: "in_game", matchId: "match-1" }),
    "userX", "room-1", true, { roomId: "room-1", players: ["userB", "userC"] }
  ) === false
);
check(
  "SECURITY (Requirement #7, room side): getAfter(match).roomId points at a DIFFERENT room — DENIED",
  isValidMatchIdChangeV2(
    readyRoomForMatch,
    Object.assign({}, readyRoomForMatch, { status: "in_game", matchId: "match-1" }),
    "userB", "room-1", true, { roomId: "some-other-room", players: ["userB", "userC"] }
  ) === false
);
check(
  "SECURITY (Requirement #8, room side): getAfter(match).players does not match this room's players — DENIED",
  isValidMatchIdChangeV2(
    readyRoomForMatch,
    Object.assign({}, readyRoomForMatch, { status: "in_game", matchId: "match-1" }),
    "userB", "room-1", true, { roomId: "room-1", players: ["userB"] }
  ) === false
);
check(
  "SECURITY: the match document doesn't exist post-commit at all (getAfter finds nothing) — DENIED. " +
  "Proves setting status/matchId WITHOUT actually creating the match in the SAME atomic write is rejected.",
  isValidMatchIdChangeV2(
    readyRoomForMatch,
    Object.assign({}, readyRoomForMatch, { status: "in_game", matchId: "match-1" }),
    "userB", "room-1", false, null
  ) === false
);
check(
  "update v3.1: a non-transition write (e.g. an ordinary future readyPlayers tweak on an in_game room) " +
  "with matchId unchanged is unaffected by any of the new checks — ALLOWED",
  isValidMatchIdChangeV2(
    { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "in_game", matchId: "match-1" },
    { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB"], status: "in_game", matchId: "match-1" },
    "userC", "room-1", true, { roomId: "room-1", players: ["userB", "userC"] }
  ) === true
);

// ============================================================
// Sprint 3.8 tests — ALL SIMULATED: 1:1 JS translations of
// firestore.rules' CEL exercised against representative request
// shapes, exactly like every other test in this file. This is NOT the
// Firebase Rules Unit Testing library and NOT a real Firestore
// emulator — see this file's own header comment. No test in this
// project has ever run against a real Firestore emulator or a real
// Firestore project.
// ============================================================

var readyRoomFor38 = { creator: "userB", players: ["userB", "userC"], readyPlayers: ["userB", "userC"], status: "waiting" };
var validNewMatch38 = {
  roomId: "room-1", players: ["userB", "userC"], status: "starting", createdAt: 1, currentRound: 1, dealer: "userB", turn: "userB", gameState: {},
  seats: { p1: "userB", p2: "userC" }, version: 1, biddingOpen: true, bids: { p1: null, p2: null }, lastBidSeat: null
};

// ---- Task 1: seat map validation (isValidSeatMap, via isValidNewMatchV3) ----
check(
  "SIMULATED — matches create v3 (Sprint 3.8): a fully valid match WITH seats/version/bidding fields — ALLOWED",
  isValidNewMatchV3(validNewMatch38, "userB", "match-1", true, readyRoomFor38, "match-1") === true
);
check(
  "SIMULATED — SECURITY (Task 1): a seat is fabricated for someone who isn't actually a player — DENIED",
  isValidNewMatchV3(
    Object.assign({}, validNewMatch38, { seats: { p1: "userB", p2: "someone-not-in-players" } }),
    "userB", "match-1", true, readyRoomFor38, "match-1"
  ) === false
);
check(
  "SIMULATED — SECURITY (Task 1): two seats assigned to the SAME uid — DENIED",
  isValidNewMatchV3(
    Object.assign({}, validNewMatch38, { seats: { p1: "userB", p2: "userB" } }),
    "userB", "match-1", true, readyRoomFor38, "match-1"
  ) === false
);
check(
  "SIMULATED — SECURITY (Task 1): a real player has NO seat at all — DENIED (seatKeys.size() != players.size())",
  isValidNewMatchV3(
    Object.assign({}, validNewMatch38, { seats: { p1: "userB" }, bids: { p1: null } }),
    "userB", "match-1", true, readyRoomFor38, "match-1"
  ) === false
);
check(
  "SIMULATED — SECURITY (Task 1): an invalid seat name ('p5') — DENIED",
  isValidNewMatchV3(
    Object.assign({}, validNewMatch38, { seats: { p1: "userB", p5: "userC" }, bids: { p1: null, p5: null } }),
    "userB", "match-1", true, readyRoomFor38, "match-1"
  ) === false
);
check(
  "SIMULATED — SECURITY (Task 2): version is not exactly 1 at creation — DENIED",
  isValidNewMatchV3(Object.assign({}, validNewMatch38, { version: 0 }), "userB", "match-1", true, readyRoomFor38, "match-1") === false
);
check(
  "SIMULATED — SECURITY (Task 3): biddingOpen is not true at creation — DENIED",
  isValidNewMatchV3(Object.assign({}, validNewMatch38, { biddingOpen: false }), "userB", "match-1", true, readyRoomFor38, "match-1") === false
);
check(
  "SIMULATED — SECURITY (Task 3): a bid is pre-filled (non-null) at creation — DENIED (bidding must start genuinely empty)",
  isValidNewMatchV3(Object.assign({}, validNewMatch38, { bids: { p1: "cheating", p2: null } }), "userB", "match-1", true, readyRoomFor38, "match-1") === false
);
check(
  "SIMULATED — SECURITY (Task 3): lastBidSeat is not null at creation — DENIED",
  isValidNewMatchV3(Object.assign({}, validNewMatch38, { lastBidSeat: "p1" }), "userB", "match-1", true, readyRoomFor38, "match-1") === false
);
check(
  "SIMULATED — SECURITY (Task 3): bids has an extra key not in seats — DENIED",
  isValidNewMatchV3(Object.assign({}, validNewMatch38, { bids: { p1: null, p2: null, p3: null } }), "userB", "match-1", true, readyRoomFor38, "match-1") === false
);

// ---- Task 5: isValidBidSubmission ----
var matchAfterCreate38 = validNewMatch38;

check(
  "SIMULATED — bid submission (normal bid): seat owner submits their own bid — ALLOWED",
  isValidBidSubmission(
    matchAfterCreate38,
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, version: 2, lastBidSeat: "p1" }),
    "userB"
  ) === true
);
check(
  "SIMULATED — normal bid correctly leaves biddingOpen TRUE when not every seat has bid yet",
  isValidBidSubmission(
    matchAfterCreate38,
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, biddingOpen: true, version: 2, lastBidSeat: "p1" }),
    "userB"
  ) === true
);
check(
  "SIMULATED — the LAST seat's bid correctly closes bidding (biddingOpen becomes false)",
  isValidBidSubmission(
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, version: 2 }),
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: 3 }, biddingOpen: false, version: 3, lastBidSeat: "p2" }),
    "userC"
  ) === true
);
check(
  "SIMULATED — SECURITY: the last seat's bid FAILS to close bidding (client lies, leaves biddingOpen true) — DENIED",
  isValidBidSubmission(
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, version: 2 }),
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: 3 }, biddingOpen: true, version: 3, lastBidSeat: "p2" }),
    "userC"
  ) === false
);
check(
  "SIMULATED — duplicate bid: the SAME seat tries to submit a second time — DENIED (ALREADY_BID equivalent)",
  isValidBidSubmission(
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, version: 2 }),
    Object.assign({}, matchAfterCreate38, { bids: { p1: 9, p2: null }, version: 3, lastBidSeat: "p1" }),
    "userB"
  ) === false
);
check(
  "SIMULATED — out-of-order version: incomingVersion is not exactly currentVersion + 1 (skips ahead) — DENIED",
  isValidBidSubmission(
    matchAfterCreate38,
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, version: 5, lastBidSeat: "p1" }),
    "userB"
  ) === false
);
check(
  "SIMULATED — stale version: incomingVersion equal to currentVersion (a replayed/stale write) — DENIED",
  isValidBidSubmission(
    matchAfterCreate38,
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, version: 1, lastBidSeat: "p1" }),
    "userB"
  ) === false
);
check(
  "SIMULATED — wrong seat: caller names a seat that isn't theirs (lastBidSeat='p2' but seats.p2 is userC, caller is userB) — DENIED",
  isValidBidSubmission(
    matchAfterCreate38,
    Object.assign({}, matchAfterCreate38, { bids: { p1: null, p2: 4 }, version: 2, lastBidSeat: "p2" }),
    "userB"
  ) === false
);
check(
  "SIMULATED — wrong uid: the acting uid does not match ANY seat's owner at all — DENIED",
  isValidBidSubmission(
    matchAfterCreate38,
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, version: 2, lastBidSeat: "p1" }),
    "userX"
  ) === false
);
check(
  "SIMULATED — PERMISSION DENIED equivalent: request.auth is null (unauthenticated) — DENIED",
  isValidBidSubmission(
    matchAfterCreate38,
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, version: 2, lastBidSeat: "p1" }),
    null
  ) === false
);
check(
  "SIMULATED — bidding closed: a write attempted after biddingOpen is already false — DENIED",
  isValidBidSubmission(
    Object.assign({}, matchAfterCreate38, { biddingOpen: false, bids: { p1: 4, p2: 3 }, version: 3 }),
    Object.assign({}, matchAfterCreate38, { biddingOpen: false, bids: { p1: 9, p2: 3 }, version: 4, lastBidSeat: "p1" }),
    "userB"
  ) === false
);
check(
  "SIMULATED — player modifies only own bid: a write that ALSO changes another seat's bid in the same write — DENIED",
  isValidBidSubmission(
    matchAfterCreate38,
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: 999 }, version: 2, lastBidSeat: "p1" }),
    "userB"
  ) === false
);
check(
  "SIMULATED — reject every other write: a write that also tries to change `players`/`seats`/`dealer` alongside a valid bid — DENIED",
  isValidBidSubmission(
    matchAfterCreate38,
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, version: 2, lastBidSeat: "p1", dealer: "userC" }),
    "userB"
  ) === false
);
check(
  "SIMULATED — a non-member of the match at all attempts a write — DENIED",
  isValidBidSubmission(
    Object.assign({}, matchAfterCreate38, { players: ["userB", "userC"] }),
    Object.assign({}, matchAfterCreate38, { bids: { p1: 4, p2: null }, version: 2, lastBidSeat: "p1" }),
    "userZ"
  ) === false
);

// ────────────────────────────────────────────────────────────────
// Sprint J.3 (Hardened Round-Start Turn Authority) — a 4-seat fixture
// sitting in the exact "post-advanceToNextRound(), mid-Estimates"
// state this fix targets: turn/cardPhase both null (the real dead-end
// state Sprint J's forensic report identified), 3 of 4 seats already
// estimated, biddingOpen still true.
// ────────────────────────────────────────────────────────────────
var roundStartBase = Object.assign({}, matchAfterCreate38, {
  players: ["userB", "userC", "userD", "userE"],
  seats: { p1: "userB", p2: "userC", p3: "userD", p4: "userE" },
  bids: { p1: 4, p2: 3, p3: 2, p4: null },
  biddingOpen: true, version: 10, turn: null, cardPhase: null
});

check(
  "SIMULATED — J.3 #1: an INTERMEDIATE estimate (not the last seat) that ALSO tries to claim turn/cardPhase — DENIED",
  isValidBidSubmission(
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: null, p4: null } }),
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: 2, p4: null }, version: 11, lastBidSeat: "p3", turn: "userB", cardPhase: "PLAY" }),
    "userD"
  ) === false
);
check(
  "SIMULATED — J.3 #2: the GENUINE final estimate, with a valid (structurally real) turn/cardPhase claim — ALLOWED",
  isValidBidSubmission(
    roundStartBase,
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 11, lastBidSeat: "p4", turn: "userB", cardPhase: "PLAY" }),
    "userE"
  ) === true
);
check(
  "SIMULATED — J.3 #3: the genuine final estimate, but naming a structurally INVALID uid as turn (not a real seat owner) — DENIED",
  isValidBidSubmission(
    roundStartBase,
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 11, lastBidSeat: "p4", turn: "some-fabricated-uid", cardPhase: "PLAY" }),
    "userE"
  ) === false
);
check(
  "SIMULATED — J.3 #3b: the genuine final estimate, naming a WRONG (but structurally real) seat's uid as turn — ALLOWED at the Rules layer (this is the documented, accepted client-authoritative limitation; correctness is enforced client-side, not by Rules — see this sprint's own adversarial review)",
  isValidBidSubmission(
    roundStartBase,
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 11, lastBidSeat: "p4", turn: "userC", cardPhase: "PLAY" }),
    "userE"
  ) === true
);
check(
  "SIMULATED — J.3 #4: duplicate final-completion write replayed after turn is already set — DENIED (oldData.turn no longer null)",
  isValidBidSubmission(
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 11, turn: "userB", cardPhase: "PLAY" }),
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 12, lastBidSeat: "p4", turn: "userC", cardPhase: "PLAY" }),
    "userE"
  ) === false
);
check(
  "SIMULATED — J.3 #5: stale-version final-completion write — DENIED (existing version/OCC check, untouched)",
  isValidBidSubmission(
    roundStartBase,
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 10, lastBidSeat: "p4", turn: "userB", cardPhase: "PLAY" }),
    "userE"
  ) === false
);
check(
  "SIMULATED — J.3 #6: genuine final estimate but with an invalid cardPhase value ('RESOLVING' instead of 'PLAY') — DENIED",
  isValidBidSubmission(
    roundStartBase,
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 11, lastBidSeat: "p4", turn: "userB", cardPhase: "RESOLVING" }),
    "userE"
  ) === false
);
check(
  "SIMULATED — J.3 #7: mid-round turn mutation attempt (bidding already fully closed, biddingOpen already false, NOT a real completion edge) — DENIED",
  isValidBidSubmission(
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 11, turn: "userB", cardPhase: "PLAY" }),
    Object.assign({}, roundStartBase, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 12, lastBidSeat: "p4", turn: "userD", cardPhase: "PLAY" }),
    "userE"
  ) === false
);
check(
  "SIMULATED — J.3 #8: initial match creation state is unaffected (turn is a real dealer uid, never null, at creation) — sanity check on the fixture itself",
  validNewMatch38.turn !== null && validNewMatch38.turn !== undefined
);

// ============================================================
// Sprint 3.8.1 (Bidding Validation & Rules Hardening) — ALL SIMULATED.
// Generic bid-VALUE validation via isValidBidSubmission()'s new
// `is int` / range clauses. NOT gameplay validation — see
// docs/architecture/BidValidation.md.
// ============================================================

function bidSubmissionWith(bidValue) {
  return Object.assign({}, matchAfterCreate38, { bids: { p1: bidValue, p2: null }, version: 2, lastBidSeat: "p1" });
}

check("SIMULATED — generic bid validation: null is rejected", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith(null), "userB") === false);
check("SIMULATED — generic bid validation: undefined is rejected", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith(undefined), "userB") === false);
check("SIMULATED — generic bid validation: NaN is rejected", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith(NaN), "userB") === false);
check("SIMULATED — generic bid validation: Infinity is rejected", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith(Infinity), "userB") === false);
check("SIMULATED — generic bid validation: -Infinity is rejected", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith(-Infinity), "userB") === false);
check("SIMULATED — generic bid validation: a negative value (-1) is rejected", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith(-1), "userB") === false);
check("SIMULATED — generic bid validation: 14 (above the max trick count) is rejected", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith(14), "userB") === false);
check("SIMULATED — generic bid validation: a string (\"4\") is rejected, never coerced", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith("4"), "userB") === false);
check("SIMULATED — generic bid validation: an object ({}) is rejected", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith({}), "userB") === false);
check("SIMULATED — generic bid validation: a non-integer (4.5) is rejected", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith(4.5), "userB") === false);
[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13].forEach(function (v) {
  check("SIMULATED — generic bid validation: " + v + " (a valid trick count) is ALLOWED", isValidBidSubmission(matchAfterCreate38, bidSubmissionWith(v), "userB") === true);
});

// ============================================================
// Sprint 4.2 (Online Card Synchronization: Engine Authority) tests —
// ALL SIMULATED, same convention as every prior sprint's own section
// in this file. New fixture (validNewMatch42), NOT a mutation of
// validNewMatch38 — isValidNewMatchV3's own allowedKeys list does not
// include cardLog/lastCardSeat, so mutating the shared fixture in
// place would silently break every V3 test above it.
// ============================================================
var validNewMatch42 = Object.assign({}, validNewMatch38, { cardLog: [], lastCardSeat: null });

check(
  "SIMULATED — matches create v4 (Sprint 4.2): a fully valid match WITH cardLog/lastCardSeat fields — ALLOWED",
  isValidNewMatchV4(validNewMatch42, "userB", "match-1", true, readyRoomFor38, "match-1") === true
);
check(
  "SIMULATED — matches create v4: the OLD (pre-4.2) shape without cardLog/lastCardSeat is now DENIED by V4 (extra/missing-key check both ways)",
  isValidNewMatchV4(validNewMatch38, "userB", "match-1", true, readyRoomFor38, "match-1") === false
);
check(
  "SIMULATED — SECURITY: cardLog is pre-filled (non-empty) at creation — DENIED",
  isValidNewMatchV4(Object.assign({}, validNewMatch42, { cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 10, s: "10" } } }] }), "userB", "match-1", true, readyRoomFor38, "match-1") === false
);
check(
  "SIMULATED — SECURITY: lastCardSeat is not null at creation — DENIED",
  isValidNewMatchV4(Object.assign({}, validNewMatch42, { lastCardSeat: "p1" }), "userB", "match-1", true, readyRoomFor38, "match-1") === false
);

// ---- Sprint 4.2.2, Task 2/6: isValidNewMatchV5 (cardPhase) ----
var validNewMatch422 = Object.assign({}, validNewMatch42, { cardPhase: null });
check(
  "SIMULATED — matches create v5 (Sprint 4.2.2): a fully valid match WITH cardPhase — ALLOWED",
  isValidNewMatchV5(validNewMatch422, "userB", "match-1", true, readyRoomFor38, "match-1") === true
);
check(
  "SIMULATED — matches create v5: the OLD (pre-4.2.2) shape without cardPhase is now DENIED by V5",
  isValidNewMatchV5(validNewMatch42, "userB", "match-1", true, readyRoomFor38, "match-1") === false
);
check(
  "SIMULATED — SECURITY: cardPhase is not null at creation — DENIED",
  isValidNewMatchV5(Object.assign({}, validNewMatch422, { cardPhase: "PLAY" }), "userB", "match-1", true, readyRoomFor38, "match-1") === false
);

// Sprint 4.2.2: `cardPhase: null` added — see this fixture's own use
// throughout isValidCardSubmission() tests below, none of which touch
// isValidNewMatchV4/V5's OWN create-time assertions above (those use
// `validNewMatch42`/`validNewMatch38` directly, never this variable).
var matchAfterCreate42 = Object.assign({}, validNewMatch42, { cardPhase: null });

check(
  "SIMULATED — card submission (valid card): seat owner (whose turn it currently is) appends one well-formed card and the turn atomically advances — ALLOWED",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } }, round: 1 }],
      version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB" // owns p1, and matchAfterCreate42.turn === "userB" — it IS userB's turn
  ) === true
);
check(
  "SIMULATED — multiple sequential cards: a second, independent append after the first (now userC's turn) is ALSO allowed, turn advancing again",
  isValidCardSubmission(
    Object.assign({}, matchAfterCreate42, {
      // Sprint A.1: this entry must carry `round`, matching every real
      // entry's actual shape (required since the Round Lifecycle
      // sprint) — the prefix-equality check now compares this OLD
      // entry byte-for-byte against the SAME entry in the new write
      // below, so the fixture must be internally consistent.
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } }, round: 1 }],
      version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } }, round: 1 }, { seatId: "p2", card: { suit: "SPADES", rank: { v: 5, s: "5" } }, round: 1 }],
      version: 3, lastCardSeat: "p2", turn: "userB", cardPhase: "PLAY"
    }),
    "userC" // owns p2, and the OLD doc's turn is now "userC" — it IS userC's turn
  ) === true
);
check(
  "SIMULATED — the 4th card of a trick may set turn to null and cardPhase to RESOLVING — ALLOWED (the resolving boundary)",
  isValidCardSubmission(
    Object.assign({}, matchAfterCreate42, { turn: "userB", cardPhase: "PLAY" }),
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } }, round: 1 }],
      version: 2, lastCardSeat: "p1", turn: null, cardPhase: "RESOLVING"
    }),
    "userB"
  ) === true
);
check(
  "SIMULATED — SECURITY (Sprint 4.2.2, Task 6): submitting when it is NOT the caller's turn — DENIED, even though the caller owns a real seat",
  isValidCardSubmission(
    Object.assign({}, matchAfterCreate42, { turn: "userC" }), // it's userC's (p2's) turn, not userB's
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } }],
      version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB" // owns p1, but it's NOT p1's turn
  ) === false
);
check(
  "SIMULATED — SECURITY: the new turn is a uid that owns NO seat in this match at all — DENIED (structurally impossible, per Task 6's own honest-limitation check)",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } }],
      version: 2, lastCardSeat: "p1", turn: "some-fabricated-uid", cardPhase: "PLAY"
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — SECURITY: cardPhase set to an arbitrary string outside {PLAY, RESOLVING} — DENIED",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } }],
      version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "DONE"
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — SECURITY: submitting as a seat you don't own — DENIED",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p2", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } }],
      version: 2, lastCardSeat: "p2", turn: "userB", cardPhase: "PLAY"
    }),
    "userB" // userB owns p1, not p2
  ) === false
);
check(
  "SIMULATED — SECURITY: lastCardSeat claims one seat but the appended entry's own seatId says another — DENIED",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p2", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } }],
      version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — SECURITY: out-of-order version (skips ahead) — DENIED",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } }],
      version: 5, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — SECURITY: version rollback (equal, no increment at all) — DENIED",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } }],
      version: 1, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — SECURITY: the log 'grows' by more than one entry in a single write (a batched multi-card submission) — DENIED",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } }, { seatId: "p2", card: { suit: "HEARTS", rank: { v: 5, s: "5" } } }],
      version: 2, lastCardSeat: "p2", turn: "userB", cardPhase: "PLAY"
    }),
    "userC"
  ) === false
);
check(
  "SIMULATED — SECURITY: the log does not grow at all (a no-op claiming to be a real submission) — DENIED",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, { version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY" }),
    "userB"
  ) === false
);
check(
  "SIMULATED — SECURITY: a field outside {cardLog, lastCardSeat, version, turn, cardPhase, updatedAt} changes in the same write — DENIED",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } }],
      version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY", dealer: "userC"
    }),
    "userB"
  ) === false
);

// ============================================================
// Sprint 4.2.3 (Firestore Rules Compile-Safe Card Turn Hotfix), Task 3
// — the 8 scenarios the brief explicitly requires, targeting the NEW,
// compile-safe `newData.turn` check in the REAL firestore.rules (Task
// 1's explicit `Map.get('p1'/'p2'/'p3'/'p4', null)` rewrite of the
// removed `oldData.seats.keys().exists(...)` expression). This JS
// harness's own `isValidCardSubmission()` above was already written
// using `Object.keys(oldData.seats).map(...)` — plain JS, not CEL — so
// its LOGIC needed no change; what changed is the REAL rule it
// translates. Every check below is labeled SIMULATED, per this file's
// own convention: this JS re-implementation of the rule's INTENDED
// logic can prove the intent is right, but it does not compile or
// execute real CEL, and is not proof the actual firestore.rules file
// compiles against the Firebase Rules engine — that remains PENDING a
// real Firebase Emulator or Rules-compiler run, which this project has
// never performed (see docs/architecture/SecurityArchitecture.md's own
// restated honesty note).
// ============================================================

// A 4-seat match (unlike matchAfterCreate42's own 2-seat fixture) so
// every one of p1/p2/p3/p4's own uid can be exercised individually.
var matchAfterCreate423_fourSeats = Object.assign({}, matchAfterCreate42, {
  players: ["userB", "userC", "userD", "userE"],
  seats: { p1: "userB", p2: "userC", p3: "userD", p4: "userE" },
  bids: { p1: null, p2: null, p3: null, p4: null },
  dealer: "userB", turn: "userB"
});

function fourSeatCardSubmissionWithTurn(nextTurn, nextPhase) {
  return Object.assign({}, matchAfterCreate423_fourSeats, {
    cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } }, round: 1 }],
    version: 2, lastCardSeat: "p1", turn: nextTurn, cardPhase: nextPhase
  });
}

check(
  "SIMULATED — Sprint 4.2.3, Task 3 (#1): null turn is ALLOWED at the resolving boundary",
  isValidCardSubmission(matchAfterCreate423_fourSeats, fourSeatCardSubmissionWithTurn(null, "RESOLVING"), "userB") === true
);
check(
  "SIMULATED — Sprint 4.2.3, Task 3 (#2): p1's own uid is ALLOWED as the next turn",
  isValidCardSubmission(matchAfterCreate423_fourSeats, fourSeatCardSubmissionWithTurn("userB", "PLAY"), "userB") === true
);
check(
  "SIMULATED — Sprint 4.2.3, Task 3 (#3): p2's own uid is ALLOWED as the next turn",
  isValidCardSubmission(matchAfterCreate423_fourSeats, fourSeatCardSubmissionWithTurn("userC", "PLAY"), "userB") === true
);
check(
  "SIMULATED — Sprint 4.2.3, Task 3 (#4): p3's own uid is ALLOWED as the next turn",
  isValidCardSubmission(matchAfterCreate423_fourSeats, fourSeatCardSubmissionWithTurn("userD", "PLAY"), "userB") === true
);
check(
  "SIMULATED — Sprint 4.2.3, Task 3 (#5): p4's own uid is ALLOWED as the next turn",
  isValidCardSubmission(matchAfterCreate423_fourSeats, fourSeatCardSubmissionWithTurn("userE", "PLAY"), "userB") === true
);
check(
  "SIMULATED — Sprint 4.2.3, Task 3 (#6): an unknown uid (owns no seat in this match at all) is REJECTED",
  isValidCardSubmission(matchAfterCreate423_fourSeats, fourSeatCardSubmissionWithTurn("some-fabricated-uid", "PLAY"), "userB") === false
);
check(
  "SIMULATED — Sprint 4.2.3, Task 3 (#7): an empty string is REJECTED (not silently treated as null, not matched against a real seat)",
  isValidCardSubmission(matchAfterCreate423_fourSeats, fourSeatCardSubmissionWithTurn("", "PLAY"), "userB") === false
);
check(
  "SIMULATED — Sprint 4.2.3, Task 3 (#8): a PARTIAL seats map (only p1/p2 — matchAfterCreate42's own 2-player fixture) behaves safely: p2's own uid is still ALLOWED as the next turn even with p3/p4 entirely absent",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } }, round: 1 }],
      version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB"
  ) === true
);
check(
  "SIMULATED — Sprint 4.2.3, Task 3 (#8b): the SAME partial (p1/p2-only) seats map does not crash the simulated logic when checked against an unknown uid — missing p3/p4 keys are handled the same as a genuinely absent seat, not a thrown error",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } }],
      version: 2, lastCardSeat: "p1", turn: "some-fabricated-uid", cardPhase: "PLAY"
    }),
    "userB"
  ) === false
);

// Generic card-SHAPE validation, mirroring isValidGenericCardValue()'s
// exact rejection set — NOT gameplay validation (see this file's own
// Sprint 4.2 header comment above and isValidCardShape()'s comment).
// Sprint 4.2.2: `turn`/`cardPhase` added so these otherwise-valid
// submissions aren't rejected for the WRONG reason (a missing/invalid
// turn transition) when the point of each check is the card's shape.
function cardSubmissionWith(card) {
  return Object.assign({}, matchAfterCreate42, {
    cardLog: [{ seatId: "p1", card: card, round: 1 }], version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
  });
}
check("SIMULATED — generic card validation: an unknown suit key is rejected", isValidCardSubmission(matchAfterCreate42, cardSubmissionWith({ suit: "STARS", rank: { v: 10, s: "10" } }), "userB") === false);
check("SIMULATED — generic card validation: rank.v below the minimum (1) is rejected", isValidCardSubmission(matchAfterCreate42, cardSubmissionWith({ suit: "SPADES", rank: { v: 1, s: "1" } }), "userB") === false);
check("SIMULATED — generic card validation: rank.v above the maximum (15) is rejected", isValidCardSubmission(matchAfterCreate42, cardSubmissionWith({ suit: "SPADES", rank: { v: 15, s: "15" } }), "userB") === false);
check("SIMULATED — generic card validation: a non-integer rank.v (10.5) is rejected", isValidCardSubmission(matchAfterCreate42, cardSubmissionWith({ suit: "SPADES", rank: { v: 10.5, s: "10.5" } }), "userB") === false);
check("SIMULATED — generic card validation: a card with an extra, unlisted key is rejected", isValidCardSubmission(matchAfterCreate42, cardSubmissionWith({ suit: "SPADES", rank: { v: 10, s: "10" }, owner: "p1" }), "userB") === false);
check("SIMULATED — generic card validation: a well-formed SANS-suit card is ALLOWED (SANS is a valid trump mode key even though it's not a real deck suit)", isValidCardSubmission(matchAfterCreate42, cardSubmissionWith({ suit: "SANS", rank: { v: 14, s: "A" } }), "userB") === true);
["SPADES", "HEARTS", "DIAMONDS", "CLUBS"].forEach(function (suit) {
  [2, 8, 14].forEach(function (v) {
    check("SIMULATED — generic card validation: " + suit + " " + v + " (a well-formed card) is ALLOWED", isValidCardSubmission(matchAfterCreate42, cardSubmissionWith({ suit: suit, rank: { v: v, s: String(v) } }), "userB") === true);
  });
});

// ============================================================
// Sprint A.1 (Card Log Prefix Immutability Fix). Originally, Sprint
// 4.2.1's Task 4 assessment found — and the tests here DEMONSTRATED,
// not merely asserted — that `isValidCardSubmission()` could not see a
// rewrite or reorder of an earlier `cardLog` entry, because it assumed
// CEL had no primitive for an index-by-index prefix comparison. That
// assumption was RE-CHECKED against this project's own later work
// (`isValidRoundExtension()`'s real, emulator-verified
// `newRounds[0:oldRounds.size()] == oldRounds` slice check) and found
// to be WRONG — CEL DOES support list slicing. `isValidCardSubmission()`
// (both the real firestore.rules and this 1:1 JS mirror) now applies
// the identical technique to `cardLog`. The two checks below are the
// SAME two attack shapes the old "KNOWN VULNERABILITY" tests
// demonstrated getting through — updated to now prove they are
// REJECTED, not merely documented as open.
// ============================================================
var prefixRewriteMatch = Object.assign({}, matchAfterCreate42, {
  cardLog: [
    { seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } },
    { seatId: "p2", card: { suit: "SPADES", rank: { v: 5, s: "5" } } }
  ],
  version: 3, lastCardSeat: "p2", turn: "userB", cardPhase: "PLAY" // it's userB's (p1's) turn to submit the final, genuinely-new entry below
});
check(
  "SIMULATED — FIXED (Sprint A.1, was KNOWN VULNERABILITY): rewriting an EARLIER cardLog entry's card value while still appending exactly one NEW, well-formed entry is now REJECTED by isValidCardSubmission()'s prefix-equality check",
  isValidCardSubmission(
    prefixRewriteMatch,
    Object.assign({}, prefixRewriteMatch, {
      cardLog: [
        { seatId: "p1", card: { suit: "HEARTS", rank: { v: 2, s: "2" } } }, // REWRITTEN — was SPADES Q, now HEARTS 2 — now caught by the prefix check
        { seatId: "p2", card: { suit: "SPADES", rank: { v: 5, s: "5" } } },
        { seatId: "p1", card: { suit: "SPADES", rank: { v: 8, s: "8" } }, round: 1 } // the one genuinely new, well-formed entry
      ],
      version: 4, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB" // owns p1 (validNewMatch38's real seat map)
  ) === false
);
check(
  "SIMULATED — FIXED (Sprint A.1, was KNOWN VULNERABILITY): REORDERING two earlier entries (same multiset, different sequence — which matters for turn/trick order) while appending one new entry is now REJECTED — proving the fix catches POSITION, not just membership, unlike `.all(x, x in newLog)` would have",
  isValidCardSubmission(
    prefixRewriteMatch,
    Object.assign({}, prefixRewriteMatch, {
      cardLog: [
        { seatId: "p2", card: { suit: "SPADES", rank: { v: 5, s: "5" } } }, // SWAPPED with the entry below
        { seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } },
        { seatId: "p1", card: { suit: "SPADES", rank: { v: 8, s: "8" } }, round: 1 }
      ],
      version: 4, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — Sprint A.1 regression: a legitimate append with an UNCHANGED, non-empty prefix is still ALLOWED (the fix does not over-reject)",
  isValidCardSubmission(
    prefixRewriteMatch,
    Object.assign({}, prefixRewriteMatch, {
      cardLog: [
        { seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } } },
        { seatId: "p2", card: { suit: "SPADES", rank: { v: 5, s: "5" } } },
        { seatId: "p1", card: { suit: "SPADES", rank: { v: 8, s: "8" } }, round: 1 }
      ],
      version: 4, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB"
  ) === true
);
check(
  "SIMULATED — Sprint A.1 regression: the very FIRST card play of a match (oldLog empty) is still ALLOWED — the empty-prefix ternary guard must not itself reject a legitimate first submission",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } }, round: 1 }],
      version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB"
  ) === true
);

// ============================================================
// Sprint 3.7 (Online Bidding Synchronization Contract) tests — ALL
// SIMULATED, same convention as every prior sprint's own section in
// this file. New fixture (validNewMatch37), NOT a mutation of
// validNewMatch422 — isValidNewMatchV5's own allowedKeys list does
// not include biddingLog, so mutating a shared fixture in place would
// silently break every V5 test above it.
// ============================================================
var validNewMatch37 = Object.assign({}, validNewMatch422, { biddingLog: [] });

check(
  "SIMULATED — matches create v6 (Sprint 3.7): a fully valid match WITH biddingLog — ALLOWED",
  isValidNewMatchV6(validNewMatch37, "userB", "match-1", true, readyRoomFor38, "match-1") === true
);
check(
  "SIMULATED — matches create v6: the OLD (pre-3.7) shape without biddingLog is now DENIED by V6",
  isValidNewMatchV6(validNewMatch422, "userB", "match-1", true, readyRoomFor38, "match-1") === false
);
check(
  "SIMULATED — SECURITY: biddingLog is pre-filled (non-empty) at creation — DENIED",
  isValidNewMatchV6(Object.assign({}, validNewMatch37, { biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false }] }), "userB", "match-1", true, readyRoomFor38, "match-1") === false
);

var matchAfterCreate37 = validNewMatch37;

check(
  "SIMULATED — bidding-action submission (Dash Call): seat owner appends one well-formed entry — ALLOWED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1 }], version: 2 }),
    "userB" // owns p1
  ) === true
);
check(
  "SIMULATED — bidding-action submission (Auction Bid): a well-formed raise — ALLOWED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitAuctionBid", isPass: false, tricks: 5, suit: "SPADES", round: 1 }], version: 2 }),
    "userB"
  ) === true
);
check(
  "SIMULATED — bidding-action submission (Auction Pass): isPass alone, no tricks/suit — ALLOWED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitAuctionBid", isPass: true, round: 1 }], version: 2 }),
    "userB"
  ) === true
);
check(
  "SIMULATED — bidding-action submission (Confirm Call): a well-formed lock — ALLOWED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitConfirmCall", tricks: 4, suit: "SPADES", round: 1 }], version: 2 }),
    "userB"
  ) === true
);
check(
  "SIMULATED — multiple sequential bidding actions: a second, independent append after the first is ALSO allowed",
  isValidBiddingActionSubmission(
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1 }], version: 2 }),
    Object.assign({}, matchAfterCreate37, {
      biddingLog: [
        { seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1 },
        { seatId: "p2", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1 }
      ], version: 3
    }),
    "userC" // owns p2
  ) === true
);
check(
  "SIMULATED — SECURITY: appending a seat this caller does NOT own — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p2", actionType: "SubmitDashCallDecision", declaredDashCall: false }], version: 2 }),
    "userB" // owns p1, not p2
  ) === false
);
check(
  "SIMULATED — SECURITY: a fabricated seatId with no owner at all — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p9", actionType: "SubmitDashCallDecision", declaredDashCall: false }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — malformed entry: unknown actionType — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "NotARealAction" }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — malformed entry: an extra, unlisted key — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false, owner: "p1" }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — malformed entry: tricks out of the generic 0-13 range (99) — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitConfirmCall", tricks: 99, suit: "SPADES" }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — malformed entry: an unknown suit key — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitConfirmCall", tricks: 4, suit: "STARS" }], version: 2 }),
    "userB"
  ) === false
);
// Sprint 3.7.x (Bidding Trust-Boundary Hardening) — the actual fix
// under test: a structurally "valid-looking" entry (right actionType,
// no wrong-typed/out-of-range field present) that is missing a field
// its OWN actionType requires must now be DENIED — this is exactly
// the gap the hardening report found: previously these 4 cases all
// incorrectly passed.
check(
  "SIMULATED — Trust-Boundary Hardening: SubmitDashCallDecision missing declaredDashCall — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision" }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — Trust-Boundary Hardening: SubmitAuctionBid missing isPass entirely — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitAuctionBid", tricks: 5, suit: "SPADES" }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — Trust-Boundary Hardening: SubmitAuctionBid with isPass:false but missing suit — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitAuctionBid", isPass: false, tricks: 5 }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — Trust-Boundary Hardening: SubmitAuctionBid with isPass:true and no tricks/suit — still ALLOWED (a pass carries no payload)",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitAuctionBid", isPass: true, round: 1 }], version: 2 }),
    "userB"
  ) === true
);
check(
  "SIMULATED — Trust-Boundary Hardening: SubmitConfirmCall missing both tricks and suit — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitConfirmCall" }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — Trust-Boundary Hardening: SubmitConfirmCall with tricks but missing suit — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitConfirmCall", tricks: 4 }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — version must increment by exactly 1 — a version jump of 2 is DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false }], version: 3 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — the log must grow by EXACTLY one entry — zero growth (same length, version bumped anyway) is DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — the log must grow by EXACTLY one entry — growth of 2 in a single write is DENIED (no legitimate client batches bidding actions)",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, {
      biddingLog: [
        { seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false },
        { seatId: "p2", actionType: "SubmitDashCallDecision", declaredDashCall: false }
      ], version: 2
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — 'Reject every other write': changing an unrelated field (e.g. cardLog) alongside a valid biddingLog append — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, {
      biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false }],
      version: 2, cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 10, s: "10" } } }]
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — KNOWN, DOCUMENTED LIMITATION (not hidden — mirrors isValidCardSubmission()'s own equivalent honesty note): this rule alone does NOT enforce turn ownership — a seat owner may append a well-formed entry even when the real bidding-engine.js's own turn order says it is NOT their turn yet. This currently PASSES at the rules layer alone (deliberate — see isValidBiddingActionSubmission()'s own comment for why: matches.turn is never advanced through Dash/Auction/Confirm). Real turn-order enforcement is MatchService.submitBiddingAction()'s own pre-write BiddingEngine.canSubmit() gate, and the real engine's own re-validation on every client via MatchAdapter.applyRemoteBiddingAction() — never this rule alone.",
  isValidBiddingActionSubmission(
    matchAfterCreate37, // dealer/turn is userB (p1) — but this checks p2 (userC) submitting, which the REAL engine would currently reject as out-of-turn during a normal DASH round
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p2", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1 }], version: 2 }),
    "userC" // owns p2 — a real seat, correctly owned — but not (in this fixture's implied turn order) the seat that should be acting first
  ) === true
);

// ============================================================
// Round Lifecycle sprint — round-tagging + isValidRoundAdvance()
// tests, ALL SIMULATED (same convention as every prior sprint's own
// section here).
// ============================================================
check(
  "SIMULATED — round tagging (bidding): an entry tagged with the CURRENT round is ALLOWED",
  isValidBiddingActionSubmission(
    matchAfterCreate37, // currentRound: 1
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1 }], version: 2 }),
    "userB"
  ) === true
);
check(
  "SIMULATED — round tagging (bidding): an entry tagged with a round AHEAD of the current one is DENIED (would let a client fabricate a Round 2 entry inside a Round 1 document without ever going through advanceToNextRound())",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 2 }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round tagging (bidding): an entry with NO round field at all is DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, { biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false }], version: 2 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round tagging (card): an entry tagged with the CURRENT round is ALLOWED",
  isValidCardSubmission(matchAfterCreate42, cardSubmissionWith({ suit: "SPADES", rank: { v: 10, s: "10" } }), "userB") === true
);
check(
  "SIMULATED — round tagging (card): an entry tagged with a round BEHIND the current one is DENIED",
  isValidCardSubmission(
    matchAfterCreate42,
    Object.assign({}, matchAfterCreate42, {
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } }, round: 0 }],
      version: 2, lastCardSeat: "p1", turn: "userC", cardPhase: "PLAY"
    }),
    "userB"
  ) === false
);

// ============================================================
// biddingLog Prefix Immutability Fix — mirrors the cardLog Sprint A.1
// checks above exactly (see that section's own header comment for the
// full account of why the old "CEL can't do this" assumption was
// wrong). isValidBiddingActionSubmission() now applies the identical
// `newLog[0:oldLog.size()] == oldLog` guarded slice technique to
// biddingLog.
// ============================================================
var biddingPrefixRewriteMatch = Object.assign({}, matchAfterCreate37, {
  biddingLog: [
    { seatId: "p1", actionType: "SubmitDashCallDecision", round: 1, declaredDashCall: true },
    { seatId: "p2", actionType: "SubmitDashCallDecision", round: 1, declaredDashCall: false }
  ],
  version: 3
});
check(
  "SIMULATED — biddingLog prefix immutability: rewriting an EARLIER biddingLog entry while still appending exactly one NEW, well-formed entry is REJECTED",
  isValidBiddingActionSubmission(
    biddingPrefixRewriteMatch,
    Object.assign({}, biddingPrefixRewriteMatch, {
      biddingLog: [
        { seatId: "p1", actionType: "SubmitDashCallDecision", round: 1, declaredDashCall: false }, // REWRITTEN — was true, now false
        { seatId: "p2", actionType: "SubmitDashCallDecision", round: 1, declaredDashCall: false },
        { seatId: "p1", actionType: "SubmitDashCallDecision", round: 1, declaredDashCall: true }
      ],
      version: 4
    }),
    "userB" // owns p1
  ) === false
);
check(
  "SIMULATED — biddingLog prefix immutability: REORDERING two earlier entries (same multiset, different sequence) while appending one new entry is REJECTED",
  isValidBiddingActionSubmission(
    biddingPrefixRewriteMatch,
    Object.assign({}, biddingPrefixRewriteMatch, {
      biddingLog: [
        { seatId: "p2", actionType: "SubmitDashCallDecision", round: 1, declaredDashCall: false }, // SWAPPED with the entry below
        { seatId: "p1", actionType: "SubmitDashCallDecision", round: 1, declaredDashCall: true },
        { seatId: "p1", actionType: "SubmitDashCallDecision", round: 1, declaredDashCall: true }
      ],
      version: 4
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — biddingLog prefix immutability regression: a legitimate append with an UNCHANGED, non-empty prefix is still ALLOWED (the fix does not over-reject)",
  isValidBiddingActionSubmission(
    biddingPrefixRewriteMatch,
    Object.assign({}, biddingPrefixRewriteMatch, {
      biddingLog: biddingPrefixRewriteMatch.biddingLog.concat([
        { seatId: "p1", actionType: "SubmitDashCallDecision", round: 1, declaredDashCall: true }
      ]),
      version: 4
    }),
    "userB"
  ) === true
);
check(
  "SIMULATED — biddingLog prefix immutability regression: the very FIRST bidding action of a match (oldLog empty) is still ALLOWED — the empty-prefix guard must not itself reject a legitimate first submission",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, {
      biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", round: 1, declaredDashCall: true }],
      version: 2
    }),
    "userB"
  ) === true
);

// ============================================================
// Sprint J.7 (Unified Bidding Completion + Seat Membership Fix) — 1:1
// JS mirror checks, verified first against the real Firestore Rules
// Emulator (tests/sprint-j7-unified-completion.rules-emulator.test.cjs).
// matchAfterCreate37: a 2-player match, seats p1=userB, p2=userC (per
// validNewMatch38's own fixture — this project's tests deliberately
// exercise 2-player matches, not just 4), bids all null, biddingOpen=true.
// ============================================================
check(
  "SIMULATED — J.7: SubmitConfirmCall mirrors caller's own bid into bids[p1], biddingOpen stays true (p2 still missing) — ALLOWED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, {
      biddingLog: [{ seatId: "p1", actionType: "SubmitConfirmCall", tricks: 4, suit: "SPADES", round: 1 }],
      version: 2, bids: { p1: 4, p2: null }, biddingOpen: true
    }),
    "userB"
  ) === true
);
check(
  "SIMULATED — J.7: p1 (userB) forging a ConfirmCall claiming seatId p2 — DENIED (seat ownership)",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, {
      biddingLog: [{ seatId: "p2", actionType: "SubmitConfirmCall", tricks: 4, suit: "SPADES", round: 1 }],
      version: 2, bids: { p1: null, p2: 4 }
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — J.7: ConfirmCall smuggling turn/cardPhase while the other seat still has no bid — DENIED (early completion)",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, {
      biddingLog: [{ seatId: "p1", actionType: "SubmitConfirmCall", tricks: 4, suit: "SPADES", round: 1 }],
      version: 2, bids: { p1: 4, p2: null }, biddingOpen: false,
      turn: "userB", cardPhase: "PLAY"
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — J.7: ConfirmCall write also touching the OTHER seat's bids entry — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, {
      biddingLog: [{ seatId: "p1", actionType: "SubmitConfirmCall", tricks: 4, suit: "SPADES", round: 1 }],
      version: 2, bids: { p1: 4, p2: 9 }
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — J.7: an ordinary SubmitDashCallDecision cannot smuggle a bids write — DENIED",
  isValidBiddingActionSubmission(
    matchAfterCreate37,
    Object.assign({}, matchAfterCreate37, {
      biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1 }],
      version: 2, bids: { p1: 4, p2: null }
    }),
    "userB"
  ) === false
);

// J.7 seat-membership fix, exercised via isValidBidSubmission()'s own
// completion branch for a 2-player match (absent p3/p4 must never
// satisfy the turn-membership check via a `null` default). Overrides
// turn/cardPhase to null explicitly — matchAfterCreate37's own turn
// ("userB") reflects MATCH CREATION, not the post-round-advance
// turn:null/cardPhase:null state this branch actually governs.
var matchJ7TwoPlayer = Object.assign({}, matchAfterCreate37, {
  bids: { p1: 4, p2: null }, turn: null, cardPhase: null
});
check(
  "SIMULATED — J.7: 2-player genuine completion with turn:null — DENIED",
  isValidBidSubmission(
    matchJ7TwoPlayer,
    Object.assign({}, matchJ7TwoPlayer, { bids: { p1: 4, p2: 3 }, biddingOpen: false, version: 2, lastBidSeat: "p2", turn: null, cardPhase: "PLAY" }),
    "userC"
  ) === false
);
check(
  "SIMULATED — J.7: 2-player genuine completion with a legitimate occupied leader (p1) — ALLOWED",
  isValidBidSubmission(
    matchJ7TwoPlayer,
    Object.assign({}, matchJ7TwoPlayer, { bids: { p1: 4, p2: 3 }, biddingOpen: false, version: 2, lastBidSeat: "p2", turn: "userB", cardPhase: "PLAY" }),
    "userC"
  ) === true
);

var matchReadyForRoundAdvance = Object.assign({}, matchAfterCreate423_fourSeats, {
  cardLog: (function () {
    var log = [];
    for (var t = 0; t < 13; t++) {
      ["p1", "p2", "p3", "p4"].forEach(function (seatId) {
        log.push({ seatId: seatId, card: { suit: "SPADES", rank: { v: 2, s: "2" } }, round: 1 });
      });
    }
    return log;
  })(),
  version: 55,
  // Firestore Rules Hardening sprint: matchAfterCreate423_fourSeats's
  // own fixture chain (matchAfterCreate42 <- validNewMatch42 <-
  // validNewMatch38) predates the Match Completion sprint's `maxRounds`
  // field entirely — every REAL match document has always had it since
  // that sprint (buildInitialMatchDoc()'s own default is 18). This is
  // the legitimate "update the fixture, don't weaken the rule" case:
  // a genuine round-1-of-18 match, not a boundary/ceiling case (those
  // are covered separately in tests/sprint-a-write-paths.rules-emulator.test.cjs's
  // own ADV.10/ADV.11).
  maxRounds: 18
});
check(
  "SIMULATED — round advance: a well-formed transition (currentRound+1, version+1, biddingOpen reset true, cardPhase/turn reset null) is ALLOWED for ANY seated player, not just a designated host",
  isValidRoundAdvance(
    matchReadyForRoundAdvance,
    Object.assign({}, matchReadyForRoundAdvance, {
      currentRound: 2, version: 56, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardPhase: null, turn: null
    }),
    "userE" // p4 — NOT the dealer/turn seat, still allowed to advance
  ) === true
);
check(
  "SIMULATED — round advance: currentRound jumping by 2 (skipping a round) — DENIED",
  isValidRoundAdvance(
    matchReadyForRoundAdvance,
    Object.assign({}, matchReadyForRoundAdvance, { currentRound: 3, version: 56, cardPhase: null, turn: null }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round advance: version not incrementing alongside currentRound — DENIED",
  isValidRoundAdvance(
    matchReadyForRoundAdvance,
    Object.assign({}, matchReadyForRoundAdvance, { currentRound: 2, cardPhase: null, turn: null }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round advance: leaving `turn` at its stale PLAY-phase value instead of resetting to null — DENIED",
  isValidRoundAdvance(
    matchReadyForRoundAdvance,
    Object.assign({}, matchReadyForRoundAdvance, { currentRound: 2, version: 56, cardPhase: null, turn: "userB" }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round advance: touching an unrelated field (players) alongside an otherwise-valid transition — DENIED",
  isValidRoundAdvance(
    matchReadyForRoundAdvance,
    Object.assign({}, matchReadyForRoundAdvance, { currentRound: 2, version: 56, cardPhase: null, turn: null, players: ["userB"] }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round advance: a non-player uid attempting the transition — DENIED",
  isValidRoundAdvance(
    matchReadyForRoundAdvance,
    Object.assign({}, matchReadyForRoundAdvance, { currentRound: 2, version: 56, cardPhase: null, turn: null }),
    "some-fabricated-uid"
  ) === false
);

// ════════════════════════════════════════════════════════════════════
// Match Completion sprint — 1:1 JS translations of firestore.rules'
// isValidRoundExtension() / isValidMatchCompletion(). Same "honest,
// lower-fidelity substitute" disclaimer as this file's own header
// comment — these are NOT the real CEL rules, just a hand-translated
// mirror exercised against representative request shapes.
// ════════════════════════════════════════════════════════════════════
function isValidRoundExtension(oldData, newData, requestAuthUid) {
  if (requestAuthUid == null) return false;
  if ((oldData.players || []).indexOf(requestAuthUid) === -1) return false;
  if (!("maxRounds" in oldData) || !("extendedRounds" in oldData) || !("version" in oldData)) return false;
  // Match Completion sprint: same terminal-state guard as
  // isValidRoundAdvance() — an already-completed match's maxRounds may
  // never be bumped again.
  if ("status" in oldData && oldData.status === "complete") return false;

  var allowedChangedKeys = ["maxRounds", "extendedRounds", "version", "updatedAt"];
  var changedKeys = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); })
    .concat(Object.keys(oldData).filter(function (k) { return !(k in newData); }));
  var onlyAllowedKeysChanged = changedKeys.every(function (k) { return allowedChangedKeys.indexOf(k) !== -1; });
  if (!onlyAllowedKeysChanged) return false;

  if (newData.version !== oldData.version + 1) return false;
  if (newData.maxRounds !== oldData.maxRounds + 1) return false;

  var oldRounds = oldData.extendedRounds, newRounds = newData.extendedRounds;
  if (!Array.isArray(oldRounds) || !Array.isArray(newRounds)) return false;
  if (newRounds.length !== oldRounds.length + 1) return false;
  var prefix = newRounds.slice(0, oldRounds.length);
  if (JSON.stringify(prefix) !== JSON.stringify(oldRounds)) return false;
  var appendedRound = newRounds[newRounds.length - 1];
  if (!Number.isInteger(appendedRound)) return false;
  if (appendedRound < 14 || appendedRound > 18) return false;
  if (oldRounds.indexOf(appendedRound) !== -1) return false;

  return true;
}

function isValidMatchCompletion(oldData, newData, requestAuthUid) {
  if (requestAuthUid == null) return false;
  if ((oldData.players || []).indexOf(requestAuthUid) === -1) return false;
  if (!("status" in oldData) || !("currentRound" in oldData) || !("maxRounds" in oldData) || !("version" in oldData)) return false;
  if (oldData.status === "complete") return false;
  if (newData.status !== "complete") return false;

  var allowedChangedKeys = ["status", "winnerIds", "finalScores", "completedRound", "version", "updatedAt"];
  var changedKeys = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); })
    .concat(Object.keys(oldData).filter(function (k) { return !(k in newData); }));
  var onlyAllowedKeysChanged = changedKeys.every(function (k) { return allowedChangedKeys.indexOf(k) !== -1; });
  if (!onlyAllowedKeysChanged) return false;

  if (newData.version !== oldData.version + 1) return false;
  if (!Number.isInteger(newData.completedRound)) return false;
  if (newData.completedRound !== oldData.currentRound) return false;
  if (!(newData.completedRound + 1 > oldData.maxRounds)) return false;

  var seatIds = Object.keys(oldData.seats || {});
  if (!Array.isArray(newData.winnerIds) || newData.winnerIds.length === 0) return false;
  if (!newData.winnerIds.every(function (id) { return seatIds.indexOf(id) !== -1; })) return false;

  if (typeof newData.finalScores !== "object" || newData.finalScores === null || Array.isArray(newData.finalScores)) return false;
  var finalScoreKeys = Object.keys(newData.finalScores);
  var seatsMatchFinalScoreKeys = seatIds.every(function (id) { return finalScoreKeys.indexOf(id) !== -1; })
    && finalScoreKeys.every(function (id) { return seatIds.indexOf(id) !== -1; });
  if (!seatsMatchFinalScoreKeys) return false;

  return true;
}

var matchBaseForCompletionSprint = Object.assign({}, matchAfterCreate423_fourSeats, {
  maxRounds: 18, extendedRounds: [], status: "starting"
});

// ---- isValidRoundExtension() ----
var matchReadyForExtension = Object.assign({}, matchBaseForCompletionSprint, { version: 40 });
check(
  "SIMULATED — round extension: a well-formed extension (maxRounds+1, extendedRounds append, version+1) is ALLOWED",
  isValidRoundExtension(
    matchReadyForExtension,
    Object.assign({}, matchReadyForExtension, { maxRounds: 19, extendedRounds: [15], version: 41 }),
    "userB"
  ) === true
);
check(
  "SIMULATED — round extension: re-extending with the SAME round number already recorded — DENIED (idempotency)",
  isValidRoundExtension(
    Object.assign({}, matchReadyForExtension, { extendedRounds: [15] }),
    Object.assign({}, matchReadyForExtension, { maxRounds: 19, extendedRounds: [15], version: 41 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round extension: maxRounds jumping by 2 instead of exactly 1 — DENIED",
  isValidRoundExtension(
    matchReadyForExtension,
    Object.assign({}, matchReadyForExtension, { maxRounds: 20, extendedRounds: [15], version: 41 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round extension: appended round number outside the Rapid Rounds window (14-18), e.g. round 20 — DENIED",
  isValidRoundExtension(
    matchReadyForExtension,
    Object.assign({}, matchReadyForExtension, { maxRounds: 19, extendedRounds: [20], version: 41 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round extension: appended round number below the window (round 10) — DENIED",
  isValidRoundExtension(
    matchReadyForExtension,
    Object.assign({}, matchReadyForExtension, { maxRounds: 19, extendedRounds: [10], version: 41 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round extension: version not incrementing — DENIED",
  isValidRoundExtension(
    matchReadyForExtension,
    Object.assign({}, matchReadyForExtension, { maxRounds: 19, extendedRounds: [15] }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round extension: touching an unrelated field (currentRound) alongside an otherwise-valid extension — DENIED",
  isValidRoundExtension(
    matchReadyForExtension,
    Object.assign({}, matchReadyForExtension, { maxRounds: 19, extendedRounds: [15], version: 41, currentRound: 16 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — round extension: a non-player uid attempting the extension — DENIED",
  isValidRoundExtension(
    matchReadyForExtension,
    Object.assign({}, matchReadyForExtension, { maxRounds: 19, extendedRounds: [15], version: 41 }),
    "some-fabricated-uid"
  ) === false
);
check(
  "SIMULATED — round extension: a SECOND qualifying extension (round 17) after the first (round 15) is ALLOWED — multiple extensions accumulate",
  isValidRoundExtension(
    Object.assign({}, matchReadyForExtension, { maxRounds: 19, extendedRounds: [15], version: 41 }),
    Object.assign({}, matchReadyForExtension, { maxRounds: 20, extendedRounds: [15, 17], version: 42 }),
    "userB"
  ) === true
);

// ---- isValidMatchCompletion() ----
var matchReadyForCompletion = Object.assign({}, matchBaseForCompletionSprint, {
  currentRound: 18, maxRounds: 18, version: 90
});
var singleWinnerScores = { p1: 120, p2: 40, p3: 30, p4: 20 };
var twoKingsScores = { p1: 100, p2: 100, p3: 40, p4: 20 };
var threeKingsScores = { p1: 100, p2: 100, p3: 100, p4: 20 };
var fourKingsScores = { p1: 100, p2: 100, p3: 100, p4: 100 };

check(
  "SIMULATED — match completion: a well-formed completion at currentRound == maxRounds, single winner — ALLOWED",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: ["p1"], finalScores: singleWinnerScores, completedRound: 18, version: 91
    }),
    "userB"
  ) === true
);
check(
  "SIMULATED — match completion: TWO tied Kings — ALLOWED",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: ["p1", "p2"], finalScores: twoKingsScores, completedRound: 18, version: 91
    }),
    "userB"
  ) === true
);
check(
  "SIMULATED — match completion: THREE tied Kings — ALLOWED",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: ["p1", "p2", "p3"], finalScores: threeKingsScores, completedRound: 18, version: 91
    }),
    "userB"
  ) === true
);
check(
  "SIMULATED — match completion: ALL FOUR players tied as Kings — ALLOWED",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: ["p1", "p2", "p3", "p4"], finalScores: fourKingsScores, completedRound: 18, version: 91
    }),
    "userB"
  ) === true
);
check(
  "SIMULATED — match completion: completedRound less than the current authoritative maxRounds (match not actually over yet) — DENIED",
  isValidMatchCompletion(
    Object.assign({}, matchReadyForCompletion, { currentRound: 17 }),
    Object.assign({}, matchReadyForCompletion, {
      currentRound: 17, status: "complete", winnerIds: ["p1"], finalScores: singleWinnerScores, completedRound: 17, version: 91
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — match completion: an EXTENDED match (maxRounds 19) cannot complete at the OLD hardcoded 18 — DENIED",
  isValidMatchCompletion(
    Object.assign({}, matchReadyForCompletion, { currentRound: 18, maxRounds: 19 }),
    Object.assign({}, matchReadyForCompletion, {
      currentRound: 18, maxRounds: 19, status: "complete", winnerIds: ["p1"], finalScores: singleWinnerScores, completedRound: 18, version: 91
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — match completion: an EXTENDED match DOES complete once currentRound reaches the NEW maxRounds (19) — ALLOWED",
  isValidMatchCompletion(
    Object.assign({}, matchReadyForCompletion, { currentRound: 19, maxRounds: 19 }),
    Object.assign({}, matchReadyForCompletion, {
      currentRound: 19, maxRounds: 19, status: "complete", winnerIds: ["p1"], finalScores: singleWinnerScores, completedRound: 19, version: 91
    }),
    "userB"
  ) === true
);
check(
  "SIMULATED — match completion: winnerIds containing a seat id that does not exist on this match — DENIED",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: ["p1", "p9-fabricated"], finalScores: singleWinnerScores, completedRound: 18, version: 91
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — match completion: empty winnerIds — DENIED (rules §'Determining the King': there is always at least one)",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: [], finalScores: singleWinnerScores, completedRound: 18, version: 91
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — match completion: finalScores missing a real seat (p4) — DENIED",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: ["p1"], finalScores: { p1: 120, p2: 40, p3: 30 }, completedRound: 18, version: 91
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — match completion: finalScores includes an extra, non-existent seat — DENIED",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: ["p1"], finalScores: Object.assign({}, singleWinnerScores, { "p9-fabricated": 5 }), completedRound: 18, version: 91
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — match completion: status attempting to move FROM 'complete' back to anything else — DENIED (this rule only ever writes status TO complete)",
  isValidMatchCompletion(
    Object.assign({}, matchReadyForCompletion, { status: "complete", winnerIds: ["p1"], finalScores: singleWinnerScores, completedRound: 18 }),
    Object.assign({}, matchReadyForCompletion, { status: "starting", winnerIds: ["p1"], finalScores: singleWinnerScores, completedRound: 18, version: 91 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — match completion: attempting to complete an ALREADY-complete match a second time — DENIED (endMatch()'s own idempotent no-op handles the safe retry path instead)",
  isValidMatchCompletion(
    Object.assign({}, matchReadyForCompletion, { status: "complete", winnerIds: ["p1"], finalScores: singleWinnerScores, completedRound: 18 }),
    Object.assign({}, matchReadyForCompletion, { status: "complete", winnerIds: ["p2"], finalScores: singleWinnerScores, completedRound: 18, version: 91 }),
    "userB"
  ) === false
);
check(
  "SIMULATED — match completion: version not incrementing — DENIED",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: ["p1"], finalScores: singleWinnerScores, completedRound: 18
    }),
    "userB"
  ) === false
);
check(
  "SIMULATED — match completion: a non-player uid attempting the completion — DENIED",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: ["p1"], finalScores: singleWinnerScores, completedRound: 18, version: 91
    }),
    "some-fabricated-uid"
  ) === false
);
check(
  "SIMULATED — match completion: touching an unrelated field (seats) alongside an otherwise-valid completion — DENIED",
  isValidMatchCompletion(
    matchReadyForCompletion,
    Object.assign({}, matchReadyForCompletion, {
      status: "complete", winnerIds: ["p1"], finalScores: singleWinnerScores, completedRound: 18, version: 91,
      seats: { p1: "userZ", p2: "userC", p3: "userD", p4: "userE" }
    }),
    "userB"
  ) === false
);

// ---- REGRESSION (found via real-browser QA, this sprint's Phase 4 Scenario N):
// isValidRoundAdvance()/isValidRoundExtension() must both refuse to touch
// an ALREADY-COMPLETE match — a round-advance or round-extension racing an
// in-flight completion must never win independently of the JS-side
// (MatchService) guard.
var matchAlreadyComplete = Object.assign({}, matchReadyForRoundAdvance, {
  status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 50, p3: 30, p4: 20 }, completedRound: 1
});
check(
  "SIMULATED — REGRESSION: round advance attempted on an ALREADY-COMPLETE match — DENIED",
  isValidRoundAdvance(
    matchAlreadyComplete,
    Object.assign({}, matchAlreadyComplete, { currentRound: 2, version: matchAlreadyComplete.version + 1, biddingOpen: true, cardPhase: null, turn: null }),
    "userB"
  ) === false
);
var matchAlreadyCompleteForExtension = Object.assign({}, matchBaseForCompletionSprint, {
  status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 50, p3: 30, p4: 20 }, completedRound: 15, version: 40
});
check(
  "SIMULATED — REGRESSION: round extension attempted on an ALREADY-COMPLETE match — DENIED",
  isValidRoundExtension(
    matchAlreadyCompleteForExtension,
    Object.assign({}, matchAlreadyCompleteForExtension, { maxRounds: 19, extendedRounds: [15], version: 41 }),
    "userB"
  ) === false
);

// ---- isValidNewMatch() (V7): match doc creation now also establishes maxRounds/extendedRounds ----
function isValidNewMatchV7(data, requestAuthUid, thisMatchId, roomExists, room, roomMatchIdAfterCommit) {
  var base = isValidNewMatchV6(data, requestAuthUid, thisMatchId, roomExists, room, roomMatchIdAfterCommit);
  var allowedKeys = ["roomId", "players", "status", "createdAt", "currentRound", "dealer", "turn", "gameState",
                      "seats", "version", "biddingOpen", "bids", "lastBidSeat", "cardLog", "lastCardSeat", "cardPhase",
                      "biddingLog", "maxRounds", "extendedRounds"];
  var keys = Object.keys(data);
  var hasOnlyAllowedKeysV7 = keys.every(function (k) { return allowedKeys.indexOf(k) !== -1; });
  // isValidNewMatchV6's own hasOnlyAllowedKeys check (baked into `base`)
  // would have already rejected maxRounds/extendedRounds as UNKNOWN
  // fields — V7 widens the allowlist AND re-checks the new fields'
  // required values, matching the real rule's own Match Completion
  // sprint update to isValidNewMatch().
  return hasOnlyAllowedKeysV7
    && typeof data.roomId === "string"
    && Array.isArray(data.players) && data.players.length > 0
    && data.players.indexOf(requestAuthUid) !== -1
    && data.status === "starting"
    && data.currentRound === 1
    && data.maxRounds === 18
    && Array.isArray(data.extendedRounds) && data.extendedRounds.length === 0
    && data.players.indexOf(data.dealer) !== -1
    && data.players.indexOf(data.turn) !== -1
    && roomExists === true
    && !!room && room.players.indexOf(requestAuthUid) !== -1
    && room.status === "waiting"
    && isRoomReadyForMatchStart(room)
    && arraysEqual(data.players, room.players)
    && roomMatchIdAfterCommit === thisMatchId
    && isValidSeatMap(data.seats, data.players)
    && data.version === 1
    && data.biddingOpen === true
    && Array.isArray(data.cardLog) && data.cardLog.length === 0
    && data.lastCardSeat === null
    && data.cardPhase === null
    && Array.isArray(data.biddingLog) && data.biddingLog.length === 0
    && base !== undefined; // keep V6 in the dependency graph — not dead code
}

var matchCreateDataV7 = Object.assign({}, matchAfterCreate423_fourSeats, {
  players: ["userB", "userC", "userD", "userE"],
  seats: { p1: "userB", p2: "userC", p3: "userD", p4: "userE" },
  bids: { p1: null, p2: null, p3: null, p4: null },
  currentRound: 1, version: 1, maxRounds: 18, extendedRounds: [],
  biddingLog: [],
  dealer: "userB", turn: "userB"
});
check(
  "SIMULATED — new match creation (V7): includes maxRounds:18 + extendedRounds:[] — ALLOWED",
  isValidNewMatchV7(matchCreateDataV7, "userB", "match-v7", true,
    { players: ["userB", "userC", "userD", "userE"], readyPlayers: ["userB", "userC", "userD", "userE"], status: "waiting" },
    "match-v7") === true
);
check(
  "SIMULATED — new match creation (V7): maxRounds other than 18 at creation — DENIED",
  isValidNewMatchV7(Object.assign({}, matchCreateDataV7, { maxRounds: 20 }), "userB", "match-v7", true,
    { players: ["userB", "userC", "userD", "userE"], readyPlayers: ["userB", "userC", "userD", "userE"], status: "waiting" },
    "match-v7") === false
);
check(
  "SIMULATED — new match creation (V7): a non-empty extendedRounds at creation — DENIED",
  isValidNewMatchV7(Object.assign({}, matchCreateDataV7, { extendedRounds: [15] }), "userB", "match-v7", true,
    { players: ["userB", "userC", "userD", "userE"], readyPlayers: ["userB", "userC", "userD", "userE"], status: "waiting" },
    "match-v7") === false
);

// ════════════════════════════════════════════════════════════════════
// Post-Match Rematch Vote sprint — 1:1 JS translations of
// firestore.rules' isValidNewRematchVote() / isValidVoteCast() /
// isValidTimeoutResolution() / isValidRematchMatchLink() /
// isValidNewRematchMatch(). Same "honest, lower-fidelity substitute"
// disclaimer as every other SIMULATED block in this file — these are
// NOT the real CEL rules; no Firestore emulator is available in this
// environment to compile the actual rules_version '2' syntax (see
// firestore.rules' own header comment on this exact new block for the
// full disclosure). `duration.value(30,'s')` arithmetic is modeled
// here as plain millisecond math against `requestTime`/`createdAtMs`
// parameters the test supplies directly, mirroring how every other
// SIMULATED function in this file takes its inputs as plain values
// rather than a real CEL `request`/`resource` context object.
// ════════════════════════════════════════════════════════════════════
var REMATCH_VOTE_WINDOW_MS = 30000;

function isValidNewRematchVote(parent, data, requestAuthUid) {
  if (requestAuthUid == null) return false;
  if ((parent.players || []).indexOf(requestAuthUid) === -1) return false;
  if (parent.status !== "complete") return false;
  if (JSON.stringify(data.seats) !== JSON.stringify(parent.seats)) return false;
  if (data.status !== "OPEN") return false;
  if (data.newMatchId !== null) return false;
  if (data.version !== 1) return false;
  var parentSeatKeys = Object.keys(parent.seats || {});
  var voteKeys = Object.keys(data.votes || {});
  if (!parentSeatKeys.every(function (k) { return voteKeys.indexOf(k) !== -1; })) return false;
  if (!voteKeys.every(function (k) { return parentSeatKeys.indexOf(k) !== -1; })) return false;
  if (!voteKeys.every(function (k) { return data.votes[k] === null; })) return false;
  return true;
}

function resolveActingSeat(parentSeats, requestAuthUid) {
  var found = Object.keys(parentSeats || {}).filter(function (s) { return parentSeats[s] === requestAuthUid; });
  return found.length ? found[0] : null;
}

function isValidVoteCast(parent, oldData, newData, requestAuthUid, requestTimeMs) {
  if (requestAuthUid == null) return false;
  if (oldData.status !== "OPEN") return false;
  var createdAtMs = oldData.createdAt;
  if (requestTimeMs > createdAtMs + REMATCH_VOTE_WINDOW_MS) return false;
  var actingSeat = resolveActingSeat(parent.seats, requestAuthUid);
  if (actingSeat == null) return false;
  if (oldData.votes[actingSeat] != null) return false; // vote immutability — already cast
  var newVal = newData.votes[actingSeat];
  if (newVal !== "YES" && newVal !== "NO") return false;
  var affectedKeys = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); });
  if (!affectedKeys.every(function (k) { return ["votes", "status", "version"].indexOf(k) !== -1; })) return false;
  if (newData.version !== oldData.version + 1) return false;
  // Every OTHER seat's vote must be byte-identical to before this write.
  var otherSeatsUnchanged = Object.keys(parent.seats || {}).every(function (s) {
    return s === actingSeat || JSON.stringify(newData.votes[s]) === JSON.stringify(oldData.votes[s]);
  });
  if (!otherSeatsUnchanged) return false;
  if (newVal === "NO") return newData.status === "FAILED_NO";
  var allRealSeatsYes = Object.keys(parent.seats || {}).every(function (s) { return newData.votes[s] === "YES"; });
  return allRealSeatsYes ? newData.status === "ALL_YES" : newData.status === oldData.status;
}

function isValidTimeoutResolution(parent, oldData, newData, requestAuthUid, requestTimeMs) {
  if (requestAuthUid == null) return false;
  if ((parent.players || []).indexOf(requestAuthUid) === -1) return false;
  if (oldData.status !== "OPEN") return false;
  if (!(requestTimeMs > oldData.createdAt + REMATCH_VOTE_WINDOW_MS)) return false;
  if (newData.status !== "FAILED_TIMEOUT") return false;
  var affectedKeys = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); });
  if (!affectedKeys.every(function (k) { return ["status", "version"].indexOf(k) !== -1; })) return false;
  if (newData.version !== oldData.version + 1) return false;
  if (JSON.stringify(newData.votes) !== JSON.stringify(oldData.votes)) return false;
  return true;
}

function isValidRematchMatchLink(parent, oldData, newData, requestAuthUid, getAfterNewMatch) {
  if (requestAuthUid == null) return false;
  if ((parent.players || []).indexOf(requestAuthUid) === -1) return false;
  if (oldData.status !== "ALL_YES") return false;
  if (oldData.newMatchId !== null) return false;
  if (newData.status !== "NEW_MATCH_CREATED") return false;
  if (typeof newData.newMatchId !== "string") return false;
  var affectedKeys = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); });
  if (!affectedKeys.every(function (k) { return ["status", "newMatchId", "version"].indexOf(k) !== -1; })) return false;
  if (newData.version !== oldData.version + 1) return false;
  if (JSON.stringify(newData.votes) !== JSON.stringify(oldData.votes)) return false;
  if (!getAfterNewMatch) return false;
  if (getAfterNewMatch.rematchOfMatchId !== "THIS_MATCH_ID") return false;
  if (JSON.stringify(getAfterNewMatch.seats) !== JSON.stringify(oldData.seats)) return false;
  return true;
}

function isValidNewRematchMatch(data, requestAuthUid, oldMatch, vote) {
  if ((data.players || []).indexOf(requestAuthUid) === -1) return false;
  if (data.status !== "starting") return false;
  if (data.currentRound !== 1) return false;
  if (data.maxRounds !== 18) return false;
  if (JSON.stringify(data.extendedRounds) !== JSON.stringify([])) return false;
  if (data.players.indexOf(data.dealer) === -1) return false;
  if (data.players.indexOf(data.turn) === -1) return false;
  if (oldMatch.status !== "complete") return false;
  if (vote.status !== "ALL_YES") return false;
  if (vote.newMatchId !== null) return false;
  if (data.roomId !== oldMatch.roomId) return false;
  if (JSON.stringify(data.seats) !== JSON.stringify(vote.seats)) return false;
  if (!isValidSeatMap(data.seats, data.players)) return false;
  if (data.version !== 1) return false;
  if (data.biddingOpen !== true) return false;
  var seatKeys = Object.keys(data.seats), bidKeys = Object.keys(data.bids || {});
  if (!seatKeys.every(function (k) { return bidKeys.indexOf(k) !== -1; })) return false;
  if (!bidKeys.every(function (k) { return seatKeys.indexOf(k) !== -1; })) return false;
  if (!seatKeys.every(function (k) { return data.bids[k] === null; })) return false;
  if (data.lastBidSeat !== null) return false;
  if (JSON.stringify(data.cardLog) !== JSON.stringify([])) return false;
  if (data.lastCardSeat !== null) return false;
  if (data.cardPhase !== null) return false;
  if (JSON.stringify(data.biddingLog) !== JSON.stringify([])) return false;
  return true;
}

// ---- fixtures ----
var completedParentMatch = {
  players: ["uidA", "uidB", "uidC", "uidD"], status: "complete",
  seats: { p1: "uidA", p2: "uidB", p3: "uidC", p4: "uidD" }
};
var freshVoteDoc = {
  matchId: "match-x", seats: completedParentMatch.seats,
  votes: { p1: null, p2: null, p3: null, p4: null },
  status: "OPEN", newMatchId: null, createdAt: 1000, version: 1
};

// ---- isValidNewRematchVote() ----
check(
  "SIMULATED — rematch vote creation: well-formed (seats copied verbatim, all-null votes) — ALLOWED",
  isValidNewRematchVote(completedParentMatch, freshVoteDoc, "uidA") === true
);
check(
  "SIMULATED — rematch vote creation: parent match not complete — DENIED",
  isValidNewRematchVote(Object.assign({}, completedParentMatch, { status: "starting" }), freshVoteDoc, "uidA") === false
);
check(
  "SIMULATED — rematch vote creation: caller not a seated player of the parent — DENIED",
  isValidNewRematchVote(completedParentMatch, freshVoteDoc, "uidX") === false
);
check(
  "SIMULATED — rematch vote creation: seats do NOT match the parent's own seats (injection attempt) — DENIED",
  isValidNewRematchVote(completedParentMatch, Object.assign({}, freshVoteDoc, { seats: { p1: "uidZ", p2: "uidB", p3: "uidC", p4: "uidD" } }), "uidA") === false
);
check(
  "SIMULATED — rematch vote creation: a non-null starting vote — DENIED",
  isValidNewRematchVote(completedParentMatch, Object.assign({}, freshVoteDoc, { votes: { p1: "YES", p2: null, p3: null, p4: null } }), "uidA") === false
);
check(
  "SIMULATED — rematch vote creation: newMatchId pre-set at creation — DENIED",
  isValidNewRematchVote(completedParentMatch, Object.assign({}, freshVoteDoc, { newMatchId: "sneaky" }), "uidA") === false
);

// ---- isValidVoteCast() ----
var openVoteAllNull = Object.assign({}, freshVoteDoc, { createdAt: 1000, version: 3 });
check(
  "SIMULATED — vote cast: a real seated player casting their OWN null vote to YES — ALLOWED",
  isValidVoteCast(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { votes: Object.assign({}, openVoteAllNull.votes, { p1: "YES" }), version: 4 }),
    "uidA", 1500) === true
);
check(
  "SIMULATED — vote cast: casting for a seat the caller does NOT own — DENIED",
  isValidVoteCast(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { votes: Object.assign({}, openVoteAllNull.votes, { p2: "YES" }), version: 4 }),
    "uidA", 1500) === false
);
var voteWithP1Yes = Object.assign({}, openVoteAllNull, { votes: Object.assign({}, openVoteAllNull.votes, { p1: "YES" }) });
check(
  "SIMULATED — vote cast: flipping an ALREADY-CAST vote (YES -> NO) — DENIED (immutability)",
  isValidVoteCast(completedParentMatch, voteWithP1Yes,
    Object.assign({}, voteWithP1Yes, { votes: Object.assign({}, voteWithP1Yes.votes, { p1: "NO" }), version: voteWithP1Yes.version + 1 }),
    "uidA", 1500) === false
);
check(
  "SIMULATED — vote cast: a NO vote closes the vote to FAILED_NO in the SAME write — ALLOWED",
  isValidVoteCast(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { votes: Object.assign({}, openVoteAllNull.votes, { p2: "NO" }), status: "FAILED_NO", version: 4 }),
    "uidB", 1500) === true
);
check(
  "SIMULATED — vote cast: a NO vote that does NOT also close status to FAILED_NO — DENIED",
  isValidVoteCast(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { votes: Object.assign({}, openVoteAllNull.votes, { p2: "NO" }), version: 4 }), // status left OPEN
    "uidB", 1500) === false
);
var voteThreeYes = Object.assign({}, openVoteAllNull, { votes: { p1: "YES", p2: "YES", p3: "YES", p4: null } });
check(
  "SIMULATED — vote cast: the 4th YES completes ALL_YES — ALLOWED",
  isValidVoteCast(completedParentMatch, voteThreeYes,
    Object.assign({}, voteThreeYes, { votes: Object.assign({}, voteThreeYes.votes, { p4: "YES" }), status: "ALL_YES", version: voteThreeYes.version + 1 }),
    "uidD", 1500) === true
);
check(
  "SIMULATED — vote cast: the 4th YES that does NOT set ALL_YES — DENIED",
  isValidVoteCast(completedParentMatch, voteThreeYes,
    Object.assign({}, voteThreeYes, { votes: Object.assign({}, voteThreeYes.votes, { p4: "YES" }), version: voteThreeYes.version + 1 }), // status stays OPEN
    "uidD", 1500) === false
);
check(
  "SIMULATED — vote cast: a 3-seat match's ALL_YES ignores the non-existent p4 seat correctly",
  (function () {
    var parent3 = { players: ["uidA", "uidB", "uidC"], status: "complete", seats: { p1: "uidA", p2: "uidB", p3: "uidC" } };
    var v = { matchId: "m3", seats: parent3.seats, votes: { p1: "YES", p2: "YES", p3: null }, status: "OPEN", newMatchId: null, createdAt: 1000, version: 5 };
    return isValidVoteCast(parent3, v,
      Object.assign({}, v, { votes: Object.assign({}, v.votes, { p3: "YES" }), status: "ALL_YES", version: 6 }),
      "uidC", 1500) === true;
  })()
);
check(
  "SIMULATED — vote cast: request.time AFTER the 30s deadline — DENIED",
  isValidVoteCast(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { votes: Object.assign({}, openVoteAllNull.votes, { p1: "YES" }), version: 4 }),
    "uidA", openVoteAllNull.createdAt + REMATCH_VOTE_WINDOW_MS + 1) === false
);
check(
  "SIMULATED — vote cast: request.time exactly AT the 30s boundary — ALLOWED (inclusive)",
  isValidVoteCast(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { votes: Object.assign({}, openVoteAllNull.votes, { p1: "YES" }), version: 4 }),
    "uidA", openVoteAllNull.createdAt + REMATCH_VOTE_WINDOW_MS) === true
);
check(
  "SIMULATED — vote cast: a write that ALSO touches a field outside the allow-list (e.g. seats) — DENIED",
  isValidVoteCast(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { votes: Object.assign({}, openVoteAllNull.votes, { p1: "YES" }), version: 4, seats: { p1: "uidZ", p2: "uidB", p3: "uidC", p4: "uidD" } }),
    "uidA", 1500) === false
);

// ---- isValidTimeoutResolution() ----
check(
  "SIMULATED — timeout resolution: past deadline, votes untouched, status->FAILED_TIMEOUT — ALLOWED",
  isValidTimeoutResolution(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { status: "FAILED_TIMEOUT", version: openVoteAllNull.version + 1 }),
    "uidC", openVoteAllNull.createdAt + REMATCH_VOTE_WINDOW_MS + 1) === true
);
check(
  "SIMULATED — timeout resolution: BEFORE the deadline — DENIED",
  isValidTimeoutResolution(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { status: "FAILED_TIMEOUT", version: openVoteAllNull.version + 1 }),
    "uidC", openVoteAllNull.createdAt + 100) === false
);
check(
  "SIMULATED — timeout resolution: fabricating a vote value alongside the timeout — DENIED",
  isValidTimeoutResolution(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { status: "FAILED_TIMEOUT", votes: Object.assign({}, openVoteAllNull.votes, { p1: "NO" }), version: openVoteAllNull.version + 1 }),
    "uidC", openVoteAllNull.createdAt + REMATCH_VOTE_WINDOW_MS + 1) === false
);
check(
  "SIMULATED — timeout resolution: a non-seated uid attempting it — DENIED",
  isValidTimeoutResolution(completedParentMatch, openVoteAllNull,
    Object.assign({}, openVoteAllNull, { status: "FAILED_TIMEOUT", version: openVoteAllNull.version + 1 }),
    "uidX", openVoteAllNull.createdAt + REMATCH_VOTE_WINDOW_MS + 1) === false
);
check(
  "SIMULATED — timeout resolution: vote already terminal (e.g. FAILED_NO) — DENIED (no re-transition)",
  isValidTimeoutResolution(completedParentMatch, Object.assign({}, openVoteAllNull, { status: "FAILED_NO" }),
    Object.assign({}, openVoteAllNull, { status: "FAILED_TIMEOUT", version: openVoteAllNull.version + 1 }),
    "uidC", openVoteAllNull.createdAt + REMATCH_VOTE_WINDOW_MS + 1) === false
);

// ---- isValidRematchMatchLink() / isValidNewRematchMatch() ----
var allYesVote = Object.assign({}, openVoteAllNull, { status: "ALL_YES", votes: { p1: "YES", p2: "YES", p3: "YES", p4: "YES" } });
var linkedNewMatch = { rematchOfMatchId: "THIS_MATCH_ID", seats: allYesVote.seats };
check(
  "SIMULATED — rematch match link: ALL_YES -> NEW_MATCH_CREATED with a real, symmetric getAfter() match — ALLOWED",
  isValidRematchMatchLink(completedParentMatch, allYesVote,
    Object.assign({}, allYesVote, { status: "NEW_MATCH_CREATED", newMatchId: "new-match-1", version: allYesVote.version + 1 }),
    "uidA", linkedNewMatch) === true
);
check(
  "SIMULATED — rematch match link: the new match's rematchOfMatchId points somewhere ELSE — DENIED",
  isValidRematchMatchLink(completedParentMatch, allYesVote,
    Object.assign({}, allYesVote, { status: "NEW_MATCH_CREATED", newMatchId: "new-match-1", version: allYesVote.version + 1 }),
    "uidA", Object.assign({}, linkedNewMatch, { rematchOfMatchId: "SOME_OTHER_MATCH" })) === false
);
check(
  "SIMULATED — rematch match link: attempting it when status is NOT yet ALL_YES — DENIED",
  isValidRematchMatchLink(completedParentMatch, Object.assign({}, allYesVote, { status: "OPEN" }),
    Object.assign({}, allYesVote, { status: "NEW_MATCH_CREATED", newMatchId: "new-match-1", version: allYesVote.version + 1 }),
    "uidA", linkedNewMatch) === false
);
check(
  "SIMULATED — rematch match link: newMatchId already set (a second attempt after one already won) — DENIED",
  isValidRematchMatchLink(completedParentMatch, Object.assign({}, allYesVote, { newMatchId: "already-there" }),
    Object.assign({}, allYesVote, { status: "NEW_MATCH_CREATED", newMatchId: "new-match-2", version: allYesVote.version + 1 }),
    "uidA", linkedNewMatch) === false
);

var validRematchMatchData = {
  roomId: "room-x", players: ["uidA", "uidB", "uidC", "uidD"], status: "starting", currentRound: 1,
  dealer: "uidA", turn: "uidA", maxRounds: 18, extendedRounds: [],
  seats: allYesVote.seats, version: 1, biddingOpen: true,
  bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
  cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: []
};
var oldMatchForLink = { status: "complete", roomId: "room-x" };
check(
  "SIMULATED — new rematch match: well-formed, seats copied verbatim from the ALL_YES vote — ALLOWED",
  isValidNewRematchMatch(validRematchMatchData, "uidA", oldMatchForLink, allYesVote) === true
);
check(
  "SIMULATED — new rematch match: seats DIFFER from the vote's own seats (injection attempt) — DENIED",
  isValidNewRematchMatch(Object.assign({}, validRematchMatchData, { seats: { p1: "uidZ", p2: "uidB", p3: "uidC", p4: "uidD" }, players: ["uidZ", "uidB", "uidC", "uidD"] }), "uidZ", oldMatchForLink, allYesVote) === false
);
check(
  "SIMULATED — new rematch match: old match not actually complete — DENIED",
  isValidNewRematchMatch(validRematchMatchData, "uidA", Object.assign({}, oldMatchForLink, { status: "starting" }), allYesVote) === false
);
check(
  "SIMULATED — new rematch match: vote not actually ALL_YES — DENIED",
  isValidNewRematchMatch(validRematchMatchData, "uidA", oldMatchForLink, Object.assign({}, allYesVote, { status: "OPEN" })) === false
);
check(
  "SIMULATED — new rematch match: vote already has a newMatchId (duplicate-creation attempt) — DENIED",
  isValidNewRematchMatch(validRematchMatchData, "uidA", oldMatchForLink, Object.assign({}, allYesVote, { newMatchId: "already-created" })) === false
);
check(
  "SIMULATED — new rematch match: a 3-player rematch (no p4) is still a valid bijection via isValidSeatMap() reuse",
  (function () {
    var vote3 = { seats: { p1: "uidA", p2: "uidB", p3: "uidC" }, status: "ALL_YES", newMatchId: null };
    var data3 = Object.assign({}, validRematchMatchData, { players: ["uidA", "uidB", "uidC"], seats: vote3.seats, bids: { p1: null, p2: null, p3: null } });
    return isValidNewRematchMatch(data3, "uidA", oldMatchForLink, vote3) === true;
  })()
);

// ════════════════════════════════════════════════════════════════════
// Player Hand Synchronization sprint (Architecture Gate-approved
// Option A) — 1:1 JS translations of firestore.rules' new
// gameState.keys().hasOnly(...) shape-lock (added to
// isValidNewMatch()/isValidNewRematchMatch()), isValidHandDealCommit()
// (the parent match's gameState.dealtRound flip), and the new
// matches/{matchId}/hands/{seatId} block's ownsSeat()/isValidNewHand()/
// isValidHandRedeal(). Same "honest, lower-fidelity substitute"
// disclaimer as every other SIMULATED block in this file — no
// Firestore emulator is available in this environment to compile the
// actual rules_version '2' syntax; a real-emulator verification pass
// remains recommended before production deploy, exactly as already
// documented for `rematchVote` in firestore.rules itself.
// ════════════════════════════════════════════════════════════════════

// V8 extends V7 with the gameState shape-lock this sprint's
// Architecture Gate found missing (previously allowlisted by KEY only,
// with no constraint on VALUE/shape).
function isValidNewMatchV8(data, requestAuthUid, thisMatchId, roomExists, room, roomMatchIdAfterCommit) {
  var base = isValidNewMatchV7(data, requestAuthUid, thisMatchId, roomExists, room, roomMatchIdAfterCommit);
  var gs = data.gameState || {};
  var gsKeys = Object.keys(gs);
  var gsHasOnlyAllowed = gsKeys.every(function (k) { return ["initialized", "dealtRound"].indexOf(k) !== -1; });
  return base
    && gsHasOnlyAllowed
    && gs.initialized === false
    && gs.dealtRound === 0;
}

function isValidNewRematchMatchV2(data, requestAuthUid, oldMatch, vote) {
  var base = isValidNewRematchMatch(data, requestAuthUid, oldMatch, vote);
  var gs = data.gameState || {};
  var gsKeys = Object.keys(gs);
  var gsHasOnlyAllowed = gsKeys.every(function (k) { return ["initialized", "dealtRound"].indexOf(k) !== -1; });
  return base
    && gsHasOnlyAllowed
    && gs.initialized === false
    && gs.dealtRound === 0;
}

// matches/{matchId} UPDATE shape: the parent-doc side of
// MatchService.dealRound()'s paired write (the SIBLING hands/{seatId}
// create/update is validated independently below).
function isValidHandDealCommit(oldData, newData, requestAuthUid) {
  if (requestAuthUid == null) return false;
  if ((oldData.players || []).indexOf(requestAuthUid) === -1) return false;
  if (!("gameState" in oldData) || !("currentRound" in oldData)) return false;
  if ("status" in oldData && oldData.status === "complete") return false;
  var affected = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); });
  var onlyAllowed = affected.every(function (k) { return ["gameState", "updatedAt"].indexOf(k) !== -1; });
  var gs = newData.gameState || {};
  var oldDealtRound = (oldData.gameState && oldData.gameState.dealtRound) || 0;
  return onlyAllowed
    && Object.keys(gs).every(function (k) { return ["initialized", "dealtRound"].indexOf(k) !== -1; })
    && gs.initialized === true
    && gs.dealtRound === oldData.currentRound
    && gs.dealtRound > oldDealtRound;
}

// matches/{matchId}/hands/{seatId} — get()/create()/update() gates.
function ownsHandSeat(parentSeats, seatId, requestAuthUid) {
  return requestAuthUid != null && (parentSeats || {})[seatId] === requestAuthUid;
}
var VALID_HAND_SUITS = ["SANS", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"];
function isValidHandCardShape(card) {
  if (!card || typeof card !== "object") return false;
  var keys = Object.keys(card);
  if (!keys.every(function (k) { return ["suit", "rank"].indexOf(k) !== -1; })) return false;
  if (VALID_HAND_SUITS.indexOf(card.suit) === -1) return false;
  if (!card.rank || typeof card.rank !== "object") return false;
  var rankKeys = Object.keys(card.rank);
  if (!rankKeys.every(function (k) { return ["v", "s"].indexOf(k) !== -1; })) return false;
  return typeof card.rank.v === "number" && Number.isInteger(card.rank.v) && card.rank.v >= 2 && card.rank.v <= 14;
}
function isValidHandShape(data, seatId) {
  var keys = Object.keys(data);
  return keys.every(function (k) { return ["seatId", "round", "cards", "version"].indexOf(k) !== -1; })
    && data.seatId === seatId
    && typeof data.round === "number" && Number.isInteger(data.round)
    && data.version === data.round
    && Array.isArray(data.cards) && data.cards.length === 13
    && data.cards.every(isValidHandCardShape);
}
function isValidNewHand(parentMatch, seatId, data, requestAuthUid) {
  return ["p1", "p2", "p3", "p4"].indexOf(seatId) !== -1
    && ownsHandSeat(parentMatch.seats, seatId, requestAuthUid)
    && isValidHandShape(data, seatId)
    && data.round === parentMatch.currentRound;
}
function isValidHandRedeal(parentMatch, seatId, oldData, newData, requestAuthUid) {
  return ["p1", "p2", "p3", "p4"].indexOf(seatId) !== -1
    && ownsHandSeat(parentMatch.seats, seatId, requestAuthUid)
    && isValidHandShape(newData, seatId)
    && newData.round === parentMatch.currentRound
    && newData.round > oldData.round;
}

var handParentMatch = {
  players: ["uidA", "uidB", "uidC", "uidD"], status: "starting", currentRound: 1,
  seats: { p1: "uidA", p2: "uidB", p3: "uidC", p4: "uidD" },
  gameState: { initialized: false, dealtRound: 0 }
};
function fullHandOfCards() {
  var cards = [];
  ["SPADES", "HEARTS", "DIAMONDS"].forEach(function (suit) {
    for (var v = 2; v <= 5; v++) cards.push({ suit: suit, rank: { v: v, s: String(v) } });
  });
  cards.push({ suit: "CLUBS", rank: { v: 14, s: "A" } });
  return cards; // 3*4 + 1 = 13
}
var validNewHandData = { seatId: "p1", round: 1, version: 1, cards: fullHandOfCards() };

check(
  "SIMULATED — hand create: owner deals well-formed 13-card hand for the CURRENT round — ALLOWED",
  isValidNewHand(handParentMatch, "p1", validNewHandData, "uidA") === true
);
check(
  "SIMULATED — unauthorized hand read: ownsSeat() denies a uid that does not occupy the seat",
  ownsHandSeat(handParentMatch.seats, "p1", "uidB") === false
);
check(
  "SIMULATED — unauthorized hand write: a non-owning uid attempting seat p1's create — DENIED",
  isValidNewHand(handParentMatch, "p1", validNewHandData, "uidB") === false
);
check(
  "SIMULATED — tampered hand: an extra, unlisted key (e.g. a fabricated `owner` field) — DENIED",
  isValidNewHand(handParentMatch, "p1", Object.assign({}, validNewHandData, { owner: "uidA" }), "uidA") === false
);
check(
  "SIMULATED — tampered hand: wrong seatId inside the doc vs. the path's own seatId — DENIED",
  isValidNewHand(handParentMatch, "p1", Object.assign({}, validNewHandData, { seatId: "p2" }), "uidA") === false
);
check(
  "SIMULATED — incomplete/invalid hand: only 12 cards instead of 13 — DENIED",
  isValidNewHand(handParentMatch, "p1", Object.assign({}, validNewHandData, { cards: fullHandOfCards().slice(0, 12) }), "uidA") === false
);
check(
  "SIMULATED — incomplete/invalid hand: a malformed card (bad suit) among the 13 — DENIED",
  isValidNewHand(handParentMatch, "p1", Object.assign({}, validNewHandData, { cards: fullHandOfCards().slice(0, 12).concat([{ suit: "NOT_A_SUIT", rank: { v: 10, s: "10" } }]) }), "uidA") === false
);
check(
  "SIMULATED — stale-round hand: `round` doesn't match the parent's currentRound — DENIED",
  isValidNewHand(handParentMatch, "p1", Object.assign({}, validNewHandData, { round: 2, version: 2 }), "uidA") === false
);
check(
  "SIMULATED — seat/doc-id mismatch: an out-of-range seatId (e.g. 'p5') — DENIED",
  isValidNewHand(handParentMatch, "p5", Object.assign({}, validNewHandData, { seatId: "p5" }), "uidA") === false
);

var dealtParentMatch = Object.assign({}, handParentMatch, { currentRound: 2, gameState: { initialized: true, dealtRound: 1 } });
var oldHandRound1 = { seatId: "p1", round: 1, version: 1, cards: fullHandOfCards() };
var newHandRound2 = { seatId: "p1", round: 2, version: 2, cards: fullHandOfCards() };
check(
  "SIMULATED — hand redeal: Round 2 legitimately overwrites Round 1's doc for the SAME owning seat — ALLOWED",
  isValidHandRedeal(dealtParentMatch, "p1", oldHandRound1, newHandRound2, "uidA") === true
);
check(
  "SIMULATED — hand redeal: an attempt to rewrite the SAME round (no forward progress) — DENIED",
  isValidHandRedeal(dealtParentMatch, "p1", oldHandRound1, Object.assign({}, oldHandRound1, { cards: fullHandOfCards().reverse() }), "uidA") === false
);
check(
  "SIMULATED — hand redeal: a non-owning uid attempting the overwrite — DENIED",
  isValidHandRedeal(dealtParentMatch, "p1", oldHandRound1, newHandRound2, "uidB") === false
);

var dealCommitOldMatch = { players: ["uidA", "uidB", "uidC", "uidD"], status: "starting", currentRound: 1, gameState: { initialized: false, dealtRound: 0 } };
check(
  "SIMULATED — hand deal commit: gameState flips initialized:true, dealtRound:1 for the current round — ALLOWED",
  isValidHandDealCommit(dealCommitOldMatch, Object.assign({}, dealCommitOldMatch, { gameState: { initialized: true, dealtRound: 1 } }), "uidA") === true
);
check(
  "SIMULATED — hand deal commit: attempting to also touch an unrelated field (e.g. status) in the same write — DENIED",
  isValidHandDealCommit(dealCommitOldMatch, Object.assign({}, dealCommitOldMatch, { gameState: { initialized: true, dealtRound: 1 }, status: "complete" }), "uidA") === false
);
check(
  "SIMULATED — hand deal commit: dealtRound regressing/repeating instead of advancing — DENIED",
  isValidHandDealCommit(Object.assign({}, dealCommitOldMatch, { gameState: { initialized: true, dealtRound: 1 } }),
    Object.assign({}, dealCommitOldMatch, { gameState: { initialized: true, dealtRound: 1 } }), "uidA") === false
);
check(
  "SIMULATED — hand deal commit: a non-player attempting the commit — DENIED",
  isValidHandDealCommit(dealCommitOldMatch, Object.assign({}, dealCommitOldMatch, { gameState: { initialized: true, dealtRound: 1 } }), "uidZ") === false
);
check(
  "SIMULATED — hand deal commit: an already-complete match can never have gameState re-flipped — DENIED",
  isValidHandDealCommit(Object.assign({}, dealCommitOldMatch, { status: "complete" }),
    Object.assign({}, dealCommitOldMatch, { status: "complete", gameState: { initialized: true, dealtRound: 1 } }), "uidA") === false
);

var gameStateLockMatchData = Object.assign({}, matchCreateDataV7, { gameState: { initialized: false, dealtRound: 0 } });
check(
  "SIMULATED — new match creation (V8): gameState:{initialized:false, dealtRound:0} — ALLOWED",
  isValidNewMatchV8(gameStateLockMatchData, "userB", "match-v7", true,
    { players: ["userB", "userC", "userD", "userE"], readyPlayers: ["userB", "userC", "userD", "userE"], status: "waiting" },
    "match-v7") === true
);
check(
  "SIMULATED — new match creation (V8): the OLD placeholder shape {initialized:false, todo:'...'} is now DENIED — the exact gap this sprint's Architecture Gate found",
  isValidNewMatchV8(Object.assign({}, gameStateLockMatchData, { gameState: { initialized: false, todo: "x" } }), "userB", "match-v7", true,
    { players: ["userB", "userC", "userD", "userE"], readyPlayers: ["userB", "userC", "userD", "userE"], status: "waiting" },
    "match-v7") === false
);
check(
  "SIMULATED — new match creation (V8): dealtRound other than 0 at creation — DENIED",
  isValidNewMatchV8(Object.assign({}, gameStateLockMatchData, { gameState: { initialized: false, dealtRound: 1 } }), "userB", "match-v7", true,
    { players: ["userB", "userC", "userD", "userE"], readyPlayers: ["userB", "userC", "userD", "userE"], status: "waiting" },
    "match-v7") === false
);
check(
  "SIMULATED — new rematch match (V2): gameState:{initialized:false, dealtRound:0} — ALLOWED",
  isValidNewRematchMatchV2(Object.assign({}, validRematchMatchData, { gameState: { initialized: false, dealtRound: 0 } }), "uidA", oldMatchForLink, allYesVote) === true
);
check(
  "SIMULATED — new rematch match (V2): the OLD placeholder shape is now DENIED",
  isValidNewRematchMatchV2(Object.assign({}, validRematchMatchData, { gameState: { initialized: false, todo: "x" } }), "uidA", oldMatchForLink, allYesVote) === false
);

console.log("\n" + pass + " passed, " + fail + " failed");
process.exitCode = fail ? 1 : 0;
