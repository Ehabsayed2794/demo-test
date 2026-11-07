package com.estemshan.engine

/**
 * S71 (E6b, RD29/RD30) — the UNRANKED half of the matchmaking pool: the
 * rank-blind shared pool, its four-minute fill window, and the bot seats
 * that fill it. This is the pure policy slice only — NOT the resolver: no
 * callable, no Cloud Function, no Firestore documents, no services, no UI.
 * The queue, the search lifecycle and the server's decision of who shares
 * a table are the resolver's business; they consume this policy. Pure data
 * + pure functions, no I/O. Common-main: stdlib only.
 *
 * WHY A SEPARATE TYPE, NOT A MODE FLAG ON [MatchmakingSession]: the Unranked
 * pool is RANK-BLIND, and rank-blindness is a property of the DATA, not of
 * a flag. [MatchmakingSession] carries a tier in every one of its shapes
 * ([WaitingHuman.tier], [MatchmakingSession.bandAccepts],
 * [MatchmakingSession.strengthTiers]) and that band is the whole point of
 * it. A tiered session with `rankBlind = true` would still store, compare
 * and require a tier — the tier would still be read, and a bug that read it
 * would be invisible. Here there is no field to read: [UnrankedWaitingHuman]
 * holds a uid and nothing else, so "a King and a Bronze share a table" is
 * not a rule the code has to be careful about, it is the only thing the data
 * can express.
 *
 * WHAT IS THE SAME AND WHAT IS NOT: the table is still four seats (rule 9,
 * [MATCHMAKING_SEATS]) and the fill timer is still anchored to the FIRST
 * player (Q8) — but the window is FOUR minutes ([UNRANKED_FILL_MILLIS]),
 * not the Ranked two. There is no bot-fill VOTE here: S71's rule is
 * "configured bots only after a 4 minute fill timeout", and the vote is
 * T-20's Ranked Auto-Match mechanic, not part of this story. WHICH bot
 * takes a seat is the resolver's decision — as it is for
 * [MatchmakingSession.botSeatsToFill], which decides HOW MANY and never
 * WHICH.
 */

/**
 * The Unranked pool's fill window: four minutes from the FIRST player
 * entering the table, not from the last join (S71; the same anchoring as
 * Q8 for the Ranked window).
 */
const val UNRANKED_FILL_MILLIS: Long = 240_000L

/**
 * Where a waiting Unranked table stands in its lifecycle. There is no vote
 * phase and no rejected phase: the Unranked table either fills with humans
 * or, at the fill deadline, with the configured bots.
 */
enum class UnrankedPhase { FILLING, FILLED }

/**
 * One seated Unranked human — a uid and NOTHING else. The absence of a
 * tier here is the rank-blindness: this type cannot carry a rank, so no
 * downstream rule can consult one.
 */
data class UnrankedWaitingHuman(
  val uid: String,
)

/**
 * One waiting Unranked table as a pure value. Every transition function
 * returns a new session and never mutates the receiver; a no-op returns the
 * receiver unchanged.
 */
