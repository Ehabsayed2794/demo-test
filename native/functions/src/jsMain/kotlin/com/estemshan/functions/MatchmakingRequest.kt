package com.estemshan.functions

import com.estemshan.engine.GameType

/**
 * The stable `details.reason` tokens this guard refuses with.
 *
 * S56 hosts the guard behind `enqueueSearch`, and its own two callables carry
 * the same tokens — so the client switches on ONE vocabulary across the whole
 * queue, instead of a code plus a message it has to re-derive per callable.
 */
internal object RequestReason {
  const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
  const val FORBIDDEN_KEY = "FORBIDDEN_KEY"
}

/**
 * S57 — the Ranked search-request guard (RD9).
 *
 * **What this is.** A Ranked solo search request, parsed and validated at the
 * callable boundary, *before* anything is queued. RD9 makes the eligible tier
 * pool **server-derived**: the client may ask to be matched, and it may say
 * which GameType it wants to play (GM5 — the GameType rides along and is
 * orthogonal to the pool; the pool rule never widens or narrows for Mini).
 * It may never name a tier, a division, a rank, an RP value, a pool, or an
 * identity.
 *
 * Those keys are not *ignored* — they are refused with `permission-denied`.
 * A silently-ignored key is a key a client can keep sending while believing
 * it works, and a "the request was accepted" answer next to a forged
 * `tier: "Gold"` is exactly the ambiguity an anti-abuse guard must not
 * leave open.
 *
 * **What this is not.** This is **not** the pool authority: the eligible pool
 * is derived server-side by **S56** (the pool resolver and the queue). This
 * file decides only *what the client is allowed to say*; it decides nothing
 * about who is matched with whom, and it owns no queue, no Firestore document
 * and no callable. It is the **pre-queue guard** in front of S56's pool
 * derivation.
 *
 * **Identity.** [RankedSearchRequest] carries no uid and no tier: the caller
 * is whoever the callable runtime authenticated, and the identity is read at
 * the callable boundary with `CallableAuth.requireUid(request)` — the
 * Firebase-verified uid, never a client-supplied one. The callable that will
 * host this guard is **not** part of S57; this is its pure, testable part.
 *
 * **Why the values are never echoed.** The error messages name the offending
 * *key* and never the offending *value*: a client cannot use the error
 * channel to read data back, and a server log line never carries a payload.
 *
 * @see S57 — "Matchmaking anti-abuse: no client tier selection, no opt-up,
 *   pool authority (RD9)" in `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md`.
 */
data class RankedSearchRequest(val gameType: GameType)

/** The one field a Ranked search request may carry (GM5). */
private const val FIELD_GAME_TYPE = "gameType"

/**
 * The identity/tier keys a Ranked search may never carry (RD9).
 *
 * `uid`/`playerId` are the caller's identity, `tier`/`division`/`rank`/`rp`
 * are the caller's standing, and `pool`/`minTier`/`maxTier`/`optUp`/
 * `tierClaim`/`rankClaim` are attempts to *choose* the pool the server
 * derives — an opt-up request in every one of them. All of them are refused.
 *
 * The comparison is on the exact key name. A differently-cased spelling
 * ("Tier", "UID") is not in this set, so it is refused one step later as an
 * unknown field — still refused, never accepted, which is the property that
 * matters; only the code the client sees differs.
 *
 * `internal`, not `private`: S56's `pollSearch` and `cancelSearch` carry no
 * field at all, and they refuse the same identity/tier keys with the same
 * code — one list, so the two callables cannot drift apart.
 */
internal val FORBIDDEN_REQUEST_KEYS: Set<String> = setOf(
  "uid",
  "playerId",
  "tier",
  "division",
  "rank",
  "rp",
  "pool",
  "minTier",
  "maxTier",
  "optUp",
  "tierClaim",
  "rankClaim",
)

/**
 * The one message every malformed `gameType` gets — a missing field, a null,
 * a non-string, a lowercase spelling, or an unrecognised name. The raw value
 * is never echoed (see the file KDoc).
 */
private const val GAME_TYPE_MESSAGE =
  "rankedSearch: 'gameType' must be the string \"FULL\" or \"MINI\"."

