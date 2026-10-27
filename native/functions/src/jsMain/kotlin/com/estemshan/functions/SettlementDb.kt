package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.LadderRank
import com.estemshan.engine.MatchMode
import com.estemshan.engine.MatchOutcome
import com.estemshan.engine.PlacementState
import com.estemshan.engine.RankedProfile
import com.estemshan.engine.RankedStats
import com.estemshan.engine.RpMatchInput
import com.estemshan.functions.Settlement.ConvergedMatch
import com.estemshan.functions.Settlement.SettleDecision
import com.estemshan.engine.accumulateRankedStat
import com.estemshan.engine.applyDemotionShield
import com.estemshan.engine.finalRpDelta
import com.estemshan.engine.ladderRankOf
import com.estemshan.engine.partyTierOrder
import com.estemshan.engine.placementOutcome
import com.estemshan.engine.progressionMismatch
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder
import com.estemshan.engine.resolveRanked
import com.estemshan.engine.updateHighestRank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * S50 settlement I/O + orchestration (RD12/RD13).
 *
 * The once-only contract: claim-check, recompute, correction write and
 * settlement record commit inside ONE Admin transaction on the claim doc
 * (`matches/{matchId}/settlement/settle`). Two racers read absent, one
 * commits, the other retries into the recorded outcome — correction
 * writes and the claim can never diverge, so a crash cannot leave a
 * claimed-but-uncorrected match. This single-transaction shape is why the
 * settlement does not compose S42's two-phase IdempotencyGuard (claim
 * then commit would leave exactly that window); the guard's contract —
 * first invocation executes, replays return the recorded outcome — is
 * what this guarantees instead.
 *
 * Ranked progression (RD1/RD3/RD12) is applied HERE, in this same
 * transaction, rather than by a separate guard-composed call as S51's note
 * first anticipated: the claim's once-only property is what makes an RP
 * award once-only, and applying it in a second transaction would reopen
 * exactly the claimed-but-unapplied window this shape exists to close.
 * Every seated profile is read before any write (Firestore forbids a read
 * after a write in one transaction), so a redelivery can neither re-award
 * nor settle against a stale pre-image.
 *
 * Ports keep every Firestore touch behind interfaces so the whole
 * orchestration (gate → recompute → write/replay) is unit-testable with
 * fakes; only AdminSettlementDb loads the SDK.
 */
interface SettlementDb {
  suspend fun readMatch(matchId: String): Map<String, Any?>?
  suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>>
  suspend fun <T> transact(block: suspend (SettlementTx) -> T): T

  /**
   * S62 RP audit ledger: best-effort append of one audit record. Abstract
   * so every port wires the ledger consciously; the Admin implementation
   * writes create-if-absent, once per [SettlementLedger.appendTargets] entry,
   * keyed by [SettlementLedger.ledgerDocId], and settleOnce calls this inside
   * a catch - an append failure is logged and never changes the settlement
   * outcome.
   */
  suspend fun appendLedger(record: Map<String, Any?>)

  /**
   * S62: the bounded, most-recent-first window of CORRECTIONS the repeat
   * detector reads ([SettlementLedger.DETECT_WINDOW] documents, drawn from
   * [SettlementLedger.COLLECTION_CORRECTIONS]). Called only after a correction
   * applied; the caller catches failures. Reading corrections alone, instead
   * of the whole ledger, is what stops R25 going blind as settlement volume
   * grows - and it is a write-time materialization precisely so this read
   * needs no composite index. See [AdminSettlementDb.readCorrectionWindow].
   */
  suspend fun readCorrectionWindow(): List<Map<String, Any?>>

  interface SettlementTx {
    suspend fun readClaim(matchId: String): Map<String, Any?>?

    /**
     * The season a stale seat is lazily reset into, read ONCE inside the
     * settlement transaction and before any write (Q22, V1-C10): every seated
     * profile is judged against exactly the season this transaction commits
     * into, so a seat that missed the bulk sweep is reset here — exactly once,
     * before its RP is folded — and no settlement can leave a profile behind
     * the current season. The recompute and the claim never consult it; a
     * replay returns before it.
     *
     * This is NOT the board's current season while a reset is `RUNNING`: the
     * sweep stamps profiles with the target before the season job flips
     * `seasonId`, so judging them against the outgoing season would reset the
     * players the sweep just moved BACKWARDS, irreversibly (R27). The Admin
     * implementation answers with [lazyResetSeasonOf], which serves the
     * in-flight target for exactly that window.
     *
     * NULL means the singleton does not exist — the fresh-deployment
     * bootstrap: the Admin implementation answers null, the alignment serves
     * the bootstrap literal to a seat that carries no season beyond it, and a
     * seat already in a NON-default season fails the whole settlement closed
     * rather than being silently reset (nobody declared that season dead).
     * The DEFAULT is the bootstrap literal itself — which keeps every
     * existing fake compiling and preserves the pre-Q22 fold, where a missing
     * `seasonId` parsed as `s1` — and an EXISTING document is authoritative,
     * read from the S59 singleton (`seasons/current`) in-transaction through
     * [lazyResetSeasonOf], failing closed on a malformed document.
     */
    suspend fun currentLazyResetSeason(): String? = DEFAULT_SEASON_ID

    /**
     * The seated player's own `players/{uid}` document, or null when it does
     * not exist. Read INSIDE the settlement transaction: the RP fold needs the
     * previous RP as of the commit, and reading it outside would let two
     * racers settle against the same pre-image.
     */
    suspend fun readProfile(uid: String): Map<String, Any?>?

    fun writeCorrection(matchId: String, scores: Map<String, Int>, winners: List<String>, version: Int)
    fun writeClaim(matchId: String, record: Map<String, Any?>)

    /**
     * Merge-write ONLY the ranked-progression contract fields of
     * `players/{uid}`: `rp`, `tier`, `division`, `demotionShield`,
     * `highestRankTier`, `highestRankDivision`, `seasonId`,
     * `placementState`, `rankedGamesPlayed`, `rankedKingCount`,
     * `rankedSecondCount`, `rankedThirdCount`, `rankedKozCount`. Anything
     * else the document holds — displayName, the room/match pointers, the
     * legacy `rank` string — is untouched by construction: the Admin SDK
     * bypasses rules, so the field set is exactly what the caller passes
     * here. The four derived percentages are never written (RD23: they are
     * derived over gamesPlayed, not stored).
     */
    fun writeProfile(uid: String, fields: Map<String, Any?>)
  }
}

private const val CLAIM_PATH = "settlement"
private const val CLAIM_DOC = "settle"

private fun executedResponse(
  recomputed: Settlement.RecomputedMatch,
  matchId: String,
): Map<String, Any?> = mapOf(
  "ok" to true,
  "settled" to true,
  "replayed" to false,
  "matchId" to matchId,
  "corrected" to recomputed.corrected,
  "finalScores" to recomputed.finalScores,
  "winnerIds" to recomputed.winnerIds,
  "claimedScores" to recomputed.claimedScores,
  "claimedWinners" to recomputed.claimedWinners,
  "rounds" to recomputed.rounds,
)

