# Room Lifecycle

**Re-synced in Sprint 3.3 to describe what is actually implemented**, closing the drift the Sprint 3.2.5 Architecture Audit found (this document previously described a join-code/seats/host-transfer design that was never built — see `docs/implementation/ReadyStateFoundation.md`). Sections below are explicitly labeled **Implemented** or **Not Yet Implemented** — nothing in the latter category should be assumed to work.

## States and transitions — Implemented

```
CREATE ROOM
  Any authenticated player calls RoomService.createRoom(playerId, roomName):
  writes rooms/{autoId} with creator: playerId, players: [playerId],
  readyPlayers: [], status: "waiting". roomId is an auto-generated Firestore
  document ID — NOT a human-typeable join code (that was the original,
  unbuilt idea). Today, the creator shares roomId out-of-band (an alert()
  in Lobby) for others to join by ID.
  │
  ▼
JOIN
  RoomService.joinRoom(roomId, playerId): transaction-guarded — validates the
  room exists, isn't closed, and isn't full (MAX_PLAYERS = 4, a plain
  implementation constant, not a previously-agreed schema value), then adds
  playerId to players[]. Idempotent — joining a room you're already in is a
  safe no-op. Two players racing for the last open slot is the exact case
  the transaction guards against — proven via a Firestore-transaction-
  faithful mock in tests/room-service.test.cjs, not just asserted.
  │
  ▼
READY (Sprint 3.3)
  Each member calls RoomService.setReady(roomId, playerId, ready): adds or
  removes exactly their own uid from readyPlayers[]. Requires the player to
  already be a member. Idempotent (setting the same value twice performs no
  write). There is no per-seat structure — readyPlayers is a flat array of
  ready members' uids, parallel to players[].
  │
  ▼
(no further transition implemented yet — see "Game Start" below)
```

## Leave — Implemented

`RoomService.leaveRoom(roomId, playerId)`: removes the player from both `players[]` and `readyPlayers[]` in one transaction.
- If the room is now empty, `status` becomes `"closed"`.
- If the departing player was `creator` and others remain, `creator` transfers to the next remaining player in array order — the only ownership-transfer mechanism that exists; there is no player-initiated "make someone else the host" action.
- Idempotent — leaving a room you're not in, or that no longer exists, is a safe no-op.

There is no distinction yet between "leaving a room" and "leaving a live match" — no match exists to distinguish from, since Game Start (below) isn't implemented.

## Ready State — Implemented (Sprint 3.3)

Covered above under "States and transitions." Two things explicitly **not** implemented, so as not to be assumed:
- **No automatic transition when all members are ready.** `setReady` never touches `status` — reaching "everyone ready" today produces no visible effect beyond `readyPlayers` itself; nothing currently reads that to trigger anything. This is deliberate: an automatic "waiting → ready → starting" transition edges into Game Start, explicitly out of scope through this sprint.
- **No live UI reflection of ready state.** `RoomService.subscribeToRoom()` remains an unimplemented stub (returns a no-op unsubscribe, delivers no updates). Lobby's minimal "Toggle Ready" control (see `docs/implementation/ReadyStateFoundation.md`) fires a single `setReady` call and shows a static `alert()` confirmation — it does not display who else is ready, and does not update if another member's ready state changes while you're looking at the screen.

## Game Start — Not Yet Implemented

No code creates a `match` document, no code transitions a room past `"waiting"`/`"closed"`, no card dealing exists tied to room state. This is intentionally out of scope through Sprint 3.3.

## Reconnect — Not Yet Implemented

The heartbeat-presence idea (a `lastSeenAt` field on `players/{uid}`, refreshed on an interval, staleness computed by observing clients — see `ArchitectureDecisionLog.md` ADR-003) remains a **design**, not shipped behavior. `players/{uid}.lastSeenAt` exists and is stamped once per login (`PlayerService.ensurePlayerProfile`), but nothing refreshes it on an interval while a screen is open, and nothing reads it to compute staleness yet. No reconnect flow exists — a dropped client returning to the app does not currently rejoin a room automatically.

## Host Transfer beyond leave — Not Yet Implemented

The one ownership-transfer path that exists is the inline logic inside `leaveRoom` (see "Leave" above). A presence-triggered transfer (host goes stale without explicitly leaving) and any explicit "hand off host" player action are both unbuilt. `RoomService.transferHost()` remains a `Not implemented` stub.

## Room Close beyond the empty-room case — Not Yet Implemented

`RoomService.closeRoom()` remains a `Not implemented` stub — there is no explicit "close my room" action separate from its last member leaving.

## Room Expiration / Cleanup — Not Yet Implemented

**Flagged by the Sprint 3.2.5 Architecture Audit (finding F8), still open.** The shipped `rooms/{roomId}` schema has no `expiresAt` field and no TTL policy is configured. A Firestore-native TTL policy remains the recommended mechanism when this is built (free on Spark, no Cloud Scheduler/Blaze dependency) — but today, an abandoned `"waiting"` room that nobody ever leaves persists indefinitely. Not urgent at current scale (Firestore's free-tier storage is generous), but worth remembering before Room Listing/browsing ships, since that feature would surface a growing pile of dead rooms without this.
