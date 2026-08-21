package app.gridlink.ui.home

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.gridlink.GridlinkApplication
import app.gridlink.TestGridlinkApplication
import app.gridlink.core.data.account.SyncSelection
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.ui.gridlink.GridlinkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * [GridlinkDavViewModel] driven directly against the real container and no DAV server. Its
 * content flows are read from the cache, so binding an account the cache has nothing for settles
 * both the calendar and the contacts out of their loading state onto empty lists; [sync] with no
 * account bound does the same. Its writers answer the refusals they can decide locally: nothing
 * bound, contact sync turned off for the account, and a server that cannot be reached (a loopback
 * port nothing listens on), which is an error string and never a throw. Anything past that needs a
 * live server. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGridlinkApplication::class)
class GridlinkDavViewModelTest {

    private val app: GridlinkApplication get() = ApplicationProvider.getApplicationContext()
    private val store get() = app.container.accountStore

    private lateinit var vm: GridlinkDavViewModel

    /** Where the content flows' subscribers live: `WhileSubscribed` needs one before it reads. */
    private val subscribers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun startWithNoAccounts() {
        store.clear()
        vm = GridlinkDavViewModel(app)
    }

    @After
    fun dropSubscribers() {
        subscribers.cancel()
    }

    private fun <T> subscribe(flow: StateFlow<T>): Job = subscribers.launch { flow.collect { } }

    /**
     * The view model's work runs on the main looper, which Robolectric holds paused until asked, so
     * every wait drains it between looks.
     */
    private fun <T : Any> await(what: String, timeoutMillis: Long = 20_000, look: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            look()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(WAIT_STEP_MS)
        }
    }

    private fun addAccount(): String =
        store.add("http://127.0.0.1:9", "avery@example.invalid", "hunter2", "Avery")

    private fun event() = GridlinkEvent(
        id = "new",
        title = "Quarterly review",
        date = LocalDate.of(2026, 9, 1),
        start = LocalTime.of(9, 0),
        end = LocalTime.of(10, 0),
    )

    private fun contact() = ContactEdit(given = "Avery", family = "Quinn", emails = listOf("avery@example.invalid"))

    // ---- content ----

    @Test
    fun beforeAnyAccount_bothSectionsAreLoading_andEmpty() {
        assertTrue(vm.calendar.value.loading)
        assertTrue(vm.calendar.value.events.isEmpty())
        assertTrue(vm.contacts.value.loading)
        assertTrue(vm.contacts.value.contacts.isEmpty())
    }

    @Test
    fun bindingAnAccountTheCacheHasNothingFor_settlesBothSectionsEmpty() {
        val id = addAccount()
        subscribe(vm.calendar)
        subscribe(vm.contacts)
        vm.bind(id)
        val calendar = await("the calendar to settle") { vm.calendar.value.takeIf { !it.loading } }
        assertTrue(calendar.events.isEmpty())
        assertEquals(LocalDate.now(), calendar.today)
        val contacts = await("the contacts to settle") { vm.contacts.value.takeIf { !it.loading } }
        assertTrue(contacts.contacts.isEmpty())
    }

    @Test
    fun syncWithNoAccountBound_settlesBothSections_withoutAServer() {
        subscribe(vm.calendar)
        subscribe(vm.contacts)
        runBlocking { vm.sync() }
        await("the calendar to settle") { vm.calendar.value.takeIf { !it.loading } }
        await("the contacts to settle") { vm.contacts.value.takeIf { !it.loading } }
        assertTrue(vm.calendar.value.events.isEmpty())
        assertTrue(vm.contacts.value.contacts.isEmpty())
    }

    // ---- writers ----

    @Test
    fun writersWithNothingBound_refuseWithNoAccount() {
        assertEquals("No account is signed in.", runBlocking { vm.calendarWriter.save(event()) })
        assertEquals("No account is signed in.", runBlocking { vm.contactWriter.create(contact()) })
        assertEquals("No account is signed in.", runBlocking { vm.contactWriter.update("c1", contact()) })
        assertTrue(vm.calendarWriter.echoesIntoContent)
        assertTrue(vm.contactWriter.echoesIntoContent)
    }

    @Test
    fun contactSyncOffForTheAccount_refusesToSaveAContact_beforeAnyServer() {
        val id = addAccount()
        store.setSyncSelection(id, SyncSelection(mail = true, calendar = true, contacts = false))
        vm.bind(id)
        val error = runBlocking { vm.contactWriter.create(contact()) }
        assertEquals("Turn on contact sync for this account to save contacts", error)
    }

    @Test
    fun aServerThatCannotBeReached_isAnErrorString_neverAThrow() {
        val id = addAccount()
        vm.bind(id)
        val calendarError = runBlocking { vm.calendarWriter.save(event()) }
        assertNotNull("an unreachable server is reported, not swallowed", calendarError)
        assertNotEquals("No account is signed in.", calendarError)
        val contactError = runBlocking { vm.contactWriter.create(contact()) }
        assertNotNull(contactError)
        assertFalse("the refusal is about the server, not the account", contactError!!.startsWith("Turn on"))
    }

    private companion object {
        const val WAIT_STEP_MS = 25L
    }
}
