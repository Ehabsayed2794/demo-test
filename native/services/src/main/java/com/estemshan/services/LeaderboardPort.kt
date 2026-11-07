package com.estemshan.services

import com.estemshan.services.model.Reasons
import com.google.firebase.functions.*
import kotlinx.coroutines.tasks.await

/**
 * S64 (RD24, RD27) — the seam over the `getRankedLeaderboard` callable.
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   UI → LeaderboardPort → (Cloud Functions board read) → Firestore
 *
 * WHY THE SEAM — the same reason every service boundary in this module is an
 * interface: this repo ships no Firebase fake by design, so the seam is what
 * lets a hermetic JVM test stand in for the callable. There is no leaderboard
 * screen yet; the port ships behind its own mapper test rather than growing a
 * caller just to avoid unused surface, the [MatchmakingPort] precedent.
 *
 * WHY A CALLABLE — `firestore.rules` keeps `rp` and the rank fields off a
 * client's read of another player's profile, so the board cannot be assembled
 * on a device even if it wanted to be. The Admin SDK orders one season's
 * profiles and returns rows that already carry their position. RD27 is
 * explicit: "The client may read, never order." Nothing in this file sorts,
 * re-ranks, recomputes a position, clamps an RP, or repairs a row. A board the
 * client cannot trust is refused whole.
 */
interface LeaderboardPort {

  /**
   * Reads one page of one season's board.
   *
   * [seasonId] `null` means "the server's current season". The returned
   * [Page.seasonId] is authoritative and is the only season the page
   * describes — the client never substitutes a season id of its own. A
   * non-null [seasonId] is a current or historical board request, and the
   * response must echo that exact value or the call fails closed with
   * [Outcome.Failed].
   *
   * [limit] defaults to [DEFAULT_LIMIT] and is neither clamped nor validated
   * here: the server accepts 1..100 and rejects anything else with
   * `INVALID_ARGUMENT`. An out-of-range value is the caller's programming
   * error, and the port surfaces the server's rejection rather than hiding it.
   *
   * One self-contained round-trip: no cache, no retry loop, no snapshot
   * listener. [Page.rows] is the server's list, in the server's order,
   * unchanged. [Page.total] is the whole board's size, so
   * `rows.size <= total` always.
   */
  suspend fun board(seasonId: String? = null, limit: Int = DEFAULT_LIMIT): Outcome

  /**
   * One successfully parsed page.
   *
   * [rows] is exactly the server's list, in the server's order. [you] is the
   * caller's own row even when it falls outside the requested [rows] — for
   * example a player ranked below [limit] — and is `null` when the caller has
   * no profile in this season. A screen renders [rows] verbatim and may pin
   * [you] as "your rank"; it must not use it to reorder anything.
   */
  data class Page(
    val seasonId: String,
    val rows: List<Row>,
    val you: Row?,
    val total: Int,
    val skipped: Int,
  )

  /**
   * One display-safe row, as the server projected it.
   *
   * There is deliberately no uid, email, coin balance, highest rank, or
   * placement state here: the callable removed those fields, and this type
   * must not reintroduce them. [position] is the server's 1-based competition
   * rank (ties share and skip: 1, 2, 2, 4); [division] is `""` for King and is
   * never `null`. [tier], [tierArabic], and [division] are the server's
   * already-resolved rung — this module keeps no second ladder table that
   * could disagree with it.
   */
  data class Row(
    val position: Int,
    val displayName: String,
    val avatarInitial: String,
    val tier: String,
    val tierArabic: String,
    val division: String,
    val rp: Int,
    val seasonId: String,
    val isCurrentPlayer: Boolean,
  )

  /**
   * The two terminal answers. [Failed.reason] is one of
   * [Reasons.INVALID_ARGUMENT], [Reasons.UNAUTHENTICATED], or
   * [Reasons.UNAVAILABLE]; callers branch on it and never parse
   * [Failed.message], which is display copy.
   */
  sealed interface Outcome {
    data class Loaded(val page: Page) : Outcome
    data class Failed(val reason: String, val message: String) : Outcome
  }

