# Room Lifecycle

Design only. Covers `rooms/{roomId}` (see `FirestoreSchema.md`) end to end.

## States and transitions

```
CREATE ROOM
  host (authenticated, non-guest-restricted-if-ranked — see PlayerLifecycle.md) calls
  RoomService.createRoom(): generates a collision-checked join code, writes
  rooms/{code} with hostUid = own uid, seats[0] = { uid: own uid, ready: false }.
  │
  ▼
JOIN
  a second/third/fourth player calls RoomService.joinRoom(code): reads rooms/{code},
  finds the first seat with uid == null, writes their own uid into it.
  MUST be a Firestore transaction, not a plain read-then-write — two players joining
  the last open seat simultaneously is the exact race a transaction exists to prevent
  (re-read inside the transaction; if the seat is already taken, retry against the
  next open seat or fail with "room full").
  │
  ▼
READY
  each seated player toggles their own seat's `ready` field (rules: a player may only
  write their OWN seat's ready flag — see SecurityArchitecture.md). Once all 4 seats
  (AI seats count as auto-ready) report ready, room.status flips to "ready".
  │
  ▼
STARTING → MatchService creates matches/{matchId}; room.matchId set; room.status = "starting"
  (see MatchLifecycle.md for what happens inside the match document from here)
```

## Leave

- **Before match start:** clearing your own seat (`uid: null, ready: false`) is always allowed for a non-host. If the host leaves pre-start, host status transfers to the next occupied seat (see Host Transfer below) rather than closing the room outright — closing on host-leave would be a bad experience for the three players who were already waiting.
- **After match start:** "leaving" a live match is not the same operation as leaving a room — see Reconnection Strategy below; a mid-match leave should default to a reconnect window, not an immediate seat-clear, since the match document (not the room document) is what's live at that point.

## Reconnect

**Presence mechanism (Spark-compatible, no Realtime Database, no Cloud Functions):** each `players/{uid}` document carries a `lastSeenAt` timestamp, refreshed by the client on an interval (e.g. every 20-30 seconds) while any game screen is open. Other clients viewing the same room/match compute staleness themselves: `now - lastSeenAt > threshold` (e.g. 45-60 seconds) means "probably disconnected," without needing a server-pushed disconnect event.

This is a deliberate trade-off over Realtime Database's `onDisconnect()`, which gives an *exact*, server-detected disconnect moment for free even on Spark. The heartbeat approach is coarser (up to one heartbeat-interval of latency in detecting a drop) and costs a small, regular stream of writes against Firestore's free quota. It's still the recommended starting point — see `ArchitectureDecisionLog.md` for the reasoning (avoiding a second database's operational overhead outweighs the precision loss at this stage). If reconnection UX proves too laggy in practice, revisiting this specific decision (adding Realtime Database *just* for presence) is a low-cost, Spark-compatible change — it doesn't require the Blaze migration.

**Reconnect flow:** a player whose client drops (tab closed, network loss) and reopens the app re-reads `players/{uid}.currentRoomId`/`currentMatchId` (see `FirestoreSchema.md`) and — if either is set and the room/match hasn't reached a terminal state — rejoins directly into their existing seat rather than returning to Lobby. No new seat allocation, no re-authentication of game state; they simply re-attach a listener to a document that was never actually waiting on them to leave.

## Host Transfer

Triggered when: the host's presence goes stale (see above) while the room is still in `waiting`/`ready` state, or the host explicitly leaves. Transfer target: the next seat in seat-order with a non-null `uid` and a fresh `lastSeenAt`. This should be a rules-permitted write by *any* currently-seated player (not just the outgoing host) claiming the vacant host role — with a transaction guarding against two players claiming it simultaneously, the same pattern as the join-race case above.

## Room Close

Explicit host action (`RoomService.closeRoom()`) before a match starts. Sets `status: "closed"`; seated players' listeners should treat this as "return to Lobby," not an error state.

## Room Expiration

Rooms that never fill, or whose host disappears with no successful transfer, should not live forever. Recommended mechanism: **Firestore native TTL policy** on an `expiresAt` field (set at creation, refreshed on any room activity) — this is a core Firestore feature, free on Spark, and requires no Scheduled Function (which would require Blaze). This directly closes the gap that would otherwise exist without Cloud Scheduler: stale, abandoned rooms silently age out on their own instead of accumulating as permanent quota-consuming documents.
