# Human QA — the `qa` build and Firebase App Distribution

**Audience:** the owner (one-time setup) and the human testers (install + play).

**What the `qa` build is.** It is the app compiled against the **REAL Firebase
project** (`made---estimation-card-game`) — the same backend release uses —
but debuggable and signed with the standard Android debug key, so it needs no
Play listing and no production keystore. It is *not* the debug build: debug
points at the Firebase Emulator Suite on `10.0.2.2`, which exists only inside
an emulator and would make the app look dead on a real phone.

| | debug | **qa** | release |
| --- | --- | --- | --- |
| Firebase project | dummy `demo-test-ci` (emulator) | **real** | real |
| Package name | `com.estemshan.game` | `com.estemshan.game.qa` | `com.estemshan.game` |
| Signed with | AGP debug key | AGP debug key | production keystore |
| Where it runs | emulator / developer device | **tester devices** | Play Store |

The `.qa` package suffix is deliberate: it lets the qa build sit **next to**
the debug build on one device. The two hold different backends, so if one
overwrote the other the surviving app would silently talk to the wrong
project. CI asserts the suffix on every build (`aapt2 dump packagename` in
`.github/workflows/android.yml`, `BuildVariantWiringTest` in `:app`).

## One-time setup (owner, Firebase console + repo secrets)

1. **Enable App Distribution.** Firebase console → the project
   `made---estimation-card-game` → **App Distribution** (left nav). Accept the
   terms. It is a free service with no server of ours — that is why it fits
   decision D3.
2. **Create the tester group.** App Distribution → **Testers & Groups** tab →
   New group → name it exactly **`estemshan-qa`** (CI passes this alias to
   `--groups`). Add the human testers' Google emails. Adding a tester emails
   them an invite; a tester accepts once and keeps the group's builds forever.
3. **Create the service credential.** Firebase console → **Project settings →
   Service accounts** → Firebase App Distribution Admin → **Generate new
   private key** → download the JSON. This account may upload builds and
   nothing else.
4. **Add two repository secrets** (Settings → Secrets and variables → Actions):
   - `FIREBASE_APP_DISTRIBUTION_CREDENTIALS` — the JSON file's contents.
     Paste the JSON as-is, or base64-encode it first
     (`base64 -w0 service-account.json` on Linux, `base64 -i file.json` on
     macOS) — CI accepts either and detects which one it got.
   - `FIREBASE_APP_ID` — the **Android app id** from Project settings → Your
     apps → the `com.estemshan.game` app, shaped `1:261597513798:android:…`.
     Do not confuse it with the Android *package name*; CI already knows the
     package name from the build.

Until these exist, the `qa-distribute` job builds and verifies the APK, saves
it to the run's artifacts, and **skips** the upload with a warning instead of
failing. The next main push after step 4 distributes the build — that run is
the upload's own verification.

## How a tester installs (once per device)

1. Install **Firebase App Tester** from the Play Store (Google's app; it is
   the only supported installer for App Distribution builds).
2. Open the **invite email** on the device (the one sent in setup step 2) and
   tap the link. It opens App Tester — accept the invite there.
3. In App Tester, **Estemshan** appears under the `estemshan-qa` group. Tap
   **Install**. Allow App Tester to install unknown apps when Android asks.
4. Open **Estemshan** from the launcher. The app needs an internet connection
   from startup — it signs in and reads/writes matches against the real
   project.

New main pushes land in App Tester automatically; the tester taps **Update**
in App Tester when a new build arrives. Bug reports should name the build
number shown in App Tester. If a build fails to install, uninstall the
previous `com.estemshan.game.qa` first — the two package names are
deliberately separate, so a debug or release build on the same device is not
a conflict, but two qa builds are.

## Billing and ads: test IDs never reach a tester

**As of this build there are no ad or in-app-purchase identifiers anywhere in
the native app** — no AdMob, no RevenueCat keys, no product IDs. Nothing in
the `qa` build can bill a tester or serve a real ad. If a qa build ever asks
for payment or displays an ad, that is a bug to file, not a test to complete.

When the plan adds ads and "Remove Ads" (RevenueCat, free tier), the rule is:

- **Test IDs** (Google Play's reserved test SKUs, AdMob/RevenueCat *test*
  units) belong in the **debug** build only. They are compile-time constants
  gated on `BuildConfig`, never on a runtime flag a tester could flip.
- **Real IDs** belong in **release** — and in `qa` only if a payment path is
  deliberately being verified, which must be a separate, owner-approved step.
- The split is asserted the same way the Firebase split is: a per-variant JVM
  test in `:app` that fails the build if a test ID appears in a non-debug
  variant.

The reason is the same as the emulator/project split this story fixes: a
tester's device is a real device on a real network, so a build that reaches it
must be the real backend with none of the local-only scaffolding.

## Where things live

| Artifact | `estemshan-qa-apk` (the run's GitHub Actions artifacts) |
| --- | --- |
| Job | `qa-distribute` in `.github/workflows/android.yml` |
| Cadence | every push to `main`; PRs build + verify only |
| Gradle task | `:app:assembleQa` |
| Tester group alias | `estemshan-qa` |
| Install doc for the debug APK (older flow) | `docs/release/INSTALL_APK.md` |
