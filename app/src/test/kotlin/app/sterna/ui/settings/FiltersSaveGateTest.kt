package app.sterna.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Save button of the filters screen, as plain logic so it can be executed rather than read —
 * same treatment as [pendingExitStep], for the same reason: it is a rule about when a screen may
 * act, and it was wrong in the one state that mattered.
 */
class FiltersSaveGateTest {

    @Test fun `nothing to push while the rules are running and unchanged`() {
        // The #34 rule, unchanged: an untouched screen must not offer a write.
        assertFalse(filtersSaveEnabled(saving = false, dirty = false, rulesNotRunning = false))
    }

    @Test fun `an edited rule may be pushed`() {
        assertTrue(filtersSaveEnabled(saving = false, dirty = true, rulesNotRunning = false))
    }

    @Test fun `stopped rules may be saved back even though nothing was edited`() {
        // THE POINT. This is the state the return from holiday leaves the account in: the screen
        // says the rules are not being applied, and Save is what re-activates the script. Greying
        // it out is telling the truth and locking the door.
        assertTrue(
            "Save must be reachable when the server holds the rules but is not running them — " +
                "it is the only gesture that puts them back",
            filtersSaveEnabled(saving = false, dirty = false, rulesNotRunning = true),
        )
    }

    @Test fun `never two writes at once`() {
        for (dirty in listOf(true, false)) {
            for (notRunning in listOf(true, false)) {
                assertFalse(
                    "a write in flight closes the button whatever else is true " +
                        "(dirty=$dirty, rulesNotRunning=$notRunning)",
                    filtersSaveEnabled(saving = true, dirty = dirty, rulesNotRunning = notRunning),
                )
            }
        }
    }
}
