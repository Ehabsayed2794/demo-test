# Phase 0 spec — Native scoring (complete table)

Authority: owner decisions 2026-09-17 (D1 as-is carryover incl.
Amendment A1; D2 graduated Risk for native). This file is the ONLY
scoring reference for the Kotlin port — Phase 2 implements THESE
numbers, verified by the JUnit mirror of the cited JS tests.
`src/` keeps its flat-±10 Risk until the owner says otherwise; that
legacy difference is intentional, not drift.

Notation: E = estimate (bid), T = tricks won, miss = |T−E|.
"Win" = T == E. All bonuses/penalties stack unless stated.

## 1. Standard roles (NORMAL / CALLER / WIZZ / RISK / WIZZ_RISK)

- Win: `10 + T`, +10 Caller-or-With (single — WIZZ double-count was a
  bug, fixed), +10 sole winner, +Risk-ladder if Risk player.
  (Pin: WIZZ win-5 = 25; WIZZ_RISK win-5 = 35.)
- Loss: `−miss`, −10 Caller-or-With, −10 sole loser, −Risk-ladder if
  Risk player. (Pins: sole loss 5/4 = −11; 3/1 = −12; non-sole = −miss.)
- Risk ladder (D2, NATIVE ONLY): |total−13| of 1→0, 2–3→10, 4–5→20,
  6+→30. Risk player = last estimator (see engine `computeRiskPlayerId`
  rule). Dash Call players are NEVER Risk.

## 2. Super Call, Normal mode (Amendment A1 carryover)

- Win: **E²** — 8→64, 9→81, 10→100, 11→121, 12→144, 13→169. Sole +10
  (74/91/110/131/154/179).
- Loss: **half of that, rounded half-up, flat by bid** —
  −32/−41/−50/−61/−72/−85 — independent of tricks taken. Sole loser
  10 extra (−42/−51/−60/−71/−82/−95). No doubling anywhere on this role.

## 3. Dash Call, pre-bid (canonical §4)

- Flat by round total (Under = total ≤13, Over = >13):
  win +33/+25, loss −33/−25. Sole winner +10 (43/35); sole loser
  10 extra (−43/−35). Never Risk. Tricks taken do not matter.

## 4. Normal Dash (0 estimated mid-auction)

- Ordinary 10+T: win +10 (sole +10 → 20); loss −(10+T) (sole −10
  extra, e.g. taking 3 alone = −23).

## 5. Sa'ayda (escalation)

- All four fail → round scores 0 for everyone; next round multiplier
  ×2 → ×4 → ×6 → ×8 (cap ×8), applies to EVERY component, resets to
  ×1 on any success. A round-18 Sa'ayda forces round 19.
  (Port the canonical LADDER — not `src/`'s double-the-total shortcut.)

## 6. Classic mode (unchanged — port verbatim from sheet line 192)

Normal E+13/−miss; Caller/Wizz E+13+10/−(miss+10, or +20 if
TotalBids≤11); Risk E+13+10/−(miss+10); Wizz-Risk E+33/−(miss+20);
Super fixed +42/−20; Dash fixed +33/+23 / −20/−10 (Under/Over 13);
Normal Dash fixed +23/+13 / −10/−T; sole winner +10; sole loser
doubles capped at −22. (Yes — doubling lives ONLY here.)

## 7. JS test → JUnit mirror map (Phase 2 must port every row)

- `tests/with-grant-paths.test.cjs` (With paths C1–C3, fast Caller A1–A3)
- `tests/fast-super-reset.test.cjs` (S1–S3 Super-Call reset)
- `tests/fast-round-caller-with-d2.test.cjs` (extension suits)
- `tests/src-super-call-normal.test.cjs` (bid² tables + sole + scope)
- `tests/src-dash-normal.test.cjs` (dash tables + boundary)
- `tests/match-flow-scoring-scenarios.test.cjs` (sa'ayda/with end-to-end)
