# Estemshan — Play Store Preparation Draft

**Document status:** Internal draft for owner review; not a store submission and not a release-readiness claim.
**Application ID:** `com.estemshan.game`
**Current native display name:** `Estemshan`
**Firebase project:** `made---estimation-card-game`
**Last updated:** 2026-08-25

> This document is a preparation pack only. The final Play Console declarations, privacy-policy URL, age/content rating, data-safety answers, screenshots, and production release artifacts require owner review and live verification.

## App Name Proposals

### English

| Option | Name | Positioning note |
| --- | --- | --- |
| EN-1 | **Estemshan: Egyptian Card Game** | Clear category and regional identity; strongest search clarity. |
| EN-2 | **Estemshan — Bid Boldly** | Uses the existing product voice and is easy to remember. |
| EN-3 | **Estemshan Multiplayer** | Emphasizes the core social product. |
| EN-4 | **Estemshan: Strategy at the Table** | Highlights strategic play rather than a generic card game. |
| EN-5 | **Estemshan Cards & Bids** | Directly communicates the bid-and-play loop. |

### Arabic

| Option | Name | Positioning note |
| --- | --- | --- |
| AR-1 | **استيمشان: لعبة الورق المصرية** | Clear Arabic category plus local identity. |
| AR-2 | **استيمشان — العب بجرأة** | Localizes the existing “Bid boldly” voice. |
| AR-3 | **استيمشان متعددة اللاعبين** | Emphasizes multiplayer. |
| AR-4 | **استيمشان: تحدي الورق والاستراتيجية** | Communicates strategic competition. |
| AR-5 | **استيمشان: نداءات وطُرُق** | Draft terminology option; confirm with the rules owner before publishing. |

**Recommended starting names:** English `Estemshan: Egyptian Card Game`; Arabic `استيمشان: لعبة الورق المصرية`. The final name must be checked for Play Console length, trademark, and marketplace availability.

## Short Descriptions

### English

> Bid boldly and play sharp in Estemshan, a competitive Egyptian multiplayer strategy card game.

### Arabic

> راهن بجرأة والعب بذكاء في استيمشان، لعبة الورق المصرية التنافسية متعددة اللاعبين.

These are drafts. They avoid promising ranked fairness or competitive integrity beyond what the current implementation can prove.

## Full Description Draft

### English

**Estemshan is a competitive Egyptian strategy card game built around bold bidding, sharp estimates, and live table play.** Create or join a private room, ready up with friends, and follow the hand from bidding through tricks and scoring.

Choose your calls carefully, read the table, and adapt to the round. The game includes a dedicated Login and Lobby experience, private-room multiplayer flow, live match synchronization, bidding and trick-play surfaces, score and completion states, and rematch preparation.

Estemshan is currently being prepared for casual-readiness verification. Some features remain under active development, and ranked/public-trust claims are intentionally not made in this draft. Internet access and an account or guest session are required for online play. Google sign-in may be unavailable inside the Android WebView during the initial device phase; email/password and guest paths are the supported Android smoke-test paths.

### Arabic

**استيمشان لعبة ورق مصرية تنافسية تعتمد على النداءات الجريئة والتقدير الدقيق واللعب المباشر على الطاولة.** أنشئ غرفة خاصة أو انضم إلى أصدقائك، واستعدوا معًا، ثم تابعوا الجولة من النداءات إلى لعب الورق والنتيجة.

اختر نداءك بعناية، واقرأ مجريات الطاولة، وتكيّف مع كل جولة. تتضمن اللعبة تجربة تسجيل الدخول والردهة، والغرف الخاصة متعددة اللاعبين، والمزامنة المباشرة للمباراة، وشاشات النداءات ولعب الورق، وحالات النتيجة وإنهاء المباراة، وتجهيز الإعادة.

