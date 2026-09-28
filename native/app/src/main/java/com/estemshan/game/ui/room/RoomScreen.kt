package com.estemshan.game.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.game.R
import com.estemshan.game.ui.theme.EstemshanTheme
import com.estemshan.services.model.RoomDoc
import kotlinx.coroutines.delay

/**
 * S16 — the Room/waiting screen. Stateless: it renders [RoomUiState] and
 * forwards every intent to [RoomViewModel], so the roster, the code, and the
 * busy flag each have one owner (see LobbyScreen for the same split).
 *
 * There is no room list and no "Start Match" button. Rooms are invite-only,
 * and the match start is not a separate action — RoomService fires it the
 * instant every seat is ready and the acting uid is the creator, so the
 * host's [room_toggle_ready] is the start. A non-host's identical toggle sets
 * their own ready bit; the screen sees the match land on the next poll tick.
 *
 * The roster renders seats in [RoomDoc.players] order, so the host is first
 * and the join order reads top to bottom — the same order the room doc is
 * shared in. An empty seat is not fabricated: the room is invite-only, so
 * the honest rendering is the players who are actually here.
 */
@Composable
fun RoomScreen(
  state: RoomUiState,
  uid: String?,
  onRefresh: () -> Unit,
  onToggleReady: () -> Unit,
  onLeave: () -> Unit,
) {
  val room = state.room

  // The poll. Keyed on the code so it dies the moment the room clears —
  // [RoomViewModel.leave] sets roomCode null and this effect cancels, so a
  // cleared room cannot be loaded back in by a late tick. Matches the web
  // lobby's 4000ms interval and, like it, stops once a match has landed.
  // Opening the room is NOT here: the code arrives as a nav arg, so AppNav
  // seeds [RoomViewModel.open] — this effect only runs once that has set
  // roomCode.
  LaunchedEffect(state.roomCode, state.room?.matchId) {
    if (state.roomCode == null) return@LaunchedEffect
    if (room?.matchId != null) return@LaunchedEffect
    while (true) {
      delay(RoomViewModel.POLL_INTERVAL_MS)
      onRefresh()
    }
  }

  LazyColumn(
    modifier = Modifier.fillMaxWidth(),
    contentPadding = PaddingValues(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      Text(
        stringResource(R.string.room_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )
    }
    if (room == null) {
      // roomCode set but no room yet = the first load is in flight; roomCode
      // null too = [RoomViewModel.leave] cleared the state or the room is
      // gone. Only the second is actually "gone" — showing that during a load
      // would flicker the wrong message on every open.
      if (state.roomCode == null) {
        item {
          Text(
            stringResource(R.string.room_gone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      } else {
        item {
          CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(32.dp),
          )
        }
      }
    } else {
      item { CodeCard(state.roomCode.orEmpty()) }
      item { SectionLabel(stringResource(R.string.room_section_players)) }
      items(room.players, key = { it }) { player ->
        PlayerRow(
          playerId = player,
          isReady = room.isReady(player),
          isHost = room.creator == player,
          isYou = uid != null && uid == player,
        )
      }
      item { Spacer(Modifier.height(4.dp)) }
      item {
        if (room.allReady) {
          Text(
            stringResource(R.string.room_starting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          Text(
            stringResource(R.string.room_waiting_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
      item {
        val youReady = uid != null && room.isReady(uid)
        Button(
          onClick = onToggleReady,
          enabled = !state.busy && uid != null && uid in room.players,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
          Text(
            if (youReady) stringResource(R.string.room_toggle_not_ready)
            else stringResource(R.string.room_toggle_ready),
          )
        }
      }
      item {
        OutlinedButton(
          onClick = onLeave,
          enabled = !state.busy,
          modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) { Text(stringResource(R.string.room_leave)) }
      }
    }
  }
}

/**
 * The code, big enough to read across a table. [code] is non-null whenever
 * the screen composes this — [RoomUiState.roomCode] is only null after a
 * leave clears the whole state, and that path renders [room_gone] instead.
 */
@Composable
private fun CodeCard(code: String) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp)) {
      Text(
        stringResource(R.string.room_code, code),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/**
 * One seat: who it is, whether they are ready, and whether they own the
 * room. The badge set is derived from the room doc rather than carried, so a
 * poll tick re-renders the row from the same source the toggle wrote to.
 */
@Composable
private fun PlayerRow(
  playerId: String,
  isReady: Boolean,
  isHost: Boolean,
  isYou: Boolean,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          playerId,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        if (isHost) {
          Spacer(Modifier.width(8.dp))
          Badge(stringResource(R.string.room_host), prominent = true)
        }
        if (isYou) {
          Spacer(Modifier.width(8.dp))
          Badge(stringResource(R.string.room_you))
        }
      }
      Text(
        if (isReady) stringResource(R.string.room_ready)
        else stringResource(R.string.room_not_ready),
        style = MaterialTheme.typography.labelLarge,
        color = if (isReady) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * One small label after a player's id. [prominent] is the host — the room's
 * owner, the only seat that can start the match — so it takes the gold;
 * "You" is just orientation and stays dim.
 */
@Composable
private fun Badge(text: String, prominent: Boolean = false) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = if (prominent) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant,
  )
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

// ── Previews: one per state the screen can hold ────────────────────────────

/** The no-op wiring a preview needs: the poll effect ticks against nothing. */
private val NoOpRefresh: () -> Unit = {}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun RoomLoadingPreview() {
  // Before the first loadRoom lands: no room yet, no roster.
  EstemshanTheme {
    RoomScreen(
      state = RoomUiState(roomCode = "X7K2PQ"),
      uid = "uid-host",
      onRefresh = NoOpRefresh,
      onToggleReady = {},
      onLeave = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun RoomNotReadyPreview() {
  EstemshanTheme {
    RoomScreen(
      state = RoomUiState(
        roomCode = "X7K2PQ",
        room = RoomDoc(
          name = null,
          status = "waiting",
          creator = "uid-host",
          players = listOf("uid-host", "uid-friend"),
          readyPlayers = listOf("uid-friend"),
          matchId = null,
        ),
      ),
      uid = "uid-host",
      onRefresh = NoOpRefresh,
      onToggleReady = {},
      onLeave = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun RoomAllReadyPreview() {
  // Every seat ready — the host's next toggle starts the match.
  EstemshanTheme {
    RoomScreen(
      state = RoomUiState(
        roomCode = "X7K2PQ",
        room = RoomDoc(
          name = null,
          status = "waiting",
          creator = "uid-host",
          players = listOf("uid-host", "uid-friend"),
          readyPlayers = listOf("uid-host", "uid-friend"),
          matchId = null,
        ),
      ),
      uid = "uid-host",
      onRefresh = NoOpRefresh,
      onToggleReady = {},
      onLeave = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun RoomGonePreview() {
  // The room was closed (last player out) or the code never resolved.
  EstemshanTheme {
    RoomScreen(
      state = RoomUiState(),
      uid = "uid-host",
      onRefresh = NoOpRefresh,
      onToggleReady = {},
      onLeave = {},
    )
  }
}
