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
Number of Rounds: 18 standard rounds, plus any extension rounds created by a fast-round Super Call (see §3) or by a Sa'ayda on the final round (see §4).
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
