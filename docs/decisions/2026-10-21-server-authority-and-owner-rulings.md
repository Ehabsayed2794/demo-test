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
- **Clarified later (§O, Q1):** a General Pass consumes the round number and feeds the multiplier ladder; see §O.

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
  winner" — **the owner then confirmed (Q2c): the extension caps (FULL 5, MINI 1) are removed** so the final-round rule can repeat.

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

### J. Result statistics from V1 [D scope — UNRANKED exclusion REVERSED by §P; ROOM result recording D 2026-10-21 (later)]
- **Scope (original; the UNRANKED exclusion below is superseded by §P):** ROOM and RANKED results aggregate in the same King/Second/Third/Koz counters;
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

### N. Fourth-batch owner rulings (2026-10-21, later) [D unless marked]
- **Q2c — extension caps removed [D].** The FULL 5 / MINI 1 caps no longer apply, so the final-round winner rule repeats until a winner exists. (Engine today still caps: `Bidding.kt:111-113`.)
- **Q2d — Koz on ties [D].** The owner's examples define the rule: **Koz = every player tied at the lowest score, unless all four are tied (then all four are King).** (a) 100/100/100/100 → four Kings, no Koz. (b) 100 / 99 / 99 / 99 → one King, three Koz. (c) 100 / 99 / 10 / 10 → King, Second, two Koz. (d) 100 / 100 / 50 / 20 → two Kings, Third, one Koz. A tie at the top shares King; a tie at the bottom shares Koz and those players are **Koz, not Second/Third** (examples b and c).
  - **Supersedes** RD17's last sentence ("tie for last → no unique Koz; never fabricate a Koz") and `Placement.kt`'s `uniqueKozSeat` returning no Koz on a tie. Engine not changed.
  - **Open [Q2e]:** whether Vote Kick (needs a *unique* Koz, RD18) becomes available against a shared Koz, and how RP placement treats shared-Koz seats.
- **Q7c — Auto-Match model [D].** Auto-Match does not use a creator-less pool. Every Ranked table is **created by a player** (who chooses timer, bots and their personality); Auto-Match **joins a player-created table that is waiting for players**. Bots that later fill a seat are the **creator's chosen bots**. Example: Ehab wants his own settings → creates a table; Mohamed just wants a game → Auto-Match into an existing table. **Conflict:** the implemented search request carries only `gameType` and the plan/RD9 describes a server-derived pool (`MatchmakingRequest.kt`, RD9). **Open [Q26]:** tier rules between a joiner and a creator's table, whether joiners see the creator's settings first, and what Auto-Match does when no created table exists.
- **Q10c — takeover sequence [D].** After the decision timer (e.g. 15 s) expires, the **MEDIUM bot plays that decision and the timeout counter (15-removal) increments**. If the player is still disconnected after **10 more seconds**, the **creator-selected bot takes the seat and the takeover counter (7th = removal, 2 Koz) increments**. The 10 s is **fixed for all timers (5/10/15/20)**.
- **Q14 — departure charges and statistics [D — PARTLY SUPERSEDED by §O below].** A leave/removal worth 2 Koz counts in **RANKED and ROOM**; **UNRANKED is outside the statistics entirely**. **Supersedes** A5.1's "ROOM: no Koz" and its UNRANKED 2-Koz charge, and the 2026-10-17 "career Koz counter is a plain departure counter" reading for UNRANKED. **Open [Q14b]:** which ROOM paths charge (leave, 15-timeout removal, disconnect replacement) and whether the takeover counter exists in ROOM.
- **Q5 — sign-in [D].** Build Google sign-in and email sign-up in the Android app in V1 through Firebase Auth, no Google Play Games. (Cost/consent-screen/fingerprint setup is engineering.)
- **Q12 — ADR [D, conditional].** The owner approved writing the formal ADR superseding RD11/RD14 **after Stage 0 succeeds**. The ADR text itself is not yet written or approved.

