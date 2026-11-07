# The match-payload retention window is 30 days after `finished_ms`

- **Date:** 2026-10-31
- **Status:** **DECIDED (owner).** One ruling, recorded the day E10's local run stood.
- **Decides:** the retention window E10 costed and left to the owner — the match payload is kept
  for **30 days after `finished_ms`** (**2,592,000,000 ms**).
- **Supersedes:** the "the window is the owner's decision" pending state recorded in
  `docs/implementation/Stage0_E10_Retention_Storage_Report.md` §2, §11 and §13.1, and in
  `docs/implementation/Stage0_Remaining_Steps.md` (E10 wave notes 4 and 5, and the Arabic §8 E10
  bullet). Those bodies stay verbatim; each now carries a dated pointer note to this record.

## 1. The owner's ruling — verbatim

The owner's words, on **2026-10-31**:

> **«اوك 30 يوم»**

No condition, exception or follow-up was attached to it. The decision is the window itself:
**30 days after `finished_ms`**, i.e. `purge_after_ms = finished_ms + 2,592,000,000`.

## 2. What the ruling decides

- The **match payload** — the result, the hand distributions, the replay and the audit trail — is
  kept for **30 days** after the match finishes.
- After that threshold the payload is eligible for the cleanup sweep; the trigger is the one E10
  measured (`setAlarm(finished_ms + window)`), one alarm armed once at the end of the match.
- The window is a **policy input only**. It does not change the delete order, the settlement
  protocol or any measured figure.

## 3. Kept vs lost — the report §11 30-day row

At **80 matches/day**, the 30-day window costs, by store:

| Store | Bytes kept |
|---|---:|
| Rows/payload store (RP) | **4,857,959** |
| D1 backup (`BACKUP`) | **2,612,800** |
| Durable Object objects, **if deleted** | **127,795,200** |
| **Total if the DO objects are deleted** | **135,265,959** |

The DO figure is **2.38% of the free 5 GB DO allowance**. The D1 side barely moves with the
window (4.82 MB → 5.34 MB across 7 → 365 days) because the ledger and the tombstones dominate it;
**the DO's high-water file is where the window's money is**, and only if the objects are actually
deleted.

**If the DO objects survive, the window costs nothing in storage** — the DO figure is
**1,554,841,600 B at every window**, so the storage argument does not constrain the choice at all.

**What is lost:** anything older than a month — the detail behind a monthly challenge or a
mid-season review. The scoreboard's balance is not lost (§4).

## 4. Never pruned — permanent by design

The ruling prunes **payload only**. These are never pruned, at any window:

- **the ledger** — its `rp` column *is* the player's balance;
- **the ledger's backup copy** in D1;
- **the tombstone** — one compact row per match, the digest E8 compares, written before any
  payload row is deleted.

The balance and the idempotency anchor are therefore **permanent**. A replay after the purge is
refused because the tombstone survives, not because the payload does.

## 5. Explicitly NOT resolved by this ruling — the D1 rows-read crossing

The **D1 rows-read crossing at ≈195 days at 80 matches/day** is **not** decided here. It happens
because the settlement's balance derivation reads the whole ledger on every settlement, and the
ledger is never pruned — **no retention window delays it**. Fixing it needs a deliberate change to
`balances()`, one of the eleven hash-pinned settlement functions, and that is a **separate
decision**.

**The only owner item left open by E10 is that crossing.** The window is no longer one.

## 6. The production purge must refuse to run early

Report §12 already flags this as open: the local harness's purge **apply** route is unconditional.
The window is **recorded** (`purge_after_ms`) and **previewed** (`eligible: false` before the
window), but production must **refuse to run the purge before `purge_after_ms`**. This ruling is
not permission to ship without that gate; the §6.4 trigger is what supplies it.

## 7. No code, no harness, no result changes

This record is a **policy input**. Nothing was implemented, re-run or measured again:

- no code changed;
- no harness changed;
- the run **`2026-10-31T14-59-47-565Z`** (`65 checks, 0 failures, exit 0`) and **every measured
  figure stand**;
- the run's results JSON keeps its recorded hash
  (`f06768514383d19047c94195c9ba612f6de862bf5b620a3df8f41fc8e513d29e`), and the E10 worker keeps
  its recorded hash
  (`987dbf88c13f4f14311f49d57199c8478679e95590132c6eb76c9a9c7edb8102`).

## 8. Where this is pointed at

| Document | What it now says |
|---|---|
| `docs/implementation/Stage0_E10_Retention_Storage_Report.md` §11 | dated note after the recommendation: the window is **30 days** |
| `docs/implementation/Stage0_E10_Retention_Storage_Report.md` §13.1 | dated Arabic note after the recommendation: the window is **30 days** |
| `docs/implementation/Stage0_Remaining_Steps.md` (E10 wave note 4) | window decided; only the ≈195-day crossing waits on the owner |
| `docs/implementation/Stage0_Remaining_Steps.md` §8 (Arabic E10 bullet) | same, in Arabic |

## 9. Provenance

- Ruling content: the owner's Arabic wording was relayed **verbatim on 2026-10-31** and is quoted
  in §1. Unlike the 2026-10-16 record, no wording had to be reconstructed.
- Cost figures: `docs/implementation/Stage0_E10_Retention_Storage_Report.md` §11 (the 30-day row)
  and §12 (what E10 does and does not settle), from run `2026-10-31T14-59-47-565Z`.
- Branch `stage0/e10-retention`, tip `7c10604`; base `ef80117`, already in `origin/main` via
  PR #195.
- **Docs only.** No file outside `docs/` was created, edited, moved or deleted by this task.
