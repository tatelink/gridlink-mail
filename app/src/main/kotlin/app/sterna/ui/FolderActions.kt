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

/**
 * Whether a message should be shown by its RECIPIENTS ("To: …") instead of its sender — the one
 * decision the list row and the reader both make, so they cannot answer it differently about the
 * same message.
 *
 * Two things can make a message read as outgoing: the folder it sits in (Sent, Drafts — the author
 * is yourself there by construction, Codeberg #59), or the fact that you wrote it (Codeberg #69: a
 * sent message or a draft dragged to the Trash still has to say who it went to).
 *
 * The author test alone was true EVERYWHERE, and a message you send to yourself lands in the
 * Inbox — where it was announced as "To: your own address", i.e. as sent mail sitting in the
 * folder of received mail (#115). So the author test is dropped in a context that is explicitly
 * incoming, and only there:
 *
 *  - [role] `inbox`, and
 *  - [unified], the all-inboxes view — which selects no folder at all, so its role is null. That
 *    view aggregates Inboxes and nothing else, and it is the screen the app opens on, so a rule
 *    written for the role alone would leave the report standing where it was made.
 *
 * Everywhere else — Archive, Trash, Junk, a custom folder, a role that has not resolved yet — the
 * author test stands, because nothing there has established that the folder holds incoming mail.
 *
 * [role] is trimmed and lower-cased like its neighbours above: the IMAP side normalises roles,
 * the JMAP side hands them through as the server spells them.
 */
internal fun showsRecipients(role: String?, unified: Boolean, selfAuthored: Boolean): Boolean {
    val normalised = role?.trim()?.lowercase()
    if (normalised in OUTGOING_ROLES) return true
    if (unified || normalised == "inbox") return false
    return selfAuthored
}

/**
 * The same decision for a row INSIDE an unfolded conversation — the third surface, which used to
 * carry the pre-#115 rule under its own name and so contradicted the reader it opens.
 *
 * The difference is not the rule, it is the role: an unfolded conversation spans the viewed
 * folder(s) PLUS Sent, so its rows do not all live in the folder on screen and the visible folder's
 * role says nothing about them. [roles] is the (account, folder) → role map the folder cache
 * publishes, and the role looked up here is the message's OWN.
 *
 * That is what makes the two witnesses come out right, both BY THE ROLE:
 *
 *  - a reply you wrote in an incoming thread lives in Sent, an outgoing role, so it keeps reading
 *    "To: …" — which is what tells it apart from the incoming messages around it (#69);
 *  - your own message echoed back into the Inbox (a mailing list, a mail sent to yourself) lives in
 *    the Inbox, so it reads by its sender — exactly what the reader says when the line is tapped.
 *
 * [unified] is not a parameter and cannot be: unified is the state of having no folder selected,
 * and this rule never consults the selected folder. An unknown folder — an account whose folder
 * list has not synced, a folder with no role — resolves to a null role, which lands on the author
 * test: the pre-#115 answer, i.e. the behaviour degrades to what it was rather than to nonsense.
 */
internal fun showsRecipientsInThread(
    accountId: String?,
    mailboxId: String?,
    roles: Map<Pair<String, String>, String>,
    selfAuthored: Boolean,
): Boolean = showsRecipients(
    role = messageFolderRole(accountId, mailboxId, roles),
    unified = false,
    selfAuthored = selfAuthored,
)

/**
 * The role of the folder a message is actually filed in, or null when that cannot be said: no
 * account on the row, no folder on the row, or a folder the cache does not know a role for.
 * Account-qualified, because servers number folders per account (issue #31/#121) and a bare
 * folder id would borrow a sibling account's role.
 */
internal fun messageFolderRole(
    accountId: String?,
    mailboxId: String?,
    roles: Map<Pair<String, String>, String>,
): String? {
    if (accountId == null || mailboxId == null) return null
    return roles[accountId to mailboxId]
}
