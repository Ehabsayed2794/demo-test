# O-06 — profile authority on the DO path: the gap in D6 (documented blocker)

**Date:** 2026-11-07 · **Status:** **design blocker — per the brief's rule ("أي غياب (مثلًا
profile merge) سجّله كحاجز موثق بدل تخمين"), this is recorded, not guessed. No profile-
seeding code is to be written.**
**Supersedes:** the cross-match paragraph of `Stage0_O06_Profile_Snapshot_Design.md` ("A
second match with the same four seats upserts the same rows …") — that was incoherent.
**Blocks:** D6 Step 2's only honest end state; the DO path cannot run real matches yet.

## The gap, in one paragraph

`D6` says player profiles are **account state**, resolved from a "DO-owned `profiles` table
(snapshot written at match start)" with the post-settle fold written back through the same
transaction — and that "Firestore profiles remain the **migration source for pre-DO matches**",
never read on the DO path. It does **not** say which store owns account state *across
matches*, and the shipped architecture makes the per-match match-DO the only place a snapshot
can land today:

- `MatchDO` is one-per-match (`idFromName("match:" + matchId)`)
  (`native/worker/src/match-do.js`), and Step 2 added `profiles` to **that** DO's private
  SQLite (`DoSchema.kt`). Folded profiles after settlement live only there.
- The DO has no Firestore, no Admin SDK, no network, no other database — only its own SQL.
- The next match with the same seats lands in a **different** MatchDO and seeds from Firestore —
  which never receives match N's fold. **Progression (RP/rank/season/placement/career counters)
  does not survive a second DO-settled match for the same account.**
- The designed export channel (`D7`: `match_outbox` → alarm drain → `ranked_events` in D1; `D3`:
  at-least-once replica) does not exist yet (it is **Step 3**), and D1 is a replica, not an
  authority ("D1 is never queried by the authority path"). Even with Step 3, the current design
  moves audit rows out of the DO — it does **not** apply progression back to any account store.

So: **as implemented, the DO authority can compute a fold inside one match and lose it
immediately afterwards.** This cannot be the account authority story.

## The three coherent topologies

### A — Firestore remains the account system of record; the DO is a computing clone; the fold exports back through the outbox (recommended)

- Match-start seed (admin path — the design note's trigger sketch, unchanged in shape):
  Firestore `players/{uid}` → MatchDO.
- Settlement folds inside the DO as implemented (Step 2) — correct and verified through real
  SQLite.
- **New piece — an export leg:** the DO's settlement transaction also appends one
  `profiles`-kind record per seated human to `match_outbox` (the slice-3 table), carrying the
  exact merged contract fields it wrote to the local `profiles` table.
- A Firebase-side **apply-leg** (Admin SDK, executed from the drain or from a callable) applies
  those fields to Firestore `players/{uid}` with the same merge semantics as the Admin path's
  `writeProfile` (`update()` — untracked fields untouched), idempotently (per-outbox-record
  dedupe key, e.g. `profile:{matchId}:{uid}:{version}`).
- **Nothing on the DO path ever reads Firestore.** The app's ProfileScreen, leaderboard, reset
  sweep keep reading Firestore exactly as today — one writer of fact at all times.
- Convergence bound is the outbox drain's (≈ the `setAlarm(now + 1s)` cadence, same class of
  guarantee as D3: at-least-once, deduped, eventually consistent — a player who starts the next
  match inside the lag window seeds a slightly stale pre-image; the fold itself is never wrong
  once seeded).
- **Costs:** Step 3-class work — `match_outbox` (slice 2/3) + the apply-leg + idempotence/version
  protocol + a migration story not needed (pre-DO matches settle via Firebase unchanged) +
  an equality-living-your-life test between fold output and the apply-leg's merge.
- **What stays valid:** the whole Step 1 + Step 2 stack, `DoStorageTest`'s fold golden, the
  seeding note's trigger/route/auth design, the worker contract, the RP gate.

### B — A shared account DO ("ProfilesDO") owns all profiles

Every match's reads cross to the account DO — but Firebase-style cross-DO RPC is asynchronous,
which breaks the "no `await` between first read and last write" critical stretch the settlement
atomicity rests on, or serializes all settlements through one global DO (bottleneck, one point
of contention, single-threaded profile authority with cross-match stalls).

**Rejected without a full redesign of the settlement shape.** Not recommended.

### C — Profiles in D1 (the shared store)

- D1 has no interactive (stateful) transactions; the settlement's reads→writes stretch would
  lose its atomicity basis; D1 is explicitly never queried by the authority path (D3); the
  fold's read-then-write invariant inside one DO stretch would have to become a batched-CAS
  shape with its own failure modes.

**Rejected.** Not recommended.

## What this blocks, and what is honest right now

- **Blocked:** any seeding implementation, any trigger/route work, any end-to-end
  "DO-path creates → seeds → settles" proof. Building those on the current topology would
  prove a settlement that silently drops progression.
- **Still honestly usable:** Step 1 (port + goldens) and Step 2 (in-match fold substrate) —
  both stay green and meaningful regardless of topology; topology A keeps them verbatim.
- **Documented behaviour until the owner picks:** a DO-path settlement of a fresh match
  computes RP correctly and writes it only into that match's DO. Anyone running this in
  production between now and topology A is **silently losing progression across matches** —
  this note exists so that cannot happen quietly.

## Decisions required from the owner (all blocking any seeding work)

1. **Topology: A / B / C?** (A is my recommendation, on D3/D7 coherence and the no-`await` rule.)
2. If A: approve designing the **export leg** as an explicit Step 3-class work item (outbox
   records + Firebase apply-leg + idempotence protocol + tests), alongside — or instead of —
   the Step 3 ledger, rather than "seed now, figure write-back out later".
3. Confirm that **the Firebase path remains the account authority** (app reads Firestore
   profiles forever; the DO path never becomes a second source of truth for Progression).
4. Confirm that **temporary per-match-only progression is not an acceptable launch state** —
   or, if it is (e.g., a strictly offline DO demo), say so and accept the documented limitation
   in writing.
5. Then: either scope "seed + export together" as the milestone that unblocks PR #199-to-main,
   or keep the DO authority as compute-only and bounded, and say when it is not.
