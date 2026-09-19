package com.estemshan.game.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.game.ui.theme.EstemshanTheme

/**
 * Narrow identity card (spec 01): who is signed in. Match stats arrive
 * with the season service — no invented numbers here.
 */
@Composable
fun ProfileScreen(uid: String) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Profile", style = MaterialTheme.typography.headlineMedium)
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Card(
          shape = CircleShape,
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
          Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Text(
              avatarLetters(uid),
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onPrimary,
            )
          }
        }
        Text(uid, style = MaterialTheme.typography.labelLarge)
        Text("Signed in", style = MaterialTheme.typography.bodyMedium)
      }
    }
    Spacer(Modifier.height(8.dp))
  }
}

fun avatarLetters(uid: String): String {
  val alnum = uid.filter { it.isLetterOrDigit() }
  return when {
    alnum.length >= 2 -> "${alnum[0]}${alnum[1]}".uppercase()
    alnum.length == 1 -> "${alnum[0]}${alnum[0]}".uppercase()
    else -> "E?"
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun ProfilePreview() {
  EstemshanTheme { ProfileScreen(uid = "abc123def456") }
}
