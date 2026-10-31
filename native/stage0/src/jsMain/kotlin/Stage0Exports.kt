// No package declaration, deliberately — the same reason :functions' Functions.kt
// has none. @JsExport builds the export name from the package chain, so a
// packaged declaration would surface as `com.estemshan.stage0.runMatch` on the
// compiled module. The Worker entry consumes flat names.

import com.estemshan.engine.Card
import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.engine.RANKS
import com.estemshan.stage0.DurableMatch
import com.estemshan.stage0.E9Step
import com.estemshan.stage0.E9Submit
import com.estemshan.stage0.runCapacityChainJson
import com.estemshan.stage0.runCapacityJson
import com.estemshan.stage0.runCapacityMixJson
import com.estemshan.stage0.runCorpusJson
import com.estemshan.stage0.runMatchJson
import com.estemshan.stage0.cardLogCounts
import com.estemshan.stage0.parseRpMap
import com.estemshan.stage0.renderMatchSettlement
import com.estemshan.stage0.takeoverDecisionJson
import com.estemshan.stage0.takeoverMarkerJson
import com.estemshan.stage0.runRuleVectorsText
import com.estemshan.stage0.runSettlementStreamFromJson
import com.estemshan.stage0.runSettlementStreamJson
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * E1's entry point as seen by the Worker: play one full match and hand back the
 * canonical JSON described in [runMatchJson]. Kept to a one-liner so the exported
 * surface contains no logic of its own to diverge from the JVM side.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runMatch(seed: Int, gameType: String, scoringMode: String): String =
  runMatchJson(seed, gameTypeOf(gameType), scoringModeOf(scoringMode))

