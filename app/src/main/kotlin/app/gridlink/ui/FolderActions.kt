package app.gridlink.ui

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

/**
 * Whether "Snooze" is worth offering in a folder (Codeberg #82).
 *
 * Snoozing means "hide this until later, then bring it back to me". That is a promise about mail
 * you still intend to deal with, so on top of Drafts and Sent it is dropped in Spam and in the
 * Trash: what you already threw away or already refused is not waiting for your attention, and a
 * message sent back later would land in a folder you don't read anyway.
 *
 * Deliberately narrower than hiding the whole incoming-only group there: "Not spam" is exactly what
 * the Spam folder is for, and "Mark all read" still means something in Spam and Trash. Same rule as
 * above — an action is hidden only where it is provably meaningless, and a null/unknown role keeps
 * everything.
 */
internal fun canSnoozeIn(role: String?): Boolean =
    role?.trim()?.lowercase() !in NO_SNOOZE_ROLES

// "spam" as well as "junk": JMAP names the role `junk`, and the IMAP side maps SPECIAL-USE \Junk
// and folders literally named "Spam" onto it — but a role reaching here unmapped must not slip
// through on a spelling.
private val NO_SNOOZE_ROLES = OUTGOING_ROLES + setOf("junk", "spam", "trash")
