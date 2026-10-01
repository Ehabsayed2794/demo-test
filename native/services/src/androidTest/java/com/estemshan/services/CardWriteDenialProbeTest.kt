package com.estemshan.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estemshan.services.model.SEAT_IDS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEMPORARY diagnostic for the deterministic `submitCard` rules denial on
 * the Firestore emulator (`evaluation error at L1536:24 for 'update'`).
 *
 * v2: calls the REAL submitCard() and then replays the opening-turn
 * publish half on its own — once with the submitter's own uid (what
 * MatchService actually writes) and once with a different seat's uid, so
 * `turn` genuinely changes. The contrast isolates whether the dispatch's
 * `('turn' in affectedKeys())` routing is what misroutes the publish.
 * Fails with the whole ladder verbatim in the downloaded test report.
 * Delete this file (and the probe functions in ScriptedMatch) once the
 * denial is fixed.
 */
@RunWith(AndroidJUnit4::class)
class CardWriteDenialProbeTest {

  @Before
  fun wipeEmulators() {
    EmulatorSuite.reset()
  }

  @Test
  fun probe_cardWriteShapes() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val seating = SEAT_IDS.associateWith { EmulatorSuite.client(context, it) }
    val match = ScriptedMatch(seating, seed = 4242L)
    match.start()
    fail(match.probeSubmitCardHalves())
  }
}
