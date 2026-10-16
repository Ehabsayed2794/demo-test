# OPEN-4 / OPEN-5 — owner closure (2026-10-16)

Status: **both questions CLOSED by owner ruling on 2026-10-16.**
Type: new decision record. **No existing file was edited, moved or deleted.**
Supersedes the "pending / stays open" state of OPEN-4 and OPEN-5 as recorded in
`7dece9c` (`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:166-167`,
`docs/rules/CANONICAL_RULES.md:330-331` and `:360`).

Convention note: per `docs/agents/project-memory.md:20`, the authoritative docs carry
superseded text verbatim behind a pointer note. This file is that pointer's content — it is
written **next to** the three authoritative docs, not into them. The doc edits it implies are
listed in §6 and are deliberately **not** performed here.

---

## 0. Owner wording — ARABIC VERBATIM NOT CAPTURED (integrity flag)

This record was asked to carry the owner's Arabic statements **verbatim**. They could not be
sourced from anything readable on this machine, so **no Arabic text is quoted here** — inventing
owner wording in a decision record is worse than leaving the field empty.

Searched and found nothing: `AGENTS.md`; `docs/agents/project-memory.md`; the whole `7dece9c`
blob set (`grep -c '\p{Arabic}'` = **0** for the plan, `CANONICAL_RULES.md` and the roadmap);
`docs/rules/CANONICAL_RULES.md` A4; the project memory files; `~/.qoder/plans/`; and the session
transcripts under `~/.qoder/projects/C--Users-ehab-sayed-Desktop-demo-test/` (the only Arabic
matches were this agent's own search strings).

> **ACTION FOR THE OWNER:** paste your own wording into §1 and §2 below, replacing the
> "as relayed" lines. Until then the ruling is recorded as relayed to the agent in English on
> 2026-10-16, and the repo convention of "owner wording verbatim" (see plan `:75`, RD30) is
> **not** satisfied by this file.

Repo convention for the precedent: `7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:75` quotes RD30
as *"owner wording verbatim"*. This file is not that.

---

## 1. OPEN-4 — "what precisely does '2 Koz' record?" — CLOSED

**Ruling as relayed (2026-10-16): option (a).**

- A departure records **+2 to the career Koz count**.
- **No forced last-place placement** — the departed player is not placed below continuing players.
- **The bot seat is separate from the player** — the bot's play does not become the departed
  player's placement, and no other player's placement is displaced.
- **No new result value** — there is no distinct "2 Koz" result enum for the 9-stat accounting or
  S50 settlement to model.

**Options explicitly REJECTED:** (b) forced last-place plus the double count, and (c) a distinct
"2 Koz" result value. The three options are enumerated verbatim at
`7dece9c:docs/rules/CANONICAL_RULES.md:330` and `7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:166`.

**Scope of the Koz charge, as ruled:**

| Path | Charge |
|---|---|
| Explicit Leave Match **and** the 15-timeout automatic removal | **2 Koz in `RANKED` + `UNRANKED`; `ROOM` has no 2-Koz rule** |
| Vote Kick | **1 Koz final** (amends RD31 / RD18 — see conflict C1) |
| Disconnect, `UNRANKED` | **2 Koz** |
| Disconnect, `ROOM` | **no Koz** |
| Return when allowed (seat still reclaimable) | **no penalty** |

The "no penalty on return" line matches the discriminator already written at
`7dece9c:docs/rules/CANONICAL_RULES.md:327` ("It never fires on a borrowed seat… *did
participation end, or was the seat borrowed?*"), and the S74 "yes while the table waits" row at
`7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:676`.

---

## 2. OPEN-5 — the disconnect vote's three mechanics — CLOSED

**Ruling as relayed (2026-10-16):**

- The vote runs **30 seconds over a live table** — **no pause during the vote**.
- **Threshold = majority = 2 of 3** answers constitute agreement.
- If **WAIT** wins the vote → the match **pauses for 90 seconds**.
- If **no agreement** is reached in 30 s → **continue with the configured bot**.
- `ROOM`: **no 2 Koz on permanent replacement**; `UNRANKED` disconnect carries **2 Koz**.

The three unanswered sub-questions it closes are exactly those enumerated at
`7dece9c:docs/rules/CANONICAL_RULES.md:360` — (i) the agreement threshold, (ii) what a WAIT does
now that D9's pause is superseded, (iii) what makes a replacement permanent and whether RD31 fires
then — and at `7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:167`.

Consistency: the 30-second window, the configured-bot default and "no match pauses" are the
already-decided half (`7dece9c:docs/rules/CANONICAL_RULES.md:359`); the 90-second wait preserves
that file's surviving invariant — *"a wait is always bounded, never indefinite"* (`:359`).

---

## 3. CONFLICTS FLAGGED — NOT silently resolved

These four points are where today's ruling contradicts text committed in `7dece9c`. They are
recorded as flags for the owner's doc pass. **Do not treat §1/§2 as having quietly rewritten
them.**

**C1 — Vote Kick: 1 Koz (new) vs 2 Koz (RD31 as committed).**
`7dece9c:docs/rules/CANONICAL_RULES.md:326` fires RD31's 2 Koz on "a passed **Vote Kick** (path E,
S63)"; the departure table at `7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:677` reads
"**2 Koz (RD31); previously 'recorded as Koz'**"; `7dece9c:docs/UI_UX_ROADMAP.md:450-454` carries
the same doubling. Today's ruling returns Vote Kick to **1**, which **restores RD18's original
wording** at `7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:93` ("Success → permanent removal, no
rejoin, recorded as **Koz**") and repeals only the RD31 doubling *for this path*.

**C2 — 90 s pause after a winning WAIT vs the "no pause" texts.**
`7dece9c:docs/rules/CANONICAL_RULES.md:358` closed OPEN-2 with "A Vote Kick vote runs while
**gameplay continues**. There is no freeze-and-pause and **none may be introduced**", and `:359`
superseded D9's "pause the match" branch; `7dece9c:docs/UI_UX_ROADMAP.md:58-63` repeats it.
The ruling is **compatible only if read precisely**: no pause *while a vote is open* (unchanged,
still binding on both votes), and a bounded 90 s pause *after* a WAIT verdict **in the disconnect
vote**. If the owner meant a pause inside a Vote Kick, C1/C2 collide with a CLOSED decision and
must be re-ruled — this file does not assume either way.

**C3 — `ROOM` no-Koz vs "regardless of why, in all three match types".**
`7dece9c:docs/rules/CANONICAL_RULES.md:324` states RD31 applies "**regardless of why they left,
and in all three match types**", with `:326` firing it on "a **disconnect** whose seat is
**permanently replaced** (path D's terminal case, `ROOM`/`UNRANKED`)" and the path-D row at `:340`
("on permanent replacement only"). Today's ruling removes the charge for `ROOM` only —
`UNRANKED` keeps the 2-Koz charge — which also narrows the mode-agnostic claim and the 15-timeout row at
`7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:674-676`.

**C4 — the A3.5 Unranked sub-case, resolved.**
`7dece9c:docs/rules/CANONICAL_RULES.md:331` flagged that `UNRANKED` "records no progression of any
kind, so a 2-Koz *statistic* has nowhere to land in that mode even though the trigger fires there"
and called it a sub-case of OPEN-4. With the corrected scope (`UNRANKED` disconnect carries the
2-Koz charge; `ROOM` has no 2-Koz rule), the sub-case is resolved on those terms — recorded so it
is not re-litigated as a new rule.

Also noted, not a conflict: `7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:644` and `:651` made the
9-stat arithmetic conditional on the OPEN-4 answer ("**If OPEN-4 comes back as option (a) or (b)**
…"), and `:655-662` posed the question with "S72, S63, and the 9-stat accounting block on this
answer". Option (a) is the branch those lines anticipated.

---

## 4. Blocked work — now unblocked

- **S72 result-writing** (explicit leave; the leave→configured-difficulty path at
  `7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:111` and `:117`).
- **S63's departure assertion** — build it to the **1 Koz** value per C1, not the committed 2 Koz.
- **9-stat accounting** — option (a) means a career-count increment only; the existing
  "no implementation may displace another player's placement" hold at
  `7dece9c:docs/rules/CANONICAL_RULES.md:330` is now backed by the owner rather than provisional.
- **S74's WAIT half** — unblocked with threshold 2-of-3 and the 90 s bounded wait. Per **R34**
  (`7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:610`) S74 **may still split**; the decided half
  (disconnect detection → configured-bot takeover → 30 s vote → no-agreement default) is unchanged
  and was never blocked.

**R35** (`7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:611`) was opened precisely because these two
answers could grow S72/S74. Option (a) is the smallest-bookkeeping branch, so the expected growth
is limited — but the estimate note is not amended by this file.

**Still gated, and NOT this task:** the **S41/S42 artboard re-cut**. The roadmap gates it on the
design step, not on the ruling — `7dece9c:docs/UI_UX_ROADMAP.md:411-413` ("…are **OPEN-5** and are
not designed or drawn until answered… **re-cut before S74 is implemented**"), and the Profile result
row at `:375` ("do not draw it") / `:307` ("no screen draws the result row yet"). A design pass must
now redraw them; that is a designer's step.

---

## 5. Assertion deltas for whoever implements

| Site | Previously (7dece9c) | Now |
|---|---|---|
| Vote Kick result record | 2 Koz (RD31) | **1 Koz** |
| Leave + 15-timeout record | 2 Koz, all modes | **2 Koz in `RANKED` + `UNRANKED`; `ROOM` has no 2-Koz rule** |
| Unranked disconnect record | 2 Koz on permanent replacement | **2 Koz** |
| Room disconnect record | 2 Koz on permanent replacement | **no Koz** |
| Reclaim-after-takeover | RD31 does not fire | **no penalty** (unchanged) |
| Disconnect vote threshold | undefined, "may not be invented" | **2 of 3** |
| WAIT outcome | undefined | **pause 90 s, bounded** |
| No agreement | continue with configured bot | **unchanged** |
| Pause while vote open | forbidden | **unchanged — still forbidden** |

---

## 6. Follow-up doc pass this record implies (deliberately NOT done here)

Each is a pointer note beside verbatim superseded text, per the convention:

1. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:166-167` — mark OPEN-4/OPEN-5 CLOSED, point here.
2. `docs/rules/CANONICAL_RULES.md:326`, `:330-331`, `:340` — amend the path list and the
   bookkeeping bullet; leave the superseded wording behind the pointer.
3. `docs/UI_UX_ROADMAP.md:37-39`, `:307`, `:370-375`, `:411-413`, `:450-454` — release the
   "do not draw" gates, and open the S41/S42 design re-cut.
4. RD31's own text at `CANONICAL_RULES.md:318-327` — narrow "all three match types" per C3.

**Sequencing constraint:** `7dece9c` is **not on `main`** (this checkout is `6c67a62`, 108 behind),
and the working-tree copies of the plan and roadmap here are an **older edition** than upstream.
The doc pass must therefore be made on top of the synced `docs/roadmap-closure-decisions` tip —
not on this stale tree — or it will re-introduce superseded text.

---

## 7. Provenance

- Sources read for every citation above: `git show 7dece9c:docs/NATIVE_V1_PLAN_AND_ESTIMATE.md`,
  `git show 7dece9c:docs/rules/CANONICAL_RULES.md`, `git show 7dece9c:docs/UI_UX_ROADMAP.md`
  (read-only; line numbers are those blobs' own numbering, verified by grep this session).
- `7dece9c` = "Docs(roadmap closure): record the 2026-10-14 owner decisions in all three
  authoritative docs", on `origin/docs/roadmap-closure-decisions`, **not on `main`**.
- Ruling content: relayed to this agent in English on **2026-10-16**; Arabic verbatim **not
  captured** (§0).
- This file is **untracked**. Nothing else in the repository was created, edited, moved or
  deleted by this task, and no git state was changed.
