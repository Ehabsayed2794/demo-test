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

console.log("\n" + pass + " passed, " + fail + " failed");
process.exitCode = fail ? 1 : 0;
