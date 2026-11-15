pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android (MIT) is published via JitPack, per its README.
        // Build-time only: it has no bearing on the app's permissions, and the
        // merged-manifest guard in app/build.gradle.kts proves it adds none.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ft8ok"

// The modules of docs/ARCHITECTURE.md. Dependencies point inward only: core-*
// never imports app, data, audio or radio.
include(":core-model")
include(":core-dsp")
include(":core-time")
include(":audio")
include(":radio")
include(":core-qso")
include(":core-log")
include(":data")
include(":app")
