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
