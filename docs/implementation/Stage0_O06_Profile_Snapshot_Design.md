# Stage 0 — O-06 D6, profiles snapshot at match start (design note)

**Date:** 2026-11-07 · **Status:** **Draft — for owner review. No code written under this note yet.**
**Branch:** `feat/o06-do-sqlite` · **Base:** `origin/main@2b35b49` + Step 2 (D6) landed in `b44796c`
**Decides:** where the DO-owned `profiles` table is seeded from `players/{uid}`, by whom, and how a match becomes settleable on the DO authority path.

## Why this note exists

Step 2 landed the storage half of D6: `DoSchema.kt` gained a `profiles` table, and
`DoSettlementDb` / `DoTimeoutDb` now implement `readProfile` / `writeProfile` inside the
settlement's single synchronous stretch. The RP fold reads the checked pre-image, applies
progression, and merge-writes the progression contract fields back — all atomically, all through
real SQLite.

What is **not** there, and what this note decides, is the source of those profiles: **who writes
`players/{uid}` into the DO at match start, and how.** Until this lands, every DO-path
settlement for a match fails closed with `failed-precondition` ("seated player has no profile"),
as pinned by test — correct, and by design.

`D6` in `Stage0_O06_Authority_Storage_Design.md` is explicit:

> The Durable Object owns match state; player profiles are account state … The DO adapter must
> therefore resolve profiles from a DO-owned `profiles` table (snapshot written at match start) …
> Firestore profiles … are never read on the DO authority path. **No Firestore read on the DO
> path.**

And the port's own code enforces the same: `DoStorage.kt` has only `sql` access — no fetch, no
Firestore, no network. **The DO cannot pull the snapshot itself, ever.** The snapshot must be
*pushed* into the DO by a party with Admin-SDK access, once, at match start.

## The three facts that constrain the design

1. **Match creation is not uniform.** There are at least two creation styles in the tree today:
   - **Client path** — `native/services/.../MatchService.startMatch` creates the match inside the
     app process via the client Firestore SDK, gated by security rules. It is *not* a trusted
     Admin caller. (`RoomService.createRoom` → the same shape.)
   - **Server paths** — `MatchmakingSearchDb.createMatch` / `UnrankedPool.createMatch` run
     server-side inside the Functions runtime and create matches as part of the ranked-queue /
     unranked-fill transactions.
2. **No `onCreate` trigger exists anywhere** in the Functions codebase today; nothing observes
   match creation server-side. Adding one is possible, but it is a **deployed artifact** and it
   is not something this note takes for granted.
3. **The client is not trusted with the snapshot.** A profile pushed by the client could be a
   forgery — exactly the "settlement against a hand-built pre-image" case the RP fold's
   fail-closed contract exists to refuse. Any design that reads profiles from the request is a
   non-starter.

## Candidate designs

### A — Firestore `onDocumentCreated("matches/{matchId}")` trigger → Worker `/rpc/seedProfiles` (recommended)

The single interception point that does not care *who* created the match or *how*:

1. A new second-generation trigger fires on `matches/{matchId}` creation.
2. It reads the seats from the match document, then reads each seated human's `players/{uid}`
   with the Admin SDK (bot seats are skipped — the fold never attributes bots).
3. It calls the Worker's `/rpc/seedProfiles` (new, internal RPC, **not** a public callable),
   carrying `{ matchId, players: [{ uid, data }] }`, authenticating as a **service** principal
   (see "The auth question" below).
4. The Worker validates the envelope, then calls the DO `stub.fetch("/rpc/seedProfiles")` for
   `idFromName("match:" + matchId)` — the same DO instance the settlement will later hit.
5. The DO upserts the provided profiles into `profiles` in one synchronous stretch, idempotent
   by `uid` — re-seeding is a no-op with the same values.

**Strengths:** one hook covers every creation path (client path, matchmaking, pool, future
paths, emulator harnesses); the client never enters the trust path; the DO stores exactly what
the fold survives on, written before any match can settle; idempotent by construction.

