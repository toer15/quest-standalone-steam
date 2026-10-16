// Newer D8/R8 than AGP 8.5 bundles: it understands Kotlin 2.2 metadata. The bundled one printed a
// stack trace per class while dexing (JavaSteam/ktor are Kotlin 2.2) and ran the build out of memory.
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("com.android.tools:r8:8.11.+") }
}

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    // 2.2.x: JavaSteam 1.8.0 is compiled with Kotlin 2.2 and older compilers can't read its metadata.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
