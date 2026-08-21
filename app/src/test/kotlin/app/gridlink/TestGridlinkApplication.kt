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
 * The test WorkManager runs synchronously and never executes real workers. It also installs
 * [FakeAndroidKeyStore], so `AccountStore.add` works and account-bound screens can be tested.
 *
 * 🔴 It also forgets the Application that `ViewModelProvider.AndroidViewModelFactory` caches in a
 * process-wide static: a screen whose view model comes from a bare `viewModel()` (Vacation,
 * Filters, Storage, Connect, the mail and DAV hosts) takes the `(Application)` constructor through
 * that cached factory, so after the first test every such view model would be built on the FIRST
 * test's Application and read that test's (long gone) account store. On a phone there is one
 * Application for the life of the process, so the cache is harmless there; here a new one is made
 * per test and the cache has to follow.
 */
class TestGridlinkApplication : GridlinkApplication() {
    override fun onCreate() {
        forgetCachedViewModelFactory()
        // Before the container exists: the account store encrypts passwords through the Android
        // KeyStore, which the JVM does not have, and without it no test can add an account.
        FakeAndroidKeyStore.install()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        super.onCreate()
    }

    private fun forgetCachedViewModelFactory() {
        // lifecycle-viewmodel 2.8: `AndroidViewModelFactory.Companion._instance`, a private static.
        runCatching {
            Class.forName("androidx.lifecycle.ViewModelProvider\$AndroidViewModelFactory")
                .getDeclaredField("_instance")
                .apply { isAccessible = true }
                .set(null, null)
        }.onFailure { Log.w("TestGridlinkApplication", "Could not reset AndroidViewModelFactory", it) }
    }
}
