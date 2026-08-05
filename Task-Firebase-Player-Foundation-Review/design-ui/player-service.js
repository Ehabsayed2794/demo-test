/* ════════════════════════════════════════════════════════════════════
   Estimation — Player Service (Sprint 2.6: Firebase Player Foundation)
   The ONLY place in design-ui that reads or writes a players/{uid}
   Firestore document. Screens must call these functions — never
   firebase.firestore() directly — per docs/architecture/ServiceArchitecture.md.

   Scope: player-profile bootstrap only. No rooms, no matches, no
   multiplayer sync. Field shape and lifecycle rules follow
   docs/architecture/FirestoreSchema.md ("players/{uid}") and
   docs/architecture/PlayerLifecycle.md exactly — see
   docs/implementation/FirebasePlayerFoundation.md for the full writeup,
   including the honest limitation on the "protected fields" below.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  // New-player starter grant — defined once, here, per PlayerLifecycle.md
  // ("a fixed small grant... defined once in PlayerService, not scattered
  // across call sites").
  var STARTER_COINS = 500;
  var STARTER_GEMS = 10;

  // Fields a client is allowed to touch via updatePlayerProfile(). Everything
  // else — coins, gems, rank, rp, wins, streak, level — is PROTECTED: see
  // docs/architecture/SecurityArchitecture.md's "protected fields" note.
  // This whitelist is enforced here AND independently by firestore.rules;
  // neither layer trusts the other alone (defense in depth, not redundancy
  // for its own sake — a bug in one layer shouldn't be a total bypass).
  var ALLOWED_UPDATE_FIELDS = ["displayName", "avatarInitial", "lastSeenAt", "currentRoomId", "currentMatchId"];

  function db() {
    return global.Db || null;
  }

  function serverTimestamp() {
    return firebase.firestore.FieldValue.serverTimestamp();
  }

  /** Derive new-profile defaults from a Firebase Auth user object. Pure —
   *  no I/O, no Firestore access — safe to call just to preview a shape. */
  function mapAuthUserToProfileDefaults(user) {
    var name = (user.displayName || "Player").trim() || "Player";
    return {
      displayName: name,
      accountType: user.isAnonymous ? "guest" : "full",
      email: user.email || null,
      avatarInitial: name.charAt(0).toUpperCase(),
      rank: "Unranked",
      rp: 0,
      wins: 0,
      streak: 0,
      level: 1,
      coins: STARTER_COINS,
      gems: STARTER_GEMS,
      currentRoomId: null,
      currentMatchId: null
    };
  }

  /** Read a player's profile document. Resolves null if it doesn't exist
   *  yet (not an error) — rejects only for a genuine Firestore failure. */
  function getPlayerProfile(uid) {
    if (!db()) return Promise.reject(new Error("PlayerService: Firestore is not initialized on this page."));
    return db().collection("players").doc(uid).get().then(function (snap) {
      return snap.exists ? snap.data() : null;
    });
  }

  /** THE single centralized profile bootstrap. Call after every successful
   *  sign-in (anonymous, email/password, google, account creation).
   *
   *  - First-ever call for a uid: creates the profile once, with the
   *    approved defaults above.
   *  - Every later call: loads the EXISTING profile untouched except for
   *    stamping lastSeenAt — never re-applies defaults over real
   *    progression (see docs/architecture/PlayerLifecycle.md, "Returning
   *    User").
   *  - Idempotent and race-safe: uses a Firestore transaction so two
   *    concurrent calls for the same brand-new uid (double-tab, a refresh
   *    that races the first call) cannot create two profiles or clobber
   *    each other — one wins the create, the other observes the result
   *    and just touches lastSeenAt like any other returning-user call.
   *  - Never throws for "Firestore unavailable" in a way that should block
   *    the caller from proceeding offline — that's the CALLER's decision
   *    (see login/index.html's usage), this function just surfaces the
   *    error clearly via a rejected promise. */
  function ensurePlayerProfile(user) {
    if (!user || !user.uid) return Promise.reject(new Error("ensurePlayerProfile: no authenticated user."));
    if (!db()) return Promise.reject(new Error("PlayerService: Firestore is not initialized on this page."));
    var ref = db().collection("players").doc(user.uid);
    return db().runTransaction(function (tx) {
      return tx.get(ref).then(function (snap) {
        if (snap.exists) {
          tx.update(ref, { lastSeenAt: serverTimestamp() });
          return { created: false };
        }
        var profile = mapAuthUserToProfileDefaults(user);
        profile.createdAt = serverTimestamp();
        profile.lastSeenAt = serverTimestamp();
        tx.set(ref, profile);
        return { created: true };
      });
    }).then(function () {
      // serverTimestamp() is a write-time sentinel, not a real value until
      // the next read — re-read once so the caller gets real Timestamp
      // values instead of the FieldValue placeholder object.
      return ref.get();
    }).then(function (snap) {
      return snap.data();
    });
  }

  /** Update only the fields a client is allowed to touch. Any other key in
   *  `changes` is silently dropped — see ALLOWED_UPDATE_FIELDS above. */
  function updatePlayerProfile(uid, changes) {
    if (!db()) return Promise.reject(new Error("PlayerService: Firestore is not initialized on this page."));
    var safe = {};
    Object.keys(changes || {}).forEach(function (key) {
      if (ALLOWED_UPDATE_FIELDS.indexOf(key) !== -1) safe[key] = changes[key];
    });
    if (!Object.keys(safe).length) return Promise.resolve();
    return db().collection("players").doc(uid).update(safe);
  }

  /** Live-subscribe to a player's own profile document. Returns an
   *  unsubscribe function. Failures are delivered to callback(null, err)
   *  rather than thrown, so a UI screen can render a controlled error
   *  state instead of an uncaught exception. */
  function subscribeToPlayerProfile(uid, callback) {
    if (!db()) {
      callback(null, new Error("PlayerService: Firestore is not initialized on this page."));
      return function unsubscribe() {};
    }
    return db().collection("players").doc(uid).onSnapshot(
      function (snap) { callback(snap.exists ? snap.data() : null, null); },
      function (err) { callback(null, err); }
    );
  }

  global.PlayerService = {
    ensurePlayerProfile: ensurePlayerProfile,
    getPlayerProfile: getPlayerProfile,
    updatePlayerProfile: updatePlayerProfile,
    subscribeToPlayerProfile: subscribeToPlayerProfile,
    mapAuthUserToProfileDefaults: mapAuthUserToProfileDefaults
  };
})(window);
