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
 *   this file: a leaderboard row carries position, name, initial, tier, the
 *   tier's Arabic title, division, RP and season — nothing else, because the
 *   board is public and a profile is not. The Arabic title is read off the
 *   already-resolved [rankLadder] rung, so this file holds no second copy of
 *   the ladder's names to drift from the first.
 * - ONE BOUNDED READ. The query carries [MAX_SEASON_PROFILES] plus one, and
 *   [requireBoardWithinCap] refuses the board before classifying anything past
 *   it: an oversized season is a refusal, never a silently truncated board.
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
   *
   * A result over [MAX_SEASON_PROFILES] is refused by [requireBoardWithinCap]
   * before any of it is read, so the bound is the caller's rule, not something
   * each implementation has to remember.
   */
  suspend fun readSeasonProfiles(seasonId: String): List<StoredProfile>

  /**
   * The season a caller that names none is asking about — the server's
   * current season, read from `seasons/current`. ONLY the absent/null case
   * crosses this port: a caller who names an explicit season must be served
   * it without this read ever running, because a named season is a request
   * for that historical board, not a question to re-decide.
   *
   * The default is the bootstrap season ([DEFAULT_SEASON_ID]) — which keeps
   * every existing fake compiling — and real implementations override it:
   * [AdminLeaderboardDb] reads the S59 singleton and answers whatever an
   * existing document authoritatively names ([currentSeasonIdOf]), failing
   * closed on a malformed one.
   */
  suspend fun currentSeasonId(): String = DEFAULT_SEASON_ID
}

/** Requested rows default to a full page; the response always reports the whole board size. */
private const val DEFAULT_LIMIT = 100
private const val MIN_LIMIT = 1
private const val MAX_LIMIT = 100

/** RD27's "display identity" fallbacks: a board row is never blank. */
private const val FALLBACK_DISPLAY_NAME = "Player"
private const val FALLBACK_AVATAR_INITIAL = "?"

/**
 * How many season documents one board read may consume.
 *
 * The board is one unpaginated read: [orderLeaderboard] needs every entry to
 * assign a position, so a request cannot be answered from a page and the read
 * has no natural bound of its own. This supplies one. The query asks for ONE
 * MORE ([MAX_SEASON_PROFILES] + 1) so [requireBoardWithinCap] can tell "at the
 * cap" from "over it" from the count it already has, without a second read.
 *
 * 10,000 is far above any season's real Ranked population, so this is a ceiling
 * on what one call may cost, not a limit on the board a player can see.
 */
internal const val MAX_SEASON_PROFILES = 10_000

/** The one collection this story reads, and the field it filters on. */
private const val PLAYERS_COLLECTION = "players"
private const val SEASON_FIELD = "seasonId"

/**
 * The transition bookkeeping `seasons/current` carries beside [SEASON_FIELD]:
 * the one-way lifecycle status and the season an in-flight reset is moving
 * profiles toward. Read only by [lazyResetSeasonOf]; [currentSeasonIdOf]
 * deliberately never consults them (see its KDoc).
 */
private const val SEASON_STATUS_FIELD = "status"
private const val SEASON_TARGET_SEASON_ID_FIELD = "targetSeasonId"

/**
 * The bootstrap season — the answer when `seasons/current` does not exist.
 *
 * Absent is a normal request shape, not a mistake — and which season is
 * "current" stays the server's answer, never a client claim. A value that IS
 * present but unusable fails closed instead of silently resolving here: a
 * client that meant `s2` must not be handed `s1`'s board and never know.
 *
 * This literal is now exactly one thing, the bootstrap: S59's season
 * document (`seasons/current`) owns which season is live, and an existing
 * document is authoritative — a blank or non-string `seasonId` in it fails
 * closed ([currentSeasonIdOf]) rather than falling back here, so a reset
 * that left an unreadable document behind can never silently hand an unnamed
 * request back to `s1`. The response echo of the season actually served
 * stays whatever this resolves to, so a caller is never shown one board
 * while believing another.
 */
private const val DEFAULT_SEASON_ID = "s1"

/**
 * Where the live season lives: one singleton document, `seasons/current`
 * (S59). Two per-file private constants would be two chances to misspell the
 * same path, so the pair is module-shared — the profile's Tx resolves the
 * same singleton inside its own transaction.
 */
