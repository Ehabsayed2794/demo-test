package com.estemshan.functions

import com.estemshan.engine.LadderRank
import com.estemshan.engine.MatchMode
import com.estemshan.engine.PARTY_DENIAL_REASON
import com.estemshan.engine.PlacementState
import com.estemshan.engine.RankedProfile
import com.estemshan.engine.partyTierOrder
import com.estemshan.engine.rankLadder
import com.estemshan.engine.skipPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * S70 — the Ranked party join-gate decision core (RD29/RD30).
 *
 * These tests pin the whole contract of the pure decision the future callable
 * will wrap: the gate applies only to Rank-down (RANKED + private flag), every
 * ordered pair of the seven tiers follows the one-tier boundary, divisions
 * never matter, King borders Royal, the decision is symmetric, unplaced
 * accounts resolve to a real Bronze III, missing/corrupt profiles fail closed,
 * and no denial leaks a tier, division, RP value, or uid.
 */
class PartyGateTest {

  /**
   * RD29's seven tiers, hardcoded independently of the engine so the oracle
   * below cannot move with a regression in [partyTierOrder].
   */
  private val tiers: List<String> =
    listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond", "Royal", "King")

  private fun profileOf(rank: LadderRank): RankedProfile = RankedProfile(
    tier = rank.tier,
    division = rank.division,
    rp = rank.lowerBound,
    seasonId = "S1",
    placementState = PlacementState.COMPLETE,
  )

  /** The first rung of [tier] (III for divisions, King alone). */
  private fun at(tier: String): RankedProfile =
    profileOf(rankLadder.first { it.tier == tier })

  private fun ranked(
    creator: RankedProfile?,
    joiner: RankedProfile?,
    rankDown: Boolean = true,
  ): PartyGateDecision = decidePartyJoin(MatchMode.RANKED, rankDown, creator, joiner)

  private fun assertGenericMessage(message: String) {
    assertTrue(message.isNotBlank(), "a denial must carry a plain-language message")
    val lower = message.lowercase()
    for (tier in tiers) {
      assertFalse(
        lower.contains(tier.lowercase()),
        "message must not name tier '$tier': $message",
      )
    }
    assertFalse(message.any { it.isDigit() }, "message must not carry an RP value: $message")
    assertFalse(
      Regex("\\brp\\b", RegexOption.IGNORE_CASE).containsMatchIn(message),
      "message must not mention RP: $message",
    )
    assertFalse(lower.contains("uid"), "message must not carry a uid: $message")
    assertFalse(
      Regex("\\b(I|II|III)\\b").containsMatchIn(message),
      "message must not carry a division: $message",
    )
    assertFalse(
      Regex(
        "\\b(one|two|three|four|five|six|seven|eight|nine|ten|hundred|thousand|points?)\\b",
        RegexOption.IGNORE_CASE,
      ).containsMatchIn(message),
      "message must not spell out an RP value: $message",
    )
    for (title in listOf("مبتدئ", "لاعب", "معلم", "بلاتين", "أمير", "سلطان", "ملك")) {
      assertFalse(lower.contains(title), "message must not name the Arabic tier '$title': $message")
    }
  }

  @Test
  fun allFortyNineOrderedTierPairsFollowTheOneTierBoundary() {
    assertEquals(7, tiers.size, "RD29 compares seven tiers")
    assertEquals(tiers, partyTierOrder, "the engine's tier order must match RD29")
    var allowed = 0
    var denied = 0
    for (creatorTier in tiers) {
      for (joinerTier in tiers) {
        val distance = kotlin.math.abs(tiers.indexOf(creatorTier) - tiers.indexOf(joinerTier))
        val decision = ranked(at(creatorTier), at(joinerTier))
        val label = "$creatorTier host + $joinerTier joiner"
        if (distance <= 1) {
          assertEquals(PartyGateDecision.Allowed, decision, label)
          allowed++
        } else {
          assertIs<PartyGateDecision.Denied>(decision, label)
          assertEquals(PARTY_DENIAL_REASON, decision.reason, label)
          denied++
        }
      }
    }
    assertEquals(49, allowed + denied, "all 7 x 7 ordered pairs were exercised")
    assertEquals(19, allowed, "7 same-tier + 12 one-tier-apart ordered pairs")
    assertEquals(30, denied, "the remaining two-or-more-tier-apart ordered pairs")
  }

