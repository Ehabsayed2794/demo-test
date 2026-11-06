# Stage 0 — O-06 RP Port Design

**Date:** 2026-11-07 · **Status:** Design only. No code in this note.
**Branch:** `feat/o06-do-sqlite` (PR #199 open, not merged)
**Base:** `origin/main@2b35b49` (E8 step3 + E10 retention + RP-enabled settlement)

## Why this note exists

`main` moved ahead after this branch was cut: the Firebase settlement layer
(`functions/SettlementDb.kt`, 1,235 lines) now computes ranked progression
(S47-S51: RP engine, mixed-tier asymmetry, demotion shield, placement,
season reset, audit ledger) and session takeover
(`SeatReclaim` / `reclaimOnce`). That orchestration is coupled to Firebase
types (`HttpsError`, `FieldValue`, `CallableAuth`, `planSeasonReset`,
`SettlementLedger`).

`:authority-core` was deliberately designed to be **Firebase-free** (the Worker
can never load `firebase-*`). The PR therefore conflicts in four files, and
neither side can win wholesale:

- Taking `main`'s files breaks `:authority-core` compile (unresolved Firebase
  types).
- Keeping this branch's files deletes the RP work that landed on `main`.

A firebase-free port is required before any merge. This note is the decision
record for that port. It is the same standing as D1-D4 in
`Stage0_O06_Authority_Storage_Design.md`.

## Decisions

### D5 — One orchestration, two adapters (restated for the port)
The settlement/timeout orchestration lives **once** in `:authority-core` on
`AuthorityError` + plain `uid`/`data`. `:functions` keeps only the Admin SDK
adapters (`AdminSettlementDb` / `AdminTimeoutDb`) that translate between
Firestore and the ports. No behavior is re-implemented per runtime (RD12).

### D6 — Profile authority, not a migration punt
`settleOnce` needs `readProfile/writeProfile` (rank/RP/season/placement
state). The Durable Object owns match state; player profiles are account
state. The DO adapter must therefore resolve profiles from a DO-owned
`profiles` table (snapshot written at match start) and write the post-settle
profile back through the same transaction. Firestore profiles remain the
migration source for pre-DO matches; they are never read on the DO authority
path. No Firestore read on the DO path.

### D7 — Ledger is at-least-once, append-only
`appendLedger` / `readCorrectionWindow` map onto D1 `ranked_events` /
`match_outbox` (O-06 slice 2). On the DO adapter the ledger write rides the
same synchronous stretch as the settlement transaction (D1); the outbox
drain replays it with `event_key` dedupe. The audit trail is never used to
decide the settlement outcome.

### D8 — `reclaimOnce` is not a settlement dependency
`SeatReclaim` (S73 inactivity takeover) joins `TimeoutDb` per main's pointer
note, but it is **not** required to settle a match. The port implements
`reclaimOnce` after D6/D7, or leaves it fail-closed `unimplemented` on the DO
path with a test pinning that. It must never block settlement.

### D9 — Tests move with the logic
Main's `SettlementTest` RP goldens + `SettlementLedgerTest` /
`RpApplicationTest` / `SeatReclaimOnceTest` move with their orchestration to
`:authority-core`, re-targeted from `FakeDb`-with-Firebase-shaped fakes to
`DoSettlementDb`-over-real-SQLite (the 3 full timeout tests added to
`DoStorageTest` are the pattern). A test that only passed on the Firebase
fake is not evidence on the DO path.

## Implementation plan

| Step | Scope | Hours | Depends on |
|---|---|---|---|
| 1 | Extract main's RP settlement (S47-S51) into `:authority-core` on `AuthorityError`, drop `HttpsError/FieldValue/CallableAuth/CallableRequest/planSeasonReset` | 8-10 | — |
| 2 | `DoSettlementDb` profiles: `readProfile/writeProfile` + `profiles` table + same-transaction write | 12-16 | Step 1, D6 |
| 3 | Ledger/outbox on the DO adapter + `readCorrectionWindow` | 8 | Step 2, D7 |
| 4 | `reclaimOnce` on `TimeoutDb` (or pinned fail-closed per D8) | 8 | Step 1 |
| 5 | Port goldens (`SettlementTest` RP, `SettlementLedgerTest`, `RpApplicationTest`) to real-SQLite evidence | 10 | Steps 1-3, D9 |
| **Total** | | **38-44** | — |

## Merge gate (unchanged)

No merge until: `:authority-core` compiles firebase-free, `:functions` still
compiles against the ported ports, worker 55/55 + Kotlin 107/107 (or its
updated count) green **with 0 skipped**, and the new goldens catch a
`readHands`/`readProfile` mutation. Until then PR #199 stays open and
unmerged — do not merge a branch that does not compile on `main`.

## What this slice does NOT do

- No staging deployment, no Cloudflare account, no billing.
- No Firestore rules change, no Play Store action.
- No deletion of the Firebase path: the Admin adapters keep their behavior;
  the port only moves the shared logic underneath them.
