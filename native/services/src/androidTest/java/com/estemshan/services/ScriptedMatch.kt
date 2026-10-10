package com.estemshan.services

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.Card
import com.estemshan.engine.ExtensionReason
import com.estemshan.engine.GameSession
import com.estemshan.engine.HandAuthority
import com.estemshan.engine.MatchMode
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.RoundState
import com.estemshan.engine.SessionPlayer
import com.estemshan.engine.SessionRoom
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.accumulateMatchScores
import com.estemshan.engine.calculateRoundScore
import com.estemshan.engine.computeWinner
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.legalCards
import com.estemshan.services.model.BiddingActionInput
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.RAPID_ROUND_MIN
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.SEAT_IDS
import com.estemshan.services.model.ServiceException
import com.estemshan.services.model.VoteDoc
import com.estemshan.services.session.AuthPort
import com.estemshan.services.session.GameSessionBridge
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Four real `MatchService` instances — one per seat, each with its own
 * disjoint `GameSession` + `MatchAdapter` graph — driven through one
 * scripted match against the host emulators. This is S20 (plan §2D):
 * the first place real Kotlin hits real `firestore.rules`.
 *
 * WHY THE HARNESS EXISTS. The production orchestrator is
 * `OnlineMatchViewModel` in :app; a :services androidTest cannot see it
 * (that would be a dependency cycle), so this driver replays its pipeline
 * — `align → deal/hand → bidding → table → cards → score` — per seat per
 * step. The ordering mirrors `OnlineMatchViewModel.handleSnapshot` and
 * `scoreAndAdvance` deliberately; diverge from it and the service's
 * ordering invariants reject the write.
 *
 * WHY IT IS SYNCHRONOUS. Every Firestore write still goes through the real
 * transaction path and the real rules; the driver just pulls the document
 * between scripted moves instead of waiting on a listener. No sleeps
 * anywhere — each step is a real round trip whose completion is the wait.
 *
 * WHAT IT DOES NOT DICTATE. Who wins a race. At every shared transition
 * (deal, advance, extend, endMatch, the rematch vote and its match) all
 * four clients fire CONCURRENTLY; the emulator has zero transaction retry,
 * so the winner is luck. Convergence — all four agreeing on the outcome —
 * is the assertion, never single-winner determinism.
 */
