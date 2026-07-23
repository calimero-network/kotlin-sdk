# Changelog

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
