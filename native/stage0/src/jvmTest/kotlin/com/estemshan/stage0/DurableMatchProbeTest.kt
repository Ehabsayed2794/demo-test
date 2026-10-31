package com.estemshan.stage0

import com.estemshan.engine.Card
import com.estemshan.engine.GameType
import com.estemshan.engine.Play
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.TablePhase
import com.estemshan.engine.legalCards
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E9, stage 1 — the durable match on the JVM, no store and no runtime.
 *
 * What this file has to prove before any Worker exists:
 *
 *  - the durable path is the SAME match `playMatch` already pins (so the
 *    record-producing driver did not fork the game);
 *  - a restart fed the stored rows alone lands on byte-identical state at
 *    every prefix — including mid-round, mid-auction, and on the last trick;
 *  - a unit retried after "record written, ack lost" (boundary 2) is
 *    recognised from the head row and writes nothing;
 *  - a unit retried after "crash before the record" (boundary 1) is applied
 *    and produces the same record the killed process would have written;
 *  - an interrupted Trick leaves no row and re-drives, and a different
 *    client choice thereafter is carried by the log, not re-derived.
 *
 * Every assertion here reads state that the durable path produced — the
 * record list or `stateDocument()` — never a value returned by an in-flight
 * call that might not have been committed.
 */
class DurableMatchProbeTest {

  private val outDir = java.io.File("build/stage0-e9").apply { mkdirs() }

  private val combos = listOf(
    GameType.FULL to ScoringMode.NORMAL,
    GameType.FULL to ScoringMode.CLASSIC,
    GameType.MINI to ScoringMode.NORMAL,
    GameType.MINI to ScoringMode.CLASSIC,
  )

  private val seeds = listOf(42, 7, 1)

  /** A full match driven through the durable driver, with the artefacts every
   *  later test compares against. */
  private class Driven(
    val match: DurableMatch,
    val records: List<String>,
    /** The step that produced each record, in order. */
    val producers: List<E9Step>,
    /** `docs[k]` is the state after exactly k records; `docs[0]` is the start. */
    val docs: List<String>,
    /** `digests[k]` is the digest of `docs[k]` — the per-prefix baseline the
     *  kill matrix compares a restarted process against. */
    val digests: List<String>,
  )

  private fun drive(
    seed: Int,
    gameType: GameType = GameType.FULL,
    scoringMode: ScoringMode = ScoringMode.NORMAL,
  ): Driven {
    val m = DurableMatch.start(seed, gameType, scoringMode)
    val records = ArrayList<String>()
    val producers = ArrayList<E9Step>()
    val docs = ArrayList<String>()
    val digests = ArrayList<String>()
    docs += m.stateDocument()
    digests += m.stateDigest()
    while (true) {
      val step = m.nextStep()
      if (step is E9Step.Done) break
      when (val r = m.submit(step)) {
        is E9Submit.Applied -> {
          val record = r.record
          if (record != null) {
            records += record
            producers += step
            docs += m.stateDocument()
            digests += m.stateDigest()
          }
        }
        else -> error("live path refused a legal step: $r")
      }
    }
    check(m.isComplete()) { "match did not complete" }
    check(docs.size == records.size + 1) { "a page of state went missing" }
    check(digests.size == docs.size) { "a digest went missing" }
    return Driven(m, records, producers, docs, digests)
  }

  // ── 1. the durable path is the same match ─────────────────────────

  @Test
  fun durablePathMatchesPlayMatch() {
    for ((gameType, scoringMode) in combos) {
      for (seed in seeds) {
        val driven = drive(seed, gameType, scoringMode)
        assertEquals(
          playMatch(seed, gameType, scoringMode),
          driven.match.completedRun(),
          "durable run diverged from playMatch at seed $seed ($gameType/$scoringMode)",
        )
      }
    }
  }

  // ── 2. restart at every prefix, from the rows alone ───────────────

