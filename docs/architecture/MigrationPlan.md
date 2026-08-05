# Spark → Blaze Migration Plan

Design only. The goal stated in the brief — "upgrading later changes only the backend implementation; UI and game engines stay untouched" — is the test every decision in this document is measured against.

## What stays client-authoritative during the entire Spark phase

Everything in `ServiceArchitecture.md` runs as client code writing directly to Firestore, gated by the rules in `SecurityArchitecture.md`. This covers: room management, match state transitions, bidding/play actions, soft-currency purchases, friend requests, daily rewards, notifications.

## What Security Rules enforce during this phase (and what they can't)

See `SecurityArchitecture.md`'s dedicated "strong vs. soft" section — repeating the summary here because it's the crux of this whole migration plan: rules give hard guarantees for ownership and structural checks, and only soft approximations for full game-legality validation, stat-delta correctness, and transaction authenticity.

## What moves to Cloud Functions later, in priority order

Not "everything, eventually, all at once" — a specific, staged order, driven by where the soft-approximation risk is actually highest:

1. **`transactions/{txId}` writes.** The clearest integrity gap (a client can fabricate its own transaction record) with the smallest current blast radius (soft currency only, no real money yet). First to migrate precisely because it's cheap to migrate and closes a real hole.
2. **`MatchService.resolveTrick` and `submitBid`/`playCard` legality checks.** Move the actual `TableEngine`/`BiddingEngine` execution into a callable Cloud Function that becomes the *only* writer to `matches/{matchId}`'s live-play fields. This is the change that turns "client-authoritative-with-rules" into true server authority for gameplay.
3. **`PlayerService.applyMatchResult` / `LeaderboardService.submitRankedResult`.** Once trick/round resolution is server-side, these can be re-derived from the authoritative match document server-side too, instead of trusting a client-submitted delta.
4. **Dealing (`MatchService`'s deal step in `MatchLifecycle.md`).** Move card shuffling/dealing into a Cloud Function so no client ever sees a hand before it's dealt to the right seat — this is also the point where the `matches/{matchId}/hands/{uid}` split-subcollection design from `FirestoreSchema.md` gets its full fix (server writes each seat's hand directly to their own subcollection; no client ever reads another seat's).
5. **Real-money IAP (`InventoryService.purchaseWithRealMoney`).** Deliberately last, and deliberately not even interface-defined yet in `ServiceArchitecture.md` — this is the "absolutely no alternative" case from the original constraint (payment validation fundamentally needs a trusted server), so it's correctly gated behind the Blaze migration entirely, not just deprioritized within it.
6. **Presence, if the heartbeat approach (see `RoomLifecycle.md`) proves too coarse in practice.** Note this one doesn't actually require Blaze — Realtime Database's `onDisconnect` is Spark-compatible. Listed here only because it's a natural point to reconsider once other things are moving to Cloud Functions anyway.

## Why this order and not another

Each item above was chosen by asking "where is the soft-enforcement gap in `SecurityArchitecture.md` actually exploitable, and how expensive would that exploit be" — not by ease of implementation. Transactions first because it's a real (if small) hole that's cheap to close. Gameplay legality second because it's the largest remaining integrity gap and the one that matters most before Ranked Match can be trusted. IAP last because it's gated by an external hard requirement (payment processing), not by priority.

## The concrete trigger to actually flip to Blaze

Not "whenever it feels right" — three concrete signals, any one of which should trigger the migration:

1. **Firestore usage dashboard** (visible for free on Spark) shows daily reads/writes consistently approaching the free quota (50k reads / 20k writes / 20k deletes per day) during real beta traffic — a leading indicator that the free tier's ceiling, not just its cost-avoidance benefit, is becoming the actual constraint.
2. **Ranked Match is about to leave soft-launch.** Per `SecurityArchitecture.md`'s explicit call-out, rules-only legality checking is a soft-launch-acceptable, ranked-launch-blocking gap — real ranked stakes are the trigger, not a calendar date.
3. **Real-money IAP is about to ship.** Hard requirement, not a judgment call — see item 5 above.

## What does NOT change when the migration happens

This is the part worth stating explicitly, since it's the actual point of designing this way now:

- `Card Engine`, `Bidding Engine`, `Table Engine`, `Scoring Engine` — unchanged. The same pure functions, called from inside a Cloud Function instead of from a browser tab.
- Every `ServiceArchitecture.md` method signature — unchanged. `MatchService.playCard(matchId, uid, cardId)` looks identical to its caller whether it resolves via a direct Firestore write today or a callable Cloud Function tomorrow.
- The Firestore schema itself (`FirestoreSchema.md`) — unchanged in shape. Fields that were soft-enforced by rules become hard-enforced by function logic instead; no field gets renamed or restructured.
- The UI (Lobby, Room, Match screens) — unchanged. They already only ever call service-layer functions, never touch Firestore directly (matching the `session.js` discipline this whole design extends).

What *does* change: the `SecurityArchitecture.md` rules for the migrated collections get **simpler**, not more complex — once a Cloud Function is the only writer to `matches/{matchId}`'s live fields, the rule for those fields collapses to "clients may read, only the Cloud Functions service account may write," replacing the more elaborate per-field, per-turn rules logic this design currently needs.
