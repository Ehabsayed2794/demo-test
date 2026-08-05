# Service Layer Skeleton — Sprint 2.7 Implementation Report

**Scope actually implemented:** empty, API-stable service modules only. No Firestore logic, no business logic, no gameplay logic, no networking, no listeners, no transactions, no reads, no writes, no new Firestore collections. `PlayerService` (Sprint 2.6) is the only service with real implementation behind it — everything below is intentionally a stub.

## Purpose

This sprint exists to lock down **method signatures**, not behavior. Once these seven modules exist with stable public APIs, future sprints (Rooms, Match sync, Presence, Shop, Leaderboards, Analytics) implement the *inside* of these functions without any caller elsewhere in the codebase needing to change — the same principle already established for `PlayerService` in Sprint 2.6, now extended to every other planned service.

## Files created

| File | Global | Status |
|---|---|---|
| `design-ui/room-service.js` | `window.RoomService` | Stub |
| `design-ui/match-service.js` | `window.MatchService` | Stub |
| `design-ui/presence-service.js` | `window.PresenceService` | Stub |
| `design-ui/inventory-service.js` | `window.InventoryService` | Stub |
| `design-ui/leaderboard-service.js` | `window.LeaderboardService` | Stub |
| `design-ui/shop-service.js` | `window.ShopService` | Stub |
| `design-ui/analytics-service.js` | `window.AnalyticsService` | Stub |

No existing file was modified. `design-ui/player-service.js`, `firebase-init.js`, `login/index.html`, `lobby/index.html`, `GameState`/`GameSession`, and every file under `design-ui/engine/` are untouched — verified via `git status`/`git diff` (see Tests below).

## Stub policy — throw vs. safe placeholder, and why

The brief allows either "throw Not implemented" or "return a safe placeholder" per method. Rather than picking one for everything, this implementation uses a deliberate, documented split:

- **Default: throw `new Error("<Service>.<method>() is not implemented yet")`.** This is correct for anything that *changes* state (create/join/submit/etc.) — a caller that thinks it successfully created a room when nothing happened would be a much worse bug than a caller that gets a clear, immediate error.
- **Exception 1 — `subscribeTo*` methods** (`RoomService.subscribeToRoom`, `MatchService.subscribeToMatch`, `PresenceService.subscribeToPresence`): return a callable no-op `unsubscribe` function instead of throwing, with a `console.warn`. A caller that does `var unsub = subscribeToX(...)` and later calls `unsub()` for cleanup should never crash just because the feature underneath isn't built yet — throwing here would make even *setting up* a screen that will eventually use these impossible to write defensively against.
- **Exception 2 — `ShopService.getCatalog()` / `getItem()`**: resolve to `[]` / `null` instead of throwing. These are read-only catalog lookups; a Shop screen built against this stub can render an empty/"coming soon" state rather than crashing on load.
- **Exception 3 — `AnalyticsService.logEvent()` / `setUserProperties()`**: no-op entirely (a `console.debug`, nothing else), never throw, under any circumstance. Analytics calls are expected to be sprinkled through gameplay code later; a tracking call must never be able to break the thing it's tracking.

All three exceptions are logged (`console.warn`/`console.debug`) so their placeholder nature is visible in DevTools during development, without being disruptive to whatever screen is calling them.

## Per-service detail

### RoomService
**Current status:** stub. **Future responsibilities:** the full state machine in `docs/architecture/RoomLifecycle.md` — create/join/ready/leave/host-transfer/close, backed by `rooms/{roomId}` with transaction-guarded seat-claiming. **Dependencies:** `PlayerService` (to resolve seated `uid`s to profiles), the room-lifecycle rules in `docs/architecture/SecurityArchitecture.md` (not yet written for `rooms`, since only `players` rules exist so far). **Migration to Blaze:** none of `RoomService`'s methods are Cloud-Functions candidates per `docs/architecture/MigrationPlan.md` — room management stays client-authoritative-with-rules permanently.

