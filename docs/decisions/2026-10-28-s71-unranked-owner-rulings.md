# S71 — Unranked mode: the owner's six rulings, and the server-created match

- **Date:** 2026-10-28
- **Status:** DECIDED (owner rulings, 2026-10-28) — implemented on `agent/s71-unranked`
- **Story:** **S71** — Unranked mode (E6b, RD28 / RD29 / RD30)
- **Implements:** `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` RD28's `UNRANKED` branch,
  the S71 row of the E6b story table, and the S71 row of the sprint table
- **Does not supersede:** RD9's pool rule, RD29's party-consent rule, RD21's bot
  trio, the 2026-10-17 Unranked scope ruling (which fixes the *scope*; this
  record fixes the *mechanism*), or anything in
  `docs/decisions/2026-10-22-final-decision-sync.md`
- **Governs:** `native/engine` (the pool policy), `native/functions` (the queue,
  the resolver and the fill), `native/services` + `native/app` (the ports), and
  the three `firestore.rules` gates listed in §4

Six questions came up while S71 was being built. Each was put to the owner and
each is answered here, with the reason it was asked and what it settles.

---

## 0. The owner's words (verbatim)

Three of the six were answered in free text. The owner's words, as sent:

> automatic fill is ok

> Server creates the match.

> Unfreeze the three gates.

The other three were answered by choosing one of the offered options (the
recommended one in each case):

- **Pool scope** → *One mode-aware queue* — not two queues.
- **Party behaviour** → *Party members join the shared pool individually* — no
  group seating.
- **UI slice** → *Hold the two Compose surfaces* until artboards exist.

---

## 1. One mode-aware queue, not two

**Ruling.** There is **one** queue. The Unranked pool is the same queue with a
different `mode` on the document.

**What it means mechanically.** `rankedSearches/{uid}` gains a `mode` field
(`RANKED` | `UNRANKED`). A `RANKED` search carries the tier the server derived
from the caller's own profile; an `UNRANKED` search carries **no tier at all**,
because the Unranked pool is rank-blind and there is therefore no field to hold
a rank. The parse **refuses** an Unranked document that carries a tier rather
than ignoring it — a document this server cannot read is a corrupt document,
not a legacy one.

**Why it matters that the absence is structural.** The Unranked branch of
`enqueueSearch` never reads a profile. A player with **no ranked profile** may
still play Unranked, and a bug that consulted a rank would have nothing to
consult. A `ROOM` document is corrupt in this queue: Room never pools.

**Legacy.** A document written before S71 carries no `mode`. The queue's only
mode then was `RANKED`, so an absent mode reads as `RANKED` — a legacy
document, not a corrupt one.

**Where.** `native/functions/src/jsMain/kotlin/com/estemshan/functions/MatchmakingSearch.kt`.

## 2. Party members join the shared pool individually

**Ruling.** A party does **not** get seated as a group. Each member enters the
shared pool on their own, exactly as a solo player does.

**Why.** The pool's unit is a *search*, and a search resolves to a *table*
whose seats are filled from the searches that arrive. Group seating would make
the table's seat order depend on a party roster the server would have to trust
and keep in sync with the queue — and RD29's party-consent rule already lives
on the party side. Nothing in S71 changes RD29.

**Consequence.** A party of three and a party of one are indistinguishable to
the resolver; the difference is entirely in how the clients enqueue.

## 3. Automatic fill at the window's close — and no bot-fill vote

**Ruling.** "automatic fill is ok". When an Unranked table's fill window
closes, the remaining seats are filled with the **configured bots**. There is
**no vote** on whether to fill.

**What the window is.** Four minutes: `UNRANKED_FILL_MILLIS = 240_000L`
(`native/engine/src/commonMain/kotlin/com/estemshan/engine/UnrankedMatchmaking.kt:40`),
anchored to the **first** player's arrival (Q8) — the same anchor the ranked
pool uses.

