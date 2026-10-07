package com.estemshan.game.ui.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.estemshan.game.R
import com.estemshan.game.ui.settings.PrefsSettingsStore

/**
 * S27 — the five game sounds. Short synthesized WAVs under res/raw (see
 * the file header comments there); swap licensed assets in later under the
 * SAME names and no code changes.
 *
 * Two layers, split exactly on the unit-test boundary:
 * - [SfxClip] + [SoundManager]'s semantic methods are pure decision logic
 *   (which clip, whether allowed) — JVM-tested with a fake [ClipPlayer].
 * - [SoundPoolClipPlayer] is the only class that touches AudioTrack — it
 *   never runs in a unit test.
 */
enum class SfxClip {
  CARD_PLACE,
  TRICK_WIN,
  ROUND_SCORE,
  MATCH_WIN,
  TAP,
}

/** One-directional sink: play a clip. A lambda counts. */
fun interface ClipPlayer {
  fun play(clip: SfxClip)
}

/**
 * Short-SFX player over [SoundPool] (not MediaPlayer: these are
 * sub-second UI sounds). Loads eagerly at construction; play() is
 * fire-and-forget and synchronous from the caller's side — no coroutines,
 * so the call sites stay exactly as immediate as they are today.
 */
class SoundPoolClipPlayer(context: Context) : ClipPlayer {

  private val pool: SoundPool = SoundPool.Builder()
    .setMaxStreams(5)
    .setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build(),
    )
    .build()

  private val ids: Map<SfxClip, Int> = mapOf(
    SfxClip.CARD_PLACE to R.raw.sfx_card_place,
    SfxClip.TRICK_WIN to R.raw.sfx_trick_win,
    SfxClip.ROUND_SCORE to R.raw.sfx_round_score,
    SfxClip.MATCH_WIN to R.raw.sfx_match_win,
    SfxClip.TAP to R.raw.sfx_ui_tap,
  ).mapValues { (_, res) -> pool.load(context, res, 1) }

  override fun play(clip: SfxClip) {
    val id = ids[clip] ?: return
    pool.play(id, 1f, 1f, 1, 0, 1f)
  }

  fun release() {
    pool.release()
  }
}

/**
 * The game-sound entry point. [isEnabled] is a LAMBDA re-read on every
 * play — never a value cached at construction — so flipping Settings →
 * Sound takes effect immediately with no restart. Off means silence:
 * nothing reaches the player at all.
 */
class SoundManager(
  private val player: ClipPlayer,
  private val isEnabled: () -> Boolean,
) {

  fun play(clip: SfxClip) {
    if (isEnabled()) player.play(clip)
  }

  /** A card left the hand for the table. */
  fun cardPlaced() = play(SfxClip.CARD_PLACE)

  /** A trick was won (fires as the resolution beat starts). */
  fun trickWon() = play(SfxClip.TRICK_WIN)

  /** A bid/estimate was submitted. */
  fun bidSubmitted() = play(SfxClip.TAP)

  /** A round's bidding completed (quick match) or scored (online). */
  fun roundScored() = play(SfxClip.ROUND_SCORE)

  /**
   * The match ended — fires at most once: callers invoke this on every
   * standings arrival and only the final round passes the gate. MINI ends
   * at 10, FULL at 18 (both are [maxRounds]).
   */
  fun matchWonIfFinal(round: Int, maxRounds: Int) {
    if (round >= maxRounds) play(SfxClip.MATCH_WIN)
  }

  /** A primary-button tap. */
  fun tapped() = play(SfxClip.TAP)

  companion object {
    /** Production instance: SoundPool output, flag from SharedPreferences. */
    fun create(context: Context): SoundManager {
      val app = context.applicationContext
      val store = PrefsSettingsStore(app)
      return SoundManager(SoundPoolClipPlayer(app), store::soundEnabled)
    }

    /** Silent stand-in: previews and anywhere no provider is installed. */
    fun noop(): SoundManager = SoundManager(ClipPlayer {}, { false })
  }
}

/**
 * Ambient sound access for screens and flows. Defaults to [SoundManager.noop]
 * (not an error) so @Previews compose without a provider; the app root
 * provides the real instance once.
 */
val LocalSfx = staticCompositionLocalOf { SoundManager.noop() }

/** Remember the app-scoped manager — one SoundPool per process. */
@Composable
fun rememberSoundManager(): SoundManager {
  val context = LocalContext.current
  return remember(context.applicationContext) {
    SoundManager.create(context.applicationContext)
  }
}

/** Install the real manager for the subtree (once, at the app root). */
@Composable
fun ProvideSoundManager(content: @Composable () -> Unit) {
  val manager = rememberSoundManager()
  CompositionLocalProvider(LocalSfx provides manager, content = content)
}
