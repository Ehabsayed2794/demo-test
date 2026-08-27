# Deployed-Bundle Forensics — 2026-08-26

## Scope and corrected frame

This investigation was read-only. No production deployment, Hosting command, test edit, Rules edit, application fix, or source checkout mutation was performed. The only repository file written was this report.

The active worktree was at `b1e7088`, not detached at `4a71383`. The exact requested source was nevertheless inspected directly from the Git object `4a7138382af18e81b786166194a0e964a6a7157b`, and an isolated archive of that exact commit was built in `/tmp/b2-head-4a`. The report therefore distinguishes **worktree state** from **target-commit state** throughout.

The accepted R1 finding remains that `buildHand` and `bindStatic` are standalone repository bugs: the exact source contains bare calls but no definitions. Under the corrected frame, that finding is not treated as a staleness indicator. The resumed work completed the live/local comparison and the Git-time-line investigation requested in Q-A and Q-B.

## R1 — Source truth at 4a71383

### Result: definitions absent

The exact commit contains calls and comments referring to both names, but no function, variable, window property, or global property definition matching either name.

| Symbol | Exact source references at 4a71383 | Definition at 4a71383 |
|---|---|---|
| `buildHand` | `design-ui/engine/bidding-engine.js:963` calls `buildHand()`; `:974` mentions it in a comment | **ABSENT** |
| `bindStatic` | `design-ui/engine/bidding-engine.js:964` calls `bindStatic()`; `design-ui/engine/table-engine.js:351` calls it; `design-ui/match/index.html` references it in comments | **ABSENT** |

The definition-pattern search covered function declarations, assignments to `const`/`let`/`var`, and `window`/`globalThis` assignments. It returned no definition for either symbol. The source itself documents the condition in the Sprint 3.6 comment: the functions “remain undefined here exactly as before.”

This is a confirmed current-bundle defect. It does not establish that a p4 client was stale.

## Q-A — Live bundle versus 4a71383 build

### A1. Build method and coverage

The exact commit was archived into `/tmp/b2-head-4a`, dependencies were installed with `npm ci`, and the repository’s deployment-equivalent pipeline was run:

```text
npm run build:hosting
```

That command executed `tsc -b`, `vite build --base=/estemshan/`, and `node scripts/build-hosting.mjs`. The resulting `hosting-dist/` contained the copied `design-ui/` multi-page bundle, the generated root redirect wrapper, and the Vite-built `/estemshan/` score-tracker artifact.

The live comparison fetched the root and every same-path HTML, JavaScript, and CSS file present in the exact build output. It covered 34 files. Every request returned HTTP 200 and every live byte stream matched the isolated 4a71383 build byte-for-byte.

### Hash comparison table

