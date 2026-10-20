package com.estemshan.services

import com.estemshan.services.model.Reasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S64 (RD24, RD27) — what the client is allowed to learn from a leaderboard
 * response, and what a refused read means to the screen.
 *
 * The matrix is the port's whole contract: a page the server computed is
 * carried through field-for-field and in the server's order; a page from the
 * wrong season, or one whose positions could not have come from the server, is
 * refused rather than re-sorted, renumbered, clamped, or repaired. The happy
 * paths are therefore asserted as exact data-class equality, and every
 * malformed path asserts the one generic message — no payload value is ever
 * allowed to reach display copy.
 *
 * Firestore hands the same whole number to different clients as an Int, a Long,
 * or a Double, so those widths are pinned as accepted (when integral) and
 * fractional values as refused. The wire vocabulary (`ok`, `seasonId`, `rows`,
 * `you`, `total`, `skipped`, and every row key) is repeated here as literals
 * because `:services` cannot see `:functions`; the server's callable is the
 * other side of this mapping.
 *
 * Deliberately NOT here: `FirebaseFunctionsException` and `FirebaseFunctions`.
 * The SDK enum cannot initialize on a plain JVM, which is exactly why
 * [LeaderboardResponse.forCodeName] takes the code's name; the one-line
 * `e.code.name` delegation in [CallableLeaderboard] is the SDK boundary this
 * task leaves unexercised, because the brief scopes this suite to
 * [LeaderboardResponse]. No device or emulator test is added here.
 */
class LeaderboardResponseTest {

  private companion object {
    const val SEASON = "s2"

    const val MALFORMED = "The leaderboard response was not readable."
    const val REJECTED = "The leaderboard request was rejected."
    const val SIGN_IN = "Sign in to view the leaderboard."
    const val UNAVAILABLE_MESSAGE = "The leaderboard is unavailable right now. Please try again."

    /**
     * `FirebaseFunctionsException.Code`, spelled as names. The SDK type is
     * loaded nowhere in this file because its enum cannot initialize on a plain
     * JVM; [LeaderboardResponse.forCodeName] is the half that can be tested
     * without the Android runtime.
     */
    val ALL_CODE_NAMES = listOf(
      "CANCELLED",
      "UNKNOWN",
      "INVALID_ARGUMENT",
      "DEADLINE_EXCEEDED",
      "NOT_FOUND",
      "ALREADY_EXISTS",
      "PERMISSION_DENIED",
      "RESOURCE_EXHAUSTED",
      "FAILED_PRECONDITION",
      "ABORTED",
      "OUT_OF_RANGE",
      "UNIMPLEMENTED",
      "INTERNAL",
      "UNAVAILABLE",
      "DATA_LOSS",
      "UNAUTHENTICATED",
    )
  }

  /** One row exactly as the callable projects it. */
  private fun row(
    position: Int = 1,
    displayName: String = "Nour",
    avatarInitial: String = "N",
    tier: String = "Gold",
    tierArabic: String = "معلم",
    division: String = "II",
    rp: Int = 700,
    seasonId: String = SEASON,
    isCurrentPlayer: Boolean = false,
  ): Map<String, Any?> = mapOf(
    "position" to position,
    "displayName" to displayName,
    "avatarInitial" to avatarInitial,
    "tier" to tier,
    "tierArabic" to tierArabic,
    "division" to division,
    "rp" to rp,
    "seasonId" to seasonId,
    "isCurrentPlayer" to isCurrentPlayer,
  )

  /** One successful response; every field is overridable for malformed shapes. */
  private fun response(
    ok: Any? = true,
    seasonId: Any? = SEASON,
    rows: Any? = listOf(row()),
    you: Any? = null,
    total: Any? = 1,
    skipped: Any? = 0,
  ): Map<String, Any?> = mapOf(
    "ok" to ok,
    "seasonId" to seasonId,
    "rows" to rows,
    "you" to you,
    "total" to total,
    "skipped" to skipped,
  )

  private fun outcome(
    data: Any?,
    requestedSeasonId: String? = null,
  ): LeaderboardPort.Outcome = LeaderboardResponse.forData(data, requestedSeasonId)

