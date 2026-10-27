// Standalone build so the pure-Kotlin core (game engine + secure API protocol)
// can be built and tested on any JVM without the Android SDK. The Android app
// consumes it through `includeBuild("core")` in ../settings.gradle.kts.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "arbot-core"
