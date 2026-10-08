// The Ranked authority layer (S42, docs/adr/0002-functions-runtime.md).
//
// Kotlin/JS on the Node.js runtime. Depends on :engine so settlement re-runs
// the ACTUAL rules engine instead of a re-implementation (RD12). Invoked once
// per match, never always-on (RD11); deliberately lightweight (RD16).
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension

plugins {
  kotlin("multiplatform")
}

kotlin {
  js {
    moduleName = "functions"
    nodejs {
      // The Firebase CLI loads this module's exports (the @JsExport callables
      // below) — so this is the executable consumer of :engine.
      binaries.executable()
    }
  }

  sourceSets {
    val jsMain by getting {
      dependencies {
        implementation(project(":engine"))
        // 2nd-gen callables + the Admin SDK. Admin bypasses firestore.rules,
        // which stays byte-identical — the Functions are the only legitimate
        // Ranked write path (plan §"What is NOT included").
        // Pinned to the 6.x line, not 7.x: the pinned firebase-tools (13.35.1)
        // emulator unconditionally calls functions.config() while setting up
        // its config proxy (functionsEmulatorRuntime.js), and 7.x made that
        // call THROW — the worker dies with "Failed to load function" and
        // every callable returns deadline-exceeded. 6.6.0 still ships the
        // deprecated-but-working config(). Bump both together once a
        // firebase-tools that no longer calls config() is pinned.
        implementation(npm("firebase-functions", "6.6.0"))
        // firebase-admin, pinned to the same 13.10.0 that the peer range on
        // firebase-functions 6.6.0 admits. Not here for S42's own code — the
        // scaffold never touches Firestore — but because firebase-functions
        // hard-requires "firebase-admin/app-check" at the TOP of
        // lib/common/providers/https.js, so merely loading a callable pulls it
        // in. Kotlin/JS installs npm deps with yarn classic, which unlike npm
        // never auto-installs peer dependencies (it only warns), so leaving
        // admin out made :functions:jsNodeTest die with
        // "Cannot find module 'firebase-admin/app-check'" — locally it kept
        // passing only because a stale UP-TO-DATE cache hid it.
        // S50's settlement will use this same dependency for the real
        // rules-bypassing writes; bump both pins together (see above).
        implementation(npm("firebase-admin", "13.10.0"))
        // The idempotency guard's once-only semantics are suspend-based;
        // Kotlin/JS maps suspend to Promises, which onCall handles natively.
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
      }
    }
    val jsTest by getting {
      dependencies {
        implementation(kotlin("test"))
        // runTest: runBlocking does not exist on Kotlin/JS, so the suspend
        // tests use kotlinx-coroutines-test's multiplatform runner instead.
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
      }
    }
  }
}

// The Node version Kotlin/JS pins for the JS target.
//
// Its default (22.0.0) is too old for firebase-admin's transitive tree —
// jwks-rsa requires ^22.12.0 and yarn's engine check aborts :kotlinNpmInstall
// outright. firebase-admin is how the Ranked write path bypasses the frozen
// firestore.rules (RD14), so it is not optional. 22.14.0 is the newest 22.x
// that satisfies it, and matches the engines.node pin in package.json.
//
// This is a root-level setting (NodeJsRootExtension is the shared spec for
// every JS target) but lives here because the extension only exists once a JS
// target has been configured — :engine is configured first, so by the time
// this script runs it is already present.
rootProject.extensions.configure<NodeJsRootExtension> {
  nodeVersion = "22.14.0"
}
