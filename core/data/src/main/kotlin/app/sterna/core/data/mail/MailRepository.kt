package app.sterna.core.data.mail

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.DiscoveredMailAccount
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.OAuthCredentials
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.SieveCodec
import app.sterna.core.data.db.AccountMailboxRole
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailBodyEntity
import app.sterna.core.data.db.OutboxAttachment
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.OutboxEdit
import app.sterna.core.data.db.OutboxEntity
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.db.SnoozedEntity
import app.sterna.core.data.db.SnoozedListRow
import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.RecentContactEntity
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.pgp.PgpEngine
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.DownloadLimits
import app.sterna.core.jmap.BearerAuth
import app.sterna.core.jmap.DeviceAuthorization
import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.OAuthMetadata
import app.sterna.core.jmap.OAuthTokens
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.JmapException
import app.sterna.core.imap.CryptoEnvelope
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.ImapUidValidityChanged
import app.sterna.core.imap.decodeMailboxPath
import app.sterna.core.imap.MimeBody
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.data.pgp.PgpDecrypted
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import app.sterna.core.jmap.model.EmailHeader
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.PushSubscription
import app.sterna.core.jmap.model.Quota
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.core.jmap.model.VacationResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.util.UUID

/**
 * Cap on changes to apply incrementally before falling back to a full query. Uncollapsed
 * folder deltas count every thread member (each reply is its own change), so the cap sits
 * at the common SyncWindow page size: a delta under it stays cheaper than the full-query
 * fallback it would otherwise trigger.
 */
private const val MAX_CHANGES = 200

/**
 * How long a locally mutated id (flag/seen change, delete, or an undo's move-back) is protected
 * from sync eviction (ms). This window must comfortably outlast the server's read-after-write
 * delay on a move-back: after an Undo we move the message back on the server and re-cache it,
 * and the next reconcile (delta OR full-query) must not prune it before the server reports it
 * home again. Local Stalwart reflects a move within ~a second, but push echoes and a periodic
 * sync can land later, so keep a generous margin.
 */
private const val RECENT_MUTATION_MS = 45_000L

/** Page size for the cached email list (rows loaded per scroll step). */
private const val PAGE_SIZE = 50

/**
 * The per-id SetError type meaning the id does not exist in the account (RFC 8620 §5.3).
 * Authoritative — unlike a transport error it can only mean the message is gone, so action
 * paths receiving it prune the cached row (a ghost/zombie) instead of keeping it forever.
 */
private const val SET_ERROR_NOT_FOUND = "notFound"

/**
 * Per-APPEND fill target for the conversation list: a network page is messages, but the
 * collapsed list's rows are threads, so one page can add almost no visible rows (a big
 * thread eating it whole). The mediator keeps fetching until at least this many NEW
 * thread representatives are cached...
 */
private const val APPEND_THREAD_TARGET = 10

/** ...but never more than this many pages per APPEND: one giant thread must not chain
 *  unbounded fetches (Paging simply APPENDs again if the viewport still isn't full). */
private const val MAX_APPEND_FILL_PAGES = 4

/** How many of the inbox's newest messages to prefetch (bodies) into the cache per sync. */
private const val PREFETCH_COUNT = 20

/** Max cached message bodies kept per account (LRU); bounds on-device storage. */
private const val BODY_CACHE_CAP = 100

/**
 * Largest body row we will write (characters of JSON, body + inline images). SQLite's cursor
 * window is 2 MB by default, and a row past it can be inserted but never read back — every
 * later read throws. Staying well under keeps the cache readable; a body over this is served
 * from the network each time instead, which is slower but always works.
 */
internal const val MAX_CACHED_BODY_CHARS = 1_000_000

/** Whether a body row of this size may be written without becoming unreadable later. */
internal fun fitsBodyCache(bodyJson: String, inlineImagesJson: String): Boolean =
    bodyJson.length + inlineImagesJson.length <= MAX_CACHED_BODY_CHARS

/**
 * Refuse an address carrying a line break before it is handed to a line-oriented protocol.
 * The SMTP envelope and the RFC 5322 headers are both built by concatenation, so a CR or LF
 * inside an address is a command/header injection primitive (a hidden `RCPT TO`, an extra
 * `Bcc:`). The composer only enables Send on well-formed addresses, but two paths bypass it:
 * a notification quick reply answers the address parsed out of a hostile `From` header, and a
 * `mailto:` link can smuggle `%0D%0A` into a single-line "To" field where the break is
 * invisible. Rejecting here means such a send fails loudly instead of silently reaching an
 * address the user never saw. The wire-level filter stays in place too (OutgoingMime).
 */
/**
 * Folders an IMAP search walks, in the order [MailboxDao.searchOrder] gives. Trash and Junk are
 * left out: a message you threw away or refused is noise in a result list, and the same walk feeds
 * the inbox magnifier, where a deleted mail surfacing unannounced reads as a bug. It also saves two
 * round trips per excluded folder on a link where each one costs.
 *
 * The role is what decides, never the name, so it holds in every language and on servers that name
 * their folders freely.
 */
internal fun searchableFolderIds(folders: List<MailboxIdRole>): List<String> =
    folders.filterNot { it.role?.trim()?.lowercase() in NOT_SEARCHED_ROLES }.map { it.id }

/**
 * The complement of [searchableFolderIds]: the account's Trash/Junk/Spam folders, kept OUT of a
 * search. IMAP excludes them by simply not walking them; JMAP searches the whole account in one
 * query and the FTS crawl indexes it whole, so both need these ids to exclude explicitly — the JMAP
 * server filter via `inMailboxOtherThan`, the crawl by not indexing them. Same role rule as
 * [searchableFolderIds] (the single [NOT_SEARCHED_ROLES] source), so the two protocols and the local
 * index can never disagree about what a search may surface.
 */
internal fun excludedSearchFolderIds(folders: List<MailboxIdRole>): List<String> =
    folders.filter { it.role?.trim()?.lowercase() in NOT_SEARCHED_ROLES }.map { it.id }

internal val NOT_SEARCHED_ROLES = setOf("trash", "junk", "spam")

/** What an action that MOVED NOTHING does with the search-index row — see [noOpEvictionFor]. */
internal enum class NoOpEviction {
    /** Drop the cached row, LEAVE the index row: the message is still in a folder a search looks at,
     *  so the row is still true and nothing would bring it back if it were taken. */
    SPARE_INDEX_ROW,

    /** Take both rows: the folder is one no search looks at, where a spared row covers nothing and
     *  no re-seed can ever clear it. */
    TAKE_INDEX_ROW,
}

/**
 * Which eviction an action that MOVED NOTHING must use, given the folder the message stayed in
 * ([mailboxId], with its [role]): the index row may stay only where a search looks.
 *
 * The index row of a message that never left a SEARCHABLE folder is still true, and taking it would
 * cost offline coverage nothing brings back — the whole point of `MailRepository.evictAlreadyThere`.
 * In Trash/Junk/Spam there is nothing to preserve: the index does not cover them at either end (the
 * crawl and the re-seed skip them, the query filters them out), so the surviving row is an orphan
 * whose message is no longer cached — and `EmailFtsDao.seedFromEmails` only rewrites rows whose
 * message IS cached, so no re-seed ever clears it. It stays hidden only while the folder cache still
 * knows that folder's role; a folder renamed or dropped server-side, or a cache reset, and the
 * message comes back in the results for good.
 *
 * A function rather than an `if` inside `evictAlreadyThere`, and a named decision rather than a
 * boolean, so the rule can be CALLED — by the caller and by its test alike. A test that has to
 * restate "and this is what that boolean means" is testing its own copy of the rule, and the copy
 * stays green when the shipped condition is inverted.
 *
 * Decided through [excludedSearchFolderIds], not against a second copy of [NOT_SEARCHED_ROLES]: same
 * function the index crawl and the search filter use, down to the trim/lowercase, so this rule
 * cannot drift from what a search actually covers. A folder the cache knows no role for spares the
 * row, which is the harmless direction (a true index row in a folder that IS searched).
 */
internal fun noOpEvictionFor(mailboxId: String, role: String?): NoOpEviction =
    if (excludedSearchFolderIds(listOf(MailboxIdRole(mailboxId, role))).isEmpty()) {
        NoOpEviction.SPARE_INDEX_ROW
    } else {
        NoOpEviction.TAKE_INDEX_ROW
    }

/**
 * Which of [ids] the cache says were ALREADY in [mailboxId] — the part of a bulk move that moved
 * nothing, told from the part that moved (`MailRepository.jmapMoveAll`).
 *
 * The server cannot be asked: an `Email/set` that files a message into the folder it is in succeeds
 * like any other, so the two are indistinguishable in the response. [rows] are the cached rows read
 * before the move, within the acting account (issue #31: an id is unique only inside its account).
 * An id with no cached row is NOT counted as a no-op — where it sat is unknown, and the conservative
 * answer is the one that takes the index row.
 */
internal fun idsAlreadyIn(rows: List<EmailEntity>, ids: Set<String>, mailboxId: String): List<String> =
    rows.filter { it.id in ids && it.mailboxId == mailboxId }.map { it.id }

/**
 * Whether a search answer may be presented as a TOTAL ("3 results") rather than as a floor
 * ("at least 3" — and never the flat "No results" that an empty answer would otherwise state as a
 * fact). Protocol-independent: IMAP walks folders, JMAP queries the account in one shot, and both
 * answer the same three questions.
 *
 * All three must hold, and the screen treats the three failures identically:
 *  - [scopeCoversAccount]: the search looked at the ACCOUNT, not at a fraction of it. A search that
 *    gathers — "flagged only" above all — would otherwise hide every star filed in a folder that was
 *    never synced. This is the same objection that kept that view out of the navigation drawer: a
 *    screen announcing "your flagged mail" while showing part of it lies.
 *  - [scanComplete]: the search ran to its end. A folder can refuse to be searched, an attachment
 *    scan can stop on its own cap.
 *  - [found] < [limit]: the answer did not fill the caller's cap, so the server had no more to give.
 *
 * ⚠ [scopeCoversAccount] is a fact about the account's FOLDER LIST, never about a list DERIVED from
 * it. "The cache told us nothing" and "the cache told us there is nothing to leave out" are two
 * different states, and an empty derived list cannot tell them apart: an account with no Trash and
 * no Junk folder has an empty exclusion list while its folder cache is perfectly populated, and
 * reading emptiness off that list would deny it a total forever. Each protocol therefore derives the
 * fact its own way and passes the ANSWER here — see [imapSearchComplete] for how the IMAP walk
 * derives it, which is not the same question as the one a whole-account query has to ask.
 *
 * Named and pure so each reason is pinned by a test on its own; inline at a call site, dropping one
 * was invisible.
 */
internal fun searchAnswerIsTotal(
    scopeCoversAccount: Boolean,
    scanComplete: Boolean,
    found: Int,
    limit: Int,
): Boolean = scopeCoversAccount && scanComplete && found < limit

/**
 * [searchAnswerIsTotal] as the IMAP walk asks it.
 *
 * IMAP derives "did we cover the account?" from [knownFolders], the searchable ids the walk was
 * BUILT from: when that list is empty the walk falls back to the inbox alone (or to nothing at all),
 * so whatever came back describes one folder rather than the account. That is the derived list on
 * purpose here — an account whose only cached folders are Trash and Junk has nothing left to walk
 * and lands on the same fallback, which the raw folder list would not show.
 */
internal fun imapSearchComplete(
    knownFolders: List<String>,
    walkComplete: Boolean,
    found: Int,
    limit: Int,
): Boolean = searchAnswerIsTotal(
    scopeCoversAccount = knownFolders.isNotEmpty(),
    scanComplete = walkComplete,
    found = found,
    limit = limit,
)

/**
 * [searchAnswerIsTotal] as a whole-account JMAP query asks it.
 *
 * JMAP never walks: one `Email/query` covers the account, and the folders it must LEAVE OUT
 * (Trash/Junk) travel with the query as `inMailboxOtherThan` ids read from the folder cache. So the
 * cache decides the scope here too, in the mirror image of the IMAP walk: with nothing cached the
 * search does not shrink to one folder, it spreads to the whole account, Trash and Junk included.
 * Either way the number on screen counts something other than what the screen says it shows — a
 * deleted message the app promised to keep out of results — so it may not be stated as a total.
 *
 * ⚠ [cachedFolders] is the RAW list from [MailboxDao.searchOrder], never the exclusion list derived
 * from it: an account with neither Trash nor Junk excludes nothing while its cache is complete, and
 * reading the emptiness off the derived list would deny that account a total forever. See the
 * warning on [searchAnswerIsTotal].
 *
 * [searchAnswerIsTotal]'s scan question is answered by the gap between the two counts a search comes
 * back with. There is no local pass to interrupt here — every criterion is evaluated server-side,
 * unlike the IMAP attachment scan that can stop on its own cap — but the answer can still be SHORT:
 * `Email/query` matches ids and `Email/get` fetches objects, and the get returns fewer when the
 * server caps it (`maxObjectsInGet`) or when a message is destroyed between the two, which travel in
 * one request but not in one instant. The crawl already draws that distinction (`CrawlPage`, and
 * [MailRepository.syncSearchIndex] paginating on its `queryCount` for exactly this reason). So
 * [fetched] < [matchedIds] means the list in hand is not the set that matched, and a null
 * [matchedIds] means the server never said how many matched — neither may be dressed as a total, and
 * the second above all: it is the one that would state "No results" as a fact.
 *
 * The cap is judged on [matchedIds] too, not on what came back: a query that matched exactly [limit]
 * ids stopped at the caller's cap even if the get then handed back one object fewer.
 */
internal fun jmapSearchComplete(
    cachedFolders: List<MailboxIdRole>,
    matchedIds: Int?,
    fetched: Int,
    limit: Int,
): Boolean = searchAnswerIsTotal(
    scopeCoversAccount = cachedFolders.isNotEmpty(),
    scanComplete = matchedIds != null && fetched >= matchedIds,
    found = matchedIds ?: fetched,
    limit = limit,
)

internal fun requireSingleLineAddresses(addresses: List<String>) {
    require(addresses.none { addr -> addr.any { it == '\r' || it == '\n' } }) {
        "An address contains a line break."
    }
}

/**
 * Read a cached row through [read]; if reading it fails at all, drop it via [purge] and report a
 * miss. An unreadable row must cost a refetch, never a message that can no longer be opened —
 * SQLite throws on reading a row past its cursor window, and it throws again on every retry.
 */
internal suspend fun <T> readCachedOrPurge(
    read: suspend () -> T?,
    purge: suspend () -> Unit,
): T? = runCatching { read() }.getOrElse {
    runCatching { purge() }
    null
}

/**
 * Run [stage] — persisting an outbox item's payload/attachments after its row is already inserted —
 * and, if it throws, [rollback] the just-inserted row before the error propagates. A staging
 * failure must leave NO row behind: an orphan (inserted with attachmentsJson "[]") would be
 * re-armed by [MailRepository.unfinishedOutbox] at the next startup and sent amputated, exactly the
 * silent loss the durable outbox exists to prevent (#70). Pure control flow, so it is unit-tested.
 */
internal suspend fun <T> stageOrRollback(rollback: suspend () -> Unit, stage: suspend () -> T): T =
    try {
        stage()
    } catch (t: Throwable) {
        rollback()
        throw t
    }

/** Max full-text search matches returned to the UI. */
private const val LOCAL_SEARCH_LIMIT = 100

// Header crawl: tiny responses, so use the max page (maxObjectsInGet=500) and a high backstop —
// headers are ~200 B in FTS, so even 200k rows is cheap and covers years-old mail. The pass stops
// naturally when the query is exhausted.
private const val HEADER_PAGE = 500
private const val HEADER_MAX = 200_000
private const val INDEX_TTL_MS = 10 * 60 * 1000L
/** Give up a crawl pass after this many consecutive page failures (vs. skipping isolated bad pages). */
private const val MAX_CRAWL_ERRORS = 3

/** Rows per INSERT when persisting an Empty-trash snapshot (keeps one statement modest). */
private const val PURGE_SNAPSHOT_INSERT_BATCH = 500

/** Page size when resolving unread ids server-side (RFC 8620 maxObjectsInGet floor). */
private const val UNREAD_RESOLVE_PAGE = 500

/** Upper bound on server-resolved unread ids for one "Mark all read" (20 pages of 500). */
private const val UNREAD_RESOLVE_MAX = 10_000

/** Ids per bulk Email/set seen update — "mark all read" (RFC 8620 maxObjectsInSet floor). */
private const val SET_SEEN_BATCH = 500

/**
 * Build the dynamic ORDER BY / WHERE for the paged list: purely the chosen [sort];
 * [unreadOnly] adds a seen filter. Mailbox ids are bound as parameters; the sort
 * expression is a fixed whitelist (never user input), so it is safe to inline.
 *
 * No order is prefixed any more. Favourites used to be pinned above ALL of them, which made
 * every menu entry a half-truth (issue #111); pinning is now its own entry,
 * [SortOrder.FLAGGED_FIRST], that the reader chooses.
 */
private fun pagingQuery(
    // The folders this list covers, as (account id, mailbox id) PAIRS — one for a single-folder
    // view, one per account for the unified inbox. See [folderScopeSql] for why never bare ids.
    scopes: List<Pair<String, String>>,
    sort: SortOrder,
    unreadOnly: Boolean,
): SimpleSQLiteQuery = SimpleSQLiteQuery(
    pagingSql(scopes.size, sort, unreadOnly),
    scopes.flatMap { listOf(it.first, it.second) }.toTypedArray(),
)

/**
 * The folder scope of a list query, for rows of [table] (a table name or an alias in scope): a
 * disjunction of (account id, mailbox id) PAIRS, `(accountId = ? AND mailboxId = ?) OR …`.
 * Bind order per scope: the ACCOUNT id, then the mailbox id.
 *
 * Pairs, never bare mailbox ids, and this is the whole of Codeberg #121. A mailbox id is assigned
 * by the server and unique only within its account, so a bare `mailboxId IN (…)` selects on the id
 * STRING alone. Two things then leak into the unified list:
 *
 *  - a sibling account's folder carrying the same id (servers number folders per account —
 *    Stalwart hands every account an Inbox "a", a Trash "b"…), listed as if it were inbox mail;
 *  - the rows of an account that no longer exists. Their mailbox id comes from the server and
 *    survives a remove-and-re-add, while the local account id is a fresh UUID, so the old rows
 *    stay inside a bare scope forever — with no account to label them (InboxScreen renders no chip
 *    for an unknown account id), no account to sync them, and no account to act on them. That is
 *    the reported symptom: a bold, unlabelled twin above the real row.
 *
 * The rest of the code already reads this way — AccountStore.allInboxScopes, the unread badge,
 * the bulk-action scopes, the Sent pairs of the chip. The list was the last bare-id reader.
 *
 * A zero-scope query selects nothing (`0`) rather than emitting invalid SQL; callers page nothing
 * in that case anyway.
 */
internal fun folderScopeSql(scopeCount: Int, table: String): String =
    if (scopeCount == 0) "0" else List(scopeCount) { "($table.accountId = ? AND $table.mailboxId = ?)" }.joinToString(" OR ")

/**
 * The flat (uncollapsed) list SQL — pure, so it is unit-tested against real SQLite like
 * [conversationSql].
 *
 * Bind order: [scopeCount] (account id, mailbox id) pairs, account id first — see [folderScopeSql].
 *
 * Both callers bind the same way, and that is deliberate: a single-folder view passes its one
 * (account, folder) pair, the unified inbox passes one pair per account. Spanning accounts is a
 * matter of HOW MANY pairs are bound, never of dropping the account from the filter.
 */
internal fun pagingSql(
    scopeCount: Int,
    sort: SortOrder,
    unreadOnly: Boolean,
): String {
    val orderBy = when (sort) {
        SortOrder.DATE_DESC -> "sortKey DESC"
        SortOrder.DATE_ASC -> "sortKey ASC"
        SortOrder.SUBJECT -> "LOWER(TRIM(subject)) ASC"
        SortOrder.SENDER -> "LOWER(TRIM(COALESCE(fromName, fromEmail))) ASC"
        SortOrder.UNREAD_FIRST -> "seen ASC, sortKey DESC"
        SortOrder.FLAGGED_FIRST -> "flagged DESC, sortKey DESC"
    }
    return "SELECT * FROM emails WHERE ${listRowsWhereSql(scopeCount, unreadOnly)} ORDER BY $orderBy"
}

/**
 * WHICH ROWS the flat list holds — the whole of it, sort excluded: the (account, folder) scope,
 * the unread filter, and the snooze filter. ONE clause, shared by the two readers that must not
 * disagree about it:
 *
 *  - [pagingSql], which draws the rows;
 *  - [selectionIdsSql], which is what "Select all" takes (Codeberg #126).
 *
 * They disagreed. The list was filtered in SQL and the selection read the folder whole, so
 * turning on "unread only", long-pressing and tapping Select all handed the bulk action every
 * READ message of the folder as well — messages that were never on screen, moved to the Trash.
 * A second predicate written to look like the first drifts from it in silence, and the drift is
 * destructive; there is only one predicate now, and a test runs both queries against the same
 * rows and asserts they answer the same ids.
 */
internal fun listRowsWhereSql(scopeCount: Int, unreadOnly: Boolean): String {
    val scope = folderScopeSql(scopeCount, "emails")
    val seenFilter = if (unreadOnly) " AND seen = 0" else ""
    // Hide messages snoozed into the future (re-appear once their time passes).
    val notSnoozed = " AND ${notSnoozedSql("emails")}"
    return "($scope)$seenFilter$notSnoozed"
}

/**
 * The rows of `MailboxDao.observeRoles`, as the map a caller judges a message's folder by (#115).
 *
 * Keyed by (account, folder) and not by folder: servers number mailboxes per account (#121/#31), so
 * one account's Sent would otherwise answer for another account's Inbox — every message in it
 * showing its recipients instead of its sender. A role-less folder is left OUT rather than mapped
 * to a blank: absent means "unknown, fall back", and a blank would mean "not outgoing", which is an
 * answer this map has no business giving.
 *
 * Its own function so a test can run it beside the statement that feeds it (`FolderRolesSqlTest`).
 */
internal fun folderRoleMap(rows: List<AccountMailboxRole>): Map<Pair<String, String>, String> =
    rows.mapNotNull { row -> row.role?.let { (row.accountId to row.id) to it } }.toMap()

/**
 * The keys "Select all" may take from the folder behind the list: the same rows [pagingSql]
 * draws, projected to (accountId, id). Bind order is [pagingQuery]'s — (account id, mailbox id)
 * per scope, account first.
 */
internal fun selectionIdsSql(scopeCount: Int, unreadOnly: Boolean): String =
    "SELECT accountId, id FROM emails WHERE ${listRowsWhereSql(scopeCount, unreadOnly)}"

internal fun selectionIdsQuery(
    scopes: List<Pair<String, String>>,
    unreadOnly: Boolean,
): SimpleSQLiteQuery = SimpleSQLiteQuery(
    selectionIdsSql(scopes.size, unreadOnly),
    scopes.flatMap { listOf(it.first, it.second) }.toTypedArray(),
)

/**
 * Build the conversation-collapsed paged query: one row per thread
 * (COALESCE(threadId, id)) showing the thread's latest message in this view, how many of the
 * thread's messages the unfolded conversation would show — the view's members plus the
 * Sent-role replies in [sentMailboxes] (the chip always equals the expansion) — and
 * whether the in-view part is unread. The account-wide cached total rides along only to keep
 * the row expandable when the rest of the thread sits elsewhere.
 * [unreadOnly] keeps threads whose in-view part is unread.
 *
 * `internal` rather than private so a test can run the ASSEMBLY — this statement and these binds,
 * in this order — and not a retyped copy of it: the bind order below is invisible to the compiler
 * and a query bound one argument off answers a different question in silence.
 */
internal fun conversationQuery(
    // The folders this list covers, as (account id, mailbox id) PAIRS — see [folderScopeSql].
    scopes: List<Pair<String, String>>,
    sort: SortOrder,
    unreadOnly: Boolean,
    // The single account a folder view is pinned to, or null for the unified list. It no longer
    // filters the rows — [scopes] carries an account per folder now — and is kept for the ONE
    // thing left that needs to know: which Sent folders belong to the view's account.
    accountId: String?,
    // Each account's Sent folder as an (accountId, mailboxId) PAIR: binding bare Sent ids
    // across accounts would let a colliding mailbox id (an account's folder whose id equals a
    // sibling's Sent id) inflate that account's chip in the unified view.
    //
    // NO DEFAULT, deliberately: omitting it compiled, and produced a chip that counted no Sent
    // reply while the unfolded list showed one — the very divergence this whole path exists to
    // prevent, reintroduced by a missing argument. Callers that mean "no Sent scope" say
    // emptyList() where a reader can see them say it.
    sentMailboxes: List<Pair<String, String>>,
): SimpleSQLiteQuery {
    // Bind order matches the clauses left-to-right in the SQL, and EVERY clause now binds the
    // same shape — (accountId, mailboxId) per scope, account first: the in-view sub-query binds
    // the scopes; the chip count sub-query binds the scopes, then (accountId, sentId) per Sent
    // pair; the outer WHERE binds like the in-view sub-query; the account-wide total sub-query
    // binds nothing (it is scoped by joining on the representative's accountId).
    // The chip's Sent scope comes from [ConversationScope] — the SAME function the unfolded list
    // gets its folders from, on the same resolution — so the number on the row and the messages
    // under it cannot describe two different conversations.
    val sent = ConversationScope.sentFolders(sentMailboxes, accountId)
    val perClause = scopes.flatMap { listOf(it.first, it.second) }
    val chipClause = perClause + sent.flatMap { listOf(it.first, it.second) }
    val args = perClause + chipClause + perClause
    return SimpleSQLiteQuery(
        conversationSql(scopes.size, sort, unreadOnly, sent.size),
        args.toTypedArray(),
    )
}

/**
 * The conversation-grouping SQL (pure, so it is unit-tested against real SQLite).
 *
 * Threads are grouped by the PAIR (accountId, thread key), never the thread key alone. Servers
 * number threads per account, so two accounts of the same server can carry the same thread id: a
 * bare `GROUP BY tkey` collapsed both accounts' conversations into one row and `sortKey = maxKey`
 * then kept only the newer one — the other account's conversation vanished from the unified list
 * altogether (data loss, not a cosmetic count). Grouping on the pair keeps one row per account, in
 * line with EmailDao.observeThreadUnreadCounts, whose badge already counted per account.
 *
 * Every folder is scoped by the PAIR (accountId, mailboxId) too, for the same family of reasons
 * and one more: an account that no longer exists (Codeberg #121). See [folderScopeSql].
 *
 * Bind order:
 * the in-view sub-query `g` takes [scopeCount] (accountId, mailboxId) pairs, account first; the
 * chip count sub-query `c` takes the same pairs, then an (accountId, mailboxId) pair per
 * [sentMailboxCount] Sent-role folder — pinned to its OWN account, so a sibling account's
 * colliding mailbox id can't widen this account's chip; the outer WHERE binds like `g`; the
 * account-wide total sub-query `t` takes none. The
 * representative row and unread state come from `g` (strictly folder-scoped — a thread with
 * only Sent members must not surface a row); `threadCount` (the chip) is `c`'s count of the
 * thread's messages in the viewed mailboxes PLUS its Sent replies, matching exactly what the
 * unfolded conversation shows; `threadTotal` is `t`'s count of its cached messages across
 * the whole account and only gates the expand affordance. Both count joins pin the
 * representative's accountId so colliding server-assigned mailbox/thread ids across accounts
 * can't inflate a count in the unified view.
 */
internal fun conversationSql(scopeCount: Int, sort: SortOrder, unreadOnly: Boolean, sentMailboxCount: Int = 0): String {
    val scope = folderScopeSql(scopeCount, "emails")
    val scopeOuter = folderScopeSql(scopeCount, "e")
    val sentAlternatives = " OR (accountId = ? AND mailboxId = ?)".repeat(sentMailboxCount)
    val notSnoozed = notSnoozedSql("emails")
    val notSnoozedOuter = notSnoozedSql("e")
    val having = if (unreadOnly) " HAVING MIN(seen) = 0" else ""
    val orderBy = when (sort) {
        SortOrder.DATE_DESC -> "e.sortKey DESC"
        SortOrder.DATE_ASC -> "e.sortKey ASC"
        SortOrder.SUBJECT -> "LOWER(TRIM(e.subject)) ASC"
        SortOrder.SENDER -> "LOWER(TRIM(COALESCE(e.fromName, e.fromEmail))) ASC"
        SortOrder.UNREAD_FIRST -> "g.threadUnread ASC, e.sortKey DESC"
        // e.flagged — the REPRESENTATIVE row's star, i.e. the one the row actually draws — and
        // deliberately not MAX(flagged) over the thread. Sorting on "any message of the thread
        // is starred" is defensible and was tried, but it sorts on a state the row does not
        // show: a thread would sit at the top wearing an empty star, and tapping that star
        // twice would not dislodge it, because an invisible older message is what holds it
        // there. That is the exact WYSIWYG break this fix exists to remove (issue #111).
        // Ordering on the drawn flag keeps "what pins it" and "what you see" the same thing.
        // (The sibling aggregate threadUnread can afford the other choice: it IS projected, so
        // UNREAD_FIRST's ordering and the row's bold text agree.)
        SortOrder.FLAGGED_FIRST -> "e.flagged DESC, e.sortKey DESC"
    }
    return """
        SELECT e.*, c.threadCount AS threadCount, t.threadTotal AS threadTotal, g.threadUnread AS threadUnread
        FROM emails e
        JOIN (
            SELECT accountId AS gacc, COALESCE(threadId, id) AS tkey, MAX(sortKey) AS maxKey, MIN(seen) AS threadUnread
            FROM emails
            WHERE ($scope) AND $notSnoozed
            GROUP BY gacc, tkey$having
        ) g ON COALESCE(e.threadId, e.id) = g.tkey AND e.accountId = g.gacc AND e.sortKey = g.maxKey
        JOIN (
            SELECT accountId AS cacc, COALESCE(threadId, id) AS ckey, COUNT(*) AS threadCount
            FROM emails
            WHERE (($scope)$sentAlternatives) AND $notSnoozed
            GROUP BY cacc, ckey
        ) c ON c.ckey = g.tkey AND c.cacc = g.gacc
        JOIN (
            SELECT accountId AS tacc, COALESCE(threadId, id) AS tkey2, COUNT(*) AS threadTotal
            FROM emails
            WHERE $notSnoozed
            GROUP BY tacc, tkey2
        ) t ON t.tkey2 = g.tkey AND t.tacc = g.gacc
        WHERE ($scopeOuter) AND $notSnoozedOuter
        GROUP BY g.gacc, g.tkey
        ORDER BY $orderBy
    """.trimIndent()
}

/**
 * The predicate that hides messages snoozed into the future, for rows of [table] (a table name or
 * an alias in scope) — they re-appear on their own once the deadline passes, and immediately once
 * the `snoozed` row goes (cancelling a snooze). One definition, shared by the flat list and the
 * conversation query, so both hide exactly the same rows and a test can exercise the real thing.
 *
 * Correlated on accountId as well as the email id: snoozes are keyed per account (issue #31), so
 * one account snoozing an id must not hide a same-id message of a sibling account sharing the
 * server.
 */
internal fun notSnoozedSql(table: String): String =
    "NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = $table.id " +
        "AND snoozed.accountId = $table.accountId AND snoozed.until > " +
        "(CAST(strftime('%s','now') AS INTEGER) * 1000))"

