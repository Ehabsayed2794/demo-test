package com.estemshan.stage0

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.Dealer
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameSession
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.SessionPlayer
import com.estemshan.engine.TablePhase
import com.estemshan.engine.accumulateMatchScores
import com.estemshan.engine.bot.BidBrain
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.engine.bot.PlayBrain
import com.estemshan.engine.calculateRoundScore
import com.estemshan.engine.computeWinner
import com.estemshan.engine.emit
import com.estemshan.engine.emitPlay
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.isFastRound
import com.estemshan.engine.resolveTrick

/**
 * Stage 0 (E1): play one complete bot-vs-bot match through the real engine and
 * reduce it to a canonical JSON document.
 *
 * The driver is a lift of `BotVsBotSmokeTest.playMatch` — the S10 golden that
 * already proves a full 18-round match completes — so the match being measured
 * is the same one :engine's own suite pins, not a second implementation that
 * could agree with itself while disagreeing with the game.
 *
 * The ONLY stochastic input is the deal, and it is seeded, so one integer
 * reproduces a whole match. That is what makes the JVM/JS comparison
 * meaningful: same seed, same code, two runtimes, one expected byte string.
 *
 * Everything below is commonMain — Kotlin means the JVM build and the JS build
 * compile the same source, so a divergence in the output can only come from the
 * runtime (float formatting, collection order, string hashing), which is exactly
 * what E1 is probing for.
 */
private val SEATS = listOf("p1", "p2", "p3", "p4")

/** The smoke test's mixed roster: all four tiers and all four personalities in
 *  one match, so the harness exercises every brain, not just the safe ones. */
private val ROSTER: Map<String, Pair<BotTier, BotPersonality>> = mapOf(
  "p1" to (BotTier.EASY to BotPersonality.AGGRESSIVE),
  "p2" to (BotTier.MEDIUM to BotPersonality.BALANCED),
  "p3" to (BotTier.HARD to BotPersonality.CONSERVATIVE),
  "p4" to (BotTier.EXPERT to BotPersonality.TRICKSTER),
)

/**
 * One round, reduced to the fields that can differ between runtimes. Derived
 * from the settled round rather than from [com.estemshan.engine.RoundResult]
 * because the session's record drops two inputs the fingerprint needs —
 * `dashCallers` (a Dash-Call seat is absent from `estimates`, so the round's
 * bid types would otherwise be ambiguous) and `withPlayers`.
 */
data class RoundDigest(
  val round: Int,
  val trump: String,
  val callerId: String?,
  val withPlayers: List<String>,
  val leaderId: String,
  val tricksWon: Map<String, Int>,
  val estimates: Map<String, Int>,
  val dashCallers: List<String>,
  val riskPlayerId: String?,
  val totalBids: Int,
  val isOver: Boolean,
  val isSaayda: Boolean,
  val appliedMultiplier: Int,
  val nextMultiplier: Int,
)

data class MatchRun(
  val rounds: List<RoundDigest>,
  val dealers: List<String>,
  val redeals: Int,
  val matchScores: Map<String, Int>,
  val winners: List<String>,
)

