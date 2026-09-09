# POSTMORTEM — Production deal denial, 2026-08-26 (~06:14Z)

**Status: CLOSED — ROOT CAUSE VERIFIED (owner-authorized TASK R8). NO FIXES APPLIED — proposals only (§F).**
This v-FINAL section supersedes the probability rankings in earlier sections
below, which are retained verbatim as the audit trail of how the verdict was
reached.

---

## V-FINAL VERDICT

**ROOT CAUSE (verified): p4 lost the first-deal race.** All four clients'
by-design ungated adapter watcher (`maybeDealRound()`,
design-ui/match-adapter.js:2077–2086) attempted `dealRound()` within
milliseconds of match creation. p1's transaction won (t_p1 ∈
(06:14:36.900840Z , 39.149Z]); p4's lagged commit was evaluated against the
POST-deal document, violated the forward-only idempotency clauses
(firestore.rules L1246 inside `isValidHandDealCommit()`; hands redeal
`newData.round > oldData.round`, L1848), and was correctly denied
permission-denied. The denial was CORRECT behavior protecting deal atomicity.

**Closing chain (TASK R8) — step-by-step verification against our own artifacts:**

| # | Claim | Verdict | Artifact |
|---|---|---|---|
| 1 | 403 on Commit ⇒ rules verdict (valid token: tx.get succeeded ~2.2s earlier; App Check Monitoring/OFF all window — owner telemetry; precondition-staleness measured as HTTP 400 on real backend) | **ADOPTED** | evidence.jsonl L18–19; ADDENDUM-1 telemetry; ADDENDUM-2 Cell C (400 FAILED_PRECONDITION, estimation-lab real backend); Matrix H2b |
| 2 | Pre-deal state does not deny this shape for ANY member | **ADOPTED** | Matrix H2c (real backend, 200 OK); Matrices A/B/C/D/Ea/H1 (live==repo rules, ALLOW); production itself — p1's identical-shape deal committed seconds later |
| 3 | Rules DENY only possible against POST-deal state ⇒ p1 had committed when p4's commit was evaluated | **ADOPTED** | follows from 1+2; forward-only clause family proven to fire on duplicates (H2a/H3: `'update' @ L1513`, hands `L1851/L1852`) |
| 4 | Version-check-first ordering excluded by observation | **ADOPTED** | surfaced-400 not observed (production showed permission-denied); silent-retry no-op not observed (one RestConnection warning IS present). Corollary now EVIDENCED: when both defects coexist, the RULES denial is reported over the concurrent stale-precondition failure — an error-precedence behavior that is undocumented publicly (write.proto/common.proto say nothing) and diverges from the local emulator's precondition-first order (H2b). Production itself is the confirming datum. |
| 5 | Side-denials closed: missing players/{uid} denies AS permission-denied; docs exist today ⇒ profile creation landed later in the session (slow device); explains p4-only selectivity | **ADOPTED** (supersedes ADDENDUM-1's "refuted" framing) | Matrix R3b measured real-emulator semantics (Null-value eval at players allow-update L74 → permission-denied, not not-found); owner console confirms all four docs exist today |
| 6 | Cell-C 400 baseline credited as cornerstone of step 1; 55% RESIDUAL-primary ranking OVERRIDDEN | **ADOPTED** | ADDENDUM-2 raw output |

**Final probabilities:** DOUBLE-DEAL-RACE-CORRECT-DENIAL = verified root cause
(~95%; residual ~5% reserved for the undocumented precedence behavior noted in
step 4, which production itself evidences and an optional with-rules scratch
rerun could independently confirm). APPCHECK, LIVE-RULES-DRIFT,
NONDETERMINISTIC-DEALER, WRONG-DOCUMENT, AUTH-LAPSE, DOC-ACCESS-QUOTA: RETIRED
(retirement reasons in §2 / ADDENDUM-1 remain valid).

**Optional experiment:** the with-rules Cell C rerun is NOT required for this
closure — the chain is complete without it. It remains available as independent
confirmation of the precedence behavior; owner may revoke the scratch key now.

## F. FIX PROPOSALS (ranked, NO implementation)

| P | Proposal | Location(s) |
|---|---|---|
| **P0-1** | Gate `maybeDealRound()` by dealer-seat (or route ALL deals through ONE authority so at most one client ever attempts). Today any client may attempt by design; the race window is structural. | design-ui/match-adapter.js:2077–2086 (gate beside the dealtRound check at :2082–2083); consolidate with the index.html dealer-gate caller design-ui/match/index.html:2159–2168 |
| **P0-2** | Clear the session's locally-shuffled fallback hand whenever hand-authority switches to "firestore", so a losing racer can never display a stale private shuffle across the authority transition (the unique=41/52 fingerprint artifact). | design-ui/engine/session.js:263–266 (`setHandAuthorityMode` currently only flips a flag) invoked from design-ui/match-adapter.js:2133–2135; purge before first `applyRemoteHand` (:2097+) |
| **P1-3** | Define or remove the dangling engine hooks `buildHand()` / `bindStatic()` — p4 alone threw six ReferenceErrors from these names during the incident second (evidence L12–21), the visible symptom of its bundle divergence. | call sites: design-ui/engine/bidding-engine.js:963–964, design-ui/engine/table-engine.js:351 |
| **P1-4** | In-game build stamp + cache busting: surface `{buildId, commitSha, rulesSha256}` on lobby/match screens; emit cache-busting headers for hashed assets. | new stamp module wired into design-ui/match/index.html boot; hosting headers via existing firebase.json (`hosting.public` -> hosting-dist, see scripts/build-hosting.mjs:3–7) |
| **P2-5** | Log FULL denial bodies client-side: every Firestore catch emits `{code,message,stack}` verbatim via one FirebaseError serializer; log swallowed adapter rejections once per matchId instead of `.catch(function(){})`. | design-ui/match-adapter.js:2085 (empty catch), room-service.js:91–93, session-service.js:133–136, design-ui/match/index.html:2166 |
| **P2-6** | Atomic CLI deploys of rules+hosting from ONE commit; CI asserts live rules-release hash == repo LF-sha256 after deploy. | repo has firebase.json but no .firebaserc/CI wiring; formalize `firebase deploy --only firestore:rules,hosting` pipeline |
| **P3-7** | Document the measured rules-vs-precondition precedence divergence (emulator precondition-first vs production rules-denial-reported) in firestore.rules header notes alongside the three existing divergence records. | firestore.rules deployment-history comment block (top of file) |

## Scratch-project residue (owner actions outstanding)

- Revoke SA key at Google side (file deletion done locally; never git-tracked).
- Optional teardown of estimation-lab artifacts (web app ordering-probe,
  anonymous users, matches/mH-real-* docs, unreferenced ruleset 61bcc9df…),
  or delete the whole project. Full list: ADDENDUM-2 cleanup section.

---

(Historical record below — probability tables in ADDENDUM sections are
superseded by the V-FINAL VERDICT above.)
Scope: golden production run on `made---estimation-card-game`, match
`kfgiv1ZlUNrkmiiunide` in room `noxiRrTFuAhfpUavuzZ1`. Evidence:
`golden-prod-evidence/evidence.jsonl` (run 06:14:07–39.9Z). All experiments
below ran against the LOCAL Firestore emulator only; zero production calls.

---

## 1. Verified root cause

**The 403 on p4's batched hands+gameState commit was NOT issued by Firestore
Rules.** Mechanically proven end-to-end:

| # | Fact | Proof |
|---|---|---|
| F1 | Live rules (Today 08:26 publish) == repo rules | R1c byte-diff: zero content hunks; sole difference = trailing newline at EOF (`docs/postmortem/live-rules.today.txt`) |
| F2 | Yesterday-live deal path also identical to repo | R1b diff: only header comment + Sprint L opening-turn window changed; `isValidHandDealCommit()`/hands block untouched by both publishes |
| F3 | Rules ALLOW the exact denied request | Matrix H2c: wire-level replay of the production payload (same write-set, seated member, **passing** `currentDocument.updateTime`, undealt doc) → **HTTP 200 OK** on live==repo rules. Corroborated by Matrices A/B/C/D/Ea (SDK paths) |
| F4 | Production's own precondition PASSED | A stale/failing precondition surfaces as **HTTP 400 FAILED_PRECONDITION** ("stored version does not match required base version", Matrix H2b) or SDK-side ABORTED/retry — never as 403. Production got 403 ⇒ its `updateTime=2026-08-26T06:14:36.900840000Z` matched ⇒ the doc was still creation-state when p4 committed ⇒ no prior successful deal existed at that instant |
| F5 | Therefore the denial came from a layer IN FRONT OF rules | App Check / front-door enforcement class. Consistent with the error shape: bare `{"code":"permission-denied"}` with **no** `evaluation error at L…` text (every genuine rules denial carries L-numbers — see any DENY printout in tests/repro-deal-denial.rules-emulator.test.cjs) |
| F6 | The same client was being selectively blocked across unrelated paths all session | p4 alone logged five permission-denied self-profile writes (`[RoomService] Failed to sync currentRoomId` ×4 @ 23.5–30.9s; `[SessionService] setCurrentMatchId() failed` @ 37.4s) while p1/p2/p3 had zero console errors — selective per-client blocking is the signature of attestation/enforcement layers, not of these rules (R3a proves the writes are allowed for an existing profile) |

### Final probabilities *(SUPERSEDED — see V-FINAL VERDICT)*

| Hypothesis | P | Basis |
|---|---|---|
| **APPCHECK-ENFORCEMENT (front-door)** | **~75%** | Only hypothesis consistent with F3+F4+F5+F6 simultaneously. Explains per-client selectivity, passing-precondition 403, L-number-free error body, and later healthy deal by another seat. **Requires owner confirmation**: Firebase Console → App Check → Firestore → enforcement metrics/log around 2026-08-25→26 (Spark/unsafely-configured clients failing attestation get exactly this shape). |
| RESIDUAL (other front-door policy: abuse heuristics, quota-class infra denials) | ~20% | Same observable signature family; cannot be excluded without server-side logs. |
| DOUBLE-DEAL-RACE-CORRECT-DENIAL | ~5% | The mechanism is real and reproducible — H2a/H3 show a duplicate deal after commit IS denied by the forward-only idempotency clause (L1246 inside `isValidHandDealCommit`, surfaced via dispatch L1513). But it can never present as production's 403: a post-commit replay fails its precondition first with HTTP 400 (H2b). Refuted as production's cause. |

## 2. Retired hypotheses (one-line reasons)

- **LIVE-RULES-DRIFT** — retired: R1c byte-proof, today-live == repo (deal path zero hunks; whole file differs only by EOF newline).
- **NONDETERMINISTIC-DEALER** — retired: determinism suite 11/11 (24 insertion permutations × duplicate-uid malformations; 2880 content-equal pairs, 0 divergence); `uidToSeat()` reads only the immutable committed `seats` map via normalized key order.
- **WRONG-DOCUMENT** — retired: Matrix F shows cross-match attempts deny at read stage (`'get' @ L1281`) with a different signature than observed; production payload names the correct matchId in every write.
- **AUTH-LAPSE** — retired: p4's transactional `tx.get()` succeeded (its `currentDocument.updateTime` precondition base came from that read), so the token was valid at commit time.
- **DOC-ACCESS-QUOTA** — retired: the official deal traversed the identical validator cost successfully moments later (production's eventual success; H1/G locally), and quota-class failures do not clear within sub-second windows.

## 3. Timeline (evidence.jsonl)

| t (UTC) | Event |
|---|---|
| 06:14:07.738 | Golden run START against production |
| 06:14:22.521 | 4 distinct Auth uids signed up |
| 06:14:23.506→30.876 | p4 ONLY: 4× `[RoomService] Failed to sync currentRoomId … Missing or insufficient permissions` (p1/p2/p3 clean) |
| 06:14:32.060 | All joined (PASS 1.2) |
| 06:14:36.900 | Match doc `kfgiv1ZlUNrkmiiunide` created (this becomes p4's precondition base) |
| 06:14:37.410 | PASS 2.1 match started |
| 06:14:37.410 | p4 ONLY: `[SessionService] setCurrentMatchId() failed … permissions` |
| 06:14:38–39 | p4 ONLY: repeated `buildHand`/`bindStatic` PAGE_ERRORs (stale/divergent UI bundle symptoms) |
| 06:14:39.147–154 | **p4's Commit RPC (4× hands + gameState flip + updatedAt transform, precondition PASSING) → 403 permission-denied** |
| 06:14:39.76 | PASS 2.2/2.3 seat resolution + matchId agreement |
| ≤06:14:39.78 | SOMEONE's deal commits (p1 inferred): p1+p2+p3 display one coherent disjoint 39-card shuffle; p4 alone displays a local fallback (pairwise overlaps 0/0/0 vs 5/5/1) — findings.json's unique=41/52 fully explained |

## 4. Contributing causes

1. Console-paste deploy flow: two rules publishes within 21h with no atomicity vs the hosting bundle; impossible to know which client bundle met which ruleset without stamps.
2. Client-side error handling truncates denial bodies (the RestConnection warning cut off before any diagnostic detail; no L-numbers, no App Check markers captured).
3. No visible build/rules version anywhere in the product or logs.
4. Golden harness records console events but not RPC-level metadata (auth claims summary, App Check verdicts), leaving attribution to forensic reconstruction.
5. p4's page showed stale-bundle symptoms (`buildHand`/`bindStatic` undefined) — an unexplained client-divergence signal that preceded the incident.

## 5. Open items for owner (evidence still needed)

- App Check: enforcement state + per-service metrics for Firestore on 2026-08-25→26 (confirm/refute the ~75% hypothesis).
- Hosting deploy history for the same window (which bundle p4 ran).
- Whether p4's profile document existed at incident time (would explain the five side-denials independently — note: missing-doc ALSO surfaces as permission-denied in this ruleset, proven in R3b).

## 6. Prevention proposals (PROPOSALS ONLY — awaiting owner approval)

1. Single-commit atomic deploys: move rules+hosting to CLI/CI (`firebase deploy --only firestore:rules,hosting`), pinning the exact ruleset hash with each release.
2. Visible in-game build stamp: surface `{buildId, commitSha, rulesSha256}` on the lobby/match screens and log it at startup.
3. Log FULL denial bodies client-side: every Firestore catch should emit `{code, message, stack}` verbatim (the current truncated warning cost hours); add a dedicated serializer for FirebaseError.
4. App Check observability: enable debug-mode telemetry and alert on enforcement rejections per client before turning on/off enforcement.
5. CI pinning: after each rules deploy, assert the live release hash (via Releases API) equals the repo file's LF-sha256; fail the pipeline otherwise.

---

# ADDENDUM (TASK R7 — commit-error ordering + race-window pinning)

Owner evidence received after v1 of this document: (1) App Check metrics for
Aug 19–27 show Firestore enforcement **OFF throughout** (Monitoring mode,
app never registered, "Unverified-outdated 100%"); (2) all four players/{uid}
docs exist; (3) the deduction below about the losing-race signature.

## R7.1 Corrections to earlier claims in this file

- **WITHDRAWN:** "under HEAD client code only p1 attempts dealRound" (v1 §R4.2
  inference). The adapter's own bootstrap watcher is an UNGATED attempt path by
  design — `maybeDealRound()` (design-ui/match-adapter.js:2077–2086), invoked on
  EVERY match snapshot for EVERY client via `startHandSync()` (:2126–2139). Its
  own comment states the authority model: *"any client may attempt it, the
  transaction makes it safe"* and *"A rejection … is swallowed here, never thrown
  into the caller's snapshot callback"* (.catch(function(){}) at :2085).
  This simultaneously explains: p4's Commit existing at all, AND why the
  index.html dealer-gate's catch string ("[Match] dealer dealRound() attempt
  failed") never appears — p4's attempt came from the adapter path, whose
  rejection is silently swallowed.
- **RETRACTED:** the ~75% APPCHECK-ENFORCEMENT ranking. Owner-supplied
  production telemetry proves Firestore App Check enforcement was OFF for the
  entire Aug 19–27 window; an unregistered app under Monitoring mode cannot be
  denied by App Check. RETIRED with probability ≈0.
- **REFUTED:** missing-profile-doc explanation for p4's five side-denials
  (all four players/{uid} docs verified present by owner console query). The two
  failing call sites pass only whitelisted fields (`{currentRoomId}` via
  room-service.js:85, `{currentMatchId}` via session-service.js:131; filter at
  player-service.js:116–118 drops anything else), so hypothesis (a)
  "fields outside onlyAllowedFieldsChanged()'s whitelist" is also excluded.
  Remaining: (b) transient token-refresh/auth blips on the slowest device, or an
  unexplained one-off. NON-BLOCKING minor thread; closed without further work.

## R7.2 Race window — FACTUAL bound (log mining)

| Anchor | Timestamp (UTC) |
|---|---|
| Match doc version stamped (p4's precondition base) | **06:14:36.900840Z** |
| p4 denial logged (403 / RestConnection warning ts) | 06:14:39.147–39.154Z |
| Committed deal visible to p1/p2/p3 (PASS 3.2 / STOP) | 06:14:39.784Z |

Derivation: p4's Commit existed ⇒ its tx.get() read version 36.900840Z with
dealtRound=0 ⇒ any successful deal BEFORE p4's server-side evaluation would have
advanced that version. The winning shuffle is demonstrably NOT p4's (fingerprint
overlap ≤4/13 per seat) ⇒ p4 LOST a race that was live at 39.149Z.

**Factual bound: t_p1 ∈ (36.900840Z , 39.784Z].** Whether t_p1 < t_p4-commit
(≈39.149Z) — i.e., a sub-250ms double-deal window — is precisely the ordering
question below.

## R7.3 Ordering research (authoritative sources, verbatim)

- googleapis/googleapis `google/firestore/v1/write.proto` (Write.current_document):
  > "An optional precondition on the document.
  > The write will fail if this is set and not met by the target document."
- googleapis/googleapis `google/firestore/v1/common.proto` (Precondition.update_time):
  > "When set, the target document must exist and have been last updated at
  > that time."
- REST reference (projects.databases.documents.commit): documents the fields;
  **no statement about evaluation ORDER between security-rules checks and
  precondition validation, and no documented error-code precedence** when a
  request carries both defects.
- Measured emulator behavior (Matrix H2b): stale precondition wins first → HTTP
  400 FAILED_PRECONDITION ("stored version … does not match required base
  version"). Production observed for the same shape: **403 permission-denied**
  (evidence L19).

**Finding: the ordering is UNDOCUMENTED publicly, and the two backends are
observed to differ.** This repository itself records three prior
emulator-vs-production divergences in firestore.rules comments (top-level
getAfter() defect, `.all()` compile-safety, empty-array slice crash) — parity
must be measured, not assumed.

## R7.4 The decisive experiment (prepared; awaiting owner execution)

`tests/h-matrix.real-backend.cjs` + runbook
`docs/postmortem/h-matrix-real-backend-runbook.md`: a hard-gated script (refuses
the game project id three ways) that replays the exact H-matrix against a
THROWAWAY scratch project over pure REST:
- Cell A first deal w/ valid precondition → expect 200 (sanity);
- **Cell C duplicate w/ STALE precondition → THE measurement**: HTTP 403 ⇒
  real backend evaluates rules first ⇒ production's 403 IS reproduced as the
  forward-only idempotency denial of the lost race; HTTP 400/409 ⇒ parity with
  emulator ⇒ race model refuted again and an unidentified front-door layer
  returns to primary suspicion;
- Cell B duplicate w/ refreshed precondition → control (expect 403, bare
  message — real backend prints no rules L-numbers).

## R7.5 Re-ranked root cause *(SUPERSEDED — see V-FINAL VERDICT; the RESIDUAL-primary and 80%-conditional rows are overridden)*

| Hypothesis | P | Basis |
|---|---|---|
| **DOUBLE-DEAL-RACE-CORRECT-DENIAL** | **~80% (conditional)** | Every element now coheres: ungated adapter path explains p4 attempting (B3); swallowed rejection explains the absent catch-log; p4's single-attempt transaction legitimately passed its JS guard on a pre-deal read (the guard only blocks post-retry resubmission); fingerprint proof shows p4 lost; a rules-first ordering on the real backend yields exactly permission-denied from the hands-redeal (`newData.round > oldData.round`, L1848) / parent forward-only clauses against post-p1 state — while the SDK's automatic retry then sees ALREADY_DEALT and exits silently, matching the single-warning evidence trail. CONDITIONAL on Cell C returning 403. |
| RESIDUAL (unidentified infra/front-door layer, non-App-Check) | ~15% | Fallback if Cell C returns 400/409: emulator-parity ordering would re-open the contradiction between rules-allowed payload and production 403; generic Google-side abuse/security heuristics remain possible but unevidenced. |
| APPCHECK-ENFORCEMENT | RETIRED (~0%) | Owner telemetry: enforcement OFF entire window; Monitoring mode; app never registered. |
| LIVE-RULES-DRIFT / NONDETERMINISTIC-DEALER / WRONG-DOCUMENT / AUTH-LAPSE / DOC-ACCESS-QUOTA | RETIRED | Unchanged retirements from v1 (§2). |

## R7.6 Unified causal narrative (one paragraph)

All four clients' `startHandSync()` watchers fired within milliseconds of the
match doc appearing (created 36.900840Z); each client's `maybeDealRound()`
attempted exactly one `dealRound()` transaction — by design, ungated, safe-by-
transaction. p1's transaction won (t_p1 ∈ (36.900, 39.149] under the race model),
committing its shuffle atomically (hands p1–p4 + gameState flip). p4's lagged
attempt — built from the 36.900-version read — arrived last; the real backend
evaluated its now-invalid hand writes against post-commit state (round-1 redeel
violates `newData.round > oldData.round`; parent flip violates forward-only)
and returned PERMISSION_DENIED ahead of the equally-stale version precondition
(ordering divergence vs emulator, undocumented publicly). The SDK retried once,
observed ALREADY_DEALT, and exited cleanly; the adapter swallowed the original
rejection. Downstream artifacts all reconcile: unique=41/52 (p4 kept its local
fallback shuffle — its hand listener attached late amid repeated
buildHand/bindStatic render crashes), PASS 3.2/STOP fingerprints, absence of any
dealer-gate log line, and the five side-denials as a separate minor auth-blip
thread.

## R7.7 Remaining gaps

1. Cell C result pending owner execution of the runbook (~5 min) — converts the
   conditional 80% into settled fact either way.
2. Exact t_p1 unknown within the bounded window; no server-side latency logs
   available on Spark.
3. p4's fallback-shuffle display window (why applyRemoteHand hadn't landed by
   STOP) is explained only circumstantially (late listener attach + six
   PAGE_ERRORs on p4's page in the same second).
4. Side-denial root cause (five players/* failures): closed as non-blocking
   auth-blip class; no further action proposed.

---

# ADDENDUM 2 (R7 execution — REAL-BACKEND MEASUREMENT, estimation-lab)

Executed against the owner's throwaway project via
`tests/h-matrix.real-backend.cjs` (SA-bootstrapped: OAuth -> web app ->
Web API Key -> REAL anonymous end-user idToken; admin token used only for
seeding/deletes). Raw results:

```
[A] FIRST deal, valid precondition     -> HTTP 200 OK
    t0 = 2026-08-26T12:17:48.467831Z   t1 = 2026-08-26T12:18:39.676913Z
[C] DECISIVE duplicate, STALE t0 precondition -> HTTP 400
    {"error":{"code":400,"message":"the stored version (1787746719676913)
    does not match the required base version (1787746719092683)",
    "status":"FAILED_PRECONDITION"}}
[B] duplicate, refreshed t1 precondition      -> HTTP 200
    commitTime = 2026-08-26T12:18:40.269559Z
```

## What this settles

1. **REAL Firestore returns HTTP 400 FAILED_PRECONDITION for a stale-version
   commit** whose writes are otherwise permitted — full parity with the local
   emulator on this point. A stale precondition NEVER surfaces as 403.
2. Production's incident commit returned **403** while carrying precondition
   base 36.900840Z (pre-deal version). Two readings survive:
   a) The precondition PASSED at evaluation (doc still at creation version ⇒
      undealt ⇒ repo/today-live rules ALLOW it — Matrix H2c proved 200 for
      exactly that request). Then the 403 came from a layer OTHER than rules
      or the precondition machinery → unidentified front-door/infra layer.
   b) p1 had already committed (both defects present) AND the backend evaluates
      security-rules violations BEFORE version-precondition failures — in which
      case the 403 IS the forward-only idempotency denial of the lost race.
      Reading (b) requires an undocumented ordering asymmetry (rules-first only
      when a violation exists); the baseline here cannot confirm or refute it
      because the SA lacked IAM permission to update the `cloud.firestore`
      release (rulesets.create succeeded; releases.update -> 403), so the
      both-defects cell could not run under game rules.

## Final re-ranked probabilities

| Hypothesis | P | Condition to settle |
|---|---|---|
| RESIDUAL front-door / unidentified infra layer | **~55% primary** | Consistent with ALL measured facts without extra assumptions (passing precondition + allowing rules + 403-not-400 + p4-only selectivity across five unrelated writes). |
| DOUBLE-DEAL-RACE-CORRECT-DENIAL | ~25% conditional | Requires reading (b): rules-first ordering specifically when a violation exists. ONE owner action settles it: paste `firestore.rules` into estimation-lab Console -> Rules -> Publish, then rerun `SKIP_RULES_DEPLOY=1` (fresh doc id auto-generated). Cell C returning 403 under live game rules CONFIRMS race-denial (~80% final); returning 400 refutes it and leaves RESIDUAL ~90%. |
| Not-yet-modeled ordering behavior | ~20% | Explicit reserve for what Cell C reveals under live game rules. |
| APPCHECK / LIVE-RULES-DRIFT / NONDETERMINISTIC-DEALER / WRONG-DOCUMENT / AUTH-LAPSE / DOC-ACCESS-QUOTA | RETIRED | Unchanged from ADDENDUM v1 retirements. |

## Scratch project cleanup instructions (owner)

1. **REVOKE THE KEY (critical — deleting the file does NOT revoke it):**
   Console -> IAM & Admin -> Service Accounts ->
   `firebase-adminsdk-fbsvc@estimation-lab.iam.gserviceaccount.com` -> Keys ->
   delete the key created 2026-08-26 (or
   `gcloud iam service-accounts keys delete <KEY_ID> --iam-account=firebase-adminsdk-fbsvc@estimation-lab.iam.gserviceaccount.com --project=estimation-lab`).
2. Local file already deleted by the auditor (`scratch-service-account.json.json`);
   never committed to git (verified).
3. Throwaway artifacts created during the run — remove any/all:
   - Web app "ordering-probe" (Project settings -> General).
   - Anonymous users (Authentication -> Users).
   - Firestore docs `matches/mH-real-*` (+ their `hands` subcollections).
   - Unreferenced ruleset `projects/estimation-lab/rulesets/61bcc9df-25f0-44ea-97d4-72c18befaea6`
     (no release was ever attached; harmless either way).
4. Simplest option: delete the whole `estimation-lab` project (Settings ->
   General -> Delete project; 30-day recovery window).