/**
 * The claim-replay answer shared by both settlement paths: the recorded
 * outcome, `replayed = true`, and THIS caller's `you` slice when the claim
 * carries movements (RD13 — one claim is replayed by four different clients,
 * and each sees only its own numbers). A claim recorded before progression
 * existed has no `movements`, so the replay answers with no `you` and no
 * crash. A malformed claim fails closed.
 */
private fun replayClaim(
  existing: Map<String, Any?>,
  uid: String,
  matchId: String,
): Map<String, Any?> {
  @Suppress("UNCHECKED_CAST")
  val recorded = existing["outcome"] as? Map<String, Any?>
    ?: throw HttpsError(
      "failed-precondition",
      "settleMatch: settlement record for '$matchId' is malformed.",
    )
  val replayed = recorded.toMutableMap()
  replayed["replayed"] = true
  youOf(existing["movements"], uid)?.let { replayed["you"] = it }
  return replayed
}

private fun claimRecord(
  uid: String,
  response: Map<String, Any?>,
  movements: Map<String, Map<String, Any?>>,
): Map<String, Any?> = mapOf(
  "settledBy" to uid,
  "settledAt" to FieldValue.serverTimestamp(),
  // Caller-neutral by construction: `you` is the CALLER's slice of
  // [movements], added on the way out and never stored here (RD13 — one
  // claim document is replayed by four different clients, so a stored `you`
  // would be a stored leak).
  "outcome" to response,
  // {uid: {previousRp, rp, delta}} for every seated player. Admin-only: this
  // document lives under matches/{id}/settlement, which no client may read,
  // and it mirrors players/{uid}, whose documents are owner-`get`-only.
  "movements" to movements,
)

/**
 * One seat's raw pre-image, read before any write is issued. The typed
 * parse into [ParsedSeat] runs only after every profile has been read and
 * before the first write, so a corrupt field aborts the whole settlement
 * before anything lands (Firestore forbids a read after a write anyway).
 */
private data class SeatInput(val uid: String, val seat: String, val profile: Map<String, Any?>)

// ── the players/{uid} ranked contract, parsed fail-closed ─────────────

/**
 * The bootstrap season (RD24's seasons start at one): the season a missing
 * `players/seasonId` adopts, and the EFFECTIVE current season when the
 * `seasons/current` singleton does not exist — for a seat that carries no
 * season beyond it. A seat already in a non-default season with no singleton
 * to judge it by fails the whole settlement closed instead of being reset to
 * this literal. An existing seasonId equal to the current one is preserved; a
 * stale one is moved to the current season by the lazy alignment
 * ([alignSeatedProfilesToSeason]) that runs BEFORE the fold (Q22/V1-C10) —
 * the bulk sweep is no longer the only writer that moves a season forward.
 */
private const val DEFAULT_SEASON_ID = "s1"

/** The legacy `players/rank` string meaning "no rank has ever been assigned". */
private const val LEGACY_UNRANKED = "Unranked"

/**
 * A corrupt or missing profile value fails the WHOLE settlement closed: no
 * profile write for any seat, no claim, so the match stays settleable once
 * the data is repaired — and no seat is settled ahead of the seat that
 * failed. Fabricating a default for one seat while the other three settle
 * is the same fabrication the missing-document rule refuses, one level
 * down.
 */
private fun failClosed(uid: String, matchId: String, because: String): Nothing =
  throw HttpsError(
    "failed-precondition",
    "settleMatch: seated player '$uid' of match '$matchId' has $because — nothing writes; " +
      "a Ranked table applies every seat's progression or none.",
  )

/**
 * A finite, whole number in [min]..[max], or null. Firestore/JS numbers arrive
 * as [Double] and [Number.toInt] truncates, so a fraction, NaN, infinity or an
 * out-of-Int value must be refused BEFORE any conversion; this helper is the
 * one gate every stored progression number passes through.
 */
private fun wholeNumberInRange(value: Number, min: Int, max: Int): Int? {
  val asDouble = value.toDouble()
  if (!asDouble.isFinite() || asDouble % 1.0 != 0.0 ||
    asDouble < min.toDouble() || asDouble > max.toDouble()
  ) {
    return null
  }
  return asDouble.toInt()
}

/**
 * One seated player's parsed pre-match Ranked state: the profile fields the
 * pipeline reads ([profile]) and the five STORED statistic counters
 * ([stats]) — both exactly as the profile document held them before this
 * settlement touches anything, with the contract's defaults applied.
 */
private data class ParsedSeat(
  val uid: String,
  val seat: String,
  val profile: RankedProfile,
  val stats: RankedStats,
)

/**
 * The ladder rung named by a tier/division pair on [raw], or null when both
 * keys are absent (the caller derives the rung from the RP itself, the only
 * derivation that cannot disagree with the resolution). A pair that names no
 * rung of the one [rankLadder] table — or a pair with exactly one of the two
 * keys — is corrupt, not partial: fail the whole settlement closed rather
 * than guess which rung was meant.
 */
private fun rankIdentityOf(
  uid: String,
  matchId: String,
  raw: Map<String, Any?>,
  tierKey: String,
  divisionKey: String,
): LadderRank? {
  val tier = raw[tierKey]
  val division = raw[divisionKey]
  if (tier == null && division == null) return null
  if (tier !is String || division !is String) {
    failClosed(
      uid, matchId,
      "a half-written players/$tierKey + players/$divisionKey identity " +
        "(${tier ?: "absent"} / ${division ?: "absent"}) — the pair is written together or not at all",
    )
  }
  val rung = rankLadder.firstOrNull { it.tier == tier && it.division == division }
    ?: failClosed(
      uid, matchId,
      "a players/$tierKey + players/$divisionKey identity ('$tier' / '$division') that " +
        "names no rung of the rank ladder",
    )
  return rung
}

/**
 * The rung a legacy `players/rank` string names — "Gold III" / "King" — or
 * null when it names none. The legacy field is a plain word-and-division
 * string ("Unranked" among them); nothing else about its shape is trusted,
 * and an unparseable value is never defaulted from.
 */
private fun legacyRankRung(value: String): LadderRank? =
  rankLadder.firstOrNull { it.tier == value || "${it.tier} ${it.division}".trim() == value }

