package com.estemshan.engine

/**
 * S64 — the seasonal leaderboard ordering math (RD27; RD3 King rule).
 *
 * Specified in docs/NATIVE_V1_PLAN_AND_ESTIMATE.md (E6b, S64:
 * "server-authoritative ordering (position, player, Tier/Rank, RP,
 * season), King ranked by RP above the King lower bound, current player
 * highlighted, season isolation/reset") and RD27: "Seasonal Ranked
 * Leaderboard is in scope. Server-authoritative ordering: rank position,
 * player, Tier/Rank, RP, season, current player highlighted. King players
 * are ranked among themselves by RP above the King lower bound — King has
 * no divisions, so RP is the only ordering key there (RD3). Season
 * isolation: the board shows one season only and resets with the season
 * (RD24); rank position is recomputed per season, never blended. The
 * client may read, never order."
 *
 * Scope of THIS file (engine slice): the pure ordering from season entries
 * to positioned rows — the math the future server function applies before
 * persisting/serving the board, so ordering stays off the client by
 * construction. Firestore persistence, serving, the season-reset trigger,
 * and the S45/S54 display surfaces are separate scope.
 *
 * What is engineering judgment inside that closed envelope (documented
 * here and pinned by LeaderboardTest, not specified by RD27):
 *
 * - Primary key: ladder-rung index descending; secondary key: RP
 *   descending within the rung. For every rung below King the secondary
 *   key never overturns the primary (a rung IS its RP band); for King it
 *   is the ONLY key, per RD27/RD3. Final tiebreak: playerId ascending —
 *   pure determinism for identical (rung, RP), never a rank claim.
 * - Positions are COMPETITION ranks (1 + count strictly ahead; ties
 *   share, the next position skips: 1, 2, 2, 4) — the same rule as RD17's
 *   [placementRank] ("ties share the better rank"), applied to a board
 *   instead of a table. RD27 does not name a tie rule; sharing RD17's is
 *   the least novel choice available.
 * - Rung identity (tier/division) is the ordering authority; RP orders
 *   within it. Tier-grouping is structural, not assumed: RD27's
 *   "ranked among themselves" rule for Kings only makes sense if tiers
 *   group first. On valid data this coincides with RP order anyway (the
 *   keeps-both-consistent invariant: identity always agrees with
 *   [rankAt] of the stored RP). A bogus identity fails LOUDLY (same
 *   convention as [ladderRankOf]/[seasonResetRank]) instead of sorting
 *   somewhere vague.
 * - Season isolation is a filter: only [seasonId] entries board. A season
 *   with no entries boards empty — reset-by-absence, never blended.
 * - `isCurrentPlayer` marks the passed player id; highlighting itself is
 *   display scope (S45/S54), not engine scope.
 *
 * Pure data + pure functions, no I/O. Common-main: stdlib only.
 */

/**
 * One season's board input per player: the persisted identity (tier +
 * division reference one rung of the single [rankLadder] table), the
 * progression counter, and the season it belongs to.
 */
data class LeaderboardEntry(
  val playerId: String,
  val tier: String,
  val division: String,
  val rp: Int,
  val seasonId: String,
)

/**
 * One positioned board row: 1-based competition [position], the rung, the
 * RP, the season, and whether this row is the viewing player.
 */
data class LeaderboardRow(
  val position: Int,
  val playerId: String,
  val rank: LadderRank,
  val rp: Int,
  val seasonId: String,
  val isCurrentPlayer: Boolean,
)

/**
 * Order one season's board. Fails loudly on an entry whose tier/division
 * names no ladder rung. Empty in, empty out — a season with no entries
 * (including every fresh season after a reset) boards nothing.
 */
fun orderLeaderboard(
  entries: List<LeaderboardEntry>,
  seasonId: String,
  currentPlayerId: String,
): List<LeaderboardRow> {
  data class RankedEntry(val entry: LeaderboardEntry, val rung: LadderRank) {
    val rungIndex: Int = rankLadder.indexOf(rung)

    /**
     * Tie identity for positions: rung + RP only. The playerId determinism
     * tiebreak orders within a tie but never merges or splits one — this
     * single predicate is the only place that decision lives.
     */
    infix fun tiesWith(other: RankedEntry): Boolean =
      rungIndex == other.rungIndex && entry.rp == other.entry.rp
  }

  val placed = entries
    .filter { it.seasonId == seasonId }
    .map { entry ->
      val rung = rankLadder.firstOrNull { it.tier == entry.tier && it.division == entry.division }
      require(rung != null) {
        "Leaderboard entry for '${entry.playerId}' names no ladder rung: " +
          "tier='${entry.tier}', division='${entry.division}'"
      }
      RankedEntry(entry, rung)
    }
    .sortedWith(
      compareByDescending<RankedEntry> { it.rungIndex }
        .thenByDescending { it.entry.rp }
        .thenBy { it.entry.playerId },
    )
  val rows = mutableListOf<LeaderboardRow>()
  placed.forEachIndexed { index, (entry, rung) ->
    val position = if (index > 0) {
      if (placed[index] tiesWith placed[index - 1]) rows[index - 1].position else index + 1
    } else {
      1
    }
    rows.add(
      LeaderboardRow(
        position = position,
        playerId = entry.playerId,
        rank = rung,
        rp = entry.rp,
        seasonId = entry.seasonId,
        isCurrentPlayer = entry.playerId == currentPlayerId,
      ),
    )
  }
  return rows
}
