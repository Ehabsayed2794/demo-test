// SPRINT-3.7: Verify Firestore Security Rules for the Room->Match Start
// transaction — the exact atomic write MatchService.startMatch() performs
// (design-ui/match-service.js: tx.set(newMatchRef, doc) +
// tx.update(roomRef, {status:'in_game', matchId, updatedAt}), ONE
// transaction, two documents).
//
// FINDING (read before assuming a fix is needed): this pattern is
// NOT blocked. `firestore.rules`' isValidNewMatch() (matches/{matchId}
// create) and isValidRoomUpdate()/isValidMatchIdChange() (rooms/{roomId}
// update) already implement exactly the two acceptance criteria this
// sprint asks for, since Sprint 3.4.1 (Match Start Consistency & Security
// Hotfix) — re-confirmed by direct reading of the CURRENT file, not by
// trusting an old report:
//   - matches/{matchId} create requires `request.auth.uid in room.players`
//     (firestore.rules ~line 1218) — "allow create on matches if user is
//     in the room."
//   - rooms/{roomId} update's isValidMatchIdChange() explicitly permits
//     the waiting->in_game transition (firestore.rules ~line 249-258) —
//     "allow update on rooms to set status: 'in_game'" — paired, via
//     getAfter(), with the SAME transaction's match create.
// No firestore.rules change was made. This file is a NEW, focused
// SIMULATED (JS reimplementation, not the real CEL engine — see
// tests/rules-simulation.test.js's own header for that distinction)
// test for this ONE specific transaction pattern; it intentionally does
// NOT duplicate tests/rules-simulation.test.js's existing 278-check file
// (which already covers isValidNewMatch/isValidRoomUpdate individually,
// across several schema versions) — this file's job is narrower: prove
// the COMBINED transaction (both documents, together, exactly as
// startMatch() writes them) is allowed, and prove the specific denial
// cases the task calls out are still denied.
//
// This pattern has ALSO already been verified against the REAL
// Firestore Rules Emulator this session (tests/hand-sync.rules-emulator.test.cjs
// "F25/F26", tests/sprint-a-write-paths.rules-emulator.test.cjs) — this
// file adds the missing SIMULATED-tier, transaction-shaped coverage
// that was not literally present as one combined check anywhere else.

var pass = 0, fail = 0;
function check(label, ok, note) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; }
}

// ── Faithful JS reimplementation of the relevant CURRENT rule clauses
// (see firestore.rules line references in each function's comment). ──

// isRoomReadyForMatchStart() — firestore.rules ~line 342-344.
function isRoomReadyForMatchStart(room) {
  return room.players.length > 0 && room.players.every(function (p) { return room.readyPlayers.indexOf(p) !== -1; });
}

// isValidSeatMap() — firestore.rules ~line 382-400 (simplified: this
// simulation always builds a valid positional seat map, since seat
// assignment itself is out of scope for THIS sprint's rule pattern).
function buildSeatMap(players) {
  var seats = {};
  ["p1", "p2", "p3", "p4"].forEach(function (k, i) { if (players[i]) seats[k] = players[i]; });
  return seats;
}

// isValidNewMatch() — firestore.rules ~line 1198-1230 (the exact clauses
// relevant to THIS sprint's "create matches if user is in the room"
// acceptance criterion; full field-shape/version/bids checks are
// exercised by tests/rules-simulation.test.js already and are not
// re-duplicated here).
function isValidNewMatch(matchData, requestAuthUid, thisMatchId, room, roomExists, roomMatchIdAfterCommit) {
  return requestAuthUid != null
         && matchData.players.length > 0
         && matchData.players.indexOf(requestAuthUid) !== -1
         && matchData.status === "starting"
         && matchData.currentRound === 1
         && roomExists
         && room.players.indexOf(requestAuthUid) !== -1 // <-- "user is in the room"
         && room.status === "waiting"
         && isRoomReadyForMatchStart(room)
         && JSON.stringify(matchData.players) === JSON.stringify(room.players)
         && roomMatchIdAfterCommit === thisMatchId; // <-- getAfter() pairing
}

// isValidRoomUpdate()/isValidMatchIdChange() — firestore.rules
// ~line 244-296 (the exact clauses relevant to THIS sprint's "allow
// update on rooms to set status:'in_game'" acceptance criterion).
function isValidRoomUpdate(oldRoom, newRoom, requestAuthUid, matchAfterCommitExists, matchAfterCommit, roomId) {
  var isStartingTransition = oldRoom.status === "waiting" && newRoom.status === "in_game";
  if (!isStartingTransition) return oldRoom.matchId === newRoom.matchId; // no-op path, not this sprint's concern
  return oldRoom.matchId == null
         && typeof newRoom.matchId === "string"
         && oldRoom.players.indexOf(requestAuthUid) !== -1
         && oldRoom.players.every(function (p) { return oldRoom.readyPlayers.indexOf(p) !== -1; })
         && matchAfterCommitExists
         && matchAfterCommit.roomId === roomId
         && JSON.stringify(matchAfterCommit.players) === JSON.stringify(oldRoom.players);
}

