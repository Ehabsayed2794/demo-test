package com.estemshan.game.ui.onlinematch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.initTable
import com.estemshan.game.R
import com.estemshan.game.ui.bidding.BiddingScreen
import com.estemshan.game.ui.standings.FinalStandingsScreen
import com.estemshan.game.ui.standings.buildStandings
import com.estemshan.game.ui.table.TableScreen
import com.estemshan.game.ui.theme.EstemshanTheme

/** The online match graph: the matchId is the graph's one argument. */
const val ONLINE_MATCH_GRAPH = "onlinematch/{matchId}"

/** The graph's single destination — one view model, one state machine. */
const val ONLINE_MATCH = "onlinematch"

/** The nav-arg key [ONLINE_MATCH_GRAPH] declares; the room hands the id over. */
const val MATCH_ID_KEY = "matchId"

/** The one way to address an online match: the graph route with [matchId] in it. */
fun onlineMatchRoute(matchId: String): String = "onlinematch/$matchId"

/**
 * The online match flow — one destination driven by [OnlineMatchViewModel]'s
 * state machine, rendering the EXISTING Bidding/Table/Standings screens
 * unchanged. Unlike the offline quick-match graph this needs no per-screen
 * destinations: there is one view model and one state, so a `when` over it is
 * the whole navigation, and no transition logic is duplicated across three
 * composables. The screens stay stateless — each receives its state and pure
 * callbacks that route straight back to the view model.
 *
 * The view model is scoped to the graph's back-stack entry, so the whole
 * flow's state (and its subscription) drops the moment the player backs out.
 */
fun NavGraphBuilder.onlineMatchGraph(onLeft: () -> Unit) {
  navigation(
    startDestination = ONLINE_MATCH,
    route = ONLINE_MATCH_GRAPH,
    arguments = listOf(navArgument(MATCH_ID_KEY) { type = NavType.StringType }),
  ) {
    composable(ONLINE_MATCH) {
      val parent = remember(it) { it.navController.getBackStackEntry(ONLINE_MATCH_GRAPH) }
      val vm: OnlineMatchViewModel = viewModel(parent)
      val matchId = parent.arguments?.getString(MATCH_ID_KEY).orEmpty()
      // Seeding from the argument (not a callback) makes the view model the
      // single owner of "which match am I in"; a recomposition is a no-op.
      LaunchedEffect(matchId) { if (matchId.isNotEmpty()) vm.start(matchId) }
      OnlineMatchScreen(vm, onLeft)
    }
  }
}

/**
 * The whole online match, as a state-driven `when`. Leaving on
 * [MatchUiState.NotInMatch] is an effect, not a composition side effect.
 */
@Composable
fun OnlineMatchScreen(vm: OnlineMatchViewModel, onLeft: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val reconnecting by vm.reconnecting.collectAsStateWithLifecycle()

  LaunchedEffect(state) {
    if (state is MatchUiState.NotInMatch) onLeft()
  }

  Box(Modifier.fillMaxSize()) {
    when (val s = state) {
      is MatchUiState.Bidding -> EstemshanTheme {
        // userSeat is always OUR seat: the screens gate their controls on
        // turn == userSeat, so passing the acting seat would let this device
        // submit for an opponent.
        BiddingScreen(
          state = s.state,
          userSeat = s.userSeat,
          rejection = s.rejection,
          forbidden = s.forbidden,
          floor = s.floor,
          onIntent = vm::submitBidding,
        )
      }
      is MatchUiState.Table -> EstemshanTheme {
        // The played seat is decided by the services layer from the signed-in
        // user, so the screen's seat argument is not forwarded.
        TableScreen(
          state = s.state,
          userSeat = s.userSeat,
          rejection = s.rejection,
          onPlay = { _, card -> vm.playCard(card) },
          onResolve = vm::resolve,
        )
      }
      is MatchUiState.RoundStandings -> EstemshanTheme { OnlineRoundStandings(s) }
      is MatchUiState.MatchComplete -> EstemshanTheme { OnlineMatchComplete(s, onLeft) }
      MatchUiState.Connecting ->
        OnlineMatchOverlay(label = stringResource(R.string.online_connecting))
      MatchUiState.NotInMatch -> OnlineMatchOverlay(
        label = stringResource(R.string.online_not_in_match),
      )
      is MatchUiState.Failed -> OnlineMatchFailed(s, onLeft)
    }
    // Fail-open: the overlay never replaces the screen under it, only marks
    // it — the local game is still playable from the last good document.
    if (reconnecting) {
      OnlineMatchOverlay(
        label = stringResource(R.string.online_reconnecting),
        body = stringResource(R.string.online_reconnecting_body),
      )
    }
  }
}

