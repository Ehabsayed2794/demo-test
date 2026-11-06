// The authority core: the pure, storage-agnostic heart of the Ranked
// authority layer (E3.1, ADR 0003 option B / O-06).
//
// This module compiles into two runtimes with zero `firebase-*` imports:
//   - the Firebase Functions bundle (:functions depends on this module,
//     adds the Admin-SDK adapter and the @JsExport callables);
//   - the Cloudflare Worker bundle (native/worker imports this module's
//     build output), once the O-06 Durable Object / D1 storage lands.
//
// Everything here is pure Kotlin over plain parsed maps plus typed ports
// (SettlementDb / TimeoutDb). The bots, the bidding/table engines and the
// scoring all live in :engine and are re-run here — never re-implemented —
// so the Worker can never silently drift from the shipped rules.
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension

plugins {
  kotlin("multiplatform")
}

kotlin {
  js {
    moduleName = "authority-core"
    nodejs {
      // The Worker imports this module's build output directly.
      binaries.executable()
    }
  }

  sourceSets {
    val jsMain by getting {
      dependencies {
        implementation(project(":engine"))
        // The promise-based Promise bridging used by the Worker exports.
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
      }
    }
    val jsTest by getting {
      dependencies {
        implementation(kotlin("test"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
      }
    }
  }
}

// The repo root's package.json declares "type": "module", which would make
// every .js under this module's build output parse as ESM — but the KMP
// bundle is a UMD (CommonJS) module. Drop the KMP-generated package.json
// (no "type" field) into the executable output so Node/bundlers treat the
// bundle and its siblings as CommonJS, exactly how the Firebase side loads
// its bundle via require().
val jsExecutablePackageJson = tasks.register<Copy>("jsExecutablePackageJson") {
  dependsOn("jsPublicPackageJson")
  from(layout.buildDirectory.file("tmp/jsPublicPackageJson/package.json"))
  into(layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin"))
}

tasks.named("jsProductionExecutableCompileSync") {
  finalizedBy(jsExecutablePackageJson)
}

// Same root-level pin the :functions module sets — the extension only
// exists once a JS target has been configured (engine is configured
// first; this module is included after :functions). 22.14.0 is the
// newest 22.x line the JS tooling accepts, matching the repo's Node 22
// policy. Setting the same value again is idempotent.
rootProject.extensions.configure<NodeJsRootExtension> {
  nodeVersion = "22.14.0"
}
