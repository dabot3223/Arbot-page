plugins {
    `java-library`
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
}

group = "com.arbot"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Exposed as API: the app uses the protocol models and coroutines directly.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    // Shared cross-language fixtures (also consumed by the Deno backend tests).
    systemProperty("arbot.fixtures", rootDir.resolve("../../shared/fixtures").absolutePath)
    environment("REGEN_FIXTURES", System.getenv("REGEN_FIXTURES") ?: "")
}
