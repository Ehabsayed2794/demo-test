package com.estemshan.stage0

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode

/**
 * E9 stage 4 — Design A's row count, measured on the same driver as B.
 *
 * The plan's A is "checkpoint + action log": B's per-Trick checkpoint and
 * per-action auction, PLUS one durable row per card played, so a crash can
 * replay the in-progress trick instead of losing it. The evidence the owner
 * asked for is not a replay proof — it is the ROW COUNT, measured per unit the
 * way B's was. So this probe drives the exact live path
 * ([DurableMatch.nextStep]/[submit]) and records what A would have written:
 *
 *  - every card play is one `C` row, including the three cards of a trick that
 *    B does not persist;
 *  - B's own units are counted where they still occur — an auction action (B)
 *    and a completed Trick (T).
 *
 * [units] is the stream in true order, so the workerd leg can insert it one
 * unit per event and confirm the per-unit cost with the store's own cursor.
 * The engine is not modified: a card that produces no B record still moves the
 * match, exactly as it does in production.
 */
data class CardLogUnit(val kind: String, val record: String)

data class CardLogCounts(
  val seed: Int,
  val gameType: GameType,
  val scoringMode: ScoringMode,
  val bids: Int,
  val tricks: Int,
  val cards: Int,
  val units: List<CardLogUnit>,
) {
  /** A as the plan writes it: B's checkpoints AND the card-level log. */
  val checkpointPlusCards: Int get() = bids + tricks + cards

  /** A without the redundant Trick row — the E5 per-action axis. */
  val cardsOnly: Int get() = bids + cards
}

/**
 * Drive one match and return the units A would persist. The match is checked
 * to complete, and the bid/Trick records are the live path's own strings, so a
 * caller can assert that A's non-card rows equal B's log byte for byte.
 */
fun cardLogCounts(seed: Int, gameType: GameType, scoringMode: ScoringMode): CardLogCounts {
  val m = DurableMatch.start(seed, gameType, scoringMode)
  val units = ArrayList<CardLogUnit>()
  var bids = 0
  var tricks = 0
  var cards = 0
  var cardInTrick = 0
  var lastTrick = -1

  while (true) {
    val step = m.nextStep()
    if (step is E9Step.Done) break
    if (step is E9Step.CardPlay) {
      val table = m.currentTable() ?: error("a card step with no table")
      if (table.trickNo != lastTrick) {
        lastTrick = table.trickNo
        cardInTrick = 0
      }
      cardInTrick++
      cards++
      units += CardLogUnit(
        kind = "CARDS",
        record = "C;${units.size + 1};${table.cfg.round};${table.trickNo};$cardInTrick;" +
          "${step.seat};${step.card.suit.name};${step.card.rank.v}",
      )
    }
    when (val r = m.submit(step)) {
      is E9Submit.Applied -> {
        val record = r.record ?: continue
        if (record.startsWith("B;")) bids++
        if (record.startsWith("T;")) tricks++
        units += CardLogUnit(kind = if (record.startsWith("B;")) "BID" else "TRICK", record = record)
      }
      else -> error("live path refused a legal step: $r")
    }
  }
  check(m.isComplete()) { "match did not complete" }
  return CardLogCounts(seed, gameType, scoringMode, bids, tricks, cards, units)
}

/** The unit stream as the newline-joined text the workerd leg inserts. */
fun renderCardLog(units: List<CardLogUnit>): String = units.joinToString("\n") { it.record }
