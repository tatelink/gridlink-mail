package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapIdleConnection
import app.sterna.core.imap.ImapMessage
import app.sterna.core.imap.ImapSearchCriteria
import app.sterna.core.imap.ImapSession
import app.sterna.core.imap.MailSecurity
import app.sterna.core.imap.MailServerConfig
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.imap.SmtpClient
import app.sterna.core.imap.buildImapSearch
import app.sterna.core.imap.searchFolders
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A folder's mailboxes + a fetched page, ready for the cache. */
data class ImapFolderLoad(
    val mailboxes: List<MailboxEntity>,
    val targetMailboxId: String,
    val targetName: String,
    val unread: Int,
    val accountName: String,
    val messages: List<EmailEntity>,
)

/** One watched folder's fetched page (multi-folder push, issue #16). */
data class ImapWatchedLoad(
    val mailboxId: String,
    val name: String,
    val role: String?,
    val messages: List<EmailEntity>,
)

/**
 * The deadline arithmetic behind a time-bounded IMAP call. Pure and separate because every
 * IMAP call in the app goes through the code that uses it, and because what it protects — a
 * retry that must not be allowed to double the wait — is invisible in an integration test.
 *
 * [NO_BUDGET] is the case that must stay exactly as it always was: no deadline, and every
 * socket left blocking.
 */
internal object ImapBudget {

    /** No deadline. Also the socket value meaning "block", which is why they are one constant. */
    const val NO_BUDGET = 0

    /** The instant a call started at [nowMs] with [budgetMs] must be done, or [NO_BUDGET]. */
    fun deadline(budgetMs: Int, nowMs: Long): Long = if (budgetMs > 0) nowMs + budgetMs else NO_BUDGET.toLong()

