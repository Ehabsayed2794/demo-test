param(
  [switch]$DryRun,
  [string]$EvidenceDir = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$AndroidDir = Join-Path $Root "android"
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) { $EvidenceDir = Join-Path $Root "docs/agent-ops/android-smoke" }

function Fail([string]$Message) {
  Write-Host "FAIL: $Message" -ForegroundColor Red
  Write-Host "SUMMARY: FAIL"
  exit 1
}
function Run-Checked([string]$File, [string[]]$Arguments) {
  Write-Host "+ $File $($Arguments -join ' ')"
  & $File @Arguments
  if ($LASTEXITCODE -ne 0) { Fail "$File failed (exit=$LASTEXITCODE)" }
}

Write-Host "=== Estemshan Thursday Android runner ==="
Write-Host "ROOT=$Root"
Write-Host "ANDROID_DIR=$AndroidDir"
Write-Host "EVIDENCE_DIR=$EvidenceDir"
Write-Host "DRY_RUN=$DryRun"

$Manifest = Join-Path $AndroidDir "app/src/main/AndroidManifest.xml"
if (-not (Test-Path $AndroidDir)) { Fail "android project is missing" }
if (-not (Test-Path (Join-Path $AndroidDir "gradlew"))) { Fail "android/gradlew is missing" }
if (-not (Test-Path $Manifest)) { Fail "AndroidManifest.xml is missing" }
if (-not ((Get-Content -Raw $Manifest) -match '<uses-permission android:name="android.permission.INTERNET"')) { Fail "INTERNET permission is missing from AndroidManifest.xml" }
Write-Host "PASS: Android project and INTERNET permission guards"

if ($DryRun) {
  Write-Host "--- dry-run: owner-gated Android command order ---"
  Write-Host "DRY_RUN: would require ANDROID_HOME and JDK 21"
  Write-Host "DRY_RUN: would run cd android && ./gradlew assembleDebug"
  Write-Host "DRY_RUN: would locate android/app/build/outputs/apk/debug/app-debug.apk"
  Write-Host "DRY_RUN: would run adb devices and require an available owner device"
  Write-Host "DRY_RUN: would run adb install -r android/app/build/outputs/apk/debug/app-debug.apk"
  Write-Host "DRY_RUN: would run adb shell am start -n com.estemshan.game/.MainActivity"
  Write-Host "DRY_RUN: would pull a post-launch screenshot to $EvidenceDir/after-launch.png"
  Write-Host "DRY_RUN: would pull adb logcat to $EvidenceDir/logcat.txt"
  Write-Host "PASS: dry-run Android command sequence"
  Write-Host "SUMMARY: PASS (DRY_RUN=1; no device commands executed)"
  exit 0
}

if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) { Fail "ANDROID_HOME is empty; set it to the Android SDK root" }
if (-not (Test-Path $env:ANDROID_HOME)) { Fail "ANDROID_HOME does not exist: $env:ANDROID_HOME" }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "adb is not on PATH" }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Fail "java is not on PATH" }
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { Fail "javac is not on PATH; install JDK 21" }
$JavaVersion = (& java -version 2>&1 | Select-String 'version "([0-9]+)' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -First 1)
if ($JavaVersion -ne "21") { Fail "JDK 21 required; detected Java major $JavaVersion" }

Push-Location $AndroidDir
try { Run-Checked ".\\gradlew.bat" @("assembleDebug") } finally { Pop-Location }
$Apk = Join-Path $AndroidDir "app/build/outputs/apk/debug/app-debug.apk"
if (-not (Test-Path $Apk)) { Fail "APK was not produced at $Apk" }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
Write-Host "APK_PATH=$Apk"
Write-Host "APK_SHA256=$((Get-FileHash -Algorithm SHA256 $Apk).Hash.ToLowerInvariant())"
$Devices = & adb devices 2>&1
if ($LASTEXITCODE -ne 0) { Fail "adb devices failed (exit=$LASTEXITCODE)" }
$Devices | Write-Host
$DeviceCount = @($Devices | Select-String '\sdevice$').Count
if ($DeviceCount -lt 1) { Fail "no authorized device is available" }
Run-Checked "adb" @("install", "-r", $Apk)
Run-Checked "adb" @("shell", "am", "start", "-n", "com.estemshan.game/.MainActivity")
Run-Checked "adb" @("exec-out", "screencap", "-p") | Set-Content -Encoding Byte (Join-Path $EvidenceDir "after-launch.png")
Run-Checked "adb" @("logcat", "-d") | Set-Content (Join-Path $EvidenceDir "logcat.txt")
Write-Host "SCREENSHOT_PATH=$(Join-Path $EvidenceDir 'after-launch.png')"
Write-Host "LOGCAT_PATH=$(Join-Path $EvidenceDir 'logcat.txt')"
Write-Host "MANUAL_NEXT: use EMAIL/GUEST sign-in only, reach Lobby, then capture the Lobby screenshot."
Write-Host "SUMMARY: PASS"
