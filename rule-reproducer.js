// rule-reproducer.js — Read-only offline Rules reproducer for Round-16 extension
// PURPOSE: Evaluate each predicate of isValidRoundExtension() against the exact
//          Round-16 inputs captured by the diagnostic run (gprun-diag5.log).
// SCOPE: Read-only. Does NOT write to Firestore. Does NOT modify firestore.rules.
// INPUTS: Captured directly from TRANSACTION_SNAPSHOT and EXTENSION_WRITE_INTENT
//          events in gprun-diag5.log.

'use strict';

// === EXACT CAPTURED INPUTS (from gprun-diag5.log, Round 16, p1) ===
// TRANSACTION_SNAPSHOT (line 3256 of gprun-diag5.log):
//   TRANSACTION_SNAPSHOT seat=p1 callingUid=GNZPc0AWW9tm0M9ULOzKKaSx8LfE
//   completedRound=16 reason=SAAYDA
//   match.version=1150 match.currentRound=16 match.maxRounds=19
//   match.extendedRounds=[14] match.status=starting
//   match.completedRound=undefined match.cardLogLength=832
//
// EXTENSION_WRITE_INTENT (line 3256 of gprun-diag5.log):
//   oldMaxRounds=19 oldExtendedRounds=[14] oldVersion=1150
//   newMaxRounds=20 newExtendedRounds=[14,16] newVersion=1151

const oldData = {
  version: 1150,
  currentRound: 16,
  maxRounds: 19,
  extendedRounds: [14],
  status: "starting",
  completedRound: null,  // "undefined" in the snapshot = field not present
  cardLogLength: 832,
  // Fields not captured by the diagnostic but present in the document
  // (per buildInitialMatchDoc and subsequent writes)
  players: [
    "GNZPc0AWW9tm0M9ULOzKKaSx8LfE",  // p1
    "zFkMnch0fcZHm1GNLTtLfikpR9Yi",   // p4
    "gFxGIF3WRA3IQ5Yj7oCUZ1SeI4kO",   // p2
    "nMUolfN5WAMWnPRGVu78eAfpCWht"    // p3
  ],
  seats: {
    p1: "GNZPc0AWW9tm0M9ULOzKKaSx8LfE",
    p2: "gFxGIF3WRA3IQ5Yj7oCUZ1SeI4kO",
    p3: "nMUolfN5WAMWnPRGVu78eAfpCWht",
    p4: "zFkMnch0fcZHm1GNLTtLfikpR9Yi"
  }
  // Note: The diagnostic did NOT capture these fields. They are inferred from
  // the document schema. UNKNOWN fields are left out.
};

// newData = oldData with ONLY the changed fields updated.
// In Firestore Rules, request.resource.data is the FULL post-write document
// (oldData + the write patch). All other fields are inherited from oldData.
const newData = JSON.parse(JSON.stringify(oldData));
newData.maxRounds = 20;
newData.extendedRounds = [14, 16];
newData.version = 1151;
// updatedAt is a serverTimestamp transform — handled separately by the
// engine, NOT a direct field change in diff().affectedKeys().

const authUid = "GNZPc0AWW9tm0M9ULOzKKaSx8LfE";  // p1's callingUid

// === CEL SIMULATION HELPERS ===
// These helpers simulate CEL's Firestore Rules semantics as closely as possible
// in JavaScript. They are NOT exact (Firestore Rules runs in CEL, not JS),
// but they are the best offline approximation available.

function celIsList(value) {
  // CEL: expr is list
  // In Firestore Rules, this checks the Firestore List type.
  // In JavaScript, arrays and Maps can represent lists.
  // The Firestore emulator maps JS arrays to List type for the
  // document fields and parameters.
  if (Array.isArray(value)) return true;
  return false;
}

function celHasOnly(actualSet, allowedArray) {
  // CEL: set.hasOnly(allowedList)
  // Returns true if every element in actualSet is in allowedList.
  const allowed = new Set(allowedArray);
  for (const item of actualSet) {
    if (!allowed.has(item)) return false;
  }
  return true;
}

