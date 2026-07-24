# TESTING — mero-kotlin (kotlin-sdk)

Exact, section-by-section steps to test everything in this repo. Run every command
from the repo root (`kotlin-sdk/`) unless a section says otherwise.

`./test-all.sh` runs §0–§6 in one shot with a PASS/FAIL/SKIP summary; the sections
below are what it automates, plus how to run each by hand.

---

## 0. Prerequisites (do this first — one-time / after any toolchain change)

The build and unit tests need **JDK 17** and the **Android SDK**. Instrumented
tests (§6) and the app scripts additionally need `adb` + `emulator` on `PATH` (or
`ANDROID_HOME` set) and at least one **AVD** (Android Virtual Device) created.

```bash
# JDK 17 present?
java -version                      # → openjdk version "17.x"

# Android SDK present? (Android Studio installs it here by default on macOS)
export ANDROID_HOME="$HOME/Library/Android/sdk"   # Linux: ~/Android/Sdk
ls "$ANDROID_HOME/platform-tools/adb"             # adb
ls "$ANDROID_HOME/emulator/emulator"              # emulator

# Put the tools on PATH for this shell:
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# List AVDs — you need at least one (two for the multi-user chat e2e):
emulator -list-avds
# Create one from Android Studio ▸ Device Manager, or via cmdline-tools:
#   sdkmanager "system-images;android-30;default;x86_64"
#   avdmanager create avd -n mero30 -k "system-images;android-30;default;x86_64"
```

> **Emulator ⇄ host networking.** A merod node runs on the **host** at
> `http://localhost:4001`. From **inside the emulator** the same node is reached at
> **`http://10.0.2.2:4001`** (`10.0.2.2` is the emulator's alias for the host
> loopback). The app scripts and instrumented e2e already use `10.0.2.2`; the JVM
> `RealNodeE2ETest` runs on the host and uses `localhost`.

---

## 1. Build

```bash
./gradlew assembleDebug            # debug APK/AARs for all modules
./gradlew assembleDebug --stacktrace
```

Expected: builds `:mero-core`, `:mero-compose`, and `:sample-app` with no errors.

---

## 2. Lint + unit tests (the everyday run — no node, no emulator)

```bash
./gradlew ktlintCheck detekt       # style + static analysis (what CI enforces)
./gradlew testDebugUnitTest        # JVM unit + MockWebServer-backed e2e
```

The unit suite covers the auth state machine, RPC client, SSO, JWT, capabilities,
token store, and the mocked end-to-end journeys (against OkHttp `MockWebServer`).

`RealNodeE2ETest` (§4) **self-skips** (JUnit `assume`) because `MERO_E2E_NODE_URL`
is not set — that is correct here; run it against a real node in §4.

Run a single test class or method while iterating:

```bash
./gradlew :mero-core:testDebugUnitTest --tests '*RpcClientTest*'
./gradlew :mero-core:testDebugUnitTest --tests '*RpcClientTest.executeUnwrapsOutput'
```

---

## 3. Android Lint (what CI enforces — must be clean)

```bash
./gradlew lint
```

`ktlintCheck`, `detekt`, and `lint` must all exit 0 — this is exactly what `ci.yml`
runs on every PR.

---

## 4. Live end-to-end tests (against a real merod node)

These are the tests that skip in §2. They run only when `MERO_E2E_NODE_URL` is set,
so you need a node first. This is a **JVM** test — it runs on your machine (not in
an emulator), so it talks to the node at `localhost`.

### 4a. Boot a node

```bash
# Download a released merod (Linux x86_64) — or use one you already have.
# (On macOS use merod_aarch64-apple-darwin instead.)
TAG=$(gh release list --repo calimero-network/core --limit 1 --json tagName -q '.[0].tagName')
URL=$(gh release view "$TAG" --repo calimero-network/core --json assets \
  -q '.assets[] | select(.name | test("merod_x86_64-unknown-linux-gnu\\.tar\\.gz$")) | .url')
curl -sL "$URL" | tar xz && chmod +x ./merod

# Init a single node with embedded auth + an admin account.
# rc.17+ creates the admin AT INIT — there's no longer a first-login bootstrap
# secret in the default flow. The password is never a plain flag; pass it via
# stdin (below), a file (--admin-password-file <PATH>), or env
# (MERO_AUTH_ADMIN_PASSWORD). --auth-storage persistent keeps the init-time admin
# into `run`.
printf 'dev-password' | ./merod --home ./e2e-node --node e2e init \
  --server-port 4001 --swarm-port 4002 \
  --auth-mode embedded --auth-storage persistent \
  --admin-user dev --admin-password-stdin

# Run the node:
./merod --home ./e2e-node --node e2e run > merod.log 2>&1 &
echo $! > merod.pid

# Wait for health:
until curl -sf http://localhost:4001/admin-api/health >/dev/null; do sleep 1; done
echo "node healthy"
```

### 4b. Run the live e2e tests

```bash
MERO_E2E_NODE_URL=http://localhost:4001 \
MERO_E2E_USER=dev MERO_E2E_PASS=dev-password \
MERO_AUTH_BOOTSTRAP_SECRET=kotlin-sdk-e2e-bootstrap \
./gradlew :mero-core:testDebugUnitTest --tests '*RealNodeE2ETest*'
```

Expected: `RealNodeE2ETest` now **executes** (not skips). `MERO_E2E_USER` /
`MERO_E2E_PASS` must match the `--admin-user` / password you set at init. CI runs
this in `.github/workflows/e2e.yml` (manual + weekly) and guards against a
vacuous all-skipped green by inspecting the JUnit XML.

