plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt)
}

// Apply ktlint + detekt to every module from the root so lint config lives in one place.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        // Report-only for the initial drop: this repo has never had `ktlintFormat` run against it on
        // a machine with the Kotlin toolchain, so wrapping/trailing-comma nits shouldn't block CI.
        // Flip to false after the first `./gradlew ktlintFormat` pass.
        ignoreFailures.set(true)
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
