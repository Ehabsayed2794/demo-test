package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.RANKS
import com.estemshan.engine.Suit

/**
 * Parses a stored hand card — `{suit: "SPADES", rank: {v: 14}}` — into the
 * engine's [Card]. Shared by the runtime adapters (the Firebase Admin SDK
 * and the Durable Object adapter): both read the same stored shape, and a
 * drift between two copies would decide tricks on different data.
 *
 * Returns null on anything unrecognisable — hands fail closed, never guess.
 */
fun parseStoredCard(fields: Any?): Card? {
  val map = fields as? Map<*, *> ?: return null
  val suit = (map["suit"] as? String)?.let { runCatching { Suit.valueOf(it) }.getOrNull() }
    ?: return null
  val rankMap = map["rank"] as? Map<*, *> ?: return null
  val v = (rankMap["v"] as? Number)?.toInt() ?: return null
  val rank = RANKS.firstOrNull { it.v == v } ?: return null
  return Card(suit, rank)
}