| Asset path | HTTP | Local SHA-256 | Live SHA-256 | Bytes local/live | Result |
|---|---:|---|---|---:|---|
| `analytics-service.js` | 200 | `cdc88113faaf9417a0ce037d555056bc482e782035774a93313ca24d3f7cfc67` | `cdc88113faaf9417a0ce037d555056bc482e782035774a93313ca24d3f7cfc67` | 1345/1345 | **IDENTICAL** |
| `engine/bidding-engine.js` | 200 | `a6ceb8787a66d98a7dc83e2d2e8f8aa323b51c83f415cc96bbe6e829bea7d7a7` | `a6ceb8787a66d98a7dc83e2d2e8f8aa323b51c83f415cc96bbe6e829bea7d7a7` | 53624/53624 | **IDENTICAL** |
| `engine/cards.js` | 200 | `aaf95f3630e40a6fa358f2dc7035742c505cf6842aaa8ec072ccfaef8607b4e9` | `aaf95f3630e40a6fa358f2dc7035742c505cf6842aaa8ec072ccfaef8607b4e9` | 2556/2556 | **IDENTICAL** |
| `engine/dealer.js` | 200 | `2bd29be5ed5b5c0f22a474009b39483eb3c8cde4769f891864e4ca74cd40f7d3` | `2bd29be5ed5b5c0f22a474009b39483eb3c8cde4769f891864e4ca74cd40f7d3` | 2682/2682 | **IDENTICAL** |
| `engine/deck.js` | 200 | `adf75fb1924026a7b2db669705089eda5c46105c9d54b7dc2cc287c95c859ade` | `adf75fb1924026a7b2db669705089eda5c46105c9d54b7dc2cc287c95c859ade` | 5053/5053 | **IDENTICAL** |
| `engine/scoring-engine.js` | 200 | `64dd26931e22c856e797f3fb67f569f000c9d08b2419777ce86ca4ccd7940b32` | `64dd26931e22c856e797f3fb67f569f000c9d08b2419777ce86ca4ccd7940b32` | 22157/22157 | **IDENTICAL** |
| `engine/session.js` | 200 | `15aa7a8432f946f79eb0872d78586e94b54b2cdfc043622291053823109d92b0` | `15aa7a8432f946f79eb0872d78586e94b54b2cdfc043622291053823109d92b0` | 33381/33381 | **IDENTICAL** |
| `engine/table-engine.js` | 200 | `d307e85361fdcd1e3a828761b2be35a14333884109235c9c3c86b3981a32d511` | `d307e85361fdcd1e3a828761b2be35a14333884109235c9c3c86b3981a32d511` | 21224/21224 | **IDENTICAL** |
| `estemshan/assets/index-D75ZfzrW.css` | 200 | `71e96b088b018f347e5764b253a989ba6569329e25b72482330bf86d208f1971` | `71e96b088b018f347e5764b253a989ba6569329e25b72482330bf86d208f1971` | 10727/10727 | **IDENTICAL** |
| `estemshan/assets/index-_whnY_xL.js` | 200 | `a276dc2211ec5eb467fbcdbf42204d7ce465cf1094cd122ee2a8a8969e737610` | `a276dc2211ec5eb467fbcdbf42204d7ce465cf1094cd122ee2a8a8969e737610` | 201500/201500 | **IDENTICAL** |
| `estemshan/index.html` | 200 | `ef295b16188266429df281fc3d3949cdbf769bc206e86dd4ee2240e2f9ebcf99` | `ef295b16188266429df281fc3d3949cdbf769bc206e86dd4ee2240e2f9ebcf99` | 493/493 | **IDENTICAL** |
| `firebase-init.js` | 200 | `79c412ba6340ba7af8a178a1a26351dfc67064622848cda40c6b1c5aff839b3a` | `79c412ba6340ba7af8a178a1a26351dfc67064622848cda40c6b1c5aff839b3a` | 1366/1366 | **IDENTICAL** |
| `index.html` | 200 | `b41f85bbd003b8ed5ad3b9a8f8f21de18dddbdad14112c088d8091a34b1fcc00` | `b41f85bbd003b8ed5ad3b9a8f8f21de18dddbdad14112c088d8091a34b1fcc00` | 290/290 | **IDENTICAL** |
| `inventory-service.js` | 200 | `030f4fc55524740e35f8d42a689a80e0064658b9d6d33807355974ed25920433` | `030f4fc55524740e35f8d42a689a80e0064658b9d6d33807355974ed25920433` | 1276/1276 | **IDENTICAL** |
| `leaderboard-service.js` | 200 | `b4269af12969aa3d4d35731de857e55a7c712b4a72943a4821e1af9621d6b873` | `b4269af12969aa3d4d35731de857e55a7c712b4a72943a4821e1af9621d6b873` | 1433/1433 | **IDENTICAL** |
| `lobby/game-state.js` | 200 | `1433ff3b08fb5ce71e8d5afd5ccb86b0fdb3eb8b3878898cfde09d492a7f1791` | `1433ff3b08fb5ce71e8d5afd5ccb86b0fdb3eb8b3878898cfde09d492a7f1791` | 7930/7930 | **IDENTICAL** |
| `lobby/index.html` | 200 | `8883984ebd3b732da4e00c6f56439c0d03714a33c8938e945a818fb26029d2c6` | `8883984ebd3b732da4e00c6f56439c0d03714a33c8938e945a818fb26029d2c6` | 31669/31669 | **IDENTICAL** |
| `lobby/session.js` | 200 | `62018dfa9acb62024e8b110353262da5d18253aa61cef90659f29a854608555a` | `62018dfa9acb62024e8b110353262da5d18253aa61cef90659f29a854608555a` | 22573/22573 | **IDENTICAL** |
| `login/game-state.js` | 200 | `1433ff3b08fb5ce71e8d5afd5ccb86b0fdb3eb8b3878898cfde09d492a7f1791` | `1433ff3b08fb5ce71e8d5afd5ccb86b0fdb3eb8b3878898cfde09d492a7f1791` | 7930/7930 | **IDENTICAL** |
| `login/index.html` | 200 | `1cff541bbb4aade3c2edb80b249203d9fc9a34c3d4bec179f89140c608194f41` | `1cff541bbb4aade3c2edb80b249203d9fc9a34c3d4bec179f89140c608194f41` | 18696/18696 | **IDENTICAL** |
| `login/shared-ui.css` | 200 | `074c77871cdf2330a532dcb223e73e76f0b25f49aabb6211c2ea62b17fe9a9ae` | `074c77871cdf2330a532dcb223e73e76f0b25f49aabb6211c2ea62b17fe9a9ae` | 3414/3414 | **IDENTICAL** |
| `login/shared-ui.js` | 200 | `a83c927fae795827dfcac813aa57267f4e677ed25ad3ae7b316d0c834c0e469a` | `a83c927fae795827dfcac813aa57267f4e677ed25ad3ae7b316d0c834c0e469a` | 2473/2473 | **IDENTICAL** |
| `match-adapter.js` | 200 | `2615c40d74176a560cedef033057449629b589f5c32b63785631ee7aaa7d3109` | `2615c40d74176a560cedef033057449629b589f5c32b63785631ee7aaa7d3109` | 133209/133209 | **IDENTICAL** |
| `match-service.js` | 200 | `ed9314e458bcd16dc44516b8c4589b13883e7a25877f6d543cec607fc68525e3` | `ed9314e458bcd16dc44516b8c4589b13883e7a25877f6d543cec607fc68525e3` | 142186/142186 | **IDENTICAL** |
| `match/game-state.js` | 200 | `1433ff3b08fb5ce71e8d5afd5ccb86b0fdb3eb8b3878898cfde09d492a7f1791` | `1433ff3b08fb5ce71e8d5afd5ccb86b0fdb3eb8b3878898cfde09d492a7f1791` | 7930/7930 | **IDENTICAL** |
| `match/index.html` | 200 | `2cb78b87c79e544eb6f117c1f8cf4b5675f8e263b29f4f512f7e357421ee194b` | `2cb78b87c79e544eb6f117c1f8cf4b5675f8e263b29f4f512f7e357421ee194b` | 129841/129841 | **IDENTICAL** |
| `player-service.js` | 200 | `eeed5633040f70434cc711bb53281a13703698875949f331414406a450a15b07` | `eeed5633040f70434cc711bb53281a13703698875949f331414406a450a15b07` | 6939/6939 | **IDENTICAL** |
| `presence-service.js` | 200 | `9229623e960f42f20f9e12d9de455d70ea775271756d08c807f66d9326b0cc96` | `9229623e960f42f20f9e12d9de455d70ea775271756d08c807f66d9326b0cc96` | 1745/1745 | **IDENTICAL** |
| `profile/game-state.js` | 200 | `1433ff3b08fb5ce71e8d5afd5ccb86b0fdb3eb8b3878898cfde09d492a7f1791` | `1433ff3b08fb5ce71e8d5afd5ccb86b0fdb3eb8b3878898cfde09d492a7f1791` | 7930/7930 | **IDENTICAL** |
| `profile/index.html` | 200 | `1fe9efc7202f7bafbd9d71e6275b87bf6cc0528b6706407f67afc704af38a82a` | `1fe9efc7202f7bafbd9d71e6275b87bf6cc0528b6706407f67afc704af38a82a` | 8050/8050 | **IDENTICAL** |
| `room-service.js` | 200 | `73f370bef5c1ceea3bdab1539137cf23d1c5c631ae085656abd2c2a83a3c0376` | `73f370bef5c1ceea3bdab1539137cf23d1c5c631ae085656abd2c2a83a3c0376` | 16982/16982 | **IDENTICAL** |
| `session-service.js` | 200 | `b780a7c2b29babafbff1a0319e4d68a3c1bba8e58a730f4f16d840903db57bb8` | `b780a7c2b29babafbff1a0319e4d68a3c1bba8e58a730f4f16d840903db57bb8` | 8112/8112 | **IDENTICAL** |
| `shared-i18n.js` | 200 | `7e2a5b51ab44cff793202668af263d9d227098b99956ac7c8c21d2bd911b0717` | `7e2a5b51ab44cff793202668af263d9d227098b99956ac7c8c21d2bd911b0717` | 4151/4151 | **IDENTICAL** |
| `shop-service.js` | 200 | `6ca2600bedbac2d3dc5d1c60432a71bd7b3d889eb59622564470046fbfef7765` | `6ca2600bedbac2d3dc5d1c60432a71bd7b3d889eb59622564470046fbfef7765` | 1467/1467 | **IDENTICAL** |

