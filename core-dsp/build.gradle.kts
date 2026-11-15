plugins {
    // No Kotlin plugin: AGP 9 compiles Kotlin itself (built-in Kotlin support).
    alias(libs.plugins.android.library)
}

/*
 * core-dsp: the FT8/FT4 codec behind a narrow JNI boundary (ARCHITECTURE.md).
 *
 * Android dependencies are JNI only - no Android framework types in the API, so
 * the Kotlin side stays testable and the decoder stays swappable (ADR 0004).
 */
android {
    namespace = "com.ft8ok.dsp"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        externalNativeBuild {
            cmake {
                // All four, matching Compose's own native library. With arm64 alone the
                // APK still installed on 32-bit and x86 devices, because that library
                // declares them, and then had no decoder.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.0.2"
        }
    }

    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // 16 KB page alignment matters for devices that use 16 KB pages. The
            // test phone reports 4096 (ADR 0004 amendment), so this is
            // forward-compatibility, not a fix for anything observed here.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Pure-JVM tests of the Kotlin logic.
    // These run on the VM in seconds and never need the phone, which is the whole
    // point - a phone test run costs a trip to the radio.
    testImplementation(libs.junit)
}
