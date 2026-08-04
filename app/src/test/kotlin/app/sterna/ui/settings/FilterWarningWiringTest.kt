package app.sterna.ui.settings

import org.junit.Assert.assertEquals
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
 * - the decision reached with the wrong arguments: a warning refreshed from `previous = null`
 *   (which is the M2 bug written back in), or a state folded from a status nobody read.
 *
 * The observations themselves are no longer handed to `filterScriptWarning` here: they travel
 * inside a `FilterScriptStatus`, and the mapping is executed fact by fact in
 * `app.sterna.core.data.filter.FilterStatusRefreshTest` and in [FiltersStatusUpdateTest].
 */
class FilterWarningWiringTest {

    @Test fun `the responder screen prints the statement of fact for the inactive script`() {
        assertTrue(
            "SettingsScreen must map RULES_NOT_RUNNING to R.string.settings_vacation_filters_not_running " +
                "— the responder screen states the problem and its own Save is grey, so its sentence " +
                "is the one that has to say where the remedy is",
            ARM_NOT_RUNNING.containsMatchIn(text(SETTINGS_SCREEN)),
        )
    }

    @Test fun `the responder screen names the path to the remedy, not just the problem`() {
        assertTrue(
            "the sentence takes its two arguments from the strings the user actually reads on " +
                "screen (Settings → Filters), like sender_volume_block_body does: written as " +
                "literals they drift out of the menu and out of the eight translations",
            ARM_NOT_RUNNING_ARGS.containsMatchIn(text(SETTINGS_SCREEN)),
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

    @Test fun `the filters screen chooses its foreign-script line through the tested decision`() {
        val source = text(FILTERS_SCREEN)
        assertTrue(
            "FiltersScreen must ask foreignScriptNotice(...) with BOTH facts: without " +
                "vacationScriptExists the line falls back to \"another filter script is active\", " +
                "which names the cause and hides what pressing Save costs",
            Regex(
                "foreignScriptNotice\\(\\s*foreignActive = state\\.foreignActive,\\s*" +
                    "vacationScriptExists = state\\.vacationScriptExists,",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the auto-reply case must print R.string.settings_filters_stops_auto_reply",
            Regex(
                "ForeignScriptNotice\\.STOPS_AUTO_REPLY\\s*->\\s*R\\.string\\.settings_filters_stops_auto_reply",
            ).containsMatchIn(source),
        )
        assertTrue(
            "and the case where nothing says what the other script is keeps the generic sentence",
            Regex(
                "ForeignScriptNotice\\.ANOTHER_SCRIPT\\s*->\\s*R\\.string\\.settings_filters_foreign_warning",
            ).containsMatchIn(source),
        )
    }

    @Test fun `the filters view model folds a status read into the state, at load and after a save`() {
        // Written back as `it.copy(rulesNotRunning = ...)` here, the update leaves the reach of
        // every test again — and it is where it was wrong: two of the three facts were never
        // refreshed after a save, and a failed read wiped the third.
        val source = text(FILTERS_VM)
        assertEquals(
            "FiltersViewModel must fold BOTH reads through filtersStateWithStatus(...) — the load " +
                "and the re-read after a save",
            2,
            Regex("filtersStateWithStatus\\(").findAll(source).count(),
        )
        // Measured, not supposed: with only "a call to loadFilterScriptStatus exists somewhere in
        // this file" asserted, replacing the LOAD's status with `null` left the suite green — the
        // save's own read matched the pattern and answered for it. Each of the two call sites is
        // pinned on its own.
        assertTrue(
            "the load must fold in a status it read ITSELF (`status = repo.loadFilterScriptStatus(…)`)",
            Regex("status = repo\\.loadFilterScriptStatus\\(credentials\\),").containsMatchIn(source),
        )
        assertTrue(
            "and the save must fold in the status IT read, not null and not a fresh value",
            Regex("val status = repo\\.loadFilterScriptStatus\\(credentials\\)").containsMatchIn(source) &&
                Regex("status = status").containsMatchIn(source),
        )
        assertTrue(
            "the state folded into after a save must be the CURRENT one (`state = it`): rebuilt " +
                "from scratch, the save would drop the rules and the label with it",
            Regex("filtersStateWithStatus\\(state = it, status = status\\)").containsMatchIn(source),
        )
        assertTrue(
            "the load must carry over what the RULES read concluded (`foreignFromRulesRead = " +
                "result.foreignActiveScript`): the script list cannot see a `sterna` script we " +
                "failed to parse, so passing false there loses the refusal that protects it",
            Regex("foreignFromRulesRead = result\\.foreignActiveScript").containsMatchIn(source),
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

    @Test fun `the responder view model keeps the line it has when the re-read fails`() {
        // `previous = null` here IS the bug, written back: a save whose follow-up read fails then
        // clears the warning — over the account where the responder has just been switched off and
        // the server left no script active at all.
        val source = text(VACATION_VM)
        assertTrue(
            "VacationViewModel must refresh through refreshedFilterWarning(previous = it.filterWarning, …) " +
                "after a save, so a read that failed leaves what is on screen alone",
            Regex(
                "refreshedFilterWarning\\(\\s*previous = it\\.filterWarning,\\s*status = status,",
            ).containsMatchIn(source),
        )
        assertTrue(
            "and the status it folds in must be one this run actually read",
            Regex("val status = repo\\.loadFilterScriptStatus\\(credentials\\)").containsMatchIn(source),
        )
    }

    /** Comment lines dropped whole: a false match is a false failure, not a false pass. */
    private fun text(file: File): String = file.readLines()
        .filterNot { val c = it.trimStart(); c.startsWith("//") || c.startsWith("*") || c.startsWith("/*") }
        .joinToString("\n")

    private companion object {
        val ARM_NOT_RUNNING = Regex(
            "FilterScriptWarning\\.RULES_NOT_RUNNING\\s*->\\s*stringResource\\(\\s*" +
                "R\\.string\\.settings_vacation_filters_not_running,",
        )
        val ARG_MENU = Regex("stringResource\\(R\\.string\\.inbox_settings\\)")
        val ARG_FILTERS = Regex("stringResource\\(R\\.string\\.settings_filters_title\\)")
        val ARM_NOT_RUNNING_ARGS = Regex(
            ARM_NOT_RUNNING.pattern + "\\s*" + ARG_MENU.pattern + ",\\s*" + ARG_FILTERS.pattern + ",",
        )
        val ARM_WILL_SUSPEND = Regex(
            "FilterScriptWarning\\.RESPONDER_WILL_SUSPEND_RULES\\s*->\\s*" +
                "stringResource\\(R\\.string\\.settings_vacation_suspends_filters\\)",
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
