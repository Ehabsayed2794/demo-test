# ADR 0001 — Stay on Kotlin 1.9.25 + legacy Compose compiler

- **Status:** Accepted
- **Date:** 2026-09-21
- **Story:** S2 (`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` §2C, Phase 0b)
- **Supersedes:** the open question in the plan's R12 (Compose BOM
  2024.10.01 / Kotlin 1.9.25 as a "library ceiling")

## Context

Phase 0b must move the app to `compileSdk`/`targetSdk` 36 because Google has
required API 36 for new apps since 2026-08-31 (a hard Play rejection, R1).
That forces an AGP/Gradle bump, which raises the question the plan left open
as a spike: **must Kotlin also move to 2.x?**

Kotlin 2.x replaces the legacy Compose compiler (selected via
`composeOptions { kotlinCompilerExtensionVersion }`) with the
`org.jetbrains.kotlin.plugin.compose` Gradle plugin, versioned *with* Kotlin.
The two are not interchangeable, so this is a real fork in the road, not a
version bump.

The plan frames a forced Kotlin 2.x migration as the trigger for its
**conservative** scenario (790h / 21 weeks instead of 727h / 18). The point
of this spike is to find out whether that trigger actually fires, or whether
it is a hypothetical we can defer.

## Decision

**Stay on Kotlin 1.9.25 with the legacy Compose compiler 1.5.15.**

The API-36 toolchain (AGP 8.13.2 + Gradle 8.14.3 + `compileSdk` 36) is
adopted; the Kotlin/Compose-compiler pairing is **not** changed.

## Evidence

Each claim below was checked against a primary source rather than assumed.

1. **`compileSdk` 36 does not require a newer Kotlin.** Compiling against
   API 36 needs **AGP ≥ 8.9.1** (Android Studio Meerkat 2024.3.1 Patch 1).
   That is an AGP constraint, not a Kotlin one.
   — [About Android Gradle plugin](https://developer.android.com/build/releases/about-agp)

2. **The required Gradle bump is satisfied without touching Kotlin.**
   AGP 8.11 and 8.13 both require **Gradle ≥ 8.13**. The committed wrapper
   pins 8.14.3.
   — [About Android Gradle plugin](https://developer.android.com/build/releases/about-agp)

3. **AGP 8.13 still supports the legacy Compose compiler.** The 8.13.0
   release notes contain **no** removal or deprecation of
   `composeOptions.kotlinCompilerExtensionVersion`, and no minimum-Kotlin
   requirement. (They do note `kotlinOptions` remains deprecated in favour of
   `compilerOptions` — still functional, which is why we keep it: the
   replacement DSL is awkward to pair with Kotlin 1.9.) The same page lists
   JDK 17, SDK Build Tools 35.0.0, and max API 36.1.
   — [AGP 8.13.0 release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

4. **The flagship monetization SDK does not force Kotlin 2.x.** The GMA
   Next-Gen SDK *lowered* its minimum required Kotlin from 2.1 **to 1.9** in
   version **0.15.1-alpha01** (2025-04-28), and also lowered its minimum AGP
   from 8.2.0 to 7.3.1. This is the single most load-bearing input to the
   decision, and it points at staying.
   — [Google Mobile Ads SDK release notes](https://developers.google.com/admob/android/next-gen/rel-notes)

   **That floor has not been re-raised.** Checked against every later entry
   on the same page: the line went 0.15.1-alpha01 → 0.24.x betas → the 1.x
   stable line, and **no version after 0.15.1-alpha01 states an increased
   minimum Kotlin version.** The current **1.4.0** (2026-08-20) notes cover
   only Picture-in-Picture ads and mediation-initialization error reporting
   (`onInitializationFailed(AdError)`), with no Kotlin requirement stated at
   all. So the reduction has held for ~17 months and across the 0.x→1.x
   version-scheme break — it is the SDK line's settled position, not an
   alpha-era footnote.

5. **Billing is not a forcing function either.** Play Billing Library 8's
   2026 breaking changes are **API removals**
   (`queryPurchaseHistoryAsync()`, `enablePendingPurchases()`), not
   compiler-metadata constraints; RevenueCat (D3's verification layer)
   needs only Kotlin ≥ 1.8.
   — [Google Play Billing Library 8 migration](https://foresightmobile.com/blog/google-play-billing-library-8-migration),
   [Play Billing release notes](https://developer.android.com/google/play/billing/release-notes)

6. **Nothing in the current dependency graph forces the move.** Every
   dependency is 2024-era (Compose BOM 2024.10.01, Firebase BOM 33.7.0,
   AndroidX from mid-2024) and the build is green in CI on Kotlin 1.9.25
   today. An AGP/`compileSdk` bump does not change any dependency's Kotlin
   metadata version.

## Consequences

- **Keeps the plan's realistic path.** The conservative 21-week scenario's
  main trigger ("toolchain spike forces Kotlin 2.x migration") does not
  fire. This is the outcome the 18-week estimate assumed.
- **The library ceiling is accepted deliberately, not accidentally.** We
  remain locked out of post-October-2024 Compose APIs and any AndroidX
  artifact that requires Kotlin 2.0. **Do not bump Compose BOM or AndroidX
  past their current pins without re-opening this ADR.**
- **One known coupling to watch.** `botPersonality.ts` ↔ `botSimulation.ts`
  (R5) is a gameplay concern, unrelated to this decision, but it is the same
  shape of problem: a "deferred" module that is more coupled than it looks.
  The mitigation there (`SimPort`) is unaffected by the Kotlin version.
- **16KB page-size alignment needs no manual work today.** AGP ≥ 8.5
  auto-aligns packaged `.so` files to 16KB boundaries, and the app currently
  ships no native libraries. When AdMob/billing land (Phase 7) and pull in
  `.so` files, verify with `zipalign -c -P 16` on the release APK.

## When to reverse this decision

Migrate to Kotlin 2.x + `plugin.compose` if **any** of these becomes true:

1. A resolved dependency ships Kotlin 2.x metadata and the build fails with
   "Module was compiled with an incompatible version of Kotlin. The binary
   version of its metadata is X.Y" — the classic forced-migration symptom.
2. Any release notes for AdMob, UMP, Play Billing, AndroidX, or Compose we
   adopt states a Kotlin ≥ 2.0 minimum.
3. A feature genuinely needs a post-October-2024 Compose API.

The migration is **contained and one-way-safe**: apply
`org.jetbrains.kotlin.plugin.compose` at the same version as Kotlin, delete
`composeOptions.kotlinCompilerExtensionVersion`, and bump Kotlin. Kotlin 2.x
reads 1.9.x metadata, so no dependency needs a companion bump — which is why
deferring is cheap and doing it now (with bots and online in flight) would
be the expensive time to pay for it.
