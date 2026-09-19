package com.estemshan.game.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings state machine. Synchronous reads/writes — no coroutines, no
 * Android framework past the injected store, so every path is JVM-testable.
 */
class SettingsViewModel(private val store: SettingsStore) : ViewModel() {

  private val _soundEnabled = MutableStateFlow(store.soundEnabled)
  val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

  fun setSoundEnabled(enabled: Boolean) {
    store.soundEnabled = enabled
    _soundEnabled.value = enabled
  }

  companion object {
    fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        SettingsViewModel(PrefsSettingsStore(context.applicationContext))
      }
    }
  }
}
