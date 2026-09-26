package com.estemshan.game.ui.chooselevel

import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.game.ui.bot.BotTablePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S12 Step 3's tests for the view model behind the Choose Level screen.
 *
 * The model layer's own contract is pinned in `BotTablePresetTest`; these pin
 * the two things only the view model can get wrong: that the screen's chairs
 * and the roster handed to `startMatch` are the *same* table (a preset
 * applied, then a roster built, must agree seat for seat), and that a chair
 * the player tuned by hand carries that tuning into the match instead of
 * silently reverting to the preset.
 *
 * The whole point of the screen is that `startMatch` receives exactly what
 * the player was shown, so every test ends on the roster.
 */
class ChooseLevelViewModelTest {

  private val vm = ChooseLevelViewModel()

  private fun opponent(seat: String) =
    vm.state.value.opponents.firstOrNull { it.seat == seat }
      ?: error("no opponent at $seat; seating is ${vm.state.value.opponents.map { it.seat }}")

  @Test
  fun theLobbyLandsOnTheDefaultPresetAndItsChairs() {
    val state = vm.state.value

    assertEquals("a fresh lobby shows the default table", BotTablePreset.DEFAULT, state.appliedPreset)
    // Three chairs, in seat order, labelled with their seat number.
    assertEquals(listOf("p2", "p3", "p4"), state.opponents.map { it.seat })
    assertEquals(listOf(2, 3, 4), state.opponents.map { it.seatNumber })
    // The Rivals, seat for seat: p2 Fox, p3 Shark, p4 Joker.
    assertEquals(BotTier.EASY, opponent("p2").tier)
    assertEquals(BotPersonality.CONSERVATIVE, opponent("p2").personality)
    assertEquals(BotTier.HARD, opponent("p3").tier)
    assertEquals(BotPersonality.AGGRESSIVE, opponent("p3").personality)
    assertEquals(BotTier.EXPERT, opponent("p4").tier)
    assertEquals(BotPersonality.TRICKSTER, opponent("p4").personality)
  }

  @Test
  fun selectingAPresetSeatsItsOpponentsAndMarksItApplied() {
    vm.onPresetSelected(BotTablePreset.Gentle)

    assertEquals(BotTablePreset.Gentle, vm.state.value.appliedPreset)
    for (seat in listOf("p2", "p3", "p4")) {
      assertEquals("Gentle seats EASY at every chair ($seat)", BotTier.EASY, opponent(seat).tier)
      assertEquals("Gentle seats BALANCED at every chair ($seat)", BotPersonality.BALANCED, opponent(seat).personality)
    }
  }

  @Test
  fun anAppliedPresetBuildsTheSameRosterThePresetItselfWould() {
    // The two construction sites — the screen's chairs and the preset's own
    // roster() — must agree, or the table shown is not the table dealt.
    for (preset in BotTablePreset.ALL) {
      vm.onPresetSelected(preset)
      assertEquals(
        "roster built from ${preset.name} differs from the preset's own",
        preset.roster(vm.human, vm.seats),
        vm.roster(),
      )
    }
  }

  @Test
  fun tuningOneChairLeavesThePresetsAndKeepsTheRestSeated() {
    vm.onPresetSelected(BotTablePreset.DEFAULT)
    vm.onTierSelected(opponent("p3"), BotTier.EASY)

    assertNull("a hand-tuned chair is Custom, not the preset", vm.state.value.appliedPreset)
    // The tuned chair carries the new tier and keeps its personality.
    assertEquals(BotTier.EASY, opponent("p3").tier)
    assertEquals(BotPersonality.AGGRESSIVE, opponent("p3").personality)
    // Every other chair is untouched.
    assertEquals(BotTier.EASY, opponent("p2").tier)
    assertEquals(BotTier.EXPERT, opponent("p4").tier)
  }

  @Test
  fun tuningAStyleAlsoLeavesThePresets() {
    vm.onPresetSelected(BotTablePreset.SharkTank)
    vm.onPersonalitySelected(opponent("p2"), BotPersonality.TRICKSTER)

    assertNull(vm.state.value.appliedPreset)
    assertEquals(BotPersonality.TRICKSTER, opponent("p2").personality)
    assertEquals("the tier is untouched by a style edit", BotTier.HARD, opponent("p2").tier)
  }

  @Test
  fun aTunedChairCarriesItsTuningIntoTheMatch() {
    vm.onPresetSelected(BotTablePreset.Gentle)
    vm.onTierSelected(opponent("p4"), BotTier.EXPERT)
    vm.onPersonalitySelected(opponent("p4"), BotPersonality.AGGRESSIVE)

    val roster = vm.roster()
    assertFalse("the human's chair is never a bot", roster.isBot(vm.human))
    assertEquals("three opponents, no more", 3, roster.botSeats.size)
    // The tuned chair shows up exactly as edited...
    assertEquals(BotTier.EXPERT, roster.config("p4")?.tier)
    assertEquals(BotPersonality.AGGRESSIVE, roster.config("p4")?.personality)
    // ...and the untouched chairs keep the preset.
    assertEquals(BotTier.EASY, roster.config("p2")?.tier)
    assertEquals(BotTier.EASY, roster.config("p3")?.tier)
  }

  @Test
  fun theDefaultRosterSeatsTheRivalsAndLeavesTheHumanAlone() {
    val roster = vm.roster()

    assertFalse(roster.isBot("p1"))
    assertTrue("p2/p3/p4 are all bots", roster.botSeats == setOf("p2", "p3", "p4"))
    assertEquals(BotTier.EASY, roster.config("p2")?.tier)
    assertEquals(BotPersonality.CONSERVATIVE, roster.config("p2")?.personality)
    assertEquals(BotTier.HARD, roster.config("p3")?.tier)
    assertEquals(BotPersonality.AGGRESSIVE, roster.config("p3")?.personality)
    assertEquals(BotTier.EXPERT, roster.config("p4")?.tier)
    assertEquals(BotPersonality.TRICKSTER, roster.config("p4")?.personality)
  }

  @Test
  fun reselectingAPresetAfterTuningRestoresTheAppliedTable() {
    vm.onPresetSelected(BotTablePreset.SharkTank)
    vm.onTierSelected(opponent("p2"), BotTier.EASY)
    assertNull("midway through, the table is Custom", vm.state.value.appliedPreset)

    vm.onPresetSelected(BotTablePreset.SharkTank)

    assertEquals(BotTablePreset.SharkTank, vm.state.value.appliedPreset)
    assertEquals(
      "reselecting rebuilds the preset's roster, tuning and all",
      BotTablePreset.SharkTank.roster(vm.human, vm.seats),
      vm.roster(),
    )
  }

  @Test
  fun seatingFollowsTheHumanChairNotAHardcodedSeat() {
    // The chairs are the seats minus the human's, so a table whose human sits
    // elsewhere still seats three opponents against them.
    val moved = opponentsFrom(BotTablePreset.FoxSharkJoker, DEFAULT_SEATS, "p3")

    assertEquals(listOf("p1", "p2", "p4"), moved.map { it.seat })
    // The Rivals in seat order over the remaining chairs — the same order
    // BotTablePreset.roster uses, so this matches the table that would deal.
    assertEquals(BotTier.EASY, moved[0].tier)   // p1 takes the Fox
    assertEquals(BotTier.HARD, moved[1].tier)   // p2 takes the Shark
    assertEquals(BotTier.EXPERT, moved[2].tier) // p4 takes the Joker
  }
}
