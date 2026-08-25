#!/usr/bin/env bash
# Estemshan Thursday deployment kit.
# Owner-gated: do not run against production until Firebase CLI auth/project
# selection has been completed. DRY_RUN=1 validates local guards and command
# order without contacting Firebase or the live site.
set -u -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

PROJECT_ID="made---estimation-card-game"
EXPECTED_RULES_SHA="a7cfba9833c324581047e16d498bae7e8a9ed4b54250723cd5a30f45209f001f"
BASE_URL="${BASE_URL:-https://made---estimation-card-game.web.app}"
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

run_capture_checked() {
  local label="$1"
  shift
  local output status
  echo "+ $*"
  output=$("$@" 2>&1)
  status=$?
  printf '%s\n' "$output"
  [ "$status" -eq 0 ] || fail "$label failed (exit=$status)"
  printf '%s' "$output"
}

echo "=== Estemshan Thursday deployment kit ==="
echo "ROOT=$ROOT"
echo "PROJECT_ID=$PROJECT_ID"
echo "BASE_URL=$BASE_URL"
echo "DRY_RUN=$DRY_RUN"

echo "--- guard: accepted Rules hash ---"
[ -f firestore.rules ] || fail "firestore.rules is missing"
ACTUAL_RULES_SHA="$(sha256sum firestore.rules | awk '{print $1}')"
echo "RULES_SHA256=$ACTUAL_RULES_SHA"
[ "$ACTUAL_RULES_SHA" = "$EXPECTED_RULES_SHA" ] || fail "Rules SHA mismatch; expected $EXPECTED_RULES_SHA, got $ACTUAL_RULES_SHA"

echo "--- guard: configured Firebase project ---"
CONFIGURED_PROJECT_ID="$(grep -oE 'projectId:[[:space:]]*\"[^\"]+\"' design-ui/firebase-init.js | head -1 | sed -E 's/.*\"([^\"]+)\"/\1/')"
echo "CONFIGURED_PROJECT_ID=$CONFIGURED_PROJECT_ID"
[ "$CONFIGURED_PROJECT_ID" = "$PROJECT_ID" ] || fail "firebase-init project mismatch; expected $PROJECT_ID, got $CONFIGURED_PROJECT_ID"

echo "PASS: immutable local guards"

if [ "$DRY_RUN" = "1" ]; then
  echo "--- dry-run: owner-gated command order ---"
  echo "DRY_RUN: would run npx firebase login:list and require an authorized account"
  echo "DRY_RUN: would run npx firebase projects:list"
  echo "DRY_RUN: would run npx firebase use and require active project $PROJECT_ID"
  echo "DRY_RUN: would run npx firebase deploy --only firestore:rules --project $PROJECT_ID"
  echo "DRY_RUN: would run npm run build:hosting"
  echo "DRY_RUN: would run npx firebase deploy --only hosting --project $PROJECT_ID"
  for path in / /login/index.html /match-service.js /engine/bidding-engine.js /lobby/uploads/Ranked%20Match.png /estemshan/; do
    echo "DRY_RUN: would curl $BASE_URL$path"
  done
  echo "PASS: dry-run command sequence"
  echo "SUMMARY: PASS (DRY_RUN=1; no owner-gated commands executed)"
  exit 0
fi

echo "--- verify Firebase authorization ---"
LOGIN_OUTPUT="$(npx firebase login:list 2>&1)" || fail "firebase login:list failed"
printf '%s\n' "$LOGIN_OUTPUT"
printf '%s' "$LOGIN_OUTPUT" | grep -Fq "No authorized accounts" && fail "no authorized Firebase account; run firebase login"
echo "PASS: authorized Firebase account detected"

PROJECTS_OUTPUT="$(npx firebase projects:list 2>&1)" || fail "firebase projects:list failed"
printf '%s\n' "$PROJECTS_OUTPUT"
printf '%s' "$PROJECTS_OUTPUT" | grep -Fq "$PROJECT_ID" || fail "required project $PROJECT_ID was not listed by firebase projects:list"
echo "PASS: required Firebase project is accessible"

USE_OUTPUT="$(npx firebase use 2>&1)" || fail "firebase use failed; run firebase use --add"
printf '%s\n' "$USE_OUTPUT"
printf '%s' "$USE_OUTPUT" | grep -Fq "$PROJECT_ID" || fail "active/default Firebase project is not $PROJECT_ID; run firebase use --add"
echo "PASS: active/default project is $PROJECT_ID"

run_checked npx firebase deploy --only firestore:rules --project "$PROJECT_ID"
run_checked npm run build:hosting
run_checked npx firebase deploy --only hosting --project "$PROJECT_ID"

echo "--- live curl matrix ---"
for path in / /login/index.html /match-service.js /engine/bidding-engine.js /lobby/uploads/Ranked%20Match.png /estemshan/; do
  url="$BASE_URL$path"
  status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 "$url")" || fail "curl failed for $url"
  echo "$path => $status"
  case "$status" in
    2??|3??) ;;
    *) fail "unexpected HTTP status $status for $path" ;;
  esac
done

echo "SUMMARY: $SUMMARY_STATUS"
