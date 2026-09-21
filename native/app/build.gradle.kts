plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

// Phase 1: same applicationId as the Capacitor shell (com.estemshan.game)
// so the Play listing, signing key, and Firebase Android app can carry
// over when the native build replaces the wrapper.
// S3: release Firebase config. No google-services.json is committed and no
// google-services plugin is applied, so the real project's options are
// injected as BuildConfig fields. Resolution order for each value:
//   1. native/firebase-release.properties (gitignored, owner-supplied)
//   2. environment variable (CI secrets)
//   3. committed default
// The committed defaults are the values ALREADY public in the repo at
// design-ui/firebase-init.js; a Firebase API key is a public identifier that
// ships inside every APK (security comes from rules + auth, not from the
// key). The one value with NO committed source is the Android app id, so it
// stays a clearly-marked placeholder the owner must supply — FirebaseModule
// logs a warning rather than silently hitting the wrong project, which is
// the exact bug this story fixes.
val firebaseProps = java.util.Properties().apply {
  rootProject.file("firebase-release.properties").takeIf { it.exists() }
    ?.inputStream()?.use { load(it) }
}

fun firebaseValue(property: String, env: String, default: String): String =
  firebaseProps.getProperty(property)
    ?: System.getenv(env)?.takeIf { it.isNotBlank() }
    ?: default

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

    // S3: declared in defaultConfig (not release) so BOTH variants generate
    // the symbols — FirebaseModule references them from shared source, and a
    // release-only declaration breaks the debug compile. Debug never reads
    // them (it takes the emulator branch), release uses them for the real
    // project. Resolution order and the placeholder caveat: see the comment
    // on firebaseValue() above.
    buildConfigField(
      "String",
      "FIREBASE_PROJECT_ID",
      "\"${firebaseValue("firebase.projectId", "FIREBASE_PROJECT_ID", "made---estimation-card-game")}\"",
    )
    buildConfigField(
      "String",
      "FIREBASE_API_KEY",
      "\"${firebaseValue("firebase.apiKey", "FIREBASE_API_KEY", "AIzaSyAOX64y02461r7oJomYavmOowi9Eyze7KU")}\"",
    )
    buildConfigField(
      "String",
      "FIREBASE_APP_ID",
      "\"${firebaseValue("firebase.applicationId", "FIREBASE_APP_ID",
        "1:261597513798:android:00000000000000000000000000000000")}\"",
    )
  }

  // S4: release signing. The keystore and its credentials are NEVER
  // committed — they resolve from native/keystore-release.properties
  // (gitignored, owner-supplied) or CI secrets. With no keystore
  // configured, release signs with AGP's own debug key, which AGP
  // generates on demand — so ./gradlew :app:assembleRelease produces a
  // signed, installable APK even on a clean clone. Production signing
  // happens in CI from secrets (see the release job in
  // .github/workflows/android.yml). NOTE: this block must precede
  // buildTypes, which resolves the config at configuration time.
  val keystoreProps = java.util.Properties().apply {
    rootProject.file("keystore-release.properties").takeIf { it.exists() }
      ?.inputStream()?.use { load(it) }
  }
  val releaseStoreFile = keystoreProps.getProperty("storeFile")

  if (releaseStoreFile != null) {
    signingConfigs {
      create("release") {
        storeFile = file(releaseStoreFile)
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
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
      signingConfig = if (releaseStoreFile != null) {
        signingConfigs.getByName("release")
      } else {
        // No production keystore: AGP's debug key, generated on demand.
        signingConfigs.getByName("debug")
      }
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
