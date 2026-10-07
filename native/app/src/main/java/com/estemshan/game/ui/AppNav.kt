package com.estemshan.game.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.game.data.AuthUiState
import com.estemshan.game.ui.chooselevel.ChooseLevelScreen
import com.estemshan.game.ui.chooselevel.ChooseLevelViewModel
import com.estemshan.game.ui.creategame.CreateGameScreen
import com.estemshan.game.ui.creategame.CreateGameViewModel
import com.estemshan.game.ui.lobby.LobbyScreen
import com.estemshan.game.ui.lobby.LobbyViewModel
import com.estemshan.game.ui.login.LoginViewModel
import com.estemshan.game.ui.onlinematch.ONLINE_MATCH_GRAPH
import com.estemshan.game.ui.onlinematch.onlineMatchGraph
import com.estemshan.game.ui.onlinematch.onlineMatchRoute
import com.estemshan.game.ui.profile.ProfileScreen
import com.estemshan.game.ui.quickmatch.QUICKMATCH_GRAPH
import com.estemshan.game.ui.quickmatch.QuickMatchViewModel
import com.estemshan.game.ui.quickmatch.quickMatchGraph
import com.estemshan.game.ui.room.RoomScreen
import com.estemshan.game.ui.room.RoomViewModel
import com.estemshan.game.ui.settings.SettingsRoute
import com.estemshan.game.ui.sound.LocalSfx
import com.estemshan.game.ui.sound.ProvideSoundManager
import com.estemshan.game.ui.splash.SplashScreen
import com.estemshan.game.ui.splash.SplashViewModel
import com.estemshan.game.ui.standings.FinalStandingsScreen
import com.estemshan.game.ui.standings.buildStandings
import com.estemshan.game.ui.theme.EstemshanTheme

/**
 * App shell: Splash gate → Login → Lobby → Room → Profile / Settings. The
 * remaining spec-01 routes (Bidding, Table) land here as their
 * screens are built; Standings is registered but unlinked until the
 * Table screen feeds it real results — no dead buttons.
 */
