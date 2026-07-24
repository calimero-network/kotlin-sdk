# Changelog

## Unreleased — swift-sdk parity pass

Brings the Android SDK and sample level with `calimero-network/swift-sdk` as of its
`syncGroupContexts` / clean-landing work.

### mero-core
- `AuthApi`: `createRootKey`, `deleteRootKey`, `generateClientKey`, `deleteClientKey`,
  `getKeyPermissions`, `updateKeyPermissions` (core's `{ add, remove }` delta, not a replacement),
  and `generateMockTokens` — with their wire types.
- `AdminApi.syncGroupContexts(groupId)`: sync the group, then join + state-pull each of its contexts,
  so a freshly joined group initializes instead of staying on the all-ones uninitialized hash.
  Clients no longer reimplement this (and hit the trap).

### sample-app
- **Landing screen**: Open Chat Example + Explore SDK entries (with method/category counts), replacing
  the flat op list; the SDK surface now lives behind a **searchable list of collapsible categories**
  (search auto-expands matches).
- **Diagnostics screen** for the session log (clear / copy), reachable from the landing and the login
  screen; login screen gets the brand mark and a spaced-out form.
- **Chat**: joined spaces are listed (no more filtering by our own app id, which hid invited spaces);
  uninitialized contexts are joined + synced on load; joining retries `syncGroupContexts` six times
  with visible progress and registers the display name per context; "Sync now" + a diagnostic empty
  state that distinguishes "curb not installed" from "0 peers" from "still syncing"; recursive
  invitations are accepted; blocking busy overlay; single-line invite field with paste; invite sheet
  with Copy/Share; deterministic avatar colors; auto-scroll to the newest message.
- Registry: 12 more operations (root/client keys + permissions, `generateTokens`, `generateMockTokens`,
  `removeGroupMembers`, `setContextMetadata`, `syncGroup`, `syncGroupContexts`) — 127 total.
- e2e hooks as launch extras: `chatUser` (chat display name) and `invite` (auto-join on open) —
  the Android analogs of the Swift harness's `E2E_USERNAME` / `E2E_JOIN`.

### Tests / CI
- `AdminSyncContextsTest` asserts the sync/join/state-pull call sequence.
- Merobox two-node checks ported from swift-sdk: `merobox-sync.yml` (kv_store forward + bidirectional,
  gating) and `merobox-chat-sync.yml` (real published curb app, informational), with `ci/merobox/`
  scenarios and the node-matched `kv_store.wasm`.
- CI additionally compiles the instrumented tests, validates the merobox scenarios, and runs
  `ci/check-registry-parity.sh` so no SDK method can be added without an SDK Explorer entry.
- ktlint is now **enforcing** (`ignoreFailures = false`) after a full `ktlintFormat` pass; detekt's
  `LargeClass` is off for the deliberately-large `AdminApi`. Actions bumped to Node-24 runtimes.

## 0.1.0 (unreleased)

Initial drop — M1 (transport + auth core) plus a Compose login UI kit. Ports the mero-js v7.0.1 wire
contract and mirrors `calimero-network/swift-sdk`.

### mero-core
- `Mero` top-level client: `authenticate`, `setTokenData`, `getTokenData`, `clearToken`, `logout`,
  `isAuthenticated`, plus `auth`/`rpc` sub-clients and static SSO helpers.
- OkHttp transport with Bearer injection, reactive `401 token_expired` → refresh via `Authenticator`,
  and terminal `x-auth-error` → `AuthRevokedException` + store clear.
- `RefreshCoordinator`: single-use-refresh-token safety (single-flight + cross-process `FileLock` +
  store re-read inside the lock).
- `AuthApi` (`generateTokens`, `refreshToken`, `validateToken`, providers/health/identity, key lists),
  `RpcClient` (`execute` with `executorPublicKey`, `migrateMyEntries`, `countMyPending`), `Capabilities`.
- `AdminApi` — full ~106-method port of the node's admin surface: contexts, context identities,
  groups & subgroups, members/capabilities, namespaces, invitations (single + recursive), registry
  install/versions, blobs, aliases, metadata, TEE, upgrades/migrations. Faithful 1:1 with the Swift SDK.
- `SseClient` + `Mero.events(contextIds)` — auto-reconnecting Server-Sent-Events stream as a `Flow`,
  subscribing on `connect` and reconnecting on drop/`close` (powers live chat updates without polling).
- `TokenStore` with `MemoryTokenStore` and Keystore-backed `EncryptedPrefsTokenStore`.
- SSO: `parseAuthCallback`, `buildAuthLoginUrl`, `SsoLauncher` (Chrome Custom Tabs).

### mero-compose
- `MeroClient` (observable auth `StateFlow`), `MeroProvider`/`useMero`, `LoginSheet`, `ConnectButton`.

### mero-testkit
- `FakeNode` — a stateful in-memory Calimero node on OkHttp MockWebServer (rotating/single-use tokens,
  expire/revoke controls, call counters, canned RPC outputs) for node-free tests and the app's mock mode.

### sample-app
- Mock mode (deterministic login → home → RPC → logout against an in-app `FakeNode`) driving the
  instrumented UI test, plus an **SDK Explorer + native chat client** (install curb from the registry,
  spaces/channels, send/read messages, invite & join, live SSE) against a real node, with hosted SSO.

### Tests / CI
- Unit + mock suites (`EndToEndMockTest`, `HttpAndAuthTest`, `AdminApiRequestTest`) on `FakeNode`;
  a live-node `RealNodeE2ETest` (self-skips without `MERO_E2E_NODE_URL`); instrumented UI + chat e2e.
- Workflows: `e2e.yml` (live merod), `instrumented.yml` (emulator UI test, PR gate),
  `android-e2e.yml` (single- & multi-node app e2e); dev/test scripts (`run-app.sh`, `android-e2e.sh`,
  `chat-multi-e2e.sh`, `test-all.sh`) and `TESTING.md`.

### Tooling
- Gradle 8.7, AGP 8.5, Kotlin 2.0; ktlint + detekt; GitHub Actions CI (lint, unit tests, lint, assemble).
