# Canonical Estimation Rules

> **Authority:** This file is a repository Markdown extraction of the authoritative Estimation Rules DOCX. It is intended as the canonical implementation reference for gameplay semantics. The DOCX remains the source artifact; this Markdown file does not alter or reinterpret its rules.
>
> **Provenance:** Extracted on 2026-08-25 from `uploads/Estimation_Rules_v2_SingleSourceOfTruth.docx` (repository path: `/home/ubuntu/demo-test/uploads/Estimation_Rules_v2_SingleSourceOfTruth.docx`).
>
> **Source SHA-256:** `64e5ccab9a27c2dd5f6d6c43159a4350704f3db3b92426d0255c8a8e960e5b6a`
>
> **Extraction method:** DOCX `word/document.xml` paragraph and table-text extraction using the standard-library script `/tmp/extract_rules_docx.py`; no gameplay or Rules source files were modified.

---

Egyptian Estimation (Estemshan)
Official Technical Reference — Single Source of Truth
Version 2.0  •  Consolidated with all clarifications  •  25 June 2026
This document supersedes all earlier rule sheets. Where the original specification was ambiguous or self-contradictory, the resolved rule is marked “✔ CLARIFIED”. This is the authoritative reference for both gameplay and software implementation.
1. General Overview & Core Ranking
Number of Players: 4 (individual play, no partnerships).
Deck: One complete standard deck (52 cards); 13 cards dealt to each player.
Play Direction: Counter-clockwise.
Number of Rounds: 18 standard rounds, plus any extension rounds created by a fast-round Super Call (see §3) or by a Sa'ayda on the final round (see §4). *(This is the FULL Game Type — the default. A MINI match plays 10 base rounds with Forced Fast Rounds from 6 and exactly one extension; see **Amendment A2**.)*
Card Rank (weakest → strongest)
2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → J → Q → K → A
Official Suit Hierarchy (strongest → weakest)

Rank | Name (English) | Common Egyptian/Arabic Name | Symbol | Strength
1 | No-Trump (Sans) | Sanz | — | Strongest (5)
2 | Spades | Asbeed / Beg | ♠ | 4
3 | Hearts | Koba / Heart | ♥ | 3
4 | Diamonds | Karo / Dinari | ♦ | 2
5 | Clubs | Trefle | ♣ | Weakest (1)

✔ CLARIFIED: Spades is called “Asbeed” (or “Beg”). “Trefle” is the name for Clubs (the weakest suit) — the earlier sheet mislabeled Spades as “Trefle”. “Sanz/Sans” means No-Trump.
2. Game Phases
2.1 Pre-Bidding Phase
Avoid (Void)
Immediately after dealing, the system checks each hand. If a player completely lacks a suit, a generic “AVOID” tag appears next to their name for everyone to see.
The specific missing suit is NEVER revealed; it stays hidden until it emerges naturally during play.
Dash Call (Pre-Bidding)
A declaration of “zero tricks” made BEFORE the bidding phase, without yet knowing the trump.
Maximum two players per round may declare a Dash Call.
Dash Call players are still dealt 13 cards and play all tricks normally — they simply do not take an estimation turn.
✔ CLARIFIED: A pre-bidding Dash Call player does NOT estimate and is therefore NEVER the Risk player. Their fixed 0 still counts in the four-player total used for Over/Under, the 13-rule, and the Risk calculation.
2.2 Bidding Stage (The Auction) — Normal Rounds 1–13
A dynamic, continuous back-and-forth auction. Players may raise or pass.
Minimum opening/auction bid is 4 tricks.
✔ CLARIFIED: The minimum legal call is 4. Numbers below 4 cannot be bid during the auction.
Who starts: the dealer (game creator) bids first; the auction proceeds counter-clockwise. Each round the dealer rotates one seat counter-clockwise.
Player Options During the Auction
Raise: increase the number of tricks, OR keep the same number but switch to a stronger suit.
Pass: withdraw permanently from this round's auction (Hard Elimination — cannot be asked again).
The Return Mechanic & Winner
Each raise returns the turn to all still-active players to respond, continuing until only one active top bidder remains.
The highest trick bid wins the Call. On a tie in number, the stronger trump suit wins (e.g., 5♠ beats 5♥). Sans beats every suit on a tie.
Confirmation Phase (Normal Rounds only)
After winning the auction, the Caller enters a Confirmation Phase. They may: keep the bid; raise the number; or, keeping the same number, switch to a stronger suit.
✔ CLARIFIED: Raising the NUMBER frees the suit choice — the Caller may then pick ANY suit, even a weaker one. Keeping the same number requires an equal-or-stronger suit. A weaker suit at the same number is illegal.
✔ CLARIFIED: The Confirmation Phase happens ONLY in normal rounds (1–13), including normal-round Super Calls. In fast rounds (14–18) there is NO Confirmation Phase for anyone.
Super Call
Definition: bidding 8 tricks or more.
Normal rounds (1–13): the Super Caller enters the Confirmation Phase and may confirm, raise the number, or change the suit.
Fast rounds (14–18): instantly overrides the forced trump suit — no Confirmation Phase — and extends the game (see §3).
If two players Super Call, the higher bid (then stronger suit) wins the right to choose the trump.
2.2.1 “With” (Wazz) Rules
A player becomes “With” the Caller in one of two ways:
Auction Alignment: during the auction, a player bid the SAME SUIT as the eventual winning Caller — even if their trick number differed.
Estimation Jump-In: during the Estimation Stage, a player matches BOTH the Caller's number AND suit exactly. This is open to anyone, including players who passed the auction.
Defeated Suit Rule:
If a group aligned “With” on a suit that then lost to a stronger winning suit, their alignment is void — they lose “With” and re-enter Estimation as independent players.
✔ CLARIFIED: In fast rounds, if several players bid the same highest number, the FIRST to bid it is the Caller and EVERY other player who bid that same number becomes “With” (up to three) — not only the last one.
2.3 Estimation Stage
After the Caller is finalized (and Confirmation complete in normal rounds), the remaining players estimate their tricks, subject to:
The Call Cap: no other player may estimate MORE than the Caller's bid (normal rounds). Example: if the Caller won with 5, the maximum estimate for anyone else is 5. (Fast rounds have no cap.)
Normal Dash: during estimation, more than one player may bid 0 tricks.
The 13 Rule: the total of all four players' bids may NEVER equal 13.
The Risk Player & the Rule of 13
Estimation begins with the player immediately after the Caller (counter-clockwise) and ends with the player immediately before the Caller — that last player is the Risk Player (⚡ RISK) and is shown with a special icon.
The Risk Player is forced to choose a number that makes the total ≠ 13 (either Under or Over 13).
✔ CLARIFIED: If the seat immediately before the Caller is a pre-bidding Dash Call player, the Risk obligation passes backward to the next eligible (non-Dash) estimator.
✔ CLARIFIED: If the first three bids already total 13, the Risk Player cannot bid 0; they are forced to bid 1–13 (pushing the round Over).
Risk Value

