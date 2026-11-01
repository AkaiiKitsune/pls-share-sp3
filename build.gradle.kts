// Nothing is declared here on purpose. Each module declares its own plugins (versions come from
// gradle/libs.versions.toml), so :app gets the Android and Kotlin plugins on one classloader and
// :core builds on machines without the Android SDK (see settings.gradle.kts).
