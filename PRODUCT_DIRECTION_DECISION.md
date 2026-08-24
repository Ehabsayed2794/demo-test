# Product Direction Decision

**Decision ID:** PD-001
**Status:** Accepted / authoritative
**Decision date:** 24 August 2026
**Scope:** Product architecture, build target, roadmap, testing, deployment, and future engineering tasks

## Decision

`design-ui/` is the **primary production product and target production codebase** for the Estimation multiplayer game. Its engines, `GameSession`, `MatchAdapter`, `MatchService`, Firebase integration, Firestore rules, and associated multiplayer architecture are the foundation that future gameplay, UI, lifecycle, persistence, synchronization, and release work must target.

`src/` is **legacy/transitional score-tracker code**. It is not the target architecture for the full multiplayer product. It must not be expanded with new multiplayer functionality, and the multiplayer engine must not be migrated into it merely because the current root Vite build points there.

## Why this decision was made

The repository contains substantial, coherent engineering investment in `design-ui/`: a four-player Estimation rules engine, bidding and confirmation state machine, trick-taking engine, scoring engine, room and match lifecycle services, Firebase authentication, Firestore persistence and security rules, synchronization adapters, hand-deal authority work, round/match completion, rematch flows, and focused regression/contract coverage. Those modules form a recognizable multiplayer architecture with explicit ownership boundaries and a documented Spark-constrained trust model.

The root `src/` application is a narrower manual score tracker. It has useful transitional value, but it has a separate React state model, separate scoring implementation, no multiplayer session, no Firestore integration, no room/match lifecycle, no card engine, and no path to the complete multiplayer experience without reimplementing the larger system. Its scoring utility also diverges from the authoritative rules in known areas. Treating `src/` as the destination would duplicate or discard the existing multiplayer work and create a second migration of rules, state, persistence, synchronization, and UI behavior.

## Rejected alternative

Migrating the multiplayer system into `src/` is rejected. The decision is not based on the current build configuration; it is based on product scope and accumulated architecture. The correct work is to make the selected `design-ui/` product buildable, deployable, reachable, and user-facing, while preserving its existing engine/service boundaries. A future implementation may use React or another presentation technology for the `design-ui/` product, but it must consume or deliberately replace the canonical `design-ui/` contracts rather than silently creating a parallel game in `src/`.

## Migration and deprecation strategy for `src/`

`src/` remains in the repository during the transition. It is maintained only for explicitly scoped legacy score-tracker fixes, compatibility, migration tooling, or deprecation work. No new multiplayer feature, Firebase integration, card engine, lobby, room, bidding, trick-play, rematch, or multiplayer scoring feature should be added under `src/`.

The transition will occur in three controlled steps. First, the production build and deployment pipeline will be changed to produce the selected `design-ui/` artifact, without deleting `src/`. Second, any remaining users or consumers of the score tracker will be identified; a compatibility or archival decision will be recorded. Third, once no supported workflow depends on it, `src/` may be archived or removed in a separate, explicitly approved cleanup task. Deletion is not part of this decision and must never be performed by an unrelated gameplay task.

## Consequences

| Area | Consequence |
|---|---|
| Build | The current root Vite build is not yet the production build for the selected product. Build entry points, dependency loading, asset handling, and deployment packaging must be changed in a dedicated integration phase. |
| Deployment | The release artifact must load the `design-ui/` screens and their Firebase configuration. Existing rules and service deployment status must be verified separately; selecting the codebase does not claim that deployment is complete. |
| Testing | New multiplayer tests target `design-ui/`. The existing legacy `src/` score tracker may retain a small maintenance suite, but its tests cannot stand in for multiplayer engine, Firestore, synchronization, or browser tests. |
| Rules and scoring | The updated authoritative rules document governs `design-ui/` semantics. Legacy `src` scoring may be fixed only when explicitly required for compatibility or deprecation; it is not a reason to fork the canonical multiplayer rules. |
| UI | Future gameplay UI work targets the `design-ui/` state and service contracts. The missing Bidding/Table render layer and navigation are now integration work for the primary product, not evidence that the product direction is undecided. |
| Architecture | Preserve the existing Engine → `GameSession` → `MatchAdapter` → `MatchService` → UI separation. Do not move Firestore access into engines or duplicate engine rules in the UI. |
| Roadmap | P0-01 is resolved. The next critical path is canonical-rule hygiene, reproducible validation, build/deployment integration, core UI completion, and multiplayer hardening for `design-ui/`. |
| Working tree | Existing production/test/path-portability changes remain user work and must not be reset, cleaned, or folded into this documentation decision. |

## Remaining integration work

The decision does not claim that `design-ui/` is already shipped. The remaining migration/integration work is to select and implement its production entry point; make its static screens and Firebase dependencies part of the release build; preserve environment-specific Firebase configuration; connect Login → Lobby → Match and the gameplay render layer; register the required synchronization pipelines; add a reachable round/match completion/rematch flow; establish CI and emulator/browser validation for the selected artifact; verify Firestore Rules compilation/deployment; and run a production-like smoke test from a clean checkout.

The integration must also resolve whether the selected UI remains static HTML/vanilla JavaScript or is rebuilt as a React presentation layer. That is a presentation decision, not permission to create a second rules/state implementation. Whichever presentation path is selected must consume the existing `design-ui/` contracts and retain the documented trust boundary.

## Governance

Every future task must state that it targets `design-ui/` unless it is explicitly labeled legacy `src/` maintenance or migration/deprecation work. A task that changes game semantics must cite the updated authoritative rules document. A task that changes build/deployment must demonstrate that the resulting artifact is the selected `design-ui/` product. This decision should be revisited only by an explicit product-direction decision, not by an implementation convenience.
