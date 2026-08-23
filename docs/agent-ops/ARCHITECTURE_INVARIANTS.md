# Architecture Invariants

## INV-001: Layering Discipline

| Field | Value |
|-------|-------|
| **Invariant** | Engine (pure functions) → GameSession (state store) → MatchAdapter (seat/engine translation) → MatchService (Firestore I/O) → UI (HTML/vanilla JS). No circular dependencies. |
| **Evidence** | Directory structure `design-ui/engine/` contains no Firebase imports; `match-service.js` imports engine modules but not vice versa; `match-adapter.js` is the only file that interprets both Firestore and GameSession schemas. |
| **Relevant Files** | `design-ui/engine/*.js`, `design-ui/match-adapter.js`, `design-ui/match-service.js`, `design-ui/engine/session.js` |
| **Risk if Violated** | Circular dependencies would break testability and make reasoning about state flow impossible. |
| **Evidence Type** | VERIFIED BY SOURCE INSPECTION |

## INV-002: Client-Authoritative Trust Model

| Field | Value |
|-------|-------|
| **Invariant** | Under Spark (free) Firebase plan, bid/card value legality, score correctness, and hand-deal fairness are client-authoritative with server-side structural validation only. |
| **Evidence** | `firestore.rules` enforces structure (types, required fields, seat map uniqueness) but not game logic correctness. Header comment states "NOT YET DEPLOYED". |
| **Relevant Files** | `firestore.rules`, `docs/architecture/SecurityArchitecture.md` |
| **Risk if Violated** | Moving to trusted backend would require Cloud Functions + Blaze plan migration (80-150 hours estimated). |
| **Evidence Type** | VERIFIED BY SOURCE INSPECTION |

## INV-003: Fast-Round Trump Cycle

| Field | Value |
|-------|-------|
| **Invariant** | Rounds 14-18 use forced trump suit cycling (modulo 4 from round number). |
| **Evidence** | `bidding-engine.js` lines 183-212 implement fixed trump assignment based on round number modulo 4. |
| **Relevant Files** | `design-ui/engine/bidding-engine.js` |
| **Risk if Violated** | Would fundamentally change fast-round strategy and break rules compliance. |
| **Evidence Type** | VERIFIED BY SOURCE INSPECTION |

## INV-004: Transactional Room/Match Operations

| Field | Value |
|-------|-------|
| **Invariant** | All room and match lifecycle operations use Firestore transactions for atomicity. |
| **Evidence** | `room-service.js` and `match-service.js` use `runTransaction()` throughout for create/join/leave/start/abandon operations. |
| **Relevant Files** | `design-ui/room-service.js`, `design-ui/match-service.js` |
| **Risk if Violated** | Race conditions could corrupt room state, especially during concurrent join/leave operations. |
| **Evidence Type** | VERIFIED BY SOURCE INSPECTION |

## INV-005: Single Source of Truth for Rules

| Field | Value |
|-------|-------|
| **Invariant** | `Estimation_Rules_v2_SingleSourceOfTruth.docx` (in `uploads/`) is canonical rules reference. Engine comments cite specific rule sections. |
| **Evidence** | Forensic audit (`docs/reviews/Sprint-4.1-Forensic-Audit.md`) references this document for scoring formula verification. |
| **Relevant Files** | `uploads/Estimation_Rules_v2_SingleSourceOfTruth.docx`, `docs/reviews/Sprint-4.1-Forensic-Audit.md` |
| **Risk if Violated** | Already caused scoring bugs when violated (Normal-mode Super Call ±20 invented, Dash Call formula wrong). |
| **Evidence Type** | DOCUMENTED CLAIM |

## INV-006: Build Output Separation

| Field | Value |
|-------|-------|
| **Invariant** | `npm run build` produces ONLY the `src/` React app; `design-ui/` is NOT part of the build. |
| **Evidence** | `vite.config.ts` sets `root: 'src'`; `package.json` scripts point to Vite with src entry. |
| **Relevant Files** | `vite.config.ts`, `package.json`, `src/App.tsx` |
| **Risk if Violated** | Attempting to merge without planning would break the working shipped app. |
| **Evidence Type** | VERIFIED BY SOURCE INSPECTION |

## INV-007: Pure Translation Functions

| Field | Value |
|-------|-------|
| **Invariant** | `matchDocToEngineSnapshot()` and `engineSnapshotToMatchPatch()` are pure functions: same input always produces same output, input never mutated, no side effects. |
| **Evidence** | `match-adapter.js` header explicitly states these are pure functions; tests verify round-trip determinism. |
| **Relevant Files** | `design-ui/match-adapter.js`, `tests/match-adapter.test.cjs` |
| **Risk if Violated** | Non-deterministic translation would make debugging impossible and break round-trip guarantees. |
| **Evidence Type** | VERIFIED BY SOURCE INSPECTION |

## INV-008: Engine Authority for Card/Bid Legality

| Field | Value |
|-------|-------|
| **Invariant** | `TableEngine.emit()` and `BiddingEngine.emit()` are the sole authorities for card-play and bid legality. Adapter layer only reads responses, never evaluates rules. |
| **Evidence** | `EngineAdapter.md` documents this pattern; `applyRemoteCard()` and `applyRemoteBid()` replay through engine's `emit()` and read `{rejected, reason}` response. |
| **Relevant Files** | `design-ui/engine/table-engine.js`, `design-ui/engine/bidding-engine.js`, `design-ui/match-adapter.js` |
| **Risk if Violated** | Duplicate rule evaluation logic would diverge, causing inconsistent state between clients. |
| **Evidence Type** | VERIFIED BY SOURCE INSPECTION |

---

*No additional invariants added. Each invariant above is supported by direct repository evidence.*
