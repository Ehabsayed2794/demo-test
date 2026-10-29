package com.estemshan.stage0

/**
 * E3 — the table-driven rule/scoring vector runner.
 *
 * E2 proved that two whole matches agree across runtimes; E3 asks the
 * sharper question: does every RULE VECTOR of the plan's §5.3.3 produce the
 * same answer on the JVM and inside workerd? A corpus of matches can agree
 * while a single rule disagrees, because a match only exercises the rules its
 * cards happen to reach — so the vectors are named, hand-built states with a
 * known expected answer, and the runner renders ONE LINE per vector.
 *
 * The document format is line-oriented text rather than JSON on purpose: the
 * comparison between two runtimes has to be byte-for-byte (`cmp` plus a
 * sha256 over the file), and the verdict has to be readable by a human
 * without a JSON parser on either side. Every field is escaped, so the
 * separator never appears inside a value.
 *
 * Each case renders exactly one of two statuses:
 * - [RuleVectorStatus.MEASURED] — the engine answered; the answer is in
 *   [RuleVectorCase.value].
 * - [RuleVectorStatus.NOT_IMPLEMENTED] — the engine has no such behaviour.
 *   The note then names the missing behaviour precisely (never a blank, never
 *   a silent drop).
 *
 * The PARITY verdict of a case is one of exactly three:
 * - IDENTICAL — both runtimes rendered the same status and the same value.
 * - DIFFERENT — they did not; the diff says exactly how.
 * - NOT_IMPLEMENTED — both runtimes report the same missing behaviour.
 *
 * The plan's own expectation travels beside the measurement in
 * [RuleVectorCase.expected] and is compared SEPARATELY (a plan deviation is
 * not a runtime divergence) — "-" means the plan's row is not expressible as
 * a single token, and the note carries the plan's wording.
 */
enum class RuleVectorStatus { MEASURED, NOT_IMPLEMENTED }

/** One vector case, as the runner rendered it on one runtime. */
data class RuleVectorCase(
  /** The plan's vector id: T-01 … T-24. */
  val id: String,
  /** The row inside that vector: t01.s1, t01.s2 … */
  val caseId: String,
  val status: RuleVectorStatus,
  /** The decisive engine answer — the token the parity verdict compares. */
  val value: String,
  /** The plan's literal expectation for the same token, or "-" when the plan
   *  states the row in prose that has no single-token form. */
  val expected: String,
  /** What the row is, in prose: the setup, the arithmetic, the caveats. */
  val note: String,
)

/** The parity verdict of one case across the two runtimes. */
enum class ParityOutcome { IDENTICAL, DIFFERENT, NOT_IMPLEMENTED }

data class RuleVectorVerdict(
  val id: String,
  val caseId: String,
  val outcome: ParityOutcome,
  /** The JVM's rendering (status + value), or "" when the case is missing. */
  val jvm: String,
  /** The workerd's rendering, or "" when the case is missing. */
  val workerd: String,
  /** The exact difference, for a DIFFERENT verdict. */
  val diff: String,
  val expected: String,
  /** MATCHES / DEVIATES / N-A — the plan axis, reported beside the parity axis. */
  val planVerdict: String,
)

/** The document header — the format marker every artifact carries. */
const val RULE_VECTOR_FORMAT: String = "#stage0-rule-vectors-v1"

/** Renders the whole vector table: header, then one escaped line per case. */
fun renderRuleVectors(cases: List<RuleVectorCase>): String {
  val rows = cases.map { row(it) }
  val sb = StringBuilder()
  sb.append(RULE_VECTOR_FORMAT).append('\n')
  sb.append("#cases=").append(rows.size).append('\n')
  sb.append("#fingerprint=").append(fnv1a32(rows.joinToString("\n"))).append('\n')
  sb.append(rows.joinToString("\n"))
  sb.append('\n')
  return sb.toString()
}

/**
 * The whole vector table, rendered — the ONE entry point both runtimes call.
 * The JVM driver writes it to `build/stage0-parity/`; the Worker returns it
 * verbatim, so the two documents differ only if the engines differ.
 */
fun runRuleVectorsText(): String = renderRuleVectors(ruleVectorCases())

/**
 * Compares two rendered vector tables.
 *
 * The verdict is decided on the STATUS and the VALUE only — the note is
 * prose, and a difference in prose between two runtimes of the same code
 * would be a difference in the note, not in the rule. A NOT_IMPLEMENTED case
 * whose two sides describe DIFFERENT missing behaviours is a DIFFERENT
 * verdict: the two runtimes disagree about what exists.
 */
