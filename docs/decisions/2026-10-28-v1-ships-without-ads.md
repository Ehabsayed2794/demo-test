# D3 amendment — v1 ships without ads

- **Date:** 2026-10-28
- **Status:** DECIDED (owner ruling, 2026-10-28) — implemented in `agent/no-ads-v1`
- **Owner ruling:** v1 ships with no ads; monetization moves to v1.1.
- **Supersedes, for v1 scope only:** the two platform rows "AdMob + UMP consent" (20h)
  and "RevenueCat + Remove Ads" (14h) in
  `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` §2, and D3's RevenueCat sentence.
- **Does NOT supersede:** D3's free-tier posture, D5 (no Blaze), or any RD decision.

## Question

The plan's platform bucket carried ads and billing inside v1: AdMob with a UMP
consent flow, RevenueCat, and a one-time "Remove Ads" purchase. The plan also told
management to start the AdMob/Play Console/RevenueCat account activations "today"
because they are external clocks.

Should v1 include ads?

## Ruling

**No. v1 is free to play with no ad SDK, no UMP consent flow, no RevenueCat, and no
"Remove Ads" purchase.** The two platform rows (34h) leave v1 and land in v1.1.

Reasons, in the order they matter:

1. **Expected revenue at launch is ~0.** Ads pay per impression; an app with no
   installed base has no impressions. The 34h buys no near-term revenue.
2. **It removes three review surfaces from the one release that must not be
   rejected:** the ad declaration on the data-safety form, the UMP/GDPR consent
   flow, and an ad SDK inside an app whose "bid" vocabulary already sits near the
   simulated-gambling line.
3. **Pacing is not validated yet.** The Ranked ladder, the season reset and the
   real-device playthrough have not been exercised by real people. Inserting
   interstitials before pacing is measured optimizes the wrong variable.
4. **It is cheap to reverse.** Adding ads later is a normal app update plus a
   data-safety revision. It touches no backend, no rules, and no data model.

## What this changes

- **Plan:** the ad and billing rows are pointer-noted as v1.1 scope; the
  "start the AdMob/RevenueCat accounts today" instruction is superseded — the
  external clock that gates launch is the **14-day closed test**, not the ad
  accounts.
- **Store listing:** `docs/release/play-listing.md` already states "No ads SDK
  currently implemented" and "No third-party advertising or purchase SDK sharing
  planned". It stays accurate and needs no edit — but its own "Recheck final
  dependencies" instruction must be honoured at submission time.
- **Copy:** the listing must not promise "no ads, forever". "Free to play" only.

## What this does not change

- AdMob and RevenueCat **accounts** are no longer launch-gating, and starting them
  is not required. If the owner starts them anyway, that costs zero engineering
  hours and simply removes future lead time. It is optional, not a plan dependency.
- The ad **placement rule** is decided now, so a later UI does not have to be
  re-cut: when ads do land, they are **interstitial between matches only** —
  never during a hand, bidding, a placement reveal, or a settlement screen; no
  in-play banner; **no rewarded video** (there is no economy to reward, and the
  plan already excludes it).

## What would reopen this

- A revenue milestone that requires demonstrated monetization in the current
  quarter, or
- A publisher/investor requirement for it.

Neither holds as of this ruling.

## Evidence

- No ad, consent, or billing dependency exists anywhere in `native/` on
  `origin/main` (`88bea38`): no AdMob, no UMP, no RevenueCat — so this ruling
  changes **documentation and scope only**, and no code is deleted by it.
- `docs/release/play-listing.md` (lines 74, 81, 95) already declares the absence
  of an ad SDK, so the ruling keeps the listing truthful rather than changing it.
