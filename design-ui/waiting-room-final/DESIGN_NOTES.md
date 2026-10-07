# Estemshan Waiting Room — Design Notes

## Intent and canvas
A landscape-first native Android room screen using the **Card Club Table** concept: four people seated around a private table in an elegant old Cairo club. The Figma page is named `Waiting Room Final`. The vector artboards are 970 × 450; the intended Android content viewport is approximately 932 × 430 dp.

## Visual language
Maintain continuity with the Royal Cairo Lobby through charcoal surfaces, warm gold/brass accents, ivory copy, restrained borders, and a serif/sans/monospace type hierarchy.

| Role | Color |
|---|---|
| Background | `#0D0A07` |
| Panel | `#17130E` |
| Raised surface | `#1D1912` |
| Gold | `#E8A33D` |
| Dim brass | `#A8742A` |
| Ivory | `#F0EADA` |
| Muted text | `#A89F8E` |

Typography approximates Marcellus/Georgia for display, Saira/Arial for body, and Spline Sans Mono/monospace for numeric labels and controls. The central tabletop uses warm wood/leather shading and a brass edge; no green felt or new palette is introduced.

## Seating and states
The exact seat arrangement is **YOU bottom, P2 left, P3 top, P4 right**. Empty positions are drawn as chairs, not empty player cards. Capacity is four; open chairs are visual capacity, not room members. The seven included states are Host-only 1/4, 2/4, 3/4, 4/4, You-ready (3/4 ready), All-ready / Starting the match, and Match entry.

The room uses the source-valid sample code `X7K2PQ`. Ready/Cancel Ready is the current player's toggle. Match start is conditioned on all currently seated players being ready; there is no host Start button.

## Prototype and limits
Flow 1 starts at Host-only and was verified through Match entry. Click-through snapshots simulate joining and readiness. The You-ready state advances automatically after 800 ms to `Starting match…`, simulating the final remote player becoming ready. Starting match then navigates automatically to Match entry after an 800 ms delay with a 300 ms Dissolve—approximately 1.1 seconds total.

Figma is visual-only here and cannot subscribe to backend presence/readiness. A redundant imported `05 · 4/4 Ready` duplicate with inconsistent summary copy is hidden and unlinked; Flow 1 uses the accurate all-ready `06 · Starting match` screen instead. See `WAITING_ROOM_SOURCE_NOTES.md` for the repository-derived behavior and `WAITING_ROOM_FIGMA_IMPORT.md` for the Figma handoff.
