package app.gridlink.core.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Cached email for a mailbox list view.
 *
 * The primary key is composite — `(accountId, id)` — because a JMAP email id is unique only
 * within its JMAP account (RFC 8620 §1.6.2). Hosting two accounts of one login (issue #31) can
 * therefore mint two rows with the same [id]; keying on [id] alone would make one account's sync
 * clobber the other's cached row. [accountId] is the client-side StoredAccount id, so sub-accounts
 * of the same login stay on separate partitions.
 */
@Entity(
    tableName = "emails",
    primaryKeys = ["accountId", "id"],
    indices = [Index("mailboxId")],
)
data class EmailEntity(
    val id: String,
    val accountId: String,
    val mailboxId: String,
    val threadId: String?,
    val subject: String?,
    val preview: String?,
    val receivedAt: String?,
    val fromName: String?,
    val fromEmail: String?,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
    /** Epoch millis derived from receivedAt, for ordering. */
    val sortKey: Long,
    /**
     * The message's `To:` recipients, JSON-encoded by [EmailRecipients] (added in schema v17).
     *
     * In Sent/Drafts a row shows who the mail went TO rather than its sender — your own name
     * teaches nothing (Codeberg #59). Those recipients used to live only in a process-lifetime
     * memo, so a row rendered from the cold cache fell back to the sender until the folder's
     * next refresh replaced it, which is what #63 saw blink. Persisted, the row is right from
     * the first frame, offline included.
     *
     * Null on rows cached before v17 and on messages with no recipient (a draft still being
     * written): both decode to an empty list, i.e. exactly the old fallback. There is no
     * backfill — the addresses aren't held locally, so old rows gain them at their next sync.
     */
    val recipientsJson: String? = null,
    /**
     * The message's CUSTOM keywords (tags), packed by [EmailKeywords] (added in schema v24).
     *
     * Not JSON despite the name it inherited from its neighbour — see [EmailKeywords] for why the
     * packing is space-wrapped instead: this column is queried, not just read back, because the
     * tag filter chip has to narrow in SQL before the window's `LIMIT`.
     *
     * 🔴 System keywords are not in here. `$seen`/`$flagged` are [seen] and [flagged]; this is
     * only the names the user (or another client on the same mailbox) invented. Null on rows
     * cached before v24 and on the great majority of mail, which carries no tags at all; both
     * decode to an empty list, so an un-migrated row simply shows no chips until its next sync.
     */
    val keywordsJson: String? = null,
)