**Costs / requirements:** a new deployed artifact (the trigger) + a new Worker route + a decision
and deployment of service-to-service auth. Deploy is an owner gate (see §Decisions).

### B — Seed inline in the server-side creation paths only

Add a trusted seed call after each server-side `createMatch` (matchmaking, unranked-pool).
No new trigger type, smaller blast radius.

**Fatal gap:** it cannot cover the **client-start path** — the app's `startMatch` writes to
Firestore client-side, with no Admin access anywhere in that call. Client-created matches would
never get profiles ⇒ their DO settlements fail closed forever. To close the gap you must either
mirror **A's trigger anyway**, or force the client path through a server callable that creates the
match server-side — a product change of its own, much larger than A.

### C — Client pushes the snapshot as part of match start

**Rejected.** Whoever creates the match would be trusted with `players/{uid}` contents — the
fold would happily settle against a forged Gold II pre-image. Violates the same contract
`RpApplicationTest` pins (forged → corrected / refused).

### D — DO lazily pulls the snapshot on first settle

**Rejected.** The DO has no Admin SDK, no fetch, no network — only `ctx.storage.sql` (D2/D6).
Anything else breaks the DO's synchronous, atomic stretch (a network call inside it is an
`await` between the first read and the last write, which the D1 write rules forbid outright).

## The DO / Worker side (common to A and B)

- **`/rpc/seedProfiles`** on the DO (internal, never public — `stub.fetch` only, same class rule
  as `/rpc/settle` and `/rpc/timeout`):
  validate `matchId` (same shape guard as `/rpc/settle`), then validate `players` is a plain
  array of plain objects with string `uid` and plain-map `data`; fail closed otherwise.
- Idempotence: `INSERT ... ON CONFLICT(uid) DO UPDATE SET data = excluded.data, updated_at =
  excluded.updated_at` — same statement shape as the Step 2 adapter. Re-seeding the same match
  is harmless.
- **No match-scope requirement on the profiles themselves.** The `profiles` table is account
  state, keyed by `uid`; the DO simply holds the latest known pre-image at seed time. A second
  match with the same four seats *upserts the same rows* — windows between matches therefore see
  *this account's* latest seeded pre-image, exactly as intended by "profile state is account
  state". (Recorded decision: profiles are not per-match; there is deliberately no
  `profiles × match_id` table.)
- `updated_at` is the seed wall clock for operations hygiene (when was this pre-image taken);
  it is diagnostic only — **it is never read by the fold**.
- The DO never learns *where* the seed came from; the DO's contract stays "plain maps in, plain
  maps out".

## The auth question (decision for the owner, blocking A)

The seed RPC is service-to-service: **not** a user callable, not a Firebase callable, not a
public HTTP endpoint. Options, cheapest first:

1. **Shared service token** — `SEED_SERVICE_TOKEN` (secret in the Worker's env + the trigger's
   env), checked before any DO hop. Simple, but rotation is manual and a leaked token lets any
   party forge profiles.
2. **Firebase ID token for a service account** — the trigger mints a custom token / `Admin SDK`
   token (`getAccessToken`) for a dedicated service account; the Worker verifies it against the
   project's JWKS via the same verifier the Worker already runs, pinning the expected
   `serviceAccounts/{name}` subject. Stronger, more moving parts (subject allow-list).
3. **Private Cloudflare route between the trigger and the DO** — there is no shared private
   network between Firebase Functions and the Worker, so this option does not exist here.

Recommendation: **option 2** (no static shared secret to leak; reuses the deployed verifier;
subject-restricted to one service account). If the trigger is approved at all.

## Settle-before-seed (the bounded race)

A match can in principle settle before its snapshot lands (create → 18-round fast game → settle
in well under a minute; the trigger fires in milliseconds but is not transactional with creation).
Worst case: the settle refuses `failed-precondition` with nothing written — **atomicity is
never violated**, the client simply retries and succeeds once the seed lands. Two mitigations,
for the owner's choice:

