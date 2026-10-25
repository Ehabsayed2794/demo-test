# 2026-10-24 — F1 v2: the mixed Dash-Call + all-pass auction is a valid General Pass

**Status: RECORDED (documentation only).** Source: the owner's ruling of 2026-10-24 (Option A — DECIDED,
not open), plus the same-day clarification of the strict two-Dash maximum. This record documents the
ruling and where it is enforced; it decides nothing new.

Labels: **[D]** owner-confirmed.

## 1. The ruling [D]

When one or more players declare a Dash Call in the pre-bidding phase and every remaining active auction
participant passes without anyone bidding, that **IS a valid General Pass**:

- cancel the round, deal fresh, advance to the next round number;
- apply the multiplier ladder unchanged (Normal ×2 → ×4 → ×6 → ×8; Classic has no multiplier).

The owner explicitly **REJECTED** introducing a caller-less SANS round for this shape — do not propose it.
The owner accepted the redeal-farm consequence knowingly; anti-farm limits are a NEW owner decision, not a
defect to fix unilaterally.

## 2. The strict two-Dash maximum [D]

The required persisted window for an N-seat table (N = 1..4): N Dash answers (distinct seats, one per seat,
all tagged the closing round) of which exactly k carry `declaredDashCall = true` with
`0 <= k <= min(2, N-1)`; then exactly N−k auction passes, distinct seats, none of them a declared dasher.
Total entries **2N − k** (four seats: 8 / 7 / 6 at k = 0 / 1 / 2) and **zero cards committed**. k < N
because an all-dashing table ends in SANS, never a General Pass.

Four contract cases:

1. A persisted window claiming **three** declared Dashes is REJECTED with a NAMED reason — never a silent
   clamp: `TOO_MANY_DASH_CALLS` in the service proof, `HISTORY_CORRUPT` (naming the declared-Dash count) in
   the functions replay validation, and a deny in `firestore.rules`.
2. A third Dash **ATTEMPT** after two effective ones is ineffective: it persists
   `declaredDashCall = false` (normalised ONCE at the service's write boundary, and at the resolver's own
   write in `TimeoutResolution`), and that seat stays eligible in the auction.
3. Two effective Dashes + a pass from every remaining active seat **ACCEPTS**.
4. Replay excludes exactly the two effective dashers.

## 3. Where it is enforced (as implemented on `agent/f1-dash-pass-general-pass`)

- Engine (`Bidding.kt`, unchanged — already correct): `MAX_DASH_CALLS = 2`; the reducer converts an
  over-cap declared attempt to a PASS and keeps the seat active.
- Services: `normaliseDashCallEntry`/`effectiveDashCallCount` (MatchShapes) applied once at
  `MatchService.submitBiddingAction`'s write; `generalPassWindowDefect` generalises the window proof to
  2N−k with the named `TOO_MANY_DASH_CALLS` defect; `Reasons.TOO_MANY_DASH_CALLS` surfaces it.
- firestore.rules: `isAllPassBiddingLog` proves the mixed shape (size-dispatched: the k=0 branch is the
  pre-F1 proof verbatim; the mixed branch counts k, locates the dasher positions, and proves the pass block
  distinct and disjoint from the dashers). The layout is forced by the rules engine's expression budget —
  the match-update allow chain sits ~16 expressions under the per-request 1000-expression cap.
- Functions: `TimeoutResolution` normalises its bot dash write from the replayed state's effective count and
  refuses a three-declared document by name before replaying.
- App: the ViewModel advances mixed windows through the same store path and reports the named rejection;
  the test fake's write boundary mirrors the service's normalisation.

## 4. The pre-F1 refusal is repealed

The 2026-10-23 record's A8-F1 "unprovable window" refusal (UNSUPPORTED_GENERAL_PASS_SHAPE for any declared
dash) applied only to k ≥ 1 windows because the proof could not derive the ladder from a shrunken pass
block. The owner has now ruled the mixed shape valid, so that refusal survives only for genuinely malformed
windows (k ≥ 3, wrong entry counts, duplicate seats, dasher passes, foreign round tags, cards committed).
