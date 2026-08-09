package app.gridlink.ui.home

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gridlink.container
import app.gridlink.core.jmap.ContentTooLargeException
import app.gridlink.core.jmap.DownloadLimits
import app.gridlink.core.jmap.model.Email
import app.gridlink.core.jmap.model.EmailBodyPart
import app.gridlink.core.jmap.model.SearchQuery
import app.gridlink.push.FetchAndNotify
import app.gridlink.send.ScheduledSends
import app.gridlink.ui.gridlink.GridlinkAttachment
import app.gridlink.ui.gridlink.GridlinkComposeDraft
import app.gridlink.ui.gridlink.GridlinkFolder
import app.gridlink.ui.gridlink.GridlinkFolderContent
import app.gridlink.ui.gridlink.GridlinkFolderEdit
import app.gridlink.ui.gridlink.GridlinkFolderMapping
import app.gridlink.ui.gridlink.GridlinkFolderRole
import app.gridlink.ui.gridlink.GridlinkMailAction
import app.gridlink.ui.gridlink.GridlinkMailContent
import app.gridlink.ui.gridlink.GridlinkMailMapping
import app.gridlink.ui.gridlink.GridlinkMenuItem
import app.gridlink.ui.gridlink.GridlinkOpenFolder
import app.gridlink.ui.gridlink.GridlinkOpenMessage
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.gridlink.GridlinkScheduledContent
import app.gridlink.ui.gridlink.GridlinkScheduledSend
import app.gridlink.ui.gridlink.GridlinkSearchContent
import app.gridlink.ui.gridlink.gridlinkTypedRecipient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    /** Where a downloaded attachment lands before the viewer sees it. Upstream's cache, same cap. */
    private val storage = application.container.storageRepository

    /**
     * The same store upstream's reader writes to, deliberately.
     *
     * 🔴 Not a Gridlink-only copy of the allowlist. A sender the user trusted in one reader is a
     * sender they trusted, and two lists would mean allowing images twice and revoking them twice,
     * with the half they forgot still fetching.
     */
    private val settings = application.container.settingsRepository

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
     * The open message's downloadable parts, in the order [attachmentsOf] numbered them.
     *
     * 🔴 This list and the chips on screen are the same list or the tap opens the wrong file:
     * [GridlinkAttachment.id] is an index into THIS, so both must come from one call to
     * [attachmentsOf] on one [Email]. It is kept here because the parts are `core:jmap` types and
     * the UI is not allowed to hold them — the chip hands back its opaque id and this is what the
     * id means.
     */
    private var openedParts: List<EmailBodyPart> = emptyList()

    /** One attachment download at a time. Guards the tap, not the file: reopening later is free. */
    private var openingAttachment = false

    /** Which mailbox the Folders tab has open, reported by the scaffold. Null when nothing is. */
    private val openFolderId = MutableStateFlow<String?>(null)

    /**
     * Whether the folder table has answered at least once for the CURRENT account.
     *
     * [primed]'s counterpart for the tree, and separate from it because they are two queries that
     * answer at two different times. Shared, the Folders tab would say "0 mailboxes" until the first
     * message read came back.
     */
    private val folderPrimed = MutableStateFlow(false)

    /**
     * Mailboxes whose contents have been fetched this session, whether that worked or not.
     *
     * 🔴 This is what separates "this folder is empty" from "nobody has asked the server about this
     * folder yet", and only the second one deserves a skeleton. A folder other than the Inbox is
     * normally in the folder table with **no mail cached at all**, because the message sync only
     * ever fetches the inbox: without this, tapping Sent would say "Nothing in Sent" over a mailbox
     * nothing had looked in.
     *
     * A failed fetch counts as fetched, deliberately. The alternative is a skeleton that never
     * resolves, and the sync chip already owns saying that the network is not working.
     */
    private val folderFetched = MutableStateFlow<Set<String>>(emptySet())

    /** The in-flight fetch for the open mailbox, so tapping a second folder abandons the first. */
    private var folderJob: Job? = null

    /** What the search pill currently says, raw. Trimming and debouncing happen in the flow. */
    private val searchQuery = MutableStateFlow("")

    /**
     * A saved draft, fetched and rebuilt for the composer to resume. See
     * [GridlinkMailContent.draftEdit] for the round trip this is the answer half of.
     */
    private val draftEdit = MutableStateFlow<GridlinkComposeDraft?>(null)

    /** The in-flight draft fetch, so tapping a second Drafts row abandons the first. */
    private var draftJob: Job? = null

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
        openedParts = emptyList()
        primed.value = false
        // The folder half of the same argument. An open mailbox id belongs to the account it was
        // tapped in, and two accounts on one server routinely share mailbox ids, so carrying it
        // across would point the panel at whatever the new account happens to number the same.
        folderJob?.cancel()
        openFolderId.value = null
        folderFetched.value = emptySet()
        folderPrimed.value = false
        // A search is a question asked OF an account. Results from the old one under a pill the
        // new one owns would be mail the new mailbox cannot even open.
        searchQuery.value = ""
        // A draft belongs to the account it was saved on. Carried across, the composer would open
        // over it and the eventual save would write it into the NEW account's Drafts.
        draftJob?.cancel()
        draftEdit.value = null
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
     * The pill's text turned into server answers, or null while the pill is empty.
     *
     * ## Why the debounce is a delay inside the flow and not a `debounce()` on the query
     * The searching state has to appear on the FIRST keystroke, not after the quiet period: a pill
     * that sits inert for half a second before admitting it heard you reads as broken. So every
     * keystroke restarts this block (flatMapLatest cancels the old one, delay and all), the
     * searching emission goes out immediately, and only a pause long enough to survive the delay
     * reaches the network. One request per pause, zero per keystroke.
     *
     * 🔴 Results are NOT cached, deliberately, matching [MailRepository.search]'s own contract:
     * they are transient answers, and clearing the pill drops them. What IS kept is the invariant
     * that [GridlinkSearchContent.query] always names the text the answer belongs to, which is the
     * only thing that lets the screen refuse to draw a stale answer under newer text.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val search: Flow<GridlinkSearchContent?> = searchQuery
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.isEmpty()) {
                flowOf(null)
            } else {
                flow {
                    emit(GridlinkSearchContent(query = q, searching = true))
                    delay(SEARCH_DEBOUNCE_MS)
                    emit(runSearch(q))
                }
            }
        }

    /** One server search, mapped for the list. Failures come back as a value, never a throw. */
    private suspend fun runSearch(q: String): GridlinkSearchContent {
        val id = accountId.value
        val credentials = id?.let { store.credentials(it) }
            // No account behind the pill: nothing was looked at, and `failed` is what stops that
            // being drawn as the confident "No results".
            ?: return GridlinkSearchContent(query = q, complete = false, failed = true)
        return try {
            val hits = repo.search(credentials, SearchQuery(text = q), SEARCH_LIMIT)
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            GridlinkSearchContent(
                query = q,
                // The section is re-stated for [folderMail]'s reason: the mapper marks no-reply
                // senders AUTOMATED for the inbox bundle, and a results list has no bundle row to
                // put them in. Every hit gets the day heading its date earns.
                results = hits.emails.map { email ->
                    GridlinkMailMapping.message(email, LABELS, zone, today)
                        .copy(section = GridlinkMailMapping.section(email, zone, today))
                },
                complete = hits.complete,
            )
        } catch (c: CancellationException) {
            // The user kept typing. The replacement emission is already on its way.
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "search failed", t)
            GridlinkSearchContent(query = q, complete = false, failed = true)
        }
    }

    /**
     * Report what the search pill says, on every keystroke. The flow above owns the pacing, so
     * this is deliberately dumb: no trimming, no comparison, no scheduling.
     */
    fun search(query: String) {
        searchQuery.value = query
    }

    /**
     * The inbox, as the Gridlink screens take it.
     *
     * ⚠️ The calendar is read at MAP time, so "Today" is whatever day it is when a row is mapped and
     * not when the app launched. A phone left open across midnight keeps yesterday's headings until
     * the next emission, which is the same behaviour every list in this app has and is why the
     * mapper takes the date rather than reading the clock itself.
     */
    val mail: StateFlow<GridlinkMailContent> =
        combine(
            // Six sources against combine's five-flow ceiling, so the first three ride together.
            // Grouped by cadence, not at random: rows/opened/primed all change on mail movement,
            // while the other three each have their own clock (a settings write, a keystroke, a
            // Drafts tap).
            combine(rows, opened, primed) { emails, open, ready -> Triple(emails, open, ready) },
            settings.imageAllowlist,
            search,
            draftEdit,
        ) { (emails, open, ready), allowed, found, editing ->
            val zone = ZoneId.systemDefault()
            val mapped = GridlinkMailMapping.map(
                emails = emails,
                labels = LABELS,
                zone = zone,
                today = LocalDate.now(zone),
            )
            GridlinkMailContent(
                humans = mapped.humans,
                bundle = mapped.bundle,
                loading = !ready,
                open = open,
                imageAllowlist = allowed,
                search = found,
                draftEdit = editing,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
            // 🔴 Loading, not empty. The very first frame draws before Room has answered, and an
            // empty list there would flash "Inbox zero" at somebody with four hundred messages.
            initialValue = GridlinkMailContent(humans = emptyList(), bundle = null, loading = true),
        )

    // ---------------------------------------------------------------------------------------
    // Folders
    //
    // Two queries, not one. The tree is the folder table and the panel beside it is a window over
    // the message table, and they answer at different times: the tree is cached from the last sync
    // and is on screen instantly, while a mailbox nobody has opened before has to be fetched. Kept
    // apart so the second one's latency cannot hold up the first one's rows.
    // ---------------------------------------------------------------------------------------

    /**
     * The account's mailboxes, as the tree draws them.
     *
     * 🔴 [MailRepository.observeMailboxes] and not the raw folder table, because for a JMAP account
     * it swaps the server's stored unread counter for a live count over the cached messages. That is
     * the derived-never-declared rule the folder work runs on: the badge on a folder equals the bold
     * rows you get when you tap it, rather than a number the server last mentioned.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val folderTree: Flow<List<GridlinkFolder>> = accountId.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptyList())
        } else {
            repo.observeMailboxes(id)
                .onEach { folderPrimed.value = true }
                .map { GridlinkFolderMapping.tree(it) }
        }
    }

    /** Account id + open mailbox + how much of it to hold. [Window] with a different mailbox in it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val folderWindow: Flow<Window?> =
        combine(accountId, openFolderId, store.accountsFlow) { id, folder, accounts ->
            val account = accounts.firstOrNull { it.id == id } ?: return@combine null
            val mailboxId = folder ?: return@combine null
            Window(account.id, mailboxId, account.syncWindow.limit)
        }.distinctUntilChanged()

    /** The open mailbox's cached mail, or null when no mailbox is open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val folderMail: Flow<GridlinkOpenFolder?> = folderWindow.flatMapLatest { w ->
        if (w == null) {
            flowOf(null)
        } else {
            combine(
                repo.observeMailboxWindow(w.accountId, w.mailboxId, w.limit),
                folderFetched,
            ) { emails, fetched ->
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                GridlinkOpenFolder(
                    id = w.mailboxId,
                    messages = emails.map { email ->
                        // 🔴 The section is re-stated, and it has to be. [GridlinkMailMapping.message]
                        // marks anything from a no-reply address as AUTOMATED so the inbox can bundle
                        // it, and the folder list filters that section out (there is no bundle row in
                        // a folder to put it in). Left alone, opening a mailbox full of receipts would
                        // show an empty folder. Here every row gets the day heading its date earns and
                        // nothing is hidden.
                        GridlinkMailMapping.message(email, LABELS, zone, today)
                            .copy(section = GridlinkMailMapping.section(email, zone, today))
                    },
                    loading = emails.isEmpty() && w.mailboxId !in fetched,
                )
            }
        }
    }

    /** The Folders tab, as [app.gridlink.ui.gridlink.GridlinkRoot] takes it. */
    val folders: StateFlow<GridlinkFolderContent> =
        combine(folderTree, folderPrimed, folderMail) { tree, ready, open ->
            GridlinkFolderContent(tree = tree, loading = !ready, open = open)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
            // Loading, not empty, for the reason [mail]'s initial value is: the first frame draws
            // before Room has answered, and "0 mailboxes" there is a claim about the account.
            initialValue = GridlinkFolderContent(tree = emptyList(), loading = true),
        )

    // ---------------------------------------------------------------------------------------
    // The drawer's other two rows: Scheduled and the counts
    //
    // Scheduled sends are local rows with alarms on them, not mail (see GridlinkScheduledScreen's
    // doc), so their flow comes from the scheduled-send table and never touches the mailbox path.
    // ---------------------------------------------------------------------------------------

    /**
     * The account's waiting sends, soonest-first sorting left to the screen.
     *
     * 🔴 Filtered here because [MailRepository.scheduledSendsFlow] is deliberately unfiltered (its
     * other reader is the delivery worker, which wants everything). Unfiltered on this path, a
     * second account's queued mail would appear in the first one's Scheduled screen.
     *
     * The initial value is a real empty answer, NOT null: null tells the screen "nobody is
     * supplying" and it draws the sample, which must never happen in the live app.
     */
    val scheduled: StateFlow<GridlinkScheduledContent> =
        combine(accountId, repo.scheduledSendsFlow()) { id, sends ->
            GridlinkScheduledContent(
                items = sends.filter { it.accountId == id }.map { entity ->
                    val addresses = entity.recipients.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    GridlinkScheduledSend(
                        id = entity.id,
                        // The row has one line for "who": the first address, plus how many more.
                        to = when (addresses.size) {
                            0 -> ""
                            1 -> addresses.first()
                            else -> "${addresses.first()} +${addresses.size - 1}"
                        },
                        subject = entity.subject,
                        sendAtMillis = entity.sendAtMillis,
                    )
                },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
            initialValue = GridlinkScheduledContent(items = emptyList()),
        )

    /**
     * Stop a waiting send. Row first, worker second, the outbox undo's order and reason: a cancel
     * that half-lands leaves a worker that wakes to find no row and exits.
     */
    fun cancelScheduled(id: Long) {
        viewModelScope.launch {
            try {
                repo.deleteScheduledSend(id)
                ScheduledSends.cancel(getApplication(), id)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "scheduled cancel failed", t)
            }
        }
    }

    /**
     * Drafts total off the folder table, not a Gridlink count of cached rows: the Drafts mailbox is
     * usually unfetched (the sync only pulls the inbox), so counting cached mail would say 0 over a
     * folder with real drafts in it. [Mailbox.totalEmails] is the server's own total, refreshed on
     * every sync — total, not unread, because the drawer's noun for Drafts is "unsent".
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val draftsCount: Flow<Int> = accountId.flatMapLatest { id ->
        if (id == null) {
            flowOf(0)
        } else {
            repo.observeMailboxes(id).map { boxes ->
                boxes.firstOrNull {
                    GridlinkFolderMapping.roleOf(it.role) == GridlinkFolderRole.DRAFTS
                }?.totalEmails ?: 0
            }
        }
    }.distinctUntilChanged()

    /** The drawer's live sublines. Only the two rows that have a number to say appear as keys. */
    val menuCounts: StateFlow<Map<GridlinkMenuItem, Int>> =
        combine(draftsCount, scheduled) { drafts, waiting ->
            mapOf(
                GridlinkMenuItem.DRAFTS to drafts,
                GridlinkMenuItem.SCHEDULED to waiting.items.size,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
            initialValue = emptyMap(),
        )

    /**
     * Point the folder panel at [id], or close it when null.
     *
     * Fetches that mailbox as a side effect, because a folder other than the Inbox is normally in
     * the folder table with nothing cached: the message sync only fetches the inbox, so without this
     * every folder in the account would open onto an empty list. One fetch per open, and the guard
     * makes re-selecting the same mailbox free.
     *
     * ⚠️ Once per open, not once per look. A folder left open while the inbox pulls to refresh does
     * NOT re-fetch, so its list can be a few minutes behind the tab beside it. Closing and reopening
     * it is the current way to refresh it, which is a real gap rather than an intended behaviour.
     */
    fun openFolder(id: String?) {
        if (openFolderId.value == id) return
        openFolderId.value = id
        // Cancelled whatever the new id is: the previous fetch is for a mailbox nobody is looking at.
        folderJob?.cancel()
        if (id == null) return
        val account = accountId.value ?: return
        val credentials = store.credentials(account) ?: return
        folderJob = viewModelScope.launch {
            try {
                val window = store.syncWindow(account)
                repo.refresh(
                    credentials = credentials,
                    mailboxId = id,
                    limit = window.limit,
                    pruneBeforeMillis = window.maxAgeDays?.let {
                        System.currentTimeMillis() - it.toLong() * MILLIS_PER_DAY
                    },
                )
                // 🔴 Deliberately NOT store.saveInboxMetaFor. [sync] calls it because the mailbox it
                // fetched IS the inbox and its id has to be learned; calling it here would re-point
                // the account's inbox at whatever folder the user just tapped, and the Inbox tab
                // would quietly start showing Trash.
            } catch (c: CancellationException) {
                // Rethrown for [sync]'s reason, and with the same consequence: the fetch is NOT
                // marked done, so the mailbox still reads as unlooked-at if it is opened again.
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "folder fetch failed", t)
            }
            // Both paths, success and failure. See [folderFetched]: a skeleton that never resolves
            // is worse than an empty list on a network that is not working, and the chip says so.
            folderFetched.value = folderFetched.value + id
        }
    }

    /**
     * Create, rename or destroy a mailbox on the server.
     *
     * Fire and forget on the view model's scope, for [act]'s reason. 🔴 Nothing is written locally
     * first: all three repository calls re-read the folder list as part of the write, so the tree
     * redraws from what the server actually has. An optimistic rename that failed would otherwise
     * sit in the tree with nothing left to correct it.
     */
    fun editFolder(edit: GridlinkFolderEdit) {
        val id = accountId.value ?: return
        val credentials = store.credentials(id) ?: return
        viewModelScope.launch {
            try {
                when (edit) {
                    is GridlinkFolderEdit.Create ->
                        repo.createFolder(credentials, edit.name, edit.parentId)

                    is GridlinkFolderEdit.Rename ->
                        repo.renameFolder(credentials, edit.id, edit.name)

                    is GridlinkFolderEdit.Delete -> {
                        repo.deleteFolder(credentials, edit.id)
                        // The panel is already empty by then (its folder stopped resolving in the
                        // tree), but the id would still be pointed at a mailbox that no longer
                        // exists, so re-creating a folder with the same id would open onto a stale
                        // fetch flag. Cheap to be exact about.
                        if (openFolderId.value == edit.id) {
                            folderFetched.value = folderFetched.value - edit.id
                            openFolderId.value = null
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "folder edit failed: $edit", t)
            }
        }
    }

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
        // With the message goes its parts: an id from the old chips must not index into these.
        openedParts = emptyList()
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
            val readable = readableBody(body.email)
            // One call builds the chips AND the parts they index into. See [openedParts].
            val parts = attachmentPartsOf(body.email)
            openedParts = parts
            opened.value = GridlinkOpenMessage(
                id = emailId,
                html = readable.content,
                attachments = attachmentsOf(parts),
                plainText = readable.plainText,
                // 🔴 Handed over even when the body is plain text. A text part cannot reference a
                // cid:, so the map is simply unused there, and branching on it would only add a way
                // for the two to get out of step.
                inlineImages = body.inlineImages,
            )
        }
    }

    /**
     * Fetch a saved draft and rebuild it as something the composer can resume, or clear the answer.
     *
     * The scaffold calls this with the tapped Drafts row's id, and with null once it has opened the
     * composer over the answer — the clearing half of [GridlinkMailContent.draftEdit]'s one-shot
     * contract.
     *
     * 🔴 `markRead = false` is the whole reason Drafts taps come here instead of through [open]:
     * the fetch must not flip the user's own draft to read on the server, and [open] always does.
     *
     * ⚠️ A failed fetch is logged and the tap does nothing visible. Real gap, same one [sync] has:
     * there is no error surface on the folder list to say "couldn't load that draft", and inventing
     * one here would be a second sync chip.
     */
    fun editDraft(emailId: String?) {
        if (emailId == null) {
            draftEdit.value = null
            return
        }
        val id = accountId.value ?: return
        val credentials = store.credentials(id) ?: return
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            val body = try {
                repo.openMessage(credentials, emailId, markRead = false)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "draft fetch failed", t)
                return@launch
            }
            val email = body.email
            draftEdit.value = GridlinkComposeDraft(
                title = "Draft",
                recipients = email.to.filter { it.email.isNotBlank() }.map { address ->
                    // The composer's own typed-address builder, so a resumed recipient is
                    // indistinguishable from one typed just now — same id scheme, same rendering.
                    // The fallback is for addresses its validator would refuse (odd but real ones
                    // exist): dropping them here would silently shrink the draft on its next save.
                    gridlinkTypedRecipient(address.email) ?: GridlinkContact(
                        id = "typed:${address.email.lowercase()}",
                        given = "",
                        family = address.email,
                        role = "",
                        email = address.email,
                    )
                },
                recipientQuery = "",
                subject = email.subject.orEmpty(),
                body = draftText(email),
                quoted = null,
                attachments = emptyList(),
                // What turns the eventual save into a replace and the eventual send into one that
                // retires the server copy. Without it, every resume would fork the draft.
                draftEmailId = emailId,
            )
        }
    }

    /**
     * Download the tapped attachment and hand it to whatever on the phone can show it.
     *
     * The same journey upstream's reader makes: fetch the part (50 MB ceiling, refused before the
     * round-trip when the size is announced), park it in the bounded attachment cache, and start a
     * viewer chooser over a FileProvider uri. The app itself renders nothing — a PDF opens in the
     * phone's PDF viewer, an image in its gallery — which is what makes "most common filetypes"
     * true by construction instead of a format list this app has to maintain.
     *
     * Progress and failure go to [GridlinkOpenMessage.attachmentStatus], guarded by message id: a
     * download that outlives the message it belongs to reports to nobody, for the body fetch's
     * reason.
     */
    fun openAttachment(attachment: GridlinkAttachment) {
        if (openingAttachment) return
        val current = opened.value ?: return
        val messageId = current.id
        // The opaque id is an index into [openedParts]; anything else means a chip from a fixture
        // or another message's list, and the only correct response to that is nothing.
        val part = attachment.id.toIntOrNull()?.let { openedParts.getOrNull(it) } ?: return
        val id = accountId.value ?: return
        val credentials = store.credentials(id) ?: return
        openingAttachment = true
        val app = getApplication<Application>()
        status(messageId, "Opening ${attachment.name}…")
        viewModelScope.launch {
            try {
                val bytes = repo.downloadAttachment(credentials, part, messageId)
                val file = storage.cacheAttachment(part.name, bytes)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, part.type ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // A chooser, not a bare ACTION_VIEW: with no handler installed a bare intent throws
                // and with several the picker is the right answer anyway. The chooser shows its own
                // "no apps" sheet, so there is no failure branch to write here.
                //
                // unguarded: not a tap. The tap was handled above, where [openingAttachment] holds
                // the second one back until this hand-off is made; there is no composition here to
                // hang the shared leave guard on. Same reasoning, same latch, as the reader's own
                // openAttachment, which this was written from.
                app.startActivity(
                    Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                status(messageId, null)
            } catch (c: CancellationException) {
                throw c
            } catch (t: ContentTooLargeException) {
                // Our own refusal, said plainly. The constant is the ceiling the repository enforces.
                status(
                    messageId,
                    "${attachment.name} is too big to open here " +
                        "(over ${DownloadLimits.ATTACHMENT_MAX_BYTES / (1024 * 1024)} MB).",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "attachment open failed", t)
                status(messageId, "Couldn't open ${attachment.name}.")
            } finally {
                // Released when the chooser is up or the attempt failed, not when the user comes
                // back: the file is theirs to open again as often as they like.
                openingAttachment = false
            }
        }
    }

    /**
     * Put [text] on the open message's status line — IF the message it is about is still the one
     * open. A stale write would caption the wrong message, under the right chips, convincingly.
     */
    private fun status(messageId: String, text: String?) {
        opened.value = opened.value
            ?.takeIf { it.id == messageId }
            ?.copy(attachmentStatus = text)
            ?: opened.value
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

    /**
     * Remember, or forget, that this sender's remote images may load.
     *
     * 🔴 Lowercased here and nowhere else matters: the thread view asks whether an address is in the
     * set, and mail addresses arrive in whatever case the sender's client felt like. Writing
     * `News@Example.com` and later asking about `news@example.com` would silently never match, which
     * looks exactly like the setting not sticking.
     *
     * ⚠️ There is no per-account scoping. The allowlist says "I trust this sender", which is a fact
     * about the sender rather than about which of your mailboxes they wrote to.
     */
    fun setImagesAllowed(sender: String, allowed: Boolean) {
        val address = sender.trim().lowercase()
        if (address.isEmpty()) return
        viewModelScope.launch {
            try {
                settings.setImageAllowed(address, allowed)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "image allowlist write failed", t)
            }
        }
    }

    private companion object {
        const val TAG = "GridlinkMail"

        /**
         * The four strings a mapped row can need that the message itself does not carry.
         *
         * One instance, shared by the inbox and by every folder list. They are hard-coded English
         * defaults (see [GridlinkMailMapping.Labels] on why this package is not translated), so
         * there is nothing per-account or per-locale in them to go stale.
         */
        val LABELS = GridlinkMailMapping.Labels()
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /**
         * How long the cache query survives with nobody listening. Long enough to cover a rotation
         * or an unfold, so the list does not re-query and re-map on every hinge movement, and short
         * enough that a backgrounded app is not holding a Room subscription open indefinitely.
         */
        const val SUBSCRIPTION_GRACE_MS = 5_000L

        /**
         * How long the pill has to be quiet before a request goes out. Short enough that a search
         * feels attached to the typing, long enough that "invoice" is one request and not seven.
         */
        const val SEARCH_DEBOUNCE_MS = 400L

        /**
         * Upstream's SEARCH_LIMIT, for upstream's reason: a page the pill can show, with
         * [GridlinkSearchContent.complete] carrying whether the server had more to say.
         */
        const val SEARCH_LIMIT = 200
    }
}

