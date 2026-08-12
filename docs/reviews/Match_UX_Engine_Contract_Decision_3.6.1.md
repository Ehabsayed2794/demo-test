# Match UX & Engine Contract Decision Audit

**Type:** Read-only decision audit. No source, tests, HTML, CSS, or docs (other than this report) were modified. This is the only file created.

**Labeling:** FACT (directly read in source), DOCUMENTED (asserted in a `docs/` file), INFERENCE (reasoned, not directly stated), OPEN DECISION (repo evidence cannot resolve it — a product call).

---

## 1. Executive Summary

The repository has one live Match screen (`design-ui/match/index.html`), which is a Firestore-status placeholder plus engine-bootstrap diagnostics — it renders no bidding or table state. `BiddingEngine` and `TableEngine` are both real, tested, and now load correctly in-browser, but have unequal UI-facing contracts: `TableEngine` exposes pure legality helpers (`canPlayCard`, `previewPlay`); `BiddingEngine` exposes none. **No authoritative external rules document (e.g. a rules `.docx`) exists anywhere in this repository** — the closest thing to "authoritative rules" is `bidding-engine.js`'s own already-implemented, already-tested reducer logic, whose inline comments reference companion docs (`BiddingState.md`, `ScoringEngine.md`, `GameSession.md`) that **do not exist in this repository** (confirmed by filesystem search). This is reported as a DOCUMENTED-vs-FACT gap, not resolved.

**Recommendation A (Match architecture): Option C — Hybrid Match Shell.**
**Recommendation B (BiddingEngine contract): a minimal, additive legality/preview API IS required** before a render layer can be built without duplicating rules — mirroring `TableEngine`'s existing pattern, at minimum viable scope.

---

## 2. Current Verified Architecture

Restating the brief's own verified state (re-confirmed by direct source reading during this audit, not re-tested — Effort: Low, per instruction):

- `cards.js → deck.js → dealer.js → session.js`: **FACT**, real dependency chain, `GameSession.ensureHandsDealt()` calls `Dealer.dealHands()` which calls `new Deck()`.
- `BiddingEngine`/`TableEngine`: **FACT**, both IIFE-wrapped (`(function (global) {...})(window)`), export `window.BiddingEngine`/`window.TableEngine` with no shared bare-identifier collision (confirmed by the diff applied in the prior sprint).
- `TableEngine` exposes `canPlayCard(playerId, card)` and `previewPlay(playerId, card)` as pure, side-effect-free exports (added Sprints 4.2.1/4.2.2, confirmed present in the current export object: `{initState, emit, resolveTrick, getState, canPlayCard, previewPlay}`).
- `BiddingEngine`'s export object is `{initState, emit, getState}` only — **no equivalent legality helper exists**.
- `MatchAdapter` (`design-ui/match-adapter.js`) is the sole Firestore↔engine seam (`bootstrapGameSession`, `applyRemoteBid`, `applyRemoteTurn`, `applyRemoteCard`, `applyRemoteTrick`, `start*Sync()` — all confirmed present by direct read).
- `design-ui/match/index.html` loads all 8 engine files + `MatchAdapter`, calls `MatchAdapter.bootstrapGameSession()` once, then calls each engine's `initState()` directly for diagnostics only — it never calls `emit()`/`canPlayCard()`/`previewPlay()`, and starts none of the four `start*Sync()` pipelines.

---

## 3. Reconstructed Match Lifecycle

