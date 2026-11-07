# Hard task 3 — E2.2 + E2.3: suit-aware With floor, Estimation Jump-In, repeated fast Super Call

You are one of six agents working in parallel on this repository. You have your own worktree and a
file-disjoint write scope. Do not touch any file outside it.

**Task class:** HARD. This changes auction legality and the fast-round state machine. If you cannot
finish both slices, finish **E2.3 first** (it is the larger correctness gap), then E2.2, and report
`PARTIAL` naming exactly what is left.

> **MERGE GATE — read before you start.** Changing the engine's fast Super Call semantics changes
> behaviour that the Cloud Functions replay path mirrors in
> `native/functions/src/jsMain/kotlin/com/estemshan/functions/Settlement.kt`
> (`replayFastSuperConfirm`). That file belongs to another agent in this wave. **Do not edit it.**
> This branch must not be merged on its own; §8 requires you to name the exact follow-up. Treat the
> `:functions` result as evidence to report, not as a gate you may fix.

## 1. Read first, in this order

1. `AGENTS.md`.
2. `docs/engineering/NODEJS_SAFE_USAGE.md`.
3. `docs/rules/CANONICAL_RULES.md` — §2.2 "With (Wazz) Rules" (Auction Alignment + Estimation
   Jump-In, lines ~68–73), §2.2.1, and §2.3 (Call Cap, 13 Rule).
4. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — **O-02 / A7.2**, **O-02b / A8.1 / Q35**, and the
   **T-08**, **T-09**, **T-10**, **T-11**, **T-12**, **T-24** vectors.
5. `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` — §5.10 rows **E2.2** and **E2.3**.
6. `native/engine/src/commonMain/kotlin/com/estemshan/engine/Bidding.kt` and
   `native/engine/src/jvmTest/kotlin/com/estemshan/engine/BiddingTest.kt`, fully.

`docs/**` is read-only. `native/app/**` and `native/functions/**` are out of scope.

## 2. Worktree

```powershell
git -C D:\demo-test worktree add D:\worktrees\agent-bidding-super -b agent/bidding-super 8b13b12
```

If the branch exists, use `agent/bidding-super-2`. Never `--force`. Never work in `D:\demo-test`.

## 3. What already exists (verified at `8b13b12`)

S4 is merged. Do **not** redo it:

- `Bid` carries a per-declaration `suit`, and `fastDeclarations(...)` populates it from
  `bid.suit ?: state.declaredTrump ?: fixedTrumpFor(...)`.
- `resolveFastCall(declarations)` already implements A7.2/A8.1 exactly: highest **number** first,
  then the canonical suit strength (`Sans 5 → Clubs 1`), then declaration order **only** for an
  exact number-and-suit duplicate. The seat-order sort IB-16 is gone. **Do not rewrite this
  function**; it is your reference for the correct tie rules.
- `fastWithAtFinalCall(...)` already computes With as the exact final number **and** final trump,
  excluding the locked caller.
- The normal-round With set already matches number + suit.

What remains is exactly three gaps:

1. `withFloorFor(history, pId)` returns the last `BID` by that player **in any suit**.
2. Estimation Jump-In matches **only** `state.auctionTop`, not the suit.
3. `fastSuperResolved` is set once and never re-opened, and `emitConfirmCall` wipes only the seats
   **before** the caller in bidding order.

## 4. E2.2 — suit-aware With floor and Estimation Jump-In

### 4.1 `withFloorFor`

Add an overload and keep the existing two-arg function:

```kotlin
fun withFloorFor(history: List<BidAction>, pId: String, winningSuit: Suit): Int? =
  history.filter {
    it.actionType == "BID" && it.playerId == pId && it.suit == winningSuit
  }.lastOrNull()?.value
```

- The two-arg `withFloorFor` must keep its current behaviour and signature — three app callers and
  `BidBrain` depend on it: `BiddingViewModel.kt:111`, `OnlineMatchViewModel.kt:1184`,
  `BidBrain.kt:326`. You may not edit any of them.
- The three-arg overload reads the last bid **in the Caller's winning suit**, so a later higher bid in
  another suit does not raise the floor (T-12 "superseded call": Ahmed bids 5♥ then 6♠, Osama wins
  7♥ → Ahmed is With at 5).

### 4.2 `estimateBelowWithFloor`

Add an overload that takes the winning suit and uses the three-arg floor. The existing four-arg
function stays source-compatible and delegates with a null suit (falling back to the two-arg floor).

Both internal call sites in `Bidding.kt` — the legality check and the emit check — must pass the
Caller's winning suit (`state.auctionSuit`). A Dash round has `auctionSuit == null` and no With
players, so it must remain a no-op, not a crash.

### 4.3 Estimation Jump-In

