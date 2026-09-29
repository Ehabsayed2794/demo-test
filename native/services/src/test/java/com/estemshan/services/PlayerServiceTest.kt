package com.estemshan.services

import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S19 — the heartbeat's write, pinned against the injected merge seam. This
 * repo ships no [com.google.firebase.firestore.FirebaseFirestore] fake by
 * design and a JVM test cannot build one without an initialized Firebase, so
 * the seam IS the hermetic boundary — and asserting on the payload it
 * receives is the whole proof of the contract.
 *
 * THE CONTRACT — writing lastSeenAt must not clobber the profile's other
 * whitelisted fields (displayName, avatarInitial, currentRoomId,
 * currentMatchId). It is proven on the payload: the heartbeat's merge carries
 * exactly {lastSeenAt}, so there is nothing IN the write that could touch any
 * other field, and the merge itself keeps even a multi-field write from
 * erasing the doc.
 */
class PlayerServiceTest {

  private val writes = mutableListOf<Pair<String, Map<String, Any?>>>()

  private val service = PlayerService(
    profileRef = {
      // The read path is not under test here; nothing reaches it.
      error("PlayerServiceTest exercises the write seam only")
    },
    mergeOwnProfile = { uid, fields -> writes += uid to fields },
  )

  @Test
  fun heartbeat_writesOnlyLastSeenAt() = runTest {
    service.markActive("uid-a")

    val (uid, fields) = writes.single()
    assertEquals("one write, for the player's own uid", "uid-a", uid)
    assertEquals(
      "the heartbeat touches lastSeenAt and nothing else — there is nothing " +
        "in the write that could clobber the profile's other fields",
      setOf("lastSeenAt"), fields.keys,
    )
    val stamped = fields.getValue("lastSeenAt")
    assertNotNull("lastSeenAt is always written", stamped)
    assertTrue("lastSeenAt is a server timestamp, never a client clock",
      stamped is FieldValue,
    )
  }

  @Test
  fun heartbeat_anEmptyUidWritesNothing() = runTest {
    service.markActive("")
    assertEquals("an empty uid is a no-op", 0, writes.size)
  }

  @Test
  fun heartbeat_neverThrows() = runTest {
    // A failed tick must not fail the match — the write is best-effort by
    // contract, so the exception dies at the boundary.
    val offline = PlayerService(
      profileRef = { error("unused") },
      mergeOwnProfile = { _, _ -> error("offline") },
    )
    offline.markActive("uid-a")
  }

  @Test
  fun currentMatchIdMirror_writesTheTwoLifecycleFields_andNoMore() = runTest {
    service.setCurrentMatchId("uid-a", "match-1")

    val fields = writes.single().second
    assertEquals("the mirror writes the two lifecycle fields and nothing else",
      setOf("currentMatchId", "lastSeenAt"), fields.keys,
    )
    assertEquals("match-1", fields.getValue("currentMatchId"))
  }

  @Test
  fun currentMatchIdMirror_neverThrows() = runTest {
    val offline = PlayerService(
      profileRef = { error("unused") },
      mergeOwnProfile = { _, _ -> error("offline") },
    )
    offline.setCurrentMatchId("uid-a", null)
  }
}
