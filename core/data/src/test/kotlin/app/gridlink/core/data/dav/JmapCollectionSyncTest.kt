package app.gridlink.core.data.dav

import app.gridlink.core.data.db.DavCollectionDao
import app.gridlink.core.data.db.DavCollectionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The JMAP sync algorithm both collection kinds run, exercised without a server.
 *
 * These paths had no coverage while they lived twice over as private methods inside
 * `DavRepository`: the only way to reach them was a real session and a real account. Lifting them
 * into [JmapCollectionSync] behind [JmapCollectionSync.Ops] is most of what makes this file
 * possible, and the loops it pins are the ones with teeth — the four ways a delta gets rejected,
 * the two circuit breakers in the paging loop, and the rule that the sync token is written last.
 */
class JmapCollectionSyncTest {

    // ---- Fakes -----------------------------------------------------------------------------

    /** An in-memory [DavCollectionDao]. `replaceDiscovered` is a real default method, so it runs. */
    private class FakeCollectionDao : DavCollectionDao {
        val rows = mutableListOf<DavCollectionEntity>()
        val log = mutableListOf<String>()

        override fun observe(accountId: String, kind: String): Flow<List<DavCollectionEntity>> =
            throw UnsupportedOperationException("not used by the sync")

        override suspend fun forKind(accountId: String, kind: String) =
            rows.filter { it.accountId == accountId && it.kind == kind }.sortedBy { it.sortOrder }

        override suspend fun upsertAll(collections: List<DavCollectionEntity>) {
            collections.forEach { row ->
                rows.removeAll { it.accountId == row.accountId && it.url == row.url }
                rows += row
            }
        }

        override suspend fun setSyncToken(accountId: String, url: String, token: String?) {
            log += "token:$url=$token"
            rows.replaceAll { if (it.accountId == accountId && it.url == url) it.copy(syncToken = token) else it }
        }

        override suspend fun deleteNotIn(accountId: String, kind: String, keepUrls: List<String>) {
            rows.removeAll { it.accountId == accountId && it.kind == kind && it.url !in keepUrls }
        }

        override suspend fun deleteForAccount(accountId: String) {
            rows.removeAll { it.accountId == accountId }
        }

        override suspend fun accountIds() = rows.map { it.accountId }.distinct()
    }

    /**
     * A server, scripted. Every call is recorded in [log] so ORDER can be asserted, which is the
     * whole point of several of these tests.
     */
    private class FakeOps(
        val discovered: List<Pair<String, String>> = listOf("c1" to "Personal"),
        val collectionsError: Exception? = null,
        val state: String? = "state-2",
        val rounds: List<JmapCollectionSync.ChangeRound> = emptyList(),
        val pages: List<List<String>> = listOf(emptyList()),
        /** Ids the server hands back, keyed by what was asked for; absent means "all of them". */
        val withholds: Set<String> = emptySet(),
        val fetchError: Exception? = null,
    ) : JmapCollectionSync.Ops {
        val log = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        var staleKeep: Set<String>? = null
        var cleared = false
        var notInCollections: List<String>? = null
        private var round = 0
        private var page = 0

        override suspend fun collections(): List<JmapCollectionSync.Discovered> {
            log += "collections"
            collectionsError?.let { throw it }
            return discovered.map { (id, name) ->
                JmapCollectionSync.Discovered(id, name) { url, order ->
                    DavCollectionEntity(
                        accountId = ACCOUNT,
                        url = url,
                        kind = DavCollectionEntity.KIND_CALENDAR,
                        displayName = name,
                        color = null,
                        syncToken = null,
                        sortOrder = order,
                        remoteId = id,
                    )
                }
            }
        }

        override suspend fun state(): String? {
            log += "state"
            return state
        }

        override suspend fun changes(since: String?): JmapCollectionSync.ChangeRound {
            log += "changes:$since"
            return rounds.getOrElse(round++) { error("the sync asked for more rounds than were scripted") }
        }

        override suspend fun queryIds(limit: Int, position: Int): List<String> {
            log += "query:$position"
            return pages.getOrElse(page++) { emptyList() }
        }

        override suspend fun fetch(ids: List<String>): JmapCollectionSync.Fetched {
            log += "fetch:${ids.joinToString(",")}"
            fetchError?.let { throw it }
            val returned = ids.filterNot { it in withholds }
            return JmapCollectionSync.Fetched(
                returned = returned.toSet(),
                hrefs = returned.map { "href/$it" },
            )
        }

        override suspend fun forget(id: String) {
            log += "forget:$id"
            forgotten += id
        }

        override suspend fun deleteStale(keep: Set<String>): Int {
            log += "stale"
            staleKeep = keep
            return 0
        }

        override suspend fun deleteNotInCollections(urls: List<String>) {
            notInCollections = urls
        }

        override suspend fun clearItems() {
            cleared = true
        }
    }