يجري حاليًا إعداد استيمشان للتحقق من الجاهزية للاستخدام العادي. بعض الميزات ما زالت قيد التطوير، ولا يتضمن هذا الوصف أي ادعاء بجاهزية التصنيف أو الثقة العامة. يلزم الاتصال بالإنترنت وحساب أو جلسة ضيف للعب عبر الإنترنت. قد لا يتوفر تسجيل الدخول عبر Google داخل WebView في إصدار Android الأول؛ ومسارا البريد الإلكتروني/كلمة المرور والضيف هما المساران المدعومان لاختبار الجهاز.

## Content Rating Questionnaire — Draft Answers

These are draft answers for owner review, not final declarations. The owner must answer the Play Console questionnaire from the actual release build and current policy prompts.

| Topic | Draft answer | Rationale / verification note |
| --- | --- | --- |
| Product category | Multiplayer card/strategy game | The product centers on bidding, tricks, and scoring. |
| Gambling or gambling simulation | **No real-money gambling; no gambling simulation intended** | No bets, wagers, casino mechanics, or monetary prizes are present in the prepared scope. Confirm against the final build. |
| Purchases | No in-app purchases currently implemented | The current preparation scope has no purchase flow. Recheck before submission. |
| Ads | No ads SDK currently implemented | No ad network is part of the current dependency or product scope. Recheck final dependencies. |
| Violence | None | The card-game experience has no combat or violent depiction. |
| Sexual content or nudity | None | No such content is part of the product scope. |
| Profanity or crude humor | None intentionally authored | User-generated content is not currently a shipped feature in the prepared scope. |
| User interaction | Online multiplayer interaction is present | Players can create/join private rooms and exchange gameplay state. Use the final questionnaire’s exact multiplayer-interaction category. |
| Personal information | Email and display name may be collected | Required for account/profile flows; guest use may avoid a permanent email account. Confirm final auth behavior. |
| Location, contacts, microphone, camera | Not requested by current product scope | Voice PTT is not implemented in this preparation sprint. Recheck manifest/runtime permissions for the final build. |
| Data sharing | No third-party advertising or purchase SDK sharing planned | Firebase services process account/gameplay data; complete the final Data Safety form accurately. |
| Age target | 13+ is a draft audience target, not a final rating | The owner must complete the official content-rating flow; do not substitute this draft for the generated rating. |
| App access | Internet connection required for online rooms and matches | Confirm offline behavior and guest path on the final Android build. |

## Data Safety and Privacy Policy Skeleton

### Data categories currently expected

**Email address:** collected when a player chooses email/password account creation or sign-in. It is used for authentication and account recovery.
**Display name:** collected during profile/account setup and shown in the game lobby or player-facing surfaces.
**Gameplay data:** room membership, match identifiers, bidding/action records, match progress, scores, completion state, and rematch-related state may be stored to provide multiplayer synchronization and results.

### Data not currently planned

No advertising SDK, purchase SDK, precise location, contacts, camera, microphone, or voice recording is part of this preparation scope. The voice PTT spike is not implemented. Do not claim these exclusions if a later build adds them.

### Processing and retention draft

Data is processed through the application’s Firebase Authentication and Firestore-backed multiplayer services. The final policy must identify the legal entity/controller, Firebase/Google as relevant service providers, retention/deletion behavior, account deletion contact, support contact, children’s privacy position, international processing, and security practices. The current repository does not establish all legal-policy details; those fields remain owner/legal review items.

### User controls draft

Players should be able to use the guest path where supported, update their display name through the profile flow where supported, and contact the owner for account/data deletion or correction. The final product must provide a real support/contact route before submission; this document does not invent one.

### Privacy policy skeleton