  @Test
  fun everyPrefixReplaysToByteIdenticalState() {
    val driven = drive(42)
    assertTrue(driven.records.size > 400, "expected a full NFL match's record count, got ${driven.records.size}")

    for (k in driven.records.indices) {
      val replayed = DurableMatch.replay(
        driven.records.subList(0, k + 1).joinToString("\n"),
        seed = 42,
      )
      assertEquals(k + 1, replayed.recordsApplied, "restart at prefix ${k + 1} lost a unit")
      assertEquals(
        driven.docs[k + 1],
        replayed.stateDocument(),
        "restart at prefix ${k + 1} (${driven.records[k]}) diverged",
      )
    }
  }

  // ── 3. the record shape the capacity model needs ──────────────────

  @Test
  fun oneRowPerAuctionActionAndOnePerTrick() {
    val driven = drive(42)
    val parsed = driven.records.map(::parseRecord)
    val rounds = driven.match.completedRun().rounds.size
    val tricks = parsed.filterIsInstance<E9Record.TrickUnit>()
    val bids = parsed.filterIsInstance<E9Record.BidUnit>()

    assertEquals(rounds * 13, tricks.size, "a Trick must own exactly one row")
    assertEquals(driven.records.size, tricks.size + bids.size)

    // seq is dense, equals the row id, and the natural position is inside
    // the same row: there is no second key write to lose.
    parsed.forEachIndexed { i, r ->
      assertEquals(i + 1, r.seq, "seq must be the row id")
      assertEquals(r.seq, parseRecord(driven.records[i]).seq)
    }

    // Per round: bids come first (1..n), then tricks (1..13), both dense.
    val perRound = parsed.groupBy { it.key.round }
    for ((round, units) in perRound) {
      val rBids = units.filterIsInstance<E9Record.BidUnit>().map { it.key.index }
      val rTricks = units.filterIsInstance<E9Record.TrickUnit>().map { it.key.index }
      assertEquals(13, rTricks.size, "round $round has ${rTricks.size} trick rows")
      assertEquals((1..13).toList(), rTricks, "round $round trick indexes are not dense")
      assertEquals((1..rBids.size).toList(), rBids, "round $round bid indexes are not dense")
    }

    // The report quotes this number; it is the measured half of 428/match.
    println("E9 record shape seed 42: rows=${driven.records.size} tricks=${tricks.size} bids=${bids.size} rounds=$rounds")

    // The workerd leg's reference: same seed, same record text, same digest.
    val out = File("build/stage0-parity").apply { mkdirs() }
    File(out, "e9-seed42.log").writeText(driven.records.joinToString("\n"))
    File(out, "e9-seed42.digest").writeText(driven.match.stateDigest())
    // Every prefix, so a process killed after k rows can be compared with the
    // no-kill run WITHOUT replaying the JVM side first.
    File(out, "e9-seed42.prefixes").writeText(
      driven.digests.mapIndexed { k, d -> "$k\t$d" }.joinToString("\n"),
    )
  }

  /** The store's order is write-then-apply, so the record must be producible
   *  without moving the engine. This pins the primitive the Worker calls: at
   *  every unit the produced row is the row the live path produces, and
   *  applying it lands exactly where the live path lands. */
  @Test
  fun theNextUnitCanBeProducedBeforeItIsApplied() {
    val driven = drive(42)
    val m = DurableMatch.start(42)
    for (k in driven.records.indices) {
      val before = m.stateDocument()
      val next = assertNotNull(m.nextUnitRecord(), "no unit $k")
      assertEquals(driven.records[k], next, "unit $k was produced differently")
      assertEquals(before, m.stateDocument(), "producing unit $k applied it")
      assertEquals(k, m.recordsApplied, "producing unit $k wrote the row")
      m.applyRecord(next)
      assertEquals(k + 1, m.recordsApplied)
      assertEquals(driven.docs[k + 1], m.stateDocument(), "applying unit $k diverged")
    }
    assertNull(m.nextUnitRecord(), "a complete match still produced a unit")
    assertEquals(driven.docs.last(), m.stateDocument())
  }

  // ── 10. Design A's row count (stage 4) ───────────────────────────

