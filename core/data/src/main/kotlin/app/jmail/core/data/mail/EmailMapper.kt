package app.jmail.core.data.mail

import app.jmail.core.data.db.EmailEntity
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.EmailAddress
import java.time.Instant
import java.time.OffsetDateTime

internal fun Email.toEntity(accountId: String, mailboxId: String): EmailEntity {
    val sender = from.firstOrNull()
    return EmailEntity(
        id = id,
        accountId = accountId,
        mailboxId = mailboxId,
        threadId = threadId,
        subject = subject,
        preview = preview,
        receivedAt = receivedAt,
        fromName = sender?.name,
        fromEmail = sender?.email,
        seen = isSeen,
        hasAttachment = hasAttachment,
        sortKey = epochMillis(receivedAt),
    )
}

internal fun EmailEntity.toEmail(): Email = Email(
    id = id,
    threadId = threadId,
    subject = subject,
    preview = preview,
    receivedAt = receivedAt,
    from = if (fromEmail != null || fromName != null) {
        listOf(EmailAddress(name = fromName, email = fromEmail.orEmpty()))
    } else {
        emptyList()
    },
    hasAttachment = hasAttachment,
    keywords = if (seen) mapOf("\$seen" to true) else emptyMap(),
)

private fun epochMillis(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull()
        ?.toEpochMilli() ?: 0L
}
