# Sprint 5 — Trick Resolution & Round Completion Display: Final Report

**Scope authorized:** "READY PORTION ONLY" — trick resolution feedback (A), trick winner
display (B), final trick/round completion detection (C), Round Complete UI (D), round
score display (E), match score display (F), clear transition from active play → round
complete state (G). Sprint ends at: **ROUND 1 COMPLETE → SCORE SHOWN → STOP.**
Round 2 / multiplayer round transition is explicitly OUT OF SCOPE (confirmed hard
blocker, reported in the prior discovery pass — not touched this sprint).

---

### Implementation

All work is contained in `design-ui/match/index.html`. No engine, service, adapter, or
rules file was modified.

1. **Last-trick display** — `renderLastTrickInfo(state)` reads `TableEngine.getState().lastTrick`
   (already populated by the engine's own `resolveTrick()`, unmodified) and renders
   "Last trick won by <player> (<card>)" into a new `#lastTrickInfo` element, sibling to
   the existing trick/hand panels. Purely a read of already-computed engine state — no
   winner logic duplicated in the UI.

2. **Round Complete panel** — a new `#roundCompletePanel` element (sibling to
   `#tablePanel`), populated by `renderRoundComplete()` only when
   `TableEngine.getState().phase === "DONE"`. It reads exclusively from two
   already-existing, already-populated public accessors:
   - `GameSession.getLastRoundResult()` — written by `ScoringEngine.applyRoundResult()`,
     called internally by `TableEngine.resolveTrick()` the instant the 13th trick
     resolves. Supplies trump, caller, multiplier/next-multiplier, per-seat
     estimate/tricks-won, per-seat score deltas, and the Sa'ayda flag.
   - `GameSession.getMatchScores()` — the running match totals the same call already
     updates.

   No score, delta, estimate-success, multiplier, or Sa'ayda value is computed or
   re-derived by the UI — every field is a direct read of the existing result object.
   If `getLastRoundResult()` ever returns null at the moment `phase` reaches `DONE`
   (a theoretical timing gap), the panel renders nothing rather than fabricating a
   placeholder score — an explicit, honest empty state.

3. **Genuine architectural bug found and fixed (in scope, `match/index.html` only):**
   during browser verification, the Round Complete panel initially never appeared even
   though the engine had genuinely reached `phase: "DONE"`. Root cause: `startTrickSync()`/
   `startTurnSync()` (started once, the first time bidding reaches `DONE`, from inside
   the main `subscribeToMatch` render callback) register their own
   `MatchService.subscribeToMatch()` listener *after* the render callback in that
   match's listener list. Every Firestore delivery is dispatched to listeners in
   registration order, so the render callback's `renderTablePanel()` call always ran
   **before** that same delivery's card/trick update had been applied to `TableEngine` —
   the render reflected the *previous* delivery's engine state, one delivery behind.
   This never self-corrects once no further delivery arrives (the round's own final
   trick), so the completion screen simply never appeared.

   Fix: after the existing synchronous `renderTablePanel()` call, queue a microtask
   (`Promise.resolve().then(...)`) that re-renders once `tableEngineStarted` is true.
   Because all of a delivery's listener callbacks (including the trick/turn sync ones)
   run synchronously within the same dispatch, the microtask always fires after every
   listener for that delivery has finished mutating `TableEngine`, so it always reflects
   that delivery's true final state. This does not reorder any subscription (which would
   risk applying a remote card against a not-yet-`initState()`'d `TableEngine`) — it only
   defers a second, idempotent render call. Verified via a live-browser repro (bug
   reproduced with the fix removed, confirmed absent with it applied).

### Architecture

```
TableEngine.resolveTrick()  (unmodified, engine-authoritative)
    │  internally calls ScoringEngine.applyRoundResult()
    │  on the round's final trick
    ▼
GameSession.getLastRoundResult() / getMatchScores()   (unmodified public accessors)
    │
    ▼
match/index.html: renderRoundComplete()  (pure presentation, no logic)
    │
    ▼
#roundCompletePanel (DOM)
```

Every synchronized client independently replays the same `cardLog` through the same
`TableEngine.resolveTrick()` call (via `applyRemoteCard()`/`applyRemoteTrick()`'s
existing catch-up loop, Sprint 4.3), which internally calls the same `ScoringEngine`
functions — producing identical round-result and match-score state on every client with
**zero new Firestore writes and zero new sync channel**. No authority was duplicated:
the UI never computes a winner, a score delta, or a completion state itself.

### Testing

- **Regression baseline (going in):** 1123 (940 across `tests/*.test.cjs` + 183 in
  `tests/rules-simulation.test.js`).
- **Regression after this sprint:** re-ran every test file — **1123 passed, 0 failed.**
  Unchanged, as expected: no engine/service/adapter/rules file was touched.
- No new Node-level test file was added this sprint (all new logic is pure DOM
  presentation of already-tested engine/session state) — verification is the real
  Playwright browser suite below, which is the appropriate tool for DOM-rendering
  correctness.

### Browser QA

Real headless Chromium (Playwright), local static server, fake-but-transactional
Firestore harness (same pattern as every prior sprint this session), `window.__submitCardAsRemoteSeat()` used to simulate the other 3 seats' plays. All scenarios drove
bidding to a real `DONE` state, then played a full round (13 tricks) via a mix of one
genuine local play (this client) and remote-simulated plays for the other seats.

**37 checks passed, 0 failed.**

| Scenario | Result |
|---|---|
| A — Trick resolution feedback (played card appears, hand/log/turn update) | PASS |
| B — (covered inside A/L) each play recorded, trick panel updates | PASS |
| C — Final trick / round completion detected by real engine state (`phase === "DONE"`, `lastTrick.winnerId` populated), never assumed by a UI-side trick count | PASS |
| D — Round Complete panel visible only once `phase === "DONE"`; shows Trump/Caller fields, a score tile per seat with a recorded delta, and the correct ROUND COMPLETE / SA'AYDA badge text — all read from `getLastRoundResult()`/`getMatchScores()`, none recomputed | PASS |
| E — No premature completion: panel is *not* visible mid-round (checked at trick 3, phase `PLAY`) | PASS |
| F — No interaction after completion: hand renders 0 `<button>` elements once `DONE`; all 52 cards confirmed played; no stray Firestore write while probing post-completion legality | PASS |
| G — Synchronization: exactly one Firestore listener (`__ONSNAPSHOT_CALLS === 1`) through the entire round, including completion | PASS |
| H — Same-browser reload: `GameSession`'s `sessionStorage`-persisted hand and last-round-result survive a real `page.reload()` unchanged | PASS |

Console/page errors: the same 2 pre-existing, previously-documented errors seen in every
prior sprint's browser QA (`buildHand is not defined`, `bindStatic is not defined`) —
unrelated to this sprint's code, no new errors introduced.

### Scope

- **Modified:** `design-ui/match/index.html` only.
- **Untouched (protected, per this sprint's own instruction):** `table-engine.js`,
  `bidding-engine.js`, `scoring-engine.js`, `session.js`, `cards.js`, `deck.js`,
  `dealer.js`, `match-service.js`, `match-adapter.js`, `firestore.rules`. No blocker
  requiring any of these was discovered this sprint — the one bug found and fixed (the
  render-ordering microtask fix above) was resolvable entirely within `match/index.html`.
- **Out of scope, not attempted:** scoring calculations (all read from existing results),
  Round 2 / next-round advance, match completion, rewards, score-screen redesign, table
  polish, monetization.

### Known Limitation

1. **Round 2 / multiplayer round transition is not implemented and was not attempted.**
   `MatchService.completeRound()`/`advanceToNextRound()` remain literal
   `notImplemented()` stubs; `currentRound` is written once, at match creation, and never
   incremented by any real write path; `biddingLog`/`cardLog` have no round-boundary
   marker. This is a genuine Firestore-schema-level gap (confirmed in the discovery pass
   before this sprint was authorized), not something the UI can safely work around.
2. **Hand reconstruction for a new/cleared client is not solved.** Player hands are
   never written to Firestore — only `cardLog` (what was played). A genuinely new or
   cleared client reconnecting mid-round cannot reconstruct its own remaining hand from
   Firestore alone; only same-browser `sessionStorage` (`GameSession.getHand()`) makes a
   reload work today. Scenario H above only proves the `sessionStorage` half — it does
   **not** prove a real cross-device/cleared-client catch-up, and this report does not
   claim that it does. This test's own fake Firestore harness additionally resets on a
   real page reload (in-memory page JS, not persisted remote Firestore), so even that
   proof is scoped to the same-browser case only.

### Future Sprint Recommendation

A separate, explicitly-scoped **"Multi-Round Match Lifecycle & Firestore Round-Schema"**
sprint is recommended before any Round 2 UI work begins. That sprint would need to
define: a real round-boundary marker in the match document, a genuine
`completeRound()`/`advanceToNextRound()` implementation with cross-client
synchronization semantics (so no client silently classifies a real round-2 bidding
action as "already complete" against a stale `BiddingEngine`), and a plan for
player-hand persistence/reconstruction for reconnecting clients. This sprint's own
discovery finding is carried forward unchanged, not re-solved here.

---

**STOP condition reached.** Per this sprint's own explicit instruction: not beginning
Round 2, Match Completion, Rewards, Score Screen redesign, Table polish, Monetization,
or any next sprint. Waiting for explicit authorization. No commit, no push.