internal const val SEASONS_COLLECTION = "seasons"
internal const val CURRENT_SEASON_DOC = "current"

/**
 * The current season as the BOARD serves it: S59's singleton carries it, and
 * this is the parser the leaderboard path resolves through. The two lazy-reset
 * reads use [lazyResetSeasonOf] instead, and may deliberately disagree with
 * this answer while a season reset is RUNNING (see its KDoc).
 *
 * - No document (`exists = false`) is THE bootstrap: [DEFAULT_SEASON_ID].
 *   That is the only thing a missing document can produce (a fresh
 *   deployment serves season one until the season job writes the document)
 *   and it is not an error.
 * - An existing document is authoritative. Its usable answer is a non-blank
 *   `String` `seasonId` on a Kotlin map — exactly what the Admin
 *   implementations hand over after [dynamicToKotlin] converts the raw JS
 *   object — and that value is served VERBATIM: the id is case-sensitive and
 *   never normalized, because "S1" and "s1" are two different names for two
 *   different boards (RD24), and a silently normalized name would silently
 *   blend them.
 * - Everything else — a non-map (data that is not a document's fields at
 *   all), an absent or blank `seasonId`, a non-string one — FAILS CLOSED
 *   with `failed-precondition`. No recovery, no fallback, and the message is
 *   a fixed literal that never echoes the malformed value: a read-time error
 *   message is not a place to broadcast a client-controllable datum into
 *   every log collector.
 *
 * `status`, `targetSeasonId`, `nextSeasonId`, `startsAt` and `endsAt` are
 * deliberately NOT consulted: the served season is `seasonId`, full stop.
 * During a reset (status `RUNNING`) the outgoing season stays the served one
 * until the season job commits the new one as `seasonId` — serving
 * `targetSeasonId` would flip the live board mid-reset, onto a season the
 * profiles have not been moved into yet.
 */
internal fun currentSeasonIdOf(exists: Boolean, data: Any?): String {
  if (!exists) return DEFAULT_SEASON_ID
  // The season document's usable field carries the same top-level name as a
  // player profile's ([SEASON_FIELD]) — one name, two documents.
  val raw = (data as? Map<*, *>)?.get(SEASON_FIELD)
  if (raw is String && raw.isNotBlank()) return raw
  throw HttpsError("failed-precondition", "seasons/current has no usable seasonId.")
}

/**
 * The season a LAZY reset judges a profile against — [currentSeasonIdOf]'s
 * sibling for the two writer-side reads (the Ranked profile's and the
 * settlement's), never for the board.
 *
 * The board keeps serving [currentSeasonIdOf]'s answer, and it must: while the
 * season job is `RUNNING`, the live leaderboard stays on the OUTGOING season
 * until the job commits the target as `seasonId`. A lazy reset has the
 * opposite need. The bulk sweep stamps every profile with the TARGET season
 * BEFORE the job flips `seasonId`, so in that window [currentSeasonIdOf] still
 * answers the outgoing season while the profiles already carry the target.
 * Judging them against it would plan `target -> outgoing` — a BACKWARDS reset
 * that demotes every player the sweep just moved, irreversibly (R27).
 *
 * So:
 * - No document (`exists = false`) answers NULL: the bootstrap, the same
 *   absence [currentSeasonIdOf] resolves to [DEFAULT_SEASON_ID]. The caller
 *   decides whether a profile may adopt the bootstrap season or must fail
 *   closed instead.
 * - An existing document's non-blank `String` `seasonId` is authoritative and
 *   served VERBATIM (case-sensitive, never trimmed), exactly as
 *   [currentSeasonIdOf] serves it — EXCEPT while `status` is `RUNNING`, when
 *   a non-blank `targetSeasonId` is the season actually in flight and is
 *   served instead.
 * - A `status` that is absent or names no [SeasonStatus] fails closed, and so
 *   does a `RUNNING` document with no usable `targetSeasonId`: the season job
 *   writes exactly one of those states, so anything else is an unreadable
 *   transition, never a season to guess at. The bulk job refuses to run from
 *   the same document for the same reason (`NOT_CONFIGURED`).
 * - Every malformed shape fails closed with a fixed literal that never echoes
 *   the value. The missing/unusable-`seasonId` failure reuses
 *   [currentSeasonIdOf]'s exact literal: one document, one diagnostic.
 */