  /** A = B's checkpoints + one row per card. The bid and Trick rows A carries
   *  are B's own strings, so the only new cost is the card rows — and the
   *  count is measured per unit, the same way B's was. */
  @Test
  fun theCardLevelLogCountsOneRowPerCardOnTopOfTheRuledShape() {
    val driven = drive(42)
    val a = cardLogCounts(42, GameType.FULL, ScoringMode.NORMAL)

    assertEquals(driven.records.size, a.bids + a.tricks, "A's non-card units are not B's units")
    val bUnits = a.units.filter { it.kind != "CARDS" }.map { it.record }
    assertEquals(driven.records, bUnits, "A's bid/Trick rows differ from B's log")
    assertEquals(936, a.cards, "a FULL match is 18 rounds x 13 tricks x 4 cards")
    assertEquals(a.tricks * 4, a.cards, "cards must be four per Trick")

    println(
      "E9 design A seed 42: bids=${a.bids} tricks=${a.tricks} cards=${a.cards} " +
        "checkpoint+cards=${a.checkpointPlusCards} cards-only=${a.cardsOnly}",
    )

    val out = java.io.File("build/stage0-e9").apply { mkdirs() }
    out.resolve("e9-cardlog-42.json").writeText(
      """{"seed":42,"bids":${a.bids},"tricks":${a.tricks},"cards":${a.cards},""" +
        """"checkpointPlusCards":${a.checkpointPlusCards},"cardsOnly":${a.cardsOnly},""" +
        """"units":[${a.units.joinToString(",") { "\"${it.record}\"" }}]}""",
    )
  }

  // ── 11. boundary 7's decision is the engine's own ─────────────────

  /** The decision a Durable Object reaches on an alarm is the engine's
   *  [InactivityClock] arithmetic, not a second copy of it: 50 s accumulates
   *  into a takeover, 49,999 ms does not, a borrowed seat is idempotent, and
   *  an untrustworthy stored clock never takes a seat over. */
  @Test
  fun theTakeoverDecisionIsTheEnginesAndIsIdempotent() {
    // Below the threshold: wait — an early alarm does nothing.
    val early = takeoverDecisionJson(0L, 1_000L, 50_999L, borrowed = false)
    assertTrue(early.contains("\"status\":\"wait\""), early)
    assertTrue(early.contains("\"takeover\":false"), early)

    // Exactly 50 s: takeover.
    val at = takeoverDecisionJson(0L, 1_000L, 51_000L, borrowed = false)
    assertTrue(at.contains("\"status\":\"takeover\""), at)

    // A late delivery decides on the clock, not on the timer: still one takeover.
    val late = takeoverDecisionJson(0L, 1_000L, 120_000L, borrowed = false)
    assertTrue(late.contains("\"status\":\"takeover\""), late)
    // A window that never opened and no accumulation: nothing to take.
    val idle = takeoverDecisionJson(0L, null, 200_000L, borrowed = false)
    assertTrue(idle.contains("\"status\":\"wait\""), idle)

    // A borrowed seat is idempotent: a re-delivered alarm cannot re-take it.
    val dup = takeoverDecisionJson(0L, 1_000L, 51_000L, borrowed = true)
    assertTrue(dup.contains("\"status\":\"duplicate\""), dup)
    assertTrue(dup.contains("\"takeover\":false"), dup)

    // Fail-closed: a stored accumulation at or above the threshold is not state
    // the clock produced, so no takeover can fire from it.
    assertEquals(
      "refused",
      takeoverDecisionJson(50_000L, null, 200_000L, borrowed = false).substringAfter("\"status\":\"").substringBefore("\""),
    )

    val marker = takeoverMarkerJson(51_000L, 3, 7)
    assertTrue(marker.contains("\"takenOverAtMillis\":51000"), marker)
    assertTrue(marker.contains("\"roundAtTakeover\":3"), marker)
    assertTrue(marker.contains("\"trickAtTakeover\":7"), marker)
  }

  // ── 12. the stage-4 matrix's JVM anchor ───────────────────────────

