#!/usr/bin/env bash
#
# test-all.sh — run everything in TESTING.md in one shot.
#
# It is RECOVERABLE: a failing step never aborts the run; every remaining step
# still executes. At the end it prints a PASS/FAIL summary of every step.
#
# Usage:
#   ./test-all.sh                 # run all sections (§0–§6)
#   ./test-all.sh --clean         # wipe build/ dirs first (after a toolchain change)
#   ./test-all.sh --skip-e2e      # skip §4 live-node e2e
#   ./test-all.sh --skip-ui       # skip §6 emulator instrumented tests
#   ./test-all.sh --skip-e2e --skip-ui
#
# Exit code: 0 if every executed step passed, 1 if any failed.

set -u  # (no `set -e` — we want to continue past failures)

cd "$(dirname "$0")"           # always run from the repo root
REPO_ROOT="$(pwd)"

# ---- options ---------------------------------------------------------------
CLEAN=0 SKIP_E2E=0 SKIP_UI=0
for arg in "$@"; do
  case "$arg" in
    --clean)    CLEAN=1 ;;
    --skip-e2e) SKIP_E2E=1 ;;
    --skip-ui)  SKIP_UI=1 ;;
    -h|--help)  awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0 ;;
    *) echo "unknown option: $arg (try --help)"; exit 2 ;;
  esac
done

# ---- pretty output ---------------------------------------------------------
if [ -t 1 ]; then
  BOLD=$'\033[1m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'
  BLUE=$'\033[34m'; DIM=$'\033[2m'; RESET=$'\033[0m'
else
  BOLD=""; RED=""; GREEN=""; YELLOW=""; BLUE=""; DIM=""; RESET=""
fi

# ---- result tracking -------------------------------------------------------
STEP_NAMES=(); STEP_RESULTS=(); STEP_SECONDS=()

record() { STEP_NAMES+=("$1"); STEP_RESULTS+=("$2"); STEP_SECONDS+=("${3:-0}"); }

# run_step "Section — name" <command...>   (command runs live; exit code decides)
run_step() {
  local name="$1"; shift
  echo
  echo "${BOLD}${BLUE}▶ ${name}${RESET}"
  local start=$SECONDS
  if "$@"; then
    local dur=$((SECONDS - start))
    echo "${GREEN}✔ PASS${RESET} ${DIM}(${dur}s)${RESET} — ${name}"
    record "$name" PASS "$dur"
    return 0
  else
    local code=$? dur=$((SECONDS - start))
    echo "${RED}✘ FAIL (exit ${code})${RESET} ${DIM}(${dur}s)${RESET} — ${name}"
    record "$name" FAIL "$dur"
    return 1
  fi
}

# mark a step as SKIPPED without running it
skip_step() {
  echo
  echo "${YELLOW}⤼ SKIP${RESET} — $1  ${DIM}($2)${RESET}"
  record "$1" SKIP 0
}

# ---- Android SDK helpers ---------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
EMULATOR="$(command -v emulator || echo "$SDK/emulator/emulator")"

# ===========================================================================
echo "${BOLD}mero-kotlin — full local test run (TESTING.md)${RESET}"
echo "${DIM}repo: ${REPO_ROOT}${RESET}"

# ---------------------------------------------------------------------------
# §0. Prerequisites — verify JDK + Android SDK are present.
# ---------------------------------------------------------------------------
prereq() {
  local ok=0
  if command -v java >/dev/null 2>&1; then
    echo "${GREEN}java${RESET}: $(java -version 2>&1 | head -1)"
  else
    echo "${RED}java NOT found${RESET} — install a JDK 17 (see TESTING.md §0)."
    ok=1
  fi
  if [ -d "$SDK" ]; then
    echo "${GREEN}Android SDK${RESET}: $SDK"
  else
    echo "${YELLOW}Android SDK not found${RESET} at $SDK — set ANDROID_HOME (needed for §6)."
  fi
  [ -x "$ADB" ] && echo "adb: $ADB" || echo "${YELLOW}adb missing${RESET} (needed for §6)"
  return $ok
}
run_step "§0 Prerequisites — JDK + Android SDK" prereq

if [ "$CLEAN" -eq 1 ]; then
  run_step "§0 Clean — rm -rf build/" bash -c 'rm -rf build */build && echo "cleaned"'
