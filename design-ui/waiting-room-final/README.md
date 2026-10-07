# Estemshan Waiting Room — Design Handoff

Finalized landscape-first Waiting Room design package for the Estemshan card game. This is a **design asset handoff only**; it contains no application implementation.

## Figma
- [Editable Figma design — Waiting Room Final page](https://www.figma.com/design/c7aXTzOHSKpshL0YZZaClf/Estemshan-%E2%80%94-Royal-Cairo-Lobby?node-id=2037-3)
- [Interactive prototype — Flow 1, starting at Host-only](https://www.figma.com/proto/c7aXTzOHSKpshL0YZZaClf/Estemshan-%E2%80%94-Royal-Cairo-Lobby?node-id=2040-140&scaling=min-zoom&content-scaling=fixed&page-id=2037%3A3&starting-point-node-id=2040%3A140&show-proto-sidebar=1)

## Package contents
- `waiting_room_final/` — seven vector-first SVG states, matching PNG previews, a contact sheet, and the state-set README.
- `generate_waiting_room.py` — reproducible SVG state generator.
- `DESIGN_NOTES.md` — visual system, frames, final prototype flow, and known Figma limitation.
- `WAITING_ROOM_SOURCE_NOTES.md` — repository-derived room rules and seating requirements.
- `WAITING_ROOM_FIGMA_IMPORT.md` — Figma page/prototype handoff and verified interaction details.

Run `python3 generate_waiting_room.py` from this folder to regenerate the seven SVGs and state-set README. Existing PNG previews and the contact sheet are included as review assets.

The prototype uses click-through snapshots to simulate occupancy changes because Figma cannot receive live multiplayer presence or readiness events. It contains no host Start action; the all-ready state starts automatically. No HTML or app source is included.
