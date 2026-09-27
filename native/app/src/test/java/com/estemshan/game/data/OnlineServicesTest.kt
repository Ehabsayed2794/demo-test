package com.estemshan.game.data

import com.estemshan.services.CoroutineMatchScheduler
import com.estemshan.services.MatchScheduler
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S14: the wiring object's one observable contract — production gets the
 * deferred scheduler, not the test default.
 *
 * This test reads only [OnlineServices.scheduler]. Object initialization
 * happens on first member access, so this test running green on a plain JVM
 * (no Firebase, no Android Context) is also the proof that
 * [OnlineServices.auth] being `by lazy` holds: were it eager, touching
 * [OnlineServices] here would construct a FirebaseAuthBackend, read
 * FirebaseModule.auth, and throw before Firebase was ever initialized. Do NOT
 * add an assertion on [OnlineServices.auth] — that would cross exactly the
 * line the laziness exists to keep.
 */
class OnlineServicesTest {

  @Test
  fun schedulerIsDeferredNotTheTestImmediateDefault() {
    // Shipping MatchScheduler.Immediate would make every reconnect instant
    // instead of exponentially backing off: the test default exists for
    // determinism, not for production.
    assertTrue(OnlineServices.scheduler is CoroutineMatchScheduler)
    assertNotSame(MatchScheduler.Immediate, OnlineServices.scheduler)
  }
}
