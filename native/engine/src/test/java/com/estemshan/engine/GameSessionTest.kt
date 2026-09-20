package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GameSession — the pure-JVM state store ported from design-ui/engine/
 * session.js. Coverage maps to the Phase 6 definition of done: lifecycle
 * + identity, scoring mode, real-only players, dealer rotation (incl.
 * wraparound), round-fresh reset semantics, valid-for-round guards,
 * hand-authority discipline, the bidding-completion funnel, score and
 * multi-winner semantics (docs/specs/04-scoring.md), and the remote
 * mirror's delegation-only contract.
 *
 * The P1-3 pair at the bottom ports tests/reload-resume-replay.test.cjs
 * to this layer: the hazard pin must still reproduce the double-resolve,
 * the safe pin must converge exactly. Determinism comes from rigged
 * hands, not luck — the caller holds every trump and wins every trick it
 * leads, so the turn chain is fixed by construction.
 */
class GameSessionTest {

  private val seats: List<String> = DEFAULT_SEATS
  private val caller: String = "p1"
  private val trump: Suit = Suit.SPADES

  private fun newSession(): GameSession {
    var tick = 0L
    val session = GameSession(clock = { ++tick })
    session.init("ranked", force = true)
    return session
  }

  private fun testOutcome(): BiddingOutcome = BiddingOutcome(
    trump = trump,
    callerId = caller,
    withPlayers = listOf("p3"),
    estimates = seats.associateWith { 2 },
    dashCallers = emptyList(),
    riskPlayerId = "p4",
    leaderId = caller,
  )

  // ════════════════════════════════════════════════════════════════
  // Lifecycle + identity
  // ════════════════════════════════════════════════════════════════

  @Test
  fun lifecycle_initKeepsAnInProgressMatchUntilForced() {
    var tick = 0L
    val session = GameSession(clock = { ++tick })

    val first = session.init("ranked")
    assertEquals("ranked", first.mode)
    assertTrue("matchId is opaque and non-blank", first.matchId.isNotBlank())
    assertEquals(1, first.round.number)
    assertEquals(18, first.round.maxRounds)

    // A later init without force keeps the match (a reload mid-match must
    // not discard it); only the mode label moves.
    val kept = session.init("ai")
    assertEquals(first.matchId, kept.matchId)
    assertEquals("ai", kept.mode)

    val forced = session.init("ai", force = true)
    assertNotEquals(first.matchId, forced.matchId)
  }

  @Test
  fun lifecycle_resetIsAlwaysFresh() {
    var tick = 0L
    val session = GameSession(clock = { ++tick })
    session.init("ranked", force = true)
    session.setDealer("p3")
    session.setTurn("p2")

    val after = session.reset("friends")
    assertEquals("friends", after.mode)
    assertEquals("p1", after.dealerId)
    assertEquals(1, after.round.number)
    assertNull(after.turnId)
  }

  @Test
  fun lifecycle_freshSessionStartsZeroed() {
    val snap = newSession().snapshot()
    assertEquals(ScoringMode.NORMAL, snap.scoringMode)
    assertTrue(snap.winnerIds.isEmpty())
    assertEquals(seats.associateWith { 0 }, snap.matchScores)
    assertNull(snap.dealState.roundNumber)
    assertFalse(snap.dealState.completed)
  }

  // ── scoring ruleset ──────────────────────────────────────────────

  @Test
  fun scoringMode_normalByDefaultClassicCapsTheLadderAtTwo() {
    val session = newSession()
    assertEquals(ScoringMode.NORMAL, session.getScoringMode())
    assertEquals(8, session.escalationCap)

    session.setScoringMode(ScoringMode.CLASSIC)
    assertEquals(ScoringMode.CLASSIC, session.getScoringMode())
    assertEquals(2, session.escalationCap)

    session.setScoringMode(ScoringMode.NORMAL)
    assertEquals(8, session.escalationCap)
  }

  // ── players (real only — no mock roster) ─────────────────────────

