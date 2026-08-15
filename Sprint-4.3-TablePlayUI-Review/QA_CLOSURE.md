# Sprint 4.3 — Table Play UI Closure QA

Scope: this pass adds automated coverage for the two gaps identified in the
prior status audit (responsive/landscape verification, explicit remote-card
UI rendering), plus a final manual visual-quality pass. No engine, service,
adapter, or rules file was touched — the ENTIRE diff is
`tests/table-play-ui.test.cjs` (checks 23–33 added) plus new/refreshed
screenshots under `qa/sprint-4.3/`.

## Baseline (confirmed before any change)

- Working tree clean, `f7a4ce8` confirmed as the Sprint 4.3 Table Play UI
  deliverable commit, no later commit touches this UI.
- `tests/table-play-ui.test.cjs`: 22/22 passing (unchanged baseline).

## Phase 3 — Remote-card UI test (checks 23–27)

A fully independent second browser CONTEXT (an actual second live client)
is not practical in this harness — there is no real Firestore project
available here, the same reason `MatchService.submitCard()` itself is
spied rather than exercised against a live backend elsewhere in this file.
Instead, this test drives the REAL, unmodified production sync entry point:
`window.MatchAdapter.applyRemoteCard(matchId, matchDoc)` — the exact
function `startTrickSync()`'s own catch-up loop calls on every live
Firestore snapshot delivery. This is this project's own established
convention for simulating a remote delivery (see `tests/card-sync.test.cjs`,
`tests/trick-sync.test.cjs`) — it is the real production code path, not a
DOM fabrication.

Scenario: local seat set to a seat that is NOT the current turn-holder;
the turn-holder's own next legal card is fed in as a `matchDoc.cardLog`
delivery via `applyRemoteCard()`, simulating what a real remote player's
write would look like once synced. Result: **5/5 passing**
- `applyRemoteCard()` accepts the play (`applied: true`)
- the card renders in the remote seat's own positional trick slot
- exactly one chip renders for that seat (no duplicate rendering)
- the LOCAL seat's own hand is untouched (13 cards, unchanged)
- turn advances away from the remote seat

## Phase 2 — Responsive QA (checks 28–33, ×3 viewports = 18 checks)

