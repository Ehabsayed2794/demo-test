package com.estemshan.game.ui.bidding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.EmitResult
import com.estemshan.engine.Suit
import com.estemshan.engine.auctionBidBeatsTop
import com.estemshan.engine.auctionBidIsWith
import com.estemshan.engine.canSubmit
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.emit
import com.estemshan.game.ui.theme.EstemshanTheme
import kotlin.math.roundToInt

private val RedSuit = Color(0xFFE57373)

fun suitColor(suit: Suit): Color = when (suit) {
  Suit.HEARTS, Suit.DIAMONDS -> RedSuit
  Suit.SANS -> Color(0xFFE8A33D)
  else -> Color(0xFFF0EADA)
}

fun suitLabel(suit: Suit): String {
  val name = suit.name.lowercase().replaceFirstChar { it.uppercase() }
  return "${suit.symbol} $name"
}

/** Phase step for the progress header (DASH → AUCTION → CONFIRM → ESTIMATES). */
fun phaseStep(phase: BiddingPhase): String = when (phase) {
  BiddingPhase.DASH -> "Step 1 of 4 · Dash Call"
  BiddingPhase.AUCTION -> "Step 2 of 4 · Auction"
  BiddingPhase.CONFIRM -> "Step 3 of 4 · Confirm"
  BiddingPhase.ESTIMATES -> "Step 4 of 4 · Estimates"
  BiddingPhase.DONE -> "Complete"
}

/** Illegal options render dimmed with the engine's own reason inline. */
@Composable
private fun SubmitButton(label: String, legal: Boolean, reason: String?, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    enabled = legal,
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
  ) {
    Text(label)
  }
  if (!legal && reason != null) {
    Text(
      reason,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.error,
    )
  }
}

@Composable
fun DashControls(seat: String, onIntent: (BiddingIntent) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Declare a Dash Call (0 tricks, pre-trump)?", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = { onIntent(BiddingIntent.DashCallDecision(seat, true)) },
        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
      ) { Text("Dash Call") }
      OutlinedButton(
        onClick = { onIntent(BiddingIntent.DashCallDecision(seat, false)) },
        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
      ) { Text("Decline") }
    }
  }
}

@Composable
fun AuctionControls(state: BiddingState, seat: String, onIntent: (BiddingIntent) -> Unit) {
  var tricks by remember(seat, state.auctionTop) { mutableFloatStateOf(4f) }
  var suit by remember(seat, state.auctionTop) { mutableStateOf(Suit.SPADES) }
  val picked = tricks.roundToInt()
  val intent = BiddingIntent.AuctionBid(seat, false, picked, suit)
  val verdict = canSubmit(state, intent)
  val hint = when {
    auctionBidIsWith(seat, picked, suit, state.auctionTop, state.auctionSuit, state.auctionBidder) ->
      "Matches the top — goes With"
    auctionBidBeatsTop(picked, suit, state.auctionTop, state.auctionSuit) ->
      if (state.auctionTop == 0) "Opens the auction" else "New top bid"
    else -> null
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Top: ${state.auctionTop}", style = MaterialTheme.typography.labelLarge)
    Text("Tricks: $picked", style = MaterialTheme.typography.titleMedium)
    Slider(
      value = tricks,
      onValueChange = { tricks = it },
      valueRange = 4f..13f,
      steps = 8,
      modifier = Modifier.fillMaxWidth(),
    )
    SuitPicker(selected = suit, onPick = { suit = it })
    if (hint != null) {
      Text(hint, style = MaterialTheme.typography.bodyMedium)
    }
    SubmitButton("Bid $picked ${suitLabel(suit)}", verdict.legal, verdict.reason) {
      onIntent(intent)
    }
    OutlinedButton(
      onClick = { onIntent(BiddingIntent.AuctionBid(seat, true)) },
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text("Pass") }
  }
}

