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
 *
 * What it does NOT do: `navigateOnce` is an opt-in extension function, so nothing at the type
 * level stops a new graph from calling `nav.navigate(…)` bare. This catches it on the next test
 * run, after the fact; it does not make it impossible. Making it impossible means a type that
 * owns the controller, which is a design decision, not a lint rule.
 */
class NavHostSourceRulesTest {

    /**
     * Applies to EVERY file that hosts a NavHost, not just the two that exist today — a third
     * graph is checked action by action from the moment it is written. An action that must not
     * be de-duplicated (a single consumption: mailto:, a notification, a completion callback
     * that fires off a coroutine) opts out by carrying [EXEMPT] on its own line or in the
     * comment block directly above it, which is also where the reason has to be written.
     */
    @Test
    fun `every navigation action in a NavHost file goes through the shared guard`() {
        val offenders = mainSources()
            .filter { "NavHost(" in it.readText() }
            .flatMap { file ->
                val lines = file.readLines()
                lines.indices
                    .filter { lines[it].isNavAction() && !lines.isGuarded(it) && !lines.isExempt(it) }
                    .map { "${file.name}:${it + 1}" }
            }
        assertTrue(
            "navigation actions neither guarded by navigateOnce nor marked '$EXEMPT': $offenders",
            offenders.isEmpty(),
        )
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

    /**
     * Guarded either on the same line, or by the enclosing block: walking up to the first line
     * indented LESS than the action, that line must be the `navigateOnce {` that opened the block.
     * Indentation rather than brace counting, because the string templates inside the route
     * literals carry braces of their own and make naive counting lie.
     */
    private fun List<String>.isGuarded(index: Int): Boolean {
        val line = this[index]
        if (GUARD in line.substringBefore("nav.")) return true
        val indent = line.indentWidth()
        for (i in index - 1 downTo 0) {
            val candidate = this[i]
            if (candidate.isBlank() || candidate.indentWidth() >= indent) continue
            return GUARD in candidate
        }
        return false
    }

    /** Opted out on the action's own line, or in the contiguous comment block above it. */
    private fun List<String>.isExempt(index: Int): Boolean {
        if (EXEMPT in this[index]) return true
        for (i in index - 1 downTo 0) {
            if (!this[i].trimStart().startsWith("//")) return false
            if (EXEMPT in this[i]) return true
        }
        return false
    }

    private fun String.indentWidth() = length - trimStart().length

    companion object {
        private const val GUARD = "navigateOnce {"
        private const val EXEMPT = "unguarded:"

        /** Repo root, found by walking up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        fun source(relative: String): File = File(root, "app/src/main/kotlin/$relative")

        fun mainSources(): List<File> = File(root, "app/src/main/kotlin")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
