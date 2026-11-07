# Matchmaking owner rulings — both paths, a symmetric band, a 30 s lease

- **Date:** 2026-10-28
- **Status:** DECIDED (owner rulings, 2026-10-28) — implemented in `agent/s56-resolver`
- **Closes:** **O-15** (matchmaking band directionality, open since 2026-10-22)
- **Supersedes:** **Q26 clause (3)** in
  `docs/decisions/2026-10-21-server-authority-and-owner-rulings.md`
- **Does not supersede:** RD9's pool rule, A8.6 / Q41's absolute band, GM5, or
  RD29's party-consent rule.

Three rulings were given together, while S56 (the Ranked queue, its resolver
pass and the three callables) was in review. Each one is small; together they
close the last open matchmaking question and fix two numbers the backend
needed before it could ship.

## 1. Q26 — both paths must exist: Create Game *or* Auto Match

**Ruling.** The Ranked entry point offers **two** options, and neither is
optional:

1. **Create Game** — the player configures the match: bot difficulty, bot
   personality, play time, and the rest of the existing Create Game
   selections.
2. **Auto Match** — the player does not care and wants to play now.

The server **may form a private table for Auto Match**, and **both players
press Ready**. The owner explicitly accepted that flow: the point is that both
options exist, not that Auto Match avoids a table.

**What this supersedes.** Only clause (3) of Q26 ("if no waiting table exists,
show 'no tables right now' plus a Create Table button — no waiting, no
auto-created table"). The other two Q26 clauses stand: the joiner/creator tier
rule is the RD29 band, and the joiner sees the table's settings before joining.

**What it changes downstream.** The Ranked UI story must present the choice and
must define the Auto Match waiting state. **Ready is manual on both paths**
(ruled the same day, see §4): the backend writes `readyPlayers = []` and leaves
Ready to the clients, which is exactly what the UI will show.

## 2. O-15 — the band is symmetric: one tier, up or down

**Ruling.** The pairing band is **symmetric**, and it is **exactly one tier**:
the same tier, or one tier above or below. Never two, in either direction.

**How it is implemented.** RD9's policy function
(`MatchmakingPool.isPoolEligible`) is **not** changed: it still answers "which
tiers may a searcher of tier T be served from", and a client still cannot name
a higher pool. The resolver composes **both** directions
(`isPoolEligible(a, b) || isPoolEligible(b, a)`, `tiersAreAdjacent` in
`MatchmakingSearch.kt`), which is the server forming the mixed match RD9
already permits. `MatchmakingSession.bandAccepts` was already absolute and
therefore already symmetric; the two rules now agree by construction.

Arrival order still decides the table's **creator** and the deterministic order
of the pass. It no longer decides whether a pair is legal.

**Why this is not an RP exploit in itself.** Pairing is still server-derived and
tier-blind from the client's point of view: a Silver cannot request a Gold, and
cannot choose to wait for one. Mixed-tier RP asymmetry (RD5 / R28) is unchanged
and is explained on the result screen (S57).

## 3. The search lease is 30 seconds

**Ruling.** A search that has not heartbeated for **30 s** is reclaimed.
("Not a lot and not a little.")

**What the lease does.** A client that vanishes — closed app, dead network —
leaves a `rankedSearches/{uid}` document behind. The lease is what stops that
document from occupying a queue slot for ever and from being paired into a
table the player will never join. Every poll renews it; the pass reclaims what
it finds expired.

**Why 30 s and not 60 s.** The client polls every 3 s, so 30 s survives ten
missed polls — a slow round trip or a brief hiccup is not a lost search — while
a dead seat frees its slot in half the time. The boundary is exact: exactly
30 s of silence is still live, 30 s and 1 ms is reclaimed (`>` not `>=`).

**The trade-off, stated rather than hidden.** A client that is backgrounded for
more than 30 s loses its search. The production port treats the resulting
`IDLE` answer as `Failed` and the screen offers a retry — which is the intended
behaviour: a player who walked away is not kept in the queue, and a player who
comes back re-enters. The client's own wait budget (120 s) is unchanged and is
still the bound on how long a spinner may run.

## 4. Created games award RP, and Ready is always a manual press

**Rulings (given the same day, after §1 was written).**

1. **A game the player creates counts for RP.** The reason is informed
   consent: everyone who joins has seen the game's settings (bot difficulty,
   personality, play time) **before** joining — Q26 clause (2) — so a Ranked
   table is not a surprise opponent, and there is no reason to withhold RP from
   it.
2. **Pressing Ready is manual.** There is no auto-Ready on either path, Auto
   Match included. This also keeps Q26 clause (2) honest: a player cannot be
   seated and started before they have seen what they joined.

**What this settles.** The "created games vs Ranked RP" question is **closed in
favour of RP**. The UI story no longer needs to decide it; it only needs to add
the Ranked choice to Create Game and wire the existing Ready flow to both
paths. Nothing in S56's backend changes: resolver tables are `RANKED` with the
RD21 defaults, and created tables keep whatever `mode` the creator chose.

**The residual risk, stated rather than hidden.** The informed-consent argument
is about *human* joiners. A Ranked table that fills its empty seats with the
**creator's chosen bots** is a different case — a player could pick the easiest
bot and still be paid RP. Ranked-with-bots RP is already contemplated by the
existing rules (T-20's "RP with bots" row computes strength from the humans
only), so this is not new, but it is the one place where "I saw the settings"
does not apply. If it ever shows up in the wild, the cheapest bound is a
minimum of one human opponent for RP — a later, separate decision, not a change
to this ruling.

## Consequences recorded now, not implemented here

- **The next UI story must offer Create Game vs Auto Match**, with **manual
  Ready** on both (§1, §4) and RP on created games (§4).
- **`CreateGameViewModel` still has no `MatchMode` choice** — it exposes
  GameType, Calculation, the RD21 bot trio and the timer, so a created game
  currently reads as `ROOM`. Adding the Ranked choice is the UI story's work.
- **Resolver-created rooms have no abandonment/TTL path yet.** A pair whose
  players never press Ready leaves a `waiting` room behind. The 30 s lease
  bounds the *search*, not the *table*; the lobby's own timeout story owns
  that, and it is not part of S56.

## Evidence

- `native/functions/src/jsMain/kotlin/com/estemshan/functions/MatchmakingSearch.kt`
  — `tiersAreAdjacent`, `SEARCH_LEASE_MILLIS = 30_000L`.
- `native/functions/src/jsTest/.../MatchmakingSearchTest.kt` — both arrival
  orders pair (`aGoldSearcherIsServedASilver`, `aSilverSearcherIsServedAGold`),
  two tiers apart never pair in either direction, and
  `theSearchLeaseIsThirtySecondsAndTheBoundaryIsExact` pins 30 000 ms live /
  30 001 ms reclaimed.
- `native/engine/.../MatchmakingSession.kt` and `MatchmakingPool.kt` — pointer
  notes only; neither policy changed, and their tests still pass unchanged.
- Mutation checks: making `tiersAreAdjacent` directional again fails
  `aSilverSearcherIsServedAGold`; restoring a 60 s lease fails the boundary
  test.
