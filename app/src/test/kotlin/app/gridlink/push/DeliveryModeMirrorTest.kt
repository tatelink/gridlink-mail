package app.gridlink.push

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.gridlink.GridlinkApplication
import app.gridlink.TestGridlinkApplication
import app.gridlink.core.data.settings.DeliveryMode
import app.gridlink.core.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The delivery mode the push controller reads without suspending. It used to `runBlocking` on the
 * DataStore flow from inside a composition and from view-model actions on the main thread; now the
 * repository keeps a mirror warm and the controller reads that. Three things have to hold: a write
 * is visible to the very next read (the settings screen sets the mode and re-arms in one breath),
 * a repository whose mirror has not warmed yet still answers from disk rather than the default, and
 * the transport decision follows. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGridlinkApplication::class)
class DeliveryModeMirrorTest {

    private val app: GridlinkApplication get() = ApplicationProvider.getApplicationContext()
    private val settings get() = app.container.settingsRepository

    /** The DataStore file outlives a test; leave the next one the default. */
    @After
    fun backToInstant() {
        runBlocking { settings.setDeliveryMode(DeliveryMode.INSTANT) }
    }

    @Test
    fun aWrite_isReadableAtOnce_withoutSuspending() {
        runBlocking { settings.setDeliveryMode(DeliveryMode.INSTANT) }
        assertEquals(DeliveryMode.INSTANT, settings.deliveryModeNow())
        runBlocking { settings.setDeliveryMode(DeliveryMode.BATTERY_SAVER) }
        assertEquals(DeliveryMode.BATTERY_SAVER, settings.deliveryModeNow())
        runBlocking { settings.setDeliveryMode(DeliveryMode.INSTANT) }
        assertEquals(DeliveryMode.INSTANT, settings.deliveryModeNow())
    }

    @Test
    fun aRepositoryWhoseMirrorIsNotWarmYet_answersFromDisk_notTheDefault() {
        runBlocking { settings.setDeliveryMode(DeliveryMode.BATTERY_SAVER) }
        // A scope on the main looper, which Robolectric holds paused: the mirror's collector is
        // posted but has not run, so this read is the cold path.
        val cold = SettingsRepository(app, scope = CoroutineScope(Job() + Dispatchers.Main))
        assertEquals(DeliveryMode.BATTERY_SAVER, cold.deliveryModeNow())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DeliveryMode.BATTERY_SAVER, cold.deliveryModeNow())
    }

    @Test
    fun theTransportDecision_followsTheMode() {
        val store = app.container.accountStore
        store.clear()
        val id = store.add("http://127.0.0.1:9", "avery@example.invalid", "hunter2", "Avery")
        val credentials = store.credentials(id)!!
        runBlocking { settings.setDeliveryMode(DeliveryMode.BATTERY_SAVER) }
        assertEquals(Transport.PERIODIC, PushController.transportFor(app, credentials))
        runBlocking { settings.setDeliveryMode(DeliveryMode.INSTANT) }
        assertEquals(Transport.EVENT_SOURCE, PushController.transportFor(app, credentials))
    }
}
