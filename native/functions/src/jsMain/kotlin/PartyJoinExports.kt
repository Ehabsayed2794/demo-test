// Deliberately no package declaration: @JsExport builds the export name from
// the package chain, and the Firebase CLI expects the flat `joinRankDownRoom`
// member. See Functions.kt for the same convention.

import com.estemshan.functions.AdminPartyJoinDb
import com.estemshan.functions.joinOnce
import com.estemshan.functions.onCall
import com.estemshan.functions.toPlain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise

/** Module scope for callable Promise bridging (handlers return, never await). */
private val PartyJoinScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * S70 (RD29/RD30) — the Rank-down party join gate.
 *
 * The caller sends `{ roomId }` and nothing else: the seat is derived from the
 * VERIFIED callable uid, so a request cannot name a player at all and there is
 * nothing to spoof. The response is either `{ joined: true, room }` with the
 * room's public projection, or `{ joined: false, reason, message }` with one of
 * the two eligibility denials — never a tier, division, RP value, or another
 * player's uid. See `PartyJoin.kt` for the transaction order and for the rules
 * bypass this callable does NOT close.
 */
@JsExport
@JsName("joinRankDownRoom")
val joinRankDownRoom = onCall { request ->
  PartyJoinScope.promise {
    toPlain(joinOnce(AdminPartyJoinDb(), request))
  }
}
