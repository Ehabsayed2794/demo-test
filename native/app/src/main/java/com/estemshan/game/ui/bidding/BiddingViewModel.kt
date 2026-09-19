package com.estemshan.game.ui.bidding

import androidx.lifecycle.ViewModel
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingState
import com.estemshan.engine.EmitResult
import com.estemshan.engine.Legality
import com.estemshan.engine.canSubmit
import com.estemshan.engine.emit
import com.estemshan.engine.forbiddenEstimateFor
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.withFloorFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin shell over the pure BiddingEngine: holds the immutable
 * BiddingState, forwards intents, and surfaces typed rejections as
 * inline UI text. Zero rules here — legality comes from canSubmit, and
 * turn-taking across seats belongs to the session layer, not this VM.
 */
class BiddingViewModel : ViewModel() {

  private val _state = MutableStateFlow<BiddingState?>(null)
  val state: StateFlow<BiddingState?> = _state.asStateFlow()

  private val _rejection = MutableStateFlow<String?>(null)
  val rejection: StateFlow<String?> = _rejection.asStateFlow()

  private val _outcome = MutableStateFlow<BiddingOutcome?>(null)
  val outcome: StateFlow<BiddingOutcome?> = _outcome.asStateFlow()

  private val _generalPass = MutableStateFlow<Int?>(null)
  val generalPass: StateFlow<Int?> = _generalPass.asStateFlow()

  fun startNormalRound(round: Int, dealer: String, seats: List<String>, multiplier: Int = 1) {
    _state.value = initNormalRound(round, dealer, seats, multiplier)
    _rejection.value = null
    _outcome.value = null
    _generalPass.value = null
  }

  fun startFastRound(round: Int, dealer: String, seats: List<String>) {
    _state.value = initFastRound(round, dealer, seats)
    _rejection.value = null
    _outcome.value = null
    _generalPass.value = null
  }

  fun legality(intent: BiddingIntent): Legality {
    val s = _state.value ?: return Legality(false, "Bidding has not started")
    return canSubmit(s, intent)
  }

  fun submit(intent: BiddingIntent) {
    val s = _state.value ?: run {
      _rejection.value = "Bidding has not started"
      return
    }
    when (val r = emit(s, intent)) {
      is EmitResult.Applied -> {
        _state.value = r.state
        _rejection.value = null
      }
      is EmitResult.Completed -> {
        _state.value = r.state
        _outcome.value = r.outcome
        _rejection.value = null
      }
      is EmitResult.GeneralPass -> {
        _state.value = r.state
        _generalPass.value = r.doubledMultiplier
        _rejection.value = null
      }
      is EmitResult.Rejected -> {
        _rejection.value = r.reason
      }
    }
  }

  /** R1 hint for the estimate controls, if any number is forbidden now. */
  fun forbiddenFor(seat: String): Int? {
    val s = _state.value ?: return null
    return forbiddenEstimateFor(s, seat)
  }

  /** With floor hint for the estimate controls, if this seat is With. */
  fun withFloorOf(seat: String): Int? {
    val s = _state.value ?: return null
    return withFloorFor(s.actionHistory, seat)
  }
}
