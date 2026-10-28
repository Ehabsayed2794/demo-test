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
Number of Rounds: 18 standard rounds, plus any extension rounds created by a fast-round Super Call (see §3) or by a Sa'ayda on the final round (see §4). *(This is the FULL Game Type — the default. A MINI match plays 10 base rounds with Forced Fast Rounds from 6 and exactly one extension; see **Amendment A2**.)* **[Superseded 2026-10-21 — Sa'ayda/extension: see Current Rules §2–3]]**
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

> **Pointer, 2026-10-23 (owner; table above left verbatim).** The **strength order is unchanged**
> (Sans > Spades > Hearts > Diamonds > Clubs) and so are the English names and internal identifiers.
> Arabic **display labels** are standardized by **A8.5 / Q52**: سانز / أسبيد / هارت / كاروه / تريفل.
> "Sunz" and "Trefle" remain display synonyms only; comparison logic keeps reading the internal suit.
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
  > **Pointer, 2026-10-26 (documentation-only; the line above is left verbatim).** Fast rounds do not use the
  > ordinary Confirmation Phase. **Exception: a Fast Super Call initiates a special trump-selection and
  > full re-declaration cycle.** Every player except the current Super Caller re-declares counter-clockwise
  > under the selected trump. Each subsequent higher Super Call repeats the cycle. The final Caller, the
  > final trump, the estimates and the With set are derived from the final completed cycle
  > (A6.10 / A6.11; see §3 "The Golden Super Call").
Super Call
Definition: bidding 8 tricks or more.
Normal rounds (1–13): the Super Caller enters the Confirmation Phase and may confirm, raise the number, or change the suit.
Fast rounds (14–18): instantly overrides the forced trump suit — no Confirmation Phase — and extends the game (see §3).
If two players Super Call, the higher bid (then stronger suit) wins the right to choose the trump. *(Reaffirmed 2026-10-22 by A7.2 as the governing fast-round tie-break; seat order plays no part, and Sans beats every suit on a tie.)*
  > **Pointer, 2026-10-26 (documentation-only; the bullets above are left verbatim).** The fast-round
  > bullet's "no Confirmation Phase" means no ORDINARY Confirmation Phase. A Fast Super Call still has its
  > own trump-selection step — the Super Caller chooses the trump — followed by its own full
  > re-declaration cycle. See the pointer under §2's Confirmation Phase and under §3's Golden Super Call.
2.2.1 “With” (Wazz) Rules
A player becomes “With” the Caller in one of two ways:
Auction Alignment: during the auction, a player bid the SAME SUIT as the eventual winning Caller — even if their trick number differed.
Estimation Jump-In: during the Estimation Stage, a player matches BOTH the Caller's number AND suit exactly. This is open to anyone, including players who passed the auction.
Defeated Suit Rule:
If a group aligned “With” on a suit that then lost to a stronger winning suit, their alignment is void — they lose “With” and re-enter Estimation as independent players.
✔ CLARIFIED: In fast rounds, if several players bid the same highest number, the FIRST to bid it is the Caller and EVERY other player who bid that same number becomes “With” (up to three) — not only the last one. **[Superseded 2026-10-21 — With in fast rounds: Current Rules §5]** **[Superseded 2026-10-22 — Caller tie-break: A7.2; the stronger suit wins an equal-number tie and seat order is never a tie-break]**
2.3 Estimation Stage
After the Caller is finalized (and Confirmation complete in normal rounds), the remaining players estimate their tricks, subject to:
The Call Cap: no other player may estimate MORE than the Caller's bid (normal rounds). Example: if the Caller won with 5, the maximum estimate for anyone else is 5. (Fast rounds have no cap.)
  > **Pointer, 2026-10-26 (documentation-only; the bullet above is left verbatim).** "No cap" in fast
  > rounds means no CALLER-RELATIVE cap only. The absolute 13-trick maximum holds in EVERY round, fast
  > rounds included: no declaration and no confirmed call may exceed 13.
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
Caller / With: the first player to bid the highest number is the Caller; every other player who bid that same number becomes “With”. **[Superseded 2026-10-21 — With in fast rounds: Current Rules §5]**
The Golden Super Call (Fast Rounds)
If a player bids a Super Call (8+) in a fast round, the forced suit is immediately cancelled and that player chooses the trump. If outbid, the suit choice goes to the higher bidder. **[Superseded 2026-10-21 — any 8+ declarer may choose the trump: Current Rules §5]**
Super Call Reset: only players who bid BEFORE the Super Caller must re-estimate (their estimates were under the old forced suit). Players who bid after it keep their estimates. **[Superseded 2026-10-21 — all players except the Super Caller re-declare: Current Rules §5]**
  > **Pointer, 2026-10-26 (documentation-only; the lines above are left verbatim).** A Fast Super Call is
  > not the end of the fast round. The Super Caller chooses the trump, and then EVERY player except that
  > Super Caller re-declares counter-clockwise under the chosen trump — including the players who
  > declared BEFORE and AFTER the Super Caller. No declaration made under the replaced trump survives.
  > Each higher Super Call repeats the whole cycle, and the final Caller, the final trump, the estimates
  > and the With set come from the final completed cycle (A6.10 / A6.11).