/**
 * Parses one `players/{uid}` document into the engine's [RankedProfile] —
 * the shared contract every seated profile is read through, fail-closed on
 * every present-but-invalid value:
 *
 * - `rp` missing (absent, or the JSON null a client can leave) reads as 0
 *   ONLY for an account that is Bronze III or unplaced — RD30's unplaced
 *   read. A placed higher rung — named by tier/division, or by a legacy
 *   `players/rank` that resolves to one — with no rp is corrupt: inventing
 *   0 would award a placed account a first match's progression. A present
 *   non-number is the long-standing non-numeric refusal; a present number
 *   must be finite, whole and within 0..Int.MAX_VALUE.
 * - tier/division missing ⇒ [rankAt] of the RP itself; present must name a
 *   rung of the one ladder table ([rankIdentityOf]). A present identity that
 *   disagrees with [rankAt] of the RP is refused by [progressionMismatch],
 *   never repaired.
 * - seasonId missing ⇒ the parse stores the DEFAULT_SEASON_ID bootstrap
 *   literal; alignSeatedProfilesToSeason then adopts the CURRENT season before
 *   the fold. An absent seasons/current also reads as DEFAULT_SEASON_ID. A
 *   present seasonId must be a String (blank/non-string fails closed).
 * - highestRank missing (highestRankTier + highestRankDivision absent) ⇒
 *   the current rung; present must name a rung. [updateHighestRank] below
 *   never lowers whatever it holds.
 * - placementState missing ⇒ SKIPPED for an account that carries
 *   progression (rp > 0) or a legacy rank other than [LEGACY_UNRANKED] — a
 *   legacy placed account, RD30 — and NOT_STARTED otherwise (a fresh
 *   account). Present must be one of the four states.
 * - demotionShield missing ⇒ false (RD7's default: every profile written
 *   before the shield existed reads as unshielded); present must be a
 *   Boolean.
 */
private fun parseRankedProfile(
  uid: String,
  matchId: String,
  raw: Map<String, Any?>,
): RankedProfile {
  val rank = rankIdentityOf(uid, matchId, raw, "tier", "division")
  val rp = when (val value = raw["rp"]) {
    null -> {
      if (rank != null && rank.lowerBound > 0) {
        failClosed(
          uid, matchId,
          "no players/rp under a placed rank (${rank.tier} ${rank.division}) — it is not read " +
            "as 0 RP, which would award a placed account a first match's progression",
        )
      }
      // No tier/division identity: the unplaced read still refuses a legacy
      // rank that NAMES a higher rung — the same fabrication, one layer down.
      if (rank == null) {
        val legacyRung = (raw["rank"] as? String)?.let { legacyRankRung(it) }
        if (legacyRung != null && legacyRung.lowerBound > 0) {
          failClosed(
            uid, matchId,
            "no players/rp under a placed legacy rank ('${legacyRung.tier} ${legacyRung.division}') — " +
              "it is not read as 0 RP, which would award a placed account a first match's progression",
          )
        }
      }
      0
    }
    is Number -> wholeNumberInRange(value, 0, Int.MAX_VALUE)
      ?: failClosed(
        uid, matchId,
        "a players/rp ($value) that is not a finite whole number from 0 to ${Int.MAX_VALUE} — " +
          "RP must be a finite whole number inside the Int range",
      )
    else -> throw HttpsError(
      "failed-precondition",
      "settleMatch: seated player '$uid' of match '$matchId' has a non-numeric players/rp " +
        "(${raw["rp"]}) — nothing wrote; it is not read as 0 RP, which would award " +
        "the seat a first match's progression.",
    )
  }
  val rung = rank ?: rankAt(rp)
  val highest = rankIdentityOf(uid, matchId, raw, "highestRankTier", "highestRankDivision") ?: rung
  val seasonId = when (val value = raw["seasonId"]) {
    null -> DEFAULT_SEASON_ID
    is String -> value
    else -> failClosed(uid, matchId, "a non-string players/seasonId ($value)")
  }
  val legacyRank = when (val value = raw["rank"]) {
    null -> null
    is String -> value
    else -> failClosed(uid, matchId, "a non-string legacy players/rank ($value)")
  }
  val placementState = when (val value = raw["placementState"]) {
    null ->
      if (rp > 0 || (legacyRank != null && legacyRank != LEGACY_UNRANKED)) {
        PlacementState.SKIPPED
      } else {
        PlacementState.NOT_STARTED
      }
    is String -> PlacementState.entries.firstOrNull { it.name == value }
      ?: failClosed(uid, matchId, "a players/placementState ('$value') that is not a placement state")
    else -> failClosed(uid, matchId, "a non-string players/placementState ($value)")
  }
  val demotionShield = when (val value = raw["demotionShield"]) {
    null -> false
    is Boolean -> value
    else -> failClosed(uid, matchId, "a non-boolean players/demotionShield ($value)")
  }
  val profile = RankedProfile(
    tier = rung.tier,
    division = rung.division,
    rp = rp,
    seasonId = seasonId,
    highestRank = highest,
    placementState = placementState,
    demotionShield = demotionShield,
  )
  // RD3: the stored tier/division identity and the RP-derived rung must agree.
  // A mismatch is corrupt data, never repaired here; fail the whole table
  // closed before any write so the match stays settleable once it is fixed.
  progressionMismatch(profile)?.let { mismatch ->
    failClosed(uid, matchId, mismatch)
  }
  return profile
}

/**
 * Parses the five STORED statistic counters off a profile (RD23: the four
 * percentages are derived over gamesPlayed, never stored, and a settlement
 * never writes them). Missing ⇒ 0; present must be a finite whole number in
 * the Int range and non-negative. Anything else — a string, a fraction, NaN,
 * infinity, an out-of-Int value or a negative counter — is corrupt, and the
 * whole settlement fails closed rather than fold a table whose counters
 * cannot be trusted.
 */
private fun parseRankedStats(
  uid: String,
  matchId: String,
  raw: Map<String, Any?>,
): RankedStats {
  fun counter(field: String): Int = when (val value = raw[field]) {
    null -> 0
    is Number -> {
      val count = wholeNumberInRange(value, Int.MIN_VALUE, Int.MAX_VALUE)
        ?: failClosed(
          uid, matchId,
          "a players/$field ($value) that is not a finite whole number within the Int range — " +
            "a counter must be a finite whole number",
        )
      // The finite/integral/range gate runs FIRST, so a fraction such as -0.5
      // is refused as malformed rather than truncated into a negative integer.
      if (count < 0) {
        failClosed(uid, matchId, "a negative players/$field ($value) — a counter cannot be negative")
      }
      count
    }
    else -> failClosed(
      uid, matchId, "a non-numeric players/$field ($value) — a counter is a number or absent",
    )
  }
  return RankedStats(
    gamesPlayed = counter("rankedGamesPlayed"),
    kingCount = counter("rankedKingCount"),
    secondCount = counter("rankedSecondCount"),
    thirdCount = counter("rankedThirdCount"),
    kozCount = counter("rankedKozCount"),
  )
}

/** [SeatInput] + the typed parse, so the fold reads engine types only. */
private fun parseRankedSeat(seat: SeatInput, matchId: String): ParsedSeat =
  ParsedSeat(
    uid = seat.uid,
    seat = seat.seat,
    profile = parseRankedProfile(seat.uid, matchId, seat.profile),
    stats = parseRankedStats(seat.uid, matchId, seat.profile),
  )

/**
 * The stats-only seat parse: the five stored counters plus the ONE profile
 * field the fold reads — placement state. The RP/rank/season/shield fields
 * are deliberately NOT parsed or validated here: a ROOM/UNRANKED settlement
 * writes none of them, and a corrupt Ranked-progression field must not block
 * a match that never touches RP. The [RankedProfile] built below is inert
 * except for [RankedProfile.placementState]; [accumulateRankedStat] reads no
 * other field.
 */
