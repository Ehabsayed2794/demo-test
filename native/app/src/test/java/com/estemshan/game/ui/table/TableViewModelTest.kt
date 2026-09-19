package com.estemshan.game.ui.table

import com.estemshan.engine.Dealer
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableViewModelTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun cfg(hands: Map<String, List<com.estemshan.engine.Card>> = Dealer.dealHands(7)): RoundCfg =
    RoundCfg(
      round = 1,
      trump = Suit.SPADES,
      callerId = "p1",
      withPlayers = emptyList(),
      estimates = mapOf("p1" to 3, "p2" to 4, "p3" to 3, "p4" to 3),
      dashCallers = emptyList(),
      leaderId = "p1",
      riskId = "p4",
      hands = hands,
    )

  private fun TableViewModel.legalFor(seat: String) =
    state.value!!.cfg.hands.getValue(seat).let { hand ->
      val led = state.value!!.ledSuit
      if (led == null) hand[0] else hand.firstOrNull { it.suit == led } ?: hand[0]
    }

  @Test
  fun trickResolvesAndWinnerLeadsNext() {
    val vm = TableViewModel()
    vm.startRound(cfg(), seats)
    assertEquals("p1", vm.state.value?.turn)

    repeat(4) {
      val seat = vm.state.value?.turn ?: error("no turn")
      vm.play(seat, vm.legalFor(seat))
    }
    assertEquals(TablePhase.RESOLVING, vm.state.value?.phase)
    val winner = vm.state.value?.let { com.estemshan.engine.currentWinnerId(it) }
    vm.resolve()
    val s = vm.state.value!!
    assertEquals(TablePhase.PLAY, s.phase)
    assertEquals(2, s.trickNo)
    assertEquals(1, s.tricksWon.getValue(winner!!))
    assertEquals(winner, s.turn)
    assertNull(vm.rejection.value)
  }

  @Test
  fun illegalPlayRejectedWithReason() {
    val hands = mapOf(
      "p1" to listOf(com.estemshan.engine.Card(Suit.HEARTS, com.estemshan.engine.Rank(14, "A"))),
      "p2" to listOf(
        com.estemshan.engine.Card(Suit.HEARTS, com.estemshan.engine.Rank(13, "K")),
        com.estemshan.engine.Card(Suit.DIAMONDS, com.estemshan.engine.Rank(2, "2")),
      ),
      "p3" to listOf(com.estemshan.engine.Card(Suit.CLUBS, com.estemshan.engine.Rank(3, "3"))),
      "p4" to listOf(com.estemshan.engine.Card(Suit.CLUBS, com.estemshan.engine.Rank(4, "4"))),
    )
    val vm = TableViewModel()
    vm.startRound(cfg(hands), seats)
    vm.play("p1", hands.getValue("p1")[0])

    vm.play("p2", hands.getValue("p2")[1])
    assertEquals("Follow HEARTS", vm.rejection.value)
    assertEquals("p2", vm.state.value?.turn)

    assertTrue(!vm.legality("p2", hands.getValue("p2")[1]).legal)
    assertTrue(vm.legality("p2", hands.getValue("p2")[0]).legal)
    assertEquals("NOT_THIS_SEATS_TURN", vm.legality("p4", hands.getValue("p4")[0]).reason)
  }

  @Test
  fun fullRoundReachesDone() {
    val vm = TableViewModel()
    vm.startRound(cfg(), seats)
    var guard = 0
    while (vm.state.value?.phase != TablePhase.DONE && guard < 200) {
      guard++
      val s = vm.state.value!!
      if (s.phase == TablePhase.PLAY) {
        val seat = s.turn ?: error("no turn in PLAY")
        vm.play(seat, vm.legalFor(seat))
      } else {
        vm.resolve()
      }
    }
    val done = vm.state.value!!
    assertEquals(TablePhase.DONE, done.phase)
    assertEquals(13, done.tricksWon.values.sum())
    assertTrue(done.cfg.hands.values.all { it.isEmpty() })
  }

  @Test
  fun reseedMissingHand() {
    val full = Dealer.dealHands(11)
    val vm = TableViewModel()
    vm.startRound(cfg(full - "p2"), seats)
    assertTrue(vm.legality("p2", full.getValue("p2")[0]).legal == false)
    assertTrue(vm.reseedHand("p2", full.getValue("p2"), 1))
    assertEquals(13, vm.state.value?.cfg?.hands?.getValue("p2")?.size)
    assertTrue(!vm.reseedHand("p2", full.getValue("p2"), 1))
    assertTrue(!vm.reseedHand("p3", full.getValue("p3"), 2))
  }

  @Test
  fun preStartGuards() {
    val vm = TableViewModel()
    assertEquals("NOT_STARTED", vm.legality("p1", null).reason)
    vm.play("p1", com.estemshan.engine.Card(Suit.SPADES, com.estemshan.engine.Rank(14, "A")))
    assertEquals("Table has not started", vm.rejection.value)
    assertTrue(!vm.reseedHand("p1", emptyList(), 1))
  }
}
