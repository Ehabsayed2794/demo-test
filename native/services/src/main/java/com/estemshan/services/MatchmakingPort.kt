package com.estemshan.services

import com.estemshan.engine.GameType
import kotlinx.coroutines.flow.Flow

/**
 * S55/S56 (E6b) — the seam over the public Ranked solo queue.
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   UI → MatchmakingPort → (Cloud Functions pool resolver) → Firestore
 * The screen never touches the queue directly, and a test stands in for this
 * interface rather than faking the SDK — [com.google.firebase.firestore.FirebaseFirestore]
 * has no fake in this repo by design, and the surrounding seam is the cheaper
 * thing to substitute. This is the same call [RoomPort] makes.
 *
 * **RD9 — the pool is server-derived.** The client sends a *search request*
 * only and never a tier claim: the eligible pool (the player's own tier, or
 * exactly one below) is resolved server-side, so [search] deliberately carries
 * **no tier parameter** — a caller cannot request a pool even by accident. A
 * lower-tier player cannot opt up, and the server may still form a
 * mixed-tier match. Since O-15 closed (2026-10-28) the *table* rule is
 * symmetric — the same tier or exactly one tier up **or** down, never two —
 * while this per-searcher pool rule keeps its downward direction.
 *
 * **GM5 — Game Type rides along and is orthogonal to the pool.** The search
 * carries the player's Full/Mini choice and the server forms matches for it,
 * but the tier-pool derivation never widens, narrows, or branches on it. Mini
 * is a Ranked format in its own right — do not segregate it or imply it is
 * unranked.
 *
 * The production implementation is [CallableMatchmaking], over the
 * `enqueueSearch` / `pollSearch` / `cancelSearch` callables. It sends a
 * GameType and nothing else: the uid the server acts on is the callable's
 * verified identity, so `playerId` never reaches the wire — a request that
 * could name a player is a request that could name the WRONG one.
 */
interface MatchmakingPort {

  /**
   * Requests a Ranked solo search for [playerId] at [gameType]. The returned
   * flow publishes the search's outcome and then **completes** — one terminal
   * event ([MatchmakingEvent.Matched], [TimedOut][MatchmakingEvent.TimedOut],
   * or [Failed][MatchmakingEvent.Failed]) ends the search, and collecting it
   * is the whole subscription.
   *
   * Cancelling collection is the local teardown; pair it with [cancel] so the
   * player is also removed from the server queue — RD9: leaving the screen
   * tears the search down rather than leaving it queued server-side.
   */
  fun search(playerId: String, gameType: GameType): Flow<MatchmakingEvent>

  /**
   * Removes [playerId] from the queue. Idempotent — cancelling a search that
   * already ended, or one that never started, is a no-op. Cancel is available
   * in every state (RD9): there is no uncancelable spinner.
   */
  suspend fun cancel(playerId: String)
}

/**
 * The outcomes a Ranked search publishes. Every variant is terminal: the
 * search ends the moment one arrives.
 */
sealed interface MatchmakingEvent {
  /**
   * A table formed; the player transitions into [tableId].
   *
   * The id is the TABLE's id, not a match's: the resolver seats a pair in a
   * room, and the match is created by the existing room→match path once that
   * table is ready — so there is no match id to hand over yet, and calling a
   * room id a match id would be a promise the server has not made.
   */
  data class Matched(val tableId: String) : MatchmakingEvent

  /** No players were found for the pool in time (S56). */
  data object TimedOut : MatchmakingEvent

  /** The search errored — transport, rules, or state (S56). */
  data object Failed : MatchmakingEvent
}
