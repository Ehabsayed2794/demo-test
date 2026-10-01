package com.estemshan.services

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.Card
import com.estemshan.engine.ExtensionReason
import com.estemshan.engine.GameSession
import com.estemshan.engine.HandAuthority
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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
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
  suspend fun start(): String {
    val creator = uids.getValue(seats.first())
    roomCode = clients.first().rooms.createRoom(creator, "suite-room")
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
    val engineTurn = clients.first().engine.getTurn()
    for (client in clients) {
      assertEquals("seat ${client.seatId} agrees with the document's turn",
        engineTurn, client.engine.getTurn())
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

  // ── S20 denial probe v2 (TEMPORARY — delete once the deterministic
  //    submitCard rules denial is fixed) ─────────────────────────────

  /**
   * TEMPORARY: calls the REAL [MatchService.submitCard] for the engine's
   * opening seat and inspects the committed document afterwards. If
   * `cardPhase` became PLAY the publish half succeeded and the card write
   * is the denial; if it stayed null the publish half itself was denied.
   * The ladder then replays each half separately as raw transactions to
   * capture the emulator's verbose verdict per write.
   */
  suspend fun probeSubmitCardHalves(): String {
    driveBidding(1)
    val sb = StringBuilder()
    val before = loadMatch()
    val engineTurn = clients.first().engine.getTurn()
    val submitClient = bySeat.getValue(engineTurn ?: error("the engine has no turn"))
    // RAW uids matter, not seats: the rules dispatch routes on
    // affectedKeys(), and the publish is only routed to
    // isValidOpeningTurnPublication() when 'turn' is among them. If the
    // engine's opening seat already IS the document's turn holder, the
    // publish writes turn to the SAME uid and the dispatch never routes.
    sb.append("BEFORE doc.turn=${before.turn} ")
      .append("doc.dealer=${before.dealer} ")
      .append("submitter.uid=${submitClient.uid} ")
      .append("doc.turn==submitter.uid=${before.turn == submitClient.uid} ")
      .append("doc.turn==doc.dealer=${before.turn == before.dealer} ")
      .append("openingWindow=${before.isRoundOneOpeningWindow} ")
      .append("cardPhase=${before.cardPhase} version=${before.version}\n")
    sb.append("BEFORE engine.turn=").append(engineTurn).append('\n')

    val table = submitClient.engine.getPlayState() ?: error("no table engine")
    val turnNow = table.turn ?: error("the table has no turn")
    assertEquals("the probe submits as the engine's turn holder", engineTurn, turnNow)
    val card = legalCards(table, turnNow).firstOrNull()
      ?: error("no legal card for $turnNow")

    sb.append("=== RUNG E the real submitCard() (publish + card) ===\n")
    val e = try {
      submitClient.store.submitCard(matchId, card)
      "ALLOWED"
    } catch (t: Throwable) {
      "DENIED: ${t.message}"
    }
    sb.append(e).append('\n')
    val after = loadMatch()
    sb.append("AFTER cardPhase=${after.cardPhase} version=${after.version} ")
      .append("cardLog=${after.cardLog.size} turn=${after.turn}(raw)\n")
    sb.append("PUBLISH HALF: ").append(if (after.cardPhase == "PLAY") "SUCCEEDED" else "DID NOT LAND").append('\n')

    // Rung F: the publish write exactly as MatchService writes it — turn
    // set to the SUBMITTER's own uid, which is what the document already
    // holds when the engine's opening seat is the round's turn holder.
    sb.append("=== RUNG F publish write (turn = submitter uid) ===\n")
    val f = if (after.cardPhase == "PLAY") {
      "SKIPPED (the publish already landed in rung E)"
    } else {
      try {
        publishProbeWrite(submitClient, after, submitClient.uid)
        "ALLOWED"
      } catch (t: Throwable) {
        "DENIED: ${t.message}"
      }
    }
    sb.append(f).append('\n')

    // Rung G: the SAME publish shape but with ANOTHER seat's uid as turn,
    // so 'turn' genuinely changes and the dispatch's
    // `('turn' in affected)` clause can fire. If G is ALLOWED while F is
    // DENIED, the root cause is the DISPATCH ROUTING (a publish that
    // leaves turn unchanged is unroutable), not the publish's own validity.
    val postF = loadMatch()
    val otherUid = before.seats.entries.firstOrNull { it.value != submitClient.uid }?.value
      ?: error("probe: no other seat to borrow a uid from")
    sb.append("=== RUNG G publish write (turn = a DIFFERENT seat uid) ===\n")
    val g = if (postF.cardPhase == "PLAY") {
      "SKIPPED (the publish already landed)"
    } else {
      try {
        publishProbeWrite(submitClient, postF, otherUid)
        "ALLOWED"
      } catch (t: Throwable) {
        "DENIED: ${t.message}"
      }
    }
    sb.append(g).append('\n')
    return sb.toString()
  }

  private suspend fun publishProbeWrite(client: Client, match: MatchDoc, turnUid: String) {
    val matchRef = client.firebase.db.collection("matches").document(matchId)
    runTx(client.firebase.db) { tx ->
      val snap = tx.get(matchRef)
      if (!snap.exists()) {
        return@runTx TxOutcome.Err(
          ServiceException(Reasons.MATCH_NOT_FOUND, "probe: the match disappeared"),
        )
      }
      val fresh = MatchDoc.fromFields(snap.data ?: emptyMap())
        ?: return@runTx TxOutcome.Err(
          ServiceException(Reasons.MATCH_NOT_FOUND, "probe: the match could not be parsed"),
        )
      tx.update(matchRef, mapOf(
        "turn" to turnUid,
        "cardPhase" to MatchDoc.CARD_PHASE_PLAY,
        "version" to fresh.version + 1,
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(fresh.version + 1)
    }
  }

  // ── S20 denial probe (TEMPORARY — delete once the deterministic
  //    submitCard rules denial is fixed) ─────────────────────────────

  /**
   * TEMPORARY: isolates which clause of `isValidCardSubmission()` the
   * emulator's rules authorizer rejects, by replaying the card write in
   * progressively reduced shapes and capturing the emulator's verbose
   * per-write error for each one. Whatever the outcome, the caller fails
   * the test with this text so the whole ladder lands verbatim in the
   * downloaded test report.
   */
  suspend fun probeCardWriteShapes(): String {
    driveBidding(1)
    val sb = StringBuilder()
    val pre = loadMatch()
    sb.append("PRE-STATE turn=${pre.turn} cardPhase=${pre.cardPhase} version=${pre.version} ")
      .append("round=${pre.currentRound} status=${pre.status} ")
      .append("cardLog=${pre.cardLog.size} biddingLog=${pre.biddingLog.size} ")
      .append("seats=${pre.seats}\n")

    val turnSeat = fun(m: MatchDoc): String =
      m.uidToSeat(m.turn ?: error("probe: the document has no turn"))
        ?: error("probe: the turn uid owns no seat")

    val rungs: List<Pair<String, (MatchDoc) -> Map<String, Any?>>> = listOf(
      "A production: cardLog arrayUnion + turn + cardPhase" to
        { m -> val s = turnSeat(m); cardProbePatch(m, s, probeNextUid(m, s), true, true) },
      "B literal cardLog + turn + cardPhase" to
        { m -> val s = turnSeat(m); cardProbePatch(m, s, probeNextUid(m, s), false, true) },
      "C production minus turn/cardPhase" to
        { m -> val s = turnSeat(m); cardProbePatch(m, s, null, true, false) },
      "D version + updatedAt only (baseline)" to
        { m -> mapOf("version" to (m.version + 1), "updatedAt" to FieldValue.serverTimestamp()) },
    )

    for ((name, buildPatch) in rungs) {
      sb.append("=== RUNG $name ===\n")
      val outcome = try {
        probeWrite(buildPatch)
        "ALLOWED"
      } catch (t: Throwable) {
        "DENIED: ${t.message}"
      }
      sb.append(outcome).append('\n')
    }
    return sb.toString()
  }

  /** Any other seat's uid: the rules only check the new turn is SOME real
   *  seat owner, never that it is the engine-correct next seat. */
  private fun probeNextUid(m: MatchDoc, seat: String): String? =
    m.seats.entries.firstOrNull { it.key != seat }?.value

  private fun cardProbePatch(
    m: MatchDoc,
    seat: String,
    nextUid: String?,
    arrayUnion: Boolean,
    withTurnPhase: Boolean,
  ): Map<String, Any?> {
    val entry = mapOf(
      "seatId" to seat,
      "card" to mapOf("suit" to "SPADES", "rank" to mapOf("v" to 2L, "s" to "2")),
      "round" to m.currentRound,
    )
    val cardLog: Any = if (arrayUnion) {
      FieldValue.arrayUnion(entry)
    } else {
      m.cardLog.map { it.toFields() } + entry
    }
    return buildMap {
      put("cardLog", cardLog)
      put("lastCardSeat", seat)
      put("version", m.version + 1)
      put("updatedAt", FieldValue.serverTimestamp())
      if (withTurnPhase) {
        put("turn", nextUid)
        put("cardPhase", "PLAY")
      }
    }
  }

  /**
   * One transaction run as the seat that currently holds the document's
   * `turn` — the same authority `publishOpeningTurnIfNeeded` establishes
   * before a real card write, so `oldData.turn == request.auth.uid` holds.
   */
  private suspend fun probeWrite(buildPatch: (MatchDoc) -> Map<String, Any?>) {
    val peek = loadMatch()
    val turnSeat = peek.uidToSeat(peek.turn ?: error("probe: the document has no turn"))
      ?: error("probe: the turn uid owns no seat")
    val client = bySeat.getValue(turnSeat)
    val db = client.firebase.db
    val matchRef = db.collection("matches").document(matchId)
    runTx(db) { tx ->
      val snap = tx.get(matchRef)
      if (!snap.exists()) {
        return@runTx TxOutcome.Err(
          ServiceException(Reasons.MATCH_NOT_FOUND, "probe: the match disappeared"),
        )
      }
      val m = MatchDoc.fromFields(snap.data ?: emptyMap())
        ?: return@runTx TxOutcome.Err(
          ServiceException(Reasons.MATCH_NOT_FOUND, "probe: the match could not be parsed"),
        )
      tx.update(matchRef, buildPatch(m))
      TxOutcome.Ok(m.version + 1)
    }
  }

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
    assertEquals("the vote closed unanimous", VoteDoc.STATUS_ALL_YES, votes.first().status)

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