| Stage | Owning service | Owning engine | State representation | UI exists? | Nav/transition needed? |
|---|---|---|---|---|---|
| 1. Match creation | `room-service.js` → `match-service.js` (`buildInitialMatchDoc`) | — | Firestore `matches/{matchId}` doc | Yes (Lobby "start match" flow) | Room → Match (**exists**) |
| 2. Match start | `match-service.js` | — | `match.status` | Yes | — |
| 3. Player/seat resolution | `match-service.js` (`seats` map) + `match-adapter.js` (`uidToSeat`/`seatToUid`) | `GameSession` (seat-id `p1..p4` world) | `matchDoc.seats`, `GameSession.getPlayers()` | Partial — Match screen lists raw uids, not seat-resolved names | — |
| 4. Initial dealing | — (no service writes hands to Firestore — **FACT**, confirmed: `Dealer`/`Deck` are local-only, never referenced by `match-service.js`) | `Dealer`/`Deck` via `GameSession.ensureHandsDealt()` | `GameSession` sessionStorage `hands` | **No** — no hand UI anywhere | Yes, once built |
| 5. Dash phase | — | `BiddingEngine` (`subPhase: "DASH"`) | `GameSession.getBiddingState()` | **No** | Yes |
| 6. Bidding (Auction) | `match-service.js` (`submitBid` — schema-only) | `BiddingEngine` (`subPhase: "AUCTION"`/`"CONFIRM"`) | same | **No** | Yes |
| 7. Contract establishment | — | `BiddingEngine` (`SubmitConfirmCall` → `subPhase: "ESTIMATES"`, then `"DONE"`) | `GameSession.getRound()` (trump/callerId/withPlayers) | **No** | Yes |
| 8. Card play | `match-service.js` (`submitCard`) | `TableEngine` (`emit({type:"PlayCard"})`) | `GameSession.getPlayState()` | **No** | Yes |
| 9. Trick resolution | `match-adapter.js` (`applyRemoteTrick`, calls `TableEngine.resolveTrick()`) | `TableEngine` | `getPlayState().lastTrick` | **No** | — (same screen as play) |
| 10. Round completion | `GameSession.completeRound()` | `TableEngine` (trick 13) + `ScoringEngine` | `getPlayState().completed`, `matchScores` | **No** | Yes (→ round summary or Final Standings — neither exists) |
| 11. Scoring | — | `ScoringEngine.calculateRoundScore()`/`applyRoundResult()` | `GameSession.getMatchScores()` | **No** | — |
| 12. Next round | — | `GameSession.nextRound()` | `getRound().number` | **No** | Yes (loop back to stage 4) |
| 13. Match completion | — | `GameSession.setWinner()` | `getWinner()` | **No** | Yes (→ Final Standings — does not exist) |

**FACT, restated from the prior audit and re-confirmed here:** stages 4–13 have zero UI. Only stages 1–3 have a real (if minimal) screen.

---

## 4. Match Screen Architecture Options

### Option A — Single Match Screen
One HTML file, phase-driven visibility. **Compatible** with `GameSession`'s existing single-session-object model (one `sessionStorage` blob already spans bidding+play+round state — no engine change needed). **Risk:** the file would need to own render logic for 3 very different visual modes (bidding controls, table/trick layout, round summary) in one script, which is exactly the kind of single-file sprawl `bidding-engine.js`/`table-engine.js` themselves already are at 700+/400+ lines *without* any render code yet.

