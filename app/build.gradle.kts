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
        versionCode = 115
        versionName = "1.0.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // Prefer the real release key; fall back to the debug key when absent.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.findByName("debugSigned")
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
    }
}

dependencies {
    implementation(project(":core:jmap"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
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
