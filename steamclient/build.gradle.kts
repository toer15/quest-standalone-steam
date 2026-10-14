import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM Steam client (no Android APIs) so it can be compiled and unit-tested on a desktop JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    // Steam CM protocol (login, licenses, PICS) and DepotDownloader port. https://github.com/Longi94/JavaSteam
    api("in.dragonbra:javasteam:1.8.0")
    api("in.dragonbra:javasteam-depotdownloader:1.8.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // JavaSteam only declares protobuf at runtime; the owned-games request is a protobuf message.
    implementation("com.google.protobuf:protobuf-java:4.31.1")
    testImplementation("junit:junit:4.13.2")
    // JavaSteam loads BouncyCastle on desktop JVMs (Android uses SpongyCastle, added in :app).
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.80")
}

tasks.test {
    timeout.set(Duration.ofMinutes(15))
    systemProperty("steam.live", System.getProperty("steam.live") ?: "")
    testLogging { showStandardStreams = true; events("passed", "failed", "skipped") }
}
