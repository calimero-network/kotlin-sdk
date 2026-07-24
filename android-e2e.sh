#!/usr/bin/env bash
#
# android-e2e.sh — run the full-feature Android end-to-end suite (AppE2ETest)
# against a LIVE node, on one emulator. The "Playwright for Android" run: login →
# explorer method call → chat install → space → channel → send/read a message.
#
# It boots a fresh merod on :4001 (admin dev/dev-password), reuses a running
# emulator (or launches one from your AVDs), then runs the AppE2ETest instrumented
# class via `:sample-app:connectedDebugAndroidTest`. The node is left running. For
# the multi-user (2-node/2-emulator) chat e2e, use chat-multi-e2e.sh.
#
# From inside the emulator the node is reachable at http://10.0.2.2:4001 (10.0.2.2
# is the host loopback), which is passed to the test as instrumentation args.
#
# Usage: ./android-e2e.sh [--serial emulator-5554]

set -u
cd "$(dirname "$0")"
REPO_ROOT="$(pwd)"
SERIAL=""
[ "${1:-}" = "--serial" ] && SERIAL="${2:?}"
[ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ] && { awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0; }

RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
die() { echo "${RED}✘ $*${RESET}"; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
EMULATOR="$(command -v emulator || echo "$SDK/emulator/emulator")"
[ -x "$ADB" ] || die "adb not found — set ANDROID_HOME (see TESTING.md §0)"

echo "${BOLD}▶ fresh node on :4001${RESET}"
command -v merod >/dev/null 2>&1 || die "merod not on PATH"
NODE_HOME="$REPO_ROOT/.mero-e2e-node"
# Fresh node each run → deterministic state (no leftover spaces/channels).
pids=$(lsof -ti tcp:4001 2>/dev/null || true); [ -n "$pids" ] && kill -9 $pids 2>/dev/null || true
rm -rf "$NODE_HOME"
printf 'dev-password' | merod --home "$NODE_HOME" --node app init \
  --server-port 4001 --swarm-port 4002 --auth-mode embedded --auth-storage persistent \
  --admin-user dev --admin-password-stdin >/dev/null 2>&1 || die "node init failed"
merod --home "$NODE_HOME" --node app run > "$REPO_ROOT/.mero-e2e-node.log" 2>&1 &
echo $! > "$REPO_ROOT/.mero-e2e-node.pid"
until curl -sf http://localhost:4001/admin-api/health >/dev/null 2>&1; do sleep 1; done
echo "node healthy (dev / dev-password)"

echo "${BOLD}▶ resolve emulator${RESET}"
"$ADB" start-server >/dev/null 2>&1 || true
if [ -z "$SERIAL" ]; then
  SERIAL=$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/{print $1; exit}')
fi
if [ -z "$SERIAL" ]; then
  AVD=$("$EMULATOR" -list-avds 2>/dev/null | head -1)
  [ -n "$AVD" ] || die "no running emulator and no AVD found — create one (see TESTING.md §0)"
  echo "launching emulator: $AVD"
  "$EMULATOR" -avd "$AVD" -no-window -no-boot-anim -gpu swiftshader_indirect -noaudio \
    > "$REPO_ROOT/.emulator.log" 2>&1 &
  "$ADB" wait-for-device
  SERIAL=$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/{print $1; exit}')
fi
[ -n "$SERIAL" ] || die "could not resolve an emulator serial"
echo "device: $SERIAL"
for _ in $(seq 1 60); do
  [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done

echo "${BOLD}▶ run AppE2ETest${RESET}"
export ANDROID_SERIAL="$SERIAL"
# Capture the app's logcat for the whole run: when a UI wait times out, the app-side
# reason (auth error, unreachable node, crash) is only visible here.
"$ADB" -s "$SERIAL" logcat -c 2>/dev/null || true
"$ADB" -s "$SERIAL" logcat -v time > "$REPO_ROOT/.e2e-logcat.log" 2>&1 &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null || true' EXIT
set -o pipefail
# The test connects to the node at 10.0.2.2:4001 and authenticates with the admin
# creds — all handed to the instrumented runner as arguments.
./gradlew :sample-app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calimero.mero.sample.AppE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.nodeUrl=http://10.0.2.2:4001 \
  -Pandroid.testInstrumentationRunnerArguments.user=dev \
  -Pandroid.testInstrumentationRunnerArguments.pass=dev-password \
  --stacktrace 2>&1 | tee "$REPO_ROOT/.e2e-android.log"
code=${PIPESTATUS[0]}
[ "$code" -eq 0 ] && echo "${GREEN}✔ e2e passed${RESET}" || die "e2e failed — see .e2e-android.log"
