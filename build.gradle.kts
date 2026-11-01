// Both modules get their plugins from this classpath, so the Kotlin Gradle plugin is loaded once
// and sits next to the Android Gradle plugin, which it needs to see. The Android plugin is only
// put on the classpath when :app is included (see settings.gradle.kts), so the core module builds
// on machines without the Android SDK or without access to Google's Maven repository.
buildscript {
    repositories {
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        if (findProject(":app") != null) {
            classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}")
        }
    }
}
