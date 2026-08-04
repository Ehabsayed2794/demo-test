# Firestore Security Design

Design only — no `firestore.rules` file is written or deployed as part of this document. This describes what each collection's rules must enforce; `MigrationPlan.md` covers what changes once Cloud Functions become available.

**Guiding principle for this whole document:** under the Spark constraint, rules are the *only* server-side enforcement available. Every "who can write what" decision below should be read with an honest note about where rules-only enforcement is strong versus where it's a soft approximation pending the Blaze migration — stated explicitly, not glossed over.

| Collection | Read | Create | Update | Delete |
|---|---|---|---|---|
| `players/{uid}` | Any authenticated user (opponents' names/ranks must be visible) | The system, at first sign-in (`request.auth.uid == uid`, document must not already exist) | Owner only, **field-restricted**: `displayName`/`avatarInitial`/`lastSeenAt`/`currentRoomId`/`currentMatchId` are owner-writable; `coins`/`gems`/`rank`/`rp`/`wins`/`streak`/`level` are **not** owner-writable — those only change via the match-completion path (soft-enforced by rules today; see the honest limitation below) | Nobody (no account-deletion flow designed yet — out of scope, see `PlayerLifecycle.md`) |
| `rooms/{roomId}` | Any authenticated user | Any authenticated user (becomes `hostUid`) | A seated player may update only their **own** seat's `ready` field or clear their own `uid`; only `hostUid` may update `status` or another seat (kick) | `hostUid` only, and only while `status == "waiting"` |
| `matches/{matchId}` | Only players listed in `players[].uid` for this match (not public — hides the match from everyone else, though see the `hands` note in `FirestoreSchema.md` for why this doesn't fully hide cards from co-players) | The system, when a room transitions to `starting` (creator must be one of the room's seated players) | Only a player listed in `players[].uid`, and only for the specific field-path their action affects (e.g. only the player whose turn it is may advance `turnId`/`playState`) — **this is the rule that most needs the "is it your turn" logic spelled out precisely; see the note below** | Nobody directly — matches end via `MatchLifecycle.md`'s TTL-based archival, not an explicit delete |
| `matches/{matchId}/hands/{uid}` (if the split-subcollection design from `FirestoreSchema.md` is adopted) | Owner only | The system, at deal time | Owner only, and only to remove a played card (never to add one that wasn't dealt) | Never — cleared by TTL alongside the parent match |
| `leaderboards/{seasonId}/entries/{uid}` | Any authenticated user | The system, at first ranked match | Owner only, **and rules should clamp the delta** (see below) | Never |
| `inventory/{uid}` | Owner only | The system, at Profile Creation (empty) | Owner only, and only via a write that references a valid `shop/{itemId}` and (for real-money items, later) a matching `transactions/{txId}` | Never |
| `shop/{itemId}` | Any authenticated user | Admin only | Admin only | Admin only |
| `transactions/{txId}` | Owner only | Owner only — **flagged below as the weakest point in this whole design** | Never (immutable audit record) | Never |
| `friendRequests/{requestId}` | Either `fromUid` or `toUid` | `fromUid` only, and only if `fromUid == request.auth.uid` | `toUid` only, and only the `status` field | `fromUid` only, while `status == "pending"` (cancel) |
| `friends/{uid}/list/{friendUid}` | Owner only | The system, as part of accepting a friend request (writes both sides) | Never (relationship, not mutable state) | Owner only (unfriend) |
| `seasonData/{seasonId}` | Any authenticated user | Admin only | Admin only | Admin only |
| `dailyRewards/{uid}` | Owner only | The system, at first claim | Owner only, **and rules must check `request.time` against the stored `lastClaimedAt`** to prevent claiming twice in one day — this is one of the few cases where a rules-only clamp is genuinely sufficient, not just an approximation | Never |
| `notifications/{uid}/items/{notificationId}` | Owner only | **Any authenticated user acting as the event's sender** — e.g. a friend-request sender writes into the *recipient's* notification subcollection. This is a narrower, more carefully-scoped exception to "owner writes their own stuff" than anything else in this table, and worth a second look at implementation time | Owner only (marking read) | Owner only |

## Where rules-only enforcement is a real guarantee vs. a soft approximation

Stated plainly, because pretending otherwise would undercut the whole design:

- **Strong (rules alone are sufficient):** ownership checks (`request.auth.uid == uid`), structural checks (does this field exist, is it the right type), simple time-window checks (`dailyRewards`'s once-per-day clamp), and "is this uid actually seated in this room/match."
- **Soft (rules approximate, real enforcement needs Cloud Functions later):**
  - **`matches/{matchId}` turn-order/legality.** Rules *can* check "the field you're writing corresponds to the seat whose turn it currently is," but cannot re-run the full `TableEngine`/`BiddingEngine` legality logic (follow-suit rules, Forbidden-13, Sa'ayda escalation math) — a sufficiently determined client could still write an illegal-but-structurally-valid move. Acceptable for a soft launch among friends; **explicitly called out in `MigrationPlan.md` as a Ranked-Match blocker.**
  - **`leaderboards`/`players` stat writes.** The "clamp the delta to the match's actual stakes" rule is only as good as the rules author's ability to encode the scoring formula in rules syntax — realistically an approximation (e.g. "RP change this write can't exceed X") rather than an exact re-derivation of `ScoringEngine`'s output.
  - **`transactions/{txId}`.** This is the honest weak point: a client that can create its own transaction record can, in principle, fabricate one claiming a purchase happened. For soft-currency-only transactions the blast radius is small (a few fake in-game coins). **This collection should be the first thing migrated to Cloud-Functions-only writes**, ahead of everything else in this table, the moment Blaze is available — see `MigrationPlan.md`.

## What does NOT need Cloud Functions, ever

Worth stating explicitly so "postpone until Blaze" doesn't get over-applied: ownership-scoped reads/writes (the bulk of this table), the `dailyRewards` time-clamp, room seat/ready management, and friend request accept/decline are all fully and permanently satisfiable by rules alone — these are not "temporary until Blaze," they're the correct permanent design.
