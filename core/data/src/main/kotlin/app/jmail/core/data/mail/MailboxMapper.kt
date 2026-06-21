package app.jmail.core.data.mail

import app.jmail.core.data.db.MailboxEntity
import app.jmail.core.jmap.model.Mailbox

internal fun Mailbox.toEntity(): MailboxEntity = MailboxEntity(
    id = id,
    name = name,
    role = role,
    sortOrder = sortOrder,
    totalEmails = totalEmails,
    unreadEmails = unreadEmails,
)

internal fun MailboxEntity.toMailbox(): Mailbox = Mailbox(
    id = id,
    name = name,
    role = role,
    sortOrder = sortOrder,
    totalEmails = totalEmails,
    unreadEmails = unreadEmails,
)
