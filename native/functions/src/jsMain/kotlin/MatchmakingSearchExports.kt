// Deliberately no package declaration: @JsExport builds the export name from
// the package chain, and the Firebase CLI expects the flat `enqueueSearch`
// member. See Functions.kt for the same convention.

import com.estemshan.functions.AdminMatchmakingSearchDb
import com.estemshan.functions.DecisionClock
import com.estemshan.functions.cancelSearchOnce
import com.estemshan.functions.enqueueSearchOnce
import com.estemshan.functions.onCall
import com.estemshan.functions.pollSearchOnce
import com.estemshan.functions.toPlain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise

/** Module scope for callable Promise bridging (handlers return, never await). */
private val MatchmakingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * The Functions runtime's own clock.
 *
 * The queue's ordering key and its lease are both server instants, so the
 * clock is the server's — never a client's estimate of when it searched.
 */
private val MatchmakingClock = DecisionClock { (js("Date.now()") as Double).toLong() }

/**
 * S56 — the Ranked queue's three callables.
 *
 * `enqueueSearch` asks the server to find a table, `pollSearch` reports the
 * caller's own state and keeps the search alive, and `cancelSearch` leaves the
 * queue. None of them accepts a tier, a division, an RP value, a pool or an
 * identity: the tier comes from the caller's own profile inside the server's
 * transaction, and a client that tries to name one is refused rather than
 * ignored. See `MatchmakingSearch.kt` for the queue document, the resolver
 * pass and the answers.
 */
@JsExport
@JsName("enqueueSearch")
val enqueueSearch = onCall { request ->
  MatchmakingScope.promise {
    toPlain(enqueueSearchOnce(AdminMatchmakingSearchDb(), request, MatchmakingClock))
  }
}

/**
 * The bounded poll: the caller's own state, and the queue's heartbeat.
 *
 * The response names the caller's own table once the search resolved, and
 * never another player's identity, tier or search.
 */
@JsExport
@JsName("pollSearch")
val pollSearch = onCall { request ->
  MatchmakingScope.promise {
    toPlain(pollSearchOnce(AdminMatchmakingSearchDb(), request, MatchmakingClock))
  }
}

/**
 * The cancellation: idempotent, and it cannot un-resolve a search whose table
 * already formed.
 */
@JsExport
@JsName("cancelSearch")
val cancelSearch = onCall { request ->
  MatchmakingScope.promise {
    toPlain(cancelSearchOnce(AdminMatchmakingSearchDb(), request))
  }
}
