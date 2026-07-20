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
  `RpcClient` (`execute`, `migrateMyEntries`, `countMyPending`), `Capabilities`.
- `TokenStore` with `MemoryTokenStore` and Keystore-backed `EncryptedPrefsTokenStore`.
- SSO: `parseAuthCallback`, `buildAuthLoginUrl`, `SsoLauncher` (Chrome Custom Tabs).

### mero-compose
- `MeroClient` (observable auth `StateFlow`), `MeroProvider`/`useMero`, `LoginSheet`, `ConnectButton`.

### Tooling
- Gradle 8.7, AGP 8.5, Kotlin 2.0; ktlint + detekt; GitHub Actions CI (lint, unit tests, lint, assemble).
