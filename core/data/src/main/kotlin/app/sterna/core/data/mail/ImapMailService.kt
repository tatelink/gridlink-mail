package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.imap.IMAP_FOLDER_PAGE
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapFolderWalk
import app.sterna.core.imap.ImapIdleConnection
import app.sterna.core.imap.ImapMailboxStatus
import app.sterna.core.imap.ImapMessage
import app.sterna.core.imap.ImapSearchCriteria
import app.sterna.core.imap.ImapSession
import app.sterna.core.imap.ImapUidValidityChanged
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

/**
 * A folder's mailboxes + what the walk of the target folder saw.
 *
 * ⛔ There are no `messages` here any more. They were handed to the caller page by page while the
 * walk ran ([ImapMailService.loadFolder]'s `onPage`) and are already in the cache by the time this
 * value exists; what is left is [walk] — the UIDs, and whether the folder held still.
 */
data class ImapFolderLoad(
    val mailboxes: List<MailboxEntity>,
    val targetMailboxId: String,
    val targetName: String,
    val unread: Int,
    val accountName: String,
    val walk: ImapFolderWalk,
)

/**
 * The cache ids a finished IMAP folder walk MAY be reconciled against — or NULL when the folder
 * moved under the walk and there is no set the reconcile can safely be given.
 *
 * ⛔ A function of its own, and a pure one, because it is the whole IMAP half of the red line and a
 * condition buried in the middle of a refresh is a condition nobody can execute in a test. Null is
 * a refusal, never an empty set: `reconcileMailbox` given an empty keep set deletes the folder.
 *
 * Ids, not UIDs: the cache is keyed by `imap:account:folder:uid`, so the mapping needs the account
 * and the folder the walk actually landed on (which is not always the one the caller asked for —
 * `loadFolder` falls back to the inbox). And it is a mapping of the WHOLE walk, every page of it,
 * never the last one.
 */
/**
 * ⛔ Settle the folder's NUMBERING before a single row of it can be seen, then walk it.
 *
 * Two lines and an order, in a function of its own for the reason [fullQueryWriteThrough] is one: an
 * order that exists only as the sequence of two statements in the middle of a long function is an
 * order no test can execute.
 *
 * [settle] is `ImapMailService.reconcileNumbering` — on a folder whose UIDVALIDITY changed, it drops
 * every cache keyed by the old UIDs (bodies, purge snapshots, the notification baseline). [walk]
 * hands its pages to the caller AS THEY LAND, so the first of them is on screen long before the walk
 * returns. Settling afterwards left a window the length of the walk during which a row of the NEW
 * numbering was listed while the body cached for that same id under the OLD one was still there —
 * and opening a message reads the body cache first. That is Codeberg #99's exact symptom, and
 * writing pages as they land is what re-opened it; before that, nothing was visible until the read
 * was over.
 *
 * ⛔ [settle] COMPARES AND RECORDS, it does not refuse: `loadFolder` selects folders it has just
 * listed, and refusing there would fail a whole inbox refresh over one renumbered folder. Running it
 * earlier must not turn it into a gate.
 *
 * ⚠ It now also runs when the walk goes on to fail, which it did not before. That is the safe
 * direction — an invalidation seen and applied — and the caches it drops are re-fillable.
 */
internal suspend fun <R> withNumberingSettled(settle: suspend () -> Unit, walk: suspend () -> R): R {
    settle()
    return walk()
}

