package app.sterna.core.data.storage

import android.content.Context
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.SnoozedDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** On-device storage used by the cache, for the Storage & Sync settings screen. */
data class StorageUsage(
    val databaseBytes: Long,
    val attachmentBytes: Long,
    val perAccount: List<AccountUsage>,
) {
    val totalBytes: Long get() = databaseBytes + attachmentBytes
}

/** Cached-message count attributed to one account. */
data class AccountUsage(val accountId: String, val messageCount: Int)

/**
 * Reports and manages the on-device cache (Room DB + downloaded attachments).
 * This is device usage only — distinct from the server mailbox quota
 * (ARCHITECTURE.md → "Storage & data strategy"). Attachments are a re-downloadable
 * cache, so clearing them is always safe.
 */
class StorageRepository(
    private val context: Context,
    private val emailDao: EmailDao,
    private val emailFtsDao: EmailFtsDao,
    private val emailBodyDao: EmailBodyDao,
    private val mailboxDao: MailboxDao,
    private val snoozedDao: SnoozedDao,
    private val purgeSnapshotDao: PurgeSnapshotDao,
    private val mailboxUidValidityDao: MailboxUidValidityDao,
) {
    private val attachmentsDir: File get() = File(context.cacheDir, "attachments")

    /**
     * Where compose stages outgoing-attachment bytes (a picked file, a carried forward part, a
     * reopened outbox item, #70). enqueueSend copies these into the durable per-item outbox dir, so
     * once a message is queued the staged copies are orphaned cache — never read again, but not
     * cleaned up before (StorageRepository only swept `attachments`), so they piled up. Counted and
     * cleared here alongside the attachment cache.
     */
    private val outgoingDir: File get() = File(context.cacheDir, "outgoing")

    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val dbBytes = DB_FILES.sumOf { context.getDatabasePath(it).safeLength() }
        val attachmentBytes = (attachmentsDir.listFiles()?.sumOf { it.safeLength() } ?: 0L) +
            (outgoingDir.listFiles()?.sumOf { it.safeLength() } ?: 0L)
        val perAccount = emailDao.countsByAccount()
            .map { AccountUsage(it.accountId, it.messageCount) }
        StorageUsage(dbBytes, attachmentBytes, perAccount)
    }

    /** Purge every cached message + mailbox + attachment, keeping accounts/settings. */
    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        emailDao.deleteAll()
        emailFtsDao.clearAll()
        emailBodyDao.deleteAll()
        mailboxDao.deleteAll()
        // The recorded IMAP numbering describes the cache that has just gone (#99): keeping it
        // would leave "clear cache" incomplete, and the first sync records it again anyway.
        mailboxUidValidityDao.deleteAll()
        clearAttachments()
    }

    /** Cached-message count for one account. */
    suspend fun accountMessageCount(accountId: String): Int = withContext(Dispatchers.IO) {
        emailDao.countForAccount(accountId)
    }

    /** Purge one account's cached messages. Snoozes are user intent, not cache — kept. */
    suspend fun clearAccountCache(accountId: String) = withContext(Dispatchers.IO) {
        emailDao.deleteForAccount(accountId)
        emailFtsDao.clearAccount(accountId)
        emailBodyDao.deleteForAccount(accountId)
        mailboxDao.deleteForAccount(accountId)
        mailboxUidValidityDao.deleteForAccount(accountId)
    }

    /**
     * On sign-out: purge the account's cached rows and the attachment cache.
     * Attachments aren't namespaced per account, so the whole (re-downloadable)
     * cache is cleared to avoid leaking one account's files to another.
     */
    suspend fun purgeAccount(accountId: String) = withContext(Dispatchers.IO) {
        emailDao.deleteForAccount(accountId)
        emailFtsDao.clearAccount(accountId)
        emailBodyDao.deleteForAccount(accountId)
        mailboxDao.deleteForAccount(accountId)
        snoozedDao.deleteForAccount(accountId)
        // A pending Empty-trash destroy list belongs to the account that is going away (#99).
        purgeSnapshotDao.deleteForAccount(accountId)
        mailboxUidValidityDao.deleteForAccount(accountId)
        clearAttachments()
    }

    /**
     * Sweep the cached mail of accounts that no longer exist, given the accounts that DO
     * ([knownAccountIds] — `AccountStore.accounts()`' ids). Returns the account ids swept.
     *
     * Codeberg #121: a row whose account is gone is not merely mislabelled, it is unreachable —
     * no credential syncs it, no action can be routed to it, no prune covers it. It stayed in the
     * unified list forever, in whatever state it was frozen in (bold, starred), above the real
     * message. Scoping the list on (account, folder) pairs hides it; this removes it.
     *
     * ⛔ An empty [knownAccountIds] sweeps NOTHING — see [OrphanedAccountCache.orphans]. The
     * decision is that function's, not a `NOT IN` statement's, precisely so this call can never
     * degrade into "delete the whole cache".
     *
     * Orphans are found through the `emails` table ([EmailDao.countsByAccount]) and removed with
     * the same per-account deletes sign-out uses. Deliberately NOT swept:
     *  - `snoozed` — user intent rather than cache, and per-account snoozes of a gone account are
     *    inert (the not-snoozed filter correlates on accountId);
     *  - `purge_snapshot`, `mailbox_uid_validity`, the outbox and the attachment files — none of
     *    them can produce a row in a list, and the attachment cache is shared, so clearing it here
     *    would cost every account a re-download on a housekeeping pass.
     * A body or index row of an account with no `emails` row left is likewise not looked for: the
     * per-account deletes below take those tables too, so the pair only survives together.
     */
    suspend fun purgeOrphanedAccounts(knownAccountIds: Collection<String>): List<String> =
        withContext(Dispatchers.IO) {
            val cached = emailDao.countsByAccount().map { it.accountId }
            val orphans = OrphanedAccountCache.orphans(knownAccountIds, cached)
            orphans.forEach { accountId ->
                // One runCatching PER table: a failure in one must not leave the rest behind.
                runCatching { emailDao.deleteForAccount(accountId) }
                runCatching { emailFtsDao.clearAccount(accountId) }
                runCatching { emailBodyDao.deleteForAccount(accountId) }
                runCatching { mailboxDao.deleteForAccount(accountId) }
            }
            orphans
        }

    /** Write a downloaded attachment to the cache, then enforce the size/age cap. */
    suspend fun cacheAttachment(name: String?, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val dir = attachmentsDir.apply { mkdirs() }
        val safeName = (name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, safeName).apply { writeBytes(bytes) }
        enforceAttachmentCap()
        file
    }

    /** LRU eviction: drop files past the age cap, then oldest-first past the size cap. */
    private fun enforceAttachmentCap() {
        // Bound the outgoing staging dir too (#70): orphaned staged copies of already-queued sends
        // past the age cap are dead cache. The cap is far longer than any compose stays open, so an
        // in-progress attachment is never pruned from under the composer.
        val now0 = System.currentTimeMillis()
        outgoingDir.listFiles()?.forEach { if (now0 - it.lastModified() > MAX_AGE_MS) it.delete() }
        val files = attachmentsDir.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        val survivors = files.filter { file ->
            val tooOld = now - file.lastModified() > MAX_AGE_MS
            if (tooOld) file.delete()
            !tooOld
        }
        var total = survivors.sumOf { it.safeLength() }
        if (total <= MAX_ATTACHMENT_BYTES) return
        for (file in survivors.sortedBy { it.lastModified() }) {
            if (total <= MAX_ATTACHMENT_BYTES) break
            val size = file.safeLength()
            if (file.delete()) total -= size
        }
    }

    private fun clearAttachments() {
        attachmentsDir.listFiles()?.forEach { it.delete() }
        // The outgoing staging dir is re-downloadable/re-stageable cache too (#70): a queued send
        // already holds its own durable copy, so clearing the orphaned staged files loses nothing.
        outgoingDir.listFiles()?.forEach { it.delete() }
    }

    private fun File.safeLength(): Long = if (exists()) length() else 0L

    private companion object {
        val DB_FILES = listOf("sterna.db", "sterna.db-wal", "sterna.db-shm")
        const val MAX_ATTACHMENT_BYTES = 200L * 1024 * 1024 // 200 MB
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }
}
