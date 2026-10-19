package com.estemshan.functions

import com.estemshan.engine.LeaderboardEntry
import com.estemshan.engine.LeaderboardRow
import com.estemshan.engine.orderLeaderboard
import com.estemshan.engine.rankLadder
import kotlinx.coroutines.await

/**
 * S64 — the seasonal Ranked leaderboard read path (RD27, roadmap §4b.8).
 *
 * "Server-authoritative ordering — the client may read, never order." That
 * sentence is the whole design: this callable reads one season's profiles,
 * hands them to the shared :engine [orderLeaderboard], and returns rows that
 * already carry their position. A client receiving position 42 cannot have
 * chosen it, and the board it renders is the board the server computed.
 *
 * The ordering itself lives in `:engine` (Leaderboard.kt) — rung index first,
 * RP within the rung, RP alone for King (RD3: King has no divisions), then
 * playerId for determinism; competition positions so ties share and the next
 * skips. Nothing here re-implements a single line of it.
 *
 * Scope discipline:
 * - ONE collection, ONE query: `players` filtered on the top-level `seasonId`.
 *   No `matches`, no `rooms`, no new collection, no composite index — an
 *   equality filter on one field is served by the automatic single-field index,
 *   so `firestore.rules` and its SHA pin stay byte-identical.
 * - The Admin SDK is the only thing that can read another player's progression
 *   fields at all: `firestore.rules` keeps `rp`/`rank` off a client's read of
 *   someone else's profile, which is exactly why the board cannot be assembled
 *   client-side (roadmap §4b.11, tier 1).
 * - The response is a display-safe projection. Identity (uid), contact
 *   (email), currency (coins/gems) and every other profile field stay behind
 *   this file: a leaderboard row carries position, name, initial, tier,
 *   division, RP and season — nothing else, because the board is public and a
 *   profile is not.
 */

/**
 * One persisted profile as the port delivers it: the document id (which is the
 * player's uid, by the `players/{uid}` convention) and its raw fields.
 *
 * Fields stay a map on purpose — this port reads what S51's RP-application
 * path writes, and re-declaring that shape here would create a second copy
 * that can silently drift from the writer.
 */
data class StoredProfile(val playerId: String, val fields: Map<String, Any?>)

/** The board's whole Firestore touch, behind one read so ordering is unit-testable. */
interface LeaderboardDb {
  /**
   * The profiles of ONE season. Implementations filter server-side on the
   * top-level `seasonId`; [leaderboardOnce] re-checks every row anyway, so a
   * port that returned another season's document is caught rather than blended
   * (RD24: rank position is recomputed per season, never carried over).
   */
  suspend fun readSeasonProfiles(seasonId: String): List<StoredProfile>
}

/** Requested rows default to a full page; the response always reports the whole board size. */
private const val DEFAULT_LIMIT = 100
private const val MIN_LIMIT = 1
private const val MAX_LIMIT = 100

/** RD27's "display identity" fallbacks: a board row is never blank. */
private const val FALLBACK_DISPLAY_NAME = "Player"
private const val FALLBACK_AVATAR_INITIAL = "?"

/** The one collection this story reads, and the field it filters on. */
private const val PLAYERS_COLLECTION = "players"
private const val SEASON_FIELD = "seasonId"

/**
 * The season a caller that names none is asking about.
 *
 * Absent is a normal request shape, not a mistake — and which season is
 * "current" stays the server's answer, never a client claim. A value that IS
 * present but unusable fails closed instead of silently resolving here: a
 * client that meant `s2` must not be handed `s1`'s board and never know.
 */
private const val DEFAULT_SEASON_ID = "s1"

/**
 * A row's identity is the (tier, division) pair exactly as stored, resolved
 * against the single [rankLadder] table — the same exact-match rule
 * [com.estemshan.engine.ladderRankOf] applies, so a King profile must carry
 * King + "" and "King I" is not a rung anyone can hold. An unresolvable pair is
 * skipped rather than passed on: [orderLeaderboard] fails loudly by contract,
 * and one corrupt document must not cost every other player their board.
 */
