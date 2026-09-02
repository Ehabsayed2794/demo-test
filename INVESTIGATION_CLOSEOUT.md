# Investigation Closeout — Firestore Consistency & Round-16 Rules Race

**Date:** 2026-09-02
**Repository:** `Ehabsayed2794/demo-test`, branch `main`
**Status: BOTH TRACKS CLOSED — no production fix warranted**

---

## 1. STALE_GAME_STATE (Round 9 version race)

**Symptom:** `submitCard()`/`submitBiddingAction()` throw `STALE_GAME_STATE` when a transaction's `tx.get()` observes a newer `version` than the outer pre-check read. In emulator Golden Path runs, this reliably stalled matches around Round 9 — the harness's one-shot retry didn't help, because a plain `getDoc()`/`getDocFromServer()`/`onSnapshot` listener/adapter state could all remain frozen on the *old* version for up to 5 seconds after the transaction had already committed the new one.

**Root cause, confirmed:** Emulator-specific. Official Firebase docs promise strong consistency and serializable-by-commit-time semantics for production, with no equivalent latency guarantee for the emulator — and the Firestore Emulator is explicitly documented as slow/non-faithful for concurrent transactional writes ("Won't Fix" on matching production's exact transactional semantics, per the firebase-tools issue tracker).

**Real-Firestore validation (decisive):** Ran the identical version-race probe against real Firestore (`estimation-lab` project, Email/Password Auth temporarily enabled for the test, then disabled again). Result: **0ms convergence, 3/3 runs** — every external read path (plain get, forced server get, listener) saw the committed version immediately. No lag at all. Verified via raw stdout, file byte counts, and line-by-line evidence file contents — not narrative claims.

**Conclusion:** The code's behavior (reject and let the caller re-fetch and retry) is correct as designed. The observed "stuck for 5 seconds" symptom was a pure emulator artifact and does not occur in production. **No code change needed or recommended.**

---

## 2. Round-16 `extendMatchRounds()` rules race

**Symptom:** 4 concurrent clients calling `extendMatchRounds()` on the same match — 2 would reliably get a genuine Firestore Rules `permission-denied` on their very first transaction attempt, with **zero automatic retry**, while the other 2 either wrote successfully or resolved via the idempotent `ALREADY_EXTENDED` short-circuit after a retry.

**Root cause, confirmed:** Emulator-specific. Reproduced deterministically in the emulator (5/5 runs: always exactly 2 single-attempt denials + 1 real write + retriers reaching the idempotent no-op). Isolated, minimal replays of the identical rules predicates always *allowed* the write — only the real, stateful, concurrent Golden Path workload triggered the denial, indicating the emulator's transaction-retry-on-conflict mechanism was failing to engage for some concurrent writers before Rules evaluation, not a defect in the rules logic itself.

**Real-Firestore validation (decisive, run twice, both independently verified):** Ran an equivalent 4-client concurrent-write race against real Firestore (`rooms/{roomId}` collection, two different racing shapes tested). Result across both runs: **0 permission-denied, 0 zero-retry failures, 12/12 client attempts eventually succeeded** — Firestore's own transaction retry-on-conflict worked exactly as documented, with clients taking 1–4 attempts but never being denied by Rules.

**Conclusion:** The emulator's zero-retry denial does not occur on real Firestore. This is a known-class emulator limitation, not a product defect. **No code change needed or recommended.**

---

## 3. What was NOT changed

Per the standing hard rules throughout this investigation, none of the following were ever modified:
- `firestore.rules`
- `design-ui/match-service.js`
- `design-ui/match-adapter.js`
- `design-ui/engine/session.js`
- Any retry budget or production retry behavior
- The live production project (`made---estimation-card-game`) — confirmed never contacted, at every step, via grep evidence on every probe run

## 4. What WAS added (merged via PR #5, and follow-up diagnostic files)

- `scripts/golden-path.mjs`: harness-only Windows path fix + a bounded one-shot `STALE_GAME_STATE` retry for test-harness resilience (not production code)
- `scripts/firestore-consistency-probe.mjs`, `scripts/estimation-lab-real-probe.mjs`, `scripts/round16-extend-race-probe.mjs`, `scripts/round16-real-race-probe.mjs`: diagnostic probes used to produce the evidence above
- `rule-reproducer.js`, `predicate-evaluation.json`, `round16-rule-audit.json`: offline rules-predicate replay tooling from the original MiniMax investigation, preserved for provenance

## 5. Production fix gate

```
FINAL: NO FIX WARRANTED. Both investigated anomalies are confirmed
emulator-specific artifacts, not real Firestore/product defects.
```

## 6. Residual notes for anyone picking this back up

- `estimation-lab`'s Email/Password Auth provider was used twice for real-Firestore validation and has been disabled again both times — confirm it's off before assuming otherwise.
- `.firebaserc` still has a malformed project-alias key (a stray non-ASCII character instead of `"default"`) — unrelated to this investigation but worth fixing separately if `firebase` CLI default-project resolution is ever needed.
- The original MiniMax transcript referenced a `gprun-diag5.log` that no longer exists anywhere — its contents were preserved second-hand in `round16-rule-audit.json`, but the raw log itself is permanently lost. Not a blocker for anything above, just a note for historical completeness.
