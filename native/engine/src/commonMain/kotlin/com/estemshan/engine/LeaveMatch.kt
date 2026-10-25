package com.estemshan.engine

/**
 * S72 — explicit leave / domain-level Koz charge: what the career Koz count
 * records when a seat leaves a match, pure over the match mode and the leave
 * reason.
 *
 * Charge table as ruled by the ratified register
 * (docs/decisions/OPEN_QUESTIONS.md as of 2026-10-23; owner rulings of
 * 2026-10-22 — Q14b / Q14b(1) / Q14d(1) / Q37 (A8.3) / A6.12 / A7.4 — which
 * supersede the repealed 2026-10-16 OPEN-4 option (a) / C1 closure this file
 * cited before):
 * - Explicit Leave Match → +2 Koz in RANKED and UNRANKED, +1 Koz in ROOM
 *   (Q14b, confirmed by the A6.12 / Q14b(1) record).
 * - Vote Kick → +2 Koz (Q14d(1), supersedes A5.1's "1 Koz, final"). The vote
 *   holds in RANKED + UNRANKED since the 2026-10-17 owner clarification and
 *   is never held in ROOM, so the charge stays deliberately
 *   mode-independent.
 * - The 15-timeout automatic removal is NOT a departure (Q14b(1), A6.12): it
 *   keeps its own charge — 2 Koz in RANKED and UNRANKED, 1 Koz in ROOM.
 *   The ROOM amount is closed: CANONICAL_RULES.md's "Pointer, 2026-10-23"
 *   on the O-03d cell rules "the 15-timeout removal records 1 Koz in ROOM
 *   (2 Koz in RANKED/UNRANKED unchanged)" — closed by A8.3 / Q37,
 *   superseding Q14b(1)'s historical "open per O-03d" wording (the register
 *   leaves those rows verbatim under the pointer convention).
 * - A non-voluntary disconnect or app close is not a departure and charges
 *   nothing (Q14b(1)); the permanent disconnect-replacement charge is
 *   DisconnectVote's own (Q33 / A7.4: UNRANKED 2 Koz, ROOM none, RANKED has
 *   no vote) and no leave penalty is added on top.
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
      LeaveReason.VOTE_KICK -> KozCharge.Koz2
      LeaveReason.EXPLICIT_LEAVE -> when (mode) {
        MatchMode.RANKED, MatchMode.UNRANKED -> KozCharge.Koz2
        MatchMode.ROOM -> KozCharge.Koz1
      }
      // ROOM = 1 Koz (Q37): "O-03d is closed by A8.3 / Q37" — the doc's
      // "Pointer, 2026-10-23" on the O-03d cell; a positive value, not zero.
      LeaveReason.FIFTEEN_TIMEOUT -> when (mode) {
        MatchMode.RANKED, MatchMode.UNRANKED -> KozCharge.Koz2
        MatchMode.ROOM -> KozCharge.Koz1
      }
    }
  }
}
