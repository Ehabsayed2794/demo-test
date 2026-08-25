param(
  [switch]$DryRun,
  [string]$BaseUrl = "https://made---estimation-card-game.web.app"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

$ProjectId = "made---estimation-card-game"
$ExpectedRulesSha = "a7cfba9833c324581047e16d498bae7e8a9ed4b54250723cd5a30f45209f001f"

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

Write-Host "=== Estemshan Thursday deployment kit ==="
Write-Host "ROOT=$Root"
Write-Host "PROJECT_ID=$ProjectId"
Write-Host "BASE_URL=$BaseUrl"
Write-Host "DRY_RUN=$DryRun"

if (-not (Test-Path "firestore.rules")) { Fail "firestore.rules is missing" }
$ActualRulesSha = (Get-FileHash -Algorithm SHA256 firestore.rules).Hash.ToLowerInvariant()
Write-Host "RULES_SHA256=$ActualRulesSha"
if ($ActualRulesSha -ne $ExpectedRulesSha) {
  Fail "Rules SHA mismatch; expected $ExpectedRulesSha, got $ActualRulesSha"
}

$FirebaseInit = Get-Content -Raw design-ui/firebase-init.js
$ProjectMatch = [regex]::Match($FirebaseInit, 'projectId:\s*["'']([^"'']+)["'']')
if (-not $ProjectMatch.Success) { Fail "could not read projectId from design-ui/firebase-init.js" }
$ConfiguredProjectId = $ProjectMatch.Groups[1].Value
Write-Host "CONFIGURED_PROJECT_ID=$ConfiguredProjectId"
if ($ConfiguredProjectId -ne $ProjectId) {
  Fail "firebase-init project mismatch; expected $ProjectId, got $ConfiguredProjectId"
}
Write-Host "PASS: immutable local guards"

if ($DryRun) {
  Write-Host "--- dry-run: owner-gated command order ---"
  Write-Host "DRY_RUN: would run npx firebase login:list and require an authorized account"
  Write-Host "DRY_RUN: would run npx firebase projects:list"
  Write-Host "DRY_RUN: would run npx firebase use and require active project $ProjectId"
  Write-Host "DRY_RUN: would run npx firebase deploy --only firestore:rules --project $ProjectId"
  Write-Host "DRY_RUN: would run npm run build:hosting"
  Write-Host "DRY_RUN: would run npx firebase deploy --only hosting --project $ProjectId"
  @("/", "/login/index.html", "/match-service.js", "/engine/bidding-engine.js", "/lobby/uploads/Ranked%20Match.png", "/estemshan/") | ForEach-Object {
    Write-Host "DRY_RUN: would curl $BaseUrl$_"
  }
  Write-Host "PASS: dry-run command sequence"
  Write-Host "SUMMARY: PASS (DRY_RUN=1; no owner-gated commands executed)"
  exit 0
}

$LoginOutput = & npx firebase login:list 2>&1
if ($LASTEXITCODE -ne 0) { Fail "firebase login:list failed (exit=$LASTEXITCODE)" }
$LoginOutput | Write-Host
if (($LoginOutput -join "`n") -match "No authorized accounts") { Fail "no authorized Firebase account; run firebase login" }
Write-Host "PASS: authorized Firebase account detected"

$ProjectsOutput = & npx firebase projects:list 2>&1
if ($LASTEXITCODE -ne 0) { Fail "firebase projects:list failed (exit=$LASTEXITCODE)" }
$ProjectsOutput | Write-Host
if (($ProjectsOutput -join "`n") -notmatch [regex]::Escape($ProjectId)) { Fail "required project $ProjectId was not listed" }
Write-Host "PASS: required Firebase project is accessible"

$UseOutput = & npx firebase use 2>&1
if ($LASTEXITCODE -ne 0) { Fail "firebase use failed; run firebase use --add" }
$UseOutput | Write-Host
if (($UseOutput -join "`n") -notmatch [regex]::Escape($ProjectId)) { Fail "active/default project is not $ProjectId; run firebase use --add" }
Write-Host "PASS: active/default project is $ProjectId"

Run-Checked "npx" @("firebase", "deploy", "--only", "firestore:rules", "--project", $ProjectId)
Run-Checked "npm" @("run", "build:hosting")
Run-Checked "npx" @("firebase", "deploy", "--only", "hosting", "--project", $ProjectId)

Write-Host "--- live curl matrix ---"
@("/", "/login/index.html", "/match-service.js", "/engine/bidding-engine.js", "/lobby/uploads/Ranked%20Match.png", "/estemshan/") | ForEach-Object {
  $Url = "$BaseUrl$_"
  try {
    $Response = Invoke-WebRequest -Uri $Url -UseBasicParsing -MaximumRedirection 5
    $Status = [int]$Response.StatusCode
  } catch {
    if ($_.Exception.Response) { $Status = [int]$_.Exception.Response.StatusCode } else { Fail "curl failed for $Url" }
  }
  Write-Host "$_ => $Status"
  if ($Status -lt 200 -or $Status -ge 400) { Fail "unexpected HTTP status $Status for $_" }
}
Write-Host "SUMMARY: PASS"
