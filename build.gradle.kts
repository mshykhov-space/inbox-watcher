plugins {
    application
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "io.github.mshykhov.inboxwatcher"
version = providers.gradleProperty("releaseVersion").orElse("0.14.0-SNAPSHOT").get()

val http4kVersion = "6.52.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.mshykhov.inboxwatcher.MainKt")
}

dependencies {
    implementation(platform("org.http4k:http4k-bom:$http4kVersion"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-server-undertow")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.xerial:sqlite-jdbc:3.53.1.0")
    implementation("org.jsoup:jsoup:1.21.1")
    implementation("io.micrometer:micrometer-registry-prometheus:1.15.0")
    implementation("ch.qos.logback:logback-classic:1.5.32")

    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}
