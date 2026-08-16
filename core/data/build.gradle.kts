import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.gridlink.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:jmap"))
    api(project(":core:imap"))
    api(project(":core:dav"))
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    api(libs.androidx.paging.runtime)

    implementation(libs.androidx.datastore.preferences)

    // S/MIME signature verification (item 9). Verify only: no private key is ever imported,
    // so nothing here can sign, decrypt or be stolen off the phone.
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.junit)
    // Virtual time, to unit-test the outbox badge without waiting out real undo windows.
    testImplementation(libs.kotlinx.coroutines.test)
    // Real SQLite engine for unit-testing the conversation-grouping SQL on the JVM.
    testImplementation("org.xerial:sqlite-jdbc:3.53.2.1")
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
