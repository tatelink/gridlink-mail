package app.gridlink.core.data.dav

import app.gridlink.core.data.db.DavCollectionDao
import app.gridlink.core.data.db.DavCollectionEntity
import app.gridlink.core.jmap.JmapException
import kotlinx.coroutines.CancellationException

/**
 * One JMAP collection kind synced into the cache: discovery, state, changes, paging and cleanup.
 *
 * Calendars and address books are the same sync twice over. Both discover a list of collections,
 * both take an account-scoped state before listing anything, both prefer a `Foo/changes` delta and
 * fall back to a full `Foo/query`, and both then have to work out which cached rows the server has
 * stopped mentioning. The only parts that genuinely differ are which JMAP methods get called and
 * which table the rows land in, and those are [Ops].
 *
 * This used to live twice inside `DavRepository`, once per kind. Two copies of a loop with five
 * ways out is two copies that drift, and the drift would be silent: a calendar that stops syncing
 * because only the contacts copy learned a lesson looks exactly like a calendar with nothing in it.
 *
 * One instance covers ONE sync pass. The session, the credentials and the account are all bound
 * before it is built, so nothing here has to be re-checked halfway through.
 *
 * ## The order of the network steps is the point
 * The state is read FIRST, then the listing. An edit that lands between them is re-reported on the
 * next run, because the stored state predates it; taking the state last would put that edit in the
 * gap between what was listed and what the state claims was seen, and it would never be asked for
 * again. Erring towards re-fetching is free. Erring the other way loses an appointment silently,
 * which is the one failure a calendar must not have.
 *
 * ## Why the state is written to every collection row
 * `Foo/changes` is scoped to the ACCOUNT, not to one calendar or one address book, so there is
 * exactly one state for all of them. Rather than elect a row to hold it (and lose the sync history
 * the day that collection is unshared), every row carries the same copy and any of them can seed
 * the next run.
 *
 * @param kind [DavCollectionEntity.KIND_CALENDAR] or [DavCollectionEntity.KIND_CONTACTS].
 * @param syntheticUrl the local key for a collection this cache has never seen; see
 *   [DavMappers.adoptCollectionUrls] for why an already-known collection does not get one.
 */
