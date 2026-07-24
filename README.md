# mero-kotlin — Calimero Android SDK

A native Kotlin SDK that gives an Android app the same capabilities
[`@calimero-network/mero-js`](https://github.com/calimero-network/mero-js) gives a web app, talking
to a **remote** Calimero node over HTTP(S)+SSE. No embedded node on device.

It is a faithful port of the mero-js v7.0.1 wire contract (and the mero-react v4.4.0 ergonomics), and
the Android mirror of [`calimero-network/swift-sdk`](https://github.com/calimero-network/swift-sdk).
See [`ROADMAP-TASKS/task-2-android-sdk.md`] in the planning repo for the full design.

> **Status: transport + auth core, full Admin API, JSON-RPC, and the SSE event client — with a
> full-feature sample app.** Apps can sign in (credentials or hosted SSO), drive the whole
> ~110-method Admin API (contexts, groups, namespaces, invitations, registry install, root/client
> keys and permissions), call contracts over JSON-RPC, and subscribe to live node events over SSE.
> The sample app is an **SDK Explorer** over all 127 catalogued methods plus a native Calimero chat
> client — feature-for-feature with the Swift SDK's sample. See [Roadmap](#roadmap).

## Modules

| Module         | What it is                                                                 |
|----------------|-----------------------------------------------------------------------------|
| `mero-core`    | The SDK: `Mero`, HTTP transport (OkHttp), `AuthApi` (incl. root/client keys + permissions), `AdminApi` (~110 methods), `RefreshCoordinator`, `TokenStore`, `RpcClient`, `SseClient`/`events()`, SSO utils, `Capabilities`. |
| `mero-compose` | Optional Jetpack Compose UI kit: `MeroProvider`/`useMero`, `LoginSheet`, `ConnectButton`, `MeroClient`. |
| `mero-testkit` | Test-support: `FakeNode`, a stateful in-memory node on OkHttp MockWebServer, for driving a whole login → call → refresh → logout journey with no live node. |
| `sample-app`   | A Compose sample with two modes: a deterministic mock login→home→RPC→logout flow (drives the instrumented UI test), and an **SDK Explorer + native chat client** that signs in to a real node and exercises the Admin API, RPC, and live SSE. |

### The sample app

The landing screen offers two entries, mirroring the Swift sample:

- **Open Chat Example** — a native [curb](https://github.com/calimero-network/mero-chat) client:
  install the app from the registry, create/join spaces (namespaces) and channels (subgroup +
  context), send and read messages over contract RPC with live SSE updates, and share compact
  invite codes. Joining runs `AdminApi.syncGroupContexts`, so a joined space's contexts actually
  initialize instead of sitting on the all-ones uninitialized hash.
- **Explore SDK** — every catalogued SDK method as a searchable, categorized form: fill the fields,
  Run, read the pretty-printed response. A CI gate (`ci/check-registry-parity.sh`) fails the build
  if a public SDK method has no entry here.

A **Diagnostics** screen (the `>_` button) shows every request/auth event the session recorded,
copyable — so a failed connection is debuggable without Android Studio attached.

## Install

Published to **GitHub Packages** on each `v*` tag (see [PUBLISHING.md](PUBLISHING.md) for the
consumer repository + credentials snippet, and the Maven Central roadmap):

```kotlin
dependencies {
    implementation("com.calimero.mero:mero-core:0.1.0")
    implementation("com.calimero.mero:mero-compose:0.1.0") // optional UI kit
}
```

- **Kotlin 2.x**, coroutines + `Flow` throughout.
- **minSdk 24**, compileSdk 34.
- Dependencies: OkHttp (+ `okhttp-sse`), kotlinx.serialization, AndroidX Security, AndroidX Browser.

Coordinates are `com.calimero.mero:{mero-core,mero-compose}:<version>`. Maven — not npm — is the
Android package registry; there's a full walkthrough in [PUBLISHING.md](PUBLISHING.md).

## Quick start (core)

```kotlin
val mero = Mero(
    MeroConfig(
        baseUrl = "https://node.example.com",
        tokenStore = EncryptedPrefsTokenStore(context),          // Keystore-backed, survives restarts
        refreshLockFile = File(context.filesDir, "mero-refresh.lock"), // cross-process refresh lock
    ),
)

// Credential login (bootstrap_secret is included only on a fresh node's first login).
mero.authenticate(Credentials("alice", "s3cret", bootstrapSecret = "setup-code"))

// Call a contract method — result decoded with kotlinx.serialization.
val summary: MigrateMyEntriesSummary = mero.rpc.migrateMyEntries(contextId)

// Drive the node's admin surface (contexts, groups, namespaces, invitations, registry install).
val contexts = mero.admin.getContexts()

// Subscribe to live node events (auto-reconnecting SSE); cancel the coroutine to close the stream.
scope.launch {
    mero.events(listOf(contextId)).collect { event -> /* e.g. re-fetch messages */ }
}

// Logout (best-effort server revoke + local clear).
mero.logout(clientId = null)
```

### 401 → refresh, done right

Refresh tokens are **single-use** (core#3083): replaying one revokes the whole family. The SDK never
refreshes proactively — an OkHttp `Authenticator` reacts to `401 token_expired`, and
`RefreshCoordinator` serializes refresh with (1) in-process single-flight, (2) a cross-process
`FileLock`, and (3) a store re-read inside the lock so a bundle another process already rotated is
adopted rather than replayed. A terminal `x-auth-error: token_reuse|token_revoked` throws
`AuthRevokedException` and clears the store — no refresh, no retry.

## Quick start (Compose)

```kotlin
val client = remember { MeroClient.create(context, "https://node.example.com") }

MeroProvider(client) {
    val state by client.state.collectAsStateWithLifecycle()
    if (state.isAuthenticated) MyApp() else LoginSheet(showBootstrapSecret = true)
}
```

`MeroClient` also handles the SSO deep-link callback (`handleAuthCallback(url)`), trust-checking the
callback's `node_url` before storing any token.

## Build & test

```bash
./gradlew ktlintCheck detekt              # lint (ktlint 1.x, enforcing + detekt)
./gradlew testDebugUnitTest               # JVM unit + mock tests (FakeNode / MockWebServer)
./gradlew lint                            # Android Lint
./gradlew assembleDebug                   # build all modules + sample
./gradlew :sample-app:connectedDebugAndroidTest  # instrumented UI test (needs an emulator)
```

Or run everything (build → unit → lint → live-node e2e → instrumented UI) with a PASS/FAIL summary:

```bash
./test-all.sh                # add --skip-e2e / --skip-ui to skip the node/emulator sections
./run-app.sh                 # build, boot a local node, install & launch the sample on an emulator
./android-e2e.sh             # full-feature app e2e against a live merod
./chat-multi-e2e.sh          # two-node, two-emulator chat sync e2e
./ci/check-registry-parity.sh  # every SDK method must have an SDK Explorer entry
```

Backend-level sync is checked independently of the app by two **merobox** scenarios that boot real
`merod` nodes in Docker (`ci/merobox/`, run by `merobox-sync.yml` / `merobox-chat-sync.yml`): a
kv_store write must replicate across two nodes in both directions, and a message sent in a real curb
channel on node 1 must be readable on node 2. See [ci/merobox/README.md](ci/merobox/README.md).

See **[TESTING.md](TESTING.md)** for the full matrix (mock vs live node, env vars, emulator setup).
Unit + mock tests cover the highest-risk logic: single-flight/cross-process refresh, the terminal
`x-auth-error` path, the full login→call→refresh→logout journey via `FakeNode`, Admin API request
shaping, JSON-RPC unwrap/error mapping, JWT `exp` parsing, and SSO callback parsing. A live-node
`RealNodeE2ETest` runs in CI's `e2e.yml` (and self-skips locally unless `MERO_E2E_NODE_URL` is set).

## Roadmap

- [x] **M1** — Transport + auth core, RpcClient, token store, SSO parse/build, Compose login UI.
- [x] **M2** — Full Admin API (~110 methods): contexts, groups, namespaces, invitations, registry install.
- [x] **M3** — SSE event client (`okhttp-sse`) with auto-reconnect via `Mero.events(...)`.
- [x] **M4** — SSO in-app flow (Custom Tabs + deep-link callback) in the sample app.
- [ ] **M5** — More Compose hooks (`useSubscription`, `useMigrationStatus`, …).
- [ ] **M6** — Maven Central publishing, version-aligned to the mero-js contract.

## License

MIT © Calimero Network. Ports the MIT-licensed `mero-js`.
