package com.estemshan.functions

/**
 * S59 — the deploy-time kill switch for the season-reset scheduler.
 *
 * The scheduler itself is only a trigger; this gate decides whether a tick may
 * APPLY or must stay a DRY_RUN. It is deliberately fail-closed: a missing
 * document, a disabled flag, a missing target, or an environment mismatch all
 * leave the reset in DRY_RUN, where it writes nothing.
 *
 * The three conditions are intentionally independent:
 *
 * 1. `config/seasonReset.enabled` must be `true`;
 * 2. `config/seasonReset.targetSeasonId` must name the season this run is
 *    approved to open;
 * 3. the document's `environment` must equal `SEASON_RESET_ENV`, an environment
 *    variable set only on the deployed service — never committed to the repo.
 *
 * A full `firebase deploy --only functions` therefore cannot arm the reset on
 * its own: the deploy sets neither the document nor the environment variable.
 */
data class SeasonResetGateConfig(
  val enabled: Boolean,
  val targetSeasonId: String?,
  val environment: String?,
)

/** The gate's answer: the mode to run, the approved target (APPLY only), and why. */
data class SeasonResetGateDecision(
  val mode: SeasonResetMode,
  val targetSeasonId: String?,
  val reason: String,
)

object SeasonResetGate {

  fun decide(
    config: SeasonResetGateConfig?,
    deployedEnvironment: String?,
  ): SeasonResetGateDecision {
    val environment = deployedEnvironment?.trim().orEmpty()
    if (environment.isEmpty()) {
      return dry("SEASON_RESET_ENV is not set")
    }
    if (config == null) {
      return dry("config/seasonReset is missing")
    }
    if (!config.enabled) {
      return dry("config/seasonReset.enabled is not true")
    }
    val target = config.targetSeasonId?.trim().orEmpty()
    if (target.isEmpty()) {
      return dry("config/seasonReset.targetSeasonId is missing")
    }
    val configuredEnvironment = config.environment?.trim().orEmpty()
    if (configuredEnvironment.isEmpty()) {
      return dry("config/seasonReset.environment is missing")
    }
    if (configuredEnvironment != environment) {
      return dry(
        "config/seasonReset.environment '$configuredEnvironment' does not match " +
          "SEASON_RESET_ENV '$environment'",
      )
    }
    return SeasonResetGateDecision(
      mode = SeasonResetMode.APPLY,
      targetSeasonId = target,
      reason = "enabled for target '$target' in environment '$environment'",
    )
  }

  private fun dry(reason: String): SeasonResetGateDecision =
    SeasonResetGateDecision(SeasonResetMode.DRY_RUN, null, reason)
}

/**
 * The operator dry-run's allowlist. The caller is already authenticated by
 * [CallableAuth]; this adds the second check: the verified uid must be named in
 * `SEASON_RESET_OPERATOR_UIDS`, a comma-separated deploy-time environment
 * variable. An absent or empty allowlist denies everyone — fail closed.
 */
object SeasonResetOperator {

  fun isAllowed(uid: String, allowlist: String?): Boolean {
    if (uid.isBlank()) return false
    return allowlist
      ?.split(',')
      ?.map { it.trim() }
      ?.any { it.isNotEmpty() && it == uid }
      ?: false
  }
}
