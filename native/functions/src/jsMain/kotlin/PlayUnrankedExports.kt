// Deliberately no package declaration: @JsExport builds the export name from
// the package chain, and the Firebase CLI expects the flat `playUnranked`
// member. See Functions.kt for the same convention.

import com.estemshan.functions.AdminPlayUnrankedDb
import com.estemshan.functions.onCall
import com.estemshan.functions.playUnrankedOnce
import com.estemshan.functions.toPlain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise

/** Module scope for callable Promise bridging (handlers return, never await). */
private val PlayUnrankedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * S71 — the host-only "Play Unranked" conversion.
 *
 * The caller sends `{ roomId }` and nothing else: the host is derived from
 * the VERIFIED callable uid, so a request cannot name a host at all, and it
 * cannot name a mode either — the mode is the server's decision, made here.
 *
 * The response is `{ changed, alreadyUnranked, room }` with the room's public
 * projection. A second call on an already-converted room writes nothing and
 * answers `changed: false`.
 */
@JsExport
@JsName("playUnranked")
val playUnranked = onCall { request ->
  PlayUnrankedScope.promise {
    toPlain(playUnrankedOnce(AdminPlayUnrankedDb(), request))
  }
}
