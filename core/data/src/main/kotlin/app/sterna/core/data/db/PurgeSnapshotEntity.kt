package app.sterna.core.data.db

import androidx.room.Entity

/**
 * One message an "Empty trash" is allowed to destroy.
 *
 * The set is frozen when the user CONFIRMS, not when the held-back destroy finally runs
 * (Codeberg #99): the purge used to be described by a folder id and re-read the folder at
 * execution time, so anything moved to Trash during the undo window was destroyed with the
 * rest — mail the user had never designated, gone server-side and unrecoverable.
 *
 * Why the database and not the worker's input [androidx.work.Data]: WorkManager caps a
 * request's input at 10 KiB and throws past it, which a snapshot of up to
 * `PURGE_SNAPSHOT_MAX` ids blows through by two orders of magnitude. Splitting one purge
 * across many requests would also split its undo (cancelling would have to catch every
 * chunk) and its identity. A table gives the snapshot the same lifetime as the WorkManager
 * job it belongs to (both survive process death), the same account scoping as every other
 * row here, and no new on-disk format to invent. It also fails in the safe direction: if
 * the cache is ever rebuilt destructively the snapshot vanishes with it, and the purge
 * destroys NOTHING rather than falling back to "whatever is in the folder now".
 *
 * Keyed `(purgeId, accountId, emailId)`. [purgeId] scopes a wave of destroys to the exact
 * confirmation it came from — a second Empty trash on the same folder cannot feed ids to a
 * purge already running. [accountId] is part of the key, and of every read, because email
 * ids are unique only within their account (issue #31): a bare id could otherwise drag a
 * sibling account's message into another account's purge.
 */
@Entity(tableName = "purge_snapshot", primaryKeys = ["purgeId", "accountId", "emailId"])
data class PurgeSnapshotEntity(
    val purgeId: String,
    val accountId: String,
    /** The Trash folder the snapshot was taken from — lets an undo erase it by folder. */
    val mailboxId: String,
    val emailId: String,
    /** Confirmation time, so an abandoned snapshot can be swept instead of lingering forever. */
    val createdAt: Long,
)
