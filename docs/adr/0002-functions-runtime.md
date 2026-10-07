# ADR 0002 — The Functions layer is Kotlin/JS, sharing the engine

- **Status:** Accepted
- **Date:** 2026-10-07
- **Story:** S42 (`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` §E6b story table, line 911)
- **Decides:** the one thing the plan deliberately left open — the plan calls the
  Functions layer "Kotlin/TS-adjacent" (§7 backend-developer row, line 738).

## Context

S42 stands up the `functions/` module that every downstream Ranked story builds
on (S43 `mode`, S44 profile, S50 settlement, S70 party gate). Its four
deliverables are a deploy target, emulator wiring, a callable-auth helper, and an
idempotency guard — deliberately lightweight scaffold (RD11/RD16), no settlement
logic yet.

The language choice is load-bearing rather than cosmetic because of what the
plan's appendix requires of settlement (§Appendix, "The concrete validation
mechanism", lines 974–983). Correct-and-settle (RD12) must **re-run the project's
own rules engine** over the converged match document:

> 2. **The rules engine is pure and already test-covered.** Settlement re-runs
> scoring over the converged action sequence to recompute the authoritative final
> placement and scores.

That sentence only holds if the function invokes the *same* engine the clients
played the match with. Two independently maintained engines would make
"correct-and-settle" a comparison between implementations rather than a
recomputation, which is a different and weaker guarantee.

Three runtimes were considered. The choice was put to the owner; this ADR
records the decision and the evidence behind it.

## Decision

**Kotlin/JS on the Node.js runtime, sharing `:engine` as a compiled dependency.**

- `:engine` becomes a **Kotlin Multiplatform** module with two targets: the
  existing JVM target (unchanged consumers: `:app`, `:services`) and a new
  **JS / Node.js** target.
- A new **`:functions`** module applies `kotlin("multiplatform")` (JS target
  only) and depends on `project(":engine")`, so settlement calls the real engine.
- The module lives at `native/functions/` inside the existing Gradle build, and
  `firebase.json` points the Functions deploy/emulator at it via the `source`
  field. No second Gradle root, no composite build.

## Evidence

Each claim below was checked against a primary source rather than assumed.

1. **The engine is portable: it is pure Kotlin with exactly one JVM-only call.**
   15 source files under `native/engine/src/main/java/`. A scan for JVM-only APIs
   (`java.*`, `javax.*`, `System.`, `Thread.`, `TimeUnit`, `Calendar`, `Date`)
   matches a single file, and the match is benign — `Session.kt:141`:
   `private val clock: () -> Long = { System.currentTimeMillis() }`.
   The clock is **injected**, with the JVM call only as the default argument, so
   it becomes an `expect`/`actual` declaration with no change to call sites.
   Every other import is multiplatform-safe (`kotlin.random.Random`,
   `kotlin.math.*`). Nothing in the engine reads Android or the JVM stdlib.
   — verified by grep against `native/engine/src/main/java/`

