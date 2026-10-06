package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GM1-GM3/GM8 pinning for the shared [GameType] model. The FULL block below
 * is the golden regression the plan calls "the safety net": if any of it
 * fails, FULL changed and every existing match is affected. The MINI block
 * proves the model is MINI with two numbers changed and nothing else.
 *
 * See docs/NATIVE_V1_PLAN_AND_ESTIMATE.md "Test plan" (`:engine`).
 */
class GameTypeTest {

  // ── FULL constants: exactly the pre-GameType numbers (GM2) ────────────

  @Test
  fun full_constantsMatchTheLegacyHardcodes() {
    assertEquals(18, GameType.FULL.baseRounds)
    assertEquals(14, GameType.FULL.firstFastRound)
    assertEquals(5, GameType.FULL.maxExtensions)
  }

  @Test
  fun mini_constantsAreTheTwoChangedNumbers() {
    assertEquals(10, GameType.MINI.baseRounds)
    assertEquals(6, GameType.MINI.firstFastRound)
    // GM3: exactly ONE extension (10 → 11 maximum).
    assertEquals(1, GameType.MINI.maxExtensions)
  }

  // ── FULL golden regression: isFastRound reduces to `round >= 14` ──────

  @Test
  fun full_isFastRoundMatchesTheLegacyThreshold() {
    // The exact pair BiddingTest predicatesAndHelpers asserted pre-GameType.
    assertTrue(GameType.FULL.isFastRound(14))
    assertFalse(GameType.FULL.isFastRound(13))
    // Boundary both ways, plus the extension rounds and one past the ladder.
    assertFalse(GameType.FULL.isFastRound(1))
    assertFalse(GameType.FULL.isFastRound(13))
    assertTrue(GameType.FULL.isFastRound(14))
    assertTrue(GameType.FULL.isFastRound(18))
    assertTrue(GameType.FULL.isFastRound(19))
    assertTrue(GameType.FULL.isFastRound(23))
  }

  @Test
  fun full_isExtensionRoundMatchesTheLegacy14to18Window() {
    // RoundScore.kt's old guard was `round < 14 || round > 18 → false`.
    assertFalse(GameType.FULL.isExtensionRound(13))
    for (round in 14..18) {
      assertTrue("round $round should be in FULL's extension window", GameType.FULL.isExtensionRound(round))
    }
    assertFalse(GameType.FULL.isExtensionRound(19))
    assertFalse(GameType.FULL.isExtensionRound(23))
  }

  // ── FULL golden regression: the trump ladder 14-23, unchanged ─────────

  @Test
  fun full_fixedTrumpForReproducesTheLegacyLadder() {
    // Every assertion from BiddingTest predicatesAndHelpers, verbatim.
    assertEquals(Suit.SANS, GameType.FULL.fixedTrumpFor(14))
    assertEquals(Suit.SPADES, GameType.FULL.fixedTrumpFor(15))
    assertEquals(Suit.HEARTS, GameType.FULL.fixedTrumpFor(16))
    assertEquals(Suit.DIAMONDS, GameType.FULL.fixedTrumpFor(17))
    assertEquals(Suit.CLUBS, GameType.FULL.fixedTrumpFor(18))
    assertEquals(Suit.SANS, GameType.FULL.fixedTrumpFor(19))
    // The plan's golden range runs to 23 — the ladder repeats with floorMod.
    assertEquals(Suit.SPADES, GameType.FULL.fixedTrumpFor(20))
    assertEquals(Suit.HEARTS, GameType.FULL.fixedTrumpFor(21))
    assertEquals(Suit.DIAMONDS, GameType.FULL.fixedTrumpFor(22))
    assertEquals(Suit.CLUBS, GameType.FULL.fixedTrumpFor(23))
  }

  // ── The parameterized legacy entry points still default to FULL ───────

  @Test
  fun legacyEntryPointsDefaultToFull() {
    assertEquals(GameType.FULL.isFastRound(14), isFastRound(14))
    assertEquals(GameType.FULL.isFastRound(13), isFastRound(13))
    assertEquals(GameType.FULL.fixedTrumpFor(17), fixedTrumpFor(17))
    // And an explicit MINI reaches the MINI ladder through the same entry.
    assertEquals(Suit.SANS, fixedTrumpFor(6, GameType.MINI))
    assertTrue(isFastRound(6, GameType.MINI))
  }

  // ── MINI ladder (GM1): 6→Sans … 11→Sans, the one extension repeating ──

