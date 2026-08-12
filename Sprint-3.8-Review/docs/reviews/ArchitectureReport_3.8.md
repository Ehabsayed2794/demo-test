# Architecture Report — Sprint 3.8: Gameplay Synchronization (Bidding Authority)

**Sprint type:** first real multiplayer gameplay write. Scope: bidding synchronization only. No UI redesign, no Dealer/Deck/Cards/Scoring changes, no Cloud Functions, no Blaze features, no chat/voice/replay/matchmaking/AI, no card play, no trick resolution, no scoring updates, no turn rotation after bidding.

## 1. Primary objective, checked against what was built

> "A player submits a bid. Every player sees the bid. Exactly once. In order. Without conflicts."

- **Submits:** `MatchService.submitBid(matchId, seatId, bid)` — real, transactional.
- **Every player sees it:** delivered through the existing, unmodified `subscribeToMatch()` pipe — no new listener.
- **Exactly once:** enforced by the `ALREADY_BID` check, re-evaluated on every transaction retry, so a same-seat race resolves to exactly one acceptance.
- **In order:** enforced by `version` incrementing by exactly 1 per accepted write, checked both in the transaction (fresh read every time) and independently in `firestore.rules`.
- **Without conflicts:** two different seats bidding concurrently both succeed, serialized by Firestore's transaction retry into two sequential writes; no data is lost, no bid is silently dropped.

All five verified with real, executable (MOCKED) tests — see `tests/submit-bid.test.cjs`. None of this was asserted without running it.

## 2. Task 1 — Seat Identity: design decisions and why

`docs/architecture/SeatIdentityModel.md` (Sprint 3.7.1) proposed `seats: {p1..p4: uid}`, assigned positionally, owned by `MatchService`. Implementing it required resolving two things the original proposal left open:

**Decision A — partial seat maps for under-4-player matches.** This project's room system (`RoomService.MAX_PLAYERS = 4`) does not enforce a *minimum*; several pre-existing tests exercise 2-player matches. Two options: (a) fabricate seats for missing players, or (b) map only real players. Option (a) means inventing a placeholder/AI identity — explicitly forbidden this sprint ("DO NOT implement AI") and every prior sprint that touched this area. Chosen: **(b)**. A 2-player match gets exactly `p1`/`p2`; `p3`/`p4` don't exist in that match's `seats` map. Every seat-aware check in `submitBid()`/`isValidBidSubmission()` reads seat ids from the map's own keys, never a hardcoded four-seat assumption — this generalizes correctly without special-casing.

**Decision B — rules validate a bijection, not an exact positional re-derivation.** Firestore Rules' CEL has no loop construct that could re-run `players[0]->p1, players[1]->p2, ...` server-side. Rather than skip verification or invent an unsupported construct, `isValidSeatMap()` verifies the RESULT has the required shape/integrity: only real seat names, one seat per real player (size match), every seat's value is actually a real player (membership), and no two seats share a uid (uniqueness). Together these four conditions force `seats` to be a genuine bijection — a client cannot fabricate a seat for a non-player, double-assign a seat, or leave a real player seatless, even though the rule never recomputes the exact join-order assignment `buildSeatMap()` used. This is a deliberate, documented equivalence, not a weaker approximation — recorded in `firestore.rules`' own comments and `SeatIdentityModel.md`.

**What remains explicitly unimplemented:** any translation from this uid-keyed map into the engine's seat-id-keyed local state (`GameSession`, `bidding-engine.js`). `GameSession.getRemoteMatch()` still returns the raw document; nothing merges it into `round`/`biddingState`. This is the correct scope boundary — Sprint 3.8 gives bidding a write AUTHORITY layer, not an engine INTEGRATION layer.

## 3. Task 2 — Versioned Writes: why both a transaction AND a rules check

Firestore's own transaction mechanism already provides optimistic concurrency at the SDK level (a transaction re-reads and retries automatically on conflict) — so why also add an app-level `version` field, independently checked by rules? Because:

