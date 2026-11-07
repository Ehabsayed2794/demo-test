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
 * Stage 0 (E6, review item 6): the server-side CHAIN probe.
 *
 * The free plan allows 10 ms of CPU per request, and the E5/E6 per-action
 * figures say nothing about how much work lands in ONE request: whenever the
 * server executes several bot actions back-to-back without an intervening
 * human message (a trick played out between two human cards, a raise war among
 * bot seats, a takeover fast-forward, an all-bot finish), that whole run is one
 * invocation's CPU bill.
 *
 * This probe replays the same driver verbatim and measures exactly that: the
 * per-action CPU (brain + reducer, with the server-only transition work
 * conservatively attached to the action that preceded it — in production the
 * round-boundary work would run inside the same request), then computes the
 * longest consecutive run of actions that excludes a given set of "human"
 * seats:
 *
 *  - **0 humans** — the whole match as one chain: the all-bot fast-forward.
 *  - **1 human** (worst over the four attributions) — three bot seats chaining
 *    between two human messages.
 *  - **2 humans** (worst over the six pairs) — two bot seats chaining.
 *
 * A separate file, deliberately: the E5/E6 document shapes and fingerprints
 * stay reproducible untouched. Chain LENGTHS are deterministic and
 * fingerprinted; chain CPU is wall-clock and is not.
 */
data class CapacityChainMatch(
  val seed: Int,
  val actions: Int,
  /** Longest run of consecutive actions avoiding one seat (max over the 4 choices). */
  val longestOneHumanChainLen: Int,
  /** Longest run of consecutive actions avoiding two seats (max over the 6 pairs). */
  val longestTwoHumanChainLen: Int,
  val winners: List<String>,
)

data class CapacityChainBattery(
  val gameType: GameType,
  val scoringMode: ScoringMode,
  val firstSeed: Int,
  val count: Int,
  val matches: List<CapacityChainMatch>,
  val failures: List<SeedFailure>,
  /** Per-match chain CPU, ns — the same runs the counts describe. */
  val chainCpuNs: List<ChainCpu>,
)

data class ChainCpu(
  val seed: Int,
  /** Whole-match CPU (0 humans) — decide + apply + transitions. */
  val wholeMatchNs: Long,
  val oneHumanChainNs: Long,
  val twoHumanChainNs: Long,
)

/** Play one match under the chain probe. Throws on the same conditions [playMatch] throws. */
fun playCapacityChainMatch(
  seed: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): Pair<CapacityChainMatch, ChainCpu> {
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

  // Per-action record: acting seat + CPU (decide + apply, transitions attached
  // to the preceding action — conservative for chain accounting).
  val actionSeat = ArrayList<Int>(1200)
  val actionCpu = ArrayList<Long>(1200)

  var redealCount = 0
  var transitionNs = 0L

  fun seatIndex(seat: String): Int = seats.indexOf(seat)
  fun appendCpuToLast(extra: Long) {
    if (actionCpu.isNotEmpty()) actionCpu[actionCpu.size - 1] += extra
  }

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
      val si = seatIndex(seat)
      val t0 = clock.markNow()
      val intent = BidBrain.decide(bidding, hands.getValue(seat), seat, tier(seat), personality(seat))
      val t1 = clock.markNow()
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
      actionSeat.add(si)
      actionCpu.add(((t1 - t0) + (t2 - t1)).inWholeNanoseconds)
    }
    val resolved = outcome ?: error("round $round bidding ended without an outcome")

    val tA = clock.markNow()
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
      val si = seatIndex(seat)
      val t0 = clock.markNow()
      val intent = PlayBrain.decide(table, seat, tier(seat))
      val t1 = clock.markNow()
      table = when (val result = emitPlay(table, intent)) {
        is PlayEmit.Applied ->
          if (result.state.phase == TablePhase.RESOLVING) resolveTrick(result.state) else result.state
        is PlayEmit.Rejected -> error(
          "seed $seed round $round trick ${table.trickNo} ($gameType/$scoringMode) for $seat: ${result.reason}",
        )
      }
      session.updatePlayState(table)
      val t2 = clock.markNow()
      actionSeat.add(si)
      actionCpu.add(((t1 - t0) + (t2 - t1)).inWholeNanoseconds)
    }
    check(table.phase == TablePhase.DONE) { "round $round play never reached DONE" }

    val tB = clock.markNow()
    val classic = scoringMode == ScoringMode.CLASSIC
    val score = calculateRoundScore(
      RoundScoreInput(
        round = round,
        order = seats,
        bids = chainBidsFromRound(cfg),
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
    transitionNs += (clock.markNow() - tB).inWholeNanoseconds

    if (session.isMatchComplete()) break
    val tC = clock.markNow()
    session.nextRound()
    transitionNs += (clock.markNow() - tC).inWholeNanoseconds
  }
  // Round-boundary and redeal work runs inside a request in production; attach
  // it to the last action so no chain escapes the accounting.
  appendCpuToLast(transitionNs)

  // Longest consecutive run avoiding a set of human seats.
  fun longestRunExcluding(humans: Set<Int>): Pair<Int, Long> {
    var bestLen = 0
    var bestCpu = 0L
    var runLen = 0
    var runCpu = 0L
    for (i in actionSeat.indices) {
      if (actionSeat[i] in humans) {
        runLen = 0
        runCpu = 0L
      } else {
        runLen++
        runCpu += actionCpu[i]
        if (runLen > bestLen) bestLen = runLen
        if (runCpu > bestCpu) bestCpu = runCpu
      }
    }
    return bestLen to bestCpu
  }

  var oneHumanLen = 0
  var oneHumanCpu = 0L
  for (h in 0..3) {
    val (len, cpu) = longestRunExcluding(setOf(h))
    if (len > oneHumanLen) {
      oneHumanLen = len
      oneHumanCpu = cpu
    } else if (len == oneHumanLen && cpu > oneHumanCpu) {
      oneHumanCpu = cpu
    }
  }
  var twoHumanLen = 0
  var twoHumanCpu = 0L
  for (a in 0..3) for (b in a + 1..3) {
    val (len, cpu) = longestRunExcluding(setOf(a, b))
    if (len > twoHumanLen) {
      twoHumanLen = len
      twoHumanCpu = cpu
    } else if (len == twoHumanLen && cpu > twoHumanCpu) {
      twoHumanCpu = cpu
    }
  }

  val wholeMatchNs = actionCpu.sum()
  val match = CapacityChainMatch(
    seed = seed,
    actions = actionSeat.size,
    longestOneHumanChainLen = oneHumanLen,
    longestTwoHumanChainLen = twoHumanLen,
    winners = computeWinner(session.getMatchScores()),
  )
  val cpu = ChainCpu(
    seed = seed,
    wholeMatchNs = wholeMatchNs,
    oneHumanChainNs = oneHumanCpu,
    twoHumanChainNs = twoHumanCpu,
  )
  return match to cpu
}

