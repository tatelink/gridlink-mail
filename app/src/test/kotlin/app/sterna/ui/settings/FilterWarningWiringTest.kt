package app.sterna.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 * [SettingsScreenHonestyTest]: it reads files as text and proves nothing about what is drawn.
 * The decision itself is executed, not read, in
 * `app.sterna.core.data.filter.FilterScriptWarningTest`; this only holds the wiring around it,
 * which cannot be executed here (a composable, and an Application-bound view model).
 *
 * What it closes, all of them ways the fix could be present and useless:
 * - the warning computed and never displayed;
 * - the two cases mapped to each other's sentence — the statement of fact printed where the
 *   prediction belongs reads as a lie about the present, and the prediction printed where the
 *   fact belongs is a warning about something that has already happened;
 * - the four observations shuffled on their way into the decision. They are all booleans but one,
 *   so the compiler cannot see a swap, and the decision would then be right about the wrong facts.
 */
class FilterWarningWiringTest {

    @Test fun `the responder screen prints the statement of fact for the inactive script`() {
        assertTrue(
            "SettingsScreen must map RULES_NOT_RUNNING to R.string.settings_filters_not_running — " +
                "the case where the rules are already stopped is the one the screen was silent about",
            ARM_NOT_RUNNING.containsMatchIn(text(SETTINGS_SCREEN)),
        )
    }

    @Test fun `the responder screen prints the prediction only for the other case`() {
        assertTrue(
            "SettingsScreen must map RESPONDER_WILL_SUSPEND_RULES to " +
                "R.string.settings_vacation_suspends_filters",
            ARM_WILL_SUSPEND.containsMatchIn(text(SETTINGS_SCREEN)),
        )
    }

    @Test fun `the filters screen prints the statement of fact, guarded by its own flag`() {
        val source = text(FILTERS_SCREEN)
        assertTrue(
            "FiltersScreen must show R.string.settings_filters_not_running: it lists the rules as " +
                "if they were filtering mail while the script carrying them is not active",
            "R.string.settings_filters_not_running" in source,
        )
        assertTrue(
            "the line must be guarded by state.rulesNotRunning, not by state.foreignActive — the " +
                "foreign flag is false in exactly the state where the rules are dead",
            Regex("if \\(state\\.rulesNotRunning\\)").containsMatchIn(source),
        )
    }

    @Test fun `the filters screen gates Save through the tested rule, not an expression of its own`() {
        val source = text(FILTERS_SCREEN)
        assertTrue(
            "the Save button must be gated by filtersSaveEnabled(...): written inline it goes back " +
                "to `!saving && dirty`, which greys out the ONE gesture that restarts rules the " +
                "server has stopped — the screen then states the problem and locks the door",
            Regex("enabled = filtersSaveEnabled\\(").containsMatchIn(source),
        )
        assertTrue(
            "filtersSaveEnabled must be given state.rulesNotRunning: without it the widening is " +
                "decorative and the dead end is back",
            Regex("rulesNotRunning = state\\.rulesNotRunning").containsMatchIn(source),
        )
    }

    @Test fun `the filters view model reads the verdict through the tested function`() {
        // Written back as `warning == FilterScriptWarning.RULES_NOT_RUNNING` here, the reading
        // leaves the reach of every test again: one character turns it into `!=`, the screen
        // shouts in the nominal state and goes quiet when the rules are dead, and nothing fails.
        assertTrue(
            "FiltersViewModel must go through rulesAreNotRunning(...) rather than comparing the " +
                "enum in place",
            Regex("rulesAreNotRunning\\(").containsMatchIn(text(FILTERS_VM)),
        )
    }

    @Test fun `the prediction is gated on what the server holds, never on a literal`() {
        // Passing a constant here is how the responder screen came to tell somebody whose
        // auto-reply was already running that switching it on would suspend their rules.
        val literals = text(VACATION_VM).lines()
            .filter { Regex("responderEnabled = (true|false)\\b").containsMatchIn(it) }
        assertTrue(
            "VacationViewModel must pass the responder state the SERVER holds to " +
                "filterScriptWarning, not a hard-coded true/false. Found:\n" + literals.joinToString("\n"),
            literals.isEmpty(),
        )
    }

    @Test fun `both view models hand the decision the facts under their own names`() {
        for (file in listOf(VACATION_VM, FILTERS_VM)) {
            val source = text(file)
            for (field in FACTS) {
                assertTrue(
                    "${file.name} must pass `$field = scripts.$field` to filterScriptWarning: the " +
                        "four observations are three booleans and a count, so a swapped pair " +
                        "compiles and the decision is then right about the wrong facts",
                    Regex("$field = scripts\\.$field\\b").containsMatchIn(source),
                )
            }
        }
    }

    /** Comment lines dropped whole: a false match is a false failure, not a false pass. */
    private fun text(file: File): String = file.readLines()
        .filterNot { val c = it.trimStart(); c.startsWith("//") || c.startsWith("*") || c.startsWith("/*") }
        .joinToString("\n")

    private companion object {
        val FACTS = listOf("scriptExists", "scriptActive", "enabledRuleCount", "vacationScriptExists")

        val ARM_NOT_RUNNING =
            Regex("FilterScriptWarning\\.RULES_NOT_RUNNING\\s*->\\s*R\\.string\\.settings_filters_not_running")
        val ARM_WILL_SUSPEND = Regex(
            "FilterScriptWarning\\.RESPONDER_WILL_SUSPEND_RULES\\s*->\\s*" +
                "R\\.string\\.settings_vacation_suspends_filters",
        )

        const val SETTINGS_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_SCREEN: File by lazy { File(root, SETTINGS_SCREEN_PATH) }
        val FILTERS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt")
        }
        val VACATION_VM: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/VacationViewModel.kt")
        }
        val FILTERS_VM: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/FiltersViewModel.kt")
        }
    }
}
