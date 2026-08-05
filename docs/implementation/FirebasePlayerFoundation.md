# Firebase Player Foundation — Sprint 2.6 Implementation Report

**Scope actually implemented:** player-profile creation/loading only, wired into the existing Login flow. No rooms, no matches, no multiplayer sync, no Cloud Functions, no billing changes. Matches the architecture in `docs/architecture/FirestoreSchema.md` (`players/{uid}`), `PlayerLifecycle.md`, `ServiceArchitecture.md` (`PlayerService`), and `SecurityArchitecture.md` exactly — no competing schema was invented.

## Files created and modified

| File | Change |
|---|---|
| `design-ui/player-service.js` | **New.** The `PlayerService` module — the only code that reads/writes `players/{uid}`. |
| `firestore.rules` | **New.** Rules for `players/{uid}` only; deny-by-default everywhere else. **Not yet deployed** — see "Deployment" below. |
| `design-ui/firebase-init.js` | **Modified.** Added `window.Db = firebase.firestore()`, guarded so pages that don't load the Firestore compat script (none currently besides Login) don't break. |
| `design-ui/login/index.html` | **Modified.** Added the `firebase-firestore-compat.js` and `player-service.js` script tags; added a `bootstrapProfile()` helper; wired it into all five success paths (returning-session check, Create Account, Sign In, Google, Guest); fixed a pre-existing gap where anonymous (Guest) sign-in never set `displayName` on the Auth user, leaving `PlayerService` nothing to read a name from. |
| `docs/implementation/FirebasePlayerFoundation.md` | **New.** This document. |

No gameplay engine file (`design-ui/engine/*.js`) was touched. No file outside the list above was modified.

## Player profile lifecycle (as implemented)

1. **First sign-in, any provider** (Anonymous / Email+Password / Google / new account creation) → Login calls `PlayerService.ensurePlayerProfile(user)` → no `players/{uid}` document exists yet → a Firestore **transaction** reads-then-creates it in one atomic step, using the approved defaults (see below). This is the concrete fix for "refreshing/double-submitting must not create duplicates" — a transaction, not a plain get-then-set, is what actually guarantees that under a race.
2. **Every subsequent sign-in** (including the "already signed in" bootstrap on page load) → the same transaction observes the document already exists → touches only `lastSeenAt` → returns the **existing** profile untouched. Progression (`coins`, `gems`, `rank`, etc.) is never re-defaulted over.
3. **Firestore unavailable** (offline, CDN blocked, rules misconfigured) → `ensurePlayerProfile` rejects → Login's `bootstrapProfile()` wrapper catches it, logs it, and returns `null` → the player still proceeds to Lobby using whatever name is locally available (the Auth profile's `displayName`, or what they just typed). **A Firestore failure never blocks login.**

## Public PlayerService API

```
ensurePlayerProfile(user) → Promise<PlayerProfile>
getPlayerProfile(uid) → Promise<PlayerProfile | null>
updatePlayerProfile(uid, changes) → Promise<void>   // silently drops any non-whitelisted key
subscribeToPlayerProfile(uid, callback) → unsubscribe()
mapAuthUserToProfileDefaults(user) → PlayerProfile   // pure, no I/O
```

Matches `docs/architecture/ServiceArchitecture.md`'s `PlayerService` interface. `applyMatchResult` from that document is intentionally **not implemented yet** — it belongs to match-completion, which is out of this sprint's scope.

## Player document fields (matches `FirestoreSchema.md` exactly)

`displayName, accountType, email, avatarInitial, rank, rp, wins, streak, level, coins, gems, createdAt, lastSeenAt, currentRoomId, currentMatchId`

New-player defaults: `rank: "Unranked"`, `rp/wins/streak: 0`, `level: 1`, `coins: 500`, `gems: 10`, `currentRoomId/currentMatchId: null`. The 500/10 starter grant is a placeholder constant defined once in `player-service.js` (`STARTER_COINS`/`STARTER_GEMS`) — easy to tune later, not scattered across call sites, per `PlayerLifecycle.md`.

**No passwords, tokens, or Firebase config secrets are stored in this document** — confirmed by the field list above; the document contains only display/progression data.

**One field's role is worth stating explicitly, since it's a slight repurposing rather than a new field:** `lastSeenAt` is documented in `PlayerLifecycle.md`/`FirestoreSchema.md` as "the heartbeat field used for presence." This sprint doesn't implement presence (that's `RoomLifecycle.md`'s concern, explicitly out of scope here) — it stamps `lastSeenAt` once per login instead, as a "last active" timestamp. This is a staged rollout of the same field's intended purpose, not a competing schema: once Room presence-heartbeat code exists, it refreshes the same field more frequently, and nothing about this sprint's writes conflicts with that.

## Security model

Rules (in `firestore.rules`, not yet deployed — see below):