/**
 * The message body, and which kind of body it is.
 *
 * ## 🔴 This used to prefer the plain-text part, and the reason is gone
 * The thread view rendered through `AnnotatedString.fromHtml`, a rich-text mapper with no `<style>`
 * handling, so a marketing email's CSS came out as visible body text and real HTML mail was not
 * "slightly off" but unreadable. Preferring the text part was the mitigation. The thread view now
 * renders in a WebView with remote content blocked (`GridlinkMessageBody`), so the mitigation is
 * worse than the thing it was mitigating: the text alternative of an HTML newsletter is usually a
 * stub telling you to view it in a browser, and half the time it is not there at all.
 *
 * So HTML wins when it exists, and it is handed over WHOLE. The `<style>` and `<head>` blocks that
 * used to be cut out are what a browser needs to lay the message out, and the CSP plus a
 * JavaScript-free WebView is what makes it safe to keep them.
 */
internal fun readableBody(email: Email): GridlinkBody {
    email.htmlContent()?.takeIf { it.isNotBlank() }?.let { return GridlinkBody(it, plainText = false) }
    email.textContent()?.takeIf { it.isNotBlank() }?.let { return GridlinkBody(it, plainText = true) }
    // Neither part came back. The preview is a snippet the list already had, which is not a body,
    // but it beats an empty panel under a subject line.
    return GridlinkBody(email.preview.orEmpty(), plainText = true)
}

