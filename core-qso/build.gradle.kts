plugins {
    // No Kotlin plugin: AGP 9 compiles Kotlin itself (built-in Kotlin support).
    alias(libs.plugins.android.library)
}

/*
 * core-qso: the QSO state machine (docs/ARCHITECTURE.md).
 *
 * No Android types. It is fed decoded messages and a clock, and answers with the
 * message to send next - so every sequence an on-air QSO can take is testable on
 * the JVM in milliseconds, including the ones that are awkward to arrange on a real
 * band.
 */
android {
    namespace = "com.ft8ok.qso"
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
    api(project(":core-model"))

    testImplementation(libs.junit)
}