  /** Boundaries 1–8 each run a different seed; these are the uninterrupted
   *  JVM logs the workerd matrix compares a killed-and-recovered match with.
   *  Seeds 58–59 are boundary 7's other two kill points (after the takeover row,
   *  and while the replacement clock is re-armed). */
  @Test
  fun theMatrixSeedsWriteTheirJvmLogs() {
    val out = java.io.File("build/stage0-parity").apply { mkdirs() }
    for (seed in 50..59) {
      val driven = drive(seed)
      java.io.File(out, "e9-matrix-$seed.log").writeText(driven.records.joinToString("\n"))
      java.io.File(out, "e9-matrix-$seed.digest").writeText(driven.match.stateDigest())
      // Every prefix, so a match killed after k rows is compared with the
      // UNINTERRUPTED run at k — not merely with its own final digest.
      java.io.File(out, "e9-matrix-$seed.prefixes").writeText(
        driven.digests.mapIndexed { k, d -> "$k\t$d" }.joinToString("\n"),
      )
      println("E9 matrix seed $seed: rows=${driven.records.size} digest=${driven.match.stateDigest()}")
    }
  }

  // ── 9. the settlement a finished match owes ───────────────────────

  /** The durable match settles through the SAME arithmetic as E7's stream —
   *  same seed, same starting RP, identical rows. One settlement path, not a
   *  second derivation of the production formulas. */
  @Test
  fun theCompletedMatchSettlesExactlyAsTheE7StreamDoes() {
    val driven = drive(42)
    val fromDurable = driven.match.settlementRows("m-FULL-NORMAL-42", E7_STARTING_RP)
    val fromStream = buildSettlementStream(42, 1).matches.single()

    assertEquals(fromStream.matchId, fromDurable.matchId)
    assertEquals(fromStream.rows, fromDurable.rows, "the durable match settled differently from the E7 stream")
    assertEquals(4, fromDurable.rows.size, "one ledger row per seat")
    assertEquals(4, fromDurable.rows.map { it.seat }.toSet().size)
    // The document the workerd leg must reproduce byte for byte: the DO derives
    // its intent from the same function, so a different byte is a real fork.
    outDir.resolve("e9-settle-42-full-normal.json").writeText(renderMatchSettlement(fromDurable))
    println(
      "E9 settlement seed 42: " +
        fromDurable.rows.joinToString { "${it.seat}/${it.uid} ${it.outcome} ${it.delta} -> ${it.rp}" } +
        " total=${fromDurable.rows.sumOf { it.delta }}",
    )
  }

  /** An intent may only be derived from a match the log says is over. */
  @Test
  fun anUnfinishedMatchCannotBeSettled() {
    val m = DurableMatch.start(42)
    repeat(20) {
      val step = m.nextStep()
      if (step !is E9Step.Done) m.submit(step)
    }
    assertTrue(!m.isComplete())
    val before = m.recordsApplied
    val boom = runCatching { m.settlementRows("m-unfinished", E7_STARTING_RP) }
    assertTrue(boom.isFailure, "an unfinished match produced a settlement")
    assertTrue(
      boom.exceptionOrNull()?.message?.contains("unfinished match") == true,
      "wrong refusal: ${boom.exceptionOrNull()?.message}",
    )
    assertEquals(before, m.recordsApplied, "the refusal moved the log")
  }

  /** The number the capacity model quotes. E7's model is 428/match
   *  (234 trick checkpoints + 193.9 auction actions); this measures the same
   *  two quantities on the E9 record shape, per seed, and never adjusts them. */
  @Test
  fun rowCountsAcrossSeeds() {
    val totals = ArrayList<Int>()
    for (seed in listOf(1, 7, 42, 99, 123, 777, 1001, 1994, 5000, 12345)) {
      val d = drive(seed)
      val tricks = d.records.count { it.startsWith("T;") }
      val bids = d.records.count { it.startsWith("B;") }
      assertEquals(234, tricks, "a FULL match is 18 rounds x 13 tricks")
      assertEquals(d.records.size, tricks + bids)
      totals += d.records.size
      println("E9 rows seed $seed: total=${d.records.size} tricks=$tricks bids=$bids")
    }
    println(
      "E9 FULL/NORMAL rows/match over ${totals.size} seeds: " +
        "min=${totals.min()} max=${totals.max()} avg=${(totals.sum().toDouble() / totals.size)}",
    )

    val mini = drive(42, GameType.MINI, ScoringMode.NORMAL)
    val miniTricks = mini.records.count { it.startsWith("T;") }
    val miniBids = mini.records.count { it.startsWith("B;") }
    assertEquals(130, miniTricks, "a MINI match is 10 rounds x 13 tricks")
    println("E9 rows MINI seed 42: total=${mini.records.size} tricks=$miniTricks bids=$miniBids")
  }

