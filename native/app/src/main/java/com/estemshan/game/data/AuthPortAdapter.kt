package com.estemshan.game.data

import com.estemshan.services.session.AuthPort

/**
 * The [AuthPort] the services layer resolves the calling uid from, backed by
 * the app's own [AuthRepository].
 *
 * An adapter rather than `class AuthRepository(...) : AuthPort` because the
 * port lives in `:services`: implementing it there would bind `:app`'s auth
 * class to a services type for the sake of one one-line method, and would
 * make every existing [AuthRepository] test drag the services module in. The
 * adapter pays that dependency once, in a type whose only job is the seam,
 * leaving [AuthRepository] the session's owner with no services types in
 * sight. It mirrors how GameSessionBridge adapts :engine's GameSession to
 * GameSessionPort instead of the engine implementing the port: the adapted
 * type never carries the consumer's interface.
 */
class AuthPortAdapter(private val repository: AuthRepository) : AuthPort {
  override fun currentUid(): String? = repository.currentUid()
}
