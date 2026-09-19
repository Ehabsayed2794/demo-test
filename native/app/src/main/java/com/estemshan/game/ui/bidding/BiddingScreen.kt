package com.estemshan.game.ui.bidding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.EmitResult
import com.estemshan.engine.emit
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.game.ui.theme.EstemshanTheme

/**
 * Bidding screen shell. Stateless: the state and every callback arrive as
 * parameters (skill: state hoisting). Turn-taking across seats belongs to
 * the session layer — this screen renders whoever [userSeat] is and a
 * waiting banner for everyone else.
 */
@Composable
fun BiddingScreen(
  state: BiddingState,
  userSeat: String,
  rejection: String?,
  forbidden: Int?,
  floor: Int?,
  onIntent: (BiddingIntent) -> Unit,
) {
  val turn = state.waitingFor
  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Round ${state.round} · Bidding", style = MaterialTheme.typography.headlineMedium)
    Text(phaseStep(state.subPhase), style = MaterialTheme.typography.labelLarge)
    Text(
      when {
        state.subPhase == BiddingPhase.DONE -> "Bidding complete"
        turn == userSeat -> "Your turn ($userSeat)"
        turn != null -> "Waiting for $turn"
        else -> ""
      },
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.primary,
    )

    val active = turn == userSeat && state.subPhase != BiddingPhase.DONE
    when (state.subPhase) {
      BiddingPhase.DASH -> if (active) DashControls(userSeat, onIntent)
      BiddingPhase.AUCTION -> if (active) AuctionControls(state, userSeat, onIntent)
      BiddingPhase.CONFIRM -> if (active) ConfirmControls(state, userSeat, onIntent)
      BiddingPhase.ESTIMATES -> if (active) {
        EstimatesControls(state, userSeat, forbidden, floor, onIntent)
      }
      BiddingPhase.DONE -> {
        Text(
          "Caller: ${state.callerId ?: "—"} · Trump: ${state.declaredTrump?.name ?: "—"}",
          style = MaterialTheme.typography.bodyLarge,
        )
      }
    }

    if (rejection != null) {
      Text(
        rejection,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
      )
    }
    Spacer(Modifier.height(8.dp))
  }
}

private val PreviewSeats = listOf("p1", "p2", "p3", "p4")

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun BiddingScreenAuctionPreview() {
  var s = initNormalRound(1, "p1", PreviewSeats)
  repeat(4) {
    val seat = s.waitingFor ?: return
    s = (emit(s, BiddingIntent.DashCallDecision(seat, false)) as EmitResult.Applied).state
  }
  EstemshanTheme {
    BiddingScreen(
      state = s,
      userSeat = "p1",
      rejection = null,
      forbidden = null,
      floor = null,
      onIntent = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun BiddingScreenFastEstimatesPreview() {
  EstemshanTheme {
    BiddingScreen(
      state = initFastRound(15, "p1", PreviewSeats),
      userSeat = "p2",
      rejection = "Max is 8 (Caller's cap)",
      forbidden = 2,
      floor = null,
      onIntent = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun BiddingScreenDonePreview() {
  var s = initFastRound(15, "p1", PreviewSeats)
  for ((seat, v) in listOf("p1" to 4, "p2" to 5, "p3" to 6, "p4" to 5)) {
    val r = emit(s, BiddingIntent.FinalEstimate(seat, v))
    s = (r as? EmitResult.Applied)?.state ?: (r as EmitResult.Completed).state
  }
  EstemshanTheme {
    BiddingScreen(
      state = s,
      userSeat = "p1",
      rejection = null,
      forbidden = null,
      floor = null,
      onIntent = {},
    )
  }
}
