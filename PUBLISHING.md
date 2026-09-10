# Publishing & releasing

There's no "npm" for Android — the package registry is **Maven**. Artifacts are addressed by
`group:artifact:version` coordinates (e.g. `network.calimero:mero-core:0.1.0`) and resolved by
Gradle from a Maven repository. This repo publishes to two targets, both wired:

| Target | Analogous to | Consumer auth | Role |
|--------|--------------|---------------|------|
| **Maven Central** | the public npm registry | **none** | the public target |
| **GitHub Packages** | a private/org npm registry | a PAT, even for public packages | pre-releases, internal builds |

Central is the one to point people at: it resolves through the `mavenCentral()` every Android
project already declares. GitHub Packages needs no signing key and no Portal account, which is
what keeps it useful for an internal or pre-release build.

## Coordinates

`network.calimero`, not `com.calimero.*`. Central makes you prove you own the namespace, and
`com.calimero` would mean owning **calimero.com** — this is the reverse-DNS of calimero.network,
a domain we do own, verified with a TXT record. Settled before the first public release on
purpose: moving coordinates afterwards is a breaking change for every consumer.

## Testing before release

Before tagging, make sure the suite is green — see [TESTING.md](TESTING.md). At a
minimum run what CI enforces plus the live-node e2e:

```bash
./gradlew ktlintCheck detekt testDebugUnitTest lint assembleDebug   # ci.yml (§1–§3)
./test-all.sh                                                       # all of §0–§6, incl. live-node e2e
```

## How a release happens

Tag-driven, mirroring the JS repos' semantic-release cadence:

```bash
git tag v0.1.0
git push origin v0.1.0
```

`.github/workflows/release.yml` then:
1. derives the version from the tag (`v0.1.0` → `0.1.0`),
2. `./gradlew publish` → `mero-core` and `mero-compose` to **GitHub Packages**,
3. `./gradlew nmcpPublishAggregationToCentralPortal` → both modules as one signed bundle to
   **Maven Central**,
4. cuts a GitHub Release with generated notes.

`VERSION_NAME` defaults to the value in `gradle.properties` for local/dev builds; the tag overrides it.

### The Central upload is deliberately not automatic

`publishingType` is `USER_MANAGED`: the bundle is uploaded and validated, then waits for someone
to press publish in the [Central Portal](https://central.sonatype.com/). **A Central version can
never be replaced or re-published**, so the last step before it becomes permanent is a human one.
Switch to `AUTOMATIC` in the root `build.gradle.kts` once the cadence is boring.

### One-time setup before the first Central release

1. Register the `network.calimero` namespace on the Central Portal and verify it with the DNS
   TXT record it gives you.
2. Generate a GPG key, publish the public half to a keyserver, and export the private half
   ASCII-armored (`gpg --armor --export-secret-keys <id>`).
3. Add four repo secrets:

   | secret | what |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | Portal user token name |
   | `MAVEN_CENTRAL_PASSWORD` | Portal user token password |
   | `SIGNING_KEY` | the ASCII-armored private key |
   | `SIGNING_PASSWORD` | its passphrase (`''` if none) |

Signing is opted into only when a key is present, so `publishToMavenLocal` and a GitHub Packages
publish still work on a machine with no GPG key.

## Consuming from Maven Central

No repository block, no credentials — `mavenCentral()` is already there:

```kotlin
dependencies {
    implementation("network.calimero:mero-core:0.1.0")
    implementation("network.calimero:mero-compose:0.1.0") // optional UI kit
}
```


## Consuming from GitHub Packages (pre-releases, internal builds)

GitHub Packages requires auth even for public packages. In the consumer project:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/calimero-network/kotlin-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("network.calimero:mero-core:0.1.0")
    implementation("network.calimero:mero-compose:0.1.0") // optional UI kit
}
```

Put a personal access token (scope `read:packages`) in `~/.gradle/gradle.properties` as
`gpr.user` / `gpr.token`.

## Publishing locally (smoke test)

```bash
./gradlew publishToMavenLocal -PVERSION_NAME=0.1.0-local
# artifacts land in ~/.m2/repository/network/calimero/
```

To check the **actual Central bundle** — coordinates, javadoc jar, signatures, checksums — without
any credentials, which is what `ci.yml` does on every pull request:

```bash
ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys <throwaway-id>)" \
  ./gradlew nmcpZipAggregation -PVERSION_NAME=0.0.0-local
unzip -l build/nmcp/zip/aggregation.zip
```

`nmcpCheckAggregationFiles` runs as part of that and validates the bundle against Central's rules,
so a broken release config fails on the pull request rather than on a tag that cannot be undone.

## What ships

`mero-core` and `mero-compose` only. `mero-testkit` applies `maven-publish` but registers no
publication, so it is not published — it exists for this repo's own tests. Register a publication
for it if consumers ever need the fake node.

Version-align releases to the mero-js contract they implement (mero-kotlin 0.1.x ⇄ mero-js 7.x for
now; bump to 7.x when the API surface reaches parity).