  @Test
  fun players_startEmptyAndArePopulatedFromARealRoster() {
    val session = newSession()
    assertTrue("no fake names/ranks/coins are invented", session.getPlayers().isEmpty())

    val roster = listOf(
      SessionPlayer("p1", "uid-1", "You", isUser = true),
      SessionPlayer("p2", "uid-2", "Layla", isAI = true),
      SessionPlayer("p3", "uid-3", "Fatima", isRemote = true),
    )
    session.setPlayers(roster)
    assertEquals(3, session.getPlayers().size)
    assertEquals("You", session.getPlayer("p1")?.displayName)
    assertEquals("uid-3", session.getPlayer("p3")?.uid)
    assertNull(session.getPlayer("p4"))

    // The returned list is a copy — mutating the caller's roster cannot
    // leak into the store.
    assertEquals(3, session.getPlayers().size)
  }

  // ── room / dealer / turn ─────────────────────────────────────────

  @Test
  fun dealer_rotatesCounterClockwiseAndWrapsAround() {
    val session = newSession()
    assertEquals("p1", session.getDealer())
    assertEquals("p2", session.rotateDealer())
    assertEquals("p3", session.rotateDealer())
    assertEquals("p4", session.rotateDealer())
    assertEquals("p1", session.rotateDealer())
    assertEquals("p2", session.rotateDealer())
    session.setDealer("p4")
    assertEquals("p1", session.getDealer())
    session.setDealer(null)
    assertNull(session.getDealer())
  }

  @Test
  fun dealer_rotationRefusesASeatNotInThisRoom() {
    val session = newSession()
    session.setDealer("not-a-seat")
    assertThrows(IllegalArgumentException::class.java) { session.rotateDealer() }
  }

  @Test
  fun dealer_rotationIsNullWhenNoDealerIsSet() {
    val session = newSession()
    session.setDealer(null)
    assertNull(session.rotateDealer())
  }

  @Test
  fun room_andTurnRoundTrip() {
    val session = newSession()
    session.setRoom(SessionRoom(code = "ABCD", host = false))
    assertEquals("ABCD", session.getRoom().code)
    assertFalse(session.getRoom().host)
    assertEquals(seats, session.getRoom().seats)

    session.setTurn("p3")
    assertEquals("p3", session.getTurn())
    session.setTurn(null)
    assertNull(session.getTurn())
  }

  // ── round ────────────────────────────────────────────────────────

  @Test
  fun round_nextRoundClearsAuctionFieldsKeepsTheMultiplierAndInvalidatesTheDeal() {
    val session = newSession()
    session.initializeBiddingState(initNormalRound(round = 1, dealer = "p1"))
    session.completeBidding(testOutcome())
    session.dealNewHands()
    session.completeRound(emptyMap(), nextMultiplier = 4)
    assertTrue(session.hasDealtHands())

    val next = session.nextRound()
    assertEquals(2, next)
    assertEquals(2, session.getRound().number)
    // The dealer rotates with the round.
    assertEquals("p2", session.getDealer())
    // Auction-committed fields reset...
    assertNull(session.getRound().trump)
    assertNull(session.getRound().callerId)
    assertTrue(session.getRound().withPlayers.isEmpty())
    assertTrue(session.getRound().estimates.isEmpty())
    assertTrue(session.getRound().dashCallers.isEmpty())
    // ...the Sa'ayda multiplier survives...
    assertEquals(4, session.getRound().multiplier)
    // ...and the previous round's deal + engine states are gone.
    assertFalse(session.hasDealtHands())
    assertTrue(session.getHands().isEmpty())
    assertNull(session.getPlayState())
    assertNull(session.getBiddingState())
  }

  @Test
  fun round_setRoundReplacesWholesale() {
    val session = newSession()
    session.setRound(session.getRound().copy(number = 5, maxRounds = 20))
    assertEquals(5, session.getRound().number)
    assertEquals(20, session.getRound().maxRounds)
  }

  // ════════════════════════════════════════════════════════════════
  // Hands + deal authority
  // ════════════════════════════════════════════════════════════════

