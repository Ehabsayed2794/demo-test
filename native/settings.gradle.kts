pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}
dependencyResolutionManagement {
  // PREFER_SETTINGS, not FAIL_ON_PROJECT_REPOS: the Kotlin/JS Gradle plugin
  // (the :functions module, ADR 0002) registers its own Node.js and yarn
  // distribution repositories at the PROJECT level, which FAIL_ON_PROJECT_REPOS
  // rejects outright — every :functions task that needs node (jsNodeTest, npm
  // resolution) then fails before the task graph can even be built, and no
  // build-script switch can prevent it: the plugin applies them lazily from
  // YarnRootExtension, so it always re-adds them after any manual override.
  //
  // PREFER_SETTINGS still resolves google()/mavenCentral() from HERE first for
  // every module, so JVM/Android artifacts keep their single, centralized
  // source exactly as before; the Kotlin/JS distributions are the only things
  // that fall through to a project repo. Anything a build script adds now
  // warns instead of hard-failing, which is the right trade for a Kotlin/JS
  // target that cannot comply.
  repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
  repositories {
    google()
    mavenCentral()
    // Kotlin/JS (:functions, ADR 0002) fetches its own Node.js and yarn to run
    // the JS target, and registers these two Ivy distribution repositories at
    // the PROJECT level. Declaring them here centrally is what makes PREFER_SETTINGS
    // above resolve them without warnings; the patterns are the plugin's own.
    // JVM/Android artifacts still resolve to google() / mavenCentral() —
    // org.nodejs and com.yarnpkg exist in neither.
    ivy {
      url = uri("https://nodejs.org/dist")
      patternLayout {
        artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
      }
      metadataSources { artifact() }
    }
    ivy {
      url = uri("https://github.com/yarnpkg/yarn/releases/download")
      patternLayout {
        artifact("v[revision]/[artifact](-v[revision]).[ext]")
      }
      metadataSources { artifact() }
    }
  }
}
rootProject.name = "Estemshan"
include(":app")
include(":engine")
include(":services")
// E6b (S42): the Ranked authority layer — Kotlin/JS on Node, sharing :engine.
// See docs/adr/0002-functions-runtime.md.
include(":functions")
