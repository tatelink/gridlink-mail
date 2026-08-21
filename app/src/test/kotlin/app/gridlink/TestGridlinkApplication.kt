package app.gridlink

import android.util.Log
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/**
 * The real [GridlinkApplication], with WorkManager stood up first. For the screen tests whose
 * screen is bound to a view model that reads `application.container` (Connect, Filters,
 * Settings), which the default `robolectric.properties` Application deliberately does not supply.
 *
 * Opt in per class with `@Config(application = TestGridlinkApplication::class)`. [AppContainer]'s
 * init schedules the fallback mail poll through WorkManager; under Robolectric the library's
 * startup provider does not run, so without this the container throws before any screen draws.
 * The test WorkManager runs synchronously and never executes real workers.
 */
class TestGridlinkApplication : GridlinkApplication() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        super.onCreate()
    }
}
