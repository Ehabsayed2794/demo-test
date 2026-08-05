# Firestore Data Model

Design only — nothing in this document is deployed. Field names below intentionally mirror the existing `GameSession` shape (`design-ui/engine/session.js`) wherever a match/round/bid/trick concept already exists offline, so the eventual migration is a relocation of data, not a redesign of it.

**One consolidation decision up front:** the brief lists `players` and `profiles` as separate example collections. This design merges them into a single `players/{uid}` document. Two collections holding the same entity invites drift (which one is the source of truth for `displayName`?) for no benefit at this scale — see `ArchitectureDecisionLog.md`.

---

## `players/{uid}`

- **Purpose:** One document per Firebase Auth user. The profile *and* identity record — created once, on first successful sign-in.
- **Document ID:** the Firebase Auth `uid` (not an auto-ID) — guarantees at most one profile per identity and makes "does this user have a profile yet" a single `get()`, not a query.
- **Fields:**
  - `displayName: string`
  - `accountType: "guest" | "full"` (mirrors the `account.type` already produced by the Login screen)
  - `email: string | null`
  - `avatarInitial: string` (derived, not stored redundantly-authoritative — see `PlayerLifecycle.md`)
  - `rank: string`, `rp: number` (rank points — matches the mock shape in `session.js`'s `mockPlayers()`: `rank`, `rp`)
  - `wins: number`, `streak: number`, `level: number`
  - `coins: number`, `gems: number` (soft currency balances — see `ServiceArchitecture.md`'s note on why balances live here, not only in `inventory/{uid}`)
  - `createdAt: timestamp`, `lastSeenAt: timestamp` (the heartbeat field used for presence — see `PlayerLifecycle.md` and the Presence design in `RoomLifecycle.md`)
  - `currentRoomId: string | null`, `currentMatchId: string | null` (denormalized pointers so a client can find "am I already in something" in one read instead of a query — see Reconnection Strategy)
- **Relationships:** referenced by `uid` from `rooms/{roomId}.seats[]`, `matches/{matchId}.players[]`, `inventory/{uid}`, `leaderboards/{seasonId}/entries/{uid}`, `friends/{uid}`.
- **Read frequency:** High — read on every screen that shows a player's name/avatar/rank/currency (Lobby topbar, Room roster, Match seats). Mitigate with the client SDK's local cache; a player's own profile changes rarely mid-session.
- **Write frequency:** Low — currency/rank/stat changes happen at most once per completed match, plus rare profile edits (display name change).
- **Security requirements:** a user may read any `players/{uid}` document (needed to render opponents' names/ranks in a room/match) but may only write their **own** document (`request.auth.uid == uid`), and — critically — may not write `coins`, `gems`, `rank`, `rp`, `wins` themselves; those fields must only change via the match-completion write path (see `SecurityArchitecture.md`). This is the exact class of hole the Firestore rules audit already found once (any guest could write any match) — this schema is designed so that mistake can't recur in a new shape.

## `rooms/{roomId}`

> **Updated in Sprint 3.2.1 to match the actual shipped implementation** (`design-ui/room-service.js`, built in Sprint 3.2). The original draft below this note was written during the pre-implementation "design only" phase and speculated a shape (`hostUid`, a fixed-length `seats[]`) that was never built. Per Sprint 3.2.1's explicit instruction ("do NOT refactor code to match old docs; update docs to match working code"), this section now documents what actually exists. See `docs/implementation/RoomFoundation.md` and `docs/implementation/RoomSecurityFix.md` for the full history of this change.

- **Purpose:** A Play-with-Friends lobby prior to a match starting — create, join by ID, leave.
- **Document ID:** auto-generated Firestore ID (unlike the original draft's "short human-typeable code" idea — `createRoom()` uses `db().collection("rooms").doc()`, an auto-ID, and the resulting `roomId` is shared with the person joining out-of-band, e.g. via the `alert()` shown in Lobby today).
- **Fields (current, actual):**
  - `creator: string` — the `uid` of the player who created the room. Ownership transfers to another member (see `RoomLifecycle`/`RoomFoundation.md`) if the creator leaves while others remain.
  - `players: string[]` — an array of member `uid`s, capped at 4 (`room-service.js`'s `MAX_PLAYERS` constant — an implementation detail, not a previously-agreed schema value).
  - `status: "waiting" | "closed"` — `"waiting"` from creation; `"closed"` once the last player leaves. (`"ready"`/`"starting"` from the original draft do not exist yet — Ready state is explicitly out of scope through Sprint 3.2.1.)
  - `name: string | null` — an optional display label (Lobby currently auto-fills `"<DisplayName>'s Room"`).
  - `createdAt: timestamp`, `updatedAt: timestamp` (server timestamps, stamped/refreshed on every write).
- **Fields removed/deprecated — DO NOT implement against these:**
  - ~~`hostUid`~~ → use `creator`.
  - ~~`seats: [{ uid, ready, isAI }]`~~ → use the flat `players: string[]` array. Per-seat `ready`/`isAI` tracking doesn't exist yet — out of scope until a future Ready-state sprint.
  - ~~`matchId`~~, ~~`expiresAt`~~ → not yet implemented; no match-creation or TTL-cleanup code exists yet. Revisit when those features are actually built, not speculatively now.
- **Relationships:** `creator` and each entry in `players[]` → `players/{uid}`.
- **Read frequency:** Low today — nothing yet polls or listens to a room after creation/join (`subscribeToRoom()` remains an unimplemented stub); reads happen only at the moment of `create`/`join`/`leave`.
- **Write frequency:** Low — one write per create/join/leave.
- **Security requirements (as actually deployed — see `firestore.rules` and `docs/implementation/RoomSecurityFix.md` for the full reasoning):**
  - `read`: any authenticated user (`request.auth != null`) — the narrower "only a seated player" optimization from the original draft was explicitly skipped as unnecessary for this stage.
  - `create`: only if `request.resource.data.creator == request.auth.uid`, with type/shape validation (`creator` is a string, `players` is a list containing the creator, `status` is a string).
  - `update`: allowed if the acting user is present in **either** the pre-write or the post-write `players[]` — deliberately broader than "only a seated player," specifically so a new member can add themself (join). A user absent from both lists still cannot write anything. See `RoomSecurityFix.md` for why the narrower version (checking only the pre-write array, as literally specified in Sprint 3.2.1's brief) would have made joining impossible.
  - `delete`: always denied — rooms close via `status: "closed"`, never via document deletion.

## `matches/{matchId}`

- **Purpose:** The single authoritative live-match document — the multiplayer equivalent of today's local `GameSession` object. One document holds the *entire* match state; this is a deliberate flat-document choice over subcollections (see rationale below).
- **Document ID:** auto-generated Firestore ID (no external meaning needed, unlike room codes).
- **Fields** (directly modeled on `freshSession()`/`freshBiddingState()`/`freshPlayState()` in `session.js`):
  - `mode: "friends" | "ranked" | "ai"`, `scoringMode: "normal" | "classic"`
  - `players: [{ uid, name, isAI, seat }]` (4 entries, seat-ordered)
  - `dealerId: string`
  - `round: { number, maxRounds, multiplier, trump, callerId, withPlayers, estimates, dashCallers }`
  - `turnId: string`
  - `hands: { [uid]: Card[] }` — **see the security note below; this field is the one genuinely hard problem in this schema.**
  - `dealState: { roundNumber, completed, dealtAt }`
  - `playState: { roundNumber, phase, trickNumber, leaderId, turnId, ledSuit, currentPlays, tricksWon, voids, lastTrick, completed }`
  - `biddingState: { roundNumber, phase, turnId, bids, activeBidders, auctionTop, auctionSuit, callerId, withPlayers, declaredTrump, estimates, dashCallers, riskPlayerId, actionHistory }`
  - `matchScores: { [uid]: number }`, `roundHistory: [{ round, trump, callerId, tricksWon, estimates }]`
  - `winnerId: string | null`, `startedAt: timestamp`, `updatedAt: timestamp` (for staleness/abandonment detection — see `MatchLifecycle.md`)
- **Relationships:** `players[].uid` → `players/{uid}`; created from a `rooms/{roomId}` (friends/ranked) or directly (AI — though AI matches likely never need a Firestore document at all, see the note in `MigrationPlan.md` about which sprints need this collection at all).
- **Read frequency:** Very high while a match is live — every seated client holds an open `onSnapshot` listener on this one document for the whole match duration (typically 15-30 min). This is the single biggest Firestore quota consumer in the whole system — see the quota risk in the Risks section of `BackendArchitecture.md`'s companion risk file, and the mitigation (batch per-trick writes, not per-card, where the rules doc allows it).
- **Write frequency:** Medium-high — roughly one write per accepted bid/pass/estimate/card-play/trick-resolution. Four players × 13 tricks × (bid phase + play phase) per round × up to 18 rounds is the theoretical ceiling; in practice most matches end well before round 18.
- **Security requirements:** this is the collection where client-authoritative-with-rules is hardest to get fully right (see `SecurityArchitecture.md`'s dedicated section on `matches`). The critical field is `hands` — see below.

  **The `hands` problem, stated plainly:** if `hands` is a field on the match document that all four seated players can read (which they need to, to know their *own* hand), then by construction anyone can read everyone's hand — there is no way to hide opponents' cards from each other using client-authoritative Firestore rules alone, because rules can't redact individual fields differently per reader within one document. Two options, both worth stating honestly rather than hiding the limitation:
  1. **Split hands into a subcollection** `matches/{matchId}/hands/{uid}`, each doc readable only by its own `uid` — this *does* solve hiding opponents' hands, at the cost of 4 extra documents and listeners per match.
  2. **Accept the limitation during the Spark/client-authoritative phase** (hands are technically readable via direct Firestore console/API access by a determined cheater, though not through the normal UI) and close it properly once Cloud Functions can deal hands server-side and never send them to the wrong client at all.

  **Recommendation:** option 1 (split subcollection) — it's a schema decision, not a Cloud Functions dependency, so it's fully achievable on Spark today and meaningfully raises the bar against casual cheating, even though it isn't a complete fix until server-dealt hands arrive later.

## `leaderboards/{seasonId}/entries/{uid}`

- **Purpose:** Denormalized ranked standings for one season, one entry per player.
- **Document ID:** `seasonId` for the parent (e.g. `"2026-s1"`), `uid` for each entry — lets a client read "my rank" with one `get()` and the top-N with one bounded query.
- **Fields:** `rp: number`, `rank: string`, `wins: number`, `matchesPlayed: number`, `lastUpdatedAt: timestamp`.
- **Relationships:** `uid` → `players/{uid}` (this collection is a read-optimized denormalization of data that also lives on the player profile — see the honest limitation noted in `MigrationPlan.md` about why this is soft-integrity-only until Cloud Functions can write it authoritatively).
- **Read frequency:** High for the top-N leaderboard view; low per-entry otherwise.
- **Write frequency:** Low — once per completed ranked match, per participant.
- **Security requirements:** any authenticated user may read; a user may only write their **own** entry, and rules should clamp the write to plausible deltas (e.g. RP change bounded to the match's actual stakes) — acknowledged as an approximation, not a guarantee, until this migrates to Cloud Functions (see `MigrationPlan.md`).

## `inventory/{uid}`

- **Purpose:** Owned cosmetics/items, separate from the currency *balance* (which lives on `players/{uid}` for single-read topbar rendering).
- **Document ID:** `uid`.
- **Fields:** `items: [{ itemId, acquiredAt, source: "shop"|"reward"|"season" }]`, `equipped: { avatarFrame, cardBack, tableTheme }`.
- **Relationships:** `items[].itemId` → `shop/{itemId}`.
- **Read frequency:** Low-medium — read when opening Shop or a cosmetics picker.
- **Write frequency:** Low — one write per acquisition.
- **Security requirements:** owner-only read/write; write must validate the item exists in `shop/{itemId}` and (once purchases are real) that a matching `transactions/{txId}` exists — soft-currency purchases can be rules-validated now; real-money purchases cannot (see `SecurityArchitecture.md` and the IAP note in `MigrationPlan.md`).

## `shop/{itemId}`

- **Purpose:** Catalog of purchasable/unlockable items. Read-heavy, essentially static reference data.
- **Document ID:** a stable slug (e.g. `"cardback-gold-01"`).
- **Fields:** `name`, `description`, `priceCoins`, `priceGems`, `priceRealMoney` (nullable — unused until real IAP exists), `category`, `active: boolean`.
- **Relationships:** referenced by `inventory/{uid}.items[].itemId` and `transactions/{txId}.itemId`.
- **Read frequency:** High but cacheable — catalog data changes rarely; client SDK caching keeps this cheap.
- **Write frequency:** Near zero — an admin/owner operation, not a player one.
- **Security requirements:** public read; write restricted to a designated admin uid (checked via a custom claim or a hardcoded allow-list in rules — no Cloud Functions needed for this).

## `transactions/{txId}`

- **Purpose:** Audit trail of currency/item movement — soft-currency spends now, real-money purchases later.
- **Document ID:** auto-generated.
- **Fields:** `uid`, `type: "purchase"|"reward"|"match_payout"`, `itemId | null`, `amountCoins | null`, `amountGems | null`, `createdAt`.
- **Relationships:** `uid` → `players/{uid}`; `itemId` → `shop/{itemId}`.
- **Read frequency:** Low — a player's own transaction history view, if built.
- **Write frequency:** Low-medium, one per purchase/reward/payout.
- **Security requirements:** owner-only read; **write should be as narrow as rules can make it** — this is the collection most worth migrating to Cloud-Functions-only writes early, since a client that can write its own transaction history can also fabricate one. Flag this explicitly in `SecurityArchitecture.md` as a known soft spot during the Spark phase.

## `friendRequests/{requestId}`

- **Purpose:** Pending friend invitations.
- **Document ID:** auto-generated.
- **Fields:** `fromUid`, `toUid`, `status: "pending"|"accepted"|"declined"`, `createdAt`.
- **Relationships:** `fromUid`/`toUid` → `players/{uid}`.
- **Read frequency:** Low — checked on a notifications/friends screen.
- **Write frequency:** Low.
- **Security requirements:** `fromUid` may create; only `toUid` may update `status`; either party may read their own requests (rules query on `fromUid == request.auth.uid || toUid == request.auth.uid`).

## `friends/{uid}/list/{friendUid}`

- **Purpose:** Accepted friend relationships, stored per-user for cheap "my friends list" reads (subcollection, not a single global collection scanned by query).
- **Document ID:** the friend's `uid`, nested under the owning user.
- **Fields:** `since: timestamp` (profile data is looked up live from `players/{friendUid}`, not duplicated here — avoids stale-name drift).
- **Relationships:** mirrored write — accepting a request writes to both `friends/{uidA}/list/{uidB}` and `friends/{uidB}/list/{uidA}`.
- **Read frequency:** Low-medium.
- **Write frequency:** Very low.
- **Security requirements:** owner-only read/write on their own subcollection.

## `seasonData/{seasonId}`

- **Purpose:** Season metadata — start/end dates, active ruleset, reward tiers. One document, read by everyone, written by nobody at runtime.
- **Document ID:** the season slug (matches `leaderboards/{seasonId}`).
- **Fields:** `startsAt`, `endsAt`, `rewardTiers: [{ minRp, reward }]`, `active: boolean`.
- **Read frequency:** Low, cacheable (Lobby's "Season of Sands" widget).
- **Write frequency:** Near zero — admin-only.
- **Security requirements:** public read, admin-only write.

## `dailyRewards/{uid}`

- **Purpose:** Tracks a player's daily-login streak and claim state.
- **Document ID:** `uid`.
- **Fields:** `lastClaimedAt: timestamp`, `streak: number`.
- **Read frequency:** Low — checked once per session on Lobby load.
- **Write frequency:** Low — at most once per day per player.
- **Security requirements:** owner-only read/write, with a rules-level check that `lastClaimedAt` isn't being rewritten to claim twice in one day — one of the few cases where a rules-only clamp (comparing `request.time` to the stored `lastClaimedAt`) genuinely works well without needing a server.

## `notifications/{uid}/items/{notificationId}`

- **Purpose:** In-app notifications (friend request received, reward available, match found).
- **Document ID:** subcollection per user, auto-ID per notification.
- **Fields:** `type`, `payload`, `read: boolean`, `createdAt`.
- **Read frequency:** Low-medium — polled or live-listened on Lobby.
- **Write frequency:** Low.
- **Security requirements:** owner-only read; **write should come from whatever produced the event, not the recipient** — e.g. the sender of a friend request writes the recipient's notification. This is a case to flag for the Cloud Functions migration (a function fan-out is the clean long-term shape); until then, rules must allow a *different* uid than the document owner to create it, which is a narrower and more carefully-scoped rule than most of this schema needs.
