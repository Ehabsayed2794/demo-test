package com.estemshan.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S56 — the deploy manifest's parity with the Kotlin exports.
 *
 * `native/functions/index.js` is what the Firebase CLI actually walks: it
 * republishes each callable as a PLAIN enumerable property, because
 * Kotlin/JS's own `@JsExport` defines a NON-enumerable getter that the CLI's
 * `extractTriggers.js` cannot see — a callable that exists in Kotlin but not
 * in that manifest is not deployed at all, and the client gets
 * `functions/not-found`. The manifest states the contract in its own header:
 * "every `@JsName("...")` value must appear here".
 *
 * So the parity is pinned in BOTH directions: an export the manifest does not
 * publish (a 404 waiting for the first real client) and a manifest entry no
 * export backs (a name the CLI would register as a missing function).
 *
 * The three S56 callables — `enqueueSearch`, `pollSearch`, `cancelSearch` —
 * are the ones this story adds, and they are covered by the same check as
 * every other export.
 */
class MatchmakingExportParityTest {

  @Test
  fun everyExportedCallableIsPublishedInTheDeployManifest() {
    val manifest = readText(manifestPath())
    val exported = exportedNames()
    val published = publishedNames(manifest)

    assertTrue(
      exported.isNotEmpty(),
      "no @JsExport names were found — the parity check would be vacuous",
    )

    val missing = exported.filter { it !in published }
    val extra = published.filter { it !in exported }

    assertEquals(
      emptyList(),
      missing,
      "the deploy manifest does not publish these exports — a client call would 404",
    )
    assertEquals(
      emptyList(),
      extra,
      "the deploy manifest publishes names no @JsExport backs",
    )
    for (name in exported) {
      assertTrue(
        manifest.contains("$name: kotlin.$name"),
        "the manifest must publish '$name' as '$name: kotlin.$name'",
      )
    }
  }

  // ── the sources ───────────────────────────────────────────────────────

  /** Every `@JsName("...")` the functions module exports. */
  private fun exportedNames(): List<String> {
    val pattern = Regex("""@JsName\("([^"]+)"\)""")
    return kotlinSources(sourceRoot())
      .flatMap { file -> pattern.findAll(readText(file)).map { it.groupValues[1] }.toList() }
      .distinct()
  }

  /** Every `name: kotlin.name` the manifest publishes. */
  private fun publishedNames(manifest: String): List<String> = Regex(
    """([A-Za-z0-9_]+):\s*kotlin\.([A-Za-z0-9_]+)""",
  ).findAll(manifest).map { it.groupValues[1] }.distinct().toList()

  // ── the filesystem, from wherever the runner happened to start ────────

  private fun manifestPath(): String =
    findUpwards(listOf("native/functions/index.js", "functions/index.js"))
      ?: throw AssertionError(
        "native/functions/index.js was not found from ${roots()} — " +
          "the export parity check cannot run",
      )

  private fun sourceRoot(): String =
    findUpwards(
      listOf("native/functions/src/jsMain/kotlin", "functions/src/jsMain/kotlin"),
    ) ?: throw AssertionError(
      "the functions' jsMain sources were not found from ${roots()} — " +
        "the export parity check cannot run",
    )

  private fun findUpwards(candidates: List<String>): String? {
    val fs: dynamic = js("require('fs')")
    val path: dynamic = js("require('path')")
    for (root in roots()) {
      var dir: String = root
      while (true) {
        for (candidate in candidates) {
          val full = path.join(dir, candidate) as String
          if (fs.existsSync(full) as Boolean) return full
        }
        val parent = path.dirname(dir) as String
        if (parent == dir) break
        dir = parent
      }
    }
    return null
  }

  /** The runner's working directory and the module's own directory. */
  private fun roots(): List<String> {
    val out = mutableListOf<String>()
    val cwd = js("process.cwd()") as String
    if (cwd.isNotEmpty()) out += cwd
    val moduleDir = js("(typeof __dirname !== 'undefined') ? __dirname : ''") as String
    if (moduleDir.isNotEmpty() && moduleDir !in out) out += moduleDir
    return out
  }

  private fun kotlinSources(root: String): List<String> {
    val fs: dynamic = js("require('fs')")
    val path: dynamic = js("require('path')")
    val out = mutableListOf<String>()
    fun walk(dir: String) {
      for (entry in fs.readdirSync(dir) as Array<*>) {
        val child = path.join(dir, entry as String) as String
        if (fs.statSync(child).isDirectory() as Boolean) {
          walk(child)
        } else if (child.endsWith(".kt")) {
          out += child
        }
      }
    }
    walk(root)
    return out.sorted()
  }

  private fun readText(path: String): String {
    val fs: dynamic = js("require('fs')")
    return fs.readFileSync(path, "utf8") as String
  }
}
