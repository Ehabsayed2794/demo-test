package com.estemshan.stage0

import com.estemshan.engine.GameType
import com.estemshan.engine.RpMatchInput
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.finalRpDelta
import com.estemshan.engine.placementOutcome
import com.estemshan.engine.placementRank
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder
import com.estemshan.engine.resolveRanked

/**
 * Stage 0 (E7) — the settlement stream a durable RP store has to apply
 * **exactly once**.
 *
 * E7 asks whether a backup policy can reach ≥90% coverage without becoming a
 * second RP truth (`NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.4.3), and the
 * owner's 2026-10-21 ruling names the property to prove: "a stale backup
 * cannot award RP twice (single idempotency key authority)".
 *
 * That proof needs a real workload of real awards, so this probe replays the
 * E1/E2 match driver and folds each finished match through the **same**
 * settlement functions production uses (`finalRpDelta` → `resolveRanked`,
 * mirroring `foldRankedProgression` in `SettlementDb.kt`):
 *
 *  - placements come from [placementRank]/[placementOutcome] (Q2d/Q2f ties:
 *    a tied bottom is every tied seat's Koz, all-four-tie is all King);
 *  - opponent strength is the mean of the OTHER seats' ordinals, integer
 *    division — the production formula, not a second derivation;
 *  - RD29's one-tier band is re-checked per match: a table wider than one
 *    tier records the match but awards no progression, exactly as the
 *    settlement fold does;
 *  - RP carries from match to match per uid, so the stream is a sequence of
 *    dependent writes and not 4 independent awards per match.
 *
 * The RD7 demotion shield and the Highest-Rank fold are settlement-layer
 * state that never reaches the RP ledger row E7 persists, so they stay out
 * of this probe (they are covered by `SettlementRpEmulatorTest`).
 *
 * The document is deterministic and fingerprinted the same way E1–E6 are, so
 * the JVM leg and the workerd leg must agree on it bytewise before either
 * feeds the store.
 */

private val E7_SEATS = listOf("p1", "p2", "p3", "p4")

private val E7_UID_OF_SEAT = linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")

/**
 * Starting profiles: one Gold I seat and three Platinum III seats — a table
 * RD29 admits (Gold→Platinum is exactly one tier), with enough ordinal spread
 * for the mixed-tier rates to fire. Over the 60-match battery the drift stays
 * inside the band, so the stream is not dominated by zero-award rows.
 */
val E7_STARTING_RP: Map<String, Int> = linkedMapOf("u1" to 900, "u2" to 1020, "u3" to 1120, "u4" to 1180)

private val E7_TIERS = listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond", "Royal", "King")

/** One ledger row: the unit the store must apply once and only once. */
data class SettlementRow(
  val matchId: String,
  val seat: String,
  val uid: String,
  val placement: Int,
  val outcome: String,
  val delta: Int,
  val previousRp: Int,
  val rp: Int,
  val ladderIndex: Int,
  val movement: Int,
)

data class MatchSettlement(val matchId: String, val seed: Int, val rows: List<SettlementRow>)

data class SettlementStream(
  val gameType: GameType,
  val scoringMode: ScoringMode,
  val firstSeed: Int,
  val count: Int,
  val startingRp: Map<String, Int>,
  val matches: List<MatchSettlement>,
) {
  val rows: List<SettlementRow> get() = matches.flatMap { it.rows }
  val totalDelta: Int get() = rows.sumOf { it.delta }
  val noAwardRows: Int get() = matches.count { m -> m.rows.all { it.delta == 0 && it.previousRp == it.rp } } * 4
}

fun buildSettlementStream(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
  startingRp: Map<String, Int> = E7_STARTING_RP,
): SettlementStream {
  val carried = LinkedHashMap(startingRp)
  val matches = ArrayList<MatchSettlement>(count)

  for (seed in firstSeed until firstSeed + count) {
    val run = playMatch(seed, gameType, scoringMode)
    val matchId = "m-${gameType.name}-${scoringMode.name}-$seed"
    matches += settleOneMatch(matchId, seed, run.matchScores, gameType, carried)
  }

  return SettlementStream(gameType, scoringMode, firstSeed, count, startingRp, matches)
}

/**
 * One finished match's four settlement rows, folded from the RP its seats
 * carried in. [carried] is advanced in place, so a caller settling a sequence
 * of matches gets the same dependent chain the stream produces.
 *
 * Extracted so the durable match (E9) settles through THIS code and not a
 * second derivation of the production formulas — one settlement path, one
 * arithmetic, whoever the caller is.
 */
fun settleOneMatch(
  matchId: String,
  seed: Int,
  matchScores: Map<String, Int>,
  gameType: GameType,
  carried: MutableMap<String, Int>,
): MatchSettlement {
  val totals = matchScores

  val ordinals = E7_SEATS.map { rankLadder.indexOf(rankAt(carried.getValue(E7_UID_OF_SEAT.getValue(it)))) }
  val tierIndexes = E7_SEATS.map { seat ->
    E7_TIERS.indexOf(rankAt(carried.getValue(E7_UID_OF_SEAT.getValue(seat))).tier)
  }
  // RD29: a table wider than one tier records the match but awards nothing.
  val noAward = tierIndexes.max() - tierIndexes.min() > 1

  val rows = E7_SEATS.mapIndexed { index, seat ->
    val uid = E7_UID_OF_SEAT.getValue(seat)
    val previousRp = carried.getValue(uid)
    val others = ordinals.filterIndexed { i, _ -> i != index }
    val opponentStrength = if (others.isEmpty()) 0 else others.sum() / others.size
    val resolution = if (noAward) {
      resolveRanked(previousRp, 0)
    } else {
      resolveRanked(
        previousRp,
        finalRpDelta(
          RpMatchInput(
            outcome = placementOutcome(totals, seat),
            playerOrdinal = ordinals[index],
            opponentStrength = opponentStrength,
            gameType = gameType,
            rankDown = false,
          ),
        ),
      )
    }
    carried[uid] = resolution.rp
    SettlementRow(
      matchId = matchId,
      seat = seat,
      uid = uid,
      placement = placementRank(totals, seat),
      outcome = placementOutcome(totals, seat).name,
      delta = resolution.delta,
      previousRp = resolution.previousRP,
      rp = resolution.rp,
      ladderIndex = rankLadder.indexOf(resolution.next),
      movement = resolution.movement,
    )
  }
  return MatchSettlement(matchId, seed, rows)
}

