import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionCode = 36
        versionName = "0.13.1"
        ndk { abiFilters += "arm64-v8a" }
        val key = localProps.getProperty("STEAM_API_KEY", "")
        buildConfigField("String", "STEAM_API_KEY", "\"$key\"")
    }

    // Meta Quest 3 app (the phone build was dropped). The flavor keeps the quest source set.
    flavorDimensions += "device"
    productFlavors {
        create("quest") {
            dimension = "device"
            buildConfigField("boolean", "QUEST_BUILD", "true")
            resValue("string", "app_name", "Quest Steam")
        }
    }

    // One fixed key for sideloaded builds, so a new version installs over the old one (keeping games).
    // It is public on purpose: this is a personal sideload project, not a store release.
    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.jks")
            storePassword = "queststeam"
            keyAlias = "qss"
            keyPassword = "queststeam"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("sideload") }
        // Release = not debuggable, so ART fully compiles the app; Compose UI is far faster than in debug.
        getByName("release") {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    buildFeatures { buildConfig = true; compose = true; prefab = true }

    // Immersive OpenXR view for the SteamVR bridge (android.app.NativeActivity, Quest manifest only).
    externalNativeBuild {
        cmake {
            path = file("src/quest/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions { unitTests.all { it.systemProperty("live", System.getProperty("live") ?: "") } }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            // Duplicate metadata from JavaSteam's dependencies (Ktor, OkHttp, protobuf, coroutines).
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/previous-compilation-data.bin",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md",
            )
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":launcher"))
    implementation(project(":steamclient"))
    // JavaSteam's crypto provider on Android (Android's own "BC" provider refuses AES for targetSdk 28).
    implementation("com.madgag.spongycastle:prov:1.58.0.0")
    // QR code for "sign in with the Steam mobile app".
    implementation("com.google.zxing:core:3.5.3")
    // Khronos OpenXR loader (prefab) for the VR view; Meta's runtime on the headset does the rest.
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.49")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
