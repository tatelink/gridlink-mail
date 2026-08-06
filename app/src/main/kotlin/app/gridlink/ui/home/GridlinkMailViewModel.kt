package app.gridlink.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gridlink.container
import app.gridlink.core.jmap.model.Email
import app.gridlink.core.jmap.model.EmailBodyPart
import app.gridlink.push.FetchAndNotify
import app.gridlink.ui.gridlink.GridlinkAttachment
import app.gridlink.ui.gridlink.GridlinkMailAction
import app.gridlink.ui.gridlink.GridlinkMailContent
import app.gridlink.ui.gridlink.GridlinkMailMapping
import app.gridlink.ui.gridlink.GridlinkOpenMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Real mail for the Gridlink screens: the cached inbox as a flow, and the four things a tap can ask
 * the server to do.
 *
 * ## Where the line is
 * Everything under `ui.gridlink` renders values handed to it and knows nothing about accounts, Room
 * or JMAP — that is what lets the debug gallery draw the entire app with no account and no network.
 * This class is the other side of that line and the only place in the mail path that crosses it: it
 * reads the cache, maps it with [GridlinkMailMapping], and turns [GridlinkMailAction] back into
 * repository calls. [GridlinkHomeHost] is the composable half.
 *
 * ## 🔴 Why it observes the cache and never the network
 * [MailRepository.observeMailboxWindow] is a Room query, so the list is on screen before the first
 * request goes out and stays on screen when every request fails. The network only ever writes to the
 * cache ([sync]); nothing in this class returns mail to the UI directly. That is the whole
 * offline-first arrangement upstream already has, and joining it means an aeroplane and a dead
 * server look the same to the screens: the mail you had, plus a chip that says the sync is not
 * working.
 */
class GridlinkMailViewModel(application: Application) : AndroidViewModel(application) {

    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    /** Which account's mailbox is on screen. Set by the host from the app's own routing. */
    private val accountId = MutableStateFlow<String?>(null)

    /** The body of the message the reader has open, once fetched. */
    private val opened = MutableStateFlow<GridlinkOpenMessage?>(null)

    /**
     * Whether the cache has answered at least once for the CURRENT account.
     *
     * 🔴 Not "is a sync running". A refresh over mail that is already drawn must not blank it back
     * to a skeleton, so this latches true on the first read and is only reset by switching account.
     * It is also set when a sync FINISHES having found nothing, which is the case that matters: an
     * account whose very first sync fails has an empty cache and no reason to sit under a skeleton
     * forever, waiting for a read that has already happened.
     */
    private val primed = MutableStateFlow(false)

    /** The in-flight body fetch, so opening a second message abandons the first. */
    private var openJob: Job? = null

    /**
     * Point the mailbox at [id], or leave it where it is.
     *
     * The guard is the entire method: called from a composition effect, it runs again on every
     * configuration change, and re-arming an unchanged account would drop the open message and
     * re-skeleton the list every time the phone was unfolded.
     */
    fun bind(id: String) {
        if (accountId.value == id) return
        accountId.value = id
        // Both belong to the mailbox being left. A body kept across the switch would be a message
        // from the previous account sitting open in this one, keyed by an id this account may well
        // also have (two accounts on the same server routinely share ids).
        openJob?.cancel()
        opened.value = null
        primed.value = false
    }

    /** Account id + inbox + how much of it to hold, as the cache query needs them. */
    private data class Window(val accountId: String, val mailboxId: String, val limit: Int)

