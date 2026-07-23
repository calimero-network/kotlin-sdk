#!/usr/bin/env bash
#
# run-app-2.sh — launch the app on TWO emulators against TWO nodes, so you can
# test invitations / cross-user chat by hand.
#
# Clean slate each run: stops + deletes both local nodes, then boots two
# P2P-connected merods (A :4001, B :4011, admin dev/dev-password), builds the
# app, installs it on two emulators, and launches:
#   emulator A → node A (http://10.0.2.2:4001, the default)
#   emulator B → node B (http://10.0.2.2:4011, via the nodeUrl launch extra)
# Both nodes are left running. Sign in as dev / dev-password on each; on A create
# a space + channel and an invite (copy it), on B paste it to join.
#
# Usage: ./run-app-2.sh
#   Reuses up to two already-running emulators; launches more from your AVDs as
#   needed. Override the AVDs with AVD_A= / AVD_B= env.

set -u
cd "$(dirname "$0")"
REPO_ROOT="$(pwd)"

APP_ID="com.calimero.mero.sample"
APP_ACTIVITY=".MainActivity"

BOLD=$'\033[1m'; GREEN=$'\033[32m'; DIM=$'\033[2m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
step() { echo; echo "${BOLD}▶ $*${RESET}"; }
die() { echo "${RED}✘ $*${RESET}"; exit 1; }

[ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ] && { awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0; }

command -v merod >/dev/null 2>&1 || die "merod not on PATH"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
EMULATOR="$(command -v emulator || echo "$SDK/emulator/emulator")"
[ -x "$ADB" ] || die "adb not found — set ANDROID_HOME (see TESTING.md §0)"

step "Fresh start — stopping nodes"
for f in .mero-a.pid .mero-b.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null || true; done
for p in 4001 4011; do pids=$(lsof -ti "tcp:$p" 2>/dev/null || true); [ -n "$pids" ] && kill -9 $pids 2>/dev/null || true; done
rm -rf "$REPO_ROOT/.mero-a" "$REPO_ROOT/.mero-b" "$REPO_ROOT"/.mero-a.* "$REPO_ROOT"/.mero-b.*

step "Boot node A (:4001)"
printf 'dev-password' | merod --home "$REPO_ROOT/.mero-a" --node a init \
  --server-port 4001 --swarm-port 4002 --auth-mode embedded --auth-storage persistent \
  --admin-user dev --admin-password-stdin --mdns >/dev/null 2>&1 || die "node A init failed"
merod --home "$REPO_ROOT/.mero-a" --node a run > "$REPO_ROOT/.mero-a.log" 2>&1 &
echo $! > "$REPO_ROOT/.mero-a.pid"
until curl -sf http://localhost:4001/admin-api/health >/dev/null 2>&1; do sleep 1; done
echo "node A healthy"
sleep 3
BOOT=$(grep -oE '/ip4/127\.0\.0\.1/tcp/4002/p2p/[A-Za-z0-9]+' "$REPO_ROOT/.mero-a.log" | head -1)
echo "node A boot addr: ${BOOT:-<none, relying on mDNS>}"

step "Boot node B (:4011)"
BOOT_ARGS=()
[ -n "$BOOT" ] && BOOT_ARGS=(--boot-nodes "$BOOT")
printf 'dev-password' | merod --home "$REPO_ROOT/.mero-b" --node b init \
  --server-port 4011 --swarm-port 4012 --auth-mode embedded --auth-storage persistent \
  --admin-user dev --admin-password-stdin --mdns ${BOOT_ARGS[@]+"${BOOT_ARGS[@]}"} >/dev/null 2>&1 || die "node B init failed"
merod --home "$REPO_ROOT/.mero-b" --node b run > "$REPO_ROOT/.mero-b.log" 2>&1 &
echo $! > "$REPO_ROOT/.mero-b.pid"
until curl -sf http://localhost:4011/admin-api/health >/dev/null 2>&1; do sleep 1; done
echo "node B healthy"

