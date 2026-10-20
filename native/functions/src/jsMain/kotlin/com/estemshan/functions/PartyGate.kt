package com.estemshan.functions

import com.estemshan.engine.MatchMode
import com.estemshan.engine.PARTY_DENIAL_REASON
import com.estemshan.engine.RankedProfile
import com.estemshan.engine.arePartyProfilesEligible

/**
 * S70 — the Ranked party join-gate decision core (RD29/RD30).
 *
 * The future callable reads BOTH profiles server-side via the Admin SDK and
 * passes them here; its response carries only allow/deny plus the reason the
 * client may surface. **The client never reads another player's rank**
 * (RD29), so no decision — and no message — carries a tier, division, RP
 * value, or uid.
 *
 * The gate applies only to Rank-down, which RD28 defines as `mode: RANKED`
 * plus the room's private-access flag — never a mode of its own. Public
 * Ranked (an Auto-Match join) and every non-Ranked mode are
 * [PartyGateDecision.NotApplicable].
 *
 * Eligibility itself is the engine's pure RD29 tier comparison
 * ([arePartyProfilesEligible], symmetric, one tier apart at most, King next
 * to Royal, unplaced accounts already Bronze III per RD30). This file adds
 * only the join-gate envelope: the mode/flag gate, the fail-closed handling
 * of unknown profiles, and the opaque denial shape.
 *
 * Pure decision logic, no I/O: no Firestore, Admin SDK, callable, or UI.
 */

/** The denial reason the S70 join gate returns for an ineligible party. */
private const val PROFILE_UNAVAILABLE_REASON: String = "PROFILE_UNAVAILABLE"

/**
 * Generic by design: a missing or malformed profile must not disclose which
 * side failed, nor any stored rank value.
 */
private const val PROFILE_UNAVAILABLE_MESSAGE: String =
  "Player ranks aren't available right now. Please try again."

/**
 * Plain-language RD29 denial. It states the eligibility outcome without the
 * host's or the joiner's actual rank, and offers the owner-approved escape
 * hatch from the plan: the host can switch the game to Unranked.
 */
private const val PARTY_INELIGIBLE_MESSAGE: String =
  "This player's rank is too far from the host's for a Ranked party. " +
    "The host can switch this game to Unranked."

/**
 * The outcome of one Rank-down join attempt. The type deliberately has no
 * rank-bearing members: [Denied] carries the stable machine reason and a
 * plain-language, rank-free message, nothing else.
 */
sealed interface PartyGateDecision {
  /** Both players are within RD29's one-tier boundary. */
  data object Allowed : PartyGateDecision

  /** Not a Rank-down join: no eligibility check applies. */
  data object NotApplicable : PartyGateDecision

  /** The join is refused; [reason] is stable, [message] is rank-free. */
  data class Denied(val reason: String, val message: String) : PartyGateDecision
}

/**
 * Decide one join to a Rank-down Ranked room.
 *
 * @param mode the persisted authority key (RD28); only [MatchMode.RANKED]
 *   can be gated, and only together with [rankDown].
 * @param rankDown the room's private/password access flag — Rank-down is a
 *   configuration of Ranked, never a fourth mode.
 * @param creator the room creator's profile, read by the future callable via
 *   Admin; null when the profile document is missing.
 * @param joiner the joining player's profile, likewise remote to this client.
 *
 * The mode/flag gate runs first: every join that is not Rank-down is
 * [PartyGateDecision.NotApplicable], even if a profile is missing. A null
 * profile, or a profile that names no ladder rung, fails closed as
 * [PROFILE_UNAVAILABLE_REASON] rather than admitting the player. Otherwise
 * the engine's RD29 comparison decides: eligible joins are
 * [PartyGateDecision.Allowed], ineligible joins are denied with exactly
 * [PARTY_DENIAL_REASON].
 */
fun decidePartyJoin(
  mode: MatchMode,
  rankDown: Boolean,
  creator: RankedProfile?,
  joiner: RankedProfile?,
): PartyGateDecision {
  if (mode != MatchMode.RANKED || !rankDown) return PartyGateDecision.NotApplicable
  if (creator == null || joiner == null) {
    return PartyGateDecision.Denied(PROFILE_UNAVAILABLE_REASON, PROFILE_UNAVAILABLE_MESSAGE)
  }
  val eligibility = runCatching { arePartyProfilesEligible(creator, joiner) }
  return eligibility.fold(
    onSuccess = { eligible ->
      if (eligible) {
        PartyGateDecision.Allowed
      } else {
        PartyGateDecision.Denied(PARTY_DENIAL_REASON, PARTY_INELIGIBLE_MESSAGE)
      }
    },
    onFailure = {
      PartyGateDecision.Denied(PROFILE_UNAVAILABLE_REASON, PROFILE_UNAVAILABLE_MESSAGE)
    },
  )
}
