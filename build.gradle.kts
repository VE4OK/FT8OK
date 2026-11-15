plugins {
    // Declared once here with `apply false` so every module can alias them with a
    // consistent version; applying them in a module without this gives
    // "already on the classpath with an unknown version".
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