/** A scored round, while the document advances to the next one. */
@Composable
private fun OnlineRoundStandings(state: MatchUiState.RoundStandings) {
  Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) { FinalStandingsScreen(state.standings) }
    Text(
      stringResource(R.string.online_round_opening),
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

/** The terminal standings, with the one way out. */
@Composable
private fun OnlineMatchComplete(state: MatchUiState.MatchComplete, onLeft: () -> Unit) {
  Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) { FinalStandingsScreen(state.standings) }
    Button(
      onClick = onLeft,
      modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
      Text(stringResource(R.string.online_leave_match))
    }
  }
}

/**
 * The fail-open / fail-closed overlay. Full-bleed gold-on-dark, so it reads
 * as the match paused rather than an error page.
 */
@Composable
private fun OnlineMatchOverlay(label: String, body: String? = null) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(label, style = MaterialTheme.typography.headlineMedium)
    if (body != null) {
      Text(body, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun OnlineMatchFailed(state: MatchUiState.Failed, onLeft: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      stringResource(R.string.online_failed_title),
      style = MaterialTheme.typography.headlineMedium,
    )
    if (state.message.isNotEmpty()) {
      Text(
        state.message,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
    Button(
      onClick = onLeft,
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
      Text(stringResource(R.string.online_leave_match))
    }
  }
}

// ── Previews: one per state, per the screen conventions ───────────────

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun OnlineMatchConnectingPreview() {
  EstemshanTheme { OnlineMatchOverlay("Connecting…") }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun OnlineMatchReconnectingPreview() {
  EstemshanTheme { OnlineMatchOverlay("Reconnecting…", "You're still in the match.") }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun OnlineMatchFailedPreview() {
  EstemshanTheme {
    OnlineMatchFailed(MatchUiState.Failed("Firestore is not initialized.")) {}
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun OnlineMatchBiddingPreview() {
  val state = MatchUiState.Bidding(
    state = initNormalRound(round = 1, dealer = "p1", seats = DEFAULT_SEATS),
    userSeat = "p1",
    rejection = null,
    forbidden = null,
    floor = null,
  )
  EstemshanTheme {
    BiddingScreen(
      state = state.state,
      userSeat = state.userSeat,
      rejection = state.rejection,
      forbidden = state.forbidden,
      floor = state.floor,
      onIntent = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun OnlineMatchTablePreview() {
  val table = initTable(
    RoundCfg(
      round = 1,
      trump = Suit.HEARTS,
      callerId = "p2",
      withPlayers = emptyList(),
      estimates = mapOf("p2" to 7),
      dashCallers = emptyList(),
      leaderId = "p2",
      riskId = "p1",
      hands = mapOf("p1" to emptyList()),
    ),
  )
  val state = MatchUiState.Table(table, "p1", "Follow HEARTS")
  EstemshanTheme {
    TableScreen(
      state = state.state,
      userSeat = state.userSeat,
      rejection = state.rejection,
      onPlay = { _, _ -> },
      onResolve = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun OnlineMatchRoundStandingsPreview() {
  val state = MatchUiState.RoundStandings(
    standings = buildStandings(
      mapOf("p1" to 42, "p2" to 31, "p3" to 28, "p4" to 35),
      lastDeltas = mapOf("p1" to 14, "p2" to -3, "p3" to 4, "p4" to 11),
    ),
    round = 1,
    isLastRound = false,
  )
  EstemshanTheme { OnlineRoundStandings(state) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun OnlineMatchCompletePreview() {
  val state = MatchUiState.MatchComplete(
    buildStandings(
      mapOf("p1" to 120, "p2" to 98, "p3" to 134, "p4" to 77),
      title = "Final Standings",
    ),
  )
  EstemshanTheme { OnlineMatchComplete(state) {} }
}
