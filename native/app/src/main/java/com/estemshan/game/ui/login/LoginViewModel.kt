package com.estemshan.game.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estemshan.game.data.AuthRepository
import com.estemshan.game.data.AuthUiState
import com.estemshan.game.data.FirebaseAuthBackend
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
  private val repo: AuthRepository = AuthRepository(FirebaseAuthBackend()),
) : ViewModel() {

  val state: StateFlow<AuthUiState> = repo.state

  fun signInAnonymously() {
    viewModelScope.launch { repo.signInAnonymously() }
  }

  fun signInWithEmail(email: String, password: String) {
    viewModelScope.launch { repo.signInWithEmail(email, password) }
  }

  fun signOut() = repo.signOut()
}
