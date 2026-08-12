# Round Lifecycle Sprint — Round 1 → Round 2 Synchronized Transition: Final Report

**Authorization:** "SPRINT AUTHORIZATION — ROUND LIFECYCLE & MULTIPLAYER ROUND TRANSITION." Goal: Round N completes → all clients observe completion → round result finalized exactly once → match advances to Round N+1 → all clients converge on the same round number/configuration → new bidding begins → old round logs never contaminate the new round → new round card play remains synchronized. Explicitly NOT Match Completion.

---

## 1. Discovery

Verified directly against source (not assumed from the prior discovery report):

- **`currentRound`**: written exactly once, at `buildInitialMatchDoc()` (match creation), starting at `1`. Before this sprint, **no write path ever changed it again** — `MatchService.completeRound()`/`advanceToNextRound()` were literal `notImplemented()` stubs.
- **`biddingLog`/`cardLog`**: single, ever-growing, append-only arrays. Before this sprint, entries carried **no round identifier at all** — `{seatId, actionType, ...}` / `{seatId, card}`.
- **Remote catch-up is index-based, not round-aware**: `MatchAdapter.applyRemoteBiddingAction()`/`applyRemoteCard()` each track a monotonic `lastAppliedXCountByMatch[matchId]` — "how many log entries have I already replayed." Confirmed by direct read of both functions.
- **Confirmed hard contamination risk**: `BiddingEngine.canSubmit()` returns `{legal:false, reason:"Bidding is already complete"}` once `subPhase === "DONE"`, and `applyRemoteBiddingAction()`'s own `isPhaseOrTurnMismatchReason()` **already classifies that exact reason as a benign skip** — it advances the count past the entry without applying it. Traced the consequence precisely: a Round 2 entry arriving at a client whose local `BiddingEngine` is still on Round 1's `DONE` state would be silently marked "already applied," advancing the count past it — and since the count is monotonic and never revisited, that entry would be **permanently lost** for that client once it later re-initializes for Round 2. This is exactly the "Client A / Client B" race the brief warned about, now root-caused precisely rather than assumed.
- **`BiddingEngine.initState()` has no ROUND_CFG-class staleness bug**: unlike `table-engine.js` before its own Foundation Fix, `bidding-engine.js`'s `initState()` already re-reads `GameSession.ensureHandsDealt()`/`getDealer()`/`getRound().number` fresh on every call — confirmed by direct read, not assumed.
- **Round result persistence**: already fully solved by Sprint 5's own architecture — every client deterministically computes the identical round result from the same replayed `cardLog` via `TableEngine.resolveTrick()` → `ScoringEngine.applyRoundResult()` → `GameSession.recordRoundResult()`, all unmodified. No new Firestore field is needed to persist a round's score.
- **Hand synchronization gap, confirmed pre-existing and NOT specific to Round 2**: `GameSession.dealNewHands()` calls `Dealer.dealHands()`, which defaults to plain `Math.random()` (confirmed in `deck.js`). Hands are **never written to Firestore** — only `cardLog` (what was played). This means even Round 1, in a genuine multi-device match, already has no cross-client hand agreement mechanism; this sprint does not create that gap, it inherits it unchanged into Round 2 as well.

## 2. Architecture Decision

