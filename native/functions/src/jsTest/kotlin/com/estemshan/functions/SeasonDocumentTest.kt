package com.estemshan.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S59/S64 — the season singleton's parsers, in isolation.
 *
 * `seasons/current` is one document with two readers that need different
 * answers from the same bytes:
 *
 * - [currentSeasonIdOf] is the BOARD's answer: `seasonId`, verbatim. During a
 *   reset (`status` RUNNING) the board stays on the outgoing season until the
 *   job commits the new one;
 * - [lazyResetSeasonOf] is the LAZY RESET's answer: the season a stale profile
 *   must be moved into. During a reset that is the in-flight TARGET, because
 *   the sweep stamps profiles with it before `seasonId` flips — serving the
 *   outgoing season there would reset moved players BACKWARDS (R27).
 *
 * The board contract's three clauses, each its own test:
 * - an ABSENT document is the ONLY bootstrap (season one, and not an error);
 * - an existing document is authoritative — its non-blank `String`
 *   `seasonId` is served verbatim (case-sensitive, never normalized, never
 *   trimmed), and the reset bookkeeping (`status`, `targetSeasonId`,
 *   `nextSeasonId`, `startsAt`, `endsAt`) is never consulted;
 * - everything else fails closed with `failed-precondition` and a fixed
 *   message that never echoes the malformed value — a read-time error is not
 *   a place to broadcast a client-controllable datum into every log collector.
 *
 * The lazy-reset contract adds one clause: an existing document must carry a
 * known [SeasonStatus] name, `RUNNING` must name a usable target, and
 * everything else fails closed the same fixed-literal way.
 */
class SeasonDocumentTest {

  private fun thrownCode(block: () -> Any?): String {
    try {
      block()
    } catch (e: Throwable) {
      return e.asDynamic().code as String
    }
    throw AssertionError("expected HttpsError")
  }

  private fun thrownMessage(block: () -> Any?): String {
    try {
      block()
    } catch (e: Throwable) {
      return e.asDynamic().message as String
    }
    throw AssertionError("expected HttpsError")
  }

  // ── the bootstrap ───────────────────────────────────────────────────

  @Test
  fun anAbsentDocumentIsTheBootstrapSeason() {
    assertEquals("s1", currentSeasonIdOf(exists = false, data = null))
    // `exists` decides on its own: a missing document serves season one even
    // if some data shape rides along — there is no second kind of absence.
    assertEquals("s1", currentSeasonIdOf(exists = false, data = linkedMapOf<String, Any?>("seasonId" to "s9")))
  }

  // ── an existing document is authoritative, served verbatim ──────────

  @Test
  fun aDocumentServesItsSeasonIdVerbatimAndCaseSensitive() {
    assertEquals("s2", currentSeasonIdOf(exists = true, data = linkedMapOf<String, Any?>("seasonId" to "s2")))
    // Case-sensitive: "S1" is a different name for a different board
    // (RD24), and a normalized answer would silently blend them.
    assertEquals("S1", currentSeasonIdOf(exists = true, data = linkedMapOf<String, Any?>("seasonId" to "S1")))
    // No trimming: whatever the document names is exactly what is served.
    assertEquals("  s1", currentSeasonIdOf(exists = true, data = linkedMapOf<String, Any?>("seasonId" to "  s1")))
  }

  @Test
  fun theResetBookkeepingIsNeverConsulted() {
    // During a reset (status RUNNING) the outgoing season stays the served
    // one until the season job commits the new one as `seasonId` — serving
    // targetSeasonId here would flip the live board mid-reset, onto a season
    // the profiles have not been moved into yet.
    val duringReset = linkedMapOf<String, Any?>(
      "seasonId" to "s1",
      "status" to "RUNNING",
      "targetSeasonId" to "s2",
      "nextSeasonId" to "s2",
      "startsAt" to 1_700_000_000_000L,
      "endsAt" to 1_800_000_000_000L,
      "updatedAt" to 1_750_000_000_000L,
    )
    assertEquals("s1", currentSeasonIdOf(exists = true, data = duringReset))
  }

  // ── everything else fails closed ────────────────────────────────────

  @Test
  fun anExistingDocumentWithoutAUsableSeasonIdFailsClosed() {
    for (broken in listOf<Any?>(
      // The field is missing entirely, blank, whitespace, or not a string.
      linkedMapOf<String, Any?>(),
      linkedMapOf<String, Any?>("seasonId" to ""),
      linkedMapOf<String, Any?>("seasonId" to "   "),
      linkedMapOf<String, Any?>("seasonId" to 7),
      linkedMapOf<String, Any?>("seasonId" to true),
      // A document that carries nothing usable under the right key.
      linkedMapOf<String, Any?>("other" to "s2"),
      // Data that is not a document's fields at all.
      null,
      "s2",
      42,
      listOf<Any?>("seasonId" to "s2"),
    )) {
      assertEquals("failed-precondition", thrownCode { currentSeasonIdOf(exists = true, data = broken) })
    }
  }

  @Test
  fun theFailureMessageNeverEchoesTheMalformedValue() {
    // A string the fixer would have wanted to see — and must never see: the
    // message is a fixed literal, not a diagnosis that prints the input. The
    // malformed shape here is data that is not a document's fields at all,
    // with the season-looking value inside it.
    val stringDocument = thrownMessage { currentSeasonIdOf(exists = true, data = "the-most-readable-season") }
    assertTrue(stringDocument.isNotBlank(), "the failure says something, just not the value")
    assertTrue(
      "the-most-readable-season" !in stringDocument,
      "the malformed document was echoed back into the error message",
    )

    // And the same for a non-string field value and for data that is not a map.
    assertTrue(
      "7" !in thrownMessage {
        currentSeasonIdOf(exists = true, data = linkedMapOf<String, Any?>("seasonId" to 7))
      },
    )
    assertTrue(
      "the-most-readable-value" !in thrownMessage { currentSeasonIdOf(exists = true, data = "the-most-readable-value") },
    )
  }