- accept the retry (documented in the runbook: "if settle fails with `failed-precondition`,
  retry after the seed lands"); or
- a tiny, idempotent sweeper callable (`repairMatchProfiles(matchId)`), admin-gated, callable by
  support when a match reports missing profiles — a support tool, not the authority path.

Neither changes anything about the settlement's own invariants.

## Failure & observability

- Seed fails (Worker down, token rejected, DO error): nothing is written; the trigger logs
  structured lines; the affected match fails settle closed. The sweeper (if approved) repairs.
  Monitor: count of `failed-precondition` with "no profile" on the DO path should be ~0 in
  steady state.
- Trigger retries: second-generation triggers ack on completion; a failing trigger retries per
  platform policy — idempotent upserts make retries safe.
- **Never** elevate a seed failure into a Firestore write; the DO path stays authoritative.

## Scope boundaries (what this note does NOT decide)

- **No revision of the settlement logic** — the fold, the gate, the once-only claim: untouched.
- **No migration** of pre-DO matches: matches created before the DO path exists settle through
  Firebase as always; the seed applies going forward only. One-way door, by design.
- **No mid-match resync.** A profile repaired in Firestore while a match is in flight is NOT
  visible to the DO for that match (seed is a match-start snapshot). Record this in the
  settlement runbook when it ships.
- **No bot profiles.** Bot seats are skipped by `readSeatInputs(onlyUids = players)` in the
  stats-only path and by the same seat filter in the ranked fold's `readSeatInputs(tx, fields,
  parsed.order, matchId)` — the seed need only carry **human** seats. (Verify seat→bot detection
  uses the document's `players` list, as the fold does, before shipping.)
- **No deploy** of the trigger or the Worker route as part of this note; deployment is a separate
  owner gate (below).

## Test plan (for the implementation step, once the note is approved)

- **Unit (DO):** `/rpc/seedProfiles` — happy path upsert, idempotent re-seed, `uid` validation,
  malformed payload → 400-style refusal, non-matchId path → 404; via the same `node:sqlite`
  harness `DoStorageTest` uses. The upsert statement itself is already pinned in Step 2.
- **Unit (trigger):** profiles read with Admin stubbed; bot seats skipped; calls the route with
  the service token — via the Functions test harness pattern already used in `:functions:jsTest`.
- **Integration (existing worker harness):** the `node --test` suite in `native/worker` grows a
  `seed-e2e`-style case only after the route exists; `functions-bundle`/`manifest` contracts
  unchanged (the seed route is **not** a callable export and must not appear in `index.js` or
  `manifest.js`).
- **End-to-end acceptance for the milestone:** create match on the DO path → seed →
  `settleOnce` → claim + profile writes verified → replay exactly-once → corrupt-profile
  fail-closed. This is the merge-gate evidence the O-06 RP Port Design asks for.

## Decisions required from the owner (blocking)

1. **Go for option A** (onDocumentCreated trigger → `/rpc/seedProfiles`)? Or B + accepting a
   guaranteed-closed client-start path? (A is the recommendation; B is strictly weaker.)
2. **Service-to-service auth:** option 2 (service-account token through the existing verifier,
   subject-restricted) — approved? Or option 1 (shared token) with a rotation policy?
3. **Settle-before-seed policy:** accept the retry (runbook note), or approve the
   `repairMatchProfiles` sweeper?
4. **Deploy gates:** the trigger needs a Firebase deploy; the Worker needs its route deployed.
   Both are owner-approved releases — this note does not authorize them.
5. **Milestone call:** does this seeding **unblock** merging PR #199 to `main` (with the full
   review), or is there another gate (e.g. Step 3 ledger) first?
6. **Bot seats** — confirm the seed skips bots (only human `players[]` seats are seeded). I read
   the fold as enforcing this everywhere; a one-line confirmation keeps it from drifting.

## Effort & sequence (non-committal until the decisions land)

Note-approval → Worker/DO route + trigger implementation (≈1 day) → unit suites green →
`node --test` grows the seed case → one DO-path create-to-settle proof → review → owner decides
on deploy → **then** re-evaluate merging PR #199 to `main`.

## Explicitly not happening under this note

No code, no schema changes, no deploys, no merges — not until the decisions above are recorded
in this file or a decision-record note underneath it.
