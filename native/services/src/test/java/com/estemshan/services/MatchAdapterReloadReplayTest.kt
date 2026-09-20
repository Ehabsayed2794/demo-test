package com.estemshan.services

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.DECK_SUITS
import com.estemshan.engine.EmitResult
import com.estemshan.engine.RANKS
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.emit
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.initTable
import com.estemshan.services.model.CardLogEntry
import com.estemshan.services.model.GameState
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.RoundResultEntry
import com.estemshan.services.model.SEAT_IDS
import com.estemshan.services.model.StoredCard
import com.estemshan.services.session.GameSessionPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of tests/reload-resume-replay.test.cjs — the P1-3 reconnect regression
 * (issue #16), deterministic, no browser, no emulator, no timing.
 *
 * What this proves, through the REAL engine functions and the REAL
 * MatchAdapter (the replay path takes a plain MatchDoc, so no Firestore is
 * needed at all):
 *
 *   CASE A pins the hazard, unchanged by the page fix: replaying the full
 *   authoritative cardLog from wiped registries into a RESTORED mid-round
 *   engine double-resolves history — trickNo 13, tricksWon summing to 12 on
 *   a 24-card log. That is byte-for-byte the production signature from
 *   issue #16. If this case ever stops diverging, the adapter gained
 *   cross-reload epoch protection — update this file then; do not "fix" it
 *   by weakening the assertions.
 *
 *   CASE B pins the safe path the page takes on every load (discard the
 *   persisted play state, reseed hands, re-init the engine BEFORE replaying):
 *   the same full-log replay onto a round-fresh engine converges EXACTLY
 *   (trick 7, 6 counted, count 24) and genuine play continues cleanly
 *   (trick 8, 7 counted, count 28).
 *
 * Determinism, not luck: hands are rigged so the auction caller holds all
 * 13 trumps and wins every trick. The reloaded trick's leader is therefore
 * ALWAYS the caller — the exact agreeing case (restored turn == the log's
 * first entry) that diverged in production ~1/4 of deals. Both cases align
 * on every run by construction.
 */
class MatchAdapterReloadReplayTest {

  private val seats: List<String> = SEAT_IDS
  private val seatUids: Map<String, String> =
    seats.mapIndexed { i, s -> s to "u${i + 1}" }.toMap()

  // ── hermetic harness ───────────────────────────────────────────────

  /**
   * The minimal GameSession: holds the two engine states and the turn the
   * resolve mirror writes. Everything else is inert — this test exercises
   * the adapter's replay path, not match lifecycle.
   */
  private class FakeGameSession : GameSessionPort {
    var bidding: BiddingState? = null
    var table: TableState? = null
    // Backs getTurn()/setTurn() — a public `var turn` would generate JVM
    // accessors with the same signatures as the overrides below.
    private var _turn: String? = null

    override fun getPlayers(): List<String> = SEAT_IDS
    override fun getRoundNumber(): Int = 1
    override fun getMaxRounds(): Int = 18
    override fun getDealer(): String? = SEAT_IDS.first()
    override fun setDealer(uid: String?) {}
    override fun getTurn(): String? = _turn
    override fun setTurn(seatId: String?) { _turn = seatId }
    override fun nextRound(): Int = 2
    override fun getPlayState(): TableState? = table
    override fun updatePlayState(state: TableState) { table = state }
    override fun getBiddingState(): BiddingState? = bidding
    override fun updateBiddingState(state: BiddingState) { bidding = state }
    override fun getMatchScores(): Map<String, Int> = emptyMap()
    override fun setMatchScores(scores: Map<String, Int>) {}
    override fun recordRoundResult(entry: RoundResultEntry) {}
    override fun getLastRoundResult(): RoundResultEntry? = null
    override fun setWinnerIds(ids: List<String>) {}
    override fun getWinnerIds(): List<String> = emptyList()
    override fun isMatchComplete(): Boolean = false
  }

  /** The mutable authoritative document the replay reads from. */
  private inner class ReplayDoc {
    var version: Int = 1
    val cardLog: MutableList<CardLogEntry> = mutableListOf()
    fun snapshot(): MatchDoc = MatchDoc(
      roomId = "room-a",
      players = seatUids.values.toList(),
      status = MatchDoc.STATUS_STARTING,
      currentRound = 1,
      maxRounds = 18,
      extendedRounds = emptyList(),
      dealer = seatUids.getValue(seats.first()),
      turn = null,
      seats = seatUids,
      version = version,
      biddingOpen = true,
      bids = emptyMap(),
      lastBidSeat = null,
      cardLog = cardLog.toList(),
      lastCardSeat = null,
      cardPhase = null,
      biddingLog = emptyList(),
      gameState = GameState.NOT_DEALT,
    )
  }

  private data class Converged(val count: Int, val resolved: Int?)

  private data class Setup(
    val outcome: BiddingOutcome,
    val fullHands: Map<String, List<Card>>,
    val doc: ReplayDoc,
  ) {
    val callerSeat: String get() = outcome.callerId!!
    val trump: Suit get() = outcome.trump
  }

  // ── rigged determinism ─────────────────────────────────────────────

  /**
   * The caller holds ALL trumps (wins every trick it leads — and it leads
   * every trick, since it never loses one); everyone else holds only
   * non-trumps. 39 non-trump cards / 3 seats = exactly 13 each.
   */
  private fun riggedHands(trump: Suit, callerSeat: String): Map<String, List<Card>> {
    val callerCards = RANKS.map { Card(trump, it) }
    val rest = DECK_SUITS.filter { it != trump }.flatMap { s -> RANKS.map { Card(s, it) } }
    assertEquals("rig deals exactly 39 non-trump cards", 39, rest.size)
    val hands = LinkedHashMap<String, List<Card>>()
    hands[callerSeat] = callerCards
    seats.filter { it != callerSeat }.forEachIndexed { i, seat ->
      hands[seat] = rest.subList(i * 13, i * 13 + 13)
    }
    return hands
  }

  /**
   * Real bidding with exactly one bidder, so the caller is deterministic
   * AND known: whoever bids first wins. Mirrors the JS helper minus its
   * fixed-seat assumption (the engine picks the seat here).
   */
  private fun driveBiddingSingleBidder(): BiddingOutcome {
    var state = initNormalRound(round = 1, dealer = seats.first(), seats = seats)
    var bidDone = false
    var guard = 0
    while (guard < 30) {
      guard++
      if (state.subPhase == BiddingPhase.DONE) break
      val actor = state.waitingFor ?: error("no seat waiting in ${state.subPhase}")
      val intent: BiddingIntent = when (state.subPhase) {
        BiddingPhase.DASH -> BiddingIntent.DashCallDecision(actor, declaredDashCall = false)
        BiddingPhase.AUCTION -> if (!bidDone) {
          bidDone = true
          BiddingIntent.AuctionBid(actor, isPass = false, tricks = 4, suit = Suit.SPADES)
        } else {
          BiddingIntent.AuctionBid(actor, isPass = true)
        }
        BiddingPhase.CONFIRM -> BiddingIntent.ConfirmCall(
          actor,
          state.auctionTop,
          state.auctionSuit ?: error("no auction suit to confirm"),
        )
        BiddingPhase.ESTIMATES -> BiddingIntent.FinalEstimate(actor, 2)
        BiddingPhase.DONE -> break
      }
      state = when (val result = emit(state, intent)) {
        is EmitResult.Applied -> result.state
        is EmitResult.Completed -> return result.outcome
        is EmitResult.GeneralPass -> error("unexpected general pass in a single-bidder auction")
        is EmitResult.Rejected -> error("bidding rejected: ${result.reason}")
      }
    }
    error("bidding did not reach DONE within the guard")
  }

  /** Fresh round setup through the real path: bidding -> rigged hands ->
   *  initTable, exactly the production ordering. */
  private fun freshTableSetup(session: FakeGameSession, adapter: MatchAdapter, tag: String): Setup {
    val outcome = driveBiddingSingleBidder()
    assertTrue("$tag bidding reached DONE", outcome.callerId != null)
    assertTrue("$tag auction produced a real trump suit", outcome.trump != Suit.SANS)
    adapter.resetSyncState()

    val setup = Setup(outcome, riggedHands(outcome.trump, outcome.callerId!!), ReplayDoc())
    session.table = initTable(roundOneCfg(setup), seats)
    session.setTurn(setup.callerSeat)

    val st0 = session.table!!
    assertEquals("$tag engine opens at trick 1", 1, st0.trickNo)
    assertEquals("$tag the caller leads", setup.callerSeat, st0.leaderId)
    assertEquals("$tag the caller holds the turn", setup.callerSeat, st0.turn)
    return setup
  }

  // ── the replay paths ───────────────────────────────────────────────

  /** One genuine play through the REAL adapter replay path: the engine-turn
   *  seat plays its first hand card, the log and version move, and the
   *  adapter replays it plus any trick it completes. */
  private fun replayOneGenuinePlay(
    session: FakeGameSession,
    adapter: MatchAdapter,
    setup: Setup,
    matchId: String,
  ): Boolean {
    val st = session.table ?: error("no table engine")
    val seat = st.turn ?: error("no turn to play")
    val card = st.cfg.hands[seat]?.firstOrNull() ?: return false
    setup.doc.cardLog.add(CardLogEntry(seat, StoredCard.fromEngine(card), 1))
    setup.doc.version += 1
    val rc = adapter.applyRemoteCard(matchId, setup.doc.snapshot(), setup.callerSeat)
    if (rc.desync) return false
    repeat(13) {
      val rt = adapter.applyRemoteTrick(matchId, setup.doc.snapshot())
      if (!rt.applied) return@repeat
    }
    return true
  }

  private fun replayGenuinePlays(
    session: FakeGameSession,
    adapter: MatchAdapter,
    setup: Setup,
    matchId: String,
    n: Int,
  ): Boolean {
    repeat(n) { if (!replayOneGenuinePlay(session, adapter, setup, matchId)) return false }
    return true
  }

  /** Full-log redelivery after a registry wipe (what every page load's
   *  first deliveries do): alternate applyRemoteCard/applyRemoteTrick until
   *  an iteration makes no progress — one applyRemoteCard call CANNOT
   *  replay a whole log, the 4th emit of each trick flips the engine to
   *  RESOLVING, which rejects further emits until the trick is resolved. */
  private fun redeliverFullLog(
    adapter: MatchAdapter,
    setup: Setup,
    matchId: String,
  ): Converged {
    for (k in 0 until 40) {
      val countBefore = adapter.lastAppliedCardCount(matchId)
      val trickBefore = adapter.lastResolvedTrickNo(matchId)
      adapter.applyRemoteCard(matchId, setup.doc.snapshot(), setup.callerSeat)
      val rt = adapter.applyRemoteTrick(matchId, setup.doc.snapshot())
      val countAfter = adapter.lastAppliedCardCount(matchId)
      val trickAfter = adapter.lastResolvedTrickNo(matchId)
      if (countAfter == countBefore && trickAfter == trickBefore && !rt.applied) break
    }
    return Converged(adapter.lastAppliedCardCount(matchId), adapter.lastResolvedTrickNo(matchId))
  }

  private fun tricksWonSum(state: TableState): Int = state.tricksWon.values.sum()

  // ═══════════════════════════════════════════════════════════════════
  // CASE A — pin the hazard: replaying the full log from wiped registries
  // into a RESTORED mid-round engine double-resolves history.
  // ═══════════════════════════════════════════════════════════════════

  @Test
  fun caseAHazard_doubleResolvesHistoryFromWipedRegistries() {
    val session = FakeGameSession()
    val adapter = MatchAdapter(session)
    val matchId = "reload-case-a"
    val a = freshTableSetup(session, adapter, "A setup:")

    assertTrue("A history builds cleanly (24 genuine plays, no desync)",
      replayGenuinePlays(session, adapter, a, matchId, 24))

    val pre = session.table!!
    assertEquals("A pre-reload engine is mid-round (trick 7)", 7, pre.trickNo)
    assertEquals("A pre-reload has 6 tricks counted", 6, tricksWonSum(pre))
    assertEquals("A pre-reload turn is the caller's", a.callerSeat, pre.turn)
    assertEquals("A pre-reload phase is PLAY", TablePhase.PLAY, pre.phase)
    assertEquals("A adapter applied the full history", 24, adapter.lastAppliedCardCount(matchId))
    assertEquals("A adapter resolved the full history", 6, adapter.lastResolvedTrickNo(matchId))

    // Structural reload WITHOUT the page fix: registries wiped, engine state
    // kept as the restore would leave it, hands wiped then this seat's own
    // reseeded full — other seats stay absent, exactly like production.
    adapter.resetSyncState(matchId)
    session.table = session.table!!.copy(
      cfg = session.table!!.cfg.copy(hands = mapOf(a.callerSeat to a.fullHands.getValue(a.callerSeat))),
    )
    a.doc.version += 50

    val redA = redeliverFullLog(adapter, a, matchId)
    val post = session.table!!
    assertEquals("A HAZARD: history double-resolved the trick count", 13, post.trickNo)
    assertEquals("A HAZARD: 12 tricks counted on a 24-card log", 12, tricksWonSum(post))
    assertEquals("A HAZARD: the adapter still counts 24", 24, redA.count)
    assertEquals("A HAZARD: the resolve registry hit 12", 12, redA.resolved)

    // The 4 genuine next plays (trick 13, caller leads) land on the
    // diverged engine and race it to DONE, exactly like R5. Only the
    // caller's hand exists in the live engine, so the other seats' cards
    // come from the rigged deal: any non-trump is legal when trump is led.
    val usedKeys = a.doc.cardLog.map { it.card.suit + ":" + it.card.rank.v }.toMutableSet()
    val turnOrder = generateSequence(a.callerSeat) { prev ->
      seats[(seats.indexOf(prev) + 1) % seats.size]
    }
    turnOrder.take(4).forEach { seat ->
      val card = nextUnusedCard(a, seat, usedKeys)
      assertNotNull("A next-play card available for $seat", card)
      usedKeys.add(card!!.suit.name + ":" + card.rank.v)
      a.doc.cardLog.add(CardLogEntry(seat, StoredCard.fromEngine(card), 1))
      a.doc.version += 1
    }

    redeliverFullLog(adapter, a, matchId)
    val fin = session.table!!
    assertEquals("A PRODUCTION SIGNATURE: DONE", TablePhase.DONE, fin.phase)
    assertEquals("A PRODUCTION SIGNATURE: trick 13", 13, fin.trickNo)
    assertEquals("A PRODUCTION SIGNATURE: 13 counted on a 28-card log", 13, tricksWonSum(fin))
    assertEquals("A adapter count is 28", 28, adapter.lastAppliedCardCount(matchId))
    assertEquals("A resolve registry is 13", 13, adapter.lastResolvedTrickNo(matchId))
  }

  /** First rigged card for [seat] not already present in the log. The
   *  caller's matches its live hand; an absent seat's is exactly the
   *  observed card the adapter seeds for it either way. */
  private fun nextUnusedCard(
    setup: Setup,
    seat: String,
    usedKeys: MutableSet<String>,
  ): Card? {
    val live = setup.fullHands.getValue(seat)
    return live.firstOrNull { it.suit.name + ":" + it.rank.v !in usedKeys }
  }

  // ═══════════════════════════════════════════════════════════════════
  // CASE B — pin the safe path: the same full-log replay onto a
  // round-fresh engine converges EXACTLY, and genuine play continues.
  // ═══════════════════════════════════════════════════════════════════

  @Test
  fun caseBSafePath_replayOntoRoundFreshEngineConvergesExactly() {
    val session = FakeGameSession()
    val adapter = MatchAdapter(session)
    val matchId = "reload-case-b"
    val b = freshTableSetup(session, adapter, "B setup:")

    assertTrue("B history builds cleanly (24 genuine plays, no desync)",
      replayGenuinePlays(session, adapter, b, matchId, 24))

    // Reload WITH the page fix: registries wiped, the persisted play state
    // discarded, hands reseeded from the authoritative source, the engine
    // (re)started — all BEFORE the log replays.
    adapter.resetSyncState(matchId)
    session.table = null
    session.bidding = null
    // The same round config, reseeded from the authoritative hand source —
    // nothing about the deal is reinvented, only the engine state is fresh.
    session.table = initTable(roundOneCfg(b), seats)
    session.setTurn(b.callerSeat)

    val fresh = session.table!!
    assertEquals("B post-clear engine restarts at trick 1", 1, fresh.trickNo)
    assertEquals("B post-clear counts zero tricks", 0, tricksWonSum(fresh))
    assertEquals("B post-clear phase is PLAY", TablePhase.PLAY, fresh.phase)

    b.doc.version += 50
    val redB = redeliverFullLog(adapter, b, matchId)
    val post = session.table!!
    assertEquals("B replay converges to trick 7", 7, post.trickNo)
    assertEquals("B replay counts exactly 6 tricks", 6, tricksWonSum(post))
    assertEquals("B replay ends in PLAY", TablePhase.PLAY, post.phase)
    assertEquals("B replay's turn is the caller's", b.callerSeat, post.turn)
    assertEquals("B adapter count is 24", 24, redB.count)
    assertEquals("B resolve registry is 6", 6, redB.resolved)

    assertTrue("B genuine play continues cleanly (4 more plays)",
      replayGenuinePlays(session, adapter, b, matchId, 4))
    val fin = session.table!!
    assertEquals("B reaches trick 8", 8, fin.trickNo)
    assertEquals("B counts 7 tricks", 7, tricksWonSum(fin))
    assertEquals("B adapter count is 28", 28, adapter.lastAppliedCardCount(matchId))
  }

  /** The RoundCfg the (re)start uses — built from the auction's own outcome
   *  and the authoritative hands, never from invented estimates. */
  private fun roundOneCfg(setup: Setup): RoundCfg {
    val o = setup.outcome
    return RoundCfg(
      round = 1,
      trump = o.trump,
      callerId = o.callerId,
      withPlayers = o.withPlayers,
      estimates = o.estimates,
      dashCallers = o.dashCallers,
      leaderId = o.leaderId,
      riskId = o.riskPlayerId,
      hands = setup.fullHands,
    )
  }
}