data class UnrankedMatchmakingSession(
  val phase: UnrankedPhase,
  val openedAtMillis: Long,
  val humans: List<UnrankedWaitingHuman>,
  val filledWithBots: Boolean,
) {

  /** The fill timer's close: four minutes after the first player entered. */
  val fillDeadlineMillis: Long
    get() = openedAtMillis + UNRANKED_FILL_MILLIS

  /**
   * How many bot seats a filled table creates — the session decides HOW
   * MANY, never WHICH bot. Zero while the table is still filling.
   */
  val botSeatsToFill: Int
    get() = if (phase == UnrankedPhase.FILLED && filledWithBots) {
      MATCHMAKING_SEATS - humans.size
    } else {
      0
    }

  /**
   * Seats [uid] while the table is still filling, it has fewer than four
   * humans, and the uid is not already seated (a duplicate uid never takes
   * a second seat). Otherwise a no-op: the receiver is returned unchanged.
   * No tier is consulted, because there is none.
   */
  fun join(uid: String, nowMillis: Long): UnrankedMatchmakingSession {
    if (phase != UnrankedPhase.FILLING) return this
    if (humans.size >= MATCHMAKING_SEATS) return this
    if (humans.any { it.uid == uid }) return this
    val seated = humans + UnrankedWaitingHuman(uid)
    return if (seated.size == MATCHMAKING_SEATS) {
      // Four humans fill the table on their own; no bot ever joins it.
      copy(phase = UnrankedPhase.FILLED, humans = seated, filledWithBots = false)
    } else {
      copy(humans = seated)
    }
  }

  /**
   * Removes [uid]'s seat if they are seated. A no-op otherwise.
   */
  fun leave(uid: String): UnrankedMatchmakingSession {
    if (humans.none { it.uid == uid }) return this
    return copy(humans = humans.filterNot { it.uid == uid })
  }

  /**
   * Advances the table to [nowMillis]. The fill timer closes exactly at
   * [fillDeadlineMillis] — 239 999 ms is still filling, 240 000 ms has
   * closed — and a closed timer fills the remaining seats with the
   * configured bots. A late tick catches up in one call: there is no
   * intermediate state to step through. A FILLED table is terminal.
   */
  fun step(nowMillis: Long): UnrankedMatchmakingSession {
    if (phase != UnrankedPhase.FILLING) return this
    if (nowMillis < fillDeadlineMillis) return this
    return copy(
      phase = UnrankedPhase.FILLED,
      filledWithBots = humans.size < MATCHMAKING_SEATS,
    )
  }

  companion object {
    /** Opens a table with its first player. */
    fun open(firstUid: String, nowMillis: Long): UnrankedMatchmakingSession =
      UnrankedMatchmakingSession(
        phase = UnrankedPhase.FILLING,
        openedAtMillis = nowMillis,
        humans = listOf(UnrankedWaitingHuman(firstUid)),
        filledWithBots = false,
      )
  }
}

/**
 * One waiting Unranked search as the pool sees it: who, which GameType,
 * and when they arrived. There is no tier and no rank — the rank-blind pool
 * never reads one.
 */
data class UnrankedCandidate(
  val uid: String,
  val gameType: GameType,
  val enqueuedAtMillis: Long,
)

/**
 * An Unranked table the pool has formed: the humans who share it and how
 * many of the four seats the configured bots take.
 */
data class UnrankedTable(
  val humans: List<UnrankedCandidate>,
  val botSeats: Int,
  val filledWithBots: Boolean,
)

/**
 * Forms the Unranked tables that are READY at [nowMillis], and only those.
 *
 * The pool is rank-blind, so the only filter is GM5's GameType rule — a
 * Full searcher is never seated into a Mini table and vice versa — which
 * holds for Unranked exactly as it does for Ranked. Within one GameType,
 * arrival order decides the table (the same deterministic order the Ranked
 * resolver uses), and each table takes up to four humans.
 *
 * A table of four humans is ready immediately. A table of fewer than four
 * becomes ready when its FIRST searcher's four-minute fill window closes
 * ([UNRANKED_FILL_MILLIS]), and the remaining seats go to the configured
 * bots. Before that moment the table is still filling and is NOT returned:
 * the pool keeps offering those seats to any human, whatever their rank,
 * until the window closes.
 *
 * The window is anchored to the first searcher of the group. A group whose
 * earliest searcher is reclaimed (its lease expired) re-anchors to the next
 * one on the following pass — the same reclaim the Ranked resolver applies.
 */
fun formUnrankedTables(candidates: List<UnrankedCandidate>, nowMillis: Long): List<UnrankedTable> {
  val ordered = candidates.sortedWith(compareBy({ it.enqueuedAtMillis }, { it.uid }))
  val tables = mutableListOf<UnrankedTable>()
  ordered.groupBy { it.gameType }.forEach { (_, sameType) ->
    sameType.chunked(MATCHMAKING_SEATS).forEach { chunk ->
      if (chunk.size == MATCHMAKING_SEATS) {
        tables += UnrankedTable(humans = chunk, botSeats = 0, filledWithBots = false)
      } else {
        val windowCloses = chunk.first().enqueuedAtMillis + UNRANKED_FILL_MILLIS
        if (nowMillis >= windowCloses) {
          tables += UnrankedTable(
            humans = chunk,
            botSeats = MATCHMAKING_SEATS - chunk.size,
            filledWithBots = true,
          )
        }
      }
    }
  }
  return tables
}
