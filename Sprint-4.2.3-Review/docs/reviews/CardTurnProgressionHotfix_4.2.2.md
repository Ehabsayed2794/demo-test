> **UPDATE — Sprint 4.2.3 (Firestore Rules Compile-Safe Card Turn Hotfix):** a direct review of this sprint's shipped `firestore.rules` found that Task 6's own turn-validation expression, `oldData.seats.keys().exists(s, oldData.seats[s] == newData.turn)`, used `.exists()` — a List method that is not part of Firestore Rules' officially documented List method surface. This project's own JS rules simulation (`tests/rules-simulation.test.js`) could never have caught this, because it re-implements intended LOGIC in plain JavaScript rather than compiling real CEL — it proves logical intent, not compile validity. **Fixed** — see `docs/reviews/CardCompileSafeTurnHotfix_4.2.3.md` for the full account, and `docs/architecture/SecurityArchitecture.md`'s "Compile-safe Rules syntax" section for the broader audit this finding triggered (three other `.all()` occurrences elsewhere in `firestore.rules`, unrelated to this sprint's own Task 6 change, were found and fixed the same way). This document is left otherwise unedited, as the historical record of Sprint 4.2.2's own original (now-superseded) state.

# Implementation Report — Sprint 4.2.2: Atomic Card Turn Progression & Card-Log Desync Hardening

**A hotfix, not a feature sprint.** A direct review of Sprint 4.2.1's shipped code found three remaining correctness defects, all closed this sprint. NOT Trick Resolution, NOT Trick Winner Persistence, NOT Scoring, NOT Next Round, NOT a UI redesign, NOT gameplay rule duplication, NOT a `table-engine.js` rewrite, NOT Cloud Functions. Spark only.

## 1. The three defects, restated precisely

1. **`submitCard()` appended the card but never updated `matches/{matchId}.turn`.** The next player's own submission was rejected with `NOT_YOUR_TURN`, because Firestore still named the PREVIOUS player as the active turn. Sprint 4.2.1's own tests hid this by manually calling a test-only `setTurn()`/`syncTurnFieldToRealEngine()` helper between submissions — no equivalent production write existed.
2. **`applyRemoteCard()` silently skipped a `MALFORMED_ENTRY` item and kept processing**, later advancing the processed count/version registries past it — exactly the "silent skip, not desync" bug already fixed for `ENGINE_REJECTED` in Sprint 4.2.1, but never closed for this second malformed-data path.
3. **`ALREADY_APPLIED_LOCALLY` checked only `seatId`, not card identity.** A different card from the same seat could be silently treated as an identical local echo and dropped.

## 2. Task 1 — Engine-Owned Next-Turn Preview

**Question:** does `table-engine.js` support answering "is this card legal, and if so what happens next" without mutating state?

**Finding: YES**, via a new, purely additive `previewPlay(playerId, card)` composing the ALREADY-existing `canPlayCard()` (Sprint 4.2.1) plus the exact `state.plays.length`/`nextCCW()` arithmetic `emit()` already performs internally — zero new rules, zero duplicated follow-suit logic, zero mutation, never calls `emit()`. Returns `{legal:false, reason}` or `{legal:true, nextTurnSeat, nextPhase}`, with `nextTurnSeat: null, nextPhase: "RESOLVING"` on the 4th card of a trick. Exported via one new line in the existing `window.TableEngine = {...}` object — the same "minimum wiring export" precedent used for `resolveTrick`/`getState` (Sprint 3.6) and `canPlayCard` (Sprint 4.2.1).

**Verified directly**: a manual smoke test drove a real trick to completion and confirmed `previewPlay()`'s answer exactly matches `emit()`'s own subsequent behavior at every one of the 4 plays, including the RESOLVING/null boundary.

## 3. Task 2 — Atomically Persist Card + Next Turn

`submitCard()` now calls `TableEngine.previewPlay()` BEFORE the transaction, resolves `preview.nextTurnSeat` to a real UID via `MatchAdapter.seatToUid()` (or leaves it `null` when `preview.nextPhase === "RESOLVING"`), and writes `cardLog`, `lastCardSeat`, `turn`, `cardPhase`, `version+1`, and `updatedAt` in the SAME `tx.update()` call — never a parallel or follow-up write. `cardPhase` (`"PLAY"` / `"RESOLVING"` / `null`) is the one minimal new schema field this task required — no existing field (`biddingOpen` is bidding-specific, `gameState` is an untouched placeholder) could safely double for it.

