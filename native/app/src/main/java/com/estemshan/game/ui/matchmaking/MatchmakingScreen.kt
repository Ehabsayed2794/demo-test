package com.estemshan.game.ui.matchmaking

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.estemshan.game.R
import com.estemshan.game.ui.theme.DisplayFamily
import com.estemshan.game.ui.theme.EstemshanColors
import com.estemshan.game.ui.theme.EstemshanTheme

/**
 * S55/S56 — the Ranked solo search screen. Stateless: it renders
 * [MatchmakingUiState] and forwards every intent to [MatchmakingViewModel],
 * so the lifecycle has one owner. The rank ladder and its highlighted pool
 * come in as [RankPool] — RD9 makes the pool a server-derived fact, so the
 * screen never asks for a tier and never offers one.
 *
 * Copy is verbatim from
 * `docs/design/Estimation Ranked Match v2 - Matchmaking (standalone).html`
 * and lives in strings.xml; the ladder's tier names and RD1 Arabic titles are
 * product identity and sit in [RankTiers] with the model.
 *
 * Two states are deliberately NOT drawn: [MatchmakingUiState.Idle] (the player
 * is not searching — there is no screen to show) and
 * [MatchmakingUiState.MatchFound], which the design calls out as a separate
 * screen it does not draw. This composable renders nothing for either, so the
 * transition into a match is handed to whatever owns that screen.
 *
 * The page holds NO configuration: Game Type, Calculation, and Timer stay on
 * S36, per the design's mapping note. It also invents nothing — no queue
 * size, no wait estimate, no ETA. The elapsed timer and the three waiting
 * seats are the only live elements.
 */
@Composable
fun MatchmakingScreen(
  state: MatchmakingUiState,
  pool: RankPool,
  onCancel: () -> Unit,
  onTryAgain: () -> Unit,
) {
  when (state) {
    is MatchmakingUiState.Searching ->
      SearchScreen(state = state, pool = pool, onCancel = onCancel)

    is MatchmakingUiState.TimedOut ->
      FailureScreen(
        status = R.string.matchmaking_status_timed_out,
        title = R.string.matchmaking_timed_out_title,
        body = R.string.matchmaking_timed_out_body,
        pool = pool,
        onCancel = onCancel,
        onTryAgain = onTryAgain,
      )

    is MatchmakingUiState.Failed ->
      FailureScreen(
        status = R.string.matchmaking_status_failed,
        title = R.string.matchmaking_failed_title,
        body = R.string.matchmaking_failed_body,
        pool = pool,
        onCancel = onCancel,
        onTryAgain = onTryAgain,
      )

    MatchmakingUiState.Idle,
    is MatchmakingUiState.MatchFound,
    -> Unit
  }
}

// ── S55: searching ────────────────────────────────────────────────────────

@Composable
private fun SearchScreen(
  state: MatchmakingUiState.Searching,
  pool: RankPool,
  onCancel: () -> Unit,
) {
  MatchmakingScaffold(
    title = R.string.matchmaking_title_searching,
    status = { SearchingChip(state.elapsedMillis) },
    pool = pool,
    body = { SeatsRow() },
    actions = { SearchActions(onCancel) },
  )
}

// ── S56: timed out / failed ───────────────────────────────────────────────

@Composable
private fun FailureScreen(
  status: Int,
  title: Int,
  body: Int,
  pool: RankPool,
  onCancel: () -> Unit,
  onTryAgain: () -> Unit,
) {
  MatchmakingScaffold(
    title = R.string.matchmaking_title_result,
    status = { FailureChip(label = status) },
    pool = pool,
    body = { FailurePanel(title = title, body = body) },
    actions = { FailureActions(onCancel = onCancel, onTryAgain = onTryAgain) },
  )
}

/**
 * The shell both states share: the scrolled body — header, rank ladder, the
 * state's own panel, and the two notes — above a pinned action bar, so a long
 * ladder never pushes Cancel off the bottom. Cancel exists in every state;
 * there is no uncancelable spinner.
 */
@Composable
private fun MatchmakingScaffold(
  title: Int,
  status: @Composable () -> Unit,
  pool: RankPool,
  body: @Composable () -> Unit,
  actions: @Composable () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().background(EstemshanColors.Background),
  ) {
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Header(title = title, status = status)
      RankCard(pool)
      body()
      Notes()
    }
    actions()
  }
}

// ── Header ────────────────────────────────────────────────────────────────

@Composable
private fun Header(title: Int, status: @Composable () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text(
        stringResource(R.string.matchmaking_kicker),
        style = mono.copy(color = EstemshanColors.InkFaint, fontSize = 10.sp, letterSpacing = 1.8.sp),
      )
      Spacer(Modifier.height(4.dp))
      Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )
    }
    status()
  }
}

