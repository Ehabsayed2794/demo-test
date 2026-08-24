# Architecture Decision Log

Design only — a running record of the specific calls made in this design sprint, and why, so a future session (or a future you) doesn't have to re-derive the reasoning from scratch. New entries should be appended, not edited in place, as the design evolves.

---

### ADL-001 — Client-authoritative-with-rules now; contract-first for server authority later

**Decision:** Game logic runs client-side and writes directly to Firestore during the Spark phase, gated by Security Rules. Every mutating action is expressed as a fixed-signature service method (`ServiceArchitecture.md`) so its *implementation* can move to a Cloud Function later without changing any caller.
**Why:** Cloud Functions cannot be deployed on Spark at all, at any usage level — this isn't a cost optimization, it's a hard platform constraint. The contract-first shape is what makes the eventual migration a relocation of logic, not a rewrite.
**Revisit when:** Ranked Match is about to leave soft-launch, or the Firestore quota signals in `MigrationPlan.md` fire.

### ADL-002 — Merge `players` and `profiles` into one `players/{uid}` collection

**Decision:** The brief listed `players` and `profiles` as separate example collections; this design uses one.
**Why:** Two collections holding the same entity (identity + profile) invites drift — which one is authoritative for `displayName`? — with no benefit at this project's scale. A single document keyed by `uid` is simpler to reason about and cheaper to read (one `get()` instead of a join-like double read).
**Revisit when:** never expected to — this is a permanent simplification, not a placeholder.

### ADL-003 — Heartbeat presence (Firestore field) instead of Realtime Database `onDisconnect`

**Decision:** Presence is tracked via a `lastSeenAt` field on `players/{uid}`, refreshed on an interval, with staleness computed by observing clients — not via Realtime Database's server-detected disconnect event.
**Why:** RTDB's `onDisconnect` is more precise and is itself Spark-compatible, but running two databases (Firestore + RTDB) for one feature adds real operational and mental overhead. Fewer moving parts was weighted above precision at this stage.
**Revisit when:** reconnection UX proves too laggy in practice with the heartbeat interval. This is a low-cost, Spark-compatible change if revisited — it does not require the Blaze migration, unlike most items in `MigrationPlan.md`.

### ADL-004 — Split match hands into a `matches/{matchId}/hands/{uid}` subcollection

**Decision:** Rather than storing all four players' hands as one field on the match document (readable by all four, by construction), split hands into a subcollection where each document is readable only by its own `uid`.
**Why:** Firestore rules can't redact individual fields differently per reader within one document — a single `hands` field on the match doc has no way to hide opponents' cards from each other. The subcollection split is achievable entirely within the Spark/rules-only constraint and meaningfully raises the bar against casual cheating, even though it isn't a complete fix (a sufficiently determined actor could still query the collection directly) until server-dealt hands arrive with the Cloud Functions migration.
**Revisit when:** dealing moves to Cloud Functions (`MigrationPlan.md` item 4) — at that point the subcollection design is what makes "server writes each seat's hand directly to only that seat's document" a natural fit rather than a rework.

### ADL-005 — Firestore native TTL over Scheduled Functions for room/match cleanup

**Decision:** Stale rooms and completed/archived matches expire via Firestore's built-in TTL policy on a timestamp field, not a Cloud Scheduler-triggered function.
**Why:** Scheduled Functions require Blaze; TTL policies are a core Firestore feature, free on Spark, and require no function code at all.
**Revisit when:** never expected to — TTL remains the right tool for this even after the Blaze migration; there's no reason to replace it with a function later.

### ADL-006 — `transactions/{txId}` is the first collection migrated to Cloud-Functions-only writes

**Decision:** Of everything flagged as a "soft enforcement" gap in `SecurityArchitecture.md`, transaction records are migrated first once Blaze is available — ahead of gameplay legality, ahead of stat deltas.
**Why:** It's the cheapest gap to close (a single collection, no game-engine logic to port) and, while currently low-blast-radius (soft currency only), is the collection where "a client can write a record claiming something happened" is most directly exploitable in the literal sense of the word.
**Revisit when:** N/A — this is the migration plan itself, tracked in `MigrationPlan.md`.

### ADL-007 — Real-money IAP is deferred past the Blaze migration entirely, not just past Spark

**Decision:** `InventoryService.purchaseWithRealMoney` is not even given an interface definition in `ServiceArchitecture.md` yet.
**Why:** This is the one feature in the whole system that matches the original constraint's explicit carve-out ("if a paid service is unavoidable...") — payment receipt validation fundamentally requires a trusted server with no safe client-side substitute, unlike everything else in this design, which has a working (if imperfect) client-authoritative interim.
**Revisit when:** the Blaze migration is underway and Cloud Functions exist to validate receipts server-side.

### ADL-008 — Dealing remains client-authoritative during the Spark phase, flagged as a soft-launch-only limitation

**Decision:** `MatchService`'s deal step runs client-side (whichever client happens to deal) rather than being blocked on server-side dealing.
**Why:** Server-authoritative dealing requires Cloud Functions (a trusted party who deals must not be a participant who could peek). Blocking all of multiplayer on this one capability would delay Play-with-Friends (low cheating incentive between friends) for a risk that mainly matters for Ranked.
**Revisit when:** Ranked Match leaves soft-launch — same trigger as ADL-001, tracked together in `MigrationPlan.md`.

### ADL-009 — `design-ui/` is the primary production product

**Decision:** `design-ui/` is the primary production product and target production codebase for the Estimation multiplayer game. `src/` is legacy/transitional score-tracker code and is not the target architecture for multiplayer functionality. Future multiplayer, gameplay, UI, lifecycle, persistence, synchronization, and deployment work must target `design-ui/`.

**Why:** `design-ui/` contains the existing multiplayer rules engines, `GameSession`, room/match services, Firebase integration, Firestore security model, synchronization adapters, lifecycle transitions, and focused regression coverage. `src/` is a separate manual score tracker with a separate state and scoring model; migrating the multiplayer system into it would duplicate or discard the existing multiplayer architecture and create a second rules/state implementation. The product decision is therefore based on accumulated engineering investment and product scope, not on the current root build entry point.

**Consequences:** The current root Vite build is transitional and must be changed in a dedicated integration task so the released artifact builds and ships `design-ui/`. The `design-ui/` render/navigation layer, Firebase dependency packaging, environment configuration, synchronization wiring, lifecycle UX, real emulator/browser validation, and deployment verification remain unfinished. `src/` must not receive new multiplayer functionality; it may receive only explicitly scoped legacy maintenance, compatibility, migration, or deprecation work. It must not be deleted or reset as part of unrelated gameplay work.

**Migration/deprecation strategy:** First make `design-ui/` the selected build/deployment artifact and provide a clean-checkout smoke test. Then identify any supported users of the score tracker and record whether it remains as a legacy route, is archived, or is deprecated. Remove `src/` only in a later, separately approved cleanup task after no supported workflow depends on it.

**Revisit when:** Only through an explicit product-direction decision. Implementation convenience, root build inertia, or a new UI framework must not silently reverse this decision.