Round Extension
Any fast-round Super Call that overrides the forced suit adds one extra round to the game (e.g., the game becomes 19 rounds instead of 18). **[Superseded 2026-10-21 — extension only if the Caller succeeds: Current Rules §3]**
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
✔ CLARIFIED: A zeroed Sa'ayda round still counts toward the 18. If Round 18 itself is a Sa'ayda, the game is forced into Round 19 so the escalation round is actually played. **[Superseded 2026-10-21 — Sa'ayda extension: Current Rules §2]**
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
  > **Pointer, 2026-10-26 (documentation-only; the line above is left verbatim).** "Only preceding players
  > re-estimate" was superseded on 2026-10-21: after a Fast Super Call EVERY player except the Super
  > Caller re-declares, counter-clockwise, and each higher Super Call repeats the cycle (A6.10 / A6.11).
  > "No confirmation" means no ordinary Confirmation Phase — the Fast Super Call has its own
  > trump-selection and full re-declaration cycle.
Extension rounds repeat the 14–18 forced-suit sequence (19=Sans, 20=Spades, ...).
Sa'ayda multiplier: ×2 → ×4 → ×6 → ×8 (cap), applies to everything, resets on any success; a Round-18 Sa'ayda forces Round 19. **[Superseded 2026-10-21 — Sa'ayda extension: Current Rules §2]**
Classic Calculations mode (opt-in alternate scoring): Normal=E+13/−miss; Caller/Wizz=E+13+10/−(miss+10 or 20 if TotalBids≤11); Risk=E+13+10/−(miss+10); Wizz-Risk=E+33/−(miss+20); Super Call fixed +42/−20; Dash Call fixed +33/+23 (Under/Over 13) or −20/−10; Normal Dash fixed +23/+13 (Under/Over) or −10/−T; sole winner +10, sole loser doubles capped at −22 — same sole-winner/loser rule as the Official Scoring System.
All scoring bonuses/penalties stack unless explicitly forbidden.
— End of Official Reference —

---


## Current Rules — everything agreed with the owner, 2026-10-21 (CONSOLIDATED; governs where older text differs)

> Source: owner rulings of 2026-10-21 (`docs/decisions/2026-10-21-server-authority-and-owner-rulings.md`, §A–§V) as consolidated that day, then amended in place by the owner rulings of 2026-10-22 (**Amendment A6.12** and **Amendment A7**, recorded in `docs/decisions/OPEN_QUESTIONS.md` as Q14b(1) and Q30–Q34). Older sentences that these replace are tagged **[Superseded 2026-10-21]** in place and left readable. **The Kotlin engine does not yet implement most of this section** (see the discrepancy list in the decision record); this is the target rule set, not a statement of current code behaviour. Amendments A6.1–A6.12, A7.1–A7.5 and A8.1–A8.8 below are the dated trail.

1. **General Pass (all four pass the auction).** The round is consumed (round 6 all pass → round 7 is played). **Normal Calculation:** the next round is multiplied ×2 and feeds the Sa'ayda ladder (a Sa'ayda in round 7 → round 8 ×4, then ×6, ×8). **Classic:** the round is consumed with **no** multiplier. No limit on how often General Pass or Sa'ayda can happen; the multiplier is capped at **×8**. General Pass can occur only in normal rounds (fast rounds have no auction).

   > **Pointer, 2026-10-23 (owner; text above left verbatim — confirmation).** **A8.7 / Q51** confirms this
   > item and defines the mechanism: the consumed round is replaced by a **new round number** with a fresh
   > deal; Normal is ×2, Classic has no multiplier. "Consumed" and "redeal" are the same behaviour (C-2
   > closed).
2. **Sa'ayda match extension.** (a) Count the rounds in the inclusive window **14–18 (FULL) / 6–10 (MINI)** in which a Sa'ayda occurs; each such **round** (not each player) adds one round after the baseline last round (18 / 10). (b) If a Sa'ayda occurs in the **actual current final round**, add one round, and repeat for each new final round until the final round ends with a winner. A Sa'ayda in a non-final round after the window adds nothing. **No cap** on extensions. A tied top score counts as a winner (shared King).

   > **Pointer, 2026-10-23 (owner; text above left verbatim — confirmation).** **A8.8 / Q48** fixes the
   > arithmetic: 14→19, 14+15→20, 14+15+16→21, 13→18, generalized to 18 + N on FULL and 6 + N on MINI;
   > (b) still governs rounds beyond 18. The helper exists but is **not wired** (`computeRoundExtension` has
   > no production caller).
3. **Super Call extension.** A fast-round Super Call that overrides the forced trump adds one round **only if the Caller succeeds**; it combines with the Sa'ayda count.
4. **Normal Dash + Risk.** A Normal Dash player who is also the Risk player receives the Risk value **plus** the Normal Dash points, with neither dropped nor counted twice. **Classic (owner, 2026-10-22; A7.1 closes the open numbers):** the same component addition — Risk value + Normal Dash points, the miss counted **once**. On the owner's Classic case (total bids 15, Risk value 10, a Normal Dash seat taking 2 against its 0 estimate) the miss is −2 + (−10) = **−12** and a success on that Over round is +13 + 10 = **+23**. The Normal-mode **−22 is not a Classic value**, and a Classic **Wizz-Risk** formula is not this role's rule.
5. **With/Wazz.** *Ordinary rounds:* With = every other player who bid the final Caller's **winning suit** at any time; their With number is their **latest bid in that suit** (a matching number alone is not enough). *Fast rounds:* any player declaring **8+** may choose any trump (Sans, Spades, Hearts, Clubs, Diamonds); the Super Caller's suit replaces the forced suit and **every other player re-declares** (counter-clockwise); a higher Super Call repeats this. With is only a player whose **final number equals the highest winning number exactly**, and, if the trump changed, whose estimate was made **under the final trump**. Not every 8+ declarer is With. **Equal-highest tie (owner, 2026-10-22; A7.2):** the higher **number** wins whatever the suit; at an equal number the **stronger suit** wins the Call by the official hierarchy (Sans > Spades > Hearts > Diamonds > Clubs, §1), so **8♠ beats 8♥** and **9♥ beats 8♠** — **seat order never breaks a tie**. Fast-round With needs **both** the exact number **and** the matching suit, so a tied declarer whose suit lost the tie is not With. Two seats declaring the same number in the same suit is **not settled** (O-02b).

   > **Pointer, 2026-10-23 (owner; text above left verbatim).** The last sentence is now settled by
   > **A8.1 / Q35**: an exact number-and-suit duplicate is **legal**; the **first** declarer is Caller and
   > each later exact duplicate is **With**; declaration order decides only that case. A7.2's suit tie-break
   > and "seat order never decides" are unchanged.
6. **Placement and Koz on ties.** Koz = every player tied at the **lowest** score, unless all four are tied (then all four are King). A tie at the top shares King. Shared-Koz players are Koz (not Second/Third) and share the same last placement for RP. **No Vote Kick** when the Koz is shared (a unique Koz is required).
7. **Vote Kick and departure charges.** A passed Vote Kick records **2 Koz** for the kicked player (RANKED, UNRANKED; **ROOM excluded — no charge in ROOM**). Leaving a match **voluntarily** records **2 Koz in RANKED and UNRANKED** and **1 Koz in ROOM**, applied **when the match finishes** and only if the player did not return; a **disconnect or app close that is not a voluntary departure charges nothing** — meaning no *leave* penalty is added, and this does **not** cancel the separate ratified **permanent disconnect-replacement** consequence, which is **2 Koz in `UNRANKED`** and **no Koz in `ROOM`** (owner, 2026-10-22; A7.4 closes O-03c) — and a **reconnect within the takeover window is never a departure**. The 7th takeover and the 15-timeout removal are **not departures** — each keeps its own charge (the 15-timeout removal **2 Koz** per A5.1's table; the 7th takeover **2 Koz in RANKED and UNRANKED** and **1 Koz in ROOM** per item 8 and A7.3) and is **not additionally** charged as a leave. A human who leaves and does not complete the match is **scored Koz for that match even when a replacement bot finishes it as King**, and the completing humans keep their own normal results (A7.5). A match abandoned by everyone (all humans gone, or all disconnected for **one minute**) is cancelled and **every player is Koz** and, in RANKED, **loses RP equal to the engine's last-place loss**; a match stopped by a server or quota failure is **never charged** to players.

   > **Pointer, 2026-10-23 (owner; text above left verbatim).** The parenthetical "15-timeout removal
   > **2 Koz** per A5.1's table" is now mode-scoped by **A8.3 / Q37**: **1 Koz in `ROOM`**, 2 Koz in
   > `RANKED`/`UNRANKED`. The 7th takeover keeps its own A7.3 values; a single removal is charged once.
8. **Bot takeover and reconnect.** After the decision timer expires the **MEDIUM** bot plays that decision (+1 timeout, 15th removes). If the player is still disconnected after **10 more seconds** (fixed for all timers) the **creator-selected bot** takes the seat and the takeover counter increments; the **7th** takeover removes the player from the match with **2 Koz** (a match-level removal, not an account ban). Ordinary 50-second inactivity does not increment this counter. **Mode scope and timing (owner, 2026-10-22; A7.3 closes O-03):** in **ROOM** the 7th takeover records **1 Koz**, and the removal fires **immediately when the 7th qualifying takeover happens — even if a 30-second disconnect vote is open or a 90-second WAIT is running**; it does not wait for the ballot or the WAIT to end. What the counter is incremented by, and how it is made exactly-once, is engineering (`§5.7.3`), not a new ruling; the unresolved side-effect on an open ballot is **O-03b**.

   > **Pointer, 2026-10-23 (owner; text above left verbatim).** **O-03b is closed by A8.2 / Q36:** the 7th
   > takeover **cancels** an open 30-second ballot immediately, during **VOTE** or **WAIT**; it never
   > expires into a verdict, never writes a stale result, and never double-charges.
9. **Ranked tables and bot-fill.** Every Ranked table is **created by a player** (who chooses timer, game type, bots and their personality); **Auto-Match joins a waiting player-created table** within the RD29 tier band (≤1 tier apart), after showing the table settings; with none waiting the app says so and offers Create Table. **Wait two minutes from the first player entering**; ready = connected and seated; if ready humans are still missing, a **30-second vote** (≥2 waiting players; more than half of waiting humans must approve; non-votes count as rejection) fills with the creator's bots. RP in a table with bots: humans get normal RP, **opponent strength from humans only**.

   > **Pointer, 2026-10-23 (owner; text above left verbatim).** The band is **absolute** and never widens
   > after 180 s (**A8.6 / Q41**). Whether a pair one tier **up** is eligible as well as one tier **down** is
   > **not** decided: open as **O-15**, and no matchmaking code changes until it is answered.
10. **ROOM.** No ROOM without a **full table of four** players — a **start** condition; a seat vacated mid-match **may be filled by a bot** and the departed human is scored Koz regardless of how the bot finishes (A7.5, owner 2026-10-22).

   > **Pointer, 2026-10-23 (owner; text above left verbatim).** **A8.4 / Q38** completes the rule: that
   > match also adds **+1 Games Played** for the departed human (it enters the percentage denominator), the
   > bot's King is **never** attributed to them, and completing humans keep their own results. ROOM records
   > no RP.
11. **Statistics.** Profile shows counts and percentages over Games Played (King, Second, Third, Koz), **aggregated across ROOM, RANKED and UNRANKED**; Ranked RP is separate and Ranked-only; leaderboard deferred from V1.

   > **Pointer, 2026-10-22 (owner; text above left verbatim — confirmation only, no wording change).**
   > **Q46 / N-1** confirms this aggregation (ROOM + RANKED + UNRANKED in the nine career statistics, RP
   > Ranked-only). The **code** does not match yet: `accumulateRankedStat` returns early unless `RANKED`
   > (`RankedStats.kt:109`) and settlement is Ranked-only (`Settlement.kt:340-351`) — an implementation gap
   > (S3), not a rule question.
12. **Seasons.** Seasons and the two-step reset stay; fixed 90-day seasons; a match is credited to the season in which it completes.

## Amendment A1 — Legacy tracker Normal-mode Super Call (owner decision 2026-09-17)

> **Provenance:** NOT from the DOCX extraction above — owner-mandated house rule for the legacy `src/` score tracker only (`src/utils.ts` `SUPER_CALL` role in Normal mode). Classic mode (line 197) and the `design-ui/` multiplayer engine are explicitly unaffected.

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

  > **Pointer, 2026-10-23 (owner; Q2c / Q48).** The extension **caps** in this cell
  > are **superseded**: Q2c removes all extension caps, so FULL and MINI have no
  > count cap. A8.8/Q48 governs: each Sa'ayda in the type's fast window adds one
  > round, a final-round Sa'ayda repeats until a winner, and there is no cap on
  > extensions. This cell is left verbatim; the pointer note is the correction.

- **The trump ladder starts Sans on the first Forced Fast Round of either type** and repeats from Sans on every extension round. Full: 14 Sans, 15 Spades, 16 Hearts, 17 Diamonds, 18 Clubs, then 19 Sans, 20 Spades, … Mini: 6 Sans, 7 Spades, 8 Hearts, 9 Diamonds, 10 Clubs, then 11 Sans — **and Mini stops there** (A2.2).
- **Unchanged by Game Type, in both types:** 4 players, individual play, 52 cards, 13 each, 13 tricks, counter-clockwise play, dealer rotation, the minimum auction bid of 4, Super Call ≥ 8, Dash Call (max 2 players), the 13 Rule, Call Cap, Risk, With/Wazz, Sa'ayda, every scoring row, and every win condition. **MINI inherits all of it verbatim — there is no Mini-specific rule anywhere.**

### A2.2 Extensions under MINI (the only structural difference)

- §3's extension triggers — a fast-round Golden Super Call, and a Sa'ayda on the final round — operate exactly as written in both types, within the type's own fast window (Full 14–18, Mini 6–10).
  > **Pointer, 2026-10-23 (owner; Q2c / Q48).** The **one-extension cap for MINI** and the
  > **five-extension cap for FULL** in this paragraph are **superseded by Q2c**: no extension
  > cap exists for either type. A8.8/Q48 governs: each Sa'ayda in the type's fast window adds
  > one round, a final-round Sa'ayda repeats until a winner, and there is no cap on extensions.
  > Rounds past a type's ceiling **can** trigger an extension if they are the actual final round
  > and end in a Sa'ayda (the repeat-until-winner rule). This text is left verbatim; the pointer
  > note is the correction.

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

- **`ROOM`** — the invite-only table: code-shared, fills no seat from any pool, may start with 2–3 players, push-to-talk voice available. **[Superseded 2026-10-21 — ROOM needs a full table of four: Current Rules §10]**
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

  > **Pointer, 2026-10-22 (owner; text above left verbatim as history).** The **number** was superseded by **Q14d(1)** (2026-10-21): a passed Vote Kick records **2 Koz** in `RANKED` and `UNRANKED`, `ROOM` exempt because no kick is held there. A6.12 reaffirms that and narrows only the *departure* definition — it changes no charge value. The `ROOM` "no Koz" cells above still govern a **disconnect** replacement; a **voluntary** Room leave is charged 1 Koz under Q14b/A6.6. Those two cells are now **confirmed, not merely unrepealed**: the owner ratified **`UNRANKED` permanent replacement = 2 Koz, `ROOM` = no Koz** on 2026-10-22 (**A7.4**, closing **O-03c**) — "a disconnect charges nothing" adds no *leave* penalty, while a vote to permanently replace keeps its own charge. The takeover removal is mode-scoped by **A7.3**: `ROOM` **1 Koz**, `RANKED`/`UNRANKED` 2 Koz, fired immediately even during an open ballot or a WAIT.
- **`ROOM` is out of RD31 entirely.** Neither an explicit leave nor a 15-timeout removal in a
  normal room charges anything under this rule, and a Room disconnect replacement charges
  nothing — see A5.3.1.

  > **Pointer, 2026-10-22 (text above left verbatim as history; plan defect D-17).** This sentence is
  > **partly superseded and partly confirmed**, and the residue is an open owner question — not a settled
  > value. Superseded: an explicit **`ROOM` leave is charged 1 Koz** (Q14b / A6.6), and the **7th takeover**
  > is now charged in `ROOM` too — **1 Koz**, fired immediately even during an open ballot or a WAIT (A7.3)
  > — so `ROOM` is no longer "out of RD31 entirely". Confirmed: a `ROOM` **disconnect** replacement still
  > charges nothing, ratified by A7.4 / Q33. Not reached by any of those rulings: the **15-timeout** removal
  > cell above (`:487`), whose `ROOM` value is neither stated nor repealed — it stays open as **O-03d** and
  > must not be defaulted to 0 or 1.

  > **Pointer, 2026-10-23 (owner; table and note above left verbatim).** **O-03d is closed by A8.3 / Q37:**
  > the 15-timeout removal records **1 Koz in `ROOM`** (2 Koz in `RANKED`/`UNRANKED` unchanged). The cell is
  > superseded by a positive value, not repealed to zero, and it stays distinct from the 7th takeover and
  > from a permanent replacement.

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

   > **Pointer (owner clarification, 2026-10-22; the sentence above is left verbatim as
   > history).** The clause "A3.5 and RD28 state that Unranked accumulates **no Ranked
   > statistics**" is **superseded by N-1 · Q46**: profile career result statistics include
   > **ROOM, RANKED and UNRANKED**, and `UNRANKED` is included. **Ranked RP stays separate
   > and Ranked-only** — the tier, the division and the RP move no farther for an Unranked
   > result. Nothing else in item 3 changes: the 2-Koz `UNRANKED` charge and the
   > "career Koz counter is a plain departure counter" reading are unaffected, because a
   > counter that moves no tier and no RP was never Ranked progression. The canonical text
   > already said this (item 11, A6.7, `Q14c`, `Q17`); the drift was in the CODE — see
   > `docs/decisions/2026-10-22-final-decision-sync.md` §2 (N-1).
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
  2 tricks → −22 (engine −12). Classic is open (Q4). *(Classic closed 2026-10-22 by **A7.1**: the same
  component addition, −12 miss / +23 success on the owner's Classic case; the −22 above is the Normal-mode
  value and must not be reused as a Classic value.)*
- **A6.4 (owner, 2026-10-21 later; supersedes A6.1's "redeal" wording where it differs):** a General Pass consumes the round number — round 6 all pass → round 7 is played ×2; a Sa'ayda in round 7 → round 8 ×4. Classic carries no Double (consumption in Classic: Q1b). The engine redeals the same round and differs.

  > **Pointer, 2026-10-23 (owner; bullet above left verbatim).** This bullet is **operative** and resolves
  > conflict C-2: **Q51 / A8.7** defines "redeal" as a fresh deal into the **next round number** — Normal
  > multiplied ×2, Classic none. The engine's same-round model is the defect, not this wording.
- **A6.5 Super Call extension (owner):** only if the Caller succeeds; supersedes the "any Super Call" wording of §3.
- **A6.6 Departure charges (owner; supersedes A5.1 where it differs):** ROOM leave for any reason = 1 Koz; RANKED/UNRANKED leave for any reason = 2 Koz; charged only when the match finishes and the player did not return. Open: Q14c, Q14d. No Vote Kick against a shared Koz (unique Koz required).
- **A6.7 (owner, later; supersedes A6.6 and A3.5 where they differ):** UNRANKED computes no RP but enters all profile statistics. A passed Vote Kick = 2 Koz (RANKED, UNRANKED). A match abandoned by everyone: all players Koz and RP deducted (details Q27). No cap on General Passes or Sa'aydas; the multiplier caps at ×8; a Classic General Pass consumes the round with no multiplier. Players tied at the bottom share the last placement for RP.

  > **Pointer, 2026-10-22 (owner; bullet above left verbatim — confirmation only).** The statistics half is
  > **confirmed by Q46 / N-1**: career results aggregate **ROOM + RANKED + UNRANKED** (item 11, Q17), while
  > RP stays Ranked-only. No wording changes. The **code** still gates statistics to RANKED
  > (`RankedStats.kt:109`) — an implementation gap, not a rule question.
- **A6.8 (owner, 2026-10-21 later):** a ROOM needs a full table of four (supersedes A3.1's "may start with 2–3 players"); Classic Normal Dash + Risk adds both components (numbers to be confirmed); profile shows counts and percentages over Games Played. *(The "numbers to be confirmed" are confirmed 2026-10-22 by **A7.1** — components added, miss counted once. The full-table rule is a **start** condition: a vacated ROOM seat may be bot-filled per **A7.5**.)*
- **A6.9 With (owner, 2026-10-21 later):** Normal rounds — With = any other player who bid the final Caller's winning suit at any time; their With number is their last bid in that suit. Fast rounds — With only if the suit matches the final suit and the number equals the highest winning number exactly (≥8 alone is not enough). Supersedes the engine's Super Call With set. Fast-round "suit" meaning: Q28.
- **A6.10 (owner, later; supersedes A6.9's fast-round part):** any fast-round player declaring 8+ may choose any trump; a Super Caller's suit replaces the forced suit and others re-declare (who: Q29); a higher Super Call repeats this. Fast-round With: highest winning number exactly, plus the estimate made under the final trump if the trump changed. Not every 8+ is With.
- **A6.11 (owner, later; supersedes line 109's "only players who bid before re-estimate"):** after a fast-round Super Call every player except the Super Caller re-declares, counter-clockwise; repeat for each higher Super Call. Engine differs (`Bidding.kt:577-625`).
  > **Pointer, 2026-10-26 (documentation-only; the A6.10/A6.11 bullets above are left verbatim).** The
  > engine now implements A6.10 and A6.11, so the "Engine differs" note above is obsolete. The fast
  > confirmation wipes every seat but the Super Caller's own declaration (`Bidding.kt` — `emitConfirmCall`,
  > fast branch), the re-declaration runs counter-clockwise from the seat after the caller
  > (`Bidding.kt` — `emitFinalEstimate`, fast branch), and a declaration above the confirmed number opens
  > the NEXT cycle. The settlement and timeout replays follow the same cycle, and the With set is derived
  > from the final number and the final trump.
- **A6.12 Departure = VOLUNTARY only (owner, 2026-10-22; narrows A6.6/A6.7 and Current Rules item 7).** The one thing this ruling changes is the **definition** of a departure; every charge value is left exactly where Q14b and Q14d(1) put them on 2026-10-21.
  - **"For any reason" is repealed.** Only a **voluntary** leave is a departure. A **disconnect or app close that is not a voluntary departure charges nothing**, and a **reconnect within the takeover window is never a departure** — which is A5.1's "return when allowed = no penalty" bullet applied to the general case, not a new exception.
  - **A takeover-driven removal is not a *departure* — but it is still charged.** The 15-timeout removal keeps its own A5.1 **2 Koz**, and the 7th takeover keeps item 8's **2 Koz**; neither is *additionally* charged as a leave, so a player is never double-charged for one removal event. Their **ROOM** treatment stays **open (O-03)** — this ruling does not close it. (Reading "not a departure" as "charges nothing" would silently delete both charges; it does not.)
  - **Charge values unchanged, and no new value is introduced.** Voluntary leave: `ROOM` **1 Koz**, `RANKED`/`UNRANKED` **2 Koz**, applied **when the match finishes** and only if the player did not return (Q14b). A passed Vote Kick: **2 Koz** in `RANKED` and `UNRANKED`, `ROOM` exempt because no kick is held there (Q14d(1) — already decided 2026-10-21; A5.1's "Vote Kick records 1 Koz, final" stays superseded). This **reaffirms A5.1's option (c) rejection**: "2 Koz" is a counter increment, never a new result enum, and it does not touch the scoring formula.
  - **Q14c and Q14d are NOT reopened by this ruling.** Q14c (UNRANKED enters all nine profile statistics, no RP) and Q14d(1) were closed on 2026-10-21; Q14d(2) survives as **Q27**, and `docs/decisions/2026-10-16-open4-open5-owner-closure.md` is **not patched** — the pointer above A5.1 and this bullet are the record.
  - **⚠ One consequence is NOT decided — flagged, not resolved (O-03c).** "A disconnect that is not voluntary charges nothing" reaches the **S74 permanent disconnect-replacement** charge, which A5.1's table and the owner's 2026-10-17 clarification put at **2 Koz in UNRANKED** (`DisconnectVote.permanentCharge`, `DisconnectVote.kt:69-73`) — an increment ratified for an event that is, by definition, not a voluntary leave. Either reading is coherent: (i) the new wording controls and a permanent replacement records **nothing** in every mode (repeals A5.1's UNRANKED cell, changes the engine and its tests); or (ii) the S74 replacement charge is its own ratified consequence and survives, so "disconnect charges nothing" means only "no *leave* penalty is added". **A6.12 does not choose between them** — the owner must, and O-03c is where it is tracked.
  - **Consequence for the plan:** the rules side is settled; what remains is an *implementation* gap against a mechanism that already exists — `LeaveMatch.decideLeave` returns `Koz1` for `VOTE_KICK` (rule: 2), treats `FIFTEEN_TIMEOUT` as a leave, and returns `None` for a `ROOM` leave (rule: 1), while `RankedStats.accumulateDepartureCharge` short-circuits `ROOM` entirely, and `LeaveReason` carries **no** voluntary/involuntary discriminator at all. That is a V2 §4 workload item, not a rule decision.

  > **Pointer, 2026-10-22 (owner; bullets above left verbatim).** The ⚠ bullet is **resolved by A7.4**: reading **(ii)** is the rule — a permanent disconnect replacement keeps its own ratified charge (`UNRANKED` 2 Koz, `ROOM` no Koz), and "a disconnect charges nothing" means no *leave* penalty is added on top. The "ROOM treatment stays open (O-03)" clause in the second bullet is **resolved by A7.3**: the 7th takeover in `ROOM` is **1 Koz**.

## Amendment A7 — O-01, O-02, O-03, O-03c and O-04 closed by the owner (owner rulings 2026-10-22)

> Source: the owner's answers of 2026-10-22 to the decisions `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md`
> §5.7.2 listed as blocking implementation. Registered in `docs/decisions/OPEN_QUESTIONS.md` §D as
> **Q30–Q34**. A6.12 (the same day) narrowed the *definition* of a departure and changed no number; A7
> supplies the numbers, the tie-break and the ROOM bot-attribution rule that definition left untouched.
> Text these answers replace is tagged in place and left readable.

- **A7.1 Classic Normal Dash + Risk = component addition (closes O-01; refines Q4 and A6.8).** A Classic
  Normal Dash who is also the Risk player is scored exactly like A6.3 does in Normal Calculation: the
  **Risk value plus the Normal Dash points, with neither dropped nor counted twice**. "Add both tables"
  (Q4) meant the two *components*, not the two complete role formulas — a miss must not be charged twice,
  and the sum must have a matching success side. On the owner's worked Classic case (total bids 15, Risk
  value 10, a Normal Dash seat that takes 2 against its fixed 0 estimate): miss = −2 + (−10) = **−12**;
  success on that Over round = +13 + 10 = **+23** (on an Under round the same pair is +23 + 10 = +33).
  **−22 is the Normal-mode owner example (A6.3) and is not a Classic value**; a Classic **Wizz-Risk** seat
  is a different role and its formula must not be substituted for this one.
  - **What the Risk term is, derived from the owner's own two numbers.** The seat keeps its **Classic
    Normal Dash table value** (§176-180: Success Under +23, Success Over +13, Failure Under −10, Failure
    Over −T) and adds only the flat **Risk value** (±10 here). It does **not** also receive the Classic
    Risk role formula (`:159-161`, Success `E+13+10`, Failure `−(miss+10)`) — that would re-count the same
    estimate and the same miss, which is precisely what "counted twice" means. So: miss = −T + (−10) =
    **−12** at T=2, and success on an Over round = +13 + 10 = **+23**. Adding the full role formulas
    instead gives −14 / +36 on this case and is **not** the rule.
  - **Derived, deterministic, and asserted by T-07:** on the same round a **sole winner** adds its +10 bonus
    on top (§181-182) → +23 + 10 = **+33** (Over) or +33 + 10 = **+43** (Under, where the Dash value is
    already +23); a **sole loser** doubles the summed base failure and floors it at −22 (§183) →
    2 × (−12) = −24 → **−22**, the cap binding. **Test-design hazard:** a Classic Dash **alone** succeeding
    Under is also **+23** (§172), numerically identical to this seat succeeding Over with Risk — every T-07
    row must therefore assert the round's TotalBids alongside the score, or a wrong implementation passes.
- **A7.2 Fast-round equal-highest Super Call: the number first, then the official suit hierarchy (closes O-02).**
  A higher **number** beats a lower number whatever the suits; at an equal number the **stronger suit**
  wins the Call. **Seat order never breaks a tie** — the code's dealer-order tie-break (`Bidding.kt:710`
  `.thenBy { order.indexOf(it) }`, and the comment at `:740-741`) is an invention and is superseded.
  **The full precedence is not missing:** §1's "Official Suit Hierarchy (strongest → weakest)" table
  (`:27-31`) fixes Sans 5 > Spades 4 > Hearts 3 > Diamonds 2 > Clubs 1, the engine encodes the same
  strengths (`Cards.kt:10-16`), and §2.2 already states both halves of the rule ("On a tie in number, the
  stronger trump suit wins (e.g., 5♠ beats 5♥). Sans beats every suit on a tie." `:59`; "If two players
  Super Call, the higher bid (then stronger suit) wins the right to choose the trump." `:68`). So the
  owner's confirmations hold: **8♠ beats 8♥**, and **9♥ beats 8♠**. Diamonds and Clubs are settled by
  those sources, not chosen here.
  - **Derived, and asserted by T-11:** with A at 8♠ and B at 8♥, A is Caller and ♠ is the final trump;
    B is **not** With, because fast-round With requires an exact match of **both** the number **and** the
    suit, and a matching number alone never qualifies (reaffirms A6.9/A6.10 and Current Rules item 5;
    kills the seat-order-era line at `:75`).
  - **Not settled by A7.2, flagged rather than invented:** two seats declaring the **same number in the
    same suit**. The number rule ties, the suit rule ties, and no source orders them. Tracked as
    **O-02b** — the owner must say whether that is an illegal duplicate declaration or a tie needing a
    further rule; the engine must not pick silently.

  > **Pointer, 2026-10-23 (owner; bullet above left verbatim).** **O-02b is closed by A8.1 / Q35:** an exact
  > number-and-suit duplicate is **legal**; the first declarer is Caller and each later exact duplicate is
  > With; declaration order decides only that case.
- **A7.3 The 7th takeover in ROOM is 1 Koz, and it fires immediately (closes O-03).**
  1. **Amount:** the 7th qualifying takeover records **2 Koz in `RANKED` and `UNRANKED`** (item 8, Q11,
     unchanged) and **1 Koz in `ROOM`**. It is still not a *departure* under A6.12, so no leave penalty is
     added on top — one charge per removal event.
  2. **Timing:** the removal is applied **as soon as the 7th qualifying takeover happens, even if a
     30-second disconnect vote is open or a 90-second WAIT is running.** The ballot and the WAIT do not
     hold it back and it does not wait for them to close.
  - **No further timing or reset rule is asserted.** The owner specified an amount and a firing condition
     only; anything else about counter resets is engineering, recorded in V2 §5.7.3, not a new ruling.
  - **⚠ Consequence the owner has NOT ruled — flagged, not resolved (O-03b).** Firing a removal *during*
     a ballot means the vote about that player's seat is still open while the seat is being taken out of
     the match: does the ballot void, finish and take no effect, or continue because it can also produce a
     permanent replacement? Today the two flows "deliberately share no state" (`DisconnectVote.kt:19-20`)
     and, in the current code, a seat held by a ballot **cannot advance the takeover clock at all**
     (`TimeoutResolution.kt:376-380`, where a seat in `disconnectHeldSeats` counts as bot-held and no
     bot-held seat may increment the timeout counter; plus `TimeoutResolution.kt:358`
     `disconnectPauseRemaining` with `TimeoutResolutionDb.kt:212-218` and `:244-250`, which return
     `NOT_YET_EXPIRED` for the whole table during WAIT). So A7.3's timing rule requires that suppression to
     change, and the ballot's fate is a decision, not an implementation detail.

  > **Pointer, 2026-10-23 (owner; bullet above left verbatim).** **O-03b is closed by A8.2 / Q36:** an open
  > ballot is **cancelled** immediately when the 7th takeover fires, during VOTE or WAIT; it never completes
  > and never writes a stale result.
- **A7.4 A permanent disconnect replacement keeps its own charge (closes O-03c).** Reading **(ii)** of the
  A6.12 flag is the rule: **"a disconnect or app close that is not a voluntary departure charges nothing"
  means no *leave* penalty is added** — it does not cancel the separate consequence a table votes for.
  Ratified values stand exactly as A5.1's table and the owner's 2026-10-17 clarification put them:
  **`UNRANKED` permanent replacement = 2 Koz, `ROOM` permanent replacement = no Koz, and `RANKED` holds
  no disconnect vote at all.** The engine already encodes this (`DisconnectVote.permanentCharge`,
  `DisconnectVote.kt:69-73`, with `toKozCharge` at `:92-95`), so this is a **wording reconciliation plus
  wiring**, not a value change: that charge function has **no production caller**, so nothing applies it
  yet (V2 E3.5).
- **A7.5 Bots may fill a vacated ROOM seat, and the departed human is scored Koz (closes O-04 / C18).**
  - A **bot may take a vacated seat in `ROOM`**, so a room that drops below four keeps playing instead of
    stalling; A6.8's "a ROOM needs a full table of four" continues to govern *starting*, not a mid-match
    departure.
  - **A human who leaves and does not complete the match is counted Koz for that match — even if the
    replacement bot finishes the game as King.** The departure result overrides the seat's played outcome.
  - **The other humans are not penalised** by a teammate's disconnect or voluntary leave. A player who
    completes the match receives **their own normal result and normal statistics** for it.
  - **A bot's result is never attributed to the departed human.** Seat → uid must stay explicit and
    deterministic end to end: the bot's King, its trick counts and its score must not land in the departed
    player's profile, and no bot row may enter a human profile at all.
  - **Derived requirement for V2:** because a departed seat's stored outcome is now wrong on purpose, the
    ROOM/UNRANKED four-matching-reports recording path (Q15b/Q15c) needs an explicit per-seat
    "departed → Koz" override, and the nine-statistics aggregation must count bot-filled ROOM matches for
    the humans who completed them. Current code does neither: ROOM never settles
    (`Settlement.kt:340-350`), statistics accumulate only for non-placement `RANKED`
    (`RankedStats.kt:109`, `:157`), and the attribution rule exists **only as a comment**
    (`RankedStats.kt:144-147`). `accumulateDepartureCharge` additionally returns unchanged for `ROOM`
    (`:151`, guard `:157`), while a departure charge outside `ROOM` adds its amount to Koz **and one to
    Games Played** (documented at `:32`) — so whether a bot-filled ROOM match enters the departed human's
    Games Played is not derivable from the existing convention and is asked as **O-04b**.

  > **Pointer, 2026-10-23 (owner; bullets above left verbatim).** **O-04b is closed by A8.4 / Q38:** the
  > match **does** add one to the departed human's Games Played, the bot's result is still never attributed,
  > and ROOM still records no RP.
- **What A7 does not change.** It decides nothing about the permanent-replacement question A7.4 already
  closed, does not touch the 15-timeout or Vote-Koz numbers, and does not import a new result enum —
  "Koz" and "2 Koz"/"1 Koz" stay a counter increment (A5.1's option (c) rejection still holds). **O-05,
  O-06, O-07, O-08, O-09, O-10 and O-11 remain open:** the owner's answers to those had not arrived when
  this amendment was written, and their absence is **not** approval.

> **Pointer, 2026-10-23 (text above left verbatim).** The O-05…O-11 answers arrived on 2026-10-22 and are
> recorded as **Q39–Q45** (`docs/decisions/2026-10-22-final-decision-sync.md`): Platinum/stat labels
> (**A8.5**), Stage 0 gating, the absolute matchmaking band (**A8.6**), voice and monetization scope (plan
> V2 §5.6/§5.9, **O-12**/**O-13** still unresolved), deletion deferral, and the final RP values. The A7
> residuals **O-02b**, **O-03b** and **O-03d** were closed on 2026-10-22 by **A8.1**, **A8.2** and
> **A8.3**; **O-04b** by **A8.4**.

---

## Amendment A8 — Owner rulings of 2026-10-22 and 2026-10-23 (recorded 2026-10-23)

> **Source:** the owner's answers of 2026-10-22 (Q35–Q50) and 2026-10-23 (Q51–Q52), recorded in
> `docs/decisions/2026-10-22-final-decision-sync.md` and
> `docs/decisions/2026-10-23-final-owner-answers.md`. Every item either closes a prior open question or
> confirms existing text; no earlier body is rewritten. **Status: rules recorded, engine not yet changed**
> for A8.1–A8.4, A8.6 and A8.7's round progression (the gap is stated inside each item); A8.5's display
> labels are applied in this round.

- **A8.1 Fast-round exact duplicate declaration (owner, 2026-10-22; closes O-02b; Q35).** Two seats
  declaring the **same number in the same suit** in a fast round is **legal** — it is not an illegal
  duplicate declaration and not a void call. The **first** declaration in bidding order is **Caller**; each
  later exact duplicate is **With**. The rule is symmetric, and declaration order decides **only** for an
  exact number-and-suit duplicate. A tie at the same number in **different** suits stays with A7.2 — the
  stronger suit wins the Call and seat order never decides — and a higher number beats a lower one
  whatever the suit. This completes A7.2, which settled number-then-suit but left this case open.
- **A8.2 The 7th takeover and an open ballot (owner, 2026-10-22; closes O-03b; Q36).** The 7th qualifying
  takeover **cancels** an open 30-second disconnect ballot immediately: the ballot does not run to expiry,
  takes no effect, and cannot complete afterwards (no stale write, no ballot re-open). The removal fires
  during **VOTE** and during the 90-second **WAIT** alike. It is deterministic and exactly-once: one
  removal event, one charge, no double charge, and a replay never resurrects a cancelled ballot. A7.3's
  amount and immediate-fire timing are unchanged; this decides only the ballot's fate.
- **A8.3 ROOM 15-timeout removal (owner, 2026-10-22; closes O-03d; Q37).** The 15-timeout automatic removal
  records **1 Koz in ROOM** — the same amount as a voluntary ROOM leave (Q14b / A6.6) — and keeps **2 Koz**
  in `RANKED` and `UNRANKED` (A5.1). It remains distinct from the 7th takeover (also 1 Koz in ROOM, A7.3)
  and from a permanent disconnect replacement (no Koz in ROOM, A7.4): one removal event is charged exactly
  once and the three events never charge for each other. A5.1's `ROOM` "no 2-Koz rule" cell for the
  15-timeout is therefore **superseded by a positive value**, not repealed to zero.
- **A8.4 Departed ROOM human: Games Played and attribution (owner, 2026-10-22; closes O-04b; Q38).** A human
  who leaves a ROOM match and does not complete it is scored **Koz even if the replacement bot finishes as
  King** (A7.5), and that match **increments their Games Played by one** — it enters the percentage
  denominator, so every statistic computed over Games Played uses the new denominator. The bot's result is
  **never** attributed to the human: the bot's King does not increment the departed human's King counter,
  and no bot ever receives a profile row. Humans who complete the match keep their own normal results and
  statistics. ROOM still records no RP (item 11; Q17).
- **A8.5 Terminology and display labels (owner, 2026-10-22 and 2026-10-23; Q39, Q50, Q52).** The suit
  hierarchy is untouched — **Sans > Spades > Hearts > Diamonds > Clubs** (§1). Arabic **display labels** are
  standardized: Sans/Sunz **سانز**, Spades **أسبيد**, Hearts **هارت**, Diamonds **كاروه**, Clubs **تريفل**.
  The rank **Platinum** displays as **بلاتين**, never **وزير**; **وزير** survives only as the Second-place
  career-statistic label. The nine career statistics read: Games Played **عدد المباريات**; King count **👑**;
  King % **👑%**; Second count **عدد مرات وزير**; Second % **وزير%**; Third count **عدد مرات كوز صغير**;
  Third % **كوز صغير%**; Koz count **عدد مرات الكوز**; Koz % **%الكوز**. Keys, internal identifiers,
  comparison logic and score mathematics are unchanged — this is localization only, and the Arabic label for
  Clubs (تريفل vs تريفلة) is the owner's given form.
- **A8.6 Matchmaking band (owner, 2026-10-22; Q41).** One shared general pool. Players more than **one rank
  tier apart are never paired directly**, and the band is **absolute**: it does not widen after 180 seconds
  (widening would be a new decision, not a timer tweak). RD9/RD29's ≤1-tier band therefore binds as a
  pairwise distance constraint. **Directionality is not decided here:** whether "one tier apart" pairs in
  both directions or only downward (no opt-up) is open as **O-15**, and no matchmaking code changes until
  the owner answers it.
- **A8.7 General Pass = a NEW round (owner, 2026-10-23; resolves N-2/R-1, conflict C-2; Q47 / Q51).** When
  all four pass the auction, the current round is **cancelled**, cards are **dealt fresh**, and a **new
  round number** starts — it is not a replay of the same round number. **Normal Calculation:** the new round
  is scored with the **Double** multiplier (×2 → ×4 → ×6 → ×8, capped ×8 per Q1b). **Classic:** the new round
  is scored with **no multiplier** at all. The two older words describe this one behaviour: A6.4's "consumes
  the round number — round 6 all pass → round 7 is played ×2" is **operative**, and "redeal" (A6.1) means
  exactly a fresh deal into the next round number; A6.1 was already superseded by A6.4 for that word, and
  item 1 / Q1 / Q1b are **confirmed, not rewritten**. **Engine defect (not a rule change):** the engine
  currently redeals the *same* round number and always applies the Double with no game-type input
  (`Bidding.kt:493-508`), the same-round model is repeated in settlement and timeout resolution
  (`Settlement.kt:517-524`, `TimeoutResolution.kt:979-984`), and the online path ignores the pass entirely
  (`OnlineMatchViewModel.kt:724`). This is recorded as an implementation gap; **nothing here is implemented
  yet**.
- **A8.8 Sa'ayda extension count (owner, 2026-10-22; closes N-3; Q48).** Every Sa'ayda occurrence in fast
  rounds **14–18 inclusive** adds **one round** to the 18-round default, per occurrence: 14 alone → **19**;
  14+15 → **20**; 14+15+16 → **21**; 13 alone → **18** (no extension). The rule is general, not limited to
  the examples (N occurrences → 18 + N), and MINI uses the same logic on **6–10** (Q2b). Item 2(b) — a
  Sa'ayda in the **actual final round** adds one and repeats until a winner — continues to govern rounds
  beyond 18 and is **not repealed** by these examples. **Engine defect (not a rule change):**
  `computeRoundExtension` (`RoundScore.kt:222-238`) **is wired** at `OnlineMatchViewModel.kt:851`,
  so it raises `maxRounds` and `isMatchComplete` reads it (`Session.kt:568`).

  > **Pointer, 2026-10-24 (owner; text above left verbatim — confirmation).** A8.8 applies to the three-match
  > placement series exactly as it applies to any other match: a placement match ends on the **effective**
  > ceiling — `baseRounds` plus every Sa'ayda / Super Call extension — never on `GameType.baseRounds`, so the
  > series advances only after the extended final round. No placement suppression flag exists and none is to be
  > added. Recorded with its implementation evidence in
  > `docs/decisions/2026-10-24-a8-placement-and-s1-split.md` §1.

**What A8 does not do.** It decides no scope and no engineering process. **O-08** (voice
provider/protocol/cost) and **O-09** (ads/consent provider) are V1 scope with unresolved suppliers;
**O-10** (full account deletion) stays deferred from V1; **O-11**'s RP values (+30 / +15 / −8 / −20) are
recorded as final in plan V2 §5.5.6 and the `RpEngine` KDoc, not restated as a scoring formula here. A8 adds
no result enum, no charge path and no scoring change; every charge remains a counter increment.

