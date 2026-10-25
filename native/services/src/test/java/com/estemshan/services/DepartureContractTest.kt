package com.estemshan.services

import com.estemshan.engine.DisconnectVote
import com.estemshan.engine.KozCharge
import com.estemshan.engine.LeaveMatch
import com.estemshan.engine.LeaveReason
import com.estemshan.engine.MatchMode
import com.estemshan.engine.toKozCharge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The A5.1 charge table, pinned verbatim — the contract for what a match
 * *records* when a player's participation TERMINATES.
 *
 * SOURCE OF TRUTH. `docs/rules/CANONICAL_RULES.md` **Amendment A5** ("OPEN-4
 * and OPEN-5 closed by the owner, and RD31's mode scope corrected", owner
 * ruling 2026-10-16). Its charge table (:421-427) **governs over** A4.1's
 * fires-on list and A4.2's "RD31" column (:418-419), and it is amended in
 * place as of **2026-10-17** (owner clarification, PR #119): the Manual Vote
 * Kick holds in `RANKED` + `UNRANKED` — `ROOM` is the only mode without it —
 * so the row's `UNRANKED` cell is a second 1-Koz charge and its `ROOM` cell
 * reads "n/a — not held in ROOM". Provenance:
 * `docs/decisions/2026-10-17-unranked-scope-owner-clarification.md` (§1.2
 * availability, §1.4 the 1 Koz, final) and A3.5's own pointer (:312-318).
 *
 * WHAT THE OWNER RULED (A5.1, :403-416). "2 Koz" is a **counter increment**
 * — +2 to the player's career Koz count. Option (b) "a forced last-place
 * placement plus the double count" is **rejected** (:408-409): a departure
 * never positions a player below a continuing player and never displaces a
 * continuing player's placement. Option (c) "a distinct 2 Koz result value"
 * is **rejected** (:410-411): the nine-statistics accounting and the S50
 * settlement gain no new enum, and no result row changes shape. The bot
 * seat remains separate from the player (:412-413), and a return while the
 * table waits charges nothing — A4.1's borrowed-seat discriminator applied,
 * not a new exception (:414-416). The recorded form of the accounting is
 * plan-ruled too (`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:883-884`): the match
 * contributes its charge to the career Koz count with the "Koz % denominator
 * +1, Games Played +1".
 *
 * WHY A LITERAL PIN. Every string below — including the row labels, the
 * em-dashes, the "n/a" cells and each room cell — is copied from the table
 * exactly, so a doc edit and a code drift cannot pass one another silently.
 * The rows carry the table's own cross-references because those are where
 * the recording behavior is (or will be) implemented: the 15-timeout
 * counting is already shipped (S61); the engine side is the decision cores
 * ([com.estemshan.engine.LeaveMatch.decideLeave],
 * [com.estemshan.engine.DisconnectVote.permanentCharge]) plus the charge
 * fold ([com.estemshan.engine.RankedStats.accumulateDepartureCharge]) — no
 * `:services` flow writes a charge yet, and this pin is the contract the
 * flows must satisfy.
 *
 * LINE CITED. Amendment A5 at HEAD 86f01baad393643d8fc3cde1e62a4d045bca21f6
 * (merge PR #119, carrying the 2026-10-17 corrections): the governing clause
 * + table :418-427; "Vote Kick records 1 Koz, final" and the repeal of the
 * doubling **for that path only** :429-430; "`ROOM` is out of RD31 entirely"
 * :431-433; the disconnect vote's mode scope A5.2 :443-444; the flags A5.3
 * :448-478 — A5.3 item 3 is **RESOLVED (owner-ratified, 2026-10-17)**
 * (:457-465), so the 2026-10-16 pin's "textually unratified" caveat is
 * retired: the `UNRANKED` cells stand ratified and A3.5 carries its own
 * pointer (:312-318). A5.4 (:480-489) confirms none of this changes a
 * gameplay rule — it is a recording contract only.
 *
 * AMENDED 2026-10-24 (pointer; the provenance above is left unchanged, and
 * the doc's A5 table text itself stays verbatim as history under its own
 * 2026-10-22 pointer). The governing authority is now the ratified register
 * `docs/decisions/OPEN_QUESTIONS.md` (as of 2026-10-23), so the pin is
 * TWO-LAYER: [A5_TABLE_AS_WRITTEN] copies the doc's table exactly as
 * written, and [RATIFIED_CHARGE_TABLE] / [RATIFIED_DISCONNECT_CELLS] pin
 * the dated pointers that supersede cells of it — each cell carrying its
 * ruling id, and every cell cross-checked against the engine cores:
 *
 * - Vote Kick = **2 Koz** in `RANKED` + `UNRANKED`, never held in `ROOM`
 *   (**Q14d(1)**, 2026-10-22 — supersedes A5.1's "1 Koz, final" and the
 *   repealed 2026-10-16 C1 closure).
 * - An explicit leave = 2 Koz in `RANKED`/`UNRANKED`, **1 Koz in `ROOM`**
 *   (**Q14b**, confirmed by the **A6.12** / **Q14b(1)** record).
 * - The 15-timeout removal = 2 Koz in `RANKED`/`UNRANKED`, **1 Koz in
 *   `ROOM`** — "O-03d is closed by A8.3 / Q37" (CANONICAL_RULES.md's
 *   "Pointer, 2026-10-23" on the O-03d cell: "the 15-timeout removal
 *   records 1 Koz in ROOM (2 Koz in RANKED/UNRANKED unchanged)"; the cell
 *   is superseded by a positive value, not repealed to zero).
 * - The permanent-disconnect row is confirmed, not superseded (**Q33** /
 *   **A7.4**: `UNRANKED` 2 Koz, `ROOM` no Koz, `RANKED` no vote).
 * - A reclaim/return stays "no penalty" (**A6.12**: only a voluntary leave
 *   is a departure).
 *
 * The `docs/rules` files are another actor's territory this round, so the
 * doc-side cells are pinned here as written and flagged, not edited.
 */
class DepartureContractTest {

  private companion object {
    /** The table's three mode columns (:421), in table order. */
    val MODES = listOf("RANKED", "UNRANKED", "ROOM")

    /** Row labels, verbatim from :423-427 (bold markers are styling, not
     *  part of the label). Order matters: it is the table's own order.
     *  These label the AS-WRITTEN pin; the ratified table is keyed by
     *  [LeaveReason] and carries its own ruling ids per cell. */
    val EXPLICIT_LEAVE = "Explicit Leave Match (S72)"
    val FIFTEEN_TIMEOUT = "15-timeout automatic removal (RD20, OPEN-1)"
    val VOTE_KICK = "Vote Kick passed (RD18, S63)"
    val DISCONNECT = "Disconnect permanently replaced (S74)"
    val RECLAIM_RETURN = "Reclaim / return while the table waits"

    /**
     * The A5 charge table (:421-427), verbatim AS WRITTEN — the doc's own
     * cells, including every cell its dated pointers have since superseded
     * (the doc leaves its superseded text verbatim as history). Rows in
     * table order, columns in [MODES] order; no cell is paraphrased and
     * none is missing. The pointers are pinned separately, in
     * [RATIFIED_CHARGE_TABLE] and [RATIFIED_DISCONNECT_CELLS].
     */
    val A5_TABLE_AS_WRITTEN: Map<String, Map<String, String>> = linkedMapOf(
      EXPLICIT_LEAVE to linkedMapOf(
        "RANKED" to "2 Koz",
        "UNRANKED" to "2 Koz",
        "ROOM" to "no 2-Koz rule",
      ),
      FIFTEEN_TIMEOUT to linkedMapOf(
        "RANKED" to "2 Koz",
        "UNRANKED" to "2 Koz",
        "ROOM" to "no 2-Koz rule",
      ),
      VOTE_KICK to linkedMapOf(
        "RANKED" to "1 Koz",
        "UNRANKED" to "1 Koz (amended 2026-10-17)",
        "ROOM" to "n/a — not held in ROOM",
      ),
      DISCONNECT to linkedMapOf(
        "RANKED" to "n/a — the vote is not held in Ranked",
        "UNRANKED" to "2 Koz",
        "ROOM" to "no Koz",
      ),
      RECLAIM_RETURN to linkedMapOf(
        "RANKED" to "no penalty",
        "UNRANKED" to "no penalty",
        "ROOM" to "no penalty",
      ),
    )

    /** One ratified cell: the superseding ruling's value with its ruling
     *  id, and the [KozCharge] the engine must return for it — `null`
     *  where the ruling assigns no charge because the path is not held. */
    data class RatifiedCell(val cell: String, val charge: KozCharge?)

    /**
     * The ratified charge table — the dated pointers pinned cell by cell,
     * keyed by [LeaveReason] so the engine cross-check cannot drift off the
     * rows. Explicit leave 2/2/1 (Q14b); 15-timeout 2/2/1 (Q37, closing
     * O-03d); Vote Kick 2/2/n-a (Q14d(1) — the charge is mode-independent:
     * the cross-check asserts its ROOM answer separately); reclaim none
     * (A6.12). The disconnect row is not a [LeaveReason] — it lives in
     * [RATIFIED_DISCONNECT_CELLS].
     */
    val RATIFIED_CHARGE_TABLE: Map<LeaveReason, Map<String, RatifiedCell>> = linkedMapOf(
      LeaveReason.EXPLICIT_LEAVE to linkedMapOf(
        "RANKED" to RatifiedCell("2 Koz (Q14b)", KozCharge.Koz2),
        "UNRANKED" to RatifiedCell("2 Koz (Q14b)", KozCharge.Koz2),
        "ROOM" to RatifiedCell("1 Koz (Q14b)", KozCharge.Koz1),
      ),
      LeaveReason.FIFTEEN_TIMEOUT to linkedMapOf(
        "RANKED" to RatifiedCell("2 Koz (Q37)", KozCharge.Koz2),
        "UNRANKED" to RatifiedCell("2 Koz (Q37)", KozCharge.Koz2),
        "ROOM" to RatifiedCell("1 Koz (Q37)", KozCharge.Koz1),
      ),
      LeaveReason.VOTE_KICK to linkedMapOf(
        "RANKED" to RatifiedCell("2 Koz (Q14d(1))", KozCharge.Koz2),
        "UNRANKED" to RatifiedCell("2 Koz (Q14d(1))", KozCharge.Koz2),
        "ROOM" to RatifiedCell("n/a — not held in ROOM (Q14d(1))", null),
      ),
      LeaveReason.RETURNED_ALLOWED to linkedMapOf(
        "RANKED" to RatifiedCell("no penalty (A6.12)", KozCharge.None),
        "UNRANKED" to RatifiedCell("no penalty (A6.12)", KozCharge.None),
        "ROOM" to RatifiedCell("no penalty (A6.12)", KozCharge.None),
      ),
    )

    /**
     * The ratified disconnect-replacement cells (**Q33** / **A7.4**, which
     * confirm the A5 row rather than supersede it): `RANKED` has no such
     * path, `UNRANKED` charges 2 Koz, `ROOM` charges nothing.
     */
    val RATIFIED_DISCONNECT_CELLS: Map<String, RatifiedCell> = linkedMapOf(
      "RANKED" to RatifiedCell("n/a — the vote is not held in Ranked (A5.2)", null),
      "UNRANKED" to RatifiedCell("2 Koz (Q33/A7.4)", KozCharge.Koz2),
      "ROOM" to RatifiedCell("no Koz (Q33/A7.4)", KozCharge.None),
    )
  }

  /** One cell of the as-written pin. */
  private fun charge(departure: String, mode: String): String =
    A5_TABLE_AS_WRITTEN.getValue(departure).getValue(mode)

  /** One cell of the ratified pin. */
  private fun ratified(reason: LeaveReason, mode: String): RatifiedCell =
    RATIFIED_CHARGE_TABLE.getValue(reason).getValue(mode)

  // ── the table, whole ─────────────────────────────────────────────────

  @Test
  fun theChargeTableIsFiveDeparturesAcrossThreeModesInTableOrder() {
    // The pin is the table the amendment wrote — same rows, same columns,
    // same order (:421-427). A row or a mode added anywhere in code without
    // an amendment first stops here — in either layer.
    val departures = listOf(EXPLICIT_LEAVE, FIFTEEN_TIMEOUT, VOTE_KICK, DISCONNECT, RECLAIM_RETURN)
    assertEquals("the five departure rows, in table order", departures, A5_TABLE_AS_WRITTEN.keys.toList())
    for ((departure, charges) in A5_TABLE_AS_WRITTEN) {
      assertEquals("every row has exactly the three mode columns: $departure", MODES, charges.keys.toList())
    }
    // The ratified layer mirrors the same five departures: the four
    // [LeaveReason] rows plus the disconnect row.
    assertEquals(
      "the ratified table is keyed by the four LeaveReason rows, in reason order",
      listOf(
        LeaveReason.EXPLICIT_LEAVE, LeaveReason.FIFTEEN_TIMEOUT,
        LeaveReason.VOTE_KICK, LeaveReason.RETURNED_ALLOWED,
      ),
      RATIFIED_CHARGE_TABLE.keys.toList(),
    )
    for ((reason, cells) in RATIFIED_CHARGE_TABLE) {
      assertEquals("every ratified row has exactly the three mode columns: $reason", MODES, cells.keys.toList())
    }
    assertEquals(
      "the ratified disconnect row has exactly the three mode columns",
      MODES, RATIFIED_DISCONNECT_CELLS.keys.toList(),
    )
  }

  // ── RD31's own rows: the two that double, and the one that does not ──

  @Test
  fun theA5TableAsWrittenPinsTwoKozRowsAndNoTwoKozRuleInRoom() {
    // The AS-WRITTEN pin of A5.1's scope correction (:397-398): an explicit
    // leave (:423) and a 15-timeout automatic removal (:424) carry "2 Koz"
    // in RANKED and UNRANKED and "no 2-Koz rule" in ROOM (:431-433). Both
    // ROOM cells are superseded by dated pointers — the explicit-leave cell
    // by Q14b (1 Koz in ROOM) and the 15-timeout cell by Q37 ("O-03d is
    // closed by A8.3 / Q37": 1 Koz in ROOM, "superseded by a positive
    // value, not repealed to zero"). The superseding values are pinned in
    // [RATIFIED_CHARGE_TABLE] and cross-checked against the engine below.
    for (departure in listOf(EXPLICIT_LEAVE, FIFTEEN_TIMEOUT)) {
      assertEquals("2 Koz", charge(departure, "RANKED"))
      assertEquals("2 Koz", charge(departure, "UNRANKED"))
      assertEquals(
        "the as-written ROOM cell (:431-433)",
        "no 2-Koz rule",
        charge(departure, "ROOM"),
      )
    }
  }

  @Test
  fun voteKickRecordsTwoKozInBothVotableModesAndIsNotHeldInRoom() {
    // Ratified layer (Q14d(1), 2026-10-22): Vote Kick records **2 Koz** in
    // RANKED and UNRANKED; the vote is never held in ROOM, so the ratified
    // ROOM cell stays n/a. The as-written layer keeps the doc's own cells —
    // "1 Koz" / "1 Koz (amended 2026-10-17)" — verbatim: the doc's
    // 2026-10-22 pointer supersedes their number without rewriting them,
    // and this pin exists so a doc edit and a code drift cannot pass one
    // another silently.
    assertEquals("2 Koz (Q14d(1))", ratified(LeaveReason.VOTE_KICK, "RANKED").cell)
    assertEquals("2 Koz (Q14d(1))", ratified(LeaveReason.VOTE_KICK, "UNRANKED").cell)
    assertEquals("n/a — not held in ROOM (Q14d(1))", ratified(LeaveReason.VOTE_KICK, "ROOM").cell)
    assertEquals("1 Koz", charge(VOTE_KICK, "RANKED"))
    assertEquals("1 Koz (amended 2026-10-17)", charge(VOTE_KICK, "UNRANKED"))
    assertEquals("n/a — not held in ROOM", charge(VOTE_KICK, "ROOM"))
    val kozCells = RATIFIED_CHARGE_TABLE.getValue(LeaveReason.VOTE_KICK).values
      .map { it.cell }
      .filter { it.contains(" Koz") }
    assertEquals(
      "the ratified row carries exactly the two votable-mode 2-Koz cells",
      2,
      kozCells.size,
    )
    assertTrue(
      "no ratified cell of the row may carry a 1-Koz reading (Q14d(1))",
      kozCells.all { it.startsWith("2 Koz") },
    )
  }

  // ── the engine cross-check: the ratified cells ARE the cores' answers ──

  @Test
  fun theRatifiedCellsMatchTheEngineChargeForEachModeAndReason() {
    // The test this suite was missing: the doc-pin never called the engine,
    // so it stayed green whether or not the cores agreed with the register.
    // Every ratified cell with a charge must equal what
    // [LeaveMatch.decideLeave] returns for that mode × reason pair. The
    // Vote Kick ROOM cell is the one n/a — a not-held path with no engine
    // pair — so the loop skips null-charge cells and its mode-independent
    // answer gets its own assertion below.
    for ((reason, cells) in RATIFIED_CHARGE_TABLE) {
      for ((mode, cell) in cells) {
        if (cell.charge == null) continue
        assertEquals(
          "decideLeave($mode, $reason) must equal the ratified cell '${cell.cell}'",
          cell.charge,
          LeaveMatch.decideLeave(MatchMode.valueOf(mode), reason),
        )
      }
    }
    assertEquals(
      "the Vote Kick charge is mode-independent (Q14d(1))",
      KozCharge.Koz2,
      LeaveMatch.decideLeave(MatchMode.ROOM, LeaveReason.VOTE_KICK),
    )
    // The disconnect row rides the other core — permanentCharge (Q33/A7.4)
    // mapped through toKozCharge; RANKED is "no such path", not a charge.
    assertEquals(
      "permanentCharge(UNRANKED) must equal the ratified 2-Koz cell",
      RATIFIED_DISCONNECT_CELLS.getValue("UNRANKED").charge,
      DisconnectVote.permanentCharge(MatchMode.UNRANKED)!!.toKozCharge(),
    )
    assertEquals(
      "permanentCharge(ROOM) must equal the ratified no-Koz cell",
      RATIFIED_DISCONNECT_CELLS.getValue("ROOM").charge,
      DisconnectVote.permanentCharge(MatchMode.ROOM)!!.toKozCharge(),
    )
    assertEquals(
      "permanentCharge(RANKED) is no such path (A5.2)",
      null as KozCharge?,
      DisconnectVote.permanentCharge(MatchMode.RANKED),
    )
  }

  @Test
  fun aPermanentDisconnectReplacementChargesOnlyWhereTheVoteMayBeHeld() {
    // (:426, A5.2 :443-444). The vote exists for ROOM + UNRANKED only
    // (DEC-DISC-01); in RANKED "the question is not asked at all", so that
    // cell is the doc's own n/a verdict ("n/a — the vote is not held in
    // Ranked"). A finalized replacement records 2 Koz in UNRANKED and
    // charges nothing in ROOM — the ROOM half of the correction applied to
    // A4.1's blanket wording.
    assertEquals(
      "the vote is not held in Ranked",
      "n/a — the vote is not held in Ranked",
      charge(DISCONNECT, "RANKED"),
    )
    assertEquals("2 Koz", charge(DISCONNECT, "UNRANKED"))
    assertEquals("a Room disconnect replacement charges nothing (:432-433)", "no Koz", charge(DISCONNECT, "ROOM"))
  }

  // ── the borrowed-seat discriminator ──────────────────────────────────

  @Test
  fun aReclaimOrReturnWhileTheTableWaitsChargesNothingInAnyMode() {
    // (:427 in the table; :414-416 in the prose). Path C (the RD19
    // inactivity reclaim, S73) and path D before permanence (the S74
    // disconnect takeover while the table is still waiting) are
    // **borrowals of the seat**, not departures — A4.1's borrowed-seat
    // discriminator applied, "not a new exception". All three columns read
    // "no penalty", and a reclaimed seat must never read as a Koz charge.
    for (mode in MODES) {
      assertEquals("no charge for a borrowed seat in $mode", "no penalty", charge(RECLAIM_RETURN, mode))
    }
    val kozCells = A5_TABLE_AS_WRITTEN.getValue(RECLAIM_RETURN).values.filter { it.contains(" Koz") }
    assertTrue("the borrow row never charges", kozCells.isEmpty())
  }

  // ── the bookkeeping form: counter increments and nothing else ────────

  @Test
  fun theOnlyChargedValuesAreTheTwoCounterIncrements() {
    // A5.1 (:403-411): a charge is a **counter increment** — never a
    // placement (option b rejected) and never a distinct result value
    // (option c rejected); the nine-statistics accounting grows no enum and
    // no result row changes shape. In table terms: every charged cell
    // carries one of the TWO increments (1 Koz or 2 Koz), and every other
    // cell is a non-charge verdict. The vocabulary is checked in BOTH
    // layers: the as-written pin keeps the doc's superseded "1 Koz"
    // spellings representable, and the ratified pin carries their
    // superseding values.
    val asWritten = A5_TABLE_AS_WRITTEN.values.flatMap { it.values }
    assertEquals("15 cells: 5 rows × 3 modes", 15, asWritten.size)
    val asWrittenCharged = asWritten.filter { it.startsWith("1 Koz") || it.startsWith("2 Koz") }
    assertEquals(
      "as-written charged cells in table order: the RD31 rows' four 2-Koz " +
        "cells, Vote Kick's 1 Koz and its amended UNRANKED 1 Koz " +
        "(2026-10-17), and the disconnect's 2 Koz",
      listOf("2 Koz", "2 Koz", "2 Koz", "2 Koz", "1 Koz", "1 Koz (amended 2026-10-17)", "2 Koz"),
      asWrittenCharged,
    )
    assertEquals(
      "the as-written charged vocabulary",
      setOf("2 Koz", "1 Koz", "1 Koz (amended 2026-10-17)"),
      asWrittenCharged.toSet(),
    )
    assertEquals(
      "the five non-charge verdicts, as the amendment spells them",
      setOf(
        "no 2-Koz rule",
        "n/a — not held in ROOM",
        "n/a — the vote is not held in Ranked",
        "no Koz",
        "no penalty",
      ),
      (asWritten - asWrittenCharged.toSet()).toSet(),
    )

    val ratified = RATIFIED_CHARGE_TABLE.values
      .flatMap { it.values.map { cell -> cell.cell } } +
      RATIFIED_DISCONNECT_CELLS.values.map { it.cell }
    assertEquals("15 ratified cells: 4 reason rows × 3 modes + the disconnect row", 15, ratified.size)
    val ratifiedCharged = ratified.filter { it.startsWith("1 Koz") || it.startsWith("2 Koz") }
    assertEquals(
      "ratified charged cells in row order: explicit leave 2/2/1 (Q14b), " +
        "15-timeout 2/2/1 (Q37), Vote Kick 2/2 (Q14d(1)), disconnect 2 (Q33/A7.4)",
      listOf(
        "2 Koz (Q14b)", "2 Koz (Q14b)", "1 Koz (Q14b)",
        "2 Koz (Q37)", "2 Koz (Q37)", "1 Koz (Q37)",
        "2 Koz (Q14d(1))", "2 Koz (Q14d(1))",
        "2 Koz (Q33/A7.4)",
      ),
      ratifiedCharged,
    )
    assertEquals(
      "the ratified charged vocabulary carries BOTH increments with their ruling ids",
      setOf(
        "1 Koz (Q14b)", "1 Koz (Q37)",
        "2 Koz (Q14b)", "2 Koz (Q37)", "2 Koz (Q14d(1))", "2 Koz (Q33/A7.4)",
      ),
      ratifiedCharged.toSet(),
    )
    assertEquals(
      "the ratified non-charge verdicts",
      setOf(
        "no penalty (A6.12)",
        "no Koz (Q33/A7.4)",
        "n/a — not held in ROOM (Q14d(1))",
        "n/a — the vote is not held in Ranked (A5.2)",
      ),
      (ratified - ratifiedCharged.toSet()).toSet(),
    )
  }
}
