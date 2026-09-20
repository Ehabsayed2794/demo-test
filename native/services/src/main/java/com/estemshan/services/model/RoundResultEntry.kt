package com.estemshan.services.model

import com.estemshan.engine.ExtensionReason

/**
 * One persisted round result — the record scoring-engine.js's
 * applyRoundResult() writes via GameSession.recordRoundResult(). The
 * fields the completion path re-reads are the ones kept here.
 */
data class RoundResultEntry(
  val round: Int,
  val trump: String?,
  val callerId: String?,
  val tricksWon: Map<String, Int>,
  val estimates: Map<String, Int>,
  val scoreDeltas: Map<String, Int>,
  val riskPlayerId: String?,
  val totalBids: Int,
  val isOver: Boolean,
  val isSaayda: Boolean,
  val appliedMultiplier: Int,
  val nextMultiplier: Int,
  val extensionReason: ExtensionReason? = null,
)
