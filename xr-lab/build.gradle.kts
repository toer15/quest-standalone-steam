plugins { id("com.android.library") }

// This AAR includes its own complete CMake package. Its header-only Prefab
// module has no module.json, which AGP 8.5 cannot resolve. Use the supplied
// CMake package directly while retaining the AAR for manifest/JNI packaging.
val openxrVersion = "1.1.49"
val openxrSdk by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val openxrSdkDir = layout.buildDirectory.dir("generated/openxr-sdk")
val extractOpenxrSdk by tasks.registering(Sync::class) {
    from({ zipTree(openxrSdk.singleFile) })
    include("OpenXRConfig*.cmake", "prefab/**")
    into(openxrSdkDir)
}

android {
    namespace = "com.toer15.queststeam.xrlab"
    compileSdk = 34
    ndkVersion = "24.0.8215888"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            cppFlags += listOf("-std=c++17", "-Wall", "-Wextra")
            arguments += "-DOpenXR_DIR=${openxrSdkDir.get().asFile.absolutePath}"
        } }
    }
    externalNativeBuild { cmake { path = file("CMakeLists.txt"); version = "3.22.1" } }
}
tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake")) dependsOn(extractOpenxrSdk)
}
dependencies {
    // Official Khronos loader; includes headers, loader .so, and Android broker manifest.
    implementation("org.khronos.openxr:openxr_loader_for_android:$openxrVersion")
    openxrSdk("org.khronos.openxr:openxr_loader_for_android:$openxrVersion@aar")
}
