import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.estemshan.game',
  appName: 'Estemshan',
  webDir: 'hosting-dist',
  server: {
    // Load the LIVE production site instead of bundled assets: the game
    // deploys continuously via GitHub Actions, so a bundled copy goes stale.
    // The WebView lands on the *.web.app origin, which Firebase Auth
    // already authorizes. Internet required (game is online-only anyway).
    url: 'https://made---estimation-card-game.web.app',
    cleartext: false
  }
};

export default config;
