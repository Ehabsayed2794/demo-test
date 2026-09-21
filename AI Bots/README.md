# AI Bots — TypeScript reference implementation (port baseline)

These files are the **source of truth** for the native AI opponents. Epic E4a
(`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md`, stories S5–S10) ports them into
`native/engine/src/main/java/com/estemshan/engine/bot/` as pure JVM Kotlin.

They are committed **read-only reference**: the web app they were written for
is not part of this migration, so these files are never imported by anything
that ships. Do not edit them to fix a bug — fix the Kotlin, then update the
note here if the two must diverge.

## File → story mapping

| File | Port story | Notes |
|---|---|---|
| `types.ts` | — (types only) | Maps onto existing `:engine` `Card`/`Suit`/`Rank`; see below |
| `botEngine.ts` | **S5** hand evaluator + `TIER_CONFIG` | Module A + the 4-tier table. Self-contained, no missing deps — this is why it goes first |
| `botEngine.ts` §3 | S6 bidding brain | `evaluateBotBid`, `BotBidResult`, `seatJitter` |
| `botPlay.ts` | S7 play brain | Needs `seenCards`, which exists nowhere in `:engine` yet |
| `botStrategy.ts` | S8 spoiler | Reads `TableState` + estimates |
| `botPersonality.ts` + `botSimulation.ts` | S9 SimPort seam + Personality | **Coupled** — see below |
| `App.tsx` | S12 Choose Level screen | ~3754–3945 is the tier/personality picker UI |

## Gaps found while committing this baseline

1. **`./cardRules` does not exist.** `botPlay.ts`, `botStrategy.ts` and
   `botSimulation.ts` all `import { beats, legalMoves } from './cardRules'`,
   and no `cardRules.*` file exists anywhere in the repo — the drop is
   incomplete. **Resolution for the port:** do not reconstruct it. `:engine`
   already owns these rules natively (`Table.kt`: `legalCards`, `isLegal`,
   `canPlayCard`), and S7/S8 port against those. Porting a duplicate rules
   module would create the exact divergence the frozen rules exist to prevent.
2. **`types.ts` ↔ `botPersonality.ts` is circular.** `PlayerModel` imports
   `Personality` from `botPersonality.ts`, which imports `botSimulation.ts`.
   Harmless in TS modules; in Kotlin the seam is an explicit constructor
   parameter (`SimPort`, S9) rather than an import cycle.
3. **`botSimulation.ts` is EXPERT-only but not deferrable.** `botPersonality`
   uses it for **HARD, not just EXPERT** — deferring Monte-Carlo post-launch
   (as the plan's conservative scenario does) would regress HARD, so S9 ports
   a heuristic `SimPort` default instead. The source documents the bug this
   guards: *"at full confidence it trusted the optimistic heuristic and
   over-bid."*

## Port conventions (established in S5)

- **`Suit.SANS` is the TS `trump === 'NONE'` case.** The TS type is
  `Suit | 'NONE'`; `:engine`'s `Suit` enum already models Sans as a
  trump-only "suit" with strength 5, so the Kotlin evaluator takes
  `trump: Suit` with no nullable/union type. `evaluateAllTrumps` iterates
  `DECK_SUITS + Suit.SANS`, matching the JS `['SPADES','HEARTS','DIAMONDS','CLUBS','NONE']`.
- **Ranks are values, not a string enum.** TS `Rank` is `'TWO'..'ACE'`; Kotlin
  `Rank` is `data class Rank(v: Int, s: String)` with `Card.value: Int`. The
  evaluator works in integers (ACE = 14, KING = 13, QUEEN = 12, JACK = 11)
  and never needs the TS string enum.
- **`TIER_CONFIG` becomes enum constructor parameters**, not a
  `Map<BotTier, TierConfig>` — idiomatic Kotlin, same values, and
  `TIER_CONFIG[tier].canDash` becomes `tier.canDash`.
- **Determinism is a port requirement, not an optimisation.** The TS sources
  already avoid `Math.random()` in the brain (`seatJitter` is a hash); the
  Kotlin port keeps that property because S10's bot-vs-bot 18-round smoke
  test has to be reproducible in CI.
