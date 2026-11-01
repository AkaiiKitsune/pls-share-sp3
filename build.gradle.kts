// Plugins are applied per module. Both Kotlin plugins are declared here so that every module
// sees the same Kotlin Gradle plugin on the classpath. The Android Gradle plugin is only
// resolved when :app is included (see settings.gradle.kts), so the core module builds on
// machines without the Android SDK.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
}