/**
 * Parses and validates a Ranked search request.
 *
 * The runtime hands a callable a **plain JS object**, never a Kotlin map
 * (S61's lesson — see `CallablePayloadBridgingTest`), so the boundary
 * converter [dynamicToKotlin] runs first and the result must be a map:
 * a missing payload, a null, a string, a number, a boolean and an array are
 * all `invalid-argument`.
 *
 * **The order of the checks is the contract.** The keys are validated
 * *before* the `gameType` value is read, so a request that carries a
 * forbidden identity/tier key, or any field this callable does not know, is
 * refused on the key — it is never silently ignored, and a forged tier claim
 * is never traded for a "your gameType is wrong" message that would let the
 * client believe the tier part was merely malformed.
 *
 * Refusal order among the keys themselves: a forbidden key first
 * (`permission-denied` — an abuse attempt, not a typo), then an unknown key
 * (`invalid-argument`), so the outcome does not depend on the order the
 * client happened to serialise its keys in.
 *
 * @param data the raw `request.data` of a callable, in any JS shape.
 * @return the validated request — the requested [GameType] and nothing else.
 * @throws HttpsError `permission-denied` for an identity/tier key,
 *   `invalid-argument` for every other malformed request.
 */
fun parseRankedSearchRequest(data: Any?): RankedSearchRequest {
  // The boundary conversion. A plain JS object becomes a Kotlin map here; a
  // JS array becomes a list, a string stays a string — so the map check below
  // rejects every non-object shape in one place.
  val fields = dynamicToKotlin(data) as? Map<*, *>
    ?: throw refusal(
      "invalid-argument",
      RequestReason.INVALID_ARGUMENT,
      "rankedSearch: request data must be an object carrying a 'gameType' field.",
    )

  val names = fields.keys.map { key ->
    key as? String
      ?: throw refusal(
        "invalid-argument",
        RequestReason.INVALID_ARGUMENT,
        "rankedSearch: request field names must be strings.",
      )
  }

  // 1. Forbidden identity/tier keys (RD9). The message names the key and
  //    never the value. The wording is the client-facing contract text, so
  //    it carries no prefix of its own.
  for (name in names) {
    if (name in FORBIDDEN_REQUEST_KEYS) {
      throw refusal(
        "permission-denied",
        RequestReason.FORBIDDEN_KEY,
        "identity and tier are server-derived; '$name' is not accepted.",
      )
    }
  }

  // 2. Unknown keys. Refused rather than ignored, so a renamed tier claim
  //    cannot slip through as "a field we do not know yet".
  for (name in names) {
    if (name != FIELD_GAME_TYPE) {
      throw refusal(
        "invalid-argument",
        RequestReason.INVALID_ARGUMENT,
        "rankedSearch: unknown field '$name'.",
      )
    }
  }

  // 3. Only now is the value read.
  if (!fields.containsKey(FIELD_GAME_TYPE)) {
    throw refusal(
      "invalid-argument",
      RequestReason.INVALID_ARGUMENT,
      "rankedSearch: 'gameType' is required.",
    )
  }
  val raw = fields[FIELD_GAME_TYPE]
  if (raw !is String) {
    throw refusal("invalid-argument", RequestReason.INVALID_ARGUMENT, GAME_TYPE_MESSAGE)
  }
  // Exactly the enum names: "FULL" or "MINI". A lowercase spelling is a
  // different string, not a forgiving alias.
  val gameType = GameType.values().firstOrNull { it.name == raw }
    ?: throw refusal("invalid-argument", RequestReason.INVALID_ARGUMENT, GAME_TYPE_MESSAGE)

  return RankedSearchRequest(gameType)
}

/**
 * A refusal with a stable reason.
 *
 * `details` is a PLAIN object: `JSON.stringify` of a Kotlin map yields `{}`,
 * so an unconverted map would reach the client as an empty object and the
 * reason would be lost exactly where the client needs it.
 */
private fun refusal(code: String, reason: String, message: String): HttpsError = HttpsError(
  code,
  message,
  toPlain(linkedMapOf<String, Any?>("reason" to reason)),
)