@Composable
fun EstemshanNav() {
  val nav = rememberNavController()
  val vm: LoginViewModel = viewModel()
  val authState by vm.state.collectAsStateWithLifecycle()
  val qvm: QuickMatchViewModel = viewModel()

  EstemshanTheme {
    // S27: one app-scoped SoundManager for every screen below (toggle-aware).
    ProvideSoundManager {
    // Edge-to-side (targetSdk 36): keep every screen clear of the status
    // bar, gesture nav bar, and any display cutout. Applied once at the
    // root so individual screens stay written against the full window.
    NavHost(
      navController = nav,
      startDestination = Routes.SPLASH,
      modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding(),
    ) {
      composable(Routes.SPLASH) {
        val splashVm: SplashViewModel = viewModel()
        val ready by splashVm.ready.collectAsStateWithLifecycle()
        SplashScreen()
        val target = ready
        if (target != null) {
          LaunchedEffect(target) {
            // S18's cold-start resume: a live currentMatchId on the profile
            // routes the player straight into the match — the same call shape
            // as the lobby's onResumeMatch and the room→match handoff — with
            // splash popped so there is no lobby stop on the way through. A
            // blank or absent id falls through to the lobby as it did before.
            val resume = target.resumeMatchId
            if (target.auth is AuthUiState.SignedIn && !resume.isNullOrBlank()) {
              nav.navigate(onlineMatchRoute(resume)) {
                popUpTo(Routes.SPLASH) { inclusive = true }
              }
            } else if (target.auth is AuthUiState.SignedIn) {
              nav.navigate(Routes.LOBBY) { popUpTo(Routes.SPLASH) { inclusive = true } }
            } else {
              nav.navigate(Routes.LOGIN) { popUpTo(Routes.SPLASH) { inclusive = true } }
            }
          }
        }
      }
      composable(Routes.LOGIN) {
        LoginScreen(
          state = authState,
          onAnonymous = vm::signInAnonymously,
          onEmail = vm::signInWithEmail,
          onEnter = { nav.navigate(Routes.LOBBY) { popUpTo(Routes.LOGIN) { inclusive = true } } },
        )
      }
      composable(Routes.LOBBY) {
        val lobbyVm: LobbyViewModel = viewModel()
        val lobbyState by lobbyVm.state.collectAsStateWithLifecycle()
        val uid = (authState as? AuthUiState.SignedIn)?.uid
        // S27: tap sounds on the lobby's primary buttons (toggle-aware).
        val sfx = LocalSfx.current
        // The reconnect read, seeded once per signed-in arrival: a live
        // currentMatchId is what turns the lobby's Resume entry on.
        LaunchedEffect(uid) { uid?.let { lobbyVm.loadResume(it) } }
        // A create or join is the lobby's one trigger to open the room
        // screen. Navigating from the state (not the callback) keeps a single
        // owner for "which room am I in": the view model. The two keys matter:
        // a CREATE sets roomCode AND createdCode, and the created-code dialog
        // is the host's chance to read the code out — navigating on roomCode
        // alone would skip it. So this fires only once createdCode is clear,
        // which a join never sets and a create clears on the dialog's Done.
        LaunchedEffect(lobbyState.roomCode, lobbyState.createdCode) {
          val code = lobbyState.roomCode
          if (code != null && lobbyState.createdCode == null) {
            nav.navigate(Routes.roomPath(code))
          }
        }
        EstemshanTheme {
          LobbyScreen(
            state = lobbyState,
            uid = uid,
            onCreateRoom = { sfx.tapped(); nav.navigate(Routes.CREATE_GAME) },
            onJoinRoom = { code -> sfx.tapped(); uid?.let { lobbyVm.joinRoom(it, code) } },
            onLeaveRoom = { uid?.let { lobbyVm.leaveRoom(it) } },
            onDismissCreatedCode = lobbyVm::dismissCreatedCode,
            onClearJoinError = lobbyVm::clearJoinError,
            onQuickMatch = {
              sfx.tapped()
              // S69: explicit FULL + NORMAL — S68's config screen supplies
              // real values here when it exists.
              qvm.startMatch(gameType = GameType.FULL, scoringMode = ScoringMode.NORMAL)
              nav.navigate(QUICKMATCH_GRAPH)
            },
            onPlayVsAi = { sfx.tapped(); nav.navigate(Routes.CHOOSE_LEVEL) },
            // The resume entry's one navigation — the id comes from the
            // state, and the view model forgets it so backing out of the
            // match does not immediately re-offer it.
            onResumeMatch = {
              val matchId = lobbyState.resumableMatchId ?: return@LobbyScreen
              sfx.tapped()
              lobbyVm.clearResume()
              nav.navigate(onlineMatchRoute(matchId))
            },
            onProfile = { nav.navigate(Routes.PROFILE) },
            onSettings = { nav.navigate(Routes.SETTINGS) },
            onSignOut = {
              vm.signOut()
              nav.navigate(Routes.LOGIN) { popUpTo(Routes.LOBBY) { inclusive = true } }
            },
          )
        }
      }
      composable(
        route = Routes.ROOM_PATH,
        arguments = listOf(navArgument(ROOM_CODE_KEY) { type = NavType.StringType }),
      ) {
        val roomCode = it.arguments?.getString(ROOM_CODE_KEY)
        val roomVm: RoomViewModel = viewModel()
        val roomState by roomVm.state.collectAsStateWithLifecycle()
        val uid = (authState as? AuthUiState.SignedIn)?.uid
        // S27: tap sound on the ready toggle (the room's start).
        val sfx = LocalSfx.current
        // The room's code arrives as a nav arg, so this is the one place that
        // seeds the screen: open() sets roomCode, and the view model's own
        // guard on it makes a recomposition a no-op instead of a restart. The
        // poll then keys off roomCode in RoomScreen, so it lives and dies with
        // the screen rather than with this destination.
        LaunchedEffect(roomCode, uid) {
          if (roomCode != null && uid != null) roomVm.open(roomCode)
        }
        // The room↔match handoff: RoomService.setReady's atomic start lands a
        // matchId on the room document, which every seat's poll then sees.
        // Leaving the room behind (popUpTo inclusive) is what makes backing
        // out of the match return to the LOBBY, not to a room whose match is
        // already underway.
        LaunchedEffect(roomState.room?.matchId) {
          val matchId = roomState.room?.matchId ?: return@LaunchedEffect
          nav.navigate(onlineMatchRoute(matchId)) {
            popUpTo(Routes.ROOM_PATH) { inclusive = true }
          }
        }
        EstemshanTheme {
          RoomScreen(
            state = roomState,
            uid = uid,
            onRefresh = roomVm::refresh,
            // S27: the ready toggle IS the room's start — the primary tap.
            onToggleReady = { sfx.tapped(); uid?.let { roomVm.toggleReady(it) } },
            onLeave = {
              uid?.let { roomVm.leave(it) }
              nav.navigate(Routes.LOBBY) { popUpTo(Routes.ROOM_PATH) { inclusive = true } }
            },
          )
        }
      }
      composable(Routes.CHOOSE_LEVEL) {
        val chooseVm: ChooseLevelViewModel = viewModel()
        val state by chooseVm.state.collectAsStateWithLifecycle()
        EstemshanTheme {
          ChooseLevelScreen(
            state = state,
            onPresetSelected = chooseVm::onPresetSelected,
            onTierSelected = chooseVm::onTierSelected,
            onPersonalitySelected = chooseVm::onPersonalitySelected,
            // The roster the player just edited goes straight into the match:
            // the driver is armed off it, so this is where a tuned chair
            // becomes an opponent that actually plays that way.
            onStartMatch = {
              // S69: explicit FULL + NORMAL — S68's config screen supplies
              // real values here when it exists.
              qvm.startMatch(
                roster = chooseVm.roster(),
                gameType = GameType.FULL,
                scoringMode = ScoringMode.NORMAL,
              )
              nav.navigate(QUICKMATCH_GRAPH)
            },
          )
        }
      }
      composable(Routes.CREATE_GAME) {
        // S36 shares the LOBBY's own view model rather than a second
        // instance: createRoom is the lobby's action and the created-code
        // handoff (S06) is keyed on its state, so "which room am I in" keeps
        // exactly one owner. Scoping to the lobby's back-stack entry — not
        // the nav host — keeps that state's lifetime tied to the lobby, so a
        // sign-out that pops it still clears it.
        val lobbyVm: LobbyViewModel = viewModel(
          viewModelStoreOwner = nav.getBackStackEntry(Routes.LOBBY),
        )
        val lobbyState by lobbyVm.state.collectAsStateWithLifecycle()
        val createVm: CreateGameViewModel = viewModel()
        val createState by createVm.state.collectAsStateWithLifecycle()
        val uid = (authState as? AuthUiState.SignedIn)?.uid
        // The room the player was already seated at when they opened this
        // screen, if any. A create sets a different code, so this is what
        // separates a real create from having arrived already seated.
        val initialRoomCode = remember { lobbyState.roomCode }
        // A create that landed puts a roomCode in the lobby state, so pop
        // back to the lobby: its created-code dialog is the host's chance to
        // read the code out (S06), and its own effect then opens the room.
        // Until a create lands the player stays here, selections intact.
        LaunchedEffect(lobbyState.roomCode) {
          if (lobbyState.roomCode != null && lobbyState.roomCode != initialRoomCode) {
            nav.popBackStack()
          }
        }
        EstemshanTheme {
          CreateGameScreen(
            state = createState,
            busy = lobbyState.busy,
            createError = lobbyState.createError,
            onGameType = createVm::onGameType,
            onScoringMode = createVm::onScoringMode,
            onBotTier = createVm::onBotTier,
            onBotPersonality = createVm::onBotPersonality,
            onDecisionTimerSeconds = createVm::onDecisionTimerSeconds,
            onCreateRoom = {
              uid?.let {
                lobbyVm.createRoom(it, createState.gameType, createState.scoringMode)
              }
            },
            onCancel = { nav.popBackStack() },
          )
        }
      }
      quickMatchGraph(nav, qvm)
      // The online match flow. Leaving it is the one way back to the lobby —
      // the graph scopes the view model and its subscription, so both drop.
      onlineMatchGraph(nav) {
        nav.navigate(Routes.LOBBY) { popUpTo(ONLINE_MATCH_GRAPH) { inclusive = true } }
      }
      composable(Routes.STANDINGS) {
        // Unlinked until the Table screen supplies real match results.
        FinalStandingsScreen(buildStandings(emptyMap()))
      }
      composable(Routes.PROFILE) {
        ProfileScreen(
          uid = (authState as? AuthUiState.SignedIn)?.uid ?: "",
        )
      }
      composable(Routes.SETTINGS) {
        SettingsRoute(
          uid = (authState as? AuthUiState.SignedIn)?.uid ?: "",
          onSignOut = {
            vm.signOut()
            nav.navigate(Routes.LOGIN) { popUpTo(Routes.SETTINGS) { inclusive = true } }
          },
        )
      }
    }
    }
  }
}

@Composable
private fun LoginScreen(  state: AuthUiState,
  onAnonymous: () -> Unit,
  onEmail: (String, String) -> Unit,
  onEnter: () -> Unit,
) {
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }

  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Estemshan", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
      value = email,
      onValueChange = { email = it },
      label = { Text("Email") },
      singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
      value = password,
      onValueChange = { password = it },
      label = { Text("Password") },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = { onEmail(email, password) }) { Text("Sign in") }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onAnonymous) { Text("Play anonymously") }
    Spacer(Modifier.height(16.dp))
    when (state) {
      is AuthUiState.SigningIn -> Text("Signing in…")
      is AuthUiState.SignedIn -> {
        Text("Signed in")
        Spacer(Modifier.height(8.dp))
        Button(onClick = onEnter) { Text("Enter") }
      }
      is AuthUiState.Error -> Text("Error: ${state.message}")
      is AuthUiState.SignedOut -> Unit
    }
  }
}

/** The nav-arg key [Routes.ROOM_PATH] declares; the lobby passes the code here. */
private const val ROOM_CODE_KEY = "roomCode"
