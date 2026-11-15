plugins {
    // No Kotlin plugin: AGP 9 compiles Kotlin itself (built-in Kotlin support).
    alias(libs.plugins.android.library)
}

/*
 * audio: USB capture and slot-aligned buffering (ARCHITECTURE.md).
 *
 * The Android half is confined to UsbAudioIn. SampleRing - the part where the
 * timing subtleties live - is plain Kotlin and unit-tested on the JVM, because
 * sample-accurate alignment is exactly the kind of thing that is easy to get
 * plausibly wrong and expensive to debug on a hillside.
 *
 * No decoding here. This module's job ends at "15 s of correctly aligned audio".
 */
android {
    namespace = "com.ft8ok.audio"
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

    // For @RequiresPermission: it is how the RECORD_AUDIO requirement reaches
    // lint at every CALL SITE, which is where the check is actually useful.
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
}