private fun rungNames(tier: Any?, division: Any?): Pair<String, String>? =
  if (tier is String && division is String &&
    rankLadder.any { it.tier == tier && it.division == division }
  ) {
    Pair(tier, division)
  } else {
    null
  }

/**
 * RP as the engine sees it: a whole, non-negative number. `null`, a string, a
 * negative, a fraction or a value that cannot be an Int is not a progression
 * counter, and a row built from one would claim a position it never earned.
 */
private fun boardableRp(value: Any?): Int? {
  val number = value as? Number ?: return null
  val rp = number.toDouble()
  if (!rp.isFinite() || rp < 0.0 || rp != rp.toInt().toDouble()) return null
  return rp.toInt()
}

private fun displayField(value: Any?, fallback: String): String =
  (value as? String)?.takeIf { it.isNotBlank() } ?: fallback

/**
 * One profile's verdict, decided in [classify] and never re-derived: a row the
 * board can carry, or the reason this one cannot.
 */
private sealed class Candidate {
  class Boardable(val entry: LeaderboardEntry, val displayName: String, val avatarInitial: String) : Candidate()
  class Unusable(val playerId: String, val reason: String) : Candidate()
}

/**
 * Turns one stored profile into a board entry, or into the reason it cannot be
 * one — checked in the order the board depends on: the season it belongs to,
 * the rung it claims, then the counter it orders by. A refusal is reported,
 * never repaired: a rank is never invented for a profile that cannot carry one.
 */
private fun classify(profile: StoredProfile, seasonId: String): Candidate {
  val fields = profile.fields
  if (fields[SEASON_FIELD] != seasonId) return Candidate.Unusable(profile.playerId, "season-mismatch")
  val rung = rungNames(fields["tier"], fields["division"])
    ?: return Candidate.Unusable(profile.playerId, "no-ladder-rung")
  val rp = boardableRp(fields["rp"])
    ?: return Candidate.Unusable(profile.playerId, "rp-not-a-whole-number")
  return Candidate.Boardable(
    entry = LeaderboardEntry(
      playerId = profile.playerId,
      tier = rung.first,
      division = rung.second,
      rp = rp,
      seasonId = seasonId,
    ),
    displayName = displayField(fields["displayName"], FALLBACK_DISPLAY_NAME),
    avatarInitial = displayField(fields["avatarInitial"], FALLBACK_AVATAR_INITIAL),
  )
}

/**
 * Runs one leaderboard read: auth → arguments → season read → validate →
 * order → project. Throws [HttpsError] on every denial (the client switches on
 * `code`, as in [settleOnce]).
 *
 * Returns Kotlin maps and the callable handler applies [toPlain] once, at the
 * serialization boundary.
 */
suspend fun leaderboardOnce(db: LeaderboardDb, request: CallableRequest): Map<String, Any?> {
  // Identity first, before any read: the board is public, but who is looking at
  // it decides which row is highlighted, and that must not be a client claim.
  val uid = CallableAuth.requireUid(request)

  // The runtime delivers request.data as a PLAIN JS OBJECT, not a Kotlin map.
  val data = dynamicToKotlin(request.data) as? Map<*, *>
  val seasonId = resolveSeason(data?.get(SEASON_FIELD))
  val limit = parseLimit(data?.get("limit"))

  val entries = mutableListOf<LeaderboardEntry>()
  val display = LinkedHashMap<String, Pair<String, String>>()
  var skipped = 0
  for (candidate in db.readSeasonProfiles(seasonId).map { classify(it, seasonId) }) {
    when (candidate) {
      is Candidate.Boardable -> {
        entries.add(candidate.entry)
        display[candidate.entry.playerId] = Pair(candidate.displayName, candidate.avatarInitial)
      }
      is Candidate.Unusable -> {
        // Structured and greppable, carrying only the id and the reason: a
        // skipped profile's other fields are private data. The warn level is
        // deliberate — the S62 ledger uses log for traffic, while a skipped
        // profile is a signal someone should eventually want to alert on.
        console.warn(skipLine(seasonId, candidate.playerId, candidate.reason))
        skipped++
      }
    }
  }

  val ordered = orderLeaderboard(entries, seasonId, uid)
  val top = ordered.take(limit).map { displayRow(it, display[it.playerId]) }
  val caller = ordered.firstOrNull { it.playerId == uid }
  return mapOf(
    "ok" to true,
    SEASON_FIELD to seasonId,
    "rows" to top,
    "you" to caller?.let { displayRow(it, display[it.playerId]) },
    "total" to ordered.size,
    "skipped" to skipped,
  )
}

