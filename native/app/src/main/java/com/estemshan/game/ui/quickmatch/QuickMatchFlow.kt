package com.estemshan.game.ui.quickmatch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.estemshan.engine.TablePhase
import com.estemshan.game.ui.Routes
import com.estemshan.game.ui.bidding.BiddingScreen
import com.estemshan.game.ui.bidding.BiddingViewModel
import com.estemshan.game.ui.standings.FinalStandingsScreen
import com.estemshan.game.ui.table.TableScreen
import com.estemshan.game.ui.table.TableViewModel
import com.estemshan.game.ui.theme.EstemshanTheme

const val QUICKMATCH_GRAPH = "quickmatch"
const val QUICK_STANDINGS = "quickmatch/standings"

/**
 * Offline quick-match flow (hot-seat, pass-and-play): Lobby → Bidding →
 * Table → Standings → next round. Every number comes from the real
 * engines; the session layer will replace this driver without touching
 * the screens (they stay stateless).
 *
 * [qvm] is activity-scoped (owned by the top-level nav host). The screen
 * VMs are scoped to this nested graph so the whole flow state drops when
 * the player backs out to the lobby.
 */
fun NavGraphBuilder.quickMatchGraph(nav: NavController, qvm: QuickMatchViewModel) {
  navigation(startDestination = Routes.BIDDING, route = QUICKMATCH_GRAPH) {
    composable(Routes.BIDDING) {
      val parent = remember(nav) { nav.getBackStackEntry(QUICKMATCH_GRAPH) }
      val bvm: BiddingViewModel = viewModel(parent)
      val tvm: TableViewModel = viewModel(parent)
      val round by qvm.round.collectAsStateWithLifecycle()
      val dealer by qvm.dealer.collectAsStateWithLifecycle()
      val mult by qvm.biddingMultiplier.collectAsStateWithLifecycle()
      val key by qvm.biddingKey.collectAsStateWithLifecycle()
      val state by bvm.state.collectAsStateWithLifecycle()
      val outcome by bvm.outcome.collectAsStateWithLifecycle()
      val generalPass by bvm.generalPass.collectAsStateWithLifecycle()
      val rejection by bvm.rejection.collectAsStateWithLifecycle()

      LaunchedEffect(key) {
        bvm.startNormalRound(round, dealer, qvm.seats, mult)
      }
      // Arm the bot driver for this match: it moves only for seats the roster
      // marks as bots, and re-arms from the current state when the Table
      // screen takes over below.
      LaunchedEffect(Unit) { qvm.attachBots(bvm, tvm) }
      DisposableEffect(Unit) { onDispose { qvm.detachBots() } }
      LaunchedEffect(generalPass) {
        val doubled = generalPass
        if (doubled != null) qvm.applyGeneralPass(doubled)
      }
      LaunchedEffect(outcome) {
        val o = outcome
        if (o != null) {
          val cfg = qvm.onBiddingComplete(o)
          tvm.startRound(cfg, qvm.seats)
          nav.navigate(Routes.TABLE) {
            popUpTo(Routes.BIDDING) { inclusive = true }
          }
        }
      }

      EstemshanTheme {
        val s = state
        if (s != null) {
          val turn = s.waitingFor
          BiddingScreen(
            state = s,
            userSeat = turn ?: qvm.seats[0],
            rejection = rejection,
            forbidden = turn?.let { bvm.forbiddenFor(it) },
            floor = turn?.let { bvm.withFloorOf(it) },
            onIntent = bvm::submit,
          )
        }
      }
    }

    composable(Routes.TABLE) {
      val parent = remember(nav) { nav.getBackStackEntry(QUICKMATCH_GRAPH) }
      val bvm: BiddingViewModel = viewModel(parent)
      val tvm: TableViewModel = viewModel(parent)
      // Same driver, re-armed: the view models are graph-scoped, so this is
      // the same BiddingViewModel/TableViewModel the Bidding screen used.
      LaunchedEffect(Unit) { qvm.attachBots(bvm, tvm) }
      DisposableEffect(Unit) { onDispose { qvm.detachBots() } }
      val tState by tvm.state.collectAsStateWithLifecycle()
      val tRejection by tvm.rejection.collectAsStateWithLifecycle()

      EstemshanTheme {
        val s = tState
        if (s != null) {
          if (s.phase == TablePhase.DONE) {
            LaunchedEffect(Unit) {
              qvm.onTableDone(s.cfg, s.tricksWon)
              nav.navigate(QUICK_STANDINGS) {
                popUpTo(Routes.TABLE) { inclusive = true }
              }
            }
          }
          val turn = s.turn
          TableScreen(
            state = s,
            userSeat = turn ?: qvm.seats[0],
            rejection = tRejection,
            onPlay = { seat, card -> tvm.play(seat, card) },
            onResolve = { tvm.resolve() },
          )
        }
      }
    }

    composable(QUICK_STANDINGS) {
      val standings by qvm.standings.collectAsStateWithLifecycle()
      EstemshanTheme {
        Column(Modifier.fillMaxSize()) {
          Box(Modifier.weight(1f)) {
            standings?.let { FinalStandingsScreen(it) }
          }
          Button(
            onClick = {
              qvm.nextRound()
              nav.navigate(Routes.BIDDING) {
                popUpTo(QUICK_STANDINGS) { inclusive = true }
              }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
          ) {
            Text("Next round")
          }
        }
      }
    }
  }
}
