# Backend Architecture — Overview

**Status:** Design only. No code, no Firestore calls, no Cloud Functions implemented as part of this document.
**Constraint driving every decision below:** must run entirely on Firebase Spark (free plan) until explicitly migrated. See `MigrationPlan.md` for the trigger conditions and migration steps.

This document is the entry point for the other eight. Read it first; it explains how the pieces named in the other files fit together.

---

## 1. System Architecture

```
Player
  │
  ▼
Authentication (Firebase Auth — Anonymous / Email+Password / Google)
  │  produces a stable uid, independent of display name or account type
  ▼
Player Profile (Firestore: players/{uid})
  │  created once, on first sign-in, by AuthService → PlayerService
  ▼
Lobby (design-ui/lobby)
  │  reads the player's profile for topbar (name/avatar/rank/currency)
  │  offers: Play vs AI (no backend) · Play with Friends (Room) · Ranked (Matchmaking)
  ▼
Room (rooms/{roomId})
  │  RoomService: create / join-by-code / ready-up / host transfer / close
  │  exists only for multiplayer paths — Play vs AI skips this entirely
  ▼
Match (matches/{matchId})
  │  MatchService: created when a room's players are all ready
  │  one document is the single source of truth for the live match state —
  │  the same shape GameSession already owns locally today (see FirestoreSchema.md)
  ▼
Scoring (client-side ScoringEngine, invoked by MatchService per round)
  │  writes round results into matches/{matchId}.roundHistory
  │  writes final results into matches/{matchId}.matchScores + players/{uid}/stats
  ▼
Firestore (persistence + real-time sync layer)
     also feeds: leaderboards/{seasonId}, inventory/{uid}, transactions/{txId}
```

**What doesn't change from the offline build:** `Card Engine`, `Bidding Engine`, `Table Engine`, and `Scoring Engine` keep running exactly as they do today — as pure functions that take a state object and an action, and return a new state object. Multiplayer does not rewrite these engines; it changes *where the authoritative copy of the state object lives* (a Firestore document instead of `sessionStorage`) and *who is allowed to write to it* (see `SecurityArchitecture.md` and `BackendArchitecture.md §3` below).

## 2. Why this shape

The existing `GameSession` module (`design-ui/engine/session.js`) already centralizes all match state behind a single API (`GameSession.recordBidAction`, `GameSession.recordCardPlay`, `GameSession.completeRound`, etc.) instead of letting screens touch storage directly. That design decision — made for the offline build, for a completely different reason (keeping every screen in sync) — turns out to be exactly the seam multiplayer needs. The migration path is:

- Today: `GameSession.recordCardPlay(result)` writes to `sessionStorage`.
- Multiplayer: `GameSession.recordCardPlay(result)` writes to `matches/{matchId}` in Firestore instead, and every other player's client receives the update via an `onSnapshot` listener on that same document.
- The call sites in `table-engine.js`/`bidding-engine.js` that call `GameSession.recordCardPlay(...)` **do not change.** Only `session.js`'s internals do.

This is the single most important architectural fact in this whole document set: **the multiplayer rewrite is scoped to `session.js`, not to the engines that call it.**

## 3. Client vs. server authority (summary — full detail in `SecurityArchitecture.md §8` equivalent)

Per the business constraint, Cloud Functions cannot be used yet (Spark does not support deploying them at all, regardless of usage). So during this phase:

- **Client-authoritative, rules-gated**: a player's own client runs the engines and writes the result to their match document. Firestore Security Rules are the only enforcement layer — they check "is it your turn," "is this move structurally legal," "did you already submit this round," etc.
- **Contract-first for future migration**: every mutating action is expressed as a single named function (`submitBid`, `playCard`, `submitEstimate`, ...) with a fixed input/output shape, called from the engines exactly as `GameSession.recordBidAction(...)` is called today. When Cloud Functions become available, each function's *implementation* moves server-side; its *signature* does not change, so nothing above it (UI, engines) needs to change.

## 4. Document map

| Document | Covers |
|---|---|
| `FirestoreSchema.md` | Every collection: purpose, ID scheme, fields, relationships, read/write frequency, security notes |
| `MatchLifecycle.md` | Every match state and transition, mapped onto the existing `biddingState`/`playState` phases |
| `RoomLifecycle.md` | Create/join/ready/leave/reconnect/host-transfer/close/expire |
| `PlayerLifecycle.md` | Anonymous → registered → returning → guest-upgrade → profile → inventory → stats |
| `SecurityArchitecture.md` | Rules design per collection: who can read/write/update/delete, and why |
| `ServiceArchitecture.md` | Interface-only definitions for AuthService, PlayerService, RoomService, MatchService, InventoryService, LeaderboardService, ShopService, NotificationService |
| `MigrationPlan.md` | Concrete Spark → Blaze migration plan, trigger conditions, and what does/doesn't change |
| `ArchitectureDecisionLog.md` | The specific decisions made across this design sprint and why, so future-you doesn't have to re-derive them |