private fun skipLine(seasonId: String, playerId: String, reason: String): String =
  "S64_LEADERBOARD_SKIP seasonId=$seasonId playerId=$playerId reason=$reason"

/**
 * Absent names [DEFAULT_SEASON_ID]; a value that IS present must be a non-blank
 * string, and anything else is refused rather than guessed.
 *
 * `null` and `undefined` are not separable here — the callable's JSON payload
 * drops an undefined field on the way in, and Kotlin/JS reads both as null — so
 * an explicit null means "no season given", the same answer as no key at all.
 * The distinction that matters is the one this draws: silence resolves to the
 * server's current season, a malformed name gets an error, because a caller
 * that meant `s2` and silently received `s1` is the one failure mode a
 * season-scoped board cannot afford (RD24).
 */
private fun resolveSeason(value: Any?): String {
  val message = "getRankedLeaderboard: seasonId must be a non-blank string."
  return when (value) {
    null -> DEFAULT_SEASON_ID
    is String -> value.takeIf { it.isNotBlank() } ?: throw HttpsError("invalid-argument", message)
    else -> throw HttpsError("invalid-argument", message)
  }
}

private fun parseLimit(value: Any?): Int = when (value) {
  null -> DEFAULT_LIMIT
  is Number -> {
    val limit = value.toDouble()
    if (!limit.isFinite() || limit != limit.toInt().toDouble() || limit < MIN_LIMIT || limit > MAX_LIMIT) {
      throw HttpsError(
        "invalid-argument",
        "getRankedLeaderboard: limit must be a whole number from $MIN_LIMIT to $MAX_LIMIT.",
      )
    }
    limit.toInt()
  }
  else -> throw HttpsError(
    "invalid-argument",
    "getRankedLeaderboard: limit must be a whole number from $MIN_LIMIT to $MAX_LIMIT.",
  )
}

/**
 * The row as the client sees it — a whitelist by construction: [position] is
 * already the server's answer, `tier`/`division` are the rung's names (division
 * is "" for King, which is what makes the board render King as one ordered
 * group instead of inventing "King I"), and nothing else rides along. The
 * playerId that ordered the board is deliberately NOT in here.
 */
private fun displayRow(row: LeaderboardRow, nameAndInitial: Pair<String, String>?): Map<String, Any?> = mapOf(
  "position" to row.position,
  "displayName" to (nameAndInitial?.first ?: FALLBACK_DISPLAY_NAME),
  "avatarInitial" to (nameAndInitial?.second ?: FALLBACK_AVATAR_INITIAL),
  "tier" to row.rank.tier,
  "division" to row.rank.division,
  "rp" to row.rp,
  "seasonId" to row.seasonId,
  "isCurrentPlayer" to row.isCurrentPlayer,
)

/**
 * Admin-SDK implementation: one equality query on `players`, no transaction
 * (this path writes nothing) and no second collection.
 */
class AdminLeaderboardDb : LeaderboardDb {
  // Before getFirestore() resolves the default app — see ensureDefaultAdminApp.
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  override suspend fun readSeasonProfiles(seasonId: String): List<StoredProfile> {
    val snapshot = db.collection(PLAYERS_COLLECTION)
      .where(SEASON_FIELD, "==", seasonId)
      .get().await()
    return snapshot.docs.map { doc ->
      @Suppress("UNCHECKED_CAST")
      StoredProfile(
        playerId = doc.id,
        fields = dynamicToKotlin(doc.data()) as? Map<String, Any?> ?: emptyMap(),
      )
    }
  }
}