class ScriptedMatch(
  /** seat id (p1..p4) -> that seat's own signed-in Firestore instance.
   *
   * One FirebaseApp per seat is not an optimization: firestore.rules pins
   * each `hands/{seatId}` read to `request.auth.uid`, so a shared auth
   * context could never read more than one seat's hand. See
   * [EmulatorSuite.client]. */
  private val seating: Map<String, EmulatorSuite.ClientFirebase>,
  private val seed: Long,
) {
  private val seats: List<String> = SEAT_IDS
  private val uids: Map<String, String> = seating.mapValues { it.value.uid }
  private lateinit var roomCode: String
  private lateinit var matchId: String

  private val clients: List<Client> = seats.map { Client(it) }
  private val bySeat: Map<String, Client> = clients.associateBy { it.seatId }

  /** The driver's own reads (the match document, roundArchive, the
   *  rematch) go through p1's token: p1 is a seated player, so those
   *  reads pass the rules from any seat's instance. */
  private val db: FirebaseFirestore get() = clients.first().firebase.db

  /** The round the driver exercises [MatchService.extendMatchRounds] on.
   *  It must be a Rapid Round (14..18); 14 is the first, which keeps the
   *  resulting match shortest (maxRounds 18 -> 19). The service is
   *  structural-only here by design — it cannot verify a Super Call
   *  occurred, that is the caller's engine-derived fact — so the driver
   *  supplies it, exactly as production does. */
  private val extendAtRound: Int = RAPID_ROUND_MIN

  private inner class Client(val seatId: String) {
    internal val firebase = seating.getValue(seatId)
    val uid: String get() = firebase.uid
    private val db: FirebaseFirestore get() = firebase.db
    val engine: GameSession = GameSession()
    val port: GameSessionBridge = GameSessionBridge(engine)
    /** No listener: the driver pushes documents into the adapter, so the
     *  sync half is exercised without a second, racing delivery thread. */
    val adapter: MatchAdapter = MatchAdapter(port)
    private val auth: AuthPort = object : AuthPort { override fun currentUid() = uid }
    val store: MatchService = MatchService(db, auth, port, adapter, Deal.seeded(seed))
    val rooms: RoomService = RoomService(db, auth, matchStarter = store::startMatch)

    var alignedRound: Int = -1
    var outcome: BiddingOutcome? = null
    var scoredRound: Int = -1
    var scored: Scored? = null
  }

  // ── setup: room, ready-up, startMatch ───────────────────────────────

  /**
   * Creates the room, seats all four clients, readies them up and starts
   * the match. The creator readies LAST: only the room's creator is
   * allowed to trigger the start, so any other order would leave the
   * match unstarted.
   */
  suspend fun start(mode: MatchMode = MatchMode.ROOM): String {
    val creator = uids.getValue(seats.first())
    roomCode = clients.first().rooms.createRoom(creator, "suite-room", mode = mode)
    for (i in 1 until clients.size) {
      clients[i].rooms.joinRoom(roomCode, clients[i].uid)
    }
    // Non-creators first, creator last — see the KDoc.
    for (i in 1 until clients.size) {
      clients[i].rooms.setReady(roomCode, clients[i].uid, true)
    }
    val started = clients.first().rooms.setReady(roomCode, creator, true)
    matchId = started.matchStart?.matchId
      ?: error("match did not start: ${started.matchStart}")
    return matchId
  }

  // ── per-step sync: one document, four engines ───────────────────────

  /** The authoritative document — one read shared by all four clients. */
  private suspend fun loadMatch(): MatchDoc =
    db.collection("matches").document(matchId).get().await()
      .let { snap ->
        if (!snap.exists()) error("match $matchId disappeared mid-match")
        MatchDoc.fromFields(snap.data ?: emptyMap())
          ?: error("match $matchId could not be parsed")
      }

  private suspend fun syncAll(doc: MatchDoc) {
    for (client in clients) sync(client, doc)
  }

  /** One client's pass over one document, in the production order. */
  private suspend fun sync(client: Client, doc: MatchDoc) {
    align(client, doc)
    ensureDealAndHand(client, doc)
    initAuction(client, doc)
    replayBidding(client, doc)
    ensureTable(client, doc)
    replayCards(client, doc)
  }

  /**
   * Bootstrap on first sight of the match, then catch up on a round
   * change. Never re-inits mid-match: `init(force=true)` would wipe the
   * accumulated match scores, which the document does not carry (only the
   * final standings land on it at endMatch). A round change walks
   * `nextRound()` instead, which preserves the Sa'ayda multiplier.
   */
  private fun align(client: Client, doc: MatchDoc) {
    if (client.alignedRound == doc.currentRound) return
    if (client.alignedRound < 1) {
      client.engine.init("online", force = true)
      client.engine.setScoringMode(com.estemshan.engine.ScoringMode.NORMAL)
      client.engine.setHandAuthorityMode(HandAuthority.FIRESTORE)
      client.adapter.resetSyncState(matchId)
      client.engine.setPlayers(seats.map { SessionPlayer(it, uids[it], it) })
      client.engine.setRoom(SessionRoom(code = roomCode, seats = seats))
      client.engine.setMatchScores(seats.associateWith { 0 })
    } else {
      client.adapter.resetSyncState(matchId)
      while (client.engine.getRound().number < doc.currentRound) {
        client.engine.nextRound()
      }
    }
    client.engine.setRound(
      client.engine.getRound().copy(number = doc.currentRound, maxRounds = doc.maxRounds),
    )
    client.engine.setDealer(doc.dealer?.let { doc.uidToSeat(it) })
    client.outcome = null
    client.alignedRound = doc.currentRound
  }

  /** dealRound is idempotent — every client races it, one wins. */
  private suspend fun ensureDealAndHand(client: Client, doc: MatchDoc) {
    if (doc.gameState.dealtRound < doc.currentRound) {
      retryEmulatorTx("dealRound round ${doc.currentRound}") {
        client.store.dealRound(matchId, doc.currentRound)
      }
    }
    if (client.engine.getHand(client.seatId).isEmpty()) {
      val hand = client.store.loadHand(matchId, client.seatId, doc.currentRound)
        ?: return // not dealt yet; a later pass picks it up
      val cards = hand.cards.mapNotNull { it.toEngineCard() }
      assertEquals("seat ${client.seatId} round ${doc.currentRound} hand is whole",
        HAND_SIZE, cards.size)
      client.engine.setAuthoritativeHand(client.seatId, cards, doc.currentRound)
    }
  }

  /** Fresh auction for this round if the engine does not have one yet. */
  private fun initAuction(client: Client, doc: MatchDoc) {
    if (client.engine.isBiddingStateValidForCurrentRound()) return
    client.engine.clearBiddingState()
    client.engine.clearPlayState()
    val dealerSeat = doc.dealer?.let { doc.uidToSeat(it) } ?: seats.first()
    val multiplier = client.engine.getRound().multiplier
    val state = if (doc.currentRound >= RAPID_ROUND_MIN) {
      initFastRound(doc.currentRound, dealerSeat, seats, multiplier)
    } else {
      initNormalRound(doc.currentRound, dealerSeat, seats, multiplier)
    }
    client.engine.initializeBiddingState(state)
  }

  /** Replay the bid + action logs, completing the auction exactly once. */
  private fun replayBidding(client: Client, doc: MatchDoc) {
    val fromActions = client.adapter.applyRemoteBiddingAction(matchId, doc)
    val fromBid = client.adapter.applyRemoteBid(matchId, doc)
    val outcome = fromActions.outcome ?: fromBid.outcome ?: return
    client.outcome = outcome
    client.engine.completeBidding(outcome)
  }

  /** Build the round's table once the auction and our hand are known. */
  private fun ensureTable(client: Client, doc: MatchDoc) {
    val outcome = client.outcome ?: return
    val engine = client.engine
    if (engine.isPlayStateValidForCurrentRound()) return
    val mine = engine.getHand(client.seatId)
    if (mine.isEmpty()) return
    engine.restartPlayState(
      RoundCfg(
        round = doc.currentRound,
        trump = outcome.trump,
        callerId = outcome.callerId,
        withPlayers = outcome.withPlayers,
        estimates = outcome.estimates,
        dashCallers = outcome.dashCallers,
        leaderId = outcome.leaderId,
        riskId = outcome.riskPlayerId,
        multiplier = engine.getRound().multiplier,
        // Only our own seat — the adapter seeds opponents' cards as they
        // are observed, never ahead of the log.
        hands = mapOf(client.seatId to mine),
      ),
    )
    engine.setTurn(outcome.leaderId)
  }

  /** Alternate card/trick application until a full pass makes no progress. */
  private fun replayCards(client: Client, doc: MatchDoc) {
    repeat(16) {
      val before = client.adapter.lastAppliedCardCount(matchId)
      val trickBefore = client.adapter.lastResolvedTrickNo(matchId)
      client.adapter.applyRemoteCard(matchId, doc, client.seatId)
      val resolved = client.adapter.applyRemoteTrick(matchId, doc)
      if (client.adapter.lastAppliedCardCount(matchId) == before &&
        client.adapter.lastResolvedTrickNo(matchId) == trickBefore && !resolved.applied
      ) return
    }
  }

  // ── scripted moves ──────────────────────────────────────────────────

  /** The auction's actor, derived from the engines (they must agree). */
  private fun biddingActor(): String? {
    val waiting = clients.map { it.engine.getBiddingState()?.waitingFor }
    val first = waiting.first()
    waiting.forEachIndexed { i, w ->
      assertEquals("seat ${clients[i].seatId} agrees on the bidding actor", first, w)
    }
    return first
  }

  private suspend fun submitBidding(seat: String) {
    val client = bySeat.getValue(seat)
    val state = client.engine.getBiddingState() ?: return
    val actor = state.waitingFor ?: return
    // ESTIMATES writes through submitBid (the one-value-per-seat channel);
    // every other phase appends to the bidding log.
    val action: BiddingActionInput? = when (state.subPhase) {
      BiddingPhase.DASH -> BiddingActionInput(
        BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = false,
      )
      BiddingPhase.AUCTION ->
        // The first seat to act wins the auction with 4 spades; everyone
        // else passes. auctionBidder/auctionTop identify "no real bid yet"
        // without the driver keeping its own per-round flag.
        if (state.auctionBidder == null && state.auctionTop == 0) {
          BiddingActionInput(
            BiddingLogEntry.ACTION_AUCTION_BID, isPass = false,
            tricks = AUCTION_BID, suit = Suit.SPADES.name,
          )
        } else {
          BiddingActionInput(BiddingLogEntry.ACTION_AUCTION_BID, isPass = true)
        }
      BiddingPhase.CONFIRM -> BiddingActionInput(
        BiddingLogEntry.ACTION_CONFIRM_CALL,
        tricks = state.auctionTop,
        suit = (state.auctionSuit ?: error("confirm with no auction suit")).name,
      )
      BiddingPhase.ESTIMATES -> null
      BiddingPhase.DONE -> return
    }
    if (action != null) {
      retryEmulatorTx("submitBiddingAction seat ${client.seatId}") {
        client.store.submitBiddingAction(matchId, action)
      }
    } else {
      retryEmulatorTx("submitBid seat ${client.seatId}") {
        client.store.submitBid(matchId, actor, ESTIMATE)
      }
    }
  }

  /** Whose turn it is to play: the engine's leader before a round's
   *  opening publication has landed, then the document's own turn.
   *
   *  The pre-publication document is not usable as an authority here: on
   *  round 1 it still holds the dealer (not the auction's leader) and
   *  after every later advance it holds null. `submitCard` accepts the
   *  engine's own turn in exactly that window, so the driver must too. */
  private fun playTurn(doc: MatchDoc): String? {
    if (doc.turn == null || doc.isRoundOneOpeningWindow) {
      val engineTurn = clients.first().engine.getTurn()
      for (client in clients) {
        assertEquals("seat ${client.seatId} agrees on the pre-publication turn",
          engineTurn, client.engine.getTurn())
      }
      return engineTurn
    }
    val docTurn = doc.turn?.let { doc.uidToSeat(it) }
    // Mid-trick the session's getTurn() is deliberately the round LEADER,
    // not the next card's owner: MatchAdapter mirrors the resolved trick's
    // next leader into session.setTurn() only at the resolving boundary
    // (applyRemoteTrick), never per card, and applyRemoteCard advances the
    // table state alone. The authoritative per-card turn is the table
    // engine's own turn — the same source MatchService.submitCard() takes
    // its authority from (previewPlay gates on state.turn == seatId).
    val engineTurn = clients.first().engine.getPlayState()?.turn
    for (client in clients) {
      assertEquals("seat ${client.seatId} agrees with the document's turn",
        engineTurn, client.engine.getPlayState()?.turn)
    }
    assertEquals("the document's turn matches the engines'", docTurn, engineTurn)
    return docTurn
  }

  private suspend fun submitCard(seat: String) {
    val client = bySeat.getValue(seat)
    val table = client.engine.getPlayState() ?: error("no table engine for $seat")
    val turn = table.turn ?: error("table has no turn for $seat")
    assertEquals("the table's turn is the seat the driver picked", seat, turn)
    val card: Card = legalCards(table, seat).firstOrNull()
      ?: error("no legal card for $seat in trick ${table.trickNo}")
    retryEmulatorTx("submitCard seat $seat trick ${table.trickNo}") {
      client.store.submitCard(matchId, card)
    }
  }

  // ── one round: bidding, 52 cards, score, race ───────────────────────

  data class RoundReport(
    val round: Int,
    /** The round the match moved to — [round] + 1 on an advance, [round]
     *  when the match ended. */
    val currentRound: Int,
    val trump: Suit,
    val callerId: String?,
    val totals: Map<String, Int>,
    val winners: List<String>,
    val extended: Boolean,
    val maxRounds: Int,
    val advanced: Boolean,
    val ended: Boolean,
  )

  /** Play one round end to end: auction, deal, all 52 cards, then the
   *  archive+advance race (or extend, or endMatch) with all four clients
   *  firing concurrently. */
  suspend fun playRound(round: Int): RoundReport {
    driveBidding(round)
    drivePlay(round)
    return finishRound(round)
  }

  /** Plays every round until endMatch, following maxRounds as the
   *  extension lifts it. */
  suspend fun playToEnd(): List<RoundReport> {
    val reports = mutableListOf<RoundReport>()
    var round = 1
    while (round <= MAX_PLAUSIBLE_ROUNDS) {
      val report = playRound(round)
      reports += report
      if (report.ended) return reports
      round = report.currentRound
    }
    error("the match did not end within $MAX_PLAUSIBLE_ROUNDS rounds")
  }

  /** The committed document — the ground truth every client must agree on. */
  suspend fun standings(): MatchDoc = loadMatch()

  private suspend fun driveBidding(round: Int) {
    var guard = 0
    while (guard++ < BIDDING_GUARD) {
      val doc = loadMatch()
      syncAll(doc)
      if (clients.first().engine.getBiddingState()?.subPhase == BiddingPhase.DONE) return
      val actor = biddingActor() ?: return
      submitBidding(actor)
    }
    error("round $round bidding did not reach DONE within $BIDDING_GUARD moves")
  }

  private suspend fun drivePlay(round: Int) {
    while (true) {
      val doc = loadMatch()
      syncAll(doc)
      if (doc.roundCardCount(round) >= ROUND_CARD_TOTAL) return
      val turn = playTurn(doc) ?: error("round $round stalled with no turn")
      submitCard(turn)
    }
  }

  /**
   * Every client scores the round from the same cardLog, then all four
   * race the transition. The idempotent no-ops a race loser receives
   * (ALREADY_EXTENDED / ALREADY_ADVANCED / ALREADY_COMPLETE) are RETURNED,
   * never thrown — that is the convergence contract this asserts.
   */
  private suspend fun finishRound(round: Int): RoundReport {
    val doc = loadMatch()
    syncAll(doc)

    val scored = clients.map { scoreRound(it, doc) }
    val first = scored.first() ?: error("round $round never became scorable")
    for ((client, result) in clients.zip(scored)) {
      assertNotNull("seat ${client.seatId} scored round $round", result)
      // CONVERGENCE: independent engines, identical totals and winners.
      assertEquals("seat ${client.seatId} agrees on round $round totals",
        first.totals, result!!.totals)
      assertEquals("seat ${client.seatId} agrees on round $round winners",
        first.winners, result.winners)
    }

    val extend = round == extendAtRound
    val maxRounds = if (extend) doc.maxRounds + 1 else doc.maxRounds
    val isLast = round + 1 > maxRounds

    if (extend) {
      val raced = raceAll { client ->
        retryEmulatorTx("extendMatchRounds round $round") {
          client.store.extendMatchRounds(matchId, round, ExtensionReason.SUPER_CALL.name)
        }
      }
      assertConverged("extendMatchRounds round $round", raced, { it.extended }) { it.maxRounds }
    }

    val advanced: Boolean
    val ended: Boolean
    if (isLast) {
      val raced = raceAll { client ->
        retryEmulatorTx("endMatch round $round") {
          client.store.endMatch(matchId, round, first.totals, first.winners)
        }
      }
      assertConverged("endMatch round $round", raced, { it.complete }) {
        it.winnerIds to it.finalScores
      }
      advanced = false
      ended = true
    } else {
      val raced = raceAll { client ->
        retryEmulatorTx("advanceToNextRound round $round") {
          client.store.advanceToNextRound(matchId, round)
        }
      }
      // A race loser gets ALREADY_ADVANCED with the committed currentRound
      // and no archivedRound, so only the round the document landed on is
      // a safe thing to compare across all four.
      assertConverged("advanceToNextRound round $round", raced, { it.advanced }) { it.currentRound }
      advanced = true
      ended = false
    }

    // The winner's atomic archive write is really on the document.
    val archived = db.collection("matches").document(matchId)
      .collection("roundArchive").document(round.toString()).get().await()
    assertTrue("round $round was archived to roundArchive/$round", archived.exists())

    // The round archive the winner wrote is really there, and every seat
    // reads the same post-race document.
    val after = loadMatch()
    for (client in clients) {
      val reRead = client.store.loadMatch(matchId)
        ?: error("seat ${client.seatId} lost the match after round $round")
      assertEquals("seat ${client.seatId} sees the same currentRound", after.currentRound, reRead.currentRound)
      assertEquals("seat ${client.seatId} sees the same maxRounds", after.maxRounds, reRead.maxRounds)
      assertEquals("seat ${client.seatId} sees the same status", after.status, reRead.status)
    }

    return RoundReport(
      round = round,
      currentRound = if (ended) round else round + 1,
      trump = first.trump,
      callerId = first.callerId,
      totals = first.totals,
      winners = first.winners,
      extended = extend,
      maxRounds = if (extend) doc.maxRounds + 1 else doc.maxRounds,
      advanced = advanced,
      ended = ended,
    )
  }

  /** Scores one client's round; null unless the round is truly complete. */
  private fun scoreRound(client: Client, doc: MatchDoc): Scored? {
    if (client.scoredRound == doc.currentRound) {
      return client.scored?.takeIf { it.round == doc.currentRound }
    }
    val table = client.engine.getPlayState() ?: return null
    if (table.phase != TablePhase.DONE) return null
    if (doc.roundCardCount(doc.currentRound) < ROUND_CARD_TOTAL) return null
    client.scoredRound = doc.currentRound

    val cfg = table.cfg
    val result = calculateRoundScore(
      RoundScoreInput(
        round = cfg.round,
        order = seats,
        bids = bidsFromOutcome(cfg),
        tricksWon = table.tricksWon,
        callerId = cfg.callerId,
        withPlayers = cfg.withPlayers,
        multiplier = cfg.multiplier,
        riskPlayerId = cfg.riskId,
        classic = false,
        escalationCap = client.engine.escalationCap,
      ),
    )
    val totals = accumulateMatchScores(client.engine.getMatchScores(), result.deltas)
    client.engine.setMatchScores(totals)
    client.engine.setWinnerIds(computeWinner(totals))
    client.engine.completeRound(table.tricksWon, result.nextMultiplier)
    val scored = Scored(cfg.round, cfg.trump, cfg.callerId, totals, computeWinner(totals))
    client.scored = scored
    return scored
  }

  private data class Scored(
    val round: Int,
    val trump: Suit,
    val callerId: String?,
    val totals: Map<String, Int>,
    val winners: List<String>,
  )

  /**
   * Fire [block] on all four clients at once and assert the race
   * CONVERGED: exactly one client won (no call may throw — an emulator
   * with zero retry makes a lost race an idempotent no-op, not an error),
   * and every client's result agrees on [agreed].
   */
  private suspend fun <T> raceAll(block: suspend (Client) -> T): List<T> = coroutineScope {
    clients.map { async(Dispatchers.IO) { block(it) } }.awaitAll()
  }

  private fun <T> assertConverged(
    what: String,
    results: List<T>,
    won: (T) -> Boolean,
    agreed: (T) -> Any?,
  ) {
    assertEquals("$what: all four clients answered", CLIENTS, results.size)
    val winners = results.count(won)
    assertEquals("$what: exactly one client won the race", 1, winners)
    val first = results.first()
    results.forEachIndexed { i, result ->
      assertEquals("$what: seat ${clients[i].seatId} agrees on the outcome",
        agreed(first), agreed(result))
    }
  }

  /**
   * The Firestore EMULATOR has zero transaction retry and, under a real
   * stateful multi-client workload, denies a rules-correct transaction on
   * its first attempt — INVESTIGATION_CLOSEOUT.md §2 reproduced this
   * deterministically (5/5 runs) and verified the identical writes against
   * real Firestore, which never denies them. Retrying here supplies the
   * retry-on-conflict the emulator fails to perform internally.
   *
   * Suite-only, and never in runTx: production relies on runTx surfacing a
   * genuine denial, and on real Firestore there is nothing to retry. A
   * PERMISSION_DENIED means the transaction committed NOTHING (Firestore is
   * all-or-nothing), so a retry can never double-apply a write; and every
   * retried call is itself idempotent, so a lost race resolves as the
   * RETURNED no-op the convergence contract promises rather than as a
   * second write. The fresh [loadMatch] between attempts is a real round
   * trip that lets a racing client's write land first.
   */
  private suspend fun <T> retryEmulatorTx(what: String, block: suspend () -> T): T {
    var last: ServiceException? = null
    for (attempt in 1..EMULATOR_TX_ATTEMPTS) {
      try {
        return block()
      } catch (err: ServiceException) {
        if (err.reason !in RETRIED_REASONS) throw err
        last = err
        if (attempt < EMULATOR_TX_ATTEMPTS) loadMatch()
      }
    }
    error("$what: the emulator denied a rules-correct transaction " +
      "$EMULATOR_TX_ATTEMPTS times (an emulator artifact, per " +
      "INVESTIGATION_CLOSEOUT.md §2): ${last?.message}")
  }

  // ── rematch: vote, unanimous YES, next match ────────────────────────

  data class RematchReport(
    val voteCreated: Boolean,
    val newMatchId: String,
    val rematchOf: String,
    val newMatch: MatchDoc,
  )

  suspend fun rematch(): RematchReport {
    val creates = raceAll { client ->
      retryEmulatorTx("createRematchVote") { client.store.createRematchVote(matchId) }
    }
    assertConverged("createRematchVote", creates, { it.created }) { it.vote?.status }

    val votes = raceAll { client ->
      retryEmulatorTx("submitRematchVote") {
        client.store.submitRematchVote(matchId, VoteDoc.VOTE_VALUES.first())
      }
    }
    assertEquals("every vote was recorded", CLIENTS, votes.count { it.accepted })
    // A client's returned status is the vote doc as that client saw it AT
    // ITS OWN submission: submitRematchVote only flips the doc to ALL_YES
    // on the deciding (4th) vote, so votes.first().status is OPEN unless
    // client 1 happens to be the last to vote — the assertion was flaky by
    // construction, passing only in the orderings where seat 1 decided. All
    // four votes ARE recorded (above); re-read the doc and confirm it
    // settled unanimous on a fresh read instead.
    val settled = withTimeoutOrNull(VOTE_SETTLE_TIMEOUT_MS) {
      val voteRef = db.collection("matches").document(matchId)
        .collection("rematchVote").document("current")
      while (true) {
        val vote = voteRef.get().await().data?.let { VoteDoc.fromFields(it) }
        if (vote?.status == VoteDoc.STATUS_ALL_YES) return@withTimeoutOrNull vote
        delay(VOTE_SETTLE_POLL_MS)
      }
    }
    assertNotNull(
      "the vote doc never settled on ALL_YES within ${VOTE_SETTLE_TIMEOUT_MS}ms",
      settled,
    )

    val creates2 = raceAll { client ->
      retryEmulatorTx("createRematchMatch") { client.store.createRematchMatch(matchId) }
    }
    assertConverged("createRematchMatch", creates2, { it.created }) { it.newMatchId }

    val newMatchId = creates2.first().newMatchId!!
    val snap = db.collection("matches").document(newMatchId).get().await()
    assertTrue("the rematch match document exists", snap.exists())
    val newMatch = MatchDoc.fromFields(snap.data ?: emptyMap())
      ?: error("the rematch match document could not be parsed")
    assertEquals("the rematch links back to this match", matchId, newMatch.rematchOfMatchId)
    assertEquals("the rematch keeps the room", roomCode, newMatch.roomId)
    // seats copied verbatim from the vote — same seats, same assignments.
    assertEquals("the rematch keeps the seat assignments", uids, newMatch.seats)

    return RematchReport(
      voteCreated = creates.first().created,
      newMatchId = newMatchId,
      rematchOf = matchId,
      newMatch = newMatch,
    )
  }

  private companion object {
    const val CLIENTS = 4
    const val HAND_SIZE = 13
    const val ESTIMATE = 2
    const val AUCTION_BID = 4
    const val BIDDING_GUARD = 120
    const val MAX_PLAUSIBLE_ROUNDS = 40

    // Bounded wait for the rematch vote doc to settle after all four
    // clients have submitted — see rematch(). The deciding vote's
    // transaction has committed by the time raceAll returns, but the
    // emulator can serve a stale read, so poll until the doc is ALL_YES
    // rather than trusting any single snapshot.
    const val VOTE_SETTLE_TIMEOUT_MS = 20_000L
    const val VOTE_SETTLE_POLL_MS = 250L

    // Bounded retries on the emulator's spurious first-attempt denials —
    // see retryEmulatorTx(). Real Firestore never needs them.
    const val EMULATOR_TX_ATTEMPTS = 8
    val RETRIED_REASONS = setOf(Reasons.PERMISSION_DENIED, Reasons.UNAVAILABLE)
  }
}

/** Reconstructs scoring bids from a bidding outcome — the derivation
 *  table-engine.js performs, duplicated here because the production
 *  helper lives in :app (QuickMatchViewModel), unreachable from :services. */
private fun bidsFromOutcome(cfg: RoundCfg): Map<String, Bid> =
  cfg.estimates.keys.union(cfg.dashCallers).associateWith { seat ->
    when {
      cfg.dashCallers.contains(seat) -> Bid(BidType.DASHCALL, 0)
      (cfg.estimates[seat] ?: 0) == 0 -> Bid(BidType.DASH, 0)
      else -> Bid(BidType.TRICKS, cfg.estimates.getValue(seat))
    }
  }
