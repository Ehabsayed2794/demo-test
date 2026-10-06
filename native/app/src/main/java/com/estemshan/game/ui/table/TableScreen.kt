package com.estemshan.game.ui.table

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.engine.Card as EngineCard
import com.estemshan.engine.Dealer
import com.estemshan.engine.Play
import com.estemshan.engine.PlayCard
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.currentWinnerId
import com.estemshan.engine.emitPlay
import com.estemshan.engine.initTable
import com.estemshan.engine.isLegal
import com.estemshan.engine.resolveTrick
import com.estemshan.game.ui.theme.EstemshanMotion
import com.estemshan.game.ui.theme.EstemshanTheme
import kotlinx.coroutines.delay

private val RedSuit = Color(0xFFE57373)

fun tableSuitColor(suit: Suit): Color = when (suit) {
  Suit.HEARTS, Suit.DIAMONDS -> RedSuit
  Suit.SANS -> Color(0xFFE8A33D)
  else -> Color(0xFFF0EADA)
}

/**
 * Trick-taking table. Stateless: state in, card taps out. The completed
 * trick auto-collects after a short highlight (the turn loop's
 * sweep-then-resolve, UI-side timing only — resolution itself is the
 * engine's resolveTrick via [onResolve]).
 *
 * S28 animations (all purely visual, driven by [state] only):
 * - card-play transition: each new entry in state.plays enters with a
 *   180 ms fade+slide+scale (AnimatedVisibility enter).
 * - trick-collection sweep: during RESOLVING the row drifts toward the
 *   winner (450 ms) while losers dim; runs *within* the 900 ms beat.
 * - winner highlight: the winning card scales to 1.08x with a gold border.
 * No animation gates intent: taps call onPlay synchronously, input is never
 * disabled by animation state, and nothing awaits a frame.
 */
@Composable
fun TableScreen(
  state: TableState,
  userSeat: String,
  rejection: String?,
  onPlay: (String, EngineCard) -> Unit,
  onResolve: () -> Unit,
) {
  // The 900 ms trick-resolution beat (S28: deliberate, not a transition).
  // The sweep/highlight animate *within* it; this delay is unchanged.
  if (state.phase == TablePhase.RESOLVING) {
    LaunchedEffect(state.trickNo) {
      delay(900)
      onResolve()
    }
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      "Round ${state.cfg.round} · Trick ${state.trickNo}/13 · Trump ${state.cfg.trump.name}",
      style = MaterialTheme.typography.titleMedium,
    )
    Text(
      when (state.phase) {
        TablePhase.DONE -> "Round complete"
        TablePhase.RESOLVING -> "${currentWinnerId(state) ?: ""} takes the trick"
        TablePhase.PLAY ->
          if (state.turn == userSeat) "Your turn ($userSeat)" else "Waiting for ${state.turn}"
      },
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
    )

    TrickArea(state)

    TallyRow(state)

    if (state.phase == TablePhase.PLAY) {
      val turn = state.turn
      if (turn == userSeat) {
        HandRow(state, userSeat, onPlay)
      } else if (turn != null) {
        Text("Waiting for $turn…", style = MaterialTheme.typography.bodyMedium)
        HandRow(state, turn, onPlay = null)
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

/** Everyone else's hand renders as a count — no peeking. */
@Composable
private fun TallyRow(state: TableState) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceEvenly,
  ) {
    for (seat in state.seats) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(seat, style = MaterialTheme.typography.labelLarge)
        Text(
          "${state.tricksWon[seat] ?: 0} won · ${state.cfg.hands[seat]?.size ?: 0} left",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}

@Composable
private fun TrickArea(state: TableState) {
  if (state.plays.isEmpty()) {
    Text("Lead a card", style = MaterialTheme.typography.bodyMedium)
    return
  }
  val isResolving = state.phase == TablePhase.RESOLVING
  val winner = if (isResolving) currentWinnerId(state) else null
  val winnerIndex =
    state.plays.indexOfFirst { it.playerId == winner }.takeIf { it >= 0 }
      ?: (state.plays.size / 2)
  val centerIndex = (state.plays.size - 1) / 2f
  // Sweep drift toward the winner's seat side; 0 when not resolving.
  // Purely visual: runs concurrently with the 900 ms beat, never delays it.
  val sweepShift by animateFloatAsState(
    targetValue = if (isResolving) (winnerIndex - centerIndex) * 28f else 0f,
    animationSpec = EstemshanMotion.floatSweepSpec(),
  )
  Row(
    modifier = Modifier.fillMaxWidth().graphicsLayer {
      translationX = sweepShift
    },
    horizontalArrangement = Arrangement.SpaceEvenly,
  ) {
    for (play in state.plays) {
      key(play.card.id) {
        AnimatedVisibility(
          visible = true,
          enter = fadeIn(EstemshanMotion.floatControlSpec()) +
            slideInVertically(EstemshanMotion.offsetCardPlaySpec()) { it / 2 } +
            scaleIn(EstemshanMotion.floatControlSpec(), initialScale = 0.85f),
          exit = fadeOut(EstemshanMotion.floatSweepSpec()),
        ) {
          PlayedCard(
            play = play,
            isWinner = play.playerId == winner,
            dimForSweep = isResolving && play.playerId != winner,
          )
        }
      }
    }
  }
}

@Composable
private fun PlayedCard(play: Play, isWinner: Boolean, dimForSweep: Boolean = false) {
  // Winner highlight: 180 ms scale pulse + gold border. Losers dim to 45%
  // over the 450 ms sweep. Both are graphicsLayer-only — no layout, no
  // state, no intent gating.
  val winnerScale by animateFloatAsState(
    targetValue = if (isWinner) 1.08f else 1f,
    animationSpec = EstemshanMotion.floatWinnerSpec(),
  )
  val sweepAlpha by animateFloatAsState(
    targetValue = if (dimForSweep) 0.45f else 1f,
    animationSpec = EstemshanMotion.floatSweepSpec(),
  )
  Card(
    modifier = Modifier.graphicsLayer {
      scaleX = winnerScale
      scaleY = winnerScale
      alpha = sweepAlpha
    }.then(
      if (isWinner) {
        Modifier.border(
          2.dp,
          MaterialTheme.colorScheme.primary,
          RoundedCornerShape(12.dp),
        )
      } else {
        Modifier
      },
    ),
    colors = CardDefaults.cardColors(
      containerColor = if (isWinner) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      },
    ),
  ) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Text(play.playerId, style = MaterialTheme.typography.labelLarge)
      Text(
        "${play.card.rank.s}${play.card.suit.symbol}",
        style = MaterialTheme.typography.titleMedium,
        color = if (isWinner) Color.Black else tableSuitColor(play.card.suit),
      )
    }
  }
}

