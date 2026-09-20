/* ════════════════════════════════════════════════════════════════════
   Estimation — Table (Trick-Taking) Render Layer
   Single combined Bidding+Table screen: this module owns the TABLE half.

   Defines the 6 UI hooks table-engine.js's own advance()/playFromHand()/
   restart() call as bare globals — render(), bindStatic(),
   showEscalationBanner(), showRoundDone(), sweepThenResolve(),
   flashReject() — without touching table-engine.js itself.

   Contracts honored (read-only unless stated):
   - Legality: TableEngine.canPlayCard(playerId, card) is the SOLE
     legality oracle, for both rendering (grey-out) and clicks
     (pre-emit re-check). Follow-suit / turn / phase rules are never
     reimplemented here. previewPlay() is not needed by this layer
     (it answers "what turn comes next", which a renderer never decides).
   - State: TableEngine.getState() (phase, turn, hands, currentPlays/
     plays, trickNo/trickNumber, tricksWon, lastTrick, ledSuit, voids,
     trump, round) + GameSession.getPlayers()/getPlayer()/getDealer()/
     getHand()/ensureHandsDealt(). The `initial` field is the only avatar.
   - Input: clicks call TableEngine.emit({type:"PlayCard",...}) directly
     (local-engine-driven rendering). This module never calls Firestore,
     MatchService, or any MatchAdapter.start*Sync() pipeline.
   - Feedback: UI.toast()/openModal kit (login/shared-ui.js) for toasts;
     no parallel toast/modal system.
   - Cards: CSS-only (suit symbol + rank text divs). No image assets.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  "use strict";

  var SEAT_ORDER = ["p1", "p2", "p3", "p4"];
  var SWEEP_MS = 480;
  var REJECT_MS = 340;
  var BANNER_MS = 6000;

  // One-shot guards. Plain flags, not a state machine: the engine owns
  // all game state; these only prevent duplicate listeners/timers when
  // render() is re-entered (engine advance + snapshot callbacks + local
  // clicks can all trigger it for the same delivery).
  var staticBound = false;
  var sweepScheduled = false;
  var roundDoneKey = null;

  /* ── small helpers (DOM + engine access, never game rules) ── */

  function $(id) { return document.getElementById(id); }

  function prefersReducedMotion() {
    try {
      return window.matchMedia &&
        window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    } catch (e) { return false; }
  }

  function tableState() {
    try {
      if (global.TableEngine && typeof global.TableEngine.getState === "function") {
        return global.TableEngine.getState();
      }
    } catch (e) { console.error("[TableRender] getState() threw:", e); }
    return null;
  }

  /** Which seat THIS browser plays: the online seat when a real match
   *  handoff owns the screen (read via the screen's own debug accessor),
   *  else the engine roster's human seat, else p1. Display + click
   *  routing only — never consulted for legality (canPlayCard decides). */
  function humanSeatId() {
    try {
      if (global.MatchScreenDebug &&
          typeof global.MatchScreenDebug.getLocalSeatId === "function") {
        var s = global.MatchScreenDebug.getLocalSeatId();
        if (s) return s;
      }
    } catch (e) {}
    try {
      if (global.GameSession && typeof global.GameSession.getPlayers === "function") {
        var ps = global.GameSession.getPlayers();
        var u = ps.filter(function (p) { return p && p.isUser; })[0];
        if (u) return u.id;
      }
    } catch (e) {}
    return "p1";
  }

  function playerLabel(seatId) {
    if (!seatId) return "—";
    try {
      var p = global.GameSession && global.GameSession.getPlayer(seatId);
      return (p && p.name) ? p.name : seatId;
    } catch (e) { return seatId; }
  }

  /** Suit display DATA lookup (name/symbol/red flag) — the same lookup
   *  cards.js's own createCard() uses for displayName. Never a strength
   *  comparison (that stays inside table-engine.js's cardValue()). */
  function suitInfo(suitKey) {
    try {
      var s = global.Cards && global.Cards.SUITS && global.Cards.SUITS[suitKey];
      if (s) return s;
    } catch (e) {}
    return { sym: suitKey || "?", red: false, name: suitKey || "?" };
  }

  function cardText(card) {
    var s = suitInfo(card.suit);
    return (card.rank && card.rank.s ? card.rank.s : "?") + " " + s.sym;
  }

  function setText(el, text) { if (el) el.textContent = text; }

  /** THE legality gate (render-time). Every playable/illegal decision on
   *  this screen flows through TableEngine.canPlayCard() — no follow-suit
   *  check exists anywhere else in this file. Returns {legal, reason}. */
  function legalityFor(seatId, card) {
    try {
      if (global.TableEngine && typeof global.TableEngine.canPlayCard === "function") {
        return global.TableEngine.canPlayCard(seatId, card) || { legal: false };
      }
    } catch (e) {}
    return { legal: false, reason: "Table engine unavailable." };
  }

  /* ── shell: banner + round-done overlay (created once, reused) ── */

  function ensureShell() {
    var view = $("matchGameView");
    if (view && !$("escalationBanner")) {
      var banner = document.createElement("div");
      banner.id = "escalationBanner";
      banner.className = "escalation-banner";
      banner.setAttribute("role", "alert");
      banner.hidden = true;
      view.insertBefore(banner, view.firstChild);
      banner.addEventListener("click", function () { banner.hidden = true; });
    }
    if (view && !view.querySelector(".round-done")) {
      var done = document.createElement("div");
      done.className = "round-done";
      done.hidden = true;
      view.appendChild(done);
    }
  }

  /* ── 1. render() — full table redraw from engine state ── */

  function render() {
    try {
      var state = tableState();
      if (!state) return; // engine not initialized yet — leave bidding UI alone
      ensureShell();
      renderSeatsExtra(state);
      renderTableVisibility(state);
      renderTurnLine(state);
      renderTrickPile(state);
      renderLastTrickLine(state);
      renderOwnHand(state);
      driveLocalPhase(state);
    } catch (e) {
      console.error("[TableRender] render() threw:", e);
    }
  }

  /** Seats themselves are drawn by the screen's own renderSeats() when a
   *  real match handoff owns the screen; in local-engine mode (direct
   *  open, no handoff) that never runs, so draw them here from the same
   *  GameSession getters with the same layout/classes (p1 bottom / p2
   *  right / p3 top / p4 left, `initial` avatar, dealer tag). Display
   *  only — no gameplay data invented. Afterwards (both modes) sync the
   *  tricks-won badge + turn highlight from engine state. */
  var SEAT_CLASS = { p1: "seat-p1", p2: "seat-p2", p3: "seat-p3", p4: "seat-p4" };
  function renderSeatsExtra(state) {
    var seatsEl = $("matchSeats");
    if (!seatsEl) return;
    if (!seatsEl.querySelector(".seat")) renderSeatsFull(seatsEl);
    var counts = (state && state.tricksWon) || {};
    SEAT_ORDER.forEach(function (seatId) {
      var seatEl = seatsEl.querySelector(".seat-p" + seatId.slice(1));
      if (!seatEl) return;
      var badge = seatEl.querySelector(".seat-tricks");
      if (!badge) {
        badge = document.createElement("div");
        badge.className = "seat-tricks";
        seatEl.appendChild(badge);
      }
      var n = counts[seatId];
      badge.textContent = (n == null ? "" : "★ " + n);
      if (state.turn) seatEl.classList.toggle("is-turn", state.turn === seatId);
    });
  }

  function renderSeatsFull(seatsEl) {
    var players = [];
    var dealerId = null;
    try {
      players = global.GameSession.getPlayers() || [];
      dealerId = global.GameSession.getDealer();
    } catch (e) { players = []; }
    seatsEl.innerHTML = "";
    players.forEach(function (p) {
      if (!p || !SEAT_CLASS[p.id]) return;
      var el = document.createElement("div");
      el.className = "seat " + SEAT_CLASS[p.id];
      var avatar = document.createElement("div");
      avatar.className = "avatar";
      avatar.textContent = p.initial || "?";
      var name = document.createElement("div");
      name.className = "seat-name";
      name.textContent = p.name || p.id;
      el.appendChild(avatar);
      el.appendChild(name);
      if (p.id === dealerId) {
        var tag = document.createElement("div");
        tag.className = "seat-dealer";
        tag.textContent = "Dealer";
        el.appendChild(tag);
      }
      seatsEl.appendChild(el);
    });
  }

  function renderTableVisibility(state) {
    var panel = $("tablePanel");
    if (!panel || !state) return;
    panel.classList.remove("hidden");
    var placeholder = $("matchViewPlaceholder");
    if (placeholder) placeholder.style.display = "none";
  }

  function renderTurnLine(state) {
    var turnEl = $("tableTurn");
    if (!turnEl) return;
    turnEl.innerHTML = "";
    if (state.phase === "DONE") {
      turnEl.textContent = "Round complete.";
      return;
    }
    var me = humanSeatId();
    var who = document.createElement("span");
    who.className = "who";
    var t = suitInfo(state.trump);
    var suffix = "  ·  Trick " + (state.trickNo || state.trickNumber || "—") +
      "/13" + (t && t.name ? "  ·  Trump " + t.name + " " + t.sym : "");
    if (state.turn === me) {
      who.textContent = "Your turn";
      turnEl.appendChild(who);
      turnEl.appendChild(document.createTextNode(suffix));
    } else {
      who.textContent = playerLabel(state.turn);
      turnEl.appendChild(document.createTextNode("Waiting on "));
      turnEl.appendChild(who);
      turnEl.appendChild(document.createTextNode(suffix));
    }
  }

  /** Current trick's played cards, one slot per seat in seat order (empty
   *  slot when that seat hasn't played yet). Reads state.plays (this
   *  engine names the field `plays`, not `currentPlays`) — direct field
   *  reads only; no winner comparison here. */
  function renderTrickPile(state) {
    var el = $("trickPanel");
    if (!el) return;
    el.innerHTML = "";
    var plays = state.plays || state.currentPlays || [];
    SEAT_ORDER.forEach(function (seatId) {
      var slot = document.createElement("div");
      slot.className = "trick-slot";
      var label = document.createElement("div");
      label.className = "trick-seat";
      label.textContent = playerLabel(seatId);
      slot.appendChild(label);
      var play = plays.filter(function (p) { return p && p.playerId === seatId; })[0];
      var chip = document.createElement("div");
      if (play && play.card) {
        var s = suitInfo(play.card.suit);
        chip.className = "tcard" + (s.red ? " is-red" : "");
        chip.textContent = cardText(play.card);
      } else {
        chip.className = "tcard is-empty";
        chip.textContent = "—";
      }
      slot.appendChild(chip);
      el.appendChild(slot);
    });
  }

  /** "Last trick won by X (card)" — reads the engine's own
   *  state.lastTrick.winnerId (computed by resolveTrick(), never here).
   *  Separate element from the live pile: plays[] is cleared the instant
   *  a trick resolves, so the winner would otherwise vanish. */
  function renderLastTrickLine(state) {
    var el = $("lastTrickInfo");
    if (!el) return;
    el.innerHTML = "";
    if (!state.lastTrick || !state.lastTrick.winnerId) return;
    var who = document.createElement("span");
    who.className = "who";
    who.textContent = playerLabel(state.lastTrick.winnerId);
    el.appendChild(document.createTextNode("Last trick won by "));
    el.appendChild(who);
    var win = (state.lastTrick.plays || []).filter(function (p) {
      return p && p.playerId === state.lastTrick.winnerId;
    })[0];
    if (win && win.card) el.appendChild(document.createTextNode(" (" + cardText(win.card) + ")"));
  }

  /** The human player's own hand. Structure (not just disabled-ness)
   *  follows whose turn it is: a real <button> only when this seat can
   *  genuinely act now; a read-only chip otherwise. Playable vs greyed
   *  comes from legalityFor() → canPlayCard() per card. */
  function renderOwnHand(state) {
    var el = $("handPanel");
    if (!el) return;
    el.innerHTML = "";
    var me = humanSeatId();
    var hand = [];
    try {
      hand = (global.GameSession && typeof global.GameSession.getHand === "function")
        ? (global.GameSession.getHand(me) || []) : [];
    } catch (e) { hand = []; }
    if (!hand.length) {
      var empty = document.createElement("div");
      empty.className = "table-empty";
      empty.textContent = "No cards in hand.";
      el.appendChild(empty);
      return;
    }
    // Display sort only (strongest suit first) via the engine's own
    // exported comparator — presentation order, never legality.
    try {
      if (global.Cards && typeof global.Cards.compareForSort === "function") {
        hand = hand.slice().sort(global.Cards.compareForSort);
      }
    } catch (e) {}

    var canAct = state.phase === "PLAY" && state.turn === me;
    if (!canAct) {
      var wait = document.createElement("div");
      wait.className = "table-empty";
      var msg = state.phase === "DONE" ? "" : "Waiting on " + playerLabel(state.turn) + "…";
      if (msg) { wait.textContent = msg; el.appendChild(wait); }
      hand.forEach(function (card) {
        el.appendChild(readOnlyCard(card));
      });
      return;
    }

    hand.forEach(function (card) {
      var verdict = legalityFor(me, card);
      var btn = document.createElement("button");
      btn.type = "button";
      var s = suitInfo(card.suit);
      btn.className = "tcard is-playable" + (s.red ? " is-red" : "") +
        (verdict.legal ? "" : " is-illegal");
      btn.textContent = cardText(card);
      btn.dataset.cardId = card.id;
      if (verdict.legal) {
        btn.setAttribute("aria-label", "Play " + cardText(card));
      } else {
        btn.disabled = true;
        btn.setAttribute("aria-disabled", "true");
        btn.title = (verdict && verdict.reason) || "Not playable now.";
        btn.setAttribute("aria-label", cardText(card) + " (not playable: " +
          ((verdict && verdict.reason) || "illegal") + ")");
      }
      // Clicks are handled by the ONE delegated listener bindStatic()
      // attaches to #handPanel (so rebuilt buttons never double-wire).
      el.appendChild(btn);
    });
  }

  function readOnlyCard(card) {
    var s = suitInfo(card.suit);
    var chip = document.createElement("div");
    chip.className = "tcard" + (s.red ? " is-red" : "");
    chip.textContent = cardText(card);
    return chip;
  }

  /** Local phase driver. TableEngine.advance() is unreachable from
   *  outside (private inside its IIFE, and its old DOMContentLoaded
   *  bootstrap was removed) — so after a direct emit() leaves
   *  phase RESOLVING/DONE, THIS is what schedules the visual step +
   *  resolve + round summary. No Firestore, no sync pipelines. */
  function driveLocalPhase(state) {
    if (!state) return;
    if (state.phase === "RESOLVING" && !sweepScheduled) {
      sweepScheduled = true;
      sweepThenResolve();
    } else if (state.phase === "DONE") {
      showRoundDone();
    } else if (state.phase === "PLAY") {
      sweepScheduled = false;
    }
  }

  /* ── 2. bindStatic() — one-time control wiring ── */

  function bindStatic() {
    if (staticBound) return;
    staticBound = true;
    try {
      ensureShell();
      var handEl = $("handPanel");
      if (handEl) {
        // Delegated: renderOwnHand() rebuilds buttons on every render,
        // so per-button listeners would double-wire or go stale. One
        // parent listener survives rebuilds; disabled (illegal) buttons
        // never fire click events at all (enforced by the platform).
        handEl.addEventListener("click", function (ev) {
          var btn = ev.target && ev.target.closest
            ? ev.target.closest("button.tcard[data-card-id]") : null;
          if (!btn || btn.disabled) return;
          var card = findHandCard(btn.dataset.cardId);
          if (card) onCardClick(card);
        });
      }
      render();
    } catch (e) {
      console.error("[TableRender] bindStatic() threw:", e);
    }
  }

  function findHandCard(cardId) {
    try {
      var hand = global.GameSession.getHand(humanSeatId()) || [];
      var hit = hand.filter(function (c) { return c && c.id === cardId; })[0];
      if (hit) return hit;
      // Fallback: engine-state hands (same objects, different path).
      var st = tableState();
      var hs = st && st.hands && st.hands[humanSeatId()];
      return (hs || []).filter(function (c) { return c && c.id === cardId; })[0] || null;
    } catch (e) { return null; }
  }

  /** The ONE local submission path. Re-checks canPlayCard() immediately
   *  before emit (the card may have gone stale between render and tap) —
   *  never trusts the rendered enabled-state alone. */
  function onCardClick(card) {
    try {
      var me = humanSeatId();
      var verdict = legalityFor(me, card);
      if (!verdict.legal) {
        flashReject((verdict && verdict.reason) || "That card isn't legal right now.");
        render();
        return;
      }
      var res = global.TableEngine.emit({ type: "PlayCard", playerId: me, card: card });
      if (res && res.rejected) {
        flashReject(res.reason || "That card isn't legal right now.");
        render();
        return;
      }
      render();
    } catch (e) {
      console.error("[TableRender] onCardClick() threw:", e);
    }
  }

  /* ── 3. showEscalationBanner() — Sa'ayda / multiplier event ── */

  function showEscalationBanner(icon, title, subtitle) {
    try {
      ensureShell();
      var banner = $("escalationBanner");
      if (!banner) {
        if (global.UI && typeof global.UI.toast === "function") {
          global.UI.toast(String(title || "Escalation") +
            (subtitle ? " — " + subtitle : ""));
        }
        return;
      }
      banner.innerHTML = "";
      var ic = document.createElement("span");
      ic.className = "esc-icon";
      ic.setAttribute("aria-hidden", "true");
      ic.textContent = icon || "★";
      var tx = document.createElement("span");
      tx.className = "esc-text";
      var t1 = document.createElement("span");
      t1.className = "esc-title";
      t1.textContent = title || "Escalation";
      tx.appendChild(t1);
      if (subtitle) {
        var t2 = document.createElement("span");
        t2.className = "esc-sub";
        t2.textContent = subtitle;
        tx.appendChild(t2);
      }
      banner.appendChild(ic);
      banner.appendChild(tx);
      banner.hidden = false;
      banner.classList.remove("show");
      // Force reflow so the entrance transition replays on repeat events.
      void banner.offsetWidth;
      banner.classList.add("show");
      clearTimeout(banner._hideT);
      banner._hideT = setTimeout(function () { banner.hidden = true; }, BANNER_MS);
      if (global.UI && typeof global.UI.toast === "function") {
        global.UI.toast(String(title || "Escalation") +
          (subtitle ? " — " + subtitle : ""));
      }
    } catch (e) {
      console.error("[TableRender] showEscalationBanner() threw:", e);
    }
  }

  /* ── 4. showRoundDone() — round-end summary overlay ── */

  function showRoundDone() {
    try {
      ensureShell();
      var state = tableState();
      if (!state || state.phase !== "DONE") return;
      var key = (state.round != null ? state.round : "?") + ":" +
        JSON.stringify(state.tricksWon || {});
      if (roundDoneKey === key) return; // idempotent per final result
      roundDoneKey = key;
      var view = $("matchGameView");
      var overlay = view && view.querySelector(".round-done");
      if (!overlay) return;
      overlay.innerHTML = "";
      var title = document.createElement("div");
      title.className = "rd-title";
      title.textContent = state._saaydaNext
        ? "Sa'ayda — Round Zeroed · Next ×" + state._saaydaNext
        : "Round Complete";
      overlay.appendChild(title);
      var rows = document.createElement("div");
      rows.className = "rd-rows";
      SEAT_ORDER.forEach(function (seatId) {
        var row = document.createElement("div");
        row.className = "rd-row";
        var name = document.createElement("span");
        name.className = "rd-name";
        name.textContent = playerLabel(seatId);
        var won = document.createElement("span");
        won.className = "rd-won";
        var n = state.tricksWon && state.tricksWon[seatId];
        won.textContent = (n == null ? "—" : n) + " tricks";
        row.appendChild(name);
        row.appendChild(won);
        if (state.lastTrick && state.lastTrick.winnerId === seatId) {
          row.classList.add("is-last-winner");
        }
        rows.appendChild(row);
      });
      overlay.appendChild(rows);
      var close = document.createElement("button");
      close.type = "button";
      close.className = "rd-close";
      close.textContent = "Close";
      close.addEventListener("click", function () { overlay.hidden = true; });
      overlay.appendChild(close);
      overlay.hidden = false;
      // Force reflow so the entrance transition replays.
      void overlay.offsetWidth;
      overlay.classList.add("show");
    } catch (e) {
      console.error("[TableRender] showRoundDone() threw:", e);
    }
  }

  /* ── 5. sweepThenResolve() — collect animation, THEN resolve ── */

  function sweepThenResolve() {
    try {
      var state = tableState();
      if (!state || state.phase !== "RESOLVING") {
        sweepScheduled = false;
        return;
      }
      var pile = $("trickPanel");
      var delay = prefersReducedMotion() ? 0 : SWEEP_MS;
      if (pile) {
        pile.classList.remove("is-sweeping");
        void pile.offsetWidth; // restart the transition if re-entered
        if (delay > 0) pile.classList.add("is-sweeping");
      }
      setTimeout(function () {
        try {
          // THE required call: visual step first, resolveTrick() after.
          // (A CSS transition delay for a real UI animation — not a test
          // harness sleep.)
          global.TableEngine.resolveTrick();
        } catch (e) {
          console.error("[TableRender] resolveTrick() threw:", e);
        } finally {
          sweepScheduled = false;
          if (pile) pile.classList.remove("is-sweeping");
          render();
        }
      }, delay);
    } catch (e) {
      console.error("[TableRender] sweepThenResolve() threw:", e);
      sweepScheduled = false;
    }
  }

  /* ── 6. flashReject() — illegal-move feedback ── */

  function flashReject(reason) {
    try {
      var msg = reason || "That card isn't legal right now.";
      if (global.UI && typeof global.UI.toast === "function") {
        global.UI.toast(msg);
      }
      var handEl = $("handPanel");
      if (handEl) {
        handEl.classList.remove("is-reject");
        void handEl.offsetWidth; // restart the shake on repeat rejects
        if (!prefersReducedMotion()) handEl.classList.add("is-reject");
        clearTimeout(handEl._rejectT);
        handEl._rejectT = setTimeout(function () {
          handEl.classList.remove("is-reject");
        }, REJECT_MS);
      }
      var turnEl = $("tableTurn");
      if (turnEl && !global.UI) {
        turnEl.setAttribute("aria-live", "assertive");
      }
    } catch (e) {
      console.error("[TableRender] flashReject() threw:", e);
    }
  }

  /* ── local-engine auto-boot (validation / direct-open path) ──
   *  Runs ONLY when no real match handoff owns this screen (no matchId
   *  in GameState) and the table engine has no state yet — i.e. the
   *  file was opened directly, exactly the validation scenario. With a
   *  real handoff, the screen's own subscribe → maybeEnterPlayPhase flow
   *  owns initState() and this boot stays out of the way (online sync
   *  pipelines are never started here). */
  function localBoot() {
    try {
      if (!global.GameSession || !global.TableEngine) return;
      var hasHandoff = false;
      try {
        hasHandoff = !!(global.GameState &&
          typeof global.GameState.getData === "function" &&
          global.GameState.getData().match &&
          global.GameState.getData().match.id);
      } catch (e) { hasHandoff = false; }
      if (hasHandoff) return;
      if (tableState()) { bindStatic(); return; }
      if (typeof global.GameSession.ensureHandsDealt === "function") {
        global.GameSession.ensureHandsDealt();
      }
      if (typeof global.TableEngine.initState === "function") {
        global.TableEngine.initState();
      }
      bindStatic();
    } catch (e) {
      console.error("[TableRender] localBoot() threw:", e);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", localBoot);
  } else {
    localBoot();
  }

  // Bare globals: table-engine.js calls render()/bindStatic()/
  // showEscalationBanner()/showRoundDone()/sweepThenResolve()/
  // flashReject() as unqualified identifiers, which resolve against the
  // global object in classic scripts. Assign both forms explicitly.
  global.render = render;
  global.bindStatic = bindStatic;
  global.showEscalationBanner = showEscalationBanner;
  global.showRoundDone = showRoundDone;
  global.sweepThenResolve = sweepThenResolve;
  global.flashReject = flashReject;

  // Namespaced handle for diagnostics/tests (never used by engines).
  global.TableRender = {
    render: render,
    bindStatic: bindStatic,
    showEscalationBanner: showEscalationBanner,
    showRoundDone: showRoundDone,
    sweepThenResolve: sweepThenResolve,
    flashReject: flashReject,
    humanSeatId: humanSeatId
  };
})(window);
