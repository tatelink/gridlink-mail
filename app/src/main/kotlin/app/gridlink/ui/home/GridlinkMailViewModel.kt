package app.gridlink.ui.home

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gridlink.container
import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.mail.InboxScope
import app.gridlink.core.data.mail.MailFilter
import app.gridlink.core.data.mail.ScopedInboxRow
import app.gridlink.core.data.mail.MailSearchResult
import app.gridlink.core.jmap.ContentTooLargeException
import app.gridlink.core.jmap.DownloadLimits
import app.gridlink.core.jmap.model.Email
import app.gridlink.core.jmap.model.Mailbox
import app.gridlink.core.jmap.model.EmailBodyPart
import app.gridlink.core.jmap.model.SearchQuery
import app.gridlink.mail.MessageDestroyWorker
import app.gridlink.push.FetchAndNotify
import app.gridlink.push.Notifications
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
import app.gridlink.ui.gridlink.GridlinkMessage
import app.gridlink.ui.gridlink.GridlinkRowKey
import app.gridlink.ui.gridlink.GridlinkOpenFolder
import app.gridlink.ui.gridlink.GridlinkOpenMessage
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.gridlink.GridlinkScheduledContent
import app.gridlink.ui.gridlink.GridlinkScheduledSend
import app.gridlink.ui.gridlink.GridlinkSearchContent
import app.gridlink.ui.gridlink.GridlinkUnsubscribe
import app.gridlink.ui.gridlink.gridlinkTypedRecipient
import app.gridlink.ui.gridlink.parseFormattedHtml
import app.gridlink.ui.gridlink.gridlinkUnsubscribeOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
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
     * Bumped once per page the header crawl writes into the local index, so a search already on
     * screen can fold in mail that was not indexed when it ran.
     *
     * The crawl walks newest → oldest and can take a while on a large mailbox. Without this, the
     * local half of an answer would be frozen at whatever the index happened to hold at the
     * keystroke, and older mail would only appear if the user typed another character.
     */
    private val indexTick = MutableStateFlow(0)

    /** The header crawl, kept to one at a time. See [startSearchIndexing]. */
    private var crawlJob: Job? = null

    /**
     * The conversation rows the reader has unfolded, by thread key.
     *
     * A SET, so unfolding a second conversation does not fold the first: these are rows in a list
     * being scanned, not a detail view that can only hold one thing. Cleared on account switch by
     * [bind] for the reason every other piece of list state is — a thread key belongs to the account
     * it was read in, and two accounts on one server share them.
     */
    private val expandedThreads = MutableStateFlow<Set<String>>(emptySet())

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
        // Same argument for the quick filters, one step milder: a lit chip carried across would not
        // show the wrong account's mail, it would HIDE the new one's — an inbox that opens missing
        // most of itself because of something the user did in a mailbox they have left.
        filter.value = MailFilter.none
        expandedThreads.value = emptySet()
        // A draft belongs to the account it was saved on. Carried across, the composer would open
        // over it and the eventual save would write it into the NEW account's Drafts.
        draftJob?.cancel()
        draftEdit.value = null
    }

    /**
     * Whether the list is showing every account's inbox at once, as the drawer's pair reports it.
     *
     * 🔴 The preference ANDed with "there is more than one answerable account", not the preference
     * alone. Those two disagree in a real case: turn the merge on with two accounts, remove one, and
     * the stored preference is still true while the list has collapsed back to a single account. The
     * drawer must say what the list is doing, so it reads the same rule [inboxWindow] applies.
     */
    val unified: StateFlow<Boolean> =
        combine(settings.unifiedInbox, store.accountsFlow) { merged, accounts ->
            merged && accounts.count { it.inboxId != null } > 1
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Merge every account's inbox into the list, or go back to the bound account's.
     *
     * 🔴 Clears the reader and the expanded threads, and that is not tidiness. Row keys change
     * SHAPE across this call: merged rows are account-qualified and single-account rows are bare
     * ids (see [GridlinkRowKey]). A key kept across the switch would either be a qualified key the
     * single-account list will never draw, or a bare id that the merged list resolves against
     * whichever account happens to be bound — which, with two accounts on one server sharing ids,
     * is a real message and the wrong one.
     *
     * [primed] is deliberately NOT reset. The cache has answered for these accounts already, and
     * blanking to a skeleton for the duration of a re-subscription would make a toggle look like a
     * reload.
     */
    fun setUnified(merged: Boolean) {
        openJob?.cancel()
        opened.value = null
        openedParts = emptyList()
        expandedThreads.value = emptySet()
        // On [viewModelScope] rather than the app scope: the drawer closes on the same tap but this
        // view model outlives it, and the list cannot re-subscribe against a preference that was
        // never written anyway. DataStore serialises its own writes, so a burst of taps is safe.
        viewModelScope.launch { settings.setUnifiedInbox(merged) }
    }

    /**
     * Account id + inbox + how much of it to hold + how it is narrowed, as the cache query needs
     * them.
     *
     * The filter is IN the key rather than applied to the key's results, so tapping a chip
     * re-subscribes the Room query and the narrowing happens in SQL, before the limit. See
     * [MailRepository.observeMailboxWindow].
     */
    private data class Window(
        val accountId: String,
        val mailboxId: String,
        val limit: Int,
        val filter: MailFilter = MailFilter.none,
    )

    /**
     * The same thing for the INBOX list, which can now be showing several accounts at once.
     *
     * ## Why this is a second type and not a widened [Window]
     * [Window] keys the Folders tab's query, and that tab is single-account by design: a folder tree
     * belongs to one server, and there is no such thing as "everyone's Archive". Widening the shared
     * type would have put a list of scopes in front of a query that can only ever use one of them,
     * and every read site would have had to say which. Two types, each honest about what it can hold.
     *
     * [accounts] is empty in the ordinary single-account case, and that emptiness is the switch:
     * it is what decides whether row keys get qualified ([GridlinkRowKey]) and whether rows draw an
     * account marker. Non-empty, it is `id → label` for every account in [scopes].
     */
    private data class InboxWindow(
        val scopes: List<InboxScope>,
        val accounts: Map<String, String> = emptyMap(),
        val filter: MailFilter = MailFilter.none,
    ) {
        /** The list is merging more than one account, so identity has to carry the account. */
        val unified: Boolean get() = accounts.isNotEmpty()

        /** [accountId]'s inbox in this window, or null when it has none in it. */
        fun mailboxOf(accountId: String): String? =
            scopes.firstOrNull { it.accountId == accountId }?.mailboxId

        /** The marker a row from [accountId] draws, or null when there is nothing to disambiguate. */
        fun rowAccount(accountId: String): GridlinkMailMapping.Row.Account? =
            accounts[accountId]?.let { GridlinkMailMapping.Row.Account(accountId, it) }
    }

    /**
     * The list's quick filters, as the chips above the list report them.
     *
     * Transient, and owned here only as the query's input: the chips themselves are the screen's
     * state (the sample gallery has no view model and filters its own fixtures, exactly as it does
     * for the search pill). Reset on account switch by [bind] — a filter is a way of looking at one
     * mailbox, and carrying it into another account's inbox would hide mail with a chip the user
     * lit while they were somewhere else.
     */
    private val filter = MutableStateFlow(MailFilter.none)

    /**
     * The query to run, or null when there is not enough known to run one.
     *
     * Derived from [AccountStore.accountsFlow] rather than read once, because the inbox id is not
     * known until a refresh has reported it: a freshly created account has `inboxId == null`, and
     * this is what re-points the list at the mailbox the moment the first sync names it.
     */
    private val inboxWindow: Flow<InboxWindow?> =
        combine(accountId, store.accountsFlow, filter, settings.unifiedInbox) { id, accounts, chips, merged ->
            // Accounts with no inbox id are not "excluded", they are not answerable yet: the id
            // arrives with the first sync. Left in, they would contribute an empty scope and the
            // merged list would look like that account had no mail rather than no answer.
            val usable = accounts.filter { it.inboxId != null }
            // 🔴 One usable account collapses to the ordinary single-account window even with the
            // preference on, and that is the same rule the drawer row is hidden by. A merged list
            // over one account is the same list with an account marker repeated down every row,
            // and its keys would be qualified for no reason.
            if (merged && usable.size > 1) {
                InboxWindow(
                    scopes = usable.map { InboxScope(it.id, it.inboxId.orEmpty(), it.syncWindow.limit) },
                    accounts = usable.associate { it.id to it.label() },
                    filter = chips,
                )
            } else {
                val account = usable.firstOrNull { it.id == id } ?: return@combine null
                InboxWindow(
                    scopes = listOf(InboxScope(account.id, account.inboxId.orEmpty(), account.syncWindow.limit)),
                    filter = chips,
                )
            }
        }.distinctUntilChanged()

    /**
     * The list's rows, flat or collapsed by conversation.
     *
     * 🔴 The preference is in the flatMapLatest KEY, beside the window, and not combined with the
     * result: the two modes are two different Room queries, so flipping the switch has to re-run the
     * subscription rather than re-shape rows the flat query already returned. Collapsing after the
     * fact would also collapse only within the newest `limit` MESSAGES, quietly answering a
     * narrower question than the setting asks — the same trap the quick filters are in SQL to avoid.
     *
     * Flat rows are handed on as threads of one, which is what they are, so everything downstream
     * reads one shape. See [GridlinkMailMapping.Row].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val rows: Flow<List<GridlinkMailMapping.Row>> =
        combine(inboxWindow, settings.conversationView) { w, conversations -> w to conversations }
            .distinctUntilChanged()
            .flatMapLatest { (w, conversations) ->
                when {
                    w == null -> flowOf(emptyList())
                    // 🔴 Both branches go through the UNIFIED reads even with one account in the
                    // window, where they are the single-account queries plus a wrapper. One code
                    // path, so the ordinary inbox is the case that gets exercised every launch
                    // rather than a second implementation that only runs when two accounts are
                    // merged and is therefore the one nobody notices breaking.
                    conversations -> repo.observeUnifiedThreadWindow(w.scopes, w.filter)
                        .map { rows -> rows.map { it.asRow(w) } }
                        .onEach { primed.value = true }
                    else -> repo.observeUnifiedWindow(w.scopes, w.filter)
                        .map { rows -> rows.map { it.asRow(w) } }
                        .onEach { primed.value = true }
                }
            }

    /** A merged cache row as the mapper wants it, carrying its account only when the list merges. */
    private fun ScopedInboxRow.asRow(w: InboxWindow) = GridlinkMailMapping.Row(
        email = row.email,
        threadCount = row.threadCount,
        threadUnread = row.unread,
        account = w.rowAccount(accountId),
    )

    /**
     * Narrow the inbox to unread / starred / has-attachment, or widen it again.
     *
     * Deliberately dumb, like [search]: the chips upstream own what is lit and this only ever
     * records it. 🔴 It does NOT clear [primed]. A filter is a different question about mail the
     * app already holds, not a new account, and re-skeletoning the list on every chip tap would
     * flash placeholders over a query that answers from cache within a frame.
     */
    fun filter(filter: MailFilter) {
        this.filter.value = filter
    }

    /**
     * The pill's text turned into answers, or null while the pill is empty.
     *
     * ## Two legs, unioned, and why there have to be two
     * The local index is PREFIX-matched, so "amaz" finds Amazon while the user is still typing, and
     * it answers from Room, so it answers offline and within a frame. The server is stemmed
     * whole-token full text over the entire account, so it reaches the body of a mail from two years
     * ago that this phone has never cached, in every folder including ones the drawer has never
     * opened. Neither is a superset of the other, which is why the answer is their union rather than
     * a choice between them.
     *
     * 🔴 Measured against the live Stalwart, 2026-08-11, because the shape of this whole design
     * rests on it: `text:"invoice"` → 140 hits, `text:"invoic"` → 139 (the STEM, not a prefix),
     * `text:"invo"` → 0, `from:"amaz"` → 0, and `text:"invo*"` → 0 (the `*` is tokenized away, not
     * honoured). So the server cannot do partial words at all, on any field, and asking it to is not
     * a matter of finding the right syntax. That is the exact complaint a Twake Mail reviewer left
     * against this same server ("a search for 'hap' does not bring up 'happen'"), and the local leg
     * is the only answer to it.
     *
     * ## Why the debounce is a delay inside the flow and not a `debounce()` on the query
     * The searching state has to appear on the FIRST keystroke, not after the quiet period: a pill
     * that sits inert for half a second before admitting it heard you reads as broken. So every
     * keystroke restarts this block (flatMapLatest cancels the old one, delay and all), the
     * searching emission goes out immediately, and only a pause long enough to survive the delay
     * reaches the network. One request per pause, zero per keystroke. The LOCAL leg is not
     * debounced: it costs one indexed query against Room, and holding it back would throw away the
     * only thing that can answer a keystroke at the speed the keystroke arrives.
     *
     * ## The union only ever ADDS
     * Emissions go out in the order local → local+server → local+server as the crawl grows the
     * index, and nothing is ever removed between them. A row that appeared cannot vanish under the
     * user's finger as they reach for it, which is the failure mode a "replace with the better
     * answer" design has and never shows in testing on a fast link.
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
                    // Leg 1, immediately. `searching` stays true: the server has not answered, and
                    // the screen draws rows under a true `searching` as long as it has any, so this
                    // shows results without ever letting an empty local index flash "No results"
                    // over a search that is still running.
                    var local = localSearch(q)
                    if (local.emails.isNotEmpty()) emit(answer(q, local.emails, searching = true, complete = false))
                    delay(SEARCH_DEBOUNCE_MS)
                    // Leg 2. Merged, never substituted — see the note above.
                    val server = serverSearch(q)
                    emit(merged(q, local, server))
                    // The crawl indexes the mailbox behind this, newest first. Re-run the cheap leg
                    // as it lands so older mail appears progressively rather than waiting for the
                    // next keystroke. `indexTick` is a StateFlow, so the first emission is the
                    // current value and costs one extra local query; `distinctUntilChanged` on the
                    // built answer keeps a tick that added nothing from re-emitting.
                    indexTick.collect {
                        local = localSearch(q)
                        emit(merged(q, local, server))
                    }
                }.distinctUntilChanged()
            }
        }

    /**
     * The local leg: the prefix-matched offline index, narrowed to the account on screen.
     *
     * ⚠️ The narrowing is not optional. `EmailFtsDao.search` has no account column in its query, so
     * on a phone with two accounts signed in it answers out of BOTH indexes, and the extra rows
     * would be mail this mailbox cannot open — they carry another account's ids.
     *
     * A failure is reported as an incomplete answer rather than swallowed: a locked or damaged FTS
     * table would otherwise drop every partial-word hit in silence, under a list still presenting
     * itself as the whole answer.
     */
    private suspend fun localSearch(q: String): MailSearchResult {
        val id = accountId.value ?: return MailSearchResult(emptyList(), complete = false)
        return try {
            MailSearchResult(repo.searchIndex(q, SEARCH_LIMIT).filter { it.accountId == id })
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "local search failed", t)
            MailSearchResult(emptyList(), complete = false)
        }
    }

    /** The server leg. Failures come back as a value, never a throw. */
    private suspend fun serverSearch(q: String): MailSearchResult {
        val id = accountId.value
        val credentials = id?.let { store.credentials(it) }
            ?: return MailSearchResult(emptyList(), complete = false)
        return try {
            repo.search(credentials, SearchQuery(text = q), SEARCH_LIMIT)
        } catch (c: CancellationException) {
            // The user kept typing. The replacement emission is already on its way.
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "search failed", t)
            MailSearchResult(emptyList(), complete = false)
        }
    }

    /**
     * Both legs as one answer.
     *
     * `failed` is reserved for having NOTHING to show and a reason to think that is not the truth:
     * with rows on screen the screen says "there may be more" in its footer instead, because an
     * error message over a list of real hits would be telling the user the answer they are reading
     * does not exist.
     */
    private fun merged(q: String, local: MailSearchResult, server: MailSearchResult): GridlinkSearchContent {
        // Local first so a hit both legs found keeps the local row, whose fields come from the
        // cache; then newest-first across the union, or a server hit from last year would sit above
        // a local hit from this morning inside the same day heading.
        val emails = (local.emails + server.emails)
            .distinctBy { it.id }
            .sortedByDescending { receivedMillis(it) }
            .take(SEARCH_LIMIT)
        // Both legs must have run to the end for this to be a total. A good server answer does not
        // excuse a local index that fell over, and vice versa.
        val complete = local.complete && server.complete && emails.size < SEARCH_LIMIT
        return answer(
            q = q,
            emails = emails,
            searching = false,
            complete = complete,
            failed = emails.isEmpty() && !complete,
        )
    }

    /**
     * When a hit arrived, for ordering only.
     *
     * The wire value is an ISO string, and the two legs come from two different writers, so it is
     * parsed rather than compared as text: a `+02:00` offset sorts before a `Z` of the same instant
     * on any string comparison, and both spellings are legal in the same mailbox. An unreadable or
     * missing date sorts last, matching where [GridlinkMailMapping.section] puts it.
     */
    private fun receivedMillis(email: Email): Long =
        email.receivedAt?.let { iso ->
            runCatching { Instant.parse(iso) }
                .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
                .getOrNull()
                ?.toEpochMilli()
        } ?: Long.MIN_VALUE

    /** [emails] as a search answer, each hit wearing the day heading its own date earns. */
    private fun answer(
        q: String,
        emails: List<Email>,
        searching: Boolean,
        complete: Boolean,
        failed: Boolean = false,
    ): GridlinkSearchContent {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return GridlinkSearchContent(
            query = q,
            // The section is re-stated for [folderMail]'s reason: the mapper marks no-reply
            // senders AUTOMATED for the inbox bundle, and a results list has no bundle row to
            // put them in. Every hit gets the day heading its date earns.
            results = emails.map { email ->
                GridlinkMailMapping.message(email, LABELS, zone, today)
                    .copy(section = GridlinkMailMapping.section(email, zone, today))
            },
            searching = searching,
            complete = complete,
            failed = failed,
        )
    }

    /**
     * Report what the search pill says, on every keystroke, and make sure the index the local leg
     * reads is worth reading.
     *
     * The pacing lives in the flow above, so the reporting half is deliberately dumb: no trimming,
     * no comparison, no scheduling.
     */
    fun search(query: String) {
        searchQuery.value = query
        if (query.isNotBlank()) startSearchIndexing()
    }

    /**
     * Seed the local index from the cache, then crawl the mailbox's headers into it.
     *
     * 🔴 Nothing in the Gridlink path did this before, so the local index was whatever the schema
     * 21 → 22 migration seeded on upgrade and nothing since: a search's offline half was frozen at
     * a moment months in the past, and a fresh install had no offline half at all. The seed is what
     * the mail on screen right now is worth, and the crawl is what makes a search reach past the
     * cached window, into every folder, without needing the network at the moment it is asked.
     *
     * Guarded to one crawl at a time, and cheap to call on every keystroke: the repository throttles
     * a completed crawl by its own TTL and a partial one stays retryable. Deliberately NOT cancelled
     * when the pill closes — the crawl is idempotent and killing it partway (then meeting the
     * throttle on reopen) is exactly what freezes coverage halfway through a mailbox.
     */
    private fun startSearchIndexing() {
        if (crawlJob?.isActive == true) return
        val id = accountId.value ?: return
        crawlJob = viewModelScope.launch {
            val credentials = store.credentials(id) ?: return@launch
            runCatching { repo.seedIndexFromCache() }
                .onFailure { Log.w(TAG, "index seed failed", it) }
            indexTick.value++
            runCatching {
                repo.syncSearchIndex(credentials, onPage = { indexTick.value++ })
            }.onFailure { Log.w(TAG, "index crawl failed", it) }
            indexTick.value++
        }
    }

    /**
     * Unfold or refold one conversation row.
     *
     * Idempotent in both directions and safe to call for a thread of one: the list only offers the
     * control on a row that stands for more than itself, and a key with nothing under it simply maps
     * to a one-message list.
     */
    fun toggleThread(key: String) {
        expandedThreads.value = expandedThreads.value.let { if (key in it) it - key else it + key }
    }

    /**
     * The messages under each unfolded conversation row.
     *
     * ## Cache only, and live
     * [MailRepository.observeThreadEmails] reads the same table the list does, so unfolding is
     * instant and works offline, and a message filed out of the thread leaves the unfolded rows at
     * the same moment it leaves the collapsed one. Nothing is fetched: a thread's members are in the
     * window that produced the row.
     *
     * 🔴 Scoped to the ONE open mailbox, matching the count on the row. The wider scope upstream
     * uses (the folder plus the account's Sent) would put replies you wrote under a row whose count
     * did not include them, so the row would say 3 and open 5.
     *
     * The empty-key case is handled before [combine] rather than left to it: `combine` over an empty
     * list of flows never emits, so an inbox with nothing unfolded would leave the whole [mail] flow
     * waiting on a value that could not arrive, and the list would sit under its skeleton forever.
     *
     * ⚠️ Turning conversation view off empties this rather than clearing [expandedThreads]. The
     * difference matters on the way back: the setting is a switch a reader can flick twice while
     * looking at the same list, and forgetting what they had unfolded each time it went off would
     * make the second flick lose their place for no reason the list could explain.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val threads: Flow<Map<String, List<GridlinkMessage>>> =
        combine(inboxWindow, expandedThreads, settings.conversationView) { w, keys, conversations ->
            (if (conversations) w else null) to keys
        }
            .flatMapLatest { (w, keys) ->
                // 🔴 An unfolded key is a ROW key, so in the unified inbox it names the account as
                // well as the thread. Read as a bare thread id it would be handed to whichever
                // account happens to be bound, and on one server two accounts genuinely share thread
                // ids: the row would unfold the OTHER account's conversation under it.
                val resolved = if (w == null) emptyList() else keys.mapNotNull { key ->
                    val (keyAccount, threadKey) = GridlinkRowKey.decode(key)
                    // An unqualified key was written by a single-account list, and the account it
                    // meant is the one still bound. See [GridlinkRowKey.decode].
                    val account = keyAccount ?: w.scopes.firstOrNull()?.accountId
                    val mailbox = account?.let(w::mailboxOf)
                    if (account == null || mailbox == null) null else {
                        Triple(key, account, threadKey to mailbox)
                    }
                }
                if (w == null || resolved.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        resolved.map { (key, account, thread) ->
                            val (threadKey, mailbox) = thread
                            repo.observeThreadEmails(account, listOf(mailbox), threadKey)
                                .map { emails -> Triple(key, account, emails) }
                        },
                    ) { triples ->
                        val zone = ZoneId.systemDefault()
                        val today = LocalDate.now(zone)
                        triples.associate { (key, account, emails) ->
                            // The section is re-stated for [folderMail]'s reason: an unfolded child
                            // is drawn under its parent row and never under a day heading, so the
                            // AUTOMATED marking the bundle needs must not follow it there.
                            key to emails.map { email ->
                                GridlinkMailMapping.message(
                                    email = email,
                                    labels = LABELS,
                                    zone = zone,
                                    today = today,
                                    // The children are rows too: they are selectable and swipeable,
                                    // so their keys have to be qualified exactly as their parent's is.
                                    account = w.rowAccount(account),
                                ).copy(section = GridlinkMailMapping.section(email, zone, today))
                            }
                        }
                    }
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
    val mail: StateFlow<GridlinkMailContent> =
        combine(
            // Six sources against combine's five-flow ceiling, so the first three ride together.
            // Grouped by cadence, not at random: rows/opened/primed all change on mail movement,
            // while the other three each have their own clock (a settings write, a keystroke, a
            // Drafts tap).
            combine(rows, opened, primed) { listRows, open, ready -> Triple(listRows, open, ready) },
            // Two preferences in one slot, for the same ceiling reason as the triple above. They are
            // unrelated, so the pair means nothing beyond "both are settings reads".
            combine(settings.imageAllowlist, settings.bundleAutomated) { allowed, bundling ->
                allowed to bundling
            },
            search,
            draftEdit,
            threads,
        ) { (listRows, open, ready), (allowed, bundling), found, editing, unfolded ->
            val zone = ZoneId.systemDefault()
            val mapped = GridlinkMailMapping.map(
                rows = listRows,
                labels = LABELS,
                zone = zone,
                today = LocalDate.now(zone),
                bundleAutomated = bundling,
            )
            GridlinkMailContent(
                humans = mapped.humans,
                bundle = mapped.bundle,
                loading = !ready,
                open = open,
                imageAllowlist = allowed,
                search = found,
                draftEdit = editing,
                threads = unfolded,
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

    /**
     * The mailboxes whose rows name the RECIPIENT instead of the sender.
     *
     * Sent and Drafts, by role rather than by name, so a server that calls them anything else still
     * gets it right. Ids, because that is what the open window carries.
     *
     * 🔴 Deliberately NOT folded into [folderWindow]. That window keys the Room query, and a set
     * that starts empty and fills in one frame later would change the key and re-subscribe the whole
     * mailbox query for a decision that only affects how its rows are drawn. Combined below instead,
     * where a late answer costs one re-map and nothing else.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val outgoingFolderIds: Flow<Set<String>> = accountId.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptySet())
        } else {
            repo.observeMailboxes(id).map { boxes ->
                boxes.filter { GridlinkFolderMapping.roleOf(it.role) in OUTGOING_ROLES }
                    .map { it.id }
                    .toSet()
            }
        }
    }.distinctUntilChanged()

    /** The open mailbox's cached mail, or null when no mailbox is open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val folderMail: Flow<GridlinkOpenFolder?> = folderWindow.flatMapLatest { w ->
        if (w == null) {
            flowOf(null)
        } else {
            combine(
                // 🔴 Unfiltered on purpose. The chips live above the INBOX list; the Folders tab
                // draws no chips, so a filter applied here would narrow a list with nothing on
                // screen to say it had been narrowed.
                repo.observeMailboxWindow(w.accountId, w.mailboxId, w.limit, MailFilter.none),
                folderFetched,
                outgoingFolderIds,
            ) { emails, fetched, outgoing ->
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                // A property of the open mailbox, not of any message in it: in Sent and Drafts the
                // From line is you on every row, so the row names who the mail went to instead.
                val showRecipient = w.mailboxId in outgoing
                GridlinkOpenFolder(
                    id = w.mailboxId,
                    messages = emails.map { email ->
                        // 🔴 The section is re-stated, and it has to be. [GridlinkMailMapping.message]
                        // marks anything from a no-reply address as AUTOMATED so the inbox can bundle
                        // it, and the folder list filters that section out (there is no bundle row in
                        // a folder to put it in). Left alone, opening a mailbox full of receipts would
                        // show an empty folder. Here every row gets the day heading its date earns and
                        // nothing is hidden.
                        GridlinkMailMapping.message(
                            email = email,
                            labels = LABELS,
                            zone = zone,
                            today = today,
                            showRecipient = showRecipient,
                        ).copy(section = GridlinkMailMapping.section(email, zone, today))
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

                    // 🔴 The open folder is deliberately NOT re-pointed at the new id an IMAP move
                    // returns. A move does not close the mailbox the user was reading, and the tree
                    // is about to be replaced by the server's own answer, so re-pointing here would
                    // be this class guessing at an id the refresh is already carrying. The panel
                    // empties by itself if the id really did change, which is the same behaviour a
                    // rename has had since it landed.
                    is GridlinkFolderEdit.Move ->
                        repo.moveFolder(credentials, edit.id, edit.parentId)

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
     * Permanently destroy everything in [mailboxId] — the Empty button on Trash and Junk.
     *
     * ## The order is snapshot, evict, schedule, and it is not interchangeable
     * 1. **Snapshot.** [MailRepository.snapshotTrashPurge] writes down exactly which message ids are
     *    being destroyed, asking the server for the whole folder and falling back to the cache when
     *    it cannot. The photo IS the order: [MessageDestroyWorker] destroys that list and nothing
     *    else, so mail that arrives in the folder after the tap is never swept up in it.
     * 2. **Evict.** One batched local delete, so the panel empties immediately rather than at the
     *    next sync. 🔴 It has to come SECOND: the IMAP path and the offline JMAP fallback both read
     *    the snapshot out of that very cache, and evicting first photographs an empty folder and
     *    destroys nothing.
     * 3. **Schedule.** Persisted WorkManager work, so a process death between the tap and the
     *    network does not leave a folder the user watched empty itself quietly full again.
     *
     * ⚠️ Despite the repository's name this is not Trash-only. `snapshotTrashPurge` takes a mailbox
     * id and has no opinion about its role; the two-folder rule lives in the UI
     * ([GridlinkFolderMailScreen.onEmpty]), where the button is, which is the only place it can be
     * checked against something a human looked at.
     *
     * 🔴 No hold-back and no undo, unlike upstream's `emptyTrash`. That path arms a 5-second window
     * with a snackbar behind it; this one asks first, in a dialog that says the mail is destroyed on
     * the server and cannot be brought back. Two protections for one action would mean either a
     * confirmation the undo makes redundant, or an undo bar for something the user has already
     * confirmed and moved on from. ⚠️ So this is genuinely irreversible from the moment it is
     * called: nothing downstream will ask again.
     *
     * A second tap while one is in flight is dropped rather than queued. The folder IS being
     * emptied; a second photograph of a folder already evicted would be empty anyway, and taking one
     * only risks the empty-snapshot confusion (#99) this ordering exists to avoid.
     */
    fun emptyFolder(mailboxId: String) {
        if (mailboxId.isEmpty()) return
        val id = accountId.value ?: return
        val credentials = store.credentials(id) ?: return
        val target = credentials.id to mailboxId
        if (emptyingFolder == target) return
        emptyingFolder = target
        viewModelScope.launch {
            try {
                val purge = repo.snapshotTrashPurge(credentials, mailboxId)
                repo.evictAll(credentials.id, repo.cachedIds(listOf(target)).map { it.emailId })
                // An empty photo is not an order. A folder whose rows were never cached and whose
                // server could not be asked arms no destroy at all, which is the safe failure: it
                // leaves the mail where it is rather than guessing at what to delete.
                if (purge.messageCount > 0) {
                    MessageDestroyWorker.schedulePurge(
                        getApplication(), credentials.id, mailboxId, purge.purgeId, holdBackMs = 0L,
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "empty folder failed", t)
            } finally {
                // Cleared either way. A failure that latched this would leave the button permanently
                // dead on the one folder the user is trying to clear.
                if (emptyingFolder == target) emptyingFolder = null
            }
        }
    }

    /** The (account, mailbox) currently being emptied, so a second tap is a no-op. See [emptyFolder]. */
    private var emptyingFolder: Pair<String, String>? = null

    /**
     * Fetch the mail the list is showing, and say whether that worked.
     *
     * The boolean is the whole contract with the chrome row: true stamps "Synced just now", false
     * turns the chip amber and leaves the previous timestamp alone. Nothing about WHY it failed
     * reaches the UI, which is a real limitation (a wrong password and a flat tyre of a Wi-Fi look
     * identical) and is the next thing to fix in this seam, not something to paper over here with a
     * message no screen currently has a place for.
     *
     * ## 🔴 EVERY account in the merged list, not the bound one
     * This used to sync [accountId] and nothing else, while the caller was named
     * `syncAllAccounts()` and the pull gesture's own doc promised "every account, not this folder".
     * With the unified inbox on, that is mail silently missing from a list that claims to be showing
     * it: the second account's rows are whatever was cached the last time it happened to be the
     * bound one, so a refresh — the very gesture you make BECAUSE you suspect you are not being told
     * about something — leaves it exactly as stale as it was, with no indication that half the list
     * was not asked about. Found while chasing "the inbox shows 2 of 5 messages".
     *
     * Accounts are synced CONCURRENTLY: they are separate servers as far as this is concerned, and
     * a chain would make the gesture as slow as the sum of them. The verdict is the conjunction —
     * one account that could not be reached is a list that is not fully refreshed, and saying
     * "Synced just now" over it would be the same lie in a smaller place.
     */
    suspend fun sync(): Boolean {
        // 🔴 Read from the preference and the store, NOT from [unified]: that is a
        // `WhileSubscribed` StateFlow for the drawer, so its value is only live while something is
        // collecting it, and a sync started with no subscriber (a widget refresh, a background
        // fetch) would silently take the single-account branch. Same rule [inboxWindow] applies.
        val merged = settings.unifiedInbox.first()
        val ids = store.accounts().filter { it.inboxId != null }.map { it.id }
        // The single-account list is the bound account and only it: an unbound second account's
        // failure must not turn the chip amber over a mailbox that is perfectly fine and is the
        // only one on screen.
        if (!merged || ids.size < 2) return syncAccount(accountId.value)
        return coroutineScope {
            ids.map { id -> async { syncAccount(id) } }.awaitAll().all { it }
        }
    }

    /** One account's mail. See [sync], which is what the chrome and the pull gesture call. */
    private suspend fun syncAccount(id: String?): Boolean {
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
    fun open(rowKey: String) {
        // 🔴 A ROW key, not a message id: in the unified inbox it names the account too, and the
        // account it names is the one to fetch from. Everything below keeps using the row key as the
        // open message's identity ([GridlinkOpenMessage.id]) because that is what the list compares
        // against to highlight the open row; only the calls that reach the server take [emailId].
        val (credentials, emailId) = resolve(rowKey) ?: return
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
                    id = rowKey,
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
                id = rowKey,
                html = readable.content,
                attachments = attachmentsOf(parts),
                plainText = readable.plainText,
                // 🔴 Handed over even when the body is plain text. A text part cannot reference a
                // cid:, so the map is simply unused there, and branching on it would only add a way
                // for the two to get out of step.
                inlineImages = body.inlineImages,
                // Parsed here rather than stored: the headers ride along with this fetch, and the
                // action they unlock cannot be reached without it. Null for every message that
                // carries no usable method, which takes the row out of the menu entirely.
                unsubscribe = gridlinkUnsubscribeOf(
                    body.email.listUnsubscribe,
                    body.email.listUnsubscribePost,
                ),
            )
            // The message has just been marked read, so its new-mail notification is now about
            // mail the user is looking at. See [dismissNotifications].
            dismissNotifications(credentials, listOf(emailId))
        }
    }

    /**
     * Clear the new-mail notifications for [ids] and rebuild the account's group summary.
     *
     * 🔴 This is the whole of "notifications stay up after the mail has been read". The dismissal
     * machinery has always existed (Codeberg #19) but only the UPSTREAM reader called it: the
     * Sterna message screen and its inbox list both dismiss, and neither of them is what this app
     * shows. Every route by which mail becomes read in the Gridlink UI comes through this view
     * model, and none of them told the notification manager anything, so a notification only ever
     * went away when the user swiped it off the shade themselves, or when a later sync pass
     * happened to notice the read flag ([app.gridlink.push.NewMailNotifier.notifyDiff] does clear
     * read mail, but only for a folder it is diffing at the time, so a message read while the app
     * was open sat there until the next arrival in that same folder).
     *
     * Safe to call with anything: [Notifications.dismiss] filters to ids that actually have a live
     * notification and returns without touching the summary when none do, so the common case of
     * reading already-seen mail costs one cheap read of the active list.
     *
     * Deliberately called for filing actions too, not only for read ones. Archiving or deleting a
     * message leaves a notification pointing at mail that is no longer where the notification says
     * it is, and tapping it lands the reader on a message the user just got rid of.
     */
    private fun dismissNotifications(credentials: AccountCredentials, ids: Collection<String>) {
        if (ids.isEmpty()) return
        Notifications.dismiss(getApplication(), credentials.id, credentials.username, ids)
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
    fun editDraft(rowKey: String?) {
        if (rowKey == null) {
            draftEdit.value = null
            return
        }
        // Drafts are reached through the folder list, which is single-account and hands over bare
        // ids, so this decodes to itself there. It goes through [resolve] anyway rather than
        // assuming that stays true: an unqualified key IS a bare id, so the two cost the same.
        val (credentials, emailId) = resolve(rowKey) ?: return
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
            // The marks, read back out of the app's own HTML part. [parseFormattedHtml] accepts
            // only the shape this app writes and answers null for anything else — a draft from
            // another client, a signature block, mail HTML in general — and null is not a failure:
            // it falls through to the plain text, which is exactly what happened before formatting
            // existed. Better to reopen a draft plain than to reopen a mangled guess at it.
            val formatted = email.htmlContent()
                ?.takeIf { it.isNotBlank() }
                ?.let { parseFormattedHtml(it) }
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
                body = formatted?.text ?: draftText(email),
                quoted = null,
                attachments = emptyList(),
                // What turns the eventual save into a replace and the eventual send into one that
                // retires the server copy. Without it, every resume would fork the draft.
                draftEmailId = emailId,
                bodySpans = formatted?.spans.orEmpty(),
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
     * Write the tapped attachment straight into the phone's Downloads folder.
     *
     * 🔴 The complaint this answers, verbatim from the review corpus: "it downloads attachments into
     * a black hole where you can never find them again… it should download into the system
     * 'Downloads' folder like a sane app". [openAttachment] hands the bytes to a viewer and leaves
     * them in a cache this app is free to evict; nothing survives that the file manager can see.
     *
     * ## 🔴 No document picker, by Brandon's call
     * The obvious implementation launches SAF and lets the user choose. It works, and it puts
     * Android's own DocumentsUI on screen — a white Material list in the middle of this app, which
     * cannot be themed because it belongs to another package. One tap, no foreign screen, is also
     * what the reviews are asking for: nobody complaining about a black hole wants a file browser,
     * they want the file in Downloads.
     *
     * MediaStore needs no permission on API 29+, so a second save of the same invoice lands beside
     * the first rather than over it. [saveAttachment] with an explicit destination is the pre-29
     * path, where this collection does not exist. See [writeIntoDownloads] for why that collision
     * naming cannot simply be trusted.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveAttachmentToDownloads(attachment: GridlinkAttachment) {
        val current = opened.value ?: return
        val messageId = current.id
        val part = attachment.id.toIntOrNull()?.let { openedParts.getOrNull(it) } ?: return
        val id = accountId.value ?: return
        val credentials = store.credentials(id) ?: return
        val app = getApplication<Application>()
        status(messageId, "Saving ${attachment.name}…")
        viewModelScope.launch {
            try {
                val bytes = repo.downloadAttachment(credentials, part, messageId)
                val saved = withContext(Dispatchers.IO) {
                    writeIntoDownloads(app.contentResolver, attachment.name, part.type, bytes)
                }
                status(messageId, "Saved $saved to Downloads.")
            } catch (c: CancellationException) {
                throw c
            } catch (t: ContentTooLargeException) {
                status(
                    messageId,
                    "${attachment.name} is too big to save here " +
                        "(over ${DownloadLimits.ATTACHMENT_MAX_BYTES / (1024 * 1024)} MB).",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "attachment save failed", t)
                status(messageId, "Couldn't save ${attachment.name}.")
            }
        }
    }

    /**
     * Put [bytes] in the Downloads collection under [name], or the closest free name to it, and
     * return the name it actually got.
     *
     * ## 🔴 MediaStore's collision naming is not enough on its own
     * It renames around files it knows about. It does not rename around its own stale rows, and
     * those exist on any real phone: delete a download with a tool that writes the filesystem
     * directly and the row outlives the file. The next save then inserts happily, writes every
     * byte, and blows up on `UNIQUE constraint failed: files._data` at the moment it tries to
     * publish — leaving the file on disk, permanently pending, visible to nothing. That is the same
     * black hole this whole method exists to close, wearing a different hat. Observed on the first
     * live test, so this is a fix, not a precaution.
     *
     * So a constraint failure is treated as "that name is taken": drop the row, add a counter, try
     * again. Nine tries is well past the point where the real problem is something else, and the
     * original failure is what gets thrown so the log says what actually went wrong.
     *
     * `IS_PENDING` holds from insert until the bytes are all down, so nothing indexes half a PDF,
     * and any failure deletes the row rather than abandoning it — a pending row is unaddressable
     * even to us, so leaving one behind would litter storage with files nobody can reach.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeIntoDownloads(
        resolver: ContentResolver,
        name: String,
        mime: String?,
        bytes: ByteArray,
    ): String {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var taken: Throwable? = null
        for (attempt in 0..8) {
            val candidate = when {
                attempt == 0 -> name
                extension.isEmpty() -> "$stem ($attempt)"
                else -> "$stem ($attempt).$extension"
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, candidate)
                mime?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = try {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (t: SQLiteConstraintException) {
                taken = t
                null
            } ?: continue
            try {
                resolver.openOutputStream(target)?.use { it.write(bytes) }
                    ?: error("no output stream")
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
                return candidate
            } catch (t: Throwable) {
                runCatching { resolver.delete(target, null, null) }
                if (t !is SQLiteConstraintException) throw t
                taken = t
            }
        }
        throw taken ?: IllegalStateException("Downloads rejected $name")
    }

    /**
     * Write the tapped attachment to [destination], a document the user picked themselves.
     *
     * The pre-API-29 half of [saveAttachmentToDownloads], and only that: MediaStore's Downloads
     * collection does not exist before Q, and the legacy path to the same folder wants
     * WRITE_EXTERNAL_STORAGE — a runtime permission prompt asking for the whole of shared storage
     * in order to write one file. The picker asks for nothing and grants exactly one document, so
     * on those three API levels the foreign screen is the better trade.
     *
     * The bytes are fetched AFTER the destination exists, so a cancelled picker costs no download.
     * Failure reporting is [openAttachment]'s, for the same reason: same fetch, same ceiling, same
     * message-id guard against captioning a message the user has already left.
     */
    fun saveAttachment(attachment: GridlinkAttachment, destination: Uri) {
        val current = opened.value ?: return
        val messageId = current.id
        val part = attachment.id.toIntOrNull()?.let { openedParts.getOrNull(it) } ?: return
        val id = accountId.value ?: return
        val credentials = store.credentials(id) ?: return
        val app = getApplication<Application>()
        status(messageId, "Saving ${attachment.name}…")
        viewModelScope.launch {
            try {
                val bytes = repo.downloadAttachment(credentials, part, messageId)
                withContext(Dispatchers.IO) {
                    // "wt" truncates: the picker may have handed back a file that already existed
                    // and that the user chose to overwrite, and an un-truncated write would leave
                    // the tail of the old one glued to the new.
                    app.contentResolver.openOutputStream(destination, "wt")?.use { it.write(bytes) }
                        ?: error("no output stream")
                }
                status(messageId, "Saved ${attachment.name}.")
            } catch (c: CancellationException) {
                throw c
            } catch (t: ContentTooLargeException) {
                status(
                    messageId,
                    "${attachment.name} is too big to save here " +
                        "(over ${DownloadLimits.ATTACHMENT_MAX_BYTES / (1024 * 1024)} MB).",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "attachment save failed", t)
                status(messageId, "Couldn't save ${attachment.name}.")
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
     * Row keys sorted back into the accounts that own them, each with the credentials to act with.
     *
     * ## 🔴 The one thing the unified inbox cannot get wrong
     * A selection made in a merged list can hold mail from several accounts, and a message id is
     * unique only within its own account (RFC 8620 §1.6.2). Sending the whole selection to one
     * account's credentials would not fail loudly, which is the danger: the ids of the OTHER
     * account's messages are perfectly valid ids in this one, so the server would archive somebody
     * else's mail and report success. This is the single function that stands between the merged
     * list and that outcome, which is why every action goes through it rather than reading
     * [accountId] directly.
     *
     * Unqualified keys resolve to the bound account, which is what they mean (see
     * [GridlinkRowKey.decode]) and what makes the ordinary single-account path byte-identical to
     * what it was: one group, one credential, the same call.
     *
     * An account with no usable credential is DROPPED with a log rather than folded into another
     * group. There is no honest fallback: the alternative is acting on the wrong mailbox.
     */
    /**
     * [routed] for a single row key: the credentials to act with and the id the server knows.
     *
     * Null when the account behind the key has no usable credential, which stops the caller rather
     * than letting it fall back to whatever account happens to be bound.
     */
    private fun resolve(rowKey: String): Pair<AccountCredentials, String>? {
        val (keyAccount, emailId) = GridlinkRowKey.decode(rowKey)
        val credentials = (keyAccount ?: accountId.value)?.let(store::credentials) ?: return null
        return credentials to emailId
    }

    private fun routed(keys: Collection<String>): List<Pair<AccountCredentials, List<String>>> {
        val bound = accountId.value
        return keys.map(GridlinkRowKey::decode)
            .groupBy({ it.first ?: bound }, { it.second })
            .mapNotNull { (account, ids) ->
                val credentials = account?.let(store::credentials)
                if (credentials == null) {
                    Log.w(TAG, "no credentials for account $account: ${ids.size} message(s) left untouched")
                    null
                } else {
                    credentials to ids
                }
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
     * 🔴 [GridlinkMailAction.MOVE] does NOTHING here, loudly rather than quietly. The row is already
     * gone from the list at this point, so what the user sees is the message returning at the next
     * sync — which is exactly what "nothing happened" should look like. The alternative, and the
     * reason this is spelled out, is quietly archiving instead, which would be the app doing
     * something to their mail that they did not ask for and cannot see. MOVE reaching here is a
     * caller's mistake rather than a missing feature: the selection toolbar routes moves through
     * [move], which has somewhere to put them, and this enum has no room for a destination.
     *
     * ⚠️ [GridlinkMailAction.UNSUBSCRIBE] files the message and does NOT send the request. That half
     * is [unsubscribe], which needs the method off the message's own header and so cannot be reached
     * from an enum. Both are dispatched by the same tap, and the split is deliberate: the request
     * goes to a stranger and the filing does not, so they fail separately and a sender who ignores
     * the request still does not get their mail back into the inbox.
     *
     * 🔴 Split in two for the unified inbox: a selection can now span accounts, so this fans out
     * over [routed] and the body below runs once per account with that account's own credentials.
     */
    fun act(ids: Set<String>, action: GridlinkMailAction) {
        if (ids.isEmpty()) return
        routed(ids).forEach { (credentials, targets) -> act(credentials, targets, action) }
    }

    /** [act] for one account's share of a selection. See [routed]. */
    private fun act(credentials: AccountCredentials, targets: List<String>, action: GridlinkMailAction) {
        viewModelScope.launch {
            try {
                when (action) {
                    GridlinkMailAction.ARCHIVE -> repo.archiveAll(credentials, targets)
                    GridlinkMailAction.DELETE -> repo.deleteAll(credentials, targets)
                    GridlinkMailAction.SPAM -> repo.reportSpamAll(credentials, targets)
                    GridlinkMailAction.MARK_READ -> repo.setReadAll(credentials, targets, seen = true)
                    GridlinkMailAction.MARK_UNREAD -> repo.setReadAll(credentials, targets, seen = false)
                    // ⚠️ One call per message, unlike the two above. There is no batched
                    // set-flagged on the repository the way there is for seen, because the only
                    // thing that stars mail in this app is the open thread's own button and that
                    // is always exactly one id. If a multi-select star ever arrives this wants a
                    // `setFlaggedAll` with the same Email/set batching [setReadAll] has, not a
                    // loop that fires five hundred round trips.
                    GridlinkMailAction.STAR, GridlinkMailAction.UNSTAR -> {
                        val flagged = action == GridlinkMailAction.STAR
                        targets.forEach { repo.setFlagged(credentials, it, flagged) }
                    }
                    // The filing half of an unsubscribe. Archive rather than delete: the user asked to
                    // stop receiving these, not to lose the one in front of them, and the archive is
                    // where a message they are done with belongs.
                    GridlinkMailAction.UNSUBSCRIBE -> repo.archiveAll(credentials, targets)
                    GridlinkMailAction.MOVE ->
                        Log.w(TAG, "$action is not wired yet: ${targets.size} message(s) left untouched")
                }
                // Clear the shade for the actions that make a notification wrong, and only those.
                // MARK_UNREAD is the obvious exclusion (the user is putting the message BACK into
                // the unread pile, so taking its notification away would be the opposite of what
                // they asked); starring is excluded because it says nothing about having read or
                // filed the mail. MOVE is excluded because nothing happened to the mail: the
                // branch above only logs.
                when (action) {
                    GridlinkMailAction.MARK_READ,
                    GridlinkMailAction.ARCHIVE,
                    GridlinkMailAction.DELETE,
                    GridlinkMailAction.SPAM,
                    GridlinkMailAction.UNSUBSCRIBE,
                    -> dismissNotifications(credentials, targets)
                    else -> Unit
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "$action failed", t)
            }
        }
    }

    /**
     * File [ids] into the mailbox the user picked.
     *
     * Separate from [act] for one reason: a move has a destination, and [GridlinkMailAction] is an
     * enum. 🔴 [mailboxId] is a [app.gridlink.ui.gridlink.GridlinkFolder.id], which for a real
     * account IS the JMAP mailbox id — the folder tree is built from the server's own mailbox list
     * and keeps its ids — so it can go straight to the repository without a lookup.
     *
     * Fire and forget on the view model's scope, for [act]'s reason: the rows have already animated
     * out and the user may have left the screen before the request lands.
     *
     * ⚠️ Nothing here decides where mail goes. This is only ever called after the picker has been
     * shown and a folder tapped, and if the id is empty or unknown the server refuses it, which is
     * the correct outcome — the alternative would be guessing a mailbox on the user's behalf.
     */
    fun move(ids: Set<String>, mailboxId: String) {
        if (ids.isEmpty() || mailboxId.isEmpty()) return
        val groups = routed(ids)
        if (groups.isEmpty()) return
        val home = accountId.value
        viewModelScope.launch {
            // The folder the user tapped, read once, so the other accounts have something to match
            // against. Null when the tree has not answered, which skips the cross-account leg
            // entirely rather than guessing.
            val template = home
                ?.let { runCatching { repo.observeMailboxes(it).first() }.getOrNull() }
                ?.firstOrNull { it.id == mailboxId }
            var skipped = 0
            groups.forEach { (credentials, targets) ->
                try {
                    val destination = if (credentials.id == home) {
                        mailboxId
                    } else {
                        counterpartMailbox(credentials.id, template)
                    }
                    if (destination == null) {
                        // 🔴 Skipped and COUNTED, never approximated. The alternative is filing
                        // somebody's mail into whichever folder looked closest, which is the one
                        // outcome a move must not have.
                        skipped += targets.size
                        Log.w(TAG, "no counterpart for ${template?.name} in ${credentials.id}")
                        return@forEach
                    }
                    repo.moveAllToMailbox(credentials, targets, destination)
                    // The mail is no longer where its notification says it is, and the tap intent
                    // carries the OLD mailbox id. See [dismissNotifications].
                    dismissNotifications(credentials, targets)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.w(TAG, "move failed", t)
                }
            }
            if (skipped > 0) {
                // Said out loud, because the rows have already animated out of the list and their
                // coming back at the next sync would otherwise be the only sign anything went wrong.
                Toast.makeText(
                    getApplication(),
                    "$skipped message(s) stayed put: no matching folder in their account.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * The folder in [accountId] that means what [template] means in the account it came from.
     *
     * ## 🔴 Why a mailbox id cannot simply be reused
     * A mailbox id is scoped to its account exactly as a message id is, so the id the picker handed
     * back is meaningless in anybody else's mailbox — and worse than meaningless on one server,
     * where the same id exists in both accounts and names an unrelated folder. So the destination is
     * re-resolved per account, by what the folder IS rather than by what it is called on the wire.
     *
     * Role first, because a role is the server's own statement that this is the Archive; name second,
     * for the user's own folders, which have no role and are matched case-insensitively because that
     * is how a person means "the same folder". No third guess: a folder that matches on neither is
     * not the same folder, and the caller reports it instead of picking something.
     */
    private suspend fun counterpartMailbox(accountId: String, template: Mailbox?): String? {
        if (template == null) return null
        val boxes = runCatching { repo.observeMailboxes(accountId).first() }.getOrNull() ?: return null
        val role = template.role?.takeIf { it.isNotBlank() }?.lowercase()
        return boxes.firstOrNull { role != null && it.role?.lowercase() == role }?.id
            ?: boxes.firstOrNull { it.name.equals(template.name, ignoreCase = true) }?.id
    }

    /**
     * Send the unsubscribe request itself, by whichever method the sender's header offered.
     *
     * The other half of the tap; [act] does the filing. Only ever called for a method with an
     * [GridlinkUnsubscribe.httpUrl] — the `mailto:` path never reaches here, because that one opens a
     * draft in the composer and nothing is sent until the reader presses send.
     *
     * ## 🔴 The one request this app makes to a stranger
     * Everything else it does over the network goes to the user's own mail server. This goes to
     * whoever sent the newsletter, at an address they chose, and so it is built to carry as little as
     * possible:
     *  - **A bare [HttpURLConnection], not the shared client.** No cookie jar, no interceptors, no
     *    authenticator, no connection reused from a session with anyone. A third party gets a
     *    connection that has never been anywhere.
     *  - **No `Referer`, no identifying `User-Agent` beyond the platform default,** and no body except
     *    the eleven bytes RFC 8058 specifies.
     *  - **https only,** enforced at the parse ([gridlinkUnsubscribeOf]) and again here. The token in
     *    an unsubscribe URL *is* the mailbox address; sending it in clear would hand it to the path.
     *
     * ⚠️ A one-click POST is genuinely one click: it happens on the tap, with no page and no second
     * confirmation, which is why the dialog before it says so in as many words. When the sender did
     * NOT promise one-click, this opens their page in a browser instead and stops — a POST to an
     * endpoint that never agreed to accept one is a request whose meaning nobody has defined.
     */
    fun unsubscribe(method: GridlinkUnsubscribe) {
        val url = method.httpUrl?.takeIf { it.startsWith("https:", ignoreCase = true) } ?: return
        val app = getApplication<Application>()
        if (!method.oneClick) {
            // Their page, in their browser, in a chooser for [openAttachment]'s reasons. Nothing is
            // unsubscribed by this call; the user finishes it themselves on the page.
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                // unguarded: not reachable twice. There is no composition here to hang the shared
                // leave guard on, and the confirmation dialog this is behind clears its own flag
                // BEFORE dispatching (GridlinkThreadScreen's `confirmingUnsubscribe`), so the second
                // tap of a double-tap lands on a dialog that is already gone. The other hand-off in
                // this class, [openAttachment], holds a latch instead because its tap has no dialog.
                app.startActivity(
                    Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "unsubscribe page failed to open", t)
                Toast.makeText(app, "Couldn't open the unsubscribe page.", Toast.LENGTH_LONG).show()
            }
            return
        }
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) { postOneClick(url) }
            // Said either way. A silent failure here is the worst outcome of the three: the message is
            // filed regardless, so the user would believe they had unsubscribed and only find out
            // next month.
            val text = if (sent) {
                "Unsubscribe request sent."
            } else {
                "Couldn't reach them. The message is archived, but you're still subscribed."
            }
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * POST `List-Unsubscribe=One-Click` to [url], returning whether the sender accepted it.
     *
     * Blocking; call it off the main thread. Any 2xx counts as accepted and everything else does not,
     * including a 3xx: [HttpURLConnection] follows redirects itself but refuses to cross from https to
     * http, so a code left over at this point means it was pointed somewhere this app will not go.
     */
    private fun postOneClick(url: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = ONE_CLICK_TIMEOUT_MS
                readTimeout = ONE_CLICK_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            connection.outputStream.use { it.write(ONE_CLICK_BODY.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            // Drained and closed so the socket is not left half-read. Nothing in the reply is read
            // for meaning: RFC 8058 defines no response body, and a page returned here is one this
            // app has no business rendering.
            connection.errorStream?.use { it.readBytes() } ?: connection.inputStream.use { it.readBytes() }
            code in 200..299
        } catch (t: Throwable) {
            Log.w(TAG, "one-click unsubscribe failed", t)
            false
        } finally {
            connection?.disconnect()
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

    /**
     * Put a tag on a message, or take it off, on the server as well as here.
     *
     * Separate from [act] for [move]'s reason: the enum has no room for a keyword. Fire and forget on
     * the view model's scope like every other write here, so a picker dismissed the instant it was
     * tapped does not cancel the request it just made.
     *
     * 🔴 The keyword, not the label. What travels to the server is the slug minted when the tag was
     * created ([app.gridlink.core.data.settings.MailTag.keyword]); the label is this device's word for
     * it and renaming a tag deliberately does not rewrite what is already on the mail.
     */
    fun setTag(rowKey: String, keyword: String, applied: Boolean) {
        // A row key, for [open]'s reason: this is called with the OPEN message's identity, and in
        // the unified inbox that identity carries the account the message came from.
        val (credentials, emailId) = resolve(rowKey) ?: return
        viewModelScope.launch {
            try {
                repo.setTag(credentials, emailId, keyword, applied)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "tag write failed", t)
            }
        }
    }

    private companion object {
        const val TAG = "GridlinkMail"

        /** The entire body of a one-click unsubscribe, fixed by RFC 8058 §3.1. */
        const val ONE_CLICK_BODY = "List-Unsubscribe=One-Click"

        /**
         * How long a stranger's unsubscribe endpoint gets to answer.
         *
         * Shorter than the mail server's timeouts on purpose: nothing the user is looking at depends
         * on this reply, and a list that has stopped answering is not worth holding a socket open for.
         */
        const val ONE_CLICK_TIMEOUT_MS = 15_000

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
         * The mailboxes you wrote the mail in, where the row names its recipient.
         *
         * ⚠️ Role, not name, and only these two. Junk and Trash also hold your own mail sometimes,
         * but they hold everyone else's as well, so the From line there is still the useful one.
         */
        val OUTGOING_ROLES = setOf(GridlinkFolderRole.DRAFTS, GridlinkFolderRole.SENT)

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
