plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

android {
    namespace = "com.calimero.mero"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testOptions.targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all { it.useJUnit() }
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    api(libs.okhttp)
    api(libs.okhttp.sse)

    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(project(":mero-testkit"))
}

group = providers.gradleProperty("GROUP").getOrElse("com.calimero.mero")
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0")

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = group.toString()
            artifactId = "mero-core"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("mero-core")
                description.set("Calimero Android SDK — core (auth, tokens, JSON-RPC, transport).")
                url.set("https://github.com/calimero-network/kotlin-sdk")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("calimero-network")
                        name.set("Calimero Network")
                    }
                }
                scm {
                    url.set("https://github.com/calimero-network/kotlin-sdk")
                    connection.set("scm:git:https://github.com/calimero-network/kotlin-sdk.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/calimero-network/kotlin-sdk")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.token").orNull
            }
        }
    }
}
