import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.toer15.queststeam.launcher"
    compileSdk = 34
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.all { it.systemProperty("winlator.apk", System.getProperty("winlator.apk") ?: ""); it.systemProperty("winlator.download", System.getProperty("winlator.download") ?: ""); it.systemProperty("winlator.smoke", System.getProperty("winlator.smoke") ?: ""); it.maxHeapSize = "1g" } }
    // TODO(native): once openvr-bridge builds, wire it in:
    // externalNativeBuild { cmake { path = file("../openvr-bridge/CMakeLists.txt") } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.github.luben:zstd-jni:1.5.6-3@aar")
    implementation("org.apache.commons:commons-compress:1.26.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.github.luben:zstd-jni:1.5.6-3") // desktop natives for JVM tests
}
