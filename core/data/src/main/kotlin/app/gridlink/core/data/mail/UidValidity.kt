package app.gridlink.core.data.mail

import app.gridlink.core.data.db.EmailBodyDao
import app.gridlink.core.data.db.MailboxUidValidityDao
import app.gridlink.core.data.db.MailboxUidValidityEntity
import app.gridlink.core.data.db.PurgeSnapshotDao

/**
 * What an IMAP folder's UIDVALIDITY licenses (RFC 3501 §2.3.1.1, Codeberg #99).
 *
 * A UID identifies a message only INSIDE one numbering. When a server renumbers a folder — a
 * migration, a lost index, a restore from backup — it bumps the folder's UIDVALIDITY and every
 * UID we hold for it stops meaning what it meant: usually it means nothing, but after the
 * shifting kind of renumbering it means ANOTHER MESSAGE. That is the only case that matters
 * here, and it is the one that makes a stale id dangerous rather than merely useless.
 *
 * Pure and separate because the consequence — destroy, or refuse to — cannot be observed in an
 * integration test without a server willing to renumber a folder on demand.
 */
object UidValidity {

    /** How a recorded value compares to what the server now reports. */
    enum class Verdict {
        /** Nothing to compare: the server reported no UIDVALIDITY, or none was ever recorded
         *  as a positive number. Proceed — this is what every call did before the check existed. */
        UNVERIFIABLE,

        /** First time this folder is seen. Record and proceed. */
        FIRST_SIGHT,

        /** Same numbering: every cached UID still means what it meant. */
        SAME,

        /** Renumbered. Nothing keyed by UID may be acted on until the caches are dropped. */
        CHANGED,
    }

    fun verdict(recorded: Long?, observed: Long): Verdict = when {
        observed <= 0L -> Verdict.UNVERIFIABLE
        recorded == null -> Verdict.FIRST_SIGHT
        recorded <= 0L -> Verdict.UNVERIFIABLE
        recorded == observed -> Verdict.SAME
        else -> Verdict.CHANGED
    }

    /**
     * Whether a snapshot recorded with [snapshotUidValidity] may be destroyed at all.
     *
     * `false` for a snapshot that carries no numbering: it was written by a version that did not
     * record one, or was built from the cache with no numbering ever observed for that folder.
     * Either way the ids in it cannot be shown to still mean what they meant, and an unverifiable
     * destroy list destroys NOTHING — the same call `MessageDestroyWorker` already makes for a
     * purge enqueued before snapshots existed.
     *
     * Only asked on IMAP: a JMAP id survives everything a server can do to a mailbox, so there
     * is nothing to verify and nothing to record.
     */
    fun mayDestroy(snapshotUidValidity: Long?): Boolean = snapshotUidValidity != null && snapshotUidValidity > 0L

    /**
     * The `LIKE` pattern matching every cached id of one IMAP folder, with SQL's own wildcards
     * escaped: a folder called `a_b` would otherwise match `axb` too and take a neighbour's
     * cached bodies with it. Backslash first, or it would escape the escapes.
     */
    fun bodyCacheIdPrefix(accountId: String, mailboxId: String): String =
        (ImapMailService.emailId(accountId, mailboxId, 0L).dropLast(1))
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"
}

/**
 * Where the per-folder UIDVALIDITY lives, seen from [ImapMailService] — which has a connection
 * and no database.
 *
 * [None] is the do-nothing implementation: it never remembers anything, so [recorded] always
 * answers null and every SELECT goes out unverified, exactly as before the check existed. It is
 * the default so that a test (or any caller building the service by hand) is not forced to bring
 * a database along.
 */
interface UidValidityStore {

    /** What was last observed for this folder, or null if it was never observed. */
    suspend fun recorded(accountId: String, mailboxId: String): Long?

    /**
     * Every folder's last sync point for one account, keyed by mailbox id. An absent key means
     * "nothing to compare against", which is what [None] always answers for every folder — so a
     * service built without a database does the full re-read every time, exactly as it did before
     * CONDSTORE existed here.
     *
     * Whole-account because the caller must have the answer in hand BEFORE it opens the session
     * that will tell it which folder it is syncing.
     */
    suspend fun syncPoints(accountId: String): Map<String, ImapSyncPoint> = emptyMap()

