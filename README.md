# mero-kotlin — Calimero Android SDK

A native Kotlin SDK that gives an Android app the same capabilities
[`@calimero-network/mero-js`](https://github.com/calimero-network/mero-js) gives a web app, talking
to a **remote** Calimero node over HTTP(S)+SSE. No embedded node on device.

It is a faithful port of the mero-js v7.0.1 wire contract (and the mero-react v4.4.0 ergonomics), and
the Android mirror of [`calimero-network/swift-sdk`](https://github.com/calimero-network/swift-sdk).
See [`ROADMAP-TASKS/task-2-android-sdk.md`] in the planning repo for the full design.

> **Status: M1 (transport + auth core) + login UI.** This first drop covers the pieces apps need to
> sign in and call contracts: HTTP transport, the auth/refresh state machine, token storage, JSON-RPC,
> SSO deep-link parsing, and Compose login components. The full ~70-method Admin API, streaming blobs,
> and the SSE event client are scaffolded follow-ups (M2–M3) — see [Roadmap](#roadmap).

## Modules

| Module         | What it is                                                                 |
|----------------|-----------------------------------------------------------------------------|
| `mero-core`    | The SDK: `Mero`, HTTP transport (OkHttp), `AuthApi`, `RefreshCoordinator`, `TokenStore`, `RpcClient`, SSO utils, `Capabilities`. |
| `mero-compose` | Optional Jetpack Compose UI kit: `MeroProvider`/`useMero`, `LoginSheet`, `ConnectButton`, `MeroClient`. |
| `sample-app`   | A Compose sample that connects to a node, logs in, and logs out.            |

## Install

Once published (Maven Central / GitHub Packages):

```kotlin
dependencies {
    implementation("com.calimero.mero:mero-core:0.1.0")
    implementation("com.calimero.mero:mero-compose:0.1.0") // optional UI kit
}
```

- **Kotlin 2.x**, coroutines + `Flow` throughout.
- **minSdk 24**, compileSdk 34.
- Dependencies: OkHttp (+ `okhttp-sse`), kotlinx.serialization, AndroidX Security, AndroidX Browser.

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
./gradlew ktlintCheck detekt   # lint (ktlint 1.x + detekt)
./gradlew testDebugUnitTest    # JVM unit tests (MockWebServer)
./gradlew lint                 # Android Lint
./gradlew assembleDebug        # build all modules + sample
```

Unit tests cover the highest-risk logic: single-flight/cross-process refresh, the terminal
`x-auth-error` path, JSON-RPC unwrap/error mapping, JWT `exp` parsing, and SSO callback parsing.

## Roadmap

- [x] **M1** — Transport + auth core, RpcClient, token store, SSO parse/build, Compose login UI.
- [ ] **M2** — Full Admin API (~70 methods) + streaming blob up/download.
- [ ] **M3** — SSE event client (`okhttp-sse`) with reconnect + backgrounding.
- [ ] **M4** — SSO in-app flow polish (Custom Tabs + callback Activity helper).
- [ ] **M5** — More Compose hooks (`useSubscription`, `useMigrationStatus`, …).
- [ ] **M6** — Maven Central publishing, version-aligned to the mero-js contract.

## License

MIT © Calimero Network. Ports the MIT-licensed `mero-js`.
