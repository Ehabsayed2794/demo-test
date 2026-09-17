package com.estemshan.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.estemshan.game.data.FirebaseModule
import com.estemshan.game.ui.EstemshanNav

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Debug → Emulator Suite; release → real project (BuildConfig flag).
    FirebaseModule.init(this)
    setContent { EstemshanNav() }
  }
}
