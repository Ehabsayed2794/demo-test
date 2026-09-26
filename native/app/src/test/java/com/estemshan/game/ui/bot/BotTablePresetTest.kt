package com.estemshan.game.ui.bot

import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S12's tests for the ready-made tables the Choose Level screen offers.
 *
 * A preset is seating only — it decides which chair a [BotSeat] takes and
 * nothing about the engine — so these pin the *seating*, not behaviour. The
 * roster each one builds is what [com.estemshan.game.ui.quickmatch.QuickMatchViewModel.startMatch]
 * receives, and it must be intact on arrival: every opponent seated, the
 * human's own chair left empty, and the tier/personality the preset promised.
 *
 * The one structural risk worth a test is cycling: presets list three
 * opponents for a four-seat table, so a table that seated five would wrap,
 * and a bug in the modulo would mis-seat the fourth chair silently.
 */
class BotTablePresetTest {

  private val seats = DEFAULT_SEATS

  @Test
  fun theDefaultPresetSeatsTheRivalsAndLeavesTheHumanAlone() {
    val roster = BotTablePreset.FoxSharkJoker.roster("p1", seats)

    assertFalse("the human's chair was given to a bot", roster.isBot("p1"))
    // The source's SUGGESTED_TABLE, seat for seat: p2 Fox, p3 Shark, p4 Joker.
    assertEquals(BotTier.EASY, roster.config("p2")?.tier)
    assertEquals(BotPersonality.CONSERVATIVE, roster.config("p2")?.personality)
    assertEquals(BotTier.HARD, roster.config("p3")?.tier)
    assertEquals(BotPersonality.AGGRESSIVE, roster.config("p3")?.personality)
    assertEquals(BotTier.EXPERT, roster.config("p4")?.tier)
    assertEquals(BotPersonality.TRICKSTER, roster.config("p4")?.personality)
  }

  @Test
  fun theHumanChairFollowsThePlayerNotTheConstant() {
    // Seating is against whichever chair the player takes, not a hardcoded p1.
    val roster = BotTablePreset.FoxSharkJoker.roster("p3", seats)

    assertFalse("the chosen human chair is a bot", roster.isBot("p3"))
    assertTrue("a former default chair is now a bot", roster.isBot("p1"))
    assertEquals(3, roster.botSeats.size)
  }

  @Test
  fun aUniformPresetSeatsEveryOpponentIdentically() {
    val roster = BotTablePreset.SharkTank.roster("p1", seats)

    assertEquals("three opponents, no more", 3, roster.botSeats.size)
    for (seat in listOf("p2", "p3", "p4")) {
      assertEquals(BotTier.HARD, roster.config(seat)?.tier)
      assertEquals(BotPersonality.AGGRESSIVE, roster.config(seat)?.personality)
    }
  }

  @Test
  fun aPresetCyclesWhenATableSeatsMoreOpponentsThanItLists() {
    val five = listOf("p1", "p2", "p3", "p4", "p5")
    val preset = BotTablePreset.FoxSharkJoker

    val roster = preset.roster("p1", five)

    // Four opponents, three listed: the fourth chair wraps to the first entry.
    assertEquals(4, roster.botSeats.size)
    assertEquals(BotTier.EASY, roster.config("p2")?.tier)
    assertEquals(BotTier.EXPERT, roster.config("p4")?.tier)
    assertEquals(BotTier.EASY, roster.config("p5")?.tier)
  }

  @Test
  fun aPresetRefusesToSeatAHumanWhoIsNotAtTheTable() {
    assertThrows(IllegalArgumentException::class.java) {
      BotTablePreset.FoxSharkJoker.roster("p9", seats)
    }
  }

  @Test
  fun theLobbyShowsPresetsInAscendingMeannessAndDefaultsToTheRivals() {
    assertEquals("the lobby's default is the source's suggested table",
      BotTablePreset.FoxSharkJoker, BotTablePreset.DEFAULT)
    assertTrue("every shipped preset is in ALL",
      BotTablePreset.ALL.containsAll(listOf(
        BotTablePreset.Gentle, BotTablePreset.Even,
        BotTablePreset.FoxSharkJoker, BotTablePreset.SharkTank,
      )))
    // ALL is ordered gentle → even → rivals → shark tank; a reordering would
    // shuffle the lobby's difficulty ladder.
    assertEquals(BotTablePreset.Gentle, BotTablePreset.ALL.first())
    assertEquals(BotTablePreset.SharkTank, BotTablePreset.ALL.last())
  }

  @Test
  fun everyPresetHasPlayerFacingTextAndSeatsOpponents() {
    for (preset in BotTablePreset.ALL) {
      assertTrue("preset ${preset.name} has no name", preset.name.isNotBlank())
      assertTrue("preset ${preset.name} has no blurb", preset.blurb.isNotBlank())
      assertTrue("preset ${preset.name} seats no opponents", preset.bots.isNotEmpty())
      assertTrue("preset ${preset.name} must seat at least a full table's worth",
        preset.bots.size >= 3)
    }
  }
}
