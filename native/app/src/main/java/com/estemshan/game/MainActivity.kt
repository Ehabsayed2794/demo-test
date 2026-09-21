package com.estemshan.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.estemshan.game.data.FirebaseModule
import com.estemshan.game.ui.EstemshanNav

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // targetSdk 36 opts the window into edge-to-side on API 35+: the app
    // draws behind the status and navigation bars. Our theme is near-black,
    // so the system icons must be light for contrast, and the Compose tree
    // pads itself away from the bars (see EstemshanNav's root modifier).
    enableEdgeToEdge()
    WindowCompat.getInsetsController(window, window.decorView).apply {
      isAppearanceLightStatusBars = false // light icons on the dark theme
      isAppearanceLightNavigationBars = false
    }
    // Debug → Emulator Suite; release → real project (BuildConfig flag).
    FirebaseModule.init(this)
    setContent { EstemshanNav() }
  }
}
