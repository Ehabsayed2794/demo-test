# Stage 0 capacity, persistence, transport and failover — owner rulings A–G

- **Date:** 2026-10-29
- **Status:** RECORDED (documentation only). Seven rulings given together on 2026-10-29, while
  PR #190 (`stage0/e5-capacity-model`, the E5 capacity model) is open and unmerged.
- **Labels:** **[D]** owner-confirmed. Conditional approvals, planning scenarios and preferences
  are marked as exactly that — each section states its own strength.
- **Closes:** the "target usage is owner input E6 needs" slot named in
  `docs/implementation/Stage0_E5_Capacity_Model_Report.md` §8 — as a **planning range**, not a
  single number.
- **Does not close:** **O-06** (the runtime decision — unchanged; see §7), the paid-tier
  question, and the failover architecture itself (§6 records intent; the architecture is an
  implementation prerequisite).
- **Refines:** **Q13** (`OPEN_QUESTIONS.md` §D — "at 90% the backup hosts new matches only;
  running matches stay; results wait for one central RP database"). Q13 stays the approved
  direction; this record adds the guardrail mechanics, the transport and persistence design the
  capacity work must assume, and flags what the plan's "backup" does not yet specify (§6).

These are rulings about **design assumptions and planning numbers**, not about measured facts.
Nothing here claims the architecture, the recovery guarantees or the capacity numbers are proven:
E6, E9 and E12 still have to measure them (§9).

## 1. Decision A — persistence boundary: the completed Trick [D] — **conditional**

**Ruling.** The intended normal persistence boundary for card play is **the completed Trick**,
preferred over persisting after every individual card action — **subject to successful recovery
validation**. Auction/bidding decisions remain persisted **per action** unless a later measured
and approved decision changes that.

This is a **conditional architecture decision, not permission to ship weaker durability before
testing**. **E9 must validate the full recovery protocol before this persistence policy is
approved for production use.** E9 must address, at minimum:

> **POINTER NOTE (2026-10-30 — E9 has now run locally; the paragraph above is left verbatim).**
> **E9's local legs PASS at all eight interruption boundaries** — the recovered match is
> byte-identical to the uninterrupted run, the settlement is applied **exactly once**, and the
> ledger has **0 duplicate rows and 0 lost settlements**
> (`docs/implementation/Stage0_E9_Recovery_Report.md` §3 for the boundary table, §4 for the
> takeover/alarm boundary, §6 for authority and settlement, §9 for what this discharges). **This
> discharges the condition on local evidence only**: the **platform eviction leg [x]** on the
> retained throwaway account is the one open E9 item (§1, §8.1), and the owner decides whether
> local evidence suffices for production.

1. A server interruption during an **incomplete** Trick.
2. Preservation and recovery of the last committed state (the last completed-Trick checkpoint).
3. The possibility of **up to three accepted card actions** existing after the last
   completed-Trick checkpoint (the fourth completes the trick and is itself the checkpoint).
4. Durable client intent where needed; server-side **idempotency keys** or equivalent
   deduplication; **sequence ordering**; and safe replay.
5. Whether players may already have **seen** an action that was not committed — and what the
   recovery does about the visible/committed gap.
6. Reconnect and retry behaviour, including how the server distinguishes an **accepted** action
   from an unaccepted one.
7. Hibernation/eviction and restart behaviour.
8. A **deterministic recovery test** demonstrating that the recovered match produces exactly one
   valid continuation with **no duplicated and no lost committed actions**.

**Explicit warning:** deterministic engine replay alone does **not** prove safe recovery. The
engine's deterministic recalculation (what E2/E3 measured) is a different property from durable
network/action recovery (what E9 must measure): the question is not "can the state be recomputed"
but "which actions were durably accepted, in what order, and what does a client that saw one of
them do after a restart".

## 2. Decision B — bots execute inside the authoritative server [D]

**Ruling.** Bots execute **inside the authoritative server**, not through simulated client
requests.

- A server-side bot's decision and action are **internal server work**.
- Bot decisions must **not** be counted as inbound requests from an Android client.
- Bot CPU, state transitions, **database writes, timer operations and any actual internal service
  calls are still included in capacity accounting** — a bot action avoids a client-to-server
  network request but is **not free**.
- The capacity model must distinguish **four-human matches** from matches with **one or more
  server-controlled bot seats** (the E5 probe's all-bot roster is a worst case for bot CPU, not a
  request-traffic baseline).
- Server-side bot execution must preserve the canonical game rules, legal-action validation,
  timeout behaviour and the authoritative match state — a bot seat is validated by the same
  reducers as a human seat.

## 3. Decision C — WebSocket transport preferred [D] — **direction, metering unverified**

**Ruling.** WebSocket is the **preferred transport** for the authoritative match runtime.

- E5/E6 capacity accounting must use the **actual request and message paths of this design**,
  not the assumption that every action is a separate HTTP request.
- Incoming **and** outgoing WebSocket messages are accounted for according to the applicable
  Cloudflare plan and the current **documented or measured** billing behaviour.
- WebSocket **does not by itself reduce database writes** and **does not solve match recovery** —
  it changes the requests axis only (E5's report already recorded this: §5 item 2).
- Connection establishment, **reconnects, reconnect snapshots, authentication and lifecycle
  calls** belong in the model.
- The specific **free-tier metering behaviour must be verified** against current official
  documentation **and a safe experiment** before it is treated as fact. The 20:1 incoming-message
  ratio quoted in the E5 report is the **paid pricing page's** figure; the free plan's limit text
  says messages count as requests without publishing the ratio. Until verified, it is an
  **assumption labelled as an assumption** — E12 owns the verification.

## 4. Decision D — planning range: 100–150 completed matches per day [D] — **scenarios, not a forecast**

**Ruling.** The planning target is a **range: 100–150 completed matches per day**. The owner
cannot currently predict a single reliable number, and is **not** required to pick one before E6
can explore the range.

Stage 0 capacity work uses three clearly labelled scenarios:

| Scenario | Target |
|---|---:|
| Lower case | **100** completed matches/day |
| Middle case | **125** completed matches/day |
| Upper case | **150** completed matches/day |

Qualifications that bind how the numbers are used:

- These are **planning scenarios, not a promise of expected demand or a measured forecast**.
- **E5's FULL match is the reference baseline** when comparing against the existing model — the
  assumption "one completed match ≈ one E5 FULL match" is stated wherever used, not hidden.
- If the **FULL/MINI mix** or the **human/bot seat mix** materially affects the capacity
  estimate, the difference is shown **separately**. No distribution may be invented.
- Where useful, results are expressed as a **range across scenarios**; a single capacity number
  is not declared sufficient on its own.
- **E6 tests the target scenarios at the plan's defined 90% operating level** and reports
  headroom, overrun and the binding resource meter (§9).

## 5. Decision E — the 90% quota guardrail [D]

**Ruling.** At **90% of a relevant daily usage quota**, the runtime **stops starting new matches
on the primary** and preserves the remaining capacity for matches already in progress.

This is a **resource-protection rule**, not a claim that 90% is the ideal threshold for every
possible implementation. Required behaviour:

1. Monitor **each relevant quota independently** — not one combined utilization percentage.
2. At 90% of a binding daily quota, **prevent new matches from starting** on the primary runtime.
3. **Preserve the remaining quota as a safety reserve** for ongoing matches, their required state
   writes, settlement and recovery.
4. Route new matches to the **backup server** defined by the existing plan (§6), subject to the
   validation recorded there.
5. **Never terminate, silently cancel or abandon ongoing matches** because the primary reached
   the threshold.
6. If **no safe fallback capacity remains**, refuse or queue new matches with an **explicit,
   controlled response** rather than accepting matches that cannot be supported reliably.
7. Continue to record usage, errors, remaining capacity and fallback status for operational
   monitoring.

**Honesty requirement:** stopping new matches at 90% does **not** by itself guarantee enough
capacity for all ongoing matches. The reserve must be **checked against measured worst-case and
remaining-match costs**; if it is insufficient, the gap is **documented** instead of claimed as
protection.

## 6. Decision F — backup failover: new matches move, existing matches complete [D] — **architecture missing, flagged**

**Ruling.** When the primary approaches the defined 90% daily-quota threshold, **new matches
move to the backup server already referenced in the plan**, while **existing matches retain a
safe path to completion**.

**What the plan actually contains** (checked 2026-10-29):

- **Q13** (owner-approved, `OPEN_QUESTIONS.md` §D): "At 90% the backup hosts new matches only;
  running matches stay; results wait for one central RP database (provider/sync = engineering)."
  Today's §5 refines the same direction; Q13 remains its basis.
- The **E7** experiment and gate **G4**: a **backup policy** with ≥90% coverage of the primary RP
  store, an outbox, and recovery — i.e. a **backup/restore discipline for the RP store**.
- The plan's own constraint (§5.6 failure-mode F-02 and the "One RP source of truth" rule): "a
  backup is a copy, **never** a second authority".

**What the plan does NOT contain:** an **independent, quota-isolated secondary server** — a
running secondary authority with its own account/quota and separate failure domain that can
**accept a new match** when the primary is at quota. Nothing in the plan defines its runtime,
its state ownership, its quota, or the client redirect to it. **That architecture is an
implementation prerequisite and is flagged here as missing** — the owner's intended failover
behaviour is recorded, and it must **not** be implemented by silently renaming the backup *copy*
(E7/G4's artifact) into a failover *server*.

Before this is implementable, the design must establish:

1. What the backup server **is** (a running secondary authority, not a recovery copy) and which
   provider/account it runs on, with an **independent daily quota** and separate failure domain.
2. Whether it can **accept a new match** when the primary reaches quota — including how the new
   match's state, idempotency and settlement land on the secondary.
3. How **match ownership** stays single: exactly one server is the authority for a given match at
   any moment (no two servers simultaneously authoritative).
4. How **live state, committed Tricks, idempotency keys and settlement** remain consistent for
   matches that complete on the secondary — settlement still waits for the one central RP
   database per Q13.
5. That **active matches can remain on the primary** while only new matches move.
6. How the **client reconnects to the correct authority** after failover (discovery/redirect),
   including a client that reconnects mid-match to a server that is no longer the authority.

## 7. Decision G — provider preference: Cloudflare on cost grounds [preference only]

**Ruling.** Cloudflare is **preferred on cost grounds**; Firebase is **not** the preferred
alternative for this investigation.

- This is a **preference, not a production-runtime approval**. **ADR 0003 / O-06 retains its
  actual status** (option B *not approved*) until the required Stage 0 evidence is reviewed and
  the owner explicitly approves the final runtime.
- **No Firebase-versus-Cloudflare cost study is started** unless the owner later reopens that
  question.
- The **no-Blaze / $0-marginal-infrastructure constraint stays in force** (Q43) unless the owner
  explicitly changes it.
- **No paid Cloudflare subscription is authorized** by these decisions. Paid-plan figures in the
  E5 report are context, not a plan.
- Cloudflare **Free feasibility remains conditional** on measured request, storage, duration and
  recovery behaviour (E6/E9/E12).

## 8. The E5/E6 capacity-model recalibration (required)

The owner's decisions change the assumptions the E5 model was built under, so its capacity
numbers are **preliminary** and must be recalculated for the chosen design:

- The **≈ 1,135 requests/match** figure is the **action-count-based model** of measured engine
  traffic (the E5 probe's all-bot replay). It must **not** be carried forward as a confirmed
  production value; E6 maps the actual architecture's traffic (WebSocket + server-side bots).
- The **≈ 1,135 writes/match and its ×2 factor are modelled, not measured**; the revised storage
  model labels its assumptions separately and uses the **actual persistence protocol**
  (per-Trick checkpoints per §1, per-action in the auction).
- The revised model distinguishes: (1) human-originated actions; (2) internal server-side bot
  decisions; (3) server-only round/Trick transitions; (4) WebSocket messages and
  connection/lifecycle requests; (5) database writes and reads; (6) alarms/timer updates,
  cleanup/delete operations and indexes; (7) reconnects, snapshots, retries, timeout takeover
  and settlement; (8) FULL/MINI matches and the human/bot seat mix; (9) request quotas,
  rows-written quotas, duration, CPU and storage **as separate meters**.
- Capacities are recalculated for **100, 125 and 150 matches/day** with the WebSocket and
  server-side-bot paths, and every input is labelled **measured, modelled, unknown, or needs a
  real Cloudflare experiment**.

## 9. Experiment links made explicit

The acceptance criteria of E6, E9 and E12 are **unchanged**; what changes is the required
content, recorded here and pointed to from the plan. Any future criterion revision gets its own
dated pointer with a reason — none is silently edited.

- **E6 — capacity under the target workload.** Uses the **100 / 125 / 150** completed
  matches-per-day scenarios with the intended **WebSocket and server-side-bot design**;
  identifies the **binding quota**; measures or estimates headroom; reports whether the **90%
  guardrail protects ongoing matches** (§5's honesty requirement applies).
- **E9 — recovery and persistence.** Validates the **completed-Trick persistence design**
  (§1's eight points), **interrupted-Trick recovery**, **durable intent/replay/idempotency**,
  and the behaviour of ongoing matches when the primary **reaches quota or becomes unavailable**
  (§5 + §6).

  > **POINTER NOTE (2026-10-30 — E9 has now run locally; the bullet is left verbatim).** The
  > completed-Trick design was validated at all eight boundaries, with durable intent/replay, a
  > per-action auction resume, and the quota-exhaustion boundary exercised (the match is not
  > cancelled; the pending intent settles later) —
  > `docs/implementation/Stage0_E9_Recovery_Report.md` §3 (boundaries 1–8), §6 (intent and
  > dedup), §9 (ruling A's condition discharged on local evidence). **The platform eviction leg
  > [x] is open** (§1, §8.1); Cloudflare's own eviction is not measured.
- **E12 — WebSocket, hibernation and duration.** Validates **WebSocket billing** (§3's
  unverified free-plan metering), **hibernation behaviour**, object lifetime/duration
  consumption, **reconnect behaviour**, and how these affect the 100 / 125 / 150 scenarios.

## 10. Unresolved — not decided by this record

- Whether the **$5/month paid tier** is authorized in a future release (no paid approval given).
- The **final production-runtime approval under O-06** (unchanged).
- Whether the **backup design is an actual independent, quota-isolated server** able to accept
  new matches (§6: currently missing from the plan — prerequisite, not decided).
- **Exact WebSocket billing behaviour on the Free plan** where the documentation is unclear
  (E12 verifies; the 20:1 ratio is a paid-page figure).
- Actual **Durable Object rows-written cost, alarm cost, cleanup cost and duration per match**.
- The **FULL/MINI match mix and the real human/bot distribution** (no distribution invented).
- Whether the **90% safety margin suffices** for the actual distribution of ongoing matches
  (must be checked against measured worst-case costs, §5).

## 11. Sources affected by this record

| Source | Change |
| --- | --- |
| `docs/implementation/Stage0_E5_Capacity_Model_Report.md` | Dated addendum at the top marking §3–§5 as the preliminary action-count-based model; measured content left verbatim |
| `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.4.3 pointer | One dated pointer paragraph: rulings A–G, the E6/E9/E12 content requirements, target scenarios 100/125/150 |
| `docs/adr/0003-runtime-choice.md` (E5 section, option B) | Dated pointer under the E5 SLOT: rulings recorded, O-06 unchanged; "what it needs" updated to name the target range |
| `docs/decisions/OPEN_QUESTIONS.md` §B | One row pointing at this record (Q13 refinement noted) |
| Experiment rows E5/E6/E9/E12 (§5.4.3 table) | **Left verbatim** — criteria unchanged; the pointer above carries the content requirements |