/**
 * One row in the paged list. In flat mode it's a single email ([threadCount] == 1);
 * in conversation mode it's a collapsed thread whose representative is [email],
 * [threadCount] messages in this view plus its Sent replies (exactly what the unfolded
 * conversation shows), [unread] if any in-view member is unread. [threadExpandable]
 * marks a thread with 2+ cached messages account-wide: the row can still unfold when
 * the others (a Sent reply, say) sit outside this view.
 */
data class InboxRow(
    val email: Email,
    val threadCount: Int,
    val unread: Boolean,
    val threadExpandable: Boolean = threadCount > 1,
)

/**
 * An account-qualified message key. Same-server accounts can cache COLLIDING email ids (servers
 * like Stalwart number objects per account), and the unified inbox shows both rows side by side —
 * so anything that identifies a message across accounts (list keys, the multi-select set, bulk-
 * action routing) must carry the account too, or an action on id X hits both accounts' X.
 * [accountId] is null only for an email that never came from the cache (single-account fallback).
 */
data class EmailKey(val accountId: String?, val emailId: String)

/** The [EmailKey] of an email as the cache/UI sees it. */
fun Email.emailKey(): EmailKey = EmailKey(accountId, id)

/** Metadata about the selected mailbox after a refresh. */
data class MailboxMeta(
    val accountName: String,
    val mailboxId: String,
    val mailboxName: String,
    val unreadCount: Int,
)

/** Result of refreshing one account's inbox during a unified-inbox fan-out. */
data class AccountInboxMeta(
    val accountId: String,
    val accountName: String,
    val mailboxId: String,
    val mailboxName: String,
    val unreadCount: Int,
)

/**
 * Outcome of a unified refresh across every account (#65/#92): the inboxes that synced, plus the
 * per-account failures so the caller can tell "nothing came back because I am offline" from "one
 * account is unreachable but the rest are fine". A per-account failure is no longer swallowed
 * without trace — it lands here and is logged.
 */
data class UnifiedRefreshResult(
    val metas: List<AccountInboxMeta>,
    val failures: List<Throwable>,
) {
    /**
     * A connectivity failure only when at least one account failed and NONE synced: one good
     * account proves the link is up, so it must NOT read as offline (that would flash the banner
     * for a single unreachable server). All accounts failing is the VPN-killswitch case the #65
     * offline banner exists for — the framework still reports a healthy network, so the requests
     * dying is the only signal there is.
     */
    val isConnectivityFailure: Boolean get() = metas.isEmpty() && failures.isNotEmpty()
}

/** One watched folder refreshed during a push fan-out (multi-folder push, issue #16). */
data class FolderRefresh(
    val mailboxId: String,
    val name: String,
    val role: String?,
    val emails: List<Email>,
)

/** Outcome of loading an account's server-side filter rules. */
sealed interface FilterRulesState {
    /** No Sieve support (IMAP account, or capability absent). */
    data object Unsupported : FilterRulesState
    data class Loaded(
        val rules: List<FilterRule>,
        /** True if another script (not Sterna's) is the active one — saving will take over. */
        val foreignActiveScript: Boolean = false,
    ) : FilterRulesState
}

/** Outcome of loading an account's server-side vacation responder. */
sealed interface VacationState {
    /** The account's server has no vacation-responder support (IMAP, or capability absent). */
    data object Unsupported : VacationState
    data class Loaded(val response: VacationResponse) : VacationState
}

/**
 * Offline-first mail access: the UI observes cached mailboxes/emails from Room,
 * while the network methods fetch over JMAP and update the cache. A session +
 * mailbox-role map is cached in memory so actions don't re-discover them.
 */
/** A message body ready to render: the full [Email] plus its inline images (cid → data: URI). */
data class MessageBody(
    val email: Email,
    val inlineImages: Map<String, String>,
    /** OpenPGP state, or null for ordinary mail. */
    val crypto: MessageCrypto? = null,
)

/** OpenPGP state of an opened message. */
sealed interface MessageCrypto {
    /** Crypto content detected but not yet decrypted/verified. */
    data class Locked(val kind: CryptoKind) : MessageCrypto

    /** Decrypted and/or signature-verified; plaintext lives only in memory. */
    data class Decrypted(
        val signature: PgpSignatureState,
        val signatureUserId: String?,
        val signatureKeyId: Long,
        val wasEncrypted: Boolean,
    ) : MessageCrypto
}

/** A device-flow sign-in was refused, cancelled, or expired. [failure] carries the parsed error and
 *  (for Microsoft) the AADSTS code, so the UI can map it to a specific, actionable message. */
class OAuthDeniedException(val failure: DeviceTokenResult.Failed) :
    Exception(failure.description.ifBlank { failure.error })