  companion object {
    const val DEFAULT_LIMIT = 100
  }
}

/**
 * The production [LeaderboardPort] — a thin typed read over the
 * `getRankedLeaderboard` callable's response map.
 *
 * Deliberately thin: one round-trip, no retry, no cache, no local ordering, no
 * snapshot listener. The server already decided the page; the only decisions
 * left here are which season was requested and what a failure means to the
 * screen. The request sends `seasonId` only when the caller named one, because
 * an absent field is the server's own "current season" — the client must not
 * invent a default season id. `limit` always rides along; the server owns its
 * valid range.
 *
 * Failure mapping is delegated on the code's [String] name, not the SDK enum,
 * so [LeaderboardResponse] stays testable on a plain JVM: the enum's static
 * initializer needs the Android runtime. A non-Firebase throw is transport —
 * unreachable, interrupted, or no network — and is [Reasons.UNAVAILABLE].
 */
class CallableLeaderboard(
  private val functions: FirebaseFunctions,
) : LeaderboardPort {

  private val callable = functions.getHttpsCallable(NAME)

  override suspend fun board(seasonId: String?, limit: Int): LeaderboardPort.Outcome {
    val request = buildMap {
      if (seasonId != null) put(SEASON_ID, seasonId)
      put(LIMIT, limit)
    }

    val data = try {
      callable.call(request).await().getData()
    } catch (e: FirebaseFunctionsException) {
      // A denial the server chose. Read the name so the mapping needs no
      // Android runtime, and never echo the server's message to the screen.
      return LeaderboardResponse.forCodeName(e.code.name)
    } catch (e: Exception) {
      // Transport-level: no network, an interrupted await, or the callable
      // could not be reached at all. None of these is an answer about a board.
      return LeaderboardPort.Outcome.Failed(Reasons.UNAVAILABLE, UNAVAILABLE_MESSAGE)
    }

    // The requested season is the one trust anchor: a response that echoes a
    // different season is refused rather than rendered under the wrong label.
    return LeaderboardResponse.forData(data, seasonId)
  }

  private companion object {
    const val NAME = "getRankedLeaderboard"
    const val SEASON_ID = "seasonId"
    const val LIMIT = "limit"
  }
}

/**
 * The callable's response → [LeaderboardPort.Outcome] mapping, split out of
 * the class so both halves of this story's contract — which keys the client is
 * allowed to read, and which transport codes mean what — are pinned in a
 * hermetic JVM test rather than only against the Functions emulator. Both live
 * in this file, beside the only caller, so the transport and its reader cannot
 * drift apart, exactly as [SeatReclaimResponse] keeps its pair together.
 *
 * The mapper is a whitelist by construction. It reads the contracted keys and
 * no others: identity, contact, currency, highest rank, and placement state
 * never existed in the server's row projection, and a response that carries
 * extras anyway must produce exactly the same [LeaderboardPort.Page] as the
 * lean one. It never sorts, renumbers, clamps, or repairs — a shape that
 * violates the contract is refused whole, with one generic message that echoes
 * no field value.
 */
object LeaderboardResponse {

