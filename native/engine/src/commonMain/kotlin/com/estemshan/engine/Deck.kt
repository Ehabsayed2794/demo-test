package com.estemshan.engine

import kotlin.random.Random

/**
 * 52-card deck ported from design-ui/engine/deck.js + dealer.js.
 * Fisher–Yates shuffle with injectable RNG (deterministic tests pass a
 * seed). Fail loud on misuse, matching the JS convention.
 */
class Deck(seed: Long? = null) {

  private val rng: Random = if (seed == null) Random.Default else Random(seed)
  private var cards: MutableList<Card> = buildFullDeck()

  fun shuffle(): Deck {
    for (i in cards.size - 1 downTo 1) {
      val j = rng.nextInt(i + 1)
      val tmp = cards[i]
      cards[i] = cards[j]
      cards[j] = tmp
    }
    return this
  }

  fun draw(): Card {
    if (cards.isEmpty()) throw NoSuchElementException("Deck.draw(): no cards remaining.")
    return cards.removeAt(cards.size - 1)
  }

  fun remaining(): Int = cards.size

  /** Fresh 52 brand-new objects, unshuffled. Returns `this` for chaining. */
  fun reset(): Deck {
    cards = buildFullDeck()
    return this
  }

  fun snapshot(): List<Card> = cards.toList()

  companion object {
    fun buildFullDeck(): MutableList<Card> {
      val out = ArrayList<Card>(52)
      for (suit in DECK_SUITS) {
        for (rank in RANKS) {
          out.add(Card(suit, rank))
        }
      }
      return out
    }
  }
}

object Dealer {

  val SEATS: List<String> = listOf("p1", "p2", "p3", "p4")

  /**
   * Deals 4×13 unique cards, each hand pre-sorted for display.
   * Every card's owner matches its seat.
   */
  fun dealHands(seed: Long? = null): Map<String, List<Card>> {
    val deck = Deck(seed).shuffle()
    val hands = LinkedHashMap<String, MutableList<Card>>()
    for (seat in SEATS) hands[seat] = ArrayList(13)
    repeat(13) {
      for (seat in SEATS) {
        val c = deck.draw()
        hands.getValue(seat).add(c.copy(owner = seat))
      }
    }
    return hands.mapValues { (_, hand) -> hand.sortedWith(::compareForSort) }
  }
}
