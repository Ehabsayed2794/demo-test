#!/usr/bin/env bash
# Estemshan Thursday Android runner.
# Owner-gated: run only after the Android SDK, an owner device, and Firebase
# owner setup are available. DRY_RUN=1 validates command order locally.
set -u -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
EVIDENCE_DIR="${EVIDENCE_DIR:-$ROOT/docs/agent-ops/android-smoke}"
DRY_RUN="${DRY_RUN:-0}"
SUMMARY_STATUS="PASS"

fail() {
  echo "FAIL: $*" >&2
  SUMMARY_STATUS="FAIL"
  echo "SUMMARY: FAIL"
  exit 1
}

run_checked() {
  echo "+ $*"
  "$@" || fail "command failed (exit=$?)"
}

echo "=== Estemshan Thursday Android runner ==="
echo "ROOT=$ROOT"
echo "ANDROID_DIR=$ANDROID_DIR"
echo "EVIDENCE_DIR=$EVIDENCE_DIR"
echo "DRY_RUN=$DRY_RUN"

[ -d "$ANDROID_DIR" ] || fail "android project is missing"
[ -x "$ANDROID_DIR/gradlew" ] || fail "android/gradlew is missing or not executable"
[ -f "$ANDROID_DIR/app/src/main/AndroidManifest.xml" ] || fail "AndroidManifest.xml is missing"
grep -Fq '<uses-permission android:name="android.permission.INTERNET" />' "$ANDROID_DIR/app/src/main/AndroidManifest.xml" || fail "INTERNET permission is missing from AndroidManifest.xml"
echo 'PASS: Android project and INTERNET permission guards'

if [ "$DRY_RUN" = "1" ]; then
  echo '--- dry-run: owner-gated Android command order ---'
  echo 'DRY_RUN: would require ANDROID_HOME and JDK 21'
  echo 'DRY_RUN: would run cd android && ./gradlew assembleDebug'
  echo 'DRY_RUN: would locate android/app/build/outputs/apk/debug/app-debug.apk'
  echo 'DRY_RUN: would run adb devices and require an available owner device'
  echo 'DRY_RUN: would run adb install -r android/app/build/outputs/apk/debug/app-debug.apk'
  echo 'DRY_RUN: would run adb shell am start -n com.estemshan.game/.MainActivity'
  echo "DRY_RUN: would pull a post-launch screenshot to $EVIDENCE_DIR/after-launch.png"
  echo "DRY_RUN: would pull adb logcat to $EVIDENCE_DIR/logcat.txt"
  echo 'PASS: dry-run Android command sequence'
  echo 'SUMMARY: PASS (DRY_RUN=1; no device commands executed)'
  exit 0
fi

[ -n "${ANDROID_HOME:-}" ] || fail 'ANDROID_HOME is empty; set it to the Android SDK root'
[ -d "$ANDROID_HOME" ] || fail "ANDROID_HOME does not exist: $ANDROID_HOME"
command -v adb >/dev/null 2>&1 || fail 'adb is not on PATH'
command -v java >/dev/null 2>&1 || fail 'java is not on PATH'
command -v javac >/dev/null 2>&1 || fail 'javac is not on PATH; install JDK 21'
java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
[ "$java_major" = "21" ] || fail "JDK 21 required; detected Java major ${java_major:-unknown}"

run_checked bash -c "cd '$ANDROID_DIR' && ./gradlew assembleDebug"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || fail "APK was not produced at $APK"
mkdir -p "$EVIDENCE_DIR"
echo "APK_PATH=$APK"
echo "APK_SHA256=$(sha256sum "$APK" | awk '{print $1}')"

DEVICES_OUTPUT="$(adb devices 2>&1)" || fail 'adb devices failed'
printf '%s\n' "$DEVICES_OUTPUT"
DEVICE_COUNT="$(printf '%s\n' "$DEVICES_OUTPUT" | awk '$2 == "device" {count++} END {print count+0}')"
[ "$DEVICE_COUNT" -gt 0 ] || fail 'no authorized device is available'
run_checked adb install -r "$APK"
run_checked adb shell am start -n com.estemshan.game/.MainActivity
adb exec-out screencap -p > "$EVIDENCE_DIR/after-launch.png" || fail 'could not pull post-launch screenshot'
adb logcat -d > "$EVIDENCE_DIR/logcat.txt" || fail 'could not pull logcat'
echo "SCREENSHOT_PATH=$EVIDENCE_DIR/after-launch.png"
echo "LOGCAT_PATH=$EVIDENCE_DIR/logcat.txt"
echo 'MANUAL_NEXT: use EMAIL/GUEST sign-in only, reach Lobby, then rerun with SCREENSHOT_NAME=lobby to capture the Lobby screen.'
echo "SUMMARY: $SUMMARY_STATUS"