  @Test
  fun hands_localAuthorityDealsFourUniqueHandsAndIsIdempotent() {
    val session = newSession()
    assertEquals(HandAuthority.LOCAL, session.getHandAuthorityMode())
    assertFalse(session.hasDealtHands())

    val dealt = session.ensureHandsDealt()
    assertEquals(seats.toSet(), dealt.keys)
    dealt.values.forEach { assertEquals(13, it.size) }
    val all = dealt.values.flatten()
    assertEquals(52, all.size)
    assertEquals(52, all.map { it.id }.toSet().size)
    assertTrue(session.hasDealtHands())

    // No force → the existing deal is reused, not reshuffled.
    assertEquals(dealt, session.ensureHandsDealt())
  }

  @Test
  fun hands_dealValidityIsDealMetadataNotHandSize() {
    val session = newSession()
    session.ensureHandsDealt()
    // A seat that has played every card still counts as dealt for this
    // round — hand size must never be the signal.
    session.setHand("p1", emptyList())
    assertEquals(0, session.getHand("p1").size)
    assertTrue(session.hasDealtHands())
    assertEquals(13, session.getHand("p2").size)
  }

  @Test
  fun hands_forceRedealsTheCurrentRound() {
    val session = newSession()
    session.ensureHandsDealt()
    val forced = session.ensureHandsDealt(force = true)
    assertEquals(seats.toSet(), forced.keys)
    forced.values.forEach { assertEquals(13, it.size) }
    assertTrue(session.hasDealtHands())
    // A redeal is a fresh, complete deal of 52 (not a partial redeal).
    assertEquals(52, forced.values.flatten().map { it.id }.toSet().size)
  }

  @Test
  fun authority_firestoreNeverSynthesizesCardsLocally() {
    val session = newSession()
    assertFalse(session.hasDealtHands())
    session.setHandAuthorityMode(HandAuthority.FIRESTORE)
    assertEquals(HandAuthority.FIRESTORE, session.getHandAuthorityMode())

    // No authoritative hand has arrived: nothing is invented.
    assertTrue(session.ensureHandsDealt().isEmpty())
    assertFalse(session.hasDealtHands())

    // force has no meaning under Firestore authority — a real redeal is a
    // server-transaction decision, never a client one.
    assertTrue(session.ensureHandsDealt(force = true).isEmpty())
    assertFalse(session.hasDealtHands())
  }

  @Test
  fun authority_enteringFirestoreDropsALocallyDealtHand() {
    val session = newSession()
    session.ensureHandsDealt()
    assertTrue(session.hasDealtHands())

    session.setHandAuthorityMode(HandAuthority.FIRESTORE)

    assertTrue(session.getHands().isEmpty())
    assertFalse(session.hasDealtHands())
    // Re-entering firestore mode is not a re-clear (nothing to clear).
    session.setHandAuthorityMode(HandAuthority.FIRESTORE)
    assertTrue(session.getHands().isEmpty())
  }

  @Test
  fun authority_authoritativeHandWritesOneSeatOnly() {
    val session = newSession()
    session.setHandAuthorityMode(HandAuthority.FIRESTORE)
    val cards = listOf(Card(Suit.HEARTS, RANKS.first()), Card(Suit.CLUBS, RANKS.last()))

    val hands = session.setAuthoritativeHand("p2", cards, roundNumber = 1)

    assertEquals(cards, hands["p2"])
    assertEquals(cards, session.getHand("p2"))
    // This client learned exactly one seat — never another seat's cards.
    assertEquals(1, session.getHands().size)
    assertTrue(session.getHand("p3").isEmpty())
    // Deal metadata is stamped exactly like a local deal.
    assertTrue(session.hasDealtHands())
    assertEquals(1, session.snapshot().dealState.roundNumber)

    // An authoritative hand for a DIFFERENT round does not satisfy the
    // current round's deal.
    session.setRound(session.getRound().copy(number = 2))
    assertFalse(session.hasDealtHands())
  }

  // ════════════════════════════════════════════════════════════════
  // Round-scoped state + valid-for-round guards
  // ════════════════════════════════════════════════════════════════

  @Test
  fun playState_isValidOnlyForTheCurrentRound() {
    val session = newSession()
    assertFalse(session.isPlayStateValidForCurrentRound())

    session.initializePlayState(roundOneCfg(riggedHands()))
    assertTrue(session.isPlayStateValidForCurrentRound())

    session.nextRound()
    // A state persisted for the previous round is stale, even if it is
    // restored verbatim after the advance.
    session.updatePlayState(initTable(roundOneCfg(riggedHands()), seats))
    assertFalse(session.isPlayStateValidForCurrentRound())
  }