else
  echo; echo "${DIM}(skipping build/ wipe — pass --clean to force it after a toolchain change)${RESET}"
fi

# ---------------------------------------------------------------------------
# §1. Build
# ---------------------------------------------------------------------------
run_step "§1 Build — assembleDebug" ./gradlew assembleDebug --stacktrace

# ---------------------------------------------------------------------------
# §2. Lint + unit tests (what CI enforces; live e2e auto-skips without a node)
# ---------------------------------------------------------------------------
run_step "§2 Lint — ktlintCheck + detekt" ./gradlew ktlintCheck detekt --stacktrace
run_step "§2 Unit tests — testDebugUnitTest" ./gradlew testDebugUnitTest --stacktrace

# ---------------------------------------------------------------------------
# §3. Android Lint
# ---------------------------------------------------------------------------
run_step "§3 Android Lint — ./gradlew lint" ./gradlew lint --stacktrace

# ---------------------------------------------------------------------------
# §4. Live e2e against a real merod node
# ---------------------------------------------------------------------------
NODE_UP=0
MEROD_BIN=""

boot_node() {
  # pick a merod: PATH first, then ./merod, else download the latest release
  if command -v merod >/dev/null 2>&1; then MEROD_BIN="$(command -v merod)"
  elif [ -x ./merod ]; then MEROD_BIN="./merod"
  else
    echo "downloading released merod (x86_64-unknown-linux-gnu)…"
    local TAG URL
    TAG=$(gh release list --repo calimero-network/core --limit 1 --json tagName -q '.[0].tagName') || return 1
    URL=$(gh release view "$TAG" --repo calimero-network/core --json assets \
      -q '.assets[] | select(.name | test("merod_x86_64-unknown-linux-gnu\\.tar\\.gz$")) | .url') || return 1
    [ -n "$URL" ] || { echo "no merod linux asset on $TAG"; return 1; }
    curl -sL "$URL" | tar xz && chmod +x ./merod && MEROD_BIN="./merod"
  fi
  echo "using merod: ${MEROD_BIN} ($("$MEROD_BIN" --version 2>&1 | head -1))"

  rm -rf ./e2e-node
  printf 'dev-password' | "$MEROD_BIN" --home ./e2e-node --node e2e init \
    --server-port 4001 --swarm-port 4002 \
    --auth-mode embedded --auth-storage persistent \
    --admin-user dev --admin-password-stdin || return 1

  "$MEROD_BIN" --home ./e2e-node --node e2e run > merod.log 2>&1 &
  echo $! > merod.pid

  echo -n "waiting for node health"
  for _ in $(seq 1 30); do
    if curl -sf http://localhost:4001/admin-api/health >/dev/null 2>&1; then
      echo " — healthy"; NODE_UP=1; return 0
    fi
    echo -n "."; sleep 1
  done
  echo " — TIMED OUT"; echo "--- last merod.log ---"; tail -20 merod.log 2>/dev/null
  return 1
}

stop_node() {
  if [ -f merod.pid ]; then
    kill "$(cat merod.pid)" 2>/dev/null || true
    rm -f merod.pid
    echo "node stopped"
  fi
}
trap stop_node EXIT

if [ "$SKIP_E2E" -eq 1 ]; then
  skip_step "§4a Boot merod node" "--skip-e2e"
  skip_step "§4b Live e2e — RealNodeE2ETest" "--skip-e2e"
else
  run_step "§4a Boot merod node (rc.17 init-time admin creds)" boot_node
  if [ "$NODE_UP" -eq 1 ]; then
    run_step "§4b Live e2e — :mero-core RealNodeE2ETest" \
      env MERO_E2E_NODE_URL=http://localhost:4001 MERO_E2E_USER=dev MERO_E2E_PASS=dev-password \
          MERO_AUTH_BOOTSTRAP_SECRET=kotlin-sdk-e2e-bootstrap \
      ./gradlew :mero-core:testDebugUnitTest --tests '*RealNodeE2ETest*' --stacktrace
  else
    skip_step "§4b Live e2e — :mero-core RealNodeE2ETest" "node did not boot"
  fi
fi

