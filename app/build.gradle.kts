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
        // Short id: same length as "com.winlator" path (+pad) so Winlator binaries can be byte-patched.
        applicationId = "com.qssteam"
        minSdk = 29
        // 28: Android 10+ forbids exec() of files in app data for targetSdk>=29 (same as Winlator/Termux).
        @Suppress("ExpiredTargetSdkVersion")
        targetSdk = 28
        versionCode = 6
        versionName = "0.3.2"
        ndk { abiFilters += "arm64-v8a" }
        val key = localProps.getProperty("STEAM_API_KEY", "")
        buildConfigField("String", "STEAM_API_KEY", "\"$key\"")
    }

    buildFeatures { buildConfig = true; compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.all { it.systemProperty("live", System.getProperty("live") ?: "") } }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(project(":launcher"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("io.coil-kt:coil-compose:2.6.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
