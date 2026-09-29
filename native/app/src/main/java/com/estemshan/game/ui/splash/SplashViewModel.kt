package com.estemshan.game.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estemshan.game.data.AuthRepository
import com.estemshan.game.data.AuthUiState
import com.estemshan.game.data.FirebaseAuthBackend
import com.estemshan.game.data.OnlineServices
import com.estemshan.services.PlayerPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The splash gate's one resolution: the restored auth state and, for a
 * signed-in player, the reconnect pointer read off their profile. Both halves
 * land before this is published, which is what lets the nav graph route
 * exactly once. Two separate flows would race instead: the profile read can
 * finish after the nav has already acted on the auth half and routed to the
 * lobby, and the resume would never fire.
 */
data class SplashReady(
  val auth: AuthUiState,
  /**
   * players/{uid}.currentMatchId — the match this player is still in, or null
   * when they are in none (and when the read failed: a profile problem costs
   * the resume, never the launch). The splash only READS this pointer; the
   * match view model is the one that clears it when play ends.
   */
  val resumeMatchId: String? = null,
)

/**
 * Splash gate: null while the persisted session and the reconnect pointer are
 * being resolved, then a single [SplashReady]. The nav graph routes on that
 * one value (SignedIn with a live match → the match, SignedIn → lobby,
 * anything else → login) and pops splash, so the decision can never re-fire
 * on recomposition.
 */
class SplashViewModel(
  private val repo: AuthRepository = AuthRepository(FirebaseAuthBackend()),
  private val players: PlayerPort = OnlineServices.players,
) : ViewModel() {

  private val _ready = MutableStateFlow<SplashReady?>(null)
  val ready: StateFlow<SplashReady?> = _ready.asStateFlow()

  init {
    viewModelScope.launch {
      repo.checkSession()
      val auth = repo.state.value
      // The resume read completes BEFORE the gate opens, so the nav's single
      // LaunchedEffect finds the matchId inside the same value it routes on.
      _ready.value = SplashReady(auth, resumeMatchId(auth))
    }
  }

  /**
   * The cold-start reconnect read. Only a signed-in player has a uid to read
   * with. [PlayerPort.currentMatchId] is documented never to throw; the
   * try/catch is the belt-and-braces half of "an unreadable profile is no
   * current match, not an error" — a seam that breaks must not break launch.
   */
  private suspend fun resumeMatchId(auth: AuthUiState): String? {
    val uid = (auth as? AuthUiState.SignedIn)?.uid ?: return null
    return try {
      players.currentMatchId(uid)?.takeUnless { it.isBlank() }
    } catch (e: Throwable) {
      null
    }
  }
}
