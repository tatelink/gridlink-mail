package app.gridlink.core.data.mail

import app.gridlink.core.data.db.MailboxEntity
import app.gridlink.core.jmap.model.Mailbox
import app.gridlink.core.jmap.model.MailboxRights

internal fun Mailbox.toEntity(accountId: String): MailboxEntity = MailboxEntity(
    accountId = accountId,
    id = id,
    name = name,
    role = role,
    parentId = parentId,
    sortOrder = sortOrder,
    totalEmails = totalEmails,
    unreadEmails = unreadEmails,
    // Flattened into two nullable columns rather than stored as an object: two of the nine rights
    // are all the tree acts on, and a JSON blob in a column would be a second schema to migrate the
    // next time one of the other seven turns out to matter.
    mayRename = myRights?.mayRename,
    mayDelete = myRights?.mayDelete,
)

internal fun MailboxEntity.toMailbox(): Mailbox = Mailbox(
    id = id,
    name = name,
    role = role,
    parentId = parentId,
    sortOrder = sortOrder,
    totalEmails = totalEmails,
    unreadEmails = unreadEmails,
    // 🔴 Null when BOTH columns are null, not an object of two nulls. The difference is what the
    // folder tree reads: a missing `myRights` is "this row predates the columns, or came from IMAP,
    // so fall back to the local rule", and rebuilding it as an empty object says the same thing in a
    // shape that invites a `?.mayRename == false` check to start being written as `!= true`.
    myRights = if (mayRename == null && mayDelete == null) {
        null
    } else {
        MailboxRights(mayRename = mayRename, mayDelete = mayDelete)
    },
    // Default the badge to the stored server counter (the IMAP path); the repository
    // overrides it with the live local count for JMAP accounts.
    unreadForList = unreadEmails,
)
