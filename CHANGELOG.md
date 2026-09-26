# Changelog

## Unreleased — core 0.11.0-rc.44

Pinned to `0.11.0-rc.44` in `ci/core-version`, so every node lane — the two
merobox sync scenarios, the chat-sync scenario and the `merod`-booting e2e jobs —
boots that release.

### rc.41 → rc.44: no wire change

`calimero-server-primitives` (every admin request and response type) is
byte-identical between rc.41 and rc.44, and no route this SDK calls moved. The
rest is additive or server-side: delegated sessions may read the namespaces,
groups and contexts they are in (core#4038/#4039/#4042/#4045), not-found /
not-a-member errors answer 404/403 instead of 500 (core#4007), and
`account/link-proof` became `account/sign-with-root` (core#3992) — never called
here. `merod --public-intents` is now `--delegated-access` (core#4048); no script
in this repo passes it.

## Superseded — core 0.11.0-rc.41

### Breaking — the event transports now need a permission

core rc.41 (core#3942) mapped `/sse`, `/sse/subscription`, `/sse/session/{id}`
and `/ws` to **`context:subscribe`**. They required *no* permission before, so a
token minted for any purpose at all could open a stream.

- **`context:subscribe` is not implied by `context`.** core's
  `ContextPermission::All` arm matches only another `All`. `admin` does cover it,
  which is what `mero.authenticate()` asks for — so credential login is
  unaffected. A **scoped** SSO login (`AuthLoginOptions.permissions`) must name
  `context:subscribe` or every stream is refused.

- **A `403` on the stream is now terminal.** `SseClient` treated every failure
  alike and reconnected after three seconds, forever. Both of the things that
  produce a 403 here — a token without the grant, and a revoked refresh family
  (which core answers with `x-auth-error` and an EMPTY body) — are permanent, so
  that loop was a silent outage: a live-looking subscription delivering nothing,
  with no exception ever reaching the collector. A revoked family now throws
  `AuthRevokedException`, any other 403 a plain `HttpException`. Everything that
  is not a 403 still reconnects.

  The subscription POST is covered too: the stream can open and the *subscribe*
  still be refused, which used to read as "this context is quiet".

### Added — rc.39 → rc.41 surface

The admin request surface is otherwise **additive** across rc.38 → rc.41: no
existing request struct lost, renamed or closed a field. What is new:

- `rescopeDevice(deviceId, RescopeDeviceRequest)` — `PUT
  /admin-api/account/devices/{deviceId}/scope`. The direction `relinkDevice`
  cannot go: relink is add-only, this replaces. Body `{scope}`, where a
  `DeviceScope` is the bare string `"all"` or `{"only":[…]}` — serde's
  externally-tagged enum. An empty `only` is a `400`, deliberately, rather than
  the narrowest slip becoming the widest grant. Must run on the node holding the
  account root.
- `labelDevice(deviceId, LabelDeviceRequest)` — `PUT
  /admin-api/account/devices/{deviceId}/label`. Body `{label}`. The name
  replicates and comes back on `AccountDevice.label`; `labelEpoch` orders it
  against a rename made elsewhere at the same moment.
- `AccountDevice.label` — the name above, `null` while a device has none.
- `NodeIdentity.revokedFrom` — which account withdrew this node's device, `null`
  on a node no revocation has reached. A node reading this non-null still speaks
  locally, but nothing it publishes is accepted.
- `GetContextIdentitiesResponseData.identitiesOf` — `members` / `node` /
  `caller`. rc.41 made `identities-owned` mean *"the identities **you** can act
  as here"* for a delegated caller while keeping the node-wide reading for a
  node-owner session, so the same call on the same context answers differently
  per token. Kept a `String` rather than an enum so a reading a later core adds
  decodes instead of throwing; `null` from a node predating the field.

### Tests

`Rc41WireShapeTest` asserts the **exact key set** of both new bodies, from their
first commit rather than after the first outage — the two routes arrived
`deny_unknown_fields`. A `contains` assertion would pass with a fatal key beside
it, which is how `requester` rode eighteen bodies undetected until rc.38.

It also pins both directions of each added response field: the field decodes, and
a body from a node predating it — which **omits** the key, because core skips
rather than nulls it — still decodes, to `null`.

`SseForbiddenTest` covers the three outcomes that used to be one: a bare 403
fails the flow after exactly one attempt, a revoked family surfaces as
`AuthRevokedException`, and a 500 still reconnects.

## Superseded — core 0.11.0-rc.38

Verified against a real `merod 0.11.0-rc.38`.

### Breaking — keys core now refuses

core rc.32 → rc.38 added `deny_unknown_fields` to **37** admin request structs
that had been permissive. Nothing about this SDK changed; what changed is that
an extra key it had been sending — tolerated and ignored for releases — became
a 400 or 422 for the whole call.

- **`CreateGroupInNamespaceRequest` is `{groupName, visibility}`.**
  `POST /admin-api/namespaces/{id}/groups` is not the group-create body; it
  reads `groupName` and `visibility`. This request said `name` and `groupId`,
  and **both** are refused — `422 unknown field \`name\`, expected \`groupName\`
  or \`visibility\``. So the only call that ever worked was the one that named
  nothing: naming a subgroup, the reason to pass a request at all, has been
  failing outright. The sample app's "create channel" hit exactly this.
  `visibility` is new here and saves the follow-up
  `setSubgroupVisibility` call.

- **`requester` is gone from every request type (18 of them).** core has never
  had such a field — not at rc.32, not at rc.38. It was omitted whenever null
  (`explicitNulls = false`), which is why it never showed: harmless until a
  caller set it, then a 400 for the whole call. Six types held nothing else and
  are now empty `@Serializable class`es, so `SyncGroupRequest()` still compiles.

### Tests

`Rc38RequestShapeTest` asserts the **exact key set** of each body. That is the
only assertion that catches this class of break — checking that the keys you
care about are present passes just as happily with a fatal one beside them,
which is how `requester` rode along on eighteen requests unnoticed.

The live-node suite gained the provisioning chain: create a namespace, create a
**named** subgroup, list it back. Everything it did before either read, or
asserted a request shape against a mock — and a mock cannot notice that the node
stopped accepting what the SDK sends.

## Superseded — core 0.11.0-rc.32

Verified against a real `merod 0.11.0-rc.32`, and against a second one for the join.

### Breaking — the SDK could not do these against a current node

- **`InstallApplicationRequest` is `{package, version}`.** core#3652 (rc.31) made
  distribution registry-only: the node fetches from its own `[registry]`, and the
  request carries `deny_unknown_fields`, so the old body naming `url` is
  **refused** — `400 unknown field \`url\`, expected \`package\` or \`version\``.
  `installFromRegistry(registryUrl, package, version)` loses its first argument.
  ⚠️ Discover versions with **`getRegistryVersions`** (the registry read), not with
  `listPackageVersions` / `getLatestPackageVersion` — those ask the *node*, which
  reports what it has **installed**, so for a package it has never seen they answer
  `{"versions":[]}` / `{"version":null}`. That reads like "unpublished" and is a
  different question.
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

`TwoNodeInviteJoinE2ETest` drives the whole invitation journey through the SDK's
own types across two real nodes: mint on A, carry as Kotlin objects, join on B.
Simulating the old model at both levels turns that join into an HTTP 500 and
reddens three unit tests, so it is load-bearing rather than decorative — checked,
not assumed. (The first attempt at that check filtered the wrong serializer and
passed; the envelope carries the nested object verbatim, so only filtering there
reproduces the bug.)

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
