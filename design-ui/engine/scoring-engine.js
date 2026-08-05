/* ════════════════════════════════════════════════════════════════════
   Estimation — Real Scoring Engine
   Implements ONLY the rules in uploads/Estimation_Rules_v2_SingleSourceOfTruth.docx
   §4 "Official Scoring System" (individual play, no partnerships — see
   calculateTeamScore()). Pure calculation + one persistence entry point;
   see ScoringEngine.md for formulas, inputs/outputs, and Open Rule
   Questions this implementation could not resolve from the document.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  function seatOrder() { return GameSession.getPlayers().map(function (p) { return p.id; }); }
  function nextSeat(id, order) { var i = order.indexOf(id); return order[(i + 1) % order.length]; }

  function totalBidAmount(bids) {
    var sum = 0;
    Object.keys(bids).forEach(function (id) {
      var b = bids[id];
      if (b && (b.type === "TRICKS" || b.type === "DASH" || b.type === "DASHCALL")) sum += b.amount;
    });
    return sum;
  }

  // Risk Level table (rules §2.3 "Risk Value")
  function riskValueFor(diff) {
    if (diff <= 1) return 0;
    if (diff <= 3) return 10;
    if (diff <= 5) return 20;
    return 30; // diff >= 6
  }

  function succeeded(bid, tricksWon) {
    if (!bid) return false;
    if (bid.type === "DASHCALL" || bid.type === "DASH") return tricksWon === 0;
    if (bid.type === "TRICKS") return tricksWon === bid.amount;
    return false;
  }

  // ── Classic Calculations mode ───────────────────────────────────────────
  // Ported from classicScoring.ts (reverse-engineered and verified against
  // 18 rounds of real game data from a competitor app's "Classic
  // Calculations" mode) — a genuinely different formula from Normal mode,
  // not a variant of it. See ScoringEngine.md.
  var CLASSIC_ROLES = {
    NORMAL: "NORMAL", CALLER: "CALLER", WIZZ: "WIZZ", RISK: "RISK",
    WIZZ_RISK: "WIZZ_RISK", SUPER_CALL: "SUPER_CALL",
    DASH_CALL: "DASH_CALL", REG_DASH: "REG_DASH"
  };

  /** input: { role, bid, won, totalBids, isSoleWinner, isSoleLoser } */
  function calculateClassicScore(input) {
    var role = input.role, bid = input.bid, won = input.won, totalBids = input.totalBids;
    var success = won === bid;
    var miss = Math.abs(won - bid);
    var isGameOver = totalBids > 13;
    var isDeepUnder = totalBids <= 11;
    var score;

    switch (role) {
      case CLASSIC_ROLES.SUPER_CALL:
        score = success ? 42 : -20;
        break;
      case CLASSIC_ROLES.DASH_CALL:
        score = success ? (isGameOver ? 23 : 33) : (isGameOver ? -10 : -20);
        break;
      case CLASSIC_ROLES.REG_DASH:
        score = success ? (isGameOver ? 13 : 23) : (isGameOver ? -won : -10);
        break;
      case CLASSIC_ROLES.WIZZ_RISK:
        score = success ? (bid + 13 + 10 + 10) : -(miss + 10 + 10);
        break;
      case CLASSIC_ROLES.CALLER:
      case CLASSIC_ROLES.WIZZ:
        if (success) { score = bid + 13 + 10; }
        else { score = -(miss + (isDeepUnder ? 20 : 10)); }
        break;
      case CLASSIC_ROLES.RISK:
        score = success ? (bid + 13 + 10) : -(miss + 10);
        break;
      case CLASSIC_ROLES.NORMAL:
      default:
        score = success ? (bid + 13) : -miss;
        break;
    }

    if (success && input.isSoleWinner) score += 10;
    if (!success && input.isSoleLoser) score = Math.max(score * 2, -22);
    return score;
  }

  /** A Super Call is any winning/locked bid of 8+ tricks — defined by the
   *  bid amount alone, true in both a normal round's ordinary auction and a
   *  fast round's forced-trump override. Only the Caller themselves can
   *  hold this role; a matching With player stays WIZZ/WIZZ_RISK regardless
   *  of the Caller's Super Call status. */
  function classicRoleFor(bidType, bidAmount, isCaller, isWith, isRisk) {
    if (bidType === "DASHCALL") return CLASSIC_ROLES.DASH_CALL;
    if (bidType === "DASH") return CLASSIC_ROLES.REG_DASH;
    if (isCaller && bidAmount >= 8) return CLASSIC_ROLES.SUPER_CALL;
    if (isWith && isRisk) return CLASSIC_ROLES.WIZZ_RISK;
    if (isCaller) return CLASSIC_ROLES.CALLER;
    if (isWith) return CLASSIC_ROLES.WIZZ;
    if (isRisk) return CLASSIC_ROLES.RISK;
    return CLASSIC_ROLES.NORMAL;
  }

  /** Rules §2.3: estimation runs from the seat after the Caller to the
   *  seat before the Caller; the Risk obligation passes backward past
   *  any pre-bidding Dash Call seat to the previous eligible (actually
   *  estimating) player. A DASHCALL bid never estimates, so it's
   *  excluded here regardless of what any caller-supplied riskPlayerId
   *  claims — see ScoringEngine.md § Open Rule Questions #1. */
  function computeRiskPlayerId(callerId, bids, order) {
    if (!callerId) return null;
    var seats = [], cur = nextSeat(callerId, order);
    for (var i = 0; i < order.length - 1; i++) { seats.push(cur); cur = nextSeat(cur, order); }
    var estimators = seats.filter(function (id) { var b = bids[id]; return b && (b.type === "TRICKS" || b.type === "DASH"); });
    return estimators.length ? estimators[estimators.length - 1] : null;
  }

  /** Pure calculation — no persistence. input:
   *  { round, turnOrder, bids:{id:{type,amount}}, tricksWon:{id:n},
   *    callerId, withPlayers:[ids], multiplier, riskPlayerId?, scoringMode?,
   *    escalationCap? }
   *  bids[id].type is one of "DASHCALL" | "DASH" | "TRICKS". A missing
   *  entry (no bid on record) scores 0 and is flagged in breakdown[id].notes
   *  — should not happen for a genuinely completed round.
   *  riskPlayerId is optional — when the caller (table-engine.js) already
   *  knows who the real last bidder was (tracked explicitly in
   *  biddingState.lastBidderId, which also covers fast rounds with no
   *  caller at all), pass it through here so scoring and the UI's ⚡ Risk
   *  badge always agree. Falls back to the caller-relative formula only
   *  when not provided.
   *  scoringMode: "normal" (default) | "classic" — Classic is a genuinely
   *  different per-player formula (calculateClassicScore), not a variant
   *  of Normal's; see ScoringEngine.md. escalationCap defaults to 8
   *  (Normal's ×8 Sa'ayda ceiling) — the caller passes 2 for Classic. */
  function calculateRoundScore(input) {
    var order = input.turnOrder && input.turnOrder.length ? input.turnOrder : seatOrder();
    var bids = input.bids || {};
    var withPlayers = input.withPlayers || [];
    var total = totalBidAmount(bids);
    var diff = Math.abs(13 - total);
    var isOver = total > 13;
    var riskValue = riskValueFor(diff);
    var riskPlayerId = input.riskPlayerId != null ? input.riskPlayerId : computeRiskPlayerId(input.callerId, bids, order);

    var successCount = 0;
    order.forEach(function (id) { if (succeeded(bids[id], input.tricksWon[id] || 0)) successCount++; });
    var failedCount = order.length - successCount;
    var isSaayda = successCount === 0; // rules §4 "Escalation Round (Sa'ayda)"
    var isClassic = input.scoringMode === "classic";

    var deltas = {}, breakdown = {};
    order.forEach(function (id) {
      var bid = bids[id], T = input.tricksWon[id] || 0;
      var isCaller = id === input.callerId;
      var isWith = withPlayers.indexOf(id) !== -1;
      var isRisk = id === riskPlayerId;
      var win = succeeded(bid, T);
      var isSoleWinner = successCount === 1 && win;
      var isSoleLoser = failedCount === 1 && !win;
      var delta = 0; var notes = []; var role = null;

      if (isSaayda) {
        notes.push("Sa'ayda — round zeroed for everyone");
      } else if (!bid) {
        notes.push("No bid on record for this seat — scored 0 (see ScoringEngine.md Open Rule Questions)");
      } else if (isClassic) {
        // Classic Calculations mode — a genuinely different formula per
        // role, not a variant of Normal mode's. See calculateClassicScore.
        role = classicRoleFor(bid.type, bid.amount, isCaller, isWith, isRisk);
        var classicBid = (bid.type === "DASHCALL" || bid.type === "DASH") ? 0 : bid.amount;
        delta = calculateClassicScore({
          role: role, bid: classicBid, won: T, totalBids: total,
          isSoleWinner: isSoleWinner, isSoleLoser: isSoleLoser
        });
        notes.push("Classic " + role + " " + (win ? "success" : "failure") + " → " + delta);
      } else if (bid.type === "DASHCALL") {
        var dcBase = win ? (isOver ? 25 : 33) : (isOver ? -25 : -33);
        delta = dcBase; notes.push("Dash Call " + (win ? "success" : "failure") + " (" + (isOver ? "Over" : "Under") + ") base " + (dcBase > 0 ? "+" : "") + dcBase);
        if (isSoleWinner) { delta += 10; notes.push("sole winner +10"); }
        if (isSoleLoser) { delta -= 10; notes.push("sole loser -10"); }
        // Dash Call NEVER receives Risk (rules §4) — intentionally no isRisk branch here.
      } else if (bid.type === "DASH") {
        if (win) { delta = 10; notes.push("Normal Dash success +10"); }
        else { delta = -(10 + T); notes.push("Normal Dash failure -(10+" + T + ")"); }
        // Normal Dash is "an ordinary estimate of 0" under the Standard Player
        // formula (rules §4) — the Caller/With bonus applies here exactly as
        // it does in the TRICKS branch below. This was previously omitted.
        if (isCaller || isWith) { delta += win ? 10 : -10; notes.push((isCaller ? "Caller" : "With") + " " + (win ? "+10" : "-10")); }
        if (isSoleWinner) { delta += 10; notes.push("sole winner +10"); }
        if (isSoleLoser) { delta -= 10; notes.push("sole loser -10"); }
        if (isRisk && riskValue) { delta += win ? riskValue : -riskValue; notes.push("Risk " + (win ? "+" : "-") + riskValue); }
      } else { // TRICKS
        if (win) { delta = 10 + bid.amount; notes.push("Win base +" + (10 + bid.amount)); }
        else { delta = -Math.abs(T - bid.amount); notes.push("Loss base -" + Math.abs(T - bid.amount)); }
        if (isCaller || isWith) { delta += win ? 10 : -10; notes.push((isCaller ? "Caller" : "With") + " " + (win ? "+10" : "-10")); }
        if (isSoleWinner) { delta += 10; notes.push("sole winner +10"); }
        if (isSoleLoser) { delta -= 10; notes.push("sole loser -10"); }
        if (isRisk && riskValue) { delta += win ? riskValue : -riskValue; notes.push("Risk " + (win ? "+" : "-") + riskValue); }
      }

      var multiplier = isSaayda ? 1 : (input.multiplier || 1); // rules: a Sa'ayda round scores zero, not "zero × multiplier"
      var finalDelta = delta * multiplier;
      deltas[id] = finalDelta;
      breakdown[id] = { raw: delta, multiplier: multiplier, final: finalDelta, isCaller: isCaller, isWith: isWith, isRisk: isRisk, isSoleWinner: isSoleWinner, isSoleLoser: isSoleLoser, succeeded: win, role: role, notes: notes };
    });

    // Sa'ayda ladder — confirmed ruling: ×2→×4→×6→×8 on consecutive all-fail
    // rounds (Normal mode; Classic mode caps at ×2 instead of ×8 — same
    // ladder, different ceiling), resets to ×1 the instant any round has a
    // successful player. `input.multiplier` is the CURRENT round's applied
    // multiplier, which is 1 at baseline (no prior escalation) — treating
    // that 1 as "0 escalation steps so far" (not "add 2 to 1") is required
    // to actually land on ×2 for the first escalation rather than ×3.
    var cap = input.escalationCap != null ? input.escalationCap : 8;
    var priorSteps = (input.multiplier || 1) === 1 ? 0 : (input.multiplier || 1);
    var nextMultiplier = isSaayda ? Math.min(priorSteps + 2, cap) : 1;

    return {
      round: input.round, totalBids: total, diff: diff, isOver: isOver,
      riskValue: riskValue, riskPlayerId: riskPlayerId,
      successCount: successCount, failedCount: failedCount, isSaayda: isSaayda,
      appliedMultiplier: isSaayda ? 1 : (input.multiplier || 1), nextMultiplier: nextMultiplier,
      deltas: deltas, breakdown: breakdown
    };
  }

  /** THE single persistence entry point — call exactly once per round,
   *  right after the round's final trick resolves. Guards against being
   *  called twice for the same round number (idempotent no-op on a
   *  repeat call), bumps match scores, stores the round summary, and
   *  arms the next round's multiplier. Does NOT navigate or touch
   *  playState — table-engine.js still owns that via GameSession.completeRound(). */
  function applyRoundResult(result, meta) {
    meta = meta || {};
    var last = GameSession.getLastRoundResult();
    if (last && last.round === result.round && last.scoreDeltas) {
      console.warn("[ScoringEngine] applyRoundResult called again for round " + result.round + " — ignoring duplicate call.");
      return last;
    }
    var current = GameSession.getMatchScores();
    var updated = {};
    Object.keys(current).forEach(function (id) { updated[id] = (current[id] || 0) + (result.deltas[id] || 0); });
    GameSession.setMatchScores(updated);

    var entry = {
      trump: meta.trump != null ? meta.trump : GameSession.getRound().trump,
      callerId: meta.callerId != null ? meta.callerId : result.riskPlayerId && GameSession.getRound().callerId,
      tricksWon: meta.tricksWon || {},
      estimates: meta.estimates || {},
      scoreDeltas: result.deltas,
      breakdown: result.breakdown,
      riskPlayerId: result.riskPlayerId,
      totalBids: result.totalBids,
      isOver: result.isOver,
      isSaayda: result.isSaayda,
      appliedMultiplier: result.appliedMultiplier,
      nextMultiplier: result.nextMultiplier
    };
    GameSession.recordRoundResult(entry);
    GameSession.setRound({ multiplier: result.nextMultiplier });
    return entry;
  }

  /** Sum of every persisted round's score deltas for each player, i.e.
   *  GameSession's running match totals. GameSession.matchScores IS the
   *  single source of truth — this is a read-only convenience wrapper
   *  so callers go through ScoringEngine rather than reaching into
   *  GameSession directly, per this task's integration requirement. */
  function calculateMatchScore() { return GameSession.getMatchScores(); }

  /** Rules §1: "Number of Players: 4 (individual play, no partnerships)."
   *  The official document defines no team concept and no team-scoring
   *  formula. Returning null rather than inventing one — see
   *  ScoringEngine.md § Open Rule Questions #2. */
  function calculateTeamScore() { return null; }

  global.ScoringEngine = {
    calculateRoundScore: calculateRoundScore,
    applyRoundResult: applyRoundResult,
    calculateMatchScore: calculateMatchScore,
    calculateTeamScore: calculateTeamScore,
    computeRiskPlayerId: computeRiskPlayerId, // exposed for tests/inspection
    calculateClassicScore: calculateClassicScore, // exposed for tests/inspection (per-player Classic formula)
    classicRoleFor: classicRoleFor
  };
})(window);