class MailRepository(
    private val client: JmapClient,
    private val emailDao: EmailDao,
    private val emailFtsDao: EmailFtsDao,
    private val emailBodyDao: EmailBodyDao,
    private val mailboxDao: MailboxDao,
    private val imap: ImapMailService,
    private val scheduledSendDao: ScheduledSendDao,
    private val snoozedDao: SnoozedDao,
    private val recentContactDao: RecentContactDao,
    private val accountStore: AccountStore,
    private val outboxDao: OutboxDao,
    /** The frozen destroy list of a confirmed Empty trash (#99). */
    private val purgeSnapshotDao: PurgeSnapshotDao,
    private val outboxFilesDir: java.io.File,
    private val oauthClient: OAuthClient = OAuthClient(),
    /** OpenPGP operations (null = no provider wired; all PGP features disabled). */
    private val pgpEngine: PgpEngine? = null,
    /** App settings (null in tests): consulted for behavior toggles like mark-read-on-delete. */
    private val settings: SettingsRepository? = null,
    /** Persisted sync cursors (null in tests): deltas survive process death (issue #17). */
    private val syncStateStore: SyncStateStore? = null,
) {
    /**
     * Schedules the WorkManager job that delivers an outbox item. Set by the app layer at
     * startup (the data module can't reference the worker), so [enqueueSend] can arm delivery
     * from any caller, including ones inside this module (e.g. an RSVP reply).
     */
    var outboxScheduler: OutboxScheduler? = null

    /**
     * App-layer teardown for a linked sub-account pruned on reconcile (access revoked, issue #31):
     * clears its notification baselines like a sign-out would. Set by the app layer at startup —
     * the data module cannot reach the notifier itself.
     */
    var onAccountPruned: ((String) -> Unit)? = null
    private class Context(
        val credentials: AccountCredentials,
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val rolesToMailboxId: Map<String, String>,
        // This account's own mailboxes, so folder lookups (e.g. Archive by name when the
        // server set no role) stay scoped to THIS account — never the global mailbox cache,
        // which holds only the last-synced account and is wrong for a non-current account
        // archived from the unified inbox.
        val mailboxes: List<Mailbox> = emptyList(),
    )

    @Volatile
    private var context: Context? = null

    /**
     * App-layer teardown for an IMAP folder the server has renumbered (Codeberg #99), by
     * `(accountId, mailboxId)`: the data layer drops what it owns (cached bodies, pending destroy
     * lists), and this clears the notification baseline, which lives in `:app` and cannot be
     * reached from here. Without it the next push pass diffs the folder's new ids against the old
     * baseline and announces mail the user has already read. Set by the app layer at startup,
     * exactly like [onAccountPruned].
     */
    var onMailboxRenumbered: ((String, String) -> Unit)?
        get() = imap.onMailboxRenumbered
        set(value) { imap.onMailboxRenumbered = value }

    private val tokenRefresher = OAuthTokenRefresher(oauthClient, accountStore)

    /** Background scope for fire-and-forget work (body prefetch) that must outlive a sync call. */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tolerant JSON for the on-disk body cache (schema may add fields across versions). */
    private val cacheJson = Json { ignoreUnknownKeys = true }
    private val inlineImagesSerializer = MapSerializer(String.serializer(), String.serializer())

    /** Where an IMAP message was moved (for undo): emailId → (destination folder, new UID). */
    private class ImapLoc(val mailboxId: String, val uid: Long)
    private val lastImapMove = java.util.concurrent.ConcurrentHashMap<String, ImapLoc>()

    /**
     * Per-mailbox JMAP state for incremental sync. In-memory map with write-through to
     * [syncStateStore] (when wired) so cursors survive process death — vital once pushes
     * wake a dead process (issue #17); a cold start then still runs a cheap delta.
     */
    private data class SyncState(val queryState: String, val emailState: String)
    private val syncStates = java.util.concurrent.ConcurrentHashMap<String, SyncState>()

    private fun putSyncState(key: String, state: SyncState) {
        syncStates[key] = state
        syncStateStore?.save(key, state.queryState, state.emailState)
    }

    private fun dropSyncState(key: String) {
        syncStates.remove(key)
        syncStateStore?.remove(key)
    }

    private fun loadSyncState(key: String): SyncState? =
        syncStates[key]
            ?: syncStateStore?.load(key)
                ?.let { (queryState, emailState) -> SyncState(queryState, emailState) }
                ?.also { syncStates[key] = it }

    /**
     * After a local mutation (flag/move/delete) the server returns the new `Email/set`
     * state. Advancing the affected mailboxes' stored emailState to it means the next
     * `Email/changes` — including the push that echoes this very action back — no
     * longer re-reports our own change, so the optimistic cache write isn't reverted
     * (no flicker). [newState] null (e.g. IMAP) is a no-op.
     */
    private fun advanceEmailState(newState: String?, accountId: String, vararg mailboxIds: String?) {
        val s = newState ?: return
        mailboxIds.filterNotNull().distinct().forEach { mb ->
            val k = syncKey(accountId, mb)
            loadSyncState(k)?.let { putSyncState(k, it.copy(emailState = s)) }
        }
    }

    // syncStates is keyed by (account, mailbox), not mailbox alone: same-server accounts
    // can share a mailbox id, and a shared key would let one account's sync cursor
    // overwrite the other's — forcing perpetual full re-queries and cross-account wipes.
    private fun syncKey(accountId: String, mailboxId: String) = "$accountId$mailboxId"

    /**
     * Ids whose flag/seen we just changed locally, with the time we did it. An email
     * stays in its mailbox after a flag change, but the first `Email/queryChanges` run
     * from the pre-change queryState can still report it as `removed` (some servers do
     * this for any changed row under thread-collapsing). We must not evict it then —
     * guard such ids briefly so a lagging delta can't drop a just-favourited message.
     */
    // Keyed "$accountId:$emailId", not by bare id: same-server accounts can cache colliding
    // email ids (issue #31), and a bare-id guard would shield — or expire — a sibling
    // account's same-id row too.
    private val recentlyMutated = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun mutationKey(accountId: String, emailId: String) = "$accountId:$emailId"

    /**
     * Ids the app itself just moved between folders, for the notifier's diff filter
     * (Codeberg #50 follow-up — see [RecentLocalMoves]). Marked at every point a move is
     * server-acknowledged (single, bulk, undo, unarchive-on-reply); for IMAP the marked id
     * is the message's id AT ITS DESTINATION, since an IMAP move changes the id. Pure
     * bookkeeping: no action path's semantics change.
     */
    val recentLocalMoves = RecentLocalMoves()

    private fun markRecentlyMutated(accountId: String, emailId: String) {
        recentlyMutated[mutationKey(accountId, emailId)] = System.currentTimeMillis()
    }
    private fun isRecentlyMutated(accountId: String, emailId: String): Boolean =
        isRecentlyMutatedKey(mutationKey(accountId, emailId))
    private fun isRecentlyMutatedKey(key: String): Boolean {
        val at = recentlyMutated[key] ?: return false
        if (System.currentTimeMillis() - at > RECENT_MUTATION_MS) {
            recentlyMutated.remove(key)
            return false
        }
        return true
    }

    /**
     * [accountId]'s email ids still inside their mutation-protection window (expired entries are
     * pruned as a side effect). Handed to the full-query reconcile so a fresh page can't delete
     * a row we just mutated/restored but the server hasn't caught up on yet.
     */
    private fun recentlyMutatedIds(accountId: String): List<String> {
        val prefix = "$accountId:"
        return recentlyMutated.keys
            .filter { it.startsWith(prefix) && isRecentlyMutatedKey(it) }
            .map { it.removePrefix(prefix) }
    }

    /**
     * Bring a mailbox's cache up to date. Uses Email/queryChanges + Email/changes when
     * we have prior state; otherwise, or when the server can't compute the delta, falls
     * back to a full query. Both paths are UNCOLLAPSED: the cache holds every in-folder
     * thread member (conversations collapse at display time), so per-thread unread/bold
     * state and reconciliation see non-representative members too.
     *
     * Returns THE IDS THIS CYCLE FETCHED FROM THE SERVER, whichever branch ran: the full query's
     * whole page, or the delta's `toFetch`. Never null, and never a partial list — everything in
     * it has just been written to the cache, and the retention prune spares exactly it.
     *
     * The two branches must both answer, which is the correction of a first version of the
     * Codeberg #110 fix that answered only for the full query: the delta branch writes rows the
     * cache has NEVER held (`added - cachedIds`), so a message an external client or a Sieve rule
     * files into a deep folder was fetched and then deleted by the prune in the same refresh.
     */
    private suspend fun syncMailbox(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        mailboxId: String,
        limit: Int,
        // Local StoredAccount id used to tag cached rows (distinct from the JMAP
        // [accountId] used for API calls), so per-account routing and storage work.
        localAccountId: String,
    ): List<String> {
        // Same belt as [refresh] (#121): this is the other entry point that tags cached rows with
        // a local account id, and a blank one strands every row it writes.
        require(localAccountId.isNotBlank()) {
            "syncMailbox() needs a real account id: caching mail under a blank one strands it (#121)."
        }
        val key = syncKey(localAccountId, mailboxId)
        val stored = loadSyncState(key)
        if (stored != null) {
            val queryChanges = client.emailQueryChanges(session, accountId, mailboxId, stored.queryState, MAX_CHANGES, auth)
            val changes = client.emailChanges(session, accountId, stored.emailState, MAX_CHANGES, auth)
            val canApply = queryChanges.calculated && changes.calculated &&
                !changes.hasMoreChanges && queryChanges.newQueryState != null && changes.newState != null
            if (canApply) {
                // A row that merely changed position shows up in both removed and added
                // (a reorder, e.g. favouriting pins to the top). Don't delete+re-add it —
                // the cache already holds the new data and the query re-sorts it, so the
                // reorder is a no-op (no blink). Only genuinely-gone ids are removed, and
                // only genuinely-new ids are fetched.
                val added = queryChanges.added.toSet()
                // Never evict an id we just flagged/read locally: a delta computed from
                // the pre-mutation query state can report it as removed even though it's
                // still in the mailbox (it only changed a keyword).
                //
                // The verdict is MEMOISED for this delta so the eviction and the log below can
                // never disagree: the protection window can expire between two calls, and a log
                // line claiming a row was spared while it was in fact evicted (or the reverse)
                // would be worse than no log at all.
                val protectionVerdict = HashMap<String, Boolean>()
                val isProtected: (String) -> Boolean = { id ->
                    protectionVerdict.getOrPut(id) { isRecentlyMutated(localAccountId, id) }
                }
                val toRemove = deltaEvictions(queryChanges.removed, added, changes.destroyed, isProtected)
                if (toRemove.isNotEmpty()) emailDao.deleteByIds(localAccountId, toRemove)
                // Every eviction the recently-mutated spare skipped, named and motivated. A
                // `destroy` here is the one-shot loss [deltaEvictions] documents: the row survives
                // a message the server no longer has, and only the ghost sweep can still remove it.
                val spared = sparedEvictions(queryChanges.removed, added, changes.destroyed, isProtected)
                // ONE line, not one per id: this is the only sync line whose length scales with the
                // data (a delta can spare up to MAX_CHANGES ids), and the count-then-ids shape says
                // the same thing while leaving the count readable even if logcat truncates the tail.
                if (spared.isNotEmpty()) {
                    android.util.Log.i(
                        "MailSync",
                        "spared $localAccountId/$mailboxId: ${spared.size} not evicted " +
                            "(locally mutated < ${RECENT_MUTATION_MS}ms ago): " +
                            spared.joinToString { (id, reason) -> "$id ${reason.log}" },
                    )
                }
                val cachedIds = emailDao.idsForMailbox(localAccountId, mailboxId).toSet()
                val toFetch = deltaFetches(added, cachedIds, changes.updated)
                if (toFetch.isNotEmpty()) {
                    val fetched = client.getEmailsByIds(session, accountId, toFetch, auth)
                    emailDao.upsertAll(fetched.map { it.toEntity(localAccountId, mailboxId) })
                }
                putSyncState(key, SyncState(queryChanges.newQueryState!!, changes.newState!!))
                // Ghost sweep: a server-side destroy can reach us through NEITHER delta —
                // Stalwart omits a delegated (shared) account's destroys from Email/changes
                // and Email/queryChanges entirely (verified raw: same cursors report the
                // destroy on the owner's login but empty deltas on the delegated view,
                // while the state strings still advance) — and a reported destroy can also
                // be eaten one-shot by the recently-mutated spare above while the cursors
                // advance past it. Either way the cached row becomes an immortal ghost no
                // later delta ever prunes. Verify existence against the server — but NOT on
                // every sync: the states here are ACCOUNT-WIDE, so they advance on any
                // activity anywhere in the account and a state-only trigger would sweep the
                // whole cache of every watched folder almost every sync. See [shouldSweepGhosts]
                // for the gate (a real removal here sweeps at once, otherwise a time floor that
                // applies WHETHER OR NOT the deltas moved — Codeberg #107: a delegated account's
                // destroys reach us through neither delta, so a gate waiting on the deltas would
                // wait for ever and the row only died on the next cold start).
                val stateAdvanced = queryChanges.newQueryState != stored.queryState ||
                    changes.newState != stored.emailState
                val vanishedFromMailbox = queryChanges.removed.any { it !in added }
                val claim = ghostSweeps.claim(localAccountId, mailboxId, stateAdvanced, vanishedFromMailbox)
                // One line per incremental sync, carrying the sweep verdict: a ghost that survives
                // a refresh is either a sweep that ran and did not prune it, or a sweep that never
                // ran at all, and only this distinguishes them. `sweep=floor/idle` is the #107 fix
                // doing its work: an existence check on an account whose deltas reported nothing.
                android.util.Log.i(
                    "MailSync",
                    "incremental $localAccountId/$mailboxId: +${toFetch.size} -${toRemove.size} " +
                        "spared=${spared.size} sweep=${claim.reason}",
                )
                if (claim.sweep) {
                    val swept = pruneGhostRows(session, accountId, auth, mailboxId, localAccountId)
                    // A failed sweep pruned nothing, so it must not count as one: give its
                    // once-per-process credit back and the next sync retries immediately.
                    if (!swept) ghostSweeps.releaseFailed(localAccountId, mailboxId, claim)
                }
                // The rows this delta just wrote (line above): mostly ids the cache NEVER held —
                // `added - cachedIds` — so the retention prune has to spare them exactly as it
                // spares a full query's page. Returning nothing here was Codeberg #110 again on
                // the other branch: a 2019 message filed into a deep Inbox by a Sieve rule was
                // fetched, written, and deleted by the prune in the very same refresh.
                return toFetch
            }
        }
        // Cold cache, or the server can't compute changes — full query.
        val page = client.queryEmailsPage(session, accountId, mailboxId, limit, auth)
        emailDao.replaceMailbox(localAccountId, mailboxId, page.emails.map { it.toEntity(localAccountId, mailboxId) }, recentlyMutatedIds(localAccountId))
        android.util.Log.i("MailSync", "full query $mailboxId: ${page.emails.size} emails")
        val queryState = page.queryState
        val emailState = page.emailState
        if (queryState != null && emailState != null) {
            putSyncState(key, SyncState(queryState, emailState))
        } else {
            dropSyncState(key)
        }
        return page.emails.map { it.id }
    }

    /**
     * Apply an account's sync window to a mailbox once a refresh has landed: drop what falls
     * outside it, EXCEPT what that same sync just fetched from the server ([freshIds], Codeberg
     * #110), what the recently-mutated spare is protecting, and the [keepNewest] newest rows.
     *
     * The window is a bound on what we keep offline in addition to what the server just sent,
     * never a licence to delete it: a folder whose real contents are older than the window must
     * still show its real contents. Deciding in Kotlin rather than in one `DELETE … WHERE sortKey
     * < cutoff` is what makes that expressible at all, and puts the rule next to the delta and
     * sweep evictions where it can be unit-tested — see [retentionEvictions].
     *
     * The recently-mutated set is read HERE rather than passed in, because a caller that forgot it
     * would silently undo `replaceMailbox`'s reconcile spare a few lines after it ran — and on
     * these paths that spare set is the prune's only reachable victim, so forgetting it is not a
     * small mistake.
     *
     * [cutoffMillis] and [keepNewest] must come from the SAME `SyncWindow` — its `maxAgeDays` and
     * its `limit`. They are the one setting's two halves (the age it keeps, the number it keeps at
     * least), and only the caller that computed the cutoff knows which window that was.
     *
     * [freshIds] null means the caller cannot say what was fetched; [retentionEvictions] then
     * prunes nothing at all.
     */
    private suspend fun pruneRetention(
        accountId: String,
        mailboxId: String,
        cutoffMillis: Long,
        freshIds: Set<String>?,
        keepNewest: Int,
    ) {
        val gone = retentionEvictions(
            cached = emailDao.retentionRows(accountId, mailboxId),
            cutoffMillis = cutoffMillis,
            freshIds = freshIds,
            spareIds = recentlyMutatedIds(accountId).toSet(),
            keepNewest = keepNewest,
        )
        if (gone.isEmpty()) return
        // The INDEX SURVIVES this, hence `evictFromCacheKeepingIndex` and not `deleteByIds`: every
        // id here belongs to a message the server still has, sitting where it always was (see
        // [retentionEvictions]). Un-indexing them capped offline search at the sync window on every
        // single refresh — and on IMAP for good, since nothing re-indexes a row the cache no longer
        // holds. This is `EmailDao.deleteNotIn`'s case, by id instead of by page.
        //
        // Chunked for the same reason the ghost sweep chunks: the ids go into an `IN (...)` and a
        // deep cache can hold more of them than SQLite will bind in one statement.
        gone.chunked(MAX_CHANGES).forEach { emailDao.evictFromCacheKeepingIndex(accountId, it) }
        android.util.Log.i(
            "MailSync",
            "retention $accountId/$mailboxId: pruned ${gone.size}, " +
                "spared ${freshIds?.size ?: -1} fetched + floor $keepNewest",
        )
    }

    /**
     * When each (account, mailbox) may be existence-swept again: the first sync of a process
     * sweeps — which prunes ghosts inherited from a previous run — and afterwards a mailbox is
     * swept at most once per [GHOST_SWEEP_MIN_INTERVAL_MS], whether or not its deltas moved.
     */
    private val ghostSweeps = GhostSweepSchedule()

    /**
     * Existence sweep for one mailbox's cached rows: ids-only `Email/get` on everything still
     * cached, pruning exactly the ids the server reports `notFound` (see [ghostEvictions] for
     * why the recently-mutated spare is deliberately not honoured — a point lookup can't be
     * stale, and a destroyed id can't be protected back to life). Best-effort by design:
     * any transport/parse failure prunes NOTHING (only an explicit notFound may evict) and
     * returns false so the caller can retry the once-per-session sweep later.
     *
     * A CANCELLED caller is not a failed sweep and does not return at all: leaving the screen
     * mid-sweep would otherwise log a failure and hand the session credit back on behalf of a
     * caller that no longer exists — noise in the one channel that diagnoses ghosts. The throw
     * propagates, the floor stamp stays consumed, and the mailbox is checked again one interval
     * later like any other.
     */
    private suspend fun pruneGhostRows(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        mailboxId: String,
        localAccountId: String,
    ): Boolean {
        val cached = emailDao.idsForMailbox(localAccountId, mailboxId)
        if (cached.isEmpty()) return true
        // null = the check never answered, and that is what [ghostEvictions] prunes nothing for:
        // the eviction cannot be reached from a failure path by construction, rather than by an
        // early return someone has to remember not to move. getOrElseUnlessCancelled, not
        // getOrNull, so a cancellation stays an instruction to stop (see its own doc).
        val notFound: Set<String>? = runCatching {
            // Chunked so a deep cache can't exceed the server's maxObjectsInGet — one request per
            // MAX_CHANGES ids. A chunk that throws fails the WHOLE attempt: the ids already
            // answered for are discarded with it, because a partial answer says nothing about the
            // ids that were never asked about.
            cached.chunked(MAX_CHANGES).flatMapTo(mutableSetOf()) { chunk ->
                client.missingEmailIds(session, accountId, chunk, auth)
            }
        }.getOrElseUnlessCancelled { null }
        val ghosts = ghostEvictions(cached, notFound)
        if (ghosts.isNotEmpty()) pruneServerGone(localAccountId, ghosts)
        if (notFound == null) {
            // A failed sweep prunes nothing and looks exactly like a clean one from the outside —
            // say so, or a ghost surviving a sweep can't be told from a sweep that never landed.
            android.util.Log.i("MailSync", "ghost sweep $localAccountId/$mailboxId: failed over ${cached.size} cached")
            return false
        }
        android.util.Log.i(
            "MailSync",
            "ghost sweep $localAccountId/$mailboxId: -${ghosts.size} of ${cached.size} cached",
        )
        return true
    }

    /**
     * Drop rows the server authoritatively no longer has (an explicit per-id `notFound`) —
     * cache row, cached body and search-index entry — so they can't linger as zombies that
     * ignore every action.
     *
     * The index delete here is now a belt-and-braces repeat of what `EmailDao.deleteByIds` already
     * did on the line above, and it is KEPT for the shape it is written in: `runCatching`. An index
     * that cannot be written — locked, or damaged, issue #71's ground — must never abort the
     * removal of the cached row, which is the only thing standing between the user and a message
     * that keeps coming back. That is the rule `EmailDao.deleteById`/`deleteByIds` were rewritten
     * to follow, and this call is where it was first written down.
     *
     * NO folder-count nudge, unlike the action-path removals: the
     * server's counts never included these ids at the time we learn of them (the destroy
     * happened server-side and the cached mailbox counts have been refreshed from the server
     * since), so a local decrement would double-subtract; the live Room-derived badges
     * correct themselves the moment the rows are deleted.
     */
    private suspend fun pruneServerGone(localAccountId: String, emailIds: List<String>) {
        // Chunked like the retention prune and the sweep's own requests: every id here goes into an
        // `IN (...)`, and SQLite refuses a statement with more than 999 bindings. The sweep splits
        // its NETWORK calls at MAX_CHANGES but pours every answer back into one set, so a deep cache
        // full of ghosts arrived here as one oversized list — and the read and the cache delete are
        // OUTSIDE the runCatching below, so the refusal did not degrade anything, it threw out of
        // syncMailbox and failed the refresh.
        emailIds.chunked(MAX_CHANGES).forEach { chunk ->
            val ids = emailDao.emailsByIds(localAccountId, chunk).map { it.id }
            if (ids.isEmpty()) return@forEach
            emailDao.deleteByIds(localAccountId, ids)
            runCatching { emailFtsDao.deleteByIds(localAccountId, ids) }
            ids.forEach { runCatching { emailBodyDao.deleteById(localAccountId, it) } }
        }
    }

    /**
     * Live, mode-appropriate unread count per folder of the local account [accountId], keyed by
     * mailboxId, for the drawer badge: unread threads in conversation view, unread messages in
     * flat view. Both aggregates mirror the list's folder-scoped source (same thread grouping,
     * same not-snoozed filter), so a badge equals the visible bold rows and moves instantly on
     * read/move/delete/snooze — Room invalidation, no manual nudge. A folder absent from the
     * aggregate (nothing cached — e.g. never synced) maps to no badge, never a made-up count.
     */
    private fun observeUnreadByMailbox(accountId: String): Flow<Map<String, Int>> {
        val conversationView = settings?.conversationView ?: flowOf(true)
        return combine(
            emailDao.observeThreadUnreadCounts(),
            emailDao.observeMessageUnreadCounts(),
            conversationView,
        ) { threads, messages, conversation ->
            (if (conversation) threads else messages)
                .filter { it.accountId == accountId }
                .associate { it.mailboxId to it.count }
        }
    }

    /**
     * Live unread total across every account's inbox, for the drawer's "All inboxes (N)"
     * header. Each JMAP inbox contributes the SAME mode-aware aggregate its own drawer badge
     * shows (unread threads in conversation view, unread messages in flat view — the sources
     * of [observeUnreadByMailbox]), so the unified header and the Inbox badges below it agree
     * by construction. IMAP inboxes contribute their stored server counter instead: their
     * windowed cache would under-count. [scopes] is [AccountStore.allInboxScopes]' (accountId,
     * inboxId) pairs — both ids, since same-server accounts can share a mailbox id.
     */
    fun observeUnifiedInboxUnread(scopes: List<Pair<String, String>>): Flow<Int> {
        val conversationView = settings?.conversationView ?: flowOf(true)
        val (imapScopes, jmapScopes) = scopes.partition { (accountId, _) -> isImapAccount(accountId) }
        return combine(
            emailDao.observeThreadUnreadCounts(),
            emailDao.observeMessageUnreadCounts(),
            conversationView,
        ) { threads, messages, conversation ->
            val live = if (conversation) threads else messages
            jmapScopes.sumOf { (accountId, inboxId) ->
                live.firstOrNull { it.accountId == accountId && it.mailboxId == inboxId }?.count ?: 0
            } + imapScopes.sumOf { (accountId, _) -> accountStore.account(accountId)?.unread ?: 0 }
        }
    }

    /**
     * Cached mailboxes (folders) of the local account [accountId], updated reactively. JMAP
     * folders carry a live local [Mailbox.unreadForList] (the drawer badge — equals the list's
     * bold rows; unread older than the sync window is deliberately not counted, WYSIWYG). IMAP
     * folders keep the stored server-counter path: their partial cache window would make a
     * local count wrong.
     */
    fun observeMailboxes(accountId: String): Flow<List<Mailbox>> =
        if (isImapAccount(accountId)) {
            mailboxDao.observeAll(accountId).map { rows -> rows.map { it.toMailbox() } }
        } else {
            combine(mailboxDao.observeAll(accountId), observeUnreadByMailbox(accountId)) { rows, unread ->
                rows.map { it.toMailbox().copy(unreadForList = unread[it.id] ?: 0) }
            }
        }

    /**
     * Paged list of cached emails for [scopes] — the unified inbox's (account id, inbox id)
     * pairs, one per configured account — sorted server-side-style in SQL by the chosen [sort]
     * and nothing else; [unreadOnly] filters to unseen. Only a few pages are held in
     * memory at once, so very large folders no longer load (or freeze) all at once.
     *
     * PAIRS, not bare folder ids (Codeberg #121): a folder id alone matches rows of accounts that
     * are not in this list at all — a sibling account whose server numbered a folder the same, or
     * an account the user has removed, whose cached rows would otherwise be listed forever with
     * no account to label, sync or act on them. See [folderScopeSql].
     */
    fun pagedMailbox(
        scopes: List<Pair<String, String>>,
        sort: SortOrder,
        unreadOnly: Boolean,
        conversationView: Boolean,
        // Each account's Sent-role folder as an (accountId, mailboxId) pair: the conversation
        // chip also counts the thread's Sent replies, so it always equals what the unfolded
        // conversation shows — account-pinned, see [conversationQuery]. NO DEFAULT: an omitted
        // Sent scope is a chip that counts fewer messages than the row unfolds into.
        sentMailboxes: List<Pair<String, String>>,
    ): Flow<PagingData<InboxRow>> {
        if (scopes.isEmpty()) return flowOf(PagingData.empty())
        return if (conversationView) {
            Pager(
                config = pagingConfig(),
                pagingSourceFactory = { emailDao.conversationPagingSource(conversationQuery(scopes, sort, unreadOnly, accountId = null, sentMailboxes = sentMailboxes)) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        } else {
            Pager(
                config = pagingConfig(),
                pagingSourceFactory = { emailDao.pagingSource(pagingQuery(scopes, sort, unreadOnly)) },
            ).flow.map { data -> data.map { InboxRow(it.toEmail(), threadCount = 1, unread = !it.seen) } }
        }
    }

    /**
     * Paged view of a single folder, backed by a [RemoteMediator]: when the user
     * scrolls past the cached rows, the next older page is fetched from the JMAP
     * server (Email/query at the current offset) and inserted, so a large folder
     * keeps loading older mail on scroll instead of stopping at the sync window.
     */
    @OptIn(ExperimentalPagingApi::class)
    fun pagedFolder(
        credentials: AccountCredentials,
        mailboxId: String,
        sort: SortOrder,
        unreadOnly: Boolean,
        conversationView: Boolean,
        // The account's Sent-role folder as an (accountId, mailboxId) pair — see [pagedMailbox].
        // NO DEFAULT, same reason: this one must be said, not assumed.
        sentMailboxes: List<Pair<String, String>>,
    ): Flow<PagingData<InboxRow>> {
        // One scope: this account's folder. Same shape as the unified list's — the two views
        // differ in how many (account, folder) pairs they page, nothing else.
        val scopes = listOf(credentials.id to mailboxId)
        return if (conversationView) {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId, conversationView = true),
                pagingSourceFactory = { emailDao.conversationPagingSource(conversationQuery(scopes, sort, unreadOnly, credentials.id, sentMailboxes)) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        } else {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId, conversationView = false),
                pagingSourceFactory = { emailDao.pagingSource(pagingQuery(scopes, sort, unreadOnly)) },
            ).flow.map { data -> data.map { InboxRow(it.toEmail(), threadCount = 1, unread = !it.seen) } }
        }
    }

    /**
     * The scroll-to-load-more mediator for a single folder. It only extends the
     * EmailEntity cache (fetching older pages from the server on APPEND) and never
     * inspects row contents, so it works for either paged value type [V].
     * [conversationView] switches the APPEND fill target to thread representatives:
     * the collapsed list's rows are threads, so a message page must not count as a
     * full page of progress when it lands inside one big thread.
     */
    @OptIn(ExperimentalPagingApi::class)
    private fun <V : Any> folderMediator(
        credentials: AccountCredentials,
        mailboxId: String,
        conversationView: Boolean,
    ): RemoteMediator<Int, V> {
        return object : RemoteMediator<Int, V>() {
            // The cache is populated by refresh()/sync; only extend it on scroll.
            override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, V>,
            ): MediatorResult {
                if (loadType != LoadType.APPEND) {
                    return MediatorResult.Success(endOfPaginationReached = loadType == LoadType.PREPEND)
                }
                return try {
                    val (added, total) = if (credentials.protocol == MailProtocol.IMAP) {
                        val offset = emailDao.countForMailbox(credentials.id, mailboxId)
                        val (entities, exists) = imap.fetchOlderPage(credentials, mailboxId, offset, PAGE_SIZE)
                        if (entities.isNotEmpty()) emailDao.upsertAll(entities)
                        entities.size to exists
                    } else {
                        val ctx = connect(credentials)
                        // Anchor on the oldest cached representative (its thread's newest row
                        // here) and fetch the page right after it: unlike an absolute offset,
                        // the anchor doesn't shift when new mail arrives at the top, so no page
                        // is skipped or duplicated. The query is uncollapsed, so any cached
                        // in-folder row would be found — but members cached by an on-expand
                        // Thread/get can sit far below the contiguous window, and anchoring
                        // there would skip the gap; the representative overlaps at worst
                        // (upsert-only insertion makes the overlap harmless).
                        val anchorId = emailDao.oldestRepresentativeEmailId(credentials.id, mailboxId)
                        val before = emailDao.countForMailbox(credentials.id, mailboxId)
                        val repsBefore = if (conversationView) {
                            emailDao.representativeCountForMailbox(credentials.id, mailboxId)
                        } else {
                            0
                        }
                        var page = try {
                            client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                calculateTotal = true,
                                anchorId = anchorId,
                                anchorOffset = if (anchorId != null) 1 else 0,
                            )
                        } catch (e: JmapException) {
                            // The anchor can still have dropped out of the folder since it was
                            // cached, and at that point no cached id is trustworthy. Fall back
                            // once to an absolute position at the cached row count. That count
                            // can overshoot the contiguous window's edge (thread expansion
                            // caches members below it, spared undo rows linger), but any skip
                            // is bounded to this single recovery page: the fill loop below
                            // chains every follow-up on the fetched page's own last id, and
                            // upsert-only insertion makes an overlapping page harmless.
                            if (e.errorType != "anchorNotFound") throw e
                            client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                position = before, calculateTotal = true,
                            )
                        }
                        if (page.emails.isNotEmpty()) {
                            emailDao.upsertAll(page.emails.map { it.toEntity(credentials.id, mailboxId) })
                        }
                        // A page is messages, but the collapsed list's rows are threads — and
                        // the anchor page can even consist entirely of already-cached rows
                        // (the window's bottom edge cutting inside a big thread). Keep
                        // fetching at the window edge until this APPEND has produced enough
                        // NEW rows — thread representatives in conversation mode, any new
                        // cached row in flat mode (the pre-existing stall guard) — bounded
                        // so one giant thread can't chain fetches without limit: Paging
                        // simply APPENDs again if the viewport still isn't full.
                        val maxFetches = if (conversationView) MAX_APPEND_FILL_PAGES else 2
                        val fillTarget = if (conversationView) APPEND_THREAD_TARGET else 1
                        var fetches = 1
                        while (page.emails.isNotEmpty() && fetches < maxFetches) {
                            val cachedNow = emailDao.countForMailbox(credentials.id, mailboxId)
                            val gained = if (conversationView) {
                                emailDao.representativeCountForMailbox(credentials.id, mailboxId) - repsBefore
                            } else {
                                cachedNow - before
                            }
                            if (gained >= fillTarget) break
                            val serverTotal = page.total
                            if (serverTotal != null && cachedNow >= serverTotal) break
                            // Chain each follow-up on the page just fetched, never on the cached
                            // row count: the cache is not always a contiguous prefix of the
                            // server list (thread expansion caches old members below the window,
                            // spared undo rows linger), so a positional fetch at the raw count
                            // would skip that many server messages — a silent gap later APPENDs
                            // anchor below and never heal. Anchoring on the previous page's last
                            // id starts exactly where it ended, whatever the cache holds.
                            page = client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                calculateTotal = true,
                                anchorId = page.emails.last().id,
                                anchorOffset = 1,
                            )
                            if (page.emails.isNotEmpty()) {
                                emailDao.upsertAll(page.emails.map { it.toEntity(credentials.id, mailboxId) })
                            }
                            fetches++
                        }
                        page.emails.size to page.total
                    }
                    val cached = emailDao.countForMailbox(credentials.id, mailboxId)
                    val reachedEnd = added == 0 || (total != null && cached >= total)
                    MediatorResult.Success(endOfPaginationReached = reachedEnd)
                } catch (t: Throwable) {
                    MediatorResult.Error(t)
                }
            }
        }
    }

    private fun pagingConfig() = PagingConfig(
        pageSize = PAGE_SIZE,
        // Load enough up front to fill the screen and a buffer, and start fetching
        // the next page well before the edge so fast scrolling doesn't outrun paging.
        initialLoadSize = PAGE_SIZE * 3,
        prefetchDistance = PAGE_SIZE,
        enablePlaceholders = false,
    )

    // The bulk-read scopes are (accountId, mailboxId) PAIRS, not bare mailbox ids: servers
    // like Stalwart number mailboxes per-account, so two accounts' inboxes can share an id
    // and a mailbox-only read would silently pull a sibling account's rows into a bulk op.

    /** All cached (account, id) keys for the given (account, mailbox) scopes — the whole folder,
     *  filters included, so it drives cache eviction and NOT "select all" (see [selectableIds]). */
    suspend fun cachedIds(scopes: List<Pair<String, String>>): List<EmailKey> =
        scopes.flatMap { (accountId, mailboxId) ->
            emailDao.idsForMailbox(accountId, mailboxId).map { EmailKey(accountId, it) }
        }

    /**
     * The keys "Select all" may take outside a search: the rows the flat list is PAGING, run
     * through [selectionIdsQuery] — the same WHERE clause, so the unread filter (and the snooze
     * filter that rides with it) reaches the selection instead of stopping at the screen.
     * Codeberg #126: with the funnel on, the unfiltered read handed read messages to the bulk
     * action, and "select all + delete" moved mail that was never displayed to the Trash.
     */
    suspend fun selectableIds(scopes: List<Pair<String, String>>, unreadOnly: Boolean): List<EmailKey> =
        emailDao.keysForSelection(selectionIdsQuery(scopes, unreadOnly))
            .map { EmailKey(it.accountId, it.id) }

    /** All cached emails for the given (account, mailbox) scopes (drives "mark all read"). */
    suspend fun cachedEmailsForMailboxes(scopes: List<Pair<String, String>>): List<Email> =
        scopes.flatMap { (accountId, mailboxId) -> emailDao.getByMailbox(accountId, mailboxId) }.map { it.toEmail() }

    /** Cached emails for account-qualified keys (drives bulk actions on a selection). Each id is
     *  resolved ONLY inside its own account, so a colliding id can never drag a sibling account's
     *  message into the op; a null accountId falls back to an unscoped lookup. */
    suspend fun cachedEmailsByIds(keys: Collection<EmailKey>): List<Email> {
        if (keys.isEmpty()) return emptyList()
        return keys.groupBy({ it.accountId }, { it.emailId }).flatMap { (accountId, ids) ->
            if (accountId != null) emailDao.emailsByIds(accountId, ids) else emailDao.emailsByIds(ids)
        }.map { it.toEmail() }
    }

    /**
     * Every unread message id in [mailboxId], resolved SERVER-side (uncollapsed Email/query
     * filtered on `notKeyword $seen`, paginated and bounded) — so "Mark all read" reaches
     * unread mail the cache doesn't hold (non-representative thread members, mail past the
     * sync window) and the badge doesn't spring back at the next sync. Falls back to the
     * cached unread rows when the query fails (offline); IMAP has no cheap folder-wide id
     * query, so it always uses the cache.
     */
    suspend fun unreadIds(credentials: AccountCredentials, mailboxId: String): List<String> {
        suspend fun cached() =
            emailDao.getByMailbox(credentials.id, mailboxId).filter { !it.seen }.map { it.id }
        if (credentials.protocol == MailProtocol.IMAP) return cached()
        return runCatching {
            val ctx = connect(credentials)
            val ids = mutableListOf<String>()
            while (ids.size < UNREAD_RESOLVE_MAX) {
                // Ids-only query (no Email/get): only the ids matter here, headers would be waste.
                val page = client.queryEmailIds(
                    ctx.session, ctx.accountId, mailboxId, UNREAD_RESOLVE_PAGE, ctx.auth,
                    position = ids.size, calculateTotal = true, unseenOnly = true,
                )
                if (page.ids.isEmpty()) break
                ids += page.ids
                // Advance by the ACTUAL page size and stop on the server's total (or an empty
                // page): a server clamping the limit below UNREAD_RESOLVE_PAGE must not end
                // the walk early with unread mail left behind.
                val total = page.total
                if (total != null && ids.size >= total) break
            }
            ids
        }.getOrElse { cached() }
    }

    /**
     * Instant coverage floor for the search index: re-seed the rows that are in the display cache
     * (recent window), without clearing crawled-only rows. Called when a search session opens; the
     * full whole-mailbox crawl ([syncSearchIndex]) runs separately in the background.
     */
    suspend fun seedIndexFromCache() = emailFtsDao.seedFromEmails(NOT_SEARCHED_ROLES)

    /** Per-account throttle for the (network) index crawl. */
    private val lastIndexAt = mutableMapOf<String, Long>()

    /**
     * Crawl the whole mailbox's HEADERS into the local search index so as-you-type search covers all
     * mail's subject/sender instantly and offline. Headers only: responses stay tiny, so even a
     * years-deep archive is covered in seconds. Body matches are NOT indexed locally — the live
     * search unions in the server's own full-text results for those (the server already has the
     * complete index; re-downloading bodies to rebuild it client-side was IMAP-style waste).
     * JMAP only (IMAP is best-effort via the cache seed). Throttled per account by [INDEX_TTL_MS]
     * unless [force]. Upserts so it is idempotent.
     */
    suspend fun syncSearchIndex(
        credentials: AccountCredentials,
        force: Boolean = false,
        onPage: (suspend () -> Unit)? = null,
    ) {
        if (credentials.protocol == MailProtocol.IMAP) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastIndexAt[credentials.id] ?: 0L) < INDEX_TTL_MS) return
        val ctx = runCatching { connect(credentials) }.getOrNull() ?: return
        // Don't crawl Trash/Junk into the index (parity with the server search and the IMAP walk):
        // same role source as searchableFolderIds, so what the index holds and what a search may
        // surface can never diverge. Excluded server-side, so those headers never come down.
        val excluded = excludedSearchFolderIds(mailboxDao.searchOrder(credentials.id))
        var position = 0
        var failed = false
        var consecutiveErrors = 0
        while (position < HEADER_MAX) {
            val page = try {
                client.crawlHeaders(ctx.session, ctx.accountId, position, HEADER_PAGE, ctx.auth, excluded)
            } catch (e: CancellationException) {
                throw e // search closed / VM cleared: bail WITHOUT stamping so we resume next time
            } catch (e: Exception) {
                // A page error must NOT hide every older mail behind it: skip the page and keep
                // crawling; give up only after repeated failures.
                failed = true
                if (++consecutiveErrors >= MAX_CRAWL_ERRORS) break
                position += HEADER_PAGE
                continue
            }
            consecutiveErrors = 0
            // Drive pagination by how many ids Email/query returned, NOT by Email/get's object count:
            // the get can omit ids (maxObjectsInGet, moved/removed mail) and a short get must not stop
            // the walk — otherwise the crawl abandons the oldest mail (indexed last).
            if (page.queryCount == 0) break
            if (page.emails.isNotEmpty()) {
                emailFtsDao.upsert(page.emails.map { it.toFts(credentials.id) })
                onPage?.invoke()
            }
            if (page.queryCount < HEADER_PAGE) break
            position += page.queryCount
        }
        // Only throttle once the crawl finished cleanly; a partial/failed run must stay retryable so
        // coverage isn't frozen where an interrupted crawl happened to stop.
        if (!failed) lastIndexAt[credentials.id] = now
    }

    /**
     * Local full-text search over [syncSearchIndex]'s index: accent-folded, PREFIX-matched
     * ("eco*"), so it is instant, offline and monotonic as the user types (unlike the server's
     * stemmed full-text). Returns newest-first, capped at [limit]. Blank/empty query → no results.
     *
     * Trash/Junk/Spam are filtered by the query itself, from [NOT_SEARCHED_ROLES] — the single role
     * source the server-side filter also uses, so both halves of the search union (local index and
     * server answer) hide the same folders instead of the local half putting deleted mail back.
     * That filter only sees rows LABELLED with an excluded folder; a message that was indexed in
     * the Inbox and then deleted keeps the Inbox as its label forever, and is kept out by
     * `EmailDao.deleteById`/`deleteByIds` un-indexing it as it goes. See `EmailFtsDao.search`.
     */
    suspend fun searchIndex(query: String, limit: Int = LOCAL_SEARCH_LIMIT): List<Email> {
        val match = ftsMatch(query) ?: return emptyList()
        return emailFtsDao.search(match, NOT_SEARCHED_ROLES, limit).map { it.toEmail() }
    }

    /** Build an FTS4 MATCH expression: each word becomes a prefix term, AND-combined ("eco* log*"). */
    private fun ftsMatch(query: String): String? {
        val tokens = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /**
     * Refresh every account's inbox into the cache (each email tagged with its
     * accountId). Per-account failures are skipped so one bad account doesn't sink
     * the unified view. Returns metadata for the accounts that synced successfully.
     */
    suspend fun refreshAllInboxes(accounts: List<AccountCredentials>, limit: Int = 50): UnifiedRefreshResult {
        val results = mutableListOf<AccountInboxMeta>()
        val failures = mutableListOf<Throwable>()
        for (credentials in accounts) {
            try {
                if (credentials.protocol == MailProtocol.IMAP) {
                    val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = limit)
                    emailDao.replaceMailbox(credentials.id, load.targetMailboxId, load.messages, recentlyMutatedIds(credentials.id))
                    mailboxDao.replaceAll(credentials.id, load.mailboxes)
                    results += AccountInboxMeta(
                        credentials.id, load.accountName, load.targetMailboxId, load.targetName, load.unread,
                    )
                    continue
                }
                val resolved = resolve(credentials)
                val inbox = resolved.mailboxes.firstOrNull { it.role == "inbox" }
                    ?: resolved.mailboxes.firstOrNull()
                    ?: continue
                syncMailbox(resolved.session, resolved.accountId, resolved.auth, inbox.id, limit, credentials.id)
                // Persist the fetched folder counters (previously discarded), AFTER the row sync
                // so badge and list move together — the unified refresh reconciles the drawer for
                // every account, not just the current one.
                mailboxDao.replaceAll(credentials.id, resolved.mailboxes.map { it.toEntity(credentials.id) })
                val name = resolved.session.accounts[resolved.accountId]?.name ?: credentials.username
                results += AccountInboxMeta(credentials.id, name, inbox.id, inbox.name, inbox.unreadEmails)
                // Warm the body cache for the visible top of the inbox so opening is instant.
                bgScope.launch { runCatching { prefetchInboxBodies(credentials, inbox.id) } }
            } catch (c: CancellationException) {
                // A superseding refresh (or the VM being cleared) cancelled us: propagate cleanly so
                // the caller does NOT record a false success. runCatching used to swallow this too,
                // which is exactly how a cancelled pull-to-refresh reported "connected" (#65).
                throw c
            } catch (t: Throwable) {
                // One account failing must not sink the unified view, but it must not vanish without
                // trace either (#92): keep going, and remember the failure so the caller can tell an
                // all-offline refresh from a single bad account.
                failures += t
                android.util.Log.w("MailRepository", "unified refresh: account ${credentials.id} failed", t)
            }
        }
        return UnifiedRefreshResult(results, failures)
    }

    /**
     * Refresh the mailbox list and the emails of [mailboxId] (or the inbox when
     * null), updating the cache and the in-memory session context.
     */
    /** Outcome of JMAP autodiscovery for an email address. */
    sealed interface DiscoveryResult {
        /** A server host responded and authenticated; store this as the account `server`. */
        data class Found(val server: String) : DiscoveryResult
        /** A server was reached but rejected the credentials (HTTP 401/403). */
        data object BadCredentials : DiscoveryResult
        /** No candidate host responded as a JMAP server. */
        data object NotFound : DiscoveryResult
    }

    /**
     * JMAP autodiscovery (RFC 8620 §2.2): probe the email domain's
     * `/.well-known/jmap` (and conventional mail./jmap. subdomains) with the given
     * credentials, returning the host whose session authenticates. A 401/403 from
     * any reachable candidate means the server was found but the password is wrong
     * — reported distinctly so the UI can give a precise error.
     */
    suspend fun discoverJmapServer(email: String, password: String, token: String? = null): DiscoveryResult {
        val hosts = Jmap.autodiscoverHosts(email)
        if (hosts.isEmpty()) return DiscoveryResult.NotFound
        // A non-null [token] is an API token (e.g. Fastmail): Bearer, never Basic.
        val auth = if (token != null) BearerAuth(token) else BasicAuth(email.trim(), password)
        var sawAuthFailure = false
        for (host in hosts) {
            try {
                val session = client.fetchSession(Jmap.sessionUrlFor(host), auth)
                if (session.mailAccountId() != null) return DiscoveryResult.Found(host)
            } catch (e: JmapException) {
                if (e.httpCode == 401 || e.httpCode == 403) sawAuthFailure = true
                // Other failures (host unreachable, not JMAP): try the next candidate.
            } catch (_: Throwable) {
                // Transport error (DNS, TLS, timeout): try the next candidate.
            }
        }
        return if (sawAuthFailure) DiscoveryResult.BadCredentials else DiscoveryResult.NotFound
    }

    /**
     * Build JMAP auth for [credentials]: Bearer for API-token accounts (the token
     * lives in the password slot) and for OAuth accounts (refreshing the access
     * token first when it's missing or within 60s of expiry, then persisting the
     * new tokens), Basic otherwise.
     */
    private suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth {
        if (credentials.authType == AuthType.API_TOKEN) return BearerAuth(credentials.password)
        val token = tokenRefresher.freshAccessToken(credentials)
            ?: return BasicAuth(credentials.username, credentials.password)
        return BearerAuth(token)
    }

    /**
     * Resolve a manually-entered JMAP server to the value to persist as the account's
     * `server`: try each session-URL candidate (an explicit session URL is used
     * verbatim; ".../jmap" also tries ".../jmap/session" and the host's well-known)
     * and return the first whose session parses and carries a mail account. Inputs
     * [Jmap.sessionUrlFor] already resolves are returned unchanged; a probed fallback
     * returns the exact working session URL (stable under [Jmap.sessionUrlFor]).
     * Rethrows the first candidate's failure when none works.
     */
    suspend fun resolveJmapServerInput(serverInput: String, auth: JmapAuth): String {
        val candidates = Jmap.sessionUrlCandidates(serverInput)
        var firstError: Throwable? = null
        for (url in candidates) {
            val session = try {
                client.fetchSession(url, auth)
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
                continue
            }
            if (session.mailAccountId() != null) {
                return if (candidates.size == 1) serverInput.trim() else url
            }
            if (firstError == null) firstError = JmapException("This user has no JMAP mail account.")
        }
        throw firstError ?: JmapException("No JMAP server found at $serverInput")
    }

    /**
     * The address the server itself associates with [auth] at [server]: the session's
     * `username` (RFC 8620 §2), else the primary mail account's name — whichever looks
     * like an email address; null when the session declares neither (e.g. a bare login
     * name). Token sign-ins adopt this over the typed address, which Bearer auth never
     * validates (#54: a wrong email + valid token would otherwise mint a wrong identity).
     */
    suspend fun sessionIdentity(server: String, auth: JmapAuth): String? {
        val session = client.fetchSession(Jmap.sessionUrlFor(server), auth)
        val accountName = session.mailAccountId()?.let { session.accounts[it]?.name }
        val looksLikeEmail = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")
        return sequenceOf(session.username, accountName.orEmpty())
            .map { it.trim() }
            .firstOrNull { looksLikeEmail.matches(it) }
    }

    /**
     * Validate an account's credentials without persisting anything or disturbing
     * the active session: JMAP fetches the session (and checks for a mail account),
     * IMAP connects + authenticates. Returns success/failure for a "test connection".
     */
    suspend fun testConnection(credentials: AccountCredentials): Result<Unit> = runCatching {
        if (credentials.protocol == MailProtocol.IMAP) {
            imap.testConnection(credentials)
        } else {
            val session = client.fetchSession(
                Jmap.sessionUrlFor(credentials.server),
                jmapAuth(credentials),
            )
            requireNotNull(session.mailAccountId()) { "This user has no JMAP mail account." }
            Unit
        }
    }

    /** OAuth metadata for a host, or null if it advertises no usable device flow. */
    suspend fun discoverOAuth(host: String): OAuthMetadata? =
        oauthClient.discoverMetadata(host)?.takeIf { it.supportsDeviceFlow }

    /** Begin the device flow against [metadata] using Sterna's client id + scopes. */
    suspend fun startDeviceAuthorization(metadata: OAuthMetadata): DeviceAuthorization =
        oauthClient.startDeviceAuthorization(metadata, Jmap.OAUTH_CLIENT_ID, Jmap.OAUTH_SCOPE)

    /** Poll the token endpoint once for a pending device authorization. */
    suspend fun pollDeviceToken(metadata: OAuthMetadata, deviceCode: String): DeviceTokenResult =
        oauthClient.pollDeviceToken(metadata, deviceCode, Jmap.OAUTH_CLIENT_ID)

    /**
     * Validate freshly granted OAuth [tokens] against the JMAP server at [host],
     * then persist the account and prime its inbox cache. Throws (persisting
     * nothing) if the token doesn't authenticate or the user has no mail account.
     */
    suspend fun addOAuthAccount(
        host: String,
        email: String,
        metadata: OAuthMetadata,
        tokens: OAuthTokens,
        accountName: String,
    ) {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        client.fetchSession(Jmap.sessionUrlFor(host), BearerAuth(tokens.accessToken)).mailAccountId()
            ?: error("This user has no JMAP mail account.")
        val id = accountStore.addOAuth(
            server = host,
            username = email,
            accountName = accountName,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt,
            tokenEndpoint = metadata.tokenEndpoint,
            clientId = Jmap.OAUTH_CLIENT_ID,
        )
        val credentials = accountStore.credentials(id) ?: error("Account could not be loaded after creation.")
        val meta = refresh(credentials)
        accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
        reconcileLinkedAccountsAfterAdd(id)
    }

    /** Begin the device flow for a built-in OAuth provider (Microsoft, …). */
    suspend fun startProviderDeviceAuth(provider: OAuthProvider): DeviceAuthorization =
        oauthClient.startDeviceAuthorization(provider.metadata, provider.clientId, provider.scope)

    /** Poll a built-in provider's token endpoint once for a pending device authorization. */
    suspend fun pollProviderToken(provider: OAuthProvider, deviceCode: String): DeviceTokenResult =
        oauthClient.pollDeviceToken(provider.metadata, deviceCode, provider.clientId)

    /**
     * Run a provider device flow to completion: start it, hand the user code to [onCode] so the UI
     * can show it, then poll until the grant is approved, refused, or the code expires. Returns the
     * granted tokens, or fails with [OAuthDeniedException] (carrying the parsed error / AADSTS code
     * so the caller can map it) on refusal or timeout. One shared implementation for every screen
     * that signs an account in via the browser (first-run import list and account settings).
     */
    suspend fun runProviderDeviceFlow(
        provider: OAuthProvider,
        onCode: (DeviceAuthorization) -> Unit,
    ): Result<OAuthTokens> = runCatching {
        val device = startProviderDeviceAuth(provider)
        onCode(device)
        var interval = device.interval.coerceAtLeast(1).toLong()
        val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            when (val r = pollProviderToken(provider, device.deviceCode)) {
                is DeviceTokenResult.Success -> return@runCatching r.tokens
                DeviceTokenResult.Pending -> Unit
                DeviceTokenResult.SlowDown -> interval += 5
                is DeviceTokenResult.Failed -> throw OAuthDeniedException(r)
            }
        }
        throw OAuthDeniedException(DeviceTokenResult.Failed("expired_token"))
    }

    /**
     * Validate freshly granted OAuth [tokens] by connecting to [provider]'s IMAP server
     * with XOAUTH2, then persist an IMAP/SMTP account and prime its inbox cache. Throws
     * (persisting nothing) if the token is rejected.
     */
    suspend fun addOAuthImapAccount(
        provider: OAuthProvider,
        email: String,
        tokens: OAuthTokens,
        accountName: String,
    ) {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        // Microsoft IMAP wants the account's exact primary address as the XOAUTH2 user=,
        // not whatever alias the user typed — take it from the signed-in identity (id_token).
        val username = emailFromIdToken(tokens.idToken) ?: email
        val probe = AccountCredentials(
            server = "",
            username = username,
            password = "",
            protocol = MailProtocol.IMAP,
            imap = provider.imap,
            smtp = provider.smtp,
            oauth = OAuthCredentials(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.orEmpty(),
                accessExpiresAtMillis = expiresAt,
                tokenEndpoint = provider.metadata.tokenEndpoint,
                clientId = provider.clientId,
            ),
        )
        // Validate against the IMAP server before persisting anything; drop the probe conn.
        try {
            imap.testConnection(probe)
        } finally {
            runCatching { imap.disconnect("") }
        }
        val id = accountStore.addOAuth(
            server = "",
            username = username,
            accountName = accountName,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt,
            tokenEndpoint = provider.metadata.tokenEndpoint,
            clientId = provider.clientId,
            protocol = MailProtocol.IMAP,
            imapHost = provider.imap.host,
            imapPort = provider.imap.port,
            imapSecurity = provider.imap.security,
            smtpHost = provider.smtp.host,
            smtpPort = provider.smtp.port,
            smtpSecurity = provider.smtp.security,
        )
        val credentials = accountStore.credentials(id) ?: error("Account could not be loaded after creation.")
        val meta = refresh(credentials)
        accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
    }

    /** Complete OAuth sign-in for an already-imported (inert) account [accountId] using freshly
     *  granted [tokens] against [provider]. Validates via IMAP XOAUTH2 first; on success attaches the
     *  tokens to the existing account and primes its inbox. Throws (account left inert) on failure. */
    suspend fun signInImportedOAuth(accountId: String, provider: OAuthProvider, tokens: OAuthTokens) {
        val account = accountStore.account(accountId) ?: error("Account not found.")
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        val username = emailFromIdToken(tokens.idToken) ?: account.username
        val probe = AccountCredentials(
            server = "", username = username, password = "",
            protocol = MailProtocol.IMAP,
            imap = MailEndpoint(account.imapHost, account.imapPort, account.imapSecurity),
            smtp = MailEndpoint(account.smtpHost, account.smtpPort, account.smtpSecurity),
            oauth = OAuthCredentials(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.orEmpty(),
                accessExpiresAtMillis = expiresAt,
                tokenEndpoint = provider.metadata.tokenEndpoint,
                clientId = provider.clientId,
            ),
        )
        try { imap.testConnection(probe) } finally { runCatching { imap.disconnect("") } }
        accountStore.attachOAuth(
            id = accountId, username = username,
            accessToken = tokens.accessToken, refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt, tokenEndpoint = provider.metadata.tokenEndpoint,
            clientId = provider.clientId,
        )
        val credentials = accountStore.credentials(accountId) ?: error("Account could not be loaded.")
        val meta = refresh(credentials)
        accountStore.saveInboxMetaFor(accountId, meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
    }

    /** The signed-in address from an OIDC id_token (`preferred_username`/`email`), or null. */
    private fun emailFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        return runCatching {
            val payload = idToken.split(".").getOrNull(1) ?: return null
            val decoded = android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            val obj = Json.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
            (obj["preferred_username"] ?: obj["email"])?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun refresh(
        credentials: AccountCredentials,
        mailboxId: String? = null,
        limit: Int = 50,
        // Prune cached messages older than this epoch-millis cutoff (the age-based sync window);
        // null prunes nothing. When set, [limit] is read as the window's retention FLOOR as well
        // as its fetch size: the folder's newest [limit] rows are kept whatever their age, so the
        // two must come from the same SyncWindow.
        pruneBeforeMillis: Long? = null,
    ): MailboxMeta {
        // The belt behind ConnectViewModel's braces (#121). Every row this writes is tagged with
        // credentials.id; with a blank one they land under an account that does not exist, show up
        // in the unified list with no chip, never re-sync, and are only ever removed by the orphan
        // sweep. The three add paths used to prime the cache before creating the account, which is
        // exactly how that happened — they now create first. Throw rather than skip: a silent
        // no-op would hide a fourth add path making the same mistake.
        require(credentials.id.isNotBlank()) {
            "refresh() needs a real account id: caching mail under a blank one strands it (#121)."
        }
        if (credentials.protocol == MailProtocol.IMAP) return refreshImap(credentials, mailboxId, limit, pruneBeforeMillis)
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = jmapAccountIdFor(credentials, session)

        val mailboxes = client.getMailboxes(session, accountId, auth)
        context = Context(
            credentials = credentials,
            session = session,
            accountId = accountId,
            auth = auth,
            rolesToMailboxId = mailboxes.mapNotNull { mb -> mb.role?.let { it to mb.id } }.toMap(),
            mailboxes = mailboxes,
        )

        val target = mailboxId?.let { id -> mailboxes.firstOrNull { it.id == id } }
            ?: mailboxes.firstOrNull { it.role == "inbox" }
            ?: mailboxes.firstOrNull()
            ?: error("No mailboxes found.")

        // The folder rows must land even when the message sync throws (server hiccup mid-
        // refresh): otherwise the drawer keeps stale — or, right after a migration, empty —
        // folders although the list we already fetched is good. Failure persists the rows,
        // then rethrows; the prune stays success-only.
        val sync = runCatching { syncMailbox(session, accountId, auth, target.id, limit, credentials.id) }
        val syncError = sync.exceptionOrNull()
        // The prune spares whatever this very sync fetched — the full query's page, or the
        // delta's newly-written rows. `getOrNull()` is null only when the sync threw, which the
        // guard already excludes; passing it through unmapped keeps the second lock in place
        // (a null there prunes nothing rather than everything). Codeberg #110.
        if (syncError == null && pruneBeforeMillis != null) {
            pruneRetention(credentials.id, target.id, pruneBeforeMillis, sync.getOrNull()?.toSet(), limit)
        }
        // Folder counters land AFTER the email rows: badge and list update together, and an
        // optimistic nudge from a concurrent action isn't overwritten mid-refresh by counters
        // fetched before the rows synced.
        mailboxDao.replaceAll(credentials.id, mailboxes.map { it.toEntity(credentials.id) })
        if (syncError != null) throw syncError
        // Warm the body cache for the top of the inbox so opening is instant (single-account
        // refresh path; the unified path warms it in refreshAllInboxes). Inbox only, to bound
        // bandwidth. Fire-and-forget so it never delays the list.
        if (target.role == "inbox") {
            bgScope.launch { runCatching { prefetchInboxBodies(credentials, target.id) } }
        }

        val accountName = session.accounts[accountId]?.name ?: credentials.username
        return MailboxMeta(accountName, target.id, target.name, target.unreadEmails)
    }

    /** IMAP refresh: list folders + fetch the target folder's newest page into the cache. */
    private suspend fun refreshImap(
        credentials: AccountCredentials,
        mailboxId: String?,
        limit: Int,
        pruneBeforeMillis: Long?,
    ): MailboxMeta {
        val load = imap.loadFolder(credentials, mailboxId, limit)
        emailDao.replaceMailbox(credentials.id, load.targetMailboxId, load.messages, recentlyMutatedIds(credentials.id))
        mailboxDao.replaceAll(credentials.id, load.mailboxes)
        // Spares the page just fetched: IMAP re-queries the folder on every refresh, so this set
        // IS the folder's newest window as the server has it. Pruning it away is what made a
        // ten-message folder flash all ten and settle on two (Codeberg #110).
        if (pruneBeforeMillis != null) {
            pruneRetention(
                credentials.id,
                load.targetMailboxId,
                pruneBeforeMillis,
                load.messages.mapTo(HashSet()) { it.id },
                limit,
            )
        }
        return MailboxMeta(load.accountName, load.targetMailboxId, load.targetName, load.unread)
    }

    /**
     * Fetch a single message (with body). Marks it read locally and on the server when
     * [markRead] (the default); pass false to fetch the body without reading it — used by
     * the message pager, which only marks the entry read once it settles, not while it is
     * flicked past.
     */
    suspend fun openEmail(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): Email {
        if (credentials.protocol == MailProtocol.IMAP) return openEmailImap(credentials, emailId, markRead)
        val ctx = connect(credentials)
        val email = client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)
        if (markRead && !email.isSeen) {
            // Through setRead, never inline: it also nudges the drawer count, protects the id
            // from the next reconcile (recently-mutated) and advances the mailbox emailState —
            // an inline Email/set here would silently skip all three.
            runCatching { setRead(credentials, emailId, seen = true) }
        }
        return email
    }

    /** IMAP message open: fetch the raw source, parse the body, mark seen when [markRead]. */
    private suspend fun openEmailImap(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): Email {
        val cached = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull()?.toEmail()
            ?: error("Message is not in the cache.")
        val mailboxId = cached.mailboxId ?: error("Unknown mailbox for message.")
        val uid = ImapMailService.uidOf(emailId) ?: error("Not an IMAP message.")
        val raw = imap.fetchSource(credentials, mailboxId, uid)
        val body = MimeParser.parseBody(raw)
        // Refused before parsing: say so rather than render a blank message (the reader turns
        // this into a translated sentence).
        if (body.tooLarge) {
            throw ContentTooLargeException(
                "Message source is ${raw.length} characters, over the ${MimeParser.MAX_BODY_CHARS} parse limit.",
                bytes = raw.length.toLong(),
                maxBytes = MimeParser.MAX_BODY_CHARS.toLong(),
            )
        }
        if (markRead && !cached.isSeen) {
            runCatching { setRead(credentials, emailId, seen = true) }
        }
        // The cache holds no threading headers; lift them from the source so a reply
        // built from this email carries In-Reply-To/References.
        return cached.withBody(body).copy(
            messageId = headerIds(MimeParser.headerOf(raw, "Message-ID")),
            references = headerIds(MimeParser.headerOf(raw, "References")),
        )
    }

    /** The `<id@host>` tokens of a Message-ID/References header, brackets kept for re-emission. */
    private fun headerIds(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val bracketed = Regex("<[^<>]+>").findAll(value).map { it.value }.toList()
        return bracketed.ifEmpty { value.trim().split(Regex("\\s+")).map { "<$it>" } }
    }

    /** Attach a parsed [MimeBody] to a cached [Email] so the message view can render it. */
    private fun Email.withBody(body: MimeBody): Email {
        val attachments = body.attachments.map {
            val isInlineImage = !it.cid.isNullOrBlank() && it.type.startsWith("image/")
            EmailBodyPart(
                partId = it.section,
                name = it.name,
                type = it.type,
                size = it.size.toLong(),
                cid = it.cid,
                disposition = if (isInlineImage) "inline" else "attachment",
                encoding = it.encoding,
            )
        }
        val html = body.html
        val text = body.text
        return when {
            // The type MUST be set: htmlContent() only returns a part typed text/html
            // (issue #4 hardening), so a type-less part renders as an empty body.
            !html.isNullOrBlank() -> copy(
                htmlBody = listOf(EmailBodyPart(partId = "html", type = "text/html")),
                bodyValues = mapOf("html" to EmailBodyValue(value = html)),
                attachments = attachments,
            )
            !text.isNullOrBlank() -> copy(
                textBody = listOf(EmailBodyPart(partId = "text", type = "text/plain")),
                bodyValues = mapOf("text" to EmailBodyValue(value = text)),
                attachments = attachments,
            )
            else -> copy(attachments = attachments)
        }
    }

    // ---- Body cache + prefetch -------------------------------------------------------------

    /**
     * Open a message for display, body cache first: a cached (or prefetched) body renders with
     * no network round-trip; a miss fetches over the network and persists it. Inline images are
     * resolved before returning so the body renders once, complete (no cid: reflow). Marks read
     * when [markRead] (the default); the message pager passes false and marks the settled entry
     * read separately, so flicking past a message does not read it.
     */
    suspend fun openMessage(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): MessageBody {
        // A message decrypted earlier this process renders instantly (memory only,
        // never persisted — see decryptMessage).
        decryptedCache.get(cryptoKey(credentials.id, emailId))?.let { entry ->
            if (markRead) bgScope.launch { runCatching { setRead(credentials, emailId, true) } }
            return entry.body
        }
        cachedMessage(credentials.id, emailId)?.let { cached ->
            // Mark read out of band — the body is already in hand, don't make the user wait.
            if (markRead) bgScope.launch { runCatching { setRead(credentials, emailId, true) } }
            // A prefetched body can hold ciphertext (crypto is only detected on open):
            // route it to the decrypt flow instead of rendering armor.
            cryptoKindOf(cached.email)?.let { kind ->
                return cached.copy(crypto = MessageCrypto.Locked(kind))
            }
            return ensureInlineImages(credentials, emailId, cached)
        }
        val email = openEmail(credentials, emailId, markRead) // network fetch
        cryptoKindOf(email)?.let { kind ->
            // Never persist crypto message bodies — the plaintext (once decrypted)
            // must not land in the Room cache, and the ciphertext body is useless.
            return MessageBody(email, emptyMap(), crypto = MessageCrypto.Locked(kind))
        }
        val inline = fetchInlineImages(credentials, email, emailId)
        persistBody(credentials.id, emailId, email, inline)
        return MessageBody(email, inline)
    }

    /**
     * The raw header fields of a message, in original order with duplicates kept (issue #60).
     * Fetched on demand for the reader's "view headers" action, so the normal open path never
     * pulls headers. Over JMAP this is the cheap `headers` property (no blob download); IMAP has
     * no such index, so it parses the message source (fetched/cached like any other read).
     */
    suspend fun rawHeaders(credentials: AccountCredentials, emailId: String): List<EmailHeader> {
        if (credentials.protocol == MailProtocol.IMAP) {
            val email = cachedEmail(credentials.id, emailId) ?: openEmail(credentials, emailId, markRead = false)
            val raw = fetchRawSource(credentials, email, emailId)
            return MimeParser.rawHeaders(raw).map { (name, value) -> EmailHeader(name, value) }
        }
        val ctx = connect(credentials)
        return client.getEmailHeaders(ctx.session, ctx.accountId, emailId, ctx.auth)
    }

    // ---- OpenPGP read path -------------------------------------------------------------------

    /** Decrypted message bodies + their raw decrypted MIME entity, memory only, small LRU. */
    private class DecryptedEntry(val body: MessageBody, val decryptedEntity: String?)

    // Both crypto caches are keyed "$accountId:$emailId" (see [cryptoKey]): same-server accounts
    // can cache colliding email ids (issue #31), and a bare-id key would serve one account's
    // decrypted body — or raw source — for the sibling account's same-id message.
    private val decryptedCache = android.util.LruCache<String, DecryptedEntry>(8)

    /** Raw sources of crypto messages being decrypted (avoids refetching on interaction retries). */
    private val rawSourceCache = android.util.LruCache<String, String>(4)

    private fun cryptoKey(accountId: String, emailId: String) = "$accountId:$emailId"

    /**
     * Structural check for OpenPGP content on an already-fetched [Email]. Both
     * protocols surface the PGP/MIME control parts as typed attachment parts
     * (JMAP natively; IMAP via MimeParser), and inline armor shows in the text.
     */
    private fun cryptoKindOf(email: Email): CryptoKind? {
        val parts = email.attachments
        if (parts.any { it.type == "application/pgp-encrypted" }) return CryptoKind.PGP_ENCRYPTED
        if (parts.any { it.type == "application/pgp-signature" }) return CryptoKind.PGP_SIGNED
        val text = email.textContent().orEmpty()
        if (text.contains("-----BEGIN PGP MESSAGE-----") ||
            text.contains("-----BEGIN PGP SIGNED MESSAGE-----")
        ) {
            return CryptoKind.PGP_INLINE
        }
        return null
    }

    /** The exact raw RFC 5322 source of a message (IMAP fetch or JMAP blob download). */
    private suspend fun fetchRawSource(
        credentials: AccountCredentials,
        email: Email,
        emailId: String,
    ): String {
        rawSourceCache.get(cryptoKey(credentials.id, emailId))?.let { return it }
        val raw = if (credentials.protocol == MailProtocol.IMAP) {
            val mailboxId = emailDao.mailboxOf(credentials.id, emailId) ?: email.mailboxId
                ?: error("Unknown mailbox for message.")
            val uid = ImapMailService.uidOf(emailId) ?: error("Not an IMAP message.")
            imap.fetchSource(credentials, mailboxId, uid)
        } else {
            val blobId = email.blobId ?: error("Message has no blob id.")
            val ctx = connect(credentials)
            // ISO-8859-1, like the IMAP branch above: a raw message source is a byte container
            // in this app (one char per octet — see MimeParser's KDoc), whichever protocol
            // fetched it. ONE convention, or the readers downstream — MimeParser, and the
            // signature verification that needs the sender's exact bytes — would have to know
            // where the string came from.
            client.downloadBlob(ctx.session, ctx.accountId, blobId, "message/rfc822", "message.eml", ctx.auth)
                .toString(Charsets.ISO_8859_1)
        }
        rawSourceCache.put(cryptoKey(credentials.id, emailId), raw)
        return raw
    }

    /**
     * Decrypt and/or verify an OpenPGP message via the wired [PgpEngine]. On
     * success the rendered body (plus signature state) is returned and kept in a
     * small in-memory cache — nothing decrypted is ever persisted. A
     * [PgpResult.UserInteractionRequired] asks the caller to run the provider's
     * PendingIntent and retry with its result Intent.
     */
    suspend fun decryptMessage(
        credentials: AccountCredentials,
        emailId: String,
        interactionResult: android.content.Intent? = null,
    ): PgpResult<MessageBody> {
        val pgp = pgpEngine ?: return PgpResult.NotAvailable
        decryptedCache.get(cryptoKey(credentials.id, emailId))?.let { return PgpResult.Success(it.body) }
        val email = runCatching { openEmail(credentials, emailId, markRead = false) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot fetch message") }
        val raw = runCatching { fetchRawSource(credentials, email, emailId) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot fetch message source") }
        val envelope = runCatching { MimeParser.detectCrypto(raw) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot parse message structure") }
            ?: return PgpResult.Error("No OpenPGP content found")
        val sender = email.from.firstOrNull()?.email

        // Everything from here talks to the provider and rebuilds the body; contain any
        // escaping exception as an Error so the reader shows the status card instead of
        // crashing (Codeberg #14 — the caller's auto-verify coroutine is also guarded now,
        // but the repository should not throw for anticipated-failure territory either).
        val result = runCatching {
            when (envelope.kind) {
                CryptoKind.PGP_ENCRYPTED, CryptoKind.PGP_INLINE -> pgp.decryptVerify(
                    envelope.encryptedArmor!!.toByteArray(Charsets.UTF_8),
                    senderAddress = sender,
                    interactionResult = interactionResult,
                )
                // ISO-8859-1, not UTF-8: the signed entity is a verbatim slice of the message
                // source, which is a byte container (one char per wire byte — see MimeParser's
                // KDoc). Only this reading hands the verifier the exact octets the sender
                // hashed; UTF-8 would re-encode every 8-bit byte and the signature would not
                // match. Armor is ASCII either way.
                CryptoKind.PGP_SIGNED -> pgp.decryptVerify(
                    canonicalizeCrlf(envelope.signedEntityRaw!!).toByteArray(Charsets.ISO_8859_1),
                    senderAddress = sender,
                    detachedSignature = envelope.signatureArmor!!.toByteArray(Charsets.UTF_8),
                    interactionResult = interactionResult,
                )
            }
        }.getOrElse { return PgpResult.Error(it.message ?: it.javaClass.simpleName) }
        return when (result) {
            is PgpResult.Success -> {
                val entry = runCatching { buildDecrypted(email, envelope, result.value) }
                    .getOrElse { return PgpResult.Error(it.message ?: "Cannot rebuild decrypted body") }
                decryptedCache.put(cryptoKey(credentials.id, emailId), entry)
                rawSourceCache.remove(cryptoKey(credentials.id, emailId))
                PgpResult.Success(entry.body)
            }
            is PgpResult.UserInteractionRequired -> result
            is PgpResult.Error -> result
            PgpResult.NotAvailable -> PgpResult.NotAvailable
        }
    }

    /** RFC 3156: signatures are computed over the entity with CRLF line endings. */
    private fun canonicalizeCrlf(entity: String): String =
        entity.replace("\r\n", "\n").replace("\n", "\r\n")

    /** Assemble the rendered [MessageBody] for a successful decrypt/verify. */
    private fun buildDecrypted(
        email: Email,
        envelope: CryptoEnvelope,
        decrypted: PgpDecrypted,
    ): DecryptedEntry {
        val crypto = MessageCrypto.Decrypted(
            signature = decrypted.signature,
            signatureUserId = decrypted.signatureUserId,
            signatureKeyId = decrypted.signatureKeyId,
            wasEncrypted = decrypted.wasEncrypted || envelope.kind == CryptoKind.PGP_ENCRYPTED,
        )
        return when (envelope.kind) {
            CryptoKind.PGP_ENCRYPTED -> {
                // The plaintext is a full MIME entity: parse it, mark its parts as
                // "pgp:" sections so attachment downloads slice from the decrypted
                // entity in memory, and resolve inline images locally.
                //
                // ISO-8859-1 because MimeParser takes a byte container, not text (see its KDoc):
                // this entity has its own Content-Type charsets and its own attachments, and
                // decoding it as UTF-8 here would decide that question before the parser can
                // read it — and lose the octets of any attachment inside it.
                val entity = decrypted.plaintext.toString(Charsets.ISO_8859_1)
                val body = MimeParser.parseBody(entity)
                val display = email.withBody(body).run {
                    copy(
                        attachments = attachments.map { part ->
                            part.copy(partId = part.partId?.let { "pgp:$it" })
                        },
                        // The armor octet-stream/version parts of the outer message
                        // are irrelevant once decrypted.
                        hasAttachment = body.attachments.any { it.cid == null },
                    )
                }
                val inline = display.inlineImageParts().mapNotNull { part ->
                    val section = part.partId?.removePrefix("pgp:") ?: return@mapNotNull null
                    val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    val (cte, encoded) = MimeParser.partAt(entity, section) ?: return@mapNotNull null
                    val bytes = MimeParser.decodeBytes(encoded, cte)
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    cid to "data:${part.type ?: "image/jpeg"};base64,$base64"
                }.toMap()
                DecryptedEntry(MessageBody(display, inline, crypto), entity)
            }
            CryptoKind.PGP_INLINE -> {
                val text = decrypted.plaintext.toString(Charsets.UTF_8)
                val display = email.copy(
                    htmlBody = emptyList(),
                    textBody = listOf(EmailBodyPart(partId = "text")),
                    bodyValues = mapOf("text" to EmailBodyValue(value = text)),
                )
                DecryptedEntry(MessageBody(display, emptyMap(), crypto), null)
            }
            CryptoKind.PGP_SIGNED -> {
                // Content was already readable; only the verification state is new.
                DecryptedEntry(MessageBody(email, emptyMap(), crypto), null)
            }
        }
    }

    /** Serve an attachment that lives inside a decrypted entity ("pgp:<section>"). */
    private fun pgpAttachmentBytes(accountId: String, emailId: String, part: EmailBodyPart): ByteArray {
        val section = part.partId?.removePrefix("pgp:")
            ?: error("Not a decrypted attachment part.")
        val entity = decryptedCache.get(cryptoKey(accountId, emailId))?.decryptedEntity
            ?: error("Message is no longer decrypted — reopen it first.")
        val (cte, encoded) = MimeParser.partAt(entity, section)
            ?: error("Attachment not found in the decrypted message.")
        return MimeParser.decodeBytes(encoded, cte)
    }

    /**
     * [accountId]'s cached body for [emailId], or null if not yet fetched/prefetched. No network.
     *
     * The READ is inside the guard, not just the decoding: a row whose JSON is past SQLite's
     * cursor window throws on every read (SQLiteBlobTooBigException), and with the read left
     * outside, that exception escaped all the way to the reader — the message was then
     * permanently unopenable, not just uncached. A row we cannot read is dropped so the next
     * open refetches it.
     */
    suspend fun cachedMessage(accountId: String, emailId: String): MessageBody? = readCachedOrPurge(
        read = {
            val row = emailBodyDao.byId(accountId, emailId) ?: return@readCachedOrPurge null
            MessageBody(
                email = cacheJson.decodeFromString(Email.serializer(), row.bodyJson),
                inlineImages = cacheJson.decodeFromString(inlineImagesSerializer, row.inlineImagesJson),
            )
        },
        purge = { emailBodyDao.deleteById(accountId, emailId) },
    )

    /** Inline images present if the body needs them; downloads + persists them on first open. */
    private suspend fun ensureInlineImages(
        credentials: AccountCredentials,
        emailId: String,
        cached: MessageBody,
    ): MessageBody {
        if (cached.email.inlineImageParts().isEmpty() || cached.inlineImages.isNotEmpty()) return cached
        val inline = fetchInlineImages(credentials, cached.email, emailId)
        if (inline.isNotEmpty()) persistBody(credentials.id, emailId, cached.email, inline)
        return cached.copy(inlineImages = inline)
    }

    /**
     * Download a message's inline images as `data:` URIs keyed by Content-ID.
     *
     * Nobody asked for these: opening the message is enough, and each one is then base64-encoded
     * and cached at roughly four times its own weight. Hence the tighter ceiling — an image past
     * it is not fetched, and the reader says so (see [oversizedInlineImages]).
     */
    private suspend fun fetchInlineImages(
        credentials: AccountCredentials,
        email: Email,
        emailId: String,
    ): Map<String, String> {
        val parts = email.inlineImageParts()
        if (parts.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (part in parts) {
            val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() } ?: continue
            runCatching {
                val bytes = downloadAttachment(
                    credentials, part, emailId, DownloadLimits.INLINE_IMAGE_MAX_BYTES,
                )
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                map[cid] = "data:${part.type ?: "image/jpeg"};base64,$base64"
            }
        }
        return map
    }

    /**
     * Persist a fetched body (and any inline images) to the cache, then LRU-prune the account.
     *
     * A row bigger than SQLite's cursor window can be written but never read back, so an
     * oversized body is simply not cached: it costs a refetch on every open, which is the honest
     * price, where writing it would poison the cache with a row that throws on every read.
     */
    private suspend fun persistBody(
        accountId: String,
        emailId: String,
        email: Email,
        inlineImages: Map<String, String>,
    ) {
        runCatching {
            val bodyJson = cacheJson.encodeToString(Email.serializer(), email)
            val inlineJson = cacheJson.encodeToString(inlineImagesSerializer, inlineImages)
            if (!fitsBodyCache(bodyJson, inlineJson)) return
            emailBodyDao.upsert(
                EmailBodyEntity(
                    id = emailId,
                    accountId = accountId,
                    bodyJson = bodyJson,
                    inlineImagesJson = inlineJson,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            emailBodyDao.pruneForAccount(accountId, BODY_CACHE_CAP)
        }
    }

    /**
     * Prefetch the newest [PREFETCH_COUNT] inbox bodies for an account into the cache so opening
     * them is instant. JMAP only (one batched Email/get); skips ids already cached. Body only —
     * inline images fill in on first open. Best-effort: never throws into the caller.
     */
    private suspend fun prefetchInboxBodies(credentials: AccountCredentials, mailboxId: String) {
        if (credentials.protocol == MailProtocol.IMAP) return
        runCatching {
            val newest = emailDao.getByMailbox(credentials.id, mailboxId)
                .take(PREFETCH_COUNT)
                .map { it.id }
            if (newest.isEmpty()) return
            val already = emailBodyDao.cachedIds(credentials.id, newest).toSet()
            val missing = newest.filter { it !in already }
            if (missing.isEmpty()) return
            val ctx = connect(credentials)
            val emails = client.getEmailsWithBody(ctx.session, ctx.accountId, missing, ctx.auth)
            for (email in emails) persistBody(credentials.id, email.id, email, emptyMap())
        }
    }

    suspend fun setRead(credentials: AccountCredentials, emailId: String, seen: Boolean) {
        markRecentlyMutated(credentials.id, emailId)
        // Capture before the change so we only move the folder counter on a real transition.
        val wasSeen = emailDao.seenOf(credentials.id, emailId)
        val mailboxId = emailDao.mailboxOf(credentials.id, emailId)
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Seen", seen) }
            emailDao.setSeen(credentials.id, emailId, seen)
            adjustFolderUnreadOnRead(credentials.id, mailboxId, wasSeen, seen)
            return
        }
        val ctx = connect(credentials)
        val newState = try {
            client.setSeen(ctx.session, ctx.accountId, emailId, seen, ctx.auth)
        } catch (e: JmapException) {
            // A per-id notFound is authoritative: the id no longer exists in the account
            // (destroyed server-side while we had it cached). Prune the zombie row instead
            // of leaving a bold ghost that can never be marked read. Any other failure
            // (offline, transient) keeps the row untouched.
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return
        }
        emailDao.setSeen(credentials.id, emailId, seen)
        adjustFolderUnreadOnRead(credentials.id, mailboxId, wasSeen, seen)
        advanceEmailState(newState, credentials.id, mailboxId)
    }

    /**
     * Mark many messages read/unread. JMAP: ONE `Email/set` per [SET_SEEN_BATCH]-id chunk
     * instead of one round trip per message; local seen state, the folder-count nudges
     * (grouped per folder like [adjustCountsForRemoval]) and the reconcile protection are
     * applied only to ids the server confirmed. Ids beyond the cache (resolved server-side
     * by [unreadIds]) have no row to nudge from — the caller's reconciling refresh converges
     * their counters. IMAP flags are per-message, so that branch stays the per-id [setRead]
     * path (its targets are cache-bounded anyway).
     */
    suspend fun setReadAll(credentials: AccountCredentials, emailIds: List<String>, seen: Boolean) {
        if (emailIds.isEmpty()) return
        if (credentials.protocol == MailProtocol.IMAP) {
            emailIds.forEach { runCatching { setRead(credentials, it, seen) } }
            return
        }
        val ctx = connect(credentials)
        emailIds.chunked(SET_SEEN_BATCH).forEach { chunk ->
            // Captured before the write so only real transitions nudge the counters (#46).
            val rows = emailDao.emailsByIds(credentials.id, chunk).associateBy { it.id }
            val result = client.setSeenAll(ctx.session, ctx.accountId, chunk, seen, ctx.auth)
            // Per-id notFound rejections are ghosts (destroyed server-side) — prune them
            // instead of leaving zombie rows that can never change state (see setRead).
            val gone = chunk.filter { result.failed[it] == SET_ERROR_NOT_FOUND }
            if (gone.isNotEmpty()) pruneServerGone(credentials.id, gone)
            val done = chunk.filter { it in result.done }
            done.forEach { markRecentlyMutated(credentials.id, it); emailDao.setSeen(credentials.id, it, seen) }
            done.mapNotNull { rows[it] }.filter { it.seen != seen }
                .groupBy { it.mailboxId }
                .forEach { (mailboxId, group) ->
                    val delta = if (seen) -group.size else group.size
                    accountStore.adjustInboxUnread(credentials.id, mailboxId, delta)
                    mailboxDao.adjustCounts(credentials.id, mailboxId, totalDelta = 0, unreadDelta = delta)
                }
            advanceEmailState(result.newState, credentials.id, *done.mapNotNull { rows[it]?.mailboxId }.toTypedArray())
        }
    }

    /** Keep the drawer's cached folder unread counter fresh on a local read/unread (sync corrects
     *  drift). Mirrors [adjustCountsForMove]; only moves the count on a real state change (#46). */
    private suspend fun adjustFolderUnreadOnRead(accountId: String, mailboxId: String?, wasSeen: Boolean?, seen: Boolean) {
        if (mailboxId == null || wasSeen == null || wasSeen == seen) return
        val delta = if (seen) -1 else 1
        accountStore.adjustInboxUnread(accountId, mailboxId, delta)
        if (!isImapAccount(accountId)) mailboxDao.adjustCounts(accountId, mailboxId, totalDelta = 0, unreadDelta = delta)
    }

    /**
     * IMAP folder rows carry no server unread counts (a hard 0 — populating them would cost one
     * STATUS round-trip per folder on every listing), so the drawer shows no IMAP badges. Nudging
     * deltas onto that 0 baseline would manufacture transient bogus badges (e.g. "Trash (1)"
     * after deleting an unread) that the next refresh wipes — gate the folder-row nudges off so
     * IMAP badges are consistently absent rather than sporadically wrong. The stored inbox meta
     * is NOT gated: its baseline is the real SEARCH UNSEEN count from the last refresh.
     */
    private fun isImapAccount(accountId: String): Boolean =
        accountStore.account(accountId)?.protocol == MailProtocol.IMAP

    suspend fun setFlagged(credentials: AccountCredentials, emailId: String, flagged: Boolean) {
        markRecentlyMutated(credentials.id, emailId)
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Flagged", flagged) }
            emailDao.setFlagged(credentials.id, emailId, flagged)
            return
        }
        val ctx = connect(credentials)
        val mb = emailDao.mailboxOf(credentials.id, emailId)
        val newState = try {
            client.setKeyword(ctx.session, ctx.accountId, emailId, "\$flagged", flagged, ctx.auth)
        } catch (e: JmapException) {
            // notFound = destroyed server-side; prune the zombie (see setRead).
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return
        }
        emailDao.setFlagged(credentials.id, emailId, flagged)
        advanceEmailState(newState, credentials.id, mb)
    }

    /** Source (mailbox path, UID) for an IMAP message id, or null if not parseable. */
    private fun imapTarget(emailId: String): Pair<String, Long>? {
        val mb = ImapMailService.mailboxOf(emailId) ?: return null
        val uid = ImapMailService.uidOf(emailId) ?: return null
        return mb to uid
    }

    /**
     * Archive a message: move it to the account's archive location and drop it from the list.
     * Resolution order — a real Archive folder, else All Mail (Gmail-style accounts have no
     * Archive folder; "archiving" just drops the Inbox membership, leaving the message in \All),
     * else create an Archive folder as a last resort.
     */
    suspend fun archive(credentials: AccountCredentials, emailId: String): String? {
        // Leaving a folder withdraws the message from any pending Empty-trash order (#99).
        unlistFromTrashPurge(credentials.id, listOf(emailId))
        // Opt-in: flag the message read on its way out, so the archive doesn't accumulate
        // unread badges. Best-effort BEFORE the move (the id changes with an IMAP move) and
        // before [row] is read, so the count nudge below sees the new seen state; a failure
        // must never block the archiving itself (Codeberg #67).
        if (settings?.markReadOnArchive?.first() == true && emailDao.seenOf(credentials.id, emailId) == false) {
            runCatching { setRead(credentials, emailId, true) }
        }
        val row = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull()
        if (credentials.protocol == MailProtocol.IMAP) {
            val dest = imapRoleFolder(credentials, "archive", "all")
                ?: run { imap.createFolder(credentials, "Archive"); "Archive" }
            var noop = false
            imapTarget(emailId)?.let { (mb, uid) ->
                if (mb == dest) { noop = true; return@let } // already in the archive/all folder
                imap.move(credentials, mb, uid, dest)?.let {
                    lastImapMove[emailId] = ImapLoc(dest, it)
                    recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, dest, it))
                }
            }
            if (noop) {
                evictAlreadyThere(credentials.id, dest, listOf(emailId))
                return null
            }
            emailDao.deleteById(credentials.id, emailId)
            adjustCountsForRemoval(listOfNotNull(row), dest)
            return dest
        }
        val ctx = connect(credentials)
        val mb = row?.mailboxId ?: emailDao.mailboxOf(credentials.id, emailId)
        val target = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: createArchiveFolder(ctx)
        if (mb == target) return null // already in the archive/all folder — nothing to do
        // Network-first like moveToMailbox: the local row is dropped (and counts nudged) only
        // after the server acknowledged, so a failed archive never hides a message that is
        // still on the server.
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, target, ctx.auth)
        } catch (e: JmapException) {
            // notFound = destroyed server-side; prune the zombie and report a no-op
            // (nothing was moved, so there is nothing to Undo). See setRead.
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return null
        }
        recentLocalMoves.mark(credentials.id, emailId)
        emailDao.deleteById(credentials.id, emailId)
        adjustCountsForRemoval(listOfNotNull(row), target)
        advanceEmailState(newState, credentials.id, mb)
        return target
    }

    /**
     * Whether deleting [email] would permanently destroy it (it already sits in Trash, or
     * the account has no Trash) rather than move it there. The UI uses this to hold the
     * destroy back behind an Undo window instead of doing it immediately (Codeberg #23).
     */
    suspend fun deleteWouldDestroy(credentials: AccountCredentials, email: Email): Boolean {
        val trash = roleMailboxId(credentials, "trash") ?: return true
        return email.mailboxId == trash
    }

    /** Move a message to an arbitrary mailbox (e.g. unarchive → Inbox, or move-to-folder).
     *  Returns the destination the message ended up in (null = a no-op, already there), so the
     *  caller can offer an Undo that both restores the row and reverses the count nudge. */
    suspend fun moveToMailbox(credentials: AccountCredentials, emailId: String, targetMailboxId: String): String? {
        // The rescue path of #99: filing a message somewhere else during the Empty-trash undo
        // window takes it off the destroy list instead of destroying it in its new folder.
        unlistFromTrashPurge(credentials.id, listOf(emailId))
        markReadOnMoveOutOfInbox(credentials, listOf(emailId), targetMailboxId)
        // Captured before the local row is dropped, to decrement the TRUE source and increment the
        // destination in the drawer's cached counts (INV-COUNT). The next mailbox-state sync
        // (getMailboxes on every refresh) corrects any drift.
        val moved = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull()
        if (moved != null && moved.mailboxId == targetMailboxId) return null
        // Network-first (the local drop follows a successful move): this path is also reached by
        // the reader's report-spam / not-spam, which don't restore the row on failure, so a failed
        // move must never leave the cache short of a row that is still on the server.
        if (credentials.protocol == MailProtocol.IMAP) {
            val already = imapTarget(emailId)?.let { (mb, uid) ->
                if (mb == targetMailboxId) return@let true
                imap.move(credentials, mb, uid, targetMailboxId)?.let {
                    lastImapMove[emailId] = ImapLoc(targetMailboxId, it)
                    recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, targetMailboxId, it))
                }
                false
            } ?: false
            if (already) {
                evictAlreadyThere(credentials.id, targetMailboxId, listOf(emailId))
                return null
            }
            emailDao.deleteById(credentials.id, emailId)
            adjustCountsForRemoval(listOfNotNull(moved), targetMailboxId)
            return targetMailboxId
        }
        val ctx = connect(credentials)
        val mb = moved?.mailboxId ?: emailDao.mailboxOf(credentials.id, emailId)
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, targetMailboxId, ctx.auth)
        } catch (e: JmapException) {
            // notFound = destroyed server-side; prune the zombie, report a no-op (see archive).
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return null
        }
        recentLocalMoves.mark(credentials.id, emailId)
        emailDao.deleteById(credentials.id, emailId)
        adjustCountsForRemoval(listOfNotNull(moved), targetMailboxId)
        advanceEmailState(newState, credentials.id, mb)
        return targetMailboxId
    }

    /**
     * Nudge the drawer's cached folder counters when [rows] leave their source folder(s) into
     * [destMailboxId] (null when they are destroyed / emptied). Each row is decremented from its
     * OWN mailboxId, so deleting from Inbox hits Inbox and from Archive hits Archive (INV-SOURCE);
     * the destination is incremented for a move. Unread deltas count only unseen rows. Counts are
     * optimistic — the next mailbox-state sync (getMailboxes) reconciles them to server truth.
     */
    private suspend fun adjustCountsForRemoval(rows: List<EmailEntity>, destMailboxId: String?) =
        rows.groupBy { it.accountId }.forEach { (accountId, group) ->
            nudgeCounts(accountId, group.map { it.mailboxId to it.seen }, destMailboxId)
        }

    /**
     * The count-nudge primitive: [sources] is (sourceMailboxId, seen) for each row leaving its
     * folder into [destMailboxId] (null = destroyed). Decrements each distinct source, increments
     * the destination. An Undo reuses this in reverse — the "source" of the reverse move is the
     * folder the message currently sits in (Trash/Archive/dest) and the "dest" is where it goes
     * home to — so restoring counts is just another removal. Scoped to the acting [accountId]:
     * same-server accounts can share bare mailbox ids, and an unscoped nudge from the unified
     * inbox would move a sibling account's badge. Nudges that touch the account's inbox are
     * mirrored into its stored inbox meta so "All inboxes (N)" moves with the badge.
     */
    private suspend fun nudgeCounts(accountId: String, sources: List<Pair<String, Boolean>>, destMailboxId: String?) {
        val imap = isImapAccount(accountId)
        sources.groupBy { it.first }.forEach { (src, group) ->
            if (src == destMailboxId) return@forEach
            val unread = group.count { !it.second }
            accountStore.adjustInboxUnread(accountId, src, -unread)
            if (!imap) mailboxDao.adjustCounts(accountId, src, totalDelta = -group.size, unreadDelta = -unread)
        }
        if (destMailboxId != null) {
            val incoming = sources.filter { it.first != destMailboxId }
            if (incoming.isNotEmpty()) {
                val unread = incoming.count { !it.second }
                accountStore.adjustInboxUnread(accountId, destMailboxId, unread)
                if (!imap) mailboxDao.adjustCounts(accountId, destMailboxId, totalDelta = incoming.size, unreadDelta = unread)
            }
        }
    }

    // ---- bulk (batched) actions (Codeberg #29) ----------------------------------------
    // One server round-trip group per (account, source folder) instead of one command per
    // message, so a several-hundred-message archive/move/delete goes through in one shot.
    // The caller (InboxViewModel) groups the selection by account first; each method here
    // takes one account's ids, then (IMAP) groups them by source folder.

    /** Which ids of a batch actually went through, and which failed — for the bulk snackbar/toast.
     *  [dest] is the folder the succeeded ids were moved to (null = destroyed / no single dest),
     *  so an undoable bulk op can reverse the drawer-count nudge on Undo. */
    class BulkResult(val succeeded: Set<String>, val failed: Set<String>, val dest: String? = null) {
        companion object { val EMPTY = BulkResult(emptySet(), emptySet()) }
    }

    /**
     * The bulk paths' cache+index removal: `EmailDao.deleteByIds` over [ids], chunked to what
     * SQLite will bind in one `IN (...)` — [MAX_CHANGES], the bound the ghost sweep and the
     * retention prune already use for their own id lists.
     *
     * They each used to call the single-id form in a loop. `email_fts` is FTS4 with `emailId`
     * declared `notindexed`, so one un-index is a full scan of the whole index: a select-all of
     * 200 messages meant 200 scans where one statement does.
     */
    private suspend fun deleteFromCacheAndIndex(accountId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.chunked(MAX_CHANGES).forEach { emailDao.deleteByIds(accountId, it) }
    }

    /**
     * The local cleanup of an action that MOVED NOTHING: archive or move asked for [mailboxId], the
     * folder the message is already in, so the server was never touched and the message is exactly
     * where it was. The cached row still goes (the list is showing a folder the user just acted on,
     * and the row comes back with the next page like any other); whether the search-index row goes
     * with it depends on the FOLDER, not on the action — [noOpEvictionFor] decides, this only
     * carries the decision out.
     *
     * In a searched folder the row must stay. This is `EmailDao.deleteNotIn`'s case, not
     * `deleteByIds`': the message is untouched on the server and its index row is still true, and
     * these are the swipe paths, the most exercised of all. Paging deep into Archive past the sync
     * window and re-archiving what is already there took both rows away, and nothing brought the
     * index row back — the IMAP crawl does not exist and the re-seed only rewrites rows whose
     * message is still cached. Those messages left offline search for good.
     *
     * In Trash/Junk/Spam it must go. The same two branches are how the app files a message into Junk
     * (report-spam onto one already there) and how the folder picker files one into the Trash it is
     * already in — and there the index covers nothing at either end, so sparing the row preserves no
     * coverage and merely leaves an orphan the re-seed can never clear. The delete paths ([delete],
     * [deleteAll]) reach for [deleteFromCacheAndIndex] directly for the same reason.
     *
     * The role comes from the folder cache; what an unknown one gets is [noOpEvictionFor]'s to say.
     *
     * Chunked like [deleteFromCacheAndIndex]: the ids go into one `IN (...)`.
     */
    private suspend fun evictAlreadyThere(accountId: String, mailboxId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        when (noOpEvictionFor(mailboxId, mailboxDao.roleForId(accountId, mailboxId))) {
            NoOpEviction.SPARE_INDEX_ROW ->
                ids.chunked(MAX_CHANGES).forEach { emailDao.evictFromCacheKeepingIndex(accountId, it) }
            NoOpEviction.TAKE_INDEX_ROW -> deleteFromCacheAndIndex(accountId, ids)
        }
    }

    /**
     * Batch-move one IMAP source folder's [ids] to [dest] (dest != source) with a single
     * `UID MOVE <set>`, recording each id's new destination UID (from COPYUID) in
     * [lastImapMove] so Undo can move the whole set back. Ids whose UID can't be parsed, or
     * the whole group if the command fails, are marked failed.
     */
    private suspend fun imapMoveGroup(
        credentials: AccountCredentials, source: String, ids: List<String>, dest: String,
        succeeded: MutableSet<String>, failed: MutableSet<String>,
    ) {
        val uidToId = ids.mapNotNull { id -> ImapMailService.uidOf(id)?.let { it to id } }.toMap()
        failed += ids.filter { ImapMailService.uidOf(it) == null }
        if (uidToId.isEmpty()) return
        // Captured before the local rows are dropped, to nudge the drawer counts (INV-COUNT).
        val rows = emailDao.emailsByIds(credentials.id, uidToId.values.toList())
        runCatching { imap.moveBatch(credentials, source, uidToId.keys.toList(), dest) }
            .onSuccess { mapping ->
                uidToId.forEach { (uid, id) ->
                    mapping[uid]?.let {
                        lastImapMove[id] = ImapLoc(dest, it)
                        recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, dest, it))
                    }
                }
                deleteFromCacheAndIndex(credentials.id, uidToId.values)
                succeeded += uidToId.values
                adjustCountsForRemoval(rows.filter { it.id in uidToId.values }, dest)
            }
            .onFailure { failed += uidToId.values }
    }

    /** Batch-destroy one IMAP folder's [ids] permanently with a single `UID STORE`+`UID EXPUNGE`.
     *  A failed command THROWS (transport-level, retryable) rather than marking the ids failed:
     *  the held-back destroy worker must retry a user-confirmed destroy, not abandon it. */
    private suspend fun imapDestroyGroup(
        credentials: AccountCredentials, source: String, ids: List<String>,
        succeeded: MutableSet<String>, failed: MutableSet<String>,
        expectedUidValidity: Long? = null,
    ) {
        val uidToId = ids.mapNotNull { id -> ImapMailService.uidOf(id)?.let { it to id } }.toMap()
        failed += ids.filter { ImapMailService.uidOf(it) == null }
        if (uidToId.isEmpty()) return
        val rows = emailDao.emailsByIds(credentials.id, uidToId.values.toList())
        imap.deleteBatch(credentials, source, uidToId.keys.toList(), expectedUidValidity)
        deleteFromCacheAndIndex(credentials.id, uidToId.values)
        succeeded += uidToId.values
        adjustCountsForRemoval(rows, destMailboxId = null)
    }

    /** JMAP: move every id to exactly [target] in one `Email/set`, then drop the local rows.
     *  Only ids the server confirmed moved are dropped — a per-id `notUpdated` (wrong account,
     *  destroyed elsewhere, …) keeps its row and lands in [BulkResult.failed].
     *
     *  The ids that were ALREADY in [target] are among the confirmed ones: an `Email/set` that files
     *  a message into the folder it is in succeeds, having done nothing. Those are the bulk twin of
     *  the single-message paths' `mb == target` short-circuit and go to [evictAlreadyThere], which
     *  weighs the folder — so archiving a selection that is already archived no longer un-indexes
     *  messages sitting untouched in a searchable folder. [nudgeCounts] already skipped this case;
     *  the index did not. */
    private suspend fun jmapMoveAll(ctx: Context, emailIds: List<String>, target: String): BulkResult {
        val localAccountId = ctx.credentials.id
        val rows = emailDao.emailsByIds(localAccountId, emailIds)
        return runCatching { client.move(ctx.session, ctx.accountId, emailIds, target, ctx.auth) }
            .map { result ->
                val moved = emailIds.filter { it in result.done }.toSet()
                moved.forEach { recentLocalMoves.mark(localAccountId, it) }
                val alreadyThere = idsAlreadyIn(rows, moved, target)
                evictAlreadyThere(localAccountId, target, alreadyThere)
                deleteFromCacheAndIndex(localAccountId, moved - alreadyThere.toSet())
                adjustCountsForRemoval(rows.filter { it.id in moved }, target)
                // notFound rejections are ghosts (destroyed server-side): prune their rows so
                // they leave the list, but keep them in `failed` — nothing was moved to [target],
                // so they must not feed a move-Undo.
                pruneServerGone(localAccountId, emailIds.filter { result.failed[it] == SET_ERROR_NOT_FOUND })
                BulkResult(moved, emailIds.toSet() - moved, dest = target)
            }
            .getOrElse { BulkResult(emptySet(), emailIds.toSet()) }
    }

    /** JMAP: destroy every id in one `Email/set`, then drop the local rows (confirmed ids only,
     *  like [jmapMoveAll]). A failed request THROWS (transport-level, retryable) rather than
     *  marking the ids failed — see [imapDestroyGroup]; per-id `notDestroyed` rejections land
     *  in [BulkResult.failed]. */
    private suspend fun jmapDestroyAll(ctx: Context, emailIds: List<String>): BulkResult {
        val localAccountId = ctx.credentials.id
        val rows = emailDao.emailsByIds(localAccountId, emailIds)
        val result = client.destroy(ctx.session, ctx.accountId, emailIds, ctx.auth)
        val destroyed = emailIds.filter { it in result.done }.toSet()
        deleteFromCacheAndIndex(localAccountId, destroyed)
        adjustCountsForRemoval(rows.filter { it.id in destroyed }, destMailboxId = null)
        // A notFound rejection means the id was ALREADY destroyed (e.g. server-side by another
        // client) — the requested end state holds, so prune the row (no count nudge: the server's
        // counts never included it) and report success rather than a spurious per-id failure.
        val gone = emailIds.filter { result.failed[it] == SET_ERROR_NOT_FOUND }.toSet()
        pruneServerGone(localAccountId, gone.toList())
        return BulkResult(destroyed + gone, emailIds.toSet() - destroyed - gone)
    }

    /**
     * Opt-in mark-read-on-archive/delete for a bulk action: flag the currently-unread part of the
     * selection through the batched [setReadAll] (chunked `Email/set`) instead of one round trip
     * per message — a select-all of 200 unread used to fire 200 sequential calls before the single
     * batched move. The unread subset comes from ONE cached read; ids we have no row for are left
     * alone (their state is unknown, exactly as the old per-id `seenOf(id) == false` filter did).
     *
     * MUST run BEFORE the mover reads its rows for the count nudge: [adjustCountsForRemoval] then
     * sees `seen = true` and does not decrement the unread badge a second time. Best-effort: a
     * failed flag store never blocks the archive/delete.
     */
    private suspend fun markSelectionRead(credentials: AccountCredentials, emailIds: List<String>) {
        // Account-scoped read (issue #31): same-server sub-accounts can hold colliding email ids,
        // so an unscoped lookup could flag a sibling account's message.
        val unread = emailDao.emailsByIds(credentials.id, emailIds).filter { !it.seen }.map { it.id }
        if (unread.isEmpty()) return
        runCatching { setReadAll(credentials, unread, true) }
    }

    /**
     * Opt-in mark-read-on-move (Codeberg #67), deliberately scoped to messages LEAVING the Inbox:
     * only ids whose SOURCE mailbox is this account's Inbox are flagged. Reorganising between two
     * other folders is left alone, and so is a move INTO the Inbox (unarchive, Not spam) — the
     * message was brought back precisely to be read. Report spam counts: it is a message leaving
     * the Inbox. Called at the top of the movers, so the flag store still runs BEFORE they read
     * their rows for the count nudge ([markSelectionRead]); resolving the Inbox is best-effort.
     */
    private suspend fun markReadOnMoveOutOfInbox(
        credentials: AccountCredentials,
        emailIds: List<String>,
        targetMailboxId: String,
    ) {
        if (settings?.markReadOnMove?.first() != true) return
        val inbox = runCatching { roleMailboxId(credentials, "inbox") }.getOrNull() ?: return
        if (targetMailboxId == inbox) return
        val leaving = if (credentials.protocol == MailProtocol.IMAP) {
            emailIds.filter { ImapMailService.mailboxOf(it) == inbox }
        } else {
            // Account-scoped (issue #31): resolve source folders within this account only.
            emailDao.emailsByIds(credentials.id, emailIds).filter { it.mailboxId == inbox }.map { it.id }
        }
        if (leaving.isNotEmpty()) markSelectionRead(credentials, leaving)
    }

    /** Archive a whole selection (one account). Messages already in the archive/all folder are dropped locally. */
    suspend fun archiveAll(credentials: AccountCredentials, emailIds: List<String>): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        unlistFromTrashPurge(credentials.id, emailIds) // leaving a folder cancels a pending purge order (#99)
        // Opt-in mark-read-on-archive: best-effort before the move (a \Seen store keeps the UID).
        if (settings?.markReadOnArchive?.first() == true) markSelectionRead(credentials, emailIds)
        if (credentials.protocol == MailProtocol.IMAP) {
            val dest = imapRoleFolder(credentials, "archive", "all")
                ?: run { imap.createFolder(credentials, "Archive"); "Archive" }
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null -> failed += ids
                    // Already in the archive: nothing moved — and whether the index row stays is
                    // the destination's role to decide, not this branch's ([evictAlreadyThere]).
                    // An archive/all folder is searched, so here it does stay; the same call in
                    // [moveAllToMailbox] can land on a Junk or Trash destination, where it goes.
                    source == dest -> { evictAlreadyThere(credentials.id, dest, ids); succeeded += ids }
                    else -> imapMoveGroup(credentials, source, ids, dest, succeeded, failed)
                }
            }
            return BulkResult(succeeded, failed, dest = dest)
        }
        val ctx = connect(credentials)
        val target = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: createArchiveFolder(ctx)
        return jmapMoveAll(ctx, emailIds, target)
    }

    /**
     * Codeberg #50 (opt-in): when a genuinely-new reply lands in the Inbox, pull the thread's
     * archived members back so the Inbox conversation is whole again. JMAP only — IMAP has no
     * thread ids, so it can never resolve members to move. The archive resolves like
     * [archiveAll] minus creation (no archive folder means nothing is archived). Server-first
     * (one bulk `Email/set`); the confirmed rows are then RE-FILED locally into the Inbox
     * rather than dropped — the caller's inbox refresh already ran, so dropping them would
     * leave the conversation torn until the next pass — and marked recently-mutated so
     * neither reconcile path prunes them while the server catches up on the move. Per-id
     * rejections simply stay archived. Returns the re-filed members as Inbox emails so the
     * caller can fold them into its notifier baseline.
     */
    suspend fun unarchiveThreadsOnReply(credentials: AccountCredentials, threadIds: Set<String>): List<Email> {
        if (threadIds.isEmpty() || credentials.protocol == MailProtocol.IMAP) return emptyList()
        val ctx = connect(credentials)
        val inbox = ctx.rolesToMailboxId["inbox"] ?: return emptyList()
        val archive = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: return emptyList()
        if (archive == inbox) return emptyList()
        val members = emailDao.threadMembersInMailbox(credentials.id, archive, threadIds.toList())
        if (members.isEmpty()) return emptyList()
        // Uniform rule (#99): whatever moves leaves the destroy list. These members come from the
        // Archive, so in practice there is nothing to withdraw — the rule is kept exceptionless
        // rather than reasoned about at each call site.
        unlistFromTrashPurge(credentials.id, members.map { it.id })
        // Protect the rows BEFORE the server call, so a sync firing mid-move can't evict them.
        members.forEach { markRecentlyMutated(credentials.id, it.id) }
        val result = runCatching {
            client.move(ctx.session, ctx.accountId, members.map { it.id }, inbox, ctx.auth)
        }.getOrNull() ?: return emptyList()
        val moved = members.filter { it.id in result.done }
        if (moved.isEmpty()) return emptyList()
        // Self-moves too: the caller folds them into the baseline unannounced already, but a
        // concurrent pass on another watched folder must not see them as fresh either.
        moved.forEach { recentLocalMoves.mark(credentials.id, it.id) }
        val refiled = moved.map { it.copy(mailboxId = inbox) }
        emailDao.upsertAll(refiled)
        adjustCountsForRemoval(moved, inbox)
        return refiled.map { it.toEmail() }
    }

    /** Move a whole selection (one account) to [targetMailboxId]. */
    suspend fun moveAllToMailbox(credentials: AccountCredentials, emailIds: List<String>, targetMailboxId: String): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        unlistFromTrashPurge(credentials.id, emailIds) // bulk rescue out of the Trash (#99)
        markReadOnMoveOutOfInbox(credentials, emailIds, targetMailboxId)
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null -> failed += ids
                    // Already in the destination: nothing moved — and whether its index row stays
                    // depends on the destination's role, Junk and Trash included ([evictAlreadyThere]).
                    source == targetMailboxId -> {
                        evictAlreadyThere(credentials.id, targetMailboxId, ids); succeeded += ids
                    }
                    else -> imapMoveGroup(credentials, source, ids, targetMailboxId, succeeded, failed)
                }
            }
            return BulkResult(succeeded, failed, dest = targetMailboxId)
        }
        return jmapMoveAll(connect(credentials), emailIds, targetMailboxId)
    }

    /**
     * Delete a whole selection (one account): move everything to Trash (undoable). Like the
     * single-message [delete] this NEVER destroys inline — the caller routes the would-destroy
     * subset through the held-back destroy ([destroyAll]) with its cancelable Undo window
     * (Codeberg #23) — so an account with no Trash fails the batch instead of destroying it.
     */
    suspend fun deleteAll(credentials: AccountCredentials, emailIds: List<String>): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        unlistFromTrashPurge(credentials.id, emailIds) // see [archiveAll] (#99)
        // Opt-in mark-read-on-delete: best-effort before the move (a \Seen store keeps the UID).
        if (settings?.markReadOnDelete?.first() == true) markSelectionRead(credentials, emailIds)
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            val trash = imapRoleFolder(credentials, "trash")
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null || trash == null -> failed += ids
                    // Already in Trash: nothing to move — drop the row locally only. Deliberately
                    // in NEITHER set: there is no move to undo and nothing failed.
                    //
                    // And un-indexed: the Trash is excluded from search at BOTH ends — the crawl
                    // and the re-seed skip it, the query filters it out — so there is no coverage
                    // to preserve here, and a row left standing is an orphan whose message is no
                    // longer cached, which no re-seed can clear. Not, as this said, because the
                    // row carries an EARLIER label: an IMAP id encodes its folder, so the index
                    // row of an in-Trash id says Trash. That is what keeps it invisible — for
                    // exactly as long as the folder cache still knows that folder's role.
                    // The no-op branches decide the same question the same way ([evictAlreadyThere]),
                    // and so does the single-message [delete].
                    source == trash -> deleteFromCacheAndIndex(credentials.id, ids)
                    else -> imapMoveGroup(credentials, source, ids, trash, succeeded, failed)
                }
            }
            return BulkResult(succeeded, failed, dest = trash)
        }
        val ctx = connect(credentials)
        val trash = ctx.rolesToMailboxId["trash"] ?: return BulkResult(emptySet(), emailIds.toSet())
        return jmapMoveAll(ctx, emailIds, trash)
    }

    /** Permanently destroy a whole selection (one account) — the held-back bulk destroy
     *  (Codeberg #23/#29). THROWS on a transport-level failure (offline, dead connection) so
     *  the destroy worker retries instead of abandoning a confirmed destroy;
     *  [BulkResult.failed] only carries per-id rejections (unparsable id, `notDestroyed`).
     *
     *  [expectedUidValidity] is the IMAP numbering the ids were read under, when the caller has
     *  one to offer (the Empty-trash snapshot does; an immediate destroy of rows the user is
     *  looking at does not). A folder renumbered since then throws rather than destroying by
     *  numbers that now mean something else (Codeberg #99). */
    suspend fun destroyAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedUidValidity: Long? = null,
    ): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                if (source == null) {
                    failed += ids
                } else {
                    imapDestroyGroup(credentials, source, ids, succeeded, failed, expectedUidValidity)
                }
            }
            return BulkResult(succeeded, failed)
        }
        return jmapDestroyAll(connect(credentials), emailIds)
    }

    /** Report a whole selection as spam (move to Junk) in one batch. */
    suspend fun reportSpamAll(credentials: AccountCredentials, emailIds: List<String>): BulkResult {
        val junk = roleMailboxId(credentials, "junk") ?: return BulkResult(emptySet(), emailIds.toSet())
        return moveAllToMailbox(credentials, emailIds, junk)
    }

    /** Move a whole selection out of Junk back to the Inbox in one batch. */
    suspend fun notSpamAll(credentials: AccountCredentials, emailIds: List<String>): BulkResult {
        val inbox = roleMailboxId(credentials, "inbox") ?: return BulkResult(emptySet(), emailIds.toSet())
        return moveAllToMailbox(credentials, emailIds, inbox)
    }

    /** The cached role of an account's mailbox (e.g. "junk", "inbox"), or null. [accountId]
     *  null falls back to the current account — same-server accounts can share a bare mailbox
     *  id, so an unscoped lookup could read a sibling account's folder. */
    suspend fun mailboxRole(accountId: String?, mailboxId: String?): String? {
        if (mailboxId == null) return null
        val account = accountId ?: accountStore.currentId() ?: return null
        return mailboxDao.roleForId(account, mailboxId)
    }

    /** Move a message to the Junk folder (Report spam). */
    suspend fun reportSpam(credentials: AccountCredentials, emailId: String) {
        val junk = roleMailboxId(credentials, "junk") ?: error("This account has no Junk folder.")
        moveToMailbox(credentials, emailId, junk)
    }

    /** Move a message out of Junk back to the Inbox (Not spam). */
    suspend fun notSpam(credentials: AccountCredentials, emailId: String) {
        val inbox = roleMailboxId(credentials, "inbox") ?: error("This account has no Inbox.")
        moveToMailbox(credentials, emailId, inbox)
    }

    /**
     * A role's mailbox id for a SPECIFIC account. For JMAP this comes from that account's own
     * connection context rather than from the folder cache — so moving a message from a
     * non-current account in the unified inbox (Report spam / Not spam) targets the right folder
     * instead of silently no-op'ing.
     *
     * The cache is keyed per account since issue #31 and would no longer answer for the wrong one,
     * as this said it did; what it can still do is not answer at all — an account whose folders
     * have never been listed has no rows there, and a Report spam that finds no Junk does nothing.
     * The connection context is fetched for the acting account, so it always answers.
     */
    private suspend fun roleMailboxId(credentials: AccountCredentials, role: String): String? =
        if (credentials.protocol == MailProtocol.IMAP) imapRoleFolder(credentials, role)
        else connect(credentials).rolesToMailboxId[role]

    /**
     * The folder for the first matching [roles] in a SPECIFIC IMAP account, by listing
     * that account's folders. Mirrors the JMAP path (its own connection context) for the reason
     * given there: the folder cache is per account (issue #31) but only holds what has been
     * synced, and an account listed in the unified inbox without ever having been opened has
     * nothing in it — archiving such an account's message would find no Archive and fail.
     */
    private suspend fun imapRoleFolder(credentials: AccountCredentials, vararg roles: String): String? {
        val folders = imap.listMailboxes(credentials)
        for (role in roles) folders.firstOrNull { it.role == role }?.let { return it.id }
        return null
    }

    // ---- recipient suggestions ----

    /** Record people we send to, so they surface in recipient autocomplete next time. */
    suspend fun rememberRecipients(addresses: List<String>) {
        val now = System.currentTimeMillis()
        addresses.map { it.trim() }.filter { it.contains('@') }.distinctBy { it.lowercase() }.forEach { addr ->
            val (name, email) = parseAddress(addr)
            recentContactDao.upsert(RecentContactEntity(email = email.lowercase(), name = name, lastSeen = now))
        }
    }

    /** Deduped recipient suggestions for [query]: people we've sent to, plus cached senders. */
    suspend fun suggestContacts(query: String, limit: Int = 6): List<ContactRow> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return (recentContactDao.search(q, limit) + emailDao.suggestSenders(q, limit))
            .filter { it.email.contains('@') }
            .distinctBy { it.email.lowercase() }
            .take(limit)
    }

    private fun parseAddress(s: String): Pair<String?, String> {
        val m = Regex("^(.*?)<([^>]+)>").find(s.trim())
        return if (m != null) m.groupValues[1].trim().ifBlank { null } to m.groupValues[2].trim()
        else null to s.trim()
    }

    // ---- snooze ----

    /** Snooze a message until [until] (hidden from lists; re-appears at that time). */
    suspend fun snooze(emailId: String, accountId: String, until: Long) =
        snoozedDao.upsert(SnoozedEntity(emailId, accountId, until))

    /** Un-snooze one account's message now (re-appears in its list). */
    suspend fun unsnooze(accountId: String, emailId: String) = snoozedDao.delete(accountId, emailId)

    /** The deadline of the active snooze on [accountId]'s [emailId], or null when it is not
     *  snoozed (a lapsed row counts as not snoozed — same predicate as the list SQL). Snoozes are
     *  keyed per account (issue #31), so the lookup carries the account. */
    suspend fun snoozedUntil(accountId: String, emailId: String): Long? =
        snoozedDao.byId(accountId, emailId)?.until?.takeIf { it > System.currentTimeMillis() }

    /** Live list of snoozed messages with their cached headers, for the "Snoozed" screen. */
    fun snoozedFlow(): Flow<List<SnoozedListRow>> = snoozedDao.observeAll()

    /**
     * The ids an active snooze currently hides, account-qualified, AS A LIVE READING — the same
     * predicate the list and chip SQL apply, for the one reader that cannot express it in SQL.
     *
     * The list and the chip join this table, so Room re-runs them when a snooze is written or
     * lapses. The unfolded conversation's members are read from `emails` alone, so nothing told
     * them a snooze had ended: the chip came back up at the due date and the message did not come
     * back under the row. Two readings of one write, on this table as on the other.
     *
     * A lapsed row counts as not snoozed (same predicate as the SQL); the row itself is deleted at
     * the due date by the snooze worker, which is the write this flow re-emits on.
     *
     * Account-qualified (issue #31): snoozes are keyed per account, so account A snoozing id X
     * must not hide account B's same-id message.
     */
    fun observeActiveSnoozed(): Flow<Set<EmailKey>> =
        snoozedDao.observeAll()
            .map { rows ->
                val now = System.currentTimeMillis()
                rows.filter { it.until > now }.mapTo(mutableSetOf()) { EmailKey(it.accountId, it.emailId) }
            }
            // The underlying query joins `emails` for the Snoozed screen's headers, so it re-emits
            // on every write to the message table; the answer this reader wants changes far less
            // often than that.
            .distinctUntilChanged()

    /** A single cached email of one account by id (e.g. to notify when a snooze fires). */
    suspend fun cachedEmail(accountId: String, emailId: String): Email? =
        emailDao.emailsByIds(accountId, listOf(emailId)).firstOrNull()?.toEmail()

    // ---- folder management ----

    /**
     * Create a new folder, then refresh the cached folder list.
     *
     * The typed [name] is Unicode and so is [parentId] — an IMAP folder id is its path DECODED
     * from modified UTF-7 (Codeberg #101) — so the two simply concatenate here and the encoding
     * happens at the socket. Building the path from a raw wire form and a typed name is what used
     * to send a Cyrillic folder name as raw UTF-8 and have the server refuse or mangle it.
     */
    suspend fun createFolder(credentials: AccountCredentials, name: String, parentId: String? = null) {
        if (credentials.protocol == MailProtocol.IMAP) {
            // IMAP nests by path; the parent's id is its full path.
            val path = if (parentId.isNullOrEmpty()) {
                name.trim()
            } else {
                val delim = if (parentId.contains('/')) "/" else if (parentId.contains('.')) "." else "/"
                "$parentId$delim${name.trim()}"
            }
            imap.createFolder(credentials, path)
        } else {
            val ctx = connect(credentials)
            client.createMailbox(ctx.session, ctx.accountId, name.trim(), role = null, ctx.auth, parentId = parentId)
        }
        refreshMailboxes(credentials)
    }

    /**
     * Rename a folder (keeping its place in the hierarchy for IMAP), then refresh.
     * Returns the folder's id after the rename — IMAP ids are paths, so the id changes
     * there and watch flags are re-keyed to follow it (issue #16); JMAP ids are stable.
     */
    suspend fun renameFolder(credentials: AccountCredentials, mailboxId: String, newName: String): String {
        if (credentials.protocol == MailProtocol.IMAP) {
            val delim = imap.listImapFolders(credentials).firstOrNull { it.path == mailboxId }?.delimiter
                ?: if (mailboxId.contains('/')) "/" else if (mailboxId.contains('.')) "." else "/"
            val parent = mailboxId.substringBeforeLast(delim, "")
            val newPath = if (parent.isEmpty()) newName.trim() else "$parent$delim${newName.trim()}"
            imap.renameFolder(credentials, mailboxId, newPath)
            accountStore.replaceWatchedFolder(credentials.id, mailboxId, newPath, delim)
            refreshMailboxes(credentials)
            return newPath
        }
        val ctx = connect(credentials)
        client.renameMailbox(ctx.session, ctx.accountId, mailboxId, newName.trim(), ctx.auth)
        refreshMailboxes(credentials)
        return mailboxId
    }

    /**
     * Delete a folder AND its subfolders (deepest first — servers refuse to destroy a
     * parent that still has children), plus their cached messages and watch flags.
     * Returns every deleted folder id so the caller can clean per-folder state.
     *
     * The children are found by `startsWith(mailboxId + delimiter)`, so BOTH sides must be in
     * the same representation: they are, because a listed path and a cached folder id are both
     * the Unicode decoding of the wire name (Codeberg #101). Mixing the two would make this
     * match nothing — a folder delete that silently deletes nothing, or the wrong set.
     */
    suspend fun deleteFolder(credentials: AccountCredentials, mailboxId: String): List<String> {
        val targets: List<String>
        if (credentials.protocol == MailProtocol.IMAP) {
            val folders = imap.listImapFolders(credentials)
            // The folder's OWN delimiter from LIST: guessing from other folder names
            // breaks on servers whose names legitimately contain '/' or '.'.
            val delim = folders.firstOrNull { it.path == mailboxId }?.delimiter ?: "/"
            targets = folders.map { it.path }
                .filter { it == mailboxId || it.startsWith(mailboxId + delim) }
                .sortedByDescending { it.length }
                .ifEmpty { listOf(mailboxId) } // already gone server-side: still clean up locally
            targets.forEach { runCatching { imap.deleteFolder(credentials, it) } }
        } else {
            val ctx = connect(credentials)
            val childrenOf = client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).groupBy { it.parentId }
            val ordered = mutableListOf<String>()
            fun visit(id: String) { // post-order: children before their parent
                childrenOf[id].orEmpty().forEach { visit(it.id) }
                ordered += id
            }
            visit(mailboxId)
            targets = ordered
            targets.forEach { client.deleteMailbox(ctx.session, ctx.accountId, it, ctx.auth) }
        }
        targets.forEach {
            accountStore.setFolderWatched(credentials.id, it, watched = false)
            emailDao.replaceMailbox(credentials.id, it, emptyList())
        }
        refreshMailboxes(credentials)
        return targets
    }

    /**
     * Drop folders from the local cache only (drawer disappearance while a folder
     * delete waits out its undo window); any refresh restores them.
     */
    suspend fun hideMailboxesLocally(accountId: String, mailboxIds: List<String>) =
        mailboxDao.deleteByIds(accountId, mailboxIds)

    /** Re-fetch the folder list into the cache (after a create/rename/delete). */
    private suspend fun refreshMailboxes(credentials: AccountCredentials) {
        if (credentials.protocol == MailProtocol.IMAP) {
            val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = 1)
            mailboxDao.replaceAll(credentials.id, load.mailboxes)
            return
        }
        val ctx = connect(credentials)
        mailboxDao.replaceAll(credentials.id, client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).map { it.toEntity(credentials.id) })
    }

    /** Create an "Archive" folder on the server, cache it in the context, and refresh the folder list. */
    /**
     * The account's archive folder id: by JMAP `archive` role, or — when the server set
     * no role (some accounts only have a plain folder named "Archive"/"Archives"/… ) — by
     * a recognised archive name. Null when the account has neither. The name match is
     * cached into the context's role map so a bulk archive resolves it once instead of
     * making every message try (and fail) to create a duplicate archive folder.
     */
    /** Lowercased folder names that clearly denote an archive, across Sterna's locales. */
    private val ARCHIVE_FOLDER_NAMES = listOf(
        "archive", "archives", "archived",
        "archivé", "archivés", "archiv", "archivio",
        "arquivo", "arquivos", "archief", "archiwum", "архив",
    )

    private suspend fun archiveMailboxId(ctx: Context): String? {
        ctx.rolesToMailboxId["archive"]?.let { return it }
        // Match THIS account's own folders by name (top-level preferred), not the global
        // mailbox cache — otherwise archiving a non-current account from the unified inbox
        // would target the current account's Archive id (which doesn't exist server-side for
        // the message's account), and the move would silently no-op.
        val byName = ctx.mailboxes
            .filter { it.name.lowercase() in ARCHIVE_FOLDER_NAMES }
            .minByOrNull { if (it.parentId == null) 0 else 1 }
            ?.id
            ?: return null
        context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to byName), ctx.mailboxes)
        return byName
    }

    private suspend fun createArchiveFolder(ctx: Context): String {
        // Prefer creating with the archive role, but many servers reject a client-set
        // special-use role — fall back to a plain "Archive" folder, then to any existing
        // archive-named folder for THIS account (re-fetched, never the global cache which may
        // hold a different account's folders).
        val id = runCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Archive", "archive", ctx.auth)
        }.recoverCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Archive", null, ctx.auth)
        }.getOrElse { err ->
            val mbs = runCatching { client.getMailboxes(ctx.session, ctx.accountId, ctx.auth) }.getOrDefault(emptyList())
            mbs.firstOrNull { it.role == "archive" }?.id
                ?: mbs.filter { it.name.lowercase() in ARCHIVE_FOLDER_NAMES }
                    .minByOrNull { if (it.parentId == null) 0 else 1 }?.id
                ?: throw err
        }
        context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to id), ctx.mailboxes)
        return id
    }

    /**
     * One message to move back on an Undo: [emailId] returns to [sourceMailboxId] (where it was
     * before the action), coming from [destMailboxId] (where the forward action put it — Trash,
     * Archive, or a target folder; null when it was a destroy, which can't be undone but is kept
     * for shape). The dest lets the undo reverse the count nudge without a reconciling refresh.
     */
    data class RestoreTarget(val emailId: String, val sourceMailboxId: String, val destMailboxId: String?)

    /**
     * Undo a batched move/delete for one account: move every message back to its source folder,
     * grouped so each (current folder → source folder) route is ONE `UID MOVE` / `Email/set`
     * instead of one command per message (Codeberg #29). Restoring a large batch with per-message
     * moves would hit the very server limits the forward batch avoids, so the whole set must go
     * back together.
     *
     * This is an OPTIMISTIC restore that STICKS: every restored id is marked recently-mutated so
     * neither reconcile path (delta or full-query) prunes it while the server catches up on the
     * move-back, and the count nudge is reversed here — so the caller must NOT fire a reconciling
     * refresh afterwards (that would read stale server state and race the restore).
     *
     * Returns the ids that did NOT restore (server rejected the move-back, batch failed, or no
     * UID mapping survived) — their counts are left alone so the caller can tell the user.
     */
    suspend fun restoreAll(credentials: AccountCredentials, targets: List<RestoreTarget>): Set<String> {
        if (targets.isEmpty()) return emptySet()
        // Undoing a delete pulls the message back OUT of the Trash: it must leave any pending
        // Empty-trash destroy list with it, or the purge would destroy it where it went home (#99).
        unlistFromTrashPurge(credentials.id, targets.map { it.emailId })
        // Protect the restored ids from the next reconcile BEFORE any server call, so even a sync
        // that fires mid-restore can't drop them.
        targets.forEach { markRecentlyMutated(credentials.id, it.emailId) }
        val restored = mutableSetOf<String>()
        if (credentials.protocol == MailProtocol.IMAP) {
            // Group by (current folder the message sits in, source folder to return it to).
            // lastImapMove holds where the forward move put each id and its UID there.
            val byRoute = LinkedHashMap<Pair<String, String>, MutableList<Pair<Long, String>>>()
            targets.forEach { t ->
                val loc = lastImapMove[t.emailId] ?: return@forEach
                byRoute.getOrPut(loc.mailboxId to t.sourceMailboxId) { mutableListOf() } += loc.uid to t.emailId
            }
            byRoute.forEach { (route, uidAndIds) ->
                val (currentFolder, source) = route
                val mapping = runCatching {
                    imap.moveBatch(credentials, currentFolder, uidAndIds.map { it.first }, source)
                }.getOrNull() ?: return@forEach
                // Credit only the uids the COPYUID mapping confirms moved back — a partial
                // mapping means the rest didn't restore, and they must feed the failure toast.
                restored += uidAndIds.filter { it.first in mapping }.map { it.second }
                val newUids = mapping.values.toList()
                if (newUids.isNotEmpty()) {
                    runCatching { imap.fetchByUids(credentials, source, newUids) }
                        .getOrDefault(emptyList())
                        .takeIf { it.isNotEmpty() }
                        ?.let { fetched ->
                            emailDao.upsertAll(fetched)
                            fetched.forEach { markRecentlyMutated(credentials.id, it.id); recentLocalMoves.mark(credentials.id, it.id) }
                        }
                }
            }
            restored.forEach { lastImapMove.remove(it) }
            restoreCounts(credentials.id, targets.filter { it.emailId in restored })
            return targets.map { it.emailId }.toSet() - restored
        }
        // JMAP: ids are stable across mailbox moves, so move each id back to its source in one
        // Email/set per source folder, then re-fetch to re-cache the restored rows.
        val ctx = connect(credentials)
        targets.groupBy { it.sourceMailboxId }.forEach { (source, group) ->
            val result = runCatching {
                client.move(ctx.session, ctx.accountId, group.map { it.emailId }, source, ctx.auth)
            }.getOrNull() ?: return@forEach
            val ids = group.map { it.emailId }.filter { it in result.done }
            if (ids.isEmpty()) return@forEach
            restored += ids
            // The move-back is a self-move into the source folder (often the watched Inbox):
            // the next notifier pass must not announce the restored rows as new arrivals.
            ids.forEach { recentLocalMoves.mark(credentials.id, it) }
            val fetched = runCatching { client.getEmailsByIds(ctx.session, ctx.accountId, ids, ctx.auth) }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) {
                emailDao.upsertAll(fetched.map { it.toEntity(ctx.credentials.id, source) })
                fetched.forEach { markRecentlyMutated(credentials.id, it.id) }
            }
        }
        restoreCounts(credentials.id, targets.filter { it.emailId in restored })
        return targets.map { it.emailId }.toSet() - restored
    }

    /**
     * Reverse the forward removal's count nudge for an Undo: each message moves from where it now
     * sits ([RestoreTarget.destMailboxId]) back to [RestoreTarget.sourceMailboxId]. Rows whose
     * seen state we could re-cache use it; the rest are treated as read (the common case for an
     * already-triaged message) — a small transient the next sync corrects anyway.
     */
    private suspend fun restoreCounts(accountId: String, targets: List<RestoreTarget>) {
        val seenById = emailDao.emailsByIds(accountId, targets.map { it.emailId }).associate { it.id to it.seen }
        val moves = targets.mapNotNull { t ->
            val dest = t.destMailboxId ?: return@mapNotNull null // a destroy can't be undone
            (dest to (seenById[t.emailId] ?: true))
                .takeIf { dest != t.sourceMailboxId }
                ?.let { t.sourceMailboxId to it }
        }
        moves.groupBy({ it.first }, { it.second }).forEach { (source, rows) ->
            nudgeCounts(accountId, rows, destMailboxId = source)
        }
    }

    /** Move to Trash and drop from the local list. A delete here NEVER destroys: moving a
     *  message to the folder it already sits in is a safe server no-op, and permanent deletion
     *  is only reachable through the held-back path ([evictAll] + [destroyAll]) with its cancelable
     *  Undo window (Codeberg #23) — the caller routes would-destroy deletes there via
     *  [deleteWouldDestroy]. Returns the Trash folder the message landed in, so the caller can
     *  offer an Undo that restores the row and reverses the count nudge — or null when there was
     *  nothing to move (the message was already destroyed server-side; its zombie row is pruned).
     *  Throws when the account has no Trash folder. */
    suspend fun delete(credentials: AccountCredentials, emailId: String): String? {
        // Same rule as every other mover (#99): a message the user acts on individually is no
        // longer part of a pending Empty-trash order.
        unlistFromTrashPurge(credentials.id, listOf(emailId))
        // Opt-in: flag the message read on its way out, so Trash doesn't accumulate unread
        // badges. Best-effort BEFORE the move (the id changes with an IMAP move); a failure
        // must never block the deletion itself.
        if (settings?.markReadOnDelete?.first() == true && emailDao.seenOf(credentials.id, emailId) == false) {
            runCatching { setRead(credentials, emailId, true) }
        }
        val row = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull()
        if (credentials.protocol == MailProtocol.IMAP) {
            val trash = imapRoleFolder(credentials, "trash") ?: error("This account has no Trash folder.")
            imapTarget(emailId)?.let { (mb, uid) ->
                // Already in the Trash: nothing moves — but the cached row AND its index row both
                // go, exactly as the bulk twin ([deleteAll]) does, and for its reason. The Trash is
                // excluded from search at both ends (the query's folder filter, the crawl and the
                // re-seed), so an index row surviving here preserves no coverage whatever: it is
                // simply an orphan, and the re-seed only rewrites rows whose message is still
                // cached, so nothing ever clears it. It is hidden only while the folder cache knows
                // the folder's role — a folder renamed or dropped server-side and the message comes
                // back in the results for good. (It does NOT carry the label the message had before
                // it was thrown away: an IMAP id encodes its folder, so an in-Trash id's index row
                // says Trash.) [evictAlreadyThere] asks the same question of the folder the message
                // stayed in, and spares the row only where a search actually looks.
                if (mb == trash) return@let
                imap.move(credentials, mb, uid, trash)?.let {
                    lastImapMove[emailId] = ImapLoc(trash, it)
                    recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, trash, it))
                }
            }
            emailDao.deleteById(credentials.id, emailId)
            adjustCountsForRemoval(listOfNotNull(row), trash)
            return trash
        }
        val ctx = connect(credentials)
        val mb = row?.mailboxId ?: emailDao.mailboxOf(credentials.id, emailId)
        val trash = ctx.rolesToMailboxId["trash"] ?: error("This account has no Trash folder.")
        // Network-first (like moveToMailbox): the local row is dropped (and counts nudged) only
        // after the server acknowledged the move, so a failed delete never hides a message that
        // is still on the server.
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, trash, ctx.auth)
        } catch (e: JmapException) {
            // notFound = already destroyed server-side. The user wanted the message gone and
            // it IS gone — prune the zombie row and report a no-op instead of failing with
            // "Server rejected the move (notFound)" while the ghost stays in the list.
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return null
        }
        recentLocalMoves.mark(credentials.id, emailId)
        emailDao.deleteById(credentials.id, emailId)
        adjustCountsForRemoval(listOfNotNull(row), trash)
        advanceEmailState(newState, credentials.id, mb)
        return trash
    }

    /** A confirmed "Empty trash": the identifier of its frozen destroy list and its size. */
    data class TrashPurgeSnapshot(val purgeId: String, val messageCount: Int)

    /**
     * Freeze what an "Empty trash" is allowed to destroy, AT CONFIRMATION TIME (Codeberg #99).
     *
     * The purge used to be described by a folder id and re-read the folder when the held-back
     * work finally ran, so a message moved to Trash during the undo window was destroyed with
     * the rest — permanently, server-side, though the user had never designated it and it was
     * not even in the folder when they tapped Empty. The set is now read here, once, and
     * [purgeSnapshot] destroys exactly it; anything that lands in Trash afterwards is simply
     * not on the list.
     *
     * MUST be called BEFORE the caller evicts the folder's cached rows: the offline fallback of
     * both protocols reads the snapshot from that very cache.
     *
     * BOTH protocols ask the server for the WHOLE folder, so "Trash emptied" means the folder,
     * not the part of it that had been synced. JMAP queries it UNCOLLAPSED — the list query
     * collapses threads, and a collapsed snapshot would leave every non-representative member
     * behind, so the "emptied" Trash would re-populate at the next sync. IMAP enumerates it with
     * one `UID SEARCH ALL` (see [ImapMailService.allUids]); the cached ids used to be the whole
     * IMAP snapshot, which left a big Trash emptied only of its visible window.
     *
     * When the server cannot be asked (offline, a rejected query), the cached ids stand in on
     * either protocol: they are what the user was looking at, and destroying less than asked is
     * the safe error. Neither branch throws — an exception here drops the snackbar and empties
     * nothing.
     *
     * The caller has ALREADY put "Trash emptied" on screen when this runs, so the IMAP read is
     * given a deadline ([ImapMailService.ENUMERATE_BUDGET_MS]) rather than the blocking wait
     * every other IMAP call has: a black-holed network must reach the cached fallback in
     * seconds, not leave a success message over an untouched list. A cancelled confirmation
     * (Undo) is the one failure that propagates instead of falling back.
     *
     * Bounded by [TrashPurge.SNAPSHOT_MAX]. A Trash holding more keeps the surplus, and
     * emptying again clears the rest — re-reading the folder to catch up is the defect itself.
     */
    suspend fun snapshotTrashPurge(
        credentials: AccountCredentials,
        trashMailboxId: String,
    ): TrashPurgeSnapshot {
        val now = System.currentTimeMillis()
        // Collect anything a killed process or a raced Undo left behind before adding to it.
        purgeSnapshotDao.deleteOlderThan(now - TrashPurge.SNAPSHOT_TTL_MS)
        suspend fun cached() = cachedIds(listOf(credentials.id to trashMailboxId)).map { it.emailId }
        // The IMAP numbering the frozen ids belong to (Codeberg #99). Read from the SELECT that
        // enumerates the folder; if the server could not be asked and the cached ids stand in, it
        // stays the numbering those cached ids were fetched under. Null on JMAP, where an id
        // survives anything a mailbox can go through.
        //
        // Read BEFORE the enumeration, and that ordering is the whole point: if the folder turns
        // out to have been renumbered, the enumeration refuses, the cached ids stand in — and
        // those ids belong to the OLD numbering, which is what must be recorded. Reading it
        // afterwards would pick up the new one the refusal just recorded and hand the purge a
        // stale list stamped as current, i.e. exactly the destroy this guard exists to prevent.
        val uidValidityBefore =
            if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, trashMailboxId)
            else null
        var observedUidValidity: Long? = null
        val ids = if (credentials.protocol == MailProtocol.IMAP) {
            TrashPurge.imapSnapshotIds(
                accountId = credentials.id,
                mailboxId = trashMailboxId,
                serverUids = {
                    val snapshot = imap.snapshotUids(credentials, trashMailboxId, TrashPurge.SNAPSHOT_MAX)
                    observedUidValidity = snapshot.uidValidity
                    snapshot.uids
                },
                cached = { cached() },
            )
        } else {
            runCatching {
                val ctx = connect(credentials)
                // Ids-only, paged (like [unreadIds]): the purge photo needs identifiers, not bodies.
                // The old queryEmails chained an Email/get of up to SNAPSHOT_MAX messages — a several-MB
                // response of subjects and previews on a plain "Empty trash" tap, and a server enforcing
                // maxObjectsInGet threw, dropping the whole thing to the cache (a big Trash then emptied
                // only of its visible window, #99).
                val collected = mutableListOf<String>()
                while (collected.size < TrashPurge.SNAPSHOT_MAX) {
                    val page = client.queryEmailIds(
                        ctx.session, ctx.accountId, trashMailboxId, UNREAD_RESOLVE_PAGE, ctx.auth,
                        position = collected.size, calculateTotal = true,
                    )
                    if (page.ids.isEmpty()) break
                    collected += page.ids
                    // Advance by the ACTUAL page size and stop on the server's total: a server
                    // clamping the limit below the page size must not end the walk early.
                    val total = page.total
                    if (total != null && collected.size >= total) break
                }
                collected.take(TrashPurge.SNAPSHOT_MAX)
            }.getOrElseUnlessCancelled {
                // The cache stands in for an unreachable server — but NOT for a cancelled caller:
                // this walk is a paged network crawl over up to SNAPSHOT_MAX ids, and an Undo
                // tapped while it runs would otherwise be turned into a perfectly valid
                // cache-based snapshot, leaving the confirmation the user just withdrew standing.
                cached()
            }
        }
        val purgeId = UUID.randomUUID().toString()
        val uidValidity = TrashPurge.snapshotUidValidity(observedUidValidity, uidValidityBefore)
        val rows = TrashPurge.snapshotRows(
            purgeId, credentials.id, trashMailboxId, ids, now, uidValidity = uidValidity,
        )
        rows.chunked(PURGE_SNAPSHOT_INSERT_BATCH).forEach { purgeSnapshotDao.insertAll(it) }
        return TrashPurgeSnapshot(purgeId, rows.size)
    }

    /**
     * Destroy the snapshot taken by [snapshotTrashPurge] — nothing else. Returns how many
     * messages were removed.
     *
     * Wave by wave (`Email/set` / `UID STORE`+`EXPUNGE` batches), deleting each wave from the
     * snapshot as it is consumed, so the loop drains and terminates whether the server accepted
     * the ids or rejected them. That also makes the whole thing resumable: a transport failure
     * propagates (the worker retries) and the retry picks up exactly the ids not yet destroyed.
     *
     * An empty or missing snapshot destroys NOTHING. That is the point: no snapshot, no order.
     *
     * ON IMAP the order also has a numbering, and it is checked before anything is destroyed
     * (Codeberg #99). The delay between the confirmation and this call is UNBOUNDED — the worker
     * waits for connectivity, so a device left offline defers it indefinitely — and a folder can
     * be renumbered in between, which makes every id in the list name a different message. Two
     * refusals follow from that:
     * - a snapshot carrying NO numbering cannot be checked, so it destroys nothing and is
     *   dropped. That covers a purge confirmed by the previous version, and one built from the
     *   cache for a folder whose numbering was never observed;
     * - a numbering that no longer matches the server's aborts the purge where it stands. The
     *   refusal is NOT caught as a failure to be retried: the snapshot is void, and the caches
     *   that cannot heal themselves have already been dropped by the invalidation.
     */
    suspend fun purgeSnapshot(credentials: AccountCredentials, purgeId: String): Int {
        var destroyed = 0
        val head = purgeSnapshotDao.head(purgeId, credentials.id)
        val imapPurge = credentials.protocol == MailProtocol.IMAP
        if (imapPurge && head != null && !UidValidity.mayDestroy(head.uidValidity)) {
            purgeSnapshotDao.deleteSnapshot(purgeId)
            return 0
        }
        val expectedUidValidity = if (imapPurge) head?.uidValidity else null
        try {
            for (wave in 1..TrashPurge.MAX_WAVES) {
                // Read AND destroy scoped to (purgeId, accountId): an email id means something only
                // inside its own account (issue #31) and only inside its own confirmation (#99).
                val ids = purgeSnapshotDao.wave(purgeId, credentials.id, TrashPurge.DESTROY_WAVE)
                if (ids.isEmpty()) break
                val result = destroyAll(credentials, ids, expectedUidValidity)
                purgeSnapshotDao.deleteIds(purgeId, credentials.id, ids)
                destroyed += result.succeeded.size
            }
        } catch (renumbered: ImapUidValidityChanged) {
            // Not swallowed and not retried: the folder is not the one the user confirmed
            // emptying. Destroy nothing more, drop the order.
            purgeSnapshotDao.deleteSnapshot(purgeId)
            refreshMailboxes(credentials)
            return destroyed
        }
        purgeSnapshotDao.deleteSnapshot(purgeId)
        // Post-purge reconcile: re-fetch the folder list so the drawer counts reflect the
        // emptied Trash instead of keeping the pre-purge numbers.
        refreshMailboxes(credentials)
        return destroyed
    }

    /** Undo: withdraw the confirmation by erasing the destroy list of that account's Trash. The
     *  snapshot IS the order, so erasing it is what makes the undo final — even against a purge
     *  that somehow already started. Scoped to one account's folder: emptying the Trash of one
     *  account never touches another's, and undoing it never cancels another's. */
    suspend fun discardTrashPurge(accountId: String, trashMailboxId: String) =
        purgeSnapshotDao.deleteForMailbox(accountId, trashMailboxId)

    /** Drop a snapshot by id (the purge gave up for good). */
    suspend fun discardPurgeSnapshot(purgeId: String) = purgeSnapshotDao.deleteSnapshot(purgeId)

    /**
     * THE CHOKEPOINT for a message leaving a folder: it is withdrawn from every pending
     * "Empty trash" destroy list of its account (Codeberg #99).
     *
     * Why it is needed at all: a JMAP email id does NOT change when the message moves (only
     * `mailboxIds` is patched), and nothing else removes a single id from a snapshot. So a
     * message rescued OUT of the Trash during the undo window — opened and filed elsewhere,
     * or an Undo that moved it back — was still on the list and got destroyed IN ITS NEW
     * FOLDER, the one place where the frozen list destroys more than the old re-read did, and
     * against the user's explicit rescue.
     *
     * IMAP is affected LESS, not "unaffected" — the sentence that used to stand here said an
     * id encodes folder+UID, so a `UID MOVE` leaves it pointing at a UID that no longer exists.
     * True, and true only until the folder is renumbered: UIDVALIDITY changing is exactly the
     * event that makes a vacated UID mean something again (RFC 3501 §2.3.1.1), and then a stale
     * id names whatever now holds that number. So the withdrawal is not merely harmless on IMAP,
     * it is one of the two things standing between a stale id and a destroy — the other being
     * the UIDVALIDITY recorded with the snapshot, which is what [purgeSnapshot] verifies.
     *
     * Called at the TOP of every mover, on the ids the caller asked to move, BEFORE the server
     * round-trip: a rescue that races the purge's first wave then still wins, and a move that
     * fails afterwards only means the purge destroys less than ordered — the safe direction.
     *
     * Chunked like the snapshot insert: one `IN (...)` list must stay under SQLite's bound
     * variable limit, which a select-all move can otherwise exceed.
     */
    private suspend fun unlistFromTrashPurge(accountId: String, emailIds: List<String>) {
        if (emailIds.isEmpty()) return
        emailIds.chunked(PURGE_SNAPSHOT_INSERT_BATCH).forEach { purgeSnapshotDao.unlistEmails(accountId, it) }
    }

    /**
     * Structured search across the account (results are transient, not cached).
     *
     * IMAP takes the same criteria as JMAP: sender, recipient, subject, the flagged filter and
     * the date range go to the server as `SEARCH` keys (they used to be dropped in silence, so a
     * filtered search quietly answered something else), across every cached folder of the account
     * rather than the inbox alone. "Has an attachment" is the one exception: no IMAP key exists
     * for it, so it is filtered from BODYSTRUCTURE on the way back.
     *
     * The answer says whether it is whole ([MailSearchResult.complete]): a full page means the
     * server may have had more, an IMAP attachment scan can stop on its cap, a folder can refuse
     * to be searched, and the folder list itself can be a cached fraction of the account. Only a
     * caller that knows this can put an honest count on screen.
     */
    suspend fun search(credentials: AccountCredentials, query: SearchQuery, limit: Int = 50): MailSearchResult {
        if (query.isEmpty()) return MailSearchResult(emptyList())
        if (credentials.protocol == MailProtocol.IMAP) {
            // Folders come from the cache, so "the whole account" really means "every folder the
            // drawer has heard of"; an account whose folders were never synced falls back to the
            // inbox rather than nothing.
            val known = searchableFolderIds(mailboxDao.searchOrder(credentials.id))
            val folders = known.ifEmpty { listOfNotNull(mailboxDao.idForRole(credentials.id, "inbox")) }
            // Nothing to search at all — not even an inbox id. That is "we looked nowhere", which
            // must never be reported as the confident "No results" the empty state states as a
            // fact; see [imapSearchComplete] for the fallback case just above it.
            if (folders.isEmpty()) return MailSearchResult(emptyList(), complete = false)
            val hits = imap.search(credentials, folders, query.toImapCriteria(), query.requiresLocalScan(), limit)
            return MailSearchResult(
                emails = hits.messages.map { it.toEmail() },
                complete = imapSearchComplete(known, hits.complete, hits.messages.size, limit),
            )
        }
        val ctx = connect(credentials)
        // Exclude Trash/Junk from the server search too, so JMAP matches the IMAP walk and the local
        // index — a message you deleted must not reappear in results whatever the server (Stalwart
        // returned it before). Same role source as the IMAP path's searchableFolderIds.
        // The raw folder list is kept, not just the ids derived from it: it is what says whether the
        // cache had anything to say at all. An account whose folders were never synced excludes
        // NOTHING, so the query below spreads over the whole account — Trash and Junk included —
        // and that is exactly the answer that must not come back wearing a total (see below).
        val cachedFolders = mailboxDao.searchOrder(credentials.id)
        val excluded = excludedSearchFolderIds(cachedFolders)
        val hits = client.searchEmails(ctx.session, ctx.accountId, query, limit, ctx.auth, excluded)
        // A hit can live in several mailboxes: resolve its folder like [fetchThreadMembers] —
        // the cached row's folder while the server still lists it, else the role-ranked pick —
        // never the server map's arbitrary first key, which could feed a search-row action
        // (delete's destroy-vs-move, undo's restore target) a Trash/Junk folder by accident.
        val cachedMailbox =
            emailDao.emailsByIds(credentials.id, hits.emails.map { it.id }).associate { it.id to it.mailboxId }
        val resolved = hits.emails.map { e ->
            val serverBoxes = e.mailboxIds.keys
            val mailbox = cachedMailbox[e.id]?.takeIf { it in serverBoxes }
                ?: rankedMailboxPick(credentials.id, serverBoxes)
            e.copy(mailboxId = mailbox ?: e.mailboxId)
        }
        // Three ways this answer can fail to be the account's answer — a full page (the server
        // stopped at the cap), a get shorter than the query it followed, and a scope that an empty
        // folder cache chose; [jmapSearchComplete] holds all three.
        return MailSearchResult(
            resolved,
            complete = jmapSearchComplete(cachedFolders, hits.matchedIds, resolved.size, limit),
        )
    }

    /**
     * Unified search across several accounts (the unified-inbox / dedicated-search case).
     * Each account's [search] runs in parallel; every hit is tagged with its local
     * accountId so results open in the right account and show the right account colour.
     *
     * An account that fails is skipped so one unreachable account doesn't sink the search — but
     * it is LOGGED and it makes the answer incomplete, rather than being passed off as the whole
     * result: an account vanishing without a trace is indistinguishable, on screen, from an
     * account that simply holds no match.
     *
     * Results are merged fairly per account, de-duplicated, sorted newest-first and capped at
     * [limit] — see [mergeAccountSearches].
     */
    suspend fun search(accounts: List<AccountCredentials>, query: SearchQuery, limit: Int = 50): MailSearchResult {
        if (query.isEmpty() || accounts.isEmpty()) return MailSearchResult(emptyList())
        if (accounts.size == 1) {
            val only = accounts.first()
            val one = search(only, query, limit)
            return one.copy(emails = one.emails.map { it.copy(accountId = only.id) })
        }
        val perAccount = coroutineScope {
            accounts.map { credentials ->
                async {
                    runCatching {
                        val hits = search(credentials, query, limit)
                        hits.copy(emails = hits.emails.map { it.copy(accountId = credentials.id) })
                    }.onFailure { error ->
                        // Cancellation (a new keystroke, the screen closing) is not a failure and
                        // must keep propagating — runCatching catches it like any other throwable.
                        if (error is CancellationException) throw error
                        // The account id is a local UUID and the message never carries the
                        // credential (see AccountStore's warning): this is the only trace a
                        // dropped account leaves, so it must exist.
                        android.util.Log.w(
                            "MailSearch",
                            "account ${credentials.id} dropped from unified search: " +
                                "${error.javaClass.simpleName}: ${error.message}",
                        )
                    }.getOrDefault(MailSearchResult(emptyList(), complete = false))
                }
            }.awaitAll()
        }
        return mergeAccountSearches(perAccount, limit)
    }

    /** Fetch an email (with body) without marking it read — used to build replies/forwards. */
    suspend fun fetchEmail(credentials: AccountCredentials, emailId: String): Email {
        if (credentials.protocol == MailProtocol.IMAP) return openEmailImap(credentials, emailId, markRead = false)
        val ctx = connect(credentials)
        return client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)
    }

    /** All emails in a conversation (lightweight, no body). */
    suspend fun threadEmails(credentials: AccountCredentials, threadId: String): List<Email> {
        val ctx = connect(credentials)
        return client.getThreadEmails(ctx.session, ctx.accountId, threadId, ctx.auth)
    }

    /**
     * Fetch a thread's FULL membership from the server (JMAP Thread/get) so an inline-expanded
     * conversation can show received messages that fell outside the folder's short sync window
     * (the cache holds only the latest page). Best-effort persists each member under its real
     * mailbox, so the next expand — and the collapsed row's count — is complete without network.
     *
     * Returns the fetched members (header-level, no body), or empty for IMAP (no Thread/get) and
     * on any failure (offline): the caller then simply keeps what the cache already gave it.
     */
    suspend fun fetchThreadMembers(
        credentials: AccountCredentials,
        threadId: String,
        viewMailboxIds: List<String> = emptyList(),
    ): List<Email> {
        if (credentials.protocol == MailProtocol.IMAP) return emptyList()
        val emails = runCatching { threadEmails(credentials, threadId) }.getOrNull() ?: return emptyList()
        // Persist members under their actual folder (from mailboxIds); skip any without one so we
        // never invent a mailbox. The pick is DETERMINISTIC: keep the row where the cache already
        // has it while the server still lists that mailbox, else prefer the folder being viewed
        // ([viewMailboxIds]), else the "most alive" mailbox by role — never the map's arbitrary
        // first key, which could re-key a correctly-filed row (even the representative) into
        // Trash and feed the destroy-vs-move decision a wrong folder.
        val cachedMailbox = emailDao.emailsByIds(credentials.id, emails.map { it.id }).associate { it.id to it.mailboxId }
        val entities = emails.mapNotNull { e ->
            val serverBoxes = e.mailboxIds.keys
            val mailbox = cachedMailbox[e.id]?.takeIf { it in serverBoxes }
                ?: e.mailboxId
                ?: viewMailboxIds.firstOrNull { it in serverBoxes }
                ?: rankedMailboxPick(credentials.id, serverBoxes)
                ?: return@mapNotNull null
            e.toEntity(credentials.id, mailbox)
        }
        if (entities.isNotEmpty()) runCatching {
            emailDao.upsertAll(entities)
            // Guard the fresh rows through the reconcile window: the very next full re-query
            // (e.g. opening the folder a just-deleted conversation moved to) would otherwise
            // prune every non-representative member straight back out, leaving the thread's
            // chip at 1 with its members unreachable. A later out-of-window re-query can still
            // prune them — that decay is accepted; expanding re-fetches.
            entities.forEach { markRecentlyMutated(credentials.id, it.id) }
        }
        // Wire members carry no accountId; stamp it so downstream keys (selection, caches)
        // can never fall back to an unscoped lookup and hit a colliding sibling account.
        return emails.map { if (it.accountId == null) it.copy(accountId = credentials.id) else it }
    }

    /**
     * Deterministic folder pick for a message in several mailboxes: the "most alive" role wins
     * (inbox > archive > other > junk > trash), ids sorted as tie-break — so a multi-mailbox
     * member never lands in Trash/Junk by map-order accident.
     */
    private suspend fun rankedMailboxPick(accountId: String, mailboxIds: Set<String>): String? =
        mailboxIds.sorted().minByOrNull { id ->
            when (mailboxDao.roleForId(accountId, id)) {
                "inbox" -> 0
                "archive" -> 1
                "junk" -> 3
                "trash" -> 4
                else -> 2
            }
        }

    /**
     * Cached members of a thread for inline conversation expansion: newest-first, scoped to the
     * representative's [accountId] and [mailboxIds] (the viewed folder(s) plus the account's Sent
     * folder). Cache only — no network — so unfolding a conversation row is instant and works
     * offline. [threadKey] is the representative's threadId (or its id when thread-less).
     *
     * LIVE, and that is the point: the chip on the collapsed row is a live query over the same
     * table (`conversationPagingSource`), so a message written into the thread moves the chip at
     * once. Read once into a snapshot, the messages under the row could not follow it — the row
     * then announced one number and listed another until the folder was left. Both sides now read
     * the same write.
     */
    fun observeThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): Flow<List<Email>> =
        if (mailboxIds.isEmpty()) flowOf(emptyList())
        else emailDao.cachedThreadEmails(accountId, mailboxIds, threadKey).map { rows -> rows.map { it.toEmail() } }

    /**
     * One reading of [observeThreadEmails], for callers that act on a thread once (a whole-thread
     * swipe) rather than draw it.
     */
    suspend fun cachedThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): List<Email> =
        observeThreadEmails(accountId, mailboxIds, threadKey).first()

    /**
     * The cached Sent-role folder of each of [accountIds], as account-pinned
     * (accountId, mailboxId) pairs (accounts without a cached Sent folder are skipped).
     *
     * THE conversation view's Sent resolution — the single one. It backs the "this folder plus
     * Sent replies" scope that the chip count and the unfolded member list both extend the viewed
     * folder(s) with (see [ConversationScope]), so a conversation never shows, nor counts,
     * Trash/Spam/Drafts members. There is deliberately no one-shot variant: the unfold reads the
     * value this flow last handed the list rather than looking the folder up again, because two
     * lookups are two answers and the reader sees the difference as a chip that lies.
     *
     * Reactive because it must be: it re-resolves when the folder table changes, so a fresh
     * install's chips pick the Sent folder up on the first folder sync instead of staying wrong
     * until the next paging-key change. Pinned to their account so colliding mailbox ids across
     * same-server accounts never bleed into a sibling's conversation.
     */
    fun observeSentMailboxes(accountIds: List<String>): Flow<List<Pair<String, String>>> =
        mailboxDao.observeSentMailboxes(accountIds.distinct())
            .map { rows -> rows.map { it.accountId to it.id } }
            .distinctUntilChanged()

    /**
     * Every known (account, folder) → role of [accountIds], reactively — the map that lets a row
     * be judged by the folder it is IN rather than by the folder on screen (Codeberg #115, the
     * children of an unfolded conversation). A folder missing from the map is unknown, not
     * role-less: callers must fall back rather than conclude.
     */
    fun observeFolderRoles(accountIds: List<String>): Flow<Map<Pair<String, String>, String>> =
        mailboxDao.observeRoles(accountIds.distinct())
            .map(::folderRoleMap)
            .distinctUntilChanged()

    /**
     * Remove one account's [emailIds] from the local cache only (optimistic UI removal),
     * decrementing each row's OWN source folder's drawer counts. Used by the held-back destroy
     * paths (in-Trash delete, bulk delete, empty-trash): the rows leave the list and the counts drop
     * NOW, while the actual server destroy is held behind the Undo window. If the destroy is later
     * undone, [forceRefresh] re-queries the server and the next getMailboxes resets the counts to
     * truth (the messages are still there), so no explicit count restore is needed for these paths.
     *
     * Takes the whole set rather than one id at a time, and issues batched statements for it:
     * `email_fts` is FTS4 with `emailId` declared `notindexed`, so each un-index scans the ENTIRE
     * index — a bulk delete of 200, or an Empty trash, meant that many scans. Chunked to what
     * SQLite will bind in one `IN (...)`.
     *
     * Read, evict and decount CHUNK BY CHUNK, not all the counts at the end: an Empty trash of a
     * thousand messages is several statements, and one of them refusing used to leave every row it
     * had already removed uncounted — the drawer keeping a badge for mail no longer there until a
     * refresh corrected it. Per chunk, whatever left the cache has been subtracted.
     */
    suspend fun evictAll(accountId: String, emailIds: Collection<String>) {
        if (emailIds.isEmpty()) return
        emailIds.chunked(MAX_CHANGES).forEach { chunk ->
            val rows = emailDao.emailsByIds(accountId, chunk)
            deleteFromCacheAndIndex(accountId, chunk)
            adjustCountsForRemoval(rows, destMailboxId = null)
        }
    }

    /**
     * Drop in-memory sync bookkeeping so the next refresh does a full re-query.
     * Call after the on-disk cache is cleared, otherwise incremental sync would
     * compare against stale state and re-fetch nothing, leaving the cache empty.
     */
    fun resetSyncState() {
        syncStates.clear()
        syncStateStore?.clear()
        context = null
    }

    /** Close any pooled IMAP connection for an account (e.g. on sign-out). */
    suspend fun disconnectImap(accountId: String) = imap.disconnect(accountId)

    /** Whether [credentials]' account has an Archive folder (so an archive action can work). */
    suspend fun hasArchiveFolder(credentials: AccountCredentials): Boolean =
        archiveMailboxId(connect(credentials)) != null

    private class Resolved(
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val mailboxes: List<Mailbox>,
    )

    /** Fetch a fresh session + mailboxes for [credentials] without touching the cached context. */
    private suspend fun resolve(credentials: AccountCredentials): Resolved {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = jmapAccountIdFor(credentials, session)
        val mailboxes = client.getMailboxes(session, accountId, auth)
        return Resolved(session, accountId, auth, mailboxes)
    }

    /**
     * The JMAP account id to pin this credential's API calls to: a linked sub-account's own
     * [AccountCredentials.jmapAccountId], else the session's primary mail account (the standalone
     * path, byte-identical to pre-multi-account behaviour).
     */
    private fun jmapAccountIdFor(credentials: AccountCredentials, session: JmapSession): String =
        credentials.jmapAccountId
            ?: session.mailAccountId()
            ?: error("This user has no JMAP mail account.")

    /**
     * Codeberg #31: a single login can expose several mail accounts. When the session advertises
     * more than one, surface each as its own StoredAccount (delegated / shared mailboxes) and prune
     * ones whose access was revoked, purging their caches. A single-account session is a strict
     * no-op only while the login has no linked sub-accounts: once it has some, a session shrunk
     * back to one account is exactly the all-access-revoked case and must still reconcile so the
     * stale sub-accounts leave the drawer. Best-effort and off the sync hot path — it runs only on
     * a session (re)fetch in connect(), and reconcile itself writes nothing when the account set
     * is unchanged.
     */
    private fun reconcileLinkedAccounts(credentials: AccountCredentials, session: JmapSession) {
        val mailAccountIds = session.mailAccountIds()
        val loginId = accountStore.account(credentials.id)?.loginKey() ?: credentials.id
        if (mailAccountIds.size <= 1 && accountStore.linkedAccounts(loginId).isEmpty()) return
        val discovered = mailAccountIds.map { DiscoveredMailAccount(it, session.accounts[it]?.name.orEmpty()) }
        val pruned = runCatching { accountStore.reconcileLinkedAccounts(loginId, discovered) }.getOrDefault(emptyList())
        pruned.forEach { prunedId ->
            // App-layer teardown first (notification baselines); each step best-effort so one
            // failure never leaves the rest of a revoked account behind.
            onAccountPruned?.let { hook -> runCatching { hook(prunedId) } }
            bgScope.launch {
                // One runCatching PER delete: a failure in one table must not leave the
                // remaining tables' rows of a revoked account behind.
                runCatching { emailDao.deleteForAccount(prunedId) }
                runCatching { emailFtsDao.clearAccount(prunedId) }
                runCatching { emailBodyDao.deleteForAccount(prunedId) }
                runCatching { mailboxDao.deleteForAccount(prunedId) }
                runCatching { snoozedDao.deleteForAccount(prunedId) }
            }
        }
    }

    /**
     * Codeberg #31: discover linked sub-accounts for the freshly added login [id], reusing the
     * session the validating refresh() left cached — no extra network round trip. Runs once the
     * login is persisted, so the accounts screen the add flow lands on already lists the
     * sub-accounts instead of waiting for the first inbox connect(). Best-effort: any failure, or
     * a cached session belonging to another login, is a no-op — the connect() hook reconciles
     * later anyway.
     */
    fun reconcileLinkedAccountsAfterAdd(id: String) {
        runCatching {
            val credentials = accountStore.credentials(id) ?: return
            val session = context
                ?.takeIf { it.credentials.server == credentials.server && it.credentials.username == credentials.username }
                ?.session ?: return
            reconcileLinkedAccounts(credentials, session)
        }
    }

    /**
     * Bring a set of persisted watched-folder ids into the representation the app now holds, and
     * persist the correction (Codeberg #101).
     *
     * Versions up to 1.4.3 stored an IMAP folder id in its RAW WIRE FORM, because nothing decoded
     * mailbox names; folder ids are now the decoded path. A watch on "Помеченные" would therefore
     * match no listed folder, `loadWatchedFolders` would report it missing, and the caller prunes
     * a missing watch QUIETLY and clears its baseline — that path means "deleted or renamed
     * server-side". A user with multi-folder push on a non-ASCII folder would have lost it on
     * update without being told. One re-key through the same [decodeMailboxPath] the listing uses,
     * so both sides land on the same string, including for a name that deliberately does not
     * decode.
     *
     * Idempotent and self-healing: an id already in the right form maps to itself and is not
     * rewritten, so this costs one map lookup per watched folder once the correction is made.
     */
    private fun rekeyWatchedFolders(accountId: String, watched: Set<String>): Set<String> {
        if (watched.isEmpty()) return watched
        val corrected = watched.associateWith { decodeMailboxPath(it) }.filter { it.key != it.value }
        corrected.forEach { (stored, path) ->
            runCatching { accountStore.replaceWatchedFolder(accountId, stored, path) }
        }
        return watched.map { corrected[it] ?: it }.toSet()
    }

    /**
     * Refresh the account's inbox (unless [includeInbox] is false) plus the watched
     * folders in [extraFolderIds] into the cache (multi-folder push, issue #16).
     * Independent of the current-account context, so it is safe for background push.
     * Watched ids no longer on the server are omitted from the result and reported
     * via [onMissing] so the caller can prune the stale watch flag.
     */
    suspend fun refreshAccountFolders(
        credentials: AccountCredentials,
        extraFolderIds: Set<String>,
        includeInbox: Boolean = true,
        limit: Int = 50,
        onMissing: (String) -> Unit = {},
    ): List<FolderRefresh> {
        if (credentials.protocol == MailProtocol.IMAP) {
            val (loads, missing) = imap.loadWatchedFolders(
                credentials, rekeyWatchedFolders(credentials.id, extraFolderIds), includeInbox, limit,
            )
            missing.forEach(onMissing)
            loads.forEach { emailDao.upsertAll(it.messages) }
            return loads.map { load ->
                FolderRefresh(load.mailboxId, load.name, load.role, load.messages.map { it.toEmail() })
            }
        }
        val resolved = resolve(credentials)
        val inbox = resolved.mailboxes.firstOrNull { it.role == "inbox" }
            ?: resolved.mailboxes.firstOrNull()
            ?: error("No mailboxes found.")
        val byId = resolved.mailboxes.associateBy { it.id }
        val targets = buildList {
            if (includeInbox) add(inbox)
            extraFolderIds.forEach { id ->
                val mailbox = byId[id]
                when {
                    mailbox == null -> onMissing(id)
                    mailbox.id != inbox.id -> add(mailbox)
                }
            }
        }
        val refreshes = targets.map { mailbox ->
            syncMailbox(resolved.session, resolved.accountId, resolved.auth, mailbox.id, limit, credentials.id)
            FolderRefresh(
                mailboxId = mailbox.id,
                name = mailbox.name,
                role = mailbox.role,
                emails = emailDao.getByMailbox(credentials.id, mailbox.id).map { it.toEmail() },
            )
        }
        // Persist the fetched folder counters (previously discarded): without this, pushed and
        // background-fetched mail never moved the drawer badge, and reading it afterwards made
        // the badge undercount. Written after the row syncs so badge and list land together.
        mailboxDao.replaceAll(credentials.id, resolved.mailboxes.map { it.toEntity(credentials.id) })
        return refreshes
    }

    // ---- JMAP PushSubscription (issue #17) ---------------------------------------------
    // Session-level, context-free (fresh session per call — these operations are rare).

    /** Create a subscription pointing at a UnifiedPush endpoint; returns it with id/expires. */
    suspend fun createPushSubscription(credentials: AccountCredentials, subscription: PushSubscription): PushSubscription {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        return client.createPushSubscription(session, auth, subscription)
    }

    /** Confirm the PushVerification round-trip (do this promptly — servers time it out). */
    suspend fun verifyPushSubscription(credentials: AccountCredentials, subscriptionId: String, verificationCode: String) {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        client.verifyPushSubscription(session, auth, subscriptionId, verificationCode)
    }

    /** Push the expiry out; returns the (possibly server-capped) applied UTCDate. */
    suspend fun renewPushSubscription(credentials: AccountCredentials, subscriptionId: String, expires: String): String {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        return client.updatePushSubscriptionExpires(session, auth, subscriptionId, expires)
    }

    /** Destroy a subscription (sign-out / endpoint rotation); already-gone is success. */
    suspend fun destroyPushSubscription(credentials: AccountCredentials, subscriptionId: String) {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        client.destroyPushSubscription(session, auth, subscriptionId)
    }

    /** The server's VAPID key (RFC 9749) when advertised; null otherwise (e.g. Stalwart). */
    suspend fun pushVapidKey(credentials: AccountCredentials): String? {
        val auth = jmapAuth(credentials)
        return client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth).vapidPublicKey()
    }

    /**
     * Open a push connection for a specific account; [onChanged] fires when its mail
     * changes, and [onClosed] when the connection drops (so the caller can reconnect).
     */
    suspend fun openAccountPush(
        credentials: AccountCredentials,
        onChanged: () -> Unit,
        onClosed: () -> Unit = {},
        // The JMAP account ids whose Email state should wake [onChanged]. One EventSource per login
        // watches all the group's account ids through this one socket (issue #31). NB the server
        // decides what it actually emits: Stalwart only pushes StateChanges for accounts the login
        // is a member of — ACL-shared (delegated) accounts never appear here, so their delivery
        // relies on the caller's periodic poll. Empty = just this credential's own account.
        watchedJmapAccountIds: Set<String> = emptySet(),
    ): Closeable {
        if (credentials.protocol == MailProtocol.IMAP) {
            return imap.openIdle(credentials, onChanged = onChanged, onClosed = onClosed)
        }
        val resolved = resolve(credentials)
        val watched = watchedJmapAccountIds.ifEmpty { setOf(resolved.accountId) }
        return client.openEventSource(
            session = resolved.session,
            auth = resolved.auth,
            onStateChange = { change -> if (watched.any { change.emailChanged(it) }) onChanged() },
            onClosed = onClosed,
        )
    }

    /** Build an SMTP OutgoingMessage from compose fields (IMAP accounts). */
    private fun outgoing(
        credentials: AccountCredentials,
        recipients: List<String>,
        subject: String,
        body: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        html: String? = null,
        fromName: String? = null,
        fromEmail: String? = null,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
    ): OutgoingMessage {
        val from = formatFrom(fromName, fromEmail) ?: credentials.username
        requireSingleLineAddresses(listOf(from) + recipients + cc + bcc)
        return OutgoingMessage(
            from = from,
            to = recipients,
            cc = cc,
            bcc = bcc,
            subject = subject,
            body = body,
            html = html,
            inReplyTo = inReplyTo.firstOrNull(),
            references = references.joinToString(" ").ifBlank { null },
            messageId = "${java.util.UUID.randomUUID()}@${credentials.username.substringAfter('@', "localhost")}",
            dateMillis = System.currentTimeMillis(),
        )
    }

    // The From is a string-built header on the IMAP/SMTP path, so the display name must be
    // RFC 5322-quoted (specials like the '@'/'.' of an email-address name) or RFC 2047-encoded
    // (non-ASCII) here — OutgoingMime.formatAddress does that. JMAP sends a structured {name,
    // email} and is unaffected (#77).
    private fun formatFrom(name: String?, email: String?): String? = when {
        email.isNullOrBlank() -> null
        else -> OutgoingMime.formatAddress(name, email)
    }

    /**
     * Save a draft, carrying [attachments] (the chips compose shows) into it so a re-saved draft
     * keeps its files. With [replacesEmailId] set (re-saving an opened draft, #63) the old server
     * draft is destroyed once the new one is safely created, so saving never duplicates — but
     * ONLY when the new draft reproduced the old one's content: every attachment made it in and
     * [bodyIsLossy] is false. Otherwise the original survives and the caller is told
     * ([DraftSaveOutcome.ORIGINAL_KEPT]), because losing attachments or an HTML body the user was
     * just shown is irreversible while a duplicate is not. The destroy stays best-effort: a
     * failure leaves a stale copy rather than failing the save.
     */
    suspend fun saveDraft(
        credentials: AccountCredentials,
        to: List<String>,
        subject: String,
        body: String,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        replacesEmailId: String? = null,
        attachments: List<EmailBodyPart> = emptyList(),
        bodyIsLossy: Boolean = false,
        // The composer's chosen From, so a delegated sub-account's draft is honest about the
        // identity it will be sent as instead of falling back to the login's address (issue #31).
        fromName: String? = null,
        fromEmail: String? = null,
    ): DraftSaveOutcome {
        val ccTrimmed = cc.map { it.trim() }.filter { it.isNotEmpty() }
        val bccTrimmed = bcc.map { it.trim() }.filter { it.isNotEmpty() }
        if (credentials.protocol == MailProtocol.IMAP) {
            val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
            val drafts = mailboxDao.idForRole(credentials.id, "drafts") ?: error("This account has no Drafts folder.")
            val parts = imapDraftAttachments(attachments)
            imap.appendDraft(
                credentials, drafts,
                outgoing(
                    credentials, recipients, subject, body, inReplyTo, references,
                    fromName = fromName, fromEmail = fromEmail, cc = ccTrimmed, bcc = bccTrimmed,
                ).copy(attachments = parts),
            )
            val outcome = finishDraftSave(
                credentials, replacesEmailId,
                faithful = draftReplacementIsFaithful(attachments.size, parts.size, bodyIsLossy),
            )
            // IMAP APPEND returns no stable id to synthesise a keyable row from, so — after the
            // replaced original is (best-effort) expunged — reload the Drafts folder so its list
            // (a Room-backed PagingSource) shows the new draft at once, not after a pull-to-
            // refresh (#63). Best-effort: the draft is already appended; a refresh miss is benign.
            runCatching { refresh(credentials, drafts) }
            return outcome
        }
        val ctx = connect(credentials)
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }.map { EmailAddress(email = it) }
        // The From is the composer's chosen identity, falling back to the account's stored
        // identities and finally the signed-in address — never a live Identity/get: that method
        // is member-only and refused on a delegated sub-account ("You are not an owner",
        // issue #31; Stalwart answers a method-level "forbidden" for a shared account), and
        // connect() refreshes the stored server identities anyway.
        val from = if (!fromEmail.isNullOrBlank()) {
            EmailAddress(name = fromName, email = fromEmail)
        } else {
            val stored = accountStore.identities(credentials.id).firstOrNull()
            EmailAddress(name = stored?.name, email = stored?.email ?: credentials.username)
        }
        val draftsId = ctx.rolesToMailboxId["drafts"]
            ?: error("This account has no Drafts folder.")
        val blobs = jmapDraftAttachments(credentials, attachments)
        val savedId = client.saveDraft(
            session = ctx.session,
            accountId = ctx.accountId,
            auth = ctx.auth,
            from = from,
            to = recipients,
            cc = ccTrimmed.map { EmailAddress(email = it) },
            bcc = bccTrimmed.map { EmailAddress(email = it) },
            subject = subject,
            textBody = body,
            draftMailboxId = draftsId,
            inReplyTo = inReplyTo,
            references = references,
            attachments = blobs,
        )
        // Optimistically cache the just-saved draft so the Drafts list (a Room-backed
        // PagingSource) reflects it at once instead of waiting for a pull-to-refresh (#63).
        // Keyed on the server-returned id, so the next sync replaces it in place — no duplicate.
        if (savedId != null) {
            cacheSavedDraft(
                credentials, savedId, draftsId, from, recipients, subject, body,
                inReplyTo, references, hasAttachment = blobs.isNotEmpty(),
            )
        }
        // Evicts the replaced original (faithful edit) AFTER the new row is in, so the list
        // never flickers empty; a new/lossy save keeps every real draft the server now holds.
        return finishDraftSave(
            credentials, replacesEmailId,
            faithful = draftReplacementIsFaithful(attachments.size, blobs.size, bodyIsLossy),
        )
    }

    /**
     * Build and cache the Drafts-list row for a just-saved JMAP draft (#63). Uses the same
     * [Email.toEntity] mapping the sync path uses, keyed on the server-returned [emailId], so
     * the authoritative row from the next Drafts sync upserts over it in place (no duplicate,
     * no ghost). The replaced original of an edited draft is evicted separately by
     * [finishDraftSave] → [destroyDraft], keeping this row (its id is new) untouched.
     */
    private suspend fun cacheSavedDraft(
        credentials: AccountCredentials,
        emailId: String,
        draftMailboxId: String,
        from: EmailAddress,
        to: List<EmailAddress>,
        subject: String,
        body: String,
        inReplyTo: List<String>,
        references: List<String>,
        hasAttachment: Boolean,
    ) {
        val row = Email(
            id = emailId,
            accountId = credentials.id,
            mailboxId = draftMailboxId,
            subject = subject.ifBlank { null },
            preview = body.replace(Regex("\\s+"), " ").trim().take(256).ifBlank { null },
            receivedAt = java.time.Instant.now().toString(),
            from = listOf(from),
            to = to,
            inReplyTo = inReplyTo,
            references = references,
            hasAttachment = hasAttachment,
            keywords = mapOf("\$draft" to true, "\$seen" to true),
        ).toEntity(credentials.id, draftMailboxId)
        emailDao.upsertAll(listOf(row))
    }

    /**
     * Close out a draft save. The edited original is destroyed ONLY when [faithful] — every
     * attachment compose was showing made it into the replacement and the body wasn't flattened.
     * Otherwise the original stays put and the caller surfaces that, so nothing the user could
     * see is destroyed by a save that couldn't carry it (#63).
     */
    private suspend fun finishDraftSave(
        credentials: AccountCredentials,
        replacesEmailId: String?,
        faithful: Boolean,
    ): DraftSaveOutcome {
        if (replacesEmailId == null) return DraftSaveOutcome.SAVED
        if (!faithful) return DraftSaveOutcome.ORIGINAL_KEPT
        runCatching { destroyDraft(credentials, replacesEmailId) }
        return DraftSaveOutcome.SAVED
    }

    /**
     * Compose's staged attachment files, read back as MIME parts for an APPENDed IMAP draft
     * (the same bytes-from-a-staged-file path the SMTP send uses). A part whose bytes can't be
     * read is DROPPED — the caller sees fewer parts out than in and keeps the original draft.
     */
    private fun imapDraftAttachments(attachments: List<EmailBodyPart>): List<OutgoingAttachment> =
        attachments.mapNotNull { part ->
            val path = part.partId ?: return@mapNotNull null
            val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
            val inline = part.disposition.equals("inline", ignoreCase = true) && !part.cid.isNullOrBlank()
            OutgoingAttachment(
                part.name ?: "attachment", part.type ?: "application/octet-stream", bytes,
                cid = part.cid, inline = inline,
            )
        }

    /**
     * The blob-backed parts a JMAP draft can reference. Parts compose already uploaded are used
     * as-is; a part still staged as a local file (PGP SIGN keeps the bytes on the device) is
     * uploaded now so the draft can carry it — a signed draft is stored in plaintext anyway, and
     * ENCRYPT can't save drafts at all. A part that is neither is DROPPED, and the caller keeps
     * the original draft rather than destroying the only copy of that file.
     */
    private suspend fun jmapDraftAttachments(
        credentials: AccountCredentials,
        attachments: List<EmailBodyPart>,
    ): List<EmailBodyPart> = attachments.mapNotNull { part ->
        if (part.blobId != null) return@mapNotNull part
        val path = part.partId ?: return@mapNotNull null
        val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
        runCatching {
            uploadAttachment(
                credentials, bytes, part.type, part.name,
                part.disposition ?: "attachment", part.cid,
            )
        }.getOrNull()
    }

    /**
     * Permanently destroy one saved draft, server and cache — used when an edited draft has
     * been re-saved or successfully sent (#63), so no duplicate lingers in Drafts. JMAP is a
     * single-id Email/set destroy; IMAP expunges the message from its folder.
     */
    /**
     * Discard a draft the user emptied while editing it (#69): the same permanent server+cache
     * destroy the edit-replace flow uses, so an emptied draft leaves nothing behind in Drafts —
     * and, unlike a plain [delete], nothing in Trash either. Failures propagate so the caller can
     * report them rather than closing over a draft that would reappear on the next sync.
     */
    suspend fun discardDraft(credentials: AccountCredentials, emailId: String) =
        destroyDraft(credentials, emailId)

    private suspend fun destroyDraft(credentials: AccountCredentials, emailId: String) {
        if (credentials.protocol == MailProtocol.IMAP) {
            // Account-scoped (issue #31): the draft's folder must be resolved within this account.
            val folder = emailDao.mailboxOf(credentials.id, emailId)
                ?: mailboxDao.idForRole(credentials.id, "drafts")
                ?: return
            imapDestroyGroup(credentials, folder, listOf(emailId), mutableSetOf(), mutableSetOf())
            return
        }
        jmapDestroyAll(connect(credentials), listOf(emailId))
    }

    // ---- outbox (persistent send queue) ----

    /**
     * Queue a message in the persistent outbox and arm its delivery worker. This is the single
     * downstream send path: a failure (no network, server down) leaves the row in the outbox to
     * auto-retry, instead of losing the mail. With [holdMs] > 0 the item is HELD for that long
     * (the undo window), then becomes QUEUED. IMAP attachments (staged as temp files by compose)
     * are copied into a persistent per-item dir so a deferred retry can still read them; JMAP
     * attachments keep only their server blob id. Returns the new row id.
     */
    suspend fun enqueueSend(
        credentials: AccountCredentials,
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        attachments: List<EmailBodyPart> = emptyList(),
        htmlBody: String? = null,
        fromName: String? = null,
        fromEmail: String? = null,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        holdMs: Long = 0,
        pgpMode: PgpMode? = null,
        pgpEntity: String? = null,
        draftEmailId: String? = null,
    ): Long {
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
        require(recipients.isNotEmpty()) { "Add at least one recipient." }
        val ccTrimmed = cc.map { it.trim() }.filter { it.isNotEmpty() }
        val bccTrimmed = bcc.map { it.trim() }.filter { it.isNotEmpty() }
        runCatching { rememberRecipients(recipients + ccTrimmed + bccTrimmed) }

        // An encrypted payload replaces the plaintext everywhere at rest: the row
        // keeps headers only, the PGP/MIME entity (ciphertext) goes to a file, and
        // the attachments already live INSIDE the entity.
        val encrypted = pgpMode == PgpMode.ENCRYPT && pgpEntity != null
        val now = System.currentTimeMillis()
        val held = holdMs > 0
        val id = outboxDao.insert(
            OutboxEntity(
                accountId = credentials.id,
                recipients = recipients.joinToString(","),
                cc = ccTrimmed.joinToString(",").ifBlank { null },
                bcc = bccTrimmed.joinToString(",").ifBlank { null },
                subject = subject,
                textBody = if (encrypted) "" else body,
                htmlBody = if (encrypted) null else htmlBody,
                fromName = fromName,
                fromEmail = fromEmail,
                inReplyTo = inReplyTo.joinToString(" ").ifBlank { null },
                references = references.joinToString(" ").ifBlank { null },
                attachmentsJson = "[]",
                createdAtMillis = now,
                notBeforeMillis = now + holdMs,
                state = if (held) OutboxState.HELD else OutboxState.QUEUED,
                pgpMode = pgpMode?.takeIf { it != PgpMode.OFF }?.name,
                draftEmailId = draftEmailId,
            ),
        )
        // Staging runs AFTER the row is inserted (it needs the id to key the durable dir). If it
        // throws — an unreadable attachment (persistAttachments errors rather than send it short),
        // a write failure — the row must not survive: an orphan carrying attachmentsJson "[]" would
        // be re-armed at the next startup and sent amputated (#70). Roll it back and relay the error.
        stageOrRollback(rollback = { deleteOutbox(id) }) {
            if (pgpEntity != null && pgpMode != null && pgpMode != PgpMode.OFF) {
                val dir = java.io.File(outboxFilesDir, id.toString()).apply { mkdirs() }
                val entityFile = java.io.File(dir, "pgp-entity.mime").apply { writeText(pgpEntity) }
                outboxDao.byId(id)?.let { outboxDao.update(it.copy(pgpEntityPath = entityFile.absolutePath)) }
            } else {
                // Make attachments durable now that we have the item id to key the persistent dir.
                val durable = persistAttachments(id, attachments)
                outboxDao.byId(id)?.let { outboxDao.update(it.copy(attachmentsJson = OutboxAttachments.encode(durable))) }
            }
        }
        outboxScheduler?.schedule(id, holdMs)
        return id
    }

    /** Copy IMAP attachment bytes into a persistent per-item dir; keep JMAP blob ids as-is. */
    private fun persistAttachments(id: Long, attachments: List<EmailBodyPart>): List<OutboxAttachment> {
        if (attachments.isEmpty()) return emptyList()
        val dir = java.io.File(outboxFilesDir, id.toString()).apply { mkdirs() }
        return attachments.mapNotNull { part ->
            when {
                part.blobId != null -> OutboxAttachment(
                    kind = OutboxAttachments.KIND_JMAP_BLOB,
                    blobId = part.blobId, type = part.type, name = part.name, size = part.size,
                    cid = part.cid, disposition = part.disposition,
                )
                part.partId != null -> {
                    val sourcePath = part.partId!!
                    // Refuse rather than silently drop an unreadable attachment: staging fewer parts
                    // than the composer showed would queue an amputated message. The failure keeps
                    // the item out of the queue instead of sending short of what the user attached.
                    val bytes = runCatching { java.io.File(sourcePath).readBytes() }.getOrNull()
                        ?: error("Couldn't read the attachment ${part.name ?: sourcePath} to queue it.")
                    val safe = (part.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    // Keep the staged name unique so two inline images sharing a name don't collide.
                    val dest = java.io.File(dir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
                    OutboxAttachment(
                        kind = OutboxAttachments.KIND_IMAP_FILE,
                        path = dest.absolutePath, type = part.type, name = part.name, size = bytes.size.toLong(),
                        cid = part.cid, disposition = part.disposition,
                    )
                }
                else -> null
            }
        }
    }

    /**
     * Outbox items for the list, newest send order last. A row open in the composer (#70,
     * [OutboxState.EDITING]) is hidden: the user is holding it on the compose screen, so showing it
     * in the queue at the same time would be two contradictory views of one message.
     *
     * The exclusion goes through [OutboxLogic.isWaitingInOutbox] rather than being spelled out
     * here, because [outboxQueuedCount] has to agree with this list row for row.
     */
    fun outboxFlow(): Flow<List<OutboxEntity>> =
        outboxDao.observeAll().map { rows -> rows.filter { OutboxLogic.isWaitingInOutbox(it.state) } }

    /**
     * Count of failed items plus those still waiting well past their undo window, for the discreet
     * badge. A send that goes through normally never reaches it — see [OutboxLogic.activeCount].
     */
    fun outboxActiveCount(): Flow<Int> = OutboxLogic.badgeCount(outboxDao.observeBadgeItems())

    /**
     * How many messages are in the Outbox right now, for the count on the overflow menu's Outbox
     * entry — no grace, unlike [outboxActiveCount] which feeds the always-visible dot (#70).
     * Always equal to the number of rows [outboxFlow] hands the Outbox screen: same table, same
     * predicate ([OutboxLogic.isWaitingInOutbox]).
     *
     * No `distinctUntilChanged()` here, unlike [OutboxLogic.badgeCount] — and nothing is lost by
     * it: Room re-emits the whole table on every outbox write, so this does map a new list to an
     * unchanged Int fairly often, but the ViewModel collects it through `stateIn`, and a StateFlow
     * drops a value equal to the one it holds. The conflation already happens; the operator would
     * only move it one step earlier.
     */
    fun outboxQueuedCount(): Flow<Int> =
        outboxDao.observeBadgeItems().map { OutboxLogic.queuedCount(it) }

    suspend fun outboxItem(id: Long): OutboxEntity? = outboxDao.byId(id)

    /** Items still in flight, to re-arm their workers at startup (a WorkManager safety net). */
    suspend fun unfinishedOutbox(): List<OutboxEntity> = outboxDao.unfinished()

    suspend fun updateOutboxState(id: Long, state: OutboxState, attemptCount: Int, lastError: String?) {
        outboxDao.updateState(id, state, attemptCount, lastError, System.currentTimeMillis())
    }

    /** Remove an item (sent, cancelled or deleted) and clean up its persistent attachment dir. */
    suspend fun deleteOutbox(id: Long) {
        outboxDao.delete(id)
        runCatching { java.io.File(outboxFilesDir, id.toString()).deleteRecursively() }
    }

    /**
     * Re-queue an item for an immediate retry. The Outbox screen offers Retry on every row, not
     * only the failed ones, so this runs on a message that is merely waiting just as often.
     *
     * Resetting `notBeforeMillis` to now is not only about ordering: it is also the anchor the badge
     * counts from ([OutboxLogic.activeCount]), so a retried row leaves the badge for the length of
     * the grace and comes back if it still has not gone out. That is deliberate — a send the user
     * has just relaunched is a send in progress, and the badge is there for the ones that are stuck,
     * not for the ones being dealt with. On a row that had already failed it does mean the signal
     * the user was told to act on disappears for half a minute right after they acted; the Outbox
     * screen still lists the row and its state throughout, and the count returns on its own if the
     * retry does not land. Change one of the two and you change the other: they are the same field.
     */
    suspend fun retryOutbox(id: Long) {
        val item = outboxDao.byId(id) ?: return
        val now = System.currentTimeMillis()
        outboxDao.update(item.copy(state = OutboxState.QUEUED, attemptCount = 0, lastError = null, notBeforeMillis = now))
        outboxScheduler?.schedule(id, 0)
    }

    /**
     * Fields needed to reopen a queued/failed item in compose for editing (#70). IMAP attachments
     * are re-staged into the cache so they behave like freshly attached files; JMAP attachments
     * reuse their server blob id. The row itself is NOT deleted — it stays in the queue marked
     * [OutboxState.EDITING], and [outboxId] identifies it so the composer can give it back
     * ([releaseOutboxEdit]) or consume it ([deleteOutbox]) when it is done.
     */
    data class OutboxDraft(
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
        val fromAccountId: String?,
        val fromEmail: String?,
        val attachments: List<EmailBodyPart>,
        val inReplyTo: List<String>,
        val references: List<String>,
        /** The PGP mode the item was queued with (#35/#70): a signed/encrypted item reopens as such. */
        val pgpMode: String?,
        /** The saved draft the item was edited from (#63), kept so re-sending still replaces it. */
        val draftEmailId: String? = null,
        /** The queued row this draft came from, held in [OutboxState.EDITING] until the composer is done. */
        val outboxId: Long,
    )

    /**
     * Take an item out of the outbox for editing (#70): build its draft and mark the row
     * [OutboxState.EDITING] so the send worker leaves it alone while the composer holds it. The row
     * and its durable attachment dir are left in place — the message never lives only in RAM, so a
     * process death costs the edit, not the mail. Closing the composer calls [releaseOutboxEdit];
     * sending/saving/deleting calls [deleteOutbox].
     */
    suspend fun takeOutboxForEdit(id: Long, stagingDir: java.io.File): OutboxDraft? {
        val item = outboxDao.byId(id) ?: return null
        // State guard at the choke point (#70): only a genuinely waiting, non-encrypted row may be
        // taken. A SENDING row has a send in flight — marking it EDITING here would race the
        // worker's updateOutboxState (orphaned edit or double send); an EDITING row is already open.
        // The screen hides Edit for these, but the guard belongs here too, where the state flips.
        if (!OutboxLogic.canEdit(item.pgpMode, item.state)) return null
        // Stage attachments for the composer BEFORE changing state: OutboxEdit.take throws on an
        // unreadable attachment (rather than reopen an amputated message), and a throw here must
        // leave the row exactly as it was — still QUEUED, still deliverable.
        val attachments = OutboxEdit.take(OutboxAttachments.decode(item.attachmentsJson), stagingDir)
        val draft = OutboxDraft(
            to = item.recipients.split(",").joinToString(", ") { it.trim() },
            cc = item.cc?.split(",")?.joinToString(", ") { it.trim() }.orEmpty(),
            bcc = item.bcc?.split(",")?.joinToString(", ") { it.trim() }.orEmpty(),
            subject = item.subject,
            body = item.textBody,
            fromAccountId = item.accountId,
            fromEmail = item.fromEmail,
            attachments = attachments,
            inReplyTo = item.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
            references = item.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
            pgpMode = item.pgpMode,
            draftEmailId = item.draftEmailId,
            outboxId = id,
        )
        outboxDao.setState(id, OutboxState.EDITING)
        return draft
    }

    /**
     * Give a message taken out with [takeOutboxForEdit] back to the queue unchanged (#70): the
     * composer was closed, or its edits discarded. The row is right where it was left — only its
     * state flips back from EDITING to QUEUED and its delivery is re-armed. Nothing is rebuilt and
     * nothing can be lost, because nothing ever left the database.
     */
    suspend fun releaseOutboxEdit(id: Long) {
        val row = outboxDao.byId(id) ?: return
        if (row.state != OutboxState.EDITING) return
        // A FAILED item (auto-retry exhausted) reopened then closed untouched must NOT restart on its
        // own (#70 regression): it goes back to FAILED, keeping its error text, and is left off the
        // worker — only Retry/Send re-arms it. Everything else rejoins the queue and is re-armed.
        val next = OutboxLogic.stateAfterEdit(row.attemptCount)
        outboxDao.setState(id, next)
        if (next != OutboxState.QUEUED) return
        // Held or scheduled items keep whatever is left of their wait; everything else goes now.
        outboxScheduler?.schedule(id, (row.notBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0))
    }

    /**
     * Startup recovery (#70): revert rows stranded in EDITING by a process death. An exhausted row
     * (its auto-retry was spent before it was reopened) goes back to FAILED so it is NOT re-armed by
     * [unfinishedOutbox]; the rest become QUEUED again. Order matters — park the exhausted first.
     */
    suspend fun revertEditingOutbox() {
        outboxDao.revertEditingExhaustedToFailed(OutboxLogic.MAX_ATTEMPTS)
        outboxDao.revertEditingToQueued()
    }

    /** Actually deliver one outbox item (no queue indirection); exceptions propagate to the worker. */
    suspend fun performSend(credentials: AccountCredentials, item: OutboxEntity) {
        performDelivery(credentials, item)
        // Delivered: the message this item was edited from (#63) leaves Drafts. Best-effort and
        // strictly AFTER success — a cleanup hiccup must not fail (and so re-fire) a sent mail;
        // a failed delivery above throws first, leaving the draft untouched.
        item.draftEmailId?.let { runCatching { destroyDraft(credentials, it) } }
    }

    private suspend fun performDelivery(credentials: AccountCredentials, item: OutboxEntity) {
        val to = item.recipients.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val subject = item.subject
        val body = item.textBody
        val htmlBody = item.htmlBody
        val fromName = item.fromName
        val fromEmail = item.fromEmail
        val inReplyTo = item.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        val references = item.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        val ccTrimmed = item.cc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val bccTrimmed = item.bcc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val stored = OutboxAttachments.decode(item.attachmentsJson)

        // PGP/MIME items carry a pre-built entity (signed at compose time, so no
        // provider interaction is needed here in the background worker).
        val pgpEntity = item.pgpEntityPath?.let { path ->
            runCatching { java.io.File(path).readText() }.getOrNull()
                ?: error("The encrypted message payload is missing.")
        }

        if (credentials.protocol == MailProtocol.IMAP) {
            val recipients = to
            require(recipients.isNotEmpty()) { "Add at least one recipient." }
            // Durable per-item files (partId path) staged at enqueue; read them back for the MIME.
            val outAttachments = stored.mapNotNull { a ->
                val path = a.path ?: return@mapNotNull null
                // Refuse rather than deliver amputated: a staged file that can't be read fails the
                // delivery so the item stays in the outbox with its error, instead of sending short.
                val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
                    ?: error("Couldn't read the attachment ${a.name ?: path} to send it.")
                val inline = a.disposition.equals("inline", ignoreCase = true) && !a.cid.isNullOrBlank()
                OutgoingAttachment(
                    a.name ?: "attachment", a.type ?: "application/octet-stream", bytes,
                    cid = a.cid, inline = inline,
                )
            }
            val message = outgoing(
                credentials, recipients, subject, body, inReplyTo, references, htmlBody,
                fromName, fromEmail, ccTrimmed, bccTrimmed,
            ).copy(
                attachments = if (pgpEntity != null) emptyList() else outAttachments,
                pgpEntity = pgpEntity,
            )
            imap.send(credentials, message, mailboxDao.idForRole(credentials.id, "sent"))
            return
        }
        val attachments = stored.map { a ->
            EmailBodyPart(
                blobId = a.blobId, type = a.type, size = a.size, name = a.name,
                disposition = a.disposition ?: "attachment", cid = a.cid,
            )
        }
        val ctx = connect(credentials)
        val recipients = to.map { EmailAddress(email = it) }
        require(recipients.isNotEmpty()) { "Add at least one recipient." }
        val ccAddrs = ccTrimmed.map { EmailAddress(email = it) }
        val bccAddrs = bccTrimmed.map { EmailAddress(email = it) }

        // Delegated sub-account (issue #31): member-only methods are refused on the shared
        // account (Identity/get and EmailSubmission/set both fail with "You are not an owner"),
        // but the server accepts a submission created on the LOGIN's own account whose From
        // header carries the delegated address (envelope from the login's identity) — verified
        // against Stalwart. So the outgoing Email, the identity lookup and the submission all
        // target the login's primary account; the Sent copy is then re-filed into the
        // sub-account's own Sent below.
        val submissionAccountId = ctx.session.mailAccountId() ?: ctx.accountId
        val onBehalf = submissionAccountId != ctx.accountId

        val serverIdentities = client.getIdentities(ctx.session, submissionAccountId, ctx.auth)
        // Use the server identity matching the chosen address (so submission is authorised);
        // fall back to the first. The displayed From still reflects the chosen identity.
        val identity = fromEmail?.let { email -> serverIdentities.firstOrNull { it.email.equals(email, true) } }
            ?: serverIdentities.firstOrNull()
            ?: error("This account has no sending identity.")
        val from = if (!fromEmail.isNullOrBlank()) EmailAddress(name = fromName, email = fromEmail)
        else EmailAddress(name = identity.name, email = identity.email)
        // Mailbox ids are per-account: an on-behalf send stages and files in the login's
        // account, so its roles must come from there, not from the sub-account's context.
        val submissionRoles = if (onBehalf) {
            client.getMailboxes(ctx.session, submissionAccountId, ctx.auth)
                .mapNotNull { mb -> mb.role?.let { it to mb.id } }.toMap()
        } else {
            ctx.rolesToMailboxId
        }
        val draftsId = submissionRoles["drafts"]
            ?: submissionRoles["sent"]
            ?: error("This account has no Drafts or Sent folder.")
        val sentId = submissionRoles["sent"] ?: draftsId

        val sentEmailId = if (pgpEntity != null) {
            // PGP/MIME must reach the wire byte-exact (protocol=/micalg= params,
            // signed bytes), so the FULL raw message is built client-side with the
            // same builder the SMTP path uses, then imported + submitted verbatim.
            val raw = OutgoingMime.build(
                outgoing(
                    credentials, to, subject, "", inReplyTo, references, null,
                    from.name ?: fromName, from.email, ccTrimmed, bccTrimmed,
                ).copy(pgpEntity = pgpEntity),
            )
            client.importAndSendEmail(
                session = ctx.session,
                accountId = submissionAccountId,
                auth = ctx.auth,
                identityId = identity.id,
                rawMessage = raw.toByteArray(Charsets.UTF_8),
                draftMailboxId = draftsId,
                sentMailboxId = sentId,
            )
        } else {
            client.sendEmail(
                session = ctx.session,
                accountId = submissionAccountId,
                auth = ctx.auth,
                identityId = identity.id,
                from = from,
                to = recipients,
                cc = ccAddrs,
                bcc = bccAddrs,
                subject = subject,
                textBody = body,
                htmlBody = htmlBody,
                draftMailboxId = draftsId,
                sentMailboxId = sentId,
                inReplyTo = inReplyTo,
                references = references,
                attachments = attachments,
            )
        }

        // On-behalf: move the Sent copy from the login's account into the sub-account's own
        // Sent, where the user who composed there expects it. Best-effort AFTER the send stands:
        // if the copy fails the message simply stays in the login's Sent — never lost.
        val ownSentId = ctx.rolesToMailboxId["sent"]
        if (onBehalf && sentEmailId != null && ownSentId != null) {
            runCatching {
                client.copyEmailToAccount(
                    session = ctx.session,
                    auth = ctx.auth,
                    fromAccountId = submissionAccountId,
                    toAccountId = ctx.accountId,
                    emailId = sentEmailId,
                    mailboxId = ownSentId,
                )
            }
        }
        // The message is now filed in Sent with a server-assigned threadId. Pull it into the
        // local cache at once (best-effort) so the conversation it belongs to reflects the
        // reply immediately — the list counts a thread's messages from the cache, so an
        // un-cached Sent reply would otherwise leave the conversation looking like one message.
        // For an on-behalf send that is the SUB-account's Sent (where the copy just landed).
        runCatching {
            if (onBehalf) {
                ownSentId?.let { syncMailbox(ctx.session, ctx.accountId, ctx.auth, it, PAGE_SIZE, credentials.id) }
            } else {
                syncMailbox(ctx.session, ctx.accountId, ctx.auth, sentId, PAGE_SIZE, credentials.id)
            }
        }
    }

    /**
     * Send an iTIP REPLY to a calendar invite: a short text/plain note plus [replyIcs] as a
     * text/calendar attachment, addressed to [organizerEmail], from this account's identity.
     * Routes through the persistent outbox like every other send — JMAP uploads the blob, IMAP
     * stages a file — so delivery and retry happen in the background. The decisive REPLY signal is
     * the in-body METHOD:REPLY line, so the reply is valid even when a protocol can't carry the
     * Content-Type method parameter.
     */
    suspend fun sendCalendarReply(
        credentials: AccountCredentials,
        organizerEmail: String,
        subject: String,
        textBody: String,
        replyIcs: ByteArray,
    ) {
        require(organizerEmail.isNotBlank()) { "The invite has no organizer to reply to." }
        val attachment = if (credentials.protocol == MailProtocol.IMAP) {
            // No blob store for IMAP — stage the bytes as a temp file enqueueSend copies into the
            // item's durable dir, and pass the method parameter verbatim (OutgoingMime echoes it).
            val file = java.io.File.createTempFile("sterna-reply", ".ics").apply { writeBytes(replyIcs) }
            EmailBodyPart(
                partId = file.absolutePath,
                type = "text/calendar; method=REPLY; charset=utf-8",
                size = replyIcs.size.toLong(),
                name = "invite.ics",
                disposition = "attachment",
            )
        } else {
            uploadAttachment(credentials, replyIcs, "text/calendar", "invite.ics")
        }
        // Carry the account's own identity explicitly: a delegated sub-account's reply is
        // submitted through its login (issue #31) and would otherwise fall back to the
        // login's From — the organizer must see the invited address answering.
        val identity = accountStore.identities(credentials.id).firstOrNull()
        enqueueSend(
            credentials = credentials,
            to = listOf(organizerEmail),
            subject = subject,
            body = textBody,
            attachments = listOf(attachment),
            fromName = identity?.name,
            fromEmail = identity?.email,
        )
    }

    // ---- scheduled send ----

    /** Persist a message to send later; returns its row id (used to schedule the worker). */
    suspend fun insertScheduledSend(entity: ScheduledSendEntity): Long = scheduledSendDao.insert(entity)

    suspend fun scheduledSend(id: Long): ScheduledSendEntity? = scheduledSendDao.byId(id)

    suspend fun deleteScheduledSend(id: Long) = scheduledSendDao.delete(id)

    /** All scheduled sends (e.g. to re-arm workers on boot). */
    suspend fun scheduledSends(): List<ScheduledSendEntity> = scheduledSendDao.all()

    /** Observe the pending scheduled sends, newest send-time last. */
    fun scheduledSendsFlow(): Flow<List<ScheduledSendEntity>> = scheduledSendDao.observeAll()

    /** Download an attachment's bytes for the current account. */
    /**
     * The bytes of an attachment part. [maxBytes] is the ceiling this particular call accepts —
     * the caller knows whether the user asked for these bytes (an opened attachment) or whether
     * the message asked on their behalf (an inline image), and the two deserve different limits.
     */
    suspend fun downloadAttachment(
        credentials: AccountCredentials,
        part: EmailBodyPart,
        emailId: String,
        maxBytes: Long = DownloadLimits.ATTACHMENT_MAX_BYTES,
    ): ByteArray {
        // Attachments inside a decrypted OpenPGP message are sliced from the
        // in-memory decrypted entity — they have no fetchable server section.
        if (part.partId?.startsWith("pgp:") == true) return pgpAttachmentBytes(credentials.id, emailId, part)
        // The size is announced in the message itself (JMAP) or its BODYSTRUCTURE (IMAP): refuse
        // before spending the round-trip, not after buffering the answer. Enforced before the
        // protocol branch so IMAP is held to the same ceiling as JMAP (it was skipped before, the
        // check sat behind the IMAP early return).
        DownloadLimits.enforce(part.size, maxBytes)
        if (credentials.protocol == MailProtocol.IMAP) {
            val (mb, uid) = imapTarget(emailId) ?: error("Couldn't locate the message.")
            val section = part.partId ?: error("Attachment has no section.")
            return imap.fetchAttachment(credentials, mb, uid, section, part.encoding)
        }
        val ctx = connect(credentials)
        val blobId = part.blobId ?: error("Attachment has no blob.")
        return client.downloadBlob(
            ctx.session, ctx.accountId, blobId, part.type, part.name, ctx.auth, maxBytes,
        )
    }

    /** Upload bytes as an attachment blob; returns a body part ready to attach when sending. */
    suspend fun uploadAttachment(
        credentials: AccountCredentials,
        bytes: ByteArray,
        type: String?,
        name: String?,
        disposition: String = "attachment",
        cid: String? = null,
    ): EmailBodyPart {
        val ctx = connect(credentials)
        // Blobs are account-scoped (a blob uploaded to a delegated account is blobNotFound from
        // the login's — verified against Stalwart), and for a linked sub-account the outgoing
        // Email is created under the LOGIN's account (see performSend). Upload where the send
        // will reference it.
        val uploadAccountId = ctx.session.mailAccountId() ?: ctx.accountId
        val blob = client.uploadBlob(ctx.session, uploadAccountId, bytes, type, ctx.auth)
        return EmailBodyPart(
            blobId = blob.blobId,
            type = blob.type,
            size = blob.size,
            name = name,
            disposition = disposition,
            cid = cid,
        )
    }

    /**
     * Load the account's server-side vacation responder. [VacationState.Unsupported]
     * is returned for IMAP accounts (which use Sieve instead) and for JMAP servers
     * that don't advertise the vacationresponse capability.
     */
    suspend fun loadVacation(credentials: AccountCredentials): VacationState {
        if (credentials.protocol == MailProtocol.IMAP) return VacationState.Unsupported
        val ctx = connect(credentials)
        val response = client.getVacationResponse(ctx.session, ctx.accountId, ctx.auth)
            ?: return VacationState.Unsupported
        return VacationState.Loaded(response)
    }

    /** Persist the account's vacation responder server-side. */
    suspend fun saveVacation(credentials: AccountCredentials, vacation: VacationResponse): VacationResponse {
        val ctx = connect(credentials)
        return client.setVacationResponse(ctx.session, ctx.accountId, ctx.auth, vacation)
    }

    /**
     * Server-side resource quotas for the account (RFC 9425). Empty for IMAP
     * accounts and JMAP servers without the quota capability, or on any error
     * (the quota display is informational and must never break the screen).
     */
    suspend fun loadQuotas(credentials: AccountCredentials): List<Quota> {
        if (credentials.protocol == MailProtocol.IMAP) return emptyList()
        return runCatching {
            val ctx = connect(credentials)
            client.getQuotas(ctx.session, ctx.accountId, ctx.auth)
        }.getOrDefault(emptyList())
    }

    /**
     * Load the account's Sterna-managed filter rules (server-side Sieve).
     * [FilterRulesState.Unsupported] for IMAP accounts and JMAP servers without
     * the sieve capability.
     */
    suspend fun loadFilterRules(credentials: AccountCredentials): FilterRulesState {
        if (credentials.protocol == MailProtocol.IMAP) return FilterRulesState.Unsupported
        val ctx = connect(credentials)
        if (!ctx.session.capabilities.containsKey(app.sterna.core.jmap.Jmap.SIEVE_CAPABILITY)) {
            return FilterRulesState.Unsupported
        }
        val scripts = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth)
        val managed = scripts.firstOrNull { it.name == SieveCodec.SCRIPT_NAME }
        val rules = if (managed != null) {
            val bytes = client.downloadBlob(
                ctx.session, ctx.accountId, managed.blobId, "application/sieve", "sterna.siv", ctx.auth,
            )
            SieveCodec.parseRules(bytes.toString(Charsets.UTF_8))
        } else {
            emptyList()
        }
        val foreign = scripts.any { it.isActive && it.name != SieveCodec.SCRIPT_NAME }
        return FilterRulesState.Loaded(rules, foreign)
    }

    /**
     * Compile [rules] to Sieve, validate them server-side, then save and activate
     * the Sterna-managed script. Throws if the server rejects the script.
     */
    suspend fun saveFilterRules(credentials: AccountCredentials, rules: List<FilterRule>) {
        val ctx = connect(credentials)
        val script = SieveCodec.generate(rules)
        val blob = client.uploadBlob(
            ctx.session, ctx.accountId, script.toByteArray(Charsets.UTF_8), "application/sieve", ctx.auth,
        )
        client.validateSieve(ctx.session, ctx.accountId, blob.blobId, ctx.auth)?.let {
            throw IllegalStateException("The server rejected the filters: $it")
        }
        val existing = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth)
            .firstOrNull { it.name == SieveCodec.SCRIPT_NAME }
        client.saveSieveScript(
            ctx.session, ctx.accountId, SieveCodec.SCRIPT_NAME, blob.blobId, existing?.id, ctx.auth,
        )
    }

    /** Establish (or reuse) a session + mailbox-role map for the credentials. */
    private suspend fun connect(credentials: AccountCredentials): Context {
        context?.let { if (it.credentials == credentials) return it }
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = jmapAccountIdFor(credentials, session)
        reconcileLinkedAccounts(credentials, session)
        // Codeberg #32: the server is authoritative for the addresses the user may send
        // as. Refresh them into the account so the composer's From picker reflects the
        // server, on every client. Best-effort — a fetch failure must not break connect.
        runCatching {
            val serverIdentities = client.getIdentities(session, accountId, auth)
                .filter { it.email.isNotBlank() }
                .map {
                    StoredIdentity(
                        id = it.id,
                        name = it.name.orEmpty(),
                        email = it.email,
                        signature = it.textSignature.orEmpty(),
                        signatureHtml = it.htmlSignature.orEmpty(),
                    ).withSplitSignature()
                }
            accountStore.setServerIdentities(credentials.id, serverIdentities)
        }
        val mailboxes = client.getMailboxes(session, accountId, auth)
        val roles = mailboxes.mapNotNull { mb -> mb.role?.let { it to mb.id } }.toMap()
        return Context(credentials, session, accountId, auth, roles, mailboxes).also { context = it }
    }
}
