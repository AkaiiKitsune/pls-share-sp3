// Plugins are applied per module. The Android plugin is only resolved when :app is included
// (see settings.gradle.kts), so the core module builds on machines without the Android SDK.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
