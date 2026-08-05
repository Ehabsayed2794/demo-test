# Match Lifecycle

Design only, **except for the one slice called out below**, which Sprint 3.4 actually implemented. This document maps the requested generic lifecycle (Lobby → Waiting → Ready → Dealing → Auction → Trump Declaration → Play → Round End → Next Round → Match End → Archived) onto the phases that **already exist and are implemented** in `bidding-engine.js`/`table-engine.js`/`session.js`, rather than inventing a parallel set of names. Where the requested generic name and the existing engine's phase name differ, both are shown.

> **Implementation status (Sprint 3.4 — Match Initialization & Game Start).** Only the **LOBBY → WAITING → "everyone ready" → match created → room `in_game`** slice is real code today; see `docs/implementation/MatchInitialization.md`. Everything from DEALING onward (and the READY state named in the diagram below) remains exactly what this document has always been: design-only, not built, not claimed otherwise. Concretely, what's actually implemented deviates from the diagram below in two ways, both deliberate and documented, not silent:
> - **There is no `"ready"` room status.** The Sprint 3.3 decision to *not* add an automatic `room.status` transition when everyone readies up (see `RoomLifecycle.md`'s Ready State section) still stands. Instead, `RoomService.setReady()` itself detects "every player in the room is ready" and calls `MatchService.startMatch(roomId)` directly — there's no intermediate `WAITING → READY` room-status transition to observe; `RoomService`'s own `readyPlayers` array **is** the "ready" signal.
> - **The room's own status goes straight from `"waiting"` to `"in_game"`**, not to a `"starting"` room status as this diagram's original draft implied. `"starting"` is the **match** document's own `status` field (`matches/{matchId}.status == "starting"`), not a room status — the two documents' status fields are independent, and only the match's is `"starting"` immediately after creation.
>
> `DEALING` and every phase after it (`DASH`/`AUCTION`/`CONFIRM`/`ESTIMATES`/`PLAY`/round-end/`NEXT ROUND`/`MATCH END`/`ARCHIVED`) remain **not implemented** — `matches/{matchId}.gameState` today is an explicit `{ initialized: false, todo: "..." }` placeholder, not real dealt hands, because `Dealer.dealHands()` depends on a global `Deck` object that doesn't exist anywhere in this repository. See `MatchInitialization.md` for the full finding. Bidding, estimation, and card-play were explicitly out of scope for this sprint regardless of that finding.

## State diagram

```
LOBBY                         (no match document yet — player is browsing Lobby v2)
  │  RoomService creates rooms/{roomId}; players join/ready-up (see RoomLifecycle.md)
  ▼
WAITING                       (room.status == "waiting" — not all seats ready)
  │  last seat readies up
  ▼
READY                         (room.status == "ready" — host starts, or auto-start once full)
  │  MatchService creates matches/{matchId}, room.matchId set, room.status = "starting"
  ▼
DEALING                       (matches/{matchId}.dealState.completed == false → true)
  │  equivalent to today's Dealer.dealHands() — server-authoritative concern noted below
  ▼
DASH  ─────────────────────── biddingState.phase == "DASH"       (rounds 1-13 only)
  │   pre-bid Dash Call window, max 2 callers, resolved before the main auction
  ▼
AUCTION ─────────────────────  biddingState.phase == "AUCTION"    ("Auction" in the request)
  │   ascending bids/raises/passes until one bidder remains uncontested
  ▼
CONFIRM ─────────────────────  biddingState.phase == "CONFIRM"    ("Trump Declaration" in the request)
  │   auction winner declares trump suit (or Sans); Estimation Jump-In / Auction Alignment
  │   With grants are finalized here (see the rules engine's bidding-engine.js)
  ▼
ESTIMATES ───────────────────  biddingState.phase == "ESTIMATES"
  │   remaining players lock in their final trick estimates; fast rounds (14-18) enter
  │   the state machine directly at this phase — DASH/AUCTION/CONFIRM are skipped entirely,
  │   per the forced-trump-sequence rule already implemented in bidding-engine.js
  ▼
DONE (bidding)  ─────────────  biddingState.completed == true → GameSession.completeBidding()
  │   round/trump/callerId/withPlayers/estimates/riskPlayerId committed to `round`
  ▼
PLAY ────────────────────────  playState.phase == "PLAY"          ("Play" in the request)
  │   13 tricks resolved in sequence (trickNumber 1→13); each trick:
  │     PLAY → (all 4 played) → resolve winner → tricksWon updated → next leader/turn
  ▼
DONE (round)  ────────────────  playState.completed == true → GameSession.completeRound()
  │   equivalent to "Round End" in the request
  │   ScoringEngine.calculateRoundScore() runs, appends to roundHistory, updates matchScores
  ▼
   ┌─ round.number < maxRounds AND no early match-end condition ──┐
   │                                                                │
   ▼                                                                ▼
NEXT ROUND                                              MATCH END
(GameSession.nextRound(): dealer rotates,               (winnerId set, final scores committed)
 hands/dealState/playState/biddingState reset,
 round.number += 1)
   │                                                                │
   └──────────────► back to DEALING ◄──────────────────────────────┘
                                                                     │
                                                                     ▼
                                                                ARCHIVED
                                                    (see "Archival" below)
```

## Notes on transitions that need explicit design attention (not just naming)

- **DASH/AUCTION/CONFIRM are skipped for fast rounds (14-18).** This isn't a simplification for this document — it's an existing, already-implemented rule (`bidding-engine.js` initializes fast rounds directly into `ESTIMATES` with a forced trump). The match-lifecycle document must preserve this branch, not flatten it, or a multiplayer round 15 will silently behave like a normal round.
- **Super Call override.** A Super Call (8+) during a fast round extends the match past round 18 per the existing rules engine — meaning `MATCH END` is not strictly "after round `maxRounds`," it's "after round `maxRounds` AND no active Super Call extension." This condition must be evaluated the same way multiplayer-side as it already is offline.
- **Dealing is the first candidate for later server authority.** Dealing requires a source of randomness both players must trust *hasn't* been peeked at by whoever generated it. Client-authoritative dealing (whichever client happens to deal) is an honest limitation during the Spark phase — flag it in the Risks list (see `ArchitectureDecisionLog.md`) as a "soft-launch acceptable, ranked-launch blocking" issue, matching the same trigger point already identified for the Cloud Functions migration.
- **Round End → Next Round is a batched, not incremental, transition.** `GameSession.nextRound()` already resets four separate sub-objects (`hands`, `dealState`, `playState`, `biddingState`) in one call. The Firestore equivalent must be a single document update (or a transaction), never four separate writes — partial application of this transition (e.g. `playState` reset but `hands` not yet cleared) is a state a live opponent's client could actually observe mid-write and misrender. This is a concrete argument for **using Firestore transactions for every multi-field lifecycle transition**, not just for the matchmaking race case already flagged in `RoomLifecycle.md`.
- **Abandonment is a lifecycle state, not just "the player left."** A `matches/{matchId}` document needs an implicit `ABANDONED` state reachable from any of DEALING/PLAY/AUCTION/etc. when a seated player's presence heartbeat (see `PlayerLifecycle.md`) goes stale past a threshold. This should resolve to either "pause and wait for reconnect" (short absence) or "end match, distribute partial results" (long absence) — see `RoomLifecycle.md`'s Reconnection Strategy for the presence mechanism this depends on.

## Archival

Once `MATCH END` is reached, the match document has two possible futures:

1. **Keep it as-is, indefinitely.** Simple, but an abandoned/completed `matches/{matchId}` document with a live listener still attached (a player who didn't close the tab) keeps counting against read quota for no reason.
2. **Copy summary data to `players/{uid}.stats` and `roundHistory`-equivalent history, then delete or TTL the live match document.** Recommended: use Firestore's native TTL policy (free, Spark-compatible, no Scheduled Function needed) on an `archiveAt` field set at `MATCH END` — e.g. 24 hours later — giving players a window to review the final standings screen before the document disappears, without any manual cleanup job.
