package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterScriptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [filtersStateWithStatus], executed. The filters screen carries three facts about the SERVER —
 * "your rules are not running", "another script is active", "there is a vacation script" — and
 * they must move together, from one read, or not at all.
 *
 * `FiltersViewModel` cannot be built in a JVM test (it is `Application`-bound), which is why the
 * update lives here as a function on an ordinary data class rather than inside it.
 */
class FiltersStatusUpdateTest {

    private val displayed = FiltersUiState(
        loading = false,
        rulesNotRunning = true,
        foreignActive = true,
        vacationScriptExists = true,
    )

    @Test
    fun `a read that failed leaves all three facts exactly as they were`() {
        // null is "I could not look", not "all clear". The case it protects: the responder has
        // just been switched off, the server left no script active, the rules are dead — and the
        // read that would have said so failed. Blanking here erases the warning at the moment it
        // becomes true, and closes the Save button that is the way out.
        assertEquals(displayed, filtersStateWithStatus(state = displayed, status = null))
    }

    @Test
    fun `a read that failed does not invent facts on a quiet screen either`() {
        val quiet = FiltersUiState(loading = false)
        assertEquals(quiet, filtersStateWithStatus(state = quiet, status = null))
    }

    @Test
    fun `a read that succeeds replaces all three facts`() {
        // The half-refresh this closes: after a save only `rulesNotRunning` was read again, so the
        // red "another script is active" line stayed under a screen that had just made Sterna's
        // script the active one — two halves of the screen describing two different moments.
        val next = filtersStateWithStatus(
            state = displayed,
            status = FilterScriptStatus(
                scriptExists = true,
                scriptActive = true,
                enabledRuleCount = 2,
                vacationScriptExists = false,
                foreignActive = false,
            ),
        )
        assertFalse("the rules are running again", next.rulesNotRunning)
        assertFalse("our script IS the active one now", next.foreignActive)
        assertFalse("no vacation script on this server", next.vacationScriptExists)
    }

    @Test
    fun `a read that succeeds raises all three facts`() {
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false),
            status = FilterScriptStatus(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 2,
                vacationScriptExists = true,
                foreignActive = true,
            ),
        )
        assertTrue("an inactive script carrying rules is rules that are not running", next.rulesNotRunning)
        assertTrue(next.foreignActive)
        assertTrue("the fact that lets the warning name the auto-reply", next.vacationScriptExists)
    }

    @Test
    fun `the three facts come from the status, one by one`() {
        // Three booleans on the way in and three on the way out: a crossed pair compiles.
        val stopped = FilterScriptStatus(
            scriptExists = true,
            scriptActive = false,
            enabledRuleCount = 1,
            vacationScriptExists = false,
            foreignActive = false,
        )
        val fresh = FiltersUiState(loading = false)
        with(filtersStateWithStatus(fresh, stopped)) {
            assertTrue(rulesNotRunning)
            assertFalse("no other script is active in the state that follows a holiday", foreignActive)
            assertFalse(vacationScriptExists)
        }
        with(filtersStateWithStatus(fresh, stopped.copy(foreignActive = true))) {
            assertTrue(rulesNotRunning)
            assertTrue(foreignActive)
            assertFalse(vacationScriptExists)
        }
        with(filtersStateWithStatus(fresh, stopped.copy(vacationScriptExists = true))) {
            assertTrue(rulesNotRunning)
            assertFalse(foreignActive)
            assertTrue(vacationScriptExists)
        }
        with(filtersStateWithStatus(fresh, stopped.copy(scriptActive = true))) {
            assertFalse("an active script carrying rules is rules that ARE running", rulesNotRunning)
        }
    }

    @Test
    fun `the rest of the screen is not touched by a status read`() {
        val editing = displayed.copy(dirty = true, saving = true, savedTick = 3, accountLabel = "a@b.c")
        val next = filtersStateWithStatus(editing, FilterScriptStatus())
        assertTrue("a status read must not undo an edit", next.dirty)
        assertTrue(next.saving)
        assertEquals(3, next.savedTick)
        assertEquals("a@b.c", next.accountLabel)
    }

    @Test
    fun `a sterna script the rules read could not parse keeps the refusal up`() {
        // Volet D put "we could not read our own script" behind the same red line, because saving
        // over it destroys content nobody managed to read. The script LIST cannot see that — it
        // carries names and active flags — so a status read must not clear it.
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false),
            status = FilterScriptStatus(scriptExists = true, scriptActive = true, enabledRuleCount = 1),
            foreignFromRulesRead = true,
        )
        assertTrue("the status read saw nothing foreign, but the rules read did", next.foreignActive)
    }

    @Test
    fun `and it survives a read that failed altogether`() {
        val next = filtersStateWithStatus(
            state = FiltersUiState(loading = false),
            status = null,
            foreignFromRulesRead = true,
        )
        assertTrue(next.foreignActive)
    }
}
