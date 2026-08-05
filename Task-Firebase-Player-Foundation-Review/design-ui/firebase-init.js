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
  window.Auth = firebase.auth();
  // window.Db is only defined if firebase-firestore-compat.js was loaded
  // on this page — screens that don't need Firestore (none yet touch it
  // besides Login's player-profile bootstrap) don't have to include it.
  window.Db = (typeof firebase.firestore === "function") ? firebase.firestore() : null;
})();
