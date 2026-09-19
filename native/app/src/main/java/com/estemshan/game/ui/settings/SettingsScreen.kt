package com.estemshan.game.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.estemshan.game.BuildConfig
import com.estemshan.game.ui.theme.EstemshanTheme

/**
 * Minimal settings (spec 01): account, sound, logout. Stateless except
 * for the injected VM — every row shows real state.
 */
@Composable
fun SettingsRoute(
  uid: String,
  versionName: String = BuildConfig.VERSION_NAME,
  onSignOut: () -> Unit,
) {
  val context = LocalContext.current
  val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context))
  val sound by vm.soundEnabled.collectAsStateWithLifecycle()
  SettingsScreen(
    uid = uid,
    soundEnabled = sound,
    versionName = versionName,
    onSoundChange = vm::setSoundEnabled,
    onSignOut = onSignOut,
  )
}

@Composable
fun SettingsScreen(
  uid: String,
  soundEnabled: Boolean,
  versionName: String,
  onSoundChange: (Boolean) -> Unit,
  onSignOut: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Settings", style = MaterialTheme.typography.headlineMedium)

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        Text(shortUid(uid), style = MaterialTheme.typography.labelLarge)
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text("Sound", style = MaterialTheme.typography.titleMedium)
        Text(
          if (soundEnabled) "On" else "Off",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      Switch(checked = soundEnabled, onCheckedChange = onSoundChange)
    }

    Text("Version $versionName", style = MaterialTheme.typography.bodyMedium)

    Spacer(Modifier.height(8.dp))
    Button(
      onClick = onSignOut,
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text("Log out") }
  }
}

fun shortUid(uid: String): String =
  if (uid.length <= 12) uid else uid.take(6) + "…" + uid.takeLast(4)

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun SettingsPreview() {
  EstemshanTheme {
    SettingsScreen(
      uid = "abc123def456ghi789",
      soundEnabled = true,
      versionName = "0.1.0",
      onSoundChange = {},
      onSignOut = {},
    )
  }
}