  @Test
  fun playState_initializeRejectsARoundConfigThatIsNotCurrent() {
    val session = newSession()
    session.nextRound()
    assertThrows(IllegalArgumentException::class.java) {
      session.initializePlayState(roundOneCfg(riggedHands()))
    }
  }

  @Test
  fun biddingState_isValidOnlyForTheCurrentRound() {
    val session = newSession()
    assertFalse(session.isBiddingStateValidForCurrentRound())

    session.initializeBiddingState(initNormalRound(round = 1, dealer = "p1"))
    assertTrue(session.isBiddingStateValidForCurrentRound())

    session.nextRound()
    session.updateBiddingState(initNormalRound(round = 1, dealer = "p1"))
    assertFalse(session.isBiddingStateValidForCurrentRound())
  }

  @Test
  fun biddingState_initializeRejectsAStateThatIsNotCurrent() {
    val session = newSession()
    session.nextRound()
    assertThrows(IllegalArgumentException::class.java) {
      session.initializeBiddingState(initNormalRound(round = 1, dealer = "p1"))
    }
  }

  @Test
  fun completeBidding_commitsTheRoundClosesTheAuctionAndStampsTheFirstTurn() {
    val session = newSession()
    session.initializeBiddingState(initNormalRound(round = 1, dealer = "p1"))

    session.completeBidding(testOutcome())

    val round = session.getRound()
    assertEquals(trump, round.trump)
    assertEquals(caller, round.callerId)
    assertEquals(listOf("p3"), round.withPlayers)
    assertEquals(seats.associateWith { 2 }, round.estimates)
    assertEquals(caller, session.getTurn())
    val bidding = session.getBiddingState()
    assertEquals(BiddingPhase.DONE, bidding?.subPhase)
    assertEquals(caller, bidding?.callerId)
    assertEquals(trump, bidding?.declaredTrump)
  }

  @Test
  fun completeRound_MarksDoneAndRollsTheMultiplierForward() {
    val session = newSession()
    session.initializePlayState(roundOneCfg(riggedHands()))

    session.completeRound(mapOf(caller to 13), nextMultiplier = 2)

    val table = session.getPlayState()
    assertEquals(TablePhase.DONE, table?.phase)
    assertEquals(mapOf(caller to 13), table?.tricksWon)
    assertNull(table?.turn)
    assertEquals(2, session.getRound().multiplier)
  }

  @Test
  fun restartPlayState_discardsPersistedStateBeforeReinitializing() {
    val session = newSession()
    session.initializePlayState(roundOneCfg(riggedHands()))
    replay(session, buildLog(session.getPlayState()!!.cfg.hands, tricks = 2))
    assertEquals(3, session.getPlayState()!!.trickNo)

    val fresh = session.restartPlayState(roundOneCfg(riggedHands()))

    assertEquals(1, fresh.trickNo)
    assertEquals(0, fresh.tricksWon.values.sum())
    assertEquals(TablePhase.PLAY, fresh.phase)
  }

  // ════════════════════════════════════════════════════════════════
  // Scores + completion (docs/specs/04-scoring.md)
  // ════════════════════════════════════════════════════════════════

  @Test
  fun scores_roundResultsAreHistoryOrdered() {
    val session = newSession()
    session.setMatchScores(accumulateMatchScores(session.getMatchScores(), mapOf("p1" to 10)))
    assertEquals(mapOf("p1" to 10, "p2" to 0, "p3" to 0, "p4" to 0), session.getMatchScores())

    val r1 = roundResult(1, mapOf("p1" to 10))
    val r2 = roundResult(2, mapOf("p2" to 8))
    session.recordRoundResult(r1)
    session.recordRoundResult(r2)

    assertEquals(r2, session.getLastRoundResult())
    assertEquals(listOf(r1, r2), session.getRoundHistory())
  }

