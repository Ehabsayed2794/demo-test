package com.estemshan.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S59 — the kill switch's pure decision table. Every missing or mismatched
 * condition must leave the scheduler in DRY_RUN; only the full tuple arms APPLY.
 */
class SeasonResetGateTest {

  private fun config(
    enabled: Boolean = true,
    target: String? = "s2",
    environment: String? = "production",
  ): SeasonResetGateConfig = SeasonResetGateConfig(enabled, target, environment)

  @Test
  fun aMissingDeployedEnvironmentStaysDryRun() {
    val decision = SeasonResetGate.decide(config(), null)

    assertEquals(SeasonResetMode.DRY_RUN, decision.mode)
    assertNull(decision.targetSeasonId)
    assertTrue(decision.reason.contains("SEASON_RESET_ENV"))
  }

  @Test
  fun aBlankDeployedEnvironmentStaysDryRun() {
    assertEquals(
      SeasonResetMode.DRY_RUN,
      SeasonResetGate.decide(config(), "   ").mode,
    )
  }

  @Test
  fun aMissingConfigStaysDryRun() {
    val decision = SeasonResetGate.decide(null, "production")

    assertEquals(SeasonResetMode.DRY_RUN, decision.mode)
    assertNull(decision.targetSeasonId)
    assertTrue(decision.reason.contains("config/seasonReset is missing"))
  }

  @Test
  fun aDisabledConfigStaysDryRun() {
    val decision = SeasonResetGate.decide(config(enabled = false), "production")

    assertEquals(SeasonResetMode.DRY_RUN, decision.mode)
    assertNull(decision.targetSeasonId)
    assertTrue(decision.reason.contains("enabled"))
  }

  @Test
  fun aMissingTargetStaysDryRun() {
    val decision = SeasonResetGate.decide(config(target = "  "), "production")

    assertEquals(SeasonResetMode.DRY_RUN, decision.mode)
    assertNull(decision.targetSeasonId)
    assertTrue(decision.reason.contains("targetSeasonId"))
  }

  @Test
  fun aMissingConfigEnvironmentStaysDryRun() {
    val decision = SeasonResetGate.decide(config(environment = null), "production")

    assertEquals(SeasonResetMode.DRY_RUN, decision.mode)
    assertTrue(decision.reason.contains("environment"))
  }

  @Test
  fun anEnvironmentMismatchStaysDryRun() {
    val decision = SeasonResetGate.decide(config(environment = "staging"), "production")

    assertEquals(SeasonResetMode.DRY_RUN, decision.mode)
    assertNull(decision.targetSeasonId)
    assertTrue(decision.reason.contains("does not match"))
  }

  @Test
  fun allThreeConditionsArmApplyWithTheApprovedTarget() {
    val decision = SeasonResetGate.decide(config(), "production")

    assertEquals(SeasonResetMode.APPLY, decision.mode)
    assertEquals("s2", decision.targetSeasonId)
  }

  @Test
  fun anAbsentOperatorAllowlistDeniesEveryone() {
    assertFalse(SeasonResetOperator.isAllowed("operator-uid", null))
    assertFalse(SeasonResetOperator.isAllowed("operator-uid", ""))
    assertFalse(SeasonResetOperator.isAllowed("operator-uid", " , , "))
  }

  @Test
  fun anOperatorAllowlistAcceptsOnlyItsNamedUids() {
    assertTrue(SeasonResetOperator.isAllowed("operator-uid", "other-uid, operator-uid"))
    assertTrue(SeasonResetOperator.isAllowed("operator-uid", " operator-uid "))
    assertFalse(SeasonResetOperator.isAllowed("operator-uid", "other-uid"))
    assertFalse(SeasonResetOperator.isAllowed("", "operator-uid"))
  }
}
