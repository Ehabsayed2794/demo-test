/* ════════════════════════════════════════════════════════════════════
   Estimation — Centralized Game State Manager
   Single source of truth for "what state is the app in, where did the
   player come from, and where can they go next." Screens call GameState
   instead of hardcoding location.href / window.location.href.
   See GameState.md for the full state diagram + transition table.
   No networking, no backend — mock data only. Persists per-tab via
   sessionStorage so state survives real page navigation between the
   project's separate HTML files.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  var STATES = Object.freeze({
    SPLASH: "Splash",
    LOGIN: "Login",
    LOBBY: "Lobby",
    GAME_MODE_SELECTION: "GameModeSelection",
    CREATE_ROOM: "CreateRoom",
    JOIN_ROOM: "JoinRoom",
    MATCHMAKING: "Matchmaking",
    WAITING_ROOM: "WaitingRoom",
    BIDDING: "Bidding",
    GAMEPLAY: "Gameplay",
    ROUND_FINISHED: "RoundFinished",
    FINAL_STANDINGS: "FinalStandings",
    SHOP: "Shop",
    PROFILE: "Profile",
    SETTINGS: "Settings",
    DISCONNECTED: "Disconnected",
    LOADING: "Loading"
  });

  var STATES_SET = {};
  Object.keys(STATES).forEach(function (k) { STATES_SET[STATES[k]] = true; });

  // Default screen file per state. null = no screen built yet for this
  // state (tracked in GameState.md as a pending screen). Call sites may
  // override with opts.file when one state maps to more than one screen
  // (e.g. GameModeSelection is represented by either the Ranked Match
  // or Play vs AI screen, depending on the mode chosen in the Lobby).
  var STATE_SCREEN = {
    Splash: "MADE - Logo & Loading.html",
    Login: null,
    Lobby: "Estimation Lobby v2.html",
    GameModeSelection: null,
    CreateRoom: "Estimation Room.html",
    JoinRoom: null,
    Matchmaking: "Estimation Ranked Match.html",
    WaitingRoom: "Estimation Room.html",
    Bidding: "Estimation Bidding Phase.html",
    Gameplay: "Estimation Game Table.html",
    RoundFinished: "Estimation Game Table.html",
    FinalStandings: "Estimation Final Standings.html",
    Shop: "Estimation Shop.html",
    Profile: null,
    Settings: "Estimation Settings.html",
    Disconnected: null,
    Loading: null
  };

  // Allowed transitions FROM each state. Kept in one place so no screen
  // re-implements its own "where can I go" logic.
  var TRANSITIONS = {
    Splash: ["Loading", "Login", "Lobby"],
    Login: ["Lobby"],
    Lobby: ["GameModeSelection", "CreateRoom", "Shop", "Settings", "Profile", "Loading"],
    GameModeSelection: ["CreateRoom", "JoinRoom", "Matchmaking", "Lobby"],
    CreateRoom: ["WaitingRoom", "Bidding", "Lobby", "Disconnected"],
    JoinRoom: ["WaitingRoom", "Lobby", "Disconnected"],
    Matchmaking: ["GameModeSelection", "Bidding", "Lobby", "Disconnected"],
    WaitingRoom: ["Bidding", "Lobby", "Disconnected"],
    Bidding: ["Gameplay", "Disconnected"],
    Gameplay: ["RoundFinished", "Disconnected"],
    RoundFinished: ["Bidding", "FinalStandings"],
    FinalStandings: ["WaitingRoom", "Lobby"],
    Shop: ["Lobby"],
    Profile: ["Lobby"],
    Settings: ["Lobby"],
    Disconnected: ["Lobby", "Matchmaking", "WaitingRoom"],
    Loading: ["Lobby", "Splash"]
  };

  var STORAGE_KEY = "estimation_game_state_v1";

  function defaultData() {
    return {
      player: { id: "p1", name: "Khaled_X", avatar: "Y", rank: "Gold III", rp: 1240, coins: 2400, gems: 120 },
      room: { code: null, host: false, seats: [] },
      lastResult: null
    };
  }

  function loadPersisted() {
    try {
      var raw = sessionStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  function persist() {
    try { sessionStorage.setItem(STORAGE_KEY, JSON.stringify(store)); } catch (e) {}
  }

  function deepMerge(base, patch) {
    var out = Object.assign({}, base);
    Object.keys(patch || {}).forEach(function (k) {
      if (patch[k] && typeof patch[k] === "object" && !Array.isArray(patch[k]) && base[k]) {
        out[k] = Object.assign({}, base[k], patch[k]);
      } else {
        out[k] = patch[k];
      }
    });
    return out;
  }

  var store = loadPersisted() || { current: STATES.SPLASH, previous: null, history: [], data: defaultData() };
  persist();

  function canTransition(from, to) {
    var allowed = TRANSITIONS[from] || [];
    return allowed.indexOf(to) !== -1;
  }

  function setData(partial) {
    store.data = deepMerge(store.data, partial);
    persist();
  }

  function getData() { return store.data; }
  function getState() { return store.current; }
  function getPrevious() { return store.previous; }
  function getHistory() { return store.history.slice(); }

  /**
   * Move to a new state. Validates the transition against TRANSITIONS
   * unless opts.force is set. Navigates to the state's screen (or
   * opts.file, for states with more than one possible screen) unless
   * opts.noNavigate is set (used for sub-state changes within the same
   * loaded page, e.g. Matchmaking search view inside Ranked Match.html).
   */
  function goTo(to, opts) {
    opts = opts || {};
    if (!STATES_SET[to]) { console.error("[GameState] Unknown state:", to); return false; }
    if (!opts.force && !canTransition(store.current, to)) {
      console.warn("[GameState] Invalid transition:", store.current, "->", to);
      return false;
    }
    if (opts.data) setData(opts.data);
    store.history.push(store.current);
    store.previous = store.current;
    store.current = to;
    persist();
    var file = opts.file || STATE_SCREEN[to];
    if (file && !opts.noNavigate) window.location.href = file;
    return true;
  }

  /** Return to the previous state in history (used by Back buttons that
   *  don't have one fixed destination). */
  function back(opts) {
    opts = opts || {};
    var prev = store.history.pop();
    if (!prev) return false;
    store.previous = store.current;
    store.current = prev;
    persist();
    var file = opts.file || STATE_SCREEN[prev];
    if (file && !opts.noNavigate) window.location.href = file;
    return true;
  }

  /** Called by a screen on load to declare "this is where we are now."
   *  Self-healing: if the persisted state doesn't already match (e.g.
   *  the file was opened directly, or the tab was refreshed), it syncs
   *  without re-validating the transition — this is bookkeeping, not a
   *  player-initiated move. */
  function sync(state, data) {
    if (!STATES_SET[state]) { console.error("[GameState] Unknown state:", state); return; }
    if (store.current !== state) {
      store.previous = store.current;
      store.history.push(store.current);
      store.current = state;
    }
    if (data) setData(data);
    persist();
  }

  function reset() {
    store = { current: STATES.SPLASH, previous: null, history: [], data: defaultData() };
    persist();
  }

  global.GameState = {
    STATES: STATES,
    STATE_SCREEN: STATE_SCREEN,
    TRANSITIONS: TRANSITIONS,
    goTo: goTo,
    back: back,
    sync: sync,
    canTransition: function (to) { return canTransition(store.current, to); },
    getState: getState,
    getPrevious: getPrevious,
    getHistory: getHistory,
    getData: getData,
    setData: setData,
    reset: reset
  };
})(window);
