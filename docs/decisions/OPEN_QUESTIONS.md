# Open-questions register (as of 2026-10-23)

Companions: `docs/decisions/2026-10-21-server-authority-and-owner-rulings.md`,
`docs/decisions/2026-10-22-final-decision-sync.md` (Q35–Q50) and
`docs/decisions/2026-10-23-final-owner-answers.md` (Q51–Q52). Statuses:
**DECIDED** · **RESOLVED-BY-RECORDS/CODE** · **OWNER** (needs the owner) · **TECH** (Claude measures it) ·
**DEFERRED** (out of V1). Nothing here is owner-approved unless it says DECIDED. Questions are in
Egyptian Arabic; priority P1 blocks Stage 1 design, P2 blocks a later phase.

## A. Owner questions

| ID | Pri | السؤال | ليه مهم | الاختيارات / توصية | المصدر |
|---|---|---|---|---|---|
| O-12 | P2 | What voice provider, protocol, transport, moderation and permission model does V1 use, and what does it cost? | Voice is **in V1 scope** for Rooms and the Waiting Room (O-08), but no provider means no estimate and no implementation. | Needs the owner. **No recommendation recorded.** | Q42; 2026-10-23 record §4 |
| O-13 | P2 | Which ads provider and consent-management choice does V1 use, and where may ads appear? | Ads are in V1 scope (O-09); no provider, consent flow or placement policy exists, and none may be invented. | Needs the owner. **No recommendation recorded.** | Q43; 2026-10-23 record §4 |
| O-15 | P2 | O-07 says the Ranked pool is shared and players more than one tier apart are never paired. Does that require **symmetric** one-tier pairing both ways, or does RD9's no-opt-up rule stay (Gold may never be paired into Platinum)? | `isPoolEligible` is directional today: same tier or exactly one tier **below**, never one above (`MatchmakingPool.kt:82-91`). A symmetric reading changes matchmaking; an asymmetric reading leaves the code as-is. | **(a) — CLOSED 2026-10-28 (owner ruling): symmetric absolute band, exactly one tier up or down, never two.** `isPoolEligible` keeps its per-searcher direction; the resolver composes both. See `docs/decisions/2026-10-28-matchmaking-owner-rulings.md`. | O-07; `MatchmakingPool.kt:65-91`; C-5; S56 |
| O-16 | P2 | Is Stage 0 the next work item, or does IAP stay planned-but-blocked until a runtime is approved? | Play Billing verification needs a backend; Firebase Spark cannot host it and Cloudflare Workers is not approved (O-06). No purchase code can ship either way. | (a) run Stage 0 next; (b) keep IAP blocked. **Recommendation (not approved): (b).** | O-06; plan V2 §5.9; N-5 |
| O-17 | P2 | In what order should the implementation sprints run? | The order changes delivery sequence and review load, not the rules. | Proposed: S1 → S2/S4 → S3 → S5/S6 → S7 → S8. **Recommendation (not approved): as proposed**, because S2 depends on S1's round progression. | plan V2 §5.10 |
| O-18 | P2 | Does the owner supply Arabic prose for the new rule text, or do the rule documents stay English with Arabic only in the app? | The R-07 localization gate applies to user-facing text; the rule documents are English today. | (a) English rule documents, Arabic app labels only; (b) owner supplies Arabic rule prose. **Recommendation (not approved): (a).** | 2026-10-23 record §5; R-07 |

> **Five rows are open** — O-15 was **CLOSED 2026-10-28** (symmetric one-tier band; see
> `docs/decisions/2026-10-28-matchmaking-owner-rulings.md`). Every remaining recommendation is
> explicitly **not approved**. The Arabic wording of any
> future rule text remains the owner's to supply (the plan's R-07 localization gate still holds).
>
> **Pointer, 2026-10-28 (owner ruling): Q26 clause (3) is SUPERSEDED.** The server may form a
> private table for Auto Match (both players press Ready), and the player must be offered
> **both** paths — Create Game or Auto Match. The Q26 row in §C and the "direction still open"
> notes beside Q41 are historical. See `docs/decisions/2026-10-28-matchmaking-owner-rulings.md`.

