package app.gridlink.core.data.mail

import app.gridlink.core.data.db.MailboxEntity
import app.gridlink.core.jmap.model.Mailbox

internal fun Mailbox.toEntity(accountId: String): MailboxEntity = MailboxEntity(
    accountId = accountId,
    id = id,
    name = name,
    role = role,
    parentId = parentId,
    sortOrder = sortOrder,
    totalEmails = totalEmails,
    unreadEmails = unreadEmails,
)

internal fun MailboxEntity.toMailbox(): Mailbox = Mailbox(
    id = id,
    name = name,
    role = role,
    parentId = parentId,
    sortOrder = sortOrder,
    totalEmails = totalEmails,
    unreadEmails = unreadEmails,
    // Default the badge to the stored server counter (the IMAP path); the repository
    // overrides it with the live local count for JMAP accounts.
    unreadForList = unreadEmails,
)
