import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// Top-level build file. Plugins are declared here (apply false) and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt)
}

// Static analysis, applied to every module. `./gradlew detekt` runs the lot.
//
// This is a fork with ~1000 inherited upstream commits, so detekt runs against a
// BASELINE: every finding that already existed when it was switched on is recorded
// in config/detekt/baseline-*.xml and stays silent. Only NEW findings fail. That is
// deliberate — a first run without a baseline reports thousands of upstream issues,
// which is noise nobody acts on. Regenerate a module's baseline only when you mean
// to forgive its current state:
//
//     ./gradlew :app:detektBaseline
// Captured here on purpose: the type-safe `libs` accessor belongs to this script's
// scope, and referring to it directly inside subprojects {} does not resolve.
val detektFormatting = libs.detekt.formatting

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension> {
        // Our detekt.yml overrides only what the Compose style needs; everything
        // else falls through to detekt's shipped defaults.
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = rootProject.file("config/detekt/baseline-${project.path.removePrefix(":").replace(':', '-')}.xml")
        parallel = true
        // Modules keep sources in src/*/kotlin; src/debug carries the sample-data
        // gallery build, which is real code and worth linting too.
        source.setFrom(
            files(
                "src/main/kotlin",
                "src/main/java",
                "src/debug/kotlin",
                "src/test/kotlin",
                "src/test/java",
            ),
        )
    }

    dependencies {
        add("detektPlugins", detektFormatting)
    }

    tasks.withType<Detekt>().configureEach {
        // Match the modules' compile target so detekt reads the same bytecode level.
        jvmTarget = "17"
        reports {
            html.required.set(true)
            txt.required.set(true)
            xml.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }
}
