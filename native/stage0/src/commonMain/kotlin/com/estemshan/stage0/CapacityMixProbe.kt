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
 * Stage 0 (E6): the per-SEAT load probe behind the target-workload model.
 *
 * The owner's 2026-10-29 rulings (decision record §2/§4) make the human/bot mix
 * a first-class capacity input: bots execute inside the authoritative server
 * (their brain is server CPU but not an inbound client request), humans send
 * their actions as inbound messages, and the persistence boundary is the
 * completed Trick for card play while the auction stays per-action. None of
 * that can be modelled from E5's totals, which never recorded WHO acted.
 *
 * So this probe replays the same driver verbatim — same roster, same seeds —
 * and attributes every measured quantity to the seat that acted:
 *
 *  - **per-seat actions** — a seat's bid/card action counts are what the server
 *    receives as inbound messages from that seat if it is human, and performs
 *    internally if it is a bot;
 *  - **per-seat CPU** — `decideNs` (the brain) is server work only for a bot
 *    seat; `applyNs` (reducer + persist point) is server work for every seat;
 *  - **trick checkpoints** — the count of completed tricks, which is the number
 *    of persistence writes card play costs under the ruled per-Trick boundary.
 *
 * The probe itself still plays all-bot matches (the harness's one honest way to
 * finish a match); the human/bot split is applied in the model on top of these
 * per-seat loads, per scenario. No distribution is invented here.
 *
 * Like E5's document: a deterministic `counts` section (fingerprinted) and a
 * non-fingerprinted `timings` section. E5's own document shape and fingerprint
 * are untouched — this probe is a separate file with its own hash.
 */
data class SeatLoad(
  val bidActions: Int,
  val cardActions: Int,
  val decideNs: Long,
  val applyNs: Long,
)

data class CapacityMixMatch(
  val seed: Int,
  val rounds: Int,
  val redeals: Int,
  /** p1..p4 — the seat that acted owns every number. */
  val seats: Map<String, SeatLoad>,
  val bidActions: Int,
  val cardActions: Int,
  val actions: Int,
  /** Completed tricks = the per-Trick persistence writes card play costs. */
  val trickCheckpoints: Int,
  val decideNs: Long,
  val applyNs: Long,
  val applyMaxNs: Long,
  val transitionNs: Long,
  val winners: List<String>,
)

data class CapacityMixBattery(
  val gameType: GameType,
  val scoringMode: ScoringMode,
  val firstSeed: Int,
  val count: Int,
  val matches: List<CapacityMixMatch>,
  val failures: List<SeedFailure>,
)

