package com.estemshan.game.ui

/**
 * All native v1 destinations from docs/specs/01-screens.md §3, in one
 * place. Deferred screens (ranked, shop, AI) get NO route until their
 * phase — no dead destinations.
 */
object Routes {
  const val SPLASH = "splash"
  const val LOGIN = "login"
  const val LOBBY = "lobby"
  const val ROOM = "room"
  const val BIDDING = "bidding"
  const val TABLE = "table"
  const val STANDINGS = "standings"
  const val PROFILE = "profile"
  const val SETTINGS = "settings"
}