### Option B — Separate Screens
Distinct HTML files per phase (Bidding screen, Table screen, possibly Round-Summary screen), connected via `GameState.goTo()` — the same navigation primitive already used for every existing screen-to-screen hop (Login→Lobby→Match, confirmed in `game-state.js`). **Risk:** a full page navigation between Dash→Bidding→Contract→Play is a lot of page loads for what is, per `GameSession`'s data model, one continuous session — and each engine's own `DOMContentLoaded`-driven boot sequence (`initState(); buildHand(); bindStatic(); advance();`) was written assuming exactly one load per *engine*, not per *phase within* an engine (Bidding alone has 5 sub-phases: DASH/AUCTION/CONFIRM/ESTIMATES/DONE — a full-page reload for each would fight the engine's own `state` object, which is designed to persist smoothly across sub-phase transitions in memory).

### Option C — Hybrid
One persistent Match "shell" (loads the engine scripts once, subscribes once, per §2's existing bootstrap pattern), with internal view-swapping for bidding vs. table vs. results — no full page navigation between phases, but still a normal `GameState.goTo()` hop INTO the shell from Lobby and OUT of it at match end. This matches how `match/index.html`'s own inline script is ALREADY structured today (**FACT**): it already does exactly this pattern for the Firestore-status information — one shell, one subscription, redraw-in-place on every `subscribeToMatch` delivery (`renderMatch(data)`), with `bootstrapEngineOnce()` also *staying inside the same shell* rather than navigating away. Extending it to swap in a Bidding view vs. a Table view is additive to the existing structure, not a new pattern.

---

## 5. Recommended Match Architecture

| Criterion | A: Single Screen | B: Separate Screens | C: Hybrid Shell |
|---|---|---|---|
| Compatible with current architecture | Yes | Yes, but fights the "one continuous session" model | **Yes — already the pattern in use today** |
| Compatible with `MatchAdapter` | Yes (adapter is screen-agnostic) | Yes | Yes |
| Compatible with `GameSession` | Yes (one session object either way) | Requires re-reading `GameSession` fresh on every screen load — extra defensive code | Yes, natively — session lives across view swaps in one page load |
| Compatible with `BiddingEngine`/`TableEngine` | Yes | Each engine's `DOMContentLoaded` boot fires once per navigation — awkward for 5 bidding sub-phases | Yes — one boot per engine per match, matching how each engine's `initState()` is already designed (resume-aware, not repeat-load-aware) |
| Complexity | Medium (one large file) | Medium-High (multiple files + nav wiring + engine reboot per hop) | Medium (one shell + swappable views, but views can be separately authored files/partials) |
| Navigation complexity | Lowest (no extra hops) | Highest (5+ new `GameState.goTo()` targets, several inside Bidding alone if sub-phases become screens) | Low (one hop in, one hop out) |
| State sync risk | Low | **Higher** — each navigation risks re-triggering `ensureHandsDealt()`/re-bootstrapping if not carefully guarded (both engines' `initState()` are resume-safe, but a screen reload is still an extra risk surface every hop) | Low — matches the resume-safe design `initState()` already has |
| Future extensibility | Moderate | High per-screen isolation, but at real nav cost | High — a shell can add new internal views for spectator mode, replay, etc. without new nav plumbing |
| Mobile landscape suitability | Fine (932×430 fixed frame, per `match/index.html`'s existing `#screen` scaling pattern) | Fine, same fixed frame per screen | Fine, and avoids visible full-page reloads mid-hand, which reads better at a fixed small frame size |
| Multiplayer suitability | Fine — sync pipelines (`start*Sync`) are per-matchId, not per-screen | Requires re-subscribing per screen (`MatchService.subscribeToMatch` is safely ref-counted, so not incorrect, just extra wiring each time) | **Best** — one subscription for the whole match lifecycle, matching `startBidSync`/`startTurnSync`/`startCardSync`/`startTrickSync`'s own one-call-per-matchId design |
| Roadmap fit | No roadmap doc exists in this repo (searched, not found) — **OPEN DECISION**, not resolvable from repo evidence | same | same |

**RECOMMENDED MATCH ARCHITECTURE: C — Hybrid Match Shell**

**WHY:** This is not a new idea introduced by this audit — it is the pattern `design-ui/match/index.html` **already implements today** for the data it does render (FACT, §2/§4): one shell, one subscription, one bootstrap, redraw-in-place. Extending that shell to swap between a Bidding view and a Table view is the smallest architectural delta from current reality, and it is the only option that doesn't fight either engine's resume-safe, load-once `initState()` design or `MatchAdapter`'s one-subscription-per-matchId sync model. Option B's per-phase navigation would require re-solving problems (safe re-init, re-subscription, sub-phase-level screens) that Option C simply doesn't create in the first place.

---

## 6. BiddingEngine Contract Analysis

Direct re-read of `design-ui/engine/bidding-engine.js`'s `emit()` reducer (all 4 intents) confirms:

- **Turn/current player:** `state.waitingFor` — public via `getState()`.
- **Phase/subPhase:** `state.subPhase` (`DASH|AUCTION|CONFIRM|ESTIMATES|DONE`) — public.
- **Dash:** `SubmitDashCallDecision` — legality is `state.subPhase === "DASH"` AND `state.waitingFor === playerId`, plus an internal `MAX_DASH_CALLS` (2) cap read from `state.bids` counts. **The cap value and the "already at max" check are computed inline inside `emit()`, not exposed as a separate readable field or helper.**
- **Auction bid legality:** `SubmitAuctionBid` — legality requires `tricks >= 4`, `tricks <= 13`, and (for the "does this beat the top" question) a suit-strength comparison against `SUITS[bidSuit].strength` — this comparison table (`SUITS`, suit strength ordering) is internal to the module, not exported.
- **With (Wazz):** three separate paths grant With — a live exact-match during auction, "Auction Alignment" (matching the caller's suit at any earlier point, derived from `GameSession.getBiddingState().actionHistory`), and "Estimation Jump-In" (matching the caller's number during Final Estimates). All three are computed entirely inside `emit()`; none is exposed as a queryable "is this seat With" check independent of reading `state.withPlayers` after the fact.
- **Sa'ayda (general-pass escalation):** the `×2` doubling ladder (capped at `×8`) lives inline in the `SubmitAuctionBid` general-pass branch, reading/writing `GameSession.getRound().multiplier` — not exposed as a preview.
- **Forbidden-13 / With floor / Caller cap:** all three "can I submit THIS estimate" checks (`R1`, `R2`, `R2b` in the code's own inline comments) are computed inline inside `SubmitFinalEstimate`, using `state.auctionTop`, `state.withPlayers`, `withFloorFor(pId)` (a private function, not exported), and the other seats' already-submitted bids.
- **Public getters:** only `getState()` (returns the full internal `state` object by reference) and `emit()`/`initState()`. No `canBid()`/`previewBid()`/`legalBidRange()` equivalent exists.
- **Events/emits:** `emit()` is a request/response reducer (call → mutate + return `{rejected, reason}` or `undefined`), not an event-subscription model. No `on(...)`/pub-sub exists inside `BiddingEngine` itself (confirmed — `bidding-engine.js` has no `addEventListener`-style API of its own).

**Can the UI safely derive available actions from existing public state?** Only partially. `getState()` exposes every FIELD needed as raw data (`subPhase`, `waitingFor`, `auctionTop`, `auctionSuit`, `withPlayers`, `bids`), but the RULES that turn those fields into "is bid X legal right now" (the suit-strength table, the `MAX_DASH_CALLS` cap, the Forbidden-13 formula, the With-floor formula) are private computations inside `emit()`'s switch statement — reproducing them in a UI layer would mean re-typing those formulas a second time.

---

## 7. TableEngine Comparison

`TableEngine.canPlayCard(playerId, card)` and `.previewPlay(playerId, card)` exist specifically so a caller (originally `MatchAdapter`, per Sprint 4.2.1/4.2.2's own header comments) can ask "is this legal" and "what would happen" **without** re-implementing suit-following/turn-order rules — both are pure, read-only, call `emit()`'s own internal legality logic without mutating `state`. This is the SAME category of problem `BiddingEngine` has (§6) but `TableEngine` already solved it, and did so additively (neither function replaced or changed `emit()` — confirmed unchanged since Sprint 4.2.1/4.2.2, re-verified this session's own IIFE-fix diff, which touched zero lines inside either function).

---

## 8. Bidding Legality API Decision

1. **Does the UI need to know which bids are legal?** Yes — a Bidding screen (any of Options A/B/C) must show enabled/disabled bid controls, exactly as a Table screen needs `canPlayCard()` to grey out illegal cards.
2. **Can it determine this using existing public APIs?** Only the raw ingredients (§6) — not a direct legality answer. The UI would have to re-derive: the suit-strength ordering, the Dash-call cap comparison, the Forbidden-13 formula, and the With-floor formula.
3. **Would deriving bid legality inside the UI duplicate game rules?** **Yes** — each of the four formulas above is a real rule from `bidding-engine.js`'s own reducer, not incidental bookkeeping. Re-typing them in a render layer is textbook rule duplication.
4. **Would such duplication violate the project's architecture?** **Yes** — this repository's own established, repeatedly-restated principle (see `docs/architecture/BidValidation.md`'s explicit "don't duplicate rules logic across files" discipline, and `MatchAdapter`'s own header comment's "every legality decision is made BY calling into the real, unmodified engine, never re-implemented here") applies identically to a future render layer.
5. **Is a new BiddingEngine public legality API actually required?** **Yes**, by the same reasoning `TableEngine.canPlayCard()`/`previewPlay()` already established as this project's own precedent for exactly this situation.
6. **Minimum API needed (conceptual only — not implemented, not named definitively):** one pure, read-only function answering "is this action legal for this seat right now" across the four DASH/AUCTION/CONFIRM/ESTIMATES intent shapes — conceptually `canSubmit(playerId, intent)` or four smaller phase-specific checks (e.g. `canDashCall`, `canAuctionBid`, `canConfirmCall`, `canEstimate`) mirroring `emit()`'s own four-case switch, whichever proves the smaller diff when actually implemented. This audit does not choose between "one combined function" and "four small ones" — that is an implementation-time call, not a decision this audit needs to force.
7. N/A (answer to Q5 was yes).

**Rules-preservation check:** any such function must be a **pure read of `emit()`'s existing legality conditions**, never a re-derivation — i.e., implemented by literally reusing the same conditionals `emit()` already evaluates (refactored into a shared private helper `emit()` itself also calls), not by writing new, independently-derived logic that could drift out of sync. This preserves Dash/With/Sa'ayda/auction-alignment/turn-order exactly as `TableEngine`'s own `canPlayCard()` does for its own rules (confirmed by that function's own structure — it calls the SAME internal `legalCards()`/`isLegal()` helpers `emit()` itself uses, not a parallel copy).

**Rules-documentation ambiguity, reported per instruction:** no authoritative external rules document exists in this repository to check the above against (see Executive Summary). The engine's own already-tested, already-shipped behavior (954/954 passing tests covering Dash/With/Sa'ayda/Auction Alignment/Forbidden-13 scenarios per `tests/match-flow-*.test.cjs`) is therefore the only available authority, and IS internally consistent — no ambiguity was found WITHIN the code+tests, only the absence of an independent external document to cross-check them against.

---

## 9. UI State Availability Matrix

| Datum | Source | Classification |
|---|---|---|
| Current phase/subPhase (bidding & play) | `BiddingEngine.getState().subPhase`, `TableEngine.getState().phase` | AVAILABLE NOW |
| Current turn/waiting player | `getState().waitingFor` / `.turn` | AVAILABLE NOW |
| Player names/avatars | `GameSession.getPlayers()` | AVAILABLE NOW |
| Dealt hands | `GameSession.getHands()`/`getHand(id)` | AVAILABLE NOW |
| Bids placed so far | `BiddingEngine.getState().bids` | AVAILABLE NOW |
| Auction top/suit/bidder | `getState().auctionTop/.auctionSuit/.auctionBidder` | AVAILABLE NOW |
| With players | `getState().withPlayers` | AVAILABLE NOW |
| Trump/caller (post-bidding) | `GameSession.getRound()` | AVAILABLE NOW |
| Current trick plays | `TableEngine.getState().currentPlays` | AVAILABLE NOW |
| Last trick winner | `TableEngine.getState().lastTrick.winnerId` | AVAILABLE NOW |
| Tricks won per seat | `getState().tricksWon` | AVAILABLE NOW |
| Match/round scores | `GameSession.getMatchScores()` | AVAILABLE NOW |
| **Is bid X legal right now** | none | REQUIRES NEW API (§8) |
| **Is card X legal right now** | `TableEngine.canPlayCard()` | AVAILABLE NOW |
| **What would playing card X do** | `TableEngine.previewPlay()` | AVAILABLE NOW |
| Sa'ayda multiplier | `GameSession.getRound().multiplier` | AVAILABLE NOW |
| Dash-call remaining slots (2-max) | Internal to `emit()`'s `SubmitDashCallDecision` branch | AVAILABLE INTERNALLY BUT NOT PUBLIC — DERIVABLE WITHOUT RULE DUPLICATION (a simple count of existing `DASHCALL`-type bids in the already-public `state.bids`, not a hidden formula) |
| Forbidden-13 value for last estimator | Internal `emit()` computation | AVAILABLE INTERNALLY BUT NOT PUBLIC — REQUIRES NEW API if the UI must show it in advance (the formula itself, `13 - otherSum`, is simple, but "is this the last estimator" and "which bids count" are exactly the kind of condition §8 says shouldn't be re-typed) |
| Suit-strength ordering | Internal `SUITS` table | AVAILABLE INTERNALLY BUT NOT PUBLIC — feeds directly into the REQUIRES NEW API item above |

---

## 10. MVP Vertical Slice

Restating the intended flow against what's actually available (engine-level only — no render code implied by this list):

| Stage | Available? |
|---|---|
| Match Start | **Available** — `match-service.js`/`room-service.js`, tested |
| Deal | **Available** — `GameSession.ensureHandsDealt()`, tested, browser-confirmed |
| Dash | **Available** — `BiddingEngine.emit({type:"SubmitDashCallDecision"})`, tested |
| Bidding | **Available** — `emit({type:"SubmitAuctionBid"})`, tested |
| Contract | **Available** — `emit({type:"SubmitConfirmCall"})`, tested |
| Playing | **Available** — `TableEngine.emit({type:"PlayCard"})` + `canPlayCard()`, tested |
| Trick | **Available** — `resolveTrick()`, tested, and independently re-verified via `MatchAdapter.applyRemoteTrick()` this session |
| Score | **Available** — `ScoringEngine.calculateRoundScore()`/`applyRoundResult()`, tested |
| Next Round | **Available** — `GameSession.nextRound()`, tested |

**Every engine-level stage is already available and tested.** The ONLY missing piece, repeated once more for clarity since it is the single blocking fact this entire audit chain keeps re-confirming, is the render layer (§4/§9) plus, specifically for Bidding, the legality API gap (§8). This audit does not implement either.

---

## 11. Risks

**HIGH**
- Building a Bidding UI directly against `BiddingEngine.getState()` without first adding the legality API (§8) would almost certainly result in ad hoc, duplicated rule logic scattered across a new render file — the exact anti-pattern this project's own `BidValidation.md` and `MatchAdapter.js` header comments already warn against.
- No authoritative external rules document exists to verify future engine changes against (Executive Summary) — any FUTURE bidding-contract implementation sprint should treat the existing, tested `bidding-engine.js` + `tests/match-flow-*.test.cjs` behavior as the frozen reference, and flag (not silently "fix") any place a UI requirement seems to want different behavior.

**MEDIUM**
- `BiddingEngine` and `TableEngine` still assume a `DOMContentLoaded → advance() → render()` synchronous model that doesn't obviously compose with `MatchAdapter`'s asynchronous Firestore-driven `applyRemote*()` calls (documented already in the prior Gameplay Render Layer Audit, restated here since it affects how any new legality API would need to be CALLED, not just what it returns).
- Comment-referenced companion docs (`BiddingState.md`, `ScoringEngine.md`, `GameSession.md`) do not exist in this repository — anyone implementing the next sprint should not assume they can consult them.

**LOW**
- The three-turn-field ambiguity (`GameSession.getTurn()` vs `getBiddingState().turnId` vs `getPlayState().turnId`, previously documented) is unrelated to the legality-API decision itself but will matter once the Hybrid shell needs to know "whose turn, in general" across phase swaps.

---

## 12. Next Sprint Scope

**Objective:** Add the minimum additive BiddingEngine legality/preview contract (§8) — analogous to `TableEngine.canPlayCard()`/`previewPlay()` — with zero change to `emit()`'s existing behavior, rules, or return shapes.

**Files likely to be CREATED:** none required — this is additive to an existing file.

**Files likely to be MODIFIED:**
- `design-ui/engine/bidding-engine.js` — add the new pure export(s), implemented by extracting/reusing `emit()`'s own existing conditionals (not new logic).
- `tests/` — new test file or additions to an existing bidding test file, covering the new export(s) against the same scenarios already proven in `tests/match-flow-*.test.cjs`.
- `docs/architecture/GameEngine.md` (or a new, equivalent doc) — document the new export exactly as `TableEngine.canPlayCard()`/`previewPlay()` were documented in their own sprints.

**Files that MUST remain untouched:**
- `design-ui/engine/table-engine.js`, `scoring-engine.js`, `session.js`, `dealer.js`, `deck.js`, `cards.js`
- `design-ui/match-adapter.js`, `design-ui/match-service.js`
- `firestore.rules`
- Any render/UI file (none exist yet to accidentally touch, but explicitly out of scope until the Match architecture decision in §5 is separately authorized)

**Recommended effort: Low.**

**Why:** `TableEngine.canPlayCard()`/`previewPlay()` already provide a proven, minimal template for exactly this kind of addition (Sprints 4.2.1/4.2.2 were themselves scoped as small, additive, single-function sprints, not rewrites). `BiddingEngine`'s legality conditions (§6) already exist, fully written, inside `emit()` — the work is extraction into a callable, read-only form, not new rule design. This is smaller in scope than the render-layer decision itself and does not touch navigation, HTML, or CSS at all.

---

## 13. Recommended Effort

**LOW** — for the next sprint as scoped in §12 (the BiddingEngine contract addition only). Note this is a DIFFERENT, larger effort question from "build the actual render layer" (§5's Hybrid Shell decision) — that follow-on sprint, once separately authorized, should be scoped and estimated on its own, since it involves new HTML/CSS/navigation the contract sprint does not.

---

## 14. Final Decision

BIDDING CONTRACT CHANGE REQUIRED — ENGINE CONTRACT SPRINT FIRST
