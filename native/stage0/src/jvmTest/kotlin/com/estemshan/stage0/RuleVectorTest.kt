package com.estemshan.stage0

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM half of Stage 0's E3.
 *
 * Two jobs, exactly as E1's driver had them:
 *
 *  1. proves the vector table itself is sane — every T-ID E3 covers is present,
 *     every row carries a value (never a blank), the document is reproducible
 *     for a fixed table, and the classifier agrees with itself on a pair of
 *     identical documents;
 *  2. writes the JVM's rendered table to `build/stage0-parity/`, which is what
 *     the workerd run is compared against with `cmp` and a sha256.
 *
 * The comparison itself is NOT made from inside this test: workerd is a
 * separate process, and its answer has to come from a real Worker invocation.
 */
class RuleVectorTest {

  /** The T-IDs E3 covers, per the plan's E3 row: T-01…T-16, T-22 and T-24. */
  private val scope = (
    (1..16).map { "T-%02d".format(it) } + listOf("T-22", "T-24")
  )

  @Test
  fun everyVectorInScopeIsPresent() {
    val ids = ruleVectorCases().map { it.id }.distinct().sorted()

    assertEquals(scope, ids, "the vector table must cover exactly the T-IDs E3 scopes")
  }

  @Test
  fun noRowIsBlank() {
    for (case in ruleVectorCases()) {
      assertTrue(case.value.isNotBlank(), "${case.id}/${case.caseId} has a blank value")
      assertTrue(case.expected.isNotBlank(), "${case.id}/${case.caseId} has a blank expectation")
      assertTrue(case.note.isNotBlank(), "${case.id}/${case.caseId} has a blank note")
    }
  }

  @Test
  fun theCaseIdsAreUnique() {
    val cases = ruleVectorCases()
    val keys = cases.map { it.id + "/" + it.caseId }
    assertEquals(keys.size, keys.toSet().size, "two rows share an id")
  }

  @Test
  fun theDocumentIsReproducible() {
    assertEquals(
      runRuleVectorsText(),
      runRuleVectorsText(),
      "the same table must render the same document twice",
    )
  }

  @Test
  fun theDocumentRoundTripsThroughItsParser() {
    val cases = ruleVectorCases()
    val parsed = parseRuleVectors(runRuleVectorsText())

    assertEquals(cases.size, parsed.size, "the parser must recover every row")
    assertEquals(cases.map { it.id }, parsed.map { it.id })
    assertEquals(cases.map { it.caseId }, parsed.map { it.caseId })
    assertEquals(cases.map { it.value }, parsed.map { it.value })
    assertEquals(cases.map { it.expected }, parsed.map { it.expected })
  }

  @Test
  fun theClassifierCallsTwoIdenticalDocumentsIdentical() {
    val text = runRuleVectorsText()
    val verdicts = classifyRuleVectors(text, text)

    assertEquals(ruleVectorCases().size, verdicts.size)
    // A row the engine has no behaviour for is NOT_IMPLEMENTED on both sides —
    // still agreement, but it is not "the engine said the same thing".
    assertTrue(
      verdicts.none { it.outcome == ParityOutcome.DIFFERENT },
      "two identical documents must produce no DIFFERENT verdict",
    )
    assertTrue(
      verdicts.all { it.outcome == ParityOutcome.IDENTICAL || it.outcome == ParityOutcome.NOT_IMPLEMENTED },
      "a verdict is either IDENTICAL or NOT_IMPLEMENTED, never anything else",
    )
    assertEquals(
      ruleVectorCases().count { it.status == RuleVectorStatus.NOT_IMPLEMENTED },
      verdicts.count { it.outcome == ParityOutcome.NOT_IMPLEMENTED },
      "every unimplemented row must be reported as NOT_IMPLEMENTED",
    )
  }

  @Test
  fun theClassifierNamesAMissingCaseExactly() {
    val text = runRuleVectorsText()
    val trimmed = text.lineSequence().filterNot { it.startsWith("T-16|t16.s9.") }
      .joinToString("\n")

    val verdicts = classifyRuleVectors(text, trimmed)
    val dropped = verdicts.single { it.caseId == "t16.s9.the-decision-point-clock" }

    assertEquals(ParityOutcome.DIFFERENT, dropped.outcome)
    assertTrue(
      dropped.diff.contains("missing from the workerd run"),
      "the diff must say WHICH side lost the case, not just that they differ",
    )
  }

  @Test
  fun theJvmTableIsWrittenForTheWorkerdComparison() {
    val outDir = File("build/stage0-parity").apply { mkdirs() }
    val text = runRuleVectorsText()

    File(outDir, "jvm-rule-vectors.txt").writeText(text)
    assertTrue(text.startsWith(RULE_VECTOR_FORMAT), "the document must carry its format marker")
    assertTrue(text.contains("#cases="), "the document must declare its case count")
  }
}
