plugins {
    // No Kotlin plugin: AGP 9 compiles Kotlin itself (built-in Kotlin support).
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

/*
 * data: the logbook on the phone (ARCHITECTURE.md, rule 8).
 *
 * Room is the source of truth; every change to a logbook rewrites its ADIF file in
 * the folder the operator chose. What a record contains and how ADIF is written are
 * core-log's business - this module only stores and files them.
 */
android {
    namespace = "com.ft8ok.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric needs the merged Android resources and manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

// Every schema version is committed, so each migration can be tested against the
// exact schema it upgrades from. Losing one makes that test impossible.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core-log"))

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