private fun parseStatsOnlySeat(seat: SeatInput, matchId: String): ParsedSeat {
  val placementState = when (val value = seat.profile["placementState"]) {
    null -> PlacementState.NOT_STARTED
    is String -> PlacementState.entries.firstOrNull { it.name == value }
      ?: failClosed(
        seat.uid, matchId,
        "a players/placementState ('$value') that is not a placement state",
      )
    else -> failClosed(seat.uid, matchId, "a non-string players/placementState ($value)")
  }
  return ParsedSeat(
    uid = seat.uid,
    seat = seat.seat,
    profile = RankedProfile(
      seasonId = DEFAULT_SEASON_ID,
      placementState = placementState,
    ),
    stats = parseRankedStats(seat.uid, matchId, seat.profile),
  )
}

/**
 * The RAW stored `seasonId` — the value the lazy-reset decision needs, taken
 * from the stored map because [parseRankedProfile] stores the
 * DEFAULT_SEASON_ID bootstrap literal for a missing season, and that default
 * is indistinguishable from a recorded one once parsed. Null means the field
 * is absent (the caller adopts the effective lazy-reset season). A present
 * seasonId must be a String and non-string already fails closed in the parse;
 * a present-but-blank one fails closed HERE, mirroring the read path's corrupt
 * branch — a season is adopted or reset, never guessed.
 */
private fun rawSeasonIdOf(uid: String, matchId: String, raw: Map<String, Any?>): String? =
  when (val value = raw["seasonId"]) {
    null -> null
    is String -> value.takeIf { it.isNotBlank() }
      ?: failClosed(uid, matchId, "a blank players/seasonId — the season is adopted or reset, never guessed")
    else -> failClosed(uid, matchId, "a non-string players/seasonId ($value)")
  }

/**
 * Q22's lazy season reset, settlement side (E5.2, V1-C10): every seated
 * profile is aligned to the season the lazy reset must land in BEFORE the
 * fold, inside the same transaction, so settlement can never leave a profile
 * in a dead season — RP accrued into a stale `seasonId` would be progression
 * the current leaderboard cannot see.
 *
 * Per seat, from the RAW stored map ([lazyResetSeason] null = no singleton):
 *
 * - `seasonId` absent ⇒ ADOPT the effective season with no reset — the read
 *   path's lazy merge, mirrored. The parse had defaulted the field to the
 *   bootstrap literal, which would be a fabricated stale season once s2+ is
 *   live, so the adoption is applied to the parsed copy before anything else.
 * - present, equal to the effective season ⇒ untouched (today's behaviour).
 *   During a `RUNNING` reset this is the seat the bulk sweep already moved to
 *   the in-flight TARGET: it is current, not stale, and must never be reset
 *   BACKWARDS into the outgoing season the board still serves (R27).
 * - present and different, WITH a singleton ⇒ ONE reset, planned with the
 *   bulk sweep's own fail-closed [planSeasonReset] so the demotion math and
 *   the from-season guard can never drift between the sweep and this path.
 *   Seats that slept through different numbers of seasons are grouped by the
 *   season they actually recorded — one plan per recorded season, and a
 *   foreign season is never folded. Any plan failure fails the WHOLE
 *   settlement closed, exactly like every other corrupt profile value: no
 *   seat settles ahead of a seat that could not be reset.
 * - present and beyond the bootstrap literal, with NO singleton ⇒ the whole
 *   settlement fails closed before any write: a lazy reset to the bootstrap
 *   season would be a reset no season declared, and an existing season state
 *   is exactly the thing the bootstrap must not silently overwrite.
 *
 * The reset carries placementState, the demotion shield and Highest Rank
 * through the engine's own policies and touches no statistic; the fold then
 * merges only the contract fields on top, which is what preserves them.
 */
private fun alignSeatedProfilesToSeason(
  seatInputs: List<SeatInput>,
  lazyResetSeason: String?,
  matchId: String,
): List<ParsedSeat> {
  val parsed = seatInputs.map { parseRankedSeat(it, matchId) }

  // The season every seat is judged against: the read singleton — the
  // in-flight TARGET while a reset is RUNNING — or the bootstrap literal when
  // no singleton exists at all.
  val effectiveSeasonId = lazyResetSeason ?: DEFAULT_SEASON_ID

  val adopted = parsed.mapIndexed { index, seat ->
    val recorded = rawSeasonIdOf(seat.uid, matchId, seatInputs[index].profile)
    when {
      lazyResetSeason == null && recorded != null && recorded != DEFAULT_SEASON_ID ->
        failClosed(
          seat.uid, matchId,
          "a players/seasonId ('$recorded') from beyond the bootstrap season while " +
            "seasons/current does not exist — nothing wrote; a lazy reset to the " +
            "bootstrap season '$DEFAULT_SEASON_ID' would be a reset no season declared",
        )
      recorded == null ->
        seat.copy(profile = seat.profile.copy(seasonId = effectiveSeasonId))
      else -> seat
    }
  }

  // With no singleton, every seat is either in or adopted into the bootstrap
  // literal (or the alignment above failed closed), so a stale remainder here
  // implies a live singleton and a genuine missed reset.
  val stale = adopted.filter { it.profile.seasonId != effectiveSeasonId }
  if (stale.isEmpty()) return adopted

  val resets = LinkedHashMap<String, RankedProfile>()
  for ((fromSeasonId, seats) in stale.groupBy { it.profile.seasonId }) {
    val plan = planSeasonReset(
      fromSeasonId,
      effectiveSeasonId,
      seats.map { it.uid to it.profile },
    )
    plan.failures.firstOrNull()?.let { failure ->
      failClosed(failure.playerId, matchId, "a failed lazy season reset (${failure.reason})")
    }
    for (reset in plan.resets) resets[reset.playerId] = reset.profile
  }
  return adopted.map { seat ->
    val reset = resets[seat.uid]
    if (reset == null) seat else seat.copy(profile = reset)
  }
}

/** The ladder ordinal [previousRp] sits on now (RD4 input 1). */
private fun ladderOrdinal(previousRp: Int): Int = rankLadder.indexOf(rankAt(previousRp))

/**
 * One recorded movement value. A present non-null value must be a finite,
 * whole number in [min]..[max]; truncating a corrupt claim would report a
 * progression the caller never received, so the replay is refused instead.
 */
private fun movementNumber(uid: String, field: String, value: Any?, min: Int): Int {
  val number = value as? Number
  val whole = number?.let { wholeNumberInRange(it, min, Int.MAX_VALUE) }
  if (whole == null) {
    throw HttpsError(
      "failed-precondition",
      "settleMatch: replay for '$uid' has a corrupt claim movements/$field ($value) — " +
        "it must be a finite whole number in the Int range; the replay is refused rather than fabricated.",
    )
  }
  return whole
}

/**
 * The caller's own movement, sliced out of a claim's `movements` (RD13: a
 * client reads its own progression and nobody else's). Null means "no `you`
 * to report" — a claim written before progression existed, or a caller with
 * no seat in the claim — and is returned as an absent key, never as a
 * fabricated or another seat's number.
 *
 * Absent/null movement keys keep that legacy no-`you` history replaying. A
 * key that IS present with a non-null value is trusted only after the same
 * finite/whole/range gate as stored progression; a corrupt recorded movement
 * refuses the replay instead of being truncated or silently dropped.
 */
