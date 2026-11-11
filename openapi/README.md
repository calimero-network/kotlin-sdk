# Core's OpenAPI document and the generated client

`openapi.json` is core's node API (`docs/public/openapi.json` in calimero-network/core), vendored
at the commit [`SOURCE`](SOURCE) records. mero-core's API client is generated from it.

| Layer | Where | Written by |
|---|---|---|
| Facade: `client.orgs.inviteCreate(org = …, role = OrgRole.MEMBER)`, `client.apps.get(app = …)`: one suspend method per operation, grouped by its first tag, the body's fields as named parameters, the typed answer | `mero-core/src/generated/kotlin/com/calimero/mero/api/facade` | `codegen/facade.py` |
| Generated client: `AppsApi`, `OrganizationsApi`, … and their models (`com.calimero.mero.api.apis`, `.models`, `.infrastructure`), reached through `client.api()` | `mero-core/src/generated/kotlin/com/calimero/mero/api` | OpenAPI Generator 7.26.0, `kotlin` / `jvm-okhttp4`, kotlinx.serialization, coroutines, with two templates of ours (`codegen/templates`) |
| Transport: the attested sealed channel (`tee.SealedTransport`, `tee.Noise`, `http.TransportSecurity`), the node in use and failover, the credential and `401` refresh, `503` retries of calls and views, the `412` passkey step-up and the `428` operator proof, the event streams | `mero-core/src/main/kotlin` | by hand |

The generated client's `Call.Factory` is `http.MeroCallFactory`: every generated request goes through
`CalimeroClient`'s own pipeline, sealed to the attested node, exactly as the hand-written methods'
requests do. A refusal throws the `CalimeroError` it means (`ConfirmRequired`, `NotFound`, …), never
the generator's `ClientException`. A `412` is approved with the call's `passkey`, else
`CalimeroClient.passkey`; a `428` signed with the call's `operator`, else `CalimeroClient.operator`.

## Checked in, not generated at build time

The generated sources are committed. Building, testing or releasing the SDK then needs neither the
generator (about a hundred artifacts, none of them in `gradle/verification-metadata.xml`) nor the
network beyond what the build already resolves, and a spec change shows up as a reviewable diff of
the client. `codegen/` is a Gradle build of its own, with its own verification metadata, so the
generator never enters the SDK's build classpath.

```bash
scripts/refresh-openapi.sh [ref]     # fetch core's document at ref (default master), record it, regenerate
scripts/refresh-openapi.sh --check   # regenerate from the vendored document; fail on any diff (CI)
./gradlew -p codegen generateMeroClient   # regenerate only
```

`codegen/build.gradle.kts` refuses a document whose SHA-256 is not the one `SOURCE` records.

## Where the document and a generated client disagree

The generator follows the document; these spots in it do not say what a client must send or reads,
so the hand-written methods keep their own handling, and the facade method is either avoided or
noted:

- **Binary bodies** (`POST /apps/{app}/blobs`, `/apps/{app}/files`, `/packages`, `/ops/packages`,
  `PUT …/parts/{k}`, `POST /bundles`) are `type: string` with a `contentMediaType`. The generator
  makes most a `java.io.File` (and a download a temp file), and `POST /bundles`
  (`application/wasm`) a `String`. `uploadBlob`, `uploadFile`, `uploadPackage`, `uploadBundle` and
  `blobContent` stay as they are, on the generated routes.
- **`POST /consent/requests`**'s body is `type: string` with `contentMediaType: application/json`
  and its shape in prose, so a generated client sends a quoted string. `requestConsent` keeps its
  own body.
- **`POST /apps/{app}/views`**: `consistency` is `const: "stale"`; nothing names `strong`.
- **`GET /orgs/{org}/members`**: `detail` is `const: "security"`, generated as a `JsonElement`.
- **`POST /apps`** has no `confirmMethods`; `createApp` still sends it.
- **A null that means something**: `parent: null` (`POST /orgs/{org}/teams/{team}`, to the top),
  `publicKey: null` (`POST /orgs/{org}/recovery`, remove) and `state: null`
  (`POST /apps/{app}/presence`, retract). A generated request leaves a null out, so the deprecated
  methods add these explicitly.
- **Integers** carry no `format`; the node's are u64. They are generated as `Long`.
- **Untyped answers**: many routes answer `{ type: object }` or `additionalProperties: {}` only
  (`GET /orgs/{org}/invites`, `/orgs/{org}/teams/{team}`, `/packages/{package}`,
  `/deployments/{id}`, `/deployers`, `/releases`, `/publishers/{signer}`, …): a `JsonObject` or a
  `Map<String, JsonElement?>`.
- **Unions** (`Access`, `Deployer`, `OutboundSelection`) are `anyOf` without a discriminator; they
  are mapped to mero-core's `AppAccess`, `Deployer` and `OutboundSelection`.
- **Shared `{ confirm? }` bodies** are inline, so the generator names them after the first
  operation it meets (`PasskeyRemoveRequest` for `DELETE /orgs/{org}`, …).
- `info.license` has no `identifier` or `url`, which OpenAPI 3.1 requires; generation skips
  validation for it.