/** The E6 chain battery: [count] consecutive matches under one (gameType, scoringMode). */
fun playCapacityChainBattery(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): CapacityChainBattery {
  val matches = ArrayList<CapacityChainMatch>(count)
  val failures = ArrayList<SeedFailure>()
  val cpus = ArrayList<ChainCpu>(count)
  for (seed in firstSeed until firstSeed + count) {
    try {
      val (m, c) = playCapacityChainMatch(seed, gameType, scoringMode)
      matches += m
      cpus += c
    } catch (t: Throwable) {
      failures += SeedFailure(seed, t.message ?: t.toString())
    }
  }
  return CapacityChainBattery(gameType, scoringMode, firstSeed, count, matches, failures, cpus)
}

/** The chain battery document — counts (chain lengths) fingerprinted, CPU not. */
fun runCapacityChainJson(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): String {
  val battery = playCapacityChainBattery(firstSeed, count, gameType, scoringMode)

  val counts = StringBuilder()
  counts.append(
    """{"gameType":"${battery.gameType.name}","scoringMode":"${battery.scoringMode.name}"""",
  )
  counts.append(""","firstSeed":${battery.firstSeed},"count":${battery.count},"matches":[""")
  battery.matches.forEachIndexed { i, m ->
    if (i > 0) counts.append(',')
    val winnersJson = m.winners.joinToString(",") { w -> "\"$w\"" }
    counts.append(
      """{"seed":${m.seed},"actions":${m.actions},""" +
        """"longestOneHumanChainLen":${m.longestOneHumanChainLen},""" +
        """"longestTwoHumanChainLen":${m.longestTwoHumanChainLen},"winners":[$winnersJson]}""",
    )
  }
  counts.append("],\"failures\":[")
  battery.failures.forEachIndexed { i, f ->
    if (i > 0) counts.append(',')
    counts.append("""{"seed":${f.seed},"message":"${chainJsonEscape(f.message)}"}""")
  }
  counts.append("]}")
  val countsJson = counts.toString()

  val timings = StringBuilder()
  timings.append(
    """{"gameType":"${battery.gameType.name}","scoringMode":"${battery.scoringMode.name}"""",
  )
  timings.append(""","firstSeed":${battery.firstSeed},"count":${battery.count},"matches":[""")
  battery.chainCpuNs.forEachIndexed { i, c ->
    if (i > 0) timings.append(',')
    timings.append(
      """{"seed":${c.seed},"wholeMatchNs":${c.wholeMatchNs},""" +
        """"oneHumanChainNs":${c.oneHumanChainNs},"twoHumanChainNs":${c.twoHumanChainNs}}""",
    )
  }
  timings.append("]}")

  return """{"fingerprint":"${chainFnv1a32(countsJson)}","counts":$countsJson,"timings":$timings}"""
}

private fun chainBidsFromRound(cfg: RoundCfg): Map<String, Bid> =
  cfg.estimates.keys.union(cfg.dashCallers).associateWith { seat ->
    when {
      cfg.dashCallers.contains(seat) -> Bid(BidType.DASHCALL, 0)
      (cfg.estimates[seat] ?: 0) == 0 -> Bid(BidType.DASH, 0)
      else -> Bid(BidType.TRICKS, cfg.estimates.getValue(seat))
    }
  }

private fun chainJsonEscape(s: String): String = buildString {
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

private fun chainFnv1a32(s: String): String {
  var hash = -0x7ee3623b // 0x811c9dc5
  for (c in s) {
    hash = hash xor c.code
    hash *= 0x01000193
  }
  return hash.toUInt().toString(16).padStart(8, '0')
}
