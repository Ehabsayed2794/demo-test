# Scoring Divergence Analysis

Sprint 4.0, Task C. **Mapping only — no scoring code is fixed here.** This is the blueprint for Sprint 4.1.

## Scope

Comparing `src/utils.ts`'s `calcNormalScore()` (the formula actually used by the shipped Estemshan app) against:
1. The canonical rules doc, `uploads/Estimation_Rules_v2_SingleSourceOfTruth.docx` §4 "Official Scoring System."
2. `design-ui/engine/scoring-engine.js`'s Normal-mode (TRICKS/DASH) branch — the reference implementation already confirmed rules-compliant for this mode in `PROJECT_STATUS_AND_MASTER_PLAN.md` §8.

`src/utils.ts`'s **Classic-mode** formula (`calcClassicScore()`) is **not** included — it was independently cross-checked earlier this session and found byte-for-byte identical to `scoring-engine.js`'s Classic implementation, and is now documented in the canonical rules doc's "Classic Calculations Mode" section. Only Normal mode diverges.

## Divergence #1 — Super Call bonus invented for Normal mode

| Source | Formula |
|---|---|
| `src/utils.ts` (`calcNormalScore`, `role === 'SUPER_CALL'`) | Fixed **+20** on success, **−20** on failure, regardless of bid value |
| Canonical rules doc §4 "Official Scoring System" (Standard Players) | **No Super Call special case exists.** Formula is `10 + T + Bonuses` on success / `−\|T−E\| − Deductions` on failure, with the Caller/With ±10 bonus applying — the same as any other Caller |
| `scoring-engine.js` (Normal/TRICKS branch) | No Super Call role distinction at all — a Super Call bidder is scored as an ordinary Caller via the same `10 + T + bonuses` formula |

**Verdict: `src/utils.ts` invents a rule.** Super Call is a real concept (§2.2, "bidding 8 tricks or more") that changes *auction/confirmation behavior*, but nothing in the canonical doc's scoring section gives it a distinct point value in Normal mode. (It DOES have a distinct fixed value in **Classic** mode — +42/−20 — which may be where this Normal-mode value was mistakenly carried over from.)

## Divergence #2 — Dash Call and Normal Dash conflated into one wrong formula

| Source | Formula |
|---|---|
| `src/utils.ts` (`calcNormalScore`, `role === 'DASH_CALL' \|\| role === 'REG_DASH'`) | **Same formula for both roles**: success → flat `+10`; failure → `−\|bid − won\|` (i.e., `-|0 - won|` = `-won`, since both roles bid 0) |
| Canonical rules doc §4 "Dash Call (Pre-Bidding) — Flat Scoring" | Dash Call (pre-bid) is **flat ±33 (Under 13) / ±25 (Over 13)**, ±10 sole-winner/loser modifier only, **never** Risk |
| Canonical rules doc §4 "Normal Dash (0 estimated during Estimation)" | Treated as an **ordinary 0-estimate** using the `10 + T` formula: success → `+10` (plus sole/Risk if applicable); failure → `−(10 + tricks)` |
| `scoring-engine.js` (DASH vs DASHCALL branches, kept separate) | Correctly implements BOTH distinctly: `DASHCALL` → flat ±33/±25; `DASH` (Normal Dash) → `10 + T` style, including the Caller/With ±10 bonus |

**Verdict: two distinct rules, each with its own real formula, are collapsed into one incorrect formula in `src/utils.ts`:**
- Its `REG_DASH` (Normal Dash) case is *closer* to correct (success=+10 matches the doc) but its **failure** case (`-|0-won|` = `-won`) is missing the required `+10` term — the doc says failure is `−(10 + tricks)`, not `−tricks`.
- Its `DASH_CALL` (pre-bid Dash Call) case uses the SAME formula as Normal Dash — but pre-bid Dash Call should be a completely different, flat ±33/±25 value that doesn't depend on `won` at all. As written, `src/utils.ts` never applies the ±33/±25 flat scoring anywhere.

## Divergence #3 — Risk exclusion for pre-bid Dash Call not enforced

The canonical doc states a pre-bid Dash Call player is "therefore NEVER the Risk player" and Dash Call scoring "never" receives Risk. `src/utils.ts`'s `RoundPlayerData`/`calcNormalScore` signature has no visible mechanism preventing a `DASH_CALL` role from also carrying Risk bonuses elsewhere in the caller's data model (not fully auditable from `calcNormalScore()` alone, since Risk role assignment happens upstream — flagged here as a needs-verification item for Sprint 4.1, not a confirmed bug).

## Summary table

| Formula component | `src/utils.ts` (Normal mode) | Canonical rule | Compliant? |
|---|---|---|---|
| Standard Caller/With/Risk win/loss | `10+bid` win / `-miss` loss, +10 stacking bonuses | `10+T+Bonuses` / `-\|T-E\|-Deductions`, +10 stacking | ✅ Matches |
| Super Call | Fixed +20/-20 | No special case (ordinary Caller formula) | ❌ Invented |
| Pre-bid Dash Call | `+10` / `-won` (same as Normal Dash) | Flat +33/-33 (Under) or +25/-25 (Over) | ❌ Wrong formula entirely |
| Normal Dash | `+10` / `-won` | `+10` / `-(10+won)` | ❌ Failure case missing `+10` term |
| Sole winner/loser | +10 / double-capped -22 | +10 / (not explicitly doubled in doc, but consistent with engine) | ✅ Matches engine convention |

## Sprint 4.1 blueprint

Fix, in `src/utils.ts`'s `calcNormalScore()`:
1. Remove the `SUPER_CALL` fixed ±20 special case for Normal mode — route it through the ordinary Caller/With formula instead (it already gets the Caller +10 bonus via the `role === 'CALLER'` check once merged).
2. Split `DASH_CALL` and `REG_DASH` into two genuinely different formulas:
   - `DASH_CALL`: flat `±33` if the round's total bids finished ≤13 ("Under"), `±25` if >13 ("Over") — needs the round's `totalBids` passed into this function (not currently a parameter of `calcNormalScore`, only of `calcClassicScore` — a real signature gap to close).
   - `REG_DASH`: `success ? 10 : -(10 + won)`.
3. Confirm (not yet verified) whether `DASH_CALL` can currently receive a Risk bonus anywhere in the data pipeline, and exclude it if so.

Not fixed in this document, per Task C's explicit scope.
