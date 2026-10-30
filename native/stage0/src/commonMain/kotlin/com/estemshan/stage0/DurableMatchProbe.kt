package com.estemshan.stage0

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.Dealer
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameSession
import com.estemshan.engine.GameType
import com.estemshan.engine.Play
import com.estemshan.engine.PlayCard
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.SessionPlayer
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
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
 * Stage 0 (E9) — a match whose only durable truth is a log of records.
 *
 * The owner's ruling is persistence per completed Trick (card play) and per
 * action (auction), so this probe makes exactly those units durable:
 *
 *  - **one `B` record per auction action** — every successful `emit` call,
 *    including the Dash-Call declarations, the auction bids, the confirm and
 *    the estimates, plus a General-Pass action (which re-deals the same round);
 *  - **one `T` record per completed Trick** — the four cards in play order.
 *
 * Nothing else is written. The match header (seed / game type / scoring mode),
 * the round header (dealer / multiplier / deal seed) and the current trick are
 * all DERIVED: the header from the record's own position, the deal from the
 * seed, and the in-progress trick from re-driving it. That is what keeps the
 * row shape at one row per durable unit (428/full match in the E7 model).
 *
 * The engine has no state serializer, so a "checkpoint" cannot be an opaque
 * snapshot: the durable form is the record, and recovery is a replay through
 * the SAME reducer calls the live path uses (`emit`, `emitPlay`,
 * `resolveTrick`, the scoring wrapper). Recovery never asks a bot what it
 * would do — the cards come out of the record, so a real client's choice is
 * preserved exactly as a bot's is.
 *
 * Idempotency is a property of the record, not of a second table:
 *
 *  - the record's first field is the global `seq`, which is also the store's
 *    row id (a plain rowid table, no second index, no separate key row);
 *  - the next field pair is the record's natural position — `(round, unit,
 *    index)` — dense and monotonic, so "which unit is this?" is answerable
 *    from the row alone;
 *  - a retried action is recognised by comparing it with the unit already at
 *    the head of the log. If it matches, the write is skipped entirely
 *    ([Submit.AlreadyApplied]); there is no second write to lose.
 *
 * The probe therefore makes the boundary-1/boundary-2 pair a single
 * comparison: a retry after "checkpoint written, ack lost" matches the head
 * and is acknowledged without writing; a retry after "crash before the
 * checkpoint" does not match the head and is applied, producing the same
 * record the killed process would have produced.
 */

private val E9_SEATS = listOf("p1", "p2", "p3", "p4")

/** The same mixed roster `MatchHarness` drives, so the durable path is the
 *  same match the E1/E2 goldens already pin. */
private val E9_ROSTER: Map<String, Pair<BotTier, BotPersonality>> = mapOf(
  "p1" to (BotTier.EASY to BotPersonality.AGGRESSIVE),
  "p2" to (BotTier.MEDIUM to BotPersonality.BALANCED),
  "p3" to (BotTier.HARD to BotPersonality.CONSERVATIVE),
  "p4" to (BotTier.EXPERT to BotPersonality.TRICKSTER),
)

enum class E9Unit { BID, TRICK }

/** A record's natural position inside the match. Dense: a round's bid units
 *  are numbered 1..n and its tricks 1..13, and bids precede tricks. */
data class E9Key(val round: Int, val unit: E9Unit, val index: Int) : Comparable<E9Key> {
  override fun compareTo(other: E9Key): Int {
    if (round != other.round) return round.compareTo(other.round)
    if (unit != other.unit) return unit.ordinal.compareTo(other.unit.ordinal)
    return index.compareTo(other.index)
  }

  override fun toString(): String = "$round/${if (unit == E9Unit.BID) "B" else "T"}/$index"
}

/** One parsed durable unit. [seq] is both the record's identity and the store
 *  row id; [key] is its position, carried inside the same text. */
sealed class E9Record {
  abstract val seq: Int
  abstract val key: E9Key
  open val intent: BiddingIntent? get() = null
  open val plays: List<Play> get() = emptyList()

  data class BidUnit(
    override val seq: Int,
    override val key: E9Key,
    override val intent: BiddingIntent,
  ) : E9Record()

  data class TrickUnit(
    override val seq: Int,
    override val key: E9Key,
    override val plays: List<Play>,
  ) : E9Record()
}

sealed class E9Step {
  data class Bid(val intent: BiddingIntent) : E9Step()
  /** Named CardPlay, not Card: a nested `Card` would shadow the engine's. */
  data class CardPlay(val seat: String, val card: Card) : E9Step()
  object Done : E9Step()
}

sealed class E9Submit {
  /** Applied. [record] is non-null only when a durable unit completed — that
   *  string is the row to insert (and it carries its own id). */
  data class Applied(val record: String?) : E9Submit()
  /** The unit is already at the head of the log; nothing was written. */
  data class AlreadyApplied(val key: E9Key) : E9Submit()
  data class Rejected(val reason: String) : E9Submit()
}

class DurableMatch private constructor(
  val seed: Int,
  val gameType: GameType,
  val scoringMode: ScoringMode,
) {

  private var tick = 0L
  private val session = GameSession(clock = { ++tick })

  private var dealerSeat: String = ""
  private var bidding: BiddingState? = null
  private var table: TableState? = null
  private var hands: Map<String, List<Card>> = emptyMap()
  private var dealSeed = 0L
  private var roundMultiplier = 1
  private var redeals = 0
  private var bidIndex = 0

  private val dealers = ArrayList<String>()
  private val roundDigests = ArrayList<RoundDigest>()

  private var applied = 0
  private var lastKey: E9Key? = null
  private var lastRecord: String? = null

  val recordsApplied: Int get() = applied
  val lastAppliedKey: E9Key? get() = lastKey
  val lastAppliedRecord: String? get() = lastRecord

  /** The in-flight round state, for a driver that needs to choose a card
   *  itself (a client's own choice, not the bot's). */
  fun currentTable(): TableState? = table

  // ── start ─────────────────────────────────────────────────────────

  private fun bootstrap() {
    session.init("ai", force = true)
    session.setGameType(gameType)
    session.setScoringMode(scoringMode)
    session.setRound(session.getRound().copy(maxRounds = gameType.baseRounds))
    session.setPlayers(
      E9_SEATS.map { SessionPlayer(seatId = it, uid = null, displayName = "Bot $it", isAI = true) },
    )
    beginRound()
  }

  private fun beginRound() {
    val round = session.getRound().number
    roundMultiplier = session.getRound().multiplier
    val dealer = session.getDealer() ?: error("no dealer in round $round")
    dealerSeat = dealer
    dealers += dealer
    dealSeed = seed.toLong() * 1_000_000L + round
    hands = Dealer.dealHands(dealSeed)
    bidIndex = 0
    bidding = session.initializeBiddingState(freshBidding(round))
    table = null
  }

  private fun freshBidding(round: Int): BiddingState =
    if (isFastRound(round, gameType)) {
      initFastRound(round, dealerSeat, E9_SEATS, roundMultiplier, gameType, scoringMode)
    } else {
      initNormalRound(round, dealerSeat, E9_SEATS, roundMultiplier, gameType, scoringMode)
    }

  // ── the live path ─────────────────────────────────────────────────

  fun isComplete(): Boolean =
    session.isMatchComplete() &&
      table?.phase == TablePhase.DONE &&
      bidding?.subPhase == BiddingPhase.DONE

  /** What the engine is waiting for next. Bots stand in for clients here; the
   *  intent they produce is exactly what a client would send. */
  fun nextStep(): E9Step {
    val b = bidding
    if (b != null && b.subPhase != BiddingPhase.DONE) {
      val seat = b.waitingFor ?: error("round ${b.round}: no seat waiting")
      val (tier, personality) = E9_ROSTER.getValue(seat)
      return E9Step.Bid(BidBrain.decide(b, hands.getValue(seat), seat, tier, personality))
    }
    val t = table
    if (t != null && t.phase != TablePhase.DONE) {
      val seat = t.turn ?: error("round ${t.cfg.round} trick ${t.trickNo}: no turn")
      val intent = PlayBrain.decide(t, seat, E9_ROSTER.getValue(seat).first)
      return E9Step.CardPlay(intent.playerId, intent.card)
    }
    return E9Step.Done
  }

  fun submit(step: E9Step): E9Submit = when (step) {
    is E9Step.Bid -> submitBid(step.intent)
    is E9Step.CardPlay -> submitCard(step.seat, step.card)
    E9Step.Done -> E9Submit.Rejected("match is complete")
  }

  fun submitBid(intent: BiddingIntent): E9Submit {
    // Duplicate check FIRST, and against the head row only: a retried action
    // is the unit that was just applied, and that unit is the head.
    val head = lastRecord
    if (head != null && head.startsWith("B;") && bidPayloadOf(head) == encodeBidPayload(intent)) {
      return E9Submit.AlreadyApplied(keyOf(head))
    }
    val b = bidding ?: return E9Submit.Rejected("no bidding state")
    if (b.subPhase == BiddingPhase.DONE) return E9Submit.Rejected("bidding is complete")
    val record = applyBidUnit(intent) ?: return E9Submit.Rejected("bid rejected by the engine")
    return E9Submit.Applied(record)
  }

  fun submitCard(seat: String, card: Card): E9Submit {
    val head = lastRecord
    if (head != null && head.startsWith("T;")) {
      val record = parseRecord(head) as? E9Record.TrickUnit
      val last = record?.plays?.lastOrNull()
      if (last != null && last.playerId == seat &&
        last.card.suit == card.suit && last.card.rank.v == card.rank.v
      ) {
        return E9Submit.AlreadyApplied(record.key)
      }
    }
    val t = table ?: return E9Submit.Rejected("no table")
    if (t.phase != TablePhase.PLAY) return E9Submit.Rejected("round is done")
    if (t.turn != seat) return E9Submit.Rejected("not $seat's turn")
    val inHand = t.cfg.hands[seat]?.any { it.suit == card.suit && it.rank.v == card.rank.v } == true
    if (!inHand) return E9Submit.Rejected("$seat does not hold $card")
    return E9Submit.Applied(playCard(seat, card))
  }

  /**
   * Re-submit the unit stored in [recordText] — the "client retried the
   * unacked action" path, with the payload taken from the store row itself.
   */
  fun retryStored(recordText: String): E9Submit {
    return when (val parsed = parseRecord(recordText)) {
      is E9Record.BidUnit -> submitBid(parsed.intent)
      is E9Record.TrickUnit -> {
        val last = parsed.plays.lastOrNull() ?: return E9Submit.Rejected("empty trick record")
        submitCard(last.playerId, last.card)
      }
    }
  }

  private fun applyBidUnit(intent: BiddingIntent): String? {
    val b = bidding ?: error("no bidding state")
    val key = E9Key(b.round, E9Unit.BID, bidIndex + 1)
    val seq = applied + 1
    val record = "B;$seq;${key.round};${key.index};${encodeBidPayload(intent)}"

    when (val r = emit(b, intent)) {
      is EmitResult.Rejected -> return null
      is EmitResult.Applied -> {
        bidding = r.state
        session.updateBiddingState(r.state)
      }
      is EmitResult.Completed -> {
        bidding = r.state
        session.updateBiddingState(r.state)
        session.completeBidding(r.outcome)
        openTable(r.outcome)
      }
      is EmitResult.GeneralPass -> {
        bidding = r.state
        session.updateBiddingState(r.state)
        roundMultiplier = r.doubledMultiplier
        dealSeed += 500L
        redeals++
        hands = Dealer.dealHands(dealSeed)
        bidding = session.initializeBiddingState(freshBidding(b.round))
      }
    }

    bidIndex++
    commit(seq, key, record)
    return record
  }

  private fun playCard(seat: String, card: Card): String? {
    val t = table ?: error("no table")
    val handCard = t.cfg.hands[seat]?.firstOrNull { it.suit == card.suit && it.rank.v == card.rank.v }
      ?: error("$seat does not hold ${card.suit}:${card.rank.v}")

    val after = when (val r = emitPlay(t, PlayCard(seat, handCard))) {
      is PlayEmit.Applied -> r.state
      is PlayEmit.Rejected -> error("rejected play $seat ${card.suit}:${card.rank.v}: ${r.reason}")
    }
    if (after.phase != TablePhase.RESOLVING) {
      table = after
      session.updatePlayState(after)
      return null
    }

    // The 4th card completes the durable unit. The record is built from the
    // play as it happened — the trick's leader and its four cards — and the
    // key is the trick number, so recovery can tell "this trick is already
    // stored" from the row alone.
    val resolved = resolveTrick(after)
    val key = E9Key(resolved.cfg.round, E9Unit.TRICK, after.trickNo)
    val seq = applied + 1
    val record = encodeTrick(seq, key, after.leaderId, after.plays)

    table = resolved
    session.updatePlayState(resolved)
    commit(seq, key, record)
    if (resolved.phase == TablePhase.DONE) finishRound(resolved)
    return record
  }

  private fun commit(seq: Int, key: E9Key, record: String) {
    applied = seq
    lastKey = key
    lastRecord = record
  }

  private fun openTable(outcome: com.estemshan.engine.BiddingOutcome) {
    val cfg = RoundCfg(
      round = session.getRound().number,
      trump = outcome.trump,
      callerId = outcome.callerId,
      withPlayers = outcome.withPlayers,
      estimates = outcome.estimates,
      dashCallers = outcome.dashCallers,
      leaderId = outcome.leaderId,
      riskId = outcome.riskPlayerId,
      multiplier = roundMultiplier,
      hands = hands,
    )
    table = session.initializePlayState(cfg)
  }

  private fun finishRound(finalTable: TableState) {
    val cfg = finalTable.cfg
    val classic = scoringMode == ScoringMode.CLASSIC
    val score = calculateRoundScore(
      RoundScoreInput(
        round = cfg.round,
        order = E9_SEATS,
        bids = bidsFromRound(cfg),
        tricksWon = finalTable.tricksWon,
        callerId = cfg.callerId,
        withPlayers = cfg.withPlayers,
        multiplier = cfg.multiplier,
        riskPlayerId = cfg.riskId,
        classic = classic,
        escalationCap = if (classic) 2 else 8,
      ),
    )
    session.setMatchScores(accumulateMatchScores(session.getMatchScores(), score.deltas))
    session.completeRound(finalTable.tricksWon, score.nextMultiplier)

    roundDigests += RoundDigest(
      round = cfg.round,
      trump = cfg.trump.name,
      callerId = cfg.callerId,
      withPlayers = cfg.withPlayers,
      leaderId = cfg.leaderId,
      tricksWon = finalTable.tricksWon,
      estimates = cfg.estimates,
      dashCallers = cfg.dashCallers,
      riskPlayerId = score.riskPlayerId,
      totalBids = score.totalBids,
      isOver = score.isOver,
      isSaayda = score.isSaayda,
      appliedMultiplier = score.appliedMultiplier,
      nextMultiplier = score.nextMultiplier,
    )

    if (session.isMatchComplete()) return
    session.nextRound()
    beginRound()
  }

  // ── recovery ──────────────────────────────────────────────────────

  /** Apply one stored record. This is what a restart does, one row at a time;
   *  the record must be the next unit and must reproduce itself exactly. */
  fun applyRecord(recordText: String) {
    val parsed = parseRecord(recordText)
    require(parsed.seq == applied + 1) {
      "record seq ${parsed.seq} applied after $applied records"
    }
    val expected = expectedKey() ?: error("match is complete; no unit can follow ${parsed.key}")
    require(parsed.key == expected) { "expected unit $expected, record is ${parsed.key}" }

    when (parsed) {
      is E9Record.BidUnit -> {
        val produced = applyBidUnit(parsed.intent)
          ?: error("stored bid was rejected on replay: $recordText")
        require(produced == recordText) {
          "replaying ${parsed.key} produced a different record:\n  stored: $recordText\n  replay: $produced"
        }
      }
      is E9Record.TrickUnit -> {
        var produced: String? = null
        for (play in parsed.plays) {
          val r = playCard(play.playerId, play.card)
          if (r != null) {
            require(produced == null) { "one trick record produced two records" }
            produced = r
          }
        }
        require(produced == recordText) {
          "replaying ${parsed.key} produced a different record:\n  stored: $recordText\n  replay: $produced"
        }
      }
    }
  }

  /**
   * The record of the next durable unit, WITHOUT applying it.
   *
   * A Worker needs this ordering: write the row, then apply it. Producing the
   * record first is easy for an auction action (the intent is known before it
   * is emitted); a Trick's record needs four cards, so the fourth is played
   * against a simulated copy of the table and the copy is discarded. The
   * simulation uses the same brains and the same reducer, so the record it
   * yields is the one the real application then reproduces.
   */
  fun nextUnitRecord(): String? {
    val b = bidding
    if (b != null && b.subPhase != BiddingPhase.DONE) {
      val seat = b.waitingFor ?: return null
      val (tier, personality) = E9_ROSTER.getValue(seat)
      val intent = BidBrain.decide(b, hands.getValue(seat), seat, tier, personality)
      val key = E9Key(b.round, E9Unit.BID, bidIndex + 1)
      return "B;${applied + 1};${key.round};${key.index};${encodeBidPayload(intent)}"
    }
    val t = table
    if (t == null || t.phase != TablePhase.PLAY) return null

    var sim: TableState = t
    var resolvedPlays: List<Play> = emptyList()
    for (i in 0 until 4) {
      val current = sim
      val seat = current.turn ?: error("trick ${t.trickNo}: no turn while simulating")
      val intent = PlayBrain.decide(current, seat, E9_ROSTER.getValue(seat).first)
      val next = when (val r = emitPlay(current, PlayCard(intent.playerId, intent.card))) {
        is PlayEmit.Applied -> r.state
        is PlayEmit.Rejected -> error("simulated play rejected: ${r.reason}")
      }
      if (next.phase == TablePhase.RESOLVING) {
        resolvedPlays = next.plays
        sim = resolveTrick(next)
      } else {
        sim = next
      }
    }
    require(resolvedPlays.size == 4) { "a trick record needs four plays" }
    val key = E9Key(t.cfg.round, E9Unit.TRICK, t.trickNo)
    return encodeTrick(applied + 1, key, t.leaderId, resolvedPlays)
  }

  private fun expectedKey(): E9Key? {
    val b = bidding
    if (b != null && b.subPhase != BiddingPhase.DONE) return E9Key(b.round, E9Unit.BID, bidIndex + 1)
    val t = table
    if (t != null && t.phase != TablePhase.DONE) return E9Key(t.cfg.round, E9Unit.TRICK, t.trickNo)
    return null
  }

  // ── results ───────────────────────────────────────────────────────

  fun completedRun(): MatchRun = MatchRun(
    seed = seed,
    gameType = gameType,
    scoringMode = scoringMode,
    rounds = roundDigests.toList(),
    dealers = dealers.toList(),
    redeals = redeals,
    matchScores = session.getMatchScores(),
    winners = computeWinner(session.getMatchScores()),
  )

  /**
   * The whole comparable state, as text. Deliberately includes the transient
   * parts — the in-flight trick, the seats' remaining hands, the auction in
   * progress — so a recovery that dropped the last unit cannot pass by
   * matching only the completed rounds.
   */
  fun stateDocument(): String {
    val w = StringBuilder()
    w.append("seed=").append(seed)
      .append(";gameType=").append(gameType.name)
      .append(";scoringMode=").append(scoringMode.name)
      .append(";applied=").append(applied).append('\n')

    val round = session.getRound()
    w.append("round=").append(round.number).append('/').append(round.maxRounds)
      .append(";multiplier=").append(round.multiplier)
      .append(";dealer=").append(dealerSeat)
      .append(";redeals=").append(redeals)
      .append(";dealSeed=").append(dealSeed).append('\n')

    val b = bidding
    if (b == null) {
      w.append("bidding=absent\n")
    } else {
      w.append("bidding=").append(b.subPhase)
        .append(";waiting=").append(b.waitingFor ?: "-")
        .append(";first=").append(b.firstBidder)
        .append(";top=").append(b.auctionTop)
        .append(";suit=").append(b.auctionSuit?.name ?: "-")
        .append(";bidder=").append(b.auctionBidder ?: "-")
        .append(";caller=").append(b.callerId ?: "-")
        .append(";trump=").append(b.declaredTrump?.name ?: "-")
        .append(";last=").append(b.lastBidderId ?: "-")
        .append(";fast=").append(if (b.fastRound) 1 else 0)
        .append(";nosuit=").append(if (b.noSuitConstraint) 1 else 0)
        .append(";supersolved=").append(if (b.fastSuperResolved) 1 else 0)
        .append(";rmult=").append(b.roundMultiplier)
        .append(";mode=").append(b.scoringMode.name)
        .append(";active=").append(b.activeBidders.joinToString(","))
        .append(";with=").append(b.withPlayers.joinToString(","))
        .append(";bids=").append(
          E9_SEATS.joinToString(",") { seat ->
            val bid = b.bids[seat]
            if (bid == null) "$seat:-" else "$seat:${bid.type}:${bid.amount}:${bid.suit?.name ?: "-"}"
          },
        )
        .append(";history=").append(
          b.actionHistory.joinToString(",") { "${it.playerId}:${it.actionType}:${it.value ?: "-"}:${it.suit?.name ?: "-"}" },
        )
        .append('\n')
    }

    val t = table
    if (t == null) {
      w.append("table=absent\n")
    } else {
      val cfg = t.cfg
      w.append("table=").append(t.phase)
        .append(";round=").append(cfg.round)
        .append(";trump=").append(cfg.trump.name)
        .append(";caller=").append(cfg.callerId ?: "-")
        .append(";leader=").append(cfg.leaderId)
        .append(";risk=").append(cfg.riskId ?: "-")
        .append(";mult=").append(cfg.multiplier)
        .append(";with=").append(cfg.withPlayers.joinToString(","))
        .append(";trickNo=").append(t.trickNo)
        .append(";turn=").append(t.turn ?: "-")
        .append(";led=").append(t.ledSuit?.name ?: "-")
        .append(";tricks=").append(E9_SEATS.joinToString(",") { "$it:${t.tricksWon[it] ?: 0}" })
        .append(";plays=").append(t.plays.joinToString(",") { "${it.playerId}:${cardId(it.card)}" })
        .append(";seen=").append(t.seenCards.size)
        .append(";voids=").append(
          E9_SEATS.joinToString(",") { seat ->
            "$seat:${t.voids[seat].orEmpty().joinToString("/") { it.name }}"
          },
        )
        .append(";hands=").append(
          E9_SEATS.joinToString(",") { seat ->
            "$seat:${t.cfg.hands[seat].orEmpty().joinToString("/") { cardId(it) }}"
          },
        )
        .append('\n')
    }

    w.append("scores=")
      .append(E9_SEATS.joinToString(",") { "$it:${session.getMatchScores()[it] ?: 0}" })
      .append(";complete=").append(if (isComplete()) 1 else 0).append('\n')

    w.append("rounds=").append(roundDigests.size).append('\n')
    for (r in roundDigests) w.append(renderRoundDigest(r)).append('\n')
    return w.toString()
  }

  /** FNV-1a over [stateDocument] — the one-shot comparison the platform leg
   *  can make across a process kill. */
  fun stateDigest(): String = fnv1a32(stateDocument())

  companion object {
    fun start(
      seed: Int,
      gameType: GameType = GameType.FULL,
      scoringMode: ScoringMode = ScoringMode.NORMAL,
    ): DurableMatch = DurableMatch(seed, gameType, scoringMode).also { it.bootstrap() }

    /** A restart: a fresh engine fed nothing but the stored rows, in order. */
    fun replay(
      log: String,
      seed: Int,
      gameType: GameType = GameType.FULL,
      scoringMode: ScoringMode = ScoringMode.NORMAL,
    ): DurableMatch {
      val m = start(seed, gameType, scoringMode)
      for (line in log.split('\n')) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        m.applyRecord(trimmed)
      }
      return m
    }
  }
}

// ── record encoding: one line holds the id, the position and the payload ──

private fun encodeBidPayload(intent: BiddingIntent): String = when (intent) {
  is BiddingIntent.DashCallDecision ->
    "DASHCALL;${intent.playerId};${if (intent.declaredDashCall) 1 else 0};-"
  is BiddingIntent.AuctionBid ->
    "AUCTION;${intent.playerId};${if (intent.isPass) "-" else intent.tricks.toString()};${intent.suit?.name ?: "-"}"
  is BiddingIntent.ConfirmCall ->
    "CONFIRM;${intent.playerId};${intent.tricks};${intent.suit.name}"
  is BiddingIntent.FinalEstimate ->
    "ESTIMATE;${intent.playerId};${intent.tricks};-"
}

private fun bidPayloadOf(record: String): String =
  record.split(';').drop(4).joinToString(";")

private fun keyOf(record: String): E9Key {
  val f = record.split(';')
  return when (f[0]) {
    "B" -> E9Key(f[2].toInt(), E9Unit.BID, f[3].toInt())
    "T" -> E9Key(f[2].toInt(), E9Unit.TRICK, f[3].toInt())
    else -> error("unknown record kind: ${f[0]}")
  }
}

private fun encodeTrick(seq: Int, key: E9Key, leader: String, plays: List<Play>): String =
  buildString {
    append("T;").append(seq).append(';').append(key.round).append(';').append(key.index)
      .append(';').append(leader)
    for (p in plays) append(';').append(p.playerId).append(':').append(cardId(p.card))
  }

fun parseRecord(record: String): E9Record {
  val f = record.split(';')
  require(f.size >= 8) { "short record: $record" }
  val seq = f[1].toInt()
  return when (f[0]) {
    "B" -> {
      val key = E9Key(f[2].toInt(), E9Unit.BID, f[3].toInt())
      val kind = f[4]
      val seat = f[5]
      val value = f[6]
      val suit = f[7]
      val intent = when (kind) {
        "DASHCALL" -> BiddingIntent.DashCallDecision(seat, value == "1")
        "AUCTION" -> BiddingIntent.AuctionBid(
          playerId = seat,
          isPass = value == "-",
          tricks = value.toIntOrNull(),
          suit = suit.takeIf { it != "-" }?.let(::suitOf),
        )
        "CONFIRM" -> BiddingIntent.ConfirmCall(seat, value.toInt(), suitOf(suit))
        "ESTIMATE" -> BiddingIntent.FinalEstimate(seat, value.toInt())
        else -> error("unknown bid kind: $kind")
      }
      E9Record.BidUnit(seq, key, intent)
    }
    "T" -> {
      val key = E9Key(f[2].toInt(), E9Unit.TRICK, f[3].toInt())
      require(f.size == 9) { "a trick record holds exactly four plays: $record" }
      val plays = f.drop(5).map { field ->
        val parts = field.split(':')
        require(parts.size == 3) { "bad play field: $field" }
        Play(parts[0], Card(suitOf(parts[1]), rankOf(parts[2].toInt())))
      }
      E9Record.TrickUnit(seq, key, plays)
    }
    else -> error("unknown record kind: ${f[0]}")
  }
}

private fun suitOf(name: String): Suit =
  Suit.entries.firstOrNull { it.name == name } ?: error("unknown suit: $name")

private fun rankOf(v: Int): com.estemshan.engine.Rank =
  com.estemshan.engine.RANKS.firstOrNull { it.v == v } ?: error("unknown rank: $v")

private fun cardId(card: Card): String = "${card.suit.name}:${card.rank.v}"

// ── the state digest ──

private fun renderRoundDigest(r: RoundDigest): String = listOf(
  "r${r.round}",
  r.trump,
  "caller=${r.callerId ?: "-"}",
  "with=${r.withPlayers.joinToString(",")}",
  "leader=${r.leaderId}",
  "tricks=" + E9_SEATS.joinToString(",") { "$it:${r.tricksWon[it] ?: 0}" },
  "est=" + E9_SEATS.joinToString(",") { "$it:${r.estimates[it] ?: 0}" },
  "dash=${r.dashCallers.joinToString(",")}",
  "risk=${r.riskPlayerId ?: "-"}",
  "bids=${r.totalBids}",
  "over=${if (r.isOver) 1 else 0}",
  "saayda=${if (r.isSaayda) 1 else 0}",
  "mult=${r.appliedMultiplier}->${r.nextMultiplier}",
).joinToString("|")

/** FNV-1a, 32-bit — declared per file exactly as `MatchHarness.kt` and
 *  `SettlementStreamProbe.kt` already do. */
private fun fnv1a32(s: String): String {
  var hash = -0x7ee3623b // 0x811c9dc5
  for (c in s) {
    hash = hash xor c.code
    hash *= 0x01000193
  }
  return hash.toUInt().toString(16).padStart(8, '0')
}

// ── shared with the MatchHarness driver (its copy is private to that file) ──

private fun bidsFromRound(cfg: RoundCfg): Map<String, Bid> =
  cfg.estimates.keys.union(cfg.dashCallers).associateWith { seat ->
    when {
      cfg.dashCallers.contains(seat) -> Bid(BidType.DASHCALL, 0)
      (cfg.estimates[seat] ?: 0) == 0 -> Bid(BidType.DASH, 0)
      else -> Bid(BidType.TRICKS, cfg.estimates.getValue(seat))
    }
  }
