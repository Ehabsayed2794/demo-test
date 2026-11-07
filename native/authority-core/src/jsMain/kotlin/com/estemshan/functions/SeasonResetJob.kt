package com.estemshan.functions

import com.estemshan.engine.RankedProfile
import com.estemshan.engine.applySeasonReset

/**
 * S59 — the season-reset planner and one-way claim core (RD24, R27).
 *
 * Specified in docs/NATIVE_V1_PLAN_AND_ESTIMATE.md (E6b, S59: "3-month cycle,
 * season ID, two-step reset with ladder floor, King → Royal I, Highest Rank
 * preserved"; R27: "Season reset is a one-way data migration ... irreversible
 * once run") and docs/UI_UX_ROADMAP.md §4b.7 (Seasons, RD24).
 *
 * Scope of THIS file, stated honestly: it is the PURE decision + planning core
 * the (unbuilt) server-side season job will call. It reads and writes nothing
 * — no Firestore, no Admin SDK, no callable, no scheduler, no season document,
 * no clock:
 *
 * - [decideSeasonResetClaim] is the one-way claim DECISION matrix over the
 *   season document's known state (id, status, in-flight target). It is not an
 *   exactly-once guard by itself: the future server job must read that document
 *   and transition it in ONE transaction (or compare-and-set) and only then run
 *   the plan. That transaction, the season doc, and the R27 mandatory emulator
 *   dry-run against a seeded production-shaped dataset remain release gates,
 *   outside this file and never claimed satisfied by these unit tests.
 * - [planSeasonReset] applies the existing engine reset math
 *   ([applySeasonReset] in the engine's SeasonReset.kt) to every profile and
 *   collects per-profile failures. It fails CLOSED (see below) and is
 *   deterministic: same input, same plan.
 *
 * One-way direction (RD24): once a transition is underway or complete it can
 * only move forward. ALREADY_COMPLETE / RESUME / IN_PROGRESS_OTHER_SEASON are
 * the only non-rejected answers in those states; a stale FROM, a different
 * target, or a same-ID request can never produce a fresh CLAIMED.
 *
 * Pure data + pure functions: stdlib + the shared :engine only. No I/O.
 */

/** Where a season document sits in its one-way lifecycle (RD24). */
enum class SeasonStatus {
  /** The season is live; no reset is in flight. */
  ACTIVE,

  /** A reset claimed this season and is running toward its target. */
  RUNNING,

  /** The reset finished; the target season is now the current one. */
  COMPLETE,
}

/** The one-way claim decision for a requested reset (see [decideSeasonResetClaim]). */
enum class SeasonResetClaim {
  /** The request may create and run the transition FROM -> TO. */
  CLAIMED,

  /** The same transition is already RUNNING toward this target; continue it. */
  RESUME,

  /** The transition already finished; a replayed request is a no-op. */
  ALREADY_COMPLETE,

  /** A different season transition is in flight; this request must wait. */
  IN_PROGRESS_OTHER_SEASON,

  /** The request contradicts the season document's state; fail closed. */
  REJECTED,
}

/** One player's planned progression after the season reset. */
data class PlannedReset(
  val playerId: String,
  /**
   * The post-reset PROGRESSION block: tier/division/rp/seasonId/highestRank
   * (with placementState and the demotion shield carried over untouched). The
   * caller MERGES these progression fields into the player document — it never
   * replaces the whole document. The nine career statistics (RD23) are not
   * part of this type and must survive the write: the reset is irreversible
   * (R27), so a wiped career stat could not be rolled back.
   */
  val profile: RankedProfile,
)

/** One player the reset could not plan, and why. Fail closed. */
data class SeasonResetFailure(val playerId: String, val reason: String)

/** The full plan for one FROM -> TO season transition. */
data class SeasonResetPlan(
  val fromSeasonId: String,
  val toSeasonId: String,
  /**
   * The planned resets, in input order. EMPTY whenever [failures] is
   * non-empty: the plan is all-or-nothing (fail closed under R27).
   */
  val resets: List<PlannedReset>,
  /** Every profile that blocked the plan; empty when [resets] is populated. */
  val failures: List<SeasonResetFailure>,
)

/**
 * The exact one-way decision matrix for a requested reset [requestedFrom] ->
 * [requestedTo], against the season document's known state. Order of checks
 * matters:
 *
 * 1. A blank [currentSeasonId], [requestedFrom], or [requestedTo] ->
 *    REJECTED, and [requestedFrom] == [requestedTo] -> REJECTED: a reset
 *    always opens a NEW, NAMED season, so a same-ID request is definitionally
 *    a double-run (this is a decision, not the exactly-once guard).
 * 2. [SeasonStatus.COMPLETE]: [currentSeasonId] == [requestedTo] ->
 *    ALREADY_COMPLETE; anything else -> REJECTED. Completion is terminal.
 * 3. [SeasonStatus.RUNNING]:
 *    - [currentSeasonId] == [requestedTo] -> ALREADY_COMPLETE (the transition
 *      has already landed on the target);
 *    - [currentSeasonId] == [requestedFrom] and [runningTargetSeasonId] ==
 *      [requestedTo] -> RESUME (this exact transition is in flight; continue);
 *    - anything else -> IN_PROGRESS_OTHER_SEASON (a season already moving
 *      elsewhere must wait, never be mis-claimed).
 * 4. [SeasonStatus.ACTIVE]: [currentSeasonId] == [requestedFrom] -> CLAIMED;
 *    anything else -> REJECTED (a stale FROM never claims).
 *
 * [runningTargetSeasonId] is only consulted in the RUNNING state; elsewhere it
 * is ignored. Pure: the caller owns the transactional write that makes a
 * CLAIMED/RESUME decision exactly-once.
 */
