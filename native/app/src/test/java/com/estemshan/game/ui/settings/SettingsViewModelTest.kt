package com.estemshan.game.ui.settings

import com.estemshan.game.ui.profile.avatarLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSettingsStore(var sound: Boolean = true) : SettingsStore {
  var writes = 0
  override var soundEnabled: Boolean
    get() = sound
    set(value) {
      sound = value
      writes++
    }
}

class SettingsViewModelTest {

  @Test
  fun defaultSoundIsOn() {
    val vm = SettingsViewModel(FakeSettingsStore(true))
    assertTrue(vm.soundEnabled.value)
  }

  @Test
  fun togglePersistsThroughStore() {
    val store = FakeSettingsStore(true)
    val vm = SettingsViewModel(store)
    vm.setSoundEnabled(false)
    assertEquals(false, vm.soundEnabled.value)
    assertEquals(1, store.writes)
    // A new VM over the same store restores the persisted value.
    val vm2 = SettingsViewModel(store)
    assertEquals(false, vm2.soundEnabled.value)
  }

  @Test
  fun avatarUsesFirstTwoAlphanumerics() {
    assertEquals("AB", avatarLetters("abc123"))
    assertEquals("12", avatarLetters("12"))
    assertEquals("AA", avatarLetters("a"))
    assertEquals("E?", avatarLetters("!!!"))
  }

  @Test
  fun shortUidTruncatesLongIds() {
    assertEquals("abc123", shortUid("abc123"))
    assertEquals("abc123…c789", shortUid("abc123xxxc789"))
  }
}
