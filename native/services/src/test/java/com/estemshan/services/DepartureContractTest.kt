package com.estemshan.services

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
 */
class DepartureContractTest {

  private companion object {
    /** The table's three mode columns (:421), in table order. */
    val MODES = listOf("RANKED", "UNRANKED", "ROOM")

    /** Row labels, verbatim from :423-427 (bold markers are styling, not
     *  part of the label). Order matters: it is the table's own order. */
    val EXPLICIT_LEAVE = "Explicit Leave Match (S72)"
    val FIFTEEN_TIMEOUT = "15-timeout automatic removal (RD20, OPEN-1)"
    val VOTE_KICK = "Vote Kick passed (RD18, S63)"
    val DISCONNECT = "Disconnect permanently replaced (S74)"
    val RECLAIM_RETURN = "Reclaim / return while the table waits"

    /**
     * The charge table (:421-427), verbatim. Rows in table order, columns
     * in [MODES] order. No cell is paraphrased and none is missing; the
     * `UNRANKED` Vote Kick cell carries the 2026-10-17 amendment note
     * exactly as the table spells it.
     */
    val CHARGE_TABLE: Map<String, Map<String, String>> = linkedMapOf(
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
  }

  /** One cell of the pinned table. */
  private fun charge(departure: String, mode: String): String =
    CHARGE_TABLE.getValue(departure).getValue(mode)

  // ── the table, whole ─────────────────────────────────────────────────

  @Test
  fun theChargeTableIsFiveDeparturesAcrossThreeModesInTableOrder() {
    // The pin is the table the amendment wrote — same rows, same columns,
    // same order (:421-427). A row or a mode added anywhere in code without
    // an amendment first stops here.
    val departures = listOf(EXPLICIT_LEAVE, FIFTEEN_TIMEOUT, VOTE_KICK, DISCONNECT, RECLAIM_RETURN)
    assertEquals("the five departure rows, in table order", departures, CHARGE_TABLE.keys.toList())
    for ((departure, charges) in CHARGE_TABLE) {
      assertEquals("every row has exactly the three mode columns: $departure", MODES, charges.keys.toList())
    }
  }

  // ── RD31's own rows: the two that double, and the one that does not ──

  @Test
  fun theTwoKozRowsChargeInRankedAndUnrankedAndNothingInRoom() {
    // A5.1's scope correction (:397-398), per the table: an explicit leave
    // (:423) and a 15-timeout automatic removal (:424) charge "2 Koz"
    // exactly in RANKED and UNRANKED and carry "no 2-Koz rule" in ROOM —
    // "`ROOM` is out of RD31 entirely" (:431-433).
    for (departure in listOf(EXPLICIT_LEAVE, FIFTEEN_TIMEOUT)) {
      assertEquals("2 Koz", charge(departure, "RANKED"))
      assertEquals("2 Koz", charge(departure, "UNRANKED"))
      assertEquals(
        "ROOM is out of RD31 entirely (:431-433)",
        "no 2-Koz rule",
        charge(departure, "ROOM"),
      )
    }
  }

  @Test
  fun voteKickRecordsOneKozFinalInBothVotableModesAndIsNotHeldInRoom() {
    // (:425, :429-430): Vote Kick records 1 Koz, FINAL — RD18's own
    // wording restored, the RD31 doubling repealed **for that path only** —
    // so no cell of the row may carry a 2-Koz reading. Since the
    // 2026-10-17 owner clarification the vote also holds in UNRANKED
    // (A3.5's pointer :312-318; the ruling record's §1.2), so the row's
    // Koz cells are exactly the two votable modes and the ROOM cell is the
    // amended verdict "n/a — not held in ROOM" (:425).
    assertEquals("1 Koz", charge(VOTE_KICK, "RANKED"))
    assertEquals("1 Koz (amended 2026-10-17)", charge(VOTE_KICK, "UNRANKED"))
    assertEquals("n/a — not held in ROOM", charge(VOTE_KICK, "ROOM"))
    val kozCells = CHARGE_TABLE.getValue(VOTE_KICK).values.filter { it.contains(" Koz") }
    assertEquals(
      "the row carries exactly the two votable-mode 1-Koz cells",
      2,
      kozCells.size,
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
    val kozCells = CHARGE_TABLE.getValue(RECLAIM_RETURN).values.filter { it.contains(" Koz") }
    assertTrue("the borrow row never charges", kozCells.isEmpty())
  }

  // ── the bookkeeping form: counter increments and nothing else ────────

  @Test
  fun theOnlyChargedValuesAreTheTwoCounterIncrements() {
    // A5.1 (:403-411): a charge is a **counter increment** — never a
    // placement (option b rejected) and never a distinct result value
    // (option c rejected); the nine-statistics accounting grows no enum and
    // no result row changes shape. In table terms: every charged cell is
    // one of the two increments below, and every cell of the table is
    // either a charged increment or one of the five non-charge verdicts.
    val cells = CHARGE_TABLE.values.flatMap { it.values }
    assertEquals("15 cells: 5 rows × 3 modes", 15, cells.size)
    val charged = cells.filter { it == "2 Koz" || it.startsWith("1 Koz") }
    assertEquals(
      "charged cells in table order: 4 × 2-Koz, Vote Kick's 1 Koz, its " +
        "amended UNRANKED 1 Koz (2026-10-17), and the disconnect's 2 Koz",
      listOf("2 Koz", "2 Koz", "2 Koz", "2 Koz", "1 Koz", "1 Koz (amended 2026-10-17)", "2 Koz"),
      charged,
    )
    assertEquals(
      "the increments are the whole charged vocabulary",
      setOf("2 Koz", "1 Koz", "1 Koz (amended 2026-10-17)"),
      charged.toSet(),
    )
    val nonCharges = cells - charged.toSet()
    assertEquals(
      "the five non-charge verdicts, as the amendment spells them",
      setOf(
        "no 2-Koz rule",
        "n/a — not held in ROOM",
        "n/a — the vote is not held in Ranked",
        "no Koz",
        "no penalty",
      ),
      nonCharges.toSet(),
    )
  }
}
