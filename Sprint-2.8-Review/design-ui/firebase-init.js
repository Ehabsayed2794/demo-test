// Shared Firebase App + Auth + Firestore singletons for all design-ui
// screens. Classic (non-module) script — works when a screen's HTML is
// opened directly via file://, not just served over http://, since ES
// module imports are blocked from the file:// origin. Requires
// firebase-app-compat.js, firebase-auth-compat.js, and
// firebase-firestore-compat.js to be loaded first (see login/index.html
// for the include order).
(function () {
  var firebaseConfig = {
    apiKey: "AIzaSyAOX64y02461r7oJomYavmOowi9Eyze7KU",
    authDomain: "made---estimation-card-game.firebaseapp.com",
    projectId: "made---estimation-card-game",
    storageBucket: "made---estimation-card-game.firebasestorage.app",
    messagingSenderId: "261597513798",
    appId: "1:261597513798:web:f4c76b4371b46e2777a247",
    measurementId: "G-3W5KWK1XRB",
  };
  firebase.initializeApp(firebaseConfig);
  // window.Auth and window.Db are each only defined if their matching
  // compat script was loaded on this page — a screen that only needs one
  // of the two (e.g. Lobby, Sprint 2.8: Firestore only, never Auth
  // directly) doesn't have to include the other, and doesn't crash for
  // skipping it.
  window.Auth = (typeof firebase.auth === "function") ? firebase.auth() : null;
  window.Db = (typeof firebase.firestore === "function") ? firebase.firestore() : null;
})();
