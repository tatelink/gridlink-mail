package app.gridlink.core.data.mail

import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.core.data.db.EmailDao
import app.gridlink.core.data.db.EmailEntity
import app.gridlink.core.data.db.MailboxDao

/**
 * One message as the home-screen widget draws it.
 *
 * A flat, already-resolved value rather than an [EmailEntity]: the widget lives in
 * `:app` and Room stays inside `:core:data` (see `DataFactory`), and everything a widget row
 * needs to render and to be tapped travels in one object. The three ids are what a tap hands
 * back to `MainActivity`, so the message opens in its own account and its own folder.
 */
data class WidgetMessage(
    val emailId: String,
    val accountId: String,
    val mailboxId: String,
    /** Display name if the sender gave one, else their address, else empty. Never invented. */
    val sender: String,
    /** Empty when the message carries no subject. The widget, not this reader, names that case. */
    val subject: String,
    val preview: String,
    val receivedAtMillis: Long,
    val unread: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
)

/**
 * What one refresh of the widget knows.
 *
 * 🔴 [unreadCount] is nullable and null means "never synced", not zero. A widget that prints a
 * confident 0 on a mailbox it has never read is lying in the one place the user glances at
 * without opening anything; [SIGNED_OUT] and an un-synced account both have to say so instead.
 */
data class WidgetInboxSnapshot(
    val signedIn: Boolean,
    /** The account's own name, or the username it signs in with. Empty when signed out. */
    val accountLabel: String,
    val mailboxName: String,
    val unreadCount: Int?,
    val messages: List<WidgetMessage>,
) {
    companion object {
        /** No account on the device: the widget invites a sign-in rather than showing an empty inbox. */
        val SIGNED_OUT = WidgetInboxSnapshot(
            signedIn = false,
            accountLabel = "",
            mailboxName = "",
            unreadCount = null,
            messages = emptyList(),
        )
    }
}

/**
 * Reads the cached inbox for the home-screen widget. Cache only — never the network.
 *
 * A widget is drawn by the launcher at moments the user did not choose: on boot, on a wallpaper
 * change, while the phone is offline, before the app has run once this session. So every read
 * here is a local one, and an empty or absent cache is a legitimate answer that the widget
 * renders honestly. Sync is somebody else's job; this only reports what sync has already stored.
 */
class WidgetInboxReader(
    private val emailDao: EmailDao,
    private val mailboxDao: MailboxDao,
    private val accountStore: AccountStore,
) {
    /**
     * The currently selected account's inbox, newest [limit] messages first.
     *
     * The folder is resolved by ROLE first and only then from the account's own remembered
     * [app.gridlink.core.data.account.StoredAccount.inboxId]: the role comes from the folder rows
     * sync just wrote, while the remembered id is a copy that a server-side folder change can
     * leave pointing at nothing. Falling back to it still beats showing an empty widget on an
     * account whose folders have not been cached yet.
     */
    suspend fun snapshot(limit: Int = DEFAULT_LIMIT): WidgetInboxSnapshot {
        val account = accountStore.currentAccount() ?: return WidgetInboxSnapshot.SIGNED_OUT
        val mailboxId = mailboxDao.idForRole(account.id, INBOX_ROLE) ?: account.inboxId
        if (mailboxId == null) {
            // Signed in, folders never synced. The header can still name the account, and the
            // unread count says "unknown" rather than zero.
            return WidgetInboxSnapshot(
                signedIn = true,
                accountLabel = account.label(),
                mailboxName = account.inboxName,
                unreadCount = null,
                messages = emptyList(),
            )
        }
        val summary = mailboxDao.widgetSummary(account.id, mailboxId)
        val rows = emailDao.recentForWidget(account.id, mailboxId, limit.coerceAtLeast(1))
        return WidgetInboxSnapshot(
            signedIn = true,
            accountLabel = account.label(),
            mailboxName = summary?.name?.takeIf { it.isNotBlank() } ?: account.inboxName,
            unreadCount = widgetUnreadCount(
                isImap = account.protocol == MailProtocol.IMAP,
                folderCounter = summary?.unreadEmails,
                storedInboxUnread = account.unread.takeIf { account.inboxId == mailboxId },
                cachedUnread = { emailDao.unreadCountForWidget(account.id, mailboxId) },
            ),
            messages = rows.map { it.toWidgetMessage() },
        )
    }

    private companion object {
        const val INBOX_ROLE = "inbox"

        /**
         * Rows fetched per refresh. The tallest widget a launcher will grant is a handful of
         * rows, and the list scrolls within whatever it gets, so this is generous rather than
         * exact; a bigger number only costs a longer local query on a table that is already
         * indexed for this ordering.
         */
        const val DEFAULT_LIMIT = 25
    }
}

/**
 * The widget's unread number for one account's inbox, or null for "not synced yet".
 *
 * JMAP: the folder row's counter. Sync writes it from the server and every read/unread toggle
 * nudges it, so it is the freshest number there is.
 *
 * 🔴 IMAP: never the folder row. IMAP folder rows are written with a hard 0 (see
 * `ImapMailService.loadFolder` and `MailRepository.adjustFolderUnreadOnRead`: filling them would
 * cost a STATUS per folder), so reading it put "0" on the widget of every IMAP account however much
 * unread mail the inbox list was showing. The IMAP inbox count lives on the account instead
 * ([storedInboxUnread], the server's SEARCH UNSEEN from the last foreground refresh, which is also
 * what the app's "All inboxes" header and upstream Sterna's widget use). Background passes (IDLE,
 * the fallback worker) add rows without moving that number, so the cached unread rows are a floor
 * under it: a message the list shows as unread is always counted.
 */
internal suspend fun widgetUnreadCount(
    isImap: Boolean,
    folderCounter: Int?,
    storedInboxUnread: Int?,
    cachedUnread: suspend () -> Int,
): Int? {
    // No folder row yet means the folders were never cached: unknown on both protocols.
    if (folderCounter == null) return null
    if (!isImap) return folderCounter
    return maxOf(storedInboxUnread ?: 0, cachedUnread())
}

/**
 * Flatten a cached row into what the widget draws.
 *
 * The sender falls back name → address → empty, and empty stays empty: the widget decides what
 * an anonymous message is called, in the user's language, and a reader that guessed "Unknown"
 * here would put an English word on a German phone.
 */
internal fun EmailEntity.toWidgetMessage(): WidgetMessage = WidgetMessage(
    emailId = id,
    accountId = accountId,
    mailboxId = mailboxId,
    sender = fromName?.takeIf { it.isNotBlank() } ?: fromEmail.orEmpty(),
    subject = subject.orEmpty(),
    preview = preview.orEmpty(),
    receivedAtMillis = sortKey,
    unread = !seen,
    flagged = flagged,
    hasAttachment = hasAttachment,
)
