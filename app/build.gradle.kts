import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    // No Kotlin plugin: AGP 9 compiles Kotlin itself (built-in Kotlin support).
    // Applying org.jetbrains.kotlin.android here breaks the build.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/*
 * The app (docs/ARCHITECTURE.md).
 *
 * Thin shell by design: everything that decides anything lives in the core-*
 * modules as plain Kotlin with no Android types, so it can be tested on the JVM
 * with no phone and no radio. Dependencies point inward only - if this file ever
 * needs a core-* module to depend back on it, the layering has gone wrong.
 */
android {
    namespace = "com.ft8ok"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ft8ok"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode = major * 100 + minor * 10 + patch: 1.0 = 100, 1.1 = 110. It must
        // only ever go up, or Android refuses the update.
        versionCode = 100
        versionName = "1.0"
    }

    /*
     * Two apps from one code base: FT8OK and FT4OK. The mode is
     * fixed when the app is built, never switched at runtime; each flavor's
     * `Flavor` object (src/ft8, src/ft4) says which. Different applicationIds, so
     * both install side by side with their own settings and logbook.
     */
    flavorDimensions += "mode"
    productFlavors {
        create("ft8") {
            dimension = "mode"
            applicationId = "com.ft8ok"
        }
        create("ft4") {
            dimension = "mode"
            applicationId = "com.ft4ok"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG: diagnostics default on in debug builds only
    }

    // Release signing. The key and its passwords never live in the repository: they
    // are read from a properties file (storeFile, storePassword, keyAlias,
    // keyPassword) named by -Pft8okSigning=<path> or FT8OK_SIGNING, default
    // ~/keys/ft8ok-release.properties. Without it, release APKs come out unsigned.
    val signing = Properties().also { props ->
        val path = (findProperty("ft8okSigning") as String?)
            ?: System.getenv("FT8OK_SIGNING")
            ?: "${System.getProperty("user.home")}/keys/ft8ok-release.properties"
        file(path).takeIf { it.isFile }?.inputStream()?.use { props.load(it) }
    }
    signingConfigs {
        if (!signing.isEmpty) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
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
    implementation(project(":core-model"))
    implementation(libs.androidx.annotation)
    implementation(project(":core-dsp"))
    implementation(project(":core-qso"))
    implementation(project(":core-log"))
    implementation(project(":core-time"))
    implementation(project(":audio"))
    implementation(project(":radio"))
    implementation(project(":data"))

    // The BOM pins every compose-* artifact to one tested set, so the individual
    // dependencies below carry no version of their own.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    testImplementation(libs.junit)
}

/*
 * No network access, ever (ADR 0002): the build must fail if the MERGED manifest carries INTERNET.
 *
 * Checking the merged manifest rather than our own source manifest is the whole
 * point - a dependency can contribute a permission silently, and that is exactly
 * the failure this guard exists to catch. Compose pulls in a large androidx graph,
 * so this matters. It runs on assemble and on check, so there is no path to an APK
 * that skips it.
 */
abstract class VerifyPermissionsTask : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowedPermissions: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifestFile = mergedManifest.get().asFile
        val text = manifestFile.readText()

        val found = Regex("""uses-permission[^>]*?android:name="([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSortedSet()

        val allowed = allowedPermissions.get()
        val unexpected = found.filterNot { it in allowed }.toSortedSet()

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("Merged manifest: $manifestFile")
                    appendLine("Allowed: ${allowed.sorted()}")
                    appendLine("Found:   ${found.toList()}")
                    appendLine("Unexpected: ${unexpected.toList()}")
                }
            )
        }

        if ("android.permission.INTERNET" in found) {
            throw GradleException(
                "FT8OK has no network permission, ever, in v1 (ADR 0002). " +
                    "The merged manifest declares android.permission.INTERNET. " +
                    "Something - our manifest or a dependency - added it. Merged manifest: $manifestFile"
            )
        }

        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "Unexpected permission(s) in the merged manifest: ${unexpected.toList()}. " +
                    "Only ${allowed.sorted()} are allowed (ADR 0002). If one of these is " +
                    "genuinely needed, it is a design decision: record it in an ADR " +
                    "and add it to allowedPermissions here. Merged manifest: $manifestFile"
            )
        }

        logger.lifecycle("Permission guard: OK - only ${found.toList()} present.")
    }
}

androidComponents {
    onVariants { variant ->
        val capitalised = variant.name.replaceFirstChar { it.uppercase() }

        val verify = tasks.register<VerifyPermissionsTask>("verify${capitalised}Permissions") {
            group = "verification"
            description = "Fails if the merged manifest declares INTERNET or any unapproved permission."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            // The second entry is not an Android permission at all: androidx.core
            // defines a signature-level, applicationId-prefixed permission of its
            // own to guard receivers registered with RECEIVER_NOT_EXPORTED, and the
            // manifest merger adds it as soon as Compose brings core in. It grants
            // nothing, no other app can hold it, and it reaches no network - so it
            // is listed explicitly rather than by relaxing the guard.
            //
            // Found by this guard on the first build of the Compose dependency
            // graph, which is exactly what it exists to do.
            allowedPermissions.set(
                variant.applicationId.map { appId ->
                    setOf(
                        "android.permission.RECORD_AUDIO",
                        "$appId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                    )
                }
            )
            report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
        }

        // Wire into both, so neither `assemble` nor `check` can bypass the guard.
        tasks.matching { it.name == "assemble$capitalised" }.configureEach { dependsOn(verify) }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
    }
}
