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
    kotlinOptions { jvmTarget = "17" }
    // TODO(native): once openvr-bridge builds, wire it in:
    // externalNativeBuild { cmake { path = file("../openvr-bridge/CMakeLists.txt") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.github.luben:zstd-jni:1.5.6-3@aar")
    implementation("org.apache.commons:commons-compress:1.26.2")
}
