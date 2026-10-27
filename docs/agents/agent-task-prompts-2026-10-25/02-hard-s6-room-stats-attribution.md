# Hard task 2 — S6: ROOM/UNRANKED stats-only settlement + departed-seat attribution

You are one of six agents working in parallel on this repository. You have your own worktree and a
file-disjoint write scope. Do not touch any file outside it.

**Task class:** HARD. This changes what "settlement" means for two of the three match modes and adds
an attribution override to a scoring result. If you cannot finish, stop at a compiling, tested slice
and report `PARTIAL` with the uncompleted slice named.

## 1. Read first, in this order

1. `AGENTS.md`.
2. `docs/engineering/NODEJS_SAFE_USAGE.md`.
3. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — only: **O-04 / A7.5**, **A8.4 / Q38**, **Q46 / N-1**,
   the **T-19** rows "Stats, all modes", "Bot-filled ROOM, bot wins King", "Bot row attribution",
   "Placement gate", "UNRANKED during placement", and the **T-15** departure matrix.
4. `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` — §5.10 row **S6** and the ROOM-lifecycle row (E4.4).
5. The files in your write scope, fully.

`docs/**` is read-only. `firestore.rules` is frozen. `native/app/**` is out of scope.

## 2. Worktree

```powershell
git -C D:\demo-test worktree add D:\worktrees\agent-s6-room-stats -b agent/s6-room-stats 8b13b12
```

If the branch exists, use `agent/s6-room-stats-2`. Never `--force`. Never work in `D:\demo-test`.

## 3. What exists today (verified at `8b13b12`)

- `Settlement.decide(uid, match)` rejects a non-member, then returns
  `SettleDecision.NoOp(mode)` for **every** mode that is not `RANKED`. Its KDoc says "ROOM/UNRANKED
  never settle (RD28)".
- `SettlementDb.settleOnce(...)` handles that gate **before any transaction** and returns
  `noOpResponse(mode)` — so a ROOM/UNRANKED call today writes nothing, reads no profile, and never
  records a claim.
- `SettlementDb.foldRankedProgression(...)` is the RANKED path: it calls
  `alignSeatedProfilesToSeason`, computes RP through `resolveRanked` / `finalRpDelta` /
  `applyDemotionShield` / `updateHighestRank`, and writes **fourteen** fields per profile (RP, tier,
  division, shield, highest rank, season, placement state, five counters).
- `SettlementDb.parseRankedProfile(...)` is the one typed profile parse. It reads `rp`, `tier`,
  `division`, `highestRank*`, `seasonId`, `placementState`, `demotionShield` and fails closed on a
  contradiction. It also parses the five stored counters via `parseRankedStats(...)`.
- `accumulateRankedStat(stats, outcome, profile, mode)` (`:engine`, `RankedStats.kt`) is already
  **mode-agnostic**: Q46 removed the RANKED-only gate. It increments Games Played + one outcome
  counter, returns the stats unchanged for a placement profile (RD10), and never touches RP. It is
  exactly the fold you need — **do not edit it**.
- `placementOutcome(finalScores, seat)` (`:engine`, `Placement.kt`) maps a seat's final total to
  `MatchOutcome.KING/SECOND/THIRD/KOZ`, with every bottom-tied seat taking KOZ (all-four-tied is
  all-King).
- `Settlement.ConvergedMatch` carries `mode`, `status`, `order`, `players`, `completedRound`,
  `maxRounds`, `claimedScores` (`finalScores`), `claimedWinners` (`winnerIds`), `archives` — and
  **no departure provenance**.
- `Settlement.recompute(...)` replays every round archive and needs `finalScores`/`winnerIds` to
  cover exactly the seats. That is the RANKED authority path.
- `SettlementDb.SettlementTx.writeProfile(uid, fields)` is a **merge update** — it writes exactly the
  fields you pass and nothing else. `readProfile`, `readClaim`, `writeClaim` exist.
- `executedResponse(...)` returns `ok`, `settled: true`, `replayed: false`, `matchId`, `corrected`,
  `finalScores`, `winnerIds`, `claimedScores`, `claimedWinners`, `rounds`. `claimRecord(uid,
  response, movements)` stores `outcome` + `movements`; the replay path adds `you` only when
  `movements` has the caller.
- Three existing tests pin the old no-op and **must be updated by you**, not deleted:
  - `native/functions/src/jsTest/.../SettlementTest.kt` → `roomAndUnrankedAreExplicitNoOps`
  - `native/functions/src/jsTest/.../SettlementTest.kt` → `absentModeParsesAsRoomAndNoOps`
  - `native/functions/src/jsTest/.../RpApplicationTest.kt` → `roomAndUnrankedNeverTouchAProfile`

