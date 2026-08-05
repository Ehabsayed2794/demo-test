# Engine Dependency Audit — Sprint 3.4.5

**Type:** Audit only. No source file was modified, refactored, or created as part of this sprint (this report itself is the one new file). No Firestore, Services, UI, or `firestore.rules` were touched. Nothing was committed or pushed.

**Method:** Static inspection only — reading files and `grep`/`find` across the repository. **No code was executed.** Every claim below is either a direct quote/citation of a file's actual content, or explicitly marked as an inference. Nothing here should be read as "verified at runtime."

---

## 1. Executive Summary

`Deck` is **genuinely missing** — there is no naming mismatch, load-order bug, export bug, or scope-attachment bug to fix. `dealer.js`'s own header comment states it plainly: *"Depends on cards.js + deck.js."* `cards.js` was delivered; `deck.js` was not. This confirms and slightly sharpens the finding already recorded in `docs/implementation/MatchInitialization.md` (Sprint 3.4).

Two things beyond the original finding are new in this audit and materially change the risk picture:

1. **The entire gameplay-engine folder (`design-ui/engine/*`) is currently orphaned.** No HTML file anywhere in this repository loads `dealer.js`, `cards.js`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, or `design-ui/engine/session.js` via a `<script>` tag. The screens these files were built for (`Estimation Bidding Phase.html`, `Estimation Game Table.html` — see `game-state.js`'s `STATE_SCREEN` map) do not exist in the repository. This means the `ReferenceError: Deck is not defined` has **never actually fired** in this codebase's shipped surface — it is a latent, guaranteed-to-fire defect in dead code, not an active production bug.
2. **Everything downstream of the missing `Deck` is already structurally compatible.** `Dealer.dealHands()`'s return shape matches exactly what `GameSession.dealNewHands()`/`getHand()`/`setHand()` expect, and `bidding-engine.js`/`table-engine.js` both already funnel through the single `GameSession.ensureHandsDealt()` entry point, never touching `Dealer`/`Deck` directly. This is **not** a wider architecture conflict — it is one missing leaf module in an otherwise-consistent chain.

A secondary, non-blocking finding: `SUITS`/`RANKS` tables are duplicated **identically** across three files (`cards.js`, `bidding-engine.js`, `table-engine.js`) rather than defined once and shared. Not a conflict today (values match), but a real drift risk once a `Deck` module needs to pick one source of truth.

**Bottom line:** the missing dependency is exactly what it appears to be — a small, self-contained, never-delivered module — not a symptom of a deeper architectural problem. See §12 for the formal decision.

---

## 2. Root Cause

`design-ui/engine/dealer.js`'s `dealHands()` calls three methods on a global `Deck` object — `Deck.reset()`, `Deck.shuffle()`, `Deck.deal(1)` (called 52 times: 13 rounds × 4 seats) — but no file in this repository defines `window.Deck`, `global.Deck`, or any object assigned to a `Deck` identifier anywhere.

A repository-wide search (excluding `node_modules/`, `dist/`, and prior sprints' QA-package copies under `Sprint-*-Review/`) for the literal token `Deck` returns exactly:
- `dealer.js`'s own three call sites and its header comment ("`Depends on cards.js + deck.js.`")
- `cards.js`'s header comment ("`No shuffling/dealing here — see deck.js / dealer.js`")
- `match-service.js`'s comments and `gameState.todo` string (Sprint 3.4's own documentation of this exact finding)
- The architecture docs (`MatchInitialization.md`, `FirestoreSchema.md`, `MatchLifecycle.md`, `ServiceArchitecture.md`) that already recorded this finding in Sprint 3.4.

There is no `deck.js` file, no differently-named module (`card-deck.js`, `card-engine.js`, `deckManager`, etc.), and no inline object literal anywhere that could plausibly be `Deck` under another name. `Deck` was never delivered as a file — this is a missing dependency, not a discovery/wiring problem.

---

## 3. Evidence by File and Function

| File | Finding |
|---|---|
| `design-ui/engine/dealer.js` | Header: *"Depends on cards.js + deck.js."* `dealHands()` (line 19) calls `Deck.reset()`, `Deck.shuffle()`, `Deck.deal(1)` — all three are unconditional, unguarded (no `typeof Deck !== "undefined"` check, no try/catch). Any call to `Dealer.dealHands()` throws `ReferenceError: Deck is not defined` immediately. |
| `design-ui/engine/cards.js` | Header: *"No shuffling/dealing here — see deck.js / dealer.js."* Exposes `Cards.SUITS` (5 entries, including `SANS` — a trump mode, not a physical suit), `Cards.DECK_SUITS` (4 real suits: `SPADES`/`HEARTS`/`DIAMONDS`/`CLUBS`), `Cards.RANKS` (13 ranks, `2`–`A`), `Cards.createCard(suitKey, rank, owner)`, `Cards.compareForSort(a, b)`. Self-contained — no missing dependency of its own. `Cards.createCard()` is called **nowhere** in the current codebase (confirmed by `grep`) — it exists only as the building block a future `Deck` would call. |
| `design-ui/engine/session.js` (`GameSession`) | `dealNewHands()` (line 198): `session.hands = Dealer.dealHands();` — no guard. `ensureHandsDealt()` (line 419) is the single funnel every other module uses; it calls `dealNewHands()` whenever the current round has no valid deal yet. Both propagate the `Deck` `ReferenceError` unmodified — there is no try/catch anywhere in this chain. |
| `design-ui/engine/bidding-engine.js` | Line 84: `const hands = GameSession.ensureHandsDealt();` — this is the file's own comment's "Dealer + Deck modules behind `GameSession.ensureHandsDealt()`" (line 81), i.e. the author already knew this indirection existed. Also defines its own top-level `SUITS`/`RANKS`/`SUIT_ORDER` constants (lines 9–21), independent of `Cards.SUITS`/`Cards.RANKS` — see §5. |
| `design-ui/engine/table-engine.js` | Line 87: `const hands = GameSession.ensureHandsDealt();` — same funnel, same exposure. Also defines its own top-level `SUITS`/`RANKS` (lines 9–19), byte-for-byte identical in value to `bidding-engine.js`'s copies and structurally identical to `cards.js`'s (see §5). |
| `design-ui/engine/scoring-engine.js` | No `Deck`/`Dealer` reference at all — operates purely on already-resolved bid/trick results (`GameSession.getPlayers()`, bid records). Not implicated in this dependency. |
| **Every `*.html` file in this repository** | `grep`-checked directly: `design-ui/lobby/index.html`, `design-ui/profile/index.html`, `design-ui/match/index.html`, `design-ui/login/index.html` each load only `game-state.js`, the Firebase compat SDKs, `firebase-init.js`, and the relevant `*-service.js` files. **None load `dealer.js`, `cards.js`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, or `design-ui/engine/session.js`.** `design-ui/lobby/index.html` does load its own copy of `session.js` (`design-ui/lobby/session.js`, byte-identical to `design-ui/engine/session.js`) — but Lobby's own inline script never calls any `GameSession` method, so this load is inert (module-scope code in `session.js` never touches `Dealer`/`Deck` at load time — only inside function bodies that are never invoked by Lobby). |
| `docs/` (repo-wide search for `GameState.md`, `CardEngine.md`, `GameSession.md`, `BiddingState.md`, `ScoringEngine.md`, "Implementation Roadmap", "UI Architecture Inventory", "IntegrationReport") | **None of these documents exist in this repository**, despite being referenced by name in code comments inside `session.js`/`scoring-engine.js` (e.g. *"see GameSession.md"*, *"see ScoringEngine.md"*). They are either lost, never committed, or external artifacts from wherever this engine code was originally authored. This audit could not consult them. |
| `uploads/` (referenced in `bidding-engine.js`'s and `scoring-engine.js`'s comments: `uploads/kotlinCode.ts`, `uploads/Estimation_Rules_v2_SingleSourceOfTruth.docx`) | **Neither file exists in this repository.** The only `uploads/` folder present (`design-ui/lobby/uploads/`) contains three PNG/JPG images used by the Lobby screen's mode cards — no `.ts` or `.docx` file. This audit could not independently verify the engine's fidelity to either referenced source. |
| `src/` (React/TypeScript app: `App.tsx`, `utils.ts`, `types.ts`) | A **separate, unrelated** scoring-calculator prototype — role-based score computation from manually-entered bid/won counts (`PlayerRole`, `calcNormalScore`, etc.). **No card, deck, or dealing concept exists anywhere in `src/`.** Built via Vite to `dist/` (root `index.html` → `/src/main.tsx`), entirely disconnected from `design-ui/`'s Firebase/multiplayer work — no file in either tree references the other. Not a usable `Deck` reference implementation; see §6. |

---

## 4. Dependency Graph

```
                (missing)
                  Deck
                    ▲
                    │  reset() / shuffle() / deal(n)
                    │
              dealer.js (Dealer.dealHands)
                    ▲                    │
      Cards.compareForSort               │ returns { p1:[...], p2:[...], p3:[...], p4:[...] }
                    │                    ▼
              cards.js (Cards)   session.js (GameSession.dealNewHands / ensureHandsDealt)
              — self-contained            ▲                        ▲
              — createCard() UNUSED       │                        │
                                          │ ensureHandsDealt()      │ ensureHandsDealt()
                                          │                        │
                              bidding-engine.js          table-engine.js
                              (own local SUITS/RANKS)    (own local SUITS/RANKS,
                                                           identical values to
                                                           bidding-engine.js's)

scoring-engine.js — reads GameSession bid/trick results only; no path to Deck/Dealer.

──────────────────────────────────────────────────────────────────────────
Reachability from any actual HTML page in this repo: NONE.
No <script> tag anywhere loads dealer.js / bidding-engine.js / table-engine.js /
scoring-engine.js / cards.js / design-ui/engine/session.js. design-ui/lobby/session.js
(a byte-identical copy) is loaded by Lobby but never exercises the chain above.
──────────────────────────────────────────────────────────────────────────

MatchService.startMatch() (Firestore-backed, Sprint 3.4/3.4.1)
  — builds matches/{matchId}.gameState = { initialized:false, todo:"..." } directly.
  — does NOT call Dealer, Cards, or GameSession at all today.
  — entirely separate from the sessionStorage-based GameSession above — see §6.
```

---

## 5. Existing Implementations Found

**Within `design-ui/engine/` (the one gameplay-engine tree in this repo):**
- **Card model:** `cards.js` — one implementation, self-contained, unused by anything except `dealer.js`'s `compareForSort` reference.
- **Deck (shuffle/build/deal):** **zero implementations.** Not present under any name, in any file, in any language, anywhere in this repository.
- **Suit/Rank tables:** **three duplicated copies** with identical values today:
  - `cards.js`: `Cards.SUITS` (5 entries incl. `SANS`), `Cards.RANKS` (13 entries) — the one exposed as a shared module.
  - `bidding-engine.js`: local `const SUITS`/`const RANKS`, module-private, not imported from `cards.js`.
  - `table-engine.js`: local `const SUITS`/`const RANKS`, module-private, not imported from `cards.js`.
  - These three are **not currently in conflict** (spot-checked: same suit strengths, same symbols, same rank values) but are three independently-editable copies of the same data — a real drift risk, not a hypothetical one, the moment any one of them is touched without touching the other two.
- **Dealer:** one implementation (`dealer.js`), depends on the missing `Deck`.
- **Bidding/Table/Scoring engines:** one implementation each (`bidding-engine.js`, `table-engine.js`, `scoring-engine.js`), all reachable only through `GameSession`, none of them touching `Deck` directly.
- **Session/state container:** one implementation (`session.js`), duplicated byte-for-byte as `design-ui/lobby/session.js` (the same "one copy per screen folder" pattern already established for `game-state.js` across `lobby/`, `profile/`, `login/`, `match/`).

**Outside `design-ui/engine/`:**
- `src/` — a second, but **entirely unrelated**, gameplay-adjacent implementation (score calculation from manually-entered role/bid/won inputs, no cards, no dealing). Cannot serve as a `Deck` reference.
- `MatchService` (`design-ui/match-service.js`) — the new Firestore-backed multiplayer match layer (Sprint 3.4/3.4.1). Does not implement or call any card/deck logic; its `gameState` field is the documented `{initialized:false, todo:"..."}` placeholder specifically because of this gap.
- No Kotlin file exists anywhere in this repository (`find . -iname "*.kt"` returns nothing). Comments in `bidding-engine.js`/`table-engine.js` describe themselves as *"a faithful, browser-side mirror of `GameReducer.kt`'s ... logic"* — that Kotlin source is not present here to compare against.

**Total distinct gameplay-engine implementations in this repository: one** (`design-ui/engine/*`), incomplete (missing `Deck`), currently unreachable from any shipped screen. The `src/` React app is a separate, non-overlapping prototype, not a second implementation of the same engine.

---

## 6. Production Source-of-Truth Assessment

- **`design-ui/engine/*`** is, by a wide margin, the intended production surface: every sprint since 2.6 (Firebase Player Foundation through 3.4.1) has built exclusively against `design-ui/`, and `docs/architecture/*` consistently describes `design-ui/engine/*` as "the existing engine" to be integrated, not rewritten. **However, it is currently disconnected from anything a user could load** — no screen exists yet that includes it.
- **`src/` (the Vite/React/TS app)** is a separate prototype with its own build output (`dist/`). Nothing in any sprint's work references it, and it has no card/deck concept. It is not a candidate source of truth for this dependency.
- **A real, unresolved architectural fork exists, worth flagging even though it doesn't block writing a `Deck` module:** `design-ui/engine/session.js`'s `GameSession` is a **local, `sessionStorage`-only, single-browser-tab, mock/offline construct** (its own header: *"Mock data only — no networking"*) — it is not aware of Firestore, players/{uid}, rooms/{roomId}, or matches/{matchId} in any way. `MatchService`'s `matches/{matchId}` document (Sprint 3.4/3.4.1) is the real, Firestore-backed, multi-client multiplayer session. **These two are entirely separate today.** Deciding which one should own a real multiplayer deal (does `MatchService` call `Dealer.dealHands()` directly and persist the result into `matches/{matchId}.gameState`? Or does some adapter bridge `GameSession` into Firestore?) is a genuine design decision for a later sprint — not something this audit resolves, and not something that blocks writing `Deck` itself (a `Deck` module's own API doesn't need to know which caller uses it).

---

## 7. Rules Compatibility

Per this sprint's explicit scope ("Do not perform a full game-rules audit — only verify the expected deck structure supports the following"):

| Requirement | Status | Evidence |
|---|---|---|
| Standard 52-card deck | **Supported, once built** | `Cards.DECK_SUITS` (4 suits) × `Cards.RANKS` (13 ranks) = 52. `SANS` in `Cards.SUITS` is explicitly a trump *mode*, not a fifth suit of physical cards — `DECK_SUITS` correctly excludes it. |
| Unique cards | **Supported, once built** | `Cards.createCard()` stamps a unique `id` (`suitKey-rankValue-counter`) per call; a `Deck` that calls it exactly once per (suit, rank) pair produces 52 distinct cards. Not verified at runtime — no `Deck` exists to test. |
| Deterministic / injectable shuffle where tests require it | **Not currently supported — open gap, not yet designed** | `dealer.js` calls `Deck.shuffle()` with **zero arguments**. Nothing in this repository defines or references a seedable/injectable shuffle anywhere. Whether the eventual `Deck` module should accept an optional seed/PRNG (for deterministic tests) is an **open design question for the fix**, not something already decided and merely unimplemented. |
| Four-player dealing | **Supported, once built** | `dealer.js`'s `DEAL_ORDER` (`["p1","p4","p3","p2"]`) and `session.js`'s `CANONICAL_ORDER` (`["p1","p2","p3","p4"]`) both hardcode exactly 4 seats, consistently with each other and with `table-engine.js`'s `SEAT_POS`. `dealHands()` deals 13 rounds × 4 seats = 52 cards, one at a time, matching a real deal (not four pre-sliced 13-card blocks). |
| No duplicate cards | **Depends entirely on the (missing) `Deck.deal()` implementation** | `dealer.js` calls `Deck.deal(1)` 52 times and trusts it never returns the same card twice. Cannot be verified — no such method exists to inspect. |
| Preservation of the existing authoritative game-state shape | **Confirmed compatible** | `Dealer.dealHands()`'s return shape — `{ p1: [...13 cards], p2: [...], p3: [...], p4: [...] }` — is exactly what `GameSession.dealNewHands()` assigns to `session.hands`, and exactly what `getHand(id)`/`setHand(id, cards)` read/write. **No shape mismatch anywhere in this chain.** This is the strongest piece of evidence that the fix is narrowly scoped: everything *around* the missing piece already agrees on the contract. |

**Flagged inconsistency (not fixed, per scope):** the three duplicated `SUITS`/`RANKS` tables (§5) mean "which suit/rank table does `Deck` build from" is an implicit decision the fix will have to make explicitly, or risk building against a copy that later drifts from the other two.

---

## 8. Recommended Minimal Fix

**A small, standalone `Deck` module — not an adapter, not a one-line correction, and not a wider consolidation.**

- **Not a one-line loading/export fix:** there is no existing `Deck` code anywhere to load, export, or rename correctly. The dependency is missing outright, not misconfigured.
- **Not merely a small adapter:** an adapter presupposes a real deck/shuffle implementation exists elsewhere under a different shape that just needs translating. None was found — see §5.
- **A focused `Deck` module is sufficient** — it does not require a "wider engine consolidation" first. The inferred API contract is small and already fully implied by `dealer.js`'s three call sites:
  - `Deck.reset()` — (re)build a fresh, ordered 52-card set from `Cards.DECK_SUITS` × `Cards.RANKS`, via `Cards.createCard()`.
  - `Deck.shuffle()` — randomize the built deck's order in place.
  - `Deck.deal(n)` — remove and return `n` cards from the top, guaranteeing no card is returned twice within one `reset()`.
- **One open design decision the fix should resolve explicitly, not silently:** whether `Deck.shuffle()` accepts an optional seed/PRNG for deterministic tests (§7's flagged gap) — recommend deciding this *before* writing tests for the new module, not after.
- **One open cleanup worth deciding, not required to unblock the fix:** whether to consolidate the three duplicated `SUITS`/`RANKS` tables (§5) onto `Cards.SUITS`/`Cards.RANKS` as part of the same change, or defer it. Recommend deciding deliberately either way, rather than letting the new `Deck` module quietly pick one of the three without comment.

---

## 9. Files Expected to Change (in the eventual fix — none changed in this audit)

- **New:** a `Deck` module (naming/location not yet decided — e.g. `design-ui/engine/deck.js`, matching `dealer.js`'s own header comment's expectation).
- **Changed (script includes only, not logic):** whichever HTML screen is chosen to actually load the engine for the first time would need `<script>` tags added for `cards.js`, the new `deck.js`, `dealer.js`, and (depending on scope) `session.js`/`bidding-engine.js`/`table-engine.js` — **none of these are currently included anywhere** (§3).
- **Possibly changed, pending the design decision in §6:** `MatchService.startMatch()`/`buildInitialMatchDoc()`, if the decision is "call `Dealer.dealHands()` directly from `MatchService` and persist into `matches/{matchId}.gameState`" rather than bridging through the offline `GameSession`.
- **Not expected to change:** `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `cards.js`, `session.js` — all already consume the dealing result through the existing, already-compatible `GameSession.ensureHandsDealt()` contract (§7). Writing `Deck` should not require touching any of them.
- **Not expected to change:** `firestore.rules`, any `*-service.js` file, any UI screen's markup/styling — this fix is engine-internal.

---

## 10. Risk Assessment

| Risk | Severity | Notes |
|---|---|---|
| Building `Deck` against the "wrong" of the three duplicated `SUITS`/`RANKS` tables (§5) | **Medium** | Not a correctness bug today (values agree), but a real, deferred drift risk. Recommend an explicit decision, not a silent pick. |
| No deterministic shuffle path exists yet | **Medium** | Blocks writing a fully deterministic automated test for `Deck.shuffle()`/`dealHands()` output ordering (though "no duplicates, correct count, correct shape" can still be tested without determinism). Recommend deciding this as part of the fix's design, not discovering it mid-implementation. |
| Wiring the engine into a real screen for the first time (§3, §9) | **Medium** | Since nothing currently loads this code, the FIRST integration is also the first time any of it runs in a real page — `bidding-engine.js`/`table-engine.js`'s own top-level code (e.g. `const PLAYERS = GameSession.getPlayers()`) has similarly never executed in this repo's shipped surface and has not been exercised by this audit either. Static reading found it structurally consistent, but "never yet run" carries inherent first-run risk beyond the `Deck` gap specifically. |
| Choosing between the offline `GameSession` and the Firestore `MatchService` as the real dealing owner (§6) | **Low-to-Medium, but genuinely unresolved** | Not a blocker to writing `Deck` itself, but very much a blocker to *using* `Deck` for real multiplayer dealing. Recommend resolving as an explicit design decision at the start of whichever sprint does the integration, not implicitly by whichever caller happens to be written first. |
| Missing reference documents (`GameSession.md`, `ScoringEngine.md`, `kotlinCode.ts`, the rules `.docx` — §3) | **Low for this audit's narrow scope, higher for a future full rules audit** | This audit's scope (§7) didn't need them. A future full gameplay-rules audit would be working with less authoritative context than earlier sprints' comments imply is available. |
| Regression to already-shipped code | **Very low** | Nothing currently loads `dealer.js`/`bidding-engine.js`/`table-engine.js` (§3), so a `Deck` module's introduction has no blast radius against any currently-working shipped screen. The only "regression" surface is the engine files themselves, which are already non-functional for dealing today. |

---

## 11. Recommended Next Sprint

**Sprint 3.5 (proposed scope, for the user's confirmation — not started here):**
1. Design and implement a `Deck` module satisfying the inferred contract in §8, including an explicit decision on seed/PRNG injectability for deterministic tests.
2. Explicitly decide (not silently default) which of the three duplicated `SUITS`/`RANKS` tables `Deck` builds from, and whether to consolidate the other two onto it in the same change or file that as a separate, tracked cleanup.
3. Explicitly decide (§6) whether real multiplayer dealing lives in `MatchService` (Firestore-direct) or bridges through `GameSession` — before writing the integration code, not while writing it.
4. Only after 1–3: wire `Dealer.dealHands()` (now unblocked) into `MatchService.startMatch()`'s `gameState`, replacing today's `{initialized:false, todo:"..."}` placeholder — this is the actual gameplay prerequisite this audit was commissioned to clear the way for.
5. Real, executed unit tests for the new `Deck` module in isolation (52 unique cards, correct suits/ranks, `deal(n)` never repeats within a `reset()`, `reset()` after a partial deal restores exactly 52) before any integration test that depends on it.

---

## 12. GO / NO-GO Decision

**MISSING DECK IMPLEMENTATION — Plan Sprint 3.5**
