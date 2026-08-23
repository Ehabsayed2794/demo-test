# Table Play Card Selection UI — Implementation Report

**Sprint label note:** the task that requested this work called itself "Sprint
4.3 — Table Play Card Selection UI." This repository's own internal sprint
history already used the number **4.3** for an unrelated, already-completed
piece of work (`Sprint-4.3-Review/` — "Trick Resolution Synchronization,"
i.e. `MatchAdapter.applyRemoteTrick()`/`startTrickSync()`). To avoid
overwriting or being confused with that folder, this sprint's own
deliverables live in `Sprint-4.3-TablePlayUI-Review/`, not `Sprint-4.3-Review/`.
No file inside `Sprint-4.3-Review/` was read for logic reuse beyond this one
naming disambiguation, and none was modified.

## Premise correction (disclosed before any code was written)

The task brief's own stated assumptions did not match the real, current
codebase:

| Brief assumed | Actually true (confirmed by direct source read) |
|---|---|
| Hands live at `gameState.seats[mySeatId].hand` | Hands are read via `GameSession.getHand(seatId)` (`design-ui/engine/session.js`), backed by the Sprint E `matches/{matchId}/hands/{seatId}` subcollection. |
| `MatchService.submitCard(cardId)` takes a bare card id | `MatchService.submitCard(matchId, card)` (`design-ui/match-service.js:891`) takes a full `{suit, rank, ...}` card object, with pre-write `TableEngine.canPlayCard()`/`previewPlay()` validation and turn/version guards — built across the (undisclosed-to-this-task) Sprint 4.2/4.2.1/4.2.2 history. |
| `#playerHand`/`#trickArea` do not exist yet | `#handPanel`/`#trickPanel` already exist in `design-ui/match/index.html`, already fully rendered by `renderHand()`/`renderTrick()`, already gated by real turn/phase checks. |
| Click-to-play, double-click prevention, and a turn indicator need to be built | All three already existed: `submitCardPlay()` (duplicate-tap guard via `pendingCardSubmission`), `#tableTurn` ("Your turn" / "Waiting on X…"), and a read-only hand fallback with a "Waiting on X…" message when it isn't the local seat's turn. |
| `--card-bg` is an established design token | It does not exist anywhere in the codebase (confirmed by a repo-wide grep). The closest existing equivalent already in use for card chips is `--panel-hi` (`.card-chip{background:var(--panel-hi);...}`), which this sprint continues to reuse — no new token was invented. |

This is consistent with this project's own established pattern (see
`PROJECT_STATUS_AND_MASTER_PLAN.md` and the Sprint 3.5/3.6/4.0/4.1/4.2
history): sprint briefs describing this vanilla-JS prototype have
repeatedly lagged the actual, already-shipped state of the code.

## What this sprint actually built (the one genuine, verified gap)

`renderTrick()`/`#trickPanel` rendered the current trick as a flat,
unpositioned row, and had no entry animation for a card landing in play.
Per the task's explicit ask for a "positional card layout" and an "entry
animation," and using the **ui-ux-pro-max** skill for animation-timing/
motion guidance (Animation priority tier: 150–300ms, transform/opacity
only — never width/height —, spatial continuity, respect
`prefers-reduced-motion`), this sprint added:

1. **Positional trick layout** (`design-ui/match/index.html`, CSS): each
   trick slot now sits at the same compass anchor (`bottom`/`right`/`top`/
   `left`) as that seat's own avatar in `#matchSeats` (`.seat-p1..p4`),
   pulled inward toward the center of a `position:relative` trick area —
   so a played card visually "belongs" to the seat that played it instead
   of a generic list.
2. **One-shot entry animation** (`@keyframes cardLandFromBottom/Top/Right/
   Left`): a card newly appearing in its slot animates in from its own
   seat's direction (`opacity 0→1`, `translate(±14px) scale(.85)→scale(1)`,
   220ms ease-out) — transform/opacity only, gated by
   `@media (prefers-reduced-motion: reduce)`. `renderTrick()` (JS) now
   tracks which seats already had a visible card as of the previous call
   (`previousTrickSeats`) so the animation class (`is-entering`) is applied
   only to a card that is genuinely new since the last render — never
   replayed on an unrelated re-render tick (verified by test — see below).
