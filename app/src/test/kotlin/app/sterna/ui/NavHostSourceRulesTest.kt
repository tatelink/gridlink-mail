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

    /**
     * Leaving for another app is the other half of the problem [navigateOnce] solves, and the
     * harder half: `startActivity` does not demote us out of RESUMED the way `navigate()` does, so
     * the screen stays live for the few hundred milliseconds the other app takes to appear and a
     * second tap lands on a screen that still looks settled. Two browser windows is an annoyance;
     * Add to contacts and Add to calendar CREATE something, and the duplicate outlives the mail.
     *
     * This used to cover only calls of SettingsScreen's `openUrl` helper, on the grounds that
     * widening it while nine other hand-offs stayed unguarded would only spread opt-out markers
     * around — decoration, not protection. Those nine are guarded now, so the wide rule is the
     * protection: EVERY hand-off in the app module, in any file, NavHost or not.
     *
     * Three ways to satisfy it, and no fourth:
     *  - the tap goes through the opener from `rememberLeaveOnce` (`leaveOnce { … }`), anywhere in
     *    the enclosing blocks;
     *  - the line lives in a helper that REPORTS whether it left (a `: Boolean` return) — those are
     *    the openers themselves, and their own call sites are checked by this same rule;
     *  - it carries [EXEMPT] with the reason, for a hand-off that is not a tap at all.
     */
    @Test
    fun `every hand-off to another app goes through the leave guard`() {
        val offenders = mainSources().flatMap { file ->
            val lines = file.readLines()
            lines.indices
                .filter { i ->
                    lines[i].isAppHandOff() &&
                        !lines.isInsideLeaveGuard(i) &&
                        !lines.reportsWhetherItLeft(i) &&
                        !lines.isExempt(i)
                }
                .map { "${file.name}:${it + 1}" }
        }
        assertTrue(
            "hand-offs to another app neither guarded by $LEAVE_GUARD nor marked '$EXEMPT': $offenders",
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
     * A line that hands something to another app, in code — the prose about it and the openers' own
     * declarations do not count. Both the raw `startActivity(` and the named openers written around
     * it, so that pointing a new button at `addToContacts(…)` is caught as surely as building the
     * intent inline.
     */
    private fun String.isAppHandOff(): Boolean {
        val code = trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return false
        if ("fun " in code) return false
        return HAND_OFFS.any { it in code }
    }

    /**
     * Guarded by the opener on this line or by any block enclosing it. Walks strictly outwards by
     * indentation (same reason as [isGuarded]: string templates make brace counting lie), and
     * through EVERY level rather than only the innermost, because an opener that reports success
     * naturally wraps a `runCatching { … }` between the guard and the launch.
     */
    private fun List<String>.isInsideLeaveGuard(index: Int): Boolean {
        if (LEAVE_GUARD in this[index]) return true
        var indent = this[index].indentWidth()
        for (i in index - 1 downTo 0) {
            val candidate = this[i]
            if (candidate.isBlank() || candidate.indentWidth() >= indent) continue
            if (LEAVE_GUARD in candidate) return true
            indent = candidate.indentWidth()
        }
        return false
    }

    /**
     * The line sits in a helper that answers "did we actually leave?" — the shape the guard needs,
     * since a launch that threw must not arm the latch. Such a helper is not a tap: whoever calls
     * it is, and this rule sees that call too.
     */
    private fun List<String>.reportsWhetherItLeft(index: Int): Boolean {
        for (i in index downTo 0) {
            if (FUN_DECLARATION.containsMatchIn(this[i])) return ": Boolean" in this[i]
        }
        return false
    }

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
        private const val LEAVE_GUARD = "leaveOnce {"
        private const val EXEMPT = "unguarded:"

        /** Every way the app hands something to another app: the intent itself, and the openers. */
        private val HAND_OFFS = listOf(
            "startActivity(",
            "openUrl(",
            "openExternally(",
            "addToContacts(",
            "addToCalendar(",
        )

        private val FUN_DECLARATION = Regex("""^\s*(private |internal |public )?(suspend )?fun \w""")

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
