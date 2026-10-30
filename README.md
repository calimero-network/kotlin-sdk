# mero-kotlin — Calimero Android SDK

The Android client for **Calimero 1.0**, the confidential cloud. It gives an Android app what
[`@calimero-network/mero-js`](https://github.com/calimero-network/mero-js) ≥ 27 gives a web app,
with the same names: `CalimeroClient` verifies a node's TDX attestation, seals every request to it
(`calimero/sealed-http/v2`, Noise NK), signs people in with **passkeys**, and calls apps'
**intents** and **views**. It is the Android twin of
[`calimero-network/swift-sdk`](https://github.com/calimero-network/swift-sdk).

> **Calimero 1.0 only.** The 0.x API (`/admin-api`, `/jsonrpc`, `/ws`, `/sse`, `/auth/token`,
> relays, delegated execution, `/api/cloud/*`) is gone from core, and from this SDK. See
> [Migrating from 0.x](https://calimero-network.github.io/kotlin-sdk/reference/migrating-from-0x/).

## Modules

| Module | What it is |
|---|---|
| `mero-core` | `CalimeroClient` and its typed errors (`AppError`, `ConfirmRequired`, `WrongNode`, `LoginRequired`, `NotFound`, `UnavailableError`, …); attestation (`CloudPolicy`, `fetchAttestedKey`: mock quotes only when allowed, real quotes checked against Sigstore-signed mero-tee releases and Intel's DCAP collateral on the device); the sealed transport (`tee.SealedTransport`); passkeys (`PasskeyProvider`, `SoftPasskey`); approvals and the consent page; `SessionStore`; and the app-facing layer in `app` (`MeroAppBackend`, `ContextContract`, `CalimeroAppBackend`, `OrgInvitation`). Also the mero-agent-gate client (`agentgate`). |
| `mero-compose` | `MeroAppSession` (a mobile app's signed-in account over `CalimeroAppBackend`), `PlatformPasskey` (Android Credential Manager), `ConsentLauncher` (the node's consent page in a Custom Tab), `MeroTheme`. |
| `mero-testkit` | `InMemoryAppBackend` and `RecordingContextContract`: test an app's view models with no cluster. |
| `sample-app` | **Mero Chat** sample in the light Calimero style: Home / Chat / SDK tabs. |

### The sample app

- **Sign in**: a passkey on a Calimero Cloud cluster (enter the cluster URL from the console;
  sign in, or create an account), or **Developer node** for `merod local`
  (`http://10.0.2.2:2501` from the emulator) with *Allow mock attestation* and a software passkey
  kept (encrypted) on the device. Debug builds also offer an in-app demo on `InMemoryAppBackend`.
- **Home**: the account and node, your **organizations and teams** (create one with a `general`
  team, copy an invite link, redeem one), and **approvals** (node releases your scope has not
  approved), which open the node's consent page in a Custom Tab; it returns to
  `mero-sample://consent`.
- **Chat**: mero-chat (`com.calimero.chat`) on the app-facing layer: spaces are organizations,
  channels are app instances, messages are views and calls, live updates are the app's events.
- **SDK**: every public `CalimeroClient` method as a form (`ci/check-registry-parity.sh` keeps
  that true).

## Install

```kotlin
dependencies {
    implementation("com.calimero.mero:mero-core:<version>")
    implementation("com.calimero.mero:mero-compose:<version>")  // MeroAppSession, passkeys, consent
    testImplementation("com.calimero.mero:mero-testkit:<version>")
}
```

Kotlin 2.x, coroutines and `Flow`; minSdk 24, compileSdk 34. See [PUBLISHING.md](PUBLISHING.md).

## Quick start

```kotlin
val client = CalimeroClient(
    CalimeroClientOptions(
        baseUrl = clusterUrl,
        nodes = otherNodes,                    // failover on 421 WRONG_NODE
        policy = CloudPolicy.production,       // real TDX quotes, mero-tee >= 2.3.109
        onSession = store::save,               // keep the newest session: refresh tokens are single-use
    ),
)
client.signIn(PlatformPasskey(activity))

val org = client.createOrg("Acme")
val app = client.createApp("general", AppSource.Package("com.calimero.chat"), org = org, initArgs = args)
client.call(app, "send_message", message)              // retried on 503 with one idempotency key
client.view(app, "get_messages", page).result
client.events(app).collect { /* sealed SSE */ }
```

For `merod local`: `CloudPolicy.development` (`allowMock = true`) and a `SoftPasskey("localhost",
"http://localhost:3000")`.

### A mobile app's session

```kotlin
val session = MeroAppSession.create(app, "com.example.board", callbackUrl = "board://consent",
    config = MeroAppConfig(nodeUrl = clusterUrl), scope = viewModelScope)
viewModelScope.launch { session.restore() }
session.signIn(activity)                              // the passkey sheet
session.backend.collect { backend -> /* MeroAppBackend: orgs, teams, app instances, contract */ }
```

`MeroAppBackend` keeps the 0.x method names apps were written against (`listNamespaces`,
`createSubgroup`, `createContext`, `joinNamespace`, …), mapped onto organizations, teams and app
instances; see the [App session guide](https://calimero-network.github.io/kotlin-sdk/guides/app-session/).

## Build & test

```bash
./gradlew ktlintCheck detekt testDebugUnitTest lint assembleDebug   # what CI runs
./gradlew :sample-app:assembleDebugAndroidTest                       # instrumented tests compile
./gradlew :sample-app:connectedDebugAndroidTest                      # the demo UI test (an emulator)
./ci/check-registry-parity.sh                                        # every client method in the SDK tab
```

`CalimeroClientTest` drives the client against stand-in cloud nodes through the **real** sealed
channel and a mock attestation: retries with one idempotency key, single-flight refresh on `401`,
failover on `421 WRONG_NODE`, `412` confirmations, sealed SSE, the consent request, and the app
backend's org/team/app mapping. See [TESTING.md](TESTING.md).

Docs: <https://calimero-network.github.io/kotlin-sdk/> (sources in `docs/`).

## License

MIT © Calimero Network. Ports the MIT-licensed `mero-js`.
