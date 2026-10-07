package com.estemshan.engine

/**
 * Trick-taking table ported from design-ui/engine/table-engine.js for a
 * single round (13 tricks). THE reference is docs/specs/02-engine-api.md
 * §4 — every rule below traces to a branch there. Pure functions, no I/O:
 * the round config (trump/caller/estimates/hands/leader) is an explicit
 * parameter, so a stale snapshot is impossible by construction (this is
 * what the JS Foundation Fix had to enforce with lifecycle discipline).
 * No GameSession, no Android imports (spec §7 layering).
 *
 * Scoring at DONE is deliberately OUT of scope here — resolveTrick hands
 * tricksWon to the ScoringEngine wrapper (spec §5), exactly like the JS
 * hands its result to ScoringEngine.calculateRoundScore.
 */
enum class TablePhase {
  PLAY,
  RESOLVING,
  DONE,
}

data class Play(val playerId: String, val card: Card)

data class TrickResult(
  val plays: List<Play>,
  val winnerId: String,
  val ledSuit: Suit,
)

/** Round config — seeded from the bidding outcome, never from mocks. */
data class RoundCfg(
  val round: Int,
  val trump: Suit,
  val callerId: String?,
  val withPlayers: List<String>,
  val estimates: Map<String, Int>,
  val dashCallers: List<String>,
  /** Caller leads trick 1; the dealer, when there is no caller (all-Dash). */
  val leaderId: String,
  val riskId: String?,
  val multiplier: Int = 1,
  val hands: Map<String, List<Card>>,
)

data class TableState(
  val cfg: RoundCfg,
  val seats: List<String>,
  /** Suits a seat is publicly known to lack (void revealed mid-trick). */
  val voids: Map<String, List<Suit>>,
  val tricksWon: Map<String, Int>,
  val trickNo: Int,
  val leaderId: String,
  val turn: String?,
  val plays: List<Play>,
  val ledSuit: Suit?,
  val lastTrick: TrickResult?,
  /**
   * Every card played in the *resolved* tricks of this round, oldest first.
   * `plays` holds only the in-progress trick and `resolveTrick` clears it, so
   * without this field the round's history would be gone the moment a trick
   * completes. Card-counting brains (`isBoss`, S7) need it to know which high
   * cards are still outstanding; it resets with the table, at `initTable`.
   * The current trick is NOT here — read `plays` for that.
   */
  val seenCards: List<Card> = emptyList(),
  val phase: TablePhase,
)

data class PlayCard(val playerId: String, val card: Card)

sealed class PlayEmit {
  data class Applied(val state: TableState) : PlayEmit()
  data class Rejected(val reason: String?) : PlayEmit()
}

data class CardLegality(val legal: Boolean, val reason: String? = null)

data class PlayPreview(
  val legal: Boolean,
  val reason: String? = null,
  val nextTurnSeat: String?,
  val nextPhase: TablePhase?,
)

data class RestoreResult(
  val state: TableState,
  val restored: Boolean,
  val reason: String? = null,
  val count: Int = 0,
)

fun initTable(cfg: RoundCfg, seats: List<String> = DEFAULT_SEATS): TableState = TableState(
  cfg = cfg,
  seats = seats,
  voids = seats.associateWith { emptyList<Suit>() },
  tricksWon = seats.associateWith { 0 },
  trickNo = 1,
  leaderId = cfg.leaderId,
  turn = cfg.leaderId,
  plays = emptyList(),
  ledSuit = null,
  lastTrick = null,
  seenCards = emptyList(),
  phase = TablePhase.PLAY,
)

// ── Card legality (follow suit) ──

/**
 * Reconnect hardening: a missing hand answers "nothing is legal", never
 * throws — the authoritative hand re-delivers later via restoreHand.
 */
fun legalCards(state: TableState, id: String): List<Card> {
  val hand = state.cfg.hands[id] ?: return emptyList()
  val led = state.ledSuit ?: return hand.toList() // leader may play anything
  val inSuit = hand.filter { it.suit == led }
  return if (inSuit.isNotEmpty()) inSuit else hand.toList() // must follow if able
}

fun isLegal(state: TableState, id: String, card: Card): Boolean =
  legalCards(state, id).any { it.suit == card.suit && it.rank.v == card.rank.v }

// ── Trick evaluation ──

fun cardValue(trump: Suit, ledSuit: Suit?, card: Card): Int {
  val isTrump = trump != Suit.SANS && card.suit == trump
  val follows = card.suit == ledSuit
  return card.rank.v + (if (isTrump) 1000 else if (follows) 100 else 0)
}

fun trickWinner(trump: Suit, ledSuit: Suit, plays: List<Play>): String {
  var best = plays[0]
  for (p in plays) {
    if (cardValue(trump, ledSuit, p.card) > cardValue(trump, ledSuit, best.card)) best = p
  }
  return best.playerId
}

fun currentWinnerId(state: TableState): String? {
  if (state.plays.isEmpty()) return null
  val led = state.ledSuit ?: return null
  return trickWinner(state.cfg.trump, led, state.plays)
}

// ── canPlayCard / previewPlay: pure, read-only projections of emitPlay ──

