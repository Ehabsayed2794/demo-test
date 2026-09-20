// Phase 3 services module: RoomService + MatchService (Firestore
// transactions) + MatchAdapter (pure read-side sync interpreter).
//
// Layering per docs/specs/02-engine-api.md §6:
//   Engine → GameSession → MatchAdapter → MatchService → UI
// This module owns MatchAdapter + MatchService + RoomService and CONSUMES
// the engine (project(":engine")) and a GameSession seam (interfaces in
// session/, implemented elsewhere — see GameSessionPort). It is not yet a
// dependency of :app: the services are greenfield and nothing wires them
// into a screen yet, so an issue here cannot break the app build.
plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

@Suppress("UnstableApiUsage")
android {
  namespace = "com.estemshan.services"
  compileSdk = 34

  defaultConfig {
    minSdk = 26
    targetSdk = 34
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // The debug/release BuildConfig fields below need the feature on —
  // AGP 8.7 defaults it off for library modules. Same as :app.
  buildFeatures {
    buildConfig = true
  }

  buildTypes {
    debug {
      // 10.0.2.2 is the host loopback as seen from the Android emulator,
      // matching :app's FirebaseModule. Instrumented tests run against a
      // Firestore emulator the CI job starts on the host.
      buildConfigField("boolean", "USE_EMULATOR", "true")
    }
    release {
      buildConfigField("boolean", "USE_EMULATOR", "false")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  implementation(project(":engine"))

  // Firebase (BOM-managed, same versions as :app; no google-services
  // plugin — options are built in code).
  implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
  implementation("com.google.firebase:firebase-firestore-ktx")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

  androidTestImplementation("androidx.test:core:1.6.1")
  androidTestImplementation("androidx.test:runner:1.6.2")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
