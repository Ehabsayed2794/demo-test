/* ════════════════════════════════════════════════════════════════════
   Estimation — SessionService (Sprint 2.9: Session Foundation)
   The application's central session/cache layer: current authenticated
   uid, cached player profile, and authentication state. This is the
   ONLY module that watches Firebase Auth's state — every other screen
   (Lobby, and future screens) consumes SessionService instead of
   touching Auth or Firestore directly.

   PlayerService remains the only Firestore CRUD layer — SessionService
   calls it internally (read-only: getPlayerProfile, never
   ensurePlayerProfile — profile CREATION stays Login's job via the
   Sprint 2.6 bootstrap; SessionService only ever reads/caches what
   already exists, so there is exactly one path that creates a profile,
   not two). See docs/implementation/SessionService.md.

   Sprint 3.4.1 (Match Start Consistency & Security Hotfix): added
   setCurrentMatchId(matchId) — a SELF-ONLY write, always targeting
   `currentUser.uid` (the signed-in Auth user THIS module already
   tracks), never an externally-passed uid. This exists because
   MatchService.startMatch() previously tried to write currentMatchId
   onto EVERY room player's own players/{uid} document, but
   players/{uid}'s rules are (and remain) owner-only — only the
   initiating player's own write could ever succeed; every other
   player's write was silently swallowed as "non-fatal." The fix moves
   responsibility for this field to wherever the write can actually
   succeed: each client updates its OWN copy, after discovering the
   match via the room (rooms/{roomId}.matchId / matches/{matchId}), not
   via a write some OTHER client attempted on its behalf. See
   docs/implementation/MatchInitialization.md's Sprint 3.4.1 section for
   the full "authoritative source" writeup — rooms/{roomId}.matchId and
   matches/{matchId}.players are the authoritative multiplayer state;
   players/{uid}.currentMatchId is now explicitly a same-user
   convenience mirror only, never a source of truth.

   Requires firebase-auth-compat.js and firebase-firestore-compat.js to
   already be loaded (via firebase-init.js) on any page that includes
   this file, and player-service.js loaded alongside it.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  var currentUser = null;     // raw Firebase Auth user object, or null
  var currentProfile = null;  // cached PlayerService profile, or null
  var subscribers = [];
  var initialized = false;

  function safeInvoke(callback) {
    try { callback({ user: currentUser, profile: currentProfile }); }
    catch (err) { console.error("[SessionService] a subscriber callback threw:", err); }
  }

  function notifyAll() {
    subscribers.forEach(safeInvoke);
  }

  /** Load (read-only) the profile for a given Auth user and cache it.
   *  Fail-open: any PlayerService error just leaves the profile cache
   *  null and still notifies subscribers — a Firestore outage never
   *  blocks or crashes anything relying on SessionService. */
  function loadProfileFor(user) {
    if (!user || !global.PlayerService) {
      currentProfile = null;
      notifyAll();
      return;
    }
    global.PlayerService.getPlayerProfile(user.uid).then(function (profile) {
      currentProfile = profile; // may legitimately be null — "missing profile" is a valid state, not an error
      notifyAll();
    }).catch(function (err) {
      console.error("[SessionService] Failed to load the player profile (fail-open, session stays usable):", err);
      currentProfile = null;
      notifyAll();
    });
  }

  /** Wire the single Auth-state listener this whole module exists to
   *  centralize. Safe to call more than once — only the first call does
   *  anything. If Auth isn't available at all (SDK didn't load), the
   *  session simply stays signed-out rather than throwing. */
  function init() {
    if (initialized) return;
    initialized = true;
    if (!global.Auth) {
      console.warn("[SessionService] window.Auth is not available — session will remain signed-out.");
      return;
    }
    global.Auth.onAuthStateChanged(function (user) {
      currentUser = user || null;
      if (!currentUser) {
        currentProfile = null;
        notifyAll();
        return;
      }
      loadProfileFor(currentUser);
    });
  }

  function getCurrentUser() { return currentUser; }
  function getCurrentProfile() { return currentProfile; }
  function isLoggedIn() { return !!currentUser; }

  /** Re-fetch the current user's profile from PlayerService and update
   *  the cache. Resolves the (possibly unchanged) cached profile even on
   *  failure — callers never need a separate error branch just to keep
   *  going. No-ops (resolves null) if nobody is signed in. */
  function refresh() {
    if (!currentUser) return Promise.resolve(null);
    if (!global.PlayerService) return Promise.resolve(currentProfile);
    return global.PlayerService.getPlayerProfile(currentUser.uid).then(function (profile) {
      currentProfile = profile;
      notifyAll();
      return currentProfile;
    }).catch(function (err) {
      console.error("[SessionService] refresh() failed (fail-open, keeping the previous cached profile):", err);
      return currentProfile;
    });
  }

  /** Sprint 3.4.1: self-only update of the SIGNED-IN user's own
   *  currentMatchId. Deliberately takes no uid parameter at all — the
   *  target is always `currentUser.uid`, so it is structurally
   *  impossible for a caller to (accidentally or otherwise) name any
   *  other player's uid through this API. Delegates to
   *  PlayerService.updatePlayerProfile (the existing, unmodified public
   *  API — players/{uid}'s owner-only rule is satisfied because the
   *  write's uid always equals the writer's own auth uid), then
   *  refreshes the cache exactly like refresh() does. No-ops (resolves
   *  the current cache unchanged) if nobody is signed in or
   *  PlayerService isn't loaded — same fail-open shape as refresh(). */
  function setCurrentMatchId(matchId) {
    if (!currentUser) return Promise.resolve(currentProfile);
    if (!global.PlayerService) return Promise.resolve(currentProfile);
    return global.PlayerService.updatePlayerProfile(currentUser.uid, { currentMatchId: matchId }).then(function () {
      return refresh();
    }).catch(function (err) {
      console.error("[SessionService] setCurrentMatchId() failed (fail-open, keeping the previous cached profile):", err);
      return currentProfile;
    });
  }

  /** Reset the local session cache. This does NOT call Firebase Auth's
   *  signOut() — clear() is a local cache reset only; actually signing a
   *  user out is a future integration point once a logout UI exists
   *  (deliberately out of this sprint's scope — see
   *  docs/implementation/SessionService.md). */
  function clear() {
    currentUser = null;
    currentProfile = null;
    notifyAll();
  }

  /** Subscribe to session changes. Fires immediately with the current
   *  state (so a late subscriber doesn't have to wait for the next auth
   *  event to get a value), then again on every user/profile change.
   *  Returns an unsubscribe function. */
  function subscribe(callback) {
    if (typeof callback !== "function") return function unsubscribe() {};
    subscribers.push(callback);
    safeInvoke(callback);
    return function unsubscribe() {
      var idx = subscribers.indexOf(callback);
      if (idx !== -1) subscribers.splice(idx, 1);
    };
  }

  init();

  global.SessionService = {
    getCurrentUser: getCurrentUser,
    getCurrentProfile: getCurrentProfile,
    isLoggedIn: isLoggedIn,
    refresh: refresh,
    setCurrentMatchId: setCurrentMatchId,
    clear: clear,
    subscribe: subscribe
  };
})(window);