  private fun page(loaded: LeaderboardPort.Outcome): LeaderboardPort.Page {
    assertTrue("expected Loaded, was $loaded", loaded is LeaderboardPort.Outcome.Loaded)
    return (loaded as LeaderboardPort.Outcome.Loaded).page
  }

  private fun failure(outcome: LeaderboardPort.Outcome): LeaderboardPort.Outcome.Failed {
    assertTrue("expected Failed, was $outcome", outcome is LeaderboardPort.Outcome.Failed)
    return outcome as LeaderboardPort.Outcome.Failed
  }

  private fun assertMalformed(outcome: LeaderboardPort.Outcome) {
    val failed = failure(outcome)
    assertEquals(Reasons.UNAVAILABLE, failed.reason)
    assertEquals(MALFORMED, failed.message)
  }

  // ── pages the server could have produced ─────────────────────────────

  @Test
  fun aFullPageIsLoadedExactlyAsTheServerOrderedIt() {
    val first = row(
      position = 1, displayName = "Nour", avatarInitial = "N",
      tier = "Gold", tierArabic = "معلم", division = "II", rp = 700,
    )
    val second = row(
      position = 2, displayName = "Mariam", avatarInitial = "M",
      tier = "Platinum", tierArabic = "وزير", division = "I", rp = 1_400,
      isCurrentPlayer = true,
    )
    val third = row(
      position = 3, displayName = "Youssef", avatarInitial = "Y",
      tier = "Silver", tierArabic = "لاعب", division = "III", rp = 250,
    )

    val loaded = page(
      outcome(response(rows = listOf(first, second, third), you = second, total = 3, skipped = 0)),
    )

    // Every field of every row, and the order itself: no key is dropped,
    // renamed, coerced, or re-sorted on the way to the page.
    assertEquals(
      LeaderboardPort.Page(
        seasonId = "s2",
        rows = listOf(
          LeaderboardPort.Row(1, "Nour", "N", "Gold", "معلم", "II", 700, "s2", false),
          LeaderboardPort.Row(2, "Mariam", "M", "Platinum", "وزير", "I", 1_400, "s2", true),
          LeaderboardPort.Row(3, "Youssef", "Y", "Silver", "لاعب", "III", 250, "s2", false),
        ),
        you = LeaderboardPort.Row(2, "Mariam", "M", "Platinum", "وزير", "I", 1_400, "s2", true),
        total = 3,
        skipped = 0,
      ),
      loaded,
    )
  }

  @Test
  fun aCallerOutsideThePageStillGetsTheirOwnRow() {
    val top = listOf(
      row(position = 1, displayName = "Nour", avatarInitial = "N"),
      row(position = 2, displayName = "Mariam", avatarInitial = "M"),
    )
    val me = row(
      position = 42, displayName = "Hassan", avatarInitial = "H",
      isCurrentPlayer = true,
    )

    val loaded = page(outcome(response(rows = top, you = me, total = 42, skipped = 1)))

    assertEquals(listOf(1, 2), loaded.rows.map { it.position })
    assertEquals(
      LeaderboardPort.Row(42, "Hassan", "H", "Gold", "معلم", "II", 700, "s2", true),
      loaded.you,
    )
    // The board's whole size, not the page's: 2 rows are shown of 42 players.
    assertEquals(42, loaded.total)
    assertEquals(1, loaded.skipped)
  }

  @Test
  fun anEmptyBoardIsAFreshSeasonNotAFailure() {
    val loaded = page(
      outcome(response(rows = emptyList<Any>(), you = null, total = 0, skipped = 0)),
    )

    assertEquals(LeaderboardPort.Page("s2", emptyList(), null, 0, 0), loaded)
  }

  @Test
  fun aKingRowCarriesNoDivision() {
    val king = row(
      position = 1, tier = "King", tierArabic = "ملك", division = "", rp = 3_250,
    )

    val loaded = page(outcome(response(rows = listOf(king), total = 1)))

    assertEquals("", loaded.rows.single().division)
    assertEquals("ملك", loaded.rows.single().tierArabic)
    assertEquals(3_250, loaded.rows.single().rp)
  }