/** Play the match [seed] deals, through the real session and both reducers. */
fun playMatch(seed: Int): MatchRun {
  val tier = { seat: String -> ROSTER.getValue(seat).first }
  val personality = { seat: String -> ROSTER.getValue(seat).second }

  var tick = 0L
  val session = GameSession(clock = { ++tick })
  session.init("ai", force = true)
  session.setPlayers(SEATS.map {
    SessionPlayer(seatId = it, uid = null, displayName = "Bot $it", isAI = true)
  })

  val rounds = ArrayList<RoundDigest>(18)
  val dealers = ArrayList<String>(18)
  var redealCount = 0

  val maxRounds = session.getRound().maxRounds
  while (session.getRound().number <= maxRounds) {
    val round = session.getRound().number
    var multiplier = session.getRound().multiplier
    val dealer = session.getDealer() ?: error("no dealer in round $round")
    dealers += dealer

    var dealSeed = seed.toLong() * 1_000_000L + round
    var hands = Dealer.dealHands(dealSeed)

    var bidding = session.initializeBiddingState(
      if (isFastRound(round)) initFastRound(round, dealer, SEATS, multiplier)
      else initNormalRound(round, dealer, SEATS, multiplier),
    )

    var outcome: BiddingOutcome? = null
    var guard = 0
    while (bidding.subPhase != BiddingPhase.DONE && guard++ < 400) {
      val seat = bidding.waitingFor ?: error("round $round: no seat waiting")
      val intent = BidBrain.decide(bidding, hands.getValue(seat), seat, tier(seat), personality(seat))
      bidding = when (val result = emit(bidding, intent)) {
        is EmitResult.Applied -> result.state
        is EmitResult.Completed -> {
          outcome = result.outcome
          result.state
        }
        is EmitResult.GeneralPass -> {
          check(++redealCount <= 40) { "round $round redealt 40 times — auction cannot close" }
          multiplier = result.doubledMultiplier
          dealSeed += 500L
          hands = Dealer.dealHands(dealSeed)
          session.initializeBiddingState(
            if (isFastRound(round)) initFastRound(round, dealer, SEATS, multiplier)
            else initNormalRound(round, dealer, SEATS, multiplier),
          )
        }
        is EmitResult.Rejected -> error(
          "BidBrain produced an illegal intent in round $round for $seat: ${result.reason}",
        )
      }
      session.updateBiddingState(bidding)
    }
    val resolved = outcome ?: error("round $round bidding ended without an outcome")

    session.completeBidding(resolved)
    val cfg = RoundCfg(
      round = round,
      trump = resolved.trump,
      callerId = resolved.callerId,
      withPlayers = resolved.withPlayers,
      estimates = resolved.estimates,
      dashCallers = resolved.dashCallers,
      leaderId = resolved.leaderId,
      riskId = resolved.riskPlayerId,
      multiplier = multiplier,
      hands = hands,
    )

    var table = session.initializePlayState(cfg)
    var trickGuard = 0
    while (table.phase != TablePhase.DONE && trickGuard++ < 400) {
      val seat = table.turn ?: error("round $round trick ${table.trickNo}: no turn")
      val intent = PlayBrain.decide(table, seat, tier(seat))
      table = when (val result = emitPlay(table, intent)) {
        is PlayEmit.Applied ->
          if (result.state.phase == TablePhase.RESOLVING) resolveTrick(result.state) else result.state
        is PlayEmit.Rejected -> error(
          "PlayBrain produced an illegal card in round $round trick ${table.trickNo} for $seat: ${result.reason}",
        )
      }
      session.updatePlayState(table)
    }
    check(table.phase == TablePhase.DONE) { "round $round play never reached DONE" }

    val score = calculateRoundScore(
      RoundScoreInput(
        round = round,
        order = SEATS,
        bids = bidsFromRound(cfg),
        tricksWon = table.tricksWon,
        callerId = cfg.callerId,
        withPlayers = cfg.withPlayers,
        multiplier = multiplier,
        riskPlayerId = cfg.riskId,
      ),
    )
    session.setMatchScores(accumulateMatchScores(session.getMatchScores(), score.deltas))
    session.completeRound(table.tricksWon, score.nextMultiplier)

    rounds += RoundDigest(
      round = round,
      trump = cfg.trump.name,
      callerId = cfg.callerId,
      withPlayers = cfg.withPlayers,
      leaderId = cfg.leaderId,
      tricksWon = table.tricksWon,
      estimates = cfg.estimates,
      dashCallers = cfg.dashCallers,
      riskPlayerId = score.riskPlayerId,
      totalBids = score.totalBids,
      isOver = score.isOver,
      isSaayda = score.isSaayda,
      appliedMultiplier = score.appliedMultiplier,
      nextMultiplier = score.nextMultiplier,
    )

    if (session.isMatchComplete()) break
    session.nextRound()
  }

  val scores = session.getMatchScores()
  return MatchRun(
    rounds = rounds,
    dealers = dealers,
    redeals = redealCount,
    matchScores = scores,
    winners = computeWinner(scores),
  )
}

/**
 * The canonical JSON for [seed]: a wrapper with a 32-bit fingerprint plus the
 * full body it was computed over.
 *
 * The fingerprint is the fast comparison (one short string per runtime); the
 * body is the evidence, so a mismatch can be diffed instead of just detected.
 * Every map is written seat-major in [SEATS] order and numbers are integers
 * only — no floating-point formatting can leak a runtime difference into the
 * document before the rules themselves have had their say.
 */