Difference from 13 | Risk Level | Risk Value | Example total
1 (total 12 or 14) | No Risk | 0 | 12
2 to 3 (10, 11, 15, 16) | Normal Risk | 10 | 10 (diff 3)
4 to 5 (8, 9, 17, 18) | Double Risk | 20 | 8 (diff 5)
6 or more (≤7 or ≥19) | Triple Risk | 30 | 6 (diff 7)

Risk Value applies ONLY to the Risk Player: added on success, deducted (on top of the trick difference) on failure.
Combined “With” + “Risk”
If the last estimator (Risk Player) also perfectly matches the Caller (number + suit), they are simultaneously “With” AND subject to Risk — both the ±10 With bonus and the Risk Value apply.
3. Forced Fast Rounds (The Colour) — Rounds 14–18
Rounds 14–18 are closed/fast: no auction. Players bid trick numbers once each (single pass, counter-clockwise), and the trump is mandatory:

Round | Forced Trump
14 | Sans (No-Trump)
15 | Spades
16 | Hearts
17 | Diamonds
18 | Clubs

Single pass: each player states one final number; there is no back-and-forth raising and no Confirmation Phase.
Caller / With: the first player to bid the highest number is the Caller; every other player who bid that same number becomes “With”.
The Golden Super Call (Fast Rounds)
If a player bids a Super Call (8+) in a fast round, the forced suit is immediately cancelled and that player chooses the trump. If outbid, the suit choice goes to the higher bidder.
Super Call Reset: only players who bid BEFORE the Super Caller must re-estimate (their estimates were under the old forced suit). Players who bid after it keep their estimates.
Round Extension
Any fast-round Super Call that overrides the forced suit adds one extra round to the game (e.g., the game becomes 19 rounds instead of 18).
✔ CLARIFIED: Extension rounds REPEAT the 14–18 forced-suit sequence: Round 19 = Sans, 20 = Spades, 21 = Hearts, 22 = Diamonds, 23 = Clubs, and so on. (This supersedes the older “every extension round is always Sanz” wording.)
4. Official Scoring System
Let T = actual tricks won, E = estimated bid.
Standard Players
Win (T = E):
Score = 10 + T + Bonuses
+10 if the player is the Caller or a With
+10 if the player is the sole winner (everyone else lost)
+Risk Value if the player is the Risk Player (10 / 20 / 30)
Loss (T ≠ E):
Score = −|T − E| − Deductions
−10 if the player is the Caller or a With
−10 if the player is the sole loser (everyone else won)
−Risk Value if the player is the Risk Player
✔ CLARIFIED: All bonuses and penalties are cumulative (they stack) unless explicitly forbidden.
Dash Call (Pre-Bidding) — Flat Scoring
A Dash Call does NOT use the 10 + T formula. It is a flat value, and the only modifier that stacks is the ±10 sole bonus. A Dash Call NEVER receives Risk.

Outcome | Round | Base | If also Sole (alone)
Win (0 tricks) | Under (total < 13) | +33 | +43
Win (0 tricks) | Over (total > 13) | +25 | +35
Loss (≥1 trick) | Under (total < 13) | −33 | −43
Loss (≥1 trick) | Over (total > 13) | −25 | −35