**Schema options considered** (per the brief's own required comparison):

| Option | Shape | Write complexity | Read complexity | Migration | Replay safety | Listener impact | Compatibility |
|---|---|---|---|---|---|---|---|
| **A — round-tag every log entry** (chosen) | Add `round: <int>` to each `biddingLog`/`cardLog` entry, stamped server-side from the document's own `currentRound` at write time | +1 field per write, no new write path | Existing count-based catch-up loop gains one comparison | None — purely additive, existing entries default-compatible | Solved directly: an entry's round vs. local engine's round decides apply/defer/skip | Zero — reuses the exact same single listener | Full — no schema-breaking change |
| B — per-round log arrays/subcollections | `biddingLog_1`, `biddingLog_2`, ... or a `rounds/{n}/biddingLog` subcollection | New array/subcollection per round; write path must pick the right one | Every reader must know which round's array to read | Real schema migration; every existing test fixture changes shape | Solved, but by isolation rather than tagging | Would require a NEW listener per round (violates "exactly one listener" requirement) | Breaks every existing test/production assumption about one flat log |
| C — reset/version logs at round boundary | Clear `biddingLog`/`cardLog` back to `[]` on transition | One extra field wipe in the transition write | Simplest possible read | None | **Unsafe**: destroys Round 1 history mid-flight for any client still catching up on Round 1's tail; violates this project's own established append-only/prefix-immutability invariant (Sprint 4.2.1) | None | Breaks the existing "never rewritten, never cleared" contract other code already relies on |
| D — other | (none identified that improves on A without B's or C's costs) | — | — | — | — | — | — |

**Recommendation: Option A.** It is the smallest change consistent with the existing architecture (one flat, append-only log + count-based catch-up + one shared listener), and it directly resolves the confirmed contamination risk without a schema migration, a second listener, or a destructive reset.

**Round transition authority ("who advances the round"):** Not a designated host/caller. `MatchService.advanceToNextRound(matchId, completedRound)` is implemented as **one atomic Firestore transaction**, safe for **any seated client to attempt**:
- If `currentRound !== completedRound` by the time the transaction actually reads the document, it is a **no-op** (`{advanced:false, reason:"ALREADY_ADVANCED"}`) — never an error, never a second advance. This is the same idempotent, transaction-based "first commit wins" pattern this codebase already uses for `startMatch()`'s own "two players pressing Ready simultaneously" guarantee.
- Any client whose own `TableEngine` locally observes `phase === "DONE"` may call it (wired automatically, once per round, inside `MatchAdapter.startTrickSync()`) — race conditions are resolved by the transaction, not by picking a "correct" caller in advance.

**`completeRound()` vs. `advanceToNextRound()` — one transaction, not two phases:** `completeRound()` is deliberately left as an unimplemented stub. Exposing a separate "mark complete, but don't advance yet" step would create exactly the observable half-transition state the brief itself warns against (a client could see `currentRound === N` with the round already finished, unsure whether Round N+1 may safely begin). `advanceToNextRound()` verifies completion **and** advances, atomically, in one transaction — there is no intermediate state to observe.

**Completion verification, structural not gameplay:** `advanceToNextRound()` counts `cardLog` entries tagged with the completed round (`=== 52`, i.e. 13 tricks × 4 seats) — it does **not** re-run `table-engine.js`'s rules or `ScoringEngine`. This mirrors every other rule in this codebase's own established "generic/structural check only, legality stays with the real engine" line (`isValidBidSubmission()`, `isValidCardSubmission()`, etc.).

## 3. Implementation

**`design-ui/match-service.js`:**
- `buildBiddingLogEntry(seatId, action, round)` — now stamps `entry.round` from the caller's `round` argument.
- `submitBiddingAction()` — passes `freshMatch.currentRound` (the fresh, in-transaction document's own field, never a client-supplied value) into `buildBiddingLogEntry()`.
- `submitCard()` — stamps the new `cardLog` entry with `freshMatch.currentRound` identically.
- **New:** `advanceToNextRound(matchId, completedRound)` — the one real, atomic transition method (see §2). Resets `biddingOpen`/`bids`/`lastBidSeat`/`cardPhase`/`turn` for the new round; **never** touches `biddingLog`/`cardLog` themselves (append-only, unmodified).
- `completeRound()` — left as a stub, with an updated doc comment explaining why (see §2).

**`design-ui/match-adapter.js`:**
- `applyRemoteBiddingAction()`/`applyRemoteCard()` — each gained a round-tag guard, inserted **before** the existing `canSubmit()`/engine-state checks: an entry whose `round` is ahead of the local engine's own round is deferred (`AWAITING_ROUND_TRANSITION`, not a desync, not silently consumed — the count registry does **not** advance past it, so a later delivery re-attempts the exact same index once the local client catches up). An entry behind the local round is a harmless already-superseded skip.
- **New:** `applyRemoteRoundTransition(matchId, matchDoc)` — detects `matchDoc.currentRound` ahead of the local `GameSession.getRound().number`, calls the existing `GameSession.nextRound()` (unmodified, already used by the single-player flow) the correct number of times, then calls the existing `BiddingEngine.initState()` once, re-deriving the new round's config fresh (no new state store).
- **New:** `startRoundSync(matchId)` — the round-transition-sync analog of `startBidSync()`/`startCardSync()`/etc., sharing the same single `MatchService.subscribeToMatch()` listener.
- **New:** `maybeAdvanceRound(matchId, matchDoc)` — wired into `startTrickSync()`'s existing per-delivery callback: once this client's own `TableEngine` locally observes `phase === "DONE"`, it attempts `MatchService.advanceToNextRound()` exactly once per round (guarded so it never re-attempts on every subsequent delivery).

**`design-ui/match/index.html`** (two minimal, justified changes, no visual/UI work):
1. `startRoundSync(matchId)` registered alongside (before) `startBiddingActionSync()`/`startBidSync()` — a latency optimization only (see `applyRemoteRoundTransition()`'s own doc comment for why a one-delivery-late ordering self-corrects either way via the round-tag deferral).
2. `tableEngineStarted` (a one-shot-forever boolean) renamed to `tableEngineStartedForRound` (the round number `TableEngine` was last started for) — the smallest possible change to let `maybeEnterPlayPhase()` correctly re-trigger `TableEngine.initState()` once Round 2's bidding also reaches `DONE`, mirroring Round 1's exact existing flow.

**`firestore.rules`:**
- `isValidCardSubmission()`/`isValidBiddingActionSubmission()`/`isValidBiddingActionEntry()` — each now requires the newly-appended entry's `round` to equal the pre-write document's own `currentRound` (never a client-supplied value) — the read-side rules-layer counterpart of the service-layer stamping above.
- **New:** `isValidRoundAdvance()` — the fourth legitimate `matches/{matchId}` update shape. Checks `currentRound`/`version` increment by exactly 1, the reset fields (`biddingOpen`/`cardPhase`/`turn`) take their correct reset values, and no other field changes. **Honest limitation, stated in the rule's own comment**, matching every other rule in this file: it cannot verify the round was *genuinely* complete (that stays `advanceToNextRound()`'s own structural, JS-side check) — CEL has no practical way to re-derive gameplay legality here without duplicating `table-engine.js`'s rules, which no other rule in this file does either.

## 4. Synchronization

- **How clients converge:** every client independently observes the same `currentRound` bump through the one shared Firestore listener; `applyRemoteRoundTransition()` deterministically advances `GameSession`/`BiddingEngine` the same way on every client. No new sync channel, no second listener.
- **Listener count:** confirmed still exactly 1 per match — `startRoundSync()` reuses `MatchService.subscribeToMatch()`'s existing ref-counted registry, proven directly (`tests/round-lifecycle.test.cjs` Scenario J; browser QA Scenario B/G).
- **Stale-event handling:** an entry tagged for a round ahead of the local engine is deferred, not dropped (`AWAITING_ROUND_TRANSITION`); an entry behind the local round is a harmless skip. Both proven directly, at the unit level, against the real adapter functions.
- **Duplicate-transition protection:** `advanceToNextRound()`'s own transaction-level idempotency check (`currentRound !== completedRound` → no-op) — proven both at the Node level and in the real browser.

## 5. Security

- `firestore.rules` changes described in §3. No existing validation was weakened — every prior rule's own `affectedKeys().hasOnly([...])` allowlist gained exactly one new field (`round`) where relevant, and the new `isValidRoundAdvance()` rule is additive (`||`'d onto the existing `allow update` clause, mutually exclusive with the other three shapes by its own independent `hasOnly()` allowlist).
- **Authorization model for round advancement:** any authenticated player already seated in the match (`request.auth.uid in oldData.players`) — deliberately not scoped to a specific seat, matching `advanceToNextRound()`'s own "any client may attempt it; the transaction is what makes that safe" design (§2).
- **Cross-round protection:** the round-tag equality check in `isValidCardSubmission()`/`isValidBiddingActionSubmission()` structurally rejects a client attempting to fabricate a Round 2 entry inside a still-Round-1 document (or vice versa) — the tag must match `oldData.currentRound` exactly.

## 6. Tests

- **Baseline (going in):** 1123 (940 across `tests/*.test.cjs` + 183 in `tests/rules-simulation.test.js`).
- **Final:** **1157 passed, 0 failed**, across 19 files (18 pre-existing + 1 new: `tests/round-lifecycle.test.cjs`).
- **New/updated:**
  - `tests/rules-simulation.test.js`: +11 new focused checks (round-tagging on both logs, `isValidRoundAdvance()` — well-formed/skipped-round/version-mismatch/stale-field/unrelated-field/non-player-uid cases). All existing card/bidding-submission fixtures updated to carry the now-required `round` field.
  - `tests/round-lifecycle.test.cjs` (new, 28 checks): round-tagging on write (`submitBiddingAction()`/`submitCard()`), `advanceToNextRound()`'s structural completion check/idempotency/permission checks, `applyRemoteRoundTransition()`'s real `GameSession`/`BiddingEngine` re-initialization, the `AWAITING_ROUND_TRANSITION` deferral-then-recovery for both `applyRemoteBiddingAction()` and `applyRemoteCard()` (proving an entry is never permanently lost), and single-listener sharing.
  - 5 existing test files (`bid-sync`, `card-sync`, `match-service`, `match-sync`, `trick-sync`) had one stub-regression assertion each updated: `advanceToNextRound` removed from their "still an unimplemented stub" list (it is intentionally no longer a stub — this is the sprint's own deliverable, not an accidental regression) with an explanatory comment; `completeRound` remains checked as a stub in all five.
  - One `match-service.js` doc-comment wording fix (`GameSession.recordRoundResult()` → "GameSession's own `recordRoundResult()`") to avoid a false-positive on `tests/turn-sync.test.cjs`'s own regex-based "match-service.js has zero CODE reference to GameSession" isolation check — a documentation-only fix, not a behavior change.
- **Failures during development, all resolved (see git history of this session for the debugging trail):** the round-tag filter's interaction with `canSubmit()`'s pre-existing benign-skip classification; a fixture round-mismatch in the new bidding-action tests; the stub-regression assertions above.

## 7. Browser QA

Real headless Chromium (Playwright), local static server, hand-written fake-but-transactional Firestore harness (same family as every prior sprint's own browser tests this session, extended with round-tagged remote-seat helpers and a Final-Estimate remote helper).

**A two-genuinely-independent-page harness (bridged via Node-mediated `page.exposeFunction()`/`page.evaluate()` calls) was attempted first, specifically for Scenario G.** It surfaced real infrastructure timing/re-entrancy issues (a `page.evaluate()` call invoked from inside another still-in-flight `page.evaluate()`'s own exposed-function handler) that made it unreliable within this sprint's effort budget. It was abandoned in favor of the single-page harness below, which every other scenario is proven against for real; **Scenario G's underlying convergence property is instead proven at the Node level**, directly against the same production `MatchAdapter.applyRemoteRoundTransition()`/`applyRemoteBiddingAction()` functions simulating two independently-progressing clients (`tests/round-lifecycle.test.cjs`, Scenario H there). This is reported plainly, not glossed over.

**20 checks passed, 0 failed** (single-page harness):

| Scenario | Result |
|---|---|
| A — Round 1 final trick genuinely reaches `phase: DONE`, `lastTrick.winnerId` populated | PASS |
| B — Round transition: `currentRound` advances to 2 automatically (via the auto-triggered `advanceToNextRound()`); `BiddingEngine` re-initialized fresh (round 2, `DASH`); Firestore doc's own `currentRound` is 2; `bids`/`biddingOpen`/`turn`/`cardPhase` correctly reset; Round 1's 52 `cardLog` entries completely untouched | PASS (6/6 sub-checks) |
| C — Round 2 bidding: the local client's own real `submitBiddingAction()` call is accepted by the real engine for Round 2; the new `biddingLog` entry is tagged `round:2` | PASS |
| D — Round 2 card play: `TableEngine` re-initialized for Round 2 (fresh 13-card hands, not Round 1's exhausted ones); the local client's own real `submitCard()` call is accepted; the new `cardLog` entry is tagged `round:2` | PASS |
| E — Old Round 1 action replay: re-processing the full synchronized log (whose prefix is still Round 1's own entries) via `applyRemoteBiddingAction()` directly causes no new mutation — already fully consumed, never re-applied against the Round 2 engine | PASS |
| F — Duplicate transition: an explicit second `advanceToNextRound(1)` call resolves `ALREADY_ADVANCED`, not an error; `currentRound` stays at 2, no Round 3 created | PASS |
| G — Two-client convergence | Proven at the Node level (`tests/round-lifecycle.test.cjs`), not in this browser file — see above |
| H — Reload | **Reported honestly as not verifiable in this browser harness for the post-transition case** — see Known Limitations |

Console/page errors: the same 2 pre-existing, previously-documented errors seen in every prior sprint's browser QA (`buildHand is not defined`, `bindStatic is not defined`) — unrelated to this sprint, no new errors introduced.

## 8. Known Limitations

1. **Reload verification gap (Scenario H), root-caused, not guessed:** `bootstrapGameSession()` (unconditional, by design — "trust the server on load") re-seeds `GameSession.setRound()` from whatever the *current* Firestore document says on every fresh page load. This test harness's fake Firestore is in-memory page JS, not real persisted Firestore — it resets to `currentRound: 1` on a real `page.reload()`, so `bootstrapGameSession()` then (correctly, by its own logic) re-seeds `GameSession` back to round 1 against that artificially-regressed document, which cascades into a fresh hand deal. **A real client reloading against real, persisted Firestore would correctly keep seeing `currentRound: 2` and never hit this** — this is a harness-fidelity gap, not a reproduction of a real bug, and is reported as informational output in the browser test rather than asserted as a passing (or failing) check.
2. **Hand synchronization remains unsolved — for Round 2 exactly as much as Round 1, not newly introduced by this sprint.** Player hands are never written to Firestore; `Dealer.dealHands()` defaults to independent `Math.random()` per client. Genuine multi-device hand agreement was never solved by any prior sprint and is not solved here — this sprint's own two-client Node-level test (`tests/round-lifecycle.test.cjs`) proves the *round-transition machinery* converges correctly across simulated independent clients; it does not, and does not claim to, prove hand agreement.
3. **Round result is not separately persisted to Firestore**, by design (§2) — every client already deterministically computes the identical result from the same replayed `cardLog`. If a future sprint ever needs a server-visible round-result record (e.g. for a spectator view that never runs the engine), that would be new scope, not a gap in this sprint's own stated goal.

## 9. Scope

- **Modified:** `design-ui/match-service.js`, `design-ui/match-adapter.js`, `design-ui/match/index.html`, `firestore.rules`, `tests/rules-simulation.test.js`, `tests/bid-sync.test.cjs`, `tests/card-sync.test.cjs`, `tests/match-service.test.cjs`, `tests/match-sync.test.cjs`, `tests/trick-sync.test.cjs` (test-assertion updates only in the last 5), plus the new `tests/round-lifecycle.test.cjs`.
- **Untouched:** `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `session.js`, `cards.js`, `deck.js`, `dealer.js` — no engine rule, formula, or card-legality logic was changed. `GameSession.nextRound()`/`BiddingEngine.initState()` are reused verbatim, exactly as the brief required ("do not invent duplicate state stores").
- **No unexpected file needed modification** — every change was anticipated in the authorization brief's own file-scope discussion; no STOP-and-report blocker was hit during implementation (only during the earlier, separate discovery phase, whose findings this sprint directly acted on).

## 10. Recommendation

Recommend a follow-up **"Match Completion & Score Screen"** sprint (out of this sprint's own explicit scope) once N-round matches are confirmed stable in practice, and a separate, explicitly-scoped **"Player Hand Synchronization"** sprint before this project ever runs a genuine multi-device match end-to-end — the gap described in Known Limitation §2 is real, pre-existing, and orthogonal to round lifecycle; solving it requires its own schema/authority decision (e.g. server-dealt hands, or per-client-dealt-and-Firestore-synced hands) and should not be bundled into either of the sprints above. Suggested effort for each: MEDIUM (Match Completion, mostly UI + existing `GameSession.getMatchScores()`/`getWinner()` plumbing) and HIGH (Hand Synchronization, a genuine new schema/security design, not a mechanical extension of existing patterns).

---

**Definition-of-Done, walked through explicitly:**
- [x] Schema decision made and justified (Option A) — not implemented ad hoc.
- [x] Round transition authority analyzed and resolved (idempotent, any-client-may-attempt transaction) — no race left unaddressed.
- [x] Round log isolation demonstrated with real tests (deferral + recovery, both logs).
- [x] Scoring logic untouched, not duplicated.
- [x] `ROUND_CFG`-class staleness confirmed absent from `BiddingEngine`, not reintroduced anywhere.
- [x] `firestore.rules` updated, no existing validation weakened.
- [x] Full regression green (1157/1157, baseline 1123).
- [x] Real browser verification performed (not Node-only) — 20/20 scenarios A–F passing; G proven at Node level with an honest note on why; H reported honestly as a harness-fidelity gap, not asserted false-positive.
- [x] No Match Completion, Rewards, Score Screen, Table polish, or Monetization work begun.
- [x] No commit, no push.

**STOP condition reached.** Per this sprint's own explicit instruction: not beginning Match Completion, Rewards, Score Screen redesign, Table polish, Monetization, or any next sprint. Waiting for explicit authorization.