- `get`: owner only (`request.auth.uid == uid`).
- `list`: always denied — a client can never enumerate/query all player profiles.
- `create`: owner only, **and** the submitted document must exactly match the approved defaults (`rank == "Unranked"`, `coins == 500`, etc.) — a client cannot create itself a profile with a head start.
- `update`: owner only, **and** only the fields `displayName`, `avatarInitial`, `lastSeenAt`, `currentRoomId`, `currentMatchId` may change (checked via `diff().affectedKeys().hasOnly([...])`).
- `delete`: always denied (no account-deletion flow designed yet).
- Every other collection: unchanged deny-all, exactly as it was live before this sprint.

This is enforced in **two independent layers** — the rules above, and `player-service.js`'s own `ALLOWED_UPDATE_FIELDS` whitelist, which silently drops disallowed keys before ever sending a write. Neither layer assumes the other is sufficient alone.

## Protected fields — stated honestly, not glossed over

`coins`, `gems`, `rank`, `rp`, `wins`, `streak`, `level` cannot be changed by a client through `updatePlayerProfile` (service layer) or through a raw Firestore write (rules layer, once deployed). **This sprint implements no path that changes them at all** — no match-completion code exists yet to award currency or update stats. That's the honest current state: these fields are currently immutable from first creation onward, not "securely mutable." The moment match-completion logic is built (a later sprint), it will need its own decision about *how* those fields become mutable — and per `docs/architecture/MigrationPlan.md`, the intended answer is Cloud Functions, not a client-side write path with a cleverer rule. Nothing in this sprint should be read as "client-authoritative currency is secure" — it is simply "not yet writable," which is stronger than secure-but-writable, not weaker.

## Offline and failure behavior

| Scenario | Behavior |
|---|---|
| No network / Firestore CDN blocked | `window.Db` is `null` → every `PlayerService` method rejects with a clear message → Login's `bootstrapProfile()` catches it, logs it, proceeds to Lobby anyway. **Actually reproduced and observed** in this sandbox's headless browser test (see Tests below) — this is not a hypothetical path. |
| Firestore permission denied | Same rejection path as above; the rules themselves were verified live (see Tests) to correctly deny an unauthorized write today. |
| Profile read/create timeout | Not separately handled — inherits whatever timeout behavior the Firestore SDK itself has; no custom timeout wrapper was added (kept in scope; a bespoke timeout mechanism wasn't something this sprint's failure modes required). |
| Existing Auth user with no profile yet | Handled by design — `ensurePlayerProfile` treats "no document" as "create it," regardless of how long that Auth account has existed. |
| Existing profile with missing optional fields / older schema | Not applicable yet — there is only one schema version in existence (this sprint created the first one). No migration code was written because there is nothing yet to migrate from; this is called out rather than speculatively built. |

## Known Spark-plan limitations

- Everything in this sprint runs client-authoritative, per the approved architecture (`MigrationPlan.md`). Rules provide real, verified enforcement for ownership and default-shape checks; they cannot verify that a *future* stat-changing write (once one exists) was computed correctly — that's explicitly deferred to the Cloud Functions migration.
- The starter-grant amounts (500 coins / 10 gems) are an placeholder judgment call, not a game-design decision — easy to change in one place (`player-service.js`'s two constants) when real economy numbers are decided.

## Future migration notes

Per `docs/architecture/MigrationPlan.md`: when match-completion logic is built and needs to change `coins`/`gems`/`rank`/`rp`/`wins`/`streak`/`level`, the correct next step is a Cloud Function that becomes the *only* writer to those fields — at that point the rules for those specific fields simplify to "deny client writes entirely," not "loosen the current default-shape check." Nothing in this sprint needs to be rewritten for that to happen; `PlayerService`'s `updatePlayerProfile` already refuses to touch those fields today, so a future function-based path doesn't have to fight a client-side one that already exists.

## Deployment status — action required

`firestore.rules` is written and reviewed but **not deployed to the live project.** This session has no Firebase CLI credentials for this project, so deployment is a manual step, same as the earlier deny-all lockdown:

1. Firebase Console → Firestore Database → **Rules** tab
2. Replace the current contents with `firestore.rules`' contents (from this repo)
3. Click **Publish**

Until that's done, the live project keeps its current deny-all rule — meaning `PlayerService` will call `ensurePlayerProfile` correctly, but every call will fail with `permission-denied` (caught by the fail-open path, so login still works, just without a synced profile) until the rules above are published. **This is expected and safe** — it's the same reason the earlier Firestore lockdown was recommended as safe: nothing breaks by being cautious, it just means the profile sync silently no-ops until you publish the rule.

## Tests performed

See `TEST_CHECKLIST.md` in the QA package for the full, honestly-marked list. Summary: 28 real automated unit tests pass against the actual `player-service.js` code (not a re-implementation) using an in-memory Firestore stub; the current live deny-all rule was verified via a real REST call against the actual project (created and deleted a throwaway test user); the offline/fail-open path was verified in a real (if network-constrained) browser environment. Tests requiring the new rules to be live (cross-user isolation, protected-field rejection via real Firestore) are marked **NOT PERFORMED**, with the reason being the pending manual deploy step above, not an oversight.
