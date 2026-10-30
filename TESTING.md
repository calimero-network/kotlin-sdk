# TESTING — mero-kotlin (kotlin-sdk)

Run every command from the repo root. You need **JDK 17** and the **Android SDK**
(`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

## 1. What CI runs (`.github/workflows/ci.yml`)

```bash
./gradlew ktlintCheck detekt
./gradlew testDebugUnitTest
./gradlew lint
./gradlew assembleDebug
./gradlew :sample-app:assembleDebugAndroidTest   # instrumented tests must compile
./ci/check-registry-parity.sh                     # every public CalimeroClient method has an SDK-tab entry
```

The unit tests need no network and no node:

| Suite | What |
|---|---|
| `mero-core` `tee/CalimeroClientTest` | `CalimeroClient` against stand-in cloud nodes, through the real Noise NK sealed channel with a mock attestation: mock quotes refused unless allowed, intents/views, 503 retries with one idempotency key, single-flight refresh on 401, 421 failover, 412 confirmations, sealed SSE, consent, the app backend's mapping, `SoftPasskey` signatures. |
| `mero-core` `tee/*` | The sealed transport's wire format, DCAP, Sigstore releases, PCCS collateral, against pinned vectors. |
| `mero-core` `app/*` | `ContextContract`, `InMemoryAppBackend`. |
| `mero-core` `agentgate/*` | The agent gate client. |
| `sample-app` `ChatServiceTest` | The chat on `InMemoryAppBackend`. |

## 2. Instrumented UI tests (`instrumented.yml`, an emulator)

```bash
./gradlew :sample-app:connectedDebugAndroidTest
```

`LoginFlowTest` checks the sign-in screen's Cloud and developer paths, then runs the in-app demo
(no network) through Home, Chat and sign-out.

## 3. By hand against `merod local`

```bash
# in calimero-network/core (merod is Linux-only; on macOS run it in Docker)
cargo run -p merod -- local        # three nodes on 127.0.0.1:2501-2503, --dev, mock attestation
./gradlew :sample-app:installDebug
```

In the app: **Developer node (merod local)** → `http://10.0.2.2:2501` (the emulator's alias for
the host), a name, *Allow mock attestation* → Connect. The first time it signs up with a software
passkey kept on the device; after that it signs in with it.

A real cluster needs a passkey the cluster's relying-party domain allows for this app
(`assetlinks.json`); use the cluster URL from the Calimero Cloud console.
