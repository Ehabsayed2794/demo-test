# 2026-10-22 — Final decision sync: O-02b…O-11 and the N-1…N-6 clarifications

**Status: RECORDED (documentation only).** Source: the owner's decision message of 2026-10-22 (the eleven
open decisions O-02b…O-11) and the follow-up clarifications of the same day answering the audit questions
N-1…N-6. This record changes no code, rules, UI, Functions or infrastructure. No test was run to produce it.

Labels: **[D]** owner-confirmed · **[R]** recommendation, not approved · **[O]** open question ·
**[G]** confirmed implementation gap. This record follows the repository's pointer convention: superseded
text in older documents is left verbatim, with a dated blockquote pointer added above it.

Companion records: `docs/decisions/2026-10-23-final-owner-answers.md` (R-1, O-14, the sprint directive);
`docs/decisions/OPEN_QUESTIONS.md` (register, Q35–Q52).

## 1. The eleven decisions

### O-02b — fast-round duplicate declaration [D] · Q35

Two seats declaring the **same number in the same suit** may both declare; the declaration is legal.

- The **first declarer** in declaration order is the **Caller**; each later declarer of the exact same
  number and suit is **With**.
- Declaration order decides **only** this exact-duplicate case.
- For equal **numbers in different suits**, the canonical suit hierarchy decides
  (Sans > Spades > Hearts > Diamonds > Clubs) — **seat order never decides**.
- This settles the question left open by A7.2 (`CANONICAL_RULES.md:648-669`): the equal-number tie is
  resolved by suit; the equal-number-and-suit tie is resolved by who declared first.
- The Arabic wording of the rule remains the owner's to supply (R-07 localization gate).