  @Test
  fun tiesKeepTheServersSharingPositions() {
    val rows = listOf(
      row(position = 1, displayName = "A", avatarInitial = "A"),
      row(position = 2, displayName = "B", avatarInitial = "B"),
      row(position = 2, displayName = "C", avatarInitial = "C"),
      row(position = 4, displayName = "D", avatarInitial = "D"),
    )

    val loaded = page(outcome(response(rows = rows, total = 4)))

    // 1, 2, 2, 4 stays 1, 2, 2, 4: the client does not renumber a tie.
    assertEquals(listOf(1, 2, 2, 4), loaded.rows.map { it.position })
    assertEquals(listOf("A", "B", "C", "D"), loaded.rows.map { it.displayName })
  }

  @Test
  fun privateProfileFieldsNeverReachThePage() {
    val leanRows = listOf(
      row(position = 1, displayName = "Nour", avatarInitial = "N"),
      row(position = 2, displayName = "Mariam", avatarInitial = "M", isCurrentPlayer = true),
    )
    val leanYou = leanRows[1]
    val lean = page(outcome(response(rows = leanRows, you = leanYou, total = 2, skipped = 0)))

    val private = mapOf(
      "playerId" to "uid-1",
      "uid" to "uid-1",
      "email" to "nour@example.com",
      "rank" to "Diamond I",
      "highestRank" to "King",
      "coins" to 9_999,
    )
    val noisy = page(
      outcome(
        response(
          rows = leanRows.map { it + private },
          you = leanYou + private,
          total = 2,
          skipped = 0,
        ) + private,
      ),
    )

    // Data-class equality is the proof: the extra keys are not read at all.
    assertEquals(lean, noisy)
    assertFalse(noisy.toString().contains("uid-1"))
    assertFalse(noisy.toString().contains("nour@example.com"))
    assertFalse(noisy.toString().contains("coins"))
  }

  @Test
  fun integerWidthsFromTheWireAreAccepted() {
    val rows = listOf(
      row(position = 1) + mapOf("position" to 1L, "rp" to 700.0),
      row(position = 2, displayName = "Mariam") +
        mapOf("position" to 2.0, "rp" to 1_400L),
    )

    val loaded = page(outcome(response(rows = rows, total = 2L, skipped = 0.0)))

    // Int, Long and an integral Double are the same whole number.
    assertEquals(listOf(1, 2), loaded.rows.map { it.position })
    assertEquals(listOf(700, 1_400), loaded.rows.map { it.rp })
    assertEquals(2, loaded.total)
    assertEquals(0, loaded.skipped)
  }

  // ── one season, and only the one that was asked for ──────────────────

  @Test
  fun anUnnamedRequestUsesTheServersEchoedSeason() {
    val loaded = page(
      outcome(
        response(seasonId = "s2", rows = emptyList<Any>(), total = 0),
        requestedSeasonId = null,
      ),
    )

    // There is no client-side default season: the echo is the only answer to
    // "which board is this?", and it is s2 because the server said so.
    assertEquals("s2", loaded.seasonId)
  }

  @Test
  fun anExplicitRequestThatIsEchoedLoads() {
    val loaded = page(
      outcome(
        response(seasonId = "s2", rows = emptyList<Any>(), total = 0),
        requestedSeasonId = "s2",
      ),
    )

    assertEquals("s2", loaded.seasonId)
  }

  @Test
  fun anEchoedSeasonThatDiffersFailsClosed() {
    assertMalformed(
      outcome(
        response(seasonId = "s1", rows = emptyList<Any>(), total = 0),
        requestedSeasonId = "s2",
      ),
    )
  }

  @Test
  fun theSeasonEchoIsCaseSensitive() {
    assertMalformed(
      outcome(
        response(seasonId = "s2", rows = emptyList<Any>(), total = 0),
        requestedSeasonId = "S2",
      ),
    )
  }

  @Test
  fun aRowFromAnotherSeasonFailsClosed() {
    val foreign = row(position = 1, seasonId = "s1")

    assertMalformed(outcome(response(rows = listOf(foreign), total = 1)))
  }

  // ── shapes the port will not trust ───────────────────────────────────

  @Test
  fun aBodyThatIsNotAMapIsMalformed() {
    for (data in listOf(null, "board", 42, 3.5, emptyList<Any>(), true)) {
      assertMalformed(outcome(data))
    }
  }