### A2. Live marker search

The live files contain the same known-bug markers and Sprint L markers as the local build.

| Requested marker | Live location/result |
|---|---|
| `buildHand` | `engine/bidding-engine.js:963` call and `:974` comment; no definition |
| `bindStatic` | `engine/bidding-engine.js:964` call, `engine/table-engine.js:351` call, plus comments in `match/index.html`; no definition |
| `Hand-deal authority` | `match/index.html:2153`, with the dealer-only gate immediately below |
| `dealRequestedForRound` | `match/index.html:1606`, `:2162`, and `:2164` |
| Sprint L opening-window client code | `match-service.js:952–985`, including `publishOpeningTurnIfNeeded()` and `isRoundOneOpeningWindow()`; content is byte-identical to the 4a71383 build |
| Build/version stamp | No repository commit SHA or release stamp found in the design-ui HTML/JS/CSS. The Vite `/estemshan/` bundle contains ordinary framework/version strings but no `4a71383`, `b1e7088`, `6dca0d`, or `eef765` stamp |

### Q-A verdict

> **LIVE-BUNDLE == HEAD-BUILD: YES.**

The current live static bundle is byte-for-byte identical to the deployment-equivalent 4a71383 build for all 34 compared HTML/JS/CSS files. The live bundle therefore contains the same standalone `buildHand`/`bindStatic` bug and the same dealer-only hand-deal gate as the inspected source.

## Q-B — Incident-evening client and Git time line

### B1. Candidate commit at the reported evening release

The reported Hosting label `6dca0d` cannot be resolved as a Git commit in the repository, any reachable ref, reflog, or the GitHub commit API. The API returned `No commit found for SHA: 6dca0d`. It is therefore not possible to prove from repository evidence alone that `6dca0d` is a repository SHA; it may be a Firebase Hosting release identifier or another deployment-system label.

The closest traceable correlation is strong:

