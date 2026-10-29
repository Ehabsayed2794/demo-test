# 2026-10-29 — The Estimation Stage asks the With players first

**Status: RECORDED AND IMPLEMENTED.** Source: the owner's ruling of 2026-10-29, given in the plain-language
walkthrough of the seed 194 dead end ("the counter-clockwise rule breaks when there is a With"). The engine,
the tests, and the rules pointer note carry it.

Labels: **[D]** owner-confirmed.

## 1. The ruling [D]

In a **normal round**, once the Caller confirms:

1. The Caller's own number is locked by the confirmation — the Caller is never asked again.
2. The seats asked first are **all the “With” players**, in counter-clockwise order from the Caller.
3. Then **everyone still left**, also counter-clockwise from the Caller.
4. A seat that already holds a bid — the Caller, a pre-bidding Dash Call seat — is never asked. That is the
   same rule as before; it is what skips Dash Callers.
5. The **Risk Player is simply the last seat asked**. In the old plain walk that was always “the seat before
   the Caller”; now it is the last element of the new queue, and it is still possible for that seat to be
   “With” (rules §2.3 “Combined With + Risk” is unchanged).

**Fast rounds are unchanged**: no With exists until the final declaration lands, so the rotation stays the
dealer's plain counter-clockwise pass.

## 2. Why — the seed 194 dead end

The E2 Stage 0 corpus (200 seeds × 4 columns: FULL/MINI × NORMAL/CLASSIC) found **seed 194, round 5** stalling
in all four columns, always at the same seat:

- p1 (Caller) confirmed **6♠**; p3 matched exactly and was **With at 6**; p4 had declared a pre-bid
  **Dash Call** and never estimates; p2 was the only remaining seat.
- The plain walk asked **p2 first**, so **p3 — the With seat — was last**. Its three constraints then left no
  legal number: Call Cap 6, own-bid floor 6 (suit-aware), and forbidden-13 = 13 − (6 + 1 + 0) = 6. The legal
  set was empty for the seat on turn.
- Reachable for a 4-, 5-, or 6-trick call, in every column. The legacy prototype engine
  (`design-ui/engine/bidding-engine.js`) has the identical dead end, so it predates the native port.

With the With players asked first, the last seat in this shape is non-With: it has no floor and at most one
forbidden number, so it always has a legal choice. Seed 194 round 5 now completes.

## 3. Where it is enforced

- `native/engine/src/commonMain/kotlin/com/estemshan/engine/Bidding.kt` — one pure helper,
  `estimationQueue(state, bids, withPlayers)`, builds the queue (With leg, then the rest; both legs
  counter-clockwise from `callerId`, seats with a bid dropped). Both normal-round transitions use it:
  `emitConfirmCall` (first estimator) and `emitFinalEstimate` (next estimator). Recomputing it after every
  estimate is what keeps an Estimation Jump-In seat from being asked twice — it became With by declaring, so
  it already holds a bid.
- `native/engine/src/jvmTest/kotlin/com/estemshan/engine/EstimationOrderTest.kt` — the queue (With-first,
  counter-clockwise, not insertion order), the Dash Caller exclusion, the fast-round degenerate case, and the
  seed 194 shape end to end.
- `native/engine/src/jvmTest/kotlin/com/estemshan/engine/WithFloorSuitAwareTest.kt` — the two floor tests that
  drove the old order now drive the new one; what they prove (suit-aware floor, floor acceptance) is unchanged.
- `native/engine/src/commonMain/kotlin/com/estemshan/engine/bot/BidBrain.kt` — the bot now reads the same
  suit-aware floor through the new public `withFloorFor(state, pId)` instead of the suit-blind
  `withFloorFor(actionHistory, pId)`. The engine never floored on a bid in another suit, so the bot's own
  guard was raising a With estimate above the number the engine accepts; the brain had been compensating
  with a rejection it then silently corrected. Pinned by
  `BidBrainTest.aWithEstimateRaisesToTheWinningSuitBidNotTheLastBidOverall`. No bot decision in the 800-match
  corpus changed — the corpus output is byte-identical before and after.
- `docs/rules/CANONICAL_RULES.md` §2.3 — in-place pointer note; the superseded paragraph is left verbatim.

## 4. Evidence (2026-10-29)

- `:engine:jvmTest` 622 tests, 0 failures (baseline 617 + the 4 new order tests + the one brain floor test).
- `:services:testDebugUnitTest` 298, `:app:testDebugUnitTest` 242, `:functions:jsNodeTest` 760 — all green.
- E2 corpus re-run, JVM and workerd (wrangler 4.155.0 / workerd 2026-10-23, Node 22.14.0): 200 seeds per
  column, **0 failures** in all four; FULL 3,600 rounds and MINI 2,000 rounds per column; JVM output and
  workerd output **byte-identical** in all four columns
  (sha256 `4c9aa66a…`, `7ae36e7a…`, `a6c04773…`, `6a84a2e5…`).
- Mutation proof (re-run against the final file): dropping the With partition from `estimationQueue` (plain
  counter-clockwise walk) fails three of the four order tests — `theCallerAndSeatsAlreadyHoldingABidAreNotInTheQueue`
  (`expected:<[p3, p2]> but was:<[p2, p3]>`), `withSeatsAreAskedFirstThenTheRest…`
  (`expected:<[p4, p1, p3]> but was:<[p3, p4, p1]>`) and `aWithSeatIsNeverTheLastEstimatorInTheSeed194Shape`
  (`expected:<p[3]> but was:<p[2]>`) — while the fast-round pin still passes, as it must. `Bidding.kt` was
  restored byte-identically (sha256 `2dbb2d64bf577f82a65b3a43ffabaf35592e927932db059b794d63211ee934af`,
  `git diff` empty).

## 5. Not changed

- **Fast rounds** — see §1.5.
- **The legacy prototype engine** (`design-ui/engine/bidding-engine.js`) keeps the old order. It is the
  design artifact, not a shipped runtime; if it is ever revived it needs the same rule.
- **UI** — the Compose screens follow `waitingFor`, so they inherit the new order with no code change.
