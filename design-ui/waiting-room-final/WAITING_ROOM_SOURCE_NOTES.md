# Estemshan Waiting Room — Source-of-Truth Notes

## Sources reviewed
- Repository: https://github.com/Ehabsayed2794/demo-test
- Native UI: `native/app/src/main/java/com/estemshan/game/ui/room/RoomScreen.kt` and `RoomViewModel.kt`
- Room service: `design-ui/room-service.js`
- Native plan: `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md`
- Canonical rules: `docs/rules/CANONICAL_RULES.md`
- Ready-state notes: `docs/implementation/ReadyStateFoundation.md`
- Visual source: `design-ui/lobby/index.html`, `design-ui/match/index.html`, `design-ui/SHARED_COMPONENTS.md`
- Native theme: `native/app/src/main/java/com/estemshan/game/ui/theme/EstemshanTheme.kt`

## Verified product behavior
- A room is invite-only and supports at most four players; the room service's `players` array contains only players who actually joined. The design may depict three reserved empty chairs because the brief asks for four seats, but those chairs are visual capacity—not invented members.
- Room codes are six-character uppercase alphanumeric codes from an ambiguity-reduced alphabet. Use `X7K2PQ` as the sample code; do not use the brief's four-digit illustrative sample `4821` as if it were the real code format.
- `creator` identifies the host. `readyPlayers` is the source for Ready/Not ready indicators. A player can change only their own ready state.
- There is no host START action. All currently seated players ready is the match-start condition; `RoomScreen` shows “Starting the match…” when `allReady` is true. Keep Ready Up / Cancel Ready as the same personal toggle.
- The Android room screen polls on a 4,000 ms cadence. `subscribeToRoom()` is explicitly unimplemented, so do not imply live presence or always-on synchrony beyond the described states.
- Leaving removes the player from both `players` and `readyPlayers`. If the host leaves, ownership transfers to the next remaining player in array order; an empty room closes. The app returns to the lobby.
- Share/Copy should communicate the existing room code; there is no separate invite API in `RoomService`.
- Voice decisions in the plan specify push-to-talk only and muted by default; voice is full-version / not MVP and is not implemented in the current room UI. Because voice is optional in the brief, omit a microphone control in this concept rather than imply it is currently available.

## Visual tokens to preserve
- Background `#0D0A07`, surface `#17130E`, high surface `#1D1912`
- Gold `#E8A33D`, dim brass `#A8742A`, warm ivory `#F0EADA`, muted ink `#A89F8E`
- Web hierarchy maps Marcellus-like serif display, Saira-like sans body, and Spline Sans Mono numbers; Android uses system serif/sans/monospace approximations.
- Existing match shell already uses the same dark/gold token family and 9–14 px small control radii. The screen's table material can be warm wood/leather with brass edge; do not introduce green felt or a new palette.

## Layout decisions
- Use the user's explicit positions: YOU bottom, PLAYER 2 left, PLAYER 3 top, PLAYER 4 right. This is a visual arrangement for the Waiting Room and does not assign any backend seat index or change rules.
- Use initial-based avatars, not portraits. Keep empty positions as reserved chair silhouettes with Invite/Share Code affordance.
- Create landscape Android frames around 970 × 450 to match the existing Figma screen family; respect a clear inset/safe area.
- Prototype state changes are visual demonstrations of room occupancy/readiness; the only product actions are leave, copy/share code, and the player's own ready toggle. The all-ready handoff is a 1–2 second visual transition with “Starting match…” and no cancel countdown.
