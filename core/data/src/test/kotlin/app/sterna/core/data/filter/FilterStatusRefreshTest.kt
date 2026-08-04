package app.sterna.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two decisions the filters and responder screens make ON TOP of [filterScriptWarning]:
 * what a status read that FAILED does to what is on screen ([refreshedFilterWarning]), and which
 * red line goes above the Save button when saving would switch another script off
 * ([foreignScriptNotice]).
 *
 * Both are executed here, not read out of the source: they are plain functions, so there is no
 * excuse for a lint. The screens that call them are a composable and two `Application`-bound view
 * models, which is exactly why the decision was lifted out of them.
 */
class FilterStatusRefreshTest {

    // ---- refreshedFilterWarning: "I could not look" is not "nothing to report" -------------

    @Test
    fun `a failed read keeps the warning that was on screen`() {
        assertEquals(
            "a null status is a read that FAILED. Treated as an all-clear it wipes the line at " +
                "the moment it becomes true — see the ON to OFF case below",
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(
                previous = FilterScriptWarning.RULES_NOT_RUNNING,
                status = null,
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `a failed read keeps the prediction too, and keeps silence silent`() {
        assertEquals(
            FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
            refreshedFilterWarning(
                previous = FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
                status = null,
                responderEnabled = false,
            ),
        )
        assertNull(
            "a failed read must not INVENT a warning either",
            refreshedFilterWarning(previous = null, status = null, responderEnabled = false),
        )
    }

    @Test
    fun `a read that succeeds and finds nothing clears the warning`() {
        // The other half of the same rule. A red line that survives a good read is the same lie
        // the other way round: after a save the Sterna script is active again and the account is
        // in the nominal state.
        assertNull(
            "a status that says the rules are running must retire a stale RULES_NOT_RUNNING",
            refreshedFilterWarning(
                previous = FilterScriptWarning.RULES_NOT_RUNNING,
                status = FilterScriptStatus(
                    scriptExists = true,
                    scriptActive = true,
                    enabledRuleCount = 2,
                    vacationScriptExists = false,
                ),
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `a read that succeeds replaces one warning with the other`() {
        assertEquals(
            "the rules just stopped: the prediction on screen must give way to the fact",
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(
                previous = FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
                status = FilterScriptStatus(
                    scriptExists = true,
                    scriptActive = false,
                    enabledRuleCount = 2,
                    vacationScriptExists = true,
                ),
                responderEnabled = true,
            ),
        )
    }

    /**
     * The case the whole nullable return exists for, end to end, as measured on the server:
     * responder ON (rules already suspended, screen silent because the prediction is not made to
     * somebody who has done the thing predicted) → the user switches it OFF and saves → the server
     * leaves NO script active at all, so the rules are dead → the read that follows the save
     * fails. The screen must not go quiet: quiet is what it was when the rules were fine.
     */
    @Test
    fun `switching the responder off and losing the read does not silence the screen`() {
        val whileAway = refreshedFilterWarning(
            previous = null,
            status = FilterScriptStatus(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 3,
                vacationScriptExists = true,
                foreignActive = true,
            ),
            responderEnabled = true,
        )
        assertEquals(
            "with the responder on, the rules are already stopped and that is a fact, not a prediction",
            FilterScriptWarning.RULES_NOT_RUNNING,
            whileAway,
        )

        val afterTurningItOff = refreshedFilterWarning(
            previous = whileAway,
            status = null,
            responderEnabled = false,
        )
        assertEquals(
            "the rules are dead and the app could not check: keeping the line is the only honest " +
                "answer, and the only one that leaves the Save button open",
            FilterScriptWarning.RULES_NOT_RUNNING,
            afterTurningItOff,
        )
    }

    @Test
    fun `the status is read fact by fact, under its own name`() {
        // Three booleans and a count go into the decision, so a swapped pair compiles and the
        // decision is then right about the wrong facts. Each case below moves ONE fact.
        val running = FilterScriptStatus(
            scriptExists = true,
            scriptActive = true,
            enabledRuleCount = 1,
            vacationScriptExists = false,
        )
        assertNull(
            "script present, active, one rule, no vacation script: the nominal state",
            refreshedFilterWarning(previous = null, status = running, responderEnabled = false),
        )
        assertEquals(
            "only scriptActive changed",
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(
                previous = null,
                status = running.copy(scriptActive = false),
                responderEnabled = false,
            ),
        )
        assertEquals(
            "only vacationScriptExists changed",
            FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
            refreshedFilterWarning(
                previous = null,
                status = running.copy(vacationScriptExists = true),
                responderEnabled = false,
            ),
        )
        val stopped = running.copy(scriptActive = false)
        assertNull(
            "from the stopped state, only scriptExists changed: no script, nothing to say",
            refreshedFilterWarning(
                previous = null,
                status = stopped.copy(scriptExists = false),
                responderEnabled = false,
            ),
        )
        assertNull(
            "from the stopped state, only enabledRuleCount changed: nothing that filters, no alarm",
            refreshedFilterWarning(
                previous = null,
                status = stopped.copy(enabledRuleCount = 0),
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `the responder state reaches the decision from the caller, not from the status`() {
        val status = FilterScriptStatus(
            scriptExists = true,
            scriptActive = true,
            enabledRuleCount = 1,
            vacationScriptExists = true,
        )
        assertEquals(
            FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
            refreshedFilterWarning(previous = null, status = status, responderEnabled = false),
        )
        assertNull(
            "the prediction is not made to somebody whose responder is already on",
            refreshedFilterWarning(previous = null, status = status, responderEnabled = true),
        )
        assertNull(
            "nor on a guess: the filters screen never loads the responder and passes null",
            refreshedFilterWarning(previous = null, status = status, responderEnabled = null),
        )
    }

    // ---- foreignScriptNotice: which red line goes above Save --------------------------------

    @Test
    fun `no foreign active script says nothing, whatever else exists`() {
        assertNull(
            "our own script being the active one is the nominal state: no line at all",
            foreignScriptNotice(foreignActive = false, vacationScriptExists = false),
        )
        assertNull(
            "a vacation script that is NOT the active one costs nothing to save over",
            foreignScriptNotice(foreignActive = false, vacationScriptExists = true),
        )
    }

    @Test
    fun `a foreign active script with no vacation script keeps the generic line`() {
        // Nothing observed says what that script is, so nothing is claimed about what Save costs.
        assertEquals(
            ForeignScriptNotice.ANOTHER_SCRIPT,
            foreignScriptNotice(foreignActive = true, vacationScriptExists = false),
        )
    }

    @Test
    fun `a foreign active script beside a vacation script names the auto-reply`() {
        // B-2, the whole point: this release opens Save with nothing edited, and pressing it
        // during a holiday switches the auto-reply off. "Another filter script is active on the
        // server" is true and names nothing the user recognises.
        assertEquals(
            ForeignScriptNotice.STOPS_AUTO_REPLY,
            foreignScriptNotice(foreignActive = true, vacationScriptExists = true),
        )
    }
}
