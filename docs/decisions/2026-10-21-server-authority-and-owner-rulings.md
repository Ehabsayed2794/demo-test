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

### B. Sa'ayda extension [D — corrected 2026-10-21 (later); supersedes the earlier B text and Q2]
Two separate rules (the owner's wording):
1. **Count rule (window = rounds 14–18, inclusive).** Each *round* in 14–18 in which Sa'ayda occurs adds
   one extension round to the baseline ending at 18. The count is per qualifying **round**, not per
   player who announces. None → last round 18; round 14 only → 19; rounds 14 and 16 → 20; three
   qualifying rounds → 21.
2. **Final-round winner rule (no window).** After the planned last round is computed, if Sa'ayda
   occurs in the **actual current final round**, add one more round, and repeat for each new final round
   until the final round ends with a winner. A Sa'ayda in a round that is *not* the current final round
   and is *after* 18 adds nothing. Example: Sa'ayda in 14 and 16 → planned final 20; Sa'ayda in 19 adds
   nothing; Sa'ayda in 20 → 21; Sa'ayda in 21 → 22; and so on.
- **Superseded interpretations:** "final round only for the whole calculation" (`CANONICAL_RULES.md:21,144,191,228`,
  `03-transactions.md:145-147`); "fixed extension"; "one round per Sa'ayda after 18"; "automatic round 21 for
  14, 16, 19". The earlier Q2 wording question is **answered**.
- **Engine check (read, not run):** `computeRoundExtension` (`RoundScore.kt:222-238`) adds one round for a
  Sa'ayda in 14–18 (matches rule 1, per-round) and **never extends from round 19 onward**
  (`round !in firstFastRound..baseRounds`). So **rule 2 is not implemented**: with Sa'ayda in 14, 16 and 20
  the engine ends at 20; the rule requires 21. `GameType.FULL.maxExtensions = 5` and `MINI = 1`
  (`Bidding.kt:111-113`) also cap the count, which the unbounded rule 2 contradicts.
  `GameTypeTest`/`RoundScoreTest` pin "round 19 never extends" (`RoundScoreTest.kt:229-232`) — these tests
  encode the superseded interpretation and need changing at implementation time (not done here).
- **Follow-up answers [D, 2026-10-21 (third batch)]:** **MINI** uses the same logic on its window **6–10**.
  **Super Call extensions combine with the Sa'ayda count.** A **tied** final round counts as having a winner
  (shared King, RD17). The owner agreed the extension is conditional on the final round ending with a winner;
  the reply to the 5/1 extension-cap question was "yes, on the condition that the last round must end with a
  winner" — recorded as **[A] cap removed so the final-round rule can repeat**; confirmation is Q2c.
- **Open:** [Q2c] cap confirmation; [Q2d] the owner added "or a shared Koz too" for tied last place, which
  conflicts with RD17 ("tie for last → no unique Koz, never fabricate one"; `Placement.kt` returns no Koz on a
  tie) — not applied until the owner decides.

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

### F. Ranked bot-fill vote [D]; creator, timer, readiness and RP [D 2026-10-21 later]
- No automatic bot fill at match creation. Wait **two minutes, starting when the first player enters the
  table [D]**. A human is **ready only when connected and seated [D]**; launching the app or entering the
  matchmaking queue does not count. If enough ready humans are still missing, start a **30-second vote**;
  at least **two waiting players** are required; approval = majority; **non-votes count as rejection**.
- **Entry [D, Q7b]:** from the lobby a player choosing Ranked has **two paths: create the match themselves, or Auto-Match**.
- **Bots [D]:** the player who **creates the Ranked game selects bot difficulty and personality** at game
  creation. **Inspection:** Create Game already carries `botTier`/`botPersonality`
  (`CreateGameViewModel.kt:40-44`), so this applies directly to private/Rank-down tables. The public
  search request carries **only `gameType`** and any other key is refused (`MatchmakingRequest.kt:11-16,42`),
  so an Auto-Match table has **no creator-chosen bot settings today**. The owner's two-path answer does not say what bots an Auto-Match table uses or whether bot-fill applies there [Q7c].
- **Ranked RP with bots [D]:** humans get normal Ranked RP; opponent strength uses **humans only**; bots are
  never rated opponents. (Today settlement averages all other seats' profiles, `SettlementDb.kt:620`.)
- **Majority** resolved from precedent: more than half of the waiting humans (2 waiting → 2; 3 → 2).

### G. Reconnect and bot takeover [D, completed 2026-10-21 (later)]
- A player may return to the same match after a bot temporarily takes over, up to **seven** cycles.
- **Counter [D]:** increments **only when a bot actually takes control after a disconnection**. The ordinary
  50-second inactivity timeout (RD19) does **not** increment it; the two events are distinct triggers
  (disconnection takeover vs. inactivity takeover) and the existing inactivity/timeout counters are untouched.
- **Seventh qualifying takeover [D]:** remove the player from the **current match** with the **2-Koz** result.
  This is a match-level removal, **not an account ban**, and adds no account penalty. It is a separate
  mechanism from the **15-timeout removal** (RD20): different trigger and counter, same recorded result
  (2 Koz); they share no state and are not interchangeable.
- **Detection [D, Q10b]:** the **server** detects the disconnection. Owner's example: card timer 15 s → the server
  waits the 15 s; if the player is still unresponsive it waits **10 more full seconds**; if there is still no
  response the bot takes the seat. This 10 s takeover delay is separate from the earlier "no grace window for
  action-versus-timeout" ruling. How it interacts with the 15 s timeout bot decision and the timeout counter is
  open [Q10c]. Persisting the counter across a server restart is an engineering default (store with the event),
  not an owner question.
- **Inspection:** no per-match takeover counter exists in code today; RD19 reclaim is unlimited.

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

### I. Free-tier exhaustion and backup server [D; operating policy confirmed 2026-10-21 (later)]
- **Operating policy [D, Q13]:** at 90% usage the backup hosts **new matches only**; running matches stay on
  the primary; results may wait until deliverable to **one central database**; there is **one RP source of
  truth** (no independent RP authorities). The backup **provider, synchronization, recovery and duplicate-RP
  safeguards remain technical design/experiments (T3, T4), not decisions**.
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
  unspecified** (engineering to propose; not an owner question).

### J. Result statistics from V1 [D scope; ROOM result recording D 2026-10-21 (later)]
- **Scope (unchanged):** ROOM and RANKED results aggregate in the same King/Second/Third/Koz counters;
  UNRANKED excluded; Ranked RP is separate and Ranked-only; each result must be processed exactly once.
- **ROOM result recording [D, Q15]:** the **app manages all ROOM operations**; the **server records the
  final result only**. Gameplay and result calculation stay on the client. This is **not** a move of ROOM
  authority to the server.
- **Technical trade-off (engineering finding, not a decision):** a server that stores a client-reported
  result cannot prove it is correct, cannot stop a modified client fabricating one, and does not by itself
  give exactly-once recording. Mitigations are design work (idempotency key per match, rules on who may
  report, cross-checking reports from the four seats) and still cannot make the number trustworthy.
  Because ROOM statistics merge with RANKED counters, a forged ROOM result changes the same profile
  numbers. The owner's requirement is kept as stated.
- **Integrity policy [D, Q15b]:** a ROOM result is recorded **only when the four players' apps report matching results**. Consequences to design (engineering): a mismatch or a missing report leaves the result **pending**, never auto-resolved; four matching reports still cannot prove correctness if all four collude; ROOM matches with fewer than four players need a rule [Q16].
- **Supersedes (history kept):** RD23's "no statistics for ROOM/UNRANKED" and the RD28/A3.5 lines on UNRANKED.
- **Still open:** [Q14] departure charges vs. UNRANKED exclusion; [Q16] ROOM with <4 players; [Q17] whether
  Games Played/percentages also combine.

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
