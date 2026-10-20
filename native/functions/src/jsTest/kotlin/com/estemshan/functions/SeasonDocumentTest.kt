package com.estemshan.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S59/S64 — the season singleton's parser, in isolation.
 *
 * `seasons/current` is one document, and every current-season read — the
 * leaderboard's and the Ranked profile's — must answer identically from the
 * same bytes, so [currentSeasonIdOf] carries the whole contract and this class
 * pins it on plain Kotlin fixtures: no Firestore, no port, no dynamic.
 *
 * The three clauses of the contract, each its own test:
 * - an ABSENT document is the ONLY bootstrap (season one, and not an error);
 * - an existing document is authoritative — its non-blank `String`
 *   `seasonId` is served verbatim (case-sensitive, never normalized, never
 *   trimmed), and the reset bookkeeping (`status`, `targetSeasonId`,
 *   `nextSeasonId`, `startsAt`, `endsAt`) is never consulted;
 * - everything else fails closed with `failed-precondition` and a fixed
 *   message that never echoes the malformed value — a read-time error is not
 *   a place to broadcast a client-controllable datum into every log collector.
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
}
