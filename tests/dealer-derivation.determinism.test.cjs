var REPO_ROOT = require("path").join(__dirname, "..");
// TASK R3/R2 — DEALER-DERIVATION DETERMINISM AUDIT (diagnostic, no fixes).
//
// QUESTION: can any client-local or order-sensitive input make
// `MatchAdapter.uidToSeat(data, data.dealer)` (the H2 deal gate's
// dealer-seat derivation, design-ui/match/index.html:2159-2160) yield
// DIFFERENT seats on different clients for the SAME committed match?
//
// METHOD: loads the REAL design-ui/match-adapter.js (house MOCKED
// convention — plain-object docs, no Firestore) and enumerates every
// input channel uidToSeat() reads, plus the channels it DOESN'T read
// but a skeptical reviewer might suspect (players[] order, joinOrder,
// createdAt). Then brute-forces insertion-order permutations and
// duplicate-uid malformations hunting for ANY nondeterminism, and
// constructs the one true divergence channel (stale cross-match
// snapshot) explicitly.
//
// LABELING: MOCKED — real adapter code against hand-constructed docs.
global.window = global;
require(REPO_ROOT + "/design-ui/match-adapter.js");
var MatchAdapter = global.MatchAdapter;

// Production identity facts (golden-prod-evidence/evidence.jsonl L3/L5).
var UID_P1 = "CBuxgr72uVUpFbUWF29zKB37Dzs1";
var UID_P2 = "84a7hj8FRsSwZpmmmmLjE1aEQGC2";
var UID_P3 = "BeD0HLm7Fvdd4ZEJR7oVvjez5es2";
var UID_P4 = "S1WrTtnnAtUjFgj65rF84SocgS93";
var UIDS = [UID_P1, UID_P2, UID_P3, UID_P4];

var pass = 0, fail = 0;
function check(label, ok) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

function prodSeats() {
  return { p1: UID_P1, p2: UID_P2, p3: UID_P3, p4: UID_P4 };
}
function prodDoc(seats, dealerUid) {
  return {
    roomId: "noxiRrTFuAhfpUavuzZ1",
    players: UIDS.slice(),
    status: "starting", createdAt: 1756191000000,
    currentRound: 1, maxRounds: 18, extendedRounds: [],
    dealer: dealerUid == null ? UID_P1 : dealerUid,
    turn: dealerUid == null ? UID_P1 : dealerUid,
    seats: seats || prodSeats(),
    version: 1, biddingOpen: true,
    bids: { p1: null, p2: null, p3: null, p4: null },
    lastBidSeat: null, cardLog: [], lastCardSeat: null,
    cardPhase: null, biddingLog: [],
    gameState: { initialized: false, dealtRound: 0 }
  };
}
// Build an object with keys inserted in a SPECIFIC order (JS string keys
// preserve insertion order in Object.keys — this is the only way map
// iteration order could ever vary between clients).
function insertInOrder(pairs) {
  var o = {};
  pairs.forEach(function (p) { o[p[0]] = p[1]; });
  return o;
}
function permutations(arr) {
  if (arr.length <= 1) return [arr];
  var out = [];
  arr.forEach(function (x, i) {
    permutations(arr.slice(0, i).concat(arr.slice(i + 1))).forEach(function (rest) {
      out.push([x].concat(rest));
    });
  });
  return out;
}

console.log("── R2.a INPUT ENUMERATION (from source, quoted in report) ──");
console.log("uidToSeat reads EXACTLY TWO inputs:");
console.log("  1. matchDoc.seats  (map seatId->uid; iterated via sortedSeatKeys():");
console.log("     canonical p1<p2<p3<p4 FIRST, then non-canonical alphabetical —");
console.log("     design-ui/match-adapter.js:306 SEAT_ORDER, :326-334 sort)");
console.log("  2. the uid argument");
console.log("It does NOT read: players[], array order, joinOrder fields,");
console.log("createdAt tie-breaks, turn, or anything memoized/cached.");

console.log("\n── S1 baseline: canonical production doc ──");
var d1 = prodDoc();
check("S1 dealer uidP1 -> seat p1 (canonical)", MatchAdapter.uidToSeat(d1, UID_P1) === "p1");
check("S1 each simulated client derives the SAME dealerSeat",
  ["c1", "c2", "c3", "c4"].every(function () {
    return MatchAdapter.uidToSeat(d1, d1.dealer) === "p1";
  }));

console.log("\n── S2 seats-map KEY INSERTION ORDER permutations (24) ──");
var s2ok = true, s2bad = [];
permutations(["p1", "p2", "p3", "p4"]).forEach(function (perm) {
  var doc = prodDoc(insertInOrder(perm.map(function (k) { return [k, prodSeats()[k]]; })));
  var r = MatchAdapter.uidToSeat(doc, UID_P1);
  if (r !== "p1") { s2ok = false; s2bad.push(perm.join("<") + "=>" + r); }
});
check("S2 all 24 key-insertion permutations -> p1 (order-immune)", s2ok);
if (s2bad.length) console.log("   deviations: " + s2bad.join("; "));