  @Test
  fun winners_tiesAtTheMaxAreAllKings() {
    val session = newSession()
    // Two seats tied at the high score, one below: the house rule has no
    // tie-breaker — both tied seats win.
    session.setWinnerIds(computeWinner(mapOf("p1" to 50, "p2" to 50, "p3" to 30, "p4" to 50)))
    assertEquals(listOf("p1", "p2", "p4"), session.getWinnerIds())

    // A genuine single winner.
    session.setWinnerIds(computeWinner(mapOf("p1" to 60, "p2" to 50)))
    assertEquals(listOf("p1"), session.getWinnerIds())

    // Nothing scored yet → no winner, never a null scalar.
    session.setWinnerIds(computeWinner(emptyMap()))
    assertTrue(session.getWinnerIds().isEmpty())
  }

  @Test
  fun winners_storesADefensiveCopy() {
    val session = newSession()
    val ids = mutableListOf("p1", "p2")
    session.setWinnerIds(ids)
    ids.add("p3")
    assertEquals(listOf("p1", "p2"), session.getWinnerIds())
  }

  @Test
  fun matchComplete_atOrPastMaxRounds() {
    val session = newSession()
    assertFalse(session.isMatchComplete())

    session.setRound(session.getRound().copy(number = 17))
    assertFalse(session.isMatchComplete())

    session.setRound(session.getRound().copy(number = 18))
    assertTrue(session.isMatchComplete())

    // A Super Call / Sa'ayda extension past 18 is still complete.
    session.setRound(session.getRound().copy(number = 19))
    assertTrue(session.isMatchComplete())
  }

  // ════════════════════════════════════════════════════════════════
  // Remote mirror — delegates to RemoteMatchSync only
  // ════════════════════════════════════════════════════════════════

  private class FakeRemoteSync : RemoteMatchSync {
    data class Sub(val matchId: String, val onUpdate: (Any?, Throwable?) -> Unit)

    val subs: MutableList<Sub> = mutableListOf()
    val tornDown: MutableList<String> = mutableListOf()

    override fun subscribeToMatch(
      matchId: String,
      onUpdate: (data: Any?, error: Throwable?) -> Unit,
    ): () -> Unit {
      val sub = Sub(matchId, onUpdate)
      subs += sub
      val unsubscribe: () -> Unit = {
        subs -= sub
        tornDown += matchId
      }
      return unsubscribe
    }

    /** Test seam: deliver a live update to the subscription for [matchId]. */
    fun push(matchId: String, data: Any?, error: Throwable? = null) {
      subs.firstOrNull { it.matchId == matchId }?.onUpdate?.invoke(data, error)
    }
  }

  @Test
  fun remote_withoutASyncPortFailsOpen() {
    val session = GameSession()

    session.subscribeToRemoteMatch("m-1")

    assertFalse(session.isSubscribedToRemoteMatch())
    assertNull(session.getRemoteMatch())
    // A no-op, not a throw — an offline-only page survives.
    session.unsubscribeFromRemoteMatch()
  }

  @Test
  fun remote_subscribesAndReceivesUpdates() {
    val sync = FakeRemoteSync()
    val session = GameSession(remoteSync = sync)
    val received = mutableListOf<RemoteMatchUpdate>()
    session.onRemoteMatchUpdate { received += it }

    session.subscribeToRemoteMatch("m-1")

    assertTrue(session.isSubscribedToRemoteMatch())
    assertEquals(1, sync.subs.size)
    // The listener fires immediately with the pre-subscription state.
    assertEquals(1, received.size)
    assertNull(received[0].data)

    sync.push("m-1", mapOf("version" to 1))
    assertEquals(mapOf("version" to 1), session.getRemoteMatch())
    assertNull(session.getRemoteMatchError())
    assertEquals(2, received.size)
    assertEquals("m-1", received[1].matchId)
  }

  @Test
  fun remote_repeatedSubscribeToTheSameMatchIsIdempotent() {
    val sync = FakeRemoteSync()
    val session = GameSession(remoteSync = sync)

    session.subscribeToRemoteMatch("m-1")
    session.subscribeToRemoteMatch("m-1")

    assertEquals(1, sync.subs.size)
    assertTrue(sync.tornDown.isEmpty())
    // One handle held → one real teardown possible.
    session.unsubscribeFromRemoteMatch()
    assertEquals(listOf("m-1"), sync.tornDown)
    assertFalse(session.isSubscribedToRemoteMatch())
  }

