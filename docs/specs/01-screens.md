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
| GameModeSelection | — (null) | MISSING | DEFERRED except Friends/Room entry (no AI, no ranked queue) |
| CreateRoom / JoinRoom / WaitingRoom | — (`Estimation Room.html`, never imported) | MISSING | IN as one Room screen (create + join-by-code + waiting) |
| Matchmaking (ranked) | — (`Estimation Ranked Match.html`) | MISSING | DEFERRED (ranked needs trust hardening first) |
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