# ---------------------------------------------------------------------------
# §5. Runnable example — no standalone JVM/console example in this repo.
# ---------------------------------------------------------------------------
# The Swift SDK ships a `MeroExample` CLI; the Kotlin SDK's runnable demo IS the
# sample-app (exercised in §6), and there is no :mero-core `run`/`application`
# entry point. If one is added later (e.g. a :mero-core-cli module), wire it here.
if ./gradlew :mero-core:tasks --all 2>/dev/null | grep -qiE '^run\b'; then
  run_step "§5 MeroExample — :mero-core run" ./gradlew :mero-core:run --stacktrace
else
  skip_step "§5 Runnable example (MeroExample-equivalent)" "no console example — the sample-app in §6 is the runnable demo"
fi

# ---------------------------------------------------------------------------
# §6. Instrumented UI tests — sample-app (connectedDebugAndroidTest, in-app mock)
# ---------------------------------------------------------------------------
UI_SERIAL=""
prep_emulator() {
  [ -x "$ADB" ] || { echo "adb not found — set ANDROID_HOME"; return 1; }
  "$ADB" start-server >/dev/null 2>&1 || true
  UI_SERIAL=$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/{print $1; exit}')
  if [ -z "$UI_SERIAL" ]; then
    [ -x "$EMULATOR" ] || { echo "no running emulator and 'emulator' binary not found"; return 1; }
    local AVD; AVD=$("$EMULATOR" -list-avds 2>/dev/null | head -1)
    [ -n "$AVD" ] || { echo "no AVD found — create one (Android Studio ▸ Device Manager)"; return 1; }
    echo "launching emulator: $AVD"
    "$EMULATOR" -avd "$AVD" -no-boot-anim -no-window -gpu swiftshader_indirect -noaudio \
      > "$REPO_ROOT/.emulator.log" 2>&1 &
    "$ADB" wait-for-device
    UI_SERIAL=$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/{print $1; exit}')
  fi
  [ -n "$UI_SERIAL" ] || { echo "no emulator serial"; return 1; }
  echo "emulator: $UI_SERIAL"
  for _ in $(seq 1 60); do
    [ "$("$ADB" -s "$UI_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { echo "booted"; return 0; }
    sleep 2
  done
  echo "emulator did not finish booting"; return 1
}

run_ui_tests() {
  ANDROID_SERIAL="$UI_SERIAL" ./gradlew :sample-app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=com.calimero.mero.sample.AppE2ETest,com.calimero.mero.sample.ChatMultiUserTest \
    --stacktrace
}

if [ "$SKIP_UI" -eq 1 ]; then
  skip_step "§6 Emulator prep" "--skip-ui"
  skip_step "§6 Instrumented tests — connectedDebugAndroidTest" "--skip-ui"
else
  run_step "§6 Emulator prep (resolve/boot)" prep_emulator
  if [ -n "$UI_SERIAL" ]; then
    run_step "§6 Instrumented tests — sample-app (in-app mock)" run_ui_tests
  else
    skip_step "§6 Instrumented tests — connectedDebugAndroidTest" "no emulator available"
  fi
fi

# ===========================================================================
# Summary
# ===========================================================================
echo
echo "${BOLD}────────────────────────── SUMMARY ──────────────────────────${RESET}"
pass=0; fail=0; skip=0
for i in "${!STEP_NAMES[@]}"; do
  case "${STEP_RESULTS[$i]}" in
    PASS) icon="${GREEN}✔ PASS${RESET}"; pass=$((pass+1)) ;;
    FAIL) icon="${RED}✘ FAIL${RESET}"; fail=$((fail+1)) ;;
    SKIP) icon="${YELLOW}– SKIP${RESET}"; skip=$((skip+1)) ;;
  esac
  printf "  %b  %-55s %s\n" "$icon" "${STEP_NAMES[$i]}" "${DIM}${STEP_SECONDS[$i]}s${RESET}"
done
echo "${BOLD}──────────────────────────────────────────────────────────────${RESET}"
echo "  ${GREEN}${pass} passed${RESET}, ${RED}${fail} failed${RESET}, ${YELLOW}${skip} skipped${RESET}   (of $(( pass + fail + skip )) steps)"
echo

[ "$fail" -eq 0 ]
