# Stage 0 — E9: durable recovery after interruption — report

- **Date:** 2026-10-30
- **Status:** **local legs PASS at all eight boundaries.** The **platform (real-eviction) leg is OPEN** and
  is the only remaining E9 item.
- **The question this report answers:** `docs/implementation/Stage0_Remaining_Steps.md` §1 (left verbatim;
  this report is the pointer's destination).
- **Code under measurement:** `native/stage0` (the probe). The engine is **unchanged**: `native/engine`
  differs from `main` by **0 bytes** across the four stage commits (`941c7d1`, `a2735eb`, `45b38d2`,
  `24606ea`).
- **The local harness and every artefact are outside the repo:** `D:/cache/stage0/e9s4/`
  (`worker.mjs`, `driver.mjs`, `results/e9s4.json`, `logs/boot-*.log`). Nothing in that directory is
  committed; §10 says how to reproduce it.
- **Labels:** **[M] measured locally** · **[m] modelled** · **[u] unknown** · **[x] needs a real account**.

**The one-sentence verdict:** on the local `workerd` runtime a match survived a hard kill at all eight
boundaries, recovered byte-identically against the uninterrupted JVM run at the same prefix, settled
**exactly once** with **0 duplicate ledger rows and 0 lost settlements**, and — at boundary 7 — took a
seat over through the engine's own RD19 clock with exactly one applied move and a duplicate delivery that
wrote nothing. What is **not** proven is a **real** eviction on the platform: that leg needs the retained
throwaway account and its own per-step approval, and it did not run.

---

## 1. Question and verdict

**Question (verbatim from §1 of the remaining steps):** does a match survive an interruption at **every**
boundary — the process, the object, the network, the quota — and come back with the same state, exactly
one settlement, and no lost or double-applied action?

**Verdict:** **PASS for the local leg, all eight boundaries.** The authoritative run (2026-10-30) is
**144/144 checks, 0 failures**, on **one tree, one branch, one state directory**, with **12 boots**,
**11 fault-injection kills at the eight boundaries + 1 final shutdown**, and every assertion read from
store contents **after** the kill — never from an in-flight promise, a timer, or an un-awaited return.

The pass criterion was the one §1.3 sets: at every boundary the recovered match is **byte-identical to
the uninterrupted match**, the settlement is applied **exactly once**, and the RP ledger has **0 duplicate
rows and 0 lost settlements** — with the same two negative controls E7 used (`driver.mjs`
`exactlyOnce()` used against a keyless control, §6).

**What this verdict is limited to.** The kills are `taskkill /pid <wrangler> /T /F` of the whole local
process tree: the object is re-constructed from its SQLite file and re-reads its rows — a cold start, not
a platform eviction. The D1 is Miniflare's local SQLite behind the real binding. So the verdict covers the
**recovery logic and the store contents**, not Cloudflare's eviction behaviour, its quota enforcement, or
its own metering. **The platform eviction leg [x]** (§1.3 step 2 of the remaining steps) is the only E9
item still open; it runs on the retained throwaway account, with each step individually approved, no
billing (`docs/decisions/2026-10-30-test-account-retained.md`).

---

## 2. Method

1. **One tree, one state directory, wiped at start.** `driver.mjs:847–852` removes `logs/`, `results/`
   and `state/` and recreates them before the runtime boots, so no run inherits a row from a previous one.
   The Durable Object's name **is** the match id (`e9-s<seed>-<gameType>-<scoringMode>`); every seed in
   this report is its own object.
2. **One unit per event — there is no batch endpoint on the object.** The §3 requirement ("delete
   `/advance` and any other multi-unit endpoint") is structural: the worker's routes are single-unit
   (`/step`, `/retry`, `/inactive/*`, the one-phase settlement routes, `/cardlog/step`), and `/step`
   asserts the produced unit's id equals `applied + 1` before it writes. A batching route cannot be
   reached because it does not exist.
3. **Write-ahead order: produce → INSERT → read back → apply.** `step()` asks the engine for the next
   unit, validates its id, inserts it under the rowid primary key, **reads the stored row back and
   requires it byte-equal**, and only then applies **the stored record** to the match. An action that is
   applied is therefore always an action that was persisted first.
4. **Cold start is fail-closed.** `recover()` re-creates the schema, reads every row, and requires
   **contiguity** (`count == max id`, `id == i + 1`), non-empty records, and the record's embedded
   sequence equal to its row id; it then replays them through the engine and requires
   `applied == count`. Any violation **poisons** the object: every write answers **503 refused** and
   nothing is guessed.
5. **Every digest compared with the uninterrupted JVM run at the same prefix.** The JVM test writes
   `e9-matrix-<seed>.prefixes` (one digest per prefix, `e9-matrix-<seed>.log`, `.digest`) and
   `e9-seed42.prefixes`; the driver compares the recovered object against **the prefix it claims to be
   at**, not merely against the final digest. This is what makes "recovered" mean *the same match*, not
   *a match that ends the same way*.
6. **Bundle parity.** The worker's copy of the Kotlin/JS bundle is byte-identical to a fresh production
   compile: `sha256(stage0.js) = 46153a843fa81132a8d22c17acc84403724c3e9c68ad4768f0746c6cf89ca372` in
   both places (the E2 method).
7. **The settlement is derived and idempotent.** A finished match **freezes** its intent in the object
   (`final` row) before **any** D1 write; every cold start runs the same reconcile rule. D1 is derived:
   `OUTBOX` (the intent queue), `RP` (`settlement` + `ledger`), `RP_BACKUP` (`ledger_copy`), each
   deduped by primary/unique key (§6).
8. **Kill machinery.** `taskkill /T /F` → `waitDown()` (the port stops answering) → `boot()` → `ready()`
   (the runtime serves again). The driver records every kill with its `taskkill` status; the evidence
   file holds **12 records with status 0** (11 fault-injection kills + the final shutdown) and **12
   boots**.

---

## 3. The eight boundaries

FULL/NORMAL, four bot seats, one seed per boundary (`50…57` = boundary `1…8`). "Rows at kill" is the
object's own `log` row count after the kill and before any recovery; "after recovery" is the final state
compared with the JVM run of the same seed.

| # | Boundary | Seed | Units | Where the kill lands | Rows at kill | Evidence after recovery | Settlement |
|---|---|---|---|---|---|---|---|
| 1 | **Before** a Trick checkpoint | 50 | 484 | before the 12th unit (a `T;` checkpoint) | **11** = JVM prefix 11 | recovered state **is** the uninterrupted run at 11 (digest `626b70fd`); final digest **`b7b6c3a7`**; every row byte-identical | 1 settlement × 4 ledger, Δ **64** = 64 |
| 2 | **After** the checkpoint write, **before** the ack | 51 | 410 | after the 13th unit (a `T;` checkpoint) | **13** = JVM prefix 13 | re-delivering the applied unit answers **`already`** and writes **0 rows**; the log does not grow; final **`dbcde317`** | 1 × 4, Δ **67** = 67 |
| 3 | **Mid-auction** (per-action persistence) | 52 | 438 | before the 6th unit (an auction action) | **5** = JVM prefix 5 | the auction resumes at the last persisted action; the resumed bid **is the same record** the uninterrupted run wrote; no bid lost or doubled; final **`e3a3aac5`** | 1 × 4, Δ **81** = 81 |
| 4 | **At the last trick** (With/Koz point) | 53 | 453 | at the last unit (the 453rd, the last `T;`) | **452**, and **0 settlements** | the store is **fully before** the final unit; nothing is settled before **or after** the kill; the final unit applies once; final **`063bdb6c`** | 1 × 4, Δ **75** = 75 |
| 5 | **During settlement** (RP + OUTBOX + backup) | 54 | 432 | before the ack | intent frozen (1 row) + RP holds 1 settlement + 4 ledger rows; outbox not acked | restart writes **0 RP rows** (the PK refuses a second settlement), the ledger is byte-identical, the outbox is acked **once**, reconcile = `finished-from-intent`; final **`76436647`** | 1 × 4, Δ **82** = 82 |
| 6 | **After settlement, before the result is read** | 55 | 437 | after the settlement, then a cold restart | settled | `/result` is **byte-identical across the kill**, the log is not re-run, **0 rows to any D1 store**, still exactly one settlement; final **`005477b0`** | 1 × 4, Δ **68** = 68 |
| 7 | **During a bot takeover / an alarm tick** | 56 | 424 | inside the alarm handler, before the marker | **0** | exactly **one takeover** and **one applied move** (digest = JVM prefix 1), byte-identical; a duplicate delivery answers `duplicate` and writes **0 rows** (§4) | none — the match deliberately never finishes |
| 8 | **At quota exhaustion** | 57 | 439 | relay refused **507**, then a kill with the intent pending | intent frozen + queued; **0 RP rows** | the match is **not cancelled**; the intent stays pending; it lands **exactly once on the next boot** (RP 10 local D1 rows, outbox ack 1, backup 12); final **`2e39c379`** | 1 × 4, Δ **77** = 77 |

**Per-boundary note on the digests.** `b1…b6` and `b8` above are the JVM's own final digests for those
seeds, and each is also the digest of the recovered object at the same unit count, row for row
(`stateDocument()` — the transient parts included: the in-flight trick, the remaining hands, the
auction in progress). Boundary 7 is the exception by construction: that match never finishes, so its
evidence is the **prefix-1** digest (`c2bdb9f4`), matched row for row, and no final digest exists.

**Boundary 7's three kill points** (the brief's (c)) are covered in §4: the matrix row above (kill before
the marker, seed 56), the kill **after the takeover row, before the ack** (seed 58), and the kill **while
the replacement clock is re-armed** (seed 59). All three end with exactly one takeover per decision point
and exactly one applied move.

---

## 4. Boundary 7 — the takeover is the engine's decision; the alarm is only its trigger

**The decision is not re-implemented.** `native/stage0/src/commonMain/kotlin/com/estemshan/stage0/TakeoverProbe.kt:31`
wraps `InactivityClock` and `SeatControl.takeOver` and renders their verdict: `takeover`, `duplicate`
(a borrowed seat is idempotent by construction), `wait` below the threshold, or `refused` for an
untrustworthy stored clock (`InactivityClock.fromStored`'s fail-closed rule). **RD19's 50 s is unchanged**
and is never re-typed in the worker: the arm instant is **derived** as
`now + thresholdMillis − inactiveMillis` from the two stored fields (`accumulatedMillis`,
`openWindowStartMillis`), and the handler holds no timer of its own.

**The marker is stamped from the engine's own position.**
`native/stage0/src/commonMain/kotlin/com/estemshan/stage0/DurableMatchProbe.kt:634` `positionJson()`
returns the round, the count of **finished** tricks in it, the seat that owes the move, the phase and the
log head. The trick count uses the same basis as `InactiveTakeover.trickNo(cardsPlayedInRound) =
cardsPlayedInRound / 4` (`native/engine/.../InactiveTakeover.kt:122`), so the marker
(`e9TakeoverMarker`, the `InactiveTakeover` field shape) and the move the borrowed seat plays cannot
disagree about which trick a takeover interrupted.

**The store shape keeps facts, not copies.** `seat_clock(seat, decision_point, accumulated_ms,
open_since_ms)` holds no deadline — the deadline is a function of the two RD19 fields, and a stored copy
would be free to drift. The window is **bound to the decision point (the log head)** it opened at, so a
log that has moved on cannot be charged to it. `seat_control(seat, borrowed, taken_at_ms, decision_point,
round, trick)` is the marker; `borrowed = 1` alone decides that the seat is the bot's.

### 4.1 The three kill points

| Kill point (brief (c)) | Seed | What the restart had to judge | Evidence |
|---|---|---|---|
| **Before the marker/move are written** (inside the handler, after the verdict) | 56 (matrix) | nothing was written; the stored window is already due | `rearmFromStore()` derives the **same** due instant from the two stored fields, the alarm fires **on boot with no client action**, and the takeover completes: marker `takenOverAt`, round 1, **trick 0**, one move (digest = JVM prefix 1), row byte-identical |
| **After the takeover row, before the ack** | 58 | the marker and one move are committed; the replacement clock never ran | after restart: `borrowed = true`, **exactly one** move, digest = prefix 1, byte-identical. The stored clock still carries the old window (`openSince` set) — **the marker alone decides**; the next delivery is judged `duplicate`, **0 rows**, and the move count stays 1 |
| **While the replacement clock is re-armed** | 59 | the marker, the move **and** the zeroed clock are committed; only the ack is missing | after restart: `borrowed = true`, clock `accumulated = 0`, `openSince = null`, `decisionPoint = 1`; exactly one move, byte-identical; the next delivery is `duplicate`, 0 rows |

### 4.2 The duplicate path, and the decision table

**A duplicate delivery is a no-op by construction.** Once `borrowed = 1`, the handler returns
`duplicate` **before running any statement** and disarms; the marker is not re-stamped, the clock does not
move, no boundary-7 table gains a row, and the move count stays 1. A window whose log has moved on
(`decision_point != the engine's applied count`) is **stale** and also writes nothing.

**The decision table (P9, seeds 60/61).** A window opened 5 s ago writes **1 row** and arms exactly
`t0 + 50,000 ms`. At **20 s** the verdict is `wait`, 0 rows, the same deadline stays armed; at
**49,999 ms** still `wait`, same deadline, no move. A **cold restart** re-arms at the **same instant** and
reaches the **same verdict from the same rows**. At exactly **50,000 ms** the seat is taken over and
**one move (id 1)** is applied. A **late** delivery (the window 52 s old because the process was down)
still decides correctly: `takenOverAt ≥ open + 50,000`, one move.

**The pass criterion for (a) and (b) is met on local evidence:** the decision is a pure function of the
stored rows plus the wall-clock reading — a restarted object reached it again without a client request,
and a duplicate delivery wrote nothing.

---

## 5. Design A vs Design B — measured, and the ruling stands

Design A is "B plus one row per card played" (`CardLogProbe.kt`, exported as `e9CardLog`). It was built
far enough to be counted **on the same per-unit driver** as B: the stream is inserted one unit per event,
each unit asserted to cost **1 row and 1 change**, the store's row count compared with the unit count, and
the workerd count compared with the JVM's. The two variants are:

- **A1 — checkpoint + cards:** `bids + 5 × tricks` rows = **B + 936** per FULL match.
- **A2 — cards only:** `bids + 4 × tricks` rows = **B + 702**.

**Measured per seed** (JVM logs `e9-matrix-{42,50..59}.log`; every FULL match has 234 tricks = 18 rounds ×
13, so cards = 936):

| Seed | B rows | bids | tricks | **A1** | **A2** |
|---|---|---|---|---|---|
| 42 | 411 | 177 | 234 | **1,347** | **1,113** |
| 50 | 484 | 250 | 234 | **1,420** | **1,186** |
| 51 | 410 | 176 | 234 | **1,346** | **1,112** |
| 52 | 438 | 204 | 234 | 1,374 | 1,140 |
| 53 | 453 | 219 | 234 | 1,389 | 1,155 |
| 54 | 432 | 198 | 234 | 1,368 | 1,134 |
| 55 | 437 | 203 | 234 | 1,373 | 1,139 |
| 56 | 424 | 190 | 234 | 1,360 | 1,126 |
| 57 | 439 | 205 | 234 | 1,375 | 1,141 |
| 58 | 430 | 196 | 234 | 1,366 | 1,132 |
| 59 | 426 | 192 | 234 | 1,362 | 1,128 |
| **range** | 410–484 | 176–250 | 234 | **1,346–1,420** | **1,112–1,186** |

**Design A's cost is a constant added to B, on the same seed.** On seed 42, A1 adds 936 rows (**3.28×**
that seed's 411) and A2 adds 702 (**2.71×**). The ratio does **not** use B's 427.92 model basis.

**The capacity consequence at the 90,000-row guardrail and the 100,000-row hard limit:**

| Design | rows/match | rows at 80 matches/day | % of the hard limit | matches/day at 90,000 | at 100,000 |
|---|---|---|---|---|---|
| **B (ruled)** | 410–484 [M]; 427.92 [m] | 32,800–38,720 [M]; 34,234 on the model | 32.8–38.7% [M]; **34.2%** on the model | 185–219 [M]; 210 [m] | 206–243 [M]; 233 [m] |
| **A1** (checkpoint + cards) | **1,346–1,420** [M] | **107,680–113,600** | **107.7–113.6% — over the hard limit** | **63–66** | 70–74 |
| **A2** (cards only) | **1,112–1,186** [M] | **88,960–94,880** | **89.0–94.9%** | **75–80** | 84–89 |

**Ruling.** **B stands.** A1 does not fit at 80 matches/day under any seed in the range; A2 sits under the
guardrail **only at the lowest-bid seeds** (80/day is its best case, not its shape) and leaves no headroom.
Design A was **measured, not adopted**, so **no recovery proof is owed for it** — it was never run as a
recovery design, only counted per unit.

---

## 6. Authority and settlement

**The Durable Object's SQLite is the authority.** It holds the match log (`log(id, rec)`, one row per
auction action and one per completed Trick) and the frozen settlement intent (`final(payload, cleaned)`).
The log is the only source of match state; the intent is written **before** any D1 statement.

**D1 is derived, and deduped by key:**
`OUTBOX` holds one queue row per match (`match_id`, `seq`, `relayed`, payload); `RP` holds the
`settlement` row (PK `match_id`) and its four `ledger` rows (PK `match_id, seat`); `RP_BACKUP` holds
`ledger_copy`. The settlement row and its four ledger rows are **one batch** (five statements); a repeat
that trips the primary key is compared row for row with the stored settlement and either confirmed as a
duplicate (0 rows, `applied: false`) or refused as a **split brain** — never applied twice.

**Reconcile is the only recovery rule, and it runs on every cold start:**
a frozen intent → finish it idempotently (`finished-from-intent`); a complete log with no intent → derive,
freeze, settle (`derived-and-settled`); an unfinished match → play; a cleaned intent with rows → cleanup.
A D1 failure leaves the intent **pending** — it is not treated as a corrupt match.

**The end-of-run audit (P13), read from D1 with no match in the path [M]:**

| Check | Value |
|---|---|
| Finished matches → settlements | **7 → 7** |
| Ledger rows | **28** (4 per match), no more |
| Ledger Δ sum = settlements' total Δ | **514 = 514** |
| Queue rows relayed exactly once | **7 / 7** |
| Backup holds every ledger row | **28 / 28** |
| Derived-row writes made by the restarts | b5: **0 RP** (backup catches up, 12 local D1 rows); b6: **0** to any D1 store |

**The negative control fails, as it must [M].** The same check run against keyless tables written twice
with the same payload reports **2 settlements, 8 ledger rows, Δ 154 vs 77**, and the `exactlyOnce()`
check fails on all three counts — the check is not blind.

**Settlement-path non-regression.** The settlement arithmetic is stage 3's `settleOneMatch()` (lifted out
of E7's stream, unchanged), and E7's documented stream fingerprint `cbfe5a72` is unchanged on this tree
(the `SettlementStreamTest` is green in the same 48/48 suite, and `:engine` is untouched).

---

## 7. The alarm's cost

Measured on the local cursor inside the handler (seed 62); the platform's **request** side is E12-c's.

| Operation | Local [M] | E12-c platform [M] |
|---|---|---|
| `setAlarm()` | **0 SQL rows**, one armed alarm | **0 rows, 0 requests** |
| one alarm fire | **1 handler invocation** (`alarmFires` + 1) | **1 DO request** |
| an early fire (below 50 s) | **0 rows**, and it re-arms the **same** deadline | 0 rows when the handler persists nothing |
| a takeover fire | **3 rows** — the marker, the one move, the replacement clock | (request side only) |
| opening the window (the app's own write) | **1 row** | — |

**`total_changes()` is not the metered count.** Workerd's own alarm bookkeeping moves through the same
SQLite database, so `total_changes()` counts it; every figure above is the sum of the handler's **own
statement cursors** (`rowsWritten`), which is what E12-a proved the platform meters.

---

## 8. Residual limits — what this report does not claim

1. **Local only; no real eviction.** The kill is `taskkill /T /F` of the local runtime. The platform
   eviction leg [x] is open and needs account B with per-step approval; nothing here is evidence about
   Cloudflare's eviction timing, its recovery from it, or its metering of the object's writes.
2. **Boundary 7 was exercised end-to-end at an auction decision point (trick 0).** The marker's trick
   basis is the engine's finished-tricks count via `positionJson()`, and the `InactiveTakeover`
   arithmetic is unit-tested in `native/engine/src/jvmTest/.../InactiveTakeoverTest.kt`, but an
   in-play takeover (trick > 0) is **engine-derived and unit-verified, not exercised end-to-end**.
3. **The 1-row/1-change assertion covers the units written after each restart and all of Design A's
   stream.** The first few units written before a kill are evidenced by the store's row count and the
   byte-identical prefix digests, not by a per-unit cost assertion.
4. **Design A was measured per unit, not replayed.** It has no recovery proof and none is owed — it was
   not adopted.
5. **One shape only:** FULL/NORMAL, four bot seats, seeds 42 and 50–62. MINI and Classic are covered by
   the earlier E9 legs, not by this matrix.
6. **Server-side idempotency only.** The probe proves re-delivery of the **stored** record (`/retry`
   answers `already`); the product's client-side durable intent (the app's own queue) is out of scope.
7. **The local D1 is Miniflare's SQLite.** E8 — real D1 atomicity, triggers and query limits — remains
   open by its own report; the local D1 legs exercised the real DO→D1 binding, not the platform's limits.

> **POINTER NOTE (2026-10-31 — E8's local legs have now run; item 7 is left verbatim).** E8 ran
> locally on 2026-10-31 and PASSED its local criterion: the settlement batch is atomic at k = 0…4
> with **0 partial rows**, triggers abort the whole batch, and the documented 100-parameter /
> 100,000-byte limits are enforced (the 2 MB row cap and the 50-query Free cap are **not**). **The
> real-D1 leg remains [x]**, so item 7's caveat still applies to platform limits. Report:
> `docs/implementation/Stage0_E8_D1_Atomicity_Report.md`.

---

## 9. What this settles

1. **Ruling A's condition is discharged on local evidence.** The 2026-10-29 ruling made per-Trick
   persistence "subject to successful recovery validation"; on the local runtime it validated at **all
   eight boundaries** with exactly one settlement per match and no lost or double-applied action. Whether
   local evidence is enough for production — or the platform eviction leg must run first — is the owner's
   decision; the platform leg remains the only open E9 item.
2. **One action = one event = one row is a production rule (the k09 finding).** The write-ahead unit
   costs **1 row and 1 change**, asserted per unit after every restart and across Design A's 2,460
   measured units; the object exposes no endpoint that writes more than one unit per event.
3. **P3's gate state:** local evidence **passed**; the platform leg **open**; E8 still open by its own
   criterion. The owner decides the gate.

> **POINTER NOTE (2026-10-31 — the E8 half of §9.3 is now local evidence; the bullet is left
> verbatim).** E8's local legs PASS (`docs/implementation/Stage0_E8_D1_Atomicity_Report.md` §4, §7,
> §12); **E8 is still open by its own criterion for the platform**, so P3's gate is unchanged in
> kind: E9's platform eviction leg **and** E8's real-D1 leg.

---

## 10. Reproduction

Everything below ran locally on 2026-10-30: **no account, no token, no deploy, no installs.**

```bash
# 1. JVM anchors + the JS bundle (the probe and the engine; :engine unchanged)
cd native
JAVA_HOME=D:/Tools/jdk-17 ./gradlew :stage0:jvmTest :stage0:compileProductionExecutableKotlinJs \
  --rerun-tasks --no-build-cache

# 2. the worker's copy of the bundle must be byte-identical to the fresh compile
sha256sum D:/cache/stage0/e9s4/k/stage0.js \
  native/stage0/build/compileSync/js/main/productionExecutable/kotlin/stage0.js
# both: 46153a843fa81132a8d22c17acc84403724c3e9c68ad4768f0746c6cf89ca372

# 3. the matrix: wrangler dev --local on port 8984, state wiped by the driver
cd D:/cache/stage0/e9s4
"C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe" driver.mjs
```

The worker runs under `D:/cache/stage0/node_modules/wrangler/bin/wrangler.js` with
`WRANGLER_SEND_METRICS=false`, `XDG_CONFIG_HOME=D:/cache/stage0/config`, `--persist-to
D:/cache/stage0/e9s4/state`, `--ip 127.0.0.1 --port 8984`; Node is Gradle's own
`node-v22.14.0-win-x64` per `docs/engineering/NODEJS_SAFE_USAGE.md`.

**Seeds:** `50…57` = boundaries `1…8`; `58`/`59` = boundary 7's postwrite / postarm kills; `60`/`61` =
the decision table and the late alarm; `62` = the alarm cost; `42` = Design A.

**Artefacts (not committed):**

| Artefact | Where |
|---|---|
| The evidence file (144 checks, kills, phases) | `D:/cache/stage0/e9s4/results/e9s4.json` |
| The run log | `D:/cache/stage0/e9s4/driver-run.log` |
| The 12 boot logs | `D:/cache/stage0/e9s4/logs/boot-*.log` |
| The driver and the worker (local harness) | `D:/cache/stage0/e9s4/driver.mjs`, `worker.mjs` |
| JVM anchors (log, digest, every prefix) | `native/stage0/build/stage0-parity/e9-matrix-{50..59}.*`, `e9-seed42.*` |
| Design A's counts | `native/stage0/build/stage0-e9/e9-cardlog-42.json` |
| The probe sources (committed) | `native/stage0/src/commonMain/.../DurableMatchProbe.kt`, `TakeoverProbe.kt`, `CardLogProbe.kt`; `native/stage0/src/jsMain/kotlin/Stage0Exports.kt` |
| The JVM tests (committed) | `native/stage0/src/jvmTest/.../DurableMatchProbeTest.kt` |
| The engine (unchanged) | `native/engine/src/commonMain/.../InactivityClock.kt`, `InactiveTakeover.kt` |
