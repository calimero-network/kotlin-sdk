# Publishing & releasing

There's no "npm" for Android — the package registry is **Maven**. Artifacts are addressed by
`group:artifact:version` coordinates (e.g. `com.calimero.mero:mero-core:0.1.0`) and resolved by
Gradle from a Maven repository. This repo publishes to two possible targets:

| Target | Analogous to | Auth | Status |
|--------|--------------|------|--------|
| **GitHub Packages** | a private/org npm registry | the built-in `GITHUB_TOKEN` | **wired now** |
| **Maven Central** | the public npm registry | Sonatype account + GPG signing | documented below (next step) |

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
2. runs `./gradlew publish -PVERSION_NAME=0.1.0` → publishes `mero-core` and `mero-compose` to
   **GitHub Packages**,
3. cuts a GitHub Release with generated notes.

`VERSION_NAME` defaults to the value in `gradle.properties` for local/dev builds; the tag overrides it.

## Consuming from GitHub Packages

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
    implementation("com.calimero.mero:mero-core:0.1.0")
    implementation("com.calimero.mero:mero-compose:0.1.0") // optional UI kit
}
```

Put a personal access token (scope `read:packages`) in `~/.gradle/gradle.properties` as
`gpr.user` / `gpr.token`.

## Publishing locally (smoke test)

```bash
./gradlew publishToMavenLocal -PVERSION_NAME=0.1.0-local
# artifacts land in ~/.m2/repository/com/calimero/mero/
```

## Next step: Maven Central (no consumer auth needed)

Maven Central is the better long-term home (no token to consume, wider reach). To add it:

1. Register the `com.calimero` (or `network.calimero`) namespace on the
   [Central Portal](https://central.sonatype.com/) and verify domain ownership.
2. Add GPG signing — Central **requires** signed artifacts and a `javadoc` jar:
   - apply the `signing` plugin, add `withJavadocJar()` to the `singleVariant("release")` block,
   - sign the `release` publication with an in-memory key.
3. Add the `com.vanniktech.maven.publish` plugin (handles the Central Portal upload + signing
   ergonomics) or the Sonatype `nmcp`/`central-publishing` plugin.
4. Store `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD` as
   repo secrets and add a Central publish step to `release.yml`.

Version-align releases to the mero-js contract they implement (mero-kotlin 0.1.x ⇄ mero-js 7.x for
now; bump to 7.x when the API surface reaches parity).
