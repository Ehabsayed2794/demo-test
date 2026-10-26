# Phase 0 spec — Firestore transaction catalog (rules-fidelity gate)

Source: `design-ui/match-service.js` + `room-service.js`, enforced by
`firestore.rules`. Firestore transactions auto-retry on contention —
EVERY branch below must be re-entrant and idempotent (losers take
`ALREADY_*` no-ops, never partial writes). The Kotlin port replicates
these shapes EXACTLY; any field deviation is denied by the unchanged
rules (prove with the Phase 3 emulator matrix).

Conventions: `version` is always exactly old+1; every write carries
`updatedAt: serverTimestamp()`. JS-guard rejections (`bidError` codes)
happen BEFORE any write; rules-layer denials surface as
`PERMISSION_DENIED` with `evaluation error at L…` text.

## 1. `startMatch(roomId)` — `match-service.js:573-585`

ONE transaction across `rooms/{roomId}` + new `matches/{id}`:
create match doc (`buildInitialMatchDoc`: status `starting`,
`currentRound: 1`, dealer=turn=creator, seats p1..pn positional, `version:
1`, `biddingOpen: true`, null `bids`, `lastBidSeat: null`, `cardLog: []`,
`lastCardSeat: null`, `cardPhase: null`, `biddingLog: []`, `maxRounds:
18`, `extendedRounds: []`, `gameState: {initialized:false, dealtRound:0}`,
players+roomId) AND update room `{status:"in_game", matchId, updatedAt}`.
Idempotent: room already has `matchId` → return it, write nothing.
Rejects: room missing / not all-ready (re-checked here, not just RoomService).

## 2. Bids / actions / cards (per-write, `arrayUnion` single entry)

- `submitBid`: `{bids, biddingOpen, version, lastBidSeat, updatedAt}`.
- `submitBiddingAction`: `{biddingLog: arrayUnion(entry), version, updatedAt}`
  (`:1421-1427`). Entry carries its own `round` tag (roundLifecycle A/B).
- `submitCard`: `{cardLog: arrayUnion(entry), lastCardSeat, turn, cardPhase, version, updatedAt}` (allowlist locked — trick-sync).
- Opening-turn publication (round 1 / fresh rounds): `{turn, cardPhase:"PLAY", version, updatedAt}` (`:985-991`) — required before the first card because rules reject a card write whose OLD turn is null.
- Rejections: `ALREADY_BID`, `BIDDING_CLOSED`, `NOT_YOUR_TURN`, `STALE_GAME_STATE` (version moved under the read), legality via engine `canSubmit`/`previewPlay` pre-write.