**Verified directly**: `tests/submit-card.test.cjs`'s full p1→p2→p3→p4→resolving sequence proves four sequential seats can each submit exactly once, with Firestore's own `turn` field advancing automatically between submissions — with ZERO manual/test-only turn mutation anywhere in the sequence.

## 4. Task 3 — Transaction Revalidation (STALE_GAME_STATE)

The engine preview is computed from a pre-check read, at the same moment an `expectedVersion` fingerprint is captured (closure-scoped, never recomputed). Inside the transaction, on every invocation (including any automatic Firestore SDK retry), `freshMatch.version` is re-checked against that SAME `expectedVersion` before any of the other Task-1-established re-checks (seat ownership, turn ownership) run. If the version has moved, the transaction throws `STALE_GAME_STATE` and writes nothing — the client is expected to re-fetch and retry manually, never automatically, per the brief's explicit "do not automatically retry a gameplay action against changed engine state."

**Why this is stricter than `submitBid()`'s own optimistic-retry pattern**: `previewPlay()`'s answer depends on LOCAL browser engine state, which Firestore's own transaction retry mechanism has no way to recompute on its own. Re-checking `expectedVersion` (captured before the transaction, never updated inside it) on every retry is what prevents a stale, browser-side preview from silently becoming "valid" against a Firestore document that has since changed underneath it.

**Verified directly**: a smoke test intercepted the FIRST invocation of the (fake) transaction callback to bump the store's version before `fn(tx)` ran, deterministically simulating "the document changed between the pre-check read and the transaction opening" — confirmed `STALE_GAME_STATE` was thrown and zero writes occurred.

## 5. Task 4 — MALFORMED_ENTRY Must Be Desync

`applyRemoteCard()`'s entry loop now treats a malformed cardLog entry EXACTLY like `ENGINE_REJECTED` (Sprint 4.2.1's own precedent): stops immediately, does not process any later entry in that delivery, advances `lastAppliedCardCountByMatch` only up to (never past) the malformed index, does NOT advance `lastAppliedCardVersionByMatch` at all, and returns a structured `{applied:false, desync:true, reason:"MALFORMED_ENTRY", matchId, index}`.

**Verified directly**: `tests/match-adapter.test.cjs`'s new checks construct a delivery where a malformed entry sits before at least one well-formed later entry, and prove the later entry is never processed and the registries are stuck at the malformed index — not the old (Sprint 4.2 / early 4.2.1) skip-and-continue behavior.

## 6. Task 5 — Local Echo Content Verification

The local-echo check no longer stops at "does this seat already have a play in the current trick" — it now finds that play and compares its exact `suit` and `rank.v` against the remote entry's card. Same card → the existing, benign `ALREADY_APPLIED_LOCALLY` skip (registries advance past just this one index, later entries still processed normally). Different card from the same seat → a NEW `LOCAL_ECHO_MISMATCH` desync: stops immediately, does not process later entries, does not advance the version registry, returns `{applied:false, desync:true, reason:"LOCAL_ECHO_MISMATCH", matchId, index, seatId, localCard, remoteCard}`.

**Verified directly**: `tests/match-adapter.test.cjs`'s new checks prove a genuine echo (same seat, same card) resolves normally with the count registry advancing past it, while a mismatched echo (same seat, different card) produces the structured desync result with both cards' diagnostics, and any entry after the mismatch is never processed.

## 7. Task 6 — Firestore Rules

