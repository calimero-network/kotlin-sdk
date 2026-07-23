#!/usr/bin/env bash
#
# run-app.sh — build & launch the Mero sample-app on an Android emulator so you can
# click through it (login → home → run RPC → logout). This is the interactive
# app, NOT the test suite (see test-all.sh for tests).
#
# Every run is a CLEAN SLATE for the node: it stops and DELETES the local node,
# then boots a brand-new node (fresh admin creds + empty state) on
# http://localhost:4001 (reachable from the emulator as http://10.0.2.2:4001).
# Sign in with the admin creds (default dev / dev-password). The node is left
# running after the script exits so the app keeps working.
#
# Use --mock for the tiny in-app-mock flow the instrumented tests drive (no node).
#
# Usage:
#   ./run-app.sh                      # fresh node + explorer on 10.0.2.2:4001
#   ./run-app.sh --admin-user me --admin-pass s3cret
#   ./run-app.sh --mock               # in-app mock flow (no node) — log in with anything
#   ./run-app.sh --no-node            # don't manage a node (use one you booted yourself)
#   ./run-app.sh --port 4001          # node server port (swarm = port+1)
#   ./run-app.sh --serial emulator-5554   # target a specific running emulator
#   ./run-app.sh --logs               # stream the app's logcat after launch
#
# Prereqs: JDK 17, Android SDK with platform-tools (adb) + emulator, and at least
# one AVD created (see TESTING.md §0). merod on PATH for the live-node flow.

set -u
cd "$(dirname "$0")"
REPO_ROOT="$(pwd)"

APP_ID="com.calimero.mero.sample"
APP_ACTIVITY=".MainActivity"

# ---- options ---------------------------------------------------------------
MOCK=0            # 0 = real node (default), 1 = in-app mock backend
STREAM_LOGS=0
MANAGE_NODE=1     # boot/init a local merod node automatically
NODE_PORT=4001
ADMIN_USER="dev"
ADMIN_PASS="dev-password"
SERIAL=""         # explicit emulator serial; empty = auto-pick / launch one
while [ $# -gt 0 ]; do
  case "$1" in
    --live)       MOCK=0 ;;
    --mock)       MOCK=1 ;;
    --no-node)    MANAGE_NODE=0 ;;
    --admin-user) ADMIN_USER="${2:?--admin-user needs a value}"; shift ;;
    --admin-pass) ADMIN_PASS="${2:?--admin-pass needs a value}"; shift ;;
    --port)       NODE_PORT="${2:?--port needs a value}"; shift ;;
    --serial)     SERIAL="${2:?--serial needs a value}"; shift ;;
    --logs)       STREAM_LOGS=1 ;;
    -h|--help)    awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0 ;;
    *) echo "unknown option: $1 (try --help)"; exit 2 ;;
  esac
  shift
done

if [ -t 1 ]; then
  BOLD=$'\033[1m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'
  BLUE=$'\033[34m'; DIM=$'\033[2m'; RESET=$'\033[0m'
else BOLD=""; GREEN=""; YELLOW=""; RED=""; BLUE=""; DIM=""; RESET=""; fi
step() { echo; echo "${BOLD}${BLUE}▶ $*${RESET}"; }
die()  { echo "${RED}✘ $*${RESET}"; exit 1; }

# ---- locate the Android SDK (adb + emulator) -------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
EMULATOR="$(command -v emulator || echo "$SDK/emulator/emulator")"
[ -x "$ADB" ] || die "adb not found — set ANDROID_HOME or add platform-tools to PATH (see TESTING.md §0)"

# The node's host URL as seen from INSIDE the emulator (10.0.2.2 == host loopback).
EMU_NODE_URL="http://10.0.2.2:${NODE_PORT}"

# ---- ensure a local node (init if needed, run, leave it running) -----------
# Reuses a node already answering on :$NODE_PORT; otherwise inits one with the
# given admin creds (rc.17 init-time creds) and runs it in the background. The
# node is left running after this script exits so the app keeps working — stop
# it with:  kill "$(cat .mero-node.pid)"
NODE_HOME="$REPO_ROOT/.mero-node"
ensure_node() {
  if curl -sf "http://localhost:${NODE_PORT}/admin-api/health" >/dev/null 2>&1; then
    echo "${GREEN}node already running${RESET} on :${NODE_PORT} — sign in as ${ADMIN_USER} / ${ADMIN_PASS}"
    return 0
  fi
  command -v merod >/dev/null 2>&1 || {
    echo "${YELLOW}merod not on PATH${RESET} — install it or run with --mock (login will fail without a node)."
    return 1
  }
  if [ ! -d "$NODE_HOME" ]; then
    echo "initializing node (admin: ${ADMIN_USER})…"
    printf '%s' "$ADMIN_PASS" | merod --home "$NODE_HOME" --node app init \
      --server-port "$NODE_PORT" --swarm-port $((NODE_PORT + 1)) \
      --auth-mode embedded --auth-storage persistent \
      --admin-user "$ADMIN_USER" --admin-password-stdin >/dev/null 2>&1 \
      || { echo "${YELLOW}node init failed${RESET}"; return 1; }
  fi
  echo "starting node…"
  merod --home "$NODE_HOME" --node app run > "$REPO_ROOT/.mero-node.log" 2>&1 &
  echo $! > "$REPO_ROOT/.mero-node.pid"
  for _ in $(seq 1 30); do
    if curl -sf "http://localhost:${NODE_PORT}/admin-api/health" >/dev/null 2>&1; then
      echo "${GREEN}node healthy${RESET} on :${NODE_PORT} — sign in as ${ADMIN_USER} / ${ADMIN_PASS}"
      return 0
    fi
    sleep 1
  done
  echo "${YELLOW}node did not become healthy${RESET} — see .mero-node.log"
  return 1
}

