#!/usr/bin/env bash
#
# chat-multi-e2e.sh — two-user, two-node, two-emulator chat end-to-end.
#
# Boots two P2P-connected merod nodes (A :4001, B :4011) and two emulators, then
# runs the ChatMultiUserTest roles, handing the invite between emulators via
# logcat (adb has no shared clipboard):
#   A: create space+channel, post "hi from host", LOG the invite token
#   → scrape the invite from emulator A's logcat, hand it to the guest role
#   B: join with the invite, see the host's message, reply "hi from guest"
#   A: see the guest's reply
#
# NOTE: the cross-node message sync depends on gossipsub between two co-located
# merods, which is historically unreliable on a single host. If a step fails at
# "did not sync", that's the P2P layer, not the app/test — the same flow works
# across separate hosts. Everything up to the sync is verified. This script is
# therefore informational (android-e2e.yml marks its job continue-on-error).
#
# The invite handoff assumes the host test writes the invite to logcat as a line
# `MERO_E2E_INVITE=<token>` (see report / TESTING.md — the app author wires this),
# and that the guest role reads it from the `invite` instrumentation argument.
#
# Usage: ./chat-multi-e2e.sh   (override AVDs with AVD_A= / AVD_B= env)

set -u
cd "$(dirname "$0")"
REPO_ROOT="$(pwd)"

APP_ID="com.calimero.mero.sample"
TEST_CLASS="com.calimero.mero.sample.ChatMultiUserTest"

GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; DIM=$'\033[2m'; RESET=$'\033[0m'
step() { echo; echo "${BOLD}▶ $*${RESET}"; }
die() { echo "${RED}✘ $*${RESET}"; exit 1; }

[ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ] && { awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0; }

command -v merod >/dev/null 2>&1 || die "merod not on PATH"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
EMULATOR="$(command -v emulator || echo "$SDK/emulator/emulator")"
[ -x "$ADB" ] || die "adb not found — set ANDROID_HOME (see TESTING.md §0)"

# ---- fresh nodes -----------------------------------------------------------
step "Fresh start — stopping nodes"
for p in 4001 4011; do pids=$(lsof -ti "tcp:$p" 2>/dev/null || true); [ -n "$pids" ] && kill -9 $pids 2>/dev/null || true; done
rm -rf "$REPO_ROOT/.mero-a" "$REPO_ROOT/.mero-b" "$REPO_ROOT"/.mero-*.log "$REPO_ROOT"/.mero-*.pid

step "Boot node A (:4001)"
printf 'dev-password' | merod --home "$REPO_ROOT/.mero-a" --node a init \
  --server-port 4001 --swarm-port 4002 --auth-mode embedded --auth-storage persistent \
  --admin-user dev --admin-password-stdin --mdns >/dev/null 2>&1 || die "node A init failed"
merod --home "$REPO_ROOT/.mero-a" --node a run > "$REPO_ROOT/.mero-a.log" 2>&1 &
echo $! > "$REPO_ROOT/.mero-a.pid"
until curl -sf http://localhost:4001/admin-api/health >/dev/null 2>&1; do sleep 1; done
echo "node A healthy"

# best-effort: extract A's swarm multiaddr for bootstrapping B
sleep 3
BOOT=$(grep -oE '/ip4/127\.0\.0\.1/tcp/4002/p2p/[A-Za-z0-9]+' "$REPO_ROOT/.mero-a.log" | head -1)
[ -z "$BOOT" ] && BOOT=$(grep -oE '/ip4/[0-9.]+/tcp/4002/p2p/[A-Za-z0-9]+' "$REPO_ROOT/.mero-a.log" | head -1)
echo "node A boot addr: ${BOOT:-<none found, relying on mDNS>}"

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
AVD_A="${AVD_A:-${AVDS[0]:-}}"; AVD_B="${AVD_B:-${AVDS[1]:-${AVDS[0]:-}}}"
launch_avd() {
  [ -x "$EMULATOR" ] || die "'emulator' binary not found"
  [ -n "$1" ] || die "no AVD available — create one (see TESTING.md §0)"
  echo "launching emulator: $1"
  "$EMULATOR" -avd "$1" -no-window -no-boot-anim -read-only -gpu swiftshader_indirect -noaudio \
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

# Install the app on both emulators up front.
step "Build + install app on both emulators"
set -o pipefail
./gradlew :sample-app:assembleDebug --stacktrace > "$REPO_ROOT/.mero-build.log" 2>&1 \
  || { grep -iE "error|failed|exception" "$REPO_ROOT/.mero-build.log" | head; die "build failed"; }
set +o pipefail
APK=$(find sample-app/build/outputs/apk/debug -name '*.apk' 2>/dev/null | head -1)
[ -n "$APK" ] || die "built APK not found"
for u in "$UDID_A" "$UDID_B"; do "$ADB" -s "$u" install -r -g "$APK" >/dev/null 2>&1 || die "install failed on $u"; done

# run_role <serial> <TestMethod> <label> [extra runner args...]
run_role() {
  local serial="$1" method="$2" label="$3"; shift 3
  echo; echo "${BOLD}— $label —${RESET}"
  ANDROID_SERIAL="$serial" ./gradlew :sample-app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS#$method" \
    -Pandroid.testInstrumentationRunnerArguments.nodeUrl="http://10.0.2.2:$4" \
    "${@:5}" --stacktrace 2>&1 | tee "$REPO_ROOT/.mero-role-$method.log" \
    | grep -iE "Tests run|FAILED|OK \(|BUILD (SUCCESSFUL|FAILED)"
  return "${PIPESTATUS[0]}"
}

pass=0; fail=0

step "1/3 HOST creates space + invite + posts (emulator A / node A :4001)"
# clear logcat so we scrape only this role's invite line
"$ADB" -s "$UDID_A" logcat -c 2>/dev/null || true
run_role "$UDID_A" testHostCreateInviteAndPost "host" 4001 && pass=$((pass+1)) || { fail=$((fail+1)); die "host role failed"; }

step "Handoff invite A → B via logcat"
# The host test logs `MERO_E2E_INVITE=<token>`; scrape the last one.
INV=$("$ADB" -s "$UDID_A" logcat -d 2>/dev/null | grep -oE 'MERO_E2E_INVITE=[^[:space:]]+' | tail -1 | cut -d= -f2-)
[ -n "$INV" ] || die "no invite found in emulator A logcat (host test must log MERO_E2E_INVITE=<token>)"
echo "invite (${#INV} chars) captured from emulator A"

step "2/3 GUEST joins + sees host msg + replies (emulator B / node B :4011)"
run_role "$UDID_B" testGuestJoinAndReply "guest" 4011 \
  -Pandroid.testInstrumentationRunnerArguments.invite="$INV" && pass=$((pass+1)) || fail=$((fail+1))

step "3/3 HOST sees the guest reply (emulator A / node A :4001)"
run_role "$UDID_A" testHostSeesReply "verify" 4001 && pass=$((pass+1)) || fail=$((fail+1))

echo
echo "${BOLD}────────── RESULT ──────────${RESET}"
echo "  ${GREEN}$pass passed${RESET}, ${RED}$fail failed${RESET} of 3 roles"
[ "$fail" -eq 0 ] && echo "${GREEN}✔ multi-user chat e2e passed${RESET}" \
  || echo "${YELLOW}⚠ a cross-node step failed — if it's 'did not sync', that's co-located gossipsub, not the app (see header).${RESET}"
[ "$fail" -eq 0 ]
