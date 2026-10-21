// Deliberately no package declaration: @JsExport builds the export name from
// the package chain, and the Firebase CLI expects the flat `getMyRankedProfile`
// member. See Functions.kt for the same convention.

import com.estemshan.functions.AdminRankedProfileDb
import com.estemshan.functions.loadOnce
import com.estemshan.functions.onCall
import com.estemshan.functions.toPlain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise

/** Module scope for callable Promise bridging (handlers return, never await). */
private val RankedProfileScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * S44/S51 — the authenticated read of the caller's own Ranked profile.
 *
 * The uid comes only from the verified callable identity; the handler never
 * accepts a uid from the request. The response is the profile contract plus
 * the nine career statistics, and `migrated` says whether this call lazily
 * initialised missing Ranked fields inside its transaction.
 */
@JsExport
@JsName("getMyRankedProfile")
val getMyRankedProfile = onCall { request ->
  RankedProfileScope.promise {
    toPlain(loadOnce(AdminRankedProfileDb(), request))
  }
}
