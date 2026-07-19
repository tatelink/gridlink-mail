import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.sterna"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sterna"
        minSdk = 26
        targetSdk = 36
        versionCode = 141
        versionName = "1.2.5"
        // Shown on the Settings About row. Bump alongside versionCode/versionName at each
        // release (a static literal, so builds stay reproducible — never derive from clock).
        buildConfigField("String", "VERSION_DATE", "\"2026-07-19\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Reproducible builds: the compiled ART baseline profile (assets/dexopt/baseline.prof)
    // is not byte-identical across build environments even when classes.dex is — F-Droid's
    // rebuild of 1.1.2 differed only there. Don't package it at all, per
    // https://f-droid.org/docs/Reproducible_Builds/. Release APKs must also be built from a
    // clean tree (clean --no-build-cache): incremental Kotlin/Compose compilation emits
    // slightly different bytecode than a from-scratch build.
    tasks.whenTaskAdded {
        if (name.contains("ArtProfile")) enabled = false
    }

    // No Google dependency-metadata blob in the APK/AAB (required by IzzyOnDroid/F-Droid).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Release signing. If a git-ignored keystore.properties is present (real release
    // key), the release build is signed with it; otherwise it falls back to the local
    // debug keystore so debug builds and CI still work. keystore.properties holds:
    //   storeFile=/absolute/path/to/sterna-release.jks
    //   storePassword=...
    //   keyAlias=sterna
    //   keyPassword=...
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    val hasReleaseKey = keystoreProps.getProperty("storeFile") != null
    val debugKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        if (debugKeystore.exists()) {
            create("debugSigned") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // R8 minify + resource shrink, unless built with -PnoR8 (faster test builds).
            val noR8 = providers.gradleProperty("noR8").isPresent
            isMinifyEnabled = !noR8
            isShrinkResources = !noR8
            // Reproducible builds: don't embed META-INF/version-control-info.textproto —
            // its content depends on whether AGP can read git in the build environment
            // (F-Droid's rebuild of 1.1.3 differed only there: NO_VALID_GIT_FOUND vs
            // our embedded revision).
            vcsInfo {
                include = false
            }
            // Prefer the real release key; fall back to the debug key when absent.
            // Keep on ONE line: F-Droid's reproducible-build signing strip is line-based,
            // so a multi-line `?:` would be left orphaned and break the build.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.findByName("debugSigned")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:jmap"))
    implementation(project(":core:data"))
    implementation(project(":libs:openpgp-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.unifiedpush.connector)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.paging.compose)
    // Installs the bundled baseline profile (src/main/baseline-prof.txt) on first launch so
    // ART AOT-compiles the hot startup/scroll paths. Needed because sideloaded/F-Droid installs
    // don't run install-time dexopt from the embedded profile on all ROMs (verified on the S7).
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