fun runMatchJson(seed: Int): String {
  val run = playMatch(seed)

  val body = JsonWriter().beginObject()
  body.name("seed").num(seed)
  body.name("rounds").num(run.rounds.size)
  body.name("redeals").num(run.redeals)
  body.name("dealers").strArray(run.dealers)

  body.name("history").beginArray()
  for (r in run.rounds) {
    body.beginObject()
    body.name("round").num(r.round)
    body.name("trump").str(r.trump)
    body.name("callerId").str(r.callerId)
    body.name("leaderId").str(r.leaderId)
    body.name("withPlayers").strArray(r.withPlayers)
    body.name("dashCallers").strArray(r.dashCallers)
    body.name("tricksWon").seatMap(r.tricksWon)
    body.name("estimates").seatMap(r.estimates)
    body.name("riskPlayerId").str(r.riskPlayerId)
    body.name("totalBids").num(r.totalBids)
    body.name("isOver").bool(r.isOver)
    body.name("isSaayda").bool(r.isSaayda)
    body.name("appliedMultiplier").num(r.appliedMultiplier)
    body.name("nextMultiplier").num(r.nextMultiplier)
    body.endObject()
  }
  body.endArray()

  body.name("matchScores").seatMap(run.matchScores)
  body.name("winners").strArray(run.winners)
  body.endObject()

  val json = body.toString()
  return """{"fingerprint":"${fnv1a32(json)}","body":$json}"""
}

/**
 * Scoring bids from a resolved round — the derivation `QuickMatchViewModel`'s
 * `bidsFromOutcome` performs: a Dash-Call seat keeps its type (estimates alone
 * cannot carry it), a 0 estimate is a Normal Dash, else TRICKS.
 */
private fun bidsFromRound(cfg: RoundCfg): Map<String, Bid> =
  cfg.estimates.keys.union(cfg.dashCallers).associateWith { seat ->
    when {
      cfg.dashCallers.contains(seat) -> Bid(BidType.DASHCALL, 0)
      (cfg.estimates[seat] ?: 0) == 0 -> Bid(BidType.DASH, 0)
      else -> Bid(BidType.TRICKS, cfg.estimates.getValue(seat))
    }
  }

/** FNV-1a, 32-bit, over the UTF-16 code units of [s]. Integer overflow wraps
 *  identically on both targets, so the value is a pure function of the text —
 *  which is the whole point of comparing it across runtimes. */
private fun fnv1a32(s: String): String {
  var hash = -0x7ee3623b // 0x811c9dc5
  for (c in s) {
    hash = hash xor c.code
    hash *= 0x01000193
  }
  return hash.toUInt().toString(16).padStart(8, '0')
}

/** Minimal deterministic JSON writer — no reflection, no property order to
 *  inherit from a runtime's map implementation. */
private class JsonWriter {
  private val sb = StringBuilder()
  private var empty = true

  private fun sep() {
    if (!empty) sb.append(',')
    empty = false
  }

  fun beginObject(): JsonWriter {
    sep()
    sb.append('{')
    empty = true
    return this
  }

  fun endObject(): JsonWriter {
    sb.append('}')
    empty = false
    return this
  }

  fun beginArray(): JsonWriter {
    sep()
    sb.append('[')
    empty = true
    return this
  }

  fun endArray(): JsonWriter {
    sb.append(']')
    empty = false
    return this
  }

  fun name(n: String): JsonWriter {
    sep()
    quote(n)
    sb.append(':')
    empty = true
    return this
  }

  fun str(v: String?): JsonWriter {
    sep()
    if (v == null) sb.append("null") else quote(v)
    return this
  }

  fun num(v: Int): JsonWriter {
    sep()
    sb.append(v)
    return this
  }

  fun bool(v: Boolean): JsonWriter {
    sep()
    sb.append(v)
    return this
  }

  fun strArray(values: List<String>): JsonWriter {
    beginArray()
    for (v in values) str(v)
    return endArray()
  }

  fun seatMap(m: Map<String, Int>): JsonWriter {
    beginObject()
    for (seat in SEATS) {
      name(seat)
      num(m[seat] ?: 0)
    }
    return endObject()
  }

  private fun quote(s: String) {
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
      }
    }
    sb.append('"')
  }

  override fun toString(): String = sb.toString()
}
