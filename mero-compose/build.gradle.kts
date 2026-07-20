plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

android {
    namespace = "com.calimero.mero.compose"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all { it.useJUnit() }
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":mero-core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

group = providers.gradleProperty("GROUP").getOrElse("com.calimero.mero")
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0")

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = group.toString()
            artifactId = "mero-compose"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("mero-compose")
                description.set("Calimero Android SDK — Jetpack Compose login UI kit.")
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