internal fun lazyResetSeasonOf(exists: Boolean, data: Any?): String? {
  if (!exists) return null
  val fields = data as? Map<*, *>
  val seasonId = (fields?.get(SEASON_FIELD) as? String)?.takeIf { it.isNotBlank() }
    ?: throw HttpsError("failed-precondition", "seasons/current has no usable seasonId.")
  val statusName = fields[SEASON_STATUS_FIELD] as? String
  val status = SeasonStatus.values().firstOrNull { it.name == statusName }
    ?: throw HttpsError("failed-precondition", "seasons/current has no known status.")
  if (status != SeasonStatus.RUNNING) return seasonId
  return (fields[SEASON_TARGET_SEASON_ID_FIELD] as? String)?.takeIf { it.isNotBlank() }
    ?: throw HttpsError(
      "failed-precondition",
      "seasons/current is RUNNING with no usable targetSeasonId.",
    )
}

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
  class Unusable(val reason: String) : Candidate()
}

/**
 * Turns one stored profile into a board entry, or into the reason it cannot be
 * one — checked in the order the board depends on: the season it belongs to,
 * the rung it claims, then the counter it orders by. A refusal is reported,
 * never repaired: a rank is never invented for a profile that cannot carry one.
 */
private fun classify(profile: StoredProfile, seasonId: String): Candidate {
  val fields = profile.fields
  if (fields[SEASON_FIELD] != seasonId) return Candidate.Unusable("season-mismatch")
  val rung = rungNames(fields["tier"], fields["division"])
    ?: return Candidate.Unusable("no-ladder-rung")
  val rp = boardableRp(fields["rp"])
    ?: return Candidate.Unusable("rp-not-a-whole-number")
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
 * The read bound, decided on the count alone and before a single profile is
 * classified. Over the cap there is no board at all — never a partial one:
 * [orderLeaderboard] positions depend on every entry, so the top of an
 * arbitrarily cut list is a different season's answer than this one, and RD27
 * gives a client no way to tell it was cut. `resource-exhausted` is the honest
 * code — the season is too large for one read, and no argument the client
 * could send would change that.
 */
internal fun requireBoardWithinCap(size: Int, seasonId: String) {
  if (size > MAX_SEASON_PROFILES) {
    throw HttpsError(
      "resource-exhausted",
      "getRankedLeaderboard: season '$seasonId' has more than $MAX_SEASON_PROFILES profiles; " +
        "the board is refused rather than served partly.",
    )
  }
}

/**
 * Runs one leaderboard read: auth → argument checks (the limit parsed BEFORE
 * the season is resolved, so a rejected limit costs zero current-season reads
 * and zero profile reads) → the season's profiles → cap → validate → order →
 * project. Throws [HttpsError] on every denial (the client switches on
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

  // The limit is parsed before the season is resolved — both are argument
  // denials, and a rejected limit must cost zero current-season reads and
  // zero profile reads even when the season would still need resolving.
  val limit = parseLimit(data?.get("limit"))

  // An absent or explicitly null seasonId is the ONLY path that resolves
  // through the current-season document; a named season is validated locally
  // and costs no season read at all.
  val seasonId = resolveSeason(db, data?.get(SEASON_FIELD))

  // Bound the read before working through it. Classifying a document past the
  // cap to discover the board is oversized would spend the very cost the cap
  // exists to refuse.
  val profiles = db.readSeasonProfiles(seasonId)
  requireBoardWithinCap(profiles.size, seasonId)

  val entries = mutableListOf<LeaderboardEntry>()
  val display = LinkedHashMap<String, Pair<String, String>>()
  var skipped = 0
  for (candidate in profiles.map { classify(it, seasonId) }) {
    when (candidate) {
      is Candidate.Boardable -> {
        entries.add(candidate.entry)
        display[candidate.entry.playerId] = Pair(candidate.displayName, candidate.avatarInitial)
      }
      is Candidate.Unusable -> {
        // Structured and greppable, carrying the season and the reason and no
        // identifier: a skipped profile's fields are private data, and its
        // document id is the player's uid, so logging it would build a record
        // of who was refused out of a read meant to expose nothing. The warn
        // level is deliberate — the S62 ledger uses log for traffic, while a
        // skipped profile is a signal someone should eventually want to alert
        // on.
        console.warn(skipLine(seasonId, candidate.reason))
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

/**
 * One skipped profile, as the log sees it: the season and the reason, and no
 * identifier of any kind. This is the only place a skip is reported, so keeping
 * the uid out of here keeps it out of the log entirely.
 *
 * The reason is a fixed literal chosen by [classify], never a value read from
 * the profile, so a line says what the board could not use without saying
 * anything about the player it belonged to.
 */
internal fun skipLine(seasonId: String, reason: String): String =
  "S64_LEADERBOARD_SKIP seasonId=$seasonId reason=$reason"

/**
 * Absent or explicitly `null` names the SERVER's current season, resolved
 * through the port's `seasons/current` read; a value that IS present must be
 * a non-blank string, and anything else is refused rather than guessed.
 *
 * `null` and `undefined` are not separable here — the callable's JSON payload
 * drops an undefined field on the way in, and Kotlin/JS reads both as null — so
 * an explicit null means "no season given", the same answer as no key at all.
 * The distinction that matters is the one this draws: silence is resolved by
 * ONE current-season read (the server's answer, never a client claim), while a
 * named season is validated locally and never opens the current-season
 * document — a caller asking for a historical board by name (RD27's season
 * isolation) must get exactly that board, whether or not the server now calls
 * another season current, so no named season is checked against the current
 * one. A malformed name gets an error, because a caller that meant `s2` and
 * silently received `s1` is the one failure mode a season-scoped board cannot
 * afford (RD24).
 */
private suspend fun resolveSeason(db: LeaderboardDb, value: Any?): String {
  val message = "getRankedLeaderboard: seasonId must be a non-blank string."
  return when (value) {
    null -> db.currentSeasonId()
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
 * already the server's answer, `tier`/`tierArabic`/`division` are the three
 * names of ONE resolved rung taken straight off [rankLadder] (division is ""
 * for King, which is what makes the board render King as one ordered group
 * instead of inventing "King I", and its Arabic title is the ladder's own,
 * never a second table kept here), and nothing else rides along. The
 * playerId that ordered the board is deliberately NOT in here.
 */
private fun displayRow(row: LeaderboardRow, nameAndInitial: Pair<String, String>?): Map<String, Any?> = mapOf(
  "position" to row.position,
  "displayName" to (nameAndInitial?.first ?: FALLBACK_DISPLAY_NAME),
  "avatarInitial" to (nameAndInitial?.second ?: FALLBACK_AVATAR_INITIAL),
  "tier" to row.rank.tier,
  "tierArabic" to row.rank.arabic,
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
      // One past the cap, so [requireBoardWithinCap] can tell "at it" from
      // "over it" from the count it already has and without a second query.
      // Without a limit this read has no bound at all — the SDK pulls every
      // matching document, so one oversized season would cost the whole
      // collection on every call.
      .limit(MAX_SEASON_PROFILES + 1)
      .get().await()
    return snapshot.docs.map { doc ->
      @Suppress("UNCHECKED_CAST")
      StoredProfile(
        playerId = doc.id,
        fields = dynamicToKotlin(doc.data()) as? Map<String, Any?> ?: emptyMap(),
      )
    }
  }

  /**
   * The one current-season read, resolved per request — no caching: the
   * season job may commit `seasons/current` between two calls, and a stale
   * cache would serve the reset's target as if it had already landed (RD24:
   * the outgoing season stays served until the document itself names the new
   * one). The raw JS snapshot is converted through [dynamicToKotlin] exactly
   * as [readSeasonProfiles] converts its documents; parsing is
   * [currentSeasonIdOf]'s, shared with the profile path.
   */
  override suspend fun currentSeasonId(): String {
    val snap = db.collection(SEASONS_COLLECTION).doc(CURRENT_SEASON_DOC).get().await()
    return currentSeasonIdOf(snap.exists, dynamicToKotlin(snap.data()))
  }
}
