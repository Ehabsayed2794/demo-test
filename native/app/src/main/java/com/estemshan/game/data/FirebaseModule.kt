package com.estemshan.game.data

import android.content.Context
import android.util.Log
import com.estemshan.game.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

/**
 * Firebase bootstrap. No google-services.json is committed and no
 * google-services plugin is applied: options are built in code.
 *
 * Debug builds talk to the Firebase Emulator Suite (10.0.2.2 = host loopback
 * from an Android emulator) against a dummy project. Release builds talk to
 * the REAL project, from BuildConfig fields injected by
 * app/build.gradle.kts (firebase-release.properties, CI secrets, or the
 * committed defaults — see the resolution order documented there).
 *
 * S3 fixed here: previously every build type initialized the dummy
 * "demo-test-ci" project, and this comment claimed release talked to the
 * real one. It did not. Release now builds real options, and if the Android
 * app id was never supplied it fails loudly (see PLACEHOLDER_APP_ID) instead
 * of silently pointing at a project that does not exist.
 */
object FirebaseModule {

  const val EMULATOR_HOST = "10.0.2.2"
  const val AUTH_EMULATOR_PORT = 9099
  const val FIRESTORE_EMULATOR_PORT = 8080

  // Correctly-shaped but obviously unpopulated: the project number is real
  // (261597513798), the Android app id is all zeros. The owner replaces it
  // via native/firebase-release.properties or a CI secret.
  private const val PLACEHOLDER_APP_ID =
    "1:261597513798:android:00000000000000000000000000000000"
  private const val TAG = "FirebaseModule"

  @Volatile
  private var initialized = false

  @Synchronized
  fun init(context: Context, useEmulator: Boolean = BuildConfig.USE_EMULATOR) {
    if (!initialized) {
      if (FirebaseApp.getApps(context).isEmpty()) {
        val options = if (useEmulator) {
          // Debug: dummy project; the Emulator Suite ignores credentials.
          FirebaseOptions.Builder()
            .setApplicationId("1:000000000000:android:0000000000000000000000")
            .setApiKey("emulator-dummy-key-not-a-secret")
            .setProjectId("demo-test-ci")
            .build()
        } else {
          if (BuildConfig.FIREBASE_APP_ID == PLACEHOLDER_APP_ID) {
            Log.w(
              TAG,
              "Release Firebase appId is the PLACEHOLDER. Populate " +
                "native/firebase-release.properties (firebase.applicationId) " +
                "or the FIREBASE_APP_ID CI secret with the Android app id " +
                "from the Firebase console. Auth/Firestore calls will fail " +
                "until then — this warning exists so it cannot be silent.",
            )
          }
          FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .build()
        }
        FirebaseApp.initializeApp(context, options)
      }
      initialized = true
    }
    if (useEmulator) {
      Firebase.auth.useEmulator(EMULATOR_HOST, AUTH_EMULATOR_PORT)
      Firebase.firestore.useEmulator(EMULATOR_HOST, FIRESTORE_EMULATOR_PORT)
    }
  }

  val auth: FirebaseAuth get() = Firebase.auth
  val db: FirebaseFirestore get() = Firebase.firestore
}
