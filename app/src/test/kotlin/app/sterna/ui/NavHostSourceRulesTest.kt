package app.sterna.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads the navigation sources as text and checks the
 * rules a compiler cannot: it proves nothing about what happens on screen, and it would still
 * pass if the guard it looks for were broken internally.
 *
 * It exists because the defect it guards against is precisely a silent omission: the settings
 * NavHost was written without the re-entrancy guard the outer one already had, and nothing —
 * not the compiler, not a review — said so (Codeberg #106).
 */
class NavHostSourceRulesTest {

    @Test
    fun `every navigation action in the settings graph goes through the shared guard`() {
        val offenders = source("app/sterna/ui/settings/SettingsScreen.kt").readLines()
            .mapIndexed { i, line -> i + 1 to line }
            .filter { (_, line) -> line.isNavAction() && !line.isGuarded() }
        assertTrue("unguarded navigation actions: $offenders", offenders.isEmpty())
    }

    @Test
    fun `a file that hosts a NavHost uses the shared guard`() {
        val offenders = mainSources()
            .filter { "NavHost(" in it.readText() && "navigateOnce" !in it.readText() }
            .map { it.name }
        assertTrue("NavHost without the shared re-entrancy guard: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the resumed-entry check lives in exactly one place`() {
        val offenders = mainSources()
            .filter { it.name != "NavGuard.kt" && "Lifecycle.State.RESUMED" in it.readText() }
            .map { it.name }
        assertTrue("hand-rolled copy of the navigation guard: $offenders", offenders.isEmpty())
    }

    private fun String.isNavAction() = "nav.navigate(" in this || "nav.popBackStack(" in this

    /** The guard has to open before the action on the same line — the shape used in that file. */
    private fun String.isGuarded() = "navigateOnce {" in substringBefore("nav.")

    companion object {
        /** Repo root, found by walking up from the module's working directory. */
        private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt").isFile }

        fun source(relative: String): File = File(root, "app/src/main/kotlin/$relative")

        fun mainSources(): List<File> = File(root, "app/src/main/kotlin")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
