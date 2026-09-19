package com.estemshan.game.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estemshan.game.data.AuthRepository
import com.estemshan.game.data.AuthUiState
import com.estemshan.game.data.FirebaseAuthBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Splash gate: null while the persisted session is being checked, then the
 * resolved auth state exactly once. The nav graph routes on that single
 * value (SignedIn → lobby, anything else → login) and pops splash, so the
 * decision can never re-fire on recomposition.
 */
class SplashViewModel(
  private val repo: AuthRepository = AuthRepository(FirebaseAuthBackend()),
) : ViewModel() {

  private val _ready = MutableStateFlow<AuthUiState?>(null)
  val ready: StateFlow<AuthUiState?> = _ready.asStateFlow()

  init {
    viewModelScope.launch {
      repo.checkSession()
      _ready.value = repo.state.value
    }
  }
}
