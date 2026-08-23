# Changelog — Table Play Card Selection UI

**Naming note:** this repo's own sprint history already used "Sprint 4.3"
for an unrelated, already-completed piece of work (`Sprint-4.3-Review/` —
Trick Resolution Synchronization). This folder covers the *separate*,
externally-requested "Table Play Card Selection UI" task, which the request
also labeled "Sprint 4.3." See `docs/implementation/TablePlayCardSelectionUI.md`
for the full disambiguation and report.

## Premise correction (see the implementation doc for the full table)

The task brief assumed hands live at `gameState.seats[mySeatId].hand`, that
`MatchService.submitCard()` takes a bare `cardId`, and that
`#playerHand`/`#trickArea`/click-to-play/turn indicators did not exist yet.
All false: hands come from `GameSession.getHand(seatId)`,
`MatchService.submitCard(matchId, card)` already takes a full card object
with pre-write engine validation, and the hand/trick/turn-indicator UI
(`#handPanel`/`#trickPanel`/`#tableTurn`, `submitCardPlay()`'s duplicate-tap
guard, the "Waiting on X…" read-only fallback) was already built across
this project's own prior Sprint 4.2/4.2.1/4.2.2 work. `--card-bg`, the token
the brief named explicitly, does not exist anywhere in the codebase — the
existing `--panel-hi` token (already used for `.card-chip`'s background)
was kept, per the brief's own "reuse existing tokens" constraint.

## Added

- **`design-ui/match/index.html` — positional trick layout.** `#trickPanel`
  is now a `position:relative` area; each trick slot (`.trick-slot-p1..p4`)
  sits at the same compass anchor (`bottom`/`right`/`top`/`left`) as that
  seat's own avatar in `#matchSeats`, instead of a flat row.
- **`design-ui/match/index.html` — one-shot card-landing animation.**
  `@keyframes cardLandFromBottom/Top/Right/Left` (opacity + transform only,
  220ms ease-out, direction matching the seat that played the card), gated
  by `@media (prefers-reduced-motion: reduce)`. `renderTrick()` now tracks
  which seats already had a visible card as of the previous render
  (`previousTrickSeats`) so the animation plays exactly once per card, never
  replayed on an unrelated re-render.
- **`design-ui/match/index.html` — diagnostic-only additions to
  `window.MatchScreenDebug`**, matching this file's own established
  convention (e.g. `isBiddingSubmissionPending`): `renderTablePanel`,
  `submitCardPlay`, `isCardSubmissionPending`,
  `getBlockedDuplicateCardAttempts`, `setTableEngineStartedForRound`. No
  production code path calls these.
- **`design-ui/match/index.html` — `blockedDuplicateCardAttempts` counter**
  in `submitCardPlay()`, mirroring the pre-existing Bidding Controls
  `blockedDuplicateSubmissionAttempts` pattern, so the existing duplicate-tap
  guard is directly observable by a test.
- **`tests/table-play-ui.test.cjs`** (new, 22/22 passing, real Chromium):
  see TEST_CHECKLIST.md for the full scenario list.
- **`docs/implementation/TablePlayCardSelectionUI.md`**: full implementation
  report, including the premise-correction table and skill usage
  (`ui-ux-pro-max` for animation/layout guidance; "Impeccable" was checked
  against `ListSkills` and does not exist among this account's enabled
  skills, so a direct visual review of the QA screenshots was done instead
  of fabricating that skill call).

## Not changed

- `table-engine.js`, `dealer.js`, `scoring-engine.js`, `bidding-engine.js`,
  `match-service.js`, `match-adapter.js`, `firestore.rules` — byte-for-byte
  unchanged (confirmed via `git diff --name-only`).
- No game rule, legality check, turn-progression rule, or score formula was
  added, removed, or altered.
- No new CSS custom property/color was introduced.

## Testing

- `tests/table-play-ui.test.cjs`: 22/22 passing (real Chromium, real
  `TableEngine`/`GameSession`, `MatchService.submitCard` spied only —
  service-layer coverage already exists in `tests/submit-card.test.cjs`/
  `tests/card-sync.test.cjs`).
- Full non-emulator Node regression: 29/29 test files pass. 6 Firestore
  Rules Emulator files skip cleanly (no emulator running in this
  container — unrelated to this change, which touches no Firestore-facing
  file).
- Two QA screenshots (`qa/sprint-4.3/table-play-your-turn.png`,
  `table-play-waiting-state.png`) reviewed directly for visual correctness
  (positional layout, no overlap, consistent tokens).

## Closure QA pass (follow-up, same sprint)

Closed the two coverage gaps identified by a later status audit — see
`QA_CLOSURE.md` for the full write-up. Summary:
- Added an explicit remote-card UI test driving the real
  `MatchAdapter.applyRemoteCard()` sync entry point (5 new checks).
- Added automated responsive verification at 800×480/854×480/1280×720
  landscape viewports (18 new checks): no overflow, hand/trick/turn
  indicator visibility, no overlap, and real click-to-play interaction, at
  each viewport.
- `tests/table-play-ui.test.cjs` now 45/45 passing (was 22/22); still only
  this one test file changed — no engine/service/adapter/rules file
  touched.
- UI/UX Pro Max category audit (touch target, responsive, accessibility,
  animation) + a manual visual review (Impeccable is not present among
  this account's enabled skills, same finding as the original sprint)
  performed. One genuine, PRE-EXISTING (shipped with the original `f7a4ce8`
  commit, not introduced by this pass) defect found: the 13-card hand's
  second wrapped row clips below `#screen`'s own bottom edge at every
  tested viewport. Reported, not fixed — a proper fix touches shared
  layout (`#matchGameView`/`#tablePanel` vertical budget) used by every
  phase panel, outside this pass's UI-only/low-risk scope.
