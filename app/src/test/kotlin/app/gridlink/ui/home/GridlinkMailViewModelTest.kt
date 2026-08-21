package app.gridlink.ui.home

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.gridlink.GridlinkApplication
import app.gridlink.TestGridlinkApplication
import app.gridlink.core.data.db.EmailEntity
import app.gridlink.core.data.db.GridlinkDatabase
import app.gridlink.core.data.db.MailboxEntity
import app.gridlink.core.data.mail.MailFilter
import app.gridlink.ui.gridlink.GridlinkFolderRole
import app.gridlink.ui.gridlink.GridlinkMailContent
import app.gridlink.ui.gridlink.GridlinkMenuItem
import app.gridlink.ui.gridlink.GridlinkMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [GridlinkMailViewModel] driven directly against the real container and no mail server. The
 * list is read from the mail cache, so the cache is seeded through the database the container
 * opened (a second handle on the same file) and the view model is bound on top of it: the rows
 * come out mapped newest-first with the account's inbox in the folder tree; the quick filters
 * narrow the window in SQL; conversation view folds a thread into one row that [toggleThread]
 * unfolds; search answers from the local index with the server leg failing quietly; the unified
 * inbox merges two accounts and labels every row. Without an inbox id the list waits, and a sync
 * that cannot reach the server still lets it settle. Opening a message needs the server and lands
 * as an error on the open slot rather than a throw. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGridlinkApplication::class)
class GridlinkMailViewModelTest {

    private val app: GridlinkApplication get() = ApplicationProvider.getApplicationContext()
    private val store get() = app.container.accountStore
    private val settings get() = app.container.settingsRepository

    private lateinit var vm: GridlinkMailViewModel

    /** A second handle on the container's database file, for seeding the cache the list reads. */
    private val seedDb: GridlinkDatabase by lazy { GridlinkDatabase.build(app) }

    /** Where the content flows' subscribers live: `WhileSubscribed` needs one before it reads. */
    private val subscribers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun startWithNoAccounts() {
        store.clear()
        vm = GridlinkMailViewModel(app)
    }

    @After
    fun dropSubscribers() {
        subscribers.cancel()
        // The one setting this class writes goes back to its default, so it does not leak into the
        // next class in the JVM. Safe to block on here only because [setUnified] never returns with
        // a write still in flight.
        runBlocking { settings.setUnifiedInbox(false) }
        seedDb.close()
    }

    private fun <T> subscribe(flow: StateFlow<T>): Job = subscribers.launch { flow.collect { } }

    /**
     * [GridlinkMailViewModel.setUnified] and then the write it launches landing, not just the call.
     *
     * 🔴 The view model launches the DataStore write on the main dispatcher, and DataStore runs a
     * write's transform back on the CALLER's dispatcher, so the store's single writer sits waiting
     * on a main-looper post. A test that ends with that post still queued loses it with the test's
     * looper (Robolectric resets it), the writer then waits for it forever, and the next class in
     * the JVM to write ANY setting hangs on its first edit with no error and no timeout (it was
     * `SettingsScreenTest`'s `@Before`, eleven minutes into the suite). So every toggle here waits
     * for the store to read the value back before the test goes on.
     */
    private fun setUnified(merged: Boolean) {
        vm.setUnified(merged)
        await("the unified setting to land in the store") {
            true.takeIf { runBlocking { settings.unifiedInbox.first() } == merged }
        }
    }

