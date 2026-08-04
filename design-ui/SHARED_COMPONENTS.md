# Shared UI Components — Sprint 0

New files: `shared-ui.css`, `shared-ui.js`. Load order: after table tokens are in scope, before the screen's own script. Uses only existing tokens (`--accent`, `--panel`, `--panel-line`, `--ink`, `--ink-dim`, `--pill`, `--mono`) — no new colors introduced.

## Toast — `UI.toast(msg, { container, duration })`
Consolidates the ad hoc transient-message toasts in `room.js` (`toast()`, was a hand-rolled `#rmToast` div) and `shop.js` (`toast()`, was `#toast`). Both now call `UI.toast()`; one `.ui-toast` element is created lazily per container and reused. Visual style is shop's prior toast (closest to the shared token system already).

**Scoping note — `bidding-render.js`'s `waitToast` is intentionally NOT folded into this component.** It isn't a transient message — it's a persistent state indicator tied to whose turn it is (shown/hidden by turn state, not fire-and-forget). Consolidating it here would force an awkward "sticky toast" mode onto a component built for one-off messages. Left as its own pattern; flagged in the audit's Bidding Phase section for the reconnect-toast use case, which *is* a fire-and-forget message and should use `UI.toast()` when built.

## Modal — `UI.openModal(el)` / `UI.closeModal(el)` / `UI.bindModalDismiss(el)`
Consolidates Shop's `#detail-modal` and `#creator-modal`, which shared near-identical backdrop/box/close-button CSS. Both now use `.ui-modal-backdrop` / `.ui-modal-box` / `.ui-modal-close`, and `hidden` is toggled via `UI.openModal`/`UI.closeModal` instead of ad hoc `el.hidden = true/false`.

**Scoping note — Game Table's `#modal` (last-trick peek) and Final Standings' `.res-modal` are NOT migrated.** Game Table's modal is part of `table-system.css`, already shared across Room and Game Table, with its own scrim/animation treatment suited to in-table overlays (`.modal-layer.show`) — a genuinely different family from Shop's backdrop dialogs, not a duplicate of it. Final Standings' `.res-modal` isn't a toggleable dialog at all; it's the screen's own fixed result panel with no open/closed state. Forcing either into the dialog-family component would be a bigger change than requested for no real gain — flagging here rather than reworking working screens.

## Input — `.ui-field` / `.ui-input` / `.ui-hint`
```html
<div class="ui-field">
  <label class="ui-label" for="x">Room Code</label>
  <input class="ui-input" id="x" placeholder="ABCD12">
  <div class="ui-hint">Ask your host for the code.</div>
</div>
```
Error state: add `.error` to `.ui-field` (or call `UI.setFieldError(fieldEl, "message")`) — reddens border and hint text. First text-input pattern in the project; unblocks Room's join-by-code and Shop's search (Sprint 3/5).

## Loading/Skeleton — `.ui-skel` / `.ui-skel-line` / `.ui-skel-circle`
Shimmer-sweep placeholder block. Apply to any element awaiting async data (`<div class="ui-skel ui-skel-line" style="width:60%"></div>`). Unblocks Lobby/Shop/Ranked Match backend wiring (Sprint 1–5) per the roadmap's own Definition of Done.

## Files touched
- `shared-ui.css`, `shared-ui.js` (new)
- `Estimation Room.html`, `room.js` — toast wired to `UI.toast`
- `Estimation Shop.html`, `shop.js` — toast and both modals wired to shared component