  /**
   * Parses one successful response. [requestedSeasonId] is the season the
   * caller asked for, or `null` when it asked for the server's current one.
   *
   * Checks, in order:
   * - the body is a map with `ok == true`;
   * - the echoed `seasonId` is a non-blank string and, when a season was
   *   requested, exactly equal to it (case-sensitive) — the one check that
   *   keeps an `s2` request from silently rendering `s1`;
   * - `rows` is a list of valid rows (an empty list is valid);
   * - `total` and `skipped` are whole, finite, non-negative counts, and
   *   `total >= rows.size`;
   * - `you` is `null` or a valid row;
   * - positions are a page the server could have produced: the first is 1,
   *   none decreases, none exceeds `total`, and the current-player flags agree
   *   with `you`.
   *
   * Any failure is [LeaderboardPort.Outcome.Failed] with
   * [Reasons.UNAVAILABLE] and [MALFORMED_MESSAGE] — never a partially repaired
   * board, and never a payload value in the message.
   */
  fun forData(data: Any?, requestedSeasonId: String?): LeaderboardPort.Outcome {
    val fields = data as? Map<*, *> ?: return malformed()
    if (fields[OK] != true) return malformed()

    val seasonId = fields[SEASON_ID] as? String ?: return malformed()
    if (seasonId.isBlank()) return malformed()
    if (requestedSeasonId != null && seasonId != requestedSeasonId) return malformed()

    val rawRows = fields[ROWS] as? List<*> ?: return malformed()
    val rows = rawRows.map { parseRow(it, seasonId) ?: return malformed() }

    val total = wholeNumber(fields[TOTAL]) ?: return malformed()
    val skipped = wholeNumber(fields[SKIPPED]) ?: return malformed()
    if (total < rows.size) return malformed()

    // Absent and explicit-null both mean "the caller has no row this season";
    // the page treats them the same, and there is no data to trust either way.
    val you = fields[YOU]?.let { parseRow(it, seasonId) ?: return malformed() }

    // Server-computed positions are data, not a hint. A descending, shuffled,
    // or out-of-range page is refused rather than re-sorted: repairing it
    // would be the client ordering, which RD27 forbids.
    if (rows.isNotEmpty() && rows.first().position != 1) return malformed()
    if (rows.zipWithNext().any { (earlier, later) -> earlier.position > later.position }) return malformed()
    if (rows.any { it.position > total }) return malformed()
    if (you != null && you.position > total) return malformed()

    val currentRows = rows.filter { it.isCurrentPlayer }
    if (currentRows.size > 1) return malformed()
    val current = currentRows.firstOrNull()
    if (current != null && you != current) return malformed()
    if (you != null && !you.isCurrentPlayer) return malformed()

    return LeaderboardPort.Outcome.Loaded(
      LeaderboardPort.Page(
        seasonId = seasonId,
        rows = rows,
        you = you,
        total = total,
        skipped = skipped,
      ),
    )
  }

  /**
   * The transport-code mapping, decided on the code's [String] name so a plain
   * JVM test can exercise it (the SDK enum cannot initialize there).
   *
   * `INVALID_ARGUMENT` is the server's rejection of the request itself — a
   * blank season or an out-of-range limit. The auth codes ask for a sign-in.
   * Everything else is [Reasons.UNAVAILABLE], including
   * `RESOURCE_EXHAUSTED`: that is the server's oversized-season refusal, and
   * the board is too large for one read, so retrying the same request cannot
   * help and there is no partial board to show. No fourth reason is invented.
   */
  fun forCodeName(name: String): LeaderboardPort.Outcome = when (name) {
    CODE_INVALID_ARGUMENT ->
      failed(Reasons.INVALID_ARGUMENT, INVALID_ARGUMENT_MESSAGE)
    CODE_UNAUTHENTICATED, CODE_PERMISSION_DENIED ->
      failed(Reasons.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE)
    else ->
      failed(Reasons.UNAVAILABLE, UNAVAILABLE_MESSAGE)
  }