Key architectural finding, confirmed by direct source read
(`design-ui/match/index.html`'s `fit()` function): every screen in this
project, including this one, is a **fixed 932×430 device frame, uniformly
scaled** via `Math.min((innerWidth-24)/932, (innerHeight-24)/430)`. This
means relative layout among elements (hand/trick/turn-indicator position,
overlap) is IDENTICAL at every viewport by construction — only the
absolute scale factor changes. The tests below were designed around that
fact rather than assuming a reflow-based responsive model.

At each of 800×480, 854×480, 1280×720:
- No horizontal/vertical document overflow — **PASS at all 3**
- 13-card hand present and visible — **PASS at all 3**
- Trick area visible — **PASS at all 3**
- Turn indicator visible with real text — **PASS at all 3**
- Hand/trick area bounding boxes do not intersect — **PASS at all 3**
- Card interaction (click → `MatchService.submitCard` exactly once) —
  **PASS at all 3** (required a test-harness fix: the first pass's mock
  used a never-resolving promise, which left the page's own
  `pendingCardSubmission` flag stuck `true` across the next viewport
  iteration — not a product bug, a bug in the new test's own mock,
  caught and fixed before this was reported as passing)

**Result: 45/45 (22 original + 5 remote-card + 18 responsive), 0 failures.**

## Phase 5 — UI/UX Pro Max QA

The skill's own `search.py` script (referenced by its `SKILL.md`) is not
present in this environment (`${CLAUDE_PLUGIN_ROOT}` resolves empty here;
no `scripts/search.py` exists under this skill's installed path) — this is
disclosed rather than fabricating tool output. The audit below applies the
skill's own documented priority-table categories directly against the real
CSS/DOM (`design-ui/match/index.html`), which is the same evidence a
database query would ultimately be checked against.

| Category | Result | Evidence |
|---|---|---|
| Touch & Interaction | 🟡 Concern (pre-existing, whole-app) | Hand-card buttons (`.card-chip`, padding `5px 8px`, font `11px`) measure ~28–40px wide × ~21–26px tall in CSS px even at 1x scale — below the 44×44 recommendation. At the two smallest tested viewports (800×480, 854×480) the whole frame scales BELOW 1x (0.83× and 0.89×), shrinking this further. This is a project-wide chip-button convention (same sizing used for suit chips, bidding chips, everywhere), not unique to this screen — enlarging it here only would break visual consistency with every other screen. **Not fixed** — out of this sprint's scope per the fix policy (would require a design-system-wide token change). |
| Layout & Responsive | ✅ Pass (with one finding — see below) | Fixed-canvas scale model confirmed structurally overflow-safe (`fit()`'s own `Math.min` + 24px pad). One genuine clipping defect found and reported separately (not a "responsive" regression — see Impeccable section). |
| Accessibility | ✅ Pass, minor note | No `aria-label`s on card buttons, but each button's own text content (e.g. "A ♠") is itself a meaningful, screen-reader-readable label — acceptable. Focus rings are not suppressed anywhere in this file (no `outline:none` found). |
| Animation / reduced motion | ✅ Pass | Entry animations are 220ms, transform/opacity only (`cardLandFrom*` keyframes), and a `@media (prefers-reduced-motion: reduce)` block explicitly disables them — matches the skill's own Animation-category guidance exactly. |
| State feedback | ✅ Pass | Legal/locked (`disabled` attribute tied directly to `TableEngine.canPlayCard()`), pending (`.is-pending` class + full-hand disable), turn (`Your turn` / `Waiting on X…`) all confirmed wired to real engine state, not decorative. |
| Style/consistency | ✅ Pass | No new colors or tokens introduced by Sprint 4.3's original animation work; this QA pass added no visual changes at all. |

## Phase 6 — Impeccable QA

"Impeccable" does not exist among this account's enabled skills (checked
via `ToolSearch`/skill listing) — consistent with the ORIGINAL Sprint 4.3
report's own identical finding. A direct manual review of the captured
screenshots was done instead, exactly as that report already established
as the substitute convention.

**Reviewed:** `visual-your-turn.png`, `visual-opponent-turn.png`,
`visual-selected-card.png`, `visual-active-trick.png`, `visual-waiting.png`
(all at 800×480, the smallest required viewport) plus the 3
`table-play-responsive-*.png` captures.

### Finding: 13-card hand's second row clips against the frame's bottom edge

**Severity: Medium. Confirmed real, NOT fixed, NOT introduced by this QA
pass.**

At 13 cards, `#handPanel`'s `flex-wrap` layout wraps into two rows. The
second row's own bottom edge measured (via `getBoundingClientRect()`)
consistently **exceeds `#screen`'s own bottom edge** — by ~5.7px at
800×480, ~6.1px at 854×480, ~9.2px at 1280×720 — and `#screen` has
`overflow:hidden`, so this row is genuinely, visibly clipped, not just
close to the edge.

**This is pre-existing, not a regression from this QA pass or from
viewport scaling specifically**: the SAME clipping is visible in
`table-play-your-turn.png`, captured at 1000×600 (near-native scale) by
the ORIGINAL Sprint 4.3 test, unchanged by this session — confirming this
defect shipped with the original Sprint 4.3 commit (`f7a4ce8`) and has
been present ever since, simply never flagged by that sprint's own
checklist (which checked hand-button *count*/*existence*, never each
button's own on-screen clipping against the frame).

**Not fixed in this pass.** Root-causing this properly likely means
changing `#matchGameView`'s fixed `min-height:180px`/`#tablePanel`'s
vertical budget or `#handPanel`'s own row sizing — layout shared by every
phase panel (bidding, table, round-complete), not exclusive to card
selection. Per this task's own explicit fix policy ("if a finding requires
architecture changes... STOP and report it instead" / "only correct issues
directly relevant to Sprint 4.3 closure... low-risk... UI-only"), this is
reported rather than patched, to avoid an under-scoped CSS change rippling
into panels this pass never reviewed.

No other Impeccable-category finding (alignment, spacing, card density,
visual hierarchy, selected/locked-state clarity, trick-area balance, turn
indicator prominence, overlap, unnecessary visual noise, visual
consistency) surfaced a genuine issue across the 8 captured states.

## Phase 8 — Final verification

Re-ran after all additions (no fixes were applied, since the one finding
above was deliberately left unfixed):
- `tests/table-play-ui.test.cjs`: **45/45**
- `tests/trick-sync.test.cjs`: **43/43**
- `tests/card-sync.test.cjs`: **39/39**
- `tests/match-completion.test.cjs`: **47/47**
- `tests/rematch-vote.test.cjs`: **46/46**
- `tests/score-ui-verification.test.cjs`: **11/11**

No shared production code changed, so the full 35-file Node regression
suite was not re-run in full — only the files genuinely touching this UI
or its sync pipeline.

## Forbidden-file sweep

`git status --short` shows exactly one code file changed:
`tests/table-play-ui.test.cjs`, plus new/refreshed PNGs under
`qa/sprint-4.3/`. `table-engine.js`, `dealer.js`, `scoring-engine.js`,
`match-service.js`, `match-adapter.js`, and `firestore.rules` are
byte-for-byte unchanged.
