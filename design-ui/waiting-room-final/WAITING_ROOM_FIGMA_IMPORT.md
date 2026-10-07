# Waiting Room — Final Figma Handoff

## Figma links
- **Editable design file / Waiting Room Final page:** https://www.figma.com/design/c7aXTzOHSKpshL0YZZaClf/Estemshan-%E2%80%94-Royal-Cairo-Lobby?node-id=2037-3
- **Interactive Flow 1 prototype:** https://www.figma.com/proto/c7aXTzOHSKpshL0YZZaClf/Estemshan-%E2%80%94-Royal-Cairo-Lobby?node-id=2040-140&scaling=min-zoom&content-scaling=fixed&page-id=2037%3A3&starting-point-node-id=2040%3A140&show-proto-sidebar=1

## Completed prototype flow
`Host-only 1/4 → 2/4 → 3/4 → 4/4 → You ready → Starting match → Match entry`

- Flow 1 starts at Host-only 1/4.
- Occupancy states advance by the Ready Up click-through action to simulate joining and readiness snapshots.
- You ready automatically advances after **800 ms** to `06 · Starting match`, where all four seated players are shown ready and the copy reads “Starting match…”. This simulates the final remote player becoming ready; no host Start control is introduced.
- `06 · Starting match` automatically navigates to `07 · Match entry` after an **800 ms delay** with a **300 ms Dissolve** (approximately 1.1 seconds total). This was verified in the prototype preview.
- A redundant imported `05 · 4/4 Ready` duplicate is hidden and unlinked because its summary copy was inconsistent. Flow 1 uses the accurate all-ready Starting match frame instead.

## Product and Figma limitations
- This is a **visual Figma prototype**, not a live multiplayer integration. Figma cannot receive backend player-presence or readiness events; the occupancy steps and the last-player-ready event are simulated.
- Real app behavior remains governed by `WAITING_ROOM_SOURCE_NOTES.md`: four players maximum, YOU bottom / P2 left / P3 top / P4 right, personal Ready/Cancel Ready toggle, and automatic start only when all currently seated players are ready.
- The SVG assets are editable after import and are also included with `generate_waiting_room.py` in the project bundle. No HTML preview was created or modified for this waiting-room task.
