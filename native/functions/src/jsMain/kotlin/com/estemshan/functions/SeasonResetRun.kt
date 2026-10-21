package com.estemshan.functions

import com.estemshan.engine.LadderRank
import com.estemshan.engine.PlacementState
import com.estemshan.engine.RankedProfile
import com.estemshan.engine.progressionMismatch
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder

/**
 * S59 — the season-reset orchestrator (RD24, R27).
 *
 * [SeasonResetJob] owns the frozen pure core: the one-way claim matrix and the
 * engine's reset math. This file owns everything around it — the season
 * document, the clock, the profile population, and the order the three phases
 * run in:
 *
 * 1. READ `seasons/current` in a transaction and gate on it: presence, a usable
 *    identity and status, a configured target, and a passed deadline. A
 *    terminal answer here reads no profiles and writes nothing.
 * 2. CLAIM. APPLY re-reads the season document in a SECOND transaction and
 *    decides on the fresh values before writing `RUNNING`. The second read is
 *    what makes the claim safe: a deadline moved between the reads wins
 *    (NOT_DUE), a changed target or a replaced season is REJECTED, and another
 *    instance that claimed first turns this run into a RESUME rather than a
 *    second claim.
 * 3. PLAN + WRITE. Every profile is classified once, parsed with the same
 *    fail-closed defaults [RankedProfileDb] applies, and handed to
 *    [planSeasonReset]. Nothing is written unless the plan is whole. Writes are
 *    six-field progression merges in chunks of at most 400.
 * 4. FINALISE, in a third transaction, only if the season still says exactly
 *    `from` / RUNNING / `to`; the next 90-day season then becomes current.
 *
 * The two failure branches follow R27's irreversible direction rather than a
 * single "roll back everything" rule:
 *
 * - a plan that fails after a FRESH CLAIM is reverted - nothing has been
 *   written to a player yet, so the season returns to ACTIVE with the target
 *   preserved for a retry (decision REJECTED). The revert is preconditioned on
 *   the claim this run wrote: if another writer has taken that claim over in the
 *   meantime, it is left exactly as found and the report says so;
 * - a plan or batch that fails on a RESUME leaves the season RUNNING forever
 *   short of the next successful run, because players may already have been
 *   migrated (decision PARTIAL, reported for an operator).
 *
 * DRY_RUN (R27) is the same pass with every write removed: WOULD_CLAIM /
 * WOULD_RESUME, the full plan and its failures, zero season writes and zero
 * profile writes.
 */

/** R27's two readings of one job: plan only, or plan and migrate. */
enum class SeasonResetMode {
  DRY_RUN,
  APPLY,
}

/**
 * One run's whole answer. `decision` is one of:
 *
 * `CLAIMED`, `RESUME`, `WOULD_CLAIM`, `WOULD_RESUME`, `PARTIAL`,
 * `ALREADY_COMPLETE`, `IN_PROGRESS_OTHER_SEASON`, `REJECTED`,
 * `NOT_CONFIGURED`, `NOT_DUE`.
 *
 * [planned] counts the profiles the plan would move ([SeasonResetPlan.resets]);
 * [applied] counts only profiles whose batch COMMITTED. [failures] is never
 * empty on PARTIAL and is the operator's whole list of blockers otherwise.
 */
data class SeasonResetReport(
  val mode: SeasonResetMode,
  val decision: String,
  val fromSeasonId: String?,
  val toSeasonId: String?,
  val planned: Int,
  val applied: Int,
  val skippedAlreadyTarget: Int,
  val failures: List<SeasonResetFailure>,
  val message: String,
)

private const val NINETY_DAYS_MILLIS: Long = 90L * 24 * 60 * 60 * 1000

/** The Admin SDK's hard batch cap is 500 writes; stay well below it. */
private const val MAX_PROFILE_WRITES_PER_BATCH = 400

/** The first season's ID, the same placeholder [RankedProfileDb] initialises to. */
private const val LEGACY_SEASON_ID = "s1"

private const val FIELD_SEASON_ID = "seasonId"
private const val FIELD_STATUS = "status"
private const val FIELD_TARGET_SEASON_ID = "targetSeasonId"
private const val FIELD_NEXT_SEASON_ID = "nextSeasonId"
private const val FIELD_STARTS_AT = "startsAt"
private const val FIELD_ENDS_AT = "endsAt"
private const val FIELD_UPDATED_AT = "updatedAt"