  @Test
  fun okMustBeExactlyTrue() {
    assertMalformed(outcome(response() - "ok"))
    assertMalformed(outcome(response(ok = false)))
    assertMalformed(outcome(response(ok = "true")))
  }

  @Test
  fun seasonIdMustBeANonBlankString() {
    assertMalformed(outcome(response() - "seasonId"))
    assertMalformed(outcome(response(seasonId = "")))
    assertMalformed(outcome(response(seasonId = "   ")))
    assertMalformed(outcome(response(seasonId = 2)))
  }

  @Test
  fun rowsMustBeAList() {
    assertMalformed(outcome(response() - "rows"))
    assertMalformed(outcome(response(rows = null)))
    assertMalformed(outcome(response(rows = "rows")))
    assertMalformed(outcome(response(rows = mapOf("0" to row()))))
  }

  @Test
  fun totalsMustBeWholeNonNegativeCounts() {
    for (key in listOf("total", "skipped")) {
      val base = response()

      assertMalformed(outcome(base - key))
      for (bad in listOf(-1, 1.5, "3", true, Double.NaN, Double.POSITIVE_INFINITY)) {
        assertMalformed(outcome(base + (key to bad)))
      }
    }
  }

  @Test
  fun totalCannotBeSmallerThanThePage() {
    // Both tied rows sit at a valid position on a one-player board (1, 1), so
    // the only violated invariant is total < rows.size -- not a position past
    // the board. This isolates the count check from the bound check below.
    val rows = listOf(
      row(position = 1, displayName = "A"),
      row(position = 1, displayName = "B"),
    )

    assertMalformed(outcome(response(rows = rows, total = 1)))
  }

  @Test
  fun aPositionBeyondTheBoardIsMalformed() {
    // Ties make this reachable without total < rows.size: three rows at
    // 1, 1, 4 against a board of three still claims a position that cannot
    // exist on that board.
    val tied = listOf(
      row(position = 1, displayName = "A"),
      row(position = 1, displayName = "B"),
      row(position = 4, displayName = "C"),
    )
    assertMalformed(outcome(response(rows = tied, total = 3)))

    val me = row(position = 3, displayName = "Me", isCurrentPlayer = true)
    assertMalformed(outcome(response(rows = listOf(row(position = 1)), you = me, total = 2)))
  }

  @Test
  fun youMustBeARowAndMustBeTheCurrentPlayer() {
    // Not a row at all.
    assertMalformed(outcome(response(you = "me")))
    // A row map missing a contracted field.
    assertMalformed(outcome(response(you = row() - "rp")))
    // A row that claims no highlight is not the caller's current-player row.
    assertMalformed(outcome(response(you = row(isCurrentPlayer = false))))
    // A different row is highlighted, so this `you` is not the current row.
    assertMalformed(
      outcome(
        response(
          rows = listOf(
            row(position = 1, displayName = "A", isCurrentPlayer = true),
            row(position = 2, displayName = "B"),
          ),
          you = row(position = 2, displayName = "B", isCurrentPlayer = true),
          total = 2,
        ),
      ),
    )
    // A highlighted row with no caller row would point at nobody.
    assertMalformed(
      outcome(
        response(
          rows = listOf(row(position = 1, displayName = "A", isCurrentPlayer = true)),
          you = null,
          total = 1,
        ),
      ),
    )
  }

  @Test
  fun everyContractedRowKeyIsRequired() {
    val keys = listOf(
      "position",
      "displayName",
      "avatarInitial",
      "tier",
      "tierArabic",
      "division",
      "rp",
      "seasonId",
      "isCurrentPlayer",
    )

    for (key in keys) {
      assertMalformed(outcome(response(rows = listOf(row() - key), total = 1)))
    }
  }

  @Test
  fun positionMustBeAWholePositiveNumber() {
    for (bad in listOf(0, -1, 1.5, "1")) {
      assertMalformed(
        outcome(response(rows = listOf(row() + ("position" to bad)), total = 1)),
      )
    }
  }

  @Test
  fun rpMustBeAWholeNonNegativeNumber() {
    for (bad in listOf(-1, 1.5, "700")) {
      assertMalformed(
        outcome(response(rows = listOf(row() + ("rp" to bad)), total = 1)),
      )
    }
  }

