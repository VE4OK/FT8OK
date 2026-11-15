plugins {
    // No Kotlin plugin: AGP 9 compiles Kotlin itself (built-in Kotlin support).
    alias(libs.plugins.android.library)
}

/*
 * core-model: the protocol's own facts - Mode, slot parity (ARCHITECTURE.md).
 *
 * Plain Kotlin, no Android types and no dependency on any other module. It is the
 * innermost ring, so everything may depend on it and it depends on nothing.
 *
 * An Android library module rather than a plain JVM one, deliberately: AGP 9's
 * built-in Kotlin compiles this without the Kotlin Gradle plugin, which the app
 * module cannot apply (see app/build.gradle.kts). Keeping every module on the same
 * plugin keeps one toolchain on a 4 GB VM, and `testDebugUnitTest` still runs these
 * tests on the JVM in about a second with no device.
 */
android {
    namespace = "com.ft8ok.model"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