    /**
     * What is left of [deadline] at [nowMs], as a socket timeout: [NO_BUDGET] when there is no
     * deadline, otherwise a positive number of milliseconds — and an exception once the budget
     * is spent, so a caller cannot connect or read "one last time" past its own deadline.
     *
     * A [SocketTimeoutException] on purpose: it is what an expiring read throws, so the caller
     * that already falls back on one needs no second branch — and it is emphatically NOT a
     * CancellationException, which would be indistinguishable from the user cancelling.
     */
    fun remaining(deadline: Long, nowMs: Long): Int {
        if (deadline == NO_BUDGET.toLong()) return NO_BUDGET
        val left = deadline - nowMs
        if (left <= 0) throw SocketTimeoutException("IMAP budget exhausted")
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/**
 * IMAP read+write path, parallel to the JMAP path in [MailRepository]. Maps IMAP
 * folders/messages onto the same Room entities so the cache, paging, and UI are
 * protocol-agnostic. One connection is pooled per account and reused across calls
 * (IMAP is stateful, so access is serialised); a dropped connection reconnects.
 */
class ImapMailService(
    private val imapClient: ImapClient,
    private val smtpClient: SmtpClient,
    private val tokenRefresher: OAuthTokenRefresher,
) {
    /** A pooled, reusable connection for one account. */
    private class Pooled {
        val mutex = Mutex()
        var session: ImapSession? = null
        var config: MailServerConfig? = null
    }

    private val pool = ConcurrentHashMap<String, Pooled>()

    /**
     * Run [block] on the account's pooled IMAP session, opening or reconnecting as
     * needed. Serialised per account (one command at a time); on a connection error
     * the session is dropped and the block retried once with a fresh connection.
     *
     * [budgetMs] is a wall-clock deadline for the whole call, retry included; `0` (the default
     * every existing caller keeps) means the blocking behaviour this has always had. See
     * [ENUMERATE_BUDGET_MS] for what a budget is and is not worth.
     */
    private suspend fun <T> withSession(
        credentials: AccountCredentials,
        budgetMs: Int = 0,
        block: (ImapSession) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val config = config(credentials.imap ?: error("Account has no IMAP server configured."), credentials)
            val pooled = pool.getOrPut(credentials.id) { Pooled() }
            pooled.mutex.withLock {
                // Reconnect if the account's server settings changed.
                if (pooled.config != null && pooled.config != config) {
                    pooled.session?.let { runCatching { it.close() } }
                    pooled.session = null
                }
                pooled.config = config
                runWithRetry(pooled, config, budgetMs, block)
            }
        }

    private suspend fun <T> runWithRetry(
        pooled: Pooled,
        config: MailServerConfig,
        budgetMs: Int,
        block: (ImapSession) -> T,
    ): T {
        val deadline = ImapBudget.deadline(budgetMs, System.currentTimeMillis())
        var attempt = 0
        while (true) {
            // What is left of the budget bounds the connect, and what is left AFTER it bounds
            // the reads: time spent connecting is not handed back to them, and a retry that has
            // run the budget out throws here instead of connecting again. Without a budget both
            // stay 0 — "block until the OS gives up", unchanged for every other caller.
            val session = pooled.session
                ?: imapClient.connect(config, ImapBudget.remaining(deadline, System.currentTimeMillis()))
                    .also { pooled.session = it }
            // Outside the try: an exhausted budget is not a session error and must not cost a
            // close + reconnect on the way out.
            val forReads = ImapBudget.remaining(deadline, System.currentTimeMillis())
            try {
                return session.withReadTimeout(forReads) { block(session) }
            } catch (cancelled: CancellationException) {
                // The caller gave up (an Undo, a closed screen): the session is mid-command and
                // its stream state unknown, so drop it — but do NOT spend a reconnect + LOGIN
                // retrying work nobody is waiting for, and let the cancellation through.
                runCatching { session.close() }
                pooled.session = null
                throw cancelled
            } catch (t: Throwable) {
                runCatching { session.close() }
                pooled.session = null
                if (++attempt >= 2) throw t
            }
        }
    }

    /**
     * Open a dedicated IDLE connection on the account's INBOX for push. Separate from the
     * pooled connection (IDLE blocks); returns a Closeable the caller closes to stop.
     */
    suspend fun openIdle(credentials: AccountCredentials, onChanged: () -> Unit, onClosed: () -> Unit): java.io.Closeable {
        val endpoint = credentials.imap ?: error("Account has no IMAP server configured.")
        // The token is resolved fresh here; if it expires mid-IDLE the connection drops and
        // the caller reopens (re-refreshing) via onClosed.
        return ImapIdleConnection(imapClient, config(endpoint, credentials), "INBOX", onChanged, onClosed)
    }

    /** Raw folder list (paths + the server's REAL hierarchy delimiter from LIST). */
    suspend fun listImapFolders(credentials: AccountCredentials) =
        withSession(credentials) { it.listFolders() }

    /** Close and forget an account's pooled connection (e.g. on sign-out). */
    suspend fun disconnect(accountId: String) {
        val pooled = pool.remove(accountId) ?: return
        pooled.mutex.withLock {
            pooled.session?.let { runCatching { it.close() } }
            pooled.session = null
        }
    }

    /** Send via SMTP, then APPEND a \Seen copy into the Sent folder (if known). */
    suspend fun send(credentials: AccountCredentials, message: OutgoingMessage, sentMailbox: String?) {
        val smtp = credentials.smtp ?: error("Account has no SMTP server configured.")
        val token = tokenRefresher.freshAccessToken(credentials)
        smtpClient.send(
            MailServerConfig(smtp.host, smtp.port, smtp.security.toMailSecurity(), credentials.username, credentials.password, token),
            message,
        )
        if (sentMailbox != null) {
            runCatching { withSession(credentials) { it.append(sentMailbox, OutgoingMime.build(message), "\\Seen") } }
        }
    }

    /** APPEND a draft (\Draft flag) into the Drafts folder. */
    suspend fun appendDraft(credentials: AccountCredentials, draftsMailbox: String, message: OutgoingMessage) =
        withSession(credentials) { it.append(draftsMailbox, OutgoingMime.build(message), "\\Draft") }

    /** Connect + authenticate (and list folders) to verify the account's IMAP settings. */
    suspend fun testConnection(credentials: AccountCredentials) {
        withSession(credentials) { it.listFolders() }
    }

    /**
     * List a SPECIFIC account's folders (role + path), without fetching any messages.
     * Used to resolve a role's folder for the message's own account — the global mailbox
     * cache only holds the last-synced account, so it's wrong for a non-current account
     * in the unified inbox.
     */
    suspend fun listMailboxes(credentials: AccountCredentials): List<MailboxEntity> =
        withSession(credentials) { session ->
            session.listFolders().mapIndexed { index, folder ->
                MailboxEntity(
                    accountId = credentials.id,
                    id = folder.path,
                    name = folder.name,
                    role = folder.role,
                    sortOrder = rolePriority(folder.role) * 1000 + index,
                    totalEmails = 0,
                    unreadEmails = 0,
                )
            }
        }

    /** Connect, list folders, and fetch the newest [limit] of the target folder. */
    suspend fun loadFolder(
        credentials: AccountCredentials,
        requestedMailboxId: String?,
        limit: Int,
    ): ImapFolderLoad = withSession(credentials) { session ->
        val folders = session.listFolders()
        val mailboxes = folders.mapIndexed { index, folder ->
            MailboxEntity(
                accountId = credentials.id,
                id = folder.path,
                name = folder.name,
                role = folder.role,
                sortOrder = rolePriority(folder.role) * 1000 + index,
                totalEmails = 0,
                unreadEmails = 0,
            )
        }
        val target = folders.firstOrNull { it.path == requestedMailboxId }
            ?: folders.firstOrNull { it.role == "inbox" }
            ?: folders.firstOrNull { it.path.equals("INBOX", ignoreCase = true) }
            ?: folders.first()

        val status = session.select(target.path)
        val unread = session.unseenCount()
        val messages = session.fetchPage(status.exists, offset = 0, limit = limit)
            .map { it.toEntity(credentials.id, target.path) }

        ImapFolderLoad(
            mailboxes = mailboxes,
            targetMailboxId = target.path,
            targetName = target.name,
            unread = unread,
            accountName = credentials.username,
            messages = messages,
        )
    }

    /**
     * Fetch the newest [limit] messages of the inbox (unless [includeInbox] is false) plus
     * each watched folder in [extraPaths], on one session (multi-folder push, issue #16).
     * Watched paths no longer on the server come back in the second component so the
     * caller can prune the stale watch flags.
     */
    suspend fun loadWatchedFolders(
        credentials: AccountCredentials,
        extraPaths: Set<String>,
        includeInbox: Boolean,
        limit: Int,
    ): Pair<List<ImapWatchedLoad>, Set<String>> = withSession(credentials) { session ->
        val folders = session.listFolders()
        val inbox = folders.firstOrNull { it.role == "inbox" }
            ?: folders.firstOrNull { it.path.equals("INBOX", ignoreCase = true) }
            ?: folders.first()
        val targets = buildList {
            if (includeInbox) add(inbox)
            extraPaths.forEach { path ->
                val folder = folders.firstOrNull { it.path == path }
                if (folder != null && folder.path != inbox.path) add(folder)
            }
        }
        val missing = extraPaths.filterTo(mutableSetOf()) { path -> folders.none { it.path == path } }
        val loads = targets.map { folder ->
            val status = session.select(folder.path)
            val messages = session.fetchPage(status.exists, offset = 0, limit = limit)
                .map { it.toEntity(credentials.id, folder.path) }
            ImapWatchedLoad(folder.path, folder.name, folder.role, messages)
        }
        loads to missing
    }

    /** Fetch the page of messages just older than the [offset] newest, for paging. */
    suspend fun fetchOlderPage(
        credentials: AccountCredentials,
        mailboxId: String,
        offset: Int,
        limit: Int,
    ): Pair<List<EmailEntity>, Int> = withSession(credentials) { session ->
        val status = session.select(mailboxId)
        val messages = session.fetchPage(status.exists, offset, limit)
            .map { it.toEntity(credentials.id, mailboxId) }
        messages to status.exists
    }

    /** Mark a message seen on the server (UID STORE +FLAGS \Seen). */
    suspend fun markSeen(credentials: AccountCredentials, mailboxId: String, uid: Long) =
        setFlag(credentials, mailboxId, uid, "\\Seen", true)

    /** Set or clear an IMAP flag (e.g. \Seen, \Flagged) on a message. */
    suspend fun setFlag(credentials: AccountCredentials, mailboxId: String, uid: Long, flag: String, set: Boolean) =
        withSession(credentials) { it.select(mailboxId); it.setFlag(uid, flag, set) }

    /** Move a message to [destMailbox]; returns its new UID there (for undo), or null. */
    suspend fun move(credentials: AccountCredentials, sourceMailbox: String, uid: Long, destMailbox: String): Long? =
        withSession(credentials) { it.select(sourceMailbox); it.move(uid, destMailbox) }

    /** Permanently delete a message (\Deleted + EXPUNGE) when there's no Trash. */
    suspend fun deleteMessage(credentials: AccountCredentials, mailboxId: String, uid: Long) =
        withSession(credentials) { it.select(mailboxId); it.delete(uid) }

    /**
     * Move many messages from one source folder to [destMailbox] in a single session —
     * one SELECT + one `UID MOVE <set>` (chunked) instead of N round-trips (Codeberg #29).
     * Returns the source-UID → destination-UID mapping (from COPYUID) so Undo can move the
     * whole batch back; empty if the server reported none.
     */
    suspend fun moveBatch(credentials: AccountCredentials, sourceMailbox: String, uids: List<Long>, destMailbox: String): Map<Long, Long> =
        withSession(credentials) { it.select(sourceMailbox); it.move(uids, destMailbox) }

    /** Permanently delete many messages from one folder in a single session (Codeberg #29). */
    suspend fun deleteBatch(credentials: AccountCredentials, mailboxId: String, uids: List<Long>) =
        withSession(credentials) { it.select(mailboxId); it.delete(uids) }

    /**
     * At most [cap] UIDs currently in [mailboxId] — one SELECT + one `UID SEARCH ALL`, no
     * envelopes, and [cap] ids held whatever the folder's size (to a few tokens per response
     * line, see [ImapSession.allUids]).
     *
     * The folder as the SERVER holds it, not as the cache happens to have synced it, which is
     * what a whole-folder operation needs: an "Empty trash" on a Trash the user never scrolled
     * through must cover the messages below the synced window too (Codeberg #99). Past [cap]
     * the ids kept are the OLDEST — see [ImapSession.allUids], which also says why that is the
     * opposite end from JMAP's.
     *
     * TIME-BOUNDED, unlike every other call here, because the screen has already told the user
     * the Trash is empty by the time this runs: it is allowed [ENUMERATE_BUDGET_MS] (read its
     * doc for what that does and does not bound), then it fails and the caller falls back to
     * the cached ids. Waiting out a black-holed network on this path would leave a success
     * message on screen over an untouched list.
     */
    suspend fun allUids(credentials: AccountCredentials, mailboxId: String, cap: Int): List<Long> =
        withSession(credentials, budgetMs = ENUMERATE_BUDGET_MS) { session ->
            session.select(mailboxId)
            session.allUids(cap)
        }

    /** Fetch several messages by UID from one folder in a single session (e.g. to re-cache a restored batch). */
    suspend fun fetchByUids(credentials: AccountCredentials, mailboxId: String, uids: List<Long>): List<EmailEntity> =
        if (uids.isEmpty()) emptyList()
        else withSession(credentials) { session ->
            session.select(mailboxId)
            session.fetchUids(uids).map { it.toEntity(credentials.id, mailboxId) }
        }

    /** Create a folder if it doesn't exist (e.g. an Archive on first archive). */
    suspend fun createFolder(credentials: AccountCredentials, path: String) =
        withSession(credentials) { it.createFolder(path) }

    /** Rename a folder (IMAP RENAME). */
    suspend fun renameFolder(credentials: AccountCredentials, oldPath: String, newPath: String) =
        withSession(credentials) { it.renameFolder(oldPath, newPath) }

    /** Delete a folder (IMAP DELETE). */
    suspend fun deleteFolder(credentials: AccountCredentials, path: String) =
        withSession(credentials) { it.deleteFolder(path) }

    /**
     * Server-side structured search across [mailboxIds], newest first (entities not cached by
     * the caller). One SELECT + UID SEARCH per folder on the single pooled connection, in the
     * order given — the caller ranks the folders, so a per-account cap keeps the folders that
     * matter rather than whichever the server listed first.
     *
     * Each hit is turned into an entity under THE FOLDER IT CAME FROM and this account's id: an
     * IMAP UID is meaningless outside its mailbox, and the cache id is `imap:account:folder:uid`.
     * A folder that fails is logged and skipped, never silently merged away.
     *
     * The result carries [ImapSearchHits.complete]: false when a folder's attachment scan stopped
     * on its cap with candidates left, i.e. the list is what was found, not what exists.
     */
    suspend fun search(
        credentials: AccountCredentials,
        mailboxIds: List<String>,
        criteria: ImapSearchCriteria,
        requireAttachment: Boolean,
        limit: Int,
    ): ImapSearchHits {
        if (mailboxIds.isEmpty() || limit <= 0) return ImapSearchHits(emptyList(), complete = true)
        val command = buildImapSearch(criteria)
        // The walk is blocking and holds this account's only connection; if the caller is gone
        // (a new keystroke cancelled the search), stop between folders instead of making every
        // later IMAP call wait behind it.
        val caller = currentCoroutineContext()[Job]
        return withSession(credentials) { session ->
            val folders = session.searchFolders(
                mailboxIds,
                command,
                requireAttachment,
                limit,
                stillWanted = { caller?.isActive != false },
                onFolderError = { mailbox, error ->
                    android.util.Log.w("ImapSearch", "search failed in $mailbox: ${error.message}")
                },
            )
            ImapSearchHits(
                messages = folders
                    .flatMap { hits -> hits.messages.map { it.toEntity(credentials.id, hits.mailbox) } }
                    .sortedByDescending { it.sortKey }
                    .take(limit),
                complete = folders.none { it.scanTruncated },
            )
        }
    }

    /** Fetch and decode an attachment (one MIME [section]) to raw bytes. */
    suspend fun fetchAttachment(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        section: String,
        encoding: String?,
    ): ByteArray = withSession(credentials) { session ->
        session.select(mailboxId)
        MimeParser.decodeBytes(session.fetchSection(uid, section), encoding)
    }

    /** Fetch one message by UID into a cache entity (used to restore after an undo). */
    suspend fun fetchByUid(credentials: AccountCredentials, mailboxId: String, uid: Long): EmailEntity? =
        withSession(credentials) { session ->
            session.select(mailboxId)
            session.fetchByUid(uid)?.toEntity(credentials.id, mailboxId)
        }

    /** Raw RFC822 source of a message (caller parses out the body/attachments). */
    suspend fun fetchSource(credentials: AccountCredentials, mailboxId: String, uid: Long): String =
        withSession(credentials) { session ->
            session.select(mailboxId)
            session.fetchSource(uid)
        }

    private fun ImapMessage.toEntity(accountId: String, mailboxId: String): EmailEntity {
        val id = emailId(accountId, mailboxId, uid)
        return EmailEntity(
            id = id,
            accountId = accountId,
            mailboxId = mailboxId,
            threadId = null,
            subject = subject,
            preview = null,
            receivedAt = if (dateMillis > 0) Instant.ofEpochMilli(dateMillis).toString() else null,
            fromName = fromName,
            fromEmail = fromEmail,
            seen = seen,
            flagged = flagged,
            hasAttachment = hasAttachment,
            sortKey = dateMillis,
            // Envelope recipients, persisted since schema v17 so Sent/Drafts rows can show
            // "To: …" from the cold cache (Codeberg #59/#63) — like the JMAP path in EmailMapper.
            recipientsJson = EmailRecipients.encode(
                to.map { EmailAddress(name = it.name, email = it.email.orEmpty()) },
            ),
        )
    }

    private suspend fun config(endpoint: MailEndpoint, credentials: AccountCredentials) = MailServerConfig(
        host = endpoint.host,
        port = endpoint.port,
        security = endpoint.security.toMailSecurity(),
        username = credentials.username,
        password = credentials.password,
        // OAuth accounts authenticate with a fresh bearer token (XOAUTH2); null = password.
        accessToken = tokenRefresher.freshAccessToken(credentials),
    )

    private fun ConnectionSecurity.toMailSecurity(): MailSecurity = when (this) {
        ConnectionSecurity.TLS -> MailSecurity.TLS
        ConnectionSecurity.STARTTLS -> MailSecurity.STARTTLS
        ConnectionSecurity.NONE -> MailSecurity.NONE
    }

    private fun rolePriority(role: String?): Int = when (role) {
        "inbox" -> 0
        "archive" -> 1
        "sent" -> 2
        "drafts" -> 3
        "junk" -> 4
        "trash" -> 5
        else -> 6
    }

    companion object {
        /**
         * Wall-clock budget for a whole-folder enumeration ([allUids]), reconnect included.
         *
         * What it really bounds, stated plainly because the number is not the wait:
         * - the TCP connect: at most this;
         * - EACH read after it: at most what is left once the connect is paid for;
         * - the reconnect-once retry: nothing extra — the second attempt draws on the same
         *   deadline and throws instead of connecting when it is spent.
         *
         * It does NOT bound their sum. A socket timeout applies per read, and the parser reads
         * through a buffer, so a 10 000-UID answer is a handful of refills: a peer that trickles
         * one byte just before each expiry can stretch the whole call to this budget times the
         * number of refills — of the order of a minute or two, not fifteen seconds. Bounding the
         * sum would mean re-arming the deadline inside the parser's read loop; that is the fix
         * if this ever bites in the field. Against the OS default, which lets such a peer run for
         * as long as it likes on a screen that already says "Trash emptied", it is still the
         * difference between minutes and never. A real Trash answers in well under a second.
         */
        const val ENUMERATE_BUDGET_MS = 15_000

        /** Stable, globally-unique cache id for an IMAP message. */
        fun emailId(accountId: String, mailboxId: String, uid: Long): String = "imap:$accountId:$mailboxId:$uid"

        /** The IMAP UID encoded in a cache id, or null if not an IMAP id. */
        fun uidOf(emailId: String): Long? =
            if (emailId.startsWith("imap:")) emailId.substringAfterLast(':').toLongOrNull() else null

        /** The mailbox path encoded in a cache id (handles ':' in the path), or null. */
        fun mailboxOf(emailId: String): String? {
            if (!emailId.startsWith("imap:")) return null
            val parts = emailId.split(':')
            return if (parts.size >= 4) parts.subList(2, parts.size - 1).joinToString(":") else null
        }
    }
}

/**
 * What an IMAP search found, and whether that is all there was: [complete] is false when a
 * folder's local attachment filter hit its scan cap before running out of candidates.
 */
data class ImapSearchHits(val messages: List<EmailEntity>, val complete: Boolean)

/**
 * The IMAP-expressible part of a [SearchQuery]. `hasAttachment` is deliberately absent: IMAP
 * `SEARCH` has no key for it, so it is passed separately and applied locally.
 */
internal fun SearchQuery.toImapCriteria() = ImapSearchCriteria(
    text = text,
    from = from,
    recipient = recipient,
    subject = subject,
    afterMillis = afterMillis,
    beforeMillis = beforeMillis,
)