fun classifyRuleVectors(jvmText: String, workerdText: String): List<RuleVectorVerdict> {
  val jvm = parseRuleVectors(jvmText).associateBy { it.id + "/" + it.caseId }
  val workerd = parseRuleVectors(workerdText).associateBy { it.id + "/" + it.caseId }
  val keys = (jvm.keys + workerd.keys).sorted()
  return keys.map { key ->
    val a = jvm[key]
    val b = workerd[key]
    when {
      a == null -> RuleVectorVerdict(
        id = b!!.id, caseId = b.caseId, outcome = ParityOutcome.DIFFERENT,
        jvm = "", workerd = render(b), diff = "the case is missing from the JVM run",
        expected = b.expected, planVerdict = planVerdict(b),
      )
      b == null -> RuleVectorVerdict(
        id = a.id, caseId = a.caseId, outcome = ParityOutcome.DIFFERENT,
        jvm = render(a), workerd = "", diff = "the case is missing from the workerd run",
        expected = a.expected, planVerdict = planVerdict(a),
      )
      a.status == RuleVectorStatus.NOT_IMPLEMENTED &&
        b.status == RuleVectorStatus.NOT_IMPLEMENTED &&
        a.note == b.note -> RuleVectorVerdict(
        id = a.id, caseId = a.caseId, outcome = ParityOutcome.NOT_IMPLEMENTED,
        jvm = render(a), workerd = render(b), diff = a.note,
        expected = a.expected, planVerdict = planVerdict(a),
      )
      a.status == b.status && a.value == b.value -> RuleVectorVerdict(
        id = a.id, caseId = a.caseId, outcome = ParityOutcome.IDENTICAL,
        jvm = render(a), workerd = render(b), diff = "",
        expected = a.expected, planVerdict = planVerdict(a),
      )
      else -> RuleVectorVerdict(
        id = a.id, caseId = a.caseId, outcome = ParityOutcome.DIFFERENT,
        jvm = render(a), workerd = render(b),
        diff = "jvm=${render(a)} workerd=${render(b)}",
        expected = a.expected, planVerdict = planVerdict(a),
      )
    }
  }
}

/** The markdown results table the E3 report quotes. */
fun renderVerdictTable(verdicts: List<RuleVectorVerdict>): String {
  val sb = StringBuilder()
  sb.append("| Vector | Case | Parity | JVM | workerd | Plan |\n")
  sb.append("|---|---|---|---|---|---|\n")
  for (v in verdicts) {
    sb.append("| ").append(v.id).append(" | ").append(v.caseId).append(" | ")
      .append(v.outcome.name).append(" | ")
      .append(cell(v.jvm)).append(" | ").append(cell(v.workerd)).append(" | ")
      .append(v.planVerdict).append(" |\n")
  }
  return sb.toString()
}

/** The one-line summary of a case as the verdict compares it. */
private fun render(c: RuleVectorCase): String =
  if (c.status == RuleVectorStatus.NOT_IMPLEMENTED) "NOT_IMPLEMENTED" else c.value

/** The plan axis: does the engine's answer equal the plan's own token? */
private fun planVerdict(c: RuleVectorCase): String = when {
  c.status == RuleVectorStatus.NOT_IMPLEMENTED -> "N-A"
  c.expected == "-" -> "N-A"
  c.value == c.expected -> "MATCHES"
  else -> "DEVIATES"
}

private fun cell(s: String): String = if (s.isEmpty()) "—" else s.replace("|", "\\p")

/** One escaped row: id|caseId|status|value|expected|note */
private fun row(c: RuleVectorCase): String = listOf(
  c.id, c.caseId, c.status.name, c.value, c.expected, c.note,
).joinToString("|") { escape(it) }

/** Parses a rendered table back into its cases. */
fun parseRuleVectors(text: String): List<RuleVectorCase> {
  val out = ArrayList<RuleVectorCase>()
  for (line in text.split("\n")) {
    if (line.isEmpty() || line.startsWith("#")) continue
    val parts = line.split("|")
    if (parts.size < 6) continue
    out += RuleVectorCase(
      id = unescape(parts[0]),
      caseId = unescape(parts[1]),
      status = RuleVectorStatus.valueOf(unescape(parts[2])),
      value = unescape(parts[3]),
      expected = unescape(parts[4]),
      note = unescape(parts.subList(5, parts.size).joinToString("|")),
    )
  }
  return out
}

/** Escapes the separator and the line breaks so a value can never split a row. */
private fun escape(s: String): String {
  val sb = StringBuilder(s.length)
  for (c in s) {
    when (c) {
      '\\' -> sb.append("\\\\")
      '|' -> sb.append("\\p")
      '\n' -> sb.append("\\n")
      '\r' -> sb.append("\\r")
      else -> sb.append(c)
    }
  }
  return sb.toString()
}

private fun unescape(s: String): String {
  val sb = StringBuilder(s.length)
  var i = 0
  while (i < s.length) {
    val c = s[i]
    if (c == '\\' && i + 1 < s.length) {
      when (s[i + 1]) {
        '\\' -> { sb.append('\\'); i += 2 }
        'p' -> { sb.append('|'); i += 2 }
        'n' -> { sb.append('\n'); i += 2 }
        'r' -> { sb.append('\r'); i += 2 }
        else -> { sb.append(c); i += 1 }
      }
    } else {
      sb.append(c)
      i += 1
    }
  }
  return sb.toString()
}

/**
 * FNV-1a, 32-bit, over the UTF-16 code units of [s] — the same function the
 * E2 harness uses, so the two artifacts can be read with one eye. Integer
 * overflow wraps identically on both targets, which is the whole point: the
 * fingerprint is a pure function of the text.
 */
private fun fnv1a32(s: String): String {
  var hash = -0x7ee3623b // 0x811c9dc5
  for (c in s) {
    hash = hash xor c.code
    hash *= 0x01000193
  }
  return hash.toUInt().toString(16).padStart(8, '0')
}