/** A body and the one fact about it the renderer cannot work out for itself. */
internal data class GridlinkBody(val content: String, val plainText: Boolean)

/**
 * The draft's body as the composer's plain-text editor takes it.
 *
 * 🔴 [readableBody]'s preference INVERTED, on purpose. The reader wants HTML because its WebView
 * can lay it out; the composer is a plain-text field, so a draft this app saved (always text) comes
 * back exactly as typed, and only a draft some other client saved as HTML-only gets flattened.
 * That flatten is lossy and unavoidable here — the next save writes the flattened text — which is
 * the same trade upstream's composer makes when it reopens foreign drafts.
 */
private fun draftText(email: Email): String {
    email.textContent()?.takeIf { it.isNotBlank() }?.let { return it }
    email.htmlContent()?.takeIf { it.isNotBlank() }?.let {
        return HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    }
    return ""
}

/**
 * The message's downloadable parts, in the order their chips will draw. All of them: this held one
 * for a while because the design's thread view drew one chip, and a message with three attachments
 * showed one and said nothing about the other two.
 *
 * Parts with a Content-ID are skipped: those are the images the body references inline, and listing
 * a tracking pixel as an attachment is how a message with nothing attached grows a paperclip.
 */
private fun attachmentPartsOf(email: Email): List<EmailBodyPart> =
    email.attachments.filter { it.cid.isNullOrBlank() }

/**
 * [parts] as their chips. The id is the part's INDEX in the list it came from, which is why both
 * this and [GridlinkMailViewModel.openedParts] must be fed from the same [attachmentPartsOf] call —
 * see the note there.
 */
private fun attachmentsOf(parts: List<EmailBodyPart>): List<GridlinkAttachment> =
    parts.mapIndexed { index, part ->
        GridlinkAttachment(
            name = part.displayName(),
            size = formatBytes(part.size),
            id = index.toString(),
        )
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