private fun youOf(movements: Any?, uid: String): Map<String, Any?>? {
  val mine = (movements as? Map<*, *>)?.get(uid) as? Map<*, *> ?: return null
  val previous = mine["previousRp"]
  val rp = mine["rp"]
  val delta = mine["delta"]
  // Legacy claims can omit any key; absence/null still means "no you", not a
  // corrupt value. Validate nothing until all three are present.
  if (previous == null || rp == null || delta == null) return null
  return linkedMapOf(
    "previousRp" to movementNumber(uid, "previousRp", previous, 0),
    "rp" to movementNumber(uid, "rp", rp, 0),
    "delta" to movementNumber(uid, "delta", delta, Int.MIN_VALUE),
  )
}

/**
 * Reads every seated profile in the settlement transaction — the RP fold's
 * reads, all of them, before any write is issued (Firestore forbids a read
 * after a write in one transaction).
 *
 * Seat → uid comes from the document's own `seats` map, never from
 * [ConvergedMatch.players] index order: `finalScores` is SEAT-keyed (p1..p4)
 * while progression is written per uid, so a uid holds a seat's placement only
 * through that map (the same authority `parseMatch` uses for the dealer
 * marker). Index-correlating the two lists would hand one seat's award to
 * another player.
 *
 * FAILS CLOSED as a whole settlement when a seated uid has no `players/{uid}`
 * document: the settlement cannot tell whether that account is Bronze III or
 * King, and an RP would have to be invented. Fabricating one profile while
 * the other three settle is the same bug one level down — a Ranked table
 * either applies four awards in this transaction or applies none. Refusing
 * here leaves the claim absent, so the match stays settleable once the
 * profile is repaired; silently skipping the seat would strand that player's
 * progression at nothing, permanently, because the claim then forbids any
 * second execution.
 *
 * The TYPED parse ([parseRankedSeat]) runs after these reads and before the
 * first write: every present-but-invalid field — a non-numeric rp, a rank
 * pair that names no rung, a bad placement state, a negative counter —
 * fails the whole settlement the same way, before anything lands.
 */
private suspend fun readSeatInputs(
  tx: SettlementDb.SettlementTx,
  fields: Map<String, Any?>,
  order: List<String>,
  matchId: String,
  onlyUids: Set<String>? = null,
): List<SeatInput> {
  val seats = Settlement.parseSeats(fields)
  return order.mapNotNull { seat ->
    val uid = seats?.get(seat) ?: throw HttpsError(
      "failed-precondition",
      "settleMatch: seat '$seat' of match '$matchId' is not seated to a uid.",
    )
    // S6: a seat whose occupant is not in `players` is a bot seat. The
    // stats-only path passes `onlyUids` so it skips that seat BEFORE reading
    // a profile; the RANKED path passes null and reads every seat.
    if (onlyUids != null && uid !in onlyUids) return@mapNotNull null
    val profile = tx.readProfile(uid) ?: throw HttpsError(
      "failed-precondition",
      "settleMatch: seated player '$uid' of match '$matchId' has no players/ profile — " +
        "nothing wrote, the match stays settleable once that profile exists.",
    )
    SeatInput(uid, seat, profile)
  }
}

/**
 * What one [foldRankedProgression] pass produces, named so the two maps can
 * never be bound to the wrong side at the call site: [movements] is what the
 * claim records and what `you` is sliced from, [profileWrites] is what lands on
 * `players/{uid}`.
 */
private data class RankedProgression(
  val movements: Map<String, Map<String, Any?>>,
  val profileWrites: Map<String, Map<String, Any?>>,
  /**
   * The tier gap when it exceeded RD29's one-tier band, so the caller can log
   * why this settled match awarded no RP. Null when progression ran normally.
   */
  val noAwardTierGap: Int? = null,
)

/**
 * The widest tier gap on a Ranked table, measured along [partyTierOrder]'s
 * derived tier order — never the 19-rung index, which would make a division
 * gap look like a tier gap. RD29 allows at most one tier; a forged or legacy
 * match that got past the join gate is re-checked here, from the trusted
 * profiles read inside the settlement transaction.
 */
private fun rankedTierGap(seated: List<ParsedSeat>): Int {
  if (seated.isEmpty()) return 0
  val order = partyTierOrder
  val indexes = seated.map { order.indexOf(ladderRankOf(it.profile).tier) }
  // ladderRankOf/parseRankedProfile already reject an unknown rung; treating
  // an impossible order miss as an over-band gap keeps the write path closed.
  if (indexes.any { it < 0 }) return Int.MAX_VALUE
  return indexes.max() - indexes.min()
}

/**
 * One pass of the ranked progression fold: the engine's own chain, per seat —
 * placement (RD8/RD17) → dynamic delta (RD4/RD5/RD6, GM6's Mini ×0.5) →
 * floor-clamped resolution (RD3) → the demotion shield wrapped around that
 * already-final resolution (RD7) → Highest Rank raised, never lowered
 * (RD23/RD24) → the career statistics folded through the engine's own
 * placement gate (RD23/RD10) → season and placement state preserved or
 * defaulted (RD24/RD30). The parse ([parseRankedSeat]) supplied the exact
 * pre-match profile every step consumes.
 *
 * `rankDown` is false and stays that way by choice, not by absence: S43 keeps
 * the flag on `rooms/{roomId}` (`MatchModels`: "Matches never carry rankDown:
 * that flag lives on rooms"), and settlement reads only the match document —
 * one callable, one document, no room read. Reading it would mean a second
 * document inside this transaction. At the current rates the flag only caps a
 * loss at ×2; the RD29 tier re-check below is what keeps a cross-tier forged
 * table from being scored at all.
 *
 * RD29 is re-checked here, not merely trusted from the join gate. The tiers
 * come from the profiles read inside this transaction (never from the match
 * request or client payload). A table wider than one tier settles with every
 * seat's RP, rank, shield, and Highest Rank unchanged — the match still
 * records its result and statistics, but awards no progression — and
 * [RankedProgression.noAwardTierGap] lets the caller log the reason.
 *
 * Opponent strength is the mean of the OTHER seats' ordinals; a table of one
 * seat has no opponents and reads as strength 0.
 *
 * The shield (RD7): [applyDemotionShield] wraps the resolution, never a
 * delta. Unarmed it is a pass-through — the RP numbers are exactly the
 * branch's goldens. Armed, [ShieldedResolution.resolution] is what the
 * movements report and the write persists (a blocked demotion holds the rung
 * at its lower bound and clamps the RP to it), and
 * [ShieldedResolution.profile] carries the consumed / re-armed shield state
 * that is persisted. An UNARMED promotion does not arm anything here:
 * [com.estemshan.engine.armShieldOnPromotion] is the engine's unarmed entry
 * point, deliberately outside this settlement — the fold persists the
 * parsed shield and the armed state machine (consume / block / keep-armed),
 * never a brand-new arming.
 *
 * Highest Rank (RD23) and the statistics ride the PARSED pre-match profile:
 * [accumulateRankedStat] reads the profile's own placement state, so a
 * mid-placement account's counters come back unchanged (RD10 — placement
 * counts toward NO statistic) and every other seat folds its one outcome in.
 * The percentages are never persisted (RD23 — derived, not stored).
 */
