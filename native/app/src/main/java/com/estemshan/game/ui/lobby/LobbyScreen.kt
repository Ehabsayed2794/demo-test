package com.estemshan.game.ui.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.game.R
import com.estemshan.game.ui.theme.EstemshanTheme

/**
 * S15 — the Real Lobby. Stateless: it renders [LobbyUiState] and forwards
 * every intent to [LobbyViewModel], so the room code and the last failure
 * each have one owner (see ChooseLevelScreen for the same split).
 *
 * The online surface is create + join by code, each through a NATIVE dialog —
 * spec 01-screens.md §1 forbids the web Lobby's `prompt()`. There is no room
 * list anywhere on purpose: native v1 rooms are invite-only, so a public
 * roster would be a list of rooms nobody can join. The waiting room,
 * participants, and ready-up are the Room screen (S16) and are not here.
 *
 * The offline entries stay first and unchanged — quick match is the path that
 * already ships, and a room that takes a moment to reach Firestore must not
 * bury the thing a solo player came for.
 */
@Composable
fun LobbyScreen(
  state: LobbyUiState,
  uid: String?,
  onCreateRoom: () -> Unit,
  onJoinRoom: (String) -> Unit,
  onLeaveRoom: () -> Unit,
  onDismissCreatedCode: () -> Unit,
  onClearJoinError: () -> Unit,
  onQuickMatch: () -> Unit,
  onPlayVsAi: () -> Unit,
  onResumeMatch: () -> Unit,
  onProfile: () -> Unit,
  onSettings: () -> Unit,
  onSignOut: () -> Unit,
) {
  // The join dialog's own visibility — ephemeral UI state, so it lives here
  // rather than in the view model. A successful join sets a room code, which
  // is the signal to close it.
  var showJoin by remember { mutableStateOf(false) }
  LaunchedEffect(state.roomCode) {
    if (state.roomCode != null) showJoin = false
  }

  LazyColumn(
    modifier = Modifier.fillMaxWidth(),
    contentPadding = PaddingValues(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      Text(
        stringResource(R.string.lobby_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )
    }
    item {
      Text(
        uid?.let { stringResource(R.string.lobby_signed_in_as, it) } ?: "",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (state.roomCode != null) {
      item { SeatedCard(state.roomCode, state.busy, onLeaveRoom) }
    }

    // The reconnect entry, with its own section: a live match is not offline
    // play, and sitting it under "Offline" would say the opposite. First in
    // the list because it is the thing the player came back for.
    if (state.resumableMatchId != null) {
      item { SectionLabel(stringResource(R.string.lobby_section_resume)) }
      item {
        OutlinedButton(
          onClick = onResumeMatch,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.lobby_resume_match)) }
      }
      item { HintText(stringResource(R.string.lobby_resume_match_hint)) }
    }

    item { SectionLabel(stringResource(R.string.lobby_section_play)) }
    item {
      Button(
        onClick = onQuickMatch,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      ) { Text(stringResource(R.string.lobby_quick_match)) }
    }
    item {
      OutlinedButton(
        onClick = onPlayVsAi,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      ) { Text(stringResource(R.string.lobby_play_vs_ai)) }
    }

    item { SectionLabel(stringResource(R.string.lobby_section_rooms)) }
    item { HintText(stringResource(R.string.lobby_create_room_hint)) }
    item {
      Button(
        onClick = onCreateRoom,
        enabled = !state.busy && uid != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      ) { Text(stringResource(R.string.lobby_create_room)) }
    }
    item { HintText(stringResource(R.string.lobby_join_room_hint)) }
    item {
      OutlinedButton(
        onClick = { showJoin = true },
        enabled = !state.busy && uid != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      ) { Text(stringResource(R.string.lobby_join_room)) }
    }

    item {
      OutlinedButton(
        onClick = onProfile,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
      ) { Text(stringResource(R.string.lobby_profile)) }
    }
    item {
      OutlinedButton(
        onClick = onSettings,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
      ) { Text(stringResource(R.string.lobby_settings)) }
    }
    item {
      OutlinedButton(
        onClick = onSignOut,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
      ) { Text(stringResource(R.string.lobby_sign_out)) }
    }
  }

  if (state.createdCode != null) {
    CreatedRoomDialog(state.createdCode, state.busy, onDismissCreatedCode)
  }
  if (showJoin) {
    JoinRoomDialog(
      busy = state.busy,
      error = state.joinError,
      onJoin = onJoinRoom,
      onDismiss = {
        showJoin = false
        onClearJoinError()
      },
    )
  }
}

/**
 * The room you are currently seated at, with the one action it admits:
 * leave. Ready-up and starting land with the Room screen (S16). [code] is
 * non-null because the screen only composes this when [LobbyUiState.roomCode]
 * is set; taking it as a parameter keeps the null check at the one call site
 * instead of re-asserting it here.
 */
@Composable
private fun SeatedCard(code: String, busy: Boolean, onLeaveRoom: () -> Unit) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp)) {
      Text(
        stringResource(R.string.lobby_in_room, code),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(Modifier.height(12.dp))
      OutlinedButton(
        onClick = onLeaveRoom,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
      ) { Text(stringResource(R.string.lobby_leave_room)) }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun HintText(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * The room code from [LobbyViewModel.createRoom], big enough to read across
 * a table. Dismissing keeps the seat — the player is in the room either way;
 * this only stops showing the number.
 */
@Composable
private fun CreatedRoomDialog(code: String, busy: Boolean, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    confirmButton = {
      TextButton(onClick = onDismiss, enabled = !busy) {
        Text(stringResource(R.string.lobby_created_done))
      }
    },
    title = { Text(stringResource(R.string.lobby_created_title)) },
    text = {
      Column {
        Text(
          stringResource(R.string.lobby_created_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
          code,
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.primary,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
  )
}

/**
 * Join-by-code. The field uppercases as the player types — the alphabet is
 * uppercase and [normalizeRoomCode] would anyway, so a phone keyboard never
 * fights the player over case. A failed join keeps the dialog open and shows
 * the reason; dismissing it clears the stale error so reopening starts clean.
 */
@Composable
private fun JoinRoomDialog(
  busy: Boolean,
  error: LobbyJoinError?,
  onJoin: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var code by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    confirmButton = {
      TextButton(onClick = { onJoin(code) }, enabled = !busy) {
        if (busy) {
          CircularProgressIndicator(modifier = Modifier.height(16.dp))
        } else {
          Text(stringResource(R.string.lobby_join_dialog_join))
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !busy) {
        Text(stringResource(R.string.lobby_cancel))
      }
    },
    title = { Text(stringResource(R.string.lobby_join_dialog_title)) },
    text = {
      Column {
        OutlinedTextField(
          value = code,
          onValueChange = { code = it.uppercase() },
          label = { Text(stringResource(R.string.lobby_join_dialog_hint)) },
          singleLine = true,
          isError = error != null,
          enabled = !busy,
        )
        if (error != null) {
          Spacer(Modifier.height(8.dp))
          Text(
            error.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
  )
}

@Composable
private fun LobbyJoinError.label(): String = when (this) {
  LobbyJoinError.EMPTY -> stringResource(R.string.lobby_join_error_empty)
  LobbyJoinError.NOT_FOUND -> stringResource(R.string.lobby_join_error_not_found)
  LobbyJoinError.FULL -> stringResource(R.string.lobby_join_error_full)
  LobbyJoinError.CLOSED -> stringResource(R.string.lobby_join_error_closed)
  LobbyJoinError.GENERIC -> stringResource(R.string.lobby_join_error_generic)
}

// ── Previews: one per state the screen can hold ────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun LobbyDefaultPreview() {
  EstemshanTheme {
    LobbyScreen(
      state = LobbyUiState(),
      uid = "uid-preview-0001",
      onCreateRoom = {},
      onJoinRoom = {},
      onLeaveRoom = {},
      onDismissCreatedCode = {},
      onClearJoinError = {},
      onQuickMatch = {},
      onPlayVsAi = {},
      onResumeMatch = {},
      onProfile = {},
      onSettings = {},
      onSignOut = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun LobbySeatedPreview() {
  EstemshanTheme {
    LobbyScreen(
      state = LobbyUiState(roomCode = "X7K2PQ"),
      uid = "uid-preview-0001",
      onCreateRoom = {},
      onJoinRoom = {},
      onLeaveRoom = {},
      onDismissCreatedCode = {},
      onClearJoinError = {},
      onQuickMatch = {},
      onPlayVsAi = {},
      onResumeMatch = {},
      onProfile = {},
      onSettings = {},
      onSignOut = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun LobbyCreatedPreview() {
  EstemshanTheme {
    LobbyScreen(
      state = LobbyUiState(roomCode = "X7K2PQ", createdCode = "X7K2PQ"),
      uid = "uid-preview-0001",
      onCreateRoom = {},
      onJoinRoom = {},
      onLeaveRoom = {},
      onDismissCreatedCode = {},
      onClearJoinError = {},
      onQuickMatch = {},
      onPlayVsAi = {},
      onResumeMatch = {},
      onProfile = {},
      onSettings = {},
      onSignOut = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun LobbyJoinErrorPreview() {
  // The dialog is screen-local state, so the preview drives it through the
  // same JoinRoomDialog the screen composes, at the state that fails.
  EstemshanTheme {
    JoinRoomDialog(busy = false, error = LobbyJoinError.NOT_FOUND, onJoin = {}, onDismiss = {})
  }
}
