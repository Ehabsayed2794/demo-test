package com.estemshan.game.ui

/**
 * All native v1 destinations from docs/specs/01-screens.md §3, in one
 * place. Deferred screens (ranked, shop) get NO route until their
 * phase — no dead destinations.
 */
object Routes {
  const val SPLASH = "splash"
  const val LOGIN = "login"
  const val LOBBY = "lobby"
  const val ROOM = "room"
  /** [ROOM] with its argument — the lobby hands the room code over here. */
  const val ROOM_PATH = "$ROOM/{roomCode}"

  /** The one way to address a room destination: [ROOM_PATH] with [code] in it. */
  fun roomPath(code: String): String = "$ROOM/$code"
  const val BIDDING = "bidding"
  const val TABLE = "table"
  const val STANDINGS = "standings"
  const val CHOOSE_LEVEL = "choose_level"
  const val PROFILE = "profile"
  const val SETTINGS = "settings"
}
