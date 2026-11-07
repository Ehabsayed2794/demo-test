package com.estemshan.stage0

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.Dealer
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameSession
import com.estemshan.engine.GameType
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.ScoringMode
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
 * Stage 0 (E1/E2): play complete bot-vs-bot matches through the real engine and
 * reduce them to canonical JSON documents.
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
 * E2 adds the two axes the parity corpus needs — [GameType] (FULL 18 / MINI 10)
 * and [ScoringMode] (NORMAL / CLASSIC) — applied the way the product applies
 * them: `setGameType` for the rules, an explicit `RoundState.maxRounds` for the
 * ceiling (`GameSession.setGameType` deliberately leaves the current round
 * alone), and both stamped into the bidding state and the round score.
 *
 * Everything below is commonMain — Kotlin means the JVM build and the JS build
 * compile the same source, so a divergence in the output can only come from the
 * runtime (float formatting, collection order, string hashing), which is exactly
 * what the comparison is probing for.
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
  val seed: Int,
  val gameType: GameType,
  val scoringMode: ScoringMode,
  val rounds: List<RoundDigest>,
  val dealers: List<String>,
  val redeals: Int,
  val matchScores: Map<String, Int>,
  val winners: List<String>,
)

/** Play the match [seed] deals, through the real session and both reducers. */
fun playMatch(
  seed: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): MatchRun {
  val tier = { seat: String -> ROSTER.getValue(seat).first }
  val personality = { seat: String -> ROSTER.getValue(seat).second }

  var tick = 0L
  val session = GameSession(clock = { ++tick })
  session.init("ai", force = true)
  // Both are applied the way OnlineMatchViewModel applies a match document:
  // the type drives the rules, the round's own maxRounds drives the ceiling.
  // setGameType on its own would leave the FULL ceiling in place.
  session.setGameType(gameType)
  session.setScoringMode(scoringMode)
  session.setRound(session.getRound().copy(maxRounds = gameType.baseRounds))
  session.setPlayers(SEATS.map {
    SessionPlayer(seatId = it, uid = null, displayName = "Bot $it", isAI = true)
  })

  val rounds = ArrayList<RoundDigest>(gameType.baseRounds)
  val dealers = ArrayList<String>(gameType.baseRounds)
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
      if (isFastRound(round, gameType)) {
        initFastRound(round, dealer, SEATS, multiplier, gameType, scoringMode)
      } else {
        initNormalRound(round, dealer, SEATS, multiplier, gameType, scoringMode)
      },
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
            if (isFastRound(round, gameType)) {
              initFastRound(round, dealer, SEATS, multiplier, gameType, scoringMode)
            } else {
              initNormalRound(round, dealer, SEATS, multiplier, gameType, scoringMode)
            },
          )
        }
        is EmitResult.Rejected -> error(
          "seed $seed round $round ($gameType/$scoringMode) for $seat: ${result.reason}",
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
          "seed $seed round $round trick ${table.trickNo} ($gameType/$scoringMode) for $seat: ${result.reason}",
        )
      }
      session.updatePlayState(table)
    }
    check(table.phase == TablePhase.DONE) { "round $round play never reached DONE" }

    val classic = scoringMode == ScoringMode.CLASSIC
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
        classic = classic,
        escalationCap = if (classic) 2 else 8,
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
    seed = seed,
    gameType = gameType,
    scoringMode = scoringMode,
    rounds = rounds,
    dealers = dealers,
    redeals = redealCount,
    matchScores = scores,
    winners = computeWinner(scores),
  )
}

/**
 * The canonical JSON for one match: a wrapper with a 32-bit fingerprint plus the
 * full body it was computed over.
 *
 * The fingerprint is the fast comparison (one short string per match); the body
 * is the evidence, so a mismatch can be diffed instead of just detected. Every
 * map is written seat-major in [SEATS] order and numbers are integers only — no
 * floating-point formatting can leak a runtime difference into the document
 * before the rules themselves have had their say.
 */
