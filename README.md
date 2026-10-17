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
> The sample app is an **SDK Explorer** over all 194 catalogued methods plus a native Calimero chat
> client — feature-for-feature with the Swift SDK's sample. See [Roadmap](#roadmap).

## Modules

| Module         | What it is                                                                 |
|----------------|-----------------------------------------------------------------------------|
| `mero-core`    | The SDK: the Cloud account layer (`account`, `cloud`, `relay`, `crypto` packages: `CloudAccount`, device keys, enrolment, `CloudClient` (device-proven routing plus cloud login sessions, HA and account linking), `connectCloud`, `RelayClient`, warrants), plus `Mero`, HTTP transport (OkHttp), `AuthApi` (incl. root/client keys + permissions), `AdminApi` (~140 methods), `RefreshCoordinator`, `TokenStore` (node-bound bundles), `RpcClient`, `SseClient`/`events()` + the long-lived `SseConnection` (`Mero.sse`, live subscribe/unsubscribe, typed migration/membership/app-version events), an experimental `WsClient`, ephemeral presence (`Mero.ephemeral`, device-signed `RelayPresenceClient`), `Capabilities` (+ presets, `capMask`/`capNames`), `classifyError`, local-node discovery, SSO with single-use callback state, and TEE trust (`tee`: Sigstore-verified releases, on-device DCAP, `SealedTransport`; `agentgate`: the mero-agent-gate client). |
| `mero-compose` | Optional Jetpack Compose kit, the mero-react port: `MeroClient` as the provider (Cloud sign-in, node SSO with node-URL trust and token adoption, `AppMode`, `can`, the session admin, SSE-driven `isOnline`, `MeroAppStorage`, plus a development-only `login(nodeUrl, username, password)`), a state holder and `remember*` composable for every mero-react hook (reads, writes, execute, subscriptions, presence, migrations, blobs), invitations (capture from intents, `InviteRedemption`, `InvitationDialog`, `InvitationGate`, `JoinSync`, `JoinInvitation`), and `LoginSheet` (Node and Cloud tabs), `ConnectButton`, `MeroTheme`, `CalimeroLogo`, `MigrationPendingBanner`, `MigrationAdminPanel`. |
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
  attestation behind a pluggable `RelayKeyVerifier`. The default `DcapRelayKeyVerifier` verifies
  the TDX quote **on the device**: Intel's chain (a built-in DCAP port), the signed mero-tee release
  the relay runs, every register of its image, and the nonce and key binding. Writes do not depend
  on it.
- **Sealed to the TD.** A delegated session seals every request to a hosted relay to its attested
  TD, so TLS that ends outside the enclave reads none of it (`SealedTransport`; `connectCloud`'s
  `seal`). See the [TEE trust](docs/src/content/docs/guides/tee-trust.mdx) and
  [sealed transport](docs/src/content/docs/guides/sealed-transport.mdx) guides. Private agents in a
  TD are attested and provisioned with the [agent gate](docs/src/content/docs/guides/agent-gate.mdx)
  client.

Without Compose, `CloudAccount` runs the same flow: `beginEnrolment(callbackUrl)` →
`completeEnrolment(url)` → `connect()`.

## Quick start (core, self-hosted node)

For servers, tools and tests that talk to a node you run (the Compose kit has a development-only
`MeroClient.login(nodeUrl, username, password)`; `discoverLocalNodes(host = ANDROID_EMULATOR_HOST)`
finds running dev nodes):

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

// Presence: publish your cursor, watch everyone's (replayed to late subscribers).
mero.ephemeral.set(contextId, Cursor(line = 3, col = 7))
scope.launch { mero.ephemeral.subscribe<Cursor>(contextId).collect { entry -> /* render */ } }

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
shaping, JSON-RPC unwrap/error mapping, JWT `exp` parsing, SSO callback parsing and its single-use state,
node-bound token persistence, error classification and local-node discovery. A live-node
`RealNodeE2ETest` runs in CI's `e2e.yml` (and self-skips locally unless `MERO_E2E_NODE_URL` is set).

## Roadmap

- [x] **M1** — Transport + auth core, RpcClient, token store, SSO parse/build, Compose login UI.
- [x] **M2** — Full Admin API (~110 methods): contexts, groups, namespaces, invitations, registry install.
- [x] **M3** — SSE event client (`okhttp-sse`) with auto-reconnect via `Mero.events(...)`.
- [x] **M4** — SSO in-app flow (Custom Tabs + deep-link callback) in the sample app.
- [x] **M4.5** — Cloud-only sign-in on mobile: device keys, wallet enrolment, cloud routing, relay
  login, warranted writes (golden vectors shared with mero-js).
- [x] **M4.6** — mero-js parity: single-use SSO callback state, node-bound tokens, capability
  presets, `classifyError`, local-node discovery, pinned relay keys and relay logout.
- [x] **M4.7** — mero-js parity: long-lived SSE connection with live subscriptions and
  typed group events, experimental WebSocket client, ephemeral presence (node and
  device-signed relay presence).
- [x] **M4.8** — mero-js parity: signing primitives. Owner-op root proofs (and root-signed
  owner-op calls), BIP39 account roots with store-held root signers, cloud link/login
  proofs, device scopes, and request proofs (`X-Calimero-Proof`) on HTTP, SSE and WebSocket.
- [x] **M4.9** — mero-js parity: Calimero Cloud login sessions (Google or account root),
  owned namespaces, relays and machines, HA (API and Custom Tab pages), account linking
  (direct and through the consent page), `connectCloud` / `connectCloudWithAccount`.
- [x] **M4.10** — mero-js parity: delegated accounts (multi-relay map, relay key learning,
  delegated client reading by query, account admin facade over `AdminSurface`, delegated
  governance and founding, step-typed invitation bootstrap, `redeemInvitation`) and the
  unified `MeroTransportClient` (node or relay).
- [x] **M4.11** — mero-js parity: TEE trust. Sigstore-verified mero-tee releases, on-device DCAP
  quote verification (`Dcap`, a dcap-qvl port), DCAP-verified relay node keys, the sealed
  transport (Noise NK, wire-identical with core) for `connectCloud` and delegated sessions, and
  the mero-agent-gate client (HPKE Auth mode, signed agent policies).
- [x] **M5** — mero-react parity in Compose: the provider (app modes, node SSO trust and token
  adoption, online state, account/node admin, `can`), a state holder per hook, the invitation
  flow (capture, redemption, dialog, gate, join sync), and the Node/Cloud login sheet, connect
  button, theme and migration composables.
- [ ] **M6** — Maven Central publishing, version-aligned to the mero-js contract.

## License

MIT © Calimero Network. Ports the MIT-licensed `mero-js`.
