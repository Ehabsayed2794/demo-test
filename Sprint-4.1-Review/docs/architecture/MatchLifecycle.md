# Match Lifecycle

Design only, **except for the one slice called out below**, which Sprint 3.4 actually implemented. This document maps the requested generic lifecycle (Lobby → Waiting → Ready → Dealing → Auction → Trump Declaration → Play → Round End → Next Round → Match End → Archived) onto the phases that **already exist and are implemented** in `bidding-engine.js`/`table-engine.js`/`session.js`, rather than inventing a parallel set of names. Where the requested generic name and the existing engine's phase name differ, both are shown.

> **Implementation status (Sprint 3.4 — Match Initialization & Game Start).** Only the **LOBBY → WAITING → "everyone ready" → match created → room `in_game`** slice is real code today; see `docs/implementation/MatchInitialization.md`. Everything from DEALING onward (and the READY state named in the diagram below) remains exactly what this document has always been: design-only, not built, not claimed otherwise. Concretely, what's actually implemented deviates from the diagram below in two ways, both deliberate and documented, not silent:
> - **There is no `"ready"` room status.** The Sprint 3.3 decision to *not* add an automatic `room.status` transition when everyone readies up (see `RoomLifecycle.md`'s Ready State section) still stands. Instead, `RoomService.setReady()` itself detects "every player in the room is ready" and calls `MatchService.startMatch(roomId)` directly — there's no intermediate `WAITING → READY` room-status transition to observe; `RoomService`'s own `readyPlayers` array **is** the "ready" signal.
> - **The room's own status goes straight from `"waiting"` to `"in_game"`**, not to a `"starting"` room status as this diagram's original draft implied. `"starting"` is the **match** document's own `status` field (`matches/{matchId}.status == "starting"`), not a room status — the two documents' status fields are independent, and only the match's is `"starting"` immediately after creation.
>
> `DEALING` and every phase after it (`DASH`/`AUCTION`/`CONFIRM`/`ESTIMATES`/`PLAY`/round-end/`NEXT ROUND`/`MATCH END`/`ARCHIVED`) remain **not implemented** — `matches/{matchId}.gameState` today is an explicit `{ initialized: false, todo: "..." }` placeholder, not real dealt hands, because `Dealer.dealHands()` depends on a global `Deck` object that doesn't exist anywhere in this repository. See `MatchInitialization.md` for the full finding. Bidding, estimation, and card-play were explicitly out of scope for this sprint regardless of that finding.
>
> **Sprint 3.4.1 (Match Start Consistency & Security Hotfix)** did not move this slice any further along the diagram — no new phase was implemented. It hardened the SAME `LOBBY → WAITING → match created → room in_game` transition: (1) `players/{uid}.currentMatchId` is no longer written for every room player by `MatchService` (that write could only ever succeed for the initiating player against the unchanged owner-only `players/{uid}` rule) — each client now self-syncs its own copy after discovering the match via `rooms/{roomId}.matchId`/`RoomService.loadRoom()`; (2) `firestore.rules` now cross-checks the room and match documents against each other (room membership, all-ready, and a same-transaction binding via `getAfter()`), closing a real gap where a match could otherwise be fabricated independently of a legitimate room start; (3) the unsafe `createMatch()` primitive was removed from `MatchService`'s public API. See `docs/implementation/MatchInitialization.md`'s Sprint 3.4.1 section for the full writeup.
>
> **Sprint 3.7 (Real-Time Match Synchronization)** also did not move this slice further along the diagram — `DEALING` onward remains not implemented, and no gameplay write path exists (`firestore.rules`' `matches/{matchId}` block still correctly has `allow update: if false`). What changed: the read side of this lifecycle is now real-time rather than one-shot. Any seated client that calls `MatchService.subscribeToMatch(matchId, ...)` (or `GameSession.subscribeToRemoteMatch(matchId)`) now observes every future change to its match document live — so whichever future sprint activates `DEALING`/`DASH`/`AUCTION`/etc. as real Firestore writes will have every OTHER seated client already watching for them, with no further sync plumbing to build.
>
> **Sprint 3.7.1 (Synchronization Hardening & Identity Foundation)** likewise did not move this slice further along the diagram — it hardened Sprint 3.7's read-side plumbing (a real-time "reconnect on disconnect" only now correctly means "reconnect ONLY for a retryable error, never loop forever on a permanent one" — Sprint 3.7's original version retried everything unconditionally) and corrected this document's neighbor `MatchSynchronization.md` to stop overstating the (still inactive) ordering guard and to label every mocked/simulated test result as such. It also produced `docs/architecture/SeatIdentityModel.md` — the documentation-only design for mapping this diagram's future `DEALING`/`DASH`/etc. seat-level actions (currently only meaningful in the engine's `p1`..`p4` space) onto the real Firebase Auth uids `matches/{matchId}` actually stores.
>
> **Sprint 3.8 (Gameplay Synchronization: Bidding Authority) is the first sprint to move a REAL write into this diagram — but only a narrow slice of `DASH`/`AUCTION`/`CONFIRM`/`ESTIMATES`, and not the state-machine sub-phases themselves.** `matches/{matchId}` now has a minimal, generic `bids`/`biddingOpen` sync primitive (one opaque value per real seat, submitted at most once while open) implemented via `MatchService.submitBid(matchId, seatId, bid)` — but this is deliberately NOT the same thing as the rich `biddingState` sub-phase machine (`DASH → AUCTION → CONFIRM → ESTIMATES → DONE`) `bidding-engine.js` already implements offline; that engine remains completely unconnected to Firestore, untouched, and unaware any of this exists. Concretely: a real multiplayer client can now durably submit "seat p1's bid is 4" and have every other seated client observe it live, but nothing advances a phase, resolves an auction, declares a trump, or determines whose turn it is next — those are still `bidding-engine.js`'s local, offline-only concern, and wiring it to this new sync primitive is explicitly the NEXT gameplay-write sprint's job, not this one's ("DO NOT implement Turn Rotation after bidding"). `docs/architecture/SeatIdentityModel.md`'s mapping (documentation-only through 3.7.1) is implemented for real this sprint and is what makes seat-owned bid writes possible at all — see that document and `docs/architecture/MatchSynchronization.md`'s Sprint 3.8 section for the full account, including the honest limitation that the bid VALUE itself is never validated against auction rules at this layer.
>
> **Sprint 3.9 (Engine Adapter Layer)** built the translation layer (`design-ui/match-adapter.js`) connecting Firestore's uid-keyed identities to the engine's seat-keyed ones, but deliberately did not connect it to `bidding-engine.js` at all yet — a one-shot `bootstrapGameSession()` only, no live wiring.
>
> **Sprint 4.0 (Online Bidding Synchronization: Authority Layer) is the sprint that connects the ESTIMATES phase of `bidding-engine.js`'s own state machine to the sync primitive Sprint 3.8 built.** This is a narrower claim than "bidding is now synchronized" — stated precisely: when a remote seat's `SubmitFinalEstimate` (the ESTIMATES-phase action) arrives via `MatchService.submitBid()`, `MatchAdapter.applyRemoteBid()` now calls the REAL, unmodified `bidding-engine.js` reducer with that action, and its result (accepted or rejected) is what determines whether the local `GameSession` reflects it — never a re-derivation, never an assumption. `DASH`/`AUCTION`/`CONFIRM` remain exactly as unconnected as Sprint 3.8 left them (see the paragraph above this one — the schema reason is unchanged and restated in `docs/architecture/BidValidation.md`/`docs/architecture/EngineAdapter.md`). Turn rotation, card play, trick resolution, and scoring remain entirely out of scope, unchanged from every sprint's stop list since 3.4. `bidding-engine.js` itself is untouched — every decision about legality, order, and phase is still made by calling into it and reading its response, never by this adapter or any Service.
>
> **Sprint 4.1 (Turn Authority & Remote Play Validation) is NOT about card play — it is only about determining WHO is allowed to act, and keeping that fact synchronized.** `MatchAdapter.applyRemoteTurn()` makes what was previously a one-shot bootstrap value (`GameSession`'s top-level turn mirror, set once by Sprint 3.9's `bootstrapGameSession()`) an ONGOING sync, continuously kept current with `matches/{matchId}.turn`. This is a DIFFERENT field from `GameSession.getBiddingState().turnId` — the bidding-phase-specific "who bids next" pointer `bidding-engine.js`'s own reducer already owned and continues to own, completely untouched by this sprint (see `docs/architecture/EngineAdapter.md`'s Sprint 4.1 section for the full account of why these are two separate fields with two separate owners). `MatchAdapter.isLocalSeatsTurn()`/`assertLocalTurn()` give any FUTURE gameplay-write function (card play, once it exists — not this sprint) a way to check "is it actually my turn" and reject locally, without sending a write, before Firestore or `firestore.rules` ever sees the attempt — delivered ahead of its first real caller, since no such write function exists in this codebase yet. **Firestore never decides whose turn it is:** `applyRemoteTurn()` contains no decision rule, only a lookup-and-copy of whatever `matches/{matchId}.turn` already holds — and, honestly stated, nothing in this codebase yet writes `bidding-engine.js`'s own computed turn back into that field, so a remote opponent currently only sees whatever `turn` was set to at match creation, not bidding's real, locally-advancing pointer. This remaining gap is recorded, not hidden — closing it is a future gameplay-write sprint's job, per this document's own running convention. Card synchronization, card play, trick resolution, score synchronization, replay, voice chat, AI, matchmaking, and Cloud Functions remain untouched, per this sprint's own explicit stop list.

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