| Evidence | UTC time | Identifier |
|---|---:|---|
| H2 deal/sync wiring commit | 12:02:49 | `911640696e1f6cc0239701053b75cd401d108091` |
| Dealer rotation/opening bridge commit | 14:46:48 | `5d28d1c8d06439b9b7ee1ec42d0d1a496761ef0c` |
| Exact target commit | 15:33:34 | `4a7138382af18e81b786166194a0e964a6a7157b` |
| Push-triggered Hosting workflow start | 15:33:39 | Run [`32866550234`](https://github.com/Ehabsayed2794/demo-test/actions/runs/32866550234), head `4a71383` |

The reported incident-evening time of 18:34 in the user’s GMT+3 timezone corresponds to approximately 15:34 UTC. The only repository push and successful Hosting deployment at that moment is therefore `4a71383`. The most plausible incident-evening client commit is:

> **C-EVE: probably `4a71383` — strongly supported by timestamp and successful Hosting-run correlation, but not conclusively mapped to the external `6dca0d` label.**

To convert “probably” into a confirmed deployment pin, the owner must provide the Firebase Console release screen or the exact deployment method that produced the `6dca0d` label.

The currently live artifact has `Last-Modified: Wed, 26 Aug 2026 07:18:57 GMT`, matching the later push/deploy chain headed by `b1e7088`. That later chain changed APK workflow files, not the web bundle, which explains why the live web files still hash exactly to the 4a71383 build.

### B2. Gate comparison and historical variants

For the closest traceable C-EVE candidate, `4a71383`, the H2 gate is unchanged from its introduction in `9116406`. The following is the verbatim gate in both versions; the two blocks are byte-identical in the compared region.

**9116406 / C-EVE candidate gate:**

```javascript
// Hand-deal authority: only the client occupying the authoritative
// dealer seat requests the existing idempotent deal transaction when
// this round has not yet been committed. The transaction/rules remain
// the source of truth; this is bootstrap orchestration only.
var currentRoundForDeal = typeof data.currentRound === "number" ? data.currentRound : null;
var dealtRound = data.gameState && typeof data.gameState.dealtRound === "number" ? data.gameState.dealtRound : 0;
var dealerSeat = window.MatchAdapter && typeof window.MatchAdapter.uidToSeat === "function"
  ? window.MatchAdapter.uidToSeat(data, data.dealer) : null;
if (localSeatId && dealerSeat === localSeatId && currentRoundForDeal != null && dealtRound < currentRoundForDeal &&
    dealRequestedForRound[matchId] !== currentRoundForDeal &&
    window.MatchService && typeof window.MatchService.dealRound === "function") {
  dealRequestedForRound[matchId] = currentRoundForDeal;
  window.MatchService.dealRound(matchId, currentRoundForDeal).catch(function (e) {
    console.error("[Match] dealer dealRound() attempt failed (non-fatal):", e);
  });
}
```

**4a71383 gate:**

```javascript
// Hand-deal authority: only the client occupying the authoritative
// dealer seat requests the existing idempotent deal transaction when
// this round has not yet been committed. The transaction/rules remain
// the source of truth; this is bootstrap orchestration only.
var currentRoundForDeal = typeof data.currentRound === "number" ? data.currentRound : null;
var dealtRound = data.gameState && typeof data.gameState.dealtRound === "number" ? data.gameState.dealtRound : 0;
var dealerSeat = window.MatchAdapter && typeof window.MatchAdapter.uidToSeat === "function"
  ? window.MatchAdapter.uidToSeat(data, data.dealer) : null;
if (localSeatId && dealerSeat === localSeatId && currentRoundForDeal != null && dealtRound < currentRoundForDeal &&
    dealRequestedForRound[matchId] !== currentRoundForDeal &&
    window.MatchService && typeof window.MatchService.dealRound === "function") {
  dealRequestedForRound[matchId] = currentRoundForDeal;
  window.MatchService.dealRound(matchId, currentRoundForDeal).catch(function (e) {
    console.error("[Match] dealer dealRound() attempt failed (non-fatal):", e);
  });
}
```

The gate was introduced by `9116406` together with the hand-deal and sync wiring. The immediate earlier reachable commit `7795fe1` has **no `dealRound` orchestration at all** in `design-ui/match/index.html`; it does not contain a non-dealer variant. Therefore the reachable history shows:

| Historical interval | Match-page behavior |
|---|---|
| Before `9116406` | No automatic `dealRound()` call in the match page |
| `9116406` through `4a71383` | Only the client whose resolved local seat equals the resolved dealer seat can automatically call `dealRound()`; one per round per page instance via `dealRequestedForRound` |

There is one important boundary distinction. `MatchService.dealRound()` itself checks that the caller is a player, but its service-level precondition is not the same as the match-page dealer gate. A script or other caller that invokes the public service API directly can reach the function as any seated player; the standard match-page orchestrator does not do so for non-dealers. The transaction is idempotent once `gameState.dealtRound >= roundNumber`, and concurrent service calls are designed to converge rather than create a second hand set.

**B2 verdict:** For the traceable C-EVE candidate, **gate-changed-since-C-EVE: NO**. Exact external-label mapping remains unresolved. No reachable match-page variant was found that automatically let a non-dealer or multiple clients call `dealRound()`; the pre-gate version had no automatic call at all.

### B3. Symbol history

The first reachable commit containing either string is the imported engine commit:

| Symbol | First reachable string appearance | Later history | Definition ever found? |
|---|---|---|---|
| `buildHand` | `bbb1bb0`, 2026-08-04 07:59:34 UTC, “Import and fix Estimation’s vanilla-JS rules engine” | Mentioned again in `fb10322`, 2026-08-05 13:13:18 UTC, “Sprint 3.6: Match Flow Integration” | **No** |
| `bindStatic` | `bbb1bb0`, 2026-08-04 07:59:34 UTC | Mentioned again in `fb10322` and comments in `af39c94` | **No** |

`git log -S` found only those introduction/propagation events for `buildHand` and the corresponding early events for `bindStatic`. A definition-pattern search across all reachable history found no declaration or assignment for either symbol. The Sprint 3.6 patch explicitly says the UI hooks remain undefined and that integration tests avoid the DOMContentLoaded path.

### B4. Runtime trigger map for `buildHand()`

The exact call chain is short and unconditional:

```text
/match/index.html loads ../engine/bidding-engine.js
  → browser fires DOMContentLoaded
    → bidding-engine.js anonymous DOMContentLoaded listener
      → GameState.sync(GameState.STATES.BIDDING)
      → initState()
        → GameSession.ensureHandsDealt()
           → local mode: local dealNewHands() fallback if no cached deal
           → firestore mode: return cached authoritative hands, possibly {}
      → buildHand()   ← undefined ReferenceError
      → bindStatic()  ← also undefined, if execution reaches it
      → advance()
```

The exact target code is:

```javascript
window.addEventListener("DOMContentLoaded", () => {
  GameState.sync(GameState.STATES.BIDDING);
  initState();
  buildHand();
  bindStatic();
  advance();
});
```

The `table-engine.js` file has an independent DOMContentLoaded listener:

```javascript
window.addEventListener("DOMContentLoaded", () => {
  GameState.sync(GameState.STATES.GAMEPLAY);
  initState();
  bindStatic();
  advance();
});
```

The user-visible state that reaches these calls is the real `/match/index.html` match screen after its static scripts have loaded and the document reaches DOMContentLoaded. The call is not guarded by a successful match snapshot, a dealer-seat check, a completed hand sync, or an authoritative-sync error. It can occur before the match page’s asynchronous Firestore callback has delivered the authoritative hand. `GameSession.ensureHandsDealt()` can therefore use the local fallback if the page is still in its default local authority mode; in Firestore mode it deliberately returns the currently cached authoritative hands without local redealing. Either way, the subsequent `buildHand()` call is unconditional.

This mechanically explains how p4-exclusive `buildHand`/`bindStatic` errors can occur without any stale client. A client that reaches the DOMContentLoaded handler on the match page can throw the same error in the current live bundle. A timing-dependent local fallback can additionally explain why one client might have a private shuffled hand while the authoritative hand path is still pending, but that is a current runtime-ordering explanation, not proof of a divergent cached bundle.

**B4 verdict:** The “only reachable on the LOCAL-FALLBACK path after authoritative-sync failure” hypothesis is **false**. The undefined calls are reachable on the normal DOMContentLoaded path; local fallback is possible but not required.

## R4 — Cache mechanics

### Observed response metadata

The live root and same-origin application HTML/JS/CSS responses returned `Cache-Control: max-age=3600`. The entrypoint HTML responses also returned strong-looking ETags and a common `Last-Modified` timestamp for the current deployment:

| Resource class | Observed cache behavior |
|---|---|
| Root and same-origin HTML/JS/CSS | `max-age=3600` |
| Firebase gstatic JS | `public, max-age=31536000` |
| Google Fonts CSS | `private, max-age=86400` |
| Current entrypoint `Last-Modified` | `Wed, 26 Aug 2026 07:18:57 GMT` |
| Service worker registration | None found in non-minified app files |
| Workbox/precache manifest | None found |
| Content-hashed design-ui filenames | None; design-ui asset names are stable paths |

The root `index.html` is a tiny redirect shell that sends the browser to `/login/index.html` through both a meta refresh and `location.replace()`. The login page and match page then load stable, same-origin JavaScript filenames such as `game-state.js`, `match-service.js`, and `engine/bidding-engine.js`.

No service-worker file, Workbox registration, precache list, `cacheName`, or `skipWaiting` marker was found in the non-bundled live or local files. The Vite score-tracker bundle contains two incidental `serviceWorker` strings from framework code but no Workbox/precache registration. The absence of a service worker means there is no evidence that an application-managed offline cache replayed a stale bundle.

### Mechanical stale-cache path

A browser or intermediary that received the HTML or a stable-name JavaScript asset within its one-hour freshness window could reuse that response without contacting Hosting again. Because the design-ui JavaScript filenames are not content-hashed, a cached `/match/index.html` or cached `/engine/bidding-engine.js` can remain internally consistent but older than the current Hosting release. A partially refreshed client could also combine a newer HTML document with an older stable-name script, depending on which individual response was cached.

This makes stale-client behavior mechanically possible in general. However, the current live bundle and the exact 4a71383 build are identical, and the current source itself contains the unresolved UI-hook calls. Cache mechanics therefore do not explain the `buildHand`/`bindStatic` errors better than the confirmed source bug and the unconditional DOMContentLoaded path.

### Proposal only; not implemented

A user-facing remediation could combine content-hashed filenames or query-string cache busting for stable-name design-ui assets, an embedded build/release stamp rendered in the UI, a startup version-mismatch toast, and hard-refresh guidance for users who are mid-session during a deployment. These are proposals only; no fix was made in this lane.

## Final verdicts

| Question | Verdict | Confidence | Basis |
|---|---|---:|---|
| **Q-A: live bundle equals the 4a71383 deployment-equivalent build?** | **YES** | 100% | 34/34 HTML/JS/CSS files returned HTTP 200 and matched byte-for-byte |
| **Q-B1: exact external `6dca0d` label mapped to a Git commit?** | **NO / UNRESOLVED** | 100% | Not present in local history, refs, reflogs, object database, or GitHub commit API |
| **Q-B1: most plausible C-EVE?** | **4a71383** | 85% | Commit and successful Hosting run align within seconds of the reported 18:34 GMT+3 time |
| **Q-B2: gate changed after the traceable C-EVE candidate?** | **NO** | 95% | `9116406` introduced the dealer-only gate; the gate is unchanged through 4a71383 |
| **Q-B2: reachable match-page variant let non-dealers automatically call `dealRound()`?** | **NO** | 95% | Pre-gate version had no automatic call; post-gate version requires dealer-seat equality |
| **Q-B3: did definitions ever exist?** | **NO** | 100% | No definition-like match in reachable history; only bare calls/comments appear |
| **Q-B4: is `buildHand()` only reachable after authoritative-sync failure/local fallback?** | **NO** | 98% | Normal DOMContentLoaded handler calls it unconditionally |
| **C-EVE client logic explains p4’s attempt** | **POSSIBLE BUT UNPROVEN** | 25% | External C-EVE label is unmapped, and the traceable candidate already has the dealer-only gate; the current unconditional engine path is a stronger explanation |
| **General stale-cache behavior is mechanically possible?** | **YES** | 90% | Stable filenames and one-hour freshness windows permit stale individual responses, but no service-worker evidence exists |

The 25% figure is an evidence-weighted judgment, not a measured frequency. It reflects the unresolved external deployment label and the absence of any reachable match-page gate variant that would explain a non-dealer automatic deal attempt. The current-bundle DOMContentLoaded/local-authority timing path has substantially stronger direct code support.

## Required final one-liner

> **p4 stale-bundle theory: REFUTED for the `buildHand`/`bindStatic` and automatic-deal explanation — confidence 90%; Q-A independently proves LIVE-BUNDLE == HEAD-BUILD, while general HTTP-cache staleness remains mechanically possible but is not needed to explain the observed p4 errors.**

## Owner follow-up required for complete C-EVE pin

Please provide the Firebase Console Hosting release screen or the exact deployment method that generated the external label `6dca0d`. Repository and GitHub evidence strongly identify `4a71383` as the client deployed at approximately 18:34 GMT+3, but the `6dca0d` label itself cannot be reconciled to a Git commit from the available repository evidence.

## References

[1]: https://made---estimation-card-game.web.app/ "Live Estemshan Hosting root"
[2]: https://github.com/Ehabsayed2794/demo-test/commit/4a7138382af18e81b786166194a0e964a6a7157b "Commit 4a71383 — round-one opening-window service fix"
[3]: https://github.com/Ehabsayed2794/demo-test/actions/runs/32866550234 "Hosting workflow run for 4a71383"


# B3 — Deal-Attempt Call-Path Audit: How Could p4 Attempt at All?

## Scope and evidence boundary

This section is a read-only continuation of the accepted B2 report. No production deployment, Hosting command, Rules edit, test edit, application fix, or non-report repository mutation was performed. The exact p4 timestamps and fingerprints below are treated as the supplied golden-path evidence: the preserved local sandbox did not contain a file with literal timestamp strings `06:14:36.900Z` and `06:14:39.154Z`, so those two timestamps are not independently re-extracted here.

## A1 — Every `MatchService.dealRound()` caller

A repository-wide search of `design-ui/` found two executable callers. The `dealRound` definition in `match-service.js:1802` is not itself a caller. Comments mentioning `dealRound()` are excluded from the caller count.

| Caller | Enclosing function/context | Exact guards | Classification |
|---|---|---|---|
| `design-ui/match/index.html:2165` | The `MatchService.subscribeToMatch(matchId, callback)` snapshot callback, whose local seat is resolved at `:2150` | `localSeatId` exists; `dealerSeat === localSeatId`; `currentRoundForDeal != null`; `dealtRound < currentRoundForDeal`; `dealRequestedForRound[matchId] !== currentRoundForDeal`; `MatchService.dealRound` exists | **GATED-BY-DEALER-SEAT** |
| `design-ui/match-adapter.js:2085` | `maybeDealRound(matchId, matchDoc)`, invoked from the `startHandSync()` match subscription callback at `:2138` | `MatchService.dealRound` exists; `matchDoc.currentRound` is numeric; `dealtRound < currentRound`; `dealAttemptedByMatch[matchId] !== currentRound` | **UNGATED-BY-DEALER-SEAT** |

The second path is the missing door. It has a per-client/per-match/per-round attempt guard, but it does not resolve the caller’s seat, compare it to `matchDoc.dealer`, or consult the page’s `dealRequestedForRound` guard. Its own source comment explicitly describes the intended shape as “any client may attempt it, the transaction makes it safe.”

The exact adapter path is:

```text
match page calls MatchAdapter.startHandSync(matchId, mySeatId)
  → startHandSync() sets GameSession hand authority to "firestore"
  → startHandSync() subscribes to the shared match document
  → each eligible snapshot invokes maybeDealRound(matchId, data)
  → maybeDealRound() sees dealtRound < currentRound
  → dealAttemptedByMatch permits this client’s first attempt for the round
  → global.MatchService.dealRound(matchId, currentRound)
```

## A2 — `GameSession.ensureHandsDealt()` and authority transition

The function is reproduced verbatim from `design-ui/engine/session.js:482–502`:

```javascript
  /** The single funnel every screen should call instead of deciding for
   *  itself whether to reshuffle: deals fresh only if this round has no
   *  valid deal yet, otherwise reuses the existing session hands.
   *  Pass {force:true} for an explicit restart/reset action that must
   *  redeal regardless. */
  function ensureHandsDealt(opts) {
    opts = opts || {};
    // Player Hand Synchronization sprint: in "firestore" mode this
    // function must NEVER fall back to a local Math.random() deal when
    // the authoritative hand hasn't arrived yet — it waits (returns
    // whatever is already cached, which may be `{}` before the first
    // setAuthoritativeHand() call lands). `opts.force` has no meaning
    // here either: forcing a REAL redeal is a server-transaction
    // decision (MatchService.dealRound()), never something a single
    // client can do unilaterally once Firestore is the authority.
    if (handAuthorityMode === "firestore") {
      return session.hands;
    }
    if (opts.force || !hasDealtHands()) return dealNewHands();
    return session.hands;
  }
```

In `firestore` mode, a missing local hand does **not** call `dealRound()` directly or transitively. It returns the current `session.hands`, which can be `{}` before `setAuthoritativeHand()` receives the protected hand document. The service transaction is reached separately by `MatchAdapter.startHandSync()`’s match-document watcher, not through `ensureHandsDealt()`.

A client can nevertheless deal locally while its authority mode is still transitioning. The mode flag is initially local and is set to Firestore only by `startHandSync()` at `design-ui/match-adapter.js:2133–2135`; the setter itself at `design-ui/engine/session.js:263–265` only assigns the mode. If `bidding-engine.js`’s DOMContentLoaded handler runs first, `initState()` calls `ensureHandsDealt()` while the mode is still local, and `dealNewHands()` at `session.js:241–245` stores a private `Dealer.dealHands()` result in `session.hands`.

When `startHandSync()` later changes the mode to Firestore, it does not clear that existing local hand. `applyRemoteHand()` replaces only the client’s own seat when an authoritative document arrives, through `design-ui/match-adapter.js:2108–2110` and `GameSession.setAuthoritativeHand()` at `session.js:277–281`. Until that callback arrives, a local private shuffle can remain in the page cache and be displayed.

## A3 — Scope of `dealRequestedForRound`

The page-scoped guard is declared only at `design-ui/match/index.html:1606`:

```javascript
var dealRequestedForRound = {};
```

Its executable reads/writes occur only at `match/index.html:2162` and `:2164`, inside the dealer-gated match-page callback. A repository-wide search found no reference to this variable in `match-service.js` or `match-adapter.js`.

The adapter service watcher uses a different guard, declared at `match-adapter.js:2063`:

```javascript
var dealAttemptedByMatch = {};
```

and checked/set at `match-adapter.js:2083–2084`. Therefore, yes: `dealRequestedForRound` lives only in the match-page scope, and the adapter’s service-layer call path bypasses it entirely.

## A4 — Mechanical reconstruction of p4’s minute

The evidence is consistent with two independent page-level effects and one adapter-level write path.

First, p4’s match page loads both engine scripts. On DOMContentLoaded, `bidding-engine.js` executes `GameState.sync()`, `initState()`, `buildHand()`, `bindStatic()`, and `advance()` at `design-ui/engine/bidding-engine.js:960–965`. The missing `buildHand`/`bindStatic` functions throw the confirmed PAGE_ERRORs. Independently, if the page’s authority flag has not yet been switched, `initState()` has already called `GameSession.ensureHandsDealt()` in local mode and `dealNewHands()` has cached a private shuffle.

Second, the page’s asynchronous match bootstrap later starts hand synchronization. `startHandSync()` sets the mode to Firestore and subscribes to both the match document and p4’s own protected hand document. The mode transition does not erase the previously cached local hand. The first authoritative hand callback would replace p4’s own seat, but until that callback arrives the cache still contains the private fallback.

Third, the adapter’s match subscription independently sees `gameState.dealtRound < currentRound` and invokes `maybeDealRound()`. Unlike the page-level `match/index.html:2161` gate, `maybeDealRound()` at `match-adapter.js:2077–2085` does not require the local seat to equal the dealer. It therefore permits p4 to call `MatchService.dealRound(matchId, currentRound)`.

`MatchService.dealRound()` at `match-service.js:1802–1873` opens a transaction, reads the match document, verifies only that the authenticated UID is in `match.players` at `:1819–1821`, generates hands for every active seat at `:1831–1845`, writes all four `matches/{matchId}/hands/{seatId}` documents at `:1847–1864`, and updates `gameState` with `dealtRound` at `:1866–1869`. This is the full four-hand-plus-gameState Commit observed in the supplied evidence.

The resulting sequence is therefore:

```text
p4 page loads match bundle
  → DOMContentLoaded engine path throws buildHand/bindStatic errors
  → before Firestore hand mode is established, ensureHandsDealt() may cache p4’s private local shuffle
  → startHandSync() later enables Firestore mode but does not clear the cache
  → adapter match watcher sees dealtRound < currentRound
  → adapter’s ungated maybeDealRound() passes its own one-attempt guard
  → p4 calls MatchService.dealRound()
  → tx.get() succeeds at the supplied 06:14:36.900Z evidence point
  → p1’s competing full hand commit has already established different fingerprints
  → p4’s four-hand/gameState Commit is denied at the supplied 06:14:39.154Z evidence point
```

This path explains how p4 could attempt at all without traversing the deterministic dealer-only gate in `match/index.html`. The page gate is not contradicted; it was simply not the caller used by p4. The existing adapter comment already acknowledges that any client may attempt the transaction, and the source confirms that this is executable behavior, not merely documentation.

## A5 — Renderer source after the denial

The displayed hand is rendered by `design-ui/match/index.html:1901–1923`. Its source line is:

```javascript
var hand = (window.GameSession && typeof window.GameSession.getHand === "function") ? window.GameSession.getHand(localSeatId) : [];
```

The renderer does not read the Firestore hand document directly and has no separate “local versus authoritative” rendering branch. It draws whatever is currently in the `GameSession` cache. In the incident sequence, the displayed fingerprint-different p4 hand was therefore the cached private local shuffle unless and until `applyRemoteHand()` received p4’s authoritative hand document and replaced that seat in the cache.

This is the precise source distinction:

| Stage | Cache population | Renderer source |
|---|---|---|
| Before authoritative p4 hand callback | `ensureHandsDealt()` → `dealNewHands()` → local `session.hands[p4]` | `renderHand()` reads local cached shuffle |
| After authoritative p4 hand callback | `applyRemoteHand()` → `setAuthoritativeHand(p4, ...)` | The same `renderHand()` reads the now-authoritative cached hand |

Thus the answer to “which renderer?” is **the normal `renderHand()` renderer reading `GameSession.getHand()`; in the observed post-denial state, its data source was the local cache, not a direct authoritative-document read**. If a later hand snapshot arrived, the same renderer could subsequently display the authoritative p4 hand without any renderer-code change.

## B3 final verdicts

| Required verdict | Result |
|---|---|
| **(1) Ungated deal path exists** | **YES — `design-ui/match-adapter.js:2077–2085`, invoked at `:2138`; it calls `global.MatchService.dealRound()` without a dealer-seat guard.** |
| `dealRequestedForRound` page guard bypassed? | **YES — it exists only at `match/index.html:1606, 2162, 2164`; adapter uses independent `dealAttemptedByMatch` at `match-adapter.js:2063, 2083–2084`.** |
| `ensureHandsDealt()` reaches `dealRound()` in Firestore mode? | **NO.** It returns cached `session.hands`; the separate adapter watcher reaches `dealRound()`. |
| Can transitioning authority mode retain/display a local shuffle? | **YES.** Local dealing can occur before `setHandAuthorityMode("firestore")`; the setter does not clear `session.hands`, and `renderHand()` reads that cache. |
| Which renderer drew p4’s displayed hand? | **`match/index.html:1901–1923` `renderHand()`, reading `GameSession.getHand(localSeatId)` at `:1906`; in the observed state, the cache was local.** |

### Updated incident probability

> **Double-deal race via the ungated adapter path = the incident: 90% confidence.**

This is now the leading explanation because it accounts for all supplied observations in one call path: p4’s attempt bypasses the page dealer gate; the adapter guard allows one attempt per client; `dealRound()` creates the observed all-hand/gameState Commit; the p4 transaction can read the match before the competing p1 commit is reflected in its own transaction; and p4’s private local hand remains displayable until authoritative hand application replaces it.

The remaining 10% reflects the evidence-boundary limitation that the exact timestamped Firestore request records were supplied by the task rather than re-extracted from a preserved local JSONL file in this sandbox, plus the possibility of another external caller outside `design-ui/` (none was found in the requested repository scope).

## Required final one-liner

> **UNGATED deal path exists: YES — `design-ui/match-adapter.js:2077–2085` (invoked by `startHandSync()` at `:2138`); double-deal race via that path = 90% confidence that it is the incident.**