/**
 * SEARCHING 0:24 — the elapsed time the client measures, never a countdown or
 * an estimate. The dot pulses while the search is live.
 */
@Composable
private fun SearchingChip(elapsedMillis: Long) {
  val pulse = rememberInfiniteTransition(label = "chip")
  val pulseAlpha by pulse.animateFloat(
    initialValue = 1f,
    targetValue = 0.25f,
    animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
    label = "pulse",
  )
  StatusChip(
    label = R.string.matchmaking_status_searching,
    time = formatElapsed(elapsedMillis),
    dot = EstemshanColors.Gold,
    dotAlpha = pulseAlpha,
  )
}

/** S56's chip: solid red, no time, and no animation ticking behind it. */
@Composable
private fun FailureChip(label: Int) {
  StatusChip(label = label, time = null, dot = EstemshanColors.Error)
}

@Composable
private fun StatusChip(label: Int, time: String?, dot: Color, dotAlpha: Float = 1f) {
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(15.dp))
      .background(EstemshanColors.Pill)
      .border(1.dp, EstemshanColors.PanelLine, RoundedCornerShape(15.dp))
      .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier = Modifier
        .size(7.dp)
        .clip(CircleShape)
        .background(dot)
        .graphicsLayer { alpha = dotAlpha },
    )
    Text(
      stringResource(label),
      style = mono.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.2.sp),
    )
    if (time != null) {
      Text(
        time,
        style = mono.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.2.sp),
      )
    }
  }
}

// ── The rank card and its ladder ──────────────────────────────────────────

/**
 * YOUR RANK over the read-only ladder. The bracket highlights the pool the
 * server searches — your tier and the one below — and there is no selector,
 * no opt-up, and no Skill Band anywhere on it (RD9).
 */
@Composable
private fun RankCard(pool: RankPool) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, EstemshanColors.PanelLine),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        RankShield(pool.current)
        Spacer(Modifier.width(12.dp))
        Column {
          Text(
            stringResource(R.string.matchmaking_rank_label),
            style = mono.copy(color = EstemshanColors.InkFaint, fontSize = 9.sp, letterSpacing = 1.4.sp),
          )
          Spacer(Modifier.height(3.dp))
          Text(
            pool.current.name,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = DisplayFamily, fontSize = 18.sp),
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            pool.current.arabicTitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Ladder(pool)
    }
  }
}

/** The tier's initial on a gold shield — the rank's face, nothing more. */
@Composable
private fun RankShield(tier: RankTier) {
  Box(
    modifier = Modifier
      .size(width = 40.dp, height = 46.dp)
      .clip(ShieldShape)
      .background(Brush.verticalGradient(listOf(EstemshanColors.GoldHi, EstemshanColors.Gold, EstemshanColors.GoldDeep))),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      tier.name.take(1),
      fontFamily = DisplayFamily,
      fontSize = 16.sp,
      color = EstemshanColors.OnGold,
    )
  }
}

@Composable
private fun Ladder(pool: RankPool) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    PoolBracket(pool)
    Row(modifier = Modifier.fillMaxWidth()) {
      pool.tiers.forEachIndexed { index, tier ->
        LadderNode(index = index, tier = tier, pool = pool)
      }
    }
  }
}

/**
 * The "YOUR POOL" bracket, spanning exactly the pool's rungs. It uses the same
 * per-rung weights as [Ladder]'s row, so its edges land on the same boundaries
 * whether the pool is two rungs wide or — at the bottom of the ladder — one.
 */
@Composable
private fun PoolBracket(pool: RankPool) {
  val first = pool.poolIndices.min()
  val span = pool.poolIndices.size
  Row(modifier = Modifier.fillMaxWidth()) {
    if (first > 0) Spacer(Modifier.weight(first.toFloat()))
    Column(
      modifier = Modifier.weight(span.toFloat()),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        stringResource(R.string.matchmaking_pool_label),
        style = mono.copy(color = EstemshanColors.Gold, fontSize = 9.sp, letterSpacing = 1.4.sp),
      )
      Spacer(Modifier.height(3.dp))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(1.dp)
          .background(EstemshanColors.GoldDim),
      )
    }
    val after = pool.tiers.size - first - span
    if (after > 0) Spacer(Modifier.weight(after.toFloat()))
  }
}

@Composable
private fun RowScope.LadderNode(index: Int, tier: RankTier, pool: RankPool) {
  Column(
    modifier = Modifier.weight(1f),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    LadderDot(index = index, pool = pool)
    Spacer(Modifier.height(7.dp))
    Text(
      tier.name,
      style = mono.copy(
        color = when {
          index == pool.currentIndex -> EstemshanColors.Gold
          pool.isInPool(index) -> MaterialTheme.colorScheme.onSurfaceVariant
          else -> EstemshanColors.InkFaint
        },
        fontWeight = if (index == pool.currentIndex) FontWeight.Bold else FontWeight.Medium,
        fontSize = 10.sp,
      ),
    )
  }
}