    /**
     * The query to run, or null when there is not enough known to run one.
     *
     * Derived from [AccountStore.accountsFlow] rather than read once, because the inbox id is not
     * known until a refresh has reported it: a freshly created account has `inboxId == null`, and
     * this is what re-points the list at the mailbox the moment the first sync names it.
     */
    private val window: Flow<Window?> = combine(accountId, store.accountsFlow) { id, accounts ->
        val account = accounts.firstOrNull { it.id == id } ?: return@combine null
        val inbox = account.inboxId ?: return@combine null
        Window(account.id, inbox, account.syncWindow.limit)
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rows: Flow<List<Email>> = window.flatMapLatest { w ->
        if (w == null) {
            flowOf(emptyList())
        } else {
            repo.observeMailboxWindow(w.accountId, w.mailboxId, w.limit)
                .onEach { primed.value = true }
        }
    }

    /**
     * The inbox, as the Gridlink screens take it.
     *
     * ⚠️ The calendar is read at MAP time, so "Today" is whatever day it is when a row is mapped and
     * not when the app launched. A phone left open across midnight keeps yesterday's headings until
     * the next emission, which is the same behaviour every list in this app has and is why the
     * mapper takes the date rather than reading the clock itself.
     */
    val mail: StateFlow<GridlinkMailContent> = combine(rows, opened, primed) { emails, open, ready ->
        val zone = ZoneId.systemDefault()
        val mapped = GridlinkMailMapping.map(
            emails = emails,
            labels = GridlinkMailMapping.Labels(),
            zone = zone,
            today = LocalDate.now(zone),
        )
        GridlinkMailContent(
            humans = mapped.humans,
            bundle = mapped.bundle,
            loading = !ready,
            open = open,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
        // 🔴 Loading, not empty. The very first frame draws before Room has answered, and an empty
        // list there would flash "Inbox zero" at somebody with four hundred messages.
        initialValue = GridlinkMailContent(humans = emptyList(), bundle = null, loading = true),
    )

    /**
     * Fetch the account's mail, and say whether that worked.
     *
     * The boolean is the whole contract with the chrome row: true stamps "Synced just now", false
     * turns the chip amber and leaves the previous timestamp alone. Nothing about WHY it failed
     * reaches the UI, which is a real limitation (a wrong password and a flat tyre of a Wi-Fi look
     * identical) and is the next thing to fix in this seam, not something to paper over here with a
     * message no screen currently has a place for.
     */
    suspend fun sync(): Boolean {
        val id = accountId.value
        val credentials = id?.let { store.credentials(it) }
        if (id == null || credentials == null) {
            // Nothing to sync against. Latch [primed] anyway: there is no query coming, so leaving
            // it false would park the list under a skeleton with no way out.
            primed.value = true
            return false
        }
        val window = store.syncWindow(id)
        val pruneBefore = window.maxAgeDays?.let {
            System.currentTimeMillis() - it.toLong() * MILLIS_PER_DAY
        }
        return try {
            val meta = repo.refresh(
                credentials = credentials,
                // Null on a brand new account: the repository then picks the mailbox with the
                // `inbox` role, which is exactly how the id below gets learned in the first place.
                mailboxId = store.account(id)?.inboxId,
                limit = window.limit,
                pruneBeforeMillis = pruneBefore,
            )
            // Writes the inbox id back, which is what [window] above is waiting for.
            store.saveInboxMetaFor(id, meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
            // Unarchive-on-reply, if the user turned it on. A foreground refresh is the path new
            // mail arrives by while the app is open, so skipping it here would make the feature
            // work only when the app is closed. Never allowed to fail the sync.
            runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), credentials, meta.mailboxId) }
            true
        } catch (c: CancellationException) {
            // 🔴 Rethrown, not reported as a failed sync. Cancellation means the caller went away
            // (the pull gesture's scope left the composition); calling it offline would leave an
            // amber chip on a mailbox that is perfectly fine.
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "sync failed", t)
            false
        } finally {
            primed.value = true
        }
    }

    /**
     * Fetch one message's body for the reader.
     *
     * Marks it read as a side effect, because that is what opening a message means and because the
     * repository does it in the same call. 🔴 This is also why the list does NOT separately report a
     * tap as [GridlinkMailAction.MARK_READ]: two writes for one gesture would race, and the loser
     * would be an unread flag flickering back on.
     */
    fun open(emailId: String) {
        val id = accountId.value ?: return
        val credentials = store.credentials(id) ?: return
        openJob?.cancel()
        // Cleared first so the previous message's body cannot paint under the new one's header for
        // the frames before this one lands. [GridlinkOpenMessage.id] is the belt to this braces.
        opened.value = null
        openJob = viewModelScope.launch {
            val body = try {
                repo.openMessage(credentials, emailId, markRead = true)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "open failed", t)
                opened.value = GridlinkOpenMessage(
                    id = emailId,
                    html = "",
                    error = t.message ?: t.javaClass.simpleName,
                )
                return@launch
            }
            opened.value = GridlinkOpenMessage(
                id = emailId,
                html = readableBody(body.email),
                attachment = attachmentOf(body.email),
            )
        }
    }

    /**
     * Do what the list just said the user asked for.
     *
     * Fire and forget, on the view model's scope rather than the caller's: the list has already
     * animated the row out and the user may well have left the screen by the time the request
     * lands, and a write that cancelled because a screen closed would leave the mailbox disagreeing
     * with what the user watched happen.
     *
     * 🔴 [GridlinkMailAction.MOVE] and [GridlinkMailAction.UNSUBSCRIBE] do NOTHING here, loudly
     * rather than quietly: there is no folder picker and no unsubscribe request yet. The row is
     * already gone from the list at this point, so what the user sees is the message returning at
     * the next sync — which is exactly what "nothing happened" should look like. The alternative,
     * and the reason this is spelled out, is quietly archiving instead, which would be the app
     * doing something to their mail that they did not ask for and cannot see.
     */
    fun act(ids: Set<String>, action: GridlinkMailAction) {
        if (ids.isEmpty()) return
        val id = accountId.value ?: return
        val credentials = store.credentials(id) ?: return
        val targets = ids.toList()
        viewModelScope.launch {
            try {
                when (action) {
                    GridlinkMailAction.ARCHIVE -> repo.archiveAll(credentials, targets)
                    GridlinkMailAction.DELETE -> repo.deleteAll(credentials, targets)
                    GridlinkMailAction.SPAM -> repo.reportSpamAll(credentials, targets)
                    GridlinkMailAction.MARK_READ -> repo.setReadAll(credentials, targets, seen = true)
                    GridlinkMailAction.MARK_UNREAD -> repo.setReadAll(credentials, targets, seen = false)
                    GridlinkMailAction.MOVE, GridlinkMailAction.UNSUBSCRIBE ->
                        Log.w(TAG, "$action is not wired yet: ${targets.size} message(s) left untouched")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "$action failed", t)
            }
        }
    }

    private companion object {
        const val TAG = "GridlinkMail"
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /**
         * How long the cache query survives with nobody listening. Long enough to cover a rotation
         * or an unfold, so the list does not re-query and re-map on every hinge movement, and short
         * enough that a backgrounded app is not holding a Room subscription open indefinitely.
         */
        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}

