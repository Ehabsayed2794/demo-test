package com.estemshan.engine

/**
 * S74 — the disconnect vote (DEC-DISC-01; ROOM + UNRANKED only): the pure decision core.
 *
 * A seat loses its connection mid-match. From that instant — before any vote exists — the
 * seat is held by the CONFIGURED bot profile (DEC-RD21-01) and play continues: no pause is
 * introduced while the vote is open (OPEN-2 is unchanged and still binds this flow). This
 * object is everything the vote adds on top: one question for the remaining eligible humans
 * — continue with the bot, or wait for the disconnected player? — a 30-second window, and
 * the ruled settlement (owner closure 2026-10-16 §2):
 *
 * - agreement is the majority — 2 of 3 answers;
 * - a winning WAIT pauses the match for a bounded 90 seconds, then resumes;
 * - no agreement within 30 s continues with the configured bot.
 *
 * Reconnect while the table waits restores the seat (S74). Nothing here models seat control:
 * in every outcome of [DisconnectVote.decideVote] the seat stays with the configured bot
 * until the player reclaims it. The flow deliberately shares no counter with Vote Kick (S60)
 * or the RD19 inactivity takeover (S73) — DEC-DISC-01 keeps the three paths separate.
 * A8.2 / Q36 (2026-10-23) adds one interaction the caller must apply: the **7th inactivity
 * takeover fires immediately even while this ballot is open**, removes the seat, and leaves
 * the ballot terminal **CANCELLED** — no expiry verdict, no stale completion, no second charge.
 *
 * Permanent replacement — the seat stops being reclaimable — is a caller-side transition;
 * the charge it records is fixed here per the same closure §1: UNRANKED → 2 Koz, ROOM → none
 * (reaffirmed by A7.4 / O-03c, 2026-10-22: the replacement keeps its ratified charge, and
 * "no ROOM charge" means no charge for this path, not a blanket zero), and RANKED has no
 * entry — the disconnect vote does not exist in Ranked. Per option (a) the
 * charge is a career Koz-count increment only: no forced placement, no new result value.
 *
 * Layering: pure Kotlin over caller-supplied ballots — no Firestore, no clock, no GameSession.
 * :services translates the vote document into these calls and executes the window and the
 * reclaim; the S74 UI binds the exposed state.
 */
object DisconnectVote {

  /** DEC-DISC-01: the vote runs 30 seconds over a live table — no pause while it is open. */
  const val voteWindowSec: Int = 30

  /** Closure §2: a winning WAIT pauses the match for a bounded 90 seconds — never indefinite. */
  const val waitWindowSec: Int = 90

  /**
   * Settles a closed window from the ballots of the remaining eligible humans.
   *
   * Agreement is the ruled majority — 2 of 3 answers: two [DisconnectVoteChoice.WAIT] ballots
   * win WAIT, two [DisconnectVoteChoice.CONTINUE] ballots win CONTINUE. Anything short of that
   * — a 1-1 split, a single answer, or no answer at all — is
   * [DisconnectVoteStatus.NO_AGREEMENT]: continue with the configured bot.
   *
   * [votes] carries one ballot per remaining human — at most three; bots never vote and hold
   * no ballot. The 30-second window itself is the caller's timer, not this function's.
   */
  fun decideVote(votes: List<DisconnectVoteChoice>): DisconnectVoteStatus {
    require(votes.size <= 3) { "at most three humans can vote (bots hold no ballot): $votes" }
    val waitVotes = votes.count { it == DisconnectVoteChoice.WAIT }
    val continueVotes = votes.count { it == DisconnectVoteChoice.CONTINUE }
    return when {
      waitVotes >= 2 -> DisconnectVoteStatus.WAIT
      continueVotes >= 2 -> DisconnectVoteStatus.CONTINUE
      else -> DisconnectVoteStatus.NO_AGREEMENT
    }
  }

  /**
   * The charge a PERMANENT disconnect replacement records (closure §1): [MatchMode.UNRANKED]
   * → [DisconnectVoteCharge.KOZ2]; [MatchMode.ROOM] → [DisconnectVoteCharge.NONE].
   *
   * `null` for [MatchMode.RANKED] — the entry does not exist: DEC-DISC-01 keeps the disconnect
   * vote out of Ranked entirely, so there is no replacement charge to look up there. Null is
   * not "no charge" — it is "no such path".
   */
  fun permanentCharge(mode: MatchMode): DisconnectVoteCharge? = when (mode) {
    MatchMode.UNRANKED -> DisconnectVoteCharge.KOZ2
    MatchMode.ROOM -> DisconnectVoteCharge.NONE
    MatchMode.RANKED -> null
  }
}

/** S74 ballot — the one question: continue with the configured bot, or wait for the player? */
enum class DisconnectVoteChoice { CONTINUE, WAIT }

/** How the window settles: a winning answer, or the no-agreement default (configured bot). */
enum class DisconnectVoteStatus { CONTINUE, WAIT, NO_AGREEMENT }

/** The Koz a permanent disconnect replacement records: +2 in UNRANKED, none in ROOM. */
enum class DisconnectVoteCharge { KOZ2, NONE }

/**
 * The disconnect charge rides the same [KozCharge] shape the leave paths
 * record through, so a caller can feed
 * [RankedStats.accumulateDepartureCharge] without a translation of its own:
 * KOZ2 → [KozCharge.Koz2], NONE → [KozCharge.None] (a Room replacement
 * records nothing).
 */
fun DisconnectVoteCharge.toKozCharge(): KozCharge = when (this) {
  DisconnectVoteCharge.KOZ2 -> KozCharge.Koz2
  DisconnectVoteCharge.NONE -> KozCharge.None
}
