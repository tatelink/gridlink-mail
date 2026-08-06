package app.gridlink.ui.inbox

/**
 * What the system Back gesture does on the list screen, in priority order.
 *
 * Back is a global mechanism and every mode the list can be in has to give it back a step
 * before the app itself is left, so the rule is stated once, here, and the screen only binds
 * one handler per outcome. [LEAVE_APP] means no handler is enabled and the system does its
 * usual thing (the list is the start destination, so: leave the app).
 */
internal enum class InboxBackAction {
    /** Multi-select is on: back drops the selection. */
    CLEAR_SELECTION,

    /** Inline search is open: back closes it and gives the list back (Codeberg #86). */
    CLOSE_SEARCH,

    /** A folder other than the Inbox is open: back returns to the Inbox. */
    SHOW_INBOX,

    /** Nothing left to undo on this screen — the system handles it. */
    LEAVE_APP,
}

/**
 * The single Back rule of the list screen. Modes are peeled off one at a time, innermost
 * first, so each press undoes exactly the last thing the user turned on.
 *
 * Search sits between the selection and the folder on purpose: searching inside a custom
 * folder takes two presses (leave the search, then leave the folder), which is what closing
 * the search by hand then pressing Back would do.
 *
 * The drawer is NOT part of this: [androidx.compose.material3.ModalNavigationDrawer] registers
 * its own back handling while it is open, and — being composed after these — it wins, so an
 * open drawer still closes on Back before any of this applies.
 */
internal fun inboxBackAction(
    selectionActive: Boolean,
    searching: Boolean,
    atInbox: Boolean,
): InboxBackAction = when {
    selectionActive -> InboxBackAction.CLEAR_SELECTION
    searching -> InboxBackAction.CLOSE_SEARCH
    !atInbox -> InboxBackAction.SHOW_INBOX
    else -> InboxBackAction.LEAVE_APP
}
