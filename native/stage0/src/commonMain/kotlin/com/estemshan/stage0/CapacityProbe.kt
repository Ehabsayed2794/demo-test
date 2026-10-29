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
import kotlin.time.TimeSource

/**
 * Stage 0 (E5): the per-match cost probe behind the capacity model.
 *
 * The loop is the [playMatch] driver verbatim — same roster, same seeded deals,
 * same guard rails — with one thing added: a clock around each engine call, so a
 * full match reduces to the numbers the capacity table needs:
 *
 *  - **requests** — every `emit`/`emitPlay` the driver submits is one client
 *    action, and in the proposed Worker→Durable-Object architecture every client
 *    action is one HTTP request. The probe counts them per match; it never
 *    invents traffic the engine does not produce.
 *  - **CPU** — `decideNs` is the brain (server-side work for a bot seat),
 *    `applyNs` is the reducer plus the session update — the server's validate +
 *    reduce + persist point for any seat. `transitionNs` is the server-only work
 *    between requests: bidding close, round scoring, round advance. All three
 *    are wall-clock over synchronous compute, which on a single-threaded isolate
 *    is the CPU figure to model with.
 *  - **storage** — modelled, not measured: the ADR's option B persists the match
 *    state once per applied action (one SQLite row per request) and settles once
 *    per match into D1. The probe deliberately does not render the state JSON —
 *    document sizes are a separate JVM measurement — so the timed sections stay
 *    pure engine work. E9/E10 own the real storage measurements.
 *
 * The battery document splits a deterministic `counts` section (fingerprinted,
 * so the JVM and workerd batteries must agree bytewise on counts) from a
 * `timings` section (inherently run-to-run variable, never fingerprinted).
 * Timing the match is the experiment; changing a single action would be a
 * parity defect, so the two live in separate sections with only one hashed.
 */
data class CapacityMatch(
  val seed: Int,
  val rounds: Int,
  val redeals: Int,
  val bidActions: Int,
  val cardActions: Int,
  /** bidActions + cardActions — the requests one match costs. */
  val actions: Int,
  val decideNs: Long,
  val applyNs: Long,
  val applyMaxNs: Long,
  val transitionNs: Long,
  val winners: List<String>,
)

data class CapacityBattery(
  val gameType: GameType,
  val scoringMode: ScoringMode,
  val firstSeed: Int,
  val count: Int,
  val matches: List<CapacityMatch>,
  val failures: List<SeedFailure>,
)

