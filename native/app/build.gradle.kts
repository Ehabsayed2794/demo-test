import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  // S25: field-crash visibility. Crashlytics Gradle plugin 3.0.8 — requires
  // AGP 8.1+ (pinned 8.13.2) and Gradle 8.0+ (pinned 8.14.3); verified
  // against CrashlyticsPlugin.validateDependencies and the Firebase docs, so
  // no toolchain change. The version is pinned HERE rather than the root
  // build file so the S25 diff stays inside native/app/**. The
  // google-services plugin stays absent by design (S3): safe because every
  // build type has minify off, so the plugin never registers the upload
  // tasks that require it (CrashlyticsPlugin.registerTasks only registers
  // UploadMappingFileTask when mappingFileUploadEnabled.getOrElse(
  // isMinifyEnabled)); google_app_id is injected per build type below.
  id("com.google.firebase.crashlytics") version "3.0.8"
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
val firebaseProps = Properties().apply {
  rootProject.file("firebase-release.properties").takeIf { it.exists() }
    ?.inputStream()?.use { load(it) }
}

fun firebaseValue(property: String, env: String, default: String): String =
  firebaseProps.getProperty(property)
    ?: System.getenv(env)?.takeIf { it.isNotBlank() }
    ?: default

// S25: the single source for the Firebase Android app id. Feeds BOTH the
// FIREBASE_APP_ID BuildConfig field (read by FirebaseModule) and the
// google_app_id string resource per build type (read by Crashlytics) — one
// val, so the two can never drift apart.
val firebaseAppId = firebaseValue(
  "firebase.applicationId",
  "FIREBASE_APP_ID",
  "1:261597513798:android:00000000000000000000000000000000",
)

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
      // S25: via the shared firebaseAppId val (single source with the
      // google_app_id resValue below) — value unchanged.
      "\"$firebaseAppId\"",
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
  val keystoreProps = Properties().apply {
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
      // S25: no crash collection in debug (emulator/CI project — nothing
      // may leave the device). Wired to the manifest meta-data
      // firebase_crashlytics_collection_enabled via placeholder.
      manifestPlaceholders["crashlyticsCollectionEnabled"] = "false"
      // S25: google_app_id WITHOUT the google-services plugin. Debug targets
      // the dummy emulator project — the exact id FirebaseModule builds its
      // debug FirebaseOptions with — so every reader sees the same project.
      // Collection is disabled above, so nothing is ever reported.
      resValue("string", "google_app_id", "1:000000000000:android:0000000000000000000000")
    }
    // S21 prerequisite — the human-QA build. Same REAL Firebase project as
    // release (USE_EMULATOR false, so FirebaseModule builds the real
    // options and never points at 10.0.2.2, which does not exist on a
    // physical device) but debuggable and dev-signed, so it needs no store
    // listing and no production keystore. Firebase App Distribution ships
    // this variant to the tester group (docs/release/HUMAN_QA.md).
    // create(), not a qa { } block: AGP's Kotlin DSL generates typed
    // accessors only for the built-in debug/release, so a custom build type
    // named in a block is an unresolved reference at configuration time.
    create("qa") {
      buildConfigField("boolean", "USE_EMULATOR", "false")
      isMinifyEnabled = false
      isDebuggable = true
      // S25: qa reports to the REAL project — collection on, and google_app_id
      // from the shared firebaseAppId val (same source as FIREBASE_APP_ID,
      // never a second copy).
      manifestPlaceholders["crashlyticsCollectionEnabled"] = "true"
      resValue("string", "google_app_id", firebaseAppId)
      // AGP's debug key, generated on demand, so assembleQa always yields
      // an installable APK. Deliberately NOT the production keystore: a
      // tester build is not a signed release.
      signingConfig = signingConfigs.getByName("debug")
      // Installable BESIDE the debug build on one device. The two hold
      // different Firebase projects (emulator vs real), so without this
      // suffix one silently clobbers the other and changes which backend
      // the installed app talks to.
      applicationIdSuffix = ".qa"
      // :engine and :services define no qa build type; take their release
      // variants, whose USE_EMULATOR is also false.
      matchingFallbacks += "release"
    }
    release {
      buildConfigField("boolean", "USE_EMULATOR", "false")
      isMinifyEnabled = false
      // S25: release reports to the REAL project — same wiring as qa.
      manifestPlaceholders["crashlyticsCollectionEnabled"] = "true"
      resValue("string", "google_app_id", firebaseAppId)
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
  // S25: crash reporting. Version comes from the BOM above — no explicit
  // version. The -ktx artifact is deprecated upstream, so the base one.
  implementation("com.google.firebase:firebase-crashlytics")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

  val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
  implementation(composeBom)
  androidTestImplementation(composeBom)
  implementation("androidx.compose.material3:material3")
  // S28: Table-screen motion (AnimatedVisibility + animateFloatAsState).
  // Version-aligned via the compose BOM above; purely visual, no logic.
  implementation("androidx.compose.animation:animation")
  implementation("androidx.compose.ui:ui-tooling-preview")

  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
  implementation("androidx.navigation:navigation-compose:2.7.7")

  // Pure-JVM game engine (Cards/Deck/Bidding/Table/Scoring). The app is a
  // thin UI shell: all rules live in :engine and are unit-tested there.
  implementation(project(":engine"))

  // Online multiplayer (S14): MatchService/RoomService/MatchAdapter over
  // Firestore. Greenfield until now — an issue here could not break the app
  // build because nothing wired it in.
  implementation(project(":services"))

  // Coroutines: OnlineServices builds a process-scoped CoroutineScope over
  // SupervisorJob + Dispatchers. Pinned explicitly rather than resolved
  // transitively through lifecycle-viewmodel-ktx/play-services, matching the
  // version :services depends on.
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