private const val FIELD_TIER = "tier"
private const val FIELD_DIVISION = "division"
private const val FIELD_RP = "rp"
private const val FIELD_RANK = "rank"
private const val FIELD_HIGHEST_RANK_TIER = "highestRankTier"
private const val FIELD_HIGHEST_RANK_DIVISION = "highestRankDivision"
private const val FIELD_PLACEMENT_STATE = "placementState"
private const val FIELD_DEMOTION_SHIELD = "demotionShield"

/**
 * Runs one season reset. Returns a report for every domain outcome; throws only
 * when the transport prevents knowing a state (a profile read or a claim that
 * never resolved), in which case nothing is converted into a fake success.
 */
suspend fun runSeasonReset(
  db: SeasonResetDb,
  nowMillis: Long,
  mode: SeasonResetMode,
  expectedTargetSeasonId: String? = null,
): SeasonResetReport {
  // Phase 1 — the gating read. The transaction is read-only, and every
  // decision it feeds is re-derived inside the claim transaction below.
  val season = db.transact { tx -> tx.readSeason() }
    ?: return seasonReport(
      mode, "NOT_CONFIGURED", null, null,
      message = "seasons/current does not exist; no season is configured to reset.",
    )

  val from = seasonIdOf(season)
    ?: return seasonReport(
      mode, "NOT_CONFIGURED", null, nextOf(season),
      message = "seasons/current has no usable seasonId; refusing to infer one.",
    )
  val status = statusOf(season)
    ?: return seasonReport(
      mode, "NOT_CONFIGURED", from, nextOf(season),
      message = "seasons/current has no known status; expected ACTIVE, RUNNING or COMPLETE.",
    )

  if (status == SeasonStatus.COMPLETE) {
    return seasonReport(
      mode, "ALREADY_COMPLETE", from, nextOf(season),
      message = "season '$from' is COMPLETE; completion is terminal and a replay is a no-op.",
    )
  }

  val to = nextOf(season)
    ?: return seasonReport(
      mode, "NOT_CONFIGURED", from, null,
      message = "season '$from' is $status but has no nextSeasonId; there is nothing to reset toward.",
    )

  if (expectedTargetSeasonId != null && to != expectedTargetSeasonId) {
    return seasonReport(
      mode, "REJECTED", from, to,
      message = "season '$from' is configured to reset toward '$to', not the approved target " +
        "'$expectedTargetSeasonId'; nothing was written.",
    )
  }

  if (!isDue(season, nowMillis)) {
    return seasonReport(
      mode, "NOT_DUE", from, to,
      message = "season '$from' is not due: its endsAt is missing or still in the future.",
    )
  }

  val claim = decideSeasonResetClaim(from, status, targetOf(season), from, to)
  when (claim) {
    SeasonResetClaim.ALREADY_COMPLETE -> return seasonReport(
      mode, "ALREADY_COMPLETE", from, to,
      message = "season '$from' has already landed on '$to'; nothing to do.",
    )
    SeasonResetClaim.IN_PROGRESS_OTHER_SEASON -> return seasonReport(
      mode, "IN_PROGRESS_OTHER_SEASON", from, to,
      message = "season '$from' is already running toward '${targetOf(season)}'; " +
        "this reset waits rather than claims a second transition.",
    )
    SeasonResetClaim.REJECTED -> return seasonReport(
      mode, "REJECTED", from, to,
      message = "season '$from' cannot claim a transition to '$to'; the request contradicts the document.",
    )
    SeasonResetClaim.CLAIMED, SeasonResetClaim.RESUME -> Unit
  }

  return when (mode) {
    SeasonResetMode.DRY_RUN -> dryRun(db, claim, from, to)
    SeasonResetMode.APPLY -> applyReset(db, from, to, nowMillis)
  }
}

// ── R27 dry run ─────────────────────────────────────────────────────────

