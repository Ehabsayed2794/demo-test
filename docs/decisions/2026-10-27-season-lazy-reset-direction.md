# Q-E5.4 — a lazy season reset never runs backwards during a `RUNNING` transition

- **Date:** 2026-10-27
- **Status:** DECIDED and implemented in `agent/settlement-season-guard`
- **Owner ruling:** go-ahead to fix the hazard #178 reported; R27 (a season reset is one
  way and irreversible) outranks keeping one parser for all season reads.
- **Applies to:** `LeaderboardDb.lazyResetSeasonOf`, `SettlementDb.SettlementTx`,
  `RankedProfileDb.Tx`, `alignSeatedProfilesToSeason`, `normalizeProfile`,
  `SeasonDocumentTest`, `LazySeasonResetTest`.

## Question

`seasons/current` is one document with two different readers:

- the **leaderboard** needs the season whose board is live;
- the **lazy reset paths** (the Ranked profile read and the settlement) need the season
  a stale profile must be moved into.

Both used `currentSeasonIdOf`, which serves `seasonId` and deliberately ignores `status`.
That is right for the board. It is wrong for a lazy reset inside one narrow window.

The bulk season job (`SeasonResetRun`) runs in two phases:

1. **Claim:** `status = RUNNING`, `targetSeasonId = next`; `seasonId` still names the
   **outgoing** season.
2. **Sweep:** every profile is stamped with the **target** season.
3. **Finalise:** `seasonId = target`, `status = ACTIVE`.

Between 2 and 3 the document still answers the outgoing season while profiles already
carry the target. A settlement or profile read in that window judged those profiles
against the outgoing season, planned `target -> outgoing`, and applied the demotion math
again — a **backwards reset** that moves a player into a season nobody is in, after the
sweep already moved them forward. R27 makes that migration irreversible.

## Ruling

The lazy paths judge against the **in-flight target** while the reset is `RUNNING`:

```kotlin
if (status != SeasonStatus.RUNNING) return seasonId
return targetSeasonId   // non-blank, or fail closed
```

- The **board keeps `currentSeasonIdOf`**: `seasonId`, verbatim, `status` never consulted.
  While `RUNNING`, the board stays on the outgoing season until the job commits the new
  one. This is unchanged and still pinned by `SeasonDocumentTest`.
- The two lazy reads use `lazyResetSeasonOf` (new, same file): `null` when the document
  does not exist (the bootstrap), `seasonId` verbatim in `ACTIVE` / `COMPLETE`, and
  `targetSeasonId` in `RUNNING`.
- The two port methods were **replaced**, not duplicated:
  `currentSeasonId(): String?` → `currentLazyResetSeason(): String?` in
  `SettlementDb.SettlementTx` and `RankedProfileDb.Tx`. Callers cannot accidentally keep
  reading the board's answer, and the default stays the bootstrap literal `s1`.

Effect on a `RUNNING` transition:

| profile recorded | effective season | result |
|---|---|---|
| the target | the target | untouched — never demoted back |
| the outgoing season | the target | one forward reset, exactly what the sweep is doing |
| any other recorded season | the target | one reset into the target (Q22: sleeping through seasons is still one reset) |

## Fail closed

An existing document must carry a **known** `SeasonStatus` name; `RUNNING` must name a
usable `targetSeasonId`. Anything else fails closed with a fixed literal that never
echoes the value:

- `seasons/current has no usable seasonId.` — shared verbatim with `currentSeasonIdOf`:
  one document, one diagnostic;
- `seasons/current has no known status.`
- `seasons/current is RUNNING with no usable targetSeasonId.`

This mirrors the bulk job, which reads the same document as `NOT_CONFIGURED` rather than
guessing which season is authoritative. The season job is the only writer of this
document, and every state it writes carries a status, so a status-less document can only
be a corrupt or hand-edited one.

## Evidence

- `SeasonDocumentTest`: 6 new parser-contract tests (bootstrap, verbatim, `RUNNING`
  target, unknown status, unusable target, unusable `seasonId`), including the assertion
  that the board and the lazy paths disagree during `RUNNING`.
- `LazySeasonResetTest`: 4 new behavioural tests — read path at the target (no write), read
  path at the outgoing season (one forward reset), settlement folding a swept seat without
  a backwards reset, and both paths failing closed with no usable target.
- Mutation check: deleting the `RUNNING` branch fails 5 of those tests; restoring it
  returns the whole suite to green.
- Full sweep on the branch: **1992 tests, 0 failures** (functions 643, engine 593,
  services 265 ×2, app 226).