// ── The exact real transaction shape: ONE atomic write touching BOTH
// rooms/{roomId} (update) and matches/{matchId} (create), evaluated
// together — reproducing MatchService.startMatch()'s own tx.set() +
// tx.update() pair. ──────────────────────────────────────────────────
function simulateStartMatchTransaction(room, roomId, matchId, requestAuthUid) {
  var matchData = {
    roomId: roomId,
    players: room.players.slice(),
    status: "starting",
    currentRound: 1,
    dealer: room.players[0],
    turn: room.players[0],
    seats: buildSeatMap(room.players)
  };
  var newRoom = Object.assign({}, room, { status: "in_game", matchId: matchId });

  // Both checks reference the OTHER document's post-commit state via
  // getAfter() in the real rules — here, since both writes are known
  // up front (this IS the transaction), that "after" state is simply
  // newRoom/matchData themselves.
  var createAllowed = isValidNewMatch(matchData, requestAuthUid, matchId, room, true, newRoom.matchId);
  var updateAllowed = isValidRoomUpdate(room, newRoom, requestAuthUid, true, matchData, roomId);
  return { createAllowed: createAllowed, updateAllowed: updateAllowed, matchData: matchData, newRoom: newRoom };
}

console.log("=== SPRINT-3.7: Room->Match Start Transaction Rules Simulation ===\n");

// ============ 1. THE REAL PRODUCTION SHAPE — must be allowed ============
var readyRoom = { players: ["p1", "p2"], readyPlayers: ["p1", "p2"], status: "waiting", creator: "p1" };
var result1 = simulateStartMatchTransaction(readyRoom, "room-1", "match-1", "p1");
check("1. matches/{matchId} CREATE is explicitly allowed when the caller is a member of the ready room",
  result1.createAllowed === true);
check("2. rooms/{roomId} UPDATE is explicitly allowed to set status:'in_game' as part of the SAME transaction",
  result1.updateAllowed === true);
check("3. Both writes of the SAME real transaction are allowed together (the actual acceptance criterion)",
  result1.createAllowed && result1.updateAllowed);

// ============ 2. Negative cases — must remain denied ============
var result2 = simulateStartMatchTransaction(readyRoom, "room-1", "match-1", "outsider-uid");
check("4. matches/{matchId} CREATE is DENIED for a caller who is NOT in the room", result2.createAllowed === false);
check("5. rooms/{roomId} UPDATE is DENIED for a caller who is NOT in the room", result2.updateAllowed === false);

var notReadyRoom = { players: ["p1", "p2"], readyPlayers: ["p1"], status: "waiting", creator: "p1" };
var result3 = simulateStartMatchTransaction(notReadyRoom, "room-2", "match-2", "p1");
check("6. matches/{matchId} CREATE is DENIED when the room is not fully ready", result3.createAllowed === false);
check("7. rooms/{roomId} UPDATE is DENIED when the room is not fully ready", result3.updateAllowed === false);

var alreadyStartedRoom = { players: ["p1", "p2"], readyPlayers: ["p1", "p2"], status: "in_game", matchId: "existing-match", creator: "p1" };
var result4 = simulateStartMatchTransaction(alreadyStartedRoom, "room-3", "match-3", "p1");
check("8. matches/{matchId} CREATE is DENIED for a room that's already in_game (can't double-start)", result4.createAllowed === false);

// Room update's own getAfter() pairing check: the match doc that will
// exist after this transaction must actually match this room — a
// mismatched roomId/players on the match side must deny the room update.
function simulateMismatchedPairing(room, roomId, matchId, requestAuthUid) {
  var wrongMatchData = { roomId: "SOME-OTHER-ROOM", players: room.players.slice() }; // mismatched roomId
  var newRoom = Object.assign({}, room, { status: "in_game", matchId: matchId });
  return isValidRoomUpdate(room, newRoom, requestAuthUid, true, wrongMatchData, roomId);
}
check("9. rooms/{roomId} UPDATE is DENIED if the paired match doc's roomId doesn't actually point back at this room",
  simulateMismatchedPairing(readyRoom, "room-1", "match-1", "p1") === false);

console.log("\n=== RESULTS ===\n");
console.log(pass + " passed, " + fail + " failed");
process.exitCode = fail > 0 ? 1 : 0;
