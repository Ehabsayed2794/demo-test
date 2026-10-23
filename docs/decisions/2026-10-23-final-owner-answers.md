# 2026-10-23 — Final owner answers: General Pass, sprint scope, suit labels

**Status: RECORDED (documentation only).** Source: the owner's message of 2026-10-23 answering the three
questions left open after the 2026-10-22 sync (R-1, sprint scope, O-14), plus the later instruction to mark
the remaining items open and implement the plan.

Labels: **[D]** owner-confirmed · **[R]** recommendation, not approved · **[O]** open question ·
**[G]** confirmed implementation gap.

Companion records: `docs/decisions/2026-10-22-final-decision-sync.md` (Q35–Q50);
`docs/decisions/OPEN_QUESTIONS.md` (register, Q35–Q52 and O-12/O-13/O-15…O-18).

## 1. Q51 / R-1 — General Pass is a new round [D]

When all four players pass:

- **Classic:** cancel the current round, deal fresh cards, and start a **new round** with **no** points
  multiplier.
- **Normal Calculation:** cancel the current round, deal fresh cards, and start a **new round** with points
  multiplied by **×2** (the multiplier ladder continues ×2 → ×4 → ×6 → ×8, capped at ×8 per Q1b).
- **This is a new round number, not a replay of the same round number.**

**Wording reconciliation [D].** `CANONICAL_RULES.md` A6.1 (`:575`) used "redeal"; A6.4 (`:591`) used
"consumes the round number". They are not two behaviours: "redeal" means a fresh deal into the **next round
number**. A6.4 is the operative formulation and matches this ruling; A6.1 is not repealed on its multiplier
rule, only on the ambiguity of the word "redeal". A8.7 records the precise behaviour.

**Verification requirement [D].** Round progression and multiplier application must be verified in both game
types; documentation changes alone are not evidence of success.

**Implementation status [G].** The code currently does the opposite:
- `Bidding.kt:493-508` always emits the doubled multiplier (no game-type input), capped at ×8.
- `QuickMatchViewModel.kt:154-167` re-deals the same round and always arms the double.
- `Settlement.kt:517-524` and `TimeoutResolution.kt:979-984` re-init the same round in replay.
- `MatchAdapter.kt:168-171` treats the pass as an ordinary applied bid.
- `OnlineMatchViewModel.kt:724` ignores the pass entirely.

Recorded as implementation sprint **S1** in `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.10. No sprint code
is written by this record.

> **Pointer, 2026-10-23 (online half; section body unchanged).** The online General Pass is now specified as
> **Option B** — a persisted `currentMultiplier` on the match document, written with the round advance and
> read on every cold start — in `docs/decisions/2026-10-23-direction-multiplier-rounds-git.md` §1. The
> Firestore rules change it requires is **not authorized** (O-19).

## 2. Sprint scope directive [D]

The owner requires explicit, sequenced implementation sprints in the implementation plan, not a
documentation-only closure. The scope must cover:

1. Profile statistics eligibility: ROOM, RANKED and UNRANKED all contribute to profile career result
   statistics; RP remains Ranked-only (N-1).
2. Sa'ayda round-extension logic implemented and wired into the production match flow — not merely tests
   around an unwired helper.
3. Tests for the Sa'ayda rule: each occurrence in fast rounds 14–18 inclusive extends the match by one round;
   round 13 does not count.

   > **Pointer, 2026-10-23 (terminology; item above unchanged).** "Fast rounds 14–18" is the **extension
   > window**, not the match length. The match is **18 rounds** (FULL) / 10 (MINI); 14 is
   > `firstFastRound`. The live ceiling after extensions is `maxRounds` (18 + N), never 14 and never a
   > fixed 18. See `docs/decisions/2026-10-23-direction-multiplier-rounds-git.md` §2.
4. The five previously identified logic gaps: O-02b fast-bid suit/tie handling, O-03b immediate 7th-takeover
   handling with ballot cancellation, O-03d timeout-removal charge, O-04b ROOM bot/player result attribution,
   and N-1 profile statistics eligibility.
5. Documentation, decision records, Arabic localization, test expectations and requirement-to-test
   traceability updated to match.

**Sequencing constraint [D].** Server-authoritative and state-integrity changes are sequenced carefully;
unrelated approved behaviour is preserved.

**Scope limit [D].** This authorises *planning* the sprints. It does not mean their code is written, tested
or verified. Plan V2 §5.10 marks every sprint PLANNED or BLOCKED and records the affected files, acceptance
criteria and targeted tests.

## 3. Q52 / O-14 — five Arabic suit display labels [D]

| Suit | Internal id (unchanged) | Final Arabic display label |
|---|---|---|
| Sunz | `SANS` | **سانز** |
| Spades | `SPADES` | **أسبيد** |
| Hearts | `HEARTS` | **هارت** |
| Diamonds | `DIAMONDS` | **كاروه** |
| Clubs | `CLUBS` | **تريفل** |

- Apply the labels consistently in the app, design fixtures, localization resources, rules documentation and
  relevant test fixtures.
- **Display/localization only.** Do not change suit identifiers, comparison logic or the approved
  suit-strength hierarchy (Sans > Spades > Hearts > Diamonds > Clubs; `Cards.kt:10-16`).

**Implementation status.** Applied in sprint S0 as a display-only change
(`values-ar/strings.xml`, `design-ui/shared-i18n.js`, `tests/i18n-strings.test.cjs`, A8.5). No comparison
logic is touched.

## 4. Preserved constraints [D]

- Profile career result statistics include ROOM, RANKED and UNRANKED; RP is Ranked-only.
- **O-12** (voice provider/protocol/cost) and **O-13** (ads/consent provider) remain unresolved. No
  recommendation is recorded as approved.
- Google Play Billing is acceptable in principle, but secure purchase verification is **blocked** until O-06
  approves a suitable free-tier runtime. Firebase Spark/no-Blaze and the $0 marginal-runtime constraint stand.
  Costs are identified, not assumed away.

## 5. Items marked open on 2026-10-23 [O]

The owner instructed that the following stay open rather than being answered by inference. Recommendations,
where present, are explicitly **not approved**.

| ID | Question | Recommendation (not approved) |
|---|---|---|
| O-12 | Voice provider, protocol, transport, moderation, permissions and cost | none recorded |
| O-13 | Ads provider and consent-management choice | none recorded |
| O-15 | Does O-07's shared pool require symmetric one-tier pairing both ways, or keep RD9's no-opt-up? | symmetric absolute band, because "shared pool" reads as a pairwise distance |
| O-16 | Is Stage 0 the next work item, or does IAP stay planned-but-blocked? | keep IAP blocked; run Stage 0 when scheduled |
| O-17 | Sprint priority order | S1 → S2/S4 → S3 → S5/S6 → S7 → S8 |
| O-18 | Arabic rule prose for the new rulings | English rule documents; Arabic app labels only |

Execution defaults used in this round (not owner decisions): one local commit with the mandated identity;
no push, PR or merge.

> **Pointer, 2026-10-23 (supersedes the default, not a rule).** "No push, PR or merge" was an execution
> default, not an owner restriction. The owner's 2026-10-23 direction forbids inventing Git permission
> limits and asks for a proposed collaboration policy; see
> `docs/decisions/2026-10-23-direction-multiplier-rounds-git.md` §4 (O-20).

## 6. What this record does not do

- It does not claim any sprint is implemented, tested or verified.
- It does not choose a voice, ads or IAP runtime provider.
- It does not change the canonical rule bodies; those live in `CANONICAL_RULES.md` Amendment A8 and the
  pointer notes.
