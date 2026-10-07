pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}
rootProject.name = "Estemshan"
include(":app")
include(":engine")
include(":services")
// E6b (S42): the Ranked authority layer — Kotlin/JS on Node, sharing :engine.
// See docs/adr/0002-functions-runtime.md.
include(":functions")
