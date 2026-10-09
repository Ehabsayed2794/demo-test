# Estimation Final Standings

Open `Estimation Final Standings.html` with its sibling support files. The React
preview embeds this exact export; there is no divergent preview implementation.

All preview ranks, RP awards, season dates, match scores, and statistics are
bound samples. The segmented control cycles the annotated win/loss fixtures.
The Mini fixture uses the same renderer; only its RP award differs.

Production integration:

- Set `window.rankedResultBinding` before the engine scripts execute:
  `{ previousRP, delta, reason, season: { id, start, end } }`.
- `delta` is the final signed integer from the RP engine, including mode and
  mixed-tier adjustments. This screen does not invent an award formula.
- `reason` supplies the required plain-language adjustment direction for
  mixed-tier matches; no multiplier is displayed.
- Ranks follow the exact 19 lower bounds; account rank/RP must agree.
- Bind `window.returnToRanked(screenId)` to host navigation for `"S45"`.
  The S45 document was not supplied, so the standalone file reports the
  requested binding rather than linking to a fabricated destination.
- With a production binding, scores come from the unchanged scoring/session
  engines and the preview state control does not replace the real outcome.

Source conflicts found:

- `game-state.js` seeds Gold III at 1,240 RP; the approved ladder maps 1,240 RP
  to Platinum II. That unrelated seed is retained, but is not used here.
- The supplied state manager has no S45 route.
- The supplied Win/Lose control ignored opposite outcomes; fixture cycling now
  works without overwriting persisted session scores.
- The original title decorated all outcomes with crowns. Title crowns now
  appear only for Match King; champion row and mug artwork are preserved.

Only the three requested token definitions changed in `table-system.css`;
references to renamed `--crimson` were mechanically updated to `--error`.
No new tokens were added. Existing legacy tokens and styling outside the
requested corrections were retained.

Reveal timing: existing rows/950ms score tally, champion pop, RP tally starts
at 1,300ms and finishes at 1,850ms, then a 350ms rank transition (2.2s total).

Validation: `node public/standings/validate.cjs`; build: `pnpm build`.