/**
 * One rung's dot, with the rail behind it. The rail is a hairline per rung so
 * the pool's stretch can take the gold while the rest stays faint; it stops
 * at the ladder's centre dots at either end instead of running past them.
 */
@Composable
private fun LadderDot(index: Int, pool: RankPool) {
  Box(
    modifier = Modifier.height(20.dp),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val y = size.height / 2f
      val start = if (index == 0) size.width / 2f else 0f
      val end = if (index == pool.tiers.lastIndex) size.width / 2f else size.width
      val brush = when {
        index == pool.currentIndex ->
          Brush.horizontalGradient(listOf(EstemshanColors.GoldDim, EstemshanColors.Gold), 0f, size.width)

        pool.isInPool(index) -> SolidColor(EstemshanColors.GoldDim)
        else -> SolidColor(EstemshanColors.PanelLine)
      }
      drawLine(
        brush = brush,
        start = Offset(start, y),
        end = Offset(end, y),
        strokeWidth = 2.dp.toPx(),
      )
    }
    Box(
      modifier = Modifier
        .size(12.dp)
        .clip(CircleShape)
        .background(if (index == pool.currentIndex) EstemshanColors.Gold else MaterialTheme.colorScheme.background)
        .border(
          width = 2.dp,
          color = when {
            index == pool.currentIndex -> EstemshanColors.GoldHi
            pool.isInPool(index) -> EstemshanColors.GoldDim
            else -> EstemshanColors.InkFaint
          },
          shape = CircleShape,
        ),
    )
  }
}

// ── The seats ─────────────────────────────────────────────────────────────

/**
 * You, plus the three seats the pool has to fill. An empty seat waits for a
 * player — no bot ever appears in Ranked, so there is no bot name, tier, or
 * personality to show.
 */
@Composable
private fun SeatsRow() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SeatYou(modifier = Modifier.weight(1f))
    repeat(3) { SeatWaiting(modifier = Modifier.weight(1f)) }
  }
}