**Why no vote.** A vote would need the same machinery as the disconnect vote
(a 30-second ballot over a live table) and would reach the same answer: a table
that cannot fill will be filled by the bots, because the alternative is a
table that never starts. The owner took the simpler path.

**What the fill produces.** The humans keep the **first** seats, in the order
they arrived; the bots take the **last** seats. A table's arrival order is
therefore its seat order — the same positional rule `buildSeatMap` applies to
a client-created match.

## 4. "Server creates the match" — the bot seat forces it

**Ruling.** "Server creates the match." The match of a bot-filled Unranked
table is written by the **server**, not by a client.

**Why this was not a free choice.** A bot seat is a seat **no client may
write**. `firestore.rules`' own `isValidSeatMap()` requires
`seats.keys().size() == players.size()`, contiguous `p1`..`pN`, and every seat's
uid to be one of the `players` — and it is enforced **at creation**, where a
bot seat has to appear (`firestore.rules:517`, the rematch-vote creation path
and `isValidNewMatch`). The configured bots can therefore only ever take a seat
through a writer the rules do not bind: the **Admin SDK**.

**The shape the server writes.**

| Field | Value |
|---|---|
| `seats` | `p1`..`p4` — the humans in the first seats, a synthetic bot uid in the rest |
| `players` | the **humans only** |
| `botSeats` | the **seat keys** the bots took (`p2`..`p4`), never uids |
| `mode` | `UNRANKED` |
| `status` | `starting` |

The bot's uid is synthetic (`bot:unranked:<seat>`) and belongs to no account.

**Why `players` carries the humans only.** That is load-bearing, not cosmetic:
the settlement's own rule — *a seat whose occupant is not in `players` is a bot
seat* (`native/functions/src/jsMain/kotlin/com/estemshan/functions/SettlementDb.kt:697`
— `readSeatInputs`, whose `onlyUids` parameter at `:1107` is the stats path's
way of skipping it) — then skips the bot seats by itself, before it ever reads
a profile for one. `botSeats` carries **seat keys**, the same shape S72's leave
write uses and the same shape `MatchDoc.botSeats` parses.

**Why there is no client race.** `MatchService.startMatch` returns the existing
match when `room.matchId` is already set, and `room.canStart` requires the room
to be **full and all-ready**. A one-to-three-human Unranked room can therefore
only ever become a match through the server's fill-close — the two paths cannot
both fire.

**Why the hands are not dealt here.** `MatchService.dealRound` deals every
occupied seat, and `isValidNewHand` lets any seated member write any occupied
seat's hand. The bot's thirteen cards are dealt by the ordinary client path;
only the server (the Admin SDK) can read them back.

**Where.** `native/functions/src/jsMain/kotlin/com/estemshan/functions/UnrankedFill.kt`
(the builder), `UnrankedPool.kt` (the fill-close loop), `MatchmakingSearchDb.kt`
(the Admin writer).

**One structural consequence.** The fill-close loop must run **before** the
"is anybody waiting?" guard: a converted room's window closes on its own clock,
with nobody searching for it any more. The scan therefore walks the open
Unranked rooms first, closes the ones whose window has expired, and only then
does the pairing work.

## 5. "Unfreeze the three gates" — Vote Kick reaches Unranked

**Ruling.** "Unfreeze the three gates."

**What changed.** `firestore.rules` had exactly three `mode == 'RANKED'` checks
on the Vote Kick path. All three now ask one shared question instead:

```
function isVoteKickMode(data) {
  let m = data.get('mode', 'ROOM');
  return m == 'RANKED' || m == 'UNRANKED';
}
```

- the cooldown write (`firestore.rules:1744`),
- the removal write (`firestore.rules:1759`),
- the open shape (`firestore.rules:2464`).

**Why the helper, and why it defaults to `ROOM`.** The three gates ask the same
question, so they ask it the same way. The absent-mode default is `ROOM`
because an absent mode is a pre-S43 document, and a legacy match keeps its ROOM
denial — the same absent-ok contract `isValidGameType` already uses.

