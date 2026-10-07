package com.estemshan.engine

/**
 * Card model ported from design-ui/engine/cards.js.
 * Suit hierarchy (strongest → weakest): Sans(5) > Spades(4) > Hearts(3) >
 * Diamonds(2) > Clubs(1). Ranks weakest → strongest: 2..14 (J=11, Q=12,
 * K=13, A=14). NOTE: the JS SUITS/RANKS tables were triplicated across
 * modules — this single definition replaces all three (debt R10).
 */
enum class Suit(val strength: Int, val symbol: String) {
  SANS(5, "—"),
  SPADES(4, "♠"),
  HEARTS(3, "♥"),
  DIAMONDS(2, "♦"),
  CLUBS(1, "♣"),
}

data class Rank(val v: Int, val s: String)

val RANKS: List<Rank> = listOf(
  Rank(2, "2"), Rank(3, "3"), Rank(4, "4"), Rank(5, "5"), Rank(6, "6"),
  Rank(7, "7"), Rank(8, "8"), Rank(9, "9"), Rank(10, "10"),
  Rank(11, "J"), Rank(12, "Q"), Rank(13, "K"), Rank(14, "A"),
)

/** Suits that exist in a real deck (Sans is trump-only, never dealt). */
val DECK_SUITS: List<Suit> = listOf(Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)

data class Card(
  val suit: Suit,
  val rank: Rank,
  val owner: String? = null,
  val played: Boolean = false,
) {
  /** Unique within one deck (52 combos); display form matches JS. */
  val id: String get() = "${suit.name}-${rank.v}"
  val displayName: String get() = "${rank.s} ${suit.symbol}"
  val value: Int get() = rank.v
}

/** Standard display sort: strongest suit first, then highest rank. */
fun compareForSort(a: Card, b: Card): Int =
  (b.suit.strength - a.suit.strength).takeIf { it != 0 } ?: (b.rank.v - a.rank.v)
