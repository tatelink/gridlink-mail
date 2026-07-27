package app.sterna.core.data.mail

import app.sterna.core.data.db.ConversationRow
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailFtsEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.data.db.FtsHit
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Collections

/**
 * The `$draft` keyword of cached list rows, kept in memory only — the `emails` table stores just
 * `seen`/`flagged`. Recorded whenever a live fetch passes through [toEntity], replayed by [toEmail]
 * so a trashed draft can still be flagged "(Draft)" in the list (#69). After process death a row
 * falls back to no flag until the folder's next refresh.
 *
 * The recipients were memoised the same way until schema v17; they are now a real column
 * ([EmailEntity.recipientsJson]), so they survive process death and a Sent/Drafts row carries its
 * "To: …" from the very first frame, offline included (#63).
 */
private const val RECENT_DRAFTS_MAX = 2000
private val recentDrafts: MutableSet<String> = Collections.synchronizedSet(
    Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                size > RECENT_DRAFTS_MAX
        },
    ),
)

/** Remember whether [accountId]'s email [id] is a draft, so [toEmail] can replay the `$draft`
 *  keyword. Keyed by (accountId, id): JMAP ids are unique only within their account (issue #31),
 *  so a bare id would flag a sibling account's message as a draft. */
internal fun recordDraft(accountId: String, id: String, isDraft: Boolean) {
    val key = draftKey(accountId, id)
    if (isDraft) recentDrafts.add(key) else recentDrafts.remove(key)
}

private fun draftKey(accountId: String, id: String) = "$accountId\u0000$id"

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
    // The draft flag still doesn't fit the row schema — remember it aside for [toEmail].
    recordDraft(accountId, id, isDraft)
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
        // Persisted since v17: every fetch path that caches a row asks the server for `to`, so
        // what lands here is the message's real recipient set — empty included (a draft with no
        // addressee yet), which is exactly what the row should then show.
        recipientsJson = EmailRecipients.encode(to),
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
    to = EmailRecipients.decode(recipientsJson),
    hasAttachment = hasAttachment,
    keywords = buildMap {
        if (seen) put("\$seen", true)
        if (flagged) put("\$flagged", true)
        if (draftKey(accountId, id) in recentDrafts) put("\$draft", true)
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
        if (draftKey(accountId, emailId) in recentDrafts) put("\$draft", true)
    },
)

private fun epochMillis(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull()
        ?.toEpochMilli() ?: 0L
}
