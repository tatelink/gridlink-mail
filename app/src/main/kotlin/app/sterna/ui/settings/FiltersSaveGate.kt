package app.sterna.ui.settings

/**
 * When the filters screen may offer its Save button (#34, and the dead-rules dead end).
 *
 * Save had one meaning — "push what you changed" — so it was gated on [dirty], and that is still
 * the rule when the rules are running. But Save has a second meaning the screen never admitted:
 * it regenerates the script AND makes it the active one, so it is the ONE gesture that brings
 * back rules a server has stopped running. In the state the return from holiday leaves an account
 * in, the screen said "your filter rules are not being applied at the moment" over a list
 * identical to the server's — [dirty] false, Save greyed out, and no way to act on what had just
 * been announced. Toggling a rule off and on again, or adding one and deleting it, does not open
 * the door either: those come back to the same list.
 *
 * So the button opens on [rulesNotRunning] as well. ⚠ [dirty] itself is NOT touched: it guards the
 * confirm-on-exit dialog and `pendingExitStep`, and a dirty screen nobody edited would ask "save
 * your changes?" on the way out of a form that has none. Only the Save button's own condition
 * widens, which is why it lives here as a function and not as an expression inside the composable.
 *
 * @param saving a write is already in flight — never two.
 * @param dirty the rules on screen differ from the ones the server confirmed.
 * @param rulesNotRunning the server holds these rules but is not running them.
 */
internal fun filtersSaveEnabled(saving: Boolean, dirty: Boolean, rulesNotRunning: Boolean): Boolean =
    !saving && (dirty || rulesNotRunning)