private suspend fun dryRun(
  db: SeasonResetDb,
  claim: SeasonResetClaim,
  from: String,
  to: String,
): SeasonResetReport {
  val resuming = claim == SeasonResetClaim.RESUME
  val input = collectPlan(db, from, to, resuming)
  return seasonReport(
    SeasonResetMode.DRY_RUN,
    if (resuming) "WOULD_RESUME" else "WOULD_CLAIM",
    from,
    to,
    planned = input.plan.resets.size,
    skipped = input.skipped,
    failures = input.plan.failures,
    message = if (resuming) {
      "dry run: would resume '$from' → '$to' and move ${input.plan.resets.size} profile(s); nothing was written."
    } else {
      "dry run: would claim '$from' → '$to' and move ${input.plan.resets.size} profile(s); nothing was written."
    },
  )
}

// ── APPLY ───────────────────────────────────────────────────────────────

private sealed class ClaimAttempt {
  object Claimed : ClaimAttempt()
  object Resumed : ClaimAttempt()
  data class Terminal(val decision: String, val message: String) : ClaimAttempt()
}

private suspend fun applyReset(
  db: SeasonResetDb,
  from: String,
  to: String,
  nowMillis: Long,
): SeasonResetReport {
  // Phase 2 — the claim transaction re-reads the document. Nothing below trusts
  // the snapshot phase 1 decided on: the season, its target and its deadline
  // may all have moved between the two reads.
  val attempt = db.transact { tx ->
    val fresh = tx.readSeason()
      ?: return@transact ClaimAttempt.Terminal(
        "NOT_CONFIGURED",
        "seasons/current disappeared before the claim; nothing was written.",
      )
    val freshFrom = seasonIdOf(fresh)
      ?: return@transact ClaimAttempt.Terminal(
        "NOT_CONFIGURED",
        "seasons/current lost its seasonId before the claim; nothing was written.",
      )
    val freshStatus = statusOf(fresh)
      ?: return@transact ClaimAttempt.Terminal(
        "NOT_CONFIGURED",
        "seasons/current lost its status before the claim; nothing was written.",
      )
    if (freshStatus == SeasonStatus.COMPLETE) {
      val decision = decideSeasonResetClaim(freshFrom, freshStatus, null, from, to)
      return@transact ClaimAttempt.Terminal(
        decision.name,
        "season '$freshFrom' completed before the claim; nothing was written.",
      )
    }
    val freshTo = nextOf(fresh)
      ?: return@transact ClaimAttempt.Terminal(
        "NOT_CONFIGURED",
        "season '$freshFrom' lost its nextSeasonId before the claim; nothing was written.",
      )
    if (freshTo != to) {
      // A different target would mean running the plan we built for `to` — the
      // plan is not transferable, so this run stands down without a write.
      return@transact ClaimAttempt.Terminal(
        "REJECTED",
        "the season's nextSeasonId changed from '$to' to '$freshTo' before the claim; nothing was written.",
      )
    }
    if (!isDue(fresh, nowMillis)) {
      return@transact ClaimAttempt.Terminal(
        "NOT_DUE",
        "the season's deadline moved into the future before the claim; nothing was written.",
      )
    }
    when (decideSeasonResetClaim(freshFrom, freshStatus, targetOf(fresh), from, freshTo)) {
      SeasonResetClaim.CLAIMED -> {
        tx.writeSeason(claimFields(freshTo))
        ClaimAttempt.Claimed
      }
      // Another instance claimed this exact transition first: continue it
      // without rewriting the claim it already wrote.
      SeasonResetClaim.RESUME -> ClaimAttempt.Resumed
      SeasonResetClaim.ALREADY_COMPLETE -> ClaimAttempt.Terminal(
        "ALREADY_COMPLETE",
        "season '$freshFrom' already landed on '$freshTo' before the claim; nothing was written.",
      )
      SeasonResetClaim.IN_PROGRESS_OTHER_SEASON -> ClaimAttempt.Terminal(
        "IN_PROGRESS_OTHER_SEASON",
        "season '$freshFrom' is running toward '${targetOf(fresh)}'; this reset stands down.",
      )
      SeasonResetClaim.REJECTED -> ClaimAttempt.Terminal(
        "REJECTED",
        "season '$freshFrom' cannot claim a transition to '$freshTo'; nothing was written.",
      )
    }
  }

  if (attempt is ClaimAttempt.Terminal) {
    return seasonReport(
      SeasonResetMode.APPLY, attempt.decision, from, to,
      message = attempt.message,
    )
  }

  // A resume is decided by the FRESH transaction, never by the gating snapshot:
  // a claim reverted in between makes this run a FRESH claim.
  val resuming = attempt is ClaimAttempt.Resumed

  // Phase 3 — plan the whole population, then write only if it is whole.
  val input = collectPlan(db, from, to, resuming)

  if (input.plan.failures.isNotEmpty()) {
    if (resuming) {
      // R27: a resume has possibly already migrated players, so the season is
      // NOT un-claimed. It stays RUNNING toward the same target for the next
      // run and the failures are reported for an operator.
      return seasonReport(
        SeasonResetMode.APPLY, "PARTIAL", from, to,
        skipped = input.skipped,
        failures = input.plan.failures,
        message = "resume of '$from' → '$to' cannot plan; the season stays RUNNING for an operator.",
      )
    }
    val reverted = revertFreshClaim(db, from, to)
    return seasonReport(
      SeasonResetMode.APPLY, "REJECTED", from, to,
      skipped = input.skipped,
      failures = input.plan.failures,
      message = if (reverted) {
        "the fresh claim of '$from' → '$to' cannot plan; it was reverted and no profile was written."
      } else {
        "the fresh claim of '$from' → '$to' cannot plan and the claim moved on; " +
          "it was left as found and no profile was written."
      },
    )
  }

  val planned = input.plan.resets.size
  var applied = 0
  try {
    for (chunk in input.plan.resets.chunked(MAX_PROFILE_WRITES_PER_BATCH)) {
      db.writeProfiles(
        chunk.map { reset ->
          ProfileWrite(
            playerId = reset.playerId,
            fields = progressionFields(reset.profile),
            expectedUpdateTime = input.revisions[reset.playerId],
          )
        },
      )
      applied += chunk.size
    }
  } catch (failure: Throwable) {
    // Committed chunks cannot be replayed backwards (R27). Leave the season
    // RUNNING: the next run skips profiles already at the target and only
    // moves the remainder.
    return seasonReport(
      SeasonResetMode.APPLY, "PARTIAL", from, to,
      planned = planned,
      applied = applied,
      skipped = input.skipped,
      failures = input.plan.failures + SeasonResetFailure(
        "",
        "profile batch write failed after $applied of $planned profiles: " +
          (failure.message ?: failure.toString()),
      ),
      message = "the reset of '$from' → '$to' stopped after $applied of $planned profiles; " +
        "the season stays RUNNING and the next run resumes it.",
    )
  }

  // Phase 4 — finalise only on the state this run claimed. Anything else means
  // someone moved the season underneath us, and is reported without a write.
  val finalised = db.transact { tx ->
    val fresh = tx.readSeason() ?: return@transact false
    if (!isStillOurClaim(fresh, from, to)) return@transact false
    tx.writeSeason(finalFields(to, nowMillis))
    true
  }

  if (!finalised) {
    return seasonReport(
      SeasonResetMode.APPLY, "PARTIAL", from, to,
      planned = planned,
      applied = applied,
      skipped = input.skipped,
      failures = listOf(
        SeasonResetFailure(
          "",
          "the season document no longer reads '$from' / RUNNING / '$to'; " +
            "every planned profile was written but the next season was not opened.",
        ),
      ),
      message = "could not finalise '$from' → '$to'; the season document moved during the run.",
    )
  }

  return seasonReport(
    SeasonResetMode.APPLY, if (resuming) "RESUME" else "CLAIMED", from, to,
    planned = planned,
    applied = applied,
    skipped = input.skipped,
    message = "season '$to' is ACTIVE; ${applied} profile(s) reset from '$from'.",
  )
}

