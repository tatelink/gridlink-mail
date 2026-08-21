package app.gridlink

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Canary for the view-model-bound screen tests: the real [AppContainer] (Room, the account store,
 * the data layer, WorkManager scheduling in its init) can be built on the JVM under Robolectric
 * through [TestGridlinkApplication], and a fresh install has no accounts. If this goes red, every
 * screen test that opts into that Application is red for the same reason, so look here first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGridlinkApplication::class)
class AppContainerStandsUpTest {
    @Test
    fun containerBuilds_andAFreshInstallHasNoAccounts() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<GridlinkApplication>()
        assertNotNull(app.container.mailRepository)
        assertEquals(0, app.container.accountStore.accounts().size)
    }
}