  @Test
  fun everyOrderedRungPairFollowsTierDistanceNotRungIndex() {
    // RD29 compares tiers, never the 19-rung index: Bronze III + Silver I is
    // five rungs apart and still eligible, because the tiers are adjacent.
    assertEquals(19, rankLadder.size, "the ladder's 19 rungs")
    var allowed = 0
    var denied = 0
    for (creatorRung in rankLadder) {
      for (joinerRung in rankLadder) {
        val distance = kotlin.math.abs(
          tiers.indexOf(creatorRung.tier) - tiers.indexOf(joinerRung.tier),
        )
        val decision = ranked(profileOf(creatorRung), profileOf(joinerRung))
        val label = "${creatorRung.tier} ${creatorRung.division} host + " +
          "${joinerRung.tier} ${joinerRung.division} joiner"
        if (distance <= 1) {
          assertEquals(PartyGateDecision.Allowed, decision, label)
          allowed++
        } else {
          assertIs<PartyGateDecision.Denied>(decision, label)
          assertEquals(PARTY_DENIAL_REASON, decision.reason, label)
          denied++
        }
      }
    }
    assertEquals(361, allowed + denied, "all 19 x 19 ordered rung pairs were exercised")
    // 55 same-tier (6 x 3 x 3, plus King) + 96 adjacent-tier ordered pairs.
    assertEquals(151, allowed)
    assertEquals(210, denied)
  }

  @Test
  fun divisionGapsWithinTheSameTierAreAlwaysEligible() {
    for ((tier, rungs) in rankLadder.groupBy { it.tier }) {
      for (a in rungs) {
        for (b in rungs) {
          assertEquals(
            PartyGateDecision.Allowed,
            ranked(profileOf(a), profileOf(b)),
            "$tier ${a.division} + $tier ${b.division} is always eligible",
          )
        }
      }
    }
  }

  @Test
  fun swappingCreatorAndJoinerReturnsTheIdenticalDecisionAndMessage() {
    for (creatorTier in tiers) {
      for (joinerTier in tiers) {
        assertEquals(
          ranked(at(creatorTier), at(joinerTier)),
          ranked(at(joinerTier), at(creatorTier)),
          "$creatorTier <-> $joinerTier must be symmetric",
        )
      }
    }
    val forward = ranked(at("Bronze"), at("Gold")) as PartyGateDecision.Denied
    val backward = ranked(at("Gold"), at("Bronze")) as PartyGateDecision.Denied
    assertEquals(forward.message, backward.message)
    assertEquals(forward.reason, backward.reason)
  }

  @Test
  fun unplacedDefaultBronzePartiesWithAFreshBronzeButNotGold() {
    // RD30: an unplaced account holds Bronze III as a REAL tier (NOT_STARTED),
    // and an explicit "Start from Bronze" account is the same real tier.
    val unplaced = RankedProfile(seasonId = "S1")
    val freshBronze = skipPlacement("S1")
    assertEquals(PartyGateDecision.Allowed, ranked(unplaced, freshBronze))
    assertEquals(PartyGateDecision.Allowed, ranked(freshBronze, unplaced))

    val denied = ranked(unplaced, at("Gold"))
    assertIs<PartyGateDecision.Denied>(denied)
    assertEquals(PARTY_DENIAL_REASON, denied.reason)
  }