/**
 * Field-level revert of a fresh claim that could not plan, preconditioned on the
 * claim it wrote. Returns true only when this run's claim was actually reverted;
 * a claim another writer has already taken over (different season, state or
 * target) is left exactly as found and reported as such.
 */
private suspend fun revertFreshClaim(db: SeasonResetDb, from: String, to: String): Boolean {
  return db.transact { tx ->
    val fresh = tx.readSeason() ?: return@transact false
    if (!isStillOurClaim(fresh, from, to)) return@transact false
    tx.writeSeason(
      linkedMapOf(
        FIELD_STATUS to SeasonStatus.ACTIVE.name,
        FIELD_TARGET_SEASON_ID to null,
        FIELD_UPDATED_AT to FieldValue.serverTimestamp(),
      ),
    )
    true
  }
}

private fun claimFields(to: String): Map<String, Any?> = linkedMapOf(
  FIELD_STATUS to SeasonStatus.RUNNING.name,
  FIELD_TARGET_SEASON_ID to to,
  FIELD_UPDATED_AT to FieldValue.serverTimestamp(),
)

/** The claim this run may finalise or revert: same season, RUNNING, same target. */
private fun isStillOurClaim(season: Map<String, Any?>, from: String, to: String): Boolean =
  seasonIdOf(season) == from &&
    statusOf(season) == SeasonStatus.RUNNING &&
    targetOf(season) == to