internal fun reconcilableIds(load: ImapFolderLoad, accountId: String): Set<String>? {
    if (load.walk.moved) return null
    return load.walk.uids.mapTo(HashSet()) { ImapMailService.emailId(accountId, load.targetMailboxId, it) }
}

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
    /** Where each folder's UIDVALIDITY is remembered; [UidValidityStore.None] verifies nothing. */
    private val uidValidity: UidValidityStore = UidValidityStore.None,
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
        // Suspending for ONE caller, [loadFolder], whose folder walk writes each page to the
        // database between requests — that is what holds the walk's memory to one page. Every
        // other block here suspends at nothing and behaves exactly as before. ⚠ What a suspending
        // block costs is that the account's single connection is held across those suspensions:
        // nothing slow belongs in one that is not the reason the connection is open.
        block: suspend (ImapSession) -> T,
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
        block: suspend (ImapSession) -> T,
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
                return session.withReadTimeoutSuspending(forReads) { block(session) }
            } catch (cancelled: CancellationException) {
                // The caller gave up (an Undo, a closed screen): the session is mid-command and
                // its stream state unknown, so drop it — but do NOT spend a reconnect + LOGIN
                // retrying work nobody is waiting for, and let the cancellation through.
                runCatching { session.close() }
                pooled.session = null
                throw cancelled
            } catch (renumbered: ImapUidValidityChanged) {
                // Not a transport failure: the connection is healthy and the answer would be the
                // same on a fresh one. Retrying would only ask the server to renumber the folder
                // back. Keep the session and let the refusal through (Codeberg #99).
                throw renumbered
            } catch (t: Throwable) {
                runCatching { session.close() }
                pooled.session = null
                if (++attempt >= 2) throw t
            }
        }
    }

    /**
     * THE CHOKEPOINT for anything addressed by UID: SELECT the folder, check that its numbering
     * is still the one our UIDs belong to, then run [block] (Codeberg #99).
     *
     * Every command below that names a UID — a flag, a move, a destroy, a body fetch — is
     * preceded by a `select()` whose status was, until now, read and thrown away. It carries the
     * folder's UIDVALIDITY, and UIDVALIDITY changing is the one event that makes a UID mean a
     * DIFFERENT message rather than no message (RFC 3501 §2.3.1.1). Doing the check here, once,
     * is what stops a caller from forgetting it.
     *
     * [expectedUidValidity] overrides the remembered value for a caller that carries its own —
     * an "Empty trash" snapshot was frozen under a numbering of its own, possibly hours before
     * the destroy worker gets connectivity, and that is the value its ids belong to.
     *
     * A mismatch invalidates what cannot heal itself and propagates: nothing is retried, nothing
     * is swallowed. The remembered value is written AFTER the block succeeds, and the read is
     * done BEFORE the session is taken, because the store is suspending and the session block
     * is not.
     *
     * The discovery reads ([loadFolder], [loadWatchedFolders]) do not come through here: they
     * select folders they have just listed, so refusing there would fail a whole inbox refresh
     * over one renumbered folder. They still COMPARE AND RECORD, through [reconcileNumbering] —
     * without that, browsing a folder would leave its numbering unrecorded, an offline "Empty
     * trash" would then have nothing to stand in for the unreachable server, and the body cache
     * would only be dropped by whatever happened to touch a message afterwards. Refusing and
     * reconciling are different questions; only the first belongs here.
     */
    private suspend fun <T> onMailbox(
        credentials: AccountCredentials,
        mailboxId: String,
        budgetMs: Int = 0,
        expectedUidValidity: Long? = null,
        block: suspend (ImapSession, ImapMailboxStatus) -> T,
    ): T {
        val recorded = uidValidity.recorded(credentials.id, mailboxId)
        var observed = 0L
        val result = try {
            withSession(credentials, budgetMs) { session ->
                val status = session.select(mailboxId, expectedUidValidity ?: recorded)
                observed = status.uidValidity
                block(session, status)
            }
        } catch (renumbered: ImapUidValidityChanged) {
            uidValidity.invalidate(credentials.id, mailboxId, renumbered.observed)
            throw renumbered
        }
        rememberNumbering(credentials.id, mailboxId, recorded, observed)
        return result
    }

    /**
     * Apply what a SELECT just observed, WITHOUT refusing anything: record it, or — when it does
     * not match what was recorded — invalidate the caches keyed by the old numbering first.
     *
     * The comparison is the same one [onMailbox] makes; only the consequence differs. A read that
     * merely lists a folder has nothing to abort, but it is still the first thing to notice the
     * renumbering, and the body cache has to be dropped BEFORE the caller shows a message from it.
     */
    private suspend fun rememberNumbering(accountId: String, mailboxId: String, recorded: Long?, observed: Long) {
        when (UidValidity.verdict(recorded, observed)) {
            UidValidity.Verdict.CHANGED -> uidValidity.invalidate(accountId, mailboxId, observed)
            // The overwhelmingly common answer, and it costs nothing: every folder select of
            // every refresh comes through here, and rewriting the same number would be a
            // database write per folder per sync on devices that can least afford one.
            UidValidity.Verdict.SAME, UidValidity.Verdict.UNVERIFIABLE -> Unit
            UidValidity.Verdict.FIRST_SIGHT -> uidValidity.record(accountId, mailboxId, observed)
        }
    }

    /** [rememberNumbering] for a caller that had nothing in hand beforehand (the discovery reads). */
    private suspend fun reconcileNumbering(accountId: String, mailboxId: String, observed: Long) {
        if (observed <= 0L) return
        rememberNumbering(accountId, mailboxId, uidValidity.recorded(accountId, mailboxId), observed)
    }

    /** The numbering last observed for a folder, for a caller that has to record it elsewhere
     *  (the Empty-trash snapshot, when it falls back to the cached ids). */
    suspend fun recordedUidValidity(accountId: String, mailboxId: String): Long? =
        uidValidity.recorded(accountId, mailboxId)

    /**
     * Called when a folder turns out to have been renumbered, with `(accountId, mailboxId)`.
     *
     * The data layer drops what it owns (bodies, destroy lists) itself; the notification baseline
     * lives in `:app` and cannot be reached from here, so the app layer sets this at startup —
     * the same shape as `MailRepository.onAccountPruned`, and for the same reason. Left unset it
     * does nothing.
     */
    var onMailboxRenumbered: ((String, String) -> Unit)?
        get() = uidValidity.onRenumbered
        set(value) { uidValidity.onRenumbered = value }

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

    /**
     * Connect, list folders, and WALK the newest [limit] of the target folder, handing each page to
     * [onPage] as it lands.
     *
     * ⛔ [onPage] is not a convenience: it is where the window's messages go. This function keeps
     * none of them — see [ImapFolderLoad] — so a caller that passes an empty lambda is asking for
     * the folder list and nothing else, which is what the folder-list refresh does. There is no
     * default for it, deliberately: "where do these messages go" is a question every caller has to
     * answer out loud.
     *
     * ⛔ And [ImapFolderLoad.walk] carries [ImapFolderWalk.moved], which a caller that DELETES on
     * the strength of this walk must honour ([reconcilableIds]).
     *
     * Selects a folder it has just listed, so it cannot be refused on its numbering — but it is
     * usually the FIRST thing to see that numbering, and browsing a folder must be enough to
     * record it: an offline "Empty trash" afterwards has nothing else to go on, and the body
     * cache has to be dropped BEFORE the user can open a message from the list this writes. Hence
     * [reconcileNumbering] ahead of the walk and not after it ([withNumberingSettled], Codeberg
     * #99): the pages become visible while the walk is still running.
     *
     * ⚠ What this costs while it runs, stated because it is not free: the account's ONE connection
     * is held for the whole walk, database writes included, so every other IMAP operation on that
     * account (opening a message, marking read, moving, emptying the trash) queues behind it. That
     * was already true of the single FETCH this replaces, but the writes are new inside the lock.
     * ⚠ And the walk is retried ONCE as a whole on a transport failure (`runWithRetry`), so a
     * DATABASE failure in the middle of it re-runs the entire network read as well. Both are known
     * and out of this change's scope; neither can delete anything.
     */
    suspend fun loadFolder(
        credentials: AccountCredentials,
        requestedMailboxId: String?,
        limit: Int,
        onPage: suspend (List<EmailEntity>) -> Unit,
    ): ImapFolderLoad {
        return withSession(credentials) { session ->
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
            val walk = withNumberingSettled(
                settle = { reconcileNumbering(credentials.id, target.path, status.uidValidity) },
                walk = {
                    session.walkFolder(status.exists, limit, IMAP_FOLDER_PAGE) { page ->
                        onPage(page.map { it.toEntity(credentials.id, target.path) })
                    }
                },
            )

            ImapFolderLoad(
                mailboxes = mailboxes,
                targetMailboxId = target.path,
                targetName = target.name,
                unread = unread,
                accountName = credentials.username,
                walk = walk,
            )
        }
    }

    /**
     * Fetch the newest [limit] messages of the inbox (unless [includeInbox] is false) plus
     * each watched folder in [extraPaths], on one session (multi-folder push, issue #16).
     * Watched paths no longer on the server come back in the second component so the
     * caller can prune the stale watch flags.
     *
     * Like [loadFolder] it cannot be refused on a folder's numbering — it selects what it has
     * just listed — but it compares and records every folder it touched ([reconcileNumbering]),
     * so a renumbering noticed by a background push pass drops the same caches a foreground one
     * would (Codeberg #99).
     */
    suspend fun loadWatchedFolders(
        credentials: AccountCredentials,
        extraPaths: Set<String>,
        includeInbox: Boolean,
        limit: Int,
    ): Pair<List<ImapWatchedLoad>, Set<String>> {
        val numbering = mutableListOf<Pair<String, Long>>()
        val result = withSession(credentials) { session ->
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
                numbering += folder.path to status.uidValidity
                val messages = session.fetchPage(status.exists, offset = 0, limit = limit)
                    .map { it.toEntity(credentials.id, folder.path) }
                ImapWatchedLoad(folder.path, folder.name, folder.role, messages)
            }
            loads to missing
        }
        numbering.forEach { (path, observed) -> reconcileNumbering(credentials.id, path, observed) }
        return result
    }

    /** Fetch the page of messages just older than the [offset] newest, for paging. */
    suspend fun fetchOlderPage(
        credentials: AccountCredentials,
        mailboxId: String,
        offset: Int,
        limit: Int,
    ): Pair<List<EmailEntity>, Int> = onMailbox(credentials, mailboxId) { session, status ->
        val messages = session.fetchPage(status.exists, offset, limit)
            .map { it.toEntity(credentials.id, mailboxId) }
        messages to status.exists
    }

    /** Mark a message seen on the server (UID STORE +FLAGS \Seen). */
    suspend fun markSeen(credentials: AccountCredentials, mailboxId: String, uid: Long) =
        setFlag(credentials, mailboxId, uid, "\\Seen", true)

    /** Set or clear an IMAP flag (e.g. \Seen, \Flagged) on a message. */
    suspend fun setFlag(credentials: AccountCredentials, mailboxId: String, uid: Long, flag: String, set: Boolean) =
        onMailbox(credentials, mailboxId) { session, _ -> session.setFlag(uid, flag, set) }

    /** Move a message to [destMailbox]; returns its new UID there (for undo), or null. */
    suspend fun move(credentials: AccountCredentials, sourceMailbox: String, uid: Long, destMailbox: String): Long? =
        onMailbox(credentials, sourceMailbox) { session, _ -> session.move(uid, destMailbox) }

    /** Permanently delete a message (\Deleted, then erased by UID) when there's no Trash. */
    suspend fun deleteMessage(credentials: AccountCredentials, mailboxId: String, uid: Long) =
        onMailbox(credentials, mailboxId) { session, _ -> session.delete(uid) }

    /**
     * Move many messages from one source folder to [destMailbox] in a single session —
     * one SELECT + one `UID MOVE <set>` (chunked) instead of N round-trips (Codeberg #29).
     * Returns the source-UID → destination-UID mapping (from COPYUID) so Undo can move the
     * whole batch back; empty if the server reported none.
     */
    suspend fun moveBatch(credentials: AccountCredentials, sourceMailbox: String, uids: List<Long>, destMailbox: String): Map<Long, Long> =
        onMailbox(credentials, sourceMailbox) { session, _ -> session.move(uids, destMailbox) }

    /**
     * Permanently delete many messages from one folder in a single session (Codeberg #29).
     *
     * [expectedUidValidity] is the numbering the caller's UIDs were read under — the one frozen
     * with an "Empty trash" snapshot. A folder renumbered since then refuses the whole call
     * ([ImapUidValidityChanged]) instead of destroying whatever now holds those numbers (#99).
     */
    suspend fun deleteBatch(
        credentials: AccountCredentials,
        mailboxId: String,
        uids: List<Long>,
        expectedUidValidity: Long? = null,
    ) = onMailbox(credentials, mailboxId, expectedUidValidity = expectedUidValidity) { session, _ ->
        session.delete(uids)
    }

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
    suspend fun snapshotUids(credentials: AccountCredentials, mailboxId: String, cap: Int): ImapUidSnapshot =
        onMailbox(credentials, mailboxId, budgetMs = ENUMERATE_BUDGET_MS) { session, status ->
            // The numbering comes back WITH the ids: they are only an order for as long as it
            // holds, so the caller freezes the two together (Codeberg #99).
            ImapUidSnapshot(session.allUids(cap), status.uidValidity)
        }

    /** Fetch several messages by UID from one folder in a single session (e.g. to re-cache a restored batch). */
    suspend fun fetchByUids(credentials: AccountCredentials, mailboxId: String, uids: List<Long>): List<EmailEntity> =
        if (uids.isEmpty()) emptyList()
        else onMailbox(credentials, mailboxId) { session, _ ->
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
     * The result carries [ImapSearchHits.complete]: false when any folder's answer was not whole
     * — its attachment scan stopped on its cap with candidates left, OR it could not be searched
     * at all. The list is then what was found, not what exists.
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
                complete = folders.none { it.incomplete },
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
    ): ByteArray = onMailbox(credentials, mailboxId) { session, _ ->
        MimeParser.decodeBytes(session.fetchSection(uid, section), encoding)
    }

    /** Fetch one message by UID into a cache entity (used to restore after an undo). */
    suspend fun fetchByUid(credentials: AccountCredentials, mailboxId: String, uid: Long): EmailEntity? =
        onMailbox(credentials, mailboxId) { session, _ ->
            session.fetchByUid(uid)?.toEntity(credentials.id, mailboxId)
        }

    /** Raw RFC822 source of a message (caller parses out the body/attachments). */
    suspend fun fetchSource(credentials: AccountCredentials, mailboxId: String, uid: Long): String =
        onMailbox(credentials, mailboxId) { session, _ -> session.fetchSource(uid) }

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
 * A folder's UIDs and the numbering they belong to, read in the same SELECT.
 *
 * The two travel together on purpose: a list of UIDs without its UIDVALIDITY is a list of
 * numbers that may already mean something else (Codeberg #99). [uidValidity] is 0 when the
 * server reported none.
 */
data class ImapUidSnapshot(val uids: List<Long>, val uidValidity: Long)

/**
 * The IMAP-expressible part of a [SearchQuery]. `hasAttachment` is deliberately absent: IMAP
 * `SEARCH` has no key for it, so it is passed separately and applied locally. `flagged` is NOT
 * in that situation — `FLAGGED` is a standard search key — so it travels here, with the criteria
 * the server resolves itself.
 */
internal fun SearchQuery.toImapCriteria() = ImapSearchCriteria(
    text = text,
    from = from,
    recipient = recipient,
    subject = subject,
    flagged = flagged,
    afterMillis = afterMillis,
    beforeMillis = beforeMillis,
)

/**
 * Whether the IMAP walk has to fetch candidates and filter them HERE instead of letting the
 * server answer — the decision that costs an envelope FETCH per candidate, a scan cap and a
 * possibly truncated count.
 *
 * A named function rather than an expression at the call site so it can be pinned by a test:
 * this is the single line that decides whether a criterion is cheap or expensive, and widening
 * it by accident (`hasAttachment || flagged`) would be a silent, gratuitous performance
 * regression on every starred-mail search. `flagged` must NEVER appear here: `FLAGGED` is a
 * standard `SEARCH` key, so the server answers it without a single message being fetched.
 */
internal fun SearchQuery.requiresLocalScan(): Boolean = hasAttachment