  @Test
  fun remote_switchingMatchesTearsTheOldSubscriptionDownFirst() {
    val sync = FakeRemoteSync()
    val session = GameSession(remoteSync = sync)
    session.subscribeToRemoteMatch("m-1")

    session.subscribeToRemoteMatch("m-2")

    assertEquals(1, sync.subs.size)
    assertEquals("m-2", sync.subs.first().matchId)
    assertEquals(listOf("m-1"), sync.tornDown)
    // The stale match's deliveries no longer reach this session.
    sync.push("m-1", mapOf("version" to 9))
    assertNull(session.getRemoteMatch())
  }

  @Test
  fun remote_anErrorNeverClearsTheLastKnownGoodData() {
    val sync = FakeRemoteSync()
    val session = GameSession(remoteSync = sync)
    session.subscribeToRemoteMatch("m-1")
    sync.push("m-1", mapOf("version" to 3))

    sync.push("m-1", null, error = IllegalStateException("transient"))

    assertEquals(mapOf("version" to 3), session.getRemoteMatch())
    val error = session.getRemoteMatchError()
    assertTrue(error is IllegalStateException)
  }

  @Test
  fun remote_listenersUnsubscribeCleanly() {
    val sync = FakeRemoteSync()
    val session = GameSession(remoteSync = sync)
    val received = mutableListOf<RemoteMatchUpdate>()
    val stop = session.onRemoteMatchUpdate { received += it }
    session.subscribeToRemoteMatch("m-1")

    stop()
    sync.push("m-1", mapOf("version" to 1))

    // The immediate-fire delivery at subscribe time is the only one.
    assertEquals(1, received.size)
  }

  @Test
  fun remote_resetDoesNotKillAnActiveSubscription() {
    val sync = FakeRemoteSync()
    val session = GameSession(remoteSync = sync)
    session.subscribeToRemoteMatch("m-1")

    session.reset("ai")

    assertTrue(session.isSubscribedToRemoteMatch())
    assertTrue(sync.tornDown.isEmpty())
  }

  // ════════════════════════════════════════════════════════════════
  // P1-3 reload/resume replay (issue #16) — ported from
  // tests/reload-resume-replay.test.cjs to this layer
  // ════════════════════════════════════════════════════════════════

  /**
   * The caller holds ALL trumps and wins every trick it leads (and it
   * leads every trick, since it never loses one); everyone else holds
   * only non-trumps. 39 non-trump cards / 3 seats = exactly 13 each, so
   * the turn chain is deterministic by construction.
   */
  private fun riggedHands(): Map<String, List<Card>> {
    val callerCards = RANKS.map { Card(trump, it) }
    val rest = DECK_SUITS.filter { it != trump }.flatMap { s -> RANKS.map { Card(s, it) } }
    assertEquals("the rig deals exactly 39 non-trump cards", 39, rest.size)
    val hands = LinkedHashMap<String, List<Card>>()
    hands[caller] = callerCards
    seats.filter { it != caller }.forEachIndexed { i, seat ->
      hands[seat] = rest.subList(i * 13, i * 13 + 13)
    }
    return hands
  }

  private fun roundOneCfg(hands: Map<String, List<Card>>): RoundCfg = RoundCfg(
    round = 1,
    trump = trump,
    callerId = caller,
    withPlayers = emptyList(),
    estimates = seats.associateWith { 2 },
    dashCallers = emptyList(),
    leaderId = caller,
    riskId = null,
    hands = hands,
  )

  /** [tricks] of genuine plays in the deterministic turn chain: the
   *  leader plays its first trump, each other seat its first card. */
  private fun buildLog(hands: Map<String, List<Card>>, tricks: Int): List<PlayCard> {
    val remaining = hands.mapValues { it.value.toMutableList() }
    val log = ArrayList<PlayCard>(tricks * 4)
    repeat(tricks) {
      log.add(PlayCard(caller, remaining.getValue(caller).removeAt(0)))
      for (seat in seats) {
        if (seat == caller) continue
        // Trump is led and this seat holds none: any card is legal.
        log.add(PlayCard(seat, remaining.getValue(seat).removeAt(0)))
      }
    }
    return log
  }