/** E2's entry point: the corpus manifest for one (gameType, scoringMode) column. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runCorpus(firstSeed: Int, count: Int, gameType: String, scoringMode: String): String =
  runCorpusJson(firstSeed, count, gameTypeOf(gameType), scoringModeOf(scoringMode))

/**
 * E3's entry point: the whole rule/scoring vector table, rendered as the
 * deterministic line-oriented document the JVM side writes. One line per
 * vector case, so the comparison between the two runtimes is a byte
 * comparison.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runRuleVectors(): String = runRuleVectorsText()

/**
 * E5's entry point: the capacity battery — request counts (fingerprinted, so
 * the two runtimes must agree bytewise) plus the per-match timings the
 * capacity model is built from.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runCapacity(firstSeed: Int, count: Int, gameType: String, scoringMode: String): String =
  runCapacityJson(firstSeed, count, gameTypeOf(gameType), scoringModeOf(scoringMode))

/**
 * E6's entry point: the per-seat capacity battery — the same match stream as
 * E5's probe with every action attributed to the seat that acted, so the
 * human/bot mix can be applied as a model on top of measured loads.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runCapacityMix(firstSeed: Int, count: Int, gameType: String, scoringMode: String): String =
  runCapacityMixJson(firstSeed, count, gameTypeOf(gameType), scoringModeOf(scoringMode))

/**
 * E6's chain entry point: the longest run of consecutive server-side actions
 * that executes without an intervening human message, per seat attribution —
 * the quantity the 10 ms CPU-per-request budget actually bounds.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runCapacityChain(firstSeed: Int, count: Int, gameType: String, scoringMode: String): String =
  runCapacityChainJson(firstSeed, count, gameTypeOf(gameType), scoringModeOf(scoringMode))

/**
 * E7's entry point: the settlement stream a durable RP store has to apply
 * exactly once — one row per seat per finished match, RP carried seat to seat.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runSettlementStream(firstSeed: Int, count: Int, gameType: String, scoringMode: String): String =
  runSettlementStreamJson(firstSeed, count, gameTypeOf(gameType), scoringModeOf(scoringMode))

/**
 * E7's recompute entry point: the same stream, but starting from an explicit
 * RP state — the state a restored ledger actually holds. Used to recompute the
 * settlements a stale backup is missing instead of copying precomputed rows.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runSettlementStreamFrom(
  firstSeed: Int,
  count: Int,
  gameType: String,
  scoringMode: String,
  startingRpJson: String,
): String =
  runSettlementStreamFromJson(
    firstSeed,
    count,
    gameTypeOf(gameType),
    scoringModeOf(scoringMode),
    startingRpJson,
  )

// ── E9: the durable match, as the Worker sees it ──────────────────────
//
// The Worker owns the store; this surface only produces the record to write
// and consumes the rows back. Every call returns the state digest so the
// driver can compare it with the JVM leg, and every submission reports
// whether it wrote a record or recognised a unit that is already stored.

private var e9Match: DurableMatch? = null

/** Begin a match. The seed/game type/mode are the match's identity — they
 *  belong to the Durable Object's name, not to a log row. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9Start(seed: Int, gameType: String, scoringMode: String): String {
  val m = DurableMatch.start(seed, gameTypeOf(gameType), scoringModeOf(scoringMode))
  e9Match = m
  return """{"status":"started","applied":${m.recordsApplied},"digest":"${m.stateDigest()}","complete":${m.isComplete()}}"""
}

/** Produce and apply exactly one engine action. A non-null `record` is the
 *  single row the Worker must insert; its second field is the row id. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9Step(): String {
  val m = e9Match ?: return """{"status":"error","message":"no match"}"""
  val step = m.nextStep()
  if (step is E9Step.Done) {
    return """{"status":"done","applied":${m.recordsApplied},"digest":"${m.stateDigest()}","complete":${m.isComplete()}}"""
  }
  return submitJson(m, m.submit(step))
}

/** Play one named card (suit + rank) instead of asking the bot — the way the
 *  platform leg re-drives an interrupted trick with a client's own choice. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9PlayCard(seat: String, suit: String, rank: Int): String {
  val m = e9Match ?: return """{"status":"error","message":"no match"}"""
  val s = Suit.entries.firstOrNull { it.name == suit.uppercase() }
    ?: return """{"status":"error","message":"unknown suit"}"""
  val r = RANKS.firstOrNull { it.v == rank }
    ?: return """{"status":"error","message":"unknown rank"}"""
  return submitJson(m, m.submitCard(seat, Card(s, r)))
}

/**
 * The record of the next durable unit, produced WITHOUT applying it.
 *
 * This is the order a write-ahead store needs: the record must exist before
 * the row is written, and the engine may only advance once the row is in the
 * store. `record` is the single row to insert; its second field is the row id.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9NextUnit(): String {
  val m = e9Match ?: return """{"status":"error","message":"no match"}"""
  val record = m.nextUnitRecord()
    ?: return """{"done":true,"applied":${m.recordsApplied},"digest":"${m.stateDigest()}","complete":${m.isComplete()}}"""
  val kind = if (record.startsWith("B;")) "bid" else "trick"
  return """{"done":false,"kind":"$kind","record":"${jsonEscape(record)}","applied":${m.recordsApplied},"digest":"${m.stateDigest()}"}"""
}

/**
 * Apply a row that is already in the store. The record must be the next unit
 * and must reproduce itself exactly — the same check a cold start makes, so
 * the live write-ahead path and the recovery path agree row for row.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9Apply(record: String): String {
  val m = e9Match ?: return """{"status":"error","message":"no match"}"""
  return try {
    m.applyRecord(record)
    """{"status":"applied","applied":${m.recordsApplied},"digest":"${m.stateDigest()}","complete":${m.isComplete()}}"""
  } catch (e: Throwable) {
    """{"status":"error","message":"${jsonEscape(e.message ?: "replay failed")}","applied":${m.recordsApplied}}"""
  }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9Applied(): Int = e9Match?.recordsApplied ?: -1

@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9Complete(): Boolean = e9Match?.isComplete() ?: false

/**
 * The settlement this finished match owes — the outbox payload, derived from
 * the durable result and the RP each uid carried in (`startingRpJson`, a flat
 * `{"uid":rp}` object read from the RP store). Refuses an unfinished match:
 * an intent may only be derived from a match the log says is over.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9Settle(matchId: String, startingRpJson: String): String {
  val m = e9Match ?: return """{"status":"error","message":"no match"}"""
  return try {
    val set = m.settlementRows(matchId, parseRpMap(startingRpJson))
    """{"status":"ok","matchId":"${jsonEscape(matchId)}","payload":${renderMatchSettlement(set)}}"""
  } catch (e: Throwable) {
    """{"status":"error","message":"${jsonEscape(e.message ?: "settlement failed")}"}"""
  }
}

/**
 * E9 boundary 7: the takeover decision, answered by the engine's own RD19
 * clock. [openSinceMillis] is -1 when no window is open. Pure — the same
 * stored fields and the same [nowMillis] always give the same verdict, which
 * is what lets a restarted object reach it again.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9TakeoverDecision(
  accumulatedMillis: Double,
  openSinceMillis: Double,
  nowMillis: Double,
  borrowed: Boolean,
): String = takeoverDecisionJson(
  accumulatedMillis.toLong(),
  if (openSinceMillis < 0) null else openSinceMillis.toLong(),
  nowMillis.toLong(),
  borrowed,
)

/** The marker of a takeover, in the engine's `InactiveTakeover` field shape. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9TakeoverMarker(takenOverAtMillis: Double, round: Int, trick: Int): String =
  takeoverMarkerJson(takenOverAtMillis.toLong(), round, trick)

/**
 * The decision point the engine waits at — round, finished tricks in it, the
 * seat that owes the move, and the log head. The alarm handler stamps its RD19
 * marker from this, so the marker is the engine's own position rather than the
 * probe's guess at it.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9Position(): String = e9Match?.positionJson() ?: """{"status":"error","message":"no match"}"""

/**
 * Design A's unit stream for one match — every card play, every auction action
 * and (when [withTricks]) every Trick checkpoint — rendered as one row per
 * line. The workerd leg inserts it one unit per event to measure the per-unit
 * cost with the store's own cursor.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9CardLog(seed: Int, gameType: String, scoringMode: String, withTricks: Boolean): String {
  return try {
    val c = cardLogCounts(seed, gameTypeOf(gameType), scoringModeOf(scoringMode))
    val rows = c.units.filter { withTricks || it.kind != "TRICK" }.map { it.record }
    """{"status":"ok","seed":${c.seed},"bids":${c.bids},"tricks":${c.tricks},"cards":${c.cards},""" +
      """"checkpointPlusCards":${c.checkpointPlusCards},"cardsOnly":${c.cardsOnly},"units":[${rows.joinToString(",") { "\"${jsonEscape(it)}\"" }}]}"""
  } catch (e: Throwable) {
    """{"status":"error","message":"${jsonEscape(e.message ?: "card log failed")}"}"""
  }
}

/** Restart: rebuild the match from the stored rows alone. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9Recover(seed: Int, gameType: String, scoringMode: String, log: String): String {
  val m = DurableMatch.replay(log, seed, gameTypeOf(gameType), scoringModeOf(scoringMode))
  e9Match = m
  return """{"status":"recovered","applied":${m.recordsApplied},"digest":"${m.stateDigest()}","complete":${m.isComplete()}}"""
}

/**
 * Re-submit the unit held in [record] — the payload comes from the store row,
 * so this is the "client retried the action whose ack was lost" path. A
 * correct store answers `already` and writes nothing.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9Retry(record: String): String {
  val m = e9Match ?: return """{"status":"error","message":"no match"}"""
  return submitJson(m, m.retryStored(record))
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9Digest(): String = e9Match?.stateDigest() ?: ""

@OptIn(ExperimentalJsExport::class)
@JsExport
fun e9State(): String {
  val doc = e9Match?.stateDocument() ?: return ""
  return """{"digest":"${e9Match!!.stateDigest()}","document":"${jsonEscape(doc)}"}"""
}

private fun submitJson(m: DurableMatch, result: E9Submit): String = when (result) {
  is E9Submit.Applied -> {
    val record = result.record
    val key = record?.let { com.estemshan.stage0.parseRecord(it).key.toString() } ?: ""
    """{"status":"applied","record":${if (record == null) "null" else "\"${jsonEscape(record)}\""},"key":"$key","applied":${m.recordsApplied},"digest":"${m.stateDigest()}","complete":${m.isComplete()}}"""
  }
  is E9Submit.AlreadyApplied ->
    """{"status":"already","record":null,"key":"${result.key}","applied":${m.recordsApplied},"digest":"${m.stateDigest()}","complete":${m.isComplete()}}"""
  is E9Submit.Rejected ->
    """{"status":"rejected","message":"${jsonEscape(result.reason)}","applied":${m.recordsApplied},"digest":"${m.stateDigest()}","complete":${m.isComplete()}}"""
}

private fun jsonEscape(s: String): String = buildString {
  for (c in s) {
    when (c) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
    }
  }
}

private fun gameTypeOf(name: String): GameType =
  GameType.entries.firstOrNull { it.name == name.uppercase() }
    ?: error("unknown gameType: $name")

private fun scoringModeOf(name: String): ScoringMode =
  ScoringMode.entries.firstOrNull { it.name == name.uppercase() }
    ?: error("unknown scoringMode: $name")