fun canPlayCard(state: TableState, playerId: String, card: Card?): CardLegality {
  if (state.phase != TablePhase.PLAY) return CardLegality(false, "NOT_PLAY_PHASE")
  if (state.turn != playerId) return CardLegality(false, "NOT_THIS_SEATS_TURN")
  if (card == null || !isLegal(state, playerId, card)) return CardLegality(false, "ILLEGAL_CARD")
  return CardLegality(true)
}

fun previewPlay(state: TableState, playerId: String, card: Card?): PlayPreview {
  val validation = canPlayCard(state, playerId, card)
  if (!validation.legal) return PlayPreview(false, validation.reason, null, null)
  // The exact count AFTER this (not-yet-applied) play, mirroring emitPlay.
  if (state.plays.size + 1 < 4) {
    return PlayPreview(true, null, nextSeat(state.seats, playerId), TablePhase.PLAY)
  }
  return PlayPreview(true, null, null, TablePhase.RESOLVING)
}

// ── Reducer ──

fun emitPlay(state: TableState, intent: PlayCard): PlayEmit {
  if (state.phase != TablePhase.PLAY) return PlayEmit.Rejected(null)
  if (state.turn != intent.playerId) return PlayEmit.Rejected(null)
  if (!isLegal(state, intent.playerId, intent.card)) {
    return PlayEmit.Rejected("Follow ${state.ledSuit?.name}")
  }

  // Reveal a void: the player couldn't follow the led suit.
  var voids = state.voids
  val led = state.ledSuit
  if (led != null && intent.card.suit != led && !(voids[intent.playerId]?.contains(led) == true)) {
    voids = voids + (intent.playerId to (voids[intent.playerId].orEmpty() + led))
  }

  val hand = state.cfg.hands[intent.playerId].orEmpty()
    .filter { !(it.suit == intent.card.suit && it.rank.v == intent.card.rank.v) }
  val hands = state.cfg.hands + (intent.playerId to hand)
  val newLed = if (state.plays.isEmpty()) intent.card.suit else state.ledSuit
  val plays = state.plays + Play(intent.playerId, intent.card.copy(played = true))

  return if (plays.size < 4) {
    PlayEmit.Applied(
      state.copy(
        cfg = state.cfg.copy(hands = hands),
        voids = voids,
        plays = plays,
        ledSuit = newLed,
        turn = nextSeat(state.seats, intent.playerId),
      ),
    )
  } else {
    // Trick complete — resolve via resolveTrick (same call the turn loop makes).
    PlayEmit.Applied(
      state.copy(
        cfg = state.cfg.copy(hands = hands),
        voids = voids,
        plays = plays,
        ledSuit = newLed,
        phase = TablePhase.RESOLVING,
        turn = null,
      ),
    )
  }
}

/**
 * Collects the completed trick. At trick 13 the round is DONE — plays are
 * cleared and tricksWon is final for the ScoringEngine wrapper (spec §5).
 * A call outside RESOLVING (or with no plays) is a safe no-op.
 */
fun resolveTrick(state: TableState): TableState {
  if (state.phase != TablePhase.RESOLVING || state.plays.isEmpty()) return state
  val led = state.ledSuit ?: return state
  val winner = trickWinner(state.cfg.trump, led, state.plays)
  val tricksWon = state.tricksWon + (winner to (state.tricksWon[winner] ?: 0) + 1)
  val lastTrick = TrickResult(state.plays.toList(), winner, led)
  // The trick is leaving `plays` — keep its cards in seenCards before the
  // clear below, so the round's played history survives the resolve.
  val seenCards = state.seenCards + state.plays.map { it.card }

  if (state.trickNo >= 13) {
    return state.copy(
      tricksWon = tricksWon,
      lastTrick = lastTrick,
      plays = emptyList(),
      seenCards = seenCards,
      phase = TablePhase.DONE,
    )
  }
  return state.copy(
    tricksWon = tricksWon,
    lastTrick = lastTrick,
    trickNo = state.trickNo + 1,
    leaderId = winner,
    turn = winner,
    plays = emptyList(),
    ledSuit = null,
    seenCards = seenCards,
    phase = TablePhase.PLAY,
  )
}

// ── Reconnect: re-seed ONE missing seat's hand (never a legality path) ──

/**
 * Deliberately narrow: seeds ONLY a genuinely missing seat (null — never
 * an existing array, so replayed emits that already shrank the hand are
 * never resurrected) on the SAME round.
 */
fun restoreHand(state: TableState, seatId: String, cards: List<Card>, round: Int): RestoreResult {
  if (round != state.cfg.round) return RestoreResult(state, false, "ROUND_MISMATCH")
  if (seatId.isEmpty() || !state.seats.contains(seatId)) {
    return RestoreResult(state, false, "INVALID_ARGUMENT")
  }
  if (state.cfg.hands[seatId] != null) return RestoreResult(state, false, "ALREADY_PRESENT")
  val hands = state.cfg.hands + (seatId to cards.toList())
  return RestoreResult(state.copy(cfg = state.cfg.copy(hands = hands)), true, null, cards.size)
}
