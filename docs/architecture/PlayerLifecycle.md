# Player Lifecycle

Design only. Covers the path requested: Anonymous → Registered → Returning User → Guest Upgrade → Profile Creation → Inventory → Statistics — reconciled against what the Login screen (already implemented and tested this session) actually produces today.

## Anonymous

Firebase Auth's Anonymous provider (`auth.signInAnonymously()` — already wired in `design-ui/login/index.html`). Produces a stable `uid` with no email, `isAnonymous: true`. This is the "Guest" path from the Login screen's own UI ("Continue as Guest").

## Registered

Email/Password or Google sign-in produces a `uid` with `isAnonymous: false`, and (for Google) a real `displayName`/`email` already populated by the provider.

## Returning User

**Already implemented as of this session:** the Login screen checks `auth.onAuthStateChanged()` on load and, if a session already exists, skips the form and proceeds directly to Lobby using the persisted identity. This document formalizes the profile-side counterpart that doesn't exist yet: on that same "already signed in" path, `PlayerService` should confirm a matching `players/{uid}` document exists (see Profile Creation below) rather than assuming the very first sign-in already created one.

## Guest Upgrade

A guest (anonymous) account later choosing to "create a real account" should use Firebase Auth's **account linking** (`linkWithCredential`), not a fresh sign-up — linking preserves the *same* `uid`, meaning every match/room/friend reference already written under that guest's `uid` stays valid with zero data migration. This is the reason `players/{uid}` is keyed by `uid` and not by email: the identity is stable across the guest→full transition, only the auth *method* attached to it changes.

- **Design requirement:** `AuthService` must expose `upgradeGuestAccount(credential)` as a distinct operation from `signUp`, specifically so a guest's existing `currentRoomId`/`currentMatchId`/`stats`/`inventory` are never touched by the upgrade — only the Auth-side credential changes.

## Profile Creation

Currently a gap: the Login screen creates Auth identities but nothing writes a matching `players/{uid}` Firestore document. This document specifies where that write belongs:

- **Trigger:** the *first* time `PlayerService` observes a signed-in `uid` with no existing `players/{uid}` document (checked via `get()`, not assumed) — whether that's a brand-new sign-up or a returning user whose profile write failed previously (self-healing, not one-shot).
- **Initial fields:** `displayName` from the Auth profile (or a default), `accountType` derived from `user.isAnonymous`, sensible zeroed defaults for `rank`/`rp`/`wins`/`streak`/`level`, and starting `coins`/`gems` balances (a fixed small grant — the "new player" allotment, defined once in `PlayerService`, not scattered across call sites).
- **Idempotency requirement:** this write must be safe to attempt on every login, not just the first — using `set(..., { merge: false })` guarded by a preceding existence check (or a rules-enforced "can only create, never overwrite existing fields on this path") avoids accidentally resetting an existing player's progress if the creation check ever races with itself.

## Inventory

`inventory/{uid}` (see `FirestoreSchema.md`) is created empty alongside the profile at Profile Creation time — an empty `items: []` array, not a missing document, so later reads never need a "does this exist yet" branch.

## Statistics

`wins`, `streak`, `level`, `rp` live on `players/{uid}` itself (read-heavy, shown constantly in Lobby/Room/Match UI — see the schema doc's rationale for keeping them there instead of a separate `stats/{uid}` collection). They update exactly once per completed match, at `MATCH END` (see `MatchLifecycle.md`), via the same write path that commits final scores — never as a standalone client write, for the same integrity reason `coins`/`gems` are restricted (see `SecurityArchitecture.md`).

## Where this document intentionally stops

Session/device management (multiple devices signed into one account, forced logout, etc.) is out of scope for this design pass — nothing in the current offline build or the Sprint 2/3 roadmap needs it yet, and speculatively designing for it now would be exactly the kind of "design for hypothetical future requirements" this project has otherwise avoided.
