package app.gridlink.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Full-text search index over mail, SEPARATE from [EmailEntity] so it can outlive the display
 * cache's recent-window pruning and (from phase 2) cover the whole mailbox.
 *
 * The `unicode61` tokenizer with `remove_diacritics=1` folds accents, so "ecole" finds "École";
 * (`remove_diacritics=2` needs SQLite 3.27+/2019 and CRASHES older devices — Android 9 and below
 * ship an older SQLite that rejects it as "unknown tokenizer" while building the table; issue #71);
 * queries use per-token PREFIX matching ("eco*"), which is monotonic as the user types — exactly
 * what the server's stemmed full-text search cannot do. Only `subject`, `sender` and `body` are
 * tokenized/searchable; the rest are `notIndexed` (stored for ranking, filtering and rebuilding a
 * result row without touching the `emails` table). unicode61 folds the MATCH query too, so a query
 * with or without accents matches the same rows.
 */
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
    notIndexed = [
        "emailId", "accountId", "mailboxId", "threadId", "preview", "receivedAt",
        "fromName", "fromEmail", "seen", "flagged", "hasAttachment", "sortKey",
    ],
)
@Entity(tableName = "email_fts")
data class EmailFtsEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowid: Int = 0,
    val emailId: String,
    val accountId: String,
    val mailboxId: String,
    val threadId: String?,
    // Indexed (searchable) columns:
    val subject: String,
    /** Sender name + address, so a search can match either. */
    val sender: String,
    /** Truncated plain-text body (empty until phase 3 populates it). */
    val body: String,
    // notIndexed columns — carried so a hit renders without a join to `emails`:
    val preview: String?,
    val receivedAt: String?,
    val fromName: String?,
    val fromEmail: String?,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
    val sortKey: Long,
)
