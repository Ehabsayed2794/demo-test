package com.estemshan.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Scoring ported from src/utils.ts (legacy-exact, owner-confirmed) for
 * Normal/Classic modes. THE reference is docs/specs/04-scoring.md —
 * every number below traces to a row there. Pure functions, no I/O.
 */
enum class Role {
  NORMAL,
  CALLER,
  WIZZ,
  RISK,
  WIZZ_RISK,
  SUPER_CALL,
  DASH_CALL,
  REG_DASH,
}

data class ScoredPlayer(
  val seat: String,
  val role: Role,
  val bid: Int,
  val won: Int,
)

/**
 * Risk ladder (native, owner decision D2): applies ONLY to the Risk
 * player. |total−13| of 1→0, 2–3→10, 4–5→20, 6+→30.
 */
fun riskValue(totalBids: Int): Int {
  val d = abs(totalBids - 13)
  return when {
    d <= 1 -> 0
    d <= 3 -> 10
    d <= 5 -> 20
    else -> 30
  }
}

private fun isCallerOrWith(role: Role): Boolean =
  role == Role.CALLER || role == Role.WIZZ || role == Role.WIZZ_RISK

private fun isRisk(role: Role): Boolean =
  role == Role.RISK || role == Role.WIZZ_RISK

fun calculateNormalScore(
  role: Role,
  bid: Int,
  won: Int,
  isSoleWinner: Boolean,
  isSoleLoser: Boolean,
  totalBids: Int,
): Int {
  val success = won == bid
  var score = when (role) {
    // Amendment A1: win = bid²; loss = half rounded half-up, flat by bid.
    // NOTE: kotlin.math.round ties to even (40.5→40) but owner/spec + JS
    // require half-up (40.5→41), so use integer math (n+1)/2.
    Role.SUPER_CALL ->
      if (success) bid * bid else -((bid * bid + 1) / 2)
    // Canonical §4 flat table (Under = total ≤13).
    Role.DASH_CALL -> {
      val under = totalBids <= 13
      if (success) {
        if (under) 33 else 25
      } else {
        if (under) -33 else -25
      }
    }
    // Canonical §4 Normal Dash: ordinary 10+T.
    Role.REG_DASH ->
      if (success) 10 else -(10 + won)
    else -> {
      val miss = abs(bid - won)
      // Owner D2 + spec 04 §1: the Risk player's component is the graduated
      // ladder (riskValue(totalBids)), not a flat 10. Dash Call never
      // receives Risk; Normal Dash follows the 04 §4 table below as-is.
      val riskBonus = if (isRisk(role)) riskValue(totalBids) else 0
      if (success) {
        // Single Caller/With bonus ("Caller OR With", never both).
        10 + bid +
          (if (isCallerOrWith(role)) 10 else 0) + riskBonus
      } else {
        -(miss +
          (if (isCallerOrWith(role)) 10 else 0) + riskBonus)
      }
    }
  }
  // Sole winner +10 (all roles). Sole loser: flat 10 extra for every
  // Normal role — the Classic doubling below never applies here.
  if (success && isSoleWinner) score += 10
  if (!success && isSoleLoser) score -= 10
  return score
}

fun calculateClassicScore(
  role: Role,
  bid: Int,
  won: Int,
  totalBids: Int,
  isSoleWinner: Boolean,
  isSoleLoser: Boolean,
): Int {
  val success = won == bid
  val over = totalBids > 13
  val miss = abs(won - bid)
  var score = when (role) {
    Role.SUPER_CALL -> if (success) 42 else -20
    Role.DASH_CALL ->
      if (success) {
        if (over) 23 else 33
      } else {
        if (over) -10 else -20
      }
    Role.REG_DASH ->
      if (success) {
        if (over) 13 else 23
      } else {
        if (over) -won else -10
      }
    Role.WIZZ_RISK ->
      if (success) bid + 13 + 20 else -(miss + 20)
    Role.CALLER, Role.WIZZ ->
      if (success) {
        bid + 13 + 10
      } else {
        -(miss + (if (totalBids <= 11) 20 else 10))
      }
    Role.RISK ->
      if (success) bid + 13 + 10 else -(miss + 10)
    Role.NORMAL ->
      if (success) bid + 13 else -miss
  }
  if (success && isSoleWinner) score += 10
  if (!success && isSoleLoser) score = max(score * 2, -22)
  return score
}

/** Full-round aggregation mirroring calculateRoundScores (both modes). */
fun scoreRound(
  players: List<ScoredPlayer>,
  totalBids: Int,
  classic: Boolean = false,
): Map<String, Int> {
  val successes = players.count { it.won == it.bid }
  val fails = players.size - successes
  fun soleWinner(p: ScoredPlayer) = successes == 1 && p.won == p.bid
  fun soleLoser(p: ScoredPlayer) = fails == 1 && p.won != p.bid
  return players.associate { p ->
    p.seat to if (classic) {
      calculateClassicScore(p.role, p.bid, p.won, totalBids, soleWinner(p), soleLoser(p))
    } else {
      calculateNormalScore(p.role, p.bid, p.won, soleWinner(p), soleLoser(p), totalBids)
    }
  }
}

/** Winner(s): every seat tied at the max. Empty in → empty out. */
fun computeWinner(matchScores: Map<String, Int>): List<String> {
  if (matchScores.isEmpty()) return emptyList()
  val maxScore = matchScores.values.maxOrNull() ?: return emptyList()
  return matchScores.filterValues { it == maxScore }.keys.toList()
}

/**
 * Sa'ayda escalation for the NEXT round after [consecutiveAllFail]
 * all-fail rounds: ×2 → ×4 → ×6 → ×8, capped. 0 → ×1 (no escalation).
 */
fun saaydaMultiplier(consecutiveAllFail: Int): Int =
  if (consecutiveAllFail <= 0) 1 else min(2 * consecutiveAllFail, 8)
