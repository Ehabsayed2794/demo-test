package com.estemshan.engine

/**
 * S72 — explicit leave / domain-level Koz charge (roadmap closure 2026-10-16,
 * OPEN-4 option (a)): what the career Koz count records when a seat leaves a
 * match, pure over the match mode and the leave reason.
 *
 * Charge scope as ruled:
 * - Explicit Leave Match → +2 Koz in RANKED and UNRANKED; ROOM records none.
 * - The 15-timeout automatic removal → same scope as explicit leave (RD20:
 *   the timeout counter is S61's; this file shares nothing with it).
 * - Vote Kick → +1 Koz final (restores RD18's original "recorded as Koz";
 *   the RD31 doubling is repealed for this path — conflict C1). The vote
 *   holds in RANKED + UNRANKED since the 2026-10-17 owner clarification —
 *   ROOM is the only mode without it — so this charge is deliberately
 *   mode-independent.
 * - Seat still reclaimable and the human returns → no departure, no Koz.
 *
 * Layering: everything here is pure Kotlin over caller-supplied inputs — no
 * Firestore, no clock, no GameSession, no standings. :services translates
 * documents into these inputs and records the charge; this file only decides
 * it. Pure functions, no I/O.
 */

/** The Koz charge recorded on a departure: none, or +1 / +2 to the career count. */
sealed interface KozCharge {
  data object None : KozCharge
  data object Koz1 : KozCharge
  data object Koz2 : KozCharge
}

/** Why a seat stopped being occupied by its human. */
enum class LeaveReason {
  /** The player chose Explicit Leave Match (S72). */
  EXPLICIT_LEAVE,
  /** The automatic 15-timeout removal (S61's counter, S42's enforcement). */
  FIFTEEN_TIMEOUT,
  /** A passed manual Vote Kick (S60 / RD18). */
  VOTE_KICK,
  /** The seat is reclaimable and the human returned — no departure at all. */
  RETURNED_ALLOWED,
}

/**
 * Decides the Koz charge for a seat that left (or did not). The only inputs
 * are the match's authority mode and the reason — nothing else, so the answer
 * is deterministic and testable without a match doc.
 */
object LeaveMatch {
  fun decideLeave(mode: MatchMode, reason: LeaveReason): KozCharge {
    return when (reason) {
      LeaveReason.RETURNED_ALLOWED -> KozCharge.None
      LeaveReason.VOTE_KICK -> KozCharge.Koz1
      LeaveReason.EXPLICIT_LEAVE, LeaveReason.FIFTEEN_TIMEOUT -> when (mode) {
        MatchMode.RANKED, MatchMode.UNRANKED -> KozCharge.Koz2
        MatchMode.ROOM -> KozCharge.None
      }
    }
  }
}
