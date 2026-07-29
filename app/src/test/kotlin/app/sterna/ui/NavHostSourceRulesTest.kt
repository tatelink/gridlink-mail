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

    /** A real assertion, not lint: DESIGN.md caps every animation in the app at 250 ms. */
    @Test
    fun `the screen slide obeys the design cap`() {
        assertTrue("DESIGN.md caps motion at 250 ms, got $SCREEN_SLIDE_MS", SCREEN_SLIDE_MS <= 250)
    }

    @Test
    fun `no navigation transition is written longer than the design cap`() {
        val tooLong = navGraphs().flatMap { file ->
            Regex("""tween\((\d+)""").findAll(file.readText())
                .map { file.name to it.groupValues[1].toInt() }
                .filter { (_, ms) -> ms > 250 }
                .toList()
        }
        assertTrue("transition over DESIGN.md's 250 ms cap: $tooLong", tooLong.isEmpty())
    }

    @Test
    fun `every navigation transition falls back to a static state when the system asks`() {
        val motion = listOf("slideInHorizontally(", "slideOutHorizontally(", "fadeIn(", "fadeOut(")
        val ungated = navGraphs().flatMap { file ->
            file.readLines().mapIndexed { i, line -> Triple(file.name, i + 1, line) }
        }.filter { (_, _, line) ->
            motion.any { it in line } && "motionEnabled" !in line
        }
        assertTrue("motion not gated on the reduced-motion setting: $ungated", ungated.isEmpty())
    }

    /**
     * The reader's fade must test the crash sentinel FIRST: on devices latched after the #10
     * GL-functor SIGSEGV, a running fade over a hardware WebView is a hard crash, not a taste.
     */
    @Test
    fun `the crash sentinel still short-circuits the reader fade`() {
        val text = source("app/sterna/ui/SternaApp.kt").readText()
        assertTrue(
            "the #10 latch must come first in both reader fade lambdas",
            "if (messageFadeDisabled || !motionEnabled) EnterTransition.None" in text &&
                "if (messageFadeDisabled || !motionEnabled) ExitTransition.None" in text,
        )
    }

    private fun navGraphs() = listOf(
        source("app/sterna/ui/SternaApp.kt"),
        source("app/sterna/ui/settings/SettingsScreen.kt"),
    )

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
