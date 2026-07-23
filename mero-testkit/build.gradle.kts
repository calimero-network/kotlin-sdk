plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Test-support library — the Android analog of the Swift SDK's `MeroKitTestSupport` target.
// Ships `FakeNode` (a stateful in-memory Calimero node backed by OkHttp MockWebServer) so unit
// tests and the sample app's mock mode can model a whole login → call → refresh → logout journey
// without a live node. Not published (no maven-publish here).
android {
    namespace = "com.calimero.mero.testkit"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testOptions.targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // FakeNode is HTTP-level (no mero-core types), matching the Swift SDK's `FakeNode`, which
    // imports only Foundation. This keeps the harness usable from any consumer's unit tests.
    api(libs.okhttp.mockwebserver)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