# ---- two emulators ---------------------------------------------------------
step "Resolving two emulators"
"$ADB" start-server >/dev/null 2>&1 || true
running_serials() { "$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/{print $1}'; }
wait_boot() { for _ in $(seq 1 60); do [ "$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return 0; sleep 2; done; }

# portable line→array reads (macOS bash 3.2 has no `mapfile`)
SERIALS=(); while IFS= read -r _l; do SERIALS+=("$_l"); done < <(running_serials)
AVDS=()
if [ ${#SERIALS[@]} -lt 2 ]; then
  while IFS= read -r _l; do AVDS+=("$_l"); done < <("$EMULATOR" -list-avds 2>/dev/null)
fi
AVD_A="${AVD_A:-${AVDS[0]:-}}"; AVD_B="${AVD_B:-${AVDS[1]:-${AVDS[0]:-}}}"

launch_avd() {  # <avd-name> — launch one more emulator instance, echo nothing
  [ -x "$EMULATOR" ] || die "'emulator' binary not found"
  [ -n "$1" ] || die "no AVD available — create one (Android Studio ▸ Device Manager)"
  echo "launching emulator: $1"
  "$EMULATOR" -avd "$1" -no-boot-anim -read-only -netdelay none -netspeed full \
    > "$REPO_ROOT/.emulator-$RANDOM.log" 2>&1 &
  "$ADB" wait-for-device
}

while [ "$(running_serials | wc -l | tr -d ' ')" -lt 2 ]; do
  n=$(running_serials | wc -l | tr -d ' ')
  if [ "$n" -eq 0 ]; then launch_avd "$AVD_A"; else launch_avd "$AVD_B"; fi
  sleep 3
done
SERIALS=(); while IFS= read -r _l; do SERIALS+=("$_l"); done < <(running_serials)
UDID_A="${SERIALS[0]}"; UDID_B="${SERIALS[1]}"
[ "$UDID_A" != "$UDID_B" ] || die "need two distinct emulators"
echo "emulator A=$UDID_A  B=$UDID_B"
for u in "$UDID_A" "$UDID_B"; do wait_boot "$u"; done

step "Build app"
set -o pipefail
if ! ./gradlew :sample-app:assembleDebug --stacktrace > "$REPO_ROOT/.mero-build.log" 2>&1; then
  grep -iE "error|failed|exception" "$REPO_ROOT/.mero-build.log" | head; die "build failed"
fi
set +o pipefail
APK=$(find sample-app/build/outputs/apk/debug -name '*.apk' 2>/dev/null | head -1)
[ -n "$APK" ] || die "built APK not found"

step "Install + launch on both emulators"
for u in "$UDID_A" "$UDID_B"; do
  "$ADB" -s "$u" install -r -g "$APK" >/dev/null 2>&1 || die "install failed on $u"
done
"$ADB" -s "$UDID_A" shell am start -n "${APP_ID}/${APP_ACTIVITY}" \
  --es nodeUrl "http://10.0.2.2:4001" >/dev/null 2>&1   # emulator A → node A :4001
"$ADB" -s "$UDID_B" shell am start -n "${APP_ID}/${APP_ACTIVITY}" \
  --es nodeUrl "http://10.0.2.2:4011" >/dev/null 2>&1   # emulator B → node B :4011

echo
echo "${GREEN}${BOLD}✔ two apps launched.${RESET}"
echo "  emulator A ($UDID_A) → node A :4001    emulator B ($UDID_B) → node B :4011"
echo "  Sign in as ${BOLD}dev / dev-password${RESET} on both."
echo "  On A: Open Chat → create a space + channel → Invite people → Copy."
echo "  Move the invite A→B, then on B: Open Chat → + → Join with invite → paste → Join."
echo "  ${DIM}(adb has no shared clipboard between emulators; copy the invite text out of A's"
echo "   logcat or the UI, then paste it into B by hand.)${RESET}"
echo "  ${DIM}Nodes stay running. Stop them with: kill \$(cat .mero-a.pid) \$(cat .mero-b.pid)${RESET}"