3. Diagnostic-only additions to `window.MatchScreenDebug` (matching this
   file's own established convention, e.g. `isBiddingSubmissionPending`):
   `renderTablePanel`, `submitCardPlay`, `isCardSubmissionPending`,
   `getBlockedDuplicateCardAttempts`, `setTableEngineStartedForRound`. No
   production code path calls these; they exist solely so a test can drive
   and observe the real UI state machine without a live Firestore match.
4. A matching `blockedDuplicateCardAttempts` counter in `submitCardPlay()`,
   mirroring the pre-existing `blockedDuplicateSubmissionAttempts` pattern
   from Bidding Controls, so the duplicate-tap guard is directly observable
   by a test instead of inferred from timing.

**Not touched:** `table-engine.js`, `dealer.js`, `scoring-engine.js`,
`bidding-engine.js`, `match-service.js`, `match-adapter.js`, `firestore.rules`
— confirmed by `git diff --name-only` showing only `design-ui/match/index.html`
changed. No game rule, legality check, turn-progression rule, or score
formula was added, removed, or altered.

## Verification

Real Chromium (`tests/table-play-ui.test.cjs`, 22/22 passing), loading the
real `match/index.html`, driving the real `TableEngine`/`GameSession`
directly (`TableEngine.initState()`, `TableEngine.emit({type:"PlayCard",...})`
— the same test-bypass `table-engine.js`'s own header comment sanctions for
automated tests, since real per-turn timers would make a test impractically
slow). Only `MatchService.submitCard` is replaced with a spy, because a real
network write has no meaning without a live Firestore match; the
service-layer contract for `submitCard()` itself is already covered by
`tests/submit-card.test.cjs`/`tests/card-sync.test.cjs`.

Confirmed by the test, against the real DOM:
1. `#handPanel` renders exactly the real 13-card `GameSession.getHand()`
   hand as interactive buttons when it's the local seat's turn.
2. Every hand button's `disabled` state matches `TableEngine.canPlayCard()`
   exactly — the UI never recomputes or diverges from engine legality.
3. Clicking a legal card calls `MatchService.submitCard(matchId, card)`
   exactly once, with a full card object (real `suit`/`rank` fields), not a
   bare id.
4. The whole hand disables immediately on submission; the submitted chip
   gets the `.is-pending` style; `isCardSubmissionPending()` reports true.
5. A second submission attempt while pending is blocked and counted
   (`blockedDuplicateCardAttempts` increments) — no second network call.
6. After the write resolves, the pending state clears and the hand
   re-enables.
7. A real `TableEngine.emit(PlayCard)` renders the played card in its own
   seat's positional trick slot; all four slots use the positional layout.
8. The newly-played card gets the entry-animation class on its first
   render, and a later, unrelated re-render of the same unchanged trick
   does **not** replay it.
9. Turn indicator text updates correctly ("Your turn" / "Waiting on
   \<name\>…") as the engine's real turn advances.
10. When it's not the local seat's turn, the hand remains visible
    (read-only, non-button chips) with an explicit "Waiting on …" message
    — never a blank panel.
11. Zero console/page errors throughout.

Screenshots (`qa/sprint-4.3/table-play-your-turn.png`,
`table-play-waiting-state.png`) confirm the positional layout visually:
a played card appears on the same side as its player's seat label, red
suits render in the existing shared red, and no text overlaps — reviewed
directly (the task's requested "Impeccable" skill was checked against
`ListSkills` and is not among this account's enabled skills, so this visual
check was done directly rather than fabricating a call to a skill that
doesn't exist).

Full non-emulator Node regression: 29/29 test files pass (6 Firestore
Rules Emulator files skip cleanly, as in every prior sprint in this
session, because no emulator is running in this container — unrelated to
this change, which touches no Firestore-facing code).

## Explicit stop

Per the task's own instruction, this sprint stops here. It does not
continue into Replay Animation, Emoji Chat, or Spectator Mode.