  // ── the lazy-reset season: the in-flight target, never the board's ──

  @Test
  fun anAbsentDocumentIsTheBootstrapForLazyResetsToo() {
    assertEquals(null, lazyResetSeasonOf(exists = false, data = null))
    // `exists` decides on its own, exactly as the board's parser does.
    assertEquals(
      null,
      lazyResetSeasonOf(exists = false, data = linkedMapOf<String, Any?>("seasonId" to "s9", "status" to "ACTIVE")),
    )
  }

  @Test
  fun aSettledDocumentServesItsSeasonIdToLazyResetsVerbatim() {
    assertEquals(
      "s2",
      lazyResetSeasonOf(
        exists = true,
        data = linkedMapOf<String, Any?>("seasonId" to "s2", "status" to "ACTIVE"),
      ),
    )
    // COMPLETE is terminal and `seasonId` already names the landed season.
    assertEquals(
      "s3",
      lazyResetSeasonOf(
        exists = true,
        data = linkedMapOf<String, Any?>("seasonId" to "s3", "status" to "COMPLETE"),
      ),
    )
    // Case-sensitive and untrimmed here too: the id is the identity (RD24).
    assertEquals(
      "  S1 ",
      lazyResetSeasonOf(
        exists = true,
        data = linkedMapOf<String, Any?>("seasonId" to "  S1 ", "status" to "ACTIVE"),
      ),
    )
  }

  @Test
  fun aRunningResetServesTheTargetSeasonToLazyResets() {
    // The sweep stamps profiles with the target BEFORE the job flips
    // `seasonId`; judging those profiles against the outgoing season would
    // reset them BACKWARDS (R27). The lazy paths therefore serve the target —
    // while the board keeps serving the outgoing season, unchanged.
    val running = linkedMapOf<String, Any?>(
      "seasonId" to "s2",
      "status" to "RUNNING",
      "targetSeasonId" to "s3",
      "nextSeasonId" to "s3",
    )
    assertEquals("s3", lazyResetSeasonOf(exists = true, data = running))
    assertEquals("s2", currentSeasonIdOf(exists = true, data = running))
  }

  @Test
  fun aRunningResetWithNoUsableTargetFailsClosed() {
    for (broken in listOf<Any?>(
      // Absent, blank, non-string: a RUNNING claim always names its target,
      // so anything else is an unreadable transition, not a season to guess.
      null,
      "",
      "   ",
      7,
    )) {
      val running = linkedMapOf<String, Any?>(
        "seasonId" to "s2",
        "status" to "RUNNING",
        "targetSeasonId" to broken,
      )
      assertEquals("failed-precondition", thrownCode { lazyResetSeasonOf(exists = true, data = running) })
    }
    // Fixed literal, and it never echoes the malformed value.
    val message = thrownMessage {
      lazyResetSeasonOf(
        exists = true,
        data = linkedMapOf<String, Any?>("seasonId" to "s2", "status" to "RUNNING"),
      )
    }
    assertEquals("seasons/current is RUNNING with no usable targetSeasonId.", message)
    assertTrue("7" !in message)
  }

  @Test
  fun aDocumentWithNoKnownStatusFailsClosedForLazyResets() {
    for (broken in listOf<Any?>(
      // No status at all: the bulk job reads the same document as
      // NOT_CONFIGURED rather than guessing which season is authoritative.
      linkedMapOf<String, Any?>("seasonId" to "s2"),
      linkedMapOf<String, Any?>("seasonId" to "s2", "status" to ""),
      linkedMapOf<String, Any?>("seasonId" to "s2", "status" to "PAUSED"),
      linkedMapOf<String, Any?>("seasonId" to "s2", "status" to 3),
    )) {
      assertEquals("failed-precondition", thrownCode { lazyResetSeasonOf(exists = true, data = broken) })
    }
    assertEquals(
      "seasons/current has no known status.",
      thrownMessage {
        lazyResetSeasonOf(exists = true, data = linkedMapOf<String, Any?>("seasonId" to "s2"))
      },
    )
  }

  @Test
  fun anExistingDocumentWithoutAUsableSeasonIdFailsClosedForLazyResetsToo() {
    for (broken in listOf<Any?>(
      linkedMapOf<String, Any?>("status" to "ACTIVE"),
      linkedMapOf<String, Any?>("seasonId" to "", "status" to "ACTIVE"),
      linkedMapOf<String, Any?>("seasonId" to "   ", "status" to "ACTIVE"),
      linkedMapOf<String, Any?>("seasonId" to 7, "status" to "ACTIVE"),
      linkedMapOf<String, Any?>("other" to "s2", "status" to "ACTIVE"),
      null,
      "s2",
      listOf<Any?>("seasonId" to "s2", "status" to "ACTIVE"),
    )) {
      assertEquals("failed-precondition", thrownCode { lazyResetSeasonOf(exists = true, data = broken) })
    }
    // One document, one diagnostic: the same literal the board's parser uses.
    assertEquals(
      "seasons/current has no usable seasonId.",
      thrownMessage {
        lazyResetSeasonOf(exists = true, data = linkedMapOf<String, Any?>("status" to "ACTIVE"))
      },
    )
  }
}