  /**
   * One row, or `null` when any contracted property is missing or unusable.
   * The page's own season is passed in so a row from another season can never
   * slip into the page (RD24: one season only, never blended).
   *
   * No ladder validation happens here. `:services` must not keep a second copy
   * of the tier/division table; the server resolved the rung, and this layer
   * only checks that the resolved names are present and usable.
   */
  private fun parseRow(raw: Any?, pageSeasonId: String): LeaderboardPort.Row? {
    val fields = raw as? Map<*, *> ?: return null
    val position = wholeNumber(fields[POSITION])?.takeIf { it >= 1 } ?: return null
    val displayName = nonBlankText(fields[DISPLAY_NAME]) ?: return null
    val avatarInitial = nonBlankText(fields[AVATAR_INITIAL]) ?: return null
    val tier = nonBlankText(fields[TIER]) ?: return null
    val tierArabic = nonBlankText(fields[TIER_ARABIC]) ?: return null
    // Empty is the King's valid division; null is not a division at all.
    val division = fields[DIVISION] as? String ?: return null
    val rp = wholeNumber(fields[RP]) ?: return null
    val rowSeasonId = nonBlankText(fields[SEASON_ID]) ?: return null
    if (rowSeasonId != pageSeasonId) return null
    val isCurrentPlayer = fields[IS_CURRENT_PLAYER] as? Boolean ?: return null

    return LeaderboardPort.Row(
      position = position,
      displayName = displayName,
      avatarInitial = avatarInitial,
      tier = tier,
      tierArabic = tierArabic,
      division = division,
      rp = rp,
      seasonId = rowSeasonId,
      isCurrentPlayer = isCurrentPlayer,
    )
  }

  private fun malformed(): LeaderboardPort.Outcome =
    failed(Reasons.UNAVAILABLE, MALFORMED_MESSAGE)

  private fun failed(reason: String, message: String): LeaderboardPort.Outcome =
    LeaderboardPort.Outcome.Failed(reason, message)

  /**
   * A whole, finite, non-negative number as an [Int], or `null`.
   *
   * Firestore can hand the same count to different clients as an Int, a Long,
   * or a Double, so all three widths are accepted — but only when the value is
   * integral and exactly representable as the Int the port exposes. A
   * fractional, negative, non-finite, or out-of-range value is refused, never
   * rounded or clamped: a clamped RP or position would be this layer inventing
   * a row the server did not compute.
   */
  private fun wholeNumber(value: Any?): Int? {
    val number = value as? Number ?: return null
    val asDouble = number.toDouble()
    if (!asDouble.isFinite() || asDouble < 0.0 || asDouble > Int.MAX_VALUE.toDouble()) return null
    if (asDouble != asDouble.toInt().toDouble()) return null
    return asDouble.toInt()
  }

  private fun nonBlankText(value: Any?): String? =
    (value as? String)?.takeIf { it.isNotBlank() }

  private const val OK = "ok"
  private const val SEASON_ID = "seasonId"
  private const val ROWS = "rows"
  private const val YOU = "you"
  private const val TOTAL = "total"
  private const val SKIPPED = "skipped"

  private const val POSITION = "position"
  private const val DISPLAY_NAME = "displayName"
  private const val AVATAR_INITIAL = "avatarInitial"
  private const val TIER = "tier"
  private const val TIER_ARABIC = "tierArabic"
  private const val DIVISION = "division"
  private const val RP = "rp"
  private const val IS_CURRENT_PLAYER = "isCurrentPlayer"

  private const val CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT"
  private const val CODE_UNAUTHENTICATED = "UNAUTHENTICATED"
  private const val CODE_PERMISSION_DENIED = "PERMISSION_DENIED"
}

/**
 * The one generic malformed message. Fixed copy: it never includes a field
 * value, a name, a uid, or any part of the payload, so a bad response cannot
 * leak profile data through the screen.
 */
private const val MALFORMED_MESSAGE = "The leaderboard response was not readable."

/** The server rejected the request itself (a blank season, an out-of-range limit). */
private const val INVALID_ARGUMENT_MESSAGE = "The leaderboard request was rejected."

/** The caller is not authenticated, or is not permitted to read the board. */
private const val UNAUTHENTICATED_MESSAGE = "Sign in to view the leaderboard."

/** The board could not be read this time — transport, server, or an oversized season. */
private const val UNAVAILABLE_MESSAGE = "The leaderboard is unavailable right now. Please try again."
