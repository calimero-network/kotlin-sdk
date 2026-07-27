#!/usr/bin/env bash
#
# run-all-2.sh — the whole two-user stack on one laptop: TWO nodes + TWO Android
# emulators, each app pointed at its own node, so you can test invitations and
# cross-user chat by hand.
#
# Clean slate each run: stops + deletes both local nodes, boots two P2P-connected
# merods (A :4001, B :4011, admin dev / dev-password), builds the sample app,
# installs it on two emulators and launches:
#   emulator A → node A (http://10.0.2.2:4001, chat name dev1)
#   emulator B → node B (http://10.0.2.2:4011, chat name dev2)
# Both nodes are left running when the script exits.
#
# Usage: ./run-all-2.sh
#   Reuses already-running emulators, launches more from your AVDs as needed, and
#   clones a second AVD ('emulator_b') if you only have one — a second instance of
#   an already-running AVD can't start, so two DISTINCT AVDs are required.
#   Override with AVD_A= / AVD_B= env. HEADLESS=1 runs the emulators with -no-window.

set -u
cd "$(dirname "$0")"
REPO_ROOT="$(pwd)"

APP_ID="com.calimero.mero.sample"
APP_ACTIVITY=".MainActivity"

BOLD=$'\033[1m'; GREEN=$'\033[32m'; DIM=$'\033[2m'; RED=$'\033[31m'; RESET=$'\033[0m'
step() { echo; echo "${BOLD}▶ $*${RESET}"; }
die() { echo "${RED}✘ $*${RESET}"; exit 1; }

[ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ] && { awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0; }

command -v merod >/dev/null 2>&1 || die "merod not on PATH"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
EMULATOR="$(command -v emulator || echo "$SDK/emulator/emulator")"
[ -x "$ADB" ] || die "adb not found — set ANDROID_HOME (see TESTING.md §0)"
command -v java >/dev/null 2>&1 || die "java not on PATH — set JAVA_HOME (see TESTING.md §0)"

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
[ -z "$BOOT" ] && BOOT=$(grep -oE '/ip4/[0-9.]+/tcp/4002/p2p/[A-Za-z0-9]+' "$REPO_ROOT/.mero-a.log" | head -1)
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
echo "waiting for peers to connect…"; sleep 8
echo "  A peers: $(curl -s http://localhost:4001/admin-api/peers 2>/dev/null)"
echo "  B peers: $(curl -s http://localhost:4011/admin-api/peers 2>/dev/null)"

