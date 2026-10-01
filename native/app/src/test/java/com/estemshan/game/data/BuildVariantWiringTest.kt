package com.estemshan.game.data

import com.estemshan.game.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S21 prerequisite — the qa build type's wiring, asserted per variant.
 *
 * `BuildConfig` is generated PER VARIANT, so this one source set is compiled
 * once per build type and each compilation sees only that variant's
 * constants. CI runs `testDebugUnitTest` and `testQaUnitTest`, so between
 * them every branch below is executed. `BUILD_TYPE` is the exact
 * discriminator — better than `DEBUG` here, since qa is debuggable too.
 *
 * The failure mode this guards is a build-type edit that silently retargets
 * a shipped variant at the wrong backend, which no amount of reading
 * build.gradle.kts catches once the file grows. What it cannot assert is
 * that FirebaseModule ACTS on the flag — that is structural: `FirebaseModule.init`
 * defaults its `useEmulator` argument to `BuildConfig.USE_EMULATOR`, and
 * these tests pin what that constant is per variant.
 */
class BuildVariantWiringTest {

  @Test
  fun useEmulatorFlagMatchesTheBuildType() {
    // THE field that selects the backend at runtime: FirebaseModule.init
    // branches on it. debug talks to the Emulator Suite on 10.0.2.2; qa and
    // release talk to the REAL project. A shipped variant left with this
    // true sends testers' traffic to 10.0.2.2:8080, which resolves to
    // nothing on a physical device — the app would look simply dead.
    val expected = when (BuildConfig.BUILD_TYPE) {
      "debug" -> true
      "qa", "release" -> false
      else -> error("unrecognized build type: ${BuildConfig.BUILD_TYPE}")
    }
    assertEquals(
      "USE_EMULATOR for the ${BuildConfig.BUILD_TYPE} variant",
      expected,
      BuildConfig.USE_EMULATOR,
    )
  }

  @Test
  fun everyVariantCarriesTheRealProjectIdInBuildConfig() {
    // The dummy emulator project id (demo-test-ci) never reaches
    // BuildConfig: defaultConfig holds the REAL project and FirebaseModule
    // hard-codes the dummy in its own emulator branch as a source constant.
    // So this field is the real project in every variant, and USE_EMULATOR
    // — not this field — is what routes the app at runtime. Asserting it
    // catches the natural mistake of giving debug a buildConfigField
    // override of the dummy id, which would then have to be reasoned about
    // per variant instead of in the one place that already handles it.
    assertEquals(
      "FIREBASE_PROJECT_ID for the ${BuildConfig.BUILD_TYPE} variant",
      "made---estimation-card-game",
      BuildConfig.FIREBASE_PROJECT_ID,
    )
  }

  @Test
  fun onlyTheQaVariantCarriesTheApplicationIdSuffix() {
    // applicationIdSuffix = ".qa" lets the qa APK install BESIDE the debug
    // one on a single tester device. The two hold different backends, so
    // without the suffix one silently clobbers the other and the surviving
    // app talks to whichever backend that build was compiled for. Losing
    // the suffix fails no compile — this test is the only thing that
    // notices.
    if (BuildConfig.BUILD_TYPE == "qa") {
      assertTrue(
        "qa must carry the .qa suffix so it installs alongside debug",
        BuildConfig.APPLICATION_ID.endsWith(".qa"),
      )
    } else {
      assertEquals(
        "debug and release keep the base applicationId",
        "com.estemshan.game",
        BuildConfig.APPLICATION_ID,
      )
    }
  }
}
