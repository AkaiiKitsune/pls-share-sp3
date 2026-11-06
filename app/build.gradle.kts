plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every CI build gets a monotonic build number from GitHub Actions. The in-app updater compares
// it with the number embedded in the release asset's file name (…-b<number>.apk).
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val versionLabel = System.getenv("APP_VERSION_NAME") ?: "dev"

android {
    namespace = "io.github.akaiikitsune.plsshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.akaiikitsune.plsshare"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "$versionLabel-b$buildNumber"
    }

    // The release key comes from the environment (GitHub Actions secrets, see the README). Without
    // it, the release build is signed with the local debug key so it still installs, but updates
    // from the in-app updater will not install over a build signed with another key.
    val keystorePath = System.getenv("APP_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("APP_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("APP_KEY_ALIAS") ?: "pls-share-sp3"
                keyPassword = System.getenv("APP_KEY_PASSWORD") ?: System.getenv("APP_KEYSTORE_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.graphicsMode", "NATIVE") }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