  // ── 4. boundaries 1 and 2 at every unit boundary ──────────────────

  @Test
  fun everyUnitBoundaryIsIdempotent() {
    val driven = drive(42)

    // The restart path: a fresh engine fed the rows one at a time.
    val restart = DurableMatch.start(42)
    for (k in driven.records.indices) {
      val step = driven.producers[k]
      val unit = parseRecord(driven.records[k])

      // Boundary 1 — the record was never written (crash before the write).
      // An auction action is one unit, so the client's single retry must
      // produce EXACTLY the row the killed process wrote. A Trick is not one
      // card: a restart lands at the Trick's start, so the four cards are
      // re-driven — the first three from the stored row, the last from the
      // client — and the completed row must be identical.
      when (unit) {
        is E9Record.BidUnit -> {
          val first = restart.submit(step)
          check(first is E9Submit.Applied) { "unit $k was refused after restart: $first" }
          assertEquals(driven.records[k], first.record, "unit $k did not reproduce its row")
        }
        is E9Record.TrickUnit -> {
          for ((i, play) in unit.plays.withIndex()) {
            val r = restart.submitCard(play.playerId, play.card)
            check(r is E9Submit.Applied) { "unit $k card $i was refused after restart: $r" }
            if (i < unit.plays.size - 1) {
              assertTrue(r.record == null, "unit $k wrote a row before the fourth card")
            } else {
              assertEquals(driven.records[k], r.record, "unit $k did not reproduce its row")
            }
          }
        }
      }
      assertEquals(k + 1, restart.recordsApplied, "unit $k did not write")
      assertEquals(driven.docs[k + 1], restart.stateDocument(), "unit $k landed on a different state")

      // Boundary 2 — the record IS written but the ack was lost. The retry
      // must be recognised from the head row: no second write, no state move.
      val retry = restart.retryStored(driven.records[k])
      assertTrue(retry is E9Submit.AlreadyApplied, "unit $k was not recognised as applied: $retry")
      assertEquals(unit.key, (retry as E9Submit.AlreadyApplied).key)
      assertEquals(k + 1, restart.recordsApplied, "unit $k was written twice")
      assertEquals(driven.docs[k + 1], restart.stateDocument(), "the retry moved the state")
    }
    assertEquals(driven.docs.last(), restart.stateDocument())
  }

  /** The same check through the store-row path the Worker uses: hand the
   *  driver the head row it just stored, exactly as a client's retry would. */
  @Test
  fun retryFromTheStoredRowWritesNothing() {
    val driven = drive(42)
    for (k in listOf(0, 17, 100, 240, driven.records.size - 1)) {
      val prefix = driven.records.subList(0, k + 1).joinToString("\n")
      val restarted = DurableMatch.replay(prefix, seed = 42)
      val before = restarted.stateDocument()
      val result = restarted.retryStored(driven.records[k])
      assertTrue(result is E9Submit.AlreadyApplied, "row $k was not recognised: $result")
      assertEquals(k + 1, restarted.recordsApplied)
      assertEquals(before, restarted.stateDocument())
    }
  }

  // ── 5. boundary 3/4 shape: mid-trick is lost, then re-driven ──────