1. **Who we are.** Identify the publisher/operator of Estemshan and provide a support contact.
2. **What we collect.** Describe email address, display name, gameplay and synchronization data, technical logs if any, and optional guest-session identifiers.
3. **Why we use it.** Authentication, profile display, room/match synchronization, scoring, security, abuse prevention, diagnostics, and support.
4. **Service providers.** Identify Firebase Authentication, Firestore, Hosting, and any other provider present in the final dependency/build configuration.
5. **Sharing.** State whether data is shared with providers to operate the service, and state that no ads or sales SDK is currently included if that remains true.
6. **Retention.** Define account, gameplay, logs, and deletion timelines. This is not yet repository-authoritative and needs owner decision.
7. **Security.** Describe authentication controls, Firestore Rules, transport security, and the limits of client-authoritative gameplay. Do not promise cheat prevention that the current architecture cannot guarantee.
8. **Children.** State the intended audience and whether the service is directed to children. Obtain legal review before submission.
9. **International transfers.** Describe the applicable provider regions and legal mechanism after the publisher confirms them.
10. **Choices and rights.** Explain access, correction, deletion, and support requests, including response process.
11. **Changes.** Explain how policy changes will be announced.
12. **Contact.** Insert the final support/privacy contact and published policy URL.

## Screenshot Shotlist

The following set is intended for the final store listing and device certification. Capture screenshots from the real build; do not use emulator/test-only artifacts in the store listing.

| Shot | Screen/state | Required evidence |
| --- | --- | --- |
| 1 | Login — English | Product identity, email/password path, guest path, no debug text. |
| 2 | Login — Arabic | RTL direction and official Arabic terms; use the W4 i18n slice only after final visual review. |
| 3 | Lobby | Player identity, private-room CTA, ranked card, and visual hierarchy. |
| 4 | Private room / roster | Room identifier treatment, joined players, ready state. |
| 5 | Bidding | Caller/With or auction state rendered clearly; no test hooks visible. |
| 6 | Trick table | Cards, turn indicator, trump/suit presentation, and readable table state. |
| 7 | Score / round result | Scores and round outcome without debug overlays. |
| 8 | Match completion | Final status, winner/result presentation, and rematch entry point. |
| 9 | Rematch | Vote state and fresh-match transition, only after live verification. |

**Android capture requirements:** portrait/landscape orientation must match the final manifest decision; capture Login and Lobby on the owner’s device, remove status/debug overlays where appropriate, and record device model/API level outside the store image metadata.

## Exact Firebase Authorized-Domain Checklist

The owner must verify the final list in Firebase Console before enabling browser-based authentication methods. The exact production domain is derived from the configured Firebase project and Hosting target.

| Domain/origin | Purpose | Status |
| --- | --- | --- |
| `made---estimation-card-game.web.app` | Production Firebase Hosting | Required; owner must add/verify. |
| `made---estimation-card-game.firebaseapp.com` | Firebase Hosting/auth default domain | Required/expected default; owner must verify. |
| `localhost` | Local web development/auth emulator-adjacent browser work | Development only; verify whether needed in the project console. |
| `127.0.0.1` | Local static/emulator browser rehearsal | Development only; add only if Firebase Console accepts and local flow requires it. |
| `https://localhost` | Capacitor WebView origin used by native shell | Verify against the final Capacitor/Firebase Auth behavior; Google popup is expected not to work in the initial WebView phase. |
| Any owner-controlled custom domain | Future public/support/store link | Add only after the owner selects the exact hostname. |

Do not add arbitrary domains. The final authorized-domain list must match the actual deployed web origin, the chosen native-auth strategy, and the owner’s Firebase Console configuration.

## Release Checklist

1. Owner authenticates Firebase CLI and selects `made---estimation-card-game`.
2. Firestore Rules SHA is verified before Rules deployment.
3. Hosting artifact is built and deployed; the live URL matrix returns expected results.
4. Two-context casual smoke passes with one synchronized action per client; created test document IDs are recorded and later cleaned by the owner if desired.
5. Android SDK/API 36, accepted licenses, `adb`, APK build, device installation, email/guest authentication, and Lobby smoke all pass.
6. Final privacy policy URL and support contact are published.
7. Play Console Data Safety and content-rating answers are completed from the final build.
8. Store screenshots are captured from the final owner-verified build.
9. Release notes, version code/name, signing configuration, and rollback plan are recorded.
10. Only after the above does the owner decide whether casual release is appropriate. This pack does not authorize ranked/public-trust release.
