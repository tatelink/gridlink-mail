package app.sterna

import android.app.Application
import android.content.Context
import app.sterna.core.data.DataFactory
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OutboxScheduler
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.jmap.JmapClient
import app.sterna.pgp.OpenKeychainPgpEngine
import app.sterna.security.AppLock
import app.sterna.send.Outbox
import app.sterna.send.SendOutbox
import app.sterna.ui.connect.OutlookSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Simple manual DI container — holds app-wide singletons. */
class AppContainer(context: Context) {
    val accountStore: AccountStore = AccountStore(context.applicationContext)
    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)
    private val jmapClient: JmapClient = JmapClient()

    /** OpenPGP via the OpenKeychain provider (binds lazily; harmless when not installed). */
    val pgpEngine: OpenKeychainPgpEngine = OpenKeychainPgpEngine(context.applicationContext)
    private val dataLayer =
        DataFactory.create(context.applicationContext, jmapClient, accountStore, pgpEngine)
    val mailRepository: MailRepository = dataLayer.mailRepository
    val storageRepository: StorageRepository = dataLayer.storageRepository
    val appLock: AppLock = AppLock(accountStore)

    /** App-lifetime scope for work that must outlive a screen (e.g. Undo-send hold-back). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sendOutbox: SendOutbox = SendOutbox(appScope)

    /** Drives the Outlook OAuth device flow app-scoped, so it survives the browser round-trip. */
    val outlookSignIn: OutlookSignIn = OutlookSignIn(mailRepository, appScope, context.applicationContext)

    init {
        val appContext = context.applicationContext
        // Let the data layer arm the delivery worker from any send call site (compose, RSVP, …).
        mailRepository.outboxScheduler = OutboxScheduler { id, delay -> Outbox.enqueue(appContext, id, delay) }
        // Re-arm any send left mid-flight (WorkManager persists jobs, but re-checking is a safety net).
        appScope.launch {
            mailRepository.unfinishedOutbox().forEach { item ->
                val delay = (item.notBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0)
                Outbox.enqueue(appContext, item.id, delay)
            }
        }
    }
}

class SternaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Convenience accessor for ViewModels (which receive the Application). */
val Application.container: AppContainer
    get() = (this as SternaApplication).container
