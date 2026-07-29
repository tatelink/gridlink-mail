package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Lifecycle of an outgoing message held in the persistent outbox. */
enum class OutboxState {
    /** Held back for the undo window (or a scheduled time); not yet eligible to send. */
    HELD,

    /** Ready to send as soon as the worker runs and the network is up. */
    QUEUED,

    /** A send attempt is in flight. */
    SENDING,

    /** Auto-retry gave up; the item stays for manual retry/edit/delete. */
    FAILED,

    /**
     * Reopened in the composer for editing (#70): held back from the send worker while it is open,
     * so it is neither delivered nor lost. Closing the composer returns it to [QUEUED]; sending,
     * saving or deleting consumes the row. A process death mid-edit strands it here, so startup
     * reverts any [EDITING] row to [QUEUED] — the edit is gone, the message is not.
     */
    EDITING,
}

/**
 * A message persisted in the outbox: the single reliable downstream send path. A row
 * survives the app being killed and is delivered by [app.sterna.send] WorkManager job,
 * with automatic retry. Unlike the disposable cache, this is user data (unsent mail),
 * so its table is migrated additively rather than rebuilt.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val recipients: String, // comma-separated
    val cc: String? = null, // comma-separated
    val bcc: String? = null, // comma-separated
    val subject: String,
    val textBody: String,
    val htmlBody: String? = null,
    val fromName: String? = null,
    val fromEmail: String? = null,
    val inReplyTo: String? = null, // space-separated message-ids
    val references: String? = null, // space-separated message-ids
    /** Durable attachment descriptors; see [OutboxAttachments]. */
    val attachmentsJson: String = "[]",
    val createdAtMillis: Long,
    /** Don't send before this instant: serves both the undo window and a scheduled time. */
    val notBeforeMillis: Long,
    val state: OutboxState = OutboxState.QUEUED,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptMillis: Long? = null,
    /** OpenPGP mode ("SIGN"/"ENCRYPT") when the payload is PGP/MIME; null otherwise. */
    val pgpMode: String? = null,
    /**
     * File holding the pre-built PGP/MIME entity (ciphertext or signed — safe at
     * rest). For ENCRYPT items textBody/htmlBody/attachmentsJson are emptied: the
     * outbox never stores plaintext of an encrypted message.
     */
    val pgpEntityPath: String? = null,
    /**
     * Server id of the saved draft this message was edited from (#63). Destroyed once the
     * send succeeds, so the sent mail doesn't leave a stale duplicate in Drafts; a failed
     * send leaves the draft untouched.
     */
    val draftEmailId: String? = null,
)