Canonical §2.2.1: *"during the Estimation Stage, a player matches BOTH the Caller's number AND suit
exactly. This is open to anyone, including players who passed the auction."*

Today the condition is number-only. Make the suit half explicit:

- The estimate's recorded suit is `state.declaredTrump`; the Caller's winning suit is
  `state.auctionSuit`.
- When **both** are present, require equality before granting With.
- When `auctionSuit` is `null` (a hand-built or legacy state), keep the number-only behaviour and
  document why — do not fail open when both are present and differ, and do not fail closed on a state
  the runtime cannot produce.
- The "anyone, including a passer" rule is unchanged: a seat with no auction bid can still jump in by
  matching both.

Add the tests the plan asks for (T-12): matching number in a different suit is **not** With;
matching number and suit **is**; a passer matching both **is**.

## 5. E2.3 — repeated higher fast Super Call

### 5.1 The rule (T-09)

| Step | Declarer | Call | Trump after | Who re-declares |
|---|---|---|---|---|
| 1 | A | 8 | Hearts | B, C, D — **not A** |
| 2 | B | 9 | Spades | C, D, A — **not B**; A re-declares despite having declared after the first call |
| 3 | C | 10 | Clubs | D, A, B |
| 4 | no higher call | — | Clubs | auction ends; C Caller |

Every higher 8+ call:
- wipes **every** prior declaration except the new Super Caller's own;
- re-opens `BiddingPhase.CONFIRM` for the new caller;
- lets that caller choose **any** trump (A6.10);
- makes every other seat re-declare, counter-clockwise from the caller, including a seat that already
  re-declared after an earlier call.

Equal-highest is already decided by `resolveFastCall` (A7.2): higher number wins whatever the suit,
then the suit ladder, then exact-duplicate declaration order. Do not add a seat-order tie-break.

### 5.2 What to change

- **`emitConfirmCall`** currently computes `preSuper = order[0..callerIndex)`, wipes only that, and
  **completes the round when `preSuper` is empty** (the caller is first). Replace it with "wipe every
  seat except the caller", and set `waitingFor` to the seat immediately **after** the caller in the
  round's counter-clockwise order (not `preSuper[0]`). Delete the `preSuper.isNotEmpty()` completion
  branch: a Super Call with the caller first still leaves three seats to re-declare, so it cannot
  complete on the confirm.
- **`fastSuperResolved`** must mean *"the CURRENT auctionTop has been confirmed"*, not *"a Super Call
  happened once"*. When a higher 8+ declaration opens a new call, set it back to `false` until that
  call's own CONFIRM resolves; `emitConfirmCall` sets it `true`.
- **`emit`'s fast-round branch** must check for a **higher** 8+ declaration **before** the
  `if (state.fastSuperResolved)` completion branch:
  - collect `fastDeclarations(state, newBids, 8)` with `amount > state.auctionTop`;
  - if non-empty, resolve the winner with `resolveFastCall` over exactly those declarations and move
    to `CONFIRM` with that caller (do not complete the round);
  - if empty and `fastSuperResolved`, complete exactly as today via `fastWithAtFinalCall`;
  - if empty and not resolved, keep the existing first-Super-Call branch.
- Keep the provisional `withPlayers` at CONFIRM (the exact duplicates among 8+ declarations) and the
  authoritative recompute at completion. A bare 8+ is never With; a seat that kept an equal number
  under the **replaced** trump is not With.

Do **not** change `resolveFastCall`, `fastDeclarations`, `fastWithAtFinalCall`, `Bid`, or
`BiddingState`'s persisted fields.

## 6. Tests you must rewrite (they encode the old one-shot rule)

These exist on `8b13b12` and assert the behaviour this task replaces. Rewrite them to the T-09 rule;
do not delete them, and do not weaken unrelated assertions in the same test:

- `BiddingTest.fastSuperCallReset`
- `BiddingTest.fastSuperCallConfirmRecomputesWithWhenNoSeatsAreWiped`
- `BiddingTest.fastSuperCallRecomputesWithFromFinalNumberAndTrump`

`fastSuperCallHigherNumberWinsWhateverTheSuit` should still pass unchanged. If it does not, your
`resolveFastCall` use is wrong — investigate before editing it.

Add new tests for:

- the full T-09 four-step sequence, asserting after each step who must re-declare and who must not;
- a seat that already re-declared re-declaring again after a higher call;
- an equal number in a weaker suit opening no new call and granting no With;
- an exact number-and-suit duplicate: legal, first declarer Caller, later duplicates With (A8.1);
- a higher call whose caller then picks a **weaker** suit (allowed when the number rises);
- no higher call → the last caller wins and the auction completes.

## 7. Write scope

**Allowed to modify**

