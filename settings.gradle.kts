pluginManagement {
    repositories {
        maven("https://repo1.maven.org/maven2")
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        // repo1 is Maven Central's primary host; the repo.maven.apache.org alias rate-limits some networks.
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "pls-share-sp3"

include(":core")

// The Android app needs the Android SDK. Skip it on machines that do not have one,
// so that `./gradlew :core:test` and the desktop CLI work anywhere.
val localProperties = java.util.Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val androidSdk = localProperties.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
if (androidSdk != null && File(androidSdk).isDirectory) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found: skipping the :app module (set ANDROID_HOME or local.properties sdk.dir to build it)")
}
