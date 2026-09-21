// Top-level build file: plugin versions only (applied in :app).
// Phase 0b: AGP 8.13.2 + Gradle 8.14.3 + compileSdk/targetSdk 36 (Android 16).
// Google requires API 36 for new apps since 2026-08-31; compileSdk 36 needs
// AGP >= 8.9.1 and AGP 8.13 needs Gradle >= 8.13.
// Kotlin stays 1.9.25 + legacy Compose compiler 1.5.15 — see
// docs/adr/0001-kotlin-vs-compose-compiler.md (S2 decision).
plugins {
  id("com.android.application") version "8.13.2" apply false
  id("org.jetbrains.kotlin.android") version "1.9.25" apply false
  // Pure-JVM engine module (no Android): same Kotlin, testable anywhere.
  kotlin("jvm") version "1.9.25" apply false
}
