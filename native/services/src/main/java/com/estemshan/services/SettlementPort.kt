package com.estemshan.services

/**
 * The settlement seam over the `settleMatch` callable (RD12/RD13).
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   UI → SettlementPort → (Cloud Functions settler) → Firestore
 *
 * WHY THE SEAM — the same reason every other service boundary in this module
 * is an interface: this repo ships no [com.google.firebase.firestore] fake by
 * design, and `FirebaseFunctions` is no different. The ranked reveal in
 * `OnlineMatchViewModel` stands in for this interface in a hermetic JVM test.
 * No suite drives the real callable yet — no settlement emulator run, no
 * instrumented device run — so the JVM mapping test is the wire contract's
 * only pin until the settlement server tier ships. The UI never touches the
 * SDK.
 *
 * WHY A CALLABLE AT ALL — RD13: a client cannot read another player's rank
 * (players/{uid} is owner-read-only), so it cannot compute its own RP delta
 * truthfully either. The award formula, the floor clamp and the once-only
 * claim all live server-side; the movement arrives in the callable's
 * response as `you` — the CALLING player's own movement and nothing else —
 * and this port is how a client whose match document just went complete
 * asks for it. The client reads the two numbers and computes nothing behind
 * their back; whatever the screen renders from them runs through the
 * standings' existing helpers (RD13).
 *
 * ONCE-ONLY — the server's settlement record is the single authority: a
 * first execution settles and records, a replay returns the recorded
 * outcome, and any number of racers converge on the same answer. Nothing
 * here debounces a second CALLER — only a second call from this client,
 * which is the view model's discipline, not this interface's.
 */
interface SettlementPort {

  /**
   * Asks the settler to settle [matchId] authoritatively. Idempotent: the
   * settler re-derives everything from the committed document and its
   * roundArchive inside one transaction, so a raced, retried, or replayed
   * call returns the recorded outcome rather than settling twice. Safe to
   * call repeatedly.
   */
  suspend fun settle(matchId: String): SettlementOutcome

  /**
   * The outcomes, split by what the caller should do next.
   */
  sealed interface SettlementOutcome {

    /**
     * The match settled — a first execution, or a replay of the recorded
     * one. [replayed] is the server's own replay marker, true when this
     * answer is the recorded outcome rather than a fresh settlement.
     * [you] is the CALLING player's own RP movement; it may be ABSENT —
     * forward-looking wire: no shipped server build carries it yet, the
     * field is the server half's RP-application story to add — in which
     * case there is nothing to reveal and the plain standings stand.
     * Never a zero, never a client-side guess (RD13).
     */
    data class Settled(val replayed: Boolean, val you: RpMovement?) : SettlementOutcome

    /**
     * The match is not RANKED — ROOM/UNRANKED never settle (RD28). Terminal:
     * no retry can change the mode, and there is nothing to reveal. [mode]
     * is the server's own name for the match's mode, kept for the log.
     */
    data class NotRanked(val mode: String) : SettlementOutcome

    /**
     * A permanent denial — the caller is not a player, the match is gone,
     * the history failed closed, or the response was a shape this build
     * cannot read as either direction. Retrying cannot change the outcome.
     */
    data object Denied : SettlementOutcome

    /**
     * A transient failure — the callable threw a retryable transport error,
     * or answered with a body this layer cannot read at all. The caller
     * backs off and may try again, up to its bound; the server's own
     * idempotency makes the fresh look safe.
     */
    data object Retryable : SettlementOutcome
  }
}

/**
 * The calling player's own ranked movement — whatever of the response's
 * `you` the reveal acts on: the RP before, and the signed delta off it.
 * Server-authoritative end to end: the client adjusts neither number,
 * whatever it goes on to do with them on screen (RD13).
 *
 * The wire's raw `rp` is deliberately dropped rather than parsed and never
 * read. By the contract rp − previousRp = delta, `rp` is the pair's own
 * arithmetic restatement — a dead field at best, and at worst a second
 * number for the reveal to disagree with itself over. The value shown
 * derives through the standings' existing helpers from the pair that
 * governs; the reveal trusts the pair, because nothing pinned by a shipped
 * server build exists to arbitrate against.
 *
 * Forward-looking wire: no shipped server build emits `you` yet — the field
 * is the server half's RP-application story. A response without it degrades
 * to plain standings, not zeroes.
 */
data class RpMovement(val previousRp: Int, val delta: Int)
