package com.estemshan.services

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import kotlinx.coroutines.flow.Flow

/**
 * S55/S56/S71 — the seam over the public solo queue, Ranked and Unranked.
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
 * **S71 — the MODE is the player's to choose, and it is the only thing that
 * separates the two pools.** [search] carries the mode, because the mode is
 * a choice of WHAT TO PLAY, not a claim about the player's standing:
 * `UNRANKED` awards no RP — the tier, the division and the RP stay
 * Ranked-only — while its results count towards the career counters
 * (Q46/N-1, 2026-10-22), so choosing it buys no privilege. The tier is still
 * the server's: an Unranked search reads no profile at all, so the
 * rank-blind pool stays rank-blind. `ROOM` is not a
 * poolable mode — a Room is filled by invitation, never by a queue — so it
 * is refused rather than queued into a pool that could never seat it.
 *
 * **GM5 — Game Type rides along and is orthogonal to the pool.** The search
 * carries the player's Full/Mini choice and the server forms matches for it,
 * but the tier-pool derivation never widens, narrows, or branches on it. Mini
 * is a Ranked format in its own right — do not segregate it or imply it is
 * unranked.
 *
 * The production implementation is [CallableMatchmaking], over the
 * `enqueueSearch` / `pollSearch` / `cancelSearch` callables. It sends a
 * GameType, a mode and nothing else: the uid the server acts on is the
 * callable's verified identity, so `playerId` never reaches the wire — a
 * request that could name a player is a request that could name the WRONG one.
 */
interface MatchmakingPort {

  /**
   * Requests a solo search for [playerId] at [gameType] in [mode]. The
   * returned flow publishes the search's progress and then **completes** — one
   * terminal event ([MatchmakingEvent.Matched],
   * [TimedOut][MatchmakingEvent.TimedOut], or [Failed][MatchmakingEvent.Failed])
   * ends the search, and collecting it is the whole subscription.
   *
   * Cancelling collection is the local teardown; pair it with [cancel] so the
   * player is also removed from the server queue — RD9: leaving the screen
   * tears the search down rather than leaving it queued server-side.
   */
  fun search(playerId: String, gameType: GameType, mode: MatchMode): Flow<MatchmakingEvent>

  /**
   * Removes [playerId] from the queue. Idempotent — cancelling a search that
   * already ended, or one that never started, is a no-op. Cancel is available
   * in every state (RD9): there is no uncancelable spinner.
   */
  suspend fun cancel(playerId: String)
}

/**
 * The outcomes a solo search publishes.
 *
 * Three of the variants are TERMINAL: the search ends the moment one of them
 * arrives. [Searching] is the exception — it is progress, not an outcome, and
 * it carries the one number the server publishes for a waiting Unranked
 * search (the deadline its seat is filled by).
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

  /**
   * The search is still queued, and the server published the deadline this
   * search's seat is filled by. NOT terminal — a terminal event still ends
   * the search.
   *
   * The number is the SERVER's own bound (the Unranked fill window's close),
   * not an estimate of when a table will form: the table may fill sooner, and
   * never later. A Ranked search publishes no deadline — its table is a pair,
   * not a window — so the port never emits this event for one.
   */
  data class Searching(val fillDeadlineMillis: Long) : MatchmakingEvent
}