private fun finalFields(to: String, nowMillis: Long): Map<String, Any?> = linkedMapOf(
  FIELD_SEASON_ID to to,
  FIELD_STATUS to SeasonStatus.ACTIVE.name,
  FIELD_TARGET_SEASON_ID to null,
  FIELD_NEXT_SEASON_ID to null,
  FIELD_STARTS_AT to FieldValue.serverTimestamp(),
  // The next season runs 90 days from the instant this reset finished. A plain
  // millis value, not a sentinel: readers derive the deadline from it and the
  // scheduler must be able to compare it to the wall clock.
  FIELD_ENDS_AT to Timestamp.fromMillis((nowMillis + NINETY_DAYS_MILLIS).toDouble()),
  FIELD_UPDATED_AT to FieldValue.serverTimestamp(),
)

// ── planning ────────────────────────────────────────────────────────────

private class PlanInput(
  val plan: SeasonResetPlan,
  val skipped: Int,
  /** The exact `updateTime` each planned profile had when this plan read it. */
  val revisions: Map<String, Any?>,
)

/**
 * Reads the population once and classifies every profile before planning:
 *
 * - a profile in the TARGET season is a resume skip (never demoted twice);
 *   on a fresh claim it is a failure, because a profile in the target season
 *   while the target season is not live is not a state this job can fix;
 * - a profile with no `seasonId` is the legacy `s1` shape, and only when the
 *   reset is FROM `s1` — otherwise its season is unknown and it fails closed;
 * - any other profile must parse to the model and pass the RD3 progression
 *   invariant. Present-but-invalid values are failures, never repaired.
 *
 * The per-profile failures are merged with [planSeasonReset]'s own so one run
 * reports every blocker; the plan stays all-or-nothing either way.
 */
private suspend fun collectPlan(
  db: SeasonResetDb,
  from: String,
  to: String,
  resuming: Boolean,
): PlanInput {
  val profiles = db.readAllProfiles()
  val parsed = mutableListOf<Pair<String, RankedProfile>>()
  val revisions = mutableMapOf<String, Any?>()
  val failures = mutableListOf<SeasonResetFailure>()
  var skipped = 0

  for (stored in profiles) {
    if (stored.playerId.isBlank()) {
      failures += SeasonResetFailure(
        stored.playerId,
        "blank player ID in the reset batch — fail closed (R27)",
      )
      continue
    }
    val season = storedSeason(stored.fields, from)
    if (season is StoredSeason.Unusable) {
      failures += SeasonResetFailure(stored.playerId, season.reason)
      continue
    }
    val profileSeason = (season as StoredSeason.Present).id
    if (profileSeason == to) {
      if (resuming) {
        skipped++
      } else {
        failures += SeasonResetFailure(
          stored.playerId,
          "profile is already in the target season '$to' while the reset has not been claimed — fail closed (R27)",
        )
      }
      continue
    }
    if (profileSeason != from) {
      failures += SeasonResetFailure(
        stored.playerId,
        "profile is in season '$profileSeason', not '$from' — refusing to reset a foreign profile (R27)",
      )
      continue
    }
    when (val parsedProfile = parseStoredProfile(stored.fields, from)) {
      is ProfileParse.Parsed -> {
        parsed += stored.playerId to parsedProfile.profile
        revisions[stored.playerId] = stored.updateTime
      }
      is ProfileParse.Failed -> failures += SeasonResetFailure(stored.playerId, parsedProfile.reason)
    }
  }

  val plan = planSeasonReset(from, to, parsed)
  val allFailures = failures + plan.failures
  return PlanInput(
    plan = if (allFailures.isEmpty()) plan else SeasonResetPlan(from, to, emptyList(), allFailures),
    skipped = skipped,
    revisions = revisions,
  )
}