private fun foldRankedProgression(
  seated: List<ParsedSeat>,
  finalScores: Map<String, Int>,
  gameType: GameType,
): RankedProgression {
  val tierGap = rankedTierGap(seated)
  val noAward = tierGap > 1
  val ordinals = seated.map { ladderOrdinal(it.profile.rp) }
  val movements = LinkedHashMap<String, Map<String, Any?>>()
  val writes = LinkedHashMap<String, Map<String, Any?>>()
  seated.forEachIndexed { index, seat ->
    val others = ordinals.filterIndexed { i, _ -> i != index }
    val opponentStrength = if (others.isEmpty()) 0 else others.sum() / others.size
    val outcome = placementOutcome(finalScores, seat.seat)
    // No-award tables still resolve at zero: same rung, same RP, no movement,
    // and (below) no shield consumption. The match result and statistics
    // still fold; only progression is withheld.
    val resolution = if (noAward) {
      resolveRanked(seat.profile.rp, 0)
    } else {
      resolveRanked(
        seat.profile.rp,
        finalRpDelta(
          RpMatchInput(
            outcome = outcome,
            playerOrdinal = ordinals[index],
            opponentStrength = opponentStrength,
            gameType = gameType,
            rankDown = false,
          ),
        ),
      )
    }
    val shielded = if (noAward) null else applyDemotionShield(seat.profile, resolution)
    val finalResolution = shielded?.resolution ?: resolution
    val shieldedProfile = shielded?.profile ?: seat.profile
    val held =
      if (noAward) seat.profile
      else updateHighestRank(shieldedProfile, finalResolution.next)
    val stats = accumulateRankedStat(seat.stats, outcome, seat.profile, MatchMode.RANKED)
    movements[seat.uid] = linkedMapOf(
      "previousRp" to finalResolution.previousRP,
      "rp" to finalResolution.rp,
      "delta" to finalResolution.delta,
    )
    writes[seat.uid] = linkedMapOf(
      // RP/rank/delta are the SHIELDED resolution's — the held rung when a
      // demotion was blocked, the plain (or zero-award) one otherwise.
      "rp" to finalResolution.rp,
      "tier" to finalResolution.next.tier,
      "division" to finalResolution.next.division,
      // The shield state applyDemotionShield left the profile in: consumed
      // (any armed match), re-armed (armed promotion), or untouched (unarmed).
      // A no-award table never consumes a shield: no match outcome was scored.
      "demotionShield" to shieldedProfile.demotionShield,
      // Highest Rank only ever raises (RD23): the parsed peak, or the rung
      // this match climbed to — never a step down. A no-award table leaves it
      // exactly as parsed.
      "highestRankTier" to held.highestRank.tier,
      "highestRankDivision" to held.highestRank.division,
      // The current season, always — preserved when already current, adopted
      // when missing, moved to current when stale — because the seat was
      // aligned to the season read at the top of this transaction (Q22).
      "seasonId" to held.seasonId,
      // Preserved when present, defaulted when missing (RD30).
      "placementState" to held.placementState.name,
      // The five STORED counters, folded once per completed Ranked match
      // (RD23/RD28); placement profiles come back unchanged (RD10).
      "rankedGamesPlayed" to stats.gamesPlayed,
      "rankedKingCount" to stats.kingCount,
      "rankedSecondCount" to stats.secondCount,
      "rankedThirdCount" to stats.thirdCount,
      "rankedKozCount" to stats.kozCount,
    )
  }
  return RankedProgression(movements, writes, if (noAward) tierGap else null)
}

/**
 * Runs one settlement: auth → read → gate → recompute → transacted
 * claim/write. Throws HttpsError on every denial (the client switches on
 * code). Returns Kotlin maps throughout so the orchestration stays
 * unit-testable — the callable handler applies toPlain() once, at the
 * serialization boundary (plain JS objects fail Kotlin Map reads).
 */
suspend fun settleOnce(db: SettlementDb, request: CallableRequest): Map<String, Any?> {
  // The runtime delivers request.data as a PLAIN JS OBJECT, not a Kotlin map —
  // `as? Map` returns null on one, silently rejecting every real call with
  // "matchId is required". Convert at the boundary, as below.
  val data = dynamicToKotlin(request.data) as? Map<*, *>
  val matchId = data?.get("matchId") as? String
  if (matchId.isNullOrEmpty()) {
    throw HttpsError("invalid-argument", "settleMatch: matchId is required.")
  }
  val uid = CallableAuth.requireUid(request)

  val fields = db.readMatch(matchId)
    ?: throw HttpsError("not-found", "settleMatch: match '$matchId' was not found.")
  val parsed = Settlement.parseMatch(matchId, fields)
    ?: throw HttpsError(
      "failed-precondition",
      "settleMatch: match '$matchId' is not a match document.",
    )
  when (val gate = Settlement.decide(uid, parsed)) {
    is SettleDecision.StatsOnly -> {
      // S6: ROOM/UNRANKED settles stats-only. Console line only — no ledger
      // document (no RP moved, so there is no RP settlement to audit).
      console.log(SettlementLedger.statsOnlyLine(matchId, gate.mode.name))
      return settleStatsOnly(db, parsed, fields, matchId, uid)
    }
    is SettleDecision.Failed -> {
      // S62 decision: a denied attempt is console-only (no write - an
      // authenticated prober must not be able to amplify documents).
      console.log(SettlementLedger.deniedLine(matchId, uid, gate.reason))
      throw HttpsError(
        if (gate.reason == Settlement.Reason.NOT_MEMBER) "permission-denied" else "failed-precondition",
        gate.message,
      )
    }
    SettleDecision.Proceed -> Unit
    else -> throw HttpsError("internal", "settleMatch: unreachable gate.")
  }

  val parsedArchives = LinkedHashMap<String, Settlement.ArchivedRound>()
  for ((docId, archiveFields) in db.readArchives(matchId)) {
    val archive = Settlement.parseArchive(docId, archiveFields)
    if (archive == null) {
      val message = "settleMatch: roundArchive/$docId is malformed."
      // S62: a malformed archive is corrupt history - the same FAILED
      // ledger record and console line as a closed recompute.
      SettlementLedger.reportFailure(db, parsed, uid, Settlement.Reason.HISTORY_CORRUPT, message)
      throw HttpsError("failed-precondition", message)
    }
    parsedArchives[docId] = archive
  }
  val withHistory = parsed.copy(archives = parsedArchives.values.toList())

  val decision = Settlement.recompute(withHistory)
  val recomputed = when (decision) {
    is SettleDecision.Agreed -> decision.recomputed
    is SettleDecision.Corrected -> decision.recomputed
    is SettleDecision.Failed -> {
      // S62: recompute failures are ledger-recorded (FAILED) before the
      // denial is thrown; reporting is best-effort and never replaces it.
      SettlementLedger.reportFailure(db, withHistory, uid, decision.reason, decision.message)
      throw HttpsError("failed-precondition", decision.message)
    }
    else -> throw HttpsError("internal", "settleMatch: unreachable recompute.")
  }

  val settled = db.transact { tx ->
    val existing = tx.readClaim(matchId)
    if (existing != null) {
      return@transact replayClaim(existing, uid, matchId)
    }

    // Reads before writes, all four seats, inside this transaction: two racers
    // that both read absent cannot both write, and the profile a racer reads
    // is the one its claim commits against. The lazy-reset season is read ONCE
    // before the seats (Q22, V1-C10) — null when the singleton does not exist,
    // which the alignment treats as the bootstrap season and guards with
    // fail-closed — so every profile is judged, reset and folded against
    // exactly the season this transaction commits into. During a RUNNING reset
    // that season is the in-flight target, never the outgoing one the board
    // still serves. The typed contract parse — and the lazy reset it feeds —
    // runs after the reads and before the first write, so a corrupt profile
    // field still fails the whole settlement closed with nothing behind it.
    val lazyResetSeason = tx.currentLazyResetSeason()
    val seated = alignSeatedProfilesToSeason(
      seatInputs = readSeatInputs(tx, fields, parsed.order, matchId),
      lazyResetSeason = lazyResetSeason,
      matchId = matchId,
    )
    val progression = foldRankedProgression(
      seated,
      recomputed.finalScores,
      parsed.gameType,
    )
    progression.noAwardTierGap?.let { gap ->
      console.warn(
        "S50_TIER_GAP_NO_RP matchId=$matchId tierGap=$gap; " +
          "the match settled with no RP progression",
      )
    }

    if (recomputed.corrected) {
      val version = (fields["version"] as? Number)?.toInt() ?: 0
      tx.writeCorrection(matchId, recomputed.finalScores, recomputed.winnerIds, version + 1)
    }
    val response = executedResponse(recomputed, matchId)
    // The claim and every seat's progression write commit as ONE transaction —
    // which is what makes an RP award exactly-once rather than once-per-retry.
    progression.profileWrites.forEach { (seatUid, profileFields) ->
      tx.writeProfile(seatUid, profileFields)
    }
    tx.writeClaim(matchId, claimRecord(uid, response, progression.movements))
    val you = youOf(progression.movements, uid)
    if (you == null) response else response + ("you" to you)
  }

  // S62: only the invocation that applied the settlement appends to the
  // ledger - the replay path (replayed=true) is a read-only no-op for it,
  // which makes the ledger append exactly-once across retries.
  if (settled["settled"] == true && settled["replayed"] != true) {
    SettlementLedger.recordSettlement(db, parsed, recomputed, uid)
  }
  return settled
}