if [ "$MOCK" -eq 0 ] && [ "$MANAGE_NODE" -eq 1 ]; then
  step "Fresh start — stopping & wiping node on :${NODE_PORT}"
  [ -f "$REPO_ROOT/.mero-node.pid" ] && kill "$(cat "$REPO_ROOT/.mero-node.pid")" 2>/dev/null || true
  node_pids=$(lsof -ti "tcp:${NODE_PORT}" 2>/dev/null || true)
  [ -n "$node_pids" ] && kill -9 $node_pids 2>/dev/null || true
  pkill -f "merod --home .*mero-node" 2>/dev/null || true
  rm -rf "$REPO_ROOT/.mero-node" "$REPO_ROOT/.mero-node.pid" "$REPO_ROOT/.mero-node.log"
  echo ":${NODE_PORT} freed · node data wiped → a fresh node will be created"

  step "Ensuring a Calimero node on :${NODE_PORT}"
  ensure_node || true
fi

# ---- resolve / boot an emulator --------------------------------------------
step "Resolving an Android emulator"
"$ADB" start-server >/dev/null 2>&1 || true
if [ -z "$SERIAL" ]; then
  SERIAL=$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/{print $1; exit}')
fi
if [ -z "$SERIAL" ]; then
  [ -x "$EMULATOR" ] || die "no running emulator and 'emulator' binary not found — start one manually or pass --serial"
  AVD=$("$EMULATOR" -list-avds 2>/dev/null | head -1)
  [ -n "$AVD" ] || die "no AVD found — create one (Android Studio ▸ Device Manager, or avdmanager) — see TESTING.md §0"
  echo "launching emulator: $AVD"
  "$EMULATOR" -avd "$AVD" -no-boot-anim -netdelay none -netspeed full > "$REPO_ROOT/.emulator.log" 2>&1 &
  "$ADB" wait-for-device
  SERIAL=$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/{print $1; exit}')
fi
[ -n "$SERIAL" ] || die "could not resolve an emulator serial"
echo "emulator: $SERIAL"

# wait for full boot before installing
echo -n "waiting for boot"
for _ in $(seq 1 60); do
  [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { echo " — booted"; break; }
  echo -n "."; sleep 2
done

# ---- build + install -------------------------------------------------------
step "Building & installing sample-app (Debug)"
# ANDROID_SERIAL makes gradle's installDebug + adb target this one emulator.
export ANDROID_SERIAL="$SERIAL"
BUILD_LOG="$REPO_ROOT/.mero-build.log"
set -o pipefail
if ! ./gradlew :sample-app:installDebug --stacktrace > "$BUILD_LOG" 2>&1; then
  echo "${RED}build/install failed:${RESET}"
  grep -iE "error|failed|exception" "$BUILD_LOG" | head -20
  die "build/install failed — see $BUILD_LOG (not launching a stale app)"
fi
set +o pipefail
echo "${GREEN}** INSTALL SUCCEEDED **${RESET}"

# ---- launch ----------------------------------------------------------------
step "Launching ${APP_ID}  ($([ "$MOCK" -eq 1 ] && echo 'MOCK backend' || echo 'LIVE node'))"
# Launch extras (see report / TESTING.md — the app author wires these):
#   --ez mock true    → the in-app mock backend (no node)
#   --es nodeUrl URL  → pre-fills the node URL field for the live flow
LAUNCH_EXTRAS=()
if [ "$MOCK" -eq 1 ]; then
  LAUNCH_EXTRAS+=(--ez mock true)
else
  LAUNCH_EXTRAS+=(--es nodeUrl "$EMU_NODE_URL")
fi
"$ADB" -s "$SERIAL" shell am start -n "${APP_ID}/${APP_ACTIVITY}" "${LAUNCH_EXTRAS[@]}" >/dev/null \
  || die "am start failed"

echo
echo "${GREEN}${BOLD}✔ launched${RESET} on $SERIAL."
if [ "$MOCK" -eq 1 ]; then
  echo "  Log in with ${BOLD}any${RESET} username/password → Home → ${BOLD}Run sample RPC${RESET} → ${BOLD}Log Out${RESET}."
else
  echo "  Node URL: ${BOLD}${EMU_NODE_URL}${RESET} (enter it if the field isn't pre-filled)."
  echo "  Log in with your node's admin creds (e.g. ${BOLD}${ADMIN_USER} / ${ADMIN_PASS}${RESET})."
fi

if [ "$STREAM_LOGS" -eq 1 ]; then
  echo "${DIM}streaming app logcat — Ctrl-C to stop${RESET}"
  PID=$("$ADB" -s "$SERIAL" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r')
  if [ -n "$PID" ]; then "$ADB" -s "$SERIAL" logcat --pid="$PID"
  else "$ADB" -s "$SERIAL" logcat -s "$APP_ID"; fi
else
  echo "  ${DIM}Re-run with --logs to stream the app's logcat.${RESET}"
fi