    private val dao = FakeCollectionDao()

    private fun sync(ops: JmapCollectionSync.Ops) = JmapCollectionSync(
        accountId = ACCOUNT,
        kind = DavCollectionEntity.KIND_CALENDAR,
        collectionDao = dao,
        syntheticUrl = DavMappers::jmapCollectionUrl,
        ops = ops,
    )

    private fun dav(url: String, name: String, token: String? = null) = DavCollectionEntity(
        accountId = ACCOUNT,
        url = url,
        kind = DavCollectionEntity.KIND_CALENDAR,
        displayName = name,
        color = null,
        syncToken = token,
        sortOrder = 0,
    )

    private fun round(
        calculated: Boolean = true,
        changed: List<String> = emptyList(),
        destroyed: List<String> = emptyList(),
        hasMore: Boolean = false,
        newState: String? = null,
    ) = JmapCollectionSync.ChangeRound(calculated, changed, destroyed, hasMore, newState)

    // ---- Discovery -------------------------------------------------------------------------

    @Test fun aCollectionAlreadySyncedOverDavKeepsItsUrlAndGainsItsServerId() {
        dao.rows += dav("https://mail.example/dav/cal/personal/", "Personal", token = "dav-token")

        val outcome = runBlocking { sync(FakeOps()).run() }

        val row = dao.rows.single()
        // 🔴 The url is what the system-calendar mirror derives `_SYNC_ID` from. A synthetic one
        // here reads to the phone as the calendar being deleted and a stranger inserted.
        assertEquals("https://mail.example/dav/cal/personal/", row.url)
        assertEquals("c1", row.remoteId)
        assertEquals(1, outcome.collections)
    }