`isValidCardSubmission()` extended in place (matching this project's "UPDATE rules evolve in place, CREATE rules get versioned suffixes" convention) to additionally verify: the caller owns the PREVIOUS active turn (`oldData.turn == request.auth.uid`); the new `turn` is either `null` or a UID that is structurally one of `seats`' own values (`oldData.seats.keys().exists(s, oldData.seats[s] == newData.turn)` — CEL has no `.values()` method, so membership is expressed via `.keys().exists()` instead, a real but already-known-and-worked-around CEL gap, not a new one); `cardPhase` is one of `['PLAY', 'RESOLVING']`; `turn`/`cardPhase` are now part of the update's allowed-changed-keys allowlist and the field-presence guard. `isValidNewMatch()` was extended to a new versioned `isValidNewMatchV5` (create-time rules get versioned suffixes; `cardPhase: null` is required at creation).

**Honest, documented limitation, restated (not newly introduced) by this task**: rules can verify the new turn is STRUCTURALLY valid (a real seat UID or null) but CANNOT verify it is the CORRECT next seat per follow-suit/turn-order — that remains entirely client-authoritative in this Spark MVP, exactly as `table-engine.js`'s legality decisions have been since Sprint 4.2.1. No unsupported CEL was invented; the `.keys().exists()` workaround for map-value membership is a previously-established pattern in this project, not new.

## 8. Task 7 — Testing

All 14 required scenarios are covered:

- **`tests/submit-card.test.cjs`** (substantially rewritten): the full p1→p2→p3→p4→resolving production sequence with ZERO manual turn mutation (#1–#4, #13); wrong-turn zero-writes with `previewPlay()` never even called (#5); `STALE_GAME_STATE` via intercepting the transaction's first invocation to bump the store version mid-flight, proving no silent stale-preview reuse (#6, #7); atomic-write-shape verification (all fields in one `tx.update()` call).
- **`tests/match-adapter.test.cjs`**: `MALFORMED_ENTRY` stops processing and does not advance count/version, and entries after it are never processed (#8, #9); a genuine same-seat/same-card echo is safely ignored (#10); a same-seat/different-card echo produces `LOCAL_ECHO_MISMATCH` (#11), and entries after a mismatch are never processed (#12).
- **`tests/card-sync.test.cjs`**: rewritten to remove the forbidden test-only `syncTurnFieldToRealEngine()` helper entirely — `seedMockMatch()`'s ONE legitimate direct turn assignment is a one-time seed (reading the real engine's own live turn at setup, before any submission), never a between-submissions mutation. Every subsequent scenario (multiple sequential cards, duplicate snapshot, stale/rollback, local echo, late subscriber, listener restart, wrong-turn rejection, adapter corruption) now relies entirely on `submitCard()`'s own atomic write to advance `turn` — proving the production flow, not a test shortcut.
- **`tests/rules-simulation.test.js`**: `isValidCardSubmission()`'s translation extended with the same turn-ownership/new-turn-validity/`cardPhase`-enum checks as the real rule; new SECURITY tests for wrong-turn rejection, unknown-uid-as-next-turn rejection, invalid-`cardPhase` rejection, and the RESOLVING-boundary ALLOWED case; `isValidNewMatchV5` create-time tests added.
- **Full regression suite** (#14): re-run after every change, **889 automated tests total, all passing**, every check labeled MOCKED, SIMULATED, EMULATOR, or REAL — never mixed (no EMULATOR/REAL tests exist in this project; every test remains MOCKED against a hand-written fake Firestore, or SIMULATED against this project's own 1:1 CEL translation).

No test in `tests/submit-card.test.cjs` or `tests/card-sync.test.cjs` manually mutates `matches/{matchId}.turn` between two valid, sequential production `submitCard()` calls — the one remaining direct turn assignment in either file is `card-sync.test.cjs`'s `seedMockMatch()`, a one-time setup seed that runs strictly BEFORE the first submission of its scenario, never between two submissions.

## 9. Honest limitations / what remains

- `previewPlay()` is checked ONCE, pre-transaction, against the local browser's own `TableEngine` instance — not re-checked per Firestore retry. `STALE_GAME_STATE` correctly catches a Firestore-side version conflict (a concurrent WRITE), but a genuinely concurrent LOCAL engine mutation between the preview and the transaction's commit remains a narrow, inherent, documented residual race this Spark-only design cannot fully close without a Cloud Function serializing authority and persistence in one atomic server step.
- `firestore.rules` still cannot verify the new turn is the CORRECT next seat (only that it is structurally a real seat UID or null) — gameplay legality remains entirely client-authoritative in this Spark MVP, unchanged in kind from every prior sprint's own honest framing.
- `cardLog` prefix/order integrity is still NOT provable by `firestore.rules` alone (Sprint 4.2.1's Task 4 finding) — untouched, not re-assessed, this sprint.
- No trick resolution, trick winner persistence, scoring, next round, match end, replay, voice chat, AI, or matchmaking work was started, per this hotfix's own explicit stop list.

## 10. Conclusion

All three defects named in this sprint's Context are closed: `submitCard()` now atomically persists the played card AND the next turn (or the resolving/null boundary) in one transaction, using a new, minimal, purely additive `TableEngine.previewPlay()` export — no test-only turn mutation is any part of the production flow, and four sequential seats can now submit one card each with Firestore's own `turn` field advancing automatically between them. `applyRemoteCard()` now treats both `MALFORMED_ENTRY` and a content-mismatched local echo as durable, diagnosable desyncs, never a silent skip. Stopping here per the brief's stop condition — no Trick Resolution, Trick Winner Persistence, Scoring, Next Round, Match End, Voice Chat, AI, or Matchmaking. Waiting for review.
