package com.estemshan.game.ui.table

import androidx.lifecycle.ViewModel
import com.estemshan.engine.Card
import com.estemshan.engine.CardLegality
import com.estemshan.engine.PlayCard
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.PlayPreview
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.TableState
import com.estemshan.engine.canPlayCard
import com.estemshan.engine.emitPlay
import com.estemshan.engine.initTable
import com.estemshan.engine.previewPlay
import com.estemshan.engine.resolveTrick
import com.estemshan.engine.restoreHand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin shell over the pure TableEngine: holds the immutable TableState,
 * forwards plays, and surfaces typed rejections as inline UI text. Turn
 * collection (resolveTrick timing) is exposed explicitly — the screen
 * auto-collects after a short highlight; tests call [resolve] directly.
 * Zero rules here.
 */
class TableViewModel : ViewModel() {

  private val _state = MutableStateFlow<TableState?>(null)
  val state: StateFlow<TableState?> = _state.asStateFlow()

  private val _rejection = MutableStateFlow<String?>(null)
  val rejection: StateFlow<String?> = _rejection.asStateFlow()

  fun startRound(cfg: RoundCfg, seats: List<String>) {
    _state.value = initTable(cfg, seats)
    _rejection.value = null
  }

  fun legality(seat: String, card: Card?): CardLegality {
    val s = _state.value ?: return CardLegality(false, "NOT_STARTED")
    return canPlayCard(s, seat, card)
  }

  fun preview(seat: String, card: Card?): PlayPreview {
    val s = _state.value
    return if (s == null) {
      PlayPreview(false, "NOT_STARTED", null, null)
    } else {
      previewPlay(s, seat, card)
    }
  }

  fun play(seat: String, card: Card) {
    val s = _state.value ?: run {
      _rejection.value = "Table has not started"
      return
    }
    when (val r = emitPlay(s, PlayCard(seat, card))) {
      is PlayEmit.Applied -> {
        _state.value = r.state
        _rejection.value = null
      }
      is PlayEmit.Rejected -> {
        _rejection.value = r.reason ?: "Play rejected"
      }
    }
  }

  fun resolve() {
    val s = _state.value ?: return
    _state.value = resolveTrick(s)
  }

  fun reseedHand(seat: String, cards: List<Card>, round: Int): Boolean {
    val s = _state.value ?: return false
    val r = restoreHand(s, seat, cards, round)
    if (r.restored) _state.value = r.state
    return r.restored
  }
}