private sealed class StoredSeason {
  data class Present(val id: String) : StoredSeason()
  data class Unusable(val reason: String) : StoredSeason()
}

private fun storedSeason(fields: Map<String, Any?>, from: String): StoredSeason {
  if (!fields.containsKey(FIELD_SEASON_ID)) {
    return if (from == LEGACY_SEASON_ID) {
      StoredSeason.Present(LEGACY_SEASON_ID)
    } else {
      StoredSeason.Unusable(
        "profile has no seasonId and the reset is not from '$LEGACY_SEASON_ID' — its season is unknown (R27)",
      )
    }
  }
  val value = fields[FIELD_SEASON_ID] as? String
    ?: return StoredSeason.Unusable("seasonId must be a string.")
  if (value.isBlank()) return StoredSeason.Unusable("seasonId must be non-blank.")
  return StoredSeason.Present(value)
}

// ── the local profile parser ────────────────────────────────────────────
//
// The reset cannot call [RankedProfileDb]'s normalizer: that path throws one
// HttpsError for one caller and also REPAIRS missing fields. A bulk migration
// needs the same defaults (so a legacy profile parses) but per-profile
// failures and no repair at all. The rules below mirror that file's defaults
// deliberately; see RankedProfileDb.kt for their rationale.

internal sealed class ProfileParse {
  data class Parsed(val profile: RankedProfile) : ProfileParse()
  data class Failed(val reason: String) : ProfileParse()
}

internal fun parseStoredProfile(fields: Map<String, Any?>, seasonId: String): ProfileParse =
  try {
    ProfileParse.Parsed(parseStoredProfileOrThrow(fields, seasonId))
  } catch (corrupt: IllegalArgumentException) {
    ProfileParse.Failed(corrupt.message ?: "profile cannot be planned")
  }

private fun parseStoredProfileOrThrow(
  fields: Map<String, Any?>,
  seasonId: String,
): RankedProfile {
  val hasRp = fields.containsKey(FIELD_RP)
  val rp = if (hasRp) nonNegativeInt(fields.getValue(FIELD_RP), FIELD_RP) else 0

  val hasTier = fields.containsKey(FIELD_TIER)
  val hasDivision = fields.containsKey(FIELD_DIVISION)
  val current: LadderRank = when {
    hasTier && hasDivision -> rungOf(
      presentString(fields, FIELD_TIER),
      presentString(fields, FIELD_DIVISION),
    )
    !hasTier && !hasDivision -> rankAt(rp)
    else -> corrupt("tier and division must be present together.")
  }

  // A missing RP on a higher rung would award a lost counter beside an identity
  // the server did not compute. Only the bottom rung has a representable
  // absent-RP default.
  if (!hasRp && rankLadder.indexOf(current) > 0) {
    corrupt("rp is missing while tier/division name a rung above Bronze III.")
  }

  val hasHighestTier = fields.containsKey(FIELD_HIGHEST_RANK_TIER)
  val hasHighestDivision = fields.containsKey(FIELD_HIGHEST_RANK_DIVISION)
  val storedHighest: LadderRank? = when {
    hasHighestTier && hasHighestDivision -> rungOf(
      presentString(fields, FIELD_HIGHEST_RANK_TIER),
      presentString(fields, FIELD_HIGHEST_RANK_DIVISION),
    )
    !hasHighestTier && !hasHighestDivision -> null
    else -> corrupt("highestRankTier and highestRankDivision must be present together.")
  }
  // Highest Rank is a permanent record: a stale peak is raised to the pre-reset
  // rung, an already-higher peak is kept.
  val highest = when {
    storedHighest == null -> current
    rankLadder.indexOf(storedHighest) < rankLadder.indexOf(current) -> current
    else -> storedHighest
  }

  val placementState = if (fields.containsKey(FIELD_PLACEMENT_STATE)) {
    placementStateOf(presentString(fields, FIELD_PLACEMENT_STATE))
  } else if (rp > 0 || legacyRankImpliesPlacement(fields)) {
    PlacementState.SKIPPED
  } else {
    PlacementState.NOT_STARTED
  }

  val demotionShield = if (fields.containsKey(FIELD_DEMOTION_SHIELD)) {
    fields.getValue(FIELD_DEMOTION_SHIELD) as? Boolean
      ?: corrupt("demotionShield must be Boolean.")
  } else {
    false
  }

  val profile = RankedProfile(
    tier = current.tier,
    division = current.division,
    rp = rp,
    seasonId = seasonId,
    highestRank = highest,
    placementState = placementState,
    demotionShield = demotionShield,
  )
  progressionMismatch(profile)?.let { corrupt(it) }
  return profile
}

