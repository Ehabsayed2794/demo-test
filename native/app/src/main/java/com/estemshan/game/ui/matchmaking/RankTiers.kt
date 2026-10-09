package com.estemshan.game.ui.matchmaking

import com.estemshan.engine.rankLadder

/**
 * RD9 — the pool a Ranked search draws from is your own tier, or exactly one
 * below it. [RankPool] is that derivation, as a pure function of the tier the
 * player is actually seated at; the ladder the screen draws and the bracket it
 * highlights are then one and the same fact.
 *
 * The derivation is DISPLAY-ONLY on the client.
 * [com.estemshan.services.MatchmakingPort.search] takes no tier parameter at
 * all, by design — the server resolves the pool from the player's
 * authoritative rank, so a lower-tier player cannot opt up even by accident,
 * and a mixed-tier match remains the server's call. Nothing here is a request;
 * it is the plain-language rule S55 states on the screen.
 *
 * "Below" means lower on the ladder: Gold's pool is Gold and Silver. The
 * lowest tier has nothing beneath it, so its pool is itself alone and the
 * bracket draws one node instead of two. The pool never reaches above the
 * player's own tier — that is the whole of "cannot opt up".
 */

/**
 * The seven tiers the screen draws, lowest first — one node per tier, the
 * divisions collapsed. Derived from the engine's 19-rung [rankLadder], so the
 * tier names and their RD1 Arabic titles stay owned by the engine's single
 * tunable table rather than copied here. S55 draws tiers, not rungs: the pool
 * is a tier-wide fact, and a player seats on it by their rung's tier (Gold I
 * and Gold III both sit at Gold).
 */
object RankTiers {

  val all: List<RankTier> = rankLadder.distinctBy { it.tier }.map { RankTier(it.tier, it.arabic) }
}

/**
 * One node of the ladder: the tier's English face, and the RD1 Arabic title
 * shown beside the player's own rank.
 */
data class RankTier(val name: String, val arabicTitle: String)

/**
 * The ladder plus the one tier the player is seated at, with the RD9 pool
 * derived from it. Construct through the index of the player's tier — it
 * clamps onto the ladder, and refuses an empty one, so a rank that has not
 * resolved yet seats at the nearest end rather than rendering nothing.
 */
class RankPool(val tiers: List<RankTier>, currentIndex: Int) {

  init {
    require(tiers.isNotEmpty()) { "the ladder must have at least one tier to seat a rank on" }
  }

  /** Clamped into [tiers]' bounds — an unresolved rank seats at the nearest end. */
  val currentIndex: Int = currentIndex.coerceIn(0, tiers.lastIndex)

  /** The tier the player holds; the screen's "YOUR RANK" block reads this. */
  val current: RankTier
    get() = tiers[currentIndex]

  /**
   * RD9: your own tier and the one below it, as indices into [tiers]. One
   * element wide at the bottom of the ladder (nothing is below Bronze); never
   * above [currentIndex], and never wider than two.
   */
  val poolIndices: Set<Int>
    get() = setOf((currentIndex - 1).coerceAtLeast(0), currentIndex)

  /** Whether ladder node [index] falls inside the pool the bracket spans. */
  fun isInPool(index: Int): Boolean = index in poolIndices
}