/** Play one match under the per-seat probe. Throws on the same conditions [playMatch] throws. */
fun playCapacityMixMatch(
  seed: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): CapacityMixMatch {
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

  val seatBid = IntArray(4)
  val seatCard = IntArray(4)
  val seatDecide = LongArray(4)
  val seatApply = LongArray(4)
  var roundsPlayed = 0
  var redealCount = 0
  var applyMaxNs = 0L
  var transitionNs = 0L
  var t0 = clock.markNow()
  var t1 = clock.markNow()

  fun seatIndex(seat: String): Int = seats.indexOf(seat)

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
      seatDecide[si] += (t1 - t0).inWholeNanoseconds
      seatApply[si] += (t2 - t1).inWholeNanoseconds
      applyMaxNs = maxOf(applyMaxNs, (t2 - t1).inWholeNanoseconds)
      seatBid[si]++
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
      val si = seatIndex(seat)
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
      seatDecide[si] += (t1 - t0).inWholeNanoseconds
      seatApply[si] += (t2 - t1).inWholeNanoseconds
      applyMaxNs = maxOf(applyMaxNs, (t2 - t1).inWholeNanoseconds)
      seatCard[si]++
    }
    check(table.phase == TablePhase.DONE) { "round $round play never reached DONE" }

    tA = clock.markNow()
    val classic = scoringMode == ScoringMode.CLASSIC
    val score = calculateRoundScore(
      RoundScoreInput(
        round = round,
        order = seats,
        bids = mixBidsFromRound(cfg),
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

  val seatsOut = seats.associateWith { seat ->
    val si = seats.indexOf(seat)
    SeatLoad(bidActions = seatBid[si], cardActions = seatCard[si], decideNs = seatDecide[si], applyNs = seatApply[si])
  }
  return CapacityMixMatch(
    seed = seed,
    rounds = roundsPlayed,
    redeals = redealCount,
    seats = seatsOut,
    bidActions = seatBid.sum(),
    cardActions = seatCard.sum(),
    actions = seatBid.sum() + seatCard.sum(),
    trickCheckpoints = seatCard.sum() / 4,
    decideNs = seatDecide.sum(),
    applyNs = seatApply.sum(),
    applyMaxNs = applyMaxNs,
    transitionNs = transitionNs,
    winners = computeWinner(session.getMatchScores()),
  )
}

/** The E6 battery: [count] consecutive matches under one (gameType, scoringMode). */
fun playCapacityMixBattery(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): CapacityMixBattery {
  val matches = ArrayList<CapacityMixMatch>(count)
  val failures = ArrayList<SeedFailure>()
  for (seed in firstSeed until firstSeed + count) {
    try {
      matches += playCapacityMixMatch(seed, gameType, scoringMode)
    } catch (t: Throwable) {
      failures += SeedFailure(seed, t.message ?: t.toString())
    }
  }
  return CapacityMixBattery(gameType, scoringMode, firstSeed, count, matches, failures)
}

/**
 * The E6 battery document — the same counts/timings split as E5's, with the
 * per-seat attribution the mix model needs. `counts` is fingerprinted;
 * `timings` is not.
 */
fun runCapacityMixJson(
  firstSeed: Int,
  count: Int,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): String {
  val battery = playCapacityMixBattery(firstSeed, count, gameType, scoringMode)
  val seatOrder = listOf("p1", "p2", "p3", "p4")

  val counts = StringBuilder()
  counts.append(
    """{"gameType":"${battery.gameType.name}","scoringMode":"${battery.scoringMode.name}"""",
  )
  counts.append(""","firstSeed":${battery.firstSeed},"count":${battery.count},"matches":[""")
  battery.matches.forEachIndexed { i, m ->
    if (i > 0) counts.append(',')
    val winnersJson = m.winners.joinToString(",") { w -> "\"$w\"" }
    val seatsJson = seatOrder.joinToString(",") { s ->
      val l = m.seats.getValue(s)
      """"$s":{"bidActions":${l.bidActions},"cardActions":${l.cardActions}}"""
    }
    counts.append(
      """{"seed":${m.seed},"rounds":${m.rounds},"redeals":${m.redeals},""" +
        """"bidActions":${m.bidActions},"cardActions":${m.cardActions},"actions":${m.actions},""" +
        """"trickCheckpoints":${m.trickCheckpoints},"seats":{$seatsJson},"winners":[$winnersJson]}""",
    )
  }
  counts.append("],\"failures\":[")
  battery.failures.forEachIndexed { i, f ->
    if (i > 0) counts.append(',')
    counts.append("""{"seed":${f.seed},"message":"${mixJsonEscape(f.message)}"}""")
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
    val seatsJson = seatOrder.joinToString(",") { s ->
      val l = m.seats.getValue(s)
      """"$s":{"decideNs":${l.decideNs},"applyNs":${l.applyNs}}"""
    }
    timings.append(
      """{"seed":${m.seed},"decideNs":${m.decideNs},"applyNs":${m.applyNs},""" +
        """"applyMaxNs":${m.applyMaxNs},"transitionNs":${m.transitionNs},"seats":{$seatsJson}}""",
    )
  }
  timings.append("]}")

  return """{"fingerprint":"${mixFnv1a32(countsJson)}","counts":$countsJson,"timings":$timings}"""
}

/** Scoring bids from a resolved round — file-local copy of the harness's private derivation. */
private fun mixBidsFromRound(cfg: RoundCfg): Map<String, Bid> =
  cfg.estimates.keys.union(cfg.dashCallers).associateWith { seat ->
    when {
      cfg.dashCallers.contains(seat) -> Bid(BidType.DASHCALL, 0)
      (cfg.estimates[seat] ?: 0) == 0 -> Bid(BidType.DASH, 0)
      else -> Bid(BidType.TRICKS, cfg.estimates.getValue(seat))
    }
  }

private fun mixJsonEscape(s: String): String = buildString {
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

private fun mixFnv1a32(s: String): String {
  var hash = -0x7ee3623b // 0x811c9dc5
  for (c in s) {
    hash = hash xor c.code
    hash *= 0x01000193
  }
  return hash.toUInt().toString(16).padStart(8, '0')
}
