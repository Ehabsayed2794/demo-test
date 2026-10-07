package com.estemshan.game.ui.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S27 — SoundManager's decision logic: which clip each hook plays, and
 * whether anything plays at all. The player is a fake (no AudioTrack on
 * JVM); the flag is a live lambda like production's store-backed one.
 *
 * What is NOT tested here: the SoundPool wiring itself and the composable
 * call sites — those need a device. The mapping below is the contract the
 * call sites rely on: each hook plays exactly its clip, off means silence,
 * and the match win fires once at the ceiling, never per round.
 */
class SoundManagerTest {

  private class FakePlayer : ClipPlayer {
    val played = mutableListOf<SfxClip>()
    override fun play(clip: SfxClip) {
      played += clip
    }
  }

  private class Fixture(enabled: Boolean = true) {
    val player = FakePlayer()
    var enabled = enabled
    val sounds = SoundManager(player, ::enabled)
  }

  @Test
  fun toggleOffMeansNothingIsEverPlayed() {
    val f = Fixture(enabled = false)
    f.sounds.cardPlaced()
    f.sounds.trickWon()
    f.sounds.bidSubmitted()
    f.sounds.roundScored()
    f.sounds.roundScoredOrMatchWon(round = 18, maxRounds = 18)
    f.sounds.tapped()
    assertTrue("off must silence every hook", f.player.played.isEmpty())
  }

  @Test
  fun eachHookMapsToExactlyItsClip() {
    val f = Fixture()
    f.sounds.cardPlaced()
    f.sounds.trickWon()
    f.sounds.bidSubmitted()
    f.sounds.roundScored()
    f.sounds.tapped()
    assertEquals(
      listOf(
        SfxClip.CARD_PLACE,
        SfxClip.TRICK_WIN,
        SfxClip.TAP,
        SfxClip.ROUND_SCORE,
        SfxClip.TAP,
      ),
      f.player.played,
    )
  }

  @Test
  fun matchWinFiresOnceAtTheFinalStandingsNotPerRound() {
    val f = Fixture()
    // Standings arrive every round — every round chimes, only the ceiling
    // fans out.
    for (round in 1..18) f.sounds.roundScoredOrMatchWon(round, 18)
    assertEquals(
      "17 chimes then one fanfare, in order",
      List(17) { SfxClip.ROUND_SCORE } + SfxClip.MATCH_WIN,
      f.player.played,
    )
  }

  @Test
  fun matchWinRespectsMiniCeiling() {
    val f = Fixture()
    f.sounds.roundScoredOrMatchWon(round = 9, maxRounds = 10)
    assertEquals(listOf(SfxClip.ROUND_SCORE), f.player.played)
    f.sounds.roundScoredOrMatchWon(round = 10, maxRounds = 10)
    assertEquals(listOf(SfxClip.ROUND_SCORE, SfxClip.MATCH_WIN), f.player.played)
  }

  @Test
  fun round10OfFullChimesItIsNotTheMatchEnd() {
    val f = Fixture()
    f.sounds.roundScoredOrMatchWon(round = 10, maxRounds = 18)
    assertEquals(listOf(SfxClip.ROUND_SCORE), f.player.played)
  }

  @Test
  fun theFinalRoundIsTheFanfareAloneNeverChimePlusFanfare() {
    val f = Fixture()
    f.sounds.roundScoredOrMatchWon(round = 18, maxRounds = 18)
    assertEquals("the ceiling is the fanfare only", listOf(SfxClip.MATCH_WIN), f.player.played)
  }

  @Test
  fun flippingTheToggleTakesEffectWithoutRestart() {
    val f = Fixture(enabled = true)
    f.sounds.tapped()
    assertEquals(1, f.player.played.size)
    // The manager re-reads the flag per play — no rebuild needed.
    f.enabled = false
    f.sounds.tapped()
    assertEquals("a live off-switch must silence immediately", 1, f.player.played.size)
    f.enabled = true
    f.sounds.tapped()
    assertEquals(2, f.player.played.size)
  }
}
