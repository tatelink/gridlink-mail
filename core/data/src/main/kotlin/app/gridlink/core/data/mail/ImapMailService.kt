package app.gridlink.core.data.mail

import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.account.ConnectionSecurity
import app.gridlink.core.data.account.MailEndpoint
import app.gridlink.core.data.db.EmailEntity
import app.gridlink.core.data.db.EmailKeywords
import app.gridlink.core.data.db.EmailRecipients
import app.gridlink.core.data.db.MailboxEntity
import app.gridlink.core.imap.ImapClient
import app.gridlink.core.imap.ImapFlagChange
import app.gridlink.core.imap.ImapIdleConnection
import app.gridlink.core.imap.ImapMailboxStatus
import app.gridlink.core.imap.ImapMessage
import app.gridlink.core.imap.ImapSearchCriteria
import app.gridlink.core.imap.ImapSession
import app.gridlink.core.imap.ImapUidValidityChanged
import app.gridlink.core.imap.MailSecurity
import app.gridlink.core.imap.MailServerConfig
import app.gridlink.core.imap.MimeParser
import app.gridlink.core.imap.OutgoingMessage
import app.gridlink.core.imap.OutgoingMime
import app.gridlink.core.imap.SmtpClient
import app.gridlink.core.imap.buildImapSearch
import app.gridlink.core.imap.searchFolders
import app.gridlink.core.jmap.model.EmailAddress
import app.gridlink.core.jmap.model.SearchQuery
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
    val sync: ImapFolderSync,
)

/** One watched folder's fetched page (multi-folder push, issue #16). */
data class ImapWatchedLoad(
    val mailboxId: String,
    val name: String,
    val role: String?,
    val sync: ImapFolderSync,
)

/**
 * What a refresh actually brought back for one folder (RFC 7162 CONDSTORE).
 *
 * 🔴 A SEALED TYPE, and that is the point of it. Before CONDSTORE every refresh returned "the
 * folder's newest window" and callers could pass it straight to `EmailDao.replaceMailbox`, which
 * deletes every cached row the list omits. Two of the three cases below are NOT a window — one is
 * a flag delta, one is nothing at all — and handing either to that call empties the folder on
 * screen. Handing an empty one to `NewMailNotifier.seed` is worse: it writes an EMPTY baseline, and
 * the next real fetch then announces the entire folder as new mail.
 *
 * Neither mistake throws. Both are a plain `List<EmailEntity>` that happens to be short. So the
 * three cases are made distinguishable in the type system, where the compiler asks the question at
 * every call site instead of hoping each one remembered to.
 */
sealed interface ImapFolderSync {

    /**
     * The folder's newest window as the server has it: complete, authoritative, and the only case
     * that may replace the cache or seed a notification baseline.
     */
    data class Window(val messages: List<EmailEntity>) : ImapFolderSync

    /**
     * Nothing has happened in this folder since the last sync — no arrivals, no flag changes, no
     * expunges. The cache is already correct, so the correct action is to do NOTHING with it: not
     * replace it, not prune it, not diff it for notifications.
     */
    data object Unchanged : ImapFolderSync