/** One match's settlement as the outbox payload: the four rows the ledger
 *  applies, plus the totals a relay can check without re-deriving them. */
fun renderMatchSettlement(set: MatchSettlement): String {
  val w = JsonWriter().beginObject()
  w.name("matchId").str(set.matchId)
  w.name("seed").num(set.seed)
  w.name("rows").beginArray()
  for (row in set.rows) {
    w.beginObject()
    w.name("seat").str(row.seat)
    w.name("uid").str(row.uid)
    w.name("placement").num(row.placement)
    w.name("outcome").str(row.outcome)
    w.name("delta").num(row.delta)
    w.name("previousRp").num(row.previousRp)
    w.name("rp").num(row.rp)
    w.name("ladderIndex").num(row.ladderIndex)
    w.name("movement").num(row.movement)
    w.endObject()
  }
  w.endArray()
  w.name("totalDelta").num(set.rows.sumOf { it.delta })
  w.endObject()
  return w.toString()
}

fun runSettlementStreamJson(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): String {
  val stream = buildSettlementStream(firstSeed, count, gameType, scoringMode)
  val body = renderSettlementStream(stream)
  return """{"fingerprint":"${fnv1a32(body)}","body":$body}"""
}

/**
 * The same stream, but starting from an **explicit** RP state instead of
 * [E7_STARTING_RP].
 *
 * This is the recompute path E7 needs: after a disaster restore the ledger
 * holds whatever the backup copy contained, so the missing settlements must be
 * recomputed *from that state* — not from the batch's original base, and not by
 * copying precomputed rows. Starting from a different base produces different
 * RP, which is exactly what makes the recompute comparison meaningful.
 *
 * [startingRpJson] is a flat JSON object of uid → RP, e.g. `{"u1":900,"u2":1020}`.
 */
fun runSettlementStreamFromJson(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
  startingRpJson: String,
): String {
  val stream = buildSettlementStream(firstSeed, count, gameType, scoringMode, parseRpMap(startingRpJson))
  val body = renderSettlementStream(stream)
  return """{"fingerprint":"${fnv1a32(body)}","body":$body}"""
}

/** Flat `{"uid":rp,...}` → RP map. A flat object only, because that is all the
 *  restored ledger state is; anything else is a caller bug and must not be
 *  silently coerced. */
internal fun parseRpMap(json: String): Map<String, Int> {
  val trimmed = json.trim()
  require(trimmed.startsWith("{") && trimmed.endsWith("}")) { "startingRp must be a JSON object" }
  val body = trimmed.substring(1, trimmed.length - 1).trim()
  if (body.isEmpty()) return emptyMap()
  val out = LinkedHashMap<String, Int>()
  for (entry in body.split(",")) {
    val pair = entry.trim()
    require(pair.isNotEmpty()) { "empty startingRp entry" }
    val colon = pair.indexOf(':')
    require(colon > 0) { "startingRp entry must be \"uid\":rp" }
    val uid = pair.substring(0, colon).trim().trim('"')
    val rp = pair.substring(colon + 1).trim().toInt()
    out[uid] = rp
  }
  return out
}

/** FNV-1a, 32-bit — the same hash E1–E6 use, declared per file exactly as
 *  `CapacityProbe.kt` and `RuleVectors.kt` already do. */
private fun fnv1a32(s: String): String {
  var hash = -0x7ee3623b // 0x811c9dc5
  for (c in s) {
    hash = hash xor c.code
    hash *= 0x01000193
  }
  return hash.toUInt().toString(16).padStart(8, '0')
}

private fun renderSettlementStream(stream: SettlementStream): String {
  val w = JsonWriter().beginObject()
  w.name("gameType").str(stream.gameType.name)
  w.name("scoringMode").str(stream.scoringMode.name)
  w.name("firstSeed").num(stream.firstSeed)
  w.name("count").num(stream.count)
  w.name("startingRp").beginObject()
  for ((uid, rp) in stream.startingRp) {
    w.name(uid).num(rp)
  }
  w.endObject()
  w.name("matches").beginArray()
  for (match in stream.matches) {
    w.beginObject()
    w.name("matchId").str(match.matchId)
    w.name("seed").num(match.seed)
    w.name("rows").beginArray()
    for (row in match.rows) {
      w.beginObject()
      w.name("seat").str(row.seat)
      w.name("uid").str(row.uid)
      w.name("placement").num(row.placement)
      w.name("outcome").str(row.outcome)
      w.name("delta").num(row.delta)
      w.name("previousRp").num(row.previousRp)
      w.name("rp").num(row.rp)
      w.name("ladderIndex").num(row.ladderIndex)
      w.name("movement").num(row.movement)
      w.endObject()
    }
    w.endArray()
    w.endObject()
  }
  w.endArray()
  w.name("totalRows").num(stream.rows.size)
  w.name("totalDelta").num(stream.totalDelta)
  w.name("noAwardRows").num(stream.noAwardRows)
  w.endObject()
  return w.toString()
}
