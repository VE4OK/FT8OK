plugins {
    // No Kotlin plugin: AGP 9 compiles Kotlin itself (built-in Kotlin support).
    alias(libs.plugins.android.library)
}

/*
 * core-time: the clock model (docs/ARCHITECTURE.md, ADR 0005).
 *
 * No Android types and no audio dependency - it is fed DT measurements and told
 * what time the host thinks it is, through injected functions. That is what makes
 * the band-consensus loop testable on the JVM against a scripted clock instead of
 * only on a hillside.
 */
android {
    namespace = "com.ft8ok.time"
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
