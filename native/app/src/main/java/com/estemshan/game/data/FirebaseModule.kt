package com.estemshan.game.data

import android.content.Context
import com.estemshan.game.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

/**
 * Phase 1 Firebase bootstrap. No google-services.json is committed and no
 * google-services plugin is applied: options are built in code so the
 * repo carries zero secrets. Debug builds talk to the Firebase Emulator
 * Suite (10.0.2.2 = host loopback from an Android emulator); release
 * talks to the real project.
 */
object FirebaseModule {

  const val EMULATOR_HOST = "10.0.2.2"
  const val AUTH_EMULATOR_PORT = 9099
  const val FIRESTORE_EMULATOR_PORT = 8080

  @Volatile
  private var initialized = false

  @Synchronized
  fun init(context: Context, useEmulator: Boolean = BuildConfig.USE_EMULATOR) {
    if (!initialized) {
      if (FirebaseApp.getApps(context).isEmpty()) {
        FirebaseApp.initializeApp(
          context,
          FirebaseOptions.Builder()
            .setApplicationId("1:000000000000:android:0000000000000000000000")
            .setApiKey("emulator-dummy-key-not-a-secret")
            .setProjectId("demo-test-ci")
            .build(),
        )
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
