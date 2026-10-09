# mero-kotlin — Calimero Android SDK

A native Kotlin SDK that gives an Android app the same capabilities
[`@calimero-network/mero-js`](https://github.com/calimero-network/mero-js) gives a web app, talking
to a **remote** Calimero node over HTTP(S)+SSE. No embedded node on device.

It is a faithful port of the mero-js v7.0.1 wire contract (and the mero-react v4.4.0 ergonomics), and
the Android mirror of [`calimero-network/swift-sdk`](https://github.com/calimero-network/swift-sdk).
See [`ROADMAP-TASKS/task-2-android-sdk.md`] in the planning repo for the full design.

> **Status: Calimero Cloud sign-in on mobile, plus the transport + auth core, full Admin API,
> JSON-RPC and the SSE event client.** On a phone, people sign in with their Calimero account
> (a passkey in the wallet) and the app talks to the account's hosted relay through signed
> warrants — no node URL, username or password. Server-side and tooling code can still sign in to
> a node with credentials, drive the whole
> ~140-method Admin API (contexts, groups, namespaces, invitations, registry install, root/client
> keys and permissions), call contracts over JSON-RPC, and subscribe to live node events over SSE.
> The sample app is an **SDK Explorer** over all 182 catalogued methods plus a native Calimero chat
> client — feature-for-feature with the Swift SDK's sample. See [Roadmap](#roadmap).

## Modules

| Module         | What it is                                                                 |
|----------------|-----------------------------------------------------------------------------|
| `mero-core`    | The SDK: the Cloud account layer (`account`, `cloud`, `relay`, `crypto` packages: `CloudAccount`, device keys, enrolment, `CloudClient`, `RelayClient`, warrants), plus `Mero`, HTTP transport (OkHttp), `AuthApi` (incl. root/client keys + permissions), `AdminApi` (~140 methods), `RefreshCoordinator`, `TokenStore`, `RpcClient`, `SseClient`/`events()`, `Capabilities`. |
| `mero-compose` | Optional Jetpack Compose UI kit, Cloud-only: `MeroClient` (`signInWithCloud`, `handleEnrolmentCallback`), `LoginSheet` ("Continue with Calimero"), `ConnectButton`, `MeroProvider`/`useMero`. |
| `mero-testkit` | Test-support: `FakeNode`, a stateful in-memory node on OkHttp MockWebServer, and `FakeCloud` (its cloud-manager + hosted-relay routes), for driving whole sign-in → call → sign-out journeys with no live service. |
| `sample-app`   | A Compose sample in the light Calimero style: Cloud sign-in, a native chat client and an **SDK Explorer** over the relay session, plus a deterministic mock mode that drives the instrumented UI test. |

### The sample app

A light, Calimero-styled Compose app. Sign-in is **Continue with Calimero** only; the wallet
redirects back to `mero-sample://enrol` (override with the `callbackUrl` intent extra). After that:

- **Chat** — a native chat client over the account's relay: messages are read with
  `RelayClient.call` (query) and sent as warrants (`execute`), channels are created with a creation
  warrant, and a space is joined by redeeming an invite as the account.
- **Explore SDK** — every catalogued SDK method as a searchable, categorized form, with new Session,
  Cloud and Relay groups first. A CI gate (`ci/check-registry-parity.sh`) fails the build if a
  public SDK method has no entry here.
- Technical IDs (account, device, relay, keys) sit behind "Show technical details".

Mock mode (`--ez mock true`) runs the whole flow against an in-app `FakeNode` and a mock wallet that
certifies the device with a fixed root, which is what the instrumented `LoginFlowTest` drives.

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
- Dependencies: OkHttp (+ `okhttp-sse`), kotlinx.serialization, AndroidX Security, AndroidX Browser,
  Bouncy Castle (`bcprov-jdk18on`, for Ed25519/X25519 below API 33).

Coordinates are `com.calimero.mero:{mero-core,mero-compose}:<version>`. Maven — not npm — is the
Android package registry; there's a full walkthrough in [PUBLISHING.md](PUBLISHING.md).

## Quick start: Sign in with Calimero (Compose)

Mobile sign-in is **Cloud only**. The person approves this device in the Calimero wallet (a passkey,
opened in a Chrome Custom Tab); the wallet redirects back with a certificate for a key that never
leaves the phone; the SDK finds the relay that serves the account and logs in to it.

```kotlin
val client = remember { MeroClient.create(context) }   // keys + session in EncryptedSharedPreferences
LaunchedEffect(Unit) { client.restore() }               // reconnect a persisted session

MeroProvider(client) {
    val state by client.state.collectAsStateWithLifecycle()
    if (state.isAuthenticated) MyApp() else LoginSheet(callbackUrl = "myapp://calimero-enrol")
}

// In the Activity that owns the callback intent filter (scheme myapp, host calimero-enrol,
// or an https App Link): hand the redirect over.
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.toString()?.let { url -> lifecycleScope.launch { client.handleEnrolmentCallback(url) } }
}

// Then, over the account's relay:
val relay = client.relay!!
relay.call(contextId, "get_messages")                       // a view: POST …/query; a write: a warrant
relay.execute(contextId, "send_message", buildJsonObject { put("text", "hi") })
client.mero?.events(listOf(contextId))                      // live events over the relay (Bearer)
```

- **Callback URL.** Any absolute URL the app receives works on the SDK side: an https App Link or an
  app scheme. The hosted wallet currently redirects only to `https://` callbacks; app-scheme
  callbacks need the pending mero-wallet change, so production apps should use a verified App Link.
- **Signed in without a relay.** A brand-new account is a member of nothing, so no relay serves it
  yet: `state.signedInWithoutRelay` is true and `state.relayNote` says why. Redeeming an invitation
  (`client.joinWithInvitation(namespaceId, invitation)`) admits the account and gives it a relay.
- **Relay node key.** Bearer reads and SSE need the relay's node key, learned from its TEE
  attestation behind a pluggable `RelayKeyVerifier`. The default `TlsRelayKeyVerifier` trusts TLS
  and checks the attestation is bound to this request; **full DCAP quote verification is a
  follow-up**. Writes do not depend on it.

Without Compose, `CloudAccount` runs the same flow: `beginEnrolment(callbackUrl)` →
`completeEnrolment(url)` → `connect()`.

## Quick start (core, self-hosted node)

For servers, tools and tests that talk to a node you run (not available in the Compose UI kit):

```kotlin
val mero = Mero(
    MeroConfig(
        baseUrl = "https://node.example.com",
        tokenStore = EncryptedPrefsTokenStore(context),          // Keystore-backed, survives restarts
        refreshLockFile = File(context.filesDir, "mero-refresh.lock"), // cross-process refresh lock
    ),
)

// Credential login. The admin account is created by `merod init`; there is no
// first-login setup code (core stopped honouring `bootstrap_secret`).
mero.authenticate(Credentials("alice", "s3cret"))

// Call a contract method — result decoded with kotlinx.serialization.
val summary: MigrateMyEntriesSummary = mero.rpc.migrateMyEntries(contextId)

// Drive the node's admin surface (contexts, groups, namespaces, invitations, registry install).
val contexts = mero.admin.getContexts()

// Subscribe to live node events (auto-reconnecting SSE); cancel the coroutine to close the stream.
scope.launch {
    mero.events(listOf(contextId)).collect { event -> /* e.g. re-fetch messages */ }
}

// Logout (best-effort POST /auth/logout + revoke, then local clear).
mero.logout(clientId = null)
```

### Core 0.11.0-rc.83: re-login after the node upgrade

The SDK is pinned to core `0.11.0-rc.83` ([`ci/core-version`](ci/core-version)). That release made
`key_id` a required JWT claim, so tokens minted by an older node no longer verify: after upgrading
the node, users sign in again. When `/auth/refresh` answers `401` the SDK drops the stored bundle,
so `mero.isAuthenticated` turns `false` instead of every call failing. `mero.logout()` now also
retires the refresh token on the node (`POST /auth/logout`). See [CHANGELOG.md](CHANGELOG.md) for
the full rc.41 → rc.83 delta.

### 401 → refresh, done right

Refresh tokens are **single-use** (core#3083): replaying one revokes the whole family. The SDK never
refreshes proactively — an OkHttp `Authenticator` reacts to `401 token_expired`, and
`RefreshCoordinator` serializes refresh with (1) in-process single-flight, (2) a cross-process
`FileLock`, and (3) a store re-read inside the lock so a bundle another process already rotated is
adopted rather than replayed. A terminal `x-auth-error: token_reuse|token_revoked` throws
`AuthRevokedException` and clears the store — no refresh, no retry.

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
./run-all-2.sh               # two-user stack: 2 nodes + 2 emulators, for invites/chat by hand
./android-e2e.sh             # full-feature app e2e against a live merod
./chat-multi-e2e.sh          # two-node, two-emulator chat sync e2e
./ci/check-registry-parity.sh  # every SDK method must have an SDK Explorer entry
```

Backend-level sync is checked independently of the app by two **merobox** scenarios that boot real
`merod` nodes in Docker (`ci/merobox/`, run by `merobox-sync.yml` / `merobox-chat-sync.yml`): a
kv-store write must replicate across two nodes in both directions, and a message sent in a real
`com.calimero.chat` channel on node 1 must be readable on node 2. Node image and app bundle both come
from the release pinned in [`ci/core-version`](ci/core-version). See
[ci/merobox/README.md](ci/merobox/README.md).

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
- [x] **M4.5** — Cloud-only sign-in on mobile: device keys, wallet enrolment, cloud routing, relay
  login, warranted writes (golden vectors shared with mero-js).
- [ ] DCAP / TDX quote verification for the relay node key (today: TLS + binding checks).
- [ ] **M5** — More Compose hooks (`useSubscription`, `useMigrationStatus`, …).
- [ ] **M6** — Maven Central publishing, version-aligned to the mero-js contract.

## License

MIT © Calimero Network. Ports the MIT-licensed `mero-js`.
