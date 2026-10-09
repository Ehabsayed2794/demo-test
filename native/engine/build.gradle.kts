// The rules engine, shared by two runtimes (docs/adr/0002-functions-runtime.md).
//
//   JVM target  -> :app + :services (unchanged consumers)
//   JS/Node     -> :functions, so settlement re-runs the ACTUAL engine rather
//                  than a re-implementation (RD12 correct-and-settle)
//
// The engine stays a library on both targets: :functions is the executable
// consumer, so this module does not set binaries.executable().
plugins {
  kotlin("multiplatform")
}

kotlin {
  jvm {
    jvmToolchain(17)
  }

  js {
    nodejs {
      // The Functions layer consumes this engine over the JS/IR compiler.
      // No binaries.executable(): this is a library target.
    }
  }

  sourceSets {
    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
      }
    }
    val jvmTest by getting {
      dependencies {
        // The existing JVM suite is JUnit 4 and stays JVM-only — porting it to
        // kotlin.test just to also run it under Node is out of S42's scope.
        // JS-target validation is the :functions smoke callable instead.
        implementation("junit:junit:4.13.2")
      }
    }
  }
}

// Configures jvmTest only (Test is the JVM task type; the JS test task is a
// different type). Same logging shape the module had as a pure-JVM project.
tasks.withType<Test>().configureEach {
  useJUnit()
  testLogging {
    events("passed", "failed", "skipped")
  }
}