1. **Rules cannot inspect Firestore's internal transaction versioning** — that's an SDK-internal mechanism, invisible to `firestore.rules`' CEL evaluation. An explicit, plain-document `version` field is the only way rules can independently verify "this write is exactly one step forward," which is what makes "reject stale/duplicated/out-of-order writes" a SERVER-enforced guarantee rather than something only the (trusted) client's own JS happens to get right.
2. **Defense in depth** — this project's established principle since Sprint 3.4.1 ("neither layer trusts the other alone"). Even if `submitBid()`'s own JS had a bug, or a client bypassed `MatchService` entirely and wrote to Firestore directly, `isValidBidSubmission()`'s `newData.version == oldData.version + 1` check independently rejects anything that doesn't hold.

This is also, concretely, what activates Sprint 3.7's dormant ordering guard inside `subscribeToMatch()` — that guard was written to check a `version` field that, through Sprint 3.7.1, nothing ever wrote. Sprint 3.8 is the sprint that finally writes it, and the guard starts working with zero code changes to the subscription side at all — a direct, verifiable payoff of Sprint 3.7's forward-looking (if previously dormant) design.

## 4. Task 3 — API shape decision: why `submitBid(matchId, seatId, bid)`, not `(matchId, uid, bid)`

The original Sprint 2.7 speculative stub had the signature `submitBid(matchId, uid, bid)`. Sprint 3.8's brief explicitly specifies `submitBid(matchId, seatId, bid)`. Beyond just following the brief, this is the architecturally correct choice: a `uid` parameter would be an argument for a caller (buggy or malicious) to misuse to claim they're submitting on behalf of a DIFFERENT uid than their own. By deriving the caller's own uid internally (via `SessionService.getCurrentUser()`) and never accepting it as an argument, cross-identity submission is structurally impossible from this API's shape alone — the same pattern already established for `SessionService.setCurrentMatchId(matchId)` (no `uid` parameter, Sprint 3.4.1). `seatId` is the only identity-adjacent parameter, and it's checked against the immutable `seats` map, both client-side (fast, clear error) and server-side (the actual enforcement boundary).

## 5. Task 5 — Rules design: CEL constructs used, and an honest caveat

`isValidBidSubmission()`/`isValidSeatMap()` use `.keys()` on nested maps, the `.all()` macro, and `.diff()` on a NESTED map value (`newData.bids.diff(oldData.bids)`, not just top-level document data) — the first time this project's `firestore.rules` has used any of these three constructs. All three are standard, documented Firestore Security Rules / CEL features. **Honest caveat, stated plainly per this sprint's explicit instruction:** this project has never had access to a real Firestore emulator or a real Firestore project (see `docs/architecture/MatchSynchronization.md`'s Sprint 3.7.1 section — this has been true since Sprint 2.6). Every rule in `firestore.rules`, across every sprint including this one, has been verified only via a 1:1 JS translation of its INTENDED logic (`tests/rules-simulation.test.js`), never by actually running the CEL. This sprint's rules carry that same, pre-existing limitation — not a new one, but worth restating precisely because these three constructs are new to this file and therefore carry slightly more first-use risk than the already-proven `hasOnly`/`hasAll`/top-level `diff()` patterns every earlier sprint's rules relied on. **Recommendation:** before any production deployment, run a real `firebase emulators:start` + Firebase Rules Unit Testing pass against `firestore.rules` as it stands — this has not been done for any sprint's rules in this project's history, and this sprint is not an exception.

## 6. Scope discipline: what was deliberately NOT built

- Bid VALUE legality (a real trick count, Dash/With shape, auction-order correctness) — `bid` is stored as an opaque payload. `bidding-engine.js` is untouched and unconnected.
- Turn authority / whose turn it is to bid next — `turn` field unchanged, no meaning added.
- Any phase beyond "open" / "closed" — no DASH/AUCTION/CONFIRM/ESTIMATES sub-state on the match document.
- Card play, trick resolution, scoring updates, dealer synchronization, matchmaking, replay, chat, voice, AI, leaderboards, tournaments, Cloud Functions.

Every one of these is either a non-negotiable "DO NOT" from the brief or a documented, deliberate scope boundary — none is an oversight.

## 7. Conclusion

Bidding synchronization works, is tested (MOCKED for `match-service.js`, SIMULATED for `firestore.rules`), and is documented honestly, including its one first-use-of-new-CEL-constructs caveat. No gameplay rule, scoring formula, or engine file was touched. Stopping here per the brief's stop condition — waiting for review before any card-play, dealer-sync, trick-resolution, scoring, or turn-authority work begins.
