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
  - `currentRoomId: string | null`, `currentMatchId: string | null` (denormalized pointers so a client can find "am I already in something" in one read instead of a query — see Reconnection Strategy). **Sprint 3.4.1:** `currentMatchId` is explicitly a same-user convenience mirror only, kept in sync by each client SELF-updating its own document (via `SessionService.setCurrentMatchId()`) after discovering a match through the authoritative source — `rooms/{roomId}.matchId` / `matches/{matchId}.players` — never written by any service on another player's behalf. See `docs/implementation/MatchInitialization.md`'s Sprint 3.4.1 section for the bug this closed (an earlier version of `MatchService` tried to write this field for every room player, which the owner-only rule below could only ever allow for the initiating player).
- **Relationships:** referenced by `uid` from `rooms/{roomId}.seats[]`, `matches/{matchId}.players[]`, `inventory/{uid}`, `leaderboards/{seasonId}/entries/{uid}`, `friends/{uid}`.
- **Read frequency:** High — read on every screen that shows a player's name/avatar/rank/currency (Lobby topbar, Room roster, Match seats). Mitigate with the client SDK's local cache; a player's own profile changes rarely mid-session.
- **Write frequency:** Low — currency/rank/stat changes happen at most once per completed match, plus rare profile edits (display name change).
- **Security requirements:** a user may read any `players/{uid}` document (needed to render opponents' names/ranks in a room/match) but may only write their **own** document (`request.auth.uid == uid`), and — critically — may not write `coins`, `gems`, `rank`, `rp`, `wins` themselves; those fields must only change via the match-completion write path (see `SecurityArchitecture.md`). This is the exact class of hole the Firestore rules audit already found once (any guest could write any match) — this schema is designed so that mistake can't recur in a new shape.

## `rooms/{roomId}`

> **Re-synced in Sprint 3.3** (previous sync: Sprint 3.2.1) to match the actual shipped implementation. The original pre-implementation draft speculated a shape (`hostUid`, a fixed-length `seats[]`) that was never built — see `docs/implementation/RoomFoundation.md`, `RoomSecurityFix.md`, and `ReadyStateFoundation.md` for the full history.

- **Purpose:** A Play-with-Friends lobby prior to a match starting — create, join by ID, ready up, leave.
- **Document ID:** auto-generated Firestore ID (unlike the original draft's "short human-typeable code" idea — `createRoom()` uses `db().collection("rooms").doc()`, an auto-ID, and the resulting `roomId` is shared with the person joining out-of-band, e.g. via the `alert()` shown in Lobby today).
- **Fields (current, actual):**
  - `creator: string` — the `uid` of the player who created the room. Ownership transfers to another member (see `RoomLifecycle.md`) if the creator leaves while others remain. Not fully immutable — see Security requirements below.
  - `players: string[]` — an array of member `uid`s, capped at 4 (`room-service.js`'s `MAX_PLAYERS` constant — an implementation detail, not a previously-agreed schema value). Exactly 1 (the creator) at creation time.
  - `readyPlayers: string[]` (Sprint 3.3) — the subset of `players` who have marked themselves ready via `RoomService.setReady()`. Starts empty at creation. **Not** a per-seat structure (no `{uid, ready}` objects) — a flat array parallel to `players`, matching this schema's established preference for simplicity over the original draft's per-seat model.
  - `status: "waiting" | "closed" | "in_game"` — `"waiting"` from creation; `"closed"` once the last player leaves; `"in_game"` (Sprint 3.4) once `MatchService.startMatch()` fires — see the new `matchId` field below, set in the very same write. (`"ready"`/`"starting"` from the original draft still do not exist as **room** statuses — there is no intermediate `WAITING → READY` room-status transition; `RoomService.setReady()` detects "everyone ready" directly from `readyPlayers` and calls `MatchService.startMatch()`, which flips `status` straight to `"in_game"`. `"starting"` DOES exist, but as the newly-created **match** document's own `status` field, not the room's — see `matches/{matchId}` below.)
  - `name: string | null` — an optional display label (Lobby currently auto-fills `"<DisplayName>'s Room"`). Immutable after creation — no rename capability exists.
  - `createdAt: timestamp` (immutable after creation), `updatedAt: timestamp` (refreshed on every write).
  - `matchId: string | null` (Sprint 3.4) — absent/`null` until every player readies up; set exactly once, together with `status` becoming `"in_game"`, by `MatchService.startMatch()`'s own transaction. Immutable once set — see `firestore.rules`' `isValidMatchIdChange()` and `docs/implementation/MatchInitialization.md`.
- **Fields removed/deprecated — DO NOT implement against these:**
  - ~~`hostUid`~~ → use `creator`.
  - ~~`seats: [{ uid, ready, isAI }]`~~ → use the flat `players: string[]` + `readyPlayers: string[]` arrays. `isAI` tracking doesn't exist yet — no AI-filled room slots are implemented.
  - ~~`expiresAt`~~ → still not yet implemented; no TTL-cleanup code exists yet (Sprint 3.2.5 Architecture Audit finding F8, still open — see `RoomLifecycle.md`'s "Room Expiration" section). (`matchId` — listed here as not-yet-implemented through Sprint 3.3 — **is now implemented as of Sprint 3.4**; see the new field entry above.)
- **Relationships:** `creator` and each entry in `players[]`/`readyPlayers[]` → `players/{uid}`.
- **Read frequency:** Low today — nothing yet polls or listens to a room after creation/join/ready (`subscribeToRoom()` remains an unimplemented stub); reads happen only at the moment of `create`/`join`/`leave`/`setReady`.
- **Write frequency:** Low — one write per create/join/leave/ready-toggle (idempotent no-op ready-toggles perform zero writes — see `room-service.js`'s `setReady`).
- **Security requirements (as actually deployed — see `firestore.rules` and `docs/implementation/ReadyStateFoundation.md` for the full reasoning; tightened in Sprint 3.3 per Sprint 3.2.5 Architecture Audit findings F3/F6/F7):**
  - `read`: any authenticated user (`request.auth != null`) — the narrower "only a member" optimization from the original draft was explicitly skipped as unnecessary for this stage.
  - `create`: requires `creator == request.auth.uid`, a full field whitelist (closing F6), `players.size() == 1` exactly (closing F7, was previously `>= 1`), `readyPlayers` must start empty, and `status` must start `"waiting"`.
  - `update`: field-whitelisted to `players`/`readyPlayers`/`status`/`creator`/`updatedAt`/`matchId` (`matchId` added Sprint 3.4; closing F3 — the Sprint 3.2.1 rule gated *who* could write but placed no limit on *what*). `players`/`readyPlayers` may each only change by the acting user adding/removing **themself** — never another member's entry, never a bulk edit. `creator` may only be reassigned to a current member. `status` may only be `"waiting"`/`"closed"`/`"in_game"`, only becomes `"closed"` when `players` is empty, and only becomes `"in_game"` from `"waiting"`. `matchId` may only go from absent/`null` to a string, and only in the exact same write that flips `status` to `"in_game"` — once set, it is immutable for the life of the document (see `isValidMatchIdChange()`). **Sprint 3.4.1 tightened `isValidMatchIdChange()` further**, closing a real gap the Sprint 3.4 version left open: it now ALSO re-checks (independently of whatever `MatchService`'s own JS already checked) that the acting user is genuinely a room member, that every player was genuinely ready before this write, and — via `getAfter()`, which reads the match document as it will exist once this same transaction/batch commits — that a real match document was actually created in the SAME write, with its own `roomId` and `players` correctly pointing back at this room. Without this, a client could flip `status`/`matchId` without ever creating a real, correctly-bound match. **Known, documented, unclosed gap (pre-existing, not new this sprint):** an existing member can still reassign `creator` to themself outside of an actual leave event — rules can't distinguish "part of a legitimate transfer" from "a standalone write" without Cloud Functions; see `ReadyStateFoundation.md`.
  - `delete`: always denied — rooms close via `status: "closed"`, never via document deletion.

## `matches/{matchId}`

> **Re-synced in Sprint 3.4 (Match Initialization & Game Start).** The **actual, shipped** document shape below is deliberately much smaller than the speculative full-gameplay draft that follows it — bidding/estimation/card-play are out of scope through this sprint, so none of `hands`/`dealState`/`playState`/`biddingState`/`matchScores`/`roundHistory`/`winnerId` exist yet. This is the same "re-sync the schema doc to reality, keep the rest as clearly-labeled forward design" treatment Sprint 3.3 already gave `rooms/{roomId}`. **Re-synced again in Sprint 3.8 (Gameplay Synchronization: Bidding Authority)** — five new fields (`seats`, `version`, `biddingOpen`, `bids`, `lastBidSeat`) and the first real update rule. Card-play/trick-resolution/scoring/turn-rotation remain exactly as speculative as before this sprint — only bidding-sync fields were added.

- **Purpose (as shipped today):** the match document `MatchService.startMatch()` creates the moment every player in a room readies up. Holds "a match exists, these players are in it, who owns which seat, and the current state of the bidding phase" — nothing about dealing or trick-play yet.
- **Document ID:** auto-generated Firestore ID (`db().collection("matches").doc()`), matching the original draft's expectation.
- **Fields (current, actual):**
  - `roomId: string` — the room this match was created from. One-directional pointer back to `rooms/{roomId}`; nothing on the room points forward except `matchId` (see above).
  - `players: string[]` — copied from the room's `players` array at the moment of creation. Flat `uid` array, **not** the speculative `[{ uid, name, isAI, seat }]` object-array shape below — no per-player object exists in the shipped schema; `seats` (below) carries the seat concept instead, as its own separate map.
  - `status: "starting"` — the only value that exists today; `MatchService`'s remaining gameplay methods (`playCard`/`resolveTrick`/etc. — `submitBid` is real as of Sprint 3.8) that would eventually transition it further are still stubs.
  - `createdAt: timestamp`.
  - `currentRound: 1` — the only value that exists today; nothing implements advancing it yet.
  - `dealer: string` — one of `players[]`; defaults to the room's `creator` (or `players[0]` if the creator is somehow absent). Not the speculative `dealerId` name — kept as `dealer` to match `match-service.js`'s actual field name.
  - `turn: string` — one of `players[]`; defaults to the same value as `dealer`. **Has no real meaning yet** — turn order/turn authority is explicitly out of scope through Sprint 3.8 ("DO NOT implement Turn Rotation after bidding"). A placeholder, not a claim that turn order is implemented.
  - `gameState: { initialized: false, todo: string }` — an explicit TODO placeholder, **not** real dealt hands. `Dealer.dealHands()` (`design-ui/engine/dealer.js`) depends on a global `Deck` object that does not exist anywhere in this repository (`deck.js` was never delivered — only referenced in `dealer.js`'s own header comment). See `docs/implementation/MatchInitialization.md` for the full finding. (Note: `Deck` was actually delivered in Sprint 3.5 — this placeholder simply hasn't been revisited since dealing itself remains out of scope for every sprint through 3.8.)
  - **`seats: { [seatId]: uid }`** — **new in Sprint 3.8, Task 1.** Implements `docs/architecture/SeatIdentityModel.md`'s design for real. Keys are a subset of `"p1"`/`"p2"`/`"p3"`/`"p4"`, one per real player — assigned positionally from `players[]` (`players[0] -> "p1"`, etc.) once, at creation, by `buildSeatMap()`, and never recomputed or changed afterward. **The only authority for "which uid owns which seat."** A 2- or 3-player match (this project's room system does not enforce a minimum of 4 — see `SeatIdentityModel.md`) gets only that many real seats; no seat is ever fabricated for a non-existent player.
  - **`version: number`** — **new in Sprint 3.8, Task 2.** Starts at `1` at creation; incremented by exactly `1` on every accepted gameplay write (today: only `submitBid()`). The app-level optimistic-concurrency field `firestore.rules` independently validates (`newData.version == oldData.version + 1`) — this is what makes `MatchService.subscribeToMatch()`'s Sprint 3.7 ordering guard (dormant through Sprint 3.7.1, since nothing wrote this field) finally active for real.
  - **`biddingOpen: boolean`** — **new in Sprint 3.8, Task 3.** Starts `true`; flips to `false` the moment every real seat (per `seats`) has a non-null entry in `bids`. This sprint's ONLY bidding-phase gate — there is no separate `phase`/sub-state machine (DASH/AUCTION/CONFIRM/ESTIMATES, which remain `bidding-engine.js`'s local, offline concern, untouched and unconnected to this document).
  - **`bids: { [seatId]: bidValueOrNull }`** — **new in Sprint 3.8, Task 3.** One slot per real seat (mirrors `seats`' own key set exactly), `null` until that seat submits. `bidValue` is stored **opaque** — whatever `submitBid()`'s caller passes, unvalidated for game legality (a real trick count, a legal Dash/With shape, etc. — see `bidding-engine.js`'s own, separate, unconnected rules for that). This collection only enforces WHO may write WHERE and WHEN, not whether the bid VALUE makes sense under the auction rules.
  - **`lastBidSeat: seatId | null`** — **new in Sprint 3.8, Task 3.** The seat that made the most recently accepted bid write, or `null` before any bid. Informational, and also the mechanism `isValidBidSubmission()` uses to identify which single seat a given update write is claiming to be for.
- **Fields NOT yet implemented — DO NOT implement against these without re-checking this doc first:** `mode`, `scoringMode`, `dealerId` (use `dealer`), `turnId` (use `turn`), `round` (use `currentRound`, a bare number, not an object), `hands`, `dealState`, `playState`, `biddingState` (the RICH bidding-engine.js state machine — NOT the same thing as this document's own minimal `bids`/`biddingOpen`, which only sync a raw per-seat value), `matchScores`, `roundHistory`, `winnerId`, `startedAt` (use `createdAt`), `updatedAt` (a real, opaque server-timestamp value now DOES get written by `submitBid()`, but nothing reads it yet — do not assume it carries meaning beyond "the time of the last accepted write"). All of these belong to the speculative future-gameplay design retained below for planning purposes only.
- **Relationships:** `players[]` → `players/{uid}`; `roomId` → `rooms/{roomId}`; `seats` values → `players[]` (a subset, bijective).
- **Read frequency (as shipped):** Higher since Sprint 3.7/3.7.1 (a live `onSnapshot` listener per seated client, not a one-shot read) — see `MatchSynchronization.md`. Every accepted bid write (Sprint 3.8) now delivers to every subscribed seat in real time.
- **Write frequency (as shipped):** One write at creation (`startMatch()`'s transaction), plus, since Sprint 3.8, one write per accepted `submitBid()` call — at most one per real seat per match, since a seat cannot bid twice. No other gameplay method writes anything yet.
- **Security requirements (as actually deployed — see `firestore.rules` and `docs/implementation/MatchInitialization.md`/`docs/architecture/SecurityArchitecture.md`):** `get` restricted to a player listed in the match's own `players[]` (not a global authenticated read, unlike `rooms/{roomId}` — see `SecurityArchitecture.md`'s note on why); `list` always denied; `create` requires the acting user be one of the new document's own `players[]`, field-whitelisted to the actual shipped shape (now including `seats`/`version`/`biddingOpen`/`bids`/`lastBidSeat`), `status == "starting"`, `currentRound == 1`, `dealer`/`turn` both in `players[]`, a valid `seats` bijection (Sprint 3.8), `version == 1`, `biddingOpen == true`, `bids` all-null, **plus (Sprint 3.4.1) a full binding check against the referenced room**: the room must exist and have been `"waiting"` with every player ready BEFORE this write (`get()`), the match's `players[]` must exactly equal the room's, and the room's own `matchId` — as it will exist once this same transaction/batch commits (`getAfter()`) — must equal this match document's own id. This closes a real Sprint 3.4 gap where a match could be created independently of any legitimate room start. `update` **allowed for exactly one shape as of Sprint 3.8** (`isValidBidSubmission()` — see `SecurityArchitecture.md`'s "Bidding write authority" section for the full breakdown of who/what/version/concurrency); every other update shape remains denied; `delete` always denied.

---

### Speculative future design (NOT implemented — retained for planning only)

Everything below this line describes the eventual full-gameplay shape this collection is expected to grow into once bidding/estimation/card-play are activated in a future sprint. None of it exists in `match-service.js`, `firestore.rules`, or the field list above. Kept for planning continuity, per "do not invent future architecture, but don't hide it either" — not a claim that any of this is built.

- **Purpose:** The single authoritative live-match document — the multiplayer equivalent of today's local `GameSession` object. One document holds the *entire* match state; this is a deliberate flat-document choice over subcollections (see rationale below).
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