@Composable
private fun SeatYou(modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = EstemshanColors.Gold.copy(alpha = 0.10f),
    border = BorderStroke(1.dp, EstemshanColors.Gold.copy(alpha = 0.45f)),
  ) {
    Column(
      modifier = Modifier.padding(vertical = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
        modifier = Modifier
          .size(52.dp)
          .clip(CircleShape)
          .background(EstemshanColors.Pill)
          .border(2.dp, EstemshanColors.Gold, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          stringResource(R.string.matchmaking_seat_you).take(1),
          fontFamily = DisplayFamily,
          fontSize = 20.sp,
          color = EstemshanColors.GoldHi,
        )
      }
      Text(
        stringResource(R.string.matchmaking_seat_you),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun SeatWaiting(modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, EstemshanColors.PanelLine),
  ) {
    Column(
      modifier = Modifier.padding(vertical = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      WaitingAvatar()
      Text(
        stringResource(R.string.matchmaking_seat_waiting),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/**
 * A dashed ring with a gold comet travelling it — the seat is live, not
 * vacant. The comet is the only animation on the seat; the ring itself is
 * static so the shape reads as a place to fill.
 */
@Composable
private fun WaitingAvatar() {
  val transition = rememberInfiniteTransition(label = "waiting")
  val angle by transition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
    label = "orbit",
  )

  Box(
    modifier = Modifier.size(56.dp),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val radius = size.minDimension / 2f - 4.dp.toPx()
      drawCircle(
        color = EstemshanColors.InkFaint,
        radius = radius,
        style = Stroke(
          width = 2.dp.toPx(),
          cap = StrokeCap.Round,
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        ),
      )
    }
    Canvas(modifier = Modifier.matchParentSize().rotate(angle)) {
      drawArc(
        color = EstemshanColors.Gold,
        startAngle = -90f,
        sweepAngle = 80f,
        useCenter = false,
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
        topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
      )
    }
  }
}

// ── S56's panel ───────────────────────────────────────────────────────────

@Composable
private fun FailurePanel(title: Int, body: Int) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, EstemshanColors.Error.copy(alpha = 0.40f)),
  ) {
    Column(
      modifier = Modifier.padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier = Modifier
          .size(30.dp)
          .clip(CircleShape)
          .border(2.dp, EstemshanColors.Error, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "!",
          style = mono.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

// ── The notes ─────────────────────────────────────────────────────────────

@Composable
private fun Notes() {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    Note(text = R.string.matchmaking_note_pool, glyph = "◈", emphasized = true)
    Note(text = R.string.matchmaking_note_capabilities, glyph = "○", emphasized = false)
  }
}

@Composable
private fun Note(text: Int, glyph: String, emphasized: Boolean) {
  Row(verticalAlignment = Alignment.Top) {
    Text(
      glyph,
      style = mono.copy(color = EstemshanColors.Gold, fontSize = 12.sp),
      modifier = Modifier.width(14.dp).wrapContentWidth(Alignment.CenterHorizontally),
    )
    Spacer(Modifier.width(9.dp))
    Text(
      stringResource(text),
      style = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 13.sp,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
      ),
    )
  }
}

// ── The pinned actions ────────────────────────────────────────────────────

@Composable
private fun SearchActions(onCancel: () -> Unit) {
  ActionBar {
    Text(
      stringResource(R.string.matchmaking_hint_cancel_always),
      style = mono.copy(color = EstemshanColors.InkFaint, fontSize = 11.sp, letterSpacing = 0.6.sp),
      modifier = Modifier
        .weight(1f)
        .wrapContentWidth(Alignment.Start)
        .align(Alignment.CenterVertically),
    )
    OutlinedButton(
      onClick = onCancel,
      modifier = Modifier.heightIn(min = 38.dp),
    ) {
      Text(stringResource(R.string.matchmaking_action_cancel_search))
    }
  }
}

@Composable
private fun FailureActions(onCancel: () -> Unit, onTryAgain: () -> Unit) {
  ActionBar {
    OutlinedButton(
      onClick = onCancel,
      modifier = Modifier.heightIn(min = 38.dp),
    ) {
      Text(stringResource(R.string.matchmaking_action_cancel))
    }
    Spacer(Modifier.width(10.dp))
    Button(
      onClick = onTryAgain,
      modifier = Modifier.heightIn(min = 38.dp),
    ) {
      Text(stringResource(R.string.matchmaking_action_try_again))
    }
  }
}

/** The strip both states pin to the bottom, ruled off from the body above. */
@Composable
private fun ActionBar(content: @Composable RowScope.() -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .drawBehind {
        drawLine(
          brush = SolidColor(EstemshanColors.PanelLine),
          start = Offset.Zero,
          end = Offset(size.width, 0f),
          strokeWidth = 1.dp.toPx(),
        )
      }
      .padding(horizontal = 24.dp, vertical = 10.dp)
      .height(56.dp),
    horizontalArrangement = Arrangement.End,
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

// ── Faces ──────────────────────────────────────────────────────────────────

/**
 * The theme's bundled monospace face, sized and spaced per use by each caller.
 * S57 landed the design-system fonts, so this is the real Spline Sans Mono
 * rather than the platform default the theme fell back to before.
 */
private val mono
  @Composable get() = MaterialTheme.typography.labelLarge

/**
 * The timer the client measures, m:ss — elapsed only, never a countdown; the
 * pool resolver publishes no ETA, so there is no number to count down from.
 */
internal fun formatElapsed(millis: Long): String {
  val seconds = (millis / 1000L).toInt()
  return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** The heater shape behind the rank shield. */
private val ShieldShape = GenericShape { size, _ ->
  val w = size.width
  val h = size.height
  moveTo(w * 0.5f, 0f)
  lineTo(w, h * 0.22f)
  lineTo(w, h * 0.64f)
  lineTo(w * 0.5f, h)
  lineTo(0f, h * 0.64f)
  lineTo(0f, h * 0.22f)
  close()
}

// ── Previews: one per state the screen draws ──────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun SearchingPreview() {
  EstemshanTheme {
    MatchmakingScreen(
      state = MatchmakingUiState.Searching(elapsedMillis = 24_000L),
      pool = RankPool(RankTiers.all, currentIndex = 2),
      onCancel = {},
      onTryAgain = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun SearchingAtTheBottomOfTheLadderPreview() {
  // Bronze's pool is itself alone — the bracket draws one rung, not two.
  EstemshanTheme {
    MatchmakingScreen(
      state = MatchmakingUiState.Searching(elapsedMillis = 5_000L),
      pool = RankPool(RankTiers.all, currentIndex = 0),
      onCancel = {},
      onTryAgain = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun TimedOutPreview() {
  EstemshanTheme {
    MatchmakingScreen(
      state = MatchmakingUiState.TimedOut,
      pool = RankPool(RankTiers.all, currentIndex = 2),
      onCancel = {},
      onTryAgain = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun FailedPreview() {
  EstemshanTheme {
    MatchmakingScreen(
      state = MatchmakingUiState.Failed,
      pool = RankPool(RankTiers.all, currentIndex = 2),
      onCancel = {},
      onTryAgain = {},
    )
  }
}