### O. Fifth-batch owner rulings (2026-10-21, later) [D unless marked]
- **Q2e — Vote Kick with a shared Koz [D].** **No Vote Kick if the Koz is shared.** Vote Kick exists to stop one player spoiling the game, so the target must be a **unique** Koz (RD18 unchanged). **Open [Q2f]:** how RP placement treats players in a shared Koz (not answered).
- **Q26 — Auto-Match [D].** (1) The tier rule between a joiner and the creator's table is the **RD29 band (at most one tier apart)**. (2) The joiner **sees the table settings (timer, bots) before joining**. (3) If no waiting table exists, show **"no tables right now" plus a Create Table button** (no waiting, no auto-created table).
- **Q14b — departure charges, rewritten [D].** **Supersedes** the earlier Q14 line, A5.1's charge table (ROOM none; Vote Kick 1 Koz) and the 2026-10-17 UNRANKED reading, **where they differ**:
  - **ROOM:** a player who leaves **for any reason** is charged **1 Koz** (not 2).
  - **RANKED and UNRANKED:** leaving **for any reason** is charged **2 Koz**.
  - **Timing:** the charge is applied **only when the match finishes**, and only if the player **did not return**. A player who was allowed to return and did is not charged.
  - **Contradictions flagged, not resolved:** (a) UNRANKED is excluded from profile result statistics (J) yet is charged 2 Koz — does it reach the profile Koz counter? **[Q14c]** (b) "any reason" includes a passed Vote Kick, which A5.1 fixed at 1 Koz — now 2? **[Q14d]** (c) What if the match never finishes? **[Q14d]** Engine/records are unchanged: `LeaveMatch.kt`, `RankedStats.kt:151` still follow A5.1.
- **Q1 — General Pass multiplier [D, with an interpretation].** Owner example: all four pass in **round 6** → **round 7 is played ×2**; if round 7 is a **Sa'ayda** (everyone fails) → **round 8 is ×4**. So a General Pass **consumes the round number** (it is not a same-round redeal) and its ×2 is the first step of the same ladder the Sa'ayda uses. **Engine differs:** it redeals the **same** round (`Bidding.kt:493-509`; `QuickMatchViewModel.applyGeneralPass`), and caps at a literal 8 in both modes. **[A]** the ladder generalises as ×2→×4→×6→×8 (canonical Sa'ayda ladder, not doubling) — to confirm **[Q1b]**, together with what Classic does (redeal without Double: does it also consume the round?). General Pass can occur only in normal rounds (fast rounds have no auction).
- **Q3 — Super Call extension [D].** A Super Call extends the match **only if the Caller succeeds** (as the engine does, `RoundScore.kt:234`). The canonical "any Super Call" wording (`CANONICAL_RULES.md:108`) is superseded.

### P. Sixth-batch owner rulings (2026-10-21, later) [D unless marked]
- **Q14c — UNRANKED enters the profile statistics [D; REVERSES decision J and the Q14 line].** UNRANKED does **not** compute RP, but it **does enter all the statistics** (King/Second/Third/Koz, Games Played, and the 2-Koz departure charge). So the profile statistics now aggregate **ROOM, RANKED and UNRANKED**; RP stays Ranked-only. Supersedes RD23/RD28/A3.5 "no statistics for UNRANKED" and this record's earlier J scope. **Consequence for design:** UNRANKED, like ROOM, runs on the old client-managed runtime, so its final result also needs exactly-once recording — the ROOM rule (record only when the four apps' reports match) is the obvious candidate but was not stated for UNRANKED **[Q15c]**.
- **Q14d(1) — Vote Kick charge [D].** A passed Vote Kick records **2 Koz** in RANKED and UNRANKED (was 1 Koz in A5.1, which this supersedes; ROOM has no Vote Kick).
- **Q14d(2) — abandoned match [D, with gaps].** If a match never finishes because everyone left, **every player is counted Koz and every player has RP deducted.** Tensions: it is an exception to "RP only after a fully completed match", and to "charged only when the match finishes". **Open [Q27]:** the definition of "everyone left" and how long; the RP amount; and a safeguard so a **server/quota outage that ends a match is never charged to players**; and what ROOM/UNRANKED (no RP) record.
- **Q1b — General Pass / Sa'ayda repeats [D].** **No cap on the number of General Passes or Sa'aydas**; only the multiplier is capped at **×8**. **Classic:** a General Pass round is still consumed, without a multiplier **[D, approved]**. The step size after ×4 follows the canonical Sa'ayda ladder ×2→×4→×6→×8 (resolved from `CANONICAL_RULES.md:141`; the owner's example is consistent with it).
- **Q2f — RP for a shared Koz [D].** Players tied at the bottom all take the **same last placement** for RP.

