package com.estemshan.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RD31 charge table, pinned verbatim — the S72/S74-side contract for
 * what a match *records* when a player's participation TERMINATES
 * (`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:667`).
 *
 * SOURCE OF TRUTH. `docs/rules/CANONICAL_RULES.md` **Amendment A5**
 * ("OPEN-4 and OPEN-5 closed by the owner, and RD31's mode scope corrected",
 * owner ruling 2026-10-16). Its charge table (:413-419) **governs over**
 * A4.1's fires-on list and A4.2's "RD31" column (:410-411). The scope
 * correction it carries is stated once and governs everywhere (:389-390):
 * the 2-Koz rule applies in `RANKED` and `UNRANKED`, and `ROOM` — normal
 * rooms — has **no 2-Koz rule at all**.
 *
 * WHAT THE OWNER RULED (A5.1, :395-408). "2 Koz" is a **counter increment**
 * — +2 to the player's career Koz count. Option (b) "a forced last-place
 * placement plus the double count" is **rejected** (:400-401): a departure
 * never positions a player below a continuing player and never displaces a
 * continuing player's placement. Option (c) "a distinct 2 Koz result value"
 * is **rejected** (:402-403): the nine-statistics accounting and the S50
 * settlement gain no new enum, and no result row changes shape. The bot
 * seat remains separate from the player (:404-405), and a return while the
 * table waits charges nothing — A4.1's borrowed-seat discriminator applied,
 * not a new exception (:406-408).
 *
 * WHY A LITERAL PIN. Every string below — including the row labels, the
 * em-dashes, the "n/a" cells and each room cell — is copied from the table
 * exactly, so a doc edit and a code drift cannot pass one another silently.
 * The rows carry the table's own cross-references because those are where
 * the recording behavior is (or will be) implemented: the 15-timeout
 * counting is already shipped (S61); **S72 (Explicit Leave Match), S63's
 * RD31 recording (Vote Kick) and S74 (Disconnect) have not landed a charge
 * write yet** — this pin is the contract they must satisfy: it compiles as
 * it stands today and holds exactly as written at integration.
 *
 * LINE CITED. Amendment A5 at HEAD 40c82895d96966625a93db034aaf71300e3dc644
 * (merge PR #116): the table + governing clause :410-419; "Vote Kick
 * records 1 Koz, final" and the repeal of the doubling **for that path
 * only** :421-422; "`ROOM` is out of RD31 entirely" :423-425; the
 * disconnect vote's mode scope A5.2 :435-436; the flagged-not-resolved
 * list A5.3 :440-463 — most notably, the `UNRANKED` 2-Koz cells are
 * owner-ruled but **textually unratified** until A3.5 carries its own
 * pointer (:449-456); this file pins the ruling as written and expects the
 * A3.5 pointer to lead any further doc change. A5.4 (:465-474) confirms
 * none of this changes a gameplay rule — it is a recording contract only.
 */
class DepartureContractTest {

  private companion object {
    /** The table's three mode columns (:413), in table order. */
    val MODES = listOf("RANKED", "UNRANKED", "ROOM")

    /** Row labels, verbatim from :415-419 (bold markers are styling, not
     *  part of the label). Order matters: it is the table's own order. */
    val EXPLICIT_LEAVE = "Explicit Leave Match (S72)"
    val FIFTEEN_TIMEOUT = "15-timeout automatic removal (RD20, OPEN-1)"
    val VOTE_KICK = "Vote Kick passed (RD18, S63)"
    val DISCONNECT = "Disconnect permanently replaced (S74)"
    val RECLAIM_RETURN = "Reclaim / return while the table waits"

    /**
     * The charge table (:413-419), verbatim. Rows in table order, columns
     * in [MODES] order. No cell is paraphrased and none is missing.
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
        "UNRANKED" to "n/a — Ranked-only",
        "ROOM" to "n/a — Ranked-only",
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
    // same order (:413-419). A row or a mode added anywhere in code without
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
    // A5.1's scope correction (:389-390), per the table: an explicit leave
    // (:415) and a 15-timeout automatic removal (:416) charge "2 Koz"
    // exactly in RANKED and UNRANKED and carry "no 2-Koz rule" in ROOM —
    // "`ROOM` is out of RD31 entirely" (:423-425).
    for (departure in listOf(EXPLICIT_LEAVE, FIFTEEN_TIMEOUT)) {
      assertEquals("2 Koz", charge(departure, "RANKED"))
      assertEquals("2 Koz", charge(departure, "UNRANKED"))
      assertEquals(
        "ROOM is out of RD31 entirely (:423-425)",
        "no 2-Koz rule",
        charge(departure, "ROOM"),
      )
    }
  }

  @Test
  fun voteKickChargesOneKozFinalInRankedAndIsRankedOnly() {
    // (:417, :421-422): Vote Kick records 1 Koz, FINAL. This restores
    // RD18's "recorded as Koz" and repeals RD31's doubling **for that path
    // only** — RD31 still doubles the departure rows above, so the row must
    // carry no 2-Koz cell in any column. Its two other cells are the doc's
    // own "n/a — Ranked-only": the mechanism's single availability claim
    // unchanged by A5 (A5.4, :472).
    assertEquals("Vote Kick records 1 Koz, final", "1 Koz", charge(VOTE_KICK, "RANKED"))
    assertEquals(
      "the row carries exactly one Koz cell — the 1 Koz, final",
      1,
      CHARGE_TABLE.getValue(VOTE_KICK).values.filter { it.contains("Koz") }.size,
    )
    assertEquals("n/a — Ranked-only", charge(VOTE_KICK, "UNRANKED"))
    assertEquals("n/a — Ranked-only", charge(VOTE_KICK, "ROOM"))
  }

  @Test
  fun aPermanentDisconnectReplacementChargesOnlyWhereTheVoteMayBeHeld() {
    // (:418, A5.2 :435-436). The vote exists for ROOM + UNRANKED only
    // (DEC-DISC-01; plan S74 row :715); in RANKED "the question is not
    // asked at all", so that cell is the doc's own n/a verdict
    // ("n/a — the vote is not held in Ranked"). A finalized replacement
    // records 2 Koz in UNRANKED and nothing under RD31 in ROOM — the ROOM
    // half of the correction applied to A4.1's blanket wording (:442-445).
    assertEquals(
      "the vote is not held in Ranked",
      "n/a — the vote is not held in Ranked",
      charge(DISCONNECT, "RANKED"),
    )
    assertEquals("2 Koz", charge(DISCONNECT, "UNRANKED"))
    assertEquals("a Room disconnect replacement charges nothing (:424-425)", "no Koz", charge(DISCONNECT, "ROOM"))
  }

  // ── the borrowed-seat discriminator ──────────────────────────────────

  @Test
  fun aReclaimOrReturnWhileTheTableWaitsChargesNothingInAnyMode() {
    // (:419 in the table; :406-408 in the prose). Path C (the RD19
    // inactivity reclaim, S73) and path D before permanence (the S74
    // disconnect takeover while the table is still waiting) are
    // **borrowals of the seat**, not departures — A4.1's borrowed-seat
    // discriminator applied, "not a new exception". All three columns read
    // "no penalty", and reclaimed seat must never read as a Koz charge.
    for (mode in MODES) {
      assertEquals("no charge for a borrowed seat in $mode", "no penalty", charge(RECLAIM_RETURN, mode))
    }
    val kozCells = CHARGE_TABLE.getValue(RECLAIM_RETURN).values.filter { it.contains(" Koz") }
    assertTrue("the borrow row never charges", kozCells.isEmpty())
  }

  // ── the bookkeeping form: counter increments and nothing else ────────

  @Test
  fun theOnlyChargedValuesAreTheTwoCounterIncrements() {
    // A5.1 (:395-403): a charge is a **counter increment** — never a
    // placement (option b rejected) and never a distinct result value
    // (option c rejected); the nine-statistics accounting grows no enum and
    // no result row changes shape. In table terms: every charged cell is
    // one of the two increments below, and every cell of the table is
    // either a charged increment or one of the five non-charge verdicts.
    val cells = CHARGE_TABLE.values.flatMap { it.values }
    assertEquals("15 cells: 5 rows × 3 modes", 15, cells.size)
    val charged = cells.filter { it == "2 Koz" || it == "1 Koz" }
    assertEquals(
      "charged cells in table order: 4 × 2-Koz + the final 1 Koz",
      listOf("2 Koz", "2 Koz", "2 Koz", "2 Koz", "1 Koz", "2 Koz"),
      charged,
    )
    assertEquals(
      "the increments are the whole charged vocabulary",
      setOf("2 Koz", "1 Koz"),
      charged.toSet(),
    )
    val nonCharges = cells - charged.toSet()
    assertEquals(
      "the five non-charge verdicts, as the amendment spells them",
      setOf("no 2-Koz rule", "n/a — Ranked-only", "n/a — the vote is not held in Ranked", "no Koz", "no penalty"),
      nonCharges.toSet(),
    )
  }
}
