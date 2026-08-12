# Player Hand Synchronization — Architecture Discovery Report

**Type: Read-only architecture discovery. No engine, UI, service, rules, or test file was modified. This document is the only file created by this sprint.**

---

## 1. Executive Summary

Every prior sync layer in this project (bids, bidding actions, cards, tricks, rounds, match completion, rematch votes) is genuinely server-authoritative: one write path, one Firestore transaction, one shared listener, independently re-verified by `firestore.rules`. **Player hands are the one exception.** Each client deals its own hand locally, from `Math.random()`, and never writes it to Firestore. Nothing today makes two different browsers looking at the same match agree on who holds which card. This is not a new discovery — it has been flagged, unchanged, by three separate prior sprints (Sprint 5, the Round Lifecycle sprint, and the Sprint E verification pass) — this report is the first one to actually stop and design the fix before touching code, as this project's own established pattern requires for foundational changes (see the Round Lifecycle sprint's own schema-options table, which this report deliberately mirrors).

**Recommendation, stated up front:** **Option A — Server-Authoritative Deal, backed by a per-seat hands subcollection** (`matches/{matchId}/hands/{seatId}`, one document per seat, readable only by that seat's own uid). Full reasoning in §6.

---

## 2. Current-State Findings

All verified directly against source in this pass, not assumed from prior reports.

### Current dealing flow
- `design-ui/engine/cards.js` — defines what a card is (`Cards.createCard(suitKey, rank, owner)`), pure, no randomness.
- `design-ui/engine/deck.js` — `new Deck()` builds a fresh, ordered 52-card set; `Deck.prototype.shuffle(rng)` runs Fisher–Yates, defaulting to `Math.random` (an injectable `rng` exists for tests only — real gameplay always uses `Math.random`); `Deck.prototype.draw()` pops one card.
- `design-ui/engine/dealer.js` — `Dealer.dealHands(seatOrder)`: `new Deck()`, `deck.shuffle()`, draws 13 rounds × 4 seats one card at a time (mirrors a real deal), stamps `card.owner`, sorts each hand, returns `{p1:[...13], p2:[...], p3:[...], p4:[...]}`.
- `design-ui/engine/session.js` — `GameSession.dealNewHands()` calls `Dealer.dealHands()` and stores the result as `session.hands` (in-memory, then `persist()`ed); `GameSession.ensureHandsDealt(opts)` is the one funnel every screen is supposed to call — deals only if `hasDealtHands()` (keyed on `dealState.roundNumber === round.number`) is false, otherwise returns the already-stored hands.
- **`persist()` writes to `sessionStorage` only** (`STORAGE_KEY = "estimation_game_session_v1"`, confirmed directly at `session.js` lines ~24/124) — per-browser-tab, never shared, never sent to Firestore, never read by any other client.

### Current Firestore state
- `matches/{matchId}` (the actual, current, implemented schema — confirmed directly in `match-service.js`'s `buildInitialMatchDoc()`): `roomId, players, status, createdAt, currentRound, maxRounds, extendedRounds, dealer, turn, seats, version, biddingOpen, bids, lastBidSeat, cardLog, lastCardSeat, cardPhase, biddingLog, gameState`.
- `gameState` is, verbatim: `{ initialized: false, todo: "Dealer.dealHands() (backed by a real, tested Deck module since Sprint 3.5) is not called from here — MatchService must remain Firestore-only and never call the engine directly. Real dealing needs a deliberate server/client authority design first." }` — an explicit placeholder, unchanged since it was first written, still true today, still the exact gap this report addresses.
- No `hands` field exists anywhere on the match document. No `matches/{matchId}/hands/*` subcollection exists. `firestore.rules` places `gameState` in every `isValidNewMatch()`/`isValidNewRematchMatch()` key allowlist (`hasOnly([...'gameState'...])`) but **validates nothing about its shape or contents** — confirmed by direct grep, `gameState` appears only inside allowlists, never on the right-hand side of any comparison.
- `docs/architecture/FirestoreSchema.md` already contains a design-only ("nothing in this document is deployed," its own words) discussion of exactly this problem (the "hands problem" section) recommending a split subcollection — written before the actual current schema existed (it describes `dealerId`/`round.number`/`turnId`/nested `biddingState`, none of which match the actual flat `dealer`/`currentRound`/`turn`/`bids` fields implemented today). Its reasoning is sound and reused in §6 below, but its exact field names are stale and not used as-is.
- `docs/architecture/SecurityArchitecture.md` already sketches one rule row for `matches/{matchId}/hands/{uid}` "if the split-subcollection design... is adopted" — never implemented, confirmed by grep (`firestore.rules` has no `hands` match block anywhere).

### Current synchronization flow
- `MatchService.subscribeToMatch(matchId, cb)` is the one shared, ref-counted Firestore listener every sync primitive in this project reuses (bids, bidding actions, cards, tricks, rounds, match completion, rematch vote). It delivers the match document; it has never delivered hands, because hands aren't on the document.
- `MatchAdapter.bootstrapGameSession(matchDoc)` — translates `roundNumber`/`dealerSeat`/`turnSeat` into `GameSession.setRound()`/`setDealer()`/`setTurn()`. Confirmed directly: **it does not deal cards** ("Does NOT deal cards" is in its own doc comment) — dealing happens later, independently, per client, inside `BiddingEngine.initState()`'s own call to `GameSession.ensureHandsDealt()`.
- I directly proved this empirically during the Sprint E verification pass (not re-derived here, cited for continuity): loading `match/index.html` against a normal match doc and a rematch-created match doc of identical shape produced byte-identical `"hands dealt (p1:13 p4:13 p3:13 p2:13)"` diagnostic output on **both** — because both dealt locally, independently, with no server involvement either way. The rematch feature does not worsen this gap; it inherits it exactly as-is.

### Current authority model
Every other gameplay write in this codebase follows the same shape: client submits an intent → `MatchService.<verb>()` runs one Firestore transaction, re-validated independently by `firestore.rules` → the single shared listener delivers the accepted result to every seated client. Hands are the **only** gameplay-relevant piece of state that never goes through this pattern at all — there is no `MatchService.dealHands()`, no transaction, no rule, nothing. Authority for hands today is "whatever `Math.random()` returns on this browser," full stop.

### Exact verified gap
Two clients (or the same client across a real cross-device reconnect) viewing the same `matches/{matchId}` document at the same round will, with probability effectively 1, have computed **two different 52-card shuffles** and therefore two different, mutually-inconsistent sets of "who holds which card." Every rule/engine check downstream (`canPlayCard()`, follow-suit, trump comparison) is internally consistent per-client but not cross-client-consistent, because the ground truth it's checking against (the local hand) was never agreed on in the first place.

---

## 3. Requirements

Derived from the brief and from this project's own already-established conventions (never invented fresh):

1. Every seated client in the same match/round must converge on the exact same 52-card shuffle and the exact same hand assignment.
2. No client may read another seat's hidden cards through the normal client path.
3. No client may write (fabricate or alter) another seat's hand.
4. The mechanism must work on **Spark** (no Cloud Functions) — this project's own hard, repeated constraint since the Rematch Vote sprint's own header comment and every sprint before it.
5. Must reuse the existing single-shared-listener (`subscribeToMatch()`) pattern wherever possible — no second listener per match unless genuinely unavoidable (mirrors the Round Lifecycle sprint's own "exactly one listener" requirement).
6. Must be idempotent under race conditions (two clients both attempting to trigger the deal) — mirrors every existing write path's own "first commit wins, everyone else no-ops" convention (`startMatch()`, `createRematchVote()`, `advanceToNextRound()`).
7. Must survive round transition and rematch (a new deal each round, a new deal on a rematch's new match) without breaking the append-only/idempotency guarantees those two features already established.
8. Must not require re-deriving or duplicating any existing gameplay rule (follow-suit, trump, legality) — those remain exclusively `table-engine.js`'s job, unchanged.

---

## 4. Architecture Options

### Option A — Server-Authoritative Deal

One deterministic write path deals the whole deck once, at the moment dealing needs to happen (match creation, and again at every round transition/rematch), and persists the authoritative per-seat hands to Firestore. "Server-authoritative" here means **Firestore-transaction-authoritative**, not a literal server/Cloud Function — this project has no Cloud Functions and this option doesn't require any. Any seated client's `MatchService` call can be the one that performs the deal, exactly the same "any client may attempt it, the transaction makes it safe" pattern `startMatch()`/`advanceToNextRound()`/`createRematchMatch()` already use.

- **Mechanism:** a new `MatchService.dealRound(matchId, roundNumber)` (or folded into the existing `startMatch()`/`advanceToNextRound()`/`createRematchMatch()` transactions directly — see §10) calls `Dealer.dealHands()` **once**, inside the transaction, and writes the result split by seat into a subcollection (see §7). `Math.random()` is still the underlying entropy source, but it only ever runs inside the ONE transaction attempt that actually commits — every other simultaneous attempt observes the already-written hands and no-ops, exactly like every other "first commit wins" write in this codebase.
- **Trust model:** the deal is generated by whichever client's `MatchService` call happens to win the race, but the RESULT becomes authoritative the instant it commits — no client, including the one that generated it, is ever trusted again after that write. Every other client (and the dealing client itself, on every subsequent load) treats the persisted hands as ground truth, never regenerates them locally.

### Option B — Deterministic Deal + Shared Authority (seed-based)

Firestore stores only a small, authoritative **seed** (or an equivalent compact authority value, e.g. a server timestamp + matchId hash); every client independently runs the *same* deterministic shuffle algorithm against that seed and arrives at the identical 52-card order without the full deal ever being written out card-by-card.

- **Mechanism:** `Deck.prototype.shuffle(rng)` already supports an injectable `rng` (built for tests, but directly reusable) — a seeded PRNG (e.g. a small deterministic generator seeded from the stored seed) could be substituted for `Math.random` and produce identical results on every client, given the same seed.
- **Trust model:** the seed's *origin* still needs an authority decision (which client wrote it, and how do we prevent two clients writing two different seeds) — this collapses to exactly the same idempotent-transaction problem Option A has, just for a smaller payload (one seed field vs. four hands).

### Option C — Hybrid (seed-derived, but hands still persisted per seat)

Combines both: an authoritative seed is written once (Option B's mechanism), but instead of trusting every client to independently reproduce the shuffle forever, the SAME transaction that writes the seed also computes and persists the resulting per-seat hands (Option A's storage shape). This is not a third fundamentally different design — it is Option A with the entropy source made auditable/reproducible, at the cost of one extra field and no real benefit given this project's actual constraints (see §5, row "Firestore complexity").

No option beyond A/B/C was identified as viable given this project's Spark-only, single-listener, no-Cloud-Functions constraints — a fourth "trust the client's own claimed hand" option was considered and discarded immediately (it reintroduces exactly the "why does the security note in `FirestoreSchema.md` call this the one genuinely hard problem" issue and provides zero cheating resistance — not a serious option, not scored in §5).

---

## 5. Option Comparison Matrix

| Dimension | Option A (Server-Authoritative Deal) | Option B (Seed-Only) | Option C (Hybrid) |
|---|---|---|---|
| Security (write-tamper resistance) | High — hands written once, in one transaction, by generic structural rules (seat-ownership only, no content trust) | Medium — the SEED is easy to protect (one small field), but every client re-derives the full deal locally from it, so a compromised/patched client could recompute or manipulate its OWN downstream state (still can't affect others', since seed is still transaction-protected) | High — same as A, seed adds no security benefit here, only auditability |
| Hidden information (opponents' cards) | **Solved directly** — split subcollection per seat, each seat's doc readable only by its own uid (see §7/§9) | **Not solved by itself** — every client that can compute the shuffle can compute EVERY seat's hand from the same seed; hiding requires either a second per-seat secret or accepting the FirestoreSchema.md-documented "readable via console/API, not via UI" limitation | Solved — inherits A's storage shape |
| Determinism | Deterministic by construction (one write, everyone reads the same value) | Deterministic by construction, but ONLY if every client's shuffle implementation is byte-for-byte identical forever (a future engine change to `fisherYates()` could silently break replay of an already-seeded-but-not-yet-fully-dealt round — no such risk exists for A, since A never re-derives) | Deterministic; same reproducibility risk as B if the seed is ever meant to be re-derived later, avoided in practice if hands are also persisted (as C proposes) |
| Race safety | High — reuses the exact "first commit wins" transaction pattern already proven 4 times in this codebase (`startMatch`, `createRematchVote`, `advanceToNextRound`, `createRematchMatch`) | Same transaction pattern, smaller payload | Same |
| Reconnect / late-join behavior | Simple — a reconnecting/late client just reads its own already-persisted hand document; no recomputation needed | Requires re-running the SAME shuffle algorithm again on reconnect — any client-code drift between "when the round was dealt" and "when this client reconnected" (e.g. a hot-reloaded page with a patched `deck.js`) is a real, if narrow, correctness risk A doesn't have | Simple — same as A (hands already persisted) |
| Firestore complexity | Medium — one new subcollection (`hands/{seatId}`), 4 docs/match/round, cleared or superseded each round | Low — one new field, no subcollection | Medium-High — both a seed field AND the subcollection; strictly more moving parts than A for no additional benefit this project needs |
| Rules complexity | Medium — one new create/update rule per seat's hand doc, structural only (seat ownership, round tag, shape) — no gameplay legality duplicated, matching every existing rule's own established restraint | Low — one rule protecting a single seed field | Medium-High — rules for both the seed and the subcollection |
| Implementation complexity | Medium — new `MatchService` method(s), new `MatchAdapter` read path, new rules block, existing engine files (`dealer.js`/`deck.js`/`session.js`) untouched or minimally touched | Medium — requires a NEW deterministic PRNG (the existing `fisherYates(cards, rng)` injectable-rng hook already supports this cleanly), but also requires every client to run it identically forever | High — union of both |
| Scalability | Read cost: 4 extra small doc reads per match (once per round) — negligible against this project's own documented "one listener, 15-30 min match" quota profile | Read cost: near zero (one field, already inside the existing match doc read) | Same as A, plus the extra seed field |
| Cheating resistance | High — a determined cheater reading Firestore directly still sees only their OWN hand doc under real rules (or, if rules aren't perfect immediately, at worst the SAME "console/API access" caveat `FirestoreSchema.md` already honestly documents for the current no-hands-at-all state — never worse) | Low-Medium — the seed itself, if ever exposed to a client before it's supposed to know it (e.g. a future spectator mode, or a bug), lets that party compute **every** seat's hand in advance, including their own future draws in games where cards are drawn progressively (not applicable to a fixed 13-card deal dealt all at once here, but still a structurally weaker property than A) | High — same as A |
| Compatibility with current architecture | **High** — extends the exact pattern (Firestore-transaction-authoritative, single shared listener, generic/structural-only rules) already used for every other gameplay write in this codebase | Medium — introduces a NEW concept (a seeded PRNG shared across client/rules-verification boundary) this codebase has never needed before; CEL (`firestore.rules`) has no practical way to verify a shuffle's correctness anyway, so the rules-side benefit of a seed over a direct write is close to zero | Medium — most complex option for the least net-new benefit given this project's actual needs |

---

## 6. Recommended Architecture

**Option A — Server-Authoritative Deal, via a per-seat `matches/{matchId}/hands/{seatId}` subcollection.**

Not "it depends" — Option A is the right choice for **this specific project**, for concrete, evidenced reasons:

1. **It is the only option that actually solves hidden information**, which every existing project doc (`FirestoreSchema.md`'s own "hands problem," `SecurityArchitecture.md`'s own sketched-but-unbuilt rule row) already flags as the hard requirement, not an optional nice-to-have. Option B alone does not solve this at all; Option C solves it only by collapsing back into Option A's own storage shape anyway.
2. **It is a mechanical extension of a pattern already proven four separate times in this exact codebase** (`startMatch()`, `createRematchVote()`, `advanceToNextRound()`, `createRematchMatch()` — all "any client may attempt, one transaction, first commit wins, everyone else no-ops"). Option B would introduce a genuinely new concept (a shared deterministic PRNG contract between client code and a rules layer that can't verify it) that nothing else in this project has ever needed.
3. **It costs this project nothing it doesn't already pay elsewhere.** The "4 extra small documents, 4 extra listeners-or-reads per match" cost is the same shape of cost `FirestoreSchema.md` already accepted for the identical reason back when it first recommended this design, and is negligible against the project's own documented read-frequency profile (one long-lived listener per client for 15-30 minutes already).
4. **It requires no Cloud Function and no schema-breaking migration** — purely additive (a new subcollection, new rule block, new service/adapter functions), exactly matching the Round Lifecycle sprint's own "Option A" selection criterion when it faced an analogous three-way schema choice.

---

## 7. Proposed Firestore Schema

```
matches/{matchId}                          (existing document, UNCHANGED shape except below)
  ...existing fields unchanged (roomId, players, status, currentRound, dealer, turn,
     seats, version, biddingOpen, bids, lastBidSeat, cardLog, lastCardSeat, cardPhase,
     biddingLog, extendedRounds, maxRounds)...
  gameState: {
    initialized: true,                     // becomes true once Round 1's deal commits
    dealtRound: 1                          // NEW — which round's deal is currently authoritative;
                                            // compared against currentRound the same way
                                            // dealState.roundNumber already is client-side today
  }

matches/{matchId}/hands/{seatId}            (NEW subcollection — "p1".."p4", never a uid as the doc id,
                                              matching this project's existing seat-id convention, not
                                              player-uid convention, so a seat's OWNER — not a
                                              hardcoded uid — determines readability; see §9)
  {
    seatId: "p1",                          // redundant with the doc id, kept for query/debug convenience
                                            // — matches this project's own existing redundancy pattern
                                            // (vote docs already store `matchId` even though it's implicit
                                            // in the parent path)
    round: 1,                              // which round this hand belongs to — the SAME round-tagging
                                            // technique the Round Lifecycle sprint already applied to
                                            // biddingLog/cardLog entries, reused verbatim, not reinvented
    cards: [ { suit: "SPADES", rank: { v: 14, s: "A" } }, ... 13 entries ... ],
                                            // OPAQUE, generically-shaped — mirrors submitCard()'s own
                                            // isValidGenericCardValue() shape exactly (suit + rank.v only,
                                            // no id/displayName/owner/played — those are derived/engine-
                                            // internal, never stored server-side, matching this project's
                                            // established "generic vs. gameplay" line for card data)
    version: 1                             // per-seat-doc optimistic concurrency, same convention as
                                            // every other document in this project
  }
```

**Authoritative state:** `matches/{matchId}/hands/{seatId}` (all 4 docs, for the CURRENT round only) and `matches/{matchId}.gameState.dealtRound`.
**Player-private state:** each `hands/{seatId}` document — readable only by the uid occupying that seat (per the parent match's own `seats` map, resolved the same way every other per-seat rule in `firestore.rules` already resolves it).
**Public state:** everything already on `matches/{matchId}` today (round number, turn, bids, cardLog, etc.) — unchanged, still readable by every seated player, as today.
**Derived client state:** `GameSession.hands` (in-memory + `sessionStorage`) becomes a **cache of the Firestore-authoritative hand**, not the source of truth it is today — `ensureHandsDealt()`'s eventual future job changes from "deal if not yet dealt" to "read from Firestore if not yet cached, deal only as a same-machine reload optimization backed by the already-fetched authoritative value" (see §11).

**Why NOT a field directly on `matches/{matchId}` (rejected sub-option):** exactly the "hands problem" `FirestoreSchema.md` already identified — a `hands: {p1:[...], p2:[...], ...}` field on the one document every seated player already reads would, by construction, let every player read every other player's cards. The subcollection is the one schema shape that avoids this without a Cloud Function.

**Prior rounds' hand docs:** superseded (not deleted — matching this project's own established "never delete, never destructively clear" convention, e.g. `cardLog`/`biddingLog`'s own append-only, never-rewritten design) — a new round's deal simply increments `round` on a freshly-created set of 4 documents; nothing reads a stale round's hand doc because every read path filters on `round === currentRound` the same way `applyRemoteCard()`/`applyRemoteBiddingAction()` already filter log entries on their own `round` tag.

---

## 8. Authority & Trust Model

- **Client authority:** none, for hand CONTENT. A client may only ever *request* that a deal happen (call `MatchService.dealRound()` or have it folded into an existing transaction — see §10); it never supplies card values, suits, or assignments as part of that call. This exactly mirrors `createRematchMatch()`'s own "seats/players derived purely from the vote's own already-authoritative data, never from any client-supplied parameter" discipline.
- **Firestore authority:** the transaction that successfully commits the `hands/{seatId}` writes is authoritative from that moment forward. No later write may alter an already-committed round's hand documents (structurally denied by rules — see §9).
- **Trusted operations:** reading one's OWN seat's hand document; triggering a deal for a round that has no hand documents yet (idempotent, first-commit-wins).
- **Forbidden client operations:** reading another seat's hand document; writing/patching any seat's hand document directly (all writes flow through the one dealing transaction, never a generic `update()`); supplying card content as part of a deal request; re-dealing a round that already has hand documents (structurally a no-op, never an error, matching every other idempotent write in this project).

---

## 9. Security Rules Impact

(Description only — `firestore.rules` is **not modified** by this report.)

- **New `match /hands/{seatId}` block**, nested under the existing `match /matches/{matchId}` block, sibling to the existing `rematchVote` block:
  - `allow get: if` the requesting uid equals `parentMatch().seats.get(seatId, null)` — resolved via the exact same `parentMatch()`/seat-ownership pattern the `rematchVote` block already established (`isSeatedInParent()`'s own sibling, scoped to ONE seat instead of "any seat").
  - `allow list: if false` — same as `rematchVote`, for the same reason (a `list` query could otherwise enumerate all 4 hand docs at once, defeating the whole point of splitting them).
  - `allow create: if` the acting uid owns the target seat (per parent `seats`), the parent match is in a state where dealing is legitimate (`gameState.dealtRound` behind `currentRound`, or the match was just created/just transitioned — the precise condition mirrors `isValidNewRematchVote()`'s own "parent must be in the right state" check), the written `round` equals the parent's `currentRound`, and the `cards` array is a well-formed 13-entry generic card list (reusing `isValidGenericCardValue()`'s own CEL-equivalent shape check, not inventing a new one).
  - `allow update: if false` — a hand document, once created for a round, is immutable for that round (matches `cardLog`'s own append-only philosophy, applied here as "write-once per round" instead of "append-only," since a hand isn't a growing log).
  - `allow delete: if false` — matches every other collection in this project; nothing is ever deleted, only superseded by a later round's fresh documents.
- **Existing `matches/{matchId}` update rules** gain one more legitimate shape (the deal-commit write that flips `gameState.dealtRound`) — additive, `||`'d onto the existing update clause, exactly like every prior sprint's own rule addition (`isValidRoundAdvance()`, `isValidRematchMatchLink()`).
- **What is necessarily exposed to clients regardless:** the FACT that a hand document exists and which round it's for (any seated player can infer "dealing has happened for round N" from the parent match's own `gameState.dealtRound`, which is intentionally public — this is not a secret and doesn't need to be one).
- **Honest limitation, stated per this project's own established convention (same phrasing pattern as the Rematch Vote sprint's own rules-compile-safety disclosure):** these proposed rules have not been written or compiled yet. Recommend the SAME verification method Sprint E actually used and proved works in this environment — a real Firestore Rules emulator pass, not only a JS simulation — before treating any future implementation of this rule block as trustworthy.

---

## 10. Initialization & Race-Condition Protocol

```
Match creation (startMatch() / createRematchMatch())
  → gameState starts { initialized: false, dealtRound: 0 }   (0 = "no round dealt yet",
                                                                matching currentRound's own 1-based start)
  → NO deal happens inside startMatch()/createRematchMatch() itself — kept as a SEPARATE
    transaction/step, exactly like createRematchVote()/createRematchMatch() are kept
    separate from each other today, so a slow/failed deal never blocks match creation
    and match creation never blocks on engine work

Round initialization / deal generation
  → any seated client, on observing gameState.dealtRound < currentRound via the existing
    single subscribeToMatch() listener, calls MatchService.dealRound(matchId, currentRound)
  → transaction: re-reads the match fresh; if gameState.dealtRound >= currentRound already,
    NO-OP (idempotent, matches advanceToNextRound()'s own ALREADY_ADVANCED shape) — this is
    what makes "two clients both try to deal" safe: whichever transaction actually commits
    first wins, the loser's attempt just discovers the work is already done
  → the WINNING transaction: Dealer.dealHands() runs ONCE, inside the transaction callback;
    tx.set() writes all 4 matches/{matchId}/hands/{seatId} docs; tx.update() flips
    gameState.dealtRound to currentRound in the SAME transaction (atomic, same pattern as
    createRematchMatch()'s paired new-match-create + vote-link write)

Persistence
  → committed the instant the transaction returns; every seated client's existing listener
    delivers the gameState.dealtRound change automatically (no new listener needed for
    THIS signal — it's a field on the document everyone already subscribes to)

Client synchronization
  → each client, on observing dealtRound advance to the round it cares about, reads its
    OWN matches/{matchId}/hands/{mySeatId} document (a single get() or a dedicated small
    listener — see §11) and populates GameSession's hand cache from that authoritative value

Gameplay
  → unchanged — table-engine.js/bidding-engine.js keep reading through
    GameSession.ensureHandsDealt() exactly as today; only WHERE that function's underlying
    data comes from changes (Firestore-backed cache vs. local Math.random())

Round transition (advanceToNextRound())
  → the SAME transaction that already bumps currentRound (existing, unmodified) can ALSO
    reset gameState.dealtRound to the PREVIOUS round's value (i.e. "not yet dealt for the
    new round") in the same atomic write — this makes the round-transition and the
    "someone needs to deal again" signal a single, already-idempotent write, not a new race

Next deal
  → identical to "Round initialization" above, driven by the same dealtRound < currentRound
    comparison — no special-casing for round 2 vs. round 1

Rematch
  → createRematchMatch() (existing, unmodified transaction) sets the NEW match's own
    gameState.dealtRound: 0 exactly like a fresh match creation — the new match's own
    "Round initialization" step deals fresh, independent hands; nothing about the OLD
    match's hands is ever read, copied, or reused (a rematch is a genuinely new deal,
    per this project's own product decision already recorded in the Rematch Vote report)
```

**Idempotency, summarized:** every step above reuses the exact "re-read fresh inside the transaction, no-op if already done, first commit wins" shape already proven in this codebase 4 times. No new race-handling technique is introduced.

---

## 11. Synchronization Protocol

- `MatchService.subscribeToMatch()` — **unchanged.** `gameState.dealtRound` rides along on the exact same document/listener every other field already uses; no new match-level listener is needed to learn "has dealing happened."
- **Learning one's own hand — the one genuinely new consumption path:** a dedicated, small `MatchService.subscribeToHand(matchId, seatId, cb)` (mirroring `subscribeToRematchVote()`'s own shape exactly — ref-counted, one real listener per `matchId+seatId`, reconnect-with-backoff, fail-open) is the cleanest fit, rather than a one-shot `get()`, because it also naturally solves late-join/reconnect (§12) for free — a fresh subscription immediately receives the current, authoritative hand the instant it attaches, no separate "did I miss it" check needed. This is ONE new listener per client (their own hand only, never all 4), not a violation of the "exactly one listener per match" principle in spirit — that principle has always meant "one listener for the shared match document," and this project already has a documented precedent for a second, narrower listener when the data genuinely can't live on the shared document (`subscribeToRematchVote()` itself, for exactly the same "this subcollection's data doesn't belong on the parent" reason).
- **How remote clients learn their hand:** each client only ever subscribes to its OWN seat's hand doc — never needs to know or care what remote seats' hands contain, matching the whole point of the split-subcollection design.
- **Late joiners / reconnects:** subscribing fresh to `hands/{mySeatId}` immediately delivers the current authoritative value — no replay, no catch-up loop needed (unlike `cardLog`/`biddingLog`, a hand isn't an append-only sequence of events, it's a single current value per round, so there's nothing to "catch up" on beyond the one read the subscription already does).
- **Round N → N+1:** the client's existing `startRoundSync()` (Round Lifecycle sprint, unmodified) already detects `currentRound` advancing; it would additionally re-point (or the existing `subscribeToHand()` call would naturally receive) the NEW round's hand document once dealt — no second sync mechanism, just one more thing the existing round-transition detection triggers.

---

## 12. Failure & Recovery Model

- **Browser refresh (same device):** `bootstrapGameSession()` already re-seeds round/dealer/turn from the fresh Firestore read on every load (documented, unmodified behavior); the new hand-subscription would do the same — refresh becomes strictly SAFER than today (today, a refresh can trigger a brand-new local `Math.random()` deal if `sessionStorage`'s own `dealState` doesn't match; with Firestore-authoritative hands, a refresh always converges back to the one true value, never a re-roll).
- **Disconnect/reconnect:** `subscribeToHand()`'s own reconnect-with-backoff (reusing `RECONNECT_BASE_MS`/`RECONNECT_MAX_MS`/`isRetryable()`, unmodified, project-wide constants) delivers the last-known-good hand alongside any transient error, exactly like every other subscription in this project already does — never a blank/frozen hand on a flaky connection.
- **Client joining after dealing:** the normal case, not a special one — `subscribeToHand()` attaching after the hand doc already exists just receives it immediately (an `onSnapshot()`'s very first delivery for an already-existing document).
- **Firestore listener reconnect:** unchanged behavior from every other listener in this project — fail-open, last-known-good delivered alongside the error, never null'd out.
- **Partially written round state:** cannot occur for a single seat's hand doc (it's a single `tx.set()`, atomic per-document by Firestore's own guarantee) — the only "partial" risk is if the DEALING TRANSACTION itself fails after writing some of the 4 seat docs but before committing. Firestore transactions are all-or-nothing — a failed transaction writes NONE of the 4 documents, so "3 hands dealt, 1 missing" cannot happen; the next attempt (any client, on next listener delivery) just retries the whole deal cleanly.
- **Corrupted/incomplete hand state:** a hand doc failing the generic 13-card shape check (§9) is rejected by rules at write time — it can't reach a "corrupted" state in Firestore in the first place; a CLIENT-side cache corruption (e.g. a `sessionStorage` glitch) is self-healing on next subscription delivery, since Firestore remains the source of truth, not the local cache.

---

## 13. Testing & Verification Plan

Mirrors this project's own established three-tier verification (Node-mocked, rules-simulated, real-browser) plus the NEW real-emulator tier Sprint E proved is actually available in this environment — recommend using it for this sprint's rules from day one, not retrofitting it later.

**Node-level (mocked Firestore, real engine/service code), required before implementation is "complete":**
1. Two-client same-hand test: two independent `MatchService`/`MatchAdapter` instances (simulated via two separate in-memory client contexts against one shared fake store, the same technique `tests/round-lifecycle.test.cjs` already uses for its own two-client convergence proof) both resolve to the identical 4-seat hand assignment after `dealRound()`.
3–4 seats: same test generalized to all 4 seats' docs, not just 2.
2. Round transition hand agreement: Round 1 hands ≠ Round 2 hands (fresh shuffle), but all 4 clients agree on EACH round's own hands.
3. Rematch hand agreement: the new match's hands are independent of the old match's hands (never copied/reused), and all 4 clients agree on the new match's hands.
4. Refresh/reconnect preservation: a client that "reconnects" (fresh subscription, same seat, same round) receives the exact same hand it had before, never a re-deal.
5. Simultaneous initialization race: two (or four) concurrent `dealRound()` attempts for the same round resolve to exactly ONE committed deal — every other attempt observes the no-op path, no seat ever gets 2 different hands from 2 different "winning" transactions.
6. Unauthorized hand mutation: a direct `update()` attempt against a hand doc (bypassing `dealRound()`) is rejected.
7. Duplicate initialization/idempotency: calling `dealRound()` a second time for an already-dealt round is a no-op, never a second write, never an error.

**Rules-simulation-level (JS reimplementation, per this project's established technique):**
8. Hidden-hand access: seat A's simulated rules check for reading seat B's hand doc → denied. Seat A reading its own → allowed.
9. `list` on the `hands` subcollection → denied, for every actor.
10. Write-shape rejection: malformed `cards` array (wrong length, missing suit/rank, wrong round tag) → denied.
11. Update rejection: any attempt to modify an already-created hand doc → denied.

**Real-Firestore-emulator level (the tier Sprint E proved works in this environment — use it, don't skip it this time):**
12. Re-run tests 8–11 against the actual compiled CEL rules, not only the JS simulation — closing exactly the gap this project's own `SecurityArchitecture.md`/Rematch Vote rules comments have repeatedly, honestly flagged as unverified for every prior rules addition.

**Real-browser (Playwright), required before implementation is "complete":**
13. Two genuinely independent browser contexts + shared HTTP store (the proven technique from `verify-rematch-vote-two-client.cjs`) both landing on the same match, both converging on the SAME 4-seat hand assignment as observed through each one's own real `MatchAdapter`/`GameSession` state — this is the one test in this whole plan that actually proves the original bug (independent `Math.random()` per browser) is fixed, not just that the service-layer logic is correct in isolation.
14. Round transition observed live in two real browser contexts: both converge on Round 2's new hands.
15. Reconnect observed live: a real page reload in one of the two contexts still shows the same hand afterward.

---

## 14. Implementation Impact

**Files likely to change in a future implementation sprint (not changed now):**
- `design-ui/match-service.js` — new `dealRound(matchId, roundNumber)`; `buildInitialMatchDoc()`'s `gameState` shape gains `dealtRound`; `advanceToNextRound()`'s existing transaction gains one more field reset (`gameState.dealtRound` rollback); `createRematchMatch()`'s new-match doc gains the same `gameState.dealtRound: 0` initialization.
- `design-ui/match-adapter.js` — new `subscribeToHand(matchId, seatId, cb)` (mirrors `subscribeToRematchVote()`); a new small "hand cache populated from Firestore, not from local `Math.random()`" bridge into `GameSession`.
- `design-ui/engine/session.js` — `GameSession.ensureHandsDealt()`'s role narrows from "deal locally if not yet dealt" to "read from the already-fetched authoritative cache if present, otherwise (single-player/offline-mock path only) fall back to today's local deal" — a genuinely careful, backward-compatible change, not a rewrite (this project has no dedicated single-player mode today, but `session.js` is also loaded by contexts — like the existing Node test suite's own mocked scenarios — that have no Firestore at all, and must keep working unmodified).
- `design-ui/engine/dealer.js` / `design-ui/engine/deck.js` — likely **unchanged** — `Dealer.dealHands()` still does exactly what it does today; it just gets called from inside `MatchService.dealRound()`'s transaction instead of from a client screen's own `ensureHandsDealt()` call. This is the single biggest reason Option A is the lowest-risk choice: the entire existing, tested, correct dealing algorithm is reused verbatim, only its CALLER changes.
- `firestore.rules` — new `hands/{seatId}` block (§9); one additive shape on the existing `matches/{matchId}` update rule.
- `design-ui/match/index.html` — boot sequence gains one more `start*Sync()`-shaped call (`startHandSync()` or equivalent), following the exact existing pattern for every other sync primitve on this screen.
- Tests: a new `tests/hand-sync.test.cjs` (mirrors `tests/round-lifecycle.test.cjs`'s own shape), extensions to `tests/rules-simulation.test.js`, and a new/extended real-browser two-client harness (extends `verify-rematch-vote-two-client.cjs`'s proven pattern).
- Docs: `docs/architecture/FirestoreSchema.md`'s "hands problem" section, `docs/architecture/SecurityArchitecture.md`'s sketched-but-unbuilt hands rule row, and `docs/architecture/MatchLifecycle.md`'s `DEALING` phase note would all need to be updated to describe the ACTUAL implemented design (today they describe either a stale schema shape or a not-yet-decided placeholder).

**None of the files above were modified by this report.**

---

## 15. Risks & Open Questions

Only genuine, unresolved items — not restating settled decisions above.

1. **`GameSession.ensureHandsDealt()`'s fallback-to-local-deal path** (for contexts with no Firestore, e.g. the existing Node test suite, or any future genuinely-offline/single-player mode) needs a precise decision on how it's told "there is no authoritative Firestore value coming, deal locally as today" vs. "wait for the authoritative value" — this report does not resolve that flag/signal design, only flags that it's needed.
2. **Read-quota cost of a per-seat hand listener**, while argued negligible in §5/§6, has not been measured against this project's actual documented Firestore quota risk notes in `BackendArchitecture.md`'s companion risk file — recommend a quick confirmation against that document before implementation, not a re-litigation of the recommendation itself.
3. **Exact trigger point for `dealRound()`** — this report proposes "any client, on observing `dealtRound < currentRound`" (mirroring `advanceToNextRound()`'s own auto-trigger-from-`startTrickSync()` pattern), but the EXACT wiring point (inside `startRoundSync()`? a new dedicated hook?) is an implementation detail deliberately left open for the implementation sprint itself, not decided here.
4. **Whether `hands/{seatId}` documents from OLD, already-completed rounds should ever be cleaned up** (they're harmless — the parent match will eventually reach `status: "complete"` and nothing reads old rounds' hands again) — no functional need identified to delete them, but worth a one-line decision (leave forever vs. best-effort cleanup) before implementation, not a blocker.

No other open questions were identified — the architecture itself (Option A, per-seat subcollection, transaction-based dealing, idempotent race handling) is fully specified above.

---

## 16. Final Architecture Decision

**DECISION: Option A — Server-Authoritative Deal, via a per-seat `matches/{matchId}/hands/{seatId}` subcollection.**

**WHY:** It is the only option that actually solves hidden information (the one requirement every existing project doc already calls the hard part), it extends a transaction/idempotency pattern already proven four times in this exact codebase rather than introducing a new one, it reuses the existing, correct, unmodified `Dealer.dealHands()`/`Deck`/`Cards` chain verbatim (only relocating the CALLER, not the algorithm), and it costs this project a cost shape (a few extra small documents/listeners) it already accepts elsewhere for the same reason.

**IMPLEMENTATION STATUS: NOT AUTHORIZED / DISCOVERY COMPLETE.**

---

**No code, engine, UI, rules, or test file was modified in this sprint. This report is the only file created.**