  @Test
  fun mini_fixedTrumpForWalksTheSameLadderFromSix() {
    assertEquals(Suit.SANS, GameType.MINI.fixedTrumpFor(6))
    assertEquals(Suit.SPADES, GameType.MINI.fixedTrumpFor(7))
    assertEquals(Suit.HEARTS, GameType.MINI.fixedTrumpFor(8))
    assertEquals(Suit.DIAMONDS, GameType.MINI.fixedTrumpFor(9))
    assertEquals(Suit.CLUBS, GameType.MINI.fixedTrumpFor(10))
    // Round 11 is MINI's single extension; the ladder repeats from the top.
    assertEquals(Suit.SANS, GameType.MINI.fixedTrumpFor(11))
  }

  @Test
  fun mini_isFastRoundStartsAtSix() {
    assertFalse(GameType.MINI.isFastRound(5))
    assertTrue(GameType.MINI.isFastRound(6))
    assertTrue(GameType.MINI.isFastRound(10))
    assertTrue(GameType.MINI.isFastRound(11))
  }

  @Test
  fun mini_isExtensionRoundIsSixToTen() {
    assertFalse(GameType.MINI.isExtensionRound(5))
    for (round in 6..10) {
      assertTrue("round $round should be in MINI's extension window", GameType.MINI.isExtensionRound(round))
    }
    // 11 is the ONE extension; it is past the window and cannot extend again.
    assertFalse(GameType.MINI.isExtensionRound(11))
  }

  // ── The crash, pinned (audit point 4) ──────────────────────────────────

  @Test
  fun miniRoundSixCrashesThroughTheOldIndexingButNotThroughGameType() {
    // The pre-GameType formula, reproduced exactly: Kotlin's % keeps the
    // dividend's sign, so (6 - 14) % 5 == -3 and FIXED_SUITS[-3] throws.
    val legacySuits = listOf(Suit.SANS, Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)
    assertThrows(IndexOutOfBoundsException::class.java) {
      @Suppress("UNUSED_EXPRESSION")
      legacySuits[(MINI_ROUND_SIX - LEGACY_LADDER_START) % legacySuits.size]
    }
    // The same round through the GameType model returns Sans, not a crash.
    assertEquals(Suit.SANS, GameType.MINI.fixedTrumpFor(MINI_ROUND_SIX))
  }

  // ── Extension window + caps (GM2/GM3) ─────────────────────────────────

  @Test
  fun full_computeRoundExtensionKeepsTheLegacyWindowAndReasons() {
    // The pre-GameType RoundScoreTest roundExtensionWindow cases, unaltered.
    assertEquals(
      RoundExtension(true, ExtensionReason.SUPER_CALL),
      computeRoundExtension(15, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(13, "p1", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(19, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(16, null, Suit.HEARTS, callerSucceeded = false, isSaayda = true),
    )
  }

  @Test
  fun mini_computeRoundExtensionQualifiesInsideSixToTen() {
    // A Super Call inside MINI's window extends. The override must differ
    // from MINI's own fixed trump for the round: 8's fixed trump is HEARTS
    // (ladder 6→Sans, 7→Spades, 8→Hearts), so SPADES is the override here.
    assertEquals(
      RoundExtension(true, ExtensionReason.SUPER_CALL),
      computeRoundExtension(8, "p3", Suit.SPADES, callerSucceeded = true, isSaayda = false, gameType = GameType.MINI),
    )
    // Sa'ayda inside the window extends, same reason as FULL.
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(7, null, Suit.HEARTS, callerSucceeded = false, isSaayda = true, gameType = GameType.MINI),
    )
    // The forced trump standing (round 8 = HEARTS) means no Super override.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(8, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false, gameType = GameType.MINI),
    )
  }

  @Test
  fun mini_computeRoundExtensionNeverFiresOutsideItsWindow() {
    // Round 5 is a normal MINI round — no extension even on a Super Call.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(5, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false, gameType = GameType.MINI),
    )
    // Round 11 is MINI's one extension already consumed: no further extension.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(11, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false, gameType = GameType.MINI),
    )
    // The FULL path is untouched when FULL is passed explicitly.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(11, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
  }

  @Test
  fun mini_extensionWindowIsNarrowerButTheCapIsEnforcedAboveThisLayer() {
    // The engine answers "does THIS round qualify"; the count cap
    // (MINI 1, FULL 5) is a MatchService guard (S66), not an engine one.
    // The constants it will read are pinned here so the contract is explicit.
    assertEquals(1, GameType.MINI.maxExtensions)
    assertEquals(5, GameType.FULL.maxExtensions)
    // FULL's window still yields exactly the 5 rounds the cap allows.
    val fullWindow = (1..30).filter { GameType.FULL.isExtensionRound(it) }
    assertEquals(listOf(14, 15, 16, 17, 18), fullWindow)
    val miniWindow = (1..30).filter { GameType.MINI.isExtensionRound(it) }
    assertEquals(listOf(6, 7, 8, 9, 10), miniWindow)
  }

  private companion object {
    private const val MINI_ROUND_SIX = 6
    private const val LEGACY_LADDER_START = 14
  }
}
