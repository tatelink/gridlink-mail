package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.jmap.model.Mailbox

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
)