**Why Unranked is in scope at all.** RD28 says Unranked is a first-class mode
with an explicit branch at every switch site, and Vote Kick is **present** in
it (corrected 2026-10-17). The 2026-10-17 owner ruling is unambiguous: *"only
the Rooms have no Vote Kick"*.

**The re-pin.** `firestore.rules.sha256` now reads

```
bf6584dcc5c7a5746f7ba859096e2c4fdbe5f050a300d042f8b1550e34d700df  firestore.rules
```

and that is the hash of the file on disk (`sha256sum firestore.rules` — the
pair gate: the recorded pin and the file must agree before any rules merge).

**Scope note.** These three gates were the **only** rules change approved.
Nothing else in `firestore.rules` was touched.

## 6. The two Compose surfaces are HELD

**Ruling.** "Hold the 2 Compose surfaces until artboards exist."

Two surfaces are therefore **not** built in this story:

1. **S36** — the three-entry match-type picker (Room / Ranked / Unranked).
2. the **Unranked waiting-room** screen.

**What is built instead.** The whole path **behind** the picker is live: the
pool policy in `:engine`, the queue and the resolver and the fill in
`:functions`, the ports in `:services`, and the client wiring in `:app` — the
`:app` change is the *state* and the *call*, not a screen. The picker is a
presentation decision that needs an artboard.

**This is a deliberate stop, not an omission.** The standing instruction on this
story is that HTML-to-Android conversion is out of scope; the design pipeline is
HTML-first, and no HTML artboard exists for either surface.

---

## 7. What this record does not change

- The **Unranked scope** itself — the 2026-10-17 ruling (RP frozen, everything
  else exactly like Ranked, Vote Kick in Ranked + Unranked) stands as written.
- The **statistics** scope — Q46 / N-1 (2026-10-22): an `UNRANKED` completion
  increments **Games Played** and the seat's **King / 2nd / 3rd / Koz** counters,
  exactly as a `ROOM` or `RANKED` completion does. Only **RP, tier and
  division** stay Ranked-only. RD28's "statistics absent" is superseded by
  pointer notes in the plan (2026-10-22), not by this record.
- The **RP engine** — untouched. The RP freeze is a property of `UNRANKED` only.
- **RD9's pool rule**, **RD29's party-consent rule**, **RD21's bot trio**, and
  the **30 s search lease** (2026-10-28, `2026-10-28-matchmaking-owner-rulings.md`).
- The **disconnect vote's** scope and mechanics (Rooms + Unranked; one
  30-second ballot over a live table; the pause only after the verdict, bounded
  at 90 s).

---

## 8. Open item — needs a fresh owner ruling

**`firestore.rules:2186-2189` carries a comment that the bot seat makes
stale.** The round-advance comment justifies deriving the card total from
`parent.seats` with the parenthetical *"isValidSeatMap requires
`seats.keys().size() == players.size()` from creation onward"*.

The **code** is still correct: the factor is `13 * parent.seats.keys().size()`,
and `seats` is in **no** update allowlist, so the seat map is immutable after
creation — which is the invariant the comment is really about. For a bot-filled
table the factor is `13 * 4 == 52`, exactly the four-seat constant.

Only the **parenthetical citation** is stale: the server-created match has
**four seats and three humans**, which no client-written match may have.

**This needs the owner's approval** because `firestore.rules` is frozen apart
from the three gates in §5, and any change to it re-pins the SHA. The fix
would be one comment line plus a re-pin; the behaviour would not change. Until
then the comment is knowingly inaccurate and is recorded here rather than
silently corrected.

---

## 9. One S71 assertion is vacuous — "no push-to-talk voice"

**The assertion.** The S71 row of the story table
(`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:686`) asserts, among others, **"no
push-to-talk voice"** for Unranked, and the 2026-10-22 pointer note under that
table keeps the assertion alive: *"so do S71's other assertions (**Vote Kick
present**, **no push-to-talk voice**, disconnect vote present, rank-blind
mixing)"* (`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:694-696`).

