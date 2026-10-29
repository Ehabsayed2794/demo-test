package com.estemshan.stage0

import java.io.File
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E3's live cross-runtime check.
 *
 * [RuleVectorTest] proves the vector table itself is well-formed and
 * reproducible on the JVM. This class answers the question E3 actually asks:
 * does the SAME table come back out of workerd? It compares the JVM's
 * rendering against the document the Worker returned and turns the two into
 * the per-vector verdicts (IDENTICAL / DIFFERENT / NOT IMPLEMENTED) the
 * E3 report quotes.
 *
 * The workerd document is an external input: the E3 recipe builds the JS
 * artifact, runs it under `wrangler dev --local`, and writes what the Worker
 * returned to a file. When that file is absent the test SKIPS (it does not
 * pass vacuously) — the run that produced the report had it present.
 * Point the test at a different file with STAGE0_WORKERD_VECTORS.
 */
class RuleVectorParityTest {

  private val workerdFile: File = File(
    System.getenv("STAGE0_WORKERD_VECTORS") ?: "D:/cache/stage0/e3/workerd-rule-vectors.txt",
  )

  private fun workerdText(): String {
    assumeTrue(
      "no workerd vector document at ${workerdFile.path} — run the E3 recipe first",
      workerdFile.isFile,
    )
    return workerdFile.readText()
  }

  @Test
  fun theWorkerdDocumentIsByteIdenticalToTheJvmOne() {
    val jvm = runRuleVectorsText()
    val workerd = workerdText()

    assertEquals(
      sha256(jvm),
      sha256(workerd),
      "the JVM and workerd vector documents differ",
    )
    assertEquals(jvm, workerd, "the JVM and workerd vector documents differ")
  }

  @Test
  fun everyVectorGetsAVerdictAndNoneDisagree() {
    val workerd = workerdText()
    val verdicts = classifyRuleVectors(runRuleVectorsText(), workerd)

    assertTrue(verdicts.isNotEmpty(), "the classifier returned no verdicts")
    assertEquals(
      parseRuleVectors(runRuleVectorsText()).size,
      verdicts.size,
      "the classifier must reach a verdict for every case of the document",
    )
    assertEquals(
      parseRuleVectors(workerd).size,
      verdicts.size,
      "both documents must carry the same number of cases",
    )

    val different = verdicts.filter { it.outcome == ParityOutcome.DIFFERENT }
    assertTrue(
      different.isEmpty(),
      "vectors that disagree across the two runtimes: " +
        different.joinToString("; ") { "${it.id}/${it.caseId}: ${it.diff}" },
    )

    val notImplemented = verdicts.count { it.outcome == ParityOutcome.NOT_IMPLEMENTED }
    val identical = verdicts.count { it.outcome == ParityOutcome.IDENTICAL }
    assertEquals(
      verdicts.size,
      identical + notImplemented,
      "every verdict is either IDENTICAL or NOT_IMPLEMENTED",
    )

    // The report quotes these numbers; the table itself is written beside the
    // JVM document so both artifacts come from one run.
    val outDir = File("build/stage0-parity").apply { mkdirs() }
    File(outDir, "rule-vector-verdicts.md").writeText(renderVerdictTable(verdicts))
    File(outDir, "rule-vector-verdicts.txt").writeText(
      buildString {
        append("#identical=").append(identical).append('\n')
        append("#not-implemented=").append(notImplemented).append('\n')
        append("#different=").append(different.size).append('\n')
        append("#jvm-sha256=").append(sha256(runRuleVectorsText())).append('\n')
        append("#workerd-sha256=").append(sha256(workerd)).append('\n')
        for (v in verdicts) {
          append(v.id).append('|').append(v.caseId).append('|').append(v.outcome.name)
            .append('|').append(v.planVerdict).append('|')
            .append(v.jvm).append('|').append(v.workerd).append('\n')
        }
      },
    )
  }

  private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(text.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
}