Normal Dash (0 estimated during the Estimation Stage)
Treated as an ordinary estimate of 0 using the 10 + T logic.
Success (0 tricks): +10 (plus sole/Risk if applicable).
Failure (takes tricks): −(10 + tricks). Example: takes 1 trick → −10 − 1 = −11.
A Normal Dash player CAN be the Risk Player (unlike a pre-bidding Dash Call).
Escalation Round (Sa'ayda)
Condition: all four players fail their estimations in the same round.
Result: that round scores ZERO for everyone, and the NEXT round's points multiplier escalates.
✔ CLARIFIED: The multiplier ladder is ×2 → ×4 → ×6 → ×8 on consecutive all-fail rounds, capped at ×8.
✔ CLARIFIED: The multiplier applies to EVERY score component (base, Caller/With, sole, Risk, and flat Dash values).
✔ CLARIFIED: The multiplier resets to ×1 as soon as any round has at least one successful player.
✔ CLARIFIED: A zeroed Sa'ayda round still counts toward the 18. If Round 18 itself is a Sa'ayda, the game is forced into Round 19 so the escalation round is actually played.
Classic Calculations Mode (Alternate Scoring)
An alternate, opt-in scoring mode offered alongside the Official Scoring System above. Reverse-engineered and verified against 18 rounds of real game data from a competitor app's “Classic Calculations” mode; a genuinely different per-role formula, not a variant of the Standard-Players formula in §4.
Let T = actual tricks won, E = estimated bid, miss = |T − E|, and TotalBids = the sum of all four players' final bids for the round.
Normal Player
Success (T = E): Score = E + 13
Failure (T ≠ E): Score = −miss
Caller / With (Wizz)
Success: Score = E + 13 + 10
Failure: Score = −(miss + Caller Penalty), where Caller Penalty = 10 normally, or 20 if TotalBids ≤ 11 (the round is under-bid by 2 or more tricks).
Risk Player
Success: Score = E + 13 + 10
Failure: Score = −(miss + 10)
Combined With + Risk (Wizz-Risk)
Success: Score = E + 13 + 10 + 10 (= E + 33)
Failure: Score = −(miss + 10 + 10)
Super Call (Classic)
A Super Call is any Caller bid of 8+ tricks (identical definition to §2.2, Super Call) — in Classic mode it uses its OWN fixed values instead of the E+13 formula above, and does NOT also add the Caller +10 (the Super Call value already accounts for it).
Success: Score = +42 (fixed, regardless of E)
Failure: Score = −20 (fixed, regardless of miss)
✔ CLARIFIED: only the Caller themself can hold the Classic Super Call role; a matching With player on that same bid keeps the ordinary Wizz / Wizz-Risk role and formula above, not the Super Call formula.
Dash Call (Pre-Bidding, Classic)
Uses fixed values keyed on whether the round's TotalBids finished at or under 13 (“Under”) or over 13 (“Over”) — not the Official Scoring System's ±33/±25 values in §4.
Success, Under (TotalBids ≤ 13): Score = +33
Success, Over (TotalBids > 13): Score = +23
Failure, Under: Score = −20
Failure, Over: Score = −10
Normal Dash (Classic)
Success, Under (TotalBids ≤ 13): Score = +23
Success, Over (TotalBids > 13): Score = +13
Failure, Under: Score = −10
Failure, Over: Score = −T (the tricks actually taken, as a penalty)
Sole Winner / Sole Loser (Classic)
Sole Winner: +10, added on top of whichever formula above applies — identical bonus to §4's Official Scoring System.
Sole Loser: the failing score from whichever formula above applies is doubled, then capped at a floor of −22 (i.e. Score = max(2 × base_failure_score, −22)) — identical cap to §4's Official Scoring System.
5. Quick-Reference Clarifications (Locked Decisions)
Suit names: Spades = Asbeed/Beg; Clubs = Trefle; Sans = No-Trump.
Minimum auction bid is 4.
Confirmation Phase: normal rounds only; raising the number frees the suit; same number requires equal/stronger suit.
Dash Call (pre-bid): max 2 players, flat ±33 (Under) / ±25 (Over), ±10 sole, NO Risk, counts as 0 in the total, plays all tricks, no estimation turn.
Normal Dash (0 in estimation): unlimited players; scored as 10+T; can be the Risk Player.
13 Rule: only the last estimator (Risk Player) is forced off 13; if the first three sum to 13 the last player must bid 1–13.
Call Cap: estimators may not exceed the Caller's bid (normal rounds); no cap in fast rounds.
Risk applies only to the Risk Player; if that seat is a pre-bid Dash, Risk passes to the previous non-Dash estimator.
With: by auction suit-alignment (any number) or estimation exact match (number + suit). Fast rounds: all top-number bidders except the Caller are With.
Fast-round Super Call: overrides the forced suit, no confirmation, extends the game by one round; only preceding players re-estimate.
Extension rounds repeat the 14–18 forced-suit sequence (19=Sans, 20=Spades, ...).
Sa'ayda multiplier: ×2 → ×4 → ×6 → ×8 (cap), applies to everything, resets on any success; a Round-18 Sa'ayda forces Round 19.
Classic Calculations mode (opt-in alternate scoring): Normal=E+13/−miss; Caller/Wizz=E+13+10/−(miss+10 or 20 if TotalBids≤11); Risk=E+13+10/−(miss+10); Wizz-Risk=E+33/−(miss+20); Super Call fixed +42/−20; Dash Call fixed +33/+23 (Under/Over 13) or −20/−10; Normal Dash fixed +23/+13 (Under/Over) or −10/−T; sole winner +10, sole loser doubles capped at −22 — same sole-winner/loser rule as the Official Scoring System.
All scoring bonuses/penalties stack unless explicitly forbidden.
— End of Official Reference —

---

## Amendment A1 — Legacy tracker Normal-mode Super Call (owner decision 2026-09-17)

> **Provenance:** NOT from the DOCX extraction above — owner-mandated house rule for the legacy `src/` score tracker only (`src/utils.ts` `SUPER_CALL` role in Normal mode). Classic mode (line 192) and the `design-ui/` multiplayer engine are explicitly unaffected.

- Win: **bid squared** — 8→64, 9→81, 10→100, 11→121, 12→144, 13→169. Sole winner still adds +10 (74/91/110/131/154/179).
- Loss: **half of that, rounded half-up, flat by bid** — −32/−41/−50/−61/−72/−85 — independent of tricks taken. Sole loser takes 10 extra (−42/−51/−60/−71/−82/−95) instead of any doubling rule.
- Everything else in Normal mode (other roles, Dash, Risk, sole-loser handling) is unchanged.

> **Pointer, 2026-10-21 (owner, Q19c; text above left unchanged).** The "legacy tracker only / `design-ui/` unaffected" scope is outdated: the native engine applies bid² in Normal mode (decision D1). Documentation correction only; no scoring change.

---

## Amendment A2 — Game Type and Calculation Mode as match options (owner decision 2026-10-06)

> **Provenance:** owner decision set **GM1–GM8** (2026-10-06), recorded in `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (epic E7, stories S65–S69) and designed in `docs/UI_UX_ROADMAP.md` (S36, §2.4, §4b). **This amendment adds a second Game Type and makes an already-implemented scoring mode selectable. It changes not one number in the rules above.**

### A2.1 Game Type — FULL and MINI

Two Game Types are selectable on every match, in every mode (casual rooms, Ranked, **and Unranked** — GM5; the three authority modes per Amendment A3). **MINI is not a different game. It is FULL with two numbers changed; every rule in this document applies to both identically except where A2 says otherwise.**

| | FULL (the default) | MINI |
|---|---|---|
| Base rounds | **18** | **10** |
| Normal rounds (full §2 phases: Avoid, Dash Call, Bidding, Confirmation, Estimates) | 1–13 | 1–5 |
| Forced Fast Rounds (§3) | **14–18** | **6–10** |
| Extensions | **up to 5** (18 → 23 maximum) | **exactly 1** (10 → 11 maximum) |

- **The trump ladder starts Sans on the first Forced Fast Round of either type** and repeats from Sans on every extension round. Full: 14 Sans, 15 Spades, 16 Hearts, 17 Diamonds, 18 Clubs, then 19 Sans, 20 Spades, … Mini: 6 Sans, 7 Spades, 8 Hearts, 9 Diamonds, 10 Clubs, then 11 Sans — **and Mini stops there** (A2.2).
- **Unchanged by Game Type, in both types:** 4 players, individual play, 52 cards, 13 each, 13 tricks, counter-clockwise play, dealer rotation, the minimum auction bid of 4, Super Call ≥ 8, Dash Call (max 2 players), the 13 Rule, Call Cap, Risk, With/Wazz, Sa'ayda, every scoring row, and every win condition. **MINI inherits all of it verbatim — there is no Mini-specific rule anywhere.**

### A2.2 Extensions under MINI (the only structural difference)

- §3's extension triggers — a fast-round Golden Super Call, and a Sa'ayda on the final round — operate exactly as written in both types, within the type's own fast window (Full 14–18, Mini 6–10).
- **MINI permits exactly ONE extension (10 → 11 maximum).** A second extension is **rejected**; the rejection uses the existing `ALREADY_EXTENDED` outcome, so no new rule surface is introduced. **FULL is unaffected: it keeps up to 5 extensions exactly as §3 describes.**
- Rounds past a type's ceiling cannot trigger an extension (Full 19+, Mini 11+), but are still dealt and played if an extension occurred.

### A2.3 Calculation Mode — NORMAL and CLASSIC

Both scoring modes in this document are **first-class and selectable on every match** (GM4):

- **NORMAL** — the tables as §5 states them; **Sa'ayda escalation caps at ×8**.
- **CLASSIC** — the Classic tables as §5 states them; **Sa'ayda escalation caps at ×2**.

**Neither formula changes.** The modes are already implemented (`calculateNormalScore`, `calculateClassicScore`); this amendment only makes the selection reach the match. **Classic is not a house rule, not legacy, and not a variant — it is a peer option.**

### A2.4 Ranked interaction (GM5 / GM6)

- **Ranked supports both Game Types.** Ranked is not Full-only, and Mini is not a casual-only format. The matchmaking tier-pool rule is **orthogonal to Game Type** — the server derives the tier pool exactly as RD9 specifies, and the Game Type rides along without widening or narrowing it.
- **MINI Ranked RP is 50% of FULL Ranked RP.** The full Ranked result is computed exactly as the Ranked system specifies — every input, every modifier, every mixed-tier asymmetry — and **only then** is the **final RP delta** multiplied by 0.5, for gains and losses alike (+20 → +10, −14 → −7). **There is no separate Mini RP formula, and no threshold, gate, ladder, division, progression, promotion/demotion, or season rule is adjusted to compensate for the shorter match.**
- **Rounding of a half-delta is CLOSED (2026-10-06): nearest integer, ties rounded AWAY FROM ZERO, symmetric for gains and losses.** +15 → **+8**, −15 → **−8**, +9 → +5, −9 → −5; even deltas are exact (+20 → +10, −14 → −7). The precedent in Amendment A1 ("rounded half-up") is a round-score rule and does not bind RP — **note the difference: half-up would give −7.5 → −7, which this rule deliberately rejects.** RP is an integer, and the round happens once, at settlement, on the final delta only.

---

## Amendment A3 — Ranked party eligibility, optional placement, and the Unranked match type (owner decision 2026-10-06)

> **Provenance:** owner decision, 2026-10-06, recorded in `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (decisions RD29–RD30, the RD6/RD22/RD28 amendments, epic E6b stories S70–S71) and designed in `docs/UI_UX_ROADMAP.md` (§2.1, §0.5, S36, §4b.4, §4b.4b, §4b.4c). **This amendment changes not one gameplay rule above.** It governs *who may sit at which kind of table*, and *what a match of a given type records*. Every rule in §§1–5 — deal, bidding, scoring, Sa'ayda, extensions, both Game Types, both Calculation Modes — applies to an Unranked match exactly as it applies to any other.

### A3.1 The three match types and the three authority modes

`mode` on the match document is **three values: `ROOM`, `RANKED`, or `UNRANKED`** (amending the two-value model RD28 was written with).

- **`ROOM`** — the invite-only table: code-shared, fills no seat from any pool, may start with 2–3 players, push-to-talk voice available.
- **`RANKED`** — the Ranked match: full progression. **Rank-down is `RANKED` plus a private/password access flag, never a mode of its own**; the flag gates entry and nothing else, so a Rank-down match is a Ranked match for every rule below.
- **`UNRANKED`** — a first-class, independent match type: rank-blind seat assignment, no progression of any kind. **Unranked is not `ROOM` plus a marker — the authority key itself carries the value**, and each gate below states its Unranked branch explicitly rather than inheriting a Room's.

**Game Type and Calculation Mode (Amendment A2) are selectable in all three modes.** Unranked plays FULL or MINI, NORMAL or CLASSIC, by the identical rules.

### A3.2 RD29 — Ranked party eligibility

> Two players may sit at the same Ranked table together iff their **tiers differ by at most one tier**.

- The boundary is the same one the solo pool uses (RD9: own tier or exactly one tier below), applied **against the creator** — every joining member must be within one tier of the creator.
- **Symmetric between friends.** RD9's "a lower-tier player cannot opt up" is a solo-pool rule and stays asymmetric there; between two consenting friends a Silver host may invite a Gold friend and vice versa.
- **Compared by tier, not by 19-rank index**, because King has no divisions.
- **Within one tier, any division difference is eligible** (Gold I + Gold III: eligible). **Two or more tiers apart is ineligible** (Gold + Bronze: ineligible). King's one tier below is Royal — King + Royal eligible, King + Platinum not.
- **Checked server-side only.** A client cannot read another player's rank, so the check is a Cloud Function; the joiner learns the outcome and a plain-language reason, never the host's RP.
- **No MMR, no rank-range expansion, no party queue, no special party rules.**

**The rule this section exists to state plainly: *Ranked-ineligible does not mean "cannot play together."*** Two players outside the allowed tier gap are refused entry to that **Ranked** table and are offered **Unranked** (A3.4), where they may play with no restriction at all. No refusal in this flow is a dead end.

### A3.3 RD30 — Placement is optional

> *"Placement is optional and must be explicitly chosen. If a player skips placement, the account starts at Bronze III as a real Ranked tier and follows normal Ranked progression. Once placement is skipped, placement cannot be started later on that account."*

- An unplaced account resolves to **Bronze III** for every tier comparison in A3.2 and every solo-pool derivation in RD9 — **a real tier, never a "no tier" state.** There is therefore no edge case in RD29: every player resolves to a tier.
- The placement track itself (3 matches, 10/20/70 weights, Platinum ceiling, once per account, counts toward no statistic) is unchanged; what changes is that **declining it is a legitimate Bronze start, not a downgrade, a block, or a penalty.**
- **The final clause is a rule of the account, not a punishment.** Skipping placement forecloses placement; it does not cap the account.

### A3.4 Unranked — the rank-blind match type

An Unranked match is a **normal match type**, reached either by choosing it on the Create Game screen or by the host converting a refused Ranked table pre-match ("Play Unranked" — host only, idempotent, never mid-match, clears the access credential, sets `mode: UNRANKED`).

**Seat assignment is rank-blind.** A King, a Diamond, a Gold, and a Bronze may all hold seats at the same Unranked table. **RD9 and RD29 do not apply to Unranked at all.**

**Seat filling:** one **shared** Unranked pool serves a party of friends and a solo player alike. Remaining seats are offered to available human players from that pool regardless of rank; **only after a seat has waited 4 minutes does a configured bot take it** (reusing the existing inactivity/leave bot-seat mechanism), at the difficulty and personality the host configured.

### A3.5 What Unranked does and does not record

| Rule | `ROOM` | `RANKED` | `UNRANKED` |
|---|---|---|---|
| All gameplay in §§1–5, both Game Types, both Calculation Modes | yes | yes | **yes** |
| RP / Ranked progression | no | yes | **no** |
| The 9 career statistics | no | yes | **no** |
| Manual Vote Kick | no | yes (incl. Rank-down) | **yes** |
| Automatic 15-timeout removal | yes | yes | **yes** |
| Push-to-talk voice | yes | no | **no** |
| Disconnect vote + pause | yes | no | **yes** |
| Settlement | no | yes | **no** |

- **Fully Unranked means no progression of any kind** — neither RP nor any of the nine career statistics. A finished Unranked match writes no Ranked outcome.
- **Voice is not available in Unranked**, including at a table whose remaining seats were filled from the shared pool by strangers. Push-to-talk is a Rooms-only capability, and Unranked is not a Room.
- **The disconnect vote and pause are available in Unranked**, as in a Room.
- **The automatic 15-timeout removal is shared by all three modes** — it is not Vote Kick and shares no state with it.

> **Pointer (owner decision DEC-DISC-01, 2026-10-14; the text above is left unchanged).** The row above is labelled "Disconnect vote + pause" and the bullet above it says the same. **The vote is exactly as stated and stays available in `ROOM` and `UNRANKED` and not in `RANKED`; the "pause" half of both lines is superseded** — a disconnected player's seat is answered by a bounded 30-second question while play continues, and no match pauses. See Amendment A4.4. Nothing else in A3 changes: A3.5's mode table, its "no progression of any kind" rule and its Voice/15-timeout rows all stand verbatim.

> **Pointer (owner clarification, 2026-10-17; the Manual Vote Kick row above is updated in
> place).** `UNRANKED` is `RANKED` in every rule except RP (frozen both ways), so **Vote Kick
> is available in `UNRANKED`** — `ROOM` is the only mode without it — under RD17/18's
> conditions exactly as in `RANKED`. The career Koz counter is a **plain departure counter,
> not Ranked progression** (it moves no tier and no RP), which is why A5.1's `UNRANKED`
> charges record (A5.3 item 3 now ratified). See
> `docs/decisions/2026-10-17-unranked-scope-owner-clarification.md`.

### A3.6 What this amendment does not change

Solo Auto-Match (RD9), the tier ladder (19 ranks), the RP engine and its thresholds, mixed-tier asymmetry (RD5) and the ×2 private loss cap (both surviving, scoped to public matchmaking and within-one-tier Rank-down matches), placement mechanics where chosen, seasons, the leaderboard, the rules of §§1–5, and Amendment A2 in full. **Amendment A1 and A2 are untouched, and no rule number in the document above changes** — Unranked is a match type, not a ruleset.

---

## Amendment A4 — Match result on departure, and who plays a vacated seat (owner decisions 2026-10-14)

> **Provenance:** owner decision set **DEC-S61-01, DEC-RD19-01/02/03/04/05, DEC-RD21-01/02, DEC-OPEN1-01, DEC-OPEN2-01, DEC-DISC-01** (2026-10-14), recorded in `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (RD31, stories S72–S74, risks R33–R35, M11) and designed in `docs/UI_UX_ROADMAP.md` (§0.5, §1.2-H, §1.3, §2.2, §4.4.2, §4.6, §4.7, §4b.16–§4b.17). **This amendment changes no gameplay rule above.** It settles *what a match records when a player's participation ends* and *who plays a seat its owner has left*, and it names the two details the owner explicitly left open (A4.1, A4.4).

### A4.1 RD31 — a permanent departure records 2 Koz as that player's match result

When a player's **participation in a match ends permanently**, that player's individual match result is **2 Koz** — regardless of why they left, and in all three match types.

- **It fires on**: an explicit **Leave Match** (path B, story S72); a passed **Vote Kick** (path E, S63); the automatic **15-timeout removal** (path A's terminal case, all three modes, OPEN-1 CLOSED); and a **disconnect** whose seat is **permanently replaced** (path D's terminal case, `ROOM`/`UNRANKED`).
- **It never fires on a borrowed seat.** A temporary takeover that still has a reclaim path is **not** a departure: an RD19 inactivity takeover (path C, S73) or a disconnect takeover while the table is still waiting (S74). The seat returns to its owner, that player's result is decided by actual play at match end, and **RD31 does not fire**. This is the discriminator, and it is the whole reason the five paths must stay distinguishable: *did participation end, or was the seat borrowed?*
- **Koz is a placement, not a score.** RD31 writes no scoring formula and changes none — §4/§5's tables, Sa'ayda, extensions, both Game Types and both Calculation Modes are untouched, and the RP engine (S47) takes its inputs as before. What RD31 changes is only the *result recorded for the departed player*.
- **Continuing players are settled from corrected scores exactly as they always have been** (RD20/S61). Nothing in this amendment displaces a continuing player's placement.
- **OPEN-4 is pending, and it is purely about the bookkeeping** — whether "2 Koz" means **+2 to the career Koz count**, a **forced last-place placement plus the double count**, or a **distinct result value** that the nine-statistics accounting and S50 settlement must model; and whether the departed player is forced below every continuing player even when the bot left in their seat did better than last. **The trigger and the intent are decided; the record is not.** Until the owner answers: no implementation may displace another player's placement, and no screen may state the form.
- **Its interaction with A3.5 is flagged, not resolved**: `UNRANKED` records no progression of any kind, so a 2-Koz *statistic* has nowhere to land in that mode even though the trigger fires there. This is a sub-case of OPEN-4, not a new rule.

> **Pointer (owner ruling 2026-10-16 — Amendment A5; the A4.1 text above is left verbatim as
> history).** A4.1 is superseded in three respects, and A5.1 governs: (1) **OPEN-4 is CLOSED**
> on option (a) — "**+2 to the career Koz count**"; options (b) and (c) are **rejected**. (2)
> **`ROOM` is excluded from the 2-Koz rule entirely** — "in all three match types" no longer
> holds; the rule is `RANKED` + `UNRANKED` only. (3) **A passed Vote Kick records 1 Koz, not 2**,
> restoring RD18's wording; the doubling is repealed for that path alone.
> **A4.1's borrowed-seat discriminator and "no displacement of a continuing player" both stand
> unchanged** — they are the basis for A5.1's "return when allowed = no penalty" line.

### A4.2 Who plays a vacated seat, and with which bot

| Path | Bot profile | Timeout counter | Seat returns | RD31 |
|---|---|---|---|---|
| A — ordinary decision timeout | **MEDIUM** (pinned, RD20/S61) | **+1 per timeout; the 15th removes the player** | no | on the 15-timeout removal only |
| B — explicit Leave Match (S72) | the match's **configured bot** (the Create-Game choice) | no | no | **yes** |
| C — RD19 inactivity (S73) | the match's **configured bot** | **not incremented** | **yes** — after the current trick, at the next decision point | **no** |
| D — disconnect (S74) | the match's **configured bot** (DEC-RD21-01) | no | **yes**, while the table is still waiting | on permanent replacement only |
| E — Vote Kick (S63) | the match's **configured bot** (`botSeats`) | no | no | **yes** |

- **MEDIUM stays pinned to path A alone.** It is the timeout bot and nothing else; every other vacancy is played with the bot profile the match was configured with. RD20's pin is **not** widened.
- **The configurable decision timer is for humans only** (DEC-RD21-02). A bot — whichever path put it in the seat — keeps the fixed 2 s pace. No spec, artboard or test may make a bot seat wear the human timer or the human countdown.
- **The counter counts timeouts, not absence.** Only path A increments it. The 15-timeout removal it drives is shared by all three modes, is not Vote Kick, and shares no state with it (A3.5).

### A4.3 RD19 — inactivity is measured at decision points, reported by the player's own app

- **Decision-point only** (DEC-RD19-01). An ordinary timeout behaves exactly as before (path A: MEDIUM bot, +1 counter). RD19 fires only on sustained absence at decision points.
- **50 s of sustained absence → the configured bot takes the seat.** **50 s is cumulative across consecutive ignored decision windows, not a gap within one:** RD21's longest single window is 25 s (20 s plus the 5 s Dash/Bidding/Estimates offset), so no single window can reach it. **Clock origin**: the decision point presented to that player. **Deadline**: 50 s of accumulated ignored windows. **Reset**: a genuine interaction inside the player's own window. **Trigger**: expiry of the accumulation. **The takeover does not increment the timeout counter.** *(This accumulation is a derivation from RD21's 25 s ceiling, not an owner-specified clock shape — plan risk **R33**; confirm it before implementing S73 rather than inventing a variant.)*
- **Reclaim is trick-bounded** (DEC-RD19-04): the bot finishes the **current trick**, control returns at the **next decision point**, in the **same seat**. A reclaim is not a rejoin and not a departure — RD31 does not fire.
- **Activity is self-reported by the player's own app** (DEC-RD19-02). The frozen Firestore rules are respected and **no permission is widened** to carry this signal. **The security limitation is documented, not hidden:** a client reporting its own activity can lie about it, so RD19 is an experience-quality mechanism and never an anti-abuse control — no rule outcome, placement, RP decision or statistic may depend on that report being honest.
- **All three match types** (DEC-RD19-03): `ROOM`, `UNRANKED`, and `RANKED`.

### A4.4 What closed on 2026-10-14, and what the answers did not settle

- **OPEN-1 — CLOSED** (DEC-OPEN1-01). The automatic 15-timeout removal is **permanent for that match**; the player may start or join another match afterwards. The removal behaviour as implemented is preserved.
- **OPEN-2 — CLOSED** (DEC-OPEN2-01). A Vote Kick vote runs while **gameplay continues**. There is no freeze-and-pause and none may be introduced; S63's acceptance criteria and test plan are written to match.
- **The disconnect question is put to `ROOM` and `UNRANKED` tables only** (DEC-DISC-01): `mode ∈ {ROOM, UNRANKED}`. **Vote Kick serves `RANKED` and `UNRANKED`** (amended 2026-10-17 — `ROOM` alone excludes it), and the two share no state, no UI and no countdown. On a dropped connection the table is asked **one question** with a **30-second window**; if nobody agrees within 30 s, the match continues with the **Create-Game-configured bot**. D9's "pause the match" branch is **superseded**; what survives of D9 is the invariant that **a wait is always bounded, never indefinite**.
- **OPEN-5 stays open because the answers do not settle it**: (i) how many answers constitute agreement, (ii) what the table does when they agree to **wait** — D9's pause being gone, the WAIT outcome and its duration are undefined — and (iii) what makes a replacement **permanent**, and whether RD31 fires at that moment. S74's WAIT half and the S41/S42 artboards that draw it are gated on this. **No threshold may be invented and none may be drawn or specified as if it were current.**

> **Pointer (owner ruling 2026-10-16 — Amendment A5; the bullets above are left verbatim as
> history).** The last bullet above is superseded: **OPEN-5 is CLOSED** — agreement is
> **2 of 3**, a winning **WAIT** pauses the match for a **bounded 90 seconds**, and no agreement
> inside the 30-second window resumes with the **configured bot** (that half was already
> DEC-DISC-01 and is unchanged). **A4.4's OPEN-2 closure still binds and is not reopened**: the
> vote runs over a **live table with no pause while the ballot is open**; the 90-second pause
> begins only *after* a WAIT verdict. **OPEN-1 and OPEN-2 stay closed.** See A5.2.

### A4.5 What this amendment does not change

Every rule in §§1–5 — the deal, the auction, both Game Types, both Calculation Modes, all scoring tables, Sa'ayda, extensions, every win condition —, Amendments A1, A2 and A3 in full (including A3.5's mode table; see the pointer there), the RP engine and its thresholds, the tier ladder, placement mechanics, seasons, the leaderboard, Solo Auto-Match (RD9), the 2 s bot pace, and every timer value in RD21. **No rule number in this document changes and no scoring or ranking formula is modified** — RD31 is a result-recording rule, and the mechanics it reports on are exactly the ones already written above.

---

## Amendment A5 — OPEN-4 and OPEN-5 closed by the owner, and RD31's mode scope corrected (owner ruling 2026-10-16)

> **Provenance:** owner ruling relayed **2026-10-16**. It closes the two questions A4 named as
> open (A4.1's bookkeeping, A4.4's third bullet) and **narrows the mode scope A4 wrote too
> broadly**. **Scope correction, stated once and governing everywhere below:** the 2-Koz rule
> applies in **`RANKED` and `UNRANKED`**; **`ROOM` (normal rooms) has no 2-Koz rule at all.**
> A5 changes no gameplay mechanic — like A4, it is about what a match *records* and when the
> disconnect vote may pause. The owner's own wording is still **not captured** in any document
> (see `docs/decisions/2026-10-16-open4-open5-owner-closure.md` §0).

### A5.1 OPEN-4 — CLOSED: option (a), +2 to the career Koz count

**"2 Koz" is a counter increment — not a placement, and not a new result value.**

- **+2 to the player's career Koz count** — the first option A4.1 enumerated.
- **Option (b) rejected: no forced last-place.** A departure never positions the player below a
  continuing player, and never displaces a continuing player's placement.
- **Option (c) rejected: no distinct "2 Koz" result value.** The nine-statistics accounting and
  the S50 settlement gain **no new enum**, and no result row changes shape.
- **The bot seat remains separate from the player.** The bot's play in a vacated seat is not the
  departed player's placement and is not attributed to them.
- **Return when allowed = no penalty.** An RD19 reclaim (path C) and a return while the table
  still waits (path D before permanence) charge nothing. This is A4.1's borrowed-seat
  discriminator applied, not a new exception.

**The charge table (corrected scope — this table governs over A4.1's fires-on list and A4.2's
"RD31" column):**

| Departure | `RANKED` | `UNRANKED` | `ROOM` |
|---|---|---|---|
| **Explicit Leave Match** (S72) | **2 Koz** | **2 Koz** | **no 2-Koz rule** |
| **15-timeout** automatic removal (RD20, OPEN-1) | **2 Koz** | **2 Koz** | **no 2-Koz rule** |
| **Vote Kick** passed (RD18, S63) | **1 Koz** | **1 Koz** (amended 2026-10-17) | n/a — not held in `ROOM` |
| **Disconnect** permanently replaced (S74) | n/a — the vote is not held in Ranked | **2 Koz** | **no Koz** |
| Reclaim / return while the table waits | **no penalty** | **no penalty** | **no penalty** |

- **Vote Kick records 1 Koz, final.** This restores RD18's "recorded as **Koz**" and repeals
  RD31's doubling **for that path only**; RD31 still doubles the departure paths above.
- **`ROOM` is out of RD31 entirely.** Neither an explicit leave nor a 15-timeout removal in a
  normal room charges anything under this rule, and a Room disconnect replacement charges
  nothing — see A5.3.1.

### A5.2 OPEN-5 — CLOSED: the disconnect vote's three mechanics

- **Window: 30 seconds**, put to the table **while play continues**. No pause while the ballot is
  open — A4.4's OPEN-2 closure binds both votes and is **not** reopened here.
- **Agreement threshold: majority = 2 of 3** answers.
- **A winning WAIT: the match pauses for 90 seconds.** Bounded, never indefinite — the single D9
  invariant A4.4 preserved. The pause begins **after** the verdict, never during the vote.
- **No agreement inside 30 s: continue with the configured bot** (DEC-DISC-01, unchanged).
- **Permanent replacement:** in `UNRANKED` it records **2 Koz** (A5.1); in `ROOM` it records
  **nothing under RD31**. In `RANKED` the question is not asked at all.
- **Still shared by all three modes:** the 15-timeout removal itself (RD20/A4.2) — A5 removes its
  *record* in `ROOM`, not the removal.

### A5.3 Flagged, not silently resolved

1. **`ROOM` exclusion contradicts A4.1's blanket wording, which stays on the page verbatim.**
   "**regardless of why they left, and in all three match types**" (A4.1) and A4.1's path-D
   `ROOM` case are superseded by A5.1's table under the pointer-note convention. A reader who
   stops above A5 will still see the old claim; the pointers at A4.1 and A4.4 are the guard.
2. **Vote Kick 1 Koz contradicts A4.1's fires-on bullet ("a passed Vote Kick … 2 Koz") and
   A4.2's path-E "RD31: yes".** Left verbatim; A5.1 governs. This is a **repeal of the doubling
   for that path**, not of RD31.
3. **`UNRANKED` charges meet A3.5's "no progression of any kind." — RESOLVED (owner
   clarification, 2026-10-17).** A5.1 applies the 2-Koz rule in `UNRANKED`, while A3.5 and RD28
   state that Unranked accumulates **no Ranked statistics** — the sub-case A4.1 flagged and
   called "a sub-case of OPEN-4". A5.1 depends on the reading that **the career Koz counter is
   a plain departure counter, not Ranked progression** (it moves no tier and no RP). That
   reading is now **owner-ratified**, and **A3.5 carries its own pointer** (added 2026-10-17):
   `UNRANKED` is `RANKED` in every rule except RP (frozen both ways). The former fallback — "if
   the owner intends Unranked to charge nothing" — is **closed**; A5.1's `UNRANKED` column
   stands as written. See `docs/decisions/2026-10-17-unranked-scope-owner-clarification.md`.
4. **The 90-second WAIT pause must not be read as re-opening OPEN-2. — RATIFIED (owner,
   2026-10-17).** He walked the disconnect scenario end-to-end and confirmed it: the 30-second
   vote runs over a live table, nothing pauses while the ballot is open, the pause happens only
   after a WAIT verdict wins and is bounded at 90 seconds, a returning player resumes the seat,
   and a non-returning player is continued with the bot. His verdict: *"the old rule — play does
   not stop while you vote — is followed to the letter; the pause happens after the vote, not
   during it; there was no contradiction in the first place"* (Arabic verbatim in the record §6).
   The compatibility reading is therefore no longer an assumption — it is his, recorded there.
5. **`docs/decisions/2026-10-16-open4-open5-owner-closure.md` carries A5.1's scope as committed.**
   An earlier draft of the record wrote "2 Koz, `RANKED` only" and "no Koz for a Room **or
   Unranked** replacement"; the record as committed (`cd02a71`) was corrected before landing —
   2 Koz in `RANKED` + `UNRANKED` with `ROOM` excluded, and Vote Kick 1 Koz final. No outstanding
   correction; this note stands only as the guard against re-reading the older draft.

### A5.4 What A5 does not change

Every gameplay rule in §§1–5 and Amendments A1–A4: the deal, the auction, both Game Types, both
Calculation Modes, all scoring tables, Sa'ayda, extensions, every win condition, the RP engine
and its thresholds, the tier ladder, placement mechanics, seasons, the leaderboard, Solo
Auto-Match (RD9), the 2 s bot pace, every RD21 timer, RD19's decision-point clock and
trick-bounded reclaim, the five-path taxonomy, MEDIUM's pin to path A, the private cumulative
timeout counter, the Vote Kick's availability (`RANKED` + `UNRANKED` since 2026-10-17) and no-rejoin permanence, and the frozen
Firestore rules. **A5 changes only what a match records, in which modes it records it, and when
the disconnect table may pause for a bounded 90 seconds.**

## Amendment A6 — General Pass, Sa'ayda extension, Normal Dash with Risk (owner rulings 2026-10-21)

> **Status:** rules recorded; **engine not yet changed** (it differs where noted). Record:
> `docs/decisions/2026-10-21-server-authority-and-owner-rulings.md`. Earlier text above is unchanged.

- **A6.1 General Pass** (all four pass the auction): redeal. **Normal Calculation** applies the Double
  multiplier; **Classic** redeals with **no** Double. Example, Caller bids 5 and makes 5: Normal
  (10+5)+10 = 25 → ×2 = 50; Classic 5+13+10 = 28 (the engine today would give 56). Open: composition with a
  carried Sa'ayda multiplier (Q1).
- **A6.2 Sa'ayda extension (owner, corrected 2026-10-21 later; supersedes the earlier A6.2 text and the
  "final round only" wording of §1/§3/§4 and A2.2 where they conflict):** (1) each *round* in 14–18 with a
  Sa'ayda adds one round to the baseline 18 (per round, not per player; 14 and 16 → planned last round 20);
  (2) if Sa'ayda occurs in the actual current final round, add one round and repeat until the final
  round ends with a winner. Sa'ayda in a non-final round after 18 adds nothing. **Engine differs:**
  it never extends from round 19 on and caps extensions (FULL 5, MINI 1); rule (2) is not implemented.
  Follow-up (owner): MINI uses the same logic on rounds 6–10; Super Call extensions combine with the count; a tied final round counts as having a winner (shared King, RD17). Extension caps are removed (owner, Q2c). **Koz on ties (owner, Q2d; supersedes RD17's last sentence):** Koz = every player tied at the lowest score unless all four tie (then all King); shared-Koz players are Koz, not Second/Third. Vote Kick/RP effects open (Q2e).
- **A6.3 Normal Dash + Risk (Normal Calculation):** the Risk player who is a Normal Dash gets the Risk
  value **plus** the Normal Dash points. Example, total bids 15 (Risk 10): 0 tricks → +20 (engine +10);
  2 tricks → −22 (engine −12). Classic is open (Q4).
- **A6.4 (owner, 2026-10-21 later; supersedes A6.1's "redeal" wording where it differs):** a General Pass consumes the round number — round 6 all pass → round 7 is played ×2; a Sa'ayda in round 7 → round 8 ×4. Classic carries no Double (consumption in Classic: Q1b). The engine redeals the same round and differs.
- **A6.5 Super Call extension (owner):** only if the Caller succeeds; supersedes the "any Super Call" wording of §3.
- **A6.6 Departure charges (owner; supersedes A5.1 where it differs):** ROOM leave for any reason = 1 Koz; RANKED/UNRANKED leave for any reason = 2 Koz; charged only when the match finishes and the player did not return. Open: Q14c, Q14d. No Vote Kick against a shared Koz (unique Koz required).
- **A6.7 (owner, later; supersedes A6.6 and A3.5 where they differ):** UNRANKED computes no RP but enters all profile statistics. A passed Vote Kick = 2 Koz (RANKED, UNRANKED). A match abandoned by everyone: all players Koz and RP deducted (details Q27). No cap on General Passes or Sa'aydas; the multiplier caps at ×8; a Classic General Pass consumes the round with no multiplier. Players tied at the bottom share the last placement for RP.
- **A6.8 (owner, 2026-10-21 later):** a ROOM needs a full table of four (supersedes A3.1's "may start with 2–3 players"); Classic Normal Dash + Risk adds both components (numbers to be confirmed); profile shows counts and percentages over Games Played.
- **A6.9 With (owner, 2026-10-21 later):** Normal rounds — With = any other player who bid the final Caller's winning suit at any time; their With number is their last bid in that suit. Fast rounds — With only if the suit matches the final suit and the number equals the highest winning number exactly (≥8 alone is not enough). Supersedes the engine's Super Call With set. Fast-round "suit" meaning: Q28.
- **A6.10 (owner, later; supersedes A6.9's fast-round part):** any fast-round player declaring 8+ may choose any trump; a Super Caller's suit replaces the forced suit and others re-declare (who: Q29); a higher Super Call repeats this. Fast-round With: highest winning number exactly, plus the estimate made under the final trump if the trump changed. Not every 8+ is With.