### MatchService
**Current status:** stub. **Future responsibilities:** `docs/architecture/MatchLifecycle.md`'s full state machine, calling into the existing (untouched) `BiddingEngine`/`TableEngine`/`ScoringEngine` — this file's method names (`submitBid`, `playCard`, `resolveTrick`, etc.) were chosen to mirror `GameSession`'s existing `record*`/`complete*` calling convention exactly, so the future implementation is a relocation of already-working logic, not a rewrite. **Dependencies:** `PlayerService`, `RoomService` (a match is created from a room), the three gameplay engines. **Migration to Blaze:** `resolveTrick`, `submitBid`/`playCard`'s legality checks, and dealing (inside `createMatch`) are the highest-priority Cloud Functions migration candidates per `MigrationPlan.md` — see that document's staged order.

### PresenceService
**Current status:** stub. **Future responsibilities:** originally, `docs/architecture/RoomLifecycle.md` described presence as a `lastSeenAt` heartbeat field owned directly by `PlayerService` — this sprint's brief asks for a dedicated service module instead. **This is a minor architecture note, not a contradiction:** `PlayerService` still owns and writes `lastSeenAt` today exactly as implemented in Sprint 2.6; `PresenceService` is the future home for reading/subscribing to *other* players' presence (needed for Room rosters and reconnect UI), which was never `PlayerService`'s job in the first place. No existing behavior changes; this fills a gap the original architecture didn't name a dedicated owner for. **Dependencies:** `PlayerService`'s `lastSeenAt` field. **Migration to Blaze:** none required — heartbeat presence works permanently on Spark; see `ArchitectureDecisionLog.md` ADR-003.

### InventoryService
**Current status:** stub. **Future responsibilities:** `docs/architecture/FirestoreSchema.md`'s `inventory/{uid}` collection — ownership records separate from the currency balance on `players/{uid}`. **Dependencies:** `PlayerService` (currency checks), `ShopService` (validating a purchased `itemId` exists). **Migration to Blaze:** soft-currency purchases stay client-authoritative-with-rules; real-money purchases are explicitly out of scope for this entire service until the Blaze migration (see `MigrationPlan.md` item 5) — no method for that exists here yet, on purpose.

### LeaderboardService
**Current status:** stub. **Future responsibilities:** `docs/architecture/FirestoreSchema.md`'s `leaderboards/{seasonId}/entries/{uid}`. **Dependencies:** `PlayerService`, `MatchService` (a ranked match's result is the input to a leaderboard update). **Migration to Blaze:** `submitRankedResult`'s real implementation is a named Cloud Functions migration candidate in `MigrationPlan.md` (item 3) — client-submitted deltas are only a soft-enforced approximation until then.

### ShopService
**Current status:** stub (read-only placeholders, not throwing — see policy above). **Future responsibilities:** `docs/architecture/FirestoreSchema.md`'s `shop/{itemId}` catalog. **Dependencies:** none beyond Firestore itself (this is close to static reference data). **Migration to Blaze:** the catalog itself never needs Cloud Functions; real-money checkout (not part of this service's current or planned API) does, per `MigrationPlan.md`.

### AnalyticsService
**Current status:** stub (permanent no-op-on-failure policy — see above). **Future responsibilities:** not detailed in the original architecture documents (this is a new addition this sprint) — the intended shape is a thin wrapper so gameplay/UI code never calls a specific analytics SDK directly, matching this whole sprint's "call the service, not the vendor" principle. **Dependencies:** none yet — whichever analytics product gets chosen later (Firebase Analytics itself is Spark-compatible and free) plugs in behind this same two-method API. **Migration to Blaze:** not applicable — Firebase Analytics has no Blaze requirement at any usage level.

## Tests performed

See `TEST_CHECKLIST.md` in the QA package for full detail. Summary: 32 real automated tests executed against the actual service files (not a re-implementation) — every "default" method throws exactly the documented error, every subscription method returns a genuinely callable no-op, `ShopService`/`AnalyticsService` never throw under any input tried. A repo-wide `git status`/`git diff` confirms zero forbidden files were touched.
