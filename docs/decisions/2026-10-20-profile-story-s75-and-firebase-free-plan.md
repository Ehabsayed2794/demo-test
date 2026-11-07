# 2026-10-20 — Owner rulings: Profile editing is its own open story (S75); Firebase stays on the free plan

**Status: RECORDED.** Source: owner messages during the S41 Arabic retrofit session.

## 1. Profile editing → story S75 (OPEN)

- Owner asked for a Profile screen with an editable name and a Save action, as **its own story**, not part of S41.
- Tracked as **S75** in `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (§C table, next to S41). Status: **open, not started, not estimated**, not in any plan total.
- Approved Arabic (owner, S41 session): `profile_display_name` = اسمك أو لقبك في القعدة; `profile_save` = حفظ التعديلات; `profile_saved` = اتحفظت التعديلات بنجاح ✅. These keys do **not** exist in `strings.xml` yet and must be added together with the screen (EN + AR), not before.
- Constraint: stays inside the free plan (see §2) — one Firestore write to `players/{uid}.displayName` (already in the rules allowlist), no Cloud Function.
- Not built in S41: it needs UI, persistence, tests, and an Android toolchain that was unavailable in that session.

## 2. Firebase stays on the free plan (Spark)

- Owner: the project stays on Firebase's free plan. Recorded as **D5** in the plan's owner-decisions list.
- Existing consistent text: RD16 (cost principle), R29 (Spark covers v1), V4 (no card on file, voice).
- **Open conflict (not resolved here):** RD11/S42 deploy Cloud Functions, and to our knowledge Firebase requires the Blaze (pay-as-you-go) plan to deploy functions (free quota may still mean $0). This must be verified against Firebase's current pricing before S42 deploys. If confirmed, the owner chooses: accept Blaze with budget alerts/caps, or move settlement off Cloud Functions.

## 3. Same session: S41 Arabic rulings (for reference)

App name `Made`; `standings_name_saayda` removed (badge no longer shown); owner-approved Egyptian-Arabic strings in `values-ar/strings.xml`.
