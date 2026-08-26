# Estemshan Debug APK — Installation Guide

**Release:** 1.2

**Android versionCode:** 3

**Package:** `com.estemshan.game`

**Build type:** Debug APK

**Scope:** Casual testing only; this build is not a ranked-release or production-device certification.

## English

### 1. Download the APK

Download the artifact named **`estemshan-debug-apk`** from the successful `apk-debug` GitHub Actions run. Extract the downloaded artifact ZIP and locate the `.apk` file inside.

### 2. Install on an Android device

Transfer the APK to the Android device, open it from the Files or Downloads app, and approve installation. If Android blocks the installation, open the prompt’s settings and allow the Files or browser application used for the download to install unknown apps. Return to the APK and select **Install**.

Android may display a warning because this is a debug build rather than a store-distributed release. Continue only if the APK was downloaded from the project’s trusted GitHub Actions artifact.

### 3. Open the app

After installation, open **Estemshan** from the launcher. This APK loads the live production site at launch and **requires an internet connection from startup**. Keep the device online while signing in, joining a room, and playing.

### 4. Troubleshooting

If installation fails, remove an older conflicting debug build and try again, or confirm that the device has sufficient storage and supports the project’s minimum Android SDK. If Android reports a package conflict, uninstall the previous `com.estemshan.game` installation before installing this build. Do not install APKs from an untrusted source.

## العربية

### ١. تنزيل ملف APK

نزّل العنصر **`estemshan-debug-apk`** من تشغيل GitHub Actions الناجح لسير عمل **`apk-debug`**. فك ضغط ملف ZIP الذي تم تنزيله، ثم ابحث عن ملف APK بداخله.

### ٢. التثبيت على جهاز Android

انقل ملف APK إلى جهاز Android، ثم افتحه من تطبيق «الملفات» أو «التنزيلات» ووافق على التثبيت. إذا منع Android التثبيت، افتح إعدادات نافذة التنبيه واسمح للتطبيق المستخدم للتنزيل، مثل تطبيق الملفات أو المتصفح، بتثبيت التطبيقات غير المعروفة. ارجع إلى ملف APK واضغط **تثبيت**.

قد يعرض Android تحذيراً لأن هذا الإصدار مخصص للاختبار وليس إصداراً موزعاً عبر متجر التطبيقات. تابع فقط إذا تم تنزيل الملف من عنصر GitHub Actions موثوق خاص بالمشروع.

### ٣. فتح التطبيق

بعد التثبيت، افتح تطبيق **Estemshan** من قائمة التطبيقات. يقوم هذا الإصدار بتحميل الموقع المباشر عند التشغيل، ولذلك **يتطلب اتصالاً بالإنترنت منذ بدء التطبيق**. حافظ على اتصال الجهاز بالإنترنت أثناء تسجيل الدخول والانضمام إلى الغرفة واللعب.

### ٤. حل المشاكل الشائعة

إذا فشل التثبيت، احذف إصدار الاختبار الأقدم المتعارض ثم حاول مرة أخرى، وتأكد من وجود مساحة تخزين كافية ومن توافق الجهاز مع الحد الأدنى لإصدار Android المطلوب للمشروع. إذا ظهر تعارض في الحزمة، احذف التثبيت السابق للحزمة `com.estemshan.game` قبل تثبيت هذا الإصدار. لا تثبّت ملفات APK من مصادر غير موثوقة.

## Release details

| Field | Value |
|---|---|
| Version name | `1.2` |
| Version code | `3` |
| Application ID | `com.estemshan.game` |
| Artifact | `estemshan-debug-apk` |
| Build workflow | `apk-debug` |
| Certification scope | Casual testing only |
