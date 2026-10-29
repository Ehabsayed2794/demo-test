// Stage 0 (E1) — the workerd harness.
//
// A probe, not a product module: it plays one complete bot-vs-bot match through
// the REAL :engine on whichever runtime compiled it, and returns a canonical
// JSON fingerprint of that match. The JVM target and the JS target share the
// driver in commonMain, so two byte-identical fingerprints — one from the JVM,
// one from inside workerd — are the evidence E1 asks for
// (docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md, Stage 0).
//
// Node.js rather than the browser: the JS target then reuses the exact
// toolchain :functions already pins in kotlin-js-store/yarn.lock (no webpack),
// and the compiler's plain UMD output — the same shape index.js consumes — is
// what wrangler's bundler turns into a Worker.
plugins {
  kotlin("multiplatform")
}

kotlin {
  jvm {
    jvmToolchain(17)
  }

  js {
    moduleName = "stage0"
    nodejs {
      binaries.executable()
    }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":engine"))
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
      }
    }
    val jvmTest by getting {
      dependencies {
        // kotlin("test") resolves to the JUnit 4 runner once junit is present,
        // matching :engine's jvmTest.
        implementation("junit:junit:4.13.2")
      }
    }
  }
}

tasks.withType<Test>().configureEach {
  useJUnit()
  testLogging {
    events("passed", "failed", "skipped")
  }
}