fun decideSeasonResetClaim(
  currentSeasonId: String,
  currentStatus: SeasonStatus,
  runningTargetSeasonId: String?,
  requestedFrom: String,
  requestedTo: String,
): SeasonResetClaim {
  if (currentSeasonId.isBlank() || requestedFrom.isBlank() || requestedTo.isBlank()) {
    return SeasonResetClaim.REJECTED
  }
  if (requestedFrom == requestedTo) {
    return SeasonResetClaim.REJECTED
  }
  return when (currentStatus) {
    SeasonStatus.COMPLETE ->
      if (currentSeasonId == requestedTo) SeasonResetClaim.ALREADY_COMPLETE
      else SeasonResetClaim.REJECTED

    SeasonStatus.RUNNING -> when {
      currentSeasonId == requestedTo -> SeasonResetClaim.ALREADY_COMPLETE
      currentSeasonId == requestedFrom && runningTargetSeasonId == requestedTo ->
        SeasonResetClaim.RESUME
      else -> SeasonResetClaim.IN_PROGRESS_OTHER_SEASON
    }

    SeasonStatus.ACTIVE ->
      if (currentSeasonId == requestedFrom) SeasonResetClaim.CLAIMED
      else SeasonResetClaim.REJECTED
  }
}

/**
 * Plan the whole [fromSeasonId] -> [toSeasonId] reset for [profiles]
 * (player ID -> current profile).
 *
 * - [fromSeasonId] and [toSeasonId] must be non-blank and different; a bad
 *   request throws instead of producing a half-plan.
 * - Each profile must belong to [fromSeasonId]; a foreign or corrupt profile
 *   is a failure, never silently skipped.
 * - [applySeasonReset] (the shared :engine's RD24 math) runs per profile; any
 *   Throwable it raises becomes that player's [SeasonResetFailure] and the
 *   scan continues, so the caller sees EVERY blocker in one report.
 * - FAIL CLOSED (R27): when [SeasonResetPlan.failures] is non-empty the
 *   returned plan has NO resets at all — a partially applied irreversible
 *   season reset is worse than none. Otherwise the resets keep the input
 *   order.
 * - No stats, ever: the plan carries progression only (see
 *   [PlannedReset.profile]) and never returns or manufactures the nine career
 *   statistics (RD23). The caller MERGES the progression fields
 *   (tier/division/rp/seasonId/highestRank) into each player document and
 *   never replaces the whole document, so career stats survive the reset.
 */
fun planSeasonReset(
  fromSeasonId: String,
  toSeasonId: String,
  profiles: List<Pair<String, RankedProfile>>,
): SeasonResetPlan {
  require(fromSeasonId.isNotBlank()) {
    "Season reset needs the source season's ID — a reset never moves from an unnamed season (RD24)"
  }
  require(toSeasonId.isNotBlank()) {
    "Season reset needs the target season's ID — a reset always opens a NAMED, NEW season (RD24)"
  }
  require(fromSeasonId != toSeasonId) {
    "Season reset from $fromSeasonId to the same ID is a double-run — a reset always opens a NEW season"
  }

  val resets = mutableListOf<PlannedReset>()
  val failures = mutableListOf<SeasonResetFailure>()
  val seenPlayerIds = mutableSetOf<String>()

  for ((playerId, profile) in profiles) {
    if (!seenPlayerIds.add(playerId)) {
      failures += SeasonResetFailure(
        playerId,
        "duplicate player ID in the reset batch — fail closed (R27)",
      )
      continue
    }
    if (playerId.isBlank()) {
      failures += SeasonResetFailure(
        playerId,
        "blank player ID in the reset batch — fail closed (R27)",
      )
      continue
    }
    if (profile.seasonId != fromSeasonId) {
      failures += SeasonResetFailure(
        playerId,
        "profile is in season '${profile.seasonId}', not '$fromSeasonId' — refusing to reset a foreign profile (R27)",
      )
      continue
    }
    try {
      resets += PlannedReset(playerId, applySeasonReset(profile, toSeasonId))
    } catch (t: Throwable) {
      failures += SeasonResetFailure(
        playerId,
        t.message?.takeIf { it.isNotBlank() } ?: "season reset failed ($t)",
      )
    }
  }

  return if (failures.isEmpty()) {
    SeasonResetPlan(fromSeasonId, toSeasonId, resets.toList(), emptyList())
  } else {
    SeasonResetPlan(fromSeasonId, toSeasonId, emptyList(), failures.toList())
  }
}
