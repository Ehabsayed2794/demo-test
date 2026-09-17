// Top-level build file: plugin versions only (applied in :app).
// AGP 8.7.x + Kotlin 1.9.25 + Gradle 8.10 (see .github/workflows/android.yml).
// Compose compiler 1.5.15 (app module) pairs with Kotlin 1.9.25 exactly.
plugins {
  id("com.android.application") version "8.7.3" apply false
  id("org.jetbrains.kotlin.android") version "1.9.25" apply false
}
