plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

// Phase 1: same applicationId as the Capacitor shell (com.estemshan.game)
// so the Play listing, signing key, and Firebase Android app can carry
// over when the native build replaces the wrapper.
@Suppress("UnstableApiUsage")
android {
  namespace = "com.estemshan.game"
  compileSdk = 36
  // AGP 8.13's paired build tools; explicit so CI installs exactly these.
  buildToolsVersion = "35.0.0"

  defaultConfig {
    applicationId = "com.estemshan.game"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0-phase1"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    debug {
      // Emulator Suite (10.0.2.2 = host loopback from the emulator).
      // Release talks to the real project; no google-services.json is
      // committed — FirebaseOptions are built in code (FirebaseModule).
      buildConfigField("boolean", "USE_EMULATOR", "true")
    }
    release {
      buildConfigField("boolean", "USE_EMULATOR", "false")
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  composeOptions {
    // Legacy Compose compiler, paired with Kotlin 1.9.25 exactly. The
    // Kotlin-2.x plugin.compose migration is deliberately NOT taken here —
    // see docs/adr/0001-kotlin-vs-compose-compiler.md.
    kotlinCompilerExtensionVersion = "1.5.15"
  }
}

dependencies {
  // Firebase (BOM-managed). No google-services plugin: options are manual.
  implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
  implementation("com.google.firebase:firebase-auth-ktx")
  implementation("com.google.firebase:firebase-firestore-ktx")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

  val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
  implementation(composeBom)
  androidTestImplementation(composeBom)
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui-tooling-preview")

  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
  implementation("androidx.navigation:navigation-compose:2.7.7")

  // Pure-JVM game engine (Cards/Deck/Bidding/Table/Scoring). The app is a
  // thin UI shell: all rules live in :engine and are unit-tested there.
  implementation(project(":engine"))

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
