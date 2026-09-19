package com.estemshan.game.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** UI-facing auth state. Mirrors the web login flow (anonymous + email). */
sealed interface AuthUiState {
  data object SignedOut : AuthUiState
  data object SigningIn : AuthUiState
  data class SignedIn(val uid: String) : AuthUiState
  data class Error(val message: String) : AuthUiState
}

/** Seam behind the repository so unit tests run without Firebase. */
interface AuthBackend {
  suspend fun signInAnonymously(): Result<String>
  suspend fun signInWithEmail(email: String, password: String): Result<String>
  /** Persisted session, if any (Firebase Auth restores it across launches). */
  fun currentUid(): String?
  fun signOut()
}

class FirebaseAuthBackend(
  private val auth: FirebaseAuth = FirebaseModule.auth,
) : AuthBackend {
  override suspend fun signInAnonymously(): Result<String> = runCatching {
    auth.signInAnonymously().await().user?.uid
      ?: error("Anonymous sign-in returned no user")
  }

  override suspend fun signInWithEmail(email: String, password: String): Result<String> =
    runCatching {
      auth.signInWithEmailAndPassword(email.trim(), password).await().user?.uid
        ?: error("Email sign-in returned no user")
    }

  override fun currentUid(): String? = auth.currentUser?.uid

  override fun signOut() {
    auth.signOut()
  }
}

class AuthRepository(private val backend: AuthBackend) {

  private val _state = MutableStateFlow<AuthUiState>(AuthUiState.SignedOut)
  val state: StateFlow<AuthUiState> = _state.asStateFlow()

  suspend fun signInAnonymously() {
    _state.value = AuthUiState.SigningIn
    _state.value = backend.signInAnonymously().fold(
      onSuccess = { AuthUiState.SignedIn(it) },
      onFailure = { AuthUiState.Error(it.message ?: "Sign-in failed") },
    )
  }

  suspend fun signInWithEmail(email: String, password: String) {
    _state.value = AuthUiState.SigningIn
    _state.value = backend.signInWithEmail(email, password).fold(
      onSuccess = { AuthUiState.SignedIn(it) },
      onFailure = { AuthUiState.Error(it.message ?: "Sign-in failed") },
    )
  }

  fun signOut() {
    backend.signOut()
    _state.value = AuthUiState.SignedOut
  }

  /** Splash entry: restore a persisted session, if the backend holds one. */
  fun checkSession() {
    val uid = backend.currentUid()
    _state.value = if (uid != null) AuthUiState.SignedIn(uid) else AuthUiState.SignedOut
  }
}