  @Test
  fun theDisplayFieldsMustNotBeBlank() {
    for (key in listOf("displayName", "avatarInitial", "tier", "tierArabic")) {
      assertMalformed(outcome(response(rows = listOf(row() + (key to "")), total = 1)))
      assertMalformed(outcome(response(rows = listOf(row() + (key to "   ")), total = 1)))
    }
  }

  @Test
  fun divisionMustBeAStringEvenWhenEmpty() {
    assertMalformed(
      outcome(response(rows = listOf(row() + ("division" to null)), total = 1)),
    )
  }

  @Test
  fun isCurrentPlayerMustBeABoolean() {
    assertMalformed(outcome(response(rows = listOf(row() - "isCurrentPlayer"), total = 1)))
    assertMalformed(
      outcome(response(rows = listOf(row() + ("isCurrentPlayer" to "true")), total = 1)),
    )
  }

  @Test
  fun rowsMustArriveInTheServersOrder() {
    val descending = listOf(
      row(position = 3, displayName = "C"),
      row(position = 2, displayName = "B"),
      row(position = 1, displayName = "A"),
    )
    assertMalformed(outcome(response(rows = descending, total = 3)))

    val notStartingAtOne = listOf(
      row(position = 2, displayName = "B"),
      row(position = 3, displayName = "C"),
    )
    assertMalformed(outcome(response(rows = notStartingAtOne, total = 3)))

    // A list that starts at 1 but goes backwards later is still refused: the
    // port does not re-sort a page whose ordering it cannot trust.
    val shuffled = listOf(
      row(position = 1, displayName = "A"),
      row(position = 3, displayName = "C"),
      row(position = 2, displayName = "B"),
    )
    assertMalformed(outcome(response(rows = shuffled, total = 3)))
  }

  @Test
  fun onlyOneRowMayBeFlaggedAsTheCurrentPlayer() {
    val rows = listOf(
      row(position = 1, displayName = "A", isCurrentPlayer = true),
      row(position = 2, displayName = "B", isCurrentPlayer = true),
    )

    assertMalformed(outcome(response(rows = rows, you = rows[0], total = 2)))
  }

  // ── the codes: what a failed call means to the screen ────────────────

  @Test
  fun invalidArgumentIsARejectedRequest() {
    val failed = failure(LeaderboardResponse.forCodeName("INVALID_ARGUMENT"))

    assertEquals(Reasons.INVALID_ARGUMENT, failed.reason)
    assertEquals(REJECTED, failed.message)
  }

  @Test
  fun authenticationCodesAskForASignIn() {
    for (name in listOf("UNAUTHENTICATED", "PERMISSION_DENIED")) {
      val failed = failure(LeaderboardResponse.forCodeName(name))

      assertEquals(name, Reasons.UNAUTHENTICATED, failed.reason)
      assertEquals(name, SIGN_IN, failed.message)
    }
  }

  @Test
  fun unavailableCodesAreUnavailable() {
    val names = listOf(
      "UNAVAILABLE",
      "DEADLINE_EXCEEDED",
      "INTERNAL",
      "ABORTED",
      "CANCELLED",
    )

    for (name in names) {
      val failed = failure(LeaderboardResponse.forCodeName(name))

      assertEquals(name, Reasons.UNAVAILABLE, failed.reason)
      assertEquals(name, UNAVAILABLE_MESSAGE, failed.message)
    }
  }

  @Test
  fun oversizedAndEveryOtherCodeAreUnavailable() {
    // RESOURCE_EXHAUSTED is the server's oversized-season refusal: there is no
    // partial board, so it is UNAVAILABLE to the screen, not a fourth reason.
    val names = listOf(
      "RESOURCE_EXHAUSTED",
      "FAILED_PRECONDITION",
      "NOT_FOUND",
      "ALREADY_EXISTS",
      "OUT_OF_RANGE",
      "UNIMPLEMENTED",
      "DATA_LOSS",
      "UNKNOWN",
      "A_CODE_ADDED_IN_A_LATER_SDK",
    )

    for (name in names) {
      val failed = failure(LeaderboardResponse.forCodeName(name))

      assertEquals(name, Reasons.UNAVAILABLE, failed.reason)
      assertEquals(name, UNAVAILABLE_MESSAGE, failed.message)
    }
  }

