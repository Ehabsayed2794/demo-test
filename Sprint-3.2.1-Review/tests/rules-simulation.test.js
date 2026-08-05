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

// The FIXED rule actually shipped in firestore.rules.
function isExistingOrIncomingMember(resourceData, requestResourceData, requestAuthUid) {
  var oldPlayers = (resourceData && resourceData.players) || [];
  var newPlayers = (requestResourceData && requestResourceData.players) || [];
  return oldPlayers.indexOf(requestAuthUid) !== -1 || newPlayers.indexOf(requestAuthUid) !== -1;
}

// The LITERAL brief's rule, kept here ONLY to prove why it was replaced
// — never shipped in firestore.rules.
function literalBriefUpdateRule(resourceData, requestAuthUid) {
  var oldPlayers = (resourceData && resourceData.players) || [];
  return oldPlayers.indexOf(requestAuthUid) !== -1;
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

console.log("\n" + pass + " passed, " + fail + " failed");
process.exitCode = fail ? 1 : 0;