  /**
   * The adapter's essential replay loop minus its dedup registries: emit
   * each logged card into the live state, resolving each completed trick
   * exactly once. The real adapter drives this through the same
   * getPlayState()/updatePlayState() seam.
   */
  private fun replay(session: GameSession, log: List<PlayCard>) {
    for (intent in log) {
      val state = session.getPlayState() ?: error("no play state to replay into")
      val applied = emitPlay(state, intent)
      val after = when (applied) {
        is PlayEmit.Applied ->
          if (applied.state.phase == TablePhase.RESOLVING) resolveTrick(applied.state)
          else applied.state
        is PlayEmit.Rejected -> error("replay rejected for ${intent.playerId}: ${applied.reason}")
      }
      session.updatePlayState(after)
    }
  }

  private fun tricksWonSum(state: TableState): Int = state.tricksWon.values.sum()

  @Test
  fun p13_hazard_replayingAFullLogOntoARestoredStateDoubleResolvesHistory() {
    val session = newSession()
    session.initializePlayState(roundOneCfg(riggedHands()))
    val log = buildLog(riggedHands(), tricks = 6)
    replay(session, log)

    val mid = session.getPlayState()!!
    assertEquals("pre-reload the engine is mid-round", 7, mid.trickNo)
    assertEquals("pre-reload 6 tricks are counted", 6, tricksWonSum(mid))

    // The OLD, unsafe order: restore the persisted mid-round state, reseed
    // the hands from the authoritative source, and replay the full log
    // from index 0 — without discarding the state first.
    session.updatePlayState(mid.copy(cfg = mid.cfg.copy(hands = riggedHands())))
    replay(session, log)

    val diverged = session.getPlayState()!!
    // HAZARD, byte-for-byte the P1-3 signature: history double-resolved —
    // 12 tricks counted on a 24-card log, the engine at trick 13. If this
    // ever stops diverging, the store gained cross-reload epoch
    // protection — update this test then, do not weaken it.
    assertEquals("HAZARD: trickNo ran past the log", 13, diverged.trickNo)
    assertEquals("HAZARD: history double-resolved", 12, tricksWonSum(diverged))
  }

  @Test
  fun p13_safePath_discardingStateBeforeReplayConvergesExactly() {
    val session = newSession()
    session.initializePlayState(roundOneCfg(riggedHands()))
    val log = buildLog(riggedHands(), tricks = 6)
    replay(session, log)
    assertEquals(7, session.getPlayState()!!.trickNo)

    // The reload path the store now makes the obvious one: discard the
    // persisted state, reseed the hands, reinitialize — all BEFORE the
    // log replays from index 0.
    session.clearPlayState()
    val fresh = session.initializePlayState(roundOneCfg(riggedHands()))
    assertEquals("post-clear restart is round-fresh", 1, fresh.trickNo)
    assertEquals("post-clear counts zero tricks", 0, tricksWonSum(fresh))
    assertEquals(TablePhase.PLAY, fresh.phase)

    replay(session, log)
    val converged = session.getPlayState()!!
    assertEquals("replay converges to trick 7", 7, converged.trickNo)
    assertEquals("replay counts exactly 6 tricks", 6, tricksWonSum(converged))
    assertEquals(TablePhase.PLAY, converged.phase)
    assertEquals(caller, converged.turn)

    // Genuine play continues cleanly past the replay.
    replay(session, buildLog(converged.cfg.hands, tricks = 1))
    val fin = session.getPlayState()!!
    assertEquals("play continues to trick 8", 8, fin.trickNo)
    assertEquals("play counts 7 tricks", 7, tricksWonSum(fin))
  }

  private fun roundResult(round: Int, deltas: Map<String, Int>): RoundResult = RoundResult(
    round = round,
    trump = trump,
    callerId = caller,
    tricksWon = mapOf(caller to 6),
    estimates = seats.associateWith { 2 },
    scoreDeltas = deltas,
    riskPlayerId = null,
    totalBids = 8,
    isOver = false,
    isSaayda = false,
    appliedMultiplier = 1,
    nextMultiplier = 1,
    extensionReason = null,
  )
}
