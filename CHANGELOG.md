# Changelog

## Unreleased — core 0.11.0-rc.32

Verified against a real `merod 0.11.0-rc.32`, and against a second one for the join.

### Breaking — the SDK could not do these against a current node

- **`InstallApplicationRequest` is `{package, version}`.** core#3652 (rc.31) made
  distribution registry-only: the node fetches from its own `[registry]`, and the
  request carries `deny_unknown_fields`, so the old body naming `url` is
  **refused** — `400 unknown field \`url\`, expected \`package\` or \`version\``.
  `installFromRegistry(registryUrl, package, version)` loses its first argument;
  discover versions with `listPackageVersions` / `getLatestPackageVersion`, which
  ask the node and therefore answer for the same registry the install will use.
  `InstallDevApplicationRequest` is `{path}` alone, and the path must be an
  `.mpk` — a raw `.wasm` is refused with *"not a signed application bundle"*.

- **Invitations are carried verbatim.** `SignedGroupOpenInvitation` and
  `GroupInvitationFromAdmin` now hold the object core sent and re-encode it key
  for key, with typed accessors over it. Naming only the fields the model knew
  dropped the rest on re-encode — including **`admitters`**, which is inside the
  *signed* body and which core#3714 (rc.29) made non-empty on every invitation.
  The node re-encodes to borsh and checks the signature, so on one rc.32 pair,
  same namespace, two freshly minted invitations:
  `stripped → 500 invalid invitation signature` · `verbatim → 200, joined`.

- **`upgradePolicy` is gone** from `Namespace`, `GroupInfo`, `CreateNamespaceRequest`
  and `CreateGroupRequest`, along with the `UpgradePolicy` enum (core#3485 — it
  always held `"LazyOnAccess"`). Declared required, it threw
  `MissingFieldException` on every `listNamespaces` / `getNamespace` /
  `getGroupInfo`; `ignoreUnknownKeys` cannot help with a *missing* field.
  `Namespace` gains `appVersion`, `GroupInfo` gains `groupStateHash`.

- **Timestamps and sizes are `Long`.** They are `u64` in core.
  `MetadataRecord.updatedAt` is wall-clock **milliseconds** and overflowed a
  32-bit `Int` on the first real body carrying a metadata record
  (`Failed to parse int for input '1788952411519'`), taking `getGroupInfo` and
  all three metadata getters with it. Also `size` / `sizeInBytes` (a blob may
  exceed 2 GB), `createdAt`, `initiatedAt`, `completedAt`, `reportedAt`,
  `expirationTimestamp`.

- **`syncContext(null)` posted to a path that 404s.** It built
  `"/contexts/sync/${contextId ?: ""}"`, and axum 0.8 (core rc.30, #3744) stopped
  matching a trailing slash to its route — `POST /contexts/sync` is 200,
  `POST /contexts/sync/` is **404**. So "sync every context" broke at rc.30 on a
  call that had worked for every release before it. The two paths are built
  separately now.

- **Four methods removed — their routes do not exist.** Probed live:
  `getNamespaceIdentity` (404, deleted rc.23 core#3522 — use `getNodeIdentity`),
  `registerGroupSigningKey` (404, rc.21 core#3439),
  `inviteSpecializedNode` (405, rc.17 core#3267 — the path now matches
  `/contexts/:id`, so it resolves to a *different* route), and
  `updateGroupSettings` (405; its only field was `upgradePolicy`).

- **The alias list routes answer a map, and there is no `identity` scope.**
  `GET /alias/list/{context,application,device}` return
  `{"data": {"<alias>": "<value>"}}` (`{}` when empty), not `{aliases: […]}` —
  modeled as a list, all three list methods threw
  `MissingFieldException: Field 'aliases' is required` on **every** call, so they
  had never worked. And the four `alias/*/identity/{contextId}` methods are gone:
  that scope answers 404. `device` — the scope core actually serves — is added in
  its place (`createDeviceAlias` / `lookupDeviceAlias` / `deleteDeviceAlias` /
  `listDeviceAliases`).

- **`getCertificate()` returns `String?`.** A node with no TLS certificate answers
  `404 Certificate not found`, which is an absence, not a failure — it used to
  `ensureSuccessful()` and throw on any plain `merod init` node.

- **`Credentials.bootstrapSecret` removed**, with `MeroClient.login`'s third
  parameter and `LoginSheet`'s `showBootstrapSecret`. `merod init` creates the
  admin account, and rc.29 parses `bootstrap_secret` only to discard it.

### Added — surface from rc.23 to rc.32

`getNodeIdentity` (incl. rc.32's `holdsAccountRoot`) · `isReady` ·
`getApplicationAbi` · `performIntent` · `listMemberDevices` ·
`listAccountDevices` · `listAccountApplications` · `pairInit` · `pairComplete` ·
`relinkDevice` · `revokeAccountDevice` · `admitJoin`, and **`admitters` +
`admitterAddrs`** on both invite requests.

⚠️ `admitterAddrs` is silent if missed: rc.32 renamed it from `admitterHints`, and
the request is not `deny_unknown_fields`, so a node ignores the old key and
answers 200 — an unfixed client quietly mints invitations no joiner can dial,
just as core#3804 made the `admitters` those addresses point at an authorization
boundary rather than a hint.

⚠️ `listAccountDevices`, `listAccountApplications` and `listMemberDevices` answer
**flat** — `{devices:[…]}` / `{applications:[…]}` / `{members:[…]}`, no `data`
envelope — unlike every route around them.

### sample-app

- Chat installs **`com.calimero.chat`**: `com.calimero.curb` is no longer
  published (the registry answers `[]`). Same `init` params, but `send_message`
  has **no `sender_username`** argument — passing curb's panics the guest at
  argument deserialization. "Not published" is now reported as itself instead of
  leaving the UI on the install gate.
- SDK Explorer gains an **Accounts & devices** category and entries for every new
  method, so `check-registry-parity.sh` stays green.

### Docs

- **The SSE event kind is `StateMutation`, not `ExecutionEvent`.** The events guide
  showed a `when (event.kind)` branching on `"ExecutionEvent"`, which no node sends,
  so that branch never fired. A live rc.32 node sends `StateMutation` (the context
  state moved) and `SyncStatus`; the contract's own events sit under
  `data.events[]`, each with its own `kind`. Captured from the wire. The chat sample
  was unaffected because it reloads on any event rather than branching.

### Testing

Response bodies captured verbatim from the live node live in
`mero-core/src/test/resources/fixtures/`. A fixture written to match the model can
only confirm the model agrees with itself — which is how both this and the rc.25
`groupId`→`namespaceId` rename got past a green suite. 78 tests, and
`RealNodeE2ETest` now drives the new surface against a real node, and
**decode-sweeps every read that needs no id** — which is what caught the alias
map and the certificate 404 after the hand-picked checks had all passed.

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
