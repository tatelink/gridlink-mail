package app.sterna.ui

/**
 * Folders that hold the user's OWN outgoing mail: Drafts and Sent.
 *
 * Triage actions that only make sense on incoming mail are hidden there (Codeberg #82):
 * you do not snooze a draft you are still writing, you do not report your own sent message
 * as spam, and you do not "mark all read" a folder whose unread state means nothing. One
 * predicate, checked once per menu, rather than a role test scattered across every action.
 *
 * A null/unknown role (a custom folder, or a folder whose role has not resolved yet) keeps
 * the actions: the rule hides them only where they are provably meaningless.
 */
internal fun isOutgoingFolder(role: String?): Boolean =
    role?.trim()?.lowercase() in OUTGOING_ROLES

private val OUTGOING_ROLES = setOf("drafts", "sent")
