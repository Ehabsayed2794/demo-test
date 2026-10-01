package com.estemshan.game.data

import com.estemshan.game.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S21 prerequisite — the qa build type's wiring, asserted per variant.
 *
 * `BuildConfig` is generated PER VARIANT, so this single source set is
 * compiled three times — once for [testDebugUnitTest], once for
 * testQaUnitTest, once for testReleaseUnitTest — and each compilation sees
 * only that variant's constants. CI runs the debug and qa tasks, so between
 * them every branch below is actually executed. That is the point: the
 * failure mode this guards is a build-type edit that silently retargets a
 * shipped variant at the wrong backend, which no amount of reading
 * build.gradle.kts catches once the file grows.
 *
 * What cannot be asserted here: that FirebaseModule ACTS on the flag. That
 * is covered structurally — [FirebaseModule.init] takes
 * `useEmulator = BuildConfig.USE_EMULATOR` as its default, and this test
 * pins what that constant is per variant.
 */
class BuildVariantWiringTest {

  @Test
  fun emulatorFlagSelectsTheMatchingProject() {
    // The two legal pairings, and only these. debug → the dummy project
    // demo-test-ci (the Emulator Suite ignores credentials); release and qa
    // → the real project. A qa build left pointing at the emulator host
    // would send testers' traffic to 10.0.2.2:8080, which resolves to
    // nothing on a physical device, and the app would look simply "broken".
    if (BuildConfig.USE_EMULATOR) {
      assertEquals("demo-test-ci", BuildConfig.FIREBASE_PROJECT_ID)
    } else {
      assertEquals("made---estimation-card-game", BuildConfig.FIREBASE_PROJECT_ID)
    }
  }

  @Test
  fun onlyTheQaVariantCarriesTheApplicationIdSuffix() {
    // applicationIdSuffix = ".qa" lets the qa APK install BESIDE the debug
    // one on a single tester device. Asserted here because losing the
    // suffix does not fail any build: one build silently overwrites the
    // other, and the surviving app talks to whichever backend that
    // surviving build was compiled for. The debuggable + real-project
    // combination identifies qa uniquely — release is not debuggable and
    // debug uses the emulator.
    val isQa = BuildConfig.DEBUG && !BuildConfig.USE_EMULATOR
    if (isQa) {
      assertTrue(
        "qa must carry the .qa suffix so it installs alongside debug",
        BuildConfig.APPLICATION_ID.endsWith(".qa"),
      )
    } else {
      assertFalse(
        "debug and release keep the base applicationId",
        BuildConfig.APPLICATION_ID.endsWith(".qa"),
      )
    }
  }
}
