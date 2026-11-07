# Stage 0 — O-06 slice 3, the export leg: from `match_outbox` to Firestore account state

**Date:** 2026-11-07 · **Status:** **Design only — for owner review. Nothing here is built, bound,
deployed or provisioned.**
**Branch:** `feat/o06-do-sqlite` · **Builds on:** D6 Step 2 (`b44796c`,
`Stage0_O06_Profile_Authority_Gap.md`, topology A — owner-approved 2026-11-07), `D3` (outbox,
`ranked_events`, alarm drain), `D7` (ledger, append-only).
**Decides:** the single transport from a committed match on the DO path to the account state
the app reads (Firestore `players/{uid}`), and the completion of `D6`'s contract: the fold is
never allowed to live only inside one match.

## Why this note exists

Step 2 computes the fold correctly **inside** one match. It cannot, by itself, make the
progression survive to the next match, because:

- `profiles` lives inside that match's MatchDO (`idFromName("match:" + matchId)`);
- the DO has no Firestore, no network, no second database — only its own SQL;
- the only designed exit from the DO is `match_outbox` → alarm drain → `D1 ranked_events`
  (D3/D1), and D1 is a replica, never an authority.

The approved topology A: **Firestore remains the account system of record**; the DO is a
computing clone per match; the fold is exported out of the DO through the *same* outbox machinery
D3/D7 define, and a Firebase-side apply-leg carries the folded fields into
`players/{uid}` with the Admin SDK. **Nothing is ever read from Firestore on the DO authority
path.** This note designs that export leg in full, so the seeding note
(`Stage0_O06_Profile_Snapshot_Design.md`) becomes implementable afterwards.

## The one-sentence plan

One outbox row per seated human per settle (`kind = "profileUpdate"`), committed in the same
synchronous stretch as the claim; the existing D3 alarm drain moves them into `D1 ranked_events`;
a Firebase scheduled function polls an export route and applies them to Firestore `update()`-style
(absolute folded values — naturally idempotent), then acknowledges; D1 keeps a deduped, replayable
trail.

## Scope of this milestone (proposed as "Step 3")

1. `match_outbox` table in the per-match DO schema.
2. Outbox write inside the settlement stretch (after the claim write lands — order is
   irrelevant for atomicity, it changes nothing about what the DO has committed).
3. Alarm drain re-armed with capped backoff (1s → … → 5 min cap) — existing D3 design, wins over
   this note if they diverge.
4. `D1 ranked_events` gains a `rankedLedger` consumer (D7) **on the same stream** — decision (3)
   below.
5. Firebase `onSchedule` apply-leg: poll export → apply → ack.
6. Tests at every hop + the end-to-end acceptance.

## Data model (extends `Stage0_O06_DO_D1.md` / `DoSchema.kt`)

### `match_outbox` (per-match DO SQLite)

```sql
CREATE TABLE IF NOT EXISTS match_outbox (
  event_key   TEXT PRIMARY KEY,   -- "profile:{matchId}:{uid}" / "settle:{matchId}" (D7)
  kind        TEXT NOT NULL,      -- "profileUpdate" | "rankedLedger"
  match_id    TEXT NOT NULL,
  payload     TEXT NOT NULL,      -- JSON
  occurred_at INTEGER NOT NULL
);
```

`event_key` is the dedupe anchor everywhere (at-least-once → never breaks correctness):
- `profile:{matchId}:{uid}` — one per seated human; absolute folded values.
- `settle:{matchId}` — the D7 ranked-ledger row, same table.

### `D1.ranked_events` (exists per D3; extended by one column if wanted)

```sql
-- as D3 specifies, plus:
applied_at INTEGER  -- NULL until the Firebase apply-leg acks; enables retention
```

## Flow (single stream, two consumers)

```
 settleMatch (DO, synchronous stretch)
   ├── fold profiles  (Step 2, unchanged)
   ├── write claim    (once-only)
   └── INSERT match_outbox × seats  (+ rankedLedger row if D7 shares the table)
                           │
                     alarm(now + 1s)
                           │
              drain: one D1 batch INSERT ... ON CONFLICT(event_key) DO NOTHING
                     → DELETE drained rows from match_outbox
                           │
        ┌──────────────────┴───────────────────┐
        │                                      │
 rankedLedger consumer                  apply-leg consumer (new)
 (D7 audit — never                      Firebase onSchedule, every minute:
  decides the outcome;                    POST /call/exportEvents  (service auth)
  D7 wins if tension)                     → apply each profileUpdate to
                                            players/{uid} via Admin update()
                                            → POST /call/ackEvents { eventKeys }
                                            → applied_at set / rows pruned
```

