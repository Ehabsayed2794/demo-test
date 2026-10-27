# Q-S7-4 — the 7th takeover owns the removal when it collides with the 15th timeout

- **Date:** 2026-10-27
- **Status:** DECIDED and implemented in PR #172 (`agent/s7-takeover`)
- **Owner ruling:** one seat is never charged twice for one removal — «ممنوع 4 كوز خالص».
- **Applies to:** `TimeoutResolutionDb.resolveTimeoutOnce`, `TimeoutResolution`,
  `SeventhTakeoverTest`, and any future departure-charge consumer.

## Question

One expiry can satisfy both thresholds at once: the seat's 15th timeout and its 7th
takeover, inside the same resolution window. Before this ruling the transaction wrote
both removals for the same uid:

- `timeoutRemovedUids` + `timeoutRemovedCharges`, and
- `takeoverRemovedUids` + `takeoverRemovedCharges`.

In ROOM that is 2 Koz for one seat instead of 1; in RANKED and UNRANKED it is 4 Koz
instead of 2.

## Ruling

The **7th takeover owns the removal**. When one expiry is both the 15th timeout and the
7th takeover:

- exactly one removal reason is recorded — the takeover's;
- exactly one charge is recorded — the takeover's (ROOM 1, RANKED 2, UNRANKED 2);
- the timeout counter still advances to 15, because the 15th timeout did happen; only
  its removal and its charge are suppressed;
- the terminal takeover still cancels the seat's ballot and its electorate's ballots and
  clears the borrowed-seat marker, which the timeout path never touched.

The precedence lives in game logic, not in settlement:

```kotlin
val removal = counter != null && counter.shouldRemove && !terminalTakeover
```

Because the two charge maps can never name the same uid, the invariant holds by
construction rather than by a downstream dedup.

## Why the takeover wins

- The takeover path is already terminal and complete: it removes, charges, cancels the
  ballot and clears the marker in one transaction.
- The alternative (the timeout wins) would leave the takeover counter at 6 on a removed
  seat, and would make the timeout path learn ballot cancellation and marker clearing —
  widening every 15-timeout removal to fix one collision.
- Both choices cost the player the same amount, so the deciding factors are the smaller
  change and a single consistent record.

## Consequences

- The seat's record says "removed by the 7th takeover", not "removed by 15 timeouts". If
  the owner later wants the timeout message shown to the player, this ruling must be
  revisited — it is a message change, not a charge change.
- `timeoutCounts/{uid}` may read 15 for a seat removed by the takeover. That is
  intentional: the counter records the timeouts, the removal lists record the reason.
- Guard test: `noResolutionEverRecordsTwoRemovalReasonsOrTwoChargesForOneUid` fails if any
  resolution ever names one uid in both removal lists or both charge maps.

## Evidence

- `SeventhTakeoverTest.theSeventhTakeoverWinsTheCollisionWithTheFifteenthTimeout`
- `SeventhTakeoverTest.noResolutionEverRecordsTwoRemovalReasonsOrTwoChargesForOneUid`
- Mutation check: removing `&& !terminalTakeover` fails exactly those two tests
  (2 failures, 592 passes); restoring it returns 594/0.
