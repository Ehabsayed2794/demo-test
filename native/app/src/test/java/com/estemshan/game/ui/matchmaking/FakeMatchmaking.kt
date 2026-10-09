package com.estemshan.game.ui.matchmaking

import com.estemshan.engine.GameType
import com.estemshan.services.MatchmakingEvent
import com.estemshan.services.MatchmakingPort
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * In-memory stand-in for the Ranked queue, for the same reason S15's
 * FakeRooms stands in for RoomService: the repo ships no FirebaseFirestore
 * fake by design, and the pool resolver this interface points at does not
 * exist yet. This implements the port's *contract* — a search carries the game
 * type and no tier, publishes one terminal event, then completes — rather
 * than faking the SDK.
 *
 * Outcomes are handed to the in-flight search through a channel, so a test
 * starts the search, publishes an outcome, and observes the state transition.
 */
class FakeMatchmaking : MatchmakingPort {

  private val queue = Channel<MatchmakingEvent>(capacity = Channel.UNLIMITED)

  /** The game type the last search carried — proves it rode along (GM5). */
  var lastGameType: GameType? = null
    private set

  /** The player the last search was for; null until a search actually runs. */
  var lastPlayerId: String? = null
    private set

  /** Times the player was removed from the queue. */
  var cancelCount: Int = 0
    private set

  override fun search(playerId: String, gameType: GameType): Flow<MatchmakingEvent> = flow {
    lastPlayerId = playerId
    lastGameType = gameType
    emit(queue.receive())
  }

  override suspend fun cancel(playerId: String) {
    cancelCount++
  }

  /** Hands [event] to the in-flight search; that search then ends. */
  suspend fun publish(event: MatchmakingEvent) {
    queue.send(event)
  }
}
