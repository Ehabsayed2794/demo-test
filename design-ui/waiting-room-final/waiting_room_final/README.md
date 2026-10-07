# Estemshan Waiting Room — Refined SVG States

These standalone 970 × 450 SVGs are vector-first, editable after Figma import, and designed for a landscape Android screen. They preserve the charcoal/gold/ivory palette and Marcellus/Saira/monospace hierarchy, use the required seat positions (P2 left, P3 top, P4 right, YOU bottom), depict open seats as chair silhouettes, and contain no host Start button.

Files: host-only 1/4, 2/4, 3/4, 4/4, you-ready (3/4 ready), all-ready/Starting the match, and match entry. Room code is the source-valid sample `X7K2PQ`.

In the verified Figma Flow 1, click-through snapshots simulate occupancy changes. You ready auto-advances after 800 ms to the all-ready Starting match state. Starting match then auto-advances to Match entry after an 800 ms delay with a 300 ms Dissolve (about 1.1 s total). Figma is visual-only here; it does not connect to live multiplayer presence or backend readiness.

Source-derived behavior is documented in the parent project’s `WAITING_ROOM_SOURCE_NOTES.md`.