  @Test
  fun anInterruptedTrickLeavesNoRowAndRedrivesWithTheClientsOwnCard() {
    val seed = 42
    val live = DurableMatch.start(seed)
    val store = ArrayList<String>()

    fun drain() {
      while (true) {
        val step = live.nextStep()
        if (step is E9Step.Done) return
        when (val r = live.submit(step)) {
          is E9Submit.Applied -> r.record?.let { store += it }
          else -> error("refused: $r")
        }
        // stop as soon as the fourth trick is complete
        if (store.isNotEmpty() &&
          parseRecord(store.last()).key.let { it.unit == E9Unit.TRICK && it.index == 4 }
        ) return
      }
    }
    drain()

    val fourTricks = DurableMatch.replay(store.joinToString("\n"), seed)
    val stateAtTrickFive = fourTricks.stateDocument()
    val trickFiveRow = store.size

    // Two cards of trick 5 fly, then the process dies. Design B persists per
    // Trick, so neither card may have produced a row.
    repeat(2) {
      val step = live.nextStep()
      val r = live.submit(step)
      assertTrue(r is E9Submit.Applied && r.record == null, "a card must not be a durable unit")
    }
    assertEquals(trickFiveRow, store.size, "a partial trick wrote a row")

    // Restart: the in-progress trick is gone, and the engine is exactly where
    // the fourth trick left it.
    val recovered = DurableMatch.replay(store.joinToString("\n"), seed)
    assertEquals(stateAtTrickFive, recovered.stateDocument())
    assertEquals(TablePhase.PLAY, recovered.currentTable()?.phase)
    assertEquals(5, recovered.currentTable()?.trickNo ?: -1)

    // Re-drive trick 5 with a card the bot would NOT have chosen — the
    // client's own decision has to survive in the log.
    val table = assertNotNull(recovered.currentTable())
    val seat = assertNotNull(table.turn)
    val botCard = (recovered.nextStep() as E9Step.CardPlay).card
    val clientCard: Card = legalCards(table, seat).first {
      it.suit != botCard.suit || it.rank.v != botCard.rank.v
    }
    val firstCard = recovered.submitCard(seat, clientCard)
    assertTrue(firstCard is E9Submit.Applied && firstCard.record == null)

    // Rest of the trick from the bot, then the match to the end.
    while (true) {
      val step = recovered.nextStep()
      if (step is E9Step.Done) break
      val r = recovered.submit(step)
      if (r is E9Submit.Applied) r.record?.let { store += it }
    }
    assertTrue(recovered.isComplete())

    val alternative = store.joinToString("\n")
    val replayed = DurableMatch.replay(alternative, seed)
    assertEquals(recovered.stateDocument(), replayed.stateDocument(), "the log did not carry the client's choice")

    val trickFive = parseRecord(store[trickFiveRow]) as E9Record.TrickUnit
    assertEquals(clientCard.suit, trickFive.plays.first().card.suit)
    assertEquals(clientCard.rank.v, trickFive.plays.first().card.rank.v)

    // And it is a different match from the bot-only one, still complete.
    val botOnly = drive(seed)
    assertNotEquals(botOnly.records[trickFiveRow], store[trickFiveRow])
    assertEquals(18, replayed.completedRun().rounds.size)
  }

  // ── 6. determinism ────────────────────────────────────────────────

  @Test
  fun theSameSeedProducesTheSameLog() {
    val a = drive(1994, GameType.MINI, ScoringMode.CLASSIC)
    val b = drive(1994, GameType.MINI, ScoringMode.CLASSIC)
    assertEquals(a.records, b.records)
    assertEquals(a.match.stateDocument(), b.match.stateDocument())
  }

  // ── 7. a conflict is loud, never silently ignored ─────────────────

  @Test
  fun aRowThatDoesNotFollowTheStoredPrefixIsRejected() {
    val driven = drive(1)
    val replayed = DurableMatch.replay(driven.records.subList(0, 50).joinToString("\n"), seed = 1)

    // 51st row replaced by an earlier one: same payload, wrong position.
    val wrong = driven.records[10]
    val boom = runCatching { replayed.applyRecord(wrong) }
    assertTrue(boom.isFailure, "a mis-ordered row was accepted")
    assertEquals(50, replayed.recordsApplied, "a rejected row advanced the store")
  }

  // ── 8. the trick row is the play, not a re-derivation ─────────────

  @Test
  fun aTrickRowCarriesTheFourCardsInPlayOrder() {
    val driven = drive(7)
    val tricks = driven.records.map(::parseRecord).filterIsInstance<E9Record.TrickUnit>()
    assertTrue(tricks.isNotEmpty())
    for (t in tricks) {
      assertEquals(4, t.plays.size)
      val seats = t.plays.map(Play::playerId)
      assertEquals(4, seats.toSet().size, "a trick cannot be played by one seat twice")
    }
  }
}
