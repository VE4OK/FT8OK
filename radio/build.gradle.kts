plugins {
    // No Kotlin plugin: AGP 9 compiles Kotlin itself (built-in Kotlin support).
    alias(libs.plugins.android.library)
}

/*
 * radio: the CAT link (ARCHITECTURE.md).
 *
 * Civ is pure Kotlin and unit-tested on the JVM - the framing and BCD are exactly
 * the kind of detail that is easy to get plausibly wrong and expensive to debug with
 * a radio in the loop. Only Ic7300Rig touches Android and the serial port.
 *
 * READ-ONLY for now. No PTT and no frequency setting: anything that can key a
 * transmitter arrives with the TX scheduler, its arm state and its watchdog,
 * not before. The protocol constants for those commands are
 * documented in Civ so the knowledge is not lost, but nothing here sends them.
 */
android {
    namespace = "com.ft8ok.radio"
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

    // The IC-7300's CAT interface is a vendor-specific CP2102 (verified by
    // enumeration); this library carries the CP210x driver.
    implementation(libs.usb.serial)

    testImplementation(libs.junit)
}
