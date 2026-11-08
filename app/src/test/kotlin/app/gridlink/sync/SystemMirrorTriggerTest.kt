package app.gridlink.sync

import app.gridlink.core.data.account.StoredAccount
import app.gridlink.core.data.account.SyncSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the system-account mirror reconciles. A new or removed account must reach Android at once,
 * without an app restart, while the stream of unread-count updates must not reconcile anything.
 */
class SystemMirrorTriggerTest {

    private fun account(id: String, unread: Int = 0, selection: SyncSelection = SyncSelection()) =
        StoredAccount(
            id = id,
            server = "https://mail.example.com",
            username = "$id@example.com",
            unread = unread,
            syncSelection = selection,
        )

    @Test
    fun `adding and removing an account each reconcile, unread changes do not`() =
        runTest(UnconfinedTestDispatcher()) {
            val accounts = MutableStateFlow(listOf(account("a")))
            val mirror = MutableStateFlow(true)
            var count = 0
            val job = launch { SystemMirror.reconcileTriggers(accounts, mirror).collect { count++ } }
            assertEquals("start-up reconciles once", 1, count)

            accounts.value = listOf(account("a", unread = 5))
            assertEquals("an unread count is not an account change", 1, count)

            accounts.value = listOf(account("a", unread = 5), account("b"))
            assertEquals("a new account reconciles straight away", 2, count)

            accounts.value = listOf(account("a", unread = 6))
            assertEquals("a removed account reconciles straight away", 3, count)
            job.cancel()
        }

    @Test
    fun `the mirror switch and an account's calendar or contacts choice reconcile`() =
        runTest(UnconfinedTestDispatcher()) {
            val accounts = MutableStateFlow(listOf(account("a")))
            val mirror = MutableStateFlow(false)
            var count = 0
            val job = launch { SystemMirror.reconcileTriggers(accounts, mirror).collect { count++ } }

            mirror.value = true
            assertEquals(2, count)

            accounts.value = listOf(account("a", selection = SyncSelection(calendar = false)))
            assertEquals(3, count)
            job.cancel()
        }
}