fun runMatchJson(
  seed: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): String = renderDocument(playMatch(seed, gameType, scoringMode))

/**
 * E2's corpus manifest: the fingerprints and final scores of [count] consecutive
 * matches under one (gameType, scoringMode) combination.
 *
 * Deliberately compact — a byte-level diff over 200 full documents is
 * unreadable, and the per-match document stays reachable through [runMatchJson]
 * for any seed the manifest flags. The manifest still carries what the plan's
 * evidence line asks for: the seed list, per-seat scores, and a fingerprint that
 * covers every action of every round.
 */
fun runCorpusJson(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): String = renderCorpus(playCorpus(firstSeed, count, gameType, scoringMode))

/** A corpus column: the matches that completed, and the seeds the bots could
 *  not legally finish. */
data class CorpusRun(
  val gameType: GameType,
  val scoringMode: ScoringMode,
  val firstSeed: Int,
  val count: Int,
  val matches: List<MatchRun>,
  val failures: List<SeedFailure>,
)

/** A seed the drivers could not finish, with the reason. The message is built by
 *  common code, so it is comparable across runtimes too — if the two runtimes
 *  ever disagree about WHY a seed failed, that is itself a parity finding. */
data class SeedFailure(val seed: Int, val message: String)

fun playCorpus(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): CorpusRun {
  val rows = ArrayList<MatchRun>(count)
  val failures = ArrayList<SeedFailure>()
  for (seed in firstSeed until firstSeed + count) {
    try {
      rows += playMatch(seed, gameType, scoringMode)
    } catch (t: Throwable) {
      // A seed the bots cannot legally finish is a finding, not a reason to
      // abandon the rest of the corpus: it is recorded by seed so the report can
      // name it, and both runtimes must fail on exactly the same ones.
      failures += SeedFailure(seed, t.message ?: t.toString())
    }
  }
  return CorpusRun(gameType, scoringMode, firstSeed, count, rows, failures)
}

private fun renderCorpus(corpus: CorpusRun): String {
  val w = JsonWriter().beginObject()
  w.name("gameType").str(corpus.gameType.name)
  w.name("scoringMode").str(corpus.scoringMode.name)
  w.name("firstSeed").num(corpus.firstSeed)
  w.name("count").num(corpus.count)
  w.name("totalRounds").num(corpus.matches.sumOf { it.rounds.size })
  w.name("failures").beginArray()
  for (f in corpus.failures) {
    w.beginObject()
    w.name("seed").num(f.seed)
    w.name("message").str(f.message)
    w.endObject()
  }
  w.endArray()
  w.name("matches").beginArray()
  for (run in corpus.matches) {
    w.beginObject()
    w.name("seed").num(run.seed)
    w.name("rounds").num(run.rounds.size)
    // The same fingerprint runMatchJson wraps for this seed — one document,
    // one hash, whether it is fetched alone or as a row of the corpus.
    w.name("fingerprint").str(fnv1a32(renderBody(run)))
    w.name("scores").seatMap(run.matchScores)
    w.name("winners").strArray(run.winners)
    w.endObject()
  }
  w.endArray()
  w.endObject()

  val json = w.toString()
  return """{"fingerprint":"${fnv1a32(json)}","body":$json}"""
}

/** The wrapper [runMatchJson] returns: fingerprint over the body, then the body. */
private fun renderDocument(run: MatchRun): String {
  val body = renderBody(run)
  return """{"fingerprint":"${fnv1a32(body)}","body":$body}"""
}

private fun renderBody(run: MatchRun): String {
  val body = JsonWriter().beginObject()
  body.name("seed").num(run.seed)
  body.name("gameType").str(run.gameType.name)
  body.name("scoringMode").str(run.scoringMode.name)
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
  return body.toString()
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
internal class JsonWriter {
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
