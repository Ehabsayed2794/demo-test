package com.estemshan.engine

/**
 * S56 — the RD9 Ranked eligible-pool policy, engine slice: the pure tier
 * math under the server's matchmaking, plus the GM5 GameType rule. This
 * is the pure policy slice only — NOT the resolver: no callable, no
 * Cloud Function, no Firestore documents, no services, no UI. The queue,
 * the search lifecycle, and the server's decision of who pairs with whom
 * (S56's backend slice, watched by S57's anti-abuse story) are out of
 * this file's scope: they consume this policy. Pure data + pure
 * functions, no I/O. Common-main: stdlib only.
 *
 * RD9 (canonical): "Public Ranked matchmaking is server-controlled. The
 * player never picks a higher-tier pool; the server derives it. Own tier
 * or exactly one tier below, never two+; a lower-tier player cannot opt
 * up (Silver cannot request Gold). The server may form a mixed Gold/Silver
 * match — do not write 'Gold players can only be matched in Gold.'"
 *
 * GM5: ranked matchmaking carries the search's [GameType] and the RD9
 * tier-pool rule is orthogonal to it — the pool never widens or narrows
 * for Mini. [eligiblePoolTiers] and [isPoolEligible] take no GameType at
 * all (that IS the orthogonality); the only GameType rule in Ranked
 * search is [isSearchCompatible], exact equality, so a Full and a Mini
 * searcher never resolve into the same match.
 *
 * The pool is directional by design, and this file deliberately adds no
 * symmetric match-formation rule: it answers "which tiers may a searcher
 * of tier T be served from" (Gold searches down into Silver; Silver
 * cannot opt up into Gold), not "which tier pairs may share a table".
 * Whether the server forms a mixed Gold/Silver match — which RD9 allows —
 * is the resolver's decision made across directional pools (a Gold
 * searcher's pool admits the Silver candidate; the Silver player is not
 * searching Gold). The symmetric consent rule is RD29's, lives in
 * PartyEligibility.kt for parties, and never applies to solo Auto-Match.
 *
 * **O-15 CLOSED 2026-10-28 (owner ruling): the table rule is SYMMETRIC —
 * one tier, up or down.** This file is unchanged by that ruling: the pool
 * still answers per searcher, and the resolver composes BOTH directions
 * (`isPoolEligible(a, b) || isPoolEligible(b, a)`) to form the table. So
 * "no opt-up" survives as the player's own choice (a client still cannot
 * name a higher pool) while a server-formed Gold/Silver table is legal in
 * either arrival order. See
 * `docs/decisions/2026-10-28-matchmaking-owner-rulings.md`.
 *
 * Engineering judgment inside that closed envelope, pinned by
 * MatchmakingPoolTest:
 *
 * - The 7-tier order (Bronze, Silver, Gold, Platinum, Diamond, Royal,
 *   King) derives from the single [rankLadder] table's distinct tiers in
 *   order — never a second tier table, never 19-run index arithmetic
 *   (RD9 is written in tiers; King has no divisions). King's adjacency
 *   to Royal falls out of the order; Bronze has no tier below.
 * - Divisions never enter the pool math: Gold I and Gold III are the
 *   same pool key, exactly as RD9 words the rule in tiers.
 * - A foreign tier (none of the ladder's 7) fails loudly via [require],
 *   the repo's fail-loud convention (see PartyEligibility.kt).
 */

/**
 * The tier order RD9 derives pools along: the ladder's distinct tiers,
 * bottom to top. Derived, never written — an S46 retune moves bounds,
 * never this order.
 */
val poolTierOrder: List<String>
  get() = rankLadder.map { it.tier }.distinct()

/**
 * RD9: the tiers a server may serve a searcher of [searcher]'s tier from
 * — the searcher's own tier first, then exactly one tier below; never
 * two or more tiers below, never a tier above (a lower-tier player
 * cannot opt up). Bronze pools for itself only; King pools [King, Royal].
 * A foreign tier fails loudly.
 */
fun eligiblePoolTiers(searcher: LadderRank): List<String> {
  val order = poolTierOrder
  val index = order.indexOf(searcher.tier)
  require(index >= 0) {
    "The eligible pool needs a rung of the single ladder, was tier '${searcher.tier}'"
  }
  return if (index > 0) listOf(order[index], order[index - 1]) else listOf(order[index])
}