/**
 * S6 (A7.5 / A8.4, Q34 / Q38, Q46) — the ROOM/UNRANKED stats-only
 * settlement. One transaction, the claim as the once-only record, and
 * exactly five profile fields per ATTRIBUTED human seat:
 * `rankedGamesPlayed`, `rankedKingCount`, `rankedSecondCount`,
 * `rankedThirdCount`, `rankedKozCount`.
 *
 * What this path never does, by construction: no `recompute`, no archive
 * read, no `seasons/current` read or write (no season alignment — a
 * ROOM/UNRANKED match carries no season stake), no RP/rank/shield/Highest
 * Rank/placement write, no correction write, no RP ledger document. The
 * already-converged document's own `finalScores`/`winnerIds` are the
 * authority, validated for shape and never re-derived: a stats-only settle
 * invents no score.
 *
 * Per seat, in canonical order: resolve the uid from `seats`; a uid that is
 * not in `players` is a pure bot seat and contributes nothing to any human
 * profile (A7.5); a missing `players/{uid}` document fails the whole
 * settlement closed (the claim stays absent, so the match stays settleable
 * once repaired). The outcome is the seat's placement in the claimed totals
 * — EXCEPT that a uid in the document's departure provenance
 * ([Settlement.ConvergedMatch.departedUids]: left / 15-timeout / Vote Kick /
 * 7th-takeover) is forced to KOZ even when the bot playing their seat
 * finished as King (A7.5). The fold is the engine's own
 * [accumulateRankedStat]: Games Played +1 and exactly one outcome counter,
 * unchanged for a placement profile (RD10). The forced KOZ is the ONLY
 * record for a departed human here — no departure charge is also folded, or
 * the same departure would count twice. This is a cross-task contract: a
 * later S5/S7 charge consumer must NOT add a second Koz for a departure this
 * path already forced to KOZ; the separate departure-charge mechanism stays
 * unconsumed for ROOM/UNRANKED until that integration is designed.
 *
 * The response is the executed shape with `settled: true` and NO `you`
 * (counters only, never RP); the claim records an EMPTY movements map, so a
 * replay answers `replayed: true` with no `you` for every caller.
 */
private suspend fun settleStatsOnly(
  db: SettlementDb,
  parsed: Settlement.ConvergedMatch,
  fields: Map<String, Any?>,
  matchId: String,
  uid: String,
): Map<String, Any?> {
  return db.transact { tx ->
    // The claim first, exactly like the RANKED path: an existing claim
    // replays and nothing below runs — no validation, no read, no write.
    val existing = tx.readClaim(matchId)
    if (existing != null) {
      return@transact replayClaim(existing, uid, matchId)
    }

    // The already-converged document, validated for shape — the same
    // completeness rules recompute applies, judged on the claim because this
    // path replays no history. Fail closed: nothing writes, the match stays
    // settleable, and no score is ever invented.
    val claimedScores = parsed.claimedScores
    val claimedWinners = parsed.claimedWinners
    if (parsed.status != "complete") {
      throw HttpsError(
        "failed-precondition",
        "settleMatch: match '$matchId' is not complete.",
      )
    }
    if (parsed.completedRound + 1 <= parsed.maxRounds) {
      throw HttpsError(
        "failed-precondition",
        "settleMatch: completedRound ${parsed.completedRound} does not end " +
          "a ${parsed.maxRounds}-round match.",
      )
    }
    if (claimedScores == null || claimedWinners == null) {
      throw HttpsError(
        "failed-precondition",
        "settleMatch: a complete match with no claimed result cannot be settled.",
      )
    }
    if (claimedScores.keys.sorted() != parsed.order.sorted()) {
      throw HttpsError(
        "failed-precondition",
        "settleMatch: claimed finalScores do not cover exactly the match seats.",
      )
    }
    if (claimedWinners.any { it !in parsed.order }) {
      throw HttpsError(
        "failed-precondition",
        "settleMatch: claimed winnerIds name a seat that is not in the match.",
      )
    }

    // Reads before writes, inside this transaction. The seats map is the
    // same authority the RANKED path resolves uid links through; a seat
    // held by a uid that is not in `players` is a bot seat — skipped, never
    // attributed (A7.5). `readSeatInputs` enforces the same seat/uid shape
    // as the RANKED path, including the HttpsError for a malformed seat.
    val inputs = readSeatInputs(
      tx, fields, parsed.order, matchId,
      onlyUids = parsed.players.toSet(),
    )
    // The stats-only typed parse, before the first write: the five counters
    // and the placement state, never the RP contract (see parseStatsOnlySeat).
    val seated = inputs.map { parseStatsOnlySeat(it, matchId) }

    // A response-shape carrier: this path replays no history, so the shared
    // builder's "recomputed" input is only the claimed result restated.
    val executed = Settlement.RecomputedMatch(
      finalScores = claimedScores,
      winnerIds = claimedWinners,
      rounds = parsed.completedRound,
      corrected = false,
      claimedScores = claimedScores,
      claimedWinners = claimedWinners,
    )
    val response = executedResponse(executed, matchId)
    seated.forEach { seat ->
      val placement = placementOutcome(claimedScores, seat.seat)
      // A7.5: a departed human is KOZ whatever the seat's bot finished as —
      // the placement is overridden, never mixed with it.
      val outcome = if (seat.uid in parsed.departedUids) MatchOutcome.KOZ else placement
      val stats = accumulateRankedStat(seat.stats, outcome, seat.profile, parsed.mode)
      tx.writeProfile(
        seat.uid,
        linkedMapOf(
          "rankedGamesPlayed" to stats.gamesPlayed,
          "rankedKingCount" to stats.kingCount,
          "rankedSecondCount" to stats.secondCount,
          "rankedThirdCount" to stats.thirdCount,
          "rankedKozCount" to stats.kozCount,
        ),
      )
    }
    // Empty movements: the claim's once-only property still makes the
    // counter writes exactly-once, and no caller ever has a `you` to slice.
    tx.writeClaim(matchId, claimRecord(uid, response, emptyMap()))
    response
  }
}