@Composable
private fun HandRow(state: TableState, seat: String, onPlay: ((String, EngineCard) -> Unit)?) {
  val hand = state.cfg.hands[seat].orEmpty()
  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(hand, key = { it.id }) { card ->
      val legal = isLegal(state, seat, card)
      val label = "${card.rank.s}${card.suit.symbol}"
      if (onPlay != null && legal) {
        // S28: intent fires synchronously on tap — the table enter-animation
        // runs concurrently *after* dispatch, never ahead of it. No
        // awaitFrame, no animation-state gate, input never disabled.
        Button(
          onClick = { onPlay(seat, card) },
          modifier = Modifier.heightIn(min = 64.dp).width(52.dp),
        ) {
          Text(label, color = tableSuitColor(card.suit))
        }
      } else {
        OutlinedButton(
          onClick = {},
          enabled = false,
          modifier = Modifier.heightIn(min = 64.dp).width(52.dp),
        ) {
          Text(label, color = tableSuitColor(card.suit).copy(alpha = 0.45f))
        }
      }
    }
  }
}

private val PreviewSeats = listOf("p1", "p2", "p3", "p4")

private fun previewCfg(): RoundCfg = RoundCfg(
  round = 1,
  trump = Suit.SPADES,
  callerId = "p1",
  withPlayers = emptyList(),
  estimates = mapOf("p1" to 3, "p2" to 4, "p3" to 3, "p4" to 3),
  dashCallers = emptyList(),
  leaderId = "p1",
  riskId = "p4",
  hands = Dealer.dealHands(7),
)

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun TableMidTrickPreview() {
  var s = initTable(previewCfg(), PreviewSeats)
  repeat(2) {
    val seat = s.turn ?: return
    val hand = s.cfg.hands.getValue(seat)
    val led = s.ledSuit
    val card = if (led == null) hand[0] else hand.firstOrNull { it.suit == led } ?: hand[0]
    s = (emitPlay(s, PlayCard(seat, card)) as PlayEmit.Applied).state
  }
  EstemshanTheme {
    TableScreen(state = s, userSeat = "p3", rejection = null, onPlay = { _, _ -> }, onResolve = {})
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun TableDonePreview() {
  var s = initTable(previewCfg(), PreviewSeats)
  var guard = 0
  while (s.phase != TablePhase.DONE && guard < 200) {
    guard++
    if (s.phase == TablePhase.PLAY) {
      val seat = s.turn ?: break
      val hand = s.cfg.hands.getValue(seat)
      val led = s.ledSuit
      val card = if (led == null) hand[0] else hand.firstOrNull { it.suit == led } ?: hand[0]
      s = (emitPlay(s, PlayCard(seat, card)) as PlayEmit.Applied).state
    } else {
      s = resolveTrick(s)
    }
  }
  EstemshanTheme {
    TableScreen(state = s, userSeat = "p1", rejection = "Follow HEARTS", onPlay = { _, _ -> }, onResolve = {})
  }
}