**Why it cannot be tested.** There is **no voice code anywhere in `native/`**. A
case-insensitive sweep of the whole native tree for `voice`, `microphone`,
`speech` and `audio` returns **nothing**; the only matches for `record` are the
English word "recorded" in unrelated comments. Voice is **documented only**:

- Q42 / O-08 states it outright — *"Implementation status: no voice code
  exists"* (`docs/decisions/2026-10-22-final-decision-sync.md:124-131`);
- the roadmap keeps S35 (Push-to-Talk) under *"G. Future — voice (documented
  only, per owner decision)"*, now pointer-noted as V1 scope but still
  **Rooms only, absent in Ranked and Unranked**
  (`docs/UI_UX_ROADMAP.md:848-857`).

An assertion of the form *"feature X is absent from mode M"* has **no referent**
when X does not exist at all: there is no seam to assert on, and the assertion
would read identically green whether the Unranked waiting room carried a
microphone control or not. The criterion is therefore **vacuous**, not
satisfied. S71 records it as vacuous rather than claiming a green it did not
earn.

**What plays the same role instead.** The disconnect vote is the mode
discriminator the codebase actually has, and it is right for the third mode:
`DisconnectVoteFlow.modeEligible`
(`native/functions/src/jsMain/kotlin/com/estemshan/functions/DisconnectVoteFlow.kt:140-147`)
admits `ROOM` and `UNRANKED`, refuses `RANKED`, and **fails closed** on a mode
the document does not name. `DisconnectVoteFlowTest.kt` pins both arms — the
eligible path runs on an `UNRANKED` fixture by default, and
`rankedNeverAsksAndNeverHolds` (`:204`) pins the refusal.

**What this hands forward.** When voice is built (S35, Q42 / O-08), the
push-to-talk control needs the explicit `mode` branch that RD28 demands of
every mode switch site: **Rooms only** — never Ranked, never Unranked. Until
then there is nothing to build and nothing to exclude.

---

## Evidence

- **Engine** — `native/engine/src/commonMain/kotlin/com/estemshan/engine/UnrankedMatchmaking.kt`
  (the rank-blind pool policy and the four-minute window);
  `MatchmakingPool.kt` (the mode-aware eligibility).
- **Functions** — `native/functions/src/jsMain/kotlin/com/estemshan/functions/MatchmakingSearch.kt`
  (the one queue, the two mode branches, the lease);
  `UnrankedPool.kt` (the fill-close loop and the pool's own tables);
  `UnrankedFill.kt` (the server-created match);
  `MatchmakingSearchDb.kt` (the Admin writer — `createMatch`, `bindRoomToMatch`);
  `PlayUnranked.kt` (the host-only conversion callable).
- **Services / app** — `native/services/src/main/java/com/estemshan/services/MatchmakingPort.kt`,
  `CallableMatchmaking.kt`, `MatchmakingTransport.kt`;
  `native/app/src/main/java/com/estemshan/game/ui/matchmaking/MatchmakingViewModel.kt`
  (state and the call — **no Compose screen**).
- **Rules** — `firestore.rules` (the `isVoteKickMode` helper and its three call
  sites) and `firestore.rules.sha256`
  (`bf6584dcc5c7a5746f7ba859096e2c4fdbe5f050a300d042f8b1550e34d700df`).
- **Tests** — `native/functions/src/jsTest/.../UnrankedFillTest.kt` (the match
  shape, the single-transaction property, and the guards proven by mutation),
  `UnrankedPoolTest.kt`, `PlayUnrankedTest.kt`,
  `PlayUnrankedEmulatorTest.kt` (the Admin write path on storage — the pool
  anchor as a resolved server timestamp, the re-anchor, and the idempotent
  second press, against a live Firestore emulator);
  `native/engine/src/jvmTest/.../UnrankedMatchmakingTest.kt`;
  `tests/native-s71-votekick-mode-rules.test.cjs` (the three gates, against the
  pinned rules).