**Implementation status [G]:** fast estimates today store no suit (`Bidding.kt:692`, `:702`) and resolve
ties by `biddingOrder` (`:710`, `:746`). Recorded as implementation sprint S4 in
`docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.10. Not implemented by this record.

### O-03b — seventh takeover versus an open ballot [D] · Q36

The **7th qualifying takeover** removes the seat **immediately**, even while a 30-second vote against that
same seat is open and even during a 90-second WAIT.

- The open ballot is **cancelled immediately**; it does not run to expiry and takes no effect.
- The removal is deterministic: the same inputs produce the same outcome, with no stale completion and no
  resurrection of the cancelled ballot.
- The player is charged **once** for the removal event; the cancelled ballot adds no second charge.
- The charge is the A7.3 one: **1 Koz in ROOM**, **2 Koz in RANKED/UNRANKED**.

**Implementation status [G]:** a vote-held seat is treated as bot-held and cannot increment the takeover
counter at all (`TimeoutResolution.kt:376-380`), and a WAIT freezes every deadline (`:358`;
`TimeoutResolutionDb.kt:212-218`, `:244-250`). There is no CANCELLED ballot phase. Recorded as sprint S7.

### O-03d — 15-timeout removal in ROOM [D] · Q37

A **15-timeout removal in ROOM charges 1 Koz** — the same amount as a ROOM leave.

- RANKED and UNRANKED keep the A5.1 2-Koz charge.
- This closes the case A7.3 did not reach: A5.1 excluded ROOM from RD31 entirely, while A7.3 charged the
  7th takeover in ROOM. The 15-timeout is now charged too; the A5.1 "ROOM charges nothing" note no longer
  covers it.
- The charge is distinct from the 7th-takeover removal and the permanent disconnect replacement; one event
  is never double-charged.

**Implementation status [G]:** the removal path records no charge (`TimeoutResolutionDb.kt:326-343`).
Recorded as sprint S5.

### O-04b — ROOM bot fill, attribution, and Games Played [D] · Q38

A bot may fill a vacated ROOM seat, and a human who left and did not complete is **scored Koz even when the
bot finishes as King**.

- That human's **Games Played increases by 1**; the percentage denominators include the match.
- The bot's King is **never attributed** to the departed human (no King counter, no King percentage).
- Humans who complete the match keep their own normal outcomes and statistics.
- The bot itself never gets a profile row.
- ROOM records no RP.

**Implementation status [G]:** `accumulateDepartureCharge` returns early for ROOM
(`RankedStats.kt:151-167`) and settlement folds every seat with no departure override
(`SettlementDb.kt:508-527`, `:608-682`). Recorded as sprint S6.

### O-05 — terminology: Platinum and the nine statistics labels [D] · Q39

- **Platinum = بلاتين** in every user-facing surface. The old `وزير` value on the Platinum rank is wrong.
- The nine career-statistic labels are:

| # | English | Arabic |
|---|---|---|
| 1 | Games Played | عدد المباريات |
| 2 | King count | 👑 |
| 3 | King % | 👑% |
| 4 | Second count | عدد مرات وزير |
| 5 | Second % | وزير% |
| 6 | Third count | عدد مرات كوز صغير |
| 7 | Third % | كوز صغير% |
| 8 | Koz count | عدد مرات الكوز |
| 9 | Koz % | %الكوز |

- `وزير` survives **only** as the Second-place statistic label; it is never the Platinum rank.
- Keys and arithmetic are unchanged (five counters, percentages over Games Played).
- Premise correction: the audit found the original note attributed the labels to S47; S47 is the Placement
  Introduction screen. The statistics surfaces are **S03** and **S44**, and no Arabic stat keys existed.

**Implementation status:** the Platinum label is a display-only change recorded in sprint S0
(`RankLadder.kt:47`, `RankedResolutionTest.kt:44`). The nine stat keys do not exist in
`values-ar/strings.xml` and S03/S44 are not wired — sprint S3.

### O-06 — runtime: Stage 0 before any provider approval [D] · Q40

- **Stage 0 runs first**, and the runtime decision rests on measured evidence.
- **Cloudflare Workers + Durable Objects + D1 is not approved** by this decision.
- No document may state or imply that Stage 0 has passed.

**Implementation status:** no Stage 0 run exists; ADR `docs/adr/0002-functions-runtime.md` is not
superseded and its status is not flipped.

### O-07 — shared matchmaking pool and the one-tier band [D] · Q41

- Ranked matchmaking draws from a **shared general pool**.
- Players **more than one rank tier apart are never paired directly**.
- The band is **absolute**; it does not widen after the 180-second window (a widening rule would be a new
  decision).

**Implementation status (partial):** `eligiblePoolTiers`/`isPoolEligible`
(`MatchmakingPool.kt:65-91`) allow the same tier or exactly one tier **below**, never one above. Whether the
owner's "shared pool" requires symmetric pairing is tracked as **O-15** in the register; no matchmaking code
is changed until that is answered.

### O-08 — voice in V1 [D] · Q42

Voice chat is **in V1** for **Rooms and the Waiting Room only**; it is **never available in Ranked**.

- The provider, protocol, transport, moderation, permissions and cost are **not decided** — **O-12**.
- No recommendation recorded in this record is approved.

**Implementation status:** no voice code exists.

### O-09 — ads and in-app purchases in V1 [D] · Q43

- **Ads and IAP are in V1 scope.**
- **Google Play Billing is acceptable** for digital in-app purchases.
- The **Firebase Spark / no-Blaze** constraint and the **$0 marginal runtime** target remain in force.
- Play Billing is **not** permission to link a Firebase or Google Cloud billing account or to enable Blaze.
- Store/service fees are **separate from infrastructure costs**; the $0 target applies to infrastructure.
- A secure purchase-verification and entitlement-delivery design is required (plan §5.9).
- Backend/provider costs must be identified. Anything undeliverable under the existing constraints must be
  flagged; no document may claim that all purchase-related costs are zero.

**Implementation status:** no ads or billing code exists. Purchase verification is **blocked** until O-06
yields an approved free-tier runtime; the ad/consent provider is **O-13**.

### O-10 — account deletion policy [D] · Q44

- Deletion is **full deletion**: no anonymized match rows or retained profile rows as a substitute.
- Account deletion remains **deferred from V1**; the store-policy risk is accepted.
- Data-safety declarations must describe the deferred state honestly.

**Implementation status:** no deletion path exists.

### O-11 — final RP values [D] · Q45

- King **+30**, Second **+15**, Third **−8**, Koz **−20**.
- Every "placeholder", "pending a balance pass" or "pending a correct balance" phrase attached to **these
  four values** must be removed.

**Implementation status:** the default config already holds exactly these four values
(`RpEngine.kt:76-86`). The KDoc still calls them placeholders; sprint S0 removes that language for the four
values only. The scaling rates remain tunable.

## 2. Follow-up clarifications (N-1…N-6)

### N-1 — UNRANKED enters all profile statistics [D] · Q46

Profile career result statistics include **ROOM, RANKED and UNRANKED**. UNRANKED is included.

- Ranked **RP** stays separate and **Ranked-only**.
- The canonical text already said this: `CANONICAL_RULES.md` item 11 (`:250`), A6.7 (`:598`),
  `Q14c` (`OPEN_QUESTIONS.md:102`) and `Q17` (`:111`). No rewrite is needed; dated confirmation pointers are
  added.
- The conflict is in **code**: `accumulateRankedStat` returns early for every mode except RANKED
  (`RankedStats.kt:109`), and settlement is RANKED-only (`Settlement.kt:340-351`). ROOM/UNRANKED statistics
  need an app-side writer (Q15/Q24/Q15c). Recorded as sprint S3.

### N-2 — General Pass wording [D, superseded the same day by R-1] · Q47

The owner's first clarification: Normal Calculation **redeals with** the Double multiplier; Classic
**redeals without** it; the game-type distinction must be preserved, and conflicting round-progression
wording must be identified before it is resolved.

- The conflict was identified: `A6.1:575` says "redeal"; `A6.4:591` says "consumes the round number".
- **R-1 (2026-10-23)** resolved it: the two words describe one behaviour — fresh deal into the **next round
  number**. See `docs/decisions/2026-10-23-final-owner-answers.md` Q51 and `CANONICAL_RULES.md` A8.7.
- The engine's same-round redeal is a defect, recorded as sprint S1.

### N-3 — Sa'ayda count window and examples [D] · Q48

Count each Sa'ayda occurrence in fast rounds **14–18 inclusive**, one extension round each, added to the
18-round default:

| Occurrences | Planned last round |
|---|---|
| none | 18 |
| 14 | 19 |
| 14 + 15 | 20 |
| 14 + 15 + 16 | 21 |
| 13 only | 18 |

- The rule is not limited to these examples: N qualifying occurrences → 18 + N.
- `item 2(b)` of the canonical rules (a Sa'ayda in the **actual current final round** adds one and repeats)
  is **not repealed**; MINI keeps its 6–10 window (Q2b).
- No extension cap (Q2c); the ×8 scoring-multiplier cap is a separate rule.

> **Pointer, 2026-10-23 (terminology; section body unchanged).** "14–18" is the **fast window**, not the
> match length. The base match is **18 rounds** (FULL) / 10 (MINI); 14 is `firstFastRound`. An extended
> ceiling is **live** (18 + N). See `docs/decisions/2026-10-23-direction-multiplier-rounds-git.md` §2.

**Implementation status [G]:** `computeRoundExtension` (`RoundScore.kt:222-238`) implements the 14–18 window
but has **no production caller**; nothing raises `maxRounds`. Recorded as sprint S2.

### N-5 — Play Billing under the existing constraints [D] · Q49

Play Billing is acceptable for V1 digital IAP while Spark/no-Blaze and the $0 marginal-runtime target remain
in force.

- It is **not** permission for a Firebase/Google Cloud billing link or for enabling Blaze.
- Store/service fees are separate from infrastructure costs.
- A secure purchase-verification and entitlement-delivery design must be proposed.
- Backend/provider costs must be identified; anything undeliverable under the existing constraints must be
  flagged.
- No claim that all purchase-related costs are zero.

Recorded in plan V2 §5.9. **Blocked** on O-06.

### N-6 — Diamonds ♦ label [D, extended by O-14] · Q50

The Diamonds ♦ Arabic display label is **كاروه**; inconsistent user-facing labels are standardised to it.
The internal suit identifier and the approved hierarchy are not changed.

- **O-14 (2026-10-23)** extended this to all five suits. See the 2026-10-23 record Q52 and A8.5.

## 3. Conflicts identified

| ID | Conflict | Resolution |
|---|---|---|
| C-1 | UNRANKED aggregation | Resolved by N-1: the canonical text was already correct; the code is not. Sprint S3. |
| C-2 | "redeal" vs "consumes the round number" | Resolved by R-1 (2026-10-23): redeal = fresh deal into the next round number. Sprint S1. |
| C-3 | Sa'ayda examples vs the existing rule | Resolved by N-3: the examples match item 2(a); item 2(b) and MINI 6–10 stand. Sprint S2. |
| C-4 | O-05 premise (S47) | Premise corrected: statistics live on S03/S44; no stat keys exist. |

## 4. What this record does not decide

- **O-12** — voice provider/protocol/cost (open; voice is in V1 scope).
- **O-13** — ads/consent provider (open; ads are in V1 scope).
- The **Arabic rule prose** for these rulings (R-07 localization gate; the owner supplies it).
- Any implementation result: no code was changed and no test was run for this record.

## 5. Relationship to older records

| Older record | Relationship |
|---|---|
| `2026-10-21-server-authority-and-owner-rulings.md` §A (General Pass) | Confirmed; Q51 defines "redeal" precisely |
| `2026-10-21-server-authority-and-owner-rulings.md` §B (Sa'ayda) | Confirmed by N-3; rule 2 still stands |
| `2026-10-21-server-authority-and-owner-rulings.md` §P (statistics) | Confirmed by N-1 |
| `2026-10-16-open4-open5-owner-closure.md` | Not patched; A5.1/A7 pointers stand |
| `2026-10-17-unranked-scope-owner-clarification.md` | Confirmed by N-1 |
| `docs/adr/0002-functions-runtime.md` | Not superseded; status not flipped |
