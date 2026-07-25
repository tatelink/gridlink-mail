package app.sterna.core.data.mail

import app.sterna.core.data.db.ConversationRow
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailFtsEntity
import app.sterna.core.data.db.FtsHit
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Collections

/**
 * Recipients of cached list rows, kept in memory only: the `emails` table predates them
 * and a column would mean a schema bump. Recorded whenever a live fetch passes through
 * [toEntity], replayed by [toEmail] so Sent/Drafts rows can show "To: …" (Codeberg #59).
 * After process death a row falls back to its sender until the folder's next refresh.
 */
private const val RECIPIENTS_MAX = 2000
private val recentRecipients: MutableMap<String, List<EmailAddress>> = Collections.synchronizedMap(
    object : LinkedHashMap<String, List<EmailAddress>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EmailAddress>>): Boolean =
            size > RECIPIENTS_MAX
    },
)

/** Record [to] as the remembered recipients of email [id]; a no-op when empty (never
 *  erase an earlier record — some fetch paths hand over an Email without recipients). */
internal fun recordRecipients(id: String, to: List<EmailAddress>) {
    if (to.isNotEmpty()) recentRecipients[id] = to
}

/**
 * The `$draft` keyword of cached list rows, kept in memory only — the `emails` table stores just
 * `seen`/`flagged`, so a column would mean a schema bump. Recorded whenever a live fetch passes
 * through [toEntity], replayed by [toEmail] so a trashed draft can still be flagged "(Draft)" in
 * the list (#69). After process death a row falls back to no flag until the folder's next refresh —
 * the same lifecycle as the remembered recipients above.
 */
private val recentDrafts: MutableSet<String> = Collections.synchronizedSet(
    Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                size > RECIPIENTS_MAX
        },
    ),
)

/** Remember whether email [id] is a draft, so [toEmail] can replay the `$draft` keyword. */
internal fun recordDraft(id: String, isDraft: Boolean) {
    if (isDraft) recentDrafts.add(id) else recentDrafts.remove(id)
}

/** Map a grouped conversation row to the domain [InboxRow] (unread = any in thread). */
internal fun ConversationRow.toInboxRow(): InboxRow =
    InboxRow(
        email = email.toEmail(),
        threadCount = threadCount,
        unread = threadUnread == 0,
        threadExpandable = threadTotal > 1,
    )

internal fun Email.toEntity(accountId: String, mailboxId: String): EmailEntity {
    val sender = from.firstOrNull()
    // Recipients and the draft flag don't fit the row schema — remember them aside for [toEmail].
    recordRecipients(id, to)
    recordDraft(id, isDraft)
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
        flagged = isFlagged,
        hasAttachment = hasAttachment,
        sortKey = epochMillis(receivedAt),
    )
}

internal fun EmailEntity.toEmail(): Email = Email(
    id = id,
    accountId = accountId,
    mailboxId = mailboxId,
    threadId = threadId,
    subject = subject,
    preview = preview,
    receivedAt = receivedAt,
    from = if (fromEmail != null || fromName != null) {
        listOf(EmailAddress(name = fromName, email = fromEmail.orEmpty()))
    } else {
        emptyList()
    },
    to = recentRecipients[id].orEmpty(),
    hasAttachment = hasAttachment,
    keywords = buildMap {
        if (seen) put("\$seen", true)
        if (flagged) put("\$flagged", true)
        if (id in recentDrafts) put("\$draft", true)
    },
)

/**
 * A crawled/​cached [Email] → a search-index row. Headers only: body search is served live by the
 * server's own full-text index (unioned into the results), not re-indexed client-side.
 */
internal fun Email.toFts(accountId: String): EmailFtsEntity {
    val sender = from.firstOrNull()
    return EmailFtsEntity(
        emailId = id,
        accountId = accountId,
        mailboxId = mailboxIds.keys.firstOrNull() ?: mailboxId.orEmpty(),
        threadId = threadId,
        subject = subject.orEmpty(),
        sender = listOfNotNull(sender?.name, sender?.email).joinToString(" ").trim(),
        body = "",
        preview = preview,
        receivedAt = receivedAt,
        fromName = sender?.name,
        fromEmail = sender?.email,
        seen = isSeen,
        flagged = isFlagged,
        hasAttachment = hasAttachment,
        sortKey = epochMillis(receivedAt),
    )
}

/** A full-text search hit → a list-renderable [Email] (self-contained; no join to `emails`). */
internal fun FtsHit.toEmail(): Email = Email(
    id = emailId,
    accountId = accountId,
    mailboxId = mailboxId,
    threadId = threadId,
    subject = subject.ifBlank { null },
    preview = preview,
    receivedAt = receivedAt,
    from = if (fromEmail != null || fromName != null) {
        listOf(EmailAddress(name = fromName, email = fromEmail.orEmpty()))
    } else {
        emptyList()
    },
    hasAttachment = hasAttachment,
    keywords = buildMap {
        if (seen) put("\$seen", true)
        if (flagged) put("\$flagged", true)
        if (emailId in recentDrafts) put("\$draft", true)
    },
)

private fun epochMillis(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull()
        ?.toEpochMilli() ?: 0L
}