function celDiff(oldData, newData) {
  // CEL: newData.diff(oldData)
  // Returns a set of field paths that differ.
  // For the Round-16 case, only top-level fields changed: version, maxRounds, extendedRounds.
  // In Firestore Rules, diff() is computed by the engine, not by JS.
  // The result for Round 16: {version, maxRounds, extendedRounds}
  // (updatedAt is a transform, not in diff).
  const changed = new Set();
  const allKeys = new Set([...Object.keys(oldData), ...Object.keys(newData)]);
  for (const k of allKeys) {
    if (JSON.stringify(oldData[k]) !== JSON.stringify(newData[k])) {
      changed.add(k);
    }
  }
  return changed;
}

function celInList(list, value) {
  // CEL: value in list
  // Equivalent to: list.exists(x, x == value)
  if (!Array.isArray(list)) return false;
  return list.some(x => x === value);
}

function celListSize(list) {
  // CEL: list.size()
  if (!Array.isArray(list)) return 0;
  return list.length;
}

function celListSlice(list, start, end) {
  // CEL: list[start:end]
  // This is a SLICE, not a get. Returns a new list.
  if (!Array.isArray(list)) return [];
  return list.slice(start, end);
}

function celIsInt(value) {
  // CEL: expr is int
  return Number.isInteger(value);
}

// === PREDICATE EVALUATION ===
const results = [];

function evaluate(id, line, expression, pass, note) {
  results.push({ id, line, expression, result: pass ? 'PASS' : 'FAIL', note });
}

// Extract values
const oldRounds = oldData.extendedRounds;
const newRounds = newData.extendedRounds;
const appendedRound = celListSize(newRounds) > 0
  ? newRounds[celListSize(newRounds) - 1]
  : null;

// P01: request.auth != null
evaluate('P01', 1092, 'request.auth != null', authUid != null,
  `authUid = ${JSON.stringify(authUid)}, type = ${typeof authUid}`);

// P02: request.auth.uid in oldData.players
const p02_pass = Array.isArray(oldData.players) && oldData.players.indexOf(authUid) !== -1;
evaluate('P02', 1093, 'request.auth.uid in oldData.players', p02_pass,
  `authUid found in players[${oldData.players.indexOf(authUid)}]`);

// P03: 'maxRounds' in oldData && 'extendedRounds' in oldData && 'version' in oldData
const p03_pass = ('maxRounds' in oldData) && ('extendedRounds' in oldData) && ('version' in oldData);
evaluate('P03', 1094, "'maxRounds' in oldData && 'extendedRounds' in oldData && 'version' in oldData",
  p03_pass, `All three fields present in oldData`);

// P04: ('status' in oldData ? oldData.status != 'complete' : true)
const p04_pass = ('status' in oldData) ? (oldData.status !== 'complete') : true;
evaluate('P04', 1098, "('status' in oldData ? oldData.status != 'complete' : true)",
  p04_pass, `oldData.status = ${JSON.stringify(oldData.status)}`);

// P05: newData.diff(oldData).affectedKeys().hasOnly(['maxRounds', 'extendedRounds', 'version', 'updatedAt'])
const affected = celDiff(oldData, newData);
const p05_pass = celHasOnly(affected, ['maxRounds', 'extendedRounds', 'version', 'updatedAt']);
evaluate('P05', 1099, "newData.diff(oldData).affectedKeys().hasOnly(['maxRounds', 'extendedRounds', 'version', 'updatedAt'])",
  p05_pass, `affectedKeys = [${[...affected].join(', ')}], allowed = [maxRounds, extendedRounds, version, updatedAt]`);

// P06: newData.version == oldData.version + 1
evaluate('P06', 1100, 'newData.version == oldData.version + 1',
  newData.version === oldData.version + 1,
  `${newData.version} == ${oldData.version} + 1 = ${oldData.version + 1}`);

// P07: listsAreValid (oldRounds is list && newRounds is list)
const p07_pass = celIsList(oldRounds) && celIsList(newRounds);
evaluate('P07', 1087, 'oldRounds is list && newRounds is list',
  p07_pass, `oldRounds isArray=${celIsList(oldRounds)}, newRounds isArray=${celIsList(newRounds)}`);

// P08: newData.maxRounds == oldData.maxRounds + 1
evaluate('P08', 1102, 'newData.maxRounds == oldData.maxRounds + 1',
  newData.maxRounds === oldData.maxRounds + 1,
  `${newData.maxRounds} == ${oldData.maxRounds} + 1 = ${oldData.maxRounds + 1}`);

