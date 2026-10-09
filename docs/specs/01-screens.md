# Phase 0 spec — Screen inventory (native v1 scope)

Source of truth: `design-ui/*/game-state.js` (4 byte-identical copies —
same SHA-256 `5152F7C8…7069`; treat as ONE state machine) `STATES` +
`STATE_SCREEN` + `TRANSITIONS`, plus `src/App.tsx` (`setup`/`game`/`over`).
Nothing below re-derives behavior — it only records what exists, what
is referenced-but-missing, and what native v1 builds.

## 1. design-ui states → files → native verdict

| State | Web file today | Status | Native v1 |
|---|---|---|---|
| Splash | — (`MADE - Logo & Loading.html`, never imported) | MISSING | IN (app launch + Firebase init) |
| Login | `design-ui/login/index.html` (real Auth) | EXISTS | IN |
| Lobby | `design-ui/lobby/index.html` (rooms/ready; `alert`/`prompt` placeholders) | EXISTS | IN (native dialogs, no `prompt()`) |
| GameModeSelection | — (null) | MISSING | **IN — three match types + the invite-only Room (2026-10-06)**: Ranked (solo Auto-Match), **Rank-down** (Ranked + private flag), **Unranked** (rank-blind, `mode: UNRANKED`); the Room remains the separate code-shared invite-only path. See `docs/UI_UX_ROADMAP.md` S36 and `docs/rules/CANONICAL_RULES.md` A3 |
| CreateRoom / JoinRoom / WaitingRoom | — (`Estimation Room.html`, never imported) | MISSING | IN as one Room screen (create + join-by-code + waiting) |
| Matchmaking (ranked) | — (`Estimation Ranked Match.html`) | MISSING | IN as of 2026-10-05 (RD25 — Ranked is MVP and launch-blocking): solo pool per RD9, **plus the shared rank-blind Unranked pool** (RD29/RD30 amendment) |
| Bidding | — (`Estimation Bidding Phase.html`) | MISSING | IN |
| Gameplay (+ RoundFinished) | `design-ui/match/index.html` (sync real, visuals placeholder) | EXISTS | IN (full native render) |
| FinalStandings | — (`Estimation Final Standings.html`) | MISSING | IN (read-only scores/winners) |
| Shop / Missions / Season | — (stub service + static mocks) | MISSING | DEFERRED (post-core program) |
| Profile | `design-ui/profile/index.html` (narrow scope, real wiring) | EXISTS (table says null — drift, file wins) | IN (narrow: identity + stats) |
| Settings | — (`Estimation Settings.html`) | MISSING | IN (minimal: account, language, sound, logout) |
| Disconnected / Loading | — (null) | MISSING | IN (reconnect + loading states are P1-grade UX) |

Transition table (`TRANSITIONS` in the same file) carries over unchanged;
any state NOT in native v1 is unreachable until its deferred phase —
no dead buttons (the web Lobby's dead links to missing screens must
NOT be reproduced).

## 2. Legacy `src/` screens (reference only — NOT ported)

`setup` (create game: names + Normal/Classic mode) → `game` (round
entry + scoreboard) → `over` (final at 18 rounds + winner). Game-over
rule: `completedRounds.length >= 18` (`src/App.tsx`). These screens
document the tracker being superseded; native v1 has no manual
score-entry mode. Scoring math (not layout) is already spec'd in
`src/utils.ts` + `docs/rules/CANONICAL_RULES.md` Amendment A1.

## 3. Native v1 screen list (9)

Splash, Login, Lobby, Room, Bidding, Table (Gameplay), FinalStandings,
Profile, Settings — plus Disconnected/Loading as states, not destinations.

> **2026-10-06 amendment note.** Ranked is no longer deferred (RD25, 2026-10-05)
> and the 2026-10-06 decision set adds the **Unranked** match type as a
> first-class `mode: UNRANKED` — so the "9 screens" list above is the screen
> *inventory*, while S36 Create Game presents **three match types** (Ranked /
> Rank-down / Unranked) alongside the invite-only Room. None of these are new
> screens: they are states of S36 and S08, amended additively per
> `docs/UI_UX_ROADMAP.md` §4b.4b. The authoritative mode model is now
> `ROOM | RANKED | UNRANKED` (RD28 as amended); **Unranked is a mode value, not
> a Room plus a marker**, and it gates voice off, Vote Kick off, disconnect/pause
> on, and settlement plus all nine career statistics off.