### Q. Seventh-batch owner rulings (2026-10-21, later) [D]
- **Q15c [D].** An UNRANKED result is recorded under the **same condition as ROOM**: only when the four players' apps report matching results.
- **Q27(1) [D].** "Everyone left" = **as soon as the last human leaves, immediately**; the match is **cancelled and its result is computed** (all players Koz, RP deducted per Q14d). **Tension flagged [Q27b]:** an immediate rule conflicts with the reconnect right (up to seven cycles) and the 10-second takeover delay if "leaves" includes a network disconnection.
- **Q27(2) [D].** The RP deduction equals **the last-place loss computed by the RP engine**.
- **Q27(3) [D].** A match stopped by a **server or free-quota failure is never charged to players** (full exemption).
- **Q27(4) [D].** ROOM and UNRANKED record the Koz (1 and 2 respectively) with no RP.

### R. Eighth-batch owner rulings (2026-10-21, later) [D unless marked]
- **Q27b [D, with a condition].** An **explicit Leave (button)** cancels the match immediately **only if no player remains at the table**. A network disconnection follows the 10-second rule, then the bot; if **all humans have been disconnected for a fixed short period**, the match is cancelled. **Open [Q27c]:** that period's length, and confirmation that the owner's condition ("not while any player is still seated") means a seated-but-disconnected player still blocks cancellation until that period ends.
- **Q4 [D].** Normal Dash + Risk in **Classic: add both tables** (Classic Normal Dash fixed table plus the Classic Risk component), the same principle as Normal. Exact Classic numbers to be worked out at implementation with the owner's table (`CANONICAL_RULES.md:171-192`).
- **Q16 [D; supersedes A3.1 "ROOM may start with 2–3 players"].** **No ROOM without a full table of four players.** (Rules `isValidNewMatch` still accept 1–4 players, `firestore.rules:521-528`; not changed here.) This makes the ROOM statistics definitions (King/Second/Third/Koz) the same four-player ones.
- **Q17 [D].** Profile shows **counts and percentages** over Games Played, aggregated across ROOM, RANKED and UNRANKED. Percentage = outcome count ÷ games. Example: 100 King, 100 Second, 100 Third, 100 Koz → 25% each. (The owner's example used "وزير" for Second and "كوز صغير" for Third; Arabic names to be confirmed against the UI strings.)

> **Pointer (2026-10-23).** The Arabic names are now confirmed — **Q39 / O-05** fixes the nine
> stat labels (`عدد المباريات`, `👑`, `👑%`, `عدد مرات وزير`, `وزير%`, `عدد مرات كوز صغير`,
> `كوز صغير%`, `عدد مرات الكوز`, `%الكوز`) and **Q52 / O-14** fixes the five suit labels. The
> **aggregation across ROOM, RANKED and UNRANKED** stated above is **confirmed unchanged** for
> match results by **Q46 / N-1**; it remains the operative wording and is not rewritten here.
> See `docs/rules/CANONICAL_RULES.md` A8.5 and `2026-10-23-final-owner-answers.md`.

- **Q18 [D].** After the result is confirmed, keep the **final result, the RP ledger and the idempotency keys only**; delete the action history. Deletion cost is measured in Stage 0 (T2).
- **Q6 [D — risk accepted].** The owner chose to **accept the store-policy risk** of deferring account deletion and wait for the store's response. This is **not** a compliance claim; the Play policy remains unverified.
- **Q20 [D].** If the internet or server stops during a match (not a player leaving), **timers freeze and resume after recovery with no timeout charged to anyone.**
- **Q21 [D, partly].** Ranked requires **a registered account only** (no verified-email requirement). App Check / Play Integrity **not answered [Q21b]**.
- **Q22 [D].** **Seasons and the two-step reset stay in V1**: lazy reset, no records for inactive players, fixed **90-day** seasons (recorded as the owner's acceptance of the offered option; the Arabic reply restated option أ).
- **Q24 [D — supersedes my earlier recommendation].** **ROOM and UNRANKED stay app-managed.** No further migration project is committed now. After the game has succeeded, a **later release** may move everything to the server. This also settles that no server authority is planned for ROOM/UNRANKED in V1.

### S. Ninth-batch owner rulings (2026-10-21, later) [D unless marked]
- **Q27c [D].** If **all human players disconnect**, wait **one minute**; if nobody returns, **cancel the match, even if players are still registered at the table** (this answers the condition raised in Q27b: a disconnected-but-seated player does **not** block cancellation).
- **Q21b [D].** App Check / Play Integrity are **deferred to after V1**.
- **Q19 (With rules) [D — explanation supplied by the owner].**
  - **Normal rounds:** With is decided by the **winning suit (Suit) of the auction**, not by number: after the auction, find the final Caller, then every other player with an earlier bid **in that same suit** is With, and their With bid is **their last number bid in that suit** (even if they later bid another suit). Examples: Ahmed 5 Hearts and Osama 5 Hearts → Ahmed Caller, Osama With; Osama raises to 6 Hearts → Osama Caller, Ahmed With; Ahmed wins 6 Spades and Osama never bid Spades → Osama is **not** With (a matching number is not enough); Osama wins 7 Hearts, Ahmed's last Hearts bid was 6 → Ahmed With at 6.
  - **Fast rounds (14–18 and extensions):** With only if **both** the suit matches the final suit **and** the number equals the highest winning number exactly. Asking for 8 or more is **not** enough, and number alone is **not** enough.
  - **Supersedes** the engine's fast-round Super Call With set (`Bidding.kt:716`, everyone ≥8) and its use of the player's last bid in **any** suit as the With floor (`Bidding.kt:272-274`); the floor becomes the last number bid **in the winning suit**. The engine and tests are not changed here.
  - **Open [Q28]:** in a fast round bids are numbers only (single pass), so what "suit match" means there is unstated. **Open [Q19c]:** confirm the A1 (bid²) sentence is fixed in the documents only.

### T. Tenth-batch owner ruling (2026-10-21, later) [D]
- **Q28 — With in fast rounds, simplified by the owner.** (1) **Super Call and the trump changed:** With requires an exact match of **both the number and the new trump**. (2) **No Super Call and the trump unchanged:** With requires an exact match of the **highest number**. (3) **Super Call but the trump stayed the same:** With requires an exact match of the **number**.
- **Gap flagged [Q28b]:** fast-round bids are numbers only (no suit), so in case (1) no other player can currently "match the new trump"; as built, nobody would be With after a trump-changing Super Call. This is an engineering reading of the rule, not an owner statement.

### U. Eleventh-batch owner rulings (2026-10-21, later) [D unless marked]
- **Q19c [D].** The A1 sentence ("legacy tracker only") is corrected in the documents only; **no code or scoring change** (bid² stays, per D1). A pointer is added under A1.
- **Fast-round Super Call and With/Wazz [D] — supersedes Q28 (§T) and answers Q28b.**
  1. In fast rounds every player declaring **8 or more** may choose **any trump** (Sans, Spades, Hearts, Clubs, Diamonds), regardless of the forced suit.
  2. The Super Caller's suit **replaces the forced suit**; the other players get **another opportunity to declare under the new trump**.
  3. A **higher Super Call** makes that player the new Super Caller, who may choose any trump; others re-declare under it; repeat until the highest call and final trump are settled.
  4. **With/Wazz in fast rounds:** no Super Call, forced trump unchanged → With only if the **final declared number equals the highest winning number**; Super Call but trump unchanged → same; Super Call **changes** the trump → With only if the final number equals the highest winning number **and the estimate was made under the final trump** (an estimate under a previous trump cannot qualify without being reconsidered).
  5. **Not** every player declaring 8+ is With; **only a Super Caller** chooses the suit; existing tie-breaking rules unchanged.
  - Distinct from ordinary-round With (alignment with the winning Caller's suit, using the player's latest bid in that suit).
  - **Engine differs:** `Bidding.kt:700-760` makes everyone with a bid ≥8 With (superseded). Canonical `:104` says only players who bid **before** the Super Caller must re-estimate (those after keep theirs); the owner's rule 2 says "the other players" — **[Q29]** which is meant.

### V. Twelfth-batch owner ruling (2026-10-21, later) [D]
- **Q29 [D] — supersedes the "only players who bid before" re-estimate rule of `CANONICAL_RULES.md:104` and the engine's matching behaviour (`Bidding.kt:577-625`).** After a Super Call (and after each higher Super Call), **every player re-declares under the new trump, except the Super Caller**, in **counter-clockwise** order. Players who had bid after the Super Caller therefore **no longer keep** their earlier estimate. Note: the 13 Rule / Call Cap handling for the re-declaration is not restated by the owner and stays as existing.

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
| RD23 / RD28 / A3.5 statistics scope | Superseded by J, then by §P: ROOM, RANKED and UNRANKED all enter the statistics; RP Ranked-only |
| RD27 / S64 leaderboard | Deferred by K |
| PD-001 (web is production) | Superseded by H (pointer added) |
| A5.1 charge table | Interacts with J [Q14] |
