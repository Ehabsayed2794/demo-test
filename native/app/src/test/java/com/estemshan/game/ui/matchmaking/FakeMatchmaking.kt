package com.estemshan.game.ui.matchmaking

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.services.MatchmakingEvent
import com.estemshan.services.MatchmakingPort
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * In-memory stand-in for the solo queue, for the same reason S15's
 * FakeRooms stands in for RoomService: the repo ships no FirebaseFirestore
 * fake by design, and the pool resolver this interface points at does not
 * exist yet. This implements the port's *contract* — a search carries the game
 * type and the mode and no tier, publishes progress and then one terminal
 * event — rather than faking the SDK.
 *
 * Outcomes are handed to the in-flight search through a channel, so a test
 * starts the search, publishes an outcome, and observes the state transition.
 */
class FakeMatchmaking : MatchmakingPort {

  private val queue = Channel<MatchmakingEvent>(capacity = Channel.UNLIMITED)

  /** The game type the last search carried — proves it rode along (GM5). */
  var lastGameType: GameType? = null
    private set

  /** The mode the last search carried — proves it rode along (S71). */
  var lastMode: MatchMode? = null
    private set

  /** The player the last search was for; null until a search actually runs. */
  var lastPlayerId: String? = null
    private set

  /** Times the player was removed from the queue. */
  var cancelCount: Int = 0
    private set

  override fun search(
    playerId: String,
    gameType: GameType,
    mode: MatchMode,
  ): Flow<MatchmakingEvent> = flow {
    lastPlayerId = playerId
    lastGameType = gameType
    lastMode = mode
    // Progress first, terminal last: the search ends when the terminal event
    // arrives, exactly as the production port's flow completes.
    while (true) {
      val event = queue.receive()
      emit(event)
      if (event.isTerminal()) break
    }
  }

  override suspend fun cancel(playerId: String) {
    cancelCount++
  }

  /** Hands [event] to the in-flight search. */
  suspend fun publish(event: MatchmakingEvent) {
    queue.send(event)
  }
}

/**
 * A terminal event ends the search; [MatchmakingEvent.Searching] is progress
 * and does not.
 */
private fun MatchmakingEvent.isTerminal(): Boolean =
  this is MatchmakingEvent.Matched ||
    this is MatchmakingEvent.TimedOut ||
    this is MatchmakingEvent.Failed