2. **Kotlin 1.9.25 supports the Node.js target.** The Kotlin/JS project docs give
   the exact DSL this module will use — `kotlin { js { nodejs { } binaries.executable() } }`
   under the multiplatform plugin. Kotlin/JS has compiled to the IR compiler by
   default since 1.9.0, so the toolchain ADR 0001 pins (Kotlin 1.9.25) is not a
   blocker and **this decision does not force a Kotlin 2.x migration** — the
   reversal triggers in ADR 0001 are untouched.
   — [Set up a Kotlin/JS project](https://kotlinlang.org/docs/js-project-setup.html),
     [Kotlin/JavaScript overview](https://kotlinlang.org/docs/js-overview.html)

3. **The pinned firebase-tools supports the Node 22 runtime.** The repo's
   `firebase-tools@13.35.1` lists `nodejs22` among its supported runtimes, so the
   functions can target the same Node 22 the CI jobs already install
   (`android.yml` sets up node 22 for the emulator and App Distribution steps).
   — `node_modules/firebase-tools/lib/deploy/functions/runtimes/supported/types.js:56`

4. **`firebase.json` can point the Functions source at a non-default directory.**
   `prepare.js` reads `config.source` to resolve the functions directory, and the
   validator's own error text is explicit — *"could not deploy functions because
   the "dir" directory was not found. Please create it or specify a different
   source directory in firebase.json"*. So `"functions": { "source": "native/functions" }`
   is a supported first-class configuration, which is what lets the module stay
   inside the single Gradle build.
   — `node_modules/firebase-tools/lib/deploy/functions/prepare.js:141`,
     `node_modules/firebase-tools/lib/deploy/functions/validate.js:129–133`

5. **`firestore.rules` stays frozen and byte-identical.** The plan excludes any
   rules revision, and the appendix's trust model depends on the existing
   deny-list plus the Functions being the only legitimate Ranked write path. The
   Functions use the Admin SDK, which **bypasses** `firestore.rules` entirely, so
   no rules change is needed for the Functions to write progression fields that
   clients cannot. `firestore.rules.sha256` must not move.
   — plan §"What is NOT included" (line 858); §Appendix trust tiers (lines 985–992)

## Alternatives considered

- **TypeScript, engine re-implemented by hand.** The standard Firebase path with
  the best tooling and emulator support. **Rejected:** scoring would exist twice,
  drift, and correct-and-settle would recompute a match with a *different* engine
  than the four clients played it on — directly contradicting the appendix's
  "reuse the engine" design and the RD12 guarantee it is built on. Would require a
  shared golden corpus generated from `:engine` to keep the two honest, which is
  the smell that gives the answer away: the corpus exists to detect the divergence
  that a single engine makes impossible.

- **Kotlin/JVM on the Functions Framework (Java 21 runtime).** Reuses the engine
  with zero multiplatform work — the cheapest today. **Rejected:** multi-second
  JVM cold start lands on the Ranked result screen, which the plan already names
  as risk R24 ("a 'settling…' wait that feels broken at the most satisfying moment
  in the game"); and the Firebase CLI and Local Emulator Suite support for JVM
  functions is far weaker than Node's, which would make S42's own *emulator
  wiring* deliverable the hard part of the story. Paying R24 permanently to save
  one `expect`/`actual` is a bad trade under RD16.

## Consequences

- **One rules engine, two targets.** The engine is compiled twice from one source
  tree. A scoring change lands in the app and in settlement together, by
  construction. This is the single biggest integrity property of the Ranked
  architecture, and it is what the appendix's four trust tiers rest on.
- **`:engine` becomes multiplatform, which touches every build.** `:app` and
  `:services` continue to consume the JVM target unchanged, but the metadata the
  compiler emits moves, so **a clean rebuild of the whole tree is expected** and
  CI caches will invalidate once. `:engine:test` keeps running unchanged on the
  JVM target — the existing JVM test suite is not ported or duplicated.
- **Kotlin/JS costs are accepted knowingly.** The JS build is slower than the JVM
  build and produces a larger artifact; `kotlin("multiplatform")` adds an
  `expect`/`actual` seam for the clock; and the `firebase-functions` /
  `firebase-admin` SDKs are consumed through JS interop (`external` declarations),
  which is the bulk of the new boilerplate. None of it is settlement logic — S42
  pays that cost once, on scaffold, so S50 never has to.
- **The deploy artifact is a Node package.** `binaries.executable()` emits the
   entry point the Firebase CLI expects; the module's build assembles a
   deployable directory shape and `firebase.json`'s `source` points at it.
- **`firestore.rules` is not touched by this story.** The Functions write through
  the Admin SDK; clients keep hitting the frozen deny-list. Any future rules edit
  re-opens a separate decision and must re-pin `firestore.rules.sha256`.

## When to reverse this decision

Move the Functions off Kotlin/JS if **any** of these becomes true:

1. **The Kotlin/JS toolchain forces a Kotlin 2.x migration** the plan's
   conservative scenario does not budget for. ADR 0001's reversal triggers govern;
   if they fire *because of* this module, the TS alternative is the fallback, not
   a Kotlin 2.x migration.
2. **Engine reuse proves unworkable in practice** — e.g. the JS target cannot
   express a rule the settlement path needs, or the Kotlin/JS bundle is large
   enough to materially hurt cold start (R24). Then either restructure the engine
   or accept the TS re-implementation plus the golden-corpus guard.
3. **Firebase first-class support for the JS target regresses** — e.g. the
   emulator or CLI drops reliable support for a custom `source` directory or for
   the Node runtime the module targets.

The migration is one-way-safe in the direction that matters: the engine stays
Kotlin regardless, so a future move to a different Functions runtime reuses it;
only the interop layer is throwaway.