> **T-09 amendment (2026-10-26 — pointer; the JS body above is unchanged).** In a **fast round** (14–18 FULL, 6–10 MINI) a Super Call CONFIRM is the one `submitBiddingAction` write that is **not** append-only. The same transaction appends the confirmation to `biddingLog`, **replaces** `bids` with `{callerSeat: confirmedTricks}` (every other seat's declaration is wiped), sets `biddingOpen: true` and `lastBidSeat: null`, bumps `biddingCycle` by exactly one, and bumps `version` — `MatchService.submitBiddingAction`'s fast branch, shape-gated by `firestore.rules`' fast-confirm accept-list. A higher re-declaration re-opens CONFIRM and repeats the wipe. `biddingCycle` is `0` at deal/round advance and `+1` per confirm, so `bids` holds the **active** cycle only; `submitBid` carries the caller's `expectedCycle` and refuses a mismatch as `STALE_CYCLE`. Ordinary (normal-round) CONFIRMs stay append-only. Restore: `MatchAdapter.restoreFastBiddingCycle` rebuilds the active cycle from `biddingCycle` + the last CONFIRM + `bids` by replaying through the real engine, and fails closed by name (`FAST_CYCLE_UNPERSISTED`, `CYCLE_LOG_MISMATCH`, `CYCLE_BIDS_MISMATCH`) instead of reinterpreting a pre-T-09 document. A document without the field reads as cycle 0 (no migration). Archived rounds: `Settlement.replayFastSuperCallChain` requires the last caller's slot to equal its confirmed number; a lower value is the pre-T-09 archive shape and is refused, never reinterpreted (a pre-T-09 archive whose slot equals the confirmed number is indistinguishable and settles under the T-09 rules — documented limitation). `TimeoutResolution` writes the same reset and refuses a cycle/log disagreement as `HISTORY_CORRUPT`.

## 3. `advanceToNextRound(matchId, round)` — `:1568-1682`

Guards (no write): `MATCH_NOT_FOUND`, non-player, `MATCH_ALREADY_COMPLETE`
(status check), `ALREADY_ADVANCED` (currentRound ≠ round),
`ROUND_NOT_COMPLETE` (< 52 round-tagged plays). Else ONE transaction:
`roundArchive/{round}` create `{round, matchId, cardLog:[52], biddingLog:[round window]}` (deterministic id, write-once) AND parent update
`{currentRound+1, dealer: nextDealerUid (atomic rotation), version+1,
biddingOpen:true, bids: all-null, lastBidSeat:null, turn:null,
cardPhase:null, cardLog:[], biddingLog:[], updatedAt}`.
Racing losers are denied at commit (create-exists + stale base) and MUST
re-read into `ALREADY_ADVANCED` — on runtimes without tx retry, treat a
rules denial + authoritative round+1 + valid archive as convergence
(same class as the closed emulator-only races).

> **T-09 pointer (2026-10-26):** this advance also writes `biddingCycle: 0` — the next round opens in declaration cycle 0 — and `firestore.rules`' `isValidRoundAdvance` rejects any advance that carries the field at a non-zero value. See the T-09 amendment in §2.

## 4. `endMatch` — `:~1770-1910`

Same archive pattern; parent update `{status:"complete", winnerIds,
finalScores, completedRound, version+1, cardLog:[], biddingLog:[],
updatedAt}`. Consistency-only: `winnerIds` must equal the max-score
seats (INVALID_RESULT otherwise) — wrong-but-consistent scores are
ACCEPTED by design (ranked blocker, re-accept explicitly). Terminal:
`ALREADY_COMPLETE` / `MATCH_ALREADY_COMPLETE` no-ops; advance/extend
after completion are no-ops, never bumps.

## 5. `extendMatchRounds` — `:~1740-1770`

`{maxRounds+1, extendedRounds: append(round), version+1, updatedAt}`.
Eligibility: fast-round Super Call (different trump) or final-round
Sa'ayda; rounds 14–18 window only; `ALREADY_EXTENDED` idempotent.
Any-client model (dealer-only rejected — no single point of failure).

> **Game Type amendment (2026-10-06, GM2/GM3 — pointer, spec body unchanged).** The "rounds 14–18" window above is the **FULL** type. **MINI** uses rounds **6–10** and permits **exactly one extension** (a second returns `ALREADY_EXTENDED`), enforced both in `MatchService.extendMatchRounds` and in `firestore.rules`' `isValidRoundExtension()`. See `docs/rules/CANONICAL_RULES.md` **Amendment A2** and epic **E7**. This line documents the JS reference implementation, which is Full-only.

## 6. `dealRound` — `:~1991-2015`

ONE transaction: `hands/p1..p4` set (13 cards each, 52 unique,
`{seatId, round, cards}`) AND parent `gameState: {initialized:true,
dealtRound: round}`. Write authority separated from read privacy
(opponent reads denied). `ALREADY_DEALT` idempotent; forward-only
round progression.

## 7. Rematch — `:2189-2408`

`rematchVote/current` create (seats verbatim, all-null votes) → per-seat
immutable votes → `ALL_YES` → create new match (seats verbatim,
`rematchOfMatchId` link, fresh round 1) + vote
`{NEW_MATCH_CREATED, newMatchId}`. `NO` → `FAILED_NO` immediately;
30s deadline → `FAILED_TIMEOUT`. Vote races are exactly-once.

## 8. Room lifecycle — `room-service.js`

`createRoom` (6-char unambiguous code, collision-checked, auto-ID
fallback), `joinRoom`/`leaveRoom` (codes case-insensitive, legacy IDs
pass through), `setReady` (last-ready triggers `startMatch`; creator
finishes last by design), capped at 4, idempotent. `transferHost` /
`closeRoom` are UNIMPLEMENTED stubs — port as explicit TODOs or real
features, never silent no-ops.
