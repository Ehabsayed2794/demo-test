package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S57 — golden port of the 11 preview fixtures in
 * design-ui/standings-ranked-result/ranked-result.js, plus the
 * threshold-boundary / floor / ceiling cases the story requires.
 *
 * Production supplies delta already final (mode + mixed-tier adjusted);
 * the reference's half() Mini derivation is NOT re-applied here — the
 * Mini fixture below passes delta = 8 directly (half(15)).
 *
 * node was not on PATH in this environment, so expected values were
 * derived from the threshold table (§4b.1 / RankLadder.kt) by hand and
 * cross-checked against validate.cjs's assertions (19 rungs, floor,
 * ceiling, Mini rounding, per-boundary rankAt).
 */
class RankedResolutionTest {

  private data class Fixture(
    val label: String,
    val previousRP: Int,
    /** Final delta as production would supply it. */
    val delta: Int,
    val expectedRp: Int,
    val expectedDelta: Int,
    val expectedPrevious: String,
    val expectedNext: String,
    val expectedMovement: Int,
    val expectedCeiling: Boolean,
  )

  private val golden: List<Fixture> = listOf(
    Fixture(
      "Within-tier promotion", 430, 30, 460, 30,
      "Silver II · لاعب", "Silver I · لاعب", 1, false,
    ),
    Fixture(
      "Higher-tier promotion", 980, 30, 1010, 30,
      "Gold I · معلم", "Platinum III · وزير", 1, false,
    ),
    Fixture(
      "One-division demotion", 710, -30, 680, -30,
      "Gold II · معلم", "Gold III · معلم", -1, false,
    ),
    Fixture(
      "Ladder floor", 0, -30, 0, 0,
      "Bronze III · مبتدئ", "Bronze III · مبتدئ", 0, false,
    ),
    Fixture(
      "Mixed-tier loss", 740, -45, 695, -45,
      "Gold II · معلم", "Gold III · معلم", -1, false,
    ),
    Fixture(
      "Mini", 600, 8, 608, 8,
      "Gold III · معلم", "Gold III · معلم", 0, false,
    ),
    Fixture(
      "King ceiling", 3050, 30, 3080, 30,
      "King · ملك", "King · ملك", 0, true,
    ),
    Fixture(
      "Match King · Gold rank", 600, 30, 630, 30,
      "Gold III · معلم", "Gold III · معلم", 0, false,
    ),
    Fixture(
      "Tied Match Kings", 600, 30, 630, 30,
      "Gold III · معلم", "Gold III · معلم", 0, false,
    ),
    Fixture(
      "Mixed-tier win", 600, 15, 615, 15,
      "Gold III · معلم", "Gold III · معلم", 0, false,
    ),
    Fixture(
      "Lower-tier win", 430, 45, 475, 45,
      "Silver II · لاعب", "Silver I · لاعب", 1, false,
    ),
  )

  @Test
  fun ladderHas19RungsKingLastWithNoDivision() {
    assertEquals(19, rankLadder.size)
    assertTrue(rankLadder.last().isKing)
    assertEquals("", rankLadder.last().division)
    assertEquals("King · ملك", rankLabel(rankLadder.last()))
    // Every other rung carries a division; tiers ascend III → II → I.
    rankLadder.dropLast(1).forEach { assertTrue(it.division in listOf("III", "II", "I")) }
    val tiers = rankLadder.map { it.tier }.distinct()
    assertEquals(
      listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond", "Royal", "King"),
      tiers,
    )
  }

  @Test
  fun labelFormatsExactlyAsReference() {
    assertEquals("Gold III · معلم", rankLabel(rankAt(600)))
    assertEquals("King · ملك", rankLabel(rankAt(3000)))
    assertEquals("Bronze III · مبتدئ", rankLabel(rankAt(0)))
  }

  @Test
  fun goldenFixturesResolveEveryField() {
    assertEquals(11, golden.size)
    for (f in golden) {
      val r = resolveRanked(f.previousRP, f.delta)
      assertEquals("${f.label}: previousRP", f.previousRP.coerceAtLeast(0), r.previousRP)
      assertEquals("${f.label}: rp", f.expectedRp, r.rp)
      assertEquals("${f.label}: delta", f.expectedDelta, r.delta)
      assertEquals("${f.label}: previous", f.expectedPrevious, rankLabel(r.previous))
      assertEquals("${f.label}: next", f.expectedNext, rankLabel(r.next))
      assertEquals("${f.label}: movement", f.expectedMovement, r.movement)
      assertEquals("${f.label}: ceiling", f.expectedCeiling, r.ceiling)
    }
  }

  @Test
  fun everyThresholdBoundaryPromotesAtLowerBound() {
    for ((i, rung) in rankLadder.withIndex()) {
      assertEquals("rankAt(${rung.lowerBound})", rung, rankAt(rung.lowerBound))
      if (rung.lowerBound > 0) {
        // One below the floor belongs to the previous rung …
        assertEquals(
          "rankAt(${rung.lowerBound - 1})",
          rankLadder[i - 1],
          rankAt(rung.lowerBound - 1),
        )
        // … so +1 across the line promotes exactly one step.
        val up = resolveRanked(rung.lowerBound - 1, 1)
        assertEquals(rung, up.next)
        assertEquals(rankLadder[i - 1], up.previous)
        assertEquals(1, up.movement)
      }
    }
  }

  @Test
  fun everyThresholdFloorDemotesOneBelow() {
    for ((i, rung) in rankLadder.withIndex()) {
      if (rung.lowerBound == 0) continue // floor case is asserted separately
      val down = resolveRanked(rung.lowerBound, -1)
      assertEquals("demote from ${rung.label()}", rankLadder[i - 1], down.next)
      assertEquals(rung, down.previous)
      assertEquals(-1, down.movement)
      assertFalse(down.ceiling)
    }
  }

  @Test
  fun rpFloorShowsZeroWithNoDemotion() {
    val r = resolveRanked(0, -30)
    assertEquals(0, r.rp)
    assertEquals(0, r.delta)
    assertEquals(rankLadder.first(), r.previous)
    assertEquals(rankLadder.first(), r.next)
    assertEquals(0, r.movement)
    assertFalse(r.ceiling)
  }

  @Test
  fun kingCeilingAccruesWithSettledMovement() {
    val r = resolveRanked(3050, 30)
    assertEquals(3080, r.rp)
    assertEquals(30, r.delta)
    assertTrue(r.previous.isKing)
    assertTrue(r.next.isKing)
    assertEquals(0, r.movement)
    assertTrue(r.ceiling)
  }

  @Test
  fun kingAtRestHasNoCeilingAndKingLossDemotes() {
    val rest = resolveRanked(3000, 0)
    assertFalse(rest.ceiling)
    assertEquals(0, rest.movement)
    val loss = resolveRanked(3050, -100)
    assertEquals(2950, loss.rp)
    assertEquals("Royal I · سلطان", rankLabel(loss.next))
    assertEquals(-1, loss.movement)
    assertFalse(loss.ceiling)
  }

  @Test
  fun negativePreviousRpIsFlooredBeforeResolution() {
    val r = resolveRanked(-50, 100)
    assertEquals(0, r.previousRP)
    assertEquals(100, r.rp)
    assertEquals(100, r.delta)
    assertEquals("Bronze III · مبتدئ", rankLabel(r.previous))
    assertEquals("Bronze II · مبتدئ", rankLabel(r.next))
    assertEquals(1, r.movement)
  }
}
