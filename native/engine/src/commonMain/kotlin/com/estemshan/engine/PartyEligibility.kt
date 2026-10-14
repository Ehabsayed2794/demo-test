package com.estemshan.engine

/**
 * S70 — Ranked party eligibility math (RD29; RD30 unplaced rule).
 *
 * Specified in docs/NATIVE_V1_PLAN_AND_ESTIMATE.md (E6b, S70: "Server-side
 * check (Cloud Function, S42 module): at join to a Rank-down Ranked room,
 * resolve each player's tier per RD30 (unplaced → Bronze III) and apply
 * RD29 against the creator; deny with reason `RANK_INCOMPATIBLE`") and
 * RD29: "Allowed iff their tiers differ by at most one tier — the same
 * boundary as RD9, applied against the creator, and symmetric (a Silver
 * host may invite a Gold friend and vice versa). Division gaps within a
 * tier are always eligible (Gold I + Gold III); one tier apart is eligible
 * (Gold + Silver); two or more tiers apart is ineligible (Gold + Bronze).
 * King: one tier below is Royal. Compared by tier, never by 19-rank index
 * (King has no divisions)."
 *
 * Scope of THIS file (engine slice): the pure tier comparison the future
 * join gate applies. The Cloud Function gate itself (auth, room join,
 * allow/deny + plain-language reason surfacing, "the client never reads
 * the other player's rank") is server scope with no specified trigger
 * beyond S42's module — explicitly outside this file.
 *
 * What is engineering judgment inside that closed envelope (documented
 * here and pinned by PartyEligibilityTest, not specified by RD29):
 *
 * - The 7-tier order (Bronze, Silver, Gold, Platinum, Diamond, Royal,
 *   King) derives from the single [rankLadder] table's distinct tiers in
 *   order — never a second table, never 19-rank indices (which would make
 *   Gold I + Gold III look two apart). King adjacency to Royal falls out
 *   of the order, exactly as RD29 states it.
 * - Eligibility is symmetric by construction (absolute index distance),
 *   matching RD29's "against the creator, and symmetric" — host and
 *   joiner are interchangeable inputs.
 * - The denial reason is a plain string constant ([PARTY_DENIAL_REASON]),
 *   the exact `RANK_INCOMPATIBLE` token the server denial must carry so
 *   the client join path can surface it without reading any rank.
 * - Profiles resolve through the existing identity ([ladderRankOf]):
 *   per RD30 every account always resolves to a real tier (unplaced =
 *   Bronze III, never a "no tier" state), so there is no unplaced edge
 *   left for the comparison to handle. A bogus identity fails loudly via
 *   [ladderRankOf], consistent with the repo's fail-loud convention.
 *
 * Pure data + pure functions, no I/O. Common-main: stdlib only.
 */

/** The denial reason the S70 join gate returns for an ineligible party. */
const val PARTY_DENIAL_REASON: String = "RANK_INCOMPATIBLE"

/**
 * The tier order RD29 compares along: the ladder's distinct tiers, bottom
 * to top. Derived, never written — an S46 retune moves bounds, never this
 * order.
 */
val partyTierOrder: List<String>
  get() = rankLadder.map { it.tier }.distinct()

/**
 * RD29 party eligibility between two ladder rungs: eligible iff their
 * tiers differ by at most one step on [partyTierOrder] — same tier always
 * (division gaps never matter), one apart either direction, two or more
 * never. Symmetric: creator and joiner are interchangeable.
 */
fun arePartyTiersEligible(a: LadderRank, b: LadderRank): Boolean {
  val order = partyTierOrder
  val ai = order.indexOf(a.tier)
  val bi = order.indexOf(b.tier)
  require(ai >= 0 && bi >= 0) {
    "Party eligibility needs rungs of the single ladder, was '${a.tier}' and '${b.tier}'"
  }
  return kotlin.math.abs(ai - bi) <= 1
}

/**
 * RD29 + RD30 at the profile level: resolve both identities through
 * [ladderRankOf] (unplaced accounts already hold real Bronze III tiers,
 * so no special case), then apply [arePartyTiersEligible].
 */
fun arePartyProfilesEligible(creator: RankedProfile, joiner: RankedProfile): Boolean =
  arePartyTiersEligible(ladderRankOf(creator), ladderRankOf(joiner))