  @Test
  fun everyCodeMapsToADisplaySafeMessage() {
    val names = ALL_CODE_NAMES + "A_CODE_ADDED_IN_A_LATER_SDK"

    for (name in names) {
      val failed = failure(LeaderboardResponse.forCodeName(name))

      assertTrue(
        "$name must map to one of the three display reasons, was ${failed.reason}",
        failed.reason in setOf(
          Reasons.INVALID_ARGUMENT,
          Reasons.UNAUTHENTICATED,
          Reasons.UNAVAILABLE,
        ),
      )
      assertTrue("$name must leave display copy", failed.message.isNotBlank())

      // No season literal, uid, tier, or RP may ride a failure message.
      for (token in listOf("s1", "s2", "uid", "gold", "platinum", "rp")) {
        assertFalse(
          "$name leaked '$token' in '${failed.message}'",
          failed.message.contains(token, ignoreCase = true),
        )
      }
    }

    // Three fixed messages, no fourth and no interpolated payload.
    val messages = names.map { failure(LeaderboardResponse.forCodeName(it)).message }.toSet()
    assertEquals(setOf(REJECTED, SIGN_IN, UNAVAILABLE_MESSAGE), messages)
  }

  // ── valid-response edge cases and low-risk pins ───────────────────────

  @Test
  fun aCallerWithNoProfileStillSeesATruncatedBoard() {
    val rows = listOf(
      row(position = 1, displayName = "A", avatarInitial = "A"),
      row(position = 2, displayName = "B", avatarInitial = "B"),
    )

    val loaded = page(
      outcome(response(rows = rows, you = null, total = 10, skipped = 1)),
    )

    assertEquals(listOf(1, 2), loaded.rows.map { it.position })
    assertNull(loaded.you)
    assertEquals(10, loaded.total)
    assertEquals(1, loaded.skipped)
  }

  @Test
  fun aFullySkippedSeasonIsAnEmptyBoardNotAFailure() {
    // All profiles unusable: total counts the ordered board (0), skipped counts
    // the refused profiles (3). This is a valid server response, not corruption.
    val loaded = page(
      outcome(response(rows = emptyList<Any>(), you = null, total = 0, skipped = 3)),
    )

    assertEquals(LeaderboardPort.Page("s2", emptyList(), null, 0, 3), loaded)
  }

  @Test
  fun aTiedCallerOutsideThePageKeepsTheirPosition() {
    val rows = listOf(
      row(position = 1, displayName = "A", avatarInitial = "A"),
      row(position = 2, displayName = "B", avatarInitial = "B"),
      row(position = 2, displayName = "C", avatarInitial = "C"),
    )
    // Three players share position 2; the first two were delivered, so the
    // caller is outside rows but still at position 2 — a valid page.
    val me = row(
      position = 2, displayName = "D", avatarInitial = "D", isCurrentPlayer = true,
    )

    val loaded = page(
      outcome(response(rows = rows, you = me, total = 10, skipped = 0)),
    )

    assertEquals(listOf(1, 2, 2), loaded.rows.map { it.position })
    assertEquals(
      LeaderboardPort.Row(2, "D", "D", "Gold", "معلم", "II", 700, "s2", true),
      loaded.you,
    )
  }

  @Test
  fun theDefaultLimitIsOneHundred() {
    assertEquals(100, LeaderboardPort.DEFAULT_LIMIT)
  }

  @Test
  fun anAbsentYouKeyMeansNoCallerRow() {
    val loaded = page(outcome(response() - "you"))
    assertNull(loaded.you)
  }

  @Test
  fun aRowElementMustBeAMap() {
    assertMalformed(outcome(response(rows = listOf(42), total = 1)))
    assertMalformed(outcome(response(rows = listOf<Any?>(null), total = 1)))
  }

  @Test
  fun numbersBeyondTheIntRangeAreRejectedNotClamped() {
    assertMalformed(outcome(response(total = 2_147_483_648.0)))
    assertMalformed(
      outcome(response(rows = listOf(row() + ("rp" to 2_147_483_648.0)), total = 1)),
    )
  }

  @Test
  fun aRowSeasonIsCaseSensitive() {
    assertMalformed(
      outcome(response(rows = listOf(row(seasonId = "S2")), total = 1)),
    )
  }
}
