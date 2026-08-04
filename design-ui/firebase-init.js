// Shared Firebase App + Auth singleton for all design-ui screens.
// Loaded as an ES module (<script type="module">) — see Estimation Login.html.
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import {
  getAuth,
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  signInAnonymously,
  signInWithPopup,
  GoogleAuthProvider,
  sendPasswordResetEmail,
  updateProfile,
  onAuthStateChanged,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";

const firebaseConfig = {
  apiKey: "AIzaSyAOX64y02461r7oJomYavmOowi9Eyze7KU",
  authDomain: "made---estimation-card-game.firebaseapp.com",
  projectId: "made---estimation-card-game",
  storageBucket: "made---estimation-card-game.firebasestorage.app",
  messagingSenderId: "261597513798",
  appId: "1:261597513798:web:f4c76b4371b46e2777a247",
  measurementId: "G-3W5KWK1XRB",
};

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);

export {
  auth,
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  signInAnonymously,
  signInWithPopup,
  GoogleAuthProvider,
  sendPasswordResetEmail,
  updateProfile,
  onAuthStateChanged,
};
