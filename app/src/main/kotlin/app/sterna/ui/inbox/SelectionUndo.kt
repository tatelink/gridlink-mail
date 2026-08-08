package app.sterna.ui.inbox

/**
 * One message a bulk action actually wrote, and where the forward action put it ([destMailboxId],
 * null when nothing moved — a snooze — so the drawer-count nudge has nothing to reverse).
 *
 * It carries the [SelectionTarget], not a bare row, because whether the row's folder can be
 * believed is exactly what decides if an Undo may be offered at all.
 */
internal data class UndoCandidate(val target: SelectionTarget, val destMailboxId: String?)

/**
 * The Undo entries a finished batch may offer for the messages it wrote ([succeeded]).
 *
 * Undo is `restoreAll`, i.e. `client.move(..., sourceMailboxId)`, which OVERWRITES the message's
 * `mailboxIds` with `{source: true}` (JmapClient.kt:1219). The source therefore has to be the
 * folder the message really was in, and two rows cannot supply it:
 *
 *  - an untrusted row (drawn from the search index, its `mailboxId` frozen at crawl time and
 *    sometimes empty) — undoing it would file the message into a stale folder AND strip every
 *    other folder it belongs to, silently and with no way back;
 *  - a row naming no folder, or an EMPTY one: `mailboxId?.let { }` lets `""` through, and moving
 *    to an empty id is the same irreversible write against a folder that does not exist. Its twin
 *    guard sits on the destruction side (TrashPurge.kt:146).
 *
 * And when even one untrusted row went through, the WHOLE batch's Undo is withheld: an Undo that
 * would put back four of the ten messages the user just archived is a lie about what it undoes.
 * The action itself stays done for all ten — only the offer to reverse it is dropped.
 *
 * Returns plain data rather than posting the snackbar, so this decision runs in a JVM test —
 * `InboxViewModel` is an `AndroidViewModel` and cannot be instantiated in one.
 */
internal fun selectionUndoEntries(succeeded: List<UndoCandidate>): List<UndoEntry> {
    if (succeeded.any { !it.target.folderTrusted }) return emptyList()
    return succeeded.mapNotNull { candidate ->
        val email = candidate.target.email
        val source = email.mailboxId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        UndoEntry(email.id, email.accountId, source, candidate.destMailboxId)
    }
}
