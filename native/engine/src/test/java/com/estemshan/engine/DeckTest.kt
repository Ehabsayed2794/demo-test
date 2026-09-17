package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeckTest {

  @Test
  fun deckBuilds52UniqueCombos() {
    val cards = Deck.buildFullDeck()
    assertEquals(52, cards.size)
    assertEquals(52, cards.map { it.suit to it.rank.v }.toSet().size)
  }

  @Test
  fun deckCovers4SuitsTimes13RanksExactly() {
    val combos = Deck.buildFullDeck().map { it.suit to it.rank.v }.toSet()
    for (suit in DECK_SUITS) {
      for (rank in RANKS) {
        assertTrue("missing $suit ${rank.v}", combos.contains(suit to rank.v))
      }
    }
  }

  @Test
  fun shuffleIsDeterministicPerSeed() {
    val a = Deck(42).shuffle().snapshot().map { it.id }
    val b = Deck(42).shuffle().snapshot().map { it.id }
    assertEquals(a, b)
  }

  @Test
  fun shuffleChangesOrder() {
    val fresh = Deck.buildFullDeck().map { it.id }
    val shuffled = Deck(7).shuffle().snapshot().map { it.id }
    assertNotEquals(fresh, shuffled)
  }

  @Test
  fun drawEmptiesDeckThenThrows() {
    val deck = Deck(1)
    repeat(52) { deck.draw() }
    assertEquals(0, deck.remaining())
    try {
      deck.draw()
      fail("expected NoSuchElementException")
    } catch (e: NoSuchElementException) {
      // fail loud, never silent — matches JS convention
    }
  }

  @Test
  fun dealHandsGives4x13UniqueSortedOwned() {
    val hands = Dealer.dealHands(99)
    assertEquals(listOf("p1", "p2", "p3", "p4"), hands.keys.toList())
    val all = hands.values.flatten()
    assertEquals(52, all.size)
    assertEquals(52, all.map { it.suit to it.rank.v }.toSet().size)
    for ((seat, hand) in hands) {
      assertEquals(13, hand.size)
      assertTrue(hand.all { it.owner == seat })
      assertEquals(hand.sortedWith(::compareForSort), hand)
    }
  }

  @Test
  fun sansNeverDealt() {
    val all = Dealer.dealHands(5).values.flatten()
    assertTrue(all.none { it.suit == Suit.SANS })
  }
}