/** Play one match under the capacity probe. Throws on the same conditions [playMatch] throws. */
fun playCapacityMatch(
  seed: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): CapacityMatch {
  val seats = listOf("p1", "p2", "p3", "p4")
  val roster = mapOf(
    "p1" to (BotTier.EASY to BotPersonality.AGGRESSIVE),
    "p2" to (BotTier.MEDIUM to BotPersonality.BALANCED),
    "p3" to (BotTier.HARD to BotPersonality.CONSERVATIVE),
    "p4" to (BotTier.EXPERT to BotPersonality.TRICKSTER),
  )
  val tier = { seat: String -> roster.getValue(seat).first }
  val personality = { seat: String -> roster.getValue(seat).second }
  val clock = TimeSource.Monotonic

  var tick = 0L
  val session = GameSession(clock = { ++tick })
  session.init("ai", force = true)
  session.setGameType(gameType)
  session.setScoringMode(scoringMode)
  session.setRound(session.getRound().copy(maxRounds = gameType.baseRounds))
  session.setPlayers(seats.map {
    SessionPlayer(seatId = it, uid = null, displayName = "Bot $it", isAI = true)
  })

  var roundsPlayed = 0
  var redealCount = 0
  var bidActions = 0
  var cardActions = 0
  var decideNs = 0L
  var applyNs = 0L
  var applyMaxNs = 0L
  var transitionNs = 0L
  var t0 = clock.markNow()
  var t1 = clock.markNow()

  val maxRounds = session.getRound().maxRounds
  while (session.getRound().number <= maxRounds) {
    val round = session.getRound().number
    var multiplier = session.getRound().multiplier
    val dealer = session.getDealer() ?: error("no dealer in round $round")

    var dealSeed = seed.toLong() * 1_000_000L + round
    var hands = Dealer.dealHands(dealSeed)

    var bidding = session.initializeBiddingState(
      if (isFastRound(round, gameType)) {
        initFastRound(round, dealer, seats, multiplier, gameType, scoringMode)
      } else {
        initNormalRound(round, dealer, seats, multiplier, gameType, scoringMode)
      },
    )

    var outcome: BiddingOutcome? = null
    var guard = 0
    while (bidding.subPhase != BiddingPhase.DONE && guard++ < 400) {
      val seat = bidding.waitingFor ?: error("round $round: no seat waiting")
      t0 = clock.markNow()
      val intent = BidBrain.decide(bidding, hands.getValue(seat), seat, tier(seat), personality(seat))
      t1 = clock.markNow()
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
              initFastRound(round, dealer, seats, multiplier, gameType, scoringMode)
            } else {
              initNormalRound(round, dealer, seats, multiplier, gameType, scoringMode)
            },
          )
        }
        is EmitResult.Rejected -> error(
          "seed $seed round $round ($gameType/$scoringMode) for $seat: ${result.reason}",
        )
      }
      session.updateBiddingState(bidding)
      val t2 = clock.markNow()
      decideNs += (t1 - t0).inWholeNanoseconds
      applyNs += (t2 - t1).inWholeNanoseconds
      applyMaxNs = maxOf(applyMaxNs, (t2 - t1).inWholeNanoseconds)
      bidActions++
    }
    val resolved = outcome ?: error("round $round bidding ended without an outcome")

    var tA = clock.markNow()
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
    transitionNs += (clock.markNow() - tA).inWholeNanoseconds

    var trickGuard = 0
    while (table.phase != TablePhase.DONE && trickGuard++ < 400) {
      val seat = table.turn ?: error("round $round trick ${table.trickNo}: no turn")
      t0 = clock.markNow()
      val intent = PlayBrain.decide(table, seat, tier(seat))
      t1 = clock.markNow()
      table = when (val result = emitPlay(table, intent)) {
        is PlayEmit.Applied ->
          if (result.state.phase == TablePhase.RESOLVING) resolveTrick(result.state) else result.state
        is PlayEmit.Rejected -> error(
          "seed $seed round $round trick ${table.trickNo} ($gameType/$scoringMode) for $seat: ${result.reason}",
        )
      }
      session.updatePlayState(table)
      val t2 = clock.markNow()
      decideNs += (t1 - t0).inWholeNanoseconds
      applyNs += (t2 - t1).inWholeNanoseconds
      applyMaxNs = maxOf(applyMaxNs, (t2 - t1).inWholeNanoseconds)
      cardActions++
    }
    check(table.phase == TablePhase.DONE) { "round $round play never reached DONE" }

    tA = clock.markNow()
    val classic = scoringMode == ScoringMode.CLASSIC
    val score = calculateRoundScore(
      RoundScoreInput(
        round = round,
        order = seats,
        bids = capacityBidsFromRound(cfg),
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
    transitionNs += (clock.markNow() - tA).inWholeNanoseconds

    roundsPlayed++
    if (session.isMatchComplete()) break
    tA = clock.markNow()
    session.nextRound()
    transitionNs += (clock.markNow() - tA).inWholeNanoseconds
  }

  val scores = session.getMatchScores()
  return CapacityMatch(
    seed = seed,
    rounds = roundsPlayed,
    redeals = redealCount,
    bidActions = bidActions,
    cardActions = cardActions,
    actions = bidActions + cardActions,
    decideNs = decideNs,
    applyNs = applyNs,
    applyMaxNs = applyMaxNs,
    transitionNs = transitionNs,
    winners = computeWinner(scores),
  )
}

