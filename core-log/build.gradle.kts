plugins {
    // No Kotlin plugin: AGP 9 compiles Kotlin itself (built-in Kotlin support).
    alias(libs.plugins.android.library)
}

/*
 * core-log: the logbook's pure half (ARCHITECTURE.md).
 *
 * No Android types. The ADIF writer and reader, the record a logged contact becomes,
 * and the band table - so the round trip that rule 8 demands is proved on the JVM.
 * The database and the Storage Access Framework file live in `data`, not here.
 */
android {
    namespace = "com.ft8ok.log"
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
    implementation(project(":core-qso"))

    testImplementation(libs.junit)
}