@Composable
fun ConfirmControls(state: BiddingState, seat: String, onIntent: (BiddingIntent) -> Unit) {
  val floor = state.auctionTop
  var tricks by remember(seat, floor) { mutableFloatStateOf(floor.toFloat()) }
  var suit by remember(seat, floor) { mutableStateOf(state.auctionSuit ?: Suit.SPADES) }
  val picked = tricks.roundToInt().coerceAtLeast(floor)
  val intent = BiddingIntent.ConfirmCall(seat, picked, suit)
  val verdict = canSubmit(state, intent)

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Lock the call (min $floor)", style = MaterialTheme.typography.titleMedium)
    Text("Tricks: $picked", style = MaterialTheme.typography.titleMedium)
    Slider(
      value = tricks,
      onValueChange = { tricks = it },
      valueRange = floor.toFloat()..13f,
      steps = (13 - floor - 1).coerceAtLeast(0),
      modifier = Modifier.fillMaxWidth(),
    )
    SuitPicker(selected = suit, onPick = { suit = it })
    SubmitButton("Lock $picked ${suitLabel(suit)}", verdict.legal, verdict.reason) {
      onIntent(intent)
    }
  }
}

@Composable
fun EstimatesControls(
  state: BiddingState,
  seat: String,
  forbidden: Int?,
  floor: Int?,
  onIntent: (BiddingIntent) -> Unit,
) {
  val cap = state.auctionTop
  var tricks by remember(seat, cap) { mutableFloatStateOf(0f) }
  val picked = tricks.roundToInt().coerceIn(0, cap)
  val intent = BiddingIntent.FinalEstimate(seat, picked)
  val verdict = canSubmit(state, intent)

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Final estimate (max $cap)", style = MaterialTheme.typography.titleMedium)
    Text("Tricks: $picked", style = MaterialTheme.typography.titleMedium)
    Slider(
      value = tricks,
      onValueChange = { tricks = it },
      valueRange = 0f..cap.toFloat(),
      steps = (cap - 1).coerceAtLeast(0),
      modifier = Modifier.fillMaxWidth(),
    )
    if (forbidden != null) {
      Text("Can't pick $forbidden — bids would total 13", style = MaterialTheme.typography.bodyMedium)
    }
    if (floor != null) {
      Text("With floor: $floor (your own auction bid)", style = MaterialTheme.typography.bodyMedium)
    }
    SubmitButton(
      if (picked == 0) "Estimate 0 (Normal Dash)" else "Estimate $picked",
      verdict.legal,
      verdict.reason,
    ) {
      onIntent(intent)
    }
  }
}

@Composable
fun SuitPicker(selected: Suit, onPick: (Suit) -> Unit) {
  val suits = listOf(Suit.CLUBS, Suit.DIAMONDS, Suit.HEARTS, Suit.SPADES, Suit.SANS)
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Suit", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (s in suits) {
        val label = suitLabel(s)
        if (s == selected) {
          Button(
            onClick = { onPick(s) },
            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
          ) { Text(label) }
        } else {
          OutlinedButton(
            onClick = { onPick(s) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = suitColor(s)),
            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
          ) { Text(label) }
        }
      }
    }
  }
}

private val PreviewSeats = listOf("p1", "p2", "p3", "p4")

/** Mid-auction snapshot: dash declined, p1 opened 4 Spades. */
private fun previewAuctionState(): BiddingState {
  var s = initNormalRound(1, "p1", PreviewSeats)
  repeat(4) {
    val seat = s.waitingFor ?: return s
    s = (emit(s, BiddingIntent.DashCallDecision(seat, false)) as EmitResult.Applied).state
  }
  s = (emit(s, BiddingIntent.AuctionBid("p1", false, 4, Suit.SPADES)) as EmitResult.Applied).state
  return s
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun AuctionControlsPreview() {
  EstemshanTheme {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
      AuctionControls(state = previewAuctionState(), seat = "p2", onIntent = {})
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun EstimatesControlsPreview() {
  EstemshanTheme {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
      EstimatesControls(
        state = initFastRound(15, "p1", PreviewSeats),
        seat = "p1",
        forbidden = null,
        floor = null,
        onIntent = {},
      )
    }
  }
}
