package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterScriptStatus
import app.sterna.core.data.filter.refreshedFilterWarning
import app.sterna.core.data.filter.rulesAreNotRunning

/**
 * Fold a status read into the filters screen's state — the facts that describe the SERVER.
 *
 * ⚠ TWO reads, not one. Three of the facts come from [status] (`SieveScript/get`, one round
 * trip); the two [foreignFromRulesRead] / [unreadableFromRulesRead] come from the RULES read,
 * which is a second round trip a moment earlier and can therefore describe a slightly different
 * server. They are OR-ed rather than merged: the answer leans towards the alarm, so a script that
 * became unreadable between the two reads still refuses the write, and the screen never goes
 * quiet on the strength of the older half.
 *
 * Two failures this closes, both of them a screen showing half a truth:
 *
 *  - **half-refreshed.** After a save, only `rulesNotRunning` was read again. `foreignActive` kept
 *    whatever the initial load had found, so the red "another script is active" line stayed under
 *    a screen that had just made Sterna's script the active one — an alarm about a state that no
 *    longer existed, right next to a line that had been updated.
 *  - **erased by a failure.** A null [status] is a read that FAILED, not an all-clear. Overwriting
 *    with the "nothing known" value blanks the warnings at the moment they become true (switch the
 *    responder off and no script is active at all), so a null read changes nothing at all: the
 *    screen keeps what it had until a read succeeds.
 *
 * A function on the state rather than an update inside the view model because that view model is
 * `Application`-bound and no JVM test can build one — the decision would be untestable exactly
 * where it was wrong. [FiltersUiState] is an ordinary data class, so this runs in a plain test.
 *
 * @param foreignFromRulesRead what the RULES read concluded, both causes folded together: another
 *   active script, or a `sterna` script it could not parse. The second cannot be seen in the
 *   script list at all — that list carries names and active flags, nothing about content — so it
 *   is OR-ed in and never cleared by a status read that did not look at it. Left at its default
 *   after a save, where the script on the server is the one just written.
 * @param unreadableFromRulesRead the second cause ALONE, so the screen can say which one it is.
 *   Same source, same OR, same default after a save — a save replaces the unreadable script, so
 *   the line that named it goes out with the read that follows.
 */
internal fun filtersStateWithStatus(
    state: FiltersUiState,
    status: FilterScriptStatus?,
    foreignFromRulesRead: Boolean = false,
    unreadableFromRulesRead: Boolean = false,
): FiltersUiState {
    val next = if (status == null) {
        state
    } else {
        state.copy(
            // responderEnabled = null: this screen never loads the responder, and only the
            // statement of fact is read out of the verdict. The prediction belongs next to the
            // switch that triggers it.
            rulesNotRunning = rulesAreNotRunning(
                refreshedFilterWarning(previous = null, status = status, responderEnabled = null),
            ),
            foreignActive = status.foreignActive,
            vacationScriptActive = status.vacationScriptActive,
            // A successful read is a fresh answer about the script on the server, which after a
            // save is the one just written: whatever the previous rules read concluded is stale,
            // and only THIS call's argument may raise it again, one line below.
            scriptUnreadable = false,
        )
    }
    return next.copy(
        foreignActive = next.foreignActive || foreignFromRulesRead,
        scriptUnreadable = next.scriptUnreadable || unreadableFromRulesRead,
    )
}