internal class JmapCollectionSync(
    private val accountId: String,
    private val kind: String,
    private val collectionDao: DavCollectionDao,
    private val syntheticUrl: (String) -> String,
    private val ops: Ops,
) {

    /**
     * Everything about one sync that depends on which collection kind it is.
     *
     * Deliberately narrow, and deliberately not a client: an implementation is free to hold a
     * session, an auth and a DAO, and this class never learns that any of them exist. Each member
     * is a seam a test can stand in for without a server.
     */
    interface Ops {
        /** What the server says this account holds, already normalised. */
        suspend fun collections(): List<Discovered>

        /** The account-scoped `Foo/state`, or null when the server would not say. */
        suspend fun state(): String?

        /** One `Foo/changes` round from [since]. */
        suspend fun changes(since: String?): ChangeRound

        /** One `Foo/query` page of ids. */
        suspend fun queryIds(limit: Int, position: Int): List<String>

        /** Fetch these ids and write them to the cache. See [Fetched]. */
        suspend fun fetch(ids: List<String>): Fetched

        /** Drop every cached row one server id produced. */
        suspend fun forget(id: String)

        /**
         * Drop every JMAP-backed row whose server id is not in [keep], and say how many went.
         *
         * 🔴 JMAP-backed is a column, not an href prefix: a collection adopted from DAV kept its
         * DAV hrefs. Rows with no server id at all are none of a JMAP listing's business.
         */
        suspend fun deleteStale(keep: Set<String>): Int

        /** Drop cached items filed under a collection that is not in [urls]. */
        suspend fun deleteNotInCollections(urls: List<String>)

        /** Drop every cached item for this account and kind. */
        suspend fun clearItems()
    }

    /**
     * A server collection reduced to what the sync needs of it.
     *
     * [row] is a function rather than a value because the url is not known until the adoption pass
     * has run, and the url is the row's primary key.
     */
    data class Discovered(
        val id: String,
        /** The human label, and the only handle adoption has to match an existing row on. */
        val name: String,
        val row: (url: String, order: Int) -> DavCollectionEntity,
    )

    /** One `Foo/changes` response, in the shape the loop reads it. */
    data class ChangeRound(
        /** False when the server answered `cannotCalculateChanges`. */
        val calculated: Boolean,
        val changed: List<String>,
        val destroyed: List<String>,
        val hasMore: Boolean,
        val newState: String?,
    )

    /**
     * What one fetch-and-cache round produced.
     *
     * @param returned the ids the server actually handed back, which is NOT always the ids asked
     *   for; see [applyDelta].
     * @param hrefs the cache keys written, which is the item count as far as the outcome goes. A
     *   count would not do: one server event can produce several rows.
     */
    data class Fetched(val returned: Set<String>, val hrefs: List<String>)

    /** What one `Foo/changes` run came back with, flattened across its rounds. */
    private data class Delta(val changed: List<String>, val destroyed: List<String>)

    /** One whole sync pass. Never throws for a server's sake: a failure comes back as prose. */
    suspend fun run(): DavSyncOutcome {
        val collections = try {
            ops.collections()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return DavSyncOutcome(0, 0, 0, error = describeJmap(e))
        }
        val existing = collectionDao.forKind(accountId, kind)
        // 🔴 Before anything else, and before the items: a collection this account already synced
        // over DAV keeps its row rather than being deleted and recreated under a `jmap:` url. See
        // DavMappers.adoptCollectionUrls for what that costs the user otherwise. It has to happen
        // first because the items are filed under these urls, and deleteNotInCollections below
        // would drop every item of an adopted collection if it ran against the synthetic list.
        val adopted = DavMappers.adoptCollectionUrls(
            existing = existing,
            collections = collections.map { it.id to it.name },
            syntheticUrl = syntheticUrl,
        )
        val urls = collections.mapNotNull { adopted[it.id] }
        val resumeFrom = existing.firstNotNullOfOrNull { it.syncToken }
        collectionDao.replaceDiscovered(
            accountId = accountId,
            kind = kind,
            discovered = collections.mapIndexed { i, c -> c.row(adopted.getValue(c.id), i) },
        )
        // A collection the server no longer lists takes its items with it. This also clears rows a
        // previous DAV sync of the same account left behind whose collection was NOT adopted, since
        // an unadopted DAV url cannot appear among these.
        if (urls.isEmpty()) {
            ops.clearItems()
            return DavSyncOutcome(collections = 0, itemsChanged = 0, itemsRemoved = 0)
        }
        ops.deleteNotInCollections(urls)

        return try {
            val nextState = ops.state()
            val delta = incremental(resumeFrom)
            val outcome = if (delta != null) applyDelta(delta) else full()
            // Written last, and only after the rows it describes are stored: a state recorded
            // before them would make a crash mid-sync look like a completed one, and everything in
            // between would never be asked for again. Same rule the DAV sync token follows.
            if (nextState != null) urls.forEach { collectionDao.setSyncToken(accountId, it, nextState) }
            outcome.copy(collections = collections.size)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            DavSyncOutcome(collections.size, 0, 0, error = describeJmap(e))
        }
    }

    /**
     * Every change since [resumeFrom], or null when the server cannot say and a full list is needed.
     *
     * Null is returned for both "we have never synced" and "the server answered
     * `cannotCalculateChanges`", because the answer to both is the same and treating the second as
     * an empty change set would stop this account syncing without ever reporting an error.
     */
    // Four of the five exits are "this delta cannot be trusted, list everything", each spotted at a
    // different point in the loop. A single exit would need a flag threaded through the loop body
    // and would make it easy to add a fifth failure that forgets to set it.
    @Suppress("ReturnCount")
    private suspend fun incremental(resumeFrom: String?): Delta? {
        if (resumeFrom.isNullOrBlank()) return null
        val changed = LinkedHashSet<String>()
        val destroyed = LinkedHashSet<String>()
        var state: String? = resumeFrom
        var rounds = 0
        while (rounds < MAX_CHANGE_ROUNDS) {
            val result = ops.changes(state)
            if (!result.calculated) return null
            changed += result.changed
            destroyed += result.destroyed
            rounds++
            if (!result.hasMore) return Delta(changed.toList(), destroyed.toList())
            // A server that reports more changes but no new state would spin this loop forever on
            // the same page. Falling back to a full list is slower and always terminates.
            state = result.newState ?: return null
        }
        // More rounds than a sane delta needs means the account has effectively been rewritten;
        // listing it is cheaper than paging through the rest of the history.
        return null
    }

    /** Apply a change set: fetch what changed, drop what was destroyed. */
    private suspend fun applyDelta(delta: Delta): DavSyncOutcome {
        val fetched = ops.fetch(delta.changed)
        // 🔴 Ids the server reported as changed but did not return are deleted, not skipped. An id
        // that vanishes between the two calls has been removed in the meantime, and leaving its row
        // in place would keep a deleted appointment on the phone until the next full list.
        val missing = delta.changed.filterNot { it in fetched.returned }
        (delta.destroyed + missing).forEach { ops.forget(it) }
        return DavSyncOutcome(0, fetched.hrefs.size, delta.destroyed.size + missing.size)
    }

    /** List the whole account and make the cache match it. */
    private suspend fun full(): DavSyncOutcome {
        val ids = LinkedHashSet<String>()
        var page = 0
        var more = true
        while (more && page < MAX_QUERY_PAGES) {
            // 🔴 No date window. `after`/`before` filter on OCCURRENCES, so a window would be a
            // sensible-looking way to bound the sync, and it would also delete every event outside
            // it from a cache the month view can scroll anywhere in. DAV syncs the lot; so does
            // this.
            val returned = ops.queryIds(limit = QUERY_PAGE_SIZE, position = ids.size)
            val before = ids.size
            ids += returned
            // Stop on a short page (it was the last one) and on a page that added nothing, which is
            // a server paging in circles: a full page of ids we already hold would otherwise loop
            // until the page cap with the same request every time.
            more = ids.size > before && returned.size >= QUERY_PAGE_SIZE
            page++
        }
        val fetched = ops.fetch(ids.toList())
        // Anything JMAP-backed the listing did not name is gone from the server.
        val removed = ops.deleteStale(fetched.returned)
        return DavSyncOutcome(0, fetched.hrefs.size, removed)
    }
}

/**
 * A JMAP failure in words worth showing. `DavRepository.describe`'s job for the other protocol.
 *
 * 401 is the one the user can act on, and it arrives as a transport failure here rather than as a
 * DavException, so the same sentence has to be reached by a different route.
 */
internal fun describeJmap(e: Exception): String = when ((e as? JmapException)?.httpCode) {
    HTTP_UNAUTHORIZED -> "Sign-in was refused. Check the password for this account."
    HTTP_FORBIDDEN -> "The server refused access to this collection."
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Sync failed"
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

/**
 * Most `Foo/changes` rounds one sync will page through before giving up and listing.
 *
 * A delta this long is not a delta: it means the account has effectively been rewritten, and a
 * single listing costs less than paging the rest of its history.
 */
private const val MAX_CHANGE_ROUNDS = 20

/** Ids per `Foo/query` page, and the most pages one sync will ask for. */
private const val QUERY_PAGE_SIZE = 500
private const val MAX_QUERY_PAGES = 40