### 4c. Stop the node

```bash
kill "$(cat merod.pid)" 2>/dev/null; rm -f merod.pid
```

---

## 5. Runnable example

Unlike the Swift SDK (which ships a `MeroExample` CLI), the Kotlin SDK's runnable
demo **is the sample-app** — exercised interactively via `./run-app.sh` and under
test in §6. There is no standalone JVM/console entry point, so `test-all.sh` marks
§5 as **SKIP**. (If a `:mero-core-cli`-style module is added later, wire it into
`test-all.sh` §5.)

```bash
./run-app.sh            # fresh node on :4001 + sample-app on an emulator
./run-app.sh --mock     # in-app mock (no node) — log in with anything
./run-app.sh --help     # all flags
```

---

## 6. Instrumented UI tests — `sample-app` (Android emulator)

The Android analog of Swift's XCUITest: builds the Compose sample app and drives
login → home → RPC → logout in the emulator against an **in-app mock** (no node).

```bash
# Boot an emulator (or let the command below reuse a running one):
emulator -avd "$(emulator -list-avds | head -1)" -no-boot-anim &
adb wait-for-device

# Run the instrumented suite (in-app mock only — the node-backed classes below
# are filtered out; they run against a live node via android-e2e.sh):
./gradlew :sample-app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.calimero.mero.sample.AppE2ETest,com.calimero.mero.sample.ChatMultiUserTest
```

Expected: `BUILD SUCCESSFUL` with the instrumented suite green. CI runs this in
`.github/workflows/instrumented.yml` on every PR using
`reactivecircus/android-emulator-runner` (API 30, `default`, `x86_64`).

### In-app mock vs live node

| | In-app mock | Live node |
|---|---|---|
| Backend | a fake wired into the app | a real merod on `:4001` |
| Launch | `./run-app.sh --mock` / `am start … --ez mock true` | `./run-app.sh` |
| Instrumented tests | `instrumented.yml` (PR gate) | `android-e2e.yml` (manual/main/weekly) |
| Log in with | any username/password | the node's admin creds (`dev` / `dev-password`) |

### Node-backed app e2e (heavier)

```bash
./android-e2e.sh              # 1 node + 1 emulator: full-feature AppE2ETest
./chat-multi-e2e.sh           # 2 nodes + 2 emulators: cross-user chat (informational)
```

`chat-multi-e2e.sh` is **informational** (its CI job is `continue-on-error`):
cross-node gossipsub between two co-located merods is unreliable on a single host.

The host role logs the invite as `MERO_E2E_INVITE=<token>`; the script scrapes it from logcat and
hands it to the guest as the `invite` runner arg, which the test passes on as a launch extra so the
chat screen installs curb and joins on open (no typing a 1 KB code). `chatUser=dev1|dev2` keeps the
two emulators' chat display names apart — the login user is the admin on both nodes.

---

## 7. Backend sync checks without the app (merobox, Docker)

Two-node protocol-level checks that isolate node + WASM + sync from the app and emulator. They boot
real `merod` containers, so they need Docker:

```bash
pip install merobox
merobox bootstrap validate ci/merobox/sync-two-node.yml       # schema only, no Docker
merobox bootstrap run      ci/merobox/sync-two-node.yml       # kv_store, node 1 → node 2
merobox bootstrap run      ci/merobox/sync-two-node-bidi.yml  # both directions
```

`merobox-sync.yml` runs both kv_store scenarios as a **gating** job; `merobox-chat-sync.yml`
downloads the real published curb app and asserts a chat message syncs node 1 → node 2
(**informational** — it depends on the registry and the moving `edge` node image). Details and the
"`1111…` = wasm/node mismatch" history are in [ci/merobox/README.md](ci/merobox/README.md).

## 8. SDK ↔ sample parity

```bash
./ci/check-registry-parity.sh   # fails if a public SDK method has no SDK Explorer entry
```

The sample doubles as the SDK's living documentation, so CI enforces this. Intentional omissions
(internal plumbing, flows the UI drives) are listed with reasons in the script's `EXCLUDED`.

---

## Environment variables

| Var | Used by | Meaning |
|-----|---------|---------|
| `MERO_E2E_NODE_URL` | `RealNodeE2ETest` (§4) | node URL; unset ⇒ the test self-skips |
| `MERO_E2E_USER` | `RealNodeE2ETest` | admin username (matches `--admin-user`) |
| `MERO_E2E_PASS` | `RealNodeE2ETest` | admin password (matches the piped password) |
| `MERO_AUTH_BOOTSTRAP_SECRET` | node + test (§4) | first-login setup code (core#3221) |
| `ANDROID_HOME` | scripts, §6 | Android SDK location (`adb`, `emulator`) |
| `ANDROID_SERIAL` | scripts, §6 | target a specific emulator when several are running |

---

## Quick reference — full green pass

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"                 # §0 (if needed)
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
./gradlew assembleDebug                                          # §1
./gradlew ktlintCheck detekt testDebugUnitTest                   # §2
./gradlew lint                                                   # §3
./ci/check-registry-parity.sh                                    # §8
# §4 live e2e and §6 instrumented tests are optional / heavier — run as needed:
./test-all.sh                                                    # all of §0–§6
```

CI mirrors these: `ci.yml` (§1–§3, §8 + instrumented-test compile + merobox scenario validation),
`instrumented.yml` (§6 mock), `e2e.yml` (§4), `android-e2e.yml` (§6 live node + multi-user),
`merobox-sync.yml` / `merobox-chat-sync.yml` (§7).