/**
 * RD9 applied pairwise: [candidate]'s tier is in [eligiblePoolTiers] of
 * [searcher] — same tier always (divisions never matter; Gold I serves
 * Gold III), exactly one tier below always, one tier above never (no
 * opt-up), two or more below never. Implemented on the same derived
 * order rather than a list-membership scan so a foreign candidate fails
 * loudly instead of silently reading as merely ineligible.
 */
fun isPoolEligible(searcher: LadderRank, candidate: LadderRank): Boolean {
  val order = poolTierOrder
  val searcherIndex = order.indexOf(searcher.tier)
  val candidateIndex = order.indexOf(candidate.tier)
  require(searcherIndex >= 0 && candidateIndex >= 0) {
    "Pool eligibility needs rungs of the single ladder, " +
      "was '${searcher.tier}' and '${candidate.tier}'"
  }
  return candidateIndex == searcherIndex || candidateIndex == searcherIndex - 1
}

/**
 * GM5: two ranked searches may pair only on the same [GameType] — exact
 * equality. The tier pool is orthogonal to game type (it never widens or
 * narrows for Mini), so this is the only place GameType narrows a
 * search: a Full searcher is never served into a Mini match and vice
 * versa. Directional-consent symmetric — compatibility itself reads the
 * same from either side; which searcher's pool a pairing comes from
 * stays the resolver's business.
 */
fun isSearchCompatible(a: GameType, b: GameType): Boolean = a == b

/**
 * S71 — the pool a search belongs to, as a MODE-AWARE key.
 *
 * A RANKED search carries the tier the server derived (RD9's band). An
 * UNRANKED search carries NOTHING: the rank-blind pool has no tier to
 * read, so there is no field to hold one. Making the key a sealed type
 * rather than a nullable tier is what makes that structural — a
 * [MatchPoolKey.RankBlind] cannot be handed a rank even by accident, and
 * a [MatchPoolKey.Tiered] cannot be built without one.
 */
sealed interface MatchPoolKey {
  /** A RANKED search, keyed by the tier the server derived for it. */
  data class Tiered(val tier: String) : MatchPoolKey

  /** An UNRANKED search — rank-blind, no tier, by construction. */
  data object RankBlind : MatchPoolKey
}

/**
 * The pool key for a search in [mode].
 *
 * RANKED needs the tier the server derived, and fails loudly without one —
 * an enqueue that reached this point with no profile has a bug upstream,
 * and the repo's convention is to fail loud rather than silently refuse.
 * UNRANKED needs no profile at all, which is the point: a player with no
 * ranked profile can still play. ROOM never pools, so it has no key.
 */
fun poolKeyOf(mode: MatchMode, tier: String?): MatchPoolKey? {
  return when (mode) {
    MatchMode.RANKED -> {
      require(tier != null) {
        "A RANKED search needs the tier the server derived for it"
      }
      MatchPoolKey.Tiered(tier)
    }
    MatchMode.UNRANKED -> MatchPoolKey.RankBlind
    MatchMode.ROOM -> null
  }
}

/**
 * The mode-aware pairing rule (S71). Two RANKED searches pair by RD9's
 * symmetric one-tier band (O-15, closed 2026-10-28: the same tier, or one
 * above or below, never two). Two UNRANKED searches pair ALWAYS — the
 * pool is rank-blind, so a King and a Bronze share a table without
 * consulting a rank, because neither search carries one.
 *
 * Two searches in DIFFERENT modes never pair: the pools are separate, and
 * a table's mode is the mode of the searches that formed it.
 */
fun isPoolCompatible(a: MatchPoolKey, b: MatchPoolKey): Boolean = when {
  a is MatchPoolKey.Tiered && b is MatchPoolKey.Tiered -> tiersAreAdjacent(a.tier, b.tier)
  a is MatchPoolKey.RankBlind && b is MatchPoolKey.RankBlind -> true
  else -> false
}

/**
 * RD9's band, composed in BOTH directions — the symmetric table rule O-15
 * closed on 2026-10-28. The same tier, or exactly one tier above or
 * below, never two. The composition is expressed through [isPoolEligible]
 * so RD9's own definition stays the single source of the band; a foreign
 * tier fails loudly there.
 */
fun tiersAreAdjacent(a: String, b: String): Boolean {
  val rungA = rungOfTier(a)
  val rungB = rungOfTier(b)
  return isPoolEligible(rungA, rungB) || isPoolEligible(rungB, rungA)
}

private fun rungOfTier(tier: String): LadderRank {
  val order = poolTierOrder
  require(order.contains(tier)) {
    "The matchmaking band needs a rung of the single ladder, was tier '$tier'"
  }
  return rankLadder.first { it.tier == tier }
}