**Payload of `profileUpdate`** (mirrors the claim's `movements` — RD13 self-slice not needed
here; it's account state):

```json
{
  "matchId": "m1",
  "uid": "u1",
  "version": 8,
  "settledAt": 1730000000000,
  "fields": {
    "rp": 732, "tier": "Gold", "division": "II",
    "seasonId": "s7", "placementState": "PLACED",
    "rankedGamesPlayed": 13, "rankedKingCount": 4,
    "rankedSecondCount": 3, "rankedThirdCount": 2, "rankedKozCount": 4,
    "demotionShield": false,
    "highestRankTier": "King", "highestRankDivision": "I"
  }
}
```

Every value is **absolute** — re-applying the same row is idempotent, no merge arithmetic, no
version checks required in the apply-leg (dedupe is `event_key`, full stop).

## The apply-leg (Firebase side, new code, deployed later — **not in this design step**)

- `onSchedule("* * * * *")` → reads the Worker's `/call/exportEvents` with the service token
  (same service-principal model settled in the seeding note; decision (4)).
- Applies a bounded page (e.g. 100 events/minute — ≫ any realistic settle rate) as a Firestore
  batched `update()`, untracked-document-safe (`update()` semantics — `displayName`, `rank`,
  room/match pointers untouched).
- Calls `/call/ackEvents` with the applied `event_key`s.
- Never applies a `rankedLedger` row.
- Failure mid-batch: no `ackEvents` call for the unapplied keys → they are re-fetched next tick →
  re-applied (idempotent) → no progress is ever lost, none double-applied at the Firestore
  document level.
- **The fold itself is never re-run; the apply-leg only moves verdicts, not computation.**

## DO housekeeping

- Outbox rows are deleted at drain time (D3's own rule). If the alarm fails (D1 down), rows
  persist and backoff ramps; nothing is lost; no data is ever written to D1 or Firestore by an
  uncommitted match.
- `ranked_events` retention: `applied_at` lets a later sweeper prune applied rows (PITR covers
  restores; out of scope for V1, noted).
- The seeding note's trigger subscribes to *creation*; this leg consumes *settlement* — the two
  together close D6 end-to-end: create → seed → settle → export → next match sees current
  profiles.

## Failure & ordering guarantees (all reused from D3, extended)

| Failure | Behaviour |
|---|---|
| DO commits, alarm never fires | row persists; next alarm re-arms (every enqueue sets `now+1s`, coalesced) |
| D1 down | drain leaves rows; backoff 1s→2s→…→5min cap; alert log line; settle unaffected |
| Poller down | D1 accumulates `applied_at IS NULL`; re-applied harmlessly when it returns |
| Apply-leg crashes mid-batch | unapplied keys un-acked; re-applied next tick; Firestore document stays consistent |
| Two DOs, same player, concurrent settles | each exports its own absolute pre-image **if both fold from the same seeded pre-image** — then Firestore sees one last-writer with the same values, and divergent folds converge correctly next match via the seed. (The seating/placement subtleties are a known, documented limit — see §Known limits.) |
| Replay of a settle | no outbox rows on the replay path (recordSettlement/claim replay writes nothing) → no double export |

## Known limits (honest)

- **Two matches of the same player settled concurrently before either seeds or exports** can
  each carry a pre-image that doesn't include the other's fold. The second write wins in
  Firestore; RP is not double-counted (each match writes an absolute value), but one fold's
  award could be shadowed. Real, bounded, and pre-existing in the Firebase path too (the
  Firestore transaction does not serialise across matches either). The mitigation for the
  future: the seed could read the newest *applied* fold — that is a seeding-note concern, kept
  out of V1.
- `ranked_events` is a replica; Firestore lag is possible. Never used to decide an outcome.
- `updated_at` on `profiles` is diagnostic only; the fold ignores it.

## Deployment / provisioning boundary (owner gates, nothing happens here)

This milestone *cannot* be verified without a D1 database and a deploy:

1. **Create D1** (`wrangler d1 create estemshan-ranked-events` — Free plan, bytes/row trivial
   at our volumes), add `[[d1_databases]]` binding + migration to `wrangler.toml`.
2. **Secrets**: `SEED_SERVICE_TOKEN` / apply-leg credentials (or the service-account verifier,
   per the seeding note's decision (4)).
3. **Deploy** Worker → then trigger → then one real match as the proof.

None of that is authorized by this note. **No D1, no wrangler.toml edit, no deploy, no Create
of anything** until you say go — exactly like every previous slice.

## Decisions required (blocking code)

1. Topology A binds Firestore as account authority — **recorded as approved** (2026-11-07).
2. Transport check: poller pulls from a Worker route backed by the D1 binding (recommended —
   matches D3, one stream), vs direct DO-RPC export (no D1 read/write by Firebase, but a second
   mechanism forever) — **pick (a) or (b).**
3. Ship the `rankedLedger` (D7) consumer **inside this milestone** (recommended — it's the same
   stream, saves a second pass), or strictly the profile leg?
4. Export/apply service auth: reuse the seeding note's service token (recommended), or the
   service-account-through-verifier option — **pick one.**
5. D1 + binding + deploy: approve the provisioning listed under §Deployment boundary, or defer
   the milestone — **required before code, because §Tests cannot run green without at least a
   local D1.**
6. Milestone unblocking: on green, is this the merge gate for PR #199 → `main`, or does something
   else follow first?

## Test plan (executed before any deploy; order matters)

- **Kotlin unit** (authority-core): outbox row written inside `transact` with absolute values;
  replay settle writes **no** rows; drain `batch()` dedupes on `event_key`; backoff cap; alarm
  failure leaves rows; `applied_at` retention query.
- **Worker node --test**: `/call/exportEvents` with/without service auth; page limit; ack path;
  no cross-match leakage; handlers not declared in `CALLABLES` (export routes are **not**
  callables and must stay out of `manifest.test.mjs`).
- **Emulator** (existing harness): one full DO-path match → assert `players/{uid}` final values
  equal the fold; replay is a no-op; outbox empty after ack.
- **End-to-end acceptance** (the milestone's gate): create → seed → settle → export → apply →
  re-seed → verify the account carries the fold — repeat twice to prove the loop.

## Explicitly out of scope

- The seeding note's implementation (blocked on this); a profile cache; retention jobs beyond
  `applied_at`; the S64 leaderboard; anything touching `firestore.rules`; any deploy.
