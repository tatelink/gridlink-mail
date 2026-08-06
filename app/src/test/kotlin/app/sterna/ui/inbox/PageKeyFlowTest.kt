package app.sterna.ui.inbox

import app.sterna.core.data.settings.SortOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHEN the browse list's pager is rebuilt.
 *
 * Every emission of [pageKeyFlow] rebuilds it: the ViewModel's `flatMapLatest` cancels the running
 * `Pager` and constructs a new one, which starts from `initialKey = null` — the first page of the
 * cache. On screen that is the list snapping back to the top, and the reader's swipe-to-next-message
 * losing its place with it, since it pages the same flow.
 *
 * The reported gesture: read far down a folder, open a message, tap "always show images from this
 * sender", come back — top of the list. Nothing about the list had changed. DataStore republishes
 * the WHOLE `Preferences` on every write, whatever key was touched, so `settings.sortOrder` and
 * `settings.conversationView` handed out the value they already had, an EQUAL key was built, and the
 * pager was thrown away and rebuilt. `cachedIn` does not help: it caches the pages, not the decision
 * to build a new source.
 *
 * These tests RUN the function and COUNT what it emits. They never recompute the key to decide what
 * to feed it: the inputs are fixed literals and the assertion is the number — and the content — of
 * the emissions.
 *
 * Both directions matter, and the second one more:
 *  - an equal value re-emitted upstream must produce NOTHING (the bug);
 *  - a change in ANY ONE of the six members must produce a key (a dedupe that swallows a real change
 *    is worse than the bug it fixes: the list would keep showing another folder's, another account's
 *    or another sort's rows). That second half is also what pins [PageKey]'s value equality — a
 *    member added later without it, or added and never read here, reddens exactly one of the six.
 *
 * The inputs are [MutableSharedFlow]s rather than `MutableStateFlow`s on purpose: a StateFlow
 * conflates an equal assignment all by itself, so it cannot even express the re-emission this is
 * about, and a test built on one would pass against the broken code. `dataStore.data.map { … }`, the
 * flow the bug actually travels down, is a cold flow with no such conflation.
 *
 * ⛔ Nothing here says a word about the screen keeping its scroll position: `PageFetcher`,
 * `LazyListState` and Room are all out of reach in a JVM test. This proves the pager is not ASKED to
 * rebuild; that the list then stays put is bench work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageKeyFlowTest {

    /** One upstream input, with the two moves that matter: a new value, and the same value again. */
    private class Input<T>(initial: T) {
        val flow = MutableSharedFlow<T>(replay = 1, extraBufferCapacity = 64)
        var value: T = initial
            set(v) {
                field = v
                check(flow.tryEmit(v)) { "buffer full" }
            }

        init {
            check(flow.tryEmit(initial)) { "buffer full" }
        }

        /** Re-publish the value already held — what DataStore does to every preference on a write. */
        fun again() = check(flow.tryEmit(value)) { "buffer full" }
    }

    // Fixed inputs — literals, never derived from the function under test.
    private val selection = Input<Sel>(Sel.Folder("inbox-a"))
    private val scopes = Input(listOf("a" to "inbox-a", "b" to "inbox-b"))
    private val sort = Input(SortOrder.DATE_DESC)
    private val unread = Input(false)
    private val conversation = Input(true)
    private val account = Input<String?>("a")

    /** The keys the flow emitted, in order, while [act] ran. */
    private fun keys(act: TestScope.() -> Unit): List<PageKey> {
        val seen = mutableListOf<PageKey>()
        runTest {
            val job = launch(UnconfinedTestDispatcher(testScheduler)) {
                pageKeyFlow(
                    selection.flow,
                    scopes.flow,
                    sort.flow,
                    unread.flow,
                    conversation.flow,
                    account.flow,
                ).toList(seen)
            }
            runCurrent()
            act()
            runCurrent()
            job.cancel()
        }
        return seen
    }

    @Test
    fun `the first collection yields exactly one key, built from the six inputs`() {
        val seen = keys {}
        assertEquals("subscribing must produce one key, not none and not two: $seen", 1, seen.size)
        assertEquals(
            PageKey(
                sel = Sel.Folder("inbox-a"),
                unifiedScopes = listOf("a" to "inbox-a", "b" to "inbox-b"),
                sort = SortOrder.DATE_DESC,
                unreadOnly = false,
                conversationView = true,
                accountId = "a",
            ),
            seen.single(),
        )
    }

    // -- the bug: an equal value re-emitted upstream must not rebuild anything ------------------

    @Test
    fun `a setting written elsewhere re-emits the same values and rebuilds nothing`() {
        // The reported gesture, in the only shape a JVM test can hold it: setImageAllowed writes one
        // DataStore key, DataStore republishes every preference, and the list's settings flows hand
        // out the value they already had. Each input is re-emitted in turn, and settled in between,
        // so a guard that only covered some of them cannot hide behind combine's conflation.
        val seen = keys {
            selection.again(); runCurrent()
            scopes.again(); runCurrent()
            sort.again(); runCurrent()
            unread.again(); runCurrent()
            conversation.again(); runCurrent()
            account.again(); runCurrent()
        }
        assertEquals(
            "an equal key must not reach flatMapLatest: each one cancels the running Pager and " +
                "builds a new one from initialKey = null, i.e. the first page of the cache — the top " +
                "of the list, wherever the reader was. Keys emitted: $seen",
            1, seen.size,
        )
    }

    @Test
    fun `going back to a value already seen still rebuilds`() {
        // The guard compares with the PREVIOUS key only, and that is what is wanted: the pager for
        // "unread only" is not the pager for "everything", so coming back to a setting must build the
        // source again. Remembering every key ever seen would be a dedupe too wide.
        val seen = keys {
            unread.value = true; runCurrent()
            unread.value = false; runCurrent()
        }
        assertEquals("A then B then A must emit three times, not two: $seen", 3, seen.size)
        assertEquals(listOf(false, true, false), seen.map { it.unreadOnly })
    }

    // -- the other direction: each of the six members must still rebuild the pager ---------------
    //
    // One test per member, each moving a value that differs in that member ALONE. A guard that
    // stopped reading one of them, or a PageKey member that lost its value equality, reddens exactly
    // one of these — which is what names the mistake.

    @Test
    fun `changing the selected folder rebuilds`() {
        val seen = keys { selection.value = Sel.Folder("archive-a") }
        assertEquals("a different folder must be paged: $seen", 2, seen.size)
        assertEquals(listOf<Sel>(Sel.Folder("inbox-a"), Sel.Folder("archive-a")), seen.map { it.sel })
    }

    @Test
    fun `switching between a folder and the unified inbox rebuilds`() {
        // Sel's two variants are a data class and a data object: both compare by value, and the
        // unified inbox is not "some folder" — it pages a different query entirely.
        val seen = keys { selection.value = Sel.Unified }
        assertEquals("Folder to Unified must be paged afresh: $seen", 2, seen.size)
        assertEquals(listOf<Sel>(Sel.Folder("inbox-a"), Sel.Unified), seen.map { it.sel })
    }

    @Test
    fun `an account signed out of the unified inbox rebuilds`() {
        // A LIST of pairs, compared element by element, or a removed account's leftover rows keep
        // being paged (the #121 guard the key documents) with no account able to act on them.
        val seen = keys { scopes.value = listOf("a" to "inbox-a") }
        assertEquals("the unified scope set is part of the query: $seen", 2, seen.size)
        assertEquals(
            listOf(listOf("a" to "inbox-a", "b" to "inbox-b"), listOf("a" to "inbox-a")),
            seen.map { it.unifiedScopes },
        )
    }

    @Test
    fun `the same accounts in a different order rebuild`() {
        // Two scopes equal as sets and unequal as lists. The pager's SQL takes them in order, so this
        // is a real change — and it is the one a comparison flattened to a set would swallow.
        val seen = keys { scopes.value = listOf("b" to "inbox-b", "a" to "inbox-a") }
        assertEquals("the scope list's order is part of the key: $seen", 2, seen.size)
    }

    @Test
    fun `changing the sort order rebuilds`() {
        val seen = keys { sort.value = SortOrder.FLAGGED_FIRST }
        assertEquals("a new sort is a new query: $seen", 2, seen.size)
        assertEquals(listOf(SortOrder.DATE_DESC, SortOrder.FLAGGED_FIRST), seen.map { it.sort })
    }

    @Test
    fun `turning the unread filter on rebuilds`() {
        val seen = keys { unread.value = true }
        assertEquals("the unread filter is part of the query: $seen", 2, seen.size)
        assertEquals(listOf(false, true), seen.map { it.unreadOnly })
    }

    @Test
    fun `turning conversation view off rebuilds`() {
        val seen = keys { conversation.value = false }
        assertEquals("collapsing threads or not is part of the query: $seen", 2, seen.size)
        assertEquals(listOf(true, false), seen.map { it.conversationView })
    }

    @Test
    fun `switching accounts rebuilds even when the folder id is unchanged`() {
        // Why accountId is in the key at all: JMAP numbers mailboxes per account, so two accounts'
        // inboxes routinely share an id. Sel.Folder("inbox-a") names a different folder under a
        // different account, and accountId is the only thing that tells them apart.
        val seen = keys { account.value = "b" }
        assertEquals("the active account is part of the key: $seen", 2, seen.size)
        assertEquals(listOf("a", "b"), seen.map { it.accountId })
    }

    @Test
    fun `signing the last account out rebuilds`() {
        val seen = keys { account.value = null }
        assertEquals("losing the active account must reach the pager: $seen", 2, seen.size)
        assertEquals(listOf("a", null), seen.map { it.accountId })
    }
}
