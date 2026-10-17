# 2026-10-17 — Owner ruling of record: `UNRANKED` scope (Vote Kick present; RP frozen)

**Status: APPLIED.** This record carries the 2026-10-17 owner ruling into the five documents
listed in §3 — in place, with dated tags — on branch `docs/closure-cleanup` (base `6308117`).
The owner raises and merges the pull request himself. Nothing from the 2026-10-16 closure is
reopened; the two points this ruling restates/corrects are §1.4–§1.5.

## 0. The owner's words (verbatim, in Arabic — as sent)

> Unranked = مفيش نقاط RP بتنقص او بتزيد / بس كل حاجة زي ال ranked بالظبط بس من غير RP

> الغرف بس اللي مفهاش Vote Kick

*(Meaning, for the English record: “Unranked = no RP points decrease or increase; but
everything is exactly like Ranked apart from RP.” — “Only the Rooms have no Vote Kick.”)*

## 1. The ruling

1. **`UNRANKED` equals `RANKED` in every rule except RP.** RP is frozen in both directions —
   no gain, no loss, no Rank-down — and everything else about Ranked applies to Unranked
   exactly.
2. **Manual Vote Kick is available in `RANKED` + `UNRANKED`.** `ROOM` is the only mode without
   it. This corrects both the frozen Batch 1 “Rooms + Ranked” text and RD18's 2026-10-05
   “Ranked-only” narrowing — the correct scope is `RANKED` + `UNRANKED`, with `ROOM` the only
   exclusion.
3. **Vote Kick's conditions mirror Ranked** and are unchanged: only after Round 7 fully
   completes (first possible vote = Round 8), target = the unique Koz, target cannot initiate,
   2-of-3 eligible-human majority, failure = 5-round same-target cooldown, success = permanent
   removal + no rejoin.
4. **A passed Vote Kick records 1 Koz, final.** The 2026-10-14 “2 Koz” pointer on RD18 is
   repealed for this path (conflict C1, ruled 2026-10-16; RD18's original wording restored) —
   restated here because the RD18 area's text history still carries the doubling. The 2-Koz
   charge covers explicit leave, the 15-timeout removal, and an Unranked disconnect
   replacement only (`CANONICAL_RULES.md` A5.1 charge table).
5. **The career Koz counter is a plain departure counter, not Ranked progression** — it moves
   no tier and no RP. This closes the A5.3 item 3 sub-case (“`UNRANKED` charges meet A3.5's
   'no progression of any kind'”) with the charge standing as written; A3.5 carries a matching
   pointer (added 2026-10-17).

## 2. What this ruling does not change

- Unranked charges (the entry/stake model) are unchanged.
- The disconnect vote's scope (Rooms + Unranked; one 30-second ballot over a live table) is
  unchanged.
- `RANKED`'s RP engine is untouched — the RP freeze is a property of `UNRANKED` only.
- Everything else in the 2026-10-16 closure (OPEN-4 option (a), OPEN-5's three mechanics, the
  charge table's other rows) stands exactly as merged.

## 3. Sites corrected by this change (all in place, dated 2026-10-17)

| Document | Correction |
|---|---|
| `docs/UI_UX_ROADMAP.md` | Top-of-file 2026-10-17 pointer; the capability table and §0.5-E (the E row, the F-block, the five-ways row D); the §2.1 diagram amendment pointer; the mode-gating sentence; the S40 spec rows; the exception register's Vote Kick row; the contradiction register (rows 42 and 45); the S71/S57/S24/S31 artboard statements; the banned-claim register (“Vote Kick absent in Unranked” added as a banned claim); the final gate assertions |
| `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` | RD18 line (now `RANKED` + `UNRANKED`) + a new 2026-10-17 pointer carrying the C1 restatement; RD28's switch-site enumeration; the authority table cell + a new pointer beneath it; the three-value mode paragraph; S71's assertions; the five-paths row; S74's canary clause; M11's rows; the trust split; the test list |
| `docs/rules/CANONICAL_RULES.md` | A3.5's `UNRANKED` row (`no` → `yes` charging) + its own pointer; the DEC-DISC-01 line; A5.1's charge-table cell; A5.3 item 3 → RESOLVED; A5.4's availability clause |
| `docs/design/Estimation Design System Foundation.html` | The Vote Panel note (line 239): the panel renders in Unranked too, equal conditions to Ranked — the drawn sample tag strings are deliberately left as drawn |
| `docs/specs/01-screens.md` | The 2026-10-06 amendment note: Vote Kick is no longer gated off together with voice |

## 4. UI consequence

The Vote Panel (S40) is now a `RANKED` + `UNRANKED` surface: its entry point must be
**present** (not disabled) in a `mode: UNRANKED` match exactly as in Ranked — and **absent**
in a `ROOM`, now the only mode without it, where the rule remains “absent, not disabled”.
The S41/S42 re-cut is unaffected by this ruling.

## 5. Seen, left deliberately untouched (outside this wave's scope)

Stale-reading spots that are not Vote Kick scope/result claims; all governed by the
2026-10-16 closure pointers elsewhere. Left for a follow-up sweep if wanted:

- OPEN-5 “pending” phrasing — roadmap ≈ 538, ≈ 794 (S31), ≈ 3038 (safety table, Disconnect);
  plan ≈ 559.
- OPEN-4 hold phrasing — roadmap ≈ 324 (15-timeout record), ≈ 779 (S24 row), ≈ 2116
  (dependencies row), ≈ 2908 (S57 row), ≈ 4069–4073 (closure-status paragraph).
- Plan ≈ 683–685 (RD31 headline — governed by the 2026-10-16 charge table) and ≈ 705–708
  (“conflict flagged, not resolved” — resolved in the closure section); ≈ 1019 (repo-status
  sentence “no Vote Kick” — historical pre-writing note); ≈ 1368 (S74 “Depends on OPEN-5” —
  resolved by the pointer at ≈ 1392).
- S41/S42 artboards remain “re-cut before S74” carry items (a design step).
