#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EMULATOR_PORT="${AETHER_FIRESTORE_EMULATOR_PORT:-8085}"
PROJECT_ID="${AETHER_FIRESTORE_EMULATOR_PROJECT_ID:-aether-identity-emulator-gate}"
EMULATOR_LOG=""
EMULATOR_PID=""

cleanup() {
  if [[ -n "$EMULATOR_PID" ]] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
    kill "$EMULATOR_PID" 2>/dev/null || true
    wait "$EMULATOR_PID" 2>/dev/null || true
  fi
  if [[ -n "$EMULATOR_LOG" ]]; then
    rm -f "$EMULATOR_LOG"
  fi
}
trap cleanup EXIT INT TERM

case "$EMULATOR_PORT" in
  ''|*[!0-9]*) echo "AETHER_FIRESTORE_EMULATOR_PORT must be numeric" >&2; exit 2 ;;
esac
if (( EMULATOR_PORT < 1 || EMULATOR_PORT > 65535 )); then
  echo "AETHER_FIRESTORE_EMULATOR_PORT must be between 1 and 65535" >&2
  exit 2
fi
case "$PROJECT_ID" in
  aether-identity-emulator-*) ;;
  *) echo "Refusing to reset a project outside the aether-identity-emulator-* test prefix" >&2; exit 2 ;;
esac
if [[ "$PROJECT_ID" == *prod* ]]; then
  echo "Refusing to use a production-like project ID for the emulator gate" >&2
  exit 2
fi

command -v gcloud >/dev/null 2>&1 || {
  echo "gcloud is required; install the cloud-firestore-emulator component" >&2
  exit 1
}
command -v curl >/dev/null 2>&1 || {
  echo "curl is required for emulator readiness checks" >&2
  exit 1
}

EMULATOR_LOG="$(mktemp -t aether-firestore-emulator.XXXXXX.log)"
gcloud emulators firestore start \
  --host-port="127.0.0.1:$EMULATOR_PORT" \
  --project="$PROJECT_ID" \
  --quiet >"$EMULATOR_LOG" 2>&1 &
EMULATOR_PID=$!

RESET_URL="http://127.0.0.1:$EMULATOR_PORT/emulator/v1/projects/$PROJECT_ID/databases/(default)/documents"
READY=false
for _ in $(seq 1 120); do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "Firestore emulator exited during startup" >&2
    sed -n '1,200p' "$EMULATOR_LOG" >&2
    exit 1
  fi
  if curl --fail --silent --show-error --request DELETE "$RESET_URL" >/dev/null 2>&1; then
    READY=true
    break
  fi
  sleep 0.5
done
if [[ "$READY" != true ]]; then
  echo "Firestore emulator did not become ready within 60 seconds" >&2
  sed -n '1,200p' "$EMULATOR_LOG" >&2
  exit 1
fi

env \
  FIRESTORE_EMULATOR_HOST="127.0.0.1:$EMULATOR_PORT" \
  AETHER_FIRESTORE_EMULATOR_PROJECT_ID="$PROJECT_ID" \
  "$ROOT_DIR/gradlew" \
  :aether-auth-firestore:firestoreEmulatorTest \
  --dependency-verification=strict \
  --no-daemon \
  --stacktrace