## 4. Required behaviour

### 4.1 A stats-only settlement decision

- `Settlement.decide` must still reject a non-member (`NOT_MEMBER`).
- For `MatchMode.ROOM` and `MatchMode.UNRANKED` it must return a decision that `settleOnce` routes to
  a **stats-only** path — not `NoOp`. Add a decision type (suggested `SettleDecision.StatsOnly(mode)`)
  or branch on `mode`; either is acceptable, but `NoOp` must no longer be the ROOM/UNRANKED answer.
  Update the KDoc that says ROOM/UNRANKED never settle.
- An absent/legacy `mode` still parses as `ROOM` (S43) and therefore takes the stats-only path.

### 4.2 The stats-only path

Do **not** call `recompute`, `readArchives`, `alignSeatedProfilesToSeason`, `foldRankedProgression`,
`resolveRanked`, `finalRpDelta`, `applyDemotionShield`, or `updateHighestRank`.

Instead, inside one transaction:

1. Read the claim. If it exists, replay it exactly as the RANKED path does (`replayed: true`,
   caller's `you` only if `movements` contains them). A stats-only claim stores an **empty**
   `movements` map, so the replay has no `you`.
2. Validate the already-converged document:
   - `status == "complete"`;
   - `completedRound + 1 > maxRounds` (the same completeness check `recompute` applies);
   - `finalScores` and `winnerIds` are present;
   - `finalScores.keys.sorted() == order.sorted()`;
   - every `winnerIds` entry is a seat in `order`.
   Fail closed with `failed-precondition` when any of these is false. Never invent a score.
3. For every seat in `order`:
   - resolve `uid` from `seats[seat]`; **skip the seat when that uid is not in `players`** (a bot uid
     is never attributed to a human);
   - read the profile (missing profile = fail closed, the match stays settleable);
   - `outcome = placementOutcome(finalScores, seat)`;
   - if the uid is **departed** (see §4.3), force `outcome = MatchOutcome.KOZ`;
   - `stats = accumulateRankedStat(seatStats, outcome, profile, mode)`;
   - write **only** these five fields via `tx.writeProfile`:
     `rankedGamesPlayed`, `rankedKingCount`, `rankedSecondCount`, `rankedThirdCount`,
     `rankedKozCount`.
4. Write the claim with `executedResponse(...)` and an **empty** movements map.
5. Return the response with **no `you`** and `settled: true`. The existing
   `SettlementPort.Settled` consumer must treat it as settled with no RP movement.

Hard prohibitions — a single one of these fails the task:

- Do **not** write `rp`, `tier`, `division`, `demotionShield`, `highestRankTier`,
  `highestRankDivision`, `seasonId` or `placementState`.
- Do **not** read or write `seasons/current`.
- Do **not** append to the RP audit ledger (`SettlementLedger.recordSettlement`). A console line for
  retry forensics is fine; a ledger document is not.
- Do **not** add a departure-charge consumption path. The forced KOZ outcome is the **only** record
  for a departed human in this task — never also fold a `KozCharge`, or the same departure counts
  twice.
- Do **not** change `RankedStats.kt`. Its placement gate and mode-agnostic fold are already correct.
- Do **not** touch `native/functions/index.js` — the existing `settleMatch` callable is reused.

### 4.3 Departure provenance and the forced-KOZ override

Add the provenance to `ConvergedMatch` (parsed from the same match `fields` the gate already has, so
no new read and no new race):

- `leftUids` — explicit Leave Match (S72).
- `timeoutRemovedUids` — the 15-timeout counter (S61).
- `removedUids` — Vote Kick (S60).
- `takeoverRemovedUids` — the **7th inactivity takeover**, written by the parallel S7 task.

All four are **absent ⇒ empty**; parse each defensively (a non-list reads as empty, a non-string
element is skipped or fails closed — pick one and test it). Expose them as one `departedUids` set, or
as four lists plus a computed union; either is fine, but the union must be used for the override.

`takeoverRemovedUids` does not exist on `8b13b12`. Parse it as a raw field name:

```kotlin
private const val TAKEOVER_REMOVED_UIDS_FIELD = "takeoverRemovedUids"
```

Do **not** add it to `MatchModels.kt` — another agent owns that file. Do **not** wait for that agent.

Deliberately **not** forced, and reported as a gap:

- `disconnectReplacedUids` — a permanently replaced disconnect keeps its own charge table (A7.4:
  ROOM none, UNRANKED 2 Koz) and that consumer does not exist yet. Do not force KOZ for it and do not
  invent a charge. State this in `HANDOFF NOTES`.

Attribution rules, precisely:

- A departed human is KOZ **even if the bot playing their seat finished as King**. That is the whole
  point of A7.5.
- A seat whose uid is not in `players` is a pure bot seat and contributes **nothing** to any human
  profile.
- The three (or fewer) completing humans keep their own placements, unaffected by the override.
- A departed human still gets **+1 Games Played** (A8.4/Q38) — `accumulateRankedStat` does that.
- A placement profile (RD10) still counts toward **no** statistic, including for a departed human.
  `accumulateRankedStat` already returns unchanged; do not work around it.

### 4.4 Idempotence

The claim is the once-only record. Calling `settleMatch` twice for the same match must:

- write each profile's counters exactly once;
- return `replayed: true` on the second call;
- never append a second claim, a second ledger record, or a second correction.

Add a test that calls the path twice against a stateful fake and asserts the counters moved once.

## 5. Write scope

**Allowed to modify**

- `native/functions/src/jsMain/kotlin/com/estemshan/functions/Settlement.kt`
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/SettlementDb.kt`
- `native/functions/src/jsTest/kotlin/com/estemshan/functions/SettlementTest.kt`
- `native/functions/src/jsTest/kotlin/com/estemshan/functions/RpApplicationTest.kt`
- **New** test files you own, e.g.
  `native/functions/src/jsTest/kotlin/com/estemshan/functions/StatsOnlySettlementTest.kt`.

**Forbidden**

- `native/engine/**` (including `RankedStats.kt`, `Placement.kt`, `LeaveMatch.kt`).
- `native/services/**` (including `MatchModels.kt`).
- `native/app/**`, `native/functions/index.js`, any `*.gradle.kts`, `firestore.rules`,
  `firebase.json`, `.github/**`, `docs/**`.
- `SeasonReset*.kt` and `Timeout*.kt` / `DisconnectVote*.kt` / `SeatReclaim.kt` — other agents own
  them.
- Any file that already differs from `8b13b12` in your worktree. If one does, stop and report it.

## 6. Acceptance criteria

Mark each `PASS` / `FAIL` / `NOT RUN` with the test that proves it.

1. `ROOM` returns a stats-only decision, not `NoOp`; `UNRANKED` does the same; absent mode behaves as
   ROOM.
2. A non-member is still rejected.
3. A ROOM completion increments Games Played and exactly one outcome counter for each human seat.
4. An UNRANKED completion does the same.
5. No ROOM/UNRANKED settlement writes `rp`, `tier`, `division`, `demotionShield`, `highestRank*`,
   `seasonId` or `placementState` — assert the exact written field set, not just the values.
6. No ROOM/UNRANKED settlement reads or writes `seasons/current`, and none appends to the RP ledger.
7. A departed human (any of `leftUids`, `timeoutRemovedUids`, `removedUids`,
   `takeoverRemovedUids`) is recorded as KOZ even when their seat's final total is the highest.
8. A departed human still gains +1 Games Played.
9. A pure bot seat (uid not in `players`) writes nothing to any profile.
10. A completing human keeps their true placement in the same match as a departed human.
11. A placement profile's counters are unchanged.
12. A second call replays: `replayed: true`, no `you`, counters unchanged, one claim document.
13. `SettlementTest.roomAndUnrankedAreExplicitNoOps` is rewritten (not deleted) to assert the new
    behaviour.
14. `SettlementTest.absentModeParsesAsRoomAndNoOps` is rewritten to assert stats-only for absent mode.
15. `RpApplicationTest.roomAndUnrankedNeverTouchAProfile` is rewritten to assert RP/rank/season are
    untouched while counters move.
16. `:functions:jsNodeTest` is green, or `NOT RUN` with the exact runtime error.

## 7. Commands

```powershell
native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest
```

Do not run `npm`, `npx`, `npm ci`, or `node`. Do not run the full CI command.

If `:functions:jsNodeTest` cannot run because no approved Node 22 runtime is present, say so
explicitly and report every JS test as `NOT RUN` — a compile-only run is not a test pass.

## 8. Report

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

In `HANDOFF NOTES`, state:

- the exact five field names written and the exact field set asserted untouched;
- the provenance lists you consume and the one you deliberately do not (`disconnectReplacedUids`);
- that the departure **charge** is still unconsumed for ROOM/UNRANKED — this task records the
  outcome only;
- that `takeoverRemovedUids` is read as a raw field and will start arriving when S7 lands;
- any behaviour you had to change in a test and why the old assertion was the stale rule.