// P09: newRounds.size() == oldRounds.size() + 1
evaluate('P09', 1106, 'newRounds.size() == oldRounds.size() + 1',
  celListSize(newRounds) === celListSize(oldRounds) + 1,
  `${celListSize(newRounds)} == ${celListSize(oldRounds)} + 1 = ${celListSize(oldRounds) + 1}`);

// P10: (oldRounds.size() == 0 ? true : newRounds[0:oldRounds.size()] == oldRounds)
let p10_pass, p10_note;
if (celListSize(oldRounds) === 0) {
  p10_pass = true;
  p10_note = 'oldRounds is empty, short-circuit to true';
} else {
  const slice = celListSlice(newRounds, 0, celListSize(oldRounds));
  p10_pass = JSON.stringify(slice) === JSON.stringify(oldRounds);
  p10_note = `slice = ${JSON.stringify(slice)}, oldRounds = ${JSON.stringify(oldRounds)}, equal = ${p10_pass}`;
}
evaluate('P10', 1123, '(oldRounds.size() == 0 ? true : newRounds[0:oldRounds.size()] == oldRounds)',
  p10_pass, p10_note);

// P11: appendedRound is int
evaluate('P11', 1124, 'appendedRound is int',
  celIsInt(appendedRound),
  `appendedRound = ${appendedRound}, isInt = ${celIsInt(appendedRound)}`);

// P12: appendedRound >= 14 && appendedRound <= 18
evaluate('P12', 1125, 'appendedRound >= 14 && appendedRound <= 18',
  appendedRound >= 14 && appendedRound <= 18,
  `${appendedRound} >= 14 && ${appendedRound} <= 18 → ${appendedRound >= 14 && appendedRound <= 18}`);

// P13: !(appendedRound in oldRounds)
evaluate('P13', 1126, '!(appendedRound in oldRounds)',
  !celInList(oldRounds, appendedRound),
  `${appendedRound} in [${oldRounds.join(', ')}] = ${celInList(oldRounds, appendedRound)}, so ! = ${!celInList(oldRounds, appendedRound)}`);

// === OUTPUT ===
console.log('=== RULE REPRODUCER — Round 16 extendMatchRounds ===');
console.log('');
console.log('Captured inputs:');
console.log('  oldData.version = ' + oldData.version);
console.log('  oldData.maxRounds = ' + oldData.maxRounds);
console.log('  oldData.extendedRounds = ' + JSON.stringify(oldData.extendedRounds));
console.log('  oldData.status = ' + JSON.stringify(oldData.status));
console.log('  newData.version = ' + newData.version);
console.log('  newData.maxRounds = ' + newData.maxRounds);
console.log('  newData.extendedRounds = ' + JSON.stringify(newData.extendedRounds));
console.log('  authUid = ' + authUid);
console.log('  completedRound = 16, reason = SAAYDA');
console.log('');
console.log('=== PREDICATE-BY-PREDICATE EVALUATION ===');
console.log('');
for (const r of results) {
  const status = r.result === 'PASS' ? '✓' : '✗';
  console.log(`${status} ${r.id} | firestore.rules:${r.line} | ${r.result}`);
  console.log(`  Expression: ${r.expression}`);
  console.log(`  Note: ${r.note}`);
  console.log('');
}

const allPass = results.every(r => r.result === 'PASS');
console.log('=== SUMMARY ===');
console.log(`All ${results.length} predicates: ${allPass ? 'ALL PASS' : 'SOME FAILED'}`);
console.log('');
console.log('The Firestore Rules emulator returned: permission-denied');
console.log('  with "evaluation error at L1513:24 for \'update\' @ L1513"');
console.log('');
if (allPass) {
  console.log('=== DIAGNOSIS ===');
  console.log('All 13 visible predicates PASS in the JavaScript simulation.');
  console.log('The Firestore Rules emulator rejects the write.');
  console.log('The discrepancy is UNEXPLAINED by the available evidence.');
  console.log('');
  console.log('RULE FAILURE = UNKNOWN');
  console.log('');
  console.log('Possible causes (not confirmed):');
  console.log('  I. Emulator-specific behavior (documented emulator bug for list ops)');
  console.log('  H. CEL type semantics differ from JS (e.g., is list, in operator)');
  console.log('  C. resource.data includes fields not captured by JS snapshot');
  console.log('  J. Evidence insufficient — need internal emulator CEL logs');
}
