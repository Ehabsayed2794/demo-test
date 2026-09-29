package com.estemshan.game.ui.onlinematch

import com.estemshan.game.ui.onlinematch.OpponentAwayTracker.Presence
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.CardLogEntry
import com.estemshan.services.model.GameState
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.StoredCard
import com.estemshan.services.model.StoredRank
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S19 — the staleness derivation, pinned directly against the pure tracker
 * with a controllable clock. No coroutines, no engines, no Firestore: the
 * whole indicator is document progress + time, so both are the entire world.
 *
 * The three properties the view model's wiring test takes as read:
 *  1. a turn that goes stale flips the indicator — with no document arriving
 *     in between, which is the situation the indicator exists for;
 *  2. progress — a card, a bid, the turn passing — clears it and restarts the
 *     clock from THAT progress, so a fresh play never inherits the prior
 *     wait's age;
 *  3. our own move never shows it, and resets the baseline for the next one.
 *
 * What is NOT claimed anywhere here (or anywhere else): presence. The frozen
 * rules make it unreadable, so this class observes the match document only.
 */
class OpponentAwayTrackerTest {

  /** The whole clock; every test advances it by hand. */
  private var now = 0L
  private val tracker = OpponentAwayTracker(thresholdMillis = THRESHOLD, clock = { now })

  private fun doc(
    turn: String? = "p2",
    round: Int = 1,
    cards: Int = 0,
    bids: Int = 0,
    cardPhase: String? = MatchDoc.CARD_PHASE_PLAY,
    version: Int = 1,
  ): MatchDoc = MatchDoc(
    roomId = "room-1",
    players = listOf("uid-a", "uid-b"),
    status = MatchDoc.STATUS_STARTING,
    currentRound = round,
    maxRounds = 18,
    extendedRounds = emptyList(),
    dealer = "uid-a",
    turn = turn,
    seats = linkedMapOf("p1" to "uid-a", "p2" to "uid-b"),
    version = version,
    biddingOpen = false,
    bids = emptyMap(),
    lastBidSeat = null,
    cardLog = List(cards) { CardLogEntry("p2", aCard, round) },
    lastCardSeat = null,
    cardPhase = cardPhase,
    biddingLog = List(bids) { BiddingLogEntry("p2", BiddingLogEntry.ACTION_AUCTION_BID, round = round) },
    gameState = GameState(initialized = true, dealtRound = round),
  )

  private val aCard = StoredCard("HEARTS", StoredRank(2, "2"))

  /**
   * An opponent's turn, observed at t=0, is active; the clock then moves
   * alone — no document arrives — and the indicator flips exactly at the
   * threshold, not before it.
   */
  @Test
  fun staleOpponentTurn_flipsTheIndicator() {
    assertEquals(Presence.OpponentActive, tracker.onDocument(doc(), actingSeat = "p2"))
    assertEquals("armed for the full threshold", THRESHOLD, tracker.millisUntilAway())

    now = THRESHOLD - 1
    assertEquals("a hair under the threshold is still active",
      Presence.OpponentActive, tracker.reevaluate(),
    )

    now = THRESHOLD
    assertEquals("past the threshold with no document in between, appears away",
      Presence.AppearsAway, tracker.reevaluate(),
    )
    assertEquals("a re-delivery of the same stale document holds the flip",
      Presence.AppearsAway, tracker.onDocument(doc(), actingSeat = "p2"),
    )
  }

  /**
   * The alarm path: [OpponentAwayTracker.reevaluate] derives from the clock
   * alone, which is what the pending alarm calls when no snapshot arrives.
   */
  @Test
  fun reevaluate_readsTheClockWithoutADocument() {
    tracker.onDocument(doc(), actingSeat = "p2")
    now = THRESHOLD + 5_000
    assertEquals(Presence.AppearsAway, tracker.reevaluate())
    assertEquals("nothing is pending once it has flipped", 0L, tracker.millisUntilAway())
  }

