# 2026-10-21 — Owner rulings of record: server-authoritative Ranked, rules rulings, stats, backup, retention

**Status: RECORDED (documentation only).** Source: owner messages of 2026-10-21 (the architecture
decision brief, the replanning brief, and the decision list of this session). Nothing here changes
code, rules, UI, Functions, or infrastructure. No test was run to produce it.

Labels used below: **[D]** owner-confirmed in the conversation · **[P]** proposal, not approved ·
**[A]** assumption · **[T]** technical validation task · **[Q]** unresolved owner question (see
`docs/decisions/OPEN_QUESTIONS.md`). History is preserved: older records are not edited except for
dated pointer notes (the repository's pointer convention).

## 1. Decisions

### A. General Pass (all four pass the auction) [D]
- **Normal Calculation:** redeal and apply the Double multiplier.
- **Classic:** redeal **without** the Double multiplier.
- The implementation must branch on calculation mode.
- **Supersedes:** the engine's behaviour in `Bidding.kt:493-509` (doubles for both modes, hard cap 8) and the
  design text at `docs/UI_UX_ROADMAP.md` (S18). `CANONICAL_RULES.md` was silent; the rule is now
  Amendment A6 there.
- **Example (Caller bids 5 and makes 5):** Normal = (10+5)+10 = 25 → ×2 = **50**. Classic = 5+13+10 = **28**.
  The engine today would score Classic **56** (doubled). The owner rule gives 28.
- **Still open:** [Q1] the Normal doubling's composition with a carried Sa'ayda multiplier.

### B. Sa'ayda extension [D, with a flagged ambiguity]
- Preserve the current approved behaviour: a Sa'ayda in **any fast round** extends the match.
- **Conflict (not silently changed):** canonical text says Sa'ayda forces an extra round only in the
  **final** round (`CANONICAL_RULES.md:21,144,191,228`; `03-transactions.md:145-147`). The engine
  extends in any fast round (`RoundScore.kt:230-231`, pinned by `RoundScoreTest.kt:233-237`).
- **Wording ambiguity:** the owner wrote "extend the match from 14 to 18 rounds". The engine adds
  **one** round per extension (FULL: up to five, so 18→19…23). [Q2] asks which is meant.
- **Illustrative example (hypothetical totals):** FULL, all four fail round 16. *Engine:* the match
  becomes 19 rounds. If after round 18 A=120, B=112 and B wins round 19 (+25, A −11), the result flips
  to B 137, A 109. *Canonical-final-round-only:* the match ends after round 18, A wins 120–112.
  A single extra round can change the winner, so the choice matters.
- The Super Call extension also requires a **successful** caller in the engine (`RoundScore.kt:234`);
  canonical text says no such condition. Not covered by this decision. [Q3]

### C. Normal Dash combined with Risk [D]
- A Normal Dash player who is also the Risk player gets the **Risk value PLUS the Normal Dash points**;
  neither is dropped or double-counted.
- **Supersedes:** engine behaviour (`Scoring.kt:75-76`, Risk ignored for `REG_DASH`) and the silence
  of `04-scoring.md §4`. Aligns with `CANONICAL_RULES.md:133-137`.
- **Example (Normal calculation, total bids 15, so Risk ladder 10):** Dash makes 0 tricks → +10 +10 =
  **+20** (engine today **+10**). Dash takes 2 tricks → −(10+2) −10 = **−22** (engine today **−12**).
  Sole winner/loser ±10 and the Sa'ayda multiplier apply as for every other role.
- **Still open:** [Q4] Classic calculation (its Normal Dash is a fixed table); the loss-side sign of
  the Risk component is an [A] taken from `04-scoring.md §1`.

### D. Authentication and guests [D]
- Registered methods are the **existing** Google sign-in and email/password. Google Play Games is
  **not** added as an identity provider.
- Guests may play bots, ROOM and UNRANKED. Guests may **not** enter Ranked.
- **Conflict found:** the native app has only anonymous and email sign-in to an existing account.
  There is **no Google sign-in and no sign-up** in native code (`AuthRepository.kt`; the Google flow
  exists only in the web prototype). "Existing" is therefore only true of the web client. [Q5]
- Supersedes: `docs/release/play-listing.md` guest/Google statements (to be amended in Phase 0).

### E. Account deletion [D: defer in V1]
- Deferred, keeping a low-cost store-compliant implementation possible later.
- **Not claimed compliant.** Google Play's user-data policy for apps with account creation is
  understood to require an in-app deletion path and a web link; this was **not verified in this
  session** and must be validated against current Play policy before release. [T/Q6]

### F. Ranked bot-fill vote [D]
- No automatic bot fill at match creation. Wait **two minutes**; if enough ready humans are still
  missing, start a **30-second** vote. At least **two waiting players** are needed to start it.
  Approval = majority; **non-votes count as rejection**. If it passes, use the bots the **room
  creator** selected.
- **Majority resolved from existing rules:** the repo's precedent is strictly more than half of the
  eligible voters (`E/VoteKick.kt` threshold `eligible/2+1`; disconnect vote 2 of 3). With non-votes as
  rejection, "majority" = more than half of the **waiting human players**. 2 waiting → 2 yes; 3 → 2.
- **Gaps (not invented):** public auto-match has no "room creator" [Q7]; "match start" and "ready
  players" are undefined [Q8]; RP with bot seats [Q9].

### G. Reconnect after disconnection [D, partial]
- A player may reconnect to the same match after a bot temporarily takes over, up to **seven**
  leave-and-return cycles per match.
- **Inspection:** no per-match return counter exists in code. The only "seven" hits are unrelated
  poll caps (`SeatReclaimPollTest.kt`). Today RD19 reclaim is unlimited. [Q10, Q11]

### H. Server authority and hosting [D direction; P architecture]
- **[D]** Android is the production client; web is not. Server is authoritative for dealing, hidden
  hands, legality, turn order, deadlines, bots, results and Ranked RP. Firebase Auth retained. No
  Blaze. Free hosting; no paid resource without explicit approval. Local feasibility before
  implementation and deployment.
- **[P]** Cloudflare Workers + SQLite Durable Objects + D1 is the **proposed** primary architecture.
  Reuse of `:engine` and bots is **conditional on Stage 0**.
- **Supersession of RD11/RD14:** the 2026-10-21 brief ("replace the existing Ranked runtime with a
  server-authoritative architecture") is treated as the approval of the direction, and
  `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` now carries pointer notes. The owner has **not** yet
  approved a specific ADR text naming Cloudflare as final. [Q12]

### I. Free-tier exhaustion and backup server [D intent; many [Q]/[T]]
- Measure primary usage continuously. At **90%** of the applicable daily quota, begin limited
  pre-synchronization to the backup. The backup does **not** read or write gameplay state before
  the threshold (except approved setup/health checks). Running matches stay on the primary while it
  can safely process them; no migration at 90%. If primary and backup are both unavailable, reject
  new Ranked matches: "Ranked matches are temporarily unavailable. Please try again later." Never
  bypass validation, expose cards, or lose/duplicate RP. No automatic paid upgrade.
- **Post-match failure handling [D]:** (1) persist the finalized result durably before any external
  write; (2) retry result/RP delivery safely; (3) keep the result pending until persistence is
  confirmed; (4) stable idempotency ids; (5) show a clear status/error when not confirmed; (6) never
  claim saved without reliable confirmation.
- **Not assumed:** cross-provider failover is **not** assumed safe or automatic. Stage 0/Stage 7 must
  prove recovery, replication lag, quota accounting and consistency (§3). **The backup provider is
  unspecified.** [Q13]

### J. Result statistics from V1 [D]
- Store final results and update profile statistics for **ROOM and RANKED** matches. King, Second,
  Third, Koz are **one combined counter** across ROOM and RANKED (5 ROOM + 3 RANKED Kings = 8).
  **UNRANKED is excluded.** Ranked RP is separate and Ranked-only. Each result is processed **exactly
  once** across retries, disconnects, restarts and backup recovery.
- **Supersedes (history kept):** RD23 ("9 stats… Public+Private Ranked, exclude Placement"; no
  statistics for ROOM/UNRANKED) and the "no Ranked statistics for UNRANKED" lines of RD28/A3.5.
- **Contradictions handled:** (1) A5.1 charges UNRANKED departures to the career Koz counter, but
  UNRANKED is now excluded from stats [Q14]. (2) A5.1 charges ROOM no Koz [Q14]. (3) ROOM is client-
  converged on Firestore and was deferred from the new backend, yet it now needs exactly-once result
  processing, which Spark cannot do with Cloud Functions [Q15 — highest impact]. (4) ROOM may start
  with 2–3 players; placements/ties for fewer than four are undefined [Q16]. (5) Whether Games Played
  and the percentages also combine is unstated [Q17].

### K. Leaderboard [D: deferred from V1]
- Not implemented and no dedicated resource use in V1; possibility preserved. This **defers** RD27/S64
  and does not repeal the earlier ruling (season board = only players who played that season), which
  is kept for later.

### L. Post-match retention [D]
- After result and RP persistence are **confirmed**: keep the final result, the necessary RP
  ledger/audit rows and the minimum idempotency/settlement metadata. Delete transient data (hidden
  hands, live state, temporary event history) where safe. Never delete unfinished or pending data
  before the recovery policy says it is safe. Deletion is not free: it does not refund consumed
  writes, and row deletes are expected to count as writes [T]. Record **storage** savings separately
  from **request/write** savings; no promise that retention raises daily quotas. No full per-action
  history unless an approved business, security or recovery requirement exists [Q18].

### M. Validation gate [D]
Before Stage 1, Stage 0 must prove **locally** (no cloud, no spend): engine runs correctly under
workerd; ≥200 seeded JVM-vs-server-engine matches identical; rule/scoring parity; bundle start-up
under the agreed limit; measured resources vs the capacity model; D1 batch atomicity and trigger
behaviour; durable recovery and idempotent result/RP handling. Anything only checkable on Cloudflare
is deferred to a later, explicitly approved environment.

## 2. Earlier rulings in the replanning brief (unchanged, restated for completeness) [D]
Ranked-only first scope; ROOM/UNRANKED not silently migrated; no legacy data to migrate; Ranked needs
a registered account; $0 budget; no timeout grace window needing extra reads/writes; season
attribution at completion; voice signaling decided by Claude (see OPEN_QUESTIONS, V-1: delegated, now
recorded as "move to the match connection when voice is built; do not adopt RTDB").

## 3. Technical assumptions Stage 0/7 must prove [T]
Backup-lag and replay safety; quota accounting that can be read (the Worker request quota cannot be
read in-Worker); that pre-sync at 90% fits inside the remaining 10%; atomic outbox hand-off between
providers; that two free accounts/providers may legitimately be used (terms unverified); that a stale
backup cannot award RP twice (single idempotency key authority); delete-cost and storage behaviour.

## 4. Relationship to older records
| Older record | Relationship |
|---|---|
| RD11, RD12, RD14, R25, plan appendix "lightweight authority compromise" | Superseded **for Ranked** in direction (H); pointer notes added in the plan |
| RD13 (idempotent, authenticated settlement) | Carried forward and strengthened (I, J) |
| RD23 / RD28 / A3.5 statistics scope | Superseded by J (ROOM+RANKED combined; UNRANKED excluded) |
| RD27 / S64 leaderboard | Deferred by K |
| PD-001 (web is production) | Superseded by H (pointer added) |
| A5.1 charge table | Interacts with J [Q14] |