    /**
     * The view model's work runs on the main looper, which Robolectric holds paused until asked, so
     * every wait drains it between looks. It drains by ADVANCING the looper's clock, not just
     * running what is due: the search flow sits in a `delay` between its local and server legs, and
     * Robolectric's clock stands still on a plain idle, so a delay on the main dispatcher would
     * never come due and the search would never answer.
     */
    private fun <T : Any> await(what: String, timeoutMillis: Long = 20_000, look: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WAIT_STEP_MS))
            look()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(WAIT_STEP_MS)
        }
    }

    private fun awaitMail(what: String, look: (GridlinkMailContent) -> Boolean): GridlinkMailContent =
        await(what) { vm.mail.value.takeIf(look) }

    // ---- seeding ----

    /** An account whose inbox the cache knows, as a primed sign-in would have left it. */
    private fun addAccount(
        email: String = "avery@example.invalid",
        name: String = "Avery",
        inbox: String = "inbox",
    ): String {
        val id = store.add("http://127.0.0.1:9", email, "hunter2", name)
        store.saveInboxMetaFor(id, inbox, "Inbox", name, 0)
        seed { mailboxDao().upsertAll(listOf(MailboxEntity(id, inbox, "Inbox", "inbox", null, 0, 0, 0))) }
        return id
    }

    private fun seed(block: suspend GridlinkDatabase.() -> Unit) = runBlocking { seedDb.block() }

    private fun email(
        accountId: String,
        id: String,
        subject: String,
        hoursAgo: Long,
        seen: Boolean = true,
        threadId: String? = null,
        from: Pair<String, String> = "Sam Rivera" to "sam@example.invalid",
        mailbox: String = "inbox",
        flagged: Boolean = false,
    ): EmailEntity {
        val at = Instant.now().minus(hoursAgo, ChronoUnit.HOURS)
        return EmailEntity(
            id = id,
            accountId = accountId,
            mailboxId = mailbox,
            threadId = threadId,
            subject = subject,
            preview = "$subject preview",
            receivedAt = at.toString(),
            fromName = from.first,
            fromEmail = from.second,
            seen = seen,
            flagged = flagged,
            hasAttachment = false,
            sortKey = at.toEpochMilli(),
        )
    }

    private fun seedEmails(vararg emails: EmailEntity) = seed { emailDao().upsertAll(emails.toList()) }

    private fun bindAndSettle(id: String): GridlinkMailContent {
        subscribe(vm.mail)
        subscribe(vm.folders)
        vm.bind(id)
        return awaitMail("the list to settle") { !it.loading }
    }

    private fun rows(): List<GridlinkMessage> = vm.mail.value.humans

    // ---- the list ----

    @Test
    fun beforeBinding_theListIsLoadingAndEmpty() {
        assertTrue(vm.mail.value.loading)
        assertTrue(vm.mail.value.humans.isEmpty())
        assertNull(vm.mail.value.bundle)
        assertFalse(vm.unified.value)
    }

    @Test
    fun seededInbox_comesOutMappedNewestFirst_withTheTreeAndTheCounts() {
        val id = addAccount()
        seedEmails(
            email(id, "m1", "Quarterly review", hoursAgo = 1, seen = false),
            email(id, "m2", "Lunch tomorrow", hoursAgo = 30, from = "Thea Maddox" to "thea@example.invalid"),
        )
        val mail = bindAndSettle(id)
        assertEquals(listOf("Quarterly review", "Lunch tomorrow"), mail.humans.map { it.subject })
        val newest = mail.humans.first()
        assertEquals("m1", newest.id)
        assertEquals("Sam Rivera", newest.sender)
        assertEquals("example.invalid", newest.domain)
        assertTrue(newest.unread)
        assertFalse(mail.humans[1].unread)
        assertEquals("Thea Maddox", mail.humans[1].sender)
        assertNull("no automated mail, no bundle", mail.bundle)
        assertNull("no row labels outside the unified inbox", newest.accountLabel)
        assertNull(mail.open)
        assertNull(mail.search)
        assertTrue(mail.threads.isEmpty())

        val folders = await("the tree to settle") { vm.folders.value.takeIf { !it.loading } }
        assertEquals(listOf("Inbox"), folders.tree.map { it.name })
        assertEquals(GridlinkFolderRole.INBOX, folders.tree.single().role)
        assertNull(folders.open)

        subscribe(vm.menuCounts)
        shadowOf(Looper.getMainLooper()).idle()
        val counts = vm.menuCounts.value
        assertEquals(0, counts[GridlinkMenuItem.DRAFTS] ?: 0)
        assertEquals(0, counts[GridlinkMenuItem.SCHEDULED] ?: 0)
        assertEquals(0, counts[GridlinkMenuItem.SNOOZED] ?: 0)
        assertFalse(vm.unified.value)
    }

    @Test
    fun theQuickFilters_narrowTheWindow_andClearingThemBringsItBack() {
        val id = addAccount()
        seedEmails(
            email(id, "m1", "Quarterly review", hoursAgo = 1, seen = false),
            email(id, "m2", "Lunch tomorrow", hoursAgo = 30),
            email(id, "m3", "Starred one", hoursAgo = 50, flagged = true),
        )
        bindAndSettle(id)
        assertEquals(3, rows().size)

        vm.filter(MailFilter(unread = true))
        awaitMail("the unread filter") { it.humans.map { r -> r.id } == listOf("m1") }

        vm.filter(MailFilter(starred = true))
        awaitMail("the starred filter") { it.humans.map { r -> r.id } == listOf("m3") }

        vm.filter(MailFilter())
        awaitMail("the filters cleared") { it.humans.size == 3 }
        assertFalse("a filter never puts the list back into loading", vm.mail.value.loading)
    }

    @Test
    fun conversationView_foldsAThreadIntoOneRow_thatToggleThreadUnfolds() {
        val id = addAccount()
        seedEmails(
            email(id, "m1", "Re: Quarterly review", hoursAgo = 1, threadId = "t1", seen = false),
            email(id, "m2", "Quarterly review", hoursAgo = 5, threadId = "t1"),
            email(id, "m3", "Lunch tomorrow", hoursAgo = 30),
        )
        val mail = bindAndSettle(id)
        assertEquals(listOf("m1", "m3"), mail.humans.map { it.id })
        val folded = mail.humans.first()
        assertEquals(2, folded.threadCount)
        assertEquals("t1", folded.threadKey)
        assertTrue("a thread with an unread message reads unread", folded.unread)
        assertEquals(1, mail.humans[1].threadCount)

        vm.toggleThread("t1")
        val unfolded = awaitMail("the thread to unfold") { it.threads.containsKey("t1") }
        assertEquals(listOf("m1", "m2"), unfolded.threads.getValue("t1").map { it.id })
        assertEquals(listOf("m1", "m3"), unfolded.humans.map { it.id })

        vm.toggleThread("t1")
        awaitMail("the thread to fold again") { it.threads.isEmpty() }
    }

    @Test
    fun search_answersFromTheLocalIndex_andClearsOnAnEmptyQuery() {
        val id = addAccount()
        seedEmails(
            email(id, "m1", "Quarterly review", hoursAgo = 1),
            email(id, "m2", "Lunch tomorrow", hoursAgo = 30),
        )
        bindAndSettle(id)

        vm.search("quarterly")
        val found = awaitMail("the search to answer") { m ->
            m.search?.let { !it.searching && it.results.isNotEmpty() } == true
        }
        val search = found.search!!
        assertEquals("quarterly", search.query)
        assertEquals(listOf("m1"), search.results.map { it.id })
        assertFalse("the server leg could not run, so this is not a total", search.complete)
        assertEquals("the list behind the search is untouched", 2, found.humans.size)

        vm.search("")
        awaitMail("the search to clear") { it.search == null }
    }

    @Test
    fun theUnifiedInbox_mergesTwoAccounts_andLabelsEveryRow() {
        val avery = addAccount()
        val sam = addAccount(email = "sam@example.invalid", name = "Sam", inbox = "inbox-sam")
        seedEmails(
            email(avery, "a1", "For Avery", hoursAgo = 2),
            email(sam, "s1", "For Sam", hoursAgo = 1, mailbox = "inbox-sam"),
        )
        subscribe(vm.unified)
        bindAndSettle(avery)
        assertEquals(listOf("a1"), rows().map { it.id })
        assertFalse(vm.unified.value)

        setUnified(true)
        await("the merge to be reported") { true.takeIf { vm.unified.value } }
        val merged = awaitMail("both accounts' rows") { it.humans.size == 2 }
        assertEquals(listOf("For Sam", "For Avery"), merged.humans.map { it.subject })
        assertEquals(listOf("Sam", "Avery"), merged.humans.map { it.accountLabel })
        assertTrue("merged keys carry their account", merged.humans.all { it.id.contains('\u0000') })

        setUnified(false)
        awaitMail("the bound account alone again") { it.humans.map { r -> r.id } == listOf("a1") }
        await("the merge to be withdrawn") { true.takeIf { !vm.unified.value } }
    }

    @Test
    fun withOneAccount_theUnifiedSettingMergesNothing() {
        val id = addAccount()
        seedEmails(email(id, "m1", "Quarterly review", hoursAgo = 1))
        subscribe(vm.unified)
        bindAndSettle(id)
        setUnified(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("one inbox is nothing to merge", vm.unified.value)
        assertEquals(listOf("m1"), rows().map { it.id })
        assertNull(rows().single().accountLabel)
    }

    @Test
    fun anAccountWhoseInboxIsNotKnownYet_waits_andAFailedSyncStillLetsItSettle() {
        val id = store.add("http://127.0.0.1:9", "avery@example.invalid", "hunter2", "Avery")
        subscribe(vm.mail)
        vm.bind(id)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("no inbox id: nothing to read yet", vm.mail.value.loading)

        val synced = runBlocking { vm.sync() }
        assertFalse("the server at the dead port cannot be synced", synced)
        val settled = awaitMail("the list to settle after the sync") { !it.loading }
        assertTrue(settled.humans.isEmpty())
    }

    @Test
    fun openingARow_needsTheServer_andLandsAsAnErrorOnTheOpenSlot() {
        val id = addAccount()
        seedEmails(email(id, "m1", "Quarterly review", hoursAgo = 1))
        bindAndSettle(id)

        vm.open("m1")
        val opened = awaitMail("the open to fail") { it.open != null }.open!!
        assertEquals("m1", opened.id)
        assertNotNull("the failure is reported on the slot", opened.error)
        assertEquals("", opened.html)

        // A key the list never showed is still taken at its word: an unqualified key means the
        // bound account, so the slot clears and the failed fetch lands under the new id. The view
        // model does not check a key against the rows; the screen only hands it keys it drew.
        vm.open("nobody-knows-this-row")
        val unknown = awaitMail("the unknown key to fail too") { it.open?.id == "nobody-knows-this-row" }.open!!
        assertNotNull(unknown.error)
    }

    @Test
    fun openFolder_readsTheMailboxFromTheCache_andNullClosesIt() {
        val id = addAccount()
        seed { mailboxDao().upsertAll(listOf(MailboxEntity(id, "archive", "Archive", "archive", null, 1, 0, 0))) }
        seedEmails(
            email(id, "m1", "Quarterly review", hoursAgo = 1),
            email(id, "old", "Filed away", hoursAgo = 90, mailbox = "archive"),
        )
        bindAndSettle(id)
        await("the tree") { vm.folders.value.takeIf { f -> f.tree.size == 2 } }

        vm.openFolder("archive")
        val open = await("the folder to open") { vm.folders.value.open?.takeIf { it.messages.isNotEmpty() } }
        assertEquals("archive", open.id)
        assertEquals(listOf("Filed away"), open.messages.map { it.subject })

        vm.openFolder(null)
        await("the folder to close") { true.takeIf { vm.folders.value.open == null } }
    }

    private companion object {
        const val WAIT_STEP_MS = 25L
    }
}