    /**
     * Flags changed on messages the cache already holds, and provably nothing else did (see
     * [ImapSyncDecision]). Apply to existing rows only; never treat as a listing.
     *
     * No notification pass belongs here either: UIDNEXT standing still is what licensed this
     * branch, so there is nothing new to announce by construction.
     */
    data class Flags(val changes: List<ImapFlagChange>) : ImapFolderSync
}

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
        block: (ImapSession, ImapMailboxStatus) -> T,
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
            MailServerConfig(smtp.host, smtp.port, smtp.security.toMailSecurity(), credentials.loginName, credentials.password, token),
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
    /**
     * Prove an account's IMAP credentials: connect, log in, list folders, cache nothing.
     *
     * 🔴 [SIGN_IN_BUDGET_MS] is the whole point of the parameter list here. This runs behind the
     * sign-in button with somebody watching it, and until it was given a budget it inherited the
     * default — no deadline, every socket blocking, "until the OS gives up", which on a wrong port
     * or a firewalled host is minutes. [runWithRetry] then reconnected and did it again. That is
     * the sign-in hang the store reviews are about, and it is fixed by this one argument: the
     * connect, the login, the folder list and the retry all now share one wall-clock ceiling, and
     * an expired budget arrives as a [SocketTimeoutException] the caller can name.
     */
    suspend fun testConnection(credentials: AccountCredentials) {
        withSession(credentials, budgetMs = SIGN_IN_BUDGET_MS) { it.listFolders() }
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
     * Connect, list folders, and fetch the newest [limit] of the target folder.
     *
     * Selects a folder it has just listed, so it cannot be refused on its numbering — but it is
     * usually the FIRST thing to see that numbering, and browsing a folder must be enough to
     * record it: an offline "Empty trash" afterwards has nothing else to go on, and the body
     * cache has to be dropped before the user can open a message from the list this returns.
     * Hence [reconcileNumbering] on the way out (Codeberg #99).
     */
    suspend fun loadFolder(
        credentials: AccountCredentials,
        requestedMailboxId: String?,
        limit: Int,
    ): ImapFolderLoad {
        // Read BEFORE the session: the store suspends, the session block does not, and the block
        // does not yet know which folder it will land on (it resolves the target from a LIST it
        // has not issued). One query for the account answers it whichever folder that turns out
        // to be.
        val points = uidValidity.syncPoints(credentials.id)
        var numbering: Pair<String, Long>? = null
        var observedPoint: Pair<String, ImapMailboxStatus>? = null
        val load = withSession(credentials) { session ->
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

            val status = session.select(target.path, withModSeq = true)
            numbering = target.path to status.uidValidity
            observedPoint = target.path to status
            // Still asked on every pass, including the skipped ones: it is one SEARCH against the
            // ~50 envelopes the skip avoids, and it is what the account's unread badge is set
            // from. Reporting 0 for a folder we chose not to re-read would clear that badge.
            val unread = session.unseenCount()

            ImapFolderLoad(
                mailboxes = mailboxes,
                targetMailboxId = target.path,
                targetName = target.name,
                unread = unread,
                accountName = credentials.username,
                sync = session.syncFolder(credentials.id, target.path, status, points[target.path], limit),
            )
        }
        // Before returning: a renumbering seen here drops the caches keyed by the old UIDs, so the
        // first message the user opens from this list cannot come back as a stale cached body.
        numbering?.let { (path, observed) -> reconcileNumbering(credentials.id, path, observed) }
        // AFTER the work, and only because it succeeded: see [recordSyncPoint].
        observedPoint?.let { (path, status) -> recordSyncPoint(credentials.id, path, status) }
        return load
    }

    /**
     * Sync one already-SELECTed folder the cheapest way its numbers allow (RFC 7162).
     *
     * The decision itself is [ImapSyncDecision], which has no socket in it; this is only the part
     * that issues the resulting command. The full re-read is the fallthrough for every case that
     * decision is not certain about, and it is the same call the app made before any of this
     * existed — so a server without CONDSTORE runs the identical code path it always did, having
     * paid one extra capability lookup that its own LOGIN response already answered.
     */
    private fun ImapSession.syncFolder(
        accountId: String,
        mailboxId: String,
        status: ImapMailboxStatus,
        recorded: ImapSyncPoint?,
        limit: Int,
    ): ImapFolderSync {
        val plan = ImapSyncDecision.plan(
            recorded, status.uidValidity, status.highestModSeq, status.uidNext, status.exists,
        )
        return when (plan) {
            is ImapSyncPlan.Unchanged -> ImapFolderSync.Unchanged
            is ImapSyncPlan.FlagsOnly -> ImapFolderSync.Flags(fetchFlagsChangedSince(plan.sinceModSeq))
            is ImapSyncPlan.Full -> ImapFolderSync.Window(
                fetchPage(status.exists, offset = 0, limit = limit).map { it.toEntity(accountId, mailboxId) },
            )
        }
    }

    /**
     * Remember what this sync saw, so the next one can skip the folder.
     *
     * 🔴 Called only after the fetch came back, never before it. A watermark written ahead of the
     * work would be kept even when the work then failed, and the next refresh would skip a folder
     * whose changes were never actually read — mail that silently never arrives, which is the
     * exact failure this whole feature has to be careful of.
     *
     * The UIDVALIDITY goes into the UPDATE's `WHERE`, so a folder renumbered in between writes
     * nothing and simply pays for one full re-read next time.
     */
    private suspend fun recordSyncPoint(accountId: String, mailboxId: String, status: ImapMailboxStatus) {
        if (status.highestModSeq <= 0L) return // no CONDSTORE, or NOMODSEQ on this folder
        uidValidity.recordSyncPoint(
            accountId, mailboxId, status.uidValidity, status.highestModSeq, status.uidNext, status.exists,
        )
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
        val points = uidValidity.syncPoints(credentials.id)
        val numbering = mutableListOf<Pair<String, Long>>()
        val observedPoints = mutableListOf<Pair<String, ImapMailboxStatus>>()
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
                val status = session.select(folder.path, withModSeq = true)
                numbering += folder.path to status.uidValidity
                observedPoints += folder.path to status
                ImapWatchedLoad(
                    folder.path, folder.name, folder.role,
                    session.syncFolder(credentials.id, folder.path, status, points[folder.path], limit),
                )
            }
            loads to missing
        }
        numbering.forEach { (path, observed) -> reconcileNumbering(credentials.id, path, observed) }
        observedPoints.forEach { (path, status) -> recordSyncPoint(credentials.id, path, status) }
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
            // Custom IMAP keywords, persisted since v24 — the same column and the same
            // canonicalisation the JMAP path uses, so a tag means one thing across both.
            keywordsJson = EmailKeywords.encode(keywords),
        )
    }

    private suspend fun config(endpoint: MailEndpoint, credentials: AccountCredentials) = MailServerConfig(
        host = endpoint.host,
        port = endpoint.port,
        security = endpoint.security.toMailSecurity(),
        username = credentials.loginName,
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

        /**
         * Wall-clock budget for proving credentials on the sign-in screen ([testConnection]).
         *
         * Shorter than [ENUMERATE_BUDGET_MS] because the work is smaller (connect, LOGIN, LIST) and
         * the audience is different: emptying Trash happens behind a screen that has already moved
         * on, while this one is a person holding a phone watching a spinner and deciding whether
         * the app works. A reachable server finishes it in a second or two.
         *
         * ⚠️ Inherits the same caveat as its neighbour: this bounds the connect and each read, not
         * their sum, so a peer that trickles a byte before every expiry can still outlast it. It
         * cannot outlast it indefinitely, which is the difference that matters here.
         */
        const val SIGN_IN_BUDGET_MS = 12_000

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