/** The E5 battery: [count] consecutive matches under one (gameType, scoringMode). */
fun playCapacityBattery(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): CapacityBattery {
  val matches = ArrayList<CapacityMatch>(count)
  val failures = ArrayList<SeedFailure>()
  for (seed in firstSeed until firstSeed + count) {
    try {
      matches += playCapacityMatch(seed, gameType, scoringMode)
    } catch (t: Throwable) {
      failures += SeedFailure(seed, t.message ?: t.toString())
    }
  }
  return CapacityBattery(gameType, scoringMode, firstSeed, count, matches, failures)
}

/**
 * The battery document. `counts` is deterministic and fingerprinted; `timings`
 * records what the clocks saw and is never compared across runs — only against
 * the plan's 10 ms CPU/request budget.
 */
fun runCapacityJson(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): String {
  val battery = playCapacityBattery(firstSeed, count, gameType, scoringMode)

  val counts = StringBuilder()
  counts.append(
    """{"gameType":"${battery.gameType.name}","scoringMode":"${battery.scoringMode.name}"""",
  )
  counts.append(""","firstSeed":${battery.firstSeed},"count":${battery.count},"matches":[""")
  battery.matches.forEachIndexed { i, m ->
    if (i > 0) counts.append(',')
    val winnersJson = m.winners.joinToString(",") { w -> "\"$w\"" }
    counts.append(
      """{"seed":${m.seed},"rounds":${m.rounds},"redeals":${m.redeals},""" +
        """"bidActions":${m.bidActions},"cardActions":${m.cardActions},"actions":${m.actions},""" +
        """"winners":[$winnersJson]}""",
    )
  }
  counts.append("],\"failures\":[")
  battery.failures.forEachIndexed { i, f ->
    if (i > 0) counts.append(',')
    counts.append("""{"seed":${f.seed},"message":"${jsonEscape(f.message)}"}""")
  }
  counts.append("]}")
  val countsJson = counts.toString()

  val timings = StringBuilder()
  timings.append(
    """{"gameType":"${battery.gameType.name}","scoringMode":"${battery.scoringMode.name}"""",
  )
  timings.append(""","firstSeed":${battery.firstSeed},"count":${battery.count},"matches":[""")
  battery.matches.forEachIndexed { i, m ->
    if (i > 0) timings.append(',')
    timings.append(
      """{"seed":${m.seed},"decideNs":${m.decideNs},"applyNs":${m.applyNs},""" +
        """"applyMaxNs":${m.applyMaxNs},"transitionNs":${m.transitionNs}}""",
    )
  }
  timings.append("]}")

  return """{"fingerprint":"${fnv1a32(countsJson)}","counts":$countsJson,"timings":$timings}"""
}

/** Scoring bids from a resolved round — the same derivation [playMatch] feeds
 *  `calculateRoundScore`; a file-local copy because the original is private. */
private fun capacityBidsFromRound(cfg: RoundCfg): Map<String, Bid> =
  cfg.estimates.keys.union(cfg.dashCallers).associateWith { seat ->
    when {
      cfg.dashCallers.contains(seat) -> Bid(BidType.DASHCALL, 0)
      (cfg.estimates[seat] ?: 0) == 0 -> Bid(BidType.DASH, 0)
      else -> Bid(BidType.TRICKS, cfg.estimates.getValue(seat))
    }
  }

/** Minimal JSON string escaping for engine error messages. */
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

/** FNV-1a over UTF-16 code units — the same hash the E1/E2 documents use,
 *  file-local so both files stay self-contained. */
private fun fnv1a32(s: String): String {
  var hash = -0x7ee3623b // 0x811c9dc5
  for (c in s) {
    hash = hash xor c.code
    hash *= 0x01000193
  }
  return hash.toUInt().toString(16).padStart(8, '0')
}