## B. Resolved / decided / deferred (no question needed)

| Topic | Status | Basis |
|---|---|---|
| Timeout grace window | DECIDED: none (zero extra read/write ordering) | replanning brief |
| Season attribution across a boundary | DECIDED: completion season | replanning brief |
| Ranked anonymous policy | DECIDED: no Ranked for guests; linked = registered | D |
| Disconnect vote in Ranked | RESOLVED: not held (A5.2); configured bot takes the seat (roadmap line 2056) | records |
| Vote kick charge / OPEN-4, OPEN-5 | Vote Kick = **2 Koz** in RANKED+UNRANKED, not held in ROOM (Q14d(1) supersedes A5.1's 1 Koz); OPEN-4/OPEN-5 resolved by A5 | Q14d(1), A5.2 |
| Classic Dash + Risk numbers | DECIDED 2026-10-22: components added, miss counted once (−12 / +23 on the owner's case) | Q30, A7.1 |
| Fast equal-highest Super Call tie | DECIDED 2026-10-22: number first, then the **already canonical** suit hierarchy (Sans > ♠ > ♥ > ♦ > ♣); never seat order | Q31, A7.2; `CANONICAL_RULES.md:27-31`, `:59`, `:68` |
| 7th takeover in ROOM and its timing | DECIDED 2026-10-22: **1 Koz**, fires immediately even during a ballot or a WAIT | Q32, A7.3 |
| Permanent disconnect-replacement charge | CONFIRMED 2026-10-22: UNRANKED **2 Koz**, ROOM **no Koz**; a non-voluntary disconnect adds no *leave* penalty on top | Q33, A7.4 (closes O-03c) |
| Bots in ROOM and result attribution | DECIDED 2026-10-22: a bot may fill a vacated seat; a departed human is scored Koz even if the bot wins as King; completing humans are unaffected; a bot's result is never attributed to a human | Q34, A7.5 (closes O-04/C18) |
| Match completion off-by-one | RESOLVED: all checks equivalent | engine code |
| Voice signaling | DECIDED 2026-10-22: voice is **in V1** for Rooms + Waiting Room, **never Ranked**; provider/protocol/cost open (O-12) | Q42; supersedes the earlier "post-V1" note |
| Leaderboard, inactive-player board visibility | DEFERRED (K) | K |
| Inactive players need season-board records | Not needed in V1 (no board) | K |
| O-19 | CLOSED 2026-10-23 — Option B persisted currentMultiplier authorized with a strict rules surface; implemented 2026-10-24 on agent/s1b-option-b-impl and merged into main via PR #152 (88217a3) | 2026-10-23 direction record §1 (on docs/native-v1-implementation-plan-v2 @ 33ca9bc, not merged here) |
| O-20 | CLOSED 2026-10-23 — Git policy ratified: one integration branch, one feature branch/worktree per agent, integrate only after review + green tests | 2026-10-23 direction record §4; docs/agents/git-workflow.md (same branch @ 33ca9bc, not merged here) |
| Stage 0 capacity, persistence, transport, failover | DECIDED 2026-10-29: persistence per **completed Trick** (conditional on E9), auction per action; bots **inside the authoritative server**; **WebSocket transport preferred** (free-plan metering unverified → E12); planning range **100/125/150 matches/day** (three scenarios); **90% quota guardrail** — new matches stop, ongoing matches never terminated; failover per **Q13**, with the independent quota-isolated secondary-server architecture flagged missing (prerequisite); **Cloudflare preferred on cost** — preference only, **O-06 unchanged**, no paid plan authorized | `docs/decisions/2026-10-29-stage0-capacity-persistence-transport-failover.md` (refines Q13; E5 report §4/§5 = preliminary action-count-based model) |

### Decided on 2026-10-22 and 2026-10-23

| Topic | Status | Basis |
|---|---|---|
| Fast-round duplicate declaration / suit tie-break | DECIDED 2026-10-22: exact duplicate is legal; first declarer Caller, later With; different-suit tie by suit hierarchy, never seat order | Q35 |
| 7th takeover vs an open ballot | DECIDED 2026-10-22: cancel the ballot immediately; fires during VOTE and WAIT; one charge | Q36 |
| ROOM 15-timeout removal | DECIDED 2026-10-22: **1 Koz** | Q37 |
| ROOM bot fill / departed human | DECIDED 2026-10-22: Koz even if the bot wins King; Games Played +1; no bot attribution | Q38 |
| Platinum and the nine stat labels | DECIDED 2026-10-22: Platinum = **بلاتين**; nine labels listed in the record | Q39 |
| Runtime | DECIDED 2026-10-22: Stage 0 first; Cloudflare Workers+DO+D1 **not approved** | Q40 |
| Matchmaking | DECIDED 2026-10-22: shared pool; >1 tier apart never paired; band absolute. Direction still open → O-15 | Q41 |
| Voice in V1 | DECIDED 2026-10-22: Rooms + Waiting Room, not Ranked; provider open → O-12 | Q42 |
| Ads + IAP in V1 | DECIDED 2026-10-22: Play Billing allowed; Spark/no-Blaze + $0 runtime; verification blocked on O-06; provider open → O-13 | Q43 |
| Account deletion | DECIDED 2026-10-22: full deletion, no anonymized substitute; deferred from V1 | Q44 |
| RP values | DECIDED 2026-10-22: King +30, Second +15, Third −8, Koz −20 | Q45 |
| General Pass | DECIDED 2026-10-23: cancel the round, fresh deal into the **next round number**; Normal ×2, Classic none | Q51 |
| Suit display labels | DECIDED 2026-10-23: سانز / أسبيد / هارت / كاروه / تريفل | Q52 |

## C. Technical experiments (Claude performs; not owner decisions)
| ID | Experiment | Measure |
|---|---|---|
| T1 | Engine ES module in workerd + ≥200-seed JVM/Node/workerd parity | zero diffs; bundle start-up under limit |
| T2 | Delete cost: row deletes vs `deleteAll()`/table drop on DO SQLite | rows written per match with and without cleanup |
| T3 | Quota accounting: can usage be read in-code at 90%? | counter error vs dashboard |
| T4 | Backup lag/outbox: stale backup cannot award RP twice | fault-injection suite |
| T5 | D1 batch atomicity, trigger abort, 50-query cap | local then approved cloud |
| T6 | Durable recovery: kill mid-trick and between result commit and D1 | replay equality, one ledger row |
| T7 | Anonymous→registered link keeps uid; `sign_in_provider` claim value | Firebase Auth emulator |
| T8 | Hibernation/duration billing for a shared host | GB-s per match |
| T9 | Current Play account-deletion policy | policy citation |

## D. Answered (recorded in the decision records; do not ask again)
| ID | Answer |
|---|---|
| Q2 | Sa'ayda: count rounds 14–18 (+1 each), then final-round winner check (detail and engine gap in record §1.B; follow-ups in Q2b) |
| Q7 | Ranked game creator chooses bot difficulty and personality (public-table gap: Q7b) |
| Q8 | 2 min from first player entering; ready = connected and seated |
| Q9 | Humans get normal RP; opponent strength from humans only |
| Q10 | Counter increments only when a bot takes over after a disconnection; not for the 50 s inactivity timeout (detection detail: Q10b) |
| Q11 | 7th qualifying takeover: removed from the current match, 2 Koz, not an account ban (ROOM amount + timing: Q32) |
| Q13 | At 90% the backup hosts new matches only; running matches stay; results wait for one central RP database (provider/sync = engineering) |
| Q15 | ROOM stays app-managed; server records the final result only (integrity policy: Q15b) |
| Q15b | ROOM result recorded only when all four players' apps report matching results |
| Q7b | Ranked entry has two paths: create the match yourself, or Auto-Match (Auto-Match bot settings: Q7c) |
| Q2b | MINI uses the same logic on 6–10; Super Call extensions combine; tied final round = has a winner (follow-ups Q2c, Q2d) |
| Q10b | Server detects disconnection; waits the decision timer, then 10 more seconds; then the bot takes over (details: Q10c) |
| Q2c | Extension caps removed; the final-round winner rule repeats until a winner |
| Q2d | Koz = every player tied at the lowest score unless all four tie (all King); shared Koz players are Koz, not Second/Third (follow-up Q2e) |
| Q7c | Auto-Match joins a player-created table; bots are the creator's choice (follow-up Q26) |
| Q10c | After timer: MEDIUM plays, timeout counter +1; after 10 more disconnected seconds the creator's bot takes over, takeover counter +1; 10 s fixed |
| Q14 | (superseded by Q14b/Q14c) 2-Koz departures; UNRANKED exclusion reversed by Q14c |
| Q5 | Build Google sign-in and email sign-up in Android V1 via Firebase Auth, no Play Games |
| Q12 | Approved to write the ADR after Stage 0 succeeds (ADR text not yet approved) |
| Q2e | No Vote Kick when the Koz is shared (unique Koz required); RP of shared Koz: Q2f |
| Q26 | RD29 tier band joiner↔creator; joiner sees table settings first; no tables → message + Create button |
| Q14b | ROOM leave = 1 Koz; RANKED/UNRANKED leave = 2 Koz; charged only when the match finishes and the player did not return. “any reason” is narrowed to a **voluntary** leave by Q14b(1) (follow-ups Q14c, Q14d) |
| Q14b(1) | **Departure = voluntary leave only (2026-10-22, A6.12).** A non-voluntary disconnect or app close charges nothing; a reconnect inside the takeover window is never a departure; the 7th takeover and the 15-timeout removal are not departures and keep their own charge (7th takeover: 2 Koz RANKED/UNRANKED, **1 Koz ROOM** per Q32; 15-timeout: 2 Koz RANKED/UNRANKED, ROOM amount **open per O-03d**) without an added leave penalty. No charge value changes and no new value is introduced. **Residual CLOSED the same day by Q33 / A7.4: the ratified UNRANKED 2-Koz permanent disconnect-replacement cell stands, and “disconnect charges nothing” means only that no *leave* penalty is added** |
| Q1 | General Pass consumes the round: round 6 all pass → round 7 ×2; Sa'ayda in 7 → round 8 ×4 (follow-up Q1b) |
| Q3 | Super Call extends only if the Caller succeeds |
| Q14c | UNRANKED enters ALL profile statistics (no RP); reverses decision J's exclusion (follow-up Q15c) |
| Q14d | Passed Vote Kick = 2 Koz; abandoned match (everyone left): all players Koz and all lose RP (details Q27) |
| Q1b | No cap on General Pass/Sa'ayda counts, only the multiplier ×8; Classic General Pass still consumes the round, no multiplier |
| Q2f | Shared-Koz players take the same last placement for RP |
| Q15c | UNRANKED result recorded under the ROOM condition (four matching app reports) |
| Q27 | Everyone-left match: cancelled as soon as the last human leaves; all Koz, RP deducted = engine's last-place loss; server/quota failure exempt; ROOM/UNRANKED record Koz without RP (follow-up Q27b) |
| Q27b | Explicit Leave cancels immediately only if no player remains at the table; disconnection follows the 10 s rule, then bot; all humans disconnected a fixed short period → cancel (period: Q27c) |
| Q4 | Classic Normal Dash + Risk: add both tables — **“both tables” means the two components, never the two complete role formulas** (numbered and clarified 2026-10-22 by Q30 / A7.1: −12 miss, +23 success on the owner's case) |
| Q16 | No ROOM without a full table of four |
| Q17 | Counts and percentages over Games Played, aggregated across ROOM/RANKED/UNRANKED |
| Q18 | Keep final result, RP ledger and idempotency keys; delete action history |
| Q6 | Account deletion deferred; store-policy risk accepted (not verified) |
| Q20 | Outage: timers freeze and resume, no timeout charged |
| Q21 | Ranked needs a registered account only (App Check: Q21b) |
| Q22 | Seasons and reset in V1: lazy, no inactive-player records, 90 days |
| Q24 | ROOM/UNRANKED stay app-managed; any server move is a later release, no committed project |
| Q27c | All humans disconnected: wait one minute, then cancel even if still registered at the table |
| Q21b | App Check / Play Integrity deferred past V1 |
| Q19 | With rules per the owner's suit-based explanation (Normal: suit alignment, floor = last number in the winning suit; Fast: suit and exact highest number); follow-ups Q28, Q19c |
| Q28 | Fast-round With: Super Call + trump changed → exact number and new trump; no Super Call, trump unchanged → exact highest number; Super Call, trump unchanged → exact number (follow-up Q28b) |
| Q19c | A1 "legacy only" sentence fixed in documents only; no code change |
| Q28b | Superseded: players re-declare under the new trump; no suit chosen by non-Super-Callers (rule in record §U); follow-up Q29 |
| Q29 | After a Super Call every player except the Super Caller re-declares, counter-clockwise (supersedes the earlier-bidders-only rule) |
| Q30 | **Classic Normal Dash + Risk (2026-10-22, A7.1; closes O-01).** The seat keeps its Classic Dash table value and adds only the flat Risk value — miss −2 + (−10) = **−12**, Over-round success +13 + 10 = **+23**. Adding the two complete role formulas (−14 / +36) is not the rule; −22 is a Normal-mode value; Wizz-Risk is another role |
| Q31 | **Fast equal-highest Super Call (2026-10-22, A7.2; closes O-02).** Higher **number** beats lower whatever the suit; equal number → **stronger suit** by the canonical hierarchy (Sans > ♠ > ♥ > ♦ > ♣, `CANONICAL_RULES.md:27-31`, `:59`, `:68`, `Cards.kt:10-16`): 8♠ beats 8♥, 9♥ beats 8♠. **Seat order is never a tie-break.** Fast With needs the exact number **and** the matching suit, so the loser of an equal-number tie is not With. Residual: same number **and** same suit → O-02b |
| Q32 | **7th takeover in ROOM + timing (2026-10-22, A7.3; closes O-03).** ROOM records **1 Koz** (RANKED/UNRANKED keep 2), and the removal fires **immediately** even if a 30 s vote is open or a 90 s WAIT is running. Residuals: the fate of that ballot → O-03b; the ROOM 15-timeout amount → O-03d |
| Q33 | **Permanent disconnect replacement keeps its charge (2026-10-22, A7.4; closes O-03c).** UNRANKED **2 Koz**, ROOM **no Koz**, RANKED holds no vote. `DisconnectVote.permanentCharge` (`:69-73`) already encodes it and needs a caller, not a value change |
| Q34 | **ROOM bot fill and result attribution (2026-10-22, A7.5; closes O-04/C18).** A bot may take a vacated ROOM seat; a human who leaves and does not complete is **counted Koz even when the bot finishes as King**; completing humans keep their own normal results and statistics; a bot's result is never attributed to a human profile. Residual: does that match enter Games Played → O-04b |
| Q35 | **Fast-round duplicate declaration (2026-10-22; closes O-02b).** Same number **and** suit is legal; the first declarer is Caller, later declarers are With; declaration order decides only exact duplicates. Equal numbers in different suits go to the canonical suit hierarchy (Sans > Spades > Hearts > Diamonds > Clubs); seat order is never a tie-break |
| Q36 | **7th takeover during a ballot (2026-10-22; closes O-03b).** Cancel the open 30 s ballot immediately, with no expiry; the removal fires during VOTE and WAIT; deterministic, no stale completion, one charge |
| Q37 | **ROOM 15-timeout removal (2026-10-22; closes O-03d).** **1 Koz**; RANKED/UNRANKED keep 2; never double-charged with the 7th takeover |
| Q38 | **ROOM bot fill and Games Played (2026-10-22; closes O-04b).** A departed non-completing human is Koz even if the bot wins King; Games Played +1 and the percentage denominator grows; the bot's result is never attributed; completing humans are unaffected |
| Q39 | **Platinum and the nine stat labels (2026-10-22; closes O-05).** Platinum = **بلاتين**; nine labels as listed in the record; keys and arithmetic unchanged; `وزير` is only the Second-place statistic |
| Q40 | **Runtime (2026-10-22; closes O-06).** Stage 0 first; Cloudflare Workers + Durable Objects + D1 **not approved**; no claim that Stage 0 passed |
| Q41 | **Matchmaking (2026-10-22; closes O-07).** Shared pool; players more than one tier apart are never paired; the band is absolute. Direction still open → O-15 |
| Q42 | **Voice in V1 (2026-10-22; closes O-08).** Rooms + Waiting Room only, never Ranked; provider/protocol/cost open → O-12 |
| Q43 | **Ads and IAP (2026-10-22; closes O-09).** In V1; Play Billing allowed; Spark/no-Blaze and the $0 marginal-runtime target stand; no Firebase/Cloud billing link; store fees are separate from infrastructure; secure verification design required; provider open → O-13; verification blocked on O-06 |
| Q44 | **Deletion (2026-10-22; closes O-10).** Full deletion, no anonymized retention substitute; still deferred from V1 |
| Q45 | **RP final values (2026-10-22; closes O-11).** King **+30**, Second **+15**, Third **−8**, Koz **−20**; placeholder language removed for these four values |
| Q46 | **UNRANKED in profile statistics (2026-10-22; N-1).** ROOM, RANKED and UNRANKED all contribute; RP stays Ranked-only; confirms item 11, A6.7, Q14c and Q17 |
| Q47 | **General Pass wording (2026-10-22; N-2).** Normal redeals with the Double, Classic without; the round-progression conflict was identified. **Superseded by Q51**, which defines the behaviour |
| Q48 | **Sa'ayda count (2026-10-22; N-3).** Rounds 14–18 inclusive, one round each: 14→19, 14+15→20, 14+15+16→21, 13→18; not limited to the examples; rule 2 and MINI 6–10 stand; no extension caps |
| Q49 | **Play Billing (2026-10-22; N-5).** Allowed for V1 digital IAP under Spark/no-Blaze and the $0 runtime target; store fees separate; secure verification/entitlement design; identify costs and flag undeliverable items; no zero-cost claim |
| Q50 | **Diamonds label (2026-10-22; N-6).** ♦ = **كاروه**; standardise inconsistent labels; internal id and hierarchy untouched. Extended by Q52 |
| Q51 | **General Pass (2026-10-23; R-1; supersedes Q47).** Cancel the round, deal fresh cards, start a **new round number**; Normal ×2 (ladder capped ×8), Classic no multiplier |
| Q52 | **Suit display labels (2026-10-23; O-14).** سانز / أسبيد / هارت / كاروه / تريفل; display only, no identifier or hierarchy change |

> **Residual pointers closed (2026-10-23).** Q14b(1)'s "ROOM amount open per O-03d" is answered by Q37;
> Q30–Q34's residuals O-02b/O-03b/O-03d/O-04b are answered by Q35–Q38. Those historical rows are left
> verbatim under the pointer convention.
