# S52 Rank Up — Delivery notes

**Files:** `Estimation Ranked Rank Up S52.html` (canvas: 3 variants + contextual settling frame, copy table, state notes), `rank-up-s52.css`, `rank-up-s52.js` (declared `window.rankUpBinding`), shared `placement-base.css`. All frames 932×430.

> **Post-approval amendment (2026-10-17, owner).** The RP readout label is **`FINAL RP`**, replacing the
> `SETTLED RP` draft below: the displayed number is the confirmed RP *total*, and in the engine `settled`
> already names the zero-movement rank result (`RankedResolution`: +1 promote / 0 settled / −1 demote).
> **`Settling your rank…` is kept**, not "Updating your rank…" — the client never writes a rank (RD13), the
> wait is a round-trip for a server-owned value, and the roadmap's own word for that state is "settling…"
> (R24). The canvas Replay pill is renamed `.rp` → `.replay`; S45/S51 use `.rp` for the readout block.
> Still draft: the statement wording and the two support lines. See the artboard's copy table.

## Checklist
- [x] 1 One family, three variants, HTML, 932×430, approved tokens only (fonts: Marcellus, Saira, Spline Sans Mono, Noto Naskh Arabic).
- [x] 2 Both rungs are one component (shield clip-path, EN+AR pair, division, bound); previous receded (InkDim / InkFaint ring), new emphasised.
- [x] 3 Every numeral in Spline Sans Mono (bounds, SETTLED RP, statement figure).
- [x] 4 V1 Gold III→II (575/700), V2 Gold I→Platinum III (825/1,000), V3 Royal I→King (2,750/3,000); V3 is a distinct, isolated composition.
- [x] 5 V2 destination is Platinum III, shown in the same pill size as V1 and restated in a support line.
- [x] 6 V3: no division, no next rung, no continuation bar, no crown, no gold; neutral swappable tier slot.
- [x] 7 Bounds are real ladder values; RP 716 / 1,012 / 3,004 are declared samples. No game-state.js data.
- [x] 8 5px up + static restrained glow, ≈2,200ms, no count-up/shake/flash/loop; reduced-motion shows the end state.
- [x] 9 No pacing figure, no `+N`, no delta.
- [x] 10 No Mini marker or separate layout.
- [x] 11 Text uses Ink / InkDim only (≥7:1 on Surface, SurfaceHigh, Background).
- [x] 12 Settling frame: skeleton + "Settling your rank…", no number.
- Gates: #1 n/a (no ladder drawn); #2 Royal-distinct clause **not satisfiable** (no tier tokens); #3, #4, #12, #13 met.

## Open decisions (designed around)
1 Tier slot (`--tier-ring`, `--tier-ink`, `.slot`) neutral today. 2 No flow chrome; S52↔S57 navigation unwritten. 3 Draft copy: statement wording, "Platinum begins at division III.", "The highest rank on the ladder.", "SETTLED RP", "Settling your rank…". 4 King has no ceremonial token; dignity from scale and restraint. 5 Seed conflict not reproduced.

## Flags
- Kicker is InkDim, not InkFaint. Corrected 2026-10-17 with measurements: at the kicker's own glyph run 10px InkFaint is 4.59–4.65:1 (it passes there) and InkDim is 6.67–6.75:1; InkFaint fails (4.20:1) at the backdrop's brightest point, top-centre inside the warm radial, where InkDim is 6.11:1. Tokens §5 is the rule being applied.
- Gold unused: no action exists on the frame; glow is Ink at low alpha.
- Missing tokens: backdrop gradient (inherited from `.scr`), a role for the "SETTLED RP" label.
- S46/S50 siblings use proposed per-tier colours; S52 deliberately does not, per the token file.

## Self-review
1 Platinum III stays legible as the pill and statement both name it. 2 V3 reads as the ladder's top: "King · ملك", no crown, no gold. 3 Motion is a banner lifting; nothing bursts. 4 The number most wanted: a "gain" beside SETTLED RP; omitted. 5 V1 and V2 share one layout; only rungs, Arabic title and one fact line differ. 6 V3's slot absorbs a tier colour without redesign. 7 Added beyond brief: "SETTLED RP" readout and one support line per V2/V3.
