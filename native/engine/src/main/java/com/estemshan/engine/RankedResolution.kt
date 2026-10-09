package com.estemshan.engine

/**
 * S57 — Ranked match-end resolution. Ported exactly from resolve() in
 * design-ui/standings-ranked-result/ranked-result.js (canonical).
 *
 * The [delta] argument is the FINAL signed integer from the RP engine,
 * including mode and mixed-tier adjustments. This screen never invents
 * an award formula (README integration contract):
 *
 * - Do NOT apply Mini ×0.5 here. The reference's half(n) exists only to
 *   derive preview fixtures from full; production supplies delta already
 *   adjusted. The ×0.5 rule and its ties-away-from-zero rounding belong
 *   to the not-yet-built S47/S50 RP engine (OPEN-3, CLOSED 2026-10-06).
 * - DO display the floor-clamped effective delta: a loss at 0 RP shows
 *   0, not −30, with no demotion (RD3).
 * - At the King ceiling (previous = King, delta > 0): RP accrues, rank
 *   stays King, movement = settled, and the chip shows the
 *   leaderboard-only note (RD27).
 *
 * Pure functions, no I/O.
 */
data class RankedResolution(
  val previousRP: Int,
  /** Floor-clamped RP after applying delta (≥ 0 always). */
  val rp: Int,
  /** Effective delta AFTER the floor clamp (rp − previousRP). */
  val delta: Int,
  val previous: LadderRank,
  val next: LadderRank,
  /** +1 promote, 0 settled, −1 demote. */
  val movement: Int,
  /** True when previous is King and RP grew (leaderboard-only, RD27). */
  val ceiling: Boolean,
)

/**
 * Resolve [delta] (already final) against [previousRP].
 * Mirrors the reference line-for-line:
 *
 *   previousRP   = previousRP.coerceAtLeast(0)
 *   rp           = (previousRP + delta).coerceAtLeast(0)
 *   effectiveDelta = rp − previousRP
 *   previousRank = ladder.last { lowerBound <= previousRP }
 *   nextRank     = ladder.last { lowerBound <= rp }
 *   movement     = nextIndex.compareTo(previousIndex)
 *   ceiling      = previous.isKing && rp > previousRP
 */
fun resolveRanked(previousRP: Int, delta: Int): RankedResolution {
  val prev = previousRP.coerceAtLeast(0)
  val rp = (prev + delta).coerceAtLeast(0)
  val effectiveDelta = rp - prev
  val previous = rankAt(prev)
  val next = rankAt(rp)
  val movement = rankLadder.indexOf(next).compareTo(rankLadder.indexOf(previous))
  val ceiling = previous.isKing && rp > prev
  return RankedResolution(
    previousRP = prev,
    rp = rp,
    delta = effectiveDelta,
    previous = previous,
    next = next,
    movement = movement,
    ceiling = ceiling,
  )
}
