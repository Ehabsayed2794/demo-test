package com.estemshan.functions

/**
 * The room's PUBLIC shape — the one list that decides what may leave the
 * authority layer.
 *
 * A room document carries a private half (the bot trio, and any credential a
 * future story adds) beside its public half. Two callables now answer with a
 * room — S70's Rank-down join and S71's "Play Unranked" conversion — and a
 * projection that exists twice is a projection that drifts: the field a later
 * story adds to one list and forgets in the other is a leak in exactly one of
 * them, and the leak is invisible to a test that only checks the callable it
 * belongs to.
 *
 * So the list lives here, once, and both callables project through it. It is
 * an ALLOW-list, never a removal list: a projection that survives a new field
 * must name what may leave, not what may not, so a field nobody thought about
 * cannot ride out by default.
 *
 * Nothing rank-bearing is on it — no tier, division, season, RP, placement,
 * shield, email, coin or token — and [PartyJoinTest] and [PlayUnrankedTest]
 * both search the whole flattened response for every one of those words.
 */
internal val ROOM_PUBLIC_FIELDS: List<String> = listOf(
  "roomId", "name", "status", "creator", "players", "readyPlayers",
  "mode", "rankDown", "gameType", "scoringMode", "matchId",
)

/**
 * The room as it may leave: the allowed fields, and the id the read used.
 *
 * The id is always the one the read used, never one carried by the document's
 * own fields — an auto-ID room has its id nowhere inside itself, and the
 * client keys its room state on it.
 */
internal fun projectRoom(room: Map<String, Any?>, roomId: String): Map<String, Any?> {
  val projected = LinkedHashMap<String, Any?>()
  for (field in ROOM_PUBLIC_FIELDS) projected[field] = room[field]
  projected["roomId"] = roomId
  return projected
}
