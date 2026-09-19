package com.estemshan.game.ui.settings

import android.content.Context

/**
 * Persisted app settings. Interface seam so unit tests run on plain JVM;
 * the real implementation is 20 lines of SharedPreferences — no new
 * dependencies. Language selection is deliberately OUT of v1 (it needs a
 * strings infrastructure first); sound + identity are real today.
 */
interface SettingsStore {
  var soundEnabled: Boolean
}

class PrefsSettingsStore(context: Context) : SettingsStore {
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  override var soundEnabled: Boolean
    get() = prefs.getBoolean(KEY_SOUND, true)
    set(value) {
      prefs.edit().putBoolean(KEY_SOUND, value).apply()
    }

  companion object {
    const val PREFS = "estemshan_settings"
    const val KEY_SOUND = "sound_enabled"
  }
}