private fun placementStateOf(name: String): PlacementState =
  PlacementState.values().firstOrNull { it.name == name }
    ?: corrupt("placementState '$name' is not a PlacementState name.")

/** Mirrors RankedProfileDb: a legacy stored rank other than "Unranked" was placed. */
private fun legacyRankImpliesPlacement(fields: Map<String, Any?>): Boolean {
  val legacyRank = fields[FIELD_RANK]
  return legacyRank != null && legacyRank != "Unranked"
}

private fun rungOf(tier: String, division: String): LadderRank =
  rankLadder.firstOrNull { it.tier == tier && it.division == division }
    ?: corrupt("tier/division '$tier $division' name no ladder rung.")

private fun presentString(fields: Map<String, Any?>, key: String): String =
  fields[key] as? String ?: corrupt("$key must be a string.")

/**
 * A whole, finite, non-negative number that fits an Int. Fractions and
 * out-of-range values fail closed rather than being truncated into a
 * plausible-looking counter.
 */
private fun nonNegativeInt(value: Any?, key: String): Int {
  val number = value as? Number ?: corrupt("$key must be a non-negative number.")
  val asDouble = number.toDouble()
  if (!asDouble.isFinite() || asDouble < 0.0 || asDouble % 1.0 != 0.0 ||
    asDouble > Int.MAX_VALUE.toDouble()
  ) {
    corrupt("$key must be a non-negative whole number.")
  }
  return asDouble.toInt()
}

private fun corrupt(message: String): Nothing = throw IllegalArgumentException(message)

/**
 * The ONLY fields a reset writes. Career statistics, placement, the shield and
 * every display field are absent by construction: the batch merge leaves them
 * untouched, which is what lets an irreversible migration preserve them.
 */
private fun progressionFields(profile: RankedProfile): Map<String, Any?> = linkedMapOf(
  FIELD_TIER to profile.tier,
  FIELD_DIVISION to profile.division,
  FIELD_RP to profile.rp,
  FIELD_SEASON_ID to profile.seasonId,
  FIELD_HIGHEST_RANK_TIER to profile.highestRank.tier,
  FIELD_HIGHEST_RANK_DIVISION to profile.highestRank.division,
)

// ── season-document field readers ───────────────────────────────────────

private fun seasonIdOf(season: Map<String, Any?>): String? =
  (season[FIELD_SEASON_ID] as? String)?.takeIf { it.isNotBlank() }

private fun statusOf(season: Map<String, Any?>): SeasonStatus? {
  val name = season[FIELD_STATUS] as? String ?: return null
  return SeasonStatus.values().firstOrNull { it.name == name }
}

private fun nextOf(season: Map<String, Any?>): String? =
  (season[FIELD_NEXT_SEASON_ID] as? String)?.takeIf { it.isNotBlank() }

private fun targetOf(season: Map<String, Any?>): String? =
  (season[FIELD_TARGET_SEASON_ID] as? String)?.takeIf { it.isNotBlank() }

/**
 * Due means a real deadline at or before [nowMillis]. Absent, malformed and
 * future deadlines are all NOT_DUE: an unreadable deadline is never taken as
 * permission to migrate. `startsAt` is deliberately not a gate — a reset is
 * driven by the season's END.
 */
private fun isDue(season: Map<String, Any?>, nowMillis: Long): Boolean {
  val endsAt = timestampToMillis(season[FIELD_ENDS_AT]) ?: return false
  return endsAt <= nowMillis
}

private fun seasonReport(
  mode: SeasonResetMode,
  decision: String,
  from: String?,
  to: String?,
  planned: Int = 0,
  applied: Int = 0,
  skipped: Int = 0,
  failures: List<SeasonResetFailure> = emptyList(),
  message: String,
): SeasonResetReport = SeasonResetReport(
  mode = mode,
  decision = decision,
  fromSeasonId = from,
  toSeasonId = to,
  planned = planned,
  applied = applied,
  skippedAlreadyTarget = skipped,
  failures = failures,
  message = message,
)