/**
 * The message body, as the Gridlink thread view can actually render it.
 *
 * ## 🔴 Why the plain-text part wins
 * The thread renders through `AnnotatedString.fromHtml`, which is a rich-text mapper and not a
 * browser: no tables, no images, and (the part that bites) no `<style>` handling — the CSS inside a
 * marketing email's style block comes out as visible body text. Real HTML mail through it is not
 * "slightly off", it is unreadable. Almost every such message is `multipart/alternative` and carries
 * a text part written for exactly this situation, so that is what is used when it exists.
 *
 * ⚠️ When only HTML exists, the head, style, script and comment blocks are cut out and the rest is
 * handed over. That is a mitigation, not a renderer: a table-based newsletter will still come out as
 * a column of stacked cells. The real answer is a WebView with remote content blocked until asked
 * for, which is a privacy decision with a UI attached and belongs in its own change, exactly as the
 * note on `GridlinkThreadBody` says.
 */
internal fun readableBody(email: Email): String {
    email.textContent()?.takeIf { it.isNotBlank() }?.let { return plainToHtml(it) }
    val html = email.htmlContent()
    if (html.isNullOrBlank()) return plainToHtml(email.preview.orEmpty())
    return html
        .replace(COMMENT, "")
        .replace(NON_BODY_BLOCK, "")
}

/** Plain text as the minimal HTML the renderer needs: escaped, with the line breaks kept. */
private fun plainToHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\r\n", "\n")
    .replace("\n", "<br>")

/**
 * The one attachment the row can name.
 *
 * ⚠️ The first, and only the first: [GridlinkMessage.attachment] holds one file because the design's
 * thread view draws one chip. A message with three attachments therefore shows one and says nothing
 * about the other two, which is a real gap in the model rather than something to fix by inventing a
 * "+2" the chip has no room for.
 *
 * Parts with a Content-ID are skipped: those are the images the body references inline, and listing
 * a tracking pixel as an attachment is how a message with nothing attached grows a paperclip.
 */
private fun attachmentOf(email: Email): GridlinkAttachment? {
    val part = email.attachments.firstOrNull { it.cid.isNullOrBlank() } ?: return null
    return GridlinkAttachment(name = part.displayName(), size = formatBytes(part.size))
}

/** A file name for a part that may not have one (an inline forward, a bare `application/pdf`). */
private fun EmailBodyPart.displayName(): String =
    name?.takeIf { it.isNotBlank() } ?: type?.takeIf { it.isNotBlank() } ?: "Attachment"

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** `<head>`, `<style>` and `<script>` blocks, whose CONTENT would otherwise render as body text. */
private val NON_BODY_BLOCK = Regex("(?is)<(script|style|head)\\b[^>]*>.*?</\\1\\s*>")

private val COMMENT = Regex("(?s)<!--.*?-->")