# ---- two emulators ---------------------------------------------------------
step "Resolving two emulators"
"$ADB" start-server >/dev/null 2>&1 || true
running_serials() { "$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/{print $1}'; }
wait_boot() { for _ in $(seq 1 60); do [ "$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return 0; sleep 2; done; }

# portable line→array reads (macOS bash 3.2 has no `mapfile`)
AVDS=(); while IFS= read -r _l; do AVDS+=("$_l"); done < <("$EMULATOR" -list-avds 2>/dev/null)

# Resolve an installed system image straight off disk — `$SDK/system-images/<api>/<tag>/<abi>` →
# `system-images;<api>;<tag>;<abi>`. Reading the AVD's config.ini instead is unreliable: a
# cached AVD may have no `image.sysdir.1` key at all.
installed_system_image() {
  local dir
  dir=$(ls -d "$SDK"/system-images/*/*/* 2>/dev/null | head -1) || return 1
  [ -n "$dir" ] || return 1
  printf 'system-images;%s;%s;%s\n' \
    "$(basename "$(dirname "$(dirname "$dir")")")" "$(basename "$(dirname "$dir")")" "$(basename "$dir")"
}

# Two DISTINCT AVDs are required: a second instance of an already-running AVD can't start
# unless every instance is -read-only. Clone one from the same installed system image.
ensure_two_avds() {
  [ "${#AVDS[@]}" -ge 2 ] && return 0
  local mgr="$SDK/cmdline-tools/latest/bin/avdmanager" base="${AVDS[0]:-}" pkg out
  [ -n "$base" ] || return 1
  [ -x "$mgr" ] || { echo "only one AVD ('$base') and no avdmanager to clone it"; return 1; }
  pkg=$(installed_system_image) || true
  [ -n "$pkg" ] || { echo "no installed system image found under $SDK/system-images"; return 1; }
  echo "only one AVD ('$base') — creating 'emulator_b' from $pkg"
  if ! out=$(echo no | "$mgr" create avd --force --name emulator_b --package "$pkg" 2>&1); then
    echo "avdmanager failed: $out"
    return 1
  fi
  AVDS+=("emulator_b")
}
ensure_two_avds || echo "${RED}continuing with one AVD — the second emulator will likely fail${RESET}"

AVD_A="${AVD_A:-${AVDS[0]:-}}"; AVD_B="${AVD_B:-${AVDS[1]:-${AVDS[0]:-}}}"
# If B resolved to the AVD that is already running, prefer any other one.
if [ "$AVD_A" = "$AVD_B" ] && [ "${#AVDS[@]}" -ge 2 ]; then AVD_B="${AVDS[1]}"; fi

# Launch an AVD and wait for a *new* serial to appear. `adb wait-for-device` returns
# immediately when any emulator is already attached, which makes a naive loop relaunch
# forever instead of ever reporting a failure.
launch_avd() {
  [ -x "$EMULATOR" ] || die "'emulator' binary not found"
  [ -n "$1" ] || die "no AVD available — create one (Android Studio ▸ Device Manager, or see TESTING.md §0)"
  local before after log="$REPO_ROOT/.emulator-$$-$1.log" window=()
  [ "${HEADLESS:-0}" = "1" ] && window=(-no-window -gpu swiftshader_indirect -noaudio)
  before="$(running_serials | tr '\n' ' ')"
  echo "launching emulator: $1"
  "$EMULATOR" -avd "$1" -no-boot-anim -read-only -netdelay none -netspeed full \
    ${window[@]+"${window[@]}"} > "$log" 2>&1 &
  for _ in $(seq 1 60); do
    after="$(running_serials | tr '\n' ' ')"
    [ "$after" != "$before" ] && return 0
    sleep 2
  done
  echo "${RED}emulator '$1' never attached (120s). Last lines of $log:${RESET}"
  tail -20 "$log" 2>/dev/null || true
  return 1
}

attempt=0
while [ "$(running_serials | wc -l | tr -d ' ')" -lt 2 ]; do
  attempt=$((attempt + 1))
  if [ "$attempt" -gt 2 ]; then
    die "could not bring up two emulators (have $(running_serials | wc -l | tr -d ' ')).
  Two DISTINCT AVDs are needed: a second instance of an already-running AVD can't start
  unless every instance is -read-only, which is why a single AVD isn't enough.
  Create a second AVD (TESTING.md §0) or set AVD_A= / AVD_B= explicitly."
  fi
  n=$(running_serials | wc -l | tr -d ' ')
  if [ "$n" -eq 0 ]; then launch_avd "$AVD_A" || true; else launch_avd "$AVD_B" || true; fi
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
# 10.0.2.2 is the emulator's alias for the host loopback — "localhost" inside the emulator is
# the emulator itself. The login user is the admin "dev" on both nodes, so the chatUser extra
# is what keeps the two users distinguishable in the room.
"$ADB" -s "$UDID_A" shell am start -n "${APP_ID}/${APP_ACTIVITY}" \
  --es nodeUrl "http://10.0.2.2:4001" --es chatUser "dev1" >/dev/null 2>&1
"$ADB" -s "$UDID_B" shell am start -n "${APP_ID}/${APP_ACTIVITY}" \
  --es nodeUrl "http://10.0.2.2:4011" --es chatUser "dev2" >/dev/null 2>&1

echo
echo "${GREEN}${BOLD}✔ two apps launched.${RESET}"
echo "  emulator A ($UDID_A) → node A :4001    emulator B ($UDID_B) → node B :4011"
echo "  Sign in as ${BOLD}dev / dev-password${RESET} on both (chat display names are dev1/dev2)."
echo "  On A: Open Chat Example → create a space + channel → Invite people → Copy."
echo "  On B: Open Chat Example → + → Join existing space → paste the invite → Join."
echo "  ${DIM}Emulators share no clipboard. Type the invite into B's field from your shell with:"
echo "    $ADB -s $UDID_B shell input text '<invite-code>'${RESET}"
echo "  ${DIM}Node logs: .mero-a.log / .mero-b.log    Nodes stay running; stop them with:"
echo "    kill \$(cat .mero-a.pid) \$(cat .mero-b.pid)${RESET}"
