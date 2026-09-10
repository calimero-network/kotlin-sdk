plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktlint) apply false
    // Root applies the aggregation half; the modules alias this one, so it has to
    // be declared (unapplied) here or the subprojects cannot request a version for
    // a plugin the aggregation already put on the classpath.
    alias(libs.plugins.nmcp) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.nmcp.aggregation)
}

// Apply ktlint + detekt to every module from the root so lint config lives in one place.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        // Enforcing: the repo has had a full `./gradlew ktlintFormat` pass, so a style
        // regression is a real regression and should fail CI.
        ignoreFailures.set(false)
        filter {
            exclude { entry -> entry.file.path.contains("/build/") }
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel = true
    }
}

// ---------------------------------------------------------------------------
// Maven Central (Central Portal) aggregation
// ---------------------------------------------------------------------------
//
// Central takes ONE bundle per release, not a repository deploy, so the two
// library modules are zipped together and uploaded as a unit. nmcp does that on
// top of whatever `maven-publish` already produced in each module — it reads no
// AGP classes, which is why it works on AGP 8.5.2 where
// com.vanniktech.maven.publish requires 8.13+.
//
// `nmcpZipAggregation` builds the bundle and `nmcpCheckAggregationFiles` validates
// it, both without credentials — so the shape of a release is checkable in CI
// before a tag exists. Only `nmcpPublishAggregationToCentralPortal` needs secrets.
nmcpAggregation {
    centralPortal {
        username = providers.gradleProperty("mavenCentralUsername")
        password = providers.gradleProperty("mavenCentralPassword")
        // Uploads and validates, then waits for a human to press publish in the
        // Portal. Deliberate for the first releases: an AUTOMATIC drop is
        // irreversible, and a version can never be re-published on Central.
        publishingType = "USER_MANAGED"
    }
}

dependencies {
    nmcpAggregation(project(":mero-core"))
    nmcpAggregation(project(":mero-compose"))
}
