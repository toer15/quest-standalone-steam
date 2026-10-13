import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.toer15.queststeam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.toer15.queststeam"
        minSdk = 29
        targetSdk = 32 // Quest store/horizon OS commonly expects 32
        versionCode = 1
        versionName = "0.1.0-experiment"
        ndk { abiFilters += "arm64-v8a" }
        val key = localProps.getProperty("STEAM_API_KEY", "")
        buildConfigField("String", "STEAM_API_KEY", "\"$key\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":launcher"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
