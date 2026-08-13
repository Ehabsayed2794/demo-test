# Test Checklist — Table Play Card Selection UI

Test file: `tests/table-play-ui.test.cjs` — real Chromium
(`/opt/pw-browsers/chromium-1194/chrome-linux/chrome`), real
`design-ui/match/index.html`, real `TableEngine`/`GameSession`/
`MatchScreenDebug`. Only `MatchService.submitCard` is replaced with a spy
(no live Firestore match exists in this test; the real function's own
contract is covered elsewhere — see below).

Run: `node tests/table-play-ui.test.cjs` — **22/22 passing**, 0 console/page
errors. Full log: `qa/sprint-4.3/test-log.txt`.

| # | Check | Result |
|---|---|---|
| 1 | Real `TableEngine` reaches `PLAY` phase with a real turn seat | PASS |
| 2 | `#tablePanel` is visible | PASS |
| 3 | Turn indicator shows "Your turn" when `localSeatId === state.turn` | PASS |
| 4 | `#handPanel` renders exactly the real 13-card `GameSession` hand as interactive buttons | PASS |
| 5 | Every hand button's `disabled` state matches `TableEngine.canPlayCard()` exactly (no UI-side legality drift) | PASS |
| 6 | Clicking a legal card calls `MatchService.submitCard(matchId, card)` exactly once | PASS |
| 7 | The call passes a full card object with real `suit`/`rank` fields (not a bare `cardId` string) | PASS |
| 8 | The whole hand disables immediately after submission (pending state) | PASS |
| 9 | `isCardSubmissionPending()` reports `true` while the write is in flight | PASS |
| 10 | The submitted chip carries the `.is-pending` visual class | PASS |
| 11 | A second submission attempt while pending increments the duplicate-attempt guard counter | PASS |
| 12 | After the write resolves, pending clears and the hand re-enables | PASS |
| 13 | `TableEngine.emit(PlayCard)` was accepted (not rejected) | PASS |
| 14 | The played card renders in its own seat's positional trick slot | PASS |
| 15 | All four trick slots use the positional layout (mirrors `#matchSeats`' compass anchors) | PASS |
| 16 | The newly-played card gets the entry-animation class on its first render | PASS |
| 17 | A later re-render of the same unchanged trick does NOT replay the entry animation | PASS |
| 18 | Turn advances to the next seat after a real play (engine-driven, not UI-guessed) | PASS |
| 19 | When it's no longer the local seat's turn, the turn indicator shows a "Waiting on …" message | PASS |
| 20 | Hand remains visible (read-only, non-button chips) while waiting on another seat | PASS |
| 21 | An explicit "Waiting on …" message is shown | PASS |
| 22 | No console/page errors throughout the whole scenario | PASS |

## Full regression (unaffected areas)

Ran every file in `tests/` (35 total):
- **29/29 non-emulator files pass** (includes `tests/score-ui-verification.test.cjs`,
  `tests/submit-card.test.cjs`, `tests/card-sync.test.cjs`,
  `tests/table-engine-foundation-fix.test.cjs`, `tests/trick-sync.test.cjs`,
  and all others).
- **6 Firestore Rules Emulator files skip cleanly** (`hand-sync.rules-emulator*.test.cjs`,
  `matches-update-dispatch.rules-emulator.test.cjs`,
  `sprint-a-write-paths.rules-emulator.test.cjs`) — no emulator is running in
  this container, same as every prior sprint in this session. This change
  touches no `firestore.rules`-facing code, so there is no reason to expect
  these would be affected.

## Manual visual QA

- `qa/sprint-4.3/table-play-your-turn.png` — local seat's turn: hand fully
  interactive, one already-played card shown in its owning seat's
  positional slot, turn indicator reads "Your turn."
- `qa/sprint-4.3/table-play-waiting-state.png` — same round, one trick
  further: turn indicator reads "Waiting on You" (per this project's
  existing player-roster data, seat `p1`'s display name is literally
  "You" — confirmed via `GameSession.getPlayer`, not a bug), hand shown
  read-only.
- Reviewed directly for: no text overlap, positional layout correctly
  mirroring each seat's compass side, red suits using the existing shared
  red, no new colors/tokens. ("Impeccable" skill checked against
  `ListSkills` — not present among this account's enabled skills; not
  invoked, per instruction not to fabricate a call to a nonexistent tool.)

## Forbidden-file sweep

`git diff --name-only` against the base commit shows exactly one file
changed: `design-ui/match/index.html`. `table-engine.js`, `dealer.js`,
`scoring-engine.js`, `bidding-engine.js`, `match-service.js`,
`match-adapter.js`, and `firestore.rules` are byte-for-byte unchanged.
