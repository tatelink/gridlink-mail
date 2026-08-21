import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Side-by-side test app: build any variant with -PtestApp to get `app.gridlink.test`, a separate
// package that installs NEXT TO the production app instead of overwriting it (own data, own
// launcher entry). On-device checks go through it, so production stays the Obtainium-tracked
// install and version codes are never inflated just to reinstall.
//
// The property is absent by default and everything below is gated on it, so the production
// recipe is untouched: same applicationId, same versionName, same resources, same merged
// manifest. F-Droid rebuilds this repo without the property and must get the same bytes.
val testApp = providers.gradleProperty("testApp").isPresent

android {
    namespace = "app.gridlink"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.gridlink"
        minSdk = 26
        targetSdk = 36
        // 🔴 Gridlink numbers its OWN releases and does not continue Sterna's line. Inheriting
        // upstream's 1.4.6 shipped a second, different app claiming to be Sterna 1.4.6, which is
        // the one version string guaranteed to confuse a bug report from either project.
        //
        // versionCode starts at 1000, not 1: it must stay strictly above upstream's 166 or the
        // existing Obtainium-tracked install refuses the upgrade (Android rejects a downgrade),
        // and the round number leaves headroom to renumber without colliding.
        versionCode = 1000
        versionName = "0.1.0"
        // Shown on the Settings About row. Bump alongside versionCode/versionName at each
        // release (a static literal, so builds stay reproducible — never derive from clock).
        buildConfigField("String", "VERSION_DATE", "\"2026-08-08\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The launcher/settings/notification label. Substituted verbatim into the manifest,
        // so without -PtestApp the merged manifest still reads android:label="@string/app_name"
        // (localised as before).
        manifestPlaceholders["appLabel"] = "@string/app_name"
        if (testApp) {
            applicationIdSuffix = ".test"
            manifestPlaceholders["appLabel"] = "Gridlink (test)"
            // About row reads e.g. "1.3.13-test": tells the two apart from the inside.
            // A suffix only — versionName/versionCode themselves are never bumped for a test.
            versionNameSuffix = "-test"
        }
        // The AccountManager account type behind the system contacts/calendar mirror, kept in step
        // with applicationId by hand because a res value cannot read one.
        //
        // 🔴 It MUST differ between the two installable packages. Two apps claiming one account
        // type means whichever authenticator the system happens to bind owns both apps' rows, so
        // uninstalling the test app would take production's mirrored contacts with it.
        resValue("string", "gridlink_account_type", if (testApp) "app.gridlink.test" else "app.gridlink")
    }

    // Distinct launcher icon for the test app: a greyscale copy of the Gridlink mark overriding
    // the adaptive icon's foreground layer in both drawable/ and drawable-night/ (the tile and
    // the monochrome layer are untouched, so the test build still tracks the system theme).
    // Registered on the build-type source sets only under -PtestApp — build-type resources win
    // over main — so the production build never sees src/testApp/res at all.
    // 🔴 Any override added here needs BOTH qualifiers. This is a plain res srcDir, so a
    // drawable/-only override leaves the test build wearing the production icon in dark mode.
    if (testApp) {
        sourceSets.getByName("debug").res.srcDir("src/testApp/res")
        sourceSets.getByName("release").res.srcDir("src/testApp/res")
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
    //   storeFile=/absolute/path/to/gridlink-release.jks
    //   storePassword=...
    //   keyAlias=gridlink
    //   keyPassword=...
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    // 🔴 -PphoneKey signs the release build with the DEBUG key on purpose, and it exists so a
    // minified build can be TESTED on Tate's phone. His install has been debug-key signed since
    // the beginning; Android refuses to replace an app with one signed differently, so a real-key
    // release APK cannot `install -r` over it, and moving him across would mean an uninstall, which
    // costs him the account configuration on the app. Without this flag the only way to test R8 on
    // his device is to delete keystore.properties and remember to put it back, which is exactly the
    // sort of thing that ends with an unsigned release or a lost key.
    // ⚠️ Never publish a -PphoneKey build. It is signed with a keystore every Android install has.
    val phoneKey = providers.gradleProperty("phoneKey").isPresent
    val hasReleaseKey = keystoreProps.getProperty("storeFile") != null && !phoneKey
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // The screen tests under src/test run Compose on the JVM through Robolectric, and a
            // composable that reads a string resource or a theme token needs the real resources and
            // a real Context. This is what hands them over; without it every such test dies on the
            // first `stringResource` call with "no resources". (2026-08-20, audit item 8.)
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            // BouncyCastle ships bcprov, bcpkix and bcutil as multi-release jars, and all three
            // carry the same OSGi manifest path. Nothing on Android reads it, and three copies of a
            // file that cannot be told apart is a packaging clash rather than a real conflict.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
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
    // Virtual time + a background scope, to drive the unfolded conversations' live member stream
    // (a flow of flows) without sleeping on a real clock.
    testImplementation(libs.kotlinx.coroutines.test)
    // Screen tests (src/test/kotlin/app/gridlink/ui/**/*ScreenTest.kt): Compose's own test rule,
    // hosted on the JVM by Robolectric so they run inside `./gradlew test` with no device attached.
    // 🔴 Deliberately NOT an androidTest source set. The 2026-08-17 audit found every first-run
    // defect by hand because nothing covered the layer the user touches, and a suite that needs an
    // emulator plugged in is a suite that does not run. ui-test-manifest registers the empty
    // ComponentActivity the rule launches into; it is debug-only and never ships.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    // For the screen tests that need the real AppContainer (view-model-bound screens): the
    // container's init schedules WorkManager, which under Robolectric is not initialised by the
    // library's startup provider. TestGridlinkApplication (src/test) initialises a synchronous
    // test WorkManager before building the container; those tests opt in with @Config.
    testImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kotlin {
    // 🔴 The `compilerOptions` DSL, not `kotlinOptions`. The old block is an ERROR from Kotlin 2.2
    // on, which is what fails every Dependabot Kotlin bump before it compiles a line of source.
    // Same value as `compileOptions` above and they have to stay in step: Java and Kotlin bytecode
    // targets that disagree break at link time, not at build time.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