    /**
     * Remember what a sync that SUCCEEDED saw, under the numbering it saw it (RFC 7162).
     *
     * Written after the work, never before: a watermark stored ahead of a fetch that then fails
     * would make the next refresh skip the folder, and the mail it skipped would never arrive.
     */
    suspend fun recordSyncPoint(
        accountId: String,
        mailboxId: String,
        uidValidity: Long,
        highestModSeq: Long,
        uidNext: Long,
        messageCount: Int,
    ) = Unit

    /** Remember [uidValidity] as this folder's numbering. */
    suspend fun record(accountId: String, mailboxId: String, uidValidity: Long)

    /**
     * The folder was renumbered: drop what is keyed by UID and cannot heal itself, record the new
     * numbering (so the next call is not refused for the same reason), and say so.
     */
    suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long)

    /**
     * Called with `(accountId, mailboxId)` when a folder is invalidated, for the one consumer
     * outside this layer: the notification baseline, which lives in `:app`. Cleared, it makes
     * the next pass SEED that folder silently instead of announcing its whole content as new —
     * a renumbering changes every id in it, so a diff against the old baseline would notify the
     * user about mail they have already read.
     *
     * A callback rather than a flow, deliberately: `MailRepository.onAccountPruned` already does
     * exactly this for the same reason, there is no long-lived collector in the app to subscribe
     * one, and a flow nobody collects is unfinished work that reads as finished.
     */
    var onRenumbered: ((String, String) -> Unit)?

    object None : UidValidityStore {
        override suspend fun recorded(accountId: String, mailboxId: String): Long? = null
        override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) = Unit
        override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) = Unit
        override var onRenumbered: ((String, String) -> Unit)? = null
    }
}

/**
 * The database-backed store, and the one place that says what a renumbering INVALIDATES.
 *
 * What it drops, and only this:
 * - the cached bodies of that folder, because nothing else ever prunes them by folder and a body
 *   read back under a recycled UID renders the wrong message under the right header;
 * - every pending destroy list of that folder, because a frozen list of ids that no longer mean
 *   anything is not an order to be carried out — dropping it is "destroy nothing", which is the
 *   whole point.
 *
 * What it deliberately does NOT do: re-read the folder to catch up. That is bug #99 itself. Flags
 * and moves are left alone too — the list cache is replaced from the server on the next refresh,
 * so those heal on their own.
 *
 * The notification baseline lives in `:app` and this layer does not reach into it: it is told,
 * through [onRenumbered], and does its own clearing.
 */
class MailboxUidValidityStore(
    private val dao: MailboxUidValidityDao,
    private val bodies: EmailBodyDao,
    private val purgeSnapshots: PurgeSnapshotDao,
) : UidValidityStore {

    override var onRenumbered: ((String, String) -> Unit)? = null

    override suspend fun recorded(accountId: String, mailboxId: String): Long? =
        dao.recorded(accountId, mailboxId)

    override suspend fun syncPoints(accountId: String): Map<String, ImapSyncPoint> =
        dao.rowsForAccount(accountId).associate { row ->
            row.mailboxId to ImapSyncPoint(row.uidValidity, row.highestModSeq, row.uidNext, row.messageCount)
        }

    /**
     * 🔴 The UIDVALIDITY is passed on to the UPDATE's own `WHERE`, not checked here: a folder
     * renumbered between the SELECT that produced these numbers and this write matches no row and
     * writes nothing, leaving the cursor null and costing one full re-read. A check in Kotlin
     * would be a second copy of the rule that could drift from the one that decides.
     */
    override suspend fun recordSyncPoint(
        accountId: String,
        mailboxId: String,
        uidValidity: Long,
        highestModSeq: Long,
        uidNext: Long,
        messageCount: Int,
    ) {
        if (uidValidity <= 0L || highestModSeq <= 0L) return // nothing worth comparing later
        dao.recordSyncPoint(accountId, mailboxId, uidValidity, highestModSeq, uidNext, messageCount)
    }

    override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) {
        if (uidValidity <= 0L) return // the server reported none: nothing to remember
        dao.record(MailboxUidValidityEntity(accountId, mailboxId, uidValidity))
    }

    override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) {
        bodies.deleteForIdPrefix(accountId, UidValidity.bodyCacheIdPrefix(accountId, mailboxId))
        purgeSnapshots.deleteForMailbox(accountId, mailboxId)
        record(accountId, mailboxId, uidValidity)
        // Best effort, and last: the app-layer hook must never cost the invalidation itself.
        onRenumbered?.let { hook -> runCatching { hook(accountId, mailboxId) } }
    }
}