/** Admin-SDK implementation. Reads normalize through dynamicToKotlin;
 *  writes normalize through toPlain. */
class AdminSettlementDb : SettlementDb {
  // Before getFirestore() resolves the default app — see ensureDefaultAdminApp.
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  private fun matchRef(matchId: String): DocRef =
    db.collection("matches").doc(matchId)

  /** The Ranked progression document — the only one settlement writes to. */
  private fun playerRef(uid: String): DocRef = db.collection("players").doc(uid)

  override suspend fun readMatch(matchId: String): Map<String, Any?>? {
    val snap = matchRef(matchId).get().await()
    if (!snap.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snap.data()) as? Map<String, Any?>
  }

  override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> {
    val snapshot = matchRef(matchId).collection("roundArchive").get().await()
    val out = LinkedHashMap<String, Map<String, Any?>>()
    for (doc in snapshot.docs) {
      @Suppress("UNCHECKED_CAST")
      val fields = dynamicToKotlin(doc.data()) as? Map<String, Any?> ?: continue
      out[doc.id] = fields
    }
    return out
  }

  override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  override suspend fun appendLedger(record: Map<String, Any?>) {
    // S62: create-if-absent in its own Admin transaction, keyed by
    // SettlementLedger.ledgerDocId - the append is idempotent even if the
    // same event is ever delivered twice, and a failure here cannot touch
    // the settlement (that transaction has already committed).
    // appendTargets decides which collections the row lands in, and they all
    // commit together: the corrections window can never hold a row the ledger
    // lacks, or miss one it should mirror.
    val docId = SettlementLedger.ledgerDocId(record) ?: return
    val refs = SettlementLedger.appendTargets(record).map { collection ->
      db.collection(collection).doc(docId)
    }
    db.runTransaction { tx ->
      TxScope.promise {
        // Firestore requires EVERY read in a transaction to precede EVERY
        // write (the SDK throws on a get after a set), so the create-if-absent
        // test is done for all targets first and only then written.
        val existed = refs.map { tx.get(it).await().exists }
        val plain = toPlain(record)
        refs.forEachIndexed { index, ref ->
          if (!existed[index]) tx.set(ref, plain)
        }
      }
    }.await()
  }

  override suspend fun readCorrectionWindow(): List<Map<String, Any?>> {
    // S62: newest-first over ONE field of the corrections collection, so the
    // window is genuinely "the last DETECT_WINDOW corrections" and includes
    // the record the correction just wrote. An un-ordered equality query on
    // the ledger instead would return index order, not recency, and would
    // freeze on the oldest documents once the collection grows - silently
    // blinding the detector. Mirror the correction at write time and the read
    // stays on the automatic single-field index: no composite index, no
    // indexes artifact, no deployment.
    val snapshot = db.collection(SettlementLedger.COLLECTION_CORRECTIONS)
      .orderBy("appendedAt", "desc")
      .limit(SettlementLedger.DETECT_WINDOW)
      .get().await()
    return snapshot.docs.mapNotNull { doc ->
      @Suppress("UNCHECKED_CAST")
      dynamicToKotlin(doc.data()) as? Map<String, Any?>
    }
  }

  private inner class AdminTx(private val tx: Transaction) : SettlementDb.SettlementTx {
    override suspend fun readClaim(matchId: String): Map<String, Any?>? {
      val snap = tx.get(matchRef(matchId).collection(CLAIM_PATH).doc(CLAIM_DOC)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override suspend fun readProfile(uid: String): Map<String, Any?>? {
      val snap = tx.get(playerRef(uid)).await()
      // Absent, not empty: a missing profile document is a fail-closed
      // condition for the whole settlement, while an existing document with no
      // `rp` field is a different thing (an unplaced account, RD30).
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    /**
     * S59's singleton, read INSIDE the transaction and before any write (Q22,
     * V1-C10): the season a stale seat is lazily reset into is exactly the
     * season this transaction commits into, so a season flipping mid-call
     * cannot stamp a profile with a season the write did not land in. Absent
     * answers NULL — the fresh-deployment bootstrap the alignment guards —
     * while an existing document is authoritative and parsed by
     * [lazyResetSeasonOf]: the in-flight TARGET while a reset is RUNNING (a
     * seat the sweep already moved must not be reset backwards), otherwise
     * the outgoing `seasonId` the board still serves (malformed fails closed).
     */
    override suspend fun currentLazyResetSeason(): String? {
      val snap = tx.get(db.collection(SEASONS_COLLECTION).doc(CURRENT_SEASON_DOC)).await()
      if (!snap.exists) return null
      return lazyResetSeasonOf(exists = true, data = dynamicToKotlin(snap.data()))
    }

    override fun writeCorrection(
      matchId: String,
      scores: Map<String, Int>,
      winners: List<String>,
      version: Int,
    ) {
      tx.update(
        matchRef(matchId),
        toPlain(
          mapOf(
            "finalScores" to scores,
            "winnerIds" to winners,
            "version" to version,
          ),
        ),
      )
    }

    override fun writeProfile(uid: String, fields: Map<String, Any?>) {
      // update(), not set(): it merges these fields into the profile and
      // leaves everything else the account holds (name, avatar, room/match
      // pointers, the legacy `rank`, and every field this path does not own)
      // exactly as it is.
      tx.update(playerRef(uid), toPlain(fields))
    }

    override fun writeClaim(matchId: String, record: Map<String, Any?>) {
      tx.set(matchRef(matchId).collection(CLAIM_PATH).doc(CLAIM_DOC), toPlain(record))
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
