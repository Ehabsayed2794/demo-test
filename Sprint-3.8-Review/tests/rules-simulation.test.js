// Simulates firestore.rules' rooms/{roomId} logic against mock
// request/resource data. This is NOT the Firebase Rules Unit Testing
// library (@firebase/rules-unit-testing) — that needs the Firebase CLI
// + a Java-backed local emulator, neither available in this sandboxed
// session (no network-installable Java runtime, no `firebase` CLI
// login for this project). Instead, each rule expression below is
// translated 1:1 from firestore.rules into plain JS and exercised
// against representative request shapes — an honest, lower-fidelity
// substitute, not a claim of having run the real emulator.

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

// oldData/newData: matches/{matchId}'s before/after write data.
// requestAuthUid: request.auth.uid.
function isValidBidSubmission(oldData, newData, requestAuthUid) {
  var seat = newData.lastBidSeat;
  if (requestAuthUid == null) return false;
  if ((oldData.players || []).indexOf(requestAuthUid) === -1) return false;
  if (!("seats" in oldData) || !("bids" in oldData) || !("version" in oldData) || !("biddingOpen" in oldData)) return false;

  var allowedChangedKeys = ["bids", "biddingOpen", "version", "lastBidSeat", "updatedAt"];
  var changedKeys = Object.keys(newData).filter(function (k) { return JSON.stringify(newData[k]) !== JSON.stringify(oldData[k]); })
    .concat(Object.keys(oldData).filter(function (k) { return !(k in newData); }));
  var onlyAllowedKeysChanged = changedKeys.every(function (k) { return allowedChangedKeys.indexOf(k) !== -1; });
  if (!onlyAllowedKeysChanged) return false;

  if (newData.version !== oldData.version + 1) return false;
  if (oldData.biddingOpen !== true) return false;
  if (typeof seat !== "string") return false;
  if (!(seat in oldData.seats)) return false;
  if (oldData.seats[seat] !== requestAuthUid) return false;
  if (!(!(seat in oldData.bids) || oldData.bids[seat] == null)) return false;
  if (!(seat in newData.bids) || newData.bids[seat] == null) return false;

  var bidsChangedKeys = Object.keys(newData.bids).filter(function (k) { return JSON.stringify(newData.bids[k]) !== JSON.stringify((oldData.bids || {})[k]); });
  if (!(bidsChangedKeys.length === 1 && bidsChangedKeys[0] === seat)) return false;

  var allSeatsFilled = Object.keys(oldData.seats).every(function (s) { return s in newData.bids && newData.bids[s] != null; });
  if (newData.biddingOpen !== !allSeatsFilled) return false;

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

console.log("\n" + pass + " passed, " + fail + " failed");
process.exitCode = fail ? 1 : 0;
