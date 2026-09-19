package com.estemshan.game.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.estemshan.game.data.AuthUiState
import com.estemshan.game.ui.login.LoginViewModel
import com.estemshan.game.ui.splash.SplashScreen
import com.estemshan.game.ui.splash.SplashViewModel
import com.estemshan.game.ui.standings.FinalStandingsScreen
import com.estemshan.game.ui.standings.buildStandings
import com.estemshan.game.ui.theme.EstemshanTheme

/**
 * App shell: Splash gate → Login → Lobby. The remaining spec-01 routes
 * (Room, Bidding, Table, Profile, Settings) land here as their screens
 * are built; Standings is registered but unlinked until the Table screen
 * feeds it real results — no dead buttons.
 */
@Composable
fun EstemshanNav() {
  val nav = rememberNavController()
  val vm: LoginViewModel = viewModel()
  val authState by vm.state.collectAsStateWithLifecycle()

  EstemshanTheme {
    NavHost(navController = nav, startDestination = Routes.SPLASH) {
      composable(Routes.SPLASH) {
        val splashVm: SplashViewModel = viewModel()
        val ready by splashVm.ready.collectAsStateWithLifecycle()
        SplashScreen()
        val target = ready
        if (target != null) {
          LaunchedEffect(target) {
            if (target is AuthUiState.SignedIn) {
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
        LobbyPlaceholder(
          state = authState,
          onSignOut = {
            vm.signOut()
            nav.navigate(Routes.LOGIN) { popUpTo(Routes.LOBBY) { inclusive = true } }
          },
        )
      }
      composable(Routes.STANDINGS) {
        // Unlinked until the Table screen supplies real match results.
        FinalStandingsScreen(buildStandings(emptyMap()))
      }
    }
  }
}

@Composable
private fun LoginScreen(
  state: AuthUiState,
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

@Composable
private fun LobbyPlaceholder(state: AuthUiState, onSignOut: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Lobby", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
      when (state) {
        is AuthUiState.SignedIn -> "uid: ${state.uid}"
        else -> "Not signed in (room list lands in Phase 4)"
      },
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onSignOut) { Text("Sign out") }
  }
}