  @Test
  fun kingBordersRoyalOnly() {
    assertEquals(PartyGateDecision.Allowed, ranked(at("King"), at("Royal")))
    assertEquals(PartyGateDecision.Allowed, ranked(at("Royal"), at("King")))
    assertIs<PartyGateDecision.Denied>(ranked(at("King"), at("Diamond")))
    assertIs<PartyGateDecision.Denied>(ranked(at("Diamond"), at("King")))
  }

  @Test
  fun onlyRankedPlusRankDownJoinsAreGated() {
    val bronze = at("Bronze")
    val king = at("King")
    assertEquals(
      PartyGateDecision.NotApplicable,
      decidePartyJoin(MatchMode.ROOM, true, bronze, king),
    )
    assertEquals(
      PartyGateDecision.NotApplicable,
      decidePartyJoin(MatchMode.UNRANKED, true, bronze, king),
    )
    assertEquals(
      PartyGateDecision.NotApplicable,
      decidePartyJoin(MatchMode.RANKED, false, bronze, king),
    )
    // The mode gate runs first: missing profiles are not an error off-gate.
    assertEquals(
      PartyGateDecision.NotApplicable,
      decidePartyJoin(MatchMode.ROOM, true, null, null),
    )
  }

  @Test
  fun aMissingCreatorOrJoinerIsProfileUnavailable() {
    val bronze = at("Bronze")
    val missingCreator = ranked(null, bronze)
    val missingJoiner = ranked(bronze, null)
    for (decision in listOf(missingCreator, missingJoiner)) {
      assertIs<PartyGateDecision.Denied>(decision)
      assertEquals("PROFILE_UNAVAILABLE", decision.reason)
      assertGenericMessage(decision.message)
    }
    assertEquals(missingCreator, missingJoiner, "both sides fail the same way")
  }

  @Test
  fun corruptOrForeignProfilesFailClosedAsProfileUnavailable() {
    val bronze = at("Bronze")
    // Names no ladder rung: Gold has no division-less rung.
    val corrupt = RankedProfile(tier = "Gold", division = "", rp = 700, seasonId = "S1")
    // Names a tier from a different ladder entirely.
    val foreign = RankedProfile(tier = "Mithril", division = "I", rp = 9_999, seasonId = "S1")
    for (bad in listOf(corrupt, foreign)) {
      for (decision in listOf(ranked(bad, bronze), ranked(bronze, bad))) {
        assertIs<PartyGateDecision.Denied>(decision)
        assertEquals("PROFILE_UNAVAILABLE", decision.reason)
        assertGenericMessage(decision.message)
      }
    }
  }

  @Test
  fun everyDenialMessageLeaksNoTierDivisionRpOrUid() {
    var denials = 0
    for (creatorTier in tiers) {
      for (joinerTier in tiers) {
        val decision = ranked(at(creatorTier), at(joinerTier))
        if (decision is PartyGateDecision.Denied) {
          assertGenericMessage(decision.message)
          denials++
        }
      }
    }
    assertEquals(30, denials)
  }

  @Test
  fun anIneligiblePairIsDeniedWithExactlyRankIncompatible() {
    val decision = ranked(at("Bronze"), at("Gold"))
    assertIs<PartyGateDecision.Denied>(decision)
    assertEquals("RANK_INCOMPATIBLE", decision.reason)
    assertEquals(PARTY_DENIAL_REASON, decision.reason)
  }

  @Test
  fun denialAndUnavailableMessagesAreTheApprovedGenericCopy() {
    val ineligible = ranked(at("Bronze"), at("Gold"))
    assertIs<PartyGateDecision.Denied>(ineligible)
    assertEquals(
      "This player's rank is too far from the host's for a Ranked party. " +
        "The host can switch this game to Unranked.",
      ineligible.message,
    )
    val unavailable = ranked(null, at("Bronze"))
    assertIs<PartyGateDecision.Denied>(unavailable)
    assertEquals(
      "Player ranks aren't available right now. Please try again.",
      unavailable.message,
    )
  }
}