  /**
   * Progress clears the indicator and — the half that matters — restarts the
   * clock from the progress, so the same elapsed time afterward does NOT flip
   * it again. A card, a bid and a passed turn are each enough.
   */
  @Test
  fun freshActivity_clearsTheIndicator_andRestartsTheClock() {
    tracker.onDocument(doc(), actingSeat = "p2")
    now = THRESHOLD
    assertEquals(Presence.AppearsAway, tracker.reevaluate())

    // A card lands on the same turn: progress.
    now = THRESHOLD
    assertEquals(Presence.OpponentActive, tracker.onDocument(doc(cards = 1), actingSeat = "p2"))
    assertEquals("the baseline moved with the card", THRESHOLD, tracker.millisUntilAway())

    // The same age that flipped it before now does not.
    now = THRESHOLD * 2 - 1
    assertEquals(Presence.OpponentActive, tracker.reevaluate())

    // The turn passes to another opponent: progress, and again a fresh clock.
    now = THRESHOLD * 2
    assertEquals(Presence.OpponentActive, tracker.onDocument(doc(turn = "p1"), actingSeat = "p1"))
    assertEquals(THRESHOLD, tracker.millisUntilAway())
  }

  /** A bid during the auction is progress too, not just a played card. */
  @Test
  fun freshBidding_isProgress() {
    tracker.onDocument(doc(bids = 1, cardPhase = null), actingSeat = "p3")
    now = THRESHOLD
    assertEquals(Presence.AppearsAway, tracker.reevaluate())
    now = THRESHOLD
    assertEquals(Presence.OpponentActive, tracker.onDocument(doc(bids = 2, cardPhase = null), actingSeat = "p3"))
  }

  /**
   * A version bump with no content change is NOT play — it is a re-delivery
   * or a rejected write — so it must not reset the staleness clock. Counting
   * it would let noise quietly clear the hint.
   */
  @Test
  fun contentFreeVersionBump_isNotProgress() {
    tracker.onDocument(doc(version = 5), actingSeat = "p2")
    now = THRESHOLD
    assertEquals("the bumped-but-unchanged document is still stale",
      Presence.AppearsAway, tracker.onDocument(doc(version = 6), actingSeat = "p2"),
    )
  }

  /**
   * Our own move (or nobody waiting) never shows the hint, and clears the
   * baseline so the next opponent's wait starts at zero rather than at the
   * previous opponent's age.
   */
  @Test
  fun ourOwnMove_neverShowsAndResetsTheBaseline() {
    tracker.onDocument(doc(), actingSeat = "p2")
    now = THRESHOLD - 1

    // It becomes our move.
    assertEquals(Presence.WaitingOnUs, tracker.onDocument(doc(turn = "p1"), actingSeat = null))
    assertEquals("nothing is armed while we hold the turn", 0L, tracker.millisUntilAway())

    // The turn passes back to an opponent: the fresh wait is young, not the
    // old one's age plus this.
    now = THRESHOLD * 2
    assertEquals(Presence.OpponentActive, tracker.onDocument(doc(turn = "p2"), actingSeat = "p2"))
    assertEquals(THRESHOLD, tracker.millisUntilAway())
  }

  /** No baseline at all — [reset], or a document never observed with a wait. */
  @Test
  fun withNothingWaitedOn_reevaluateIsWaitingOnUs() {
    assertEquals(Presence.WaitingOnUs, tracker.reevaluate())
    assertEquals(0L, tracker.millisUntilAway())

    tracker.onDocument(doc(), actingSeat = "p2")
    now = THRESHOLD
    assertEquals(Presence.AppearsAway, tracker.reevaluate())

    tracker.reset()
    assertEquals("reset drops the baseline", Presence.WaitingOnUs, tracker.reevaluate())
    assertEquals(0L, tracker.millisUntilAway())
  }

  private companion object {
    private const val THRESHOLD = 1_000L
  }
}
