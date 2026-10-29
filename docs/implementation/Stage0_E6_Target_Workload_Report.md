# Stage 0 — E6: the target workload (100/125/150 matches/day) against the free tier

**Status: MEASURED + MODELLED 2026-10-29.** This is the exit evidence for the plan's E6 row
(`NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.4.3): "Is the primary usage scenario measurable at 90%
of the free tier? Drive a synthetic 90%-of-limit workload" — pass criterion "Measured headroom or
a documented overrun".

Branch `stage0/e6-target-workload`, base `e624879` (`stage0/e5-capacity-model` — E5 plus the
2026-10-29 owner rulings A–G, `docs/decisions/2026-10-29-stage0-capacity-persistence-transport-failover.md`).

## 1. What the experiment is

E6 runs under the owner's rulings, which change what "the primary usage scenario" is:

- the target is the **planning range 100 / 125 / 150 completed matches per day** (three labelled
  scenarios, not a forecast — ruling D);
- the transport is **WebSocket-preferred** with server-side **bots inside the authority**
  (rulings B/C), so E5's "one request per action" premise no longer holds;
- card play persists **per completed Trick**, the auction per action (ruling A, conditional on
  E9);
- the **90% quota guardrail** (ruling E) must be evaluated: does it protect ongoing matches, and
  when does it fire?

Per the record §8, every input below is labelled **[M] measured**, **[m] modelled (assumption
stated)**, **[u] unknown**, or **[x] needs a real Cloudflare experiment**. No real Cloudflare
account, deployment or quota-consuming experiment was run — E6 is the measured engine loads plus
the model the rulings require, with the unknowns named rather than guessed.

## 2. Method — the per-seat probe

E5's probe counted actions but never recorded **who** acted, which is exactly what the human/bot
mix needs. `native/stage0/src/commonMain/kotlin/com/estemshan/stage0/CapacityMixProbe.kt` replays
the same driver verbatim (same seeds, same roster, same guards) and attributes every quantity to
the acting seat: per-seat bid/card action counts, per-seat decide/apply CPU, and the completed-trick
count — the number of persistence writes card play costs under the ruled per-Trick boundary. E5's
own probe, document shape and fingerprints are untouched (separate file, separate hash), so both
experiments stay reproducible at the branch head.

The probe still plays all-bot matches (the harness's one honest way to finish a match); the
human/bot mix is applied **as a model on top of the measured per-seat loads** — no distribution is
invented. Batteries: seeds 1–50 FULL/NORMAL (primary) and 1–20 for the other three combos.
**All four count fingerprints are byte-identical between the JVM and workerd**:
`ee328556` / `31cacdd4` / `81ed9678` / `b1116c8d`. Same Node/wrangler evidence rules as E5
(portable Node 22.14.0 by explicit path, pinned local wrangler 4.155.0, no installs, local HTTP
only, `WRANGLER_SEND_METRICS=false`, **0 crashes** in the log; files written only under
`D:/cache/stage0/e6/` and the Gradle build dir).

## 3. What one seat costs (measured, FULL/NORMAL, 50 seeds)

| Measure | p1 | p2 | p3 | p4 |
| --- | --- | --- | --- | --- |
| Card actions per match | 234 | 234 | 234 | 234 |
| Auction actions (mean / min / max) | 51.4 / 46 / 66 | 47.6 / 40 / 62 | 47.3 / 36 / 63 | 47.6 / 40 / 60 |
| Brain CPU per match, ms (mean / p90) | 5.6 / 11 | 7.3 / 13 | 15.7 / 26 | 15.7 / 24 |
| Apply CPU per match, ms (mean / p90) | 5.3 / 11 | 5.1 / 9 | 4.6 / 7 | 5.5 / 9 |

Totals: **1,129.9 actions/match** (mean; 936 cards + 193.9 auction actions, p90 218; 3.48
all-pass redeals/match), **234 trick checkpoints** — the per-Trick persistence count — and ~10.8
auction actions per round. The seats are nearly balanced (each carries ≈ 25% of the traffic; the
HARD/EXPERT brains cost ~2.8× the EASY brain's CPU). MINI/NORMAL (20 seeds): 603.2 actions (520
cards, 83.2 auction, 130 checkpoints, 10 rounds).

Server engine CPU per match by mix (this run; wall-clock varies run-to-run — E5's leg measured
the all-bot case at ≈ 35 ms mean, this one at 67; both are orders of magnitude under budget):

| Mix | 4 humans | 3H+1B | 2H+2B | all-bot (E5's case) |
| --- | --- | --- | --- | --- |
| Server CPU, ms (mean / p90 / max) | 22.6 / 32 / 82 | 28.3 / 37 / 100 | 35.6 / 48 / 128 | 67.1 / 98 / 195 |

## 4. The revised per-match cost model (the ruled design)

Per FULL match, by seat mix — WebSocket transport, per-Trick persistence, bots internal:

| Quantity | 4 humans | 3H+1B | 2H+2B | Label |
| --- | --- | --- | --- | --- |
| Inbound messages (human actions) | 1,129.9 | 847.4 | 565.0 | **[M]** |
| DO request-units — paid-page reading: ⌈inbound/20⌉ + 4 connects + 5 lifecycle | 66 | 52 | 38 | inbound **[M]**; 20:1 **[m]** paid-page figure, free plan unpublished; lifecycle **[m]** minimal shape |
| Outbound messages (1 ack + 3 broadcasts per action) | 4,519.7 | 4,519.7 | 4,519.7 | **[m]** assumption: 4 per action |
| DO rows written (234 trick checkpoints + 193.9 auction actions) | 427.9 | 427.9 | 427.9 | checkpoints/auction **[M]**; 1 row/put **[m]** — Cloudflare does not publish the KV-API rows-per-put mapping (×2 bound carried) |
| D1 rows written (settlement: 1 result + 4 RP) | 5 | 5 | 5 | **[m]** |
| DO rows read (state per action) | ≈ 1,130 | ≈ 990 | ≈ 850 | **[m]** |
| Server engine CPU, ms | 22.6 | 28.3 | 35.6 | **[M]** |
| Alarms/timers | 0 | 0 | 0 | **[m]** minimal design; each `setAlarm` bills 1 row written — a turn-alarm design would add ≈ 13 rows/round |
| Reconnects + snapshots | — | — | — | **[u]** rate unknown; each ≈ 1 inbound message + snapshot read — immaterial vs the metering unknowns |

MINI/NORMAL, 4 humans: 603.2 inbound / 40 request-units (paid reading) / 213.2 DO rows / 5 D1
rows / ≈ 12 ms CPU.

## 5. The three scenarios against the free tier

Daily demand = matches/day × per-match cost, FULL baseline (the assumption Decision D requires
stated: one completed match ≈ one E5/E6 FULL match). Limits verified 2026-10-29 (E5 report §4).

**4-human FULL matches:**

| Meter (limit/day) | 100/day | 125/day | 150/day |
| --- | --- | --- | --- |
| DO requests, paid-page reading (100,000) | 6,600 — **6.6%** | 8,250 — **8.3%** | 9,900 — **9.9%** |
| DO requests, conservative reading — inbound **and** outbound 1:1 (100,000) | 565,900 — **566% ✗** | 707,300 — **707% ✗** | 848,800 — **849% ✗** |
| DO rows written, SQL-native (100,000) | 42,792 — **42.8%** | 53,490 — **53.5%** | 64,188 — **64.2%** |
| DO rows written, KV-API ×2 bound (100,000) | 85,584 — **85.6%** | 106,980 — **107% ✗** | 128,376 — **128% ✗** |
| D1 rows written (100,000) | 500 — 0.5% | 625 — 0.6% | 750 — 0.8% |
| DO rows read (5,000,000) | ≈ 113,000 — 2.3% | ≈ 141,000 | ≈ 170,000 |
| DO duration, **no hibernation** — 15-min match assumption, 0.125 GB (13,000 GB-s) | 11,250 — **86.5%** | 14,063 — **108% ✗** | 16,875 — **130% ✗** |
| DO duration, **hibernated** — ≈ 6 s active compute/match (13,000 GB-s) | 75 — 0.6% | 94 — 0.7% | 113 — 0.9% |
| CPU per message (10 ms) | ≤ 0.067 ms worst — **0.7%** | — | — |

MINI matches (4 humans) use 3,920 / 21,320 / 750 of the same meters per 100 matches — everything
proportionally lighter; the FULL baseline is the conservative case.

**The three decisive unknowns**, each able to flip a scenario on its own:

1. **Free-plan WebSocket metering** [u/x]: under the paid page's 20:1 inbound ratio the requests
   axis passes with ~10× headroom; if the free plan counted every message 1:1 (its limit text
   does not say), even 100 matches/day overruns 5.7×. **E12 verifies this on a real free
   account — it is the single biggest open fact in the capacity question.**
2. **Rows-per-put mapping** [u/x]: SQL-native writes fit all three scenarios (64% at 150); the
   KV-API ×2 bound overruns 125 and 150. E9's first real Durable Object settles it.
3. **Hibernation** [u/x]: without it, duration overruns at 125/150 (the 15-minute match is an
   **[m]** illustration, not a measurement — real wall duration is human-paced); with it, the
   axis is negligible. E12 owns the real number.

**E6's verdict, per its own pass criterion:** **measured headroom exists under the
paid-page/documented reading — and a documented overrun exists under the conservative readings.**
The plan's criterion anticipated exactly this fork; the fork is now explicit, quantified, and
owned by named experiments rather than hidden in an average.

## 6. The 90% guardrail (ruling E)

**Trip points** — matches/day at which a meter reaches 90% and new matches must stop (4H FULL):

| Meter | 90% trip at |
| --- | --- |
| DO requests, paid-page reading | ≈ 1,363/day — never trips at target usage |
| DO requests, conservative 1:1 | **≈ 16/day** — trips immediately at any scenario |
| DO rows written, SQL-native | ≈ 210/day — **never trips at 100/125/150** (150/day peaks at 64%) |
| DO rows written, KV ×2 | ≈ 105/day — **trips late in the day at 125/150** |
| DO duration, no hibernation | ≈ 104/day — trips late at 125/150 |
| DO duration, hibernated | ≈ 15,600/day — never trips |

**Reserve sufficiency** (the honesty requirement in ruling E §5): when a trip fires, an ongoing
match needs at most one more round plus settlement — 13 trick checkpoints + ≈ 10.8 auction
actions ≈ **24 DO rows** (measured per-round costs), or its remaining messages on the request
axis. The 10% reserve is 10,000 of each meter, so it sustains:

| Reading | Reserve covers |
| --- | --- |
| DO rows, SQL-native (trip never fires at target usage) | ≈ 416 concurrent tables |
| DO requests, paid-page reading | ≈ 151 tables |
| DO rows, KV ×2 bound | ≈ 11 tables — marginal |
| DO requests, conservative 1:1 | **≈ 1 table — insufficient** |

**Verdict: the 90% guardrail protects ongoing matches under the documented readings** (rows
SQL-native and requests paid-page), and under those readings it never even fires at target
usage. Under the **conservative requests reading it does not** — the reserve covers about one
ongoing table's worst-case remaining traffic — and that gap is **documented here** per ruling
E's own honesty requirement rather than claimed away. If E12 confirms 1:1 free-plan metering,
protecting ongoing matches requires either the backup/failover path (ruling F's missing
architecture) or a smaller outbound-message shape — a design consequence, not a measurement.
Two conditions attach in every reading: each meter must be monitored independently (ruling E
§5.1), and rerouted *new* matches need the backup server that ruling F flags as **not yet
designed** — without it, the correct behaviour at a trip is the explicit refuse/queue response
of ruling E §5.6, which is a product decision to design, not a capacity fact.

## 7. The finding

1. Under the ruled design (WebSocket, server-side bots, per-Trick persistence), the **measured
   engine loads leave 10–15× headroom on every meter at 100–150 matches/day** — requests
   (paid-page reading), rows written (SQL-native), CPU — and under those readings the guardrail
   never fires at target usage; its 10% reserve protects ongoing matches with two orders of
   magnitude to spare. Under the conservative readings the guardrail fires early and its reserve
   does **not** protect ongoing matches on the request axis (§6) — documented, not hidden.
2. The margin is **not yet proven**, because three unpublished/unmeasured platform behaviours
   each flip the upper scenarios to overrun: free-plan WebSocket metering, the KV-API
   rows-per-put mapping, and hibernation/duration. All three are E12/E9 work on a real Cloudflare
   account; none can be settled from a laptop.
3. E5's ≈ 1,135 requests/match and ≈ 1,135 rows/match are thereby **superseded as planning
   numbers**: the ruled design costs ≈ 66 request-units and ≈ 428 rows per FULL match (4 humans),
   with the conservative bounds stated alongside.

## 8. What E6 does **not** prove

- No real Cloudflare account, DO, or WebSocket was exercised — the platform-side inputs (metering
  ratios, rows-per-put, hibernation, duration accounting) remain **[u]/[x]**, owned by E12 and E9.
- The outbound-message shape (4 per action) and the lifecycle shape (5 HTTP + 4 connects) are
  **[m]** design assumptions; a real client will differ in detail.
- The 15-minute match duration is an illustration, not a measurement; human-paced wall time is
  unknown until a real closed test (M10's 12×14).
- Matchmaking, disconnect votes, takeover clocks and profile traffic are outside one match's
  cost; they add real requests and remain to be modelled when their services are designed.
- The FULL baseline assumption (Decision D) means the scenarios are FULL-sized; a real FULL/MINI
  mix shifts demand downward (MINI costs ≈ half), which is headroom, not risk — but the mix is
  the owner's unknowable until launch.

## 9. How to reproduce

```bash
# ── JVM leg ─────────────────────────────────────────────────────────────────
cd native && ./gradlew :stage0:jvmTest --rerun-tasks --no-build-cache
#   → native/stage0/build/stage0-e6/capacity-mix-jvm-*.json

# ── Server leg ──────────────────────────────────────────────────────────────
cd native && ./gradlew :stage0:compileProductionExecutableKotlinJs --rerun-tasks --no-build-cache
cp native/stage0/build/compileSync/js/main/productionExecutable/kotlin/*.js D:/cache/stage0/e6/
cd D:/cache/stage0/e6
WRANGLER_SEND_METRICS=false XDG_CONFIG_HOME=D:/cache/stage0/config \
  "C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe" \
  D:/cache/stage0/node_modules/wrangler/bin/wrangler.js dev --local --port 8975 --ip 127.0.0.1
curl "http://127.0.0.1:8975/?what=capacity-mix&first=1&count=50&gt=FULL&sm=NORMAL" \
  -o workerd-mix-FULL-NORMAL.json

# ── Compare ────────────────────────────────────────────────────────────────
# counts.fingerprint must be ee328556 on both legs (31cacdd4 / 81ed9678 / b1116c8d
# for the secondary batteries). Timings are compared against the 10 ms budget, not each other.
```

## 10. What the owner still has to decide

1. **Authorize E12's free-account experiment** (a real WebSocket DO on a real free plan) — it
   decides the single biggest unknown and thereby whether the scenarios pass by 10× or fail by
   6×. No deployment or billing change is involved in *this* record.
2. **The refuse/queue response** at a guardrail trip (ruling E §5.6) — a product behaviour to
   design (copy, UX, retry), needed before P3 builds the guardrail.
3. **The failover architecture** (ruling F's flagged prerequisite) — unchanged; E6 gives it its
   demand numbers: at the conservative readings the trip fires early in the day, so the backup
   path is load-bearing, not theoretical.
4. **O-06** — unchanged; E6 is evidence, and the fork it documents is exactly what the ADR slot
   waits to read.
