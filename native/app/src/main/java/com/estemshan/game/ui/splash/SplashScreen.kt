package com.estemshan.game.ui.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.game.ui.theme.EstemshanTheme

/** Brand moment + loader. Stateless: routing lives in the nav graph. */
@Composable
fun SplashScreen() {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Estemshan", style = MaterialTheme.typography.displayLarge)
    Spacer(Modifier.height(4.dp))
    Text("Estimation card game", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(24.dp))
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun SplashPreview() {
  EstemshanTheme { SplashScreen() }
}
