package com.estemshan.services.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ROOM start gate, pinned as predicates — the invite-only Room row in
 * `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` ("four ready players required to
 * start", owner decision 2026-10-26) and the T-17 room-size acceptance it
 * cites.
 *
 * WHAT WAS WRONG. [RoomDoc.allReady] answers "everyone PRESENT is ready" —
 * a 2-player room where both seats pressed Ready was all-ready, and both
 * start paths ([RoomService.maybeStartMatch] via setReady and
 * [MatchService.startMatch]) accepted that as a start. A ROOM match now
 * starts only through [RoomDoc.canStart]: FULL (`players.size >=
 * MAX_PLAYERS`) **and** all-ready. The two server call sites gate on
 * `canStart`; [RoomDoc.allReady] keeps its exact old meaning, because the
 * room screen reads it as a cosmetic hint (native/app RoomScreen.kt:125)
 * and RoomViewModelTest's fake keys its start attempt on it — a 2-player
 * all-ready room still shows the hint (deliberately; the hint's own gap is
 * a UI follow-up, not this predicate's business).
 *
 * THE OVER-FULL ROW. [RoomDoc.isFull] is `>=` [MAX_PLAYERS], not `==`, so a
 * legacy 5-player document (written before the cap was enforced) is still
 * "full" and a fully-ready one still satisfies the gate. The predicate
 * mirrors isFull's own semantics on purpose: canStart is isFull && allReady,
 * never an independent second definition of "full".
 *
 * STATUS IS NOT HERE. canStart says nothing about the room's status —
 * the STATUS_WAITING gate is the caller's (RoomService.maybeStartMatch),
 * unchanged by this predicate.
 */
class RoomDocTest {

  private companion object {
    /** p1..pN: N distinct seated players, in seat order. */
    fun players(n: Int): List<String> = (1..n).map { "p$it" }
  }

  /** A WAITING room with the given seating and ready set. */
  private fun room(players: List<String>, ready: List<String>): RoomDoc = RoomDoc(
    name = "gate-room",
    status = RoomDoc.STATUS_WAITING,
    creator = players.firstOrNull() ?: "creator",
    players = players,
    readyPlayers = ready,
    matchId = null,
  )

  // ── the gate: a non-full room never starts, however ready it is ─────

  @Test
  fun aNonFullAllReadyRoomIsAllReadyButCanNeverStart() {
    // 0–3 seated players, every one of them ready: isFull is false and
    // canStart is false at every size — the E4.4 hole this closes (a
    // 2-player all-ready room used to pass both start paths).
    for (n in 0..3) {
      val seated = players(n)
      val room = room(seated, seated)
      assertEquals("isFull at $n players", false, room.isFull)
      assertEquals("canStart at $n players", false, room.canStart)
      // allReady is UNCHANGED, including its non-empty guard: a 0-player
      // room is not all-ready (players.isNotEmpty()), 1–3 all-ready seats
      // are. The task table's "0 players → allReady == true" row cannot
      // hold without changing allReady, which this task refuses to do —
      // the pin here is the unchanged predicate, exactly as the app's hint
      // reads it today.
      assertEquals("allReady at $n players", n > 0, room.allReady)
    }
  }

  @Test
  fun theThreePlayerAllReadyHintIsUnchanged() {
    // The room screen's "starting" hint keys on allReady; a 3-player
    // all-ready room must keep reading all-ready even though it can no
    // longer start. This is the one behavior the app depends on.
    val seated = players(3)
    val room = room(seated, seated)
    assertTrue("3 ready players are all-ready (hint unchanged)", room.allReady)
    assertFalse("3 ready players still cannot start", room.canStart)
  }

  // ── the gate opens: exactly a full, fully-ready table ────────────────

  @Test
  fun aFullAllReadyTableCanStart() {
    val seated = players(4)
    val room = room(seated, seated)
    assertTrue("4 players is a full table", room.isFull)
    assertTrue("4 ready players are all-ready", room.allReady)
    assertTrue("4 ready players at a full table can start", room.canStart)
  }

  @Test
  fun aFullTableWithASeatedPlayerNotReadyCannotStart() {
    val seated = players(4)
    val room = room(seated, ready = seated.take(3))
    assertTrue("4 players is a full table", room.isFull)
    assertFalse("a seat not ready means not all-ready (unchanged)", room.allReady)
    assertFalse("a full table with an unready seat cannot start", room.canStart)
  }

  @Test
  fun aFullTableWithNoOneReadyCannotStart() {
    val seated = players(4)
    val room = room(seated, ready = emptyList())
    assertTrue("4 players is a full table", room.isFull)
    assertFalse("no ready players means not all-ready (unchanged)", room.allReady)
    assertFalse("a full table with no one ready cannot start", room.canStart)
  }

  // ── the legacy over-full document: full means >=, never == ───────────

  @Test
  fun aLegacyOverFullAllReadyRoomCanStart() {
    // A document written before the cap was enforced can carry 5 players.
    // isFull is >= MAX_PLAYERS, so the room is still "full", and canStart
    // mirrors isFull's own semantics rather than inventing a second
    // definition of fullness: an all-ready over-full table starts.
    val seated = players(5)
    val room = room(seated, seated)
    assertTrue("5 players is still a full table (isFull is >=)", room.isFull)
    assertTrue("5 ready players are all-ready", room.allReady)
    assertTrue("a legacy over-full all-ready room can start", room.canStart)
  }

  // ── status is the caller's gate, not the predicate's ─────────────────

  @Test
  fun canStartReadsOnlySeatingAndReadinessNeverStatus() {
    // canStart is status-blind by design: the STATUS_WAITING gate lives in
    // RoomService.maybeStartMatch, and this predicate must not duplicate
    // it. An in_game room that is full and all-ready still computes
    // canStart == true; the caller's status check is what stops it.
    val seated = players(4)
    val room = room(seated, seated).copy(status = RoomDoc.STATUS_IN_GAME)
    assertEquals(RoomDoc.STATUS_IN_GAME, room.status)
    assertTrue("canStart stays a seating+readiness answer", room.canStart)
  }
}