- `native/engine/src/commonMain/kotlin/com/estemshan/engine/Bidding.kt`
- `native/engine/src/jvmTest/kotlin/com/estemshan/engine/BiddingTest.kt`
- **New** engine test files you own, e.g.
  `native/engine/src/jvmTest/kotlin/com/estemshan/engine/FastSuperCallRedeclarationTest.kt`.

**Optional, only if the compiler forces it**

- `native/engine/src/commonMain/kotlin/com/estemshan/engine/bot/BidBrain.kt` — one call site. Prefer
  leaving it on the two-arg overload. If you change it, justify it in `SCOPE DEVIATIONS` and test it.

**Forbidden**

- `native/app/**` (all of it — the two ViewModels and their tests).
- `native/functions/**` (all of it — including `Settlement.kt` and its replay tests).
- `native/services/**`, any `*.gradle.kts`, `firestore.rules`, `firebase.json`, `.github/**`,
  `docs/**`.
- `DisconnectVoteState.kt`, `LeaveMatch.kt` (another agent owns them).
- `InactivityClock.kt`, `RankedStats.kt`, `Placement.kt`.
- Any file that already differs from `8b13b12` in your worktree. If one does, stop and report it.

## 8. Acceptance criteria

Mark each `PASS` / `FAIL` / `NOT RUN` with the test that proves it.

1. `withFloorFor(history, pId, winningSuit)` returns the last `BID` by that player in that suit.
2. The two-arg `withFloorFor` is unchanged and all three existing callers still compile.
3. A With estimate below the player's last bid **in the winning suit** is rejected; one at or above
   it is accepted.
4. A later higher bid in another suit does not raise the floor.
5. A player who never bid the winning suit has no floor and is not rejected by this rule.
6. Estimation Jump-In grants With only when the number **and** the suit match.
7. A matching number in a different suit does not grant With.
8. A player who passed the auction can still jump in by matching both.
9. After a confirmed Super Call, a later higher 8+ declaration opens a new `CONFIRM` with the new
   caller and does not complete the round.
10. The new call wipes every other seat, including seats that already re-declared.
11. Every non-caller re-declares, counter-clockwise from the new caller.
12. An equal number in a weaker suit opens no new call and grants no With.
13. An exact number-and-suit duplicate is legal: first declarer Caller, later duplicates With.
14. With no higher call, the auction completes with the last caller and the final-trump With set.
15. The three rewritten tests assert the new rule and keep their unrelated assertions.
16. `:engine:jvmTest` is green.
17. `:functions:jsNodeTest` is green **or** every failure is named, classified as "encodes the old
    one-shot rule" or "unexpected", and §9's follow-up names the exact function and guard to change.

## 9. Commands

```powershell
native\gradlew.bat -p native :engine:jvmTest
native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest
```

Do not run `npm`, `npx`, `npm ci`, or `node`. Do not run the full CI command. If the approved Node 22
runtime is absent, report `:functions:jsNodeTest` as `NOT RUN` with the exact error.

## 10. Report

End with exactly these headings, in this order, each on its own line:

```
STATUS: DONE | PARTIAL | BLOCKED
CHANGED FILES: <one path per line, each with +added/-removed>
CHANGE SUMMARY: <per file: what changed and why>
ACCEPTANCE CRITERIA: <every numbered criterion above, marked PASS / FAIL / NOT RUN, with the
                      test name or command that proves it>
COMMANDS RUN: <exact command -> result, including test counts>
NOT RUN: <exact command -> why>
KNOWN RISKS / ASSUMPTIONS: <including anything you could not verify>
SCOPE DEVIATIONS: <none, or file + justification>
HANDOFF NOTES: <what a reviewer or the next agent must know>
```

`DONE` requires every criterion `PASS`. If any is `FAIL` or `NOT RUN`, the status is `PARTIAL`
(or `BLOCKED` when you could not compile). Never round up.

`HANDOFF NOTES` must contain a section titled **"Required follow-up in `:functions`"** that states:

- `replayFastSuperConfirm` refuses whenever `state.fastSuperResolved || state.callerId != null`, so it
  cannot replay a second, higher Super Call;
- its synthetic `preConfirm` state only carries bids from `callerIndex..end`, which is the old
  partial-wipe shape and must become "all seats except the caller";
- which `SettlementTest` / `TimeoutResolutionTest` replay cases fail because of that, by name;
- that this branch is **not mergeable alone** until that follow-up lands.

Also state, in `KNOWN RISKS / ASSUMPTIONS`:

- the app's With-floor display still uses the two-arg (suit-blind) floor and was deliberately not
  changed;
- `BidBrain`'s bot heuristic still uses the two-arg floor (unless you changed it, and then why);
- any legacy state where `auctionSuit == null` keeps number-only jump-in.
