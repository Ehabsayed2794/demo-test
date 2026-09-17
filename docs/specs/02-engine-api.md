# Phase 0 spec — Engine API contracts (Kotlin port surface)

Source: `design-ui/engine/*.js` export objects (`global.*` / `window.*`).
Pure logic, no DOM/Firestore inside — the port keeps that boundary:
one Kotlin module with zero Android imports.

Layering (must survive the port): Engine → GameSession → MatchAdapter
→ MatchService → UI. Engines never touch Firestore; UI never duplicates
engine rules.

## 1. Cards (`Cards`) — `engine/cards.js`

Constants: `SUITS` (Sans5 > Spades4 > Hearts3 > Diamonds2 > Clubs1),
`RANKS` (2..A weakest→strongest), `DECK_SUITS` (real 4, no Sans).
Functions: `createCard(suitKey, rank, owner?)` → card object
`{id, suit, rank:{v,s}, displayName, value, owner, played}`;
`compareForSort(a, b)` (strongest suit first, then rank).
NOTE: SUITS/RANKS tables are triplicated (also in bidding/table
engines) — port consolidates to ONE table (debt R10 resolved by port).

## 2. Deck (`Deck`) — `engine/deck.js`

Constructor + `shuffle(rng?)` (Fisher–Yates, injectable RNG) /
`draw()` (throws when empty — fail loud) / `remaining()` /
`reset()` (fresh 52, new objects). `Dealer.dealHands()` → 4×13 unique,
pre-sorted via `compareForSort`.

## 3. BiddingEngine — `engine/bidding-engine.js`

`initState()` / `emit(intent)` / `getState()` / `canSubmit(intent)`
(read-only projection of `emit` legality — never mutates) /
`isFastRound(round)` / `fixedTrumpFor(round)` (14=Sans,15=Spades,
16=Hearts,17=Diamonds,18=Clubs, extensions repeat the cycle).
Intents: `SubmitDashCallDecision` / `SubmitAuctionBid` / `SubmitConfirmCall`
/ `SubmitFinalEstimate`. Sub-phases: DASH → AUCTION → CONFIRM →
ESTIMATES → DONE. Fast rounds skip to ESTIMATES; 8+ triggers Super-Call
CONFIRM + pre-super re-estimation; completion assigns Caller/With
(first highest bidder = Caller — `tests/with-grant-paths.test.cjs`
A1/A2, `tests/fast-super-reset.test.cjs` S1–S3).

## 4. TableEngine — `engine/table-engine.js`

`initState()` / `emit(cardPlay)` / `resolveTrick()` / `getState()` /
`canPlayCard()` / `previewPlay()` / `restoreHand()`.
Phases PLAY ⇄ RESOLVING; 13 tricks/round; follow-suit + trump rules;
Caller leads trick 1; `tricksWon` tally. Restores from round config,
never from mocks (`table-engine-foundation-fix`).

## 5. ScoringEngine — `engine/scoring-engine.js`

`calculateRoundScore(input)` → per-seat deltas + breakdown
(`{callerId, withPlayers, bids, ...}`); Caller/With ±10 generic;
`applyRoundResult` / `calculateMatchScore` / `calculateTeamScore`
(unused solo) / `computeWinner(scores)` (ties → ALL max seats) /
`computeRoundExtension` (Super-Call/Sa'ayda eligibility) /
`computeRiskPlayerId` / `calculateClassicScore` + `classicRoleFor`
(Classic mode). NATIVE scoring deltas come from `04-scoring.md`
(bid²/dash/sole/risk-ladder), not from re-deriving here.

## 6. GameSession — `engine/session.js`

In-memory store + persistence + remote mirror. Groups:
lifecycle (`init/reset/get`); mode; players (`getPlayers/getAIPlayers`
— AI is flag-only, no bot logic); room; dealer (`get/set/rotate`);
round (`get/set/next`); turn; hands (`dealNewHands/ensureHandsDealt/
getHands/getHand/setHand/hasDealtHands`, authority modes local vs
firestore); play/bidding state (init/update/valid-for-round,
`record*`, `completeBidding/completeRound`, clear); scores
(`get/setMatchScores`, `recordRoundResult`, `getLastRoundResult`,
`isMatchComplete`, winner ids); remote mirror (`subscribe/
unsubscribe/getRemoteMatch*/isSubscribed/onRemoteMatchUpdate`).
MOCK remnants inside (`mockPlayers` with fake names/ranks/coins,
`teamScores`, `getAIPlayers`) are NOT ported — real profile service
or honest omission instead.

## 7. Port rules

- Intent in → new state out; no hidden I/O; rejections are typed
  (`{rejected:true, reason}`), never silent.
- `canSubmit`/`canPlayCard`/`previewPlay` stay pure and mirror `emit`.
- Engine state is round-scoped; cross-round memory lives in GameSession.
- Every JS-pinned case (see `04-scoring.md` + test list in §8 of the
  migration plan) becomes a JUnit case with the SAME numbers.