console.log("\n── S3 async JOIN ORDERS -> different GLOBAL seat maps, still consistent ──");
// buildSeatMap assigns joiners positionally (players[i] -> p{i+1}); the
// room creator is always dealer wherever they landed. Simulate three
// realizable join orders; every client reads the ONE committed doc.
var s3ok = true;
[[UID_P1, UID_P2, UID_P3, UID_P4],
 [UID_P4, UID_P3, UID_P2, UID_P1],
 [UID_P2, UID_P4, UID_P1, UID_P3]].forEach(function (joinOrder, idx) {
  var seats = {};
  joinOrder.forEach(function (uid, i) { seats["p" + (i + 1)] = uid; });
  var dealerUid = UID_P1; // creator is always dealer (match-service.js:431)
  var doc = prodDoc(insertInOrder(Object.keys(seats).map(function (k) { return [k, seats[k]]; })), dealerUid);
  var dealerSeat = MatchAdapter.uidToSeat(doc, dealerUid);
  // every simulated client must agree on the derived dealer seat:
  var allSame = ["c1", "c2", "c3", "c4"].every(function () {
    return MatchAdapter.uidToSeat(doc, doc.dealer) === dealerSeat;
  });
  console.log("   joinOrder#" + (idx + 1) + " -> dealer sits at " + dealerSeat +
    (allSame ? " (all clients agree)" : " (DISAGREEMENT!)"));
  if (!allSame) s3ok = false;
});
check("S3 all join orders -> single agreed dealerSeat per committed doc", s3ok);

console.log("\n── S4 partial / stale / malformed local snapshots ──");
check("S4a doc missing seats entirely -> null (gate SKIPS, never mis-attempts)",
  MatchAdapter.uidToSeat({ roomId: "x" }, UID_P1) === null);
check("S4b empty seats map -> null", MatchAdapter.uidToSeat(prodDoc({}), UID_P1) === null);
check("S4c data.dealer null -> null", MatchAdapter.uidToSeat(prodDoc(), null) === null);
check("S4d dealer not seated (foreign uid) -> null",
  MatchAdapter.uidToSeat(prodDoc(), "ghost-uid") === null);
// Malformed duplicate-uid map (rules forbid it; defense-in-depth check):
// whichever insertion order, sortedSeatKeys() must pick the SAME winner.
var dupOk = permutations(["p1", "p2", "p3", "p4"]).every(function (perm) {
  var seats = insertInOrder(perm.map(function (k) {
    return [k, k === "p1" ? UID_P4 : prodSeats()[k]]; // p1 AND p4 both own UID_P4
  }));
  return MatchAdapter.uidToSeat(prodDoc(seats), UID_P4) === "p1"; // canonical-first wins everywhere
});
check("S4e malformed duplicate uids -> deterministic 'p1' under ALL insertion orders", dupOk);

console.log("\n── S5 KILLER PERMUTATION SEARCH (the requested construction) ──");
// Search content-divergence channel: two clients holding DIFFERENT docs
// (stale cache from a prior match where P4 created/dealt) — the ONLY way
// p4's page can compute dealerSeat='p4' while p1's page computes its own
// non-dealer status from the CURRENT doc.
var stalePriorMatch = prodDoc();          // hypothetical PRIOR run: P4 was creator+dealer
stalePriorMatch.dealer = UID_P4;
stalePriorMatch.turn = UID_P4;
var freshCurrentMatch = prodDoc();        // THIS run: P1 created -> dealer p1
var p4ViewStale = MatchAdapter.uidToSeat(stalePriorMatch, stalePriorMatch.dealer);
var p4LocalSeatOnStale = MatchAdapter.uidToSeat(stalePriorMatch, UID_P4);
var p1ViewFresh = MatchAdapter.uidToSeat(freshCurrentMatch, freshCurrentMatch.dealer);
console.log("   STALE doc view:  dealerSeat=" + p4ViewStale + ", p4.localSeatId=" + p4LocalSeatOnStale +
  "  -> gate FIRES on p4's page (" + (p4ViewStale === p4LocalSeatOnStale) + ")");
console.log("   FRESH doc view:  dealerSeat=" + p1ViewFresh + " (=p1, correct dealer)");
check("S5 killer permutation REQUIRES cross-match stale content, not order",
  p4ViewStale === p4LocalSeatOnStale && p1ViewFresh === "p1");
console.log("   NOTE: production denial targeted THIS run's matchId (kfgiv1ZlUNrkmiiunide)");
console.log("   inside the SAME transaction whose tx.get() returned fresh data + updateTime");
console.log("   precondition — so this stale-doc vector does NOT match the incident.");

console.log("\n── S6 exhaustive brute-force: content-equal => result-equal ──");
var checked = 0, diverged = 0;
permutations(["p1", "p2", "p3", "p4"]).forEach(function (permA) {
  permutations(["p1", "p2", "p3", "p4"]).forEach(function (permB) {
    [UID_P1, UID_P2, UID_P3, UID_P4, null].forEach(function (dealer) {
      var docA = prodDoc(insertInOrder(permA.map(function (k) { return [k, prodSeats()[k]]; })), dealer);
      var docB = prodDoc(insertInOrder(permB.map(function (k) { return [k, prodSeats()[k]]; })), dealer);
      checked++;
      if (MatchAdapter.uidToSeat(docA, docA.dealer) !== MatchAdapter.uidToSeat(docB, docB.dealer)) diverged++;
    });
  });
});
console.log("   pairs compared: " + checked + ", divergent results: " + diverged);
check("S6 zero nondeterminism across all " + checked + " ordered-pair comparisons (24x24x5)",
  diverged === 0 && checked === 24 * 24 * 5);

console.log("\n=== RESULTS ===");
console.log(pass + " passed, " + fail + " failed");
process.exitCode = fail > 0 ? 1 : 0;
