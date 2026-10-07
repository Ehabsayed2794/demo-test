// The Ranked authority layer (S42, docs/adr/0002-functions-runtime.md).
//
// Kotlin/JS on the Node.js runtime. Depends on :engine so settlement re-runs
// the ACTUAL rules engine instead of a re-implementation (RD12). Invoked once
// per match, never always-on (RD11); deliberately lightweight (RD16).
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
        implementation(npm("firebase-functions", "7.4.0"))
        implementation(npm("firebase-admin", "14.5.0"))
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