    @Test fun aServerThatCannotBeListedComesBackAsProseNotAnException() {
        val outcome = runBlocking { sync(FakeOps(collectionsError = IOException("no route to host"))).run() }

        assertEquals("no route to host", outcome.error)
        assertEquals(0, outcome.collections)
        // Nothing past discovery ran, so nothing was cleaned up on the strength of a failed listing.
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun anAccountWithNoCollectionsLeftHasItsItemsCleared() {
        dao.rows += dav("https://mail.example/dav/cal/personal/", "Personal")
        val ops = FakeOps(discovered = emptyList())

        val outcome = runBlocking { sync(ops).run() }

        assertTrue(ops.cleared)
        assertTrue(dao.rows.isEmpty())
        assertEquals(0, outcome.collections)
        assertNull(outcome.error)
        // No state, no listing: there is nothing left to list it into.
        assertEquals(listOf("collections"), ops.log)
    }

    // ---- The sync token --------------------------------------------------------------------

    @Test fun theStateIsTakenBeforeTheListingAndWrittenAfterTheRowsItDescribes() {
        val ops = FakeOps(pages = listOf(listOf("E1")))

        runBlocking { sync(ops).run() }

        // 🔴 state BEFORE query: an edit landing between them is re-reported next run rather than
        // falling into the gap. token AFTER fetch: a token recorded first would make a crash
        // mid-sync look like a completed one.
        assertEquals(listOf("collections", "state", "query:0", "fetch:E1", "stale"), ops.log)
        assertEquals("state-2", dao.rows.single().syncToken)
    }

    @Test fun everyCollectionRowCarriesTheSameStateBecauseChangesIsAccountScoped() {
        val ops = FakeOps(discovered = listOf("c1" to "Personal", "c2" to "Birthdays"))

        runBlocking { sync(ops).run() }

        assertEquals(2, dao.rows.size)
        assertTrue(dao.rows.all { it.syncToken == "state-2" })
    }

    @Test fun aServerThatWillNotStateItsStateStillSyncsAndStoresNoToken() {
        val ops = FakeOps(state = null)

        val outcome = runBlocking { sync(ops).run() }

        assertNull(outcome.error)
        // Storing something invented here would resume the next sync from a state no server knows.
        assertNull(dao.rows.single().syncToken)
    }

    @Test fun aFailureMidSyncReportsTheCollectionsItFoundAndWritesNoToken() {
        dao.rows += dav("https://mail.example/dav/cal/personal/", "Personal")
        val ops = FakeOps(fetchError = IOException("connection reset"))

        val outcome = runBlocking { sync(ops).run() }

        assertEquals("connection reset", outcome.error)
        assertEquals(1, outcome.collections)
        assertNull(dao.rows.single().syncToken)
    }

    // ---- The delta, and the four ways it gets rejected ---------------------------------------

    @Test fun anAccountWithNoStoredTokenIsListedWholeAndNeverAsksForChanges() {
        val ops = FakeOps(pages = listOf(listOf("E1", "E2")))

        val outcome = runBlocking { sync(ops).run() }

        assertTrue(ops.log.none { it.startsWith("changes") })
        assertEquals(2, outcome.itemsChanged)
    }

    @Test fun aDeltaIsTakenWhenThereIsAStateToResumeFrom() {
        dao.rows += dav("https://mail.example/dav/cal/personal/", "Personal", token = "state-1")
        val ops = FakeOps(rounds = listOf(round(changed = listOf("E1"), destroyed = listOf("E9"))))

        val outcome = runBlocking { sync(ops).run() }

        assertEquals(listOf("collections", "state", "changes:state-1", "fetch:E1", "forget:E9"), ops.log)
        assertEquals(1, outcome.itemsChanged)
        assertEquals(1, outcome.itemsRemoved)
    }

    @Test fun cannotCalculateChangesFallsBackToAFullListRatherThanReadingAsNoChanges() {
        dao.rows += dav("https://mail.example/dav/cal/personal/", "Personal", token = "state-1")
        // 🔴 Treating this as an empty change set is the failure mode this exit exists for: the
        // account would stop syncing entirely and never report an error while doing it.
        val ops = FakeOps(rounds = listOf(round(calculated = false)), pages = listOf(listOf("E1")))

        val outcome = runBlocking { sync(ops).run() }

        assertTrue(ops.log.contains("query:0"))
        assertEquals(1, outcome.itemsChanged)
    }

    @Test fun aServerReportingMoreChangesWithNoNewStateFallsBackInsteadOfSpinning() {
        dao.rows += dav("https://mail.example/dav/cal/personal/", "Personal", token = "state-1")
        // The same request would be re-sent forever, because there is no state to advance to.
        val ops = FakeOps(
            rounds = listOf(round(changed = listOf("E1"), hasMore = true, newState = null)),
            pages = listOf(listOf("E1")),
        )

        runBlocking { sync(ops).run() }

        assertEquals(1, ops.log.count { it.startsWith("changes") })
        assertTrue(ops.log.contains("query:0"))
    }

    @Test fun aDeltaLongerThanTheRoundCapIsAbandonedForAFullList() {
        dao.rows += dav("https://mail.example/dav/cal/personal/", "Personal", token = "state-1")
        val endless = List(MAX_ROUNDS_UNDER_TEST) { round(changed = listOf("E$it"), hasMore = true, newState = "s$it") }
        val ops = FakeOps(rounds = endless, pages = listOf(listOf("E1")))

        runBlocking { sync(ops).run() }

        // The cap is hit and the loop leaves rather than paging the account's whole history.
        assertEquals(MAX_ROUNDS_UNDER_TEST, ops.log.count { it.startsWith("changes") })
        assertTrue(ops.log.contains("query:0"))
    }

    @Test fun severalRoundsAreFlattenedIntoOneChangeSet() {
        dao.rows += dav("https://mail.example/dav/cal/personal/", "Personal", token = "state-1")
        val ops = FakeOps(
            rounds = listOf(
                round(changed = listOf("E1"), hasMore = true, newState = "state-1b"),
                round(changed = listOf("E1", "E2"), destroyed = listOf("E9")),
            ),
        )

        val outcome = runBlocking { sync(ops).run() }

        // E1 named in both rounds is fetched once: the accumulator is a set, not a list.
        assertEquals(
            listOf("collections", "state", "changes:state-1", "changes:state-1b", "fetch:E1,E2", "forget:E9"),
            ops.log,
        )
        assertEquals(2, outcome.itemsChanged)
    }

    @Test fun anIdReportedAsChangedButNotHandedBackIsDeletedNotSkipped() {
        dao.rows += dav("https://mail.example/dav/cal/personal/", "Personal", token = "state-1")
        // 🔴 It was removed between the two calls. Skipping it leaves a deleted appointment on the
        // phone until whenever the next full list happens to run.
        val ops = FakeOps(rounds = listOf(round(changed = listOf("E1", "E2"))), withholds = setOf("E2"))

        val outcome = runBlocking { sync(ops).run() }

        assertEquals(listOf("E2"), ops.forgotten)
        assertEquals(1, outcome.itemsChanged)
        assertEquals(1, outcome.itemsRemoved)
    }

    // ---- The full listing ---------------------------------------------------------------------

    @Test fun pagingStopsOnAShortPage() {
        val ops = FakeOps(pages = listOf(List(3) { "E$it" }, listOf("E99")))

        runBlocking { sync(ops).run() }

        assertEquals(1, ops.log.count { it.startsWith("query") })
    }

    @Test fun aServerPagingInCirclesIsStoppedRatherThanAskedFortyTimes() {
        // A full page of ids already held adds nothing, which is the tell. Without this exit the
        // same request goes out until the page cap.
        val repeated = List(PAGE_SIZE_UNDER_TEST) { "E$it" }
        val ops = FakeOps(pages = listOf(repeated, repeated, repeated))

        runBlocking { sync(ops).run() }

        assertEquals(2, ops.log.count { it.startsWith("query") })
    }

    @Test fun theStaleSweepIsGivenWhatTheServerActuallyReturned() {
        val ops = FakeOps(pages = listOf(listOf("E1", "E2")), withholds = setOf("E2"))

        runBlocking { sync(ops).run() }

        // 🔴 The ids handed back, not the ids asked for. An id the query named but the get did not
        // return is gone, and keeping it would leave its row behind forever.
        assertEquals(setOf("E1"), ops.staleKeep)
    }

    @Test fun itemsFiledUnderACollectionTheServerDroppedAreCleanedUpFirst() {
        dao.rows += dav("https://mail.example/dav/cal/personal/", "Personal")
        dao.rows += dav("https://mail.example/dav/cal/old/", "Retired")
        val ops = FakeOps()

        runBlocking { sync(ops).run() }

        assertEquals(listOf("https://mail.example/dav/cal/personal/"), ops.notInCollections)
        assertEquals(listOf("https://mail.example/dav/cal/personal/"), dao.rows.map { it.url })
    }

    private companion object {
        const val ACCOUNT = "acct"

        /** Mirrors `MAX_CHANGE_ROUNDS` in the class under test. */
        const val MAX_ROUNDS_UNDER_TEST = 20

        /** Mirrors `QUERY_PAGE_SIZE` in the class under test. */
        const val PAGE_SIZE_UNDER_TEST = 500
    }
}
