package com.estemshan.functions

/**
 * Fail-closed storage for the Worker build.
 *
 * The O-06 decision (2026-10-30) puts the authority layer's state in a
 * Durable Object per match (SQLite) plus D1 for the ledger / backup /
 * outbox. That layer does not exist yet, so every storage touch in this
 * build throws — an authenticated caller gets `unimplemented`, never a
 * fabricated read, a phantom "already settled" replay, or a silently
 * dropped decision.
 *
 * Deliberately NOT an in-memory fake: isolates don't share memory, so a
 * fake would break exactly-once and the decision anchor — the two
 * properties the settlement contract exists for — while looking healthy in
 * a single-request smoke. Failing closed is the honest behaviour until the
 * DO/D1 adapter lands; when it does, it replaces this file's wiring in
 * WorkerApi.kt, never in the orchestration.
 */
private fun storageUnavailable(): AuthorityError = AuthorityError(
  "unimplemented",
  "The Cloudflare storage layer (Durable Object + D1, O-06) is not wired in this build; " +
    "the callable fails closed.",
)

object UnwiredSettlementDb : SettlementDb {
  override suspend fun readMatch(matchId: String): Map<String, Any?>? =
    throw storageUnavailable()

  override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> =
    throw storageUnavailable()

  override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T =
    throw storageUnavailable()
}

object UnwiredTimeoutDb : TimeoutDb {
  override suspend fun readMatch(matchId: String): Map<String, Any?>? =
    throw storageUnavailable()

  override suspend fun readHands(
    matchId: String,
    round: Int,
    seats: List<String>,
  ): Map<String, List<com.estemshan.engine.Card>> =
    throw storageUnavailable()

  override suspend fun <T> transact(block: suspend (TimeoutDb.TimeoutTx) -> T): T =
    throw storageUnavailable()
}
