package app.gridlink.ui.connect

import android.net.ConnectivityManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.gridlink.GridlinkApplication
import app.gridlink.R
import app.gridlink.TestGridlinkApplication
import app.gridlink.core.data.account.ConnectionSecurity
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.ui.connect.ConnectViewModel.ImportSignIn
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

/**
 * [ConnectViewModel] driven directly, against the real container and account store and no mail
 * server: the paths a sign-in takes before it ever reaches one. The auto and IMAP flows refuse an
 * offline device at once with the offline message and a step that says why; a JMAP sign-in at a
 * loopback port nothing listens on is refused with the refused message and writes no account; the
 * password drafts hold and clear; the import sign-in listing walks its states (list, select, close,
 * leave, resume, dismiss, restore) over a pending account the store holds without a secret, and a
 * password submitted for it against that same dead port fails back into the listing with an error
 * and the account still pending. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGridlinkApplication::class)
class ConnectViewModelTest {

    private val app: GridlinkApplication get() = ApplicationProvider.getApplicationContext()
    private val store get() = app.container.accountStore

    private lateinit var vm: ConnectViewModel

    @Before
    fun startWithNoAccounts() {
        store.clear()
        vm = ConnectViewModel(app)
    }

    private fun string(id: Int) = app.getString(id)

    /** Robolectric's stand-in networks already carry no capabilities; removing them says so out loud. */
    private fun goOffline() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.forEach { shadowOf(cm).removeNetwork(it) }
    }

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

    private fun awaitError(): ConnectState.Error = await("an error state") { vm.state.value as? ConnectState.Error }

    // ---- sign-in paths that end before a server ----

    @Test
    fun startsIdle_withNothingPending() {
        assertEquals(ConnectState.Idle, vm.state.value)
        assertEquals(ImportSignIn.None, vm.importSignIn.value)
        assertNull(vm.imapSuggestion.value)
        assertTrue(vm.pendingStoredAccounts.isEmpty())
    }

    @Test
    fun connectAutoOffline_failsAtOnce_withTheOfflineMessageAndTheStepThatSaysWhy() {
        goOffline()
        vm.connectAuto("avery@example.invalid", "hunter2", "Avery")
        val error = vm.state.value as ConnectState.Error
        assertEquals(string(R.string.connect_offline), error.message)
        assertEquals(1, error.details.size)
        assertTrue(error.details.single().toString(), error.details.single().outcome.contains("no connection"))
        assertTrue("nothing is written for a sign-in that never ran", store.accounts().isEmpty())
    }

    @Test
    fun connectImapOffline_failsAtOnce_andWritesNoAccount() {
        goOffline()
        vm.connectImap(
            username = "avery@example.invalid", password = "hunter2", accountName = "Avery",
            imapHost = "imap.example.invalid", imapPort = 993, imapSecurity = ConnectionSecurity.TLS,
            smtpHost = "smtp.example.invalid", smtpPort = 465, smtpSecurity = ConnectionSecurity.TLS,
        )
        val error = vm.state.value as ConnectState.Error
        assertEquals(string(R.string.connect_offline), error.message)
        assertTrue(store.accounts().isEmpty())
    }

    @Test
    fun jmapSignInAtADeadPort_isRefused_andWritesNoAccount() {
        // A loopback port nothing listens on: refused at once, where an unresolvable name would sit
        // in the resolver first.
        vm.connect("http://127.0.0.1:9", "avery@example.invalid", "hunter2", "Avery")
        assertEquals(ConnectState.Connecting, vm.state.value)
        val error = awaitError()
        assertEquals(string(R.string.connect_refused), error.message)
        assertTrue("the log names the step that failed", error.details.isNotEmpty())
        assertTrue("nothing is written until the credentials are proven", store.accounts().isEmpty())
    }

    @Test
    fun aSecondConnectWhileOneIsRunning_isIgnored() {
        vm.connect("http://127.0.0.1:9", "avery@example.invalid", "hunter2", "Avery")
        assertEquals(ConnectState.Connecting, vm.state.value)
        vm.connect("http://127.0.0.1:9", "someone@example.invalid", "other", "Other")
        assertEquals(ConnectState.Connecting, vm.state.value)
        awaitError()
        assertTrue(store.accounts().isEmpty())
    }

    // ---- password drafts ----

    @Test
    fun passwordDrafts_holdTheFormAndImportCopies_andClearTogether() {
        val drafts = vm.passwordDrafts
        assertEquals("", drafts.form.value)
        assertNull(drafts.import.value)

        drafts.setForm("hunter2")
        drafts.setImport("acct-1", "letmein")
        assertEquals("hunter2", drafts.form.value)
        assertEquals("acct-1" to "letmein", drafts.import.value)

        drafts.clear()
        assertEquals("", drafts.form.value)
        assertNull(drafts.import.value)
    }

    // ---- the import sign-in listing ----

    private fun pendingAccount(id: String = "imported-1", email: String = "avery@example.invalid") = StoredAccount(
        id = id,
        server = "http://127.0.0.1:9",
        username = email,
        accountName = "Avery",
    )

    /** An imported account the store holds without a secret, which is what makes it pending. */
    private fun seedPending(account: StoredAccount = pendingAccount()): StoredAccount {
        store.readdImportedAccount(account)
        return account
    }

    @Test
    fun beginWithNothingPending_andNoSignedInAccount_staysNone() {
        vm.beginImportSignIn()
        assertEquals(ImportSignIn.None, vm.importSignIn.value)
    }

    @Test
    fun beginWithNothingPending_butASignedInAccount_isDone() {
        store.add("http://127.0.0.1:9", "avery@example.invalid", "hunter2", "Avery")
        vm.beginImportSignIn()
        assertEquals(ImportSignIn.Done, vm.importSignIn.value)
    }

    @Test
    fun aPendingAccount_isListed_selectedAndClosedAgain() {
        seedPending()
        assertEquals(listOf("imported-1"), vm.pendingStoredAccounts.map { it.id })

        vm.beginImportSignIn()
        val listing = vm.importSignIn.value as ImportSignIn.Listing
        assertEquals(1, listing.pending.size)
        assertEquals("avery@example.invalid", listing.pending.single().email)
        assertEquals("Avery", listing.pending.single().label)
        assertNull(listing.selected)

        vm.selectImportAccount("imported-1")
        val selected = (vm.importSignIn.value as ImportSignIn.Listing).selected
        assertNotNull(selected)
        assertEquals("imported-1", selected!!.account.id)
        assertFalse(selected.verifying)
        assertNull(selected.error)

        vm.selectImportAccount("no-such-account")
        assertEquals(selected, (vm.importSignIn.value as ImportSignIn.Listing).selected)

        vm.closeImportAccount()
        assertNull((vm.importSignIn.value as ImportSignIn.Listing).selected)
    }

    @Test
    fun leavingTheListing_isOnlyHonouredWithNothingSelected_andResumeBringsItBack() {
        seedPending()
        vm.beginImportSignIn()
        vm.selectImportAccount("imported-1")
        vm.leaveImportListing()
        assertTrue("a selected row pins the listing", vm.importSignIn.value is ImportSignIn.Listing)

        vm.closeImportAccount()
        vm.leaveImportListing()
        assertEquals(ImportSignIn.None, vm.importSignIn.value)

        vm.resumeImportSignIn()
        val listing = vm.importSignIn.value as ImportSignIn.Listing
        assertEquals(listOf("imported-1"), listing.pending.map { it.id })
        assertNull(listing.selected)
    }

    @Test
    fun dismissRemovesTheAccountFromTheStore_andRestorePutsItBackPending() {
        val account = seedPending()
        vm.beginImportSignIn()
        vm.selectImportAccount("imported-1")

        vm.dismissImportAccount("imported-1")
        assertTrue(store.accounts().isEmpty())
        // Nothing pending and nothing signed in: the listing has nothing to show.
        assertEquals(ImportSignIn.None, vm.importSignIn.value)

        vm.restoreImportAccount(account)
        assertEquals(listOf("imported-1"), vm.pendingStoredAccounts.map { it.id })
        val listing = vm.importSignIn.value as ImportSignIn.Listing
        assertEquals(listOf("imported-1"), listing.pending.map { it.id })
        assertNull(listing.selected)
    }

    @Test
    fun dismissingAnotherRow_keepsTheSelectionOnThisOne() {
        seedPending()
        seedPending(pendingAccount(id = "imported-2", email = "sam@example.invalid"))
        vm.beginImportSignIn()
        vm.selectImportAccount("imported-1")

        vm.dismissImportAccount("imported-2")
        val listing = vm.importSignIn.value as ImportSignIn.Listing
        assertEquals(listOf("imported-1"), listing.pending.map { it.id })
        assertEquals("imported-1", listing.selected?.account?.id)
    }

    @Test
    fun submittingAPasswordAgainstADeadPort_failsBackIntoTheListing_withTheAccountStillPending() {
        seedPending()
        vm.beginImportSignIn()
        vm.selectImportAccount("imported-1")

        vm.submitImportPassword("   ")
        val afterBlank = (vm.importSignIn.value as ImportSignIn.Listing).selected!!
        assertFalse("a blank password is not sent", afterBlank.verifying)

        vm.submitImportPassword("hunter2")
        assertTrue((vm.importSignIn.value as ImportSignIn.Listing).selected!!.verifying)
        val failed = await("the sign-in to fail") {
            (vm.importSignIn.value as? ImportSignIn.Listing)?.selected?.takeIf { !it.verifying }
        }
        assertNotNull("a refused connection is reported on the row", failed.error)
        assertFalse("a refused port is not a rejected credential", failed.offerAppPasswordFallback)
        assertEquals(listOf("imported-1"), store.pendingImportAccounts().map { it.id })
        assertNull("no secret is written for a sign-in that failed", store.credentials("imported-1"))
    }

    private companion object {
        const val WAIT_STEP_MS = 25L
    }
}
