package app.jmail

import android.app.Application
import android.content.Context
import app.jmail.core.data.DataFactory
import app.jmail.core.data.account.AccountStore
import app.jmail.core.data.mail.MailRepository
import app.jmail.core.data.settings.SettingsRepository
import app.jmail.core.data.storage.StorageRepository
import app.jmail.core.jmap.JmapClient
import app.jmail.security.AppLock

/** Simple manual DI container — holds app-wide singletons. */
class AppContainer(context: Context) {
    val accountStore: AccountStore = AccountStore(context.applicationContext)
    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)
    private val jmapClient: JmapClient = JmapClient()
    private val dataLayer = DataFactory.create(context.applicationContext, jmapClient)
    val mailRepository: MailRepository = dataLayer.mailRepository
    val storageRepository: StorageRepository = dataLayer.storageRepository
    val appLock: AppLock = AppLock(accountStore)
}

class JmailApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Convenience accessor for ViewModels (which receive the Application). */
val Application.container: AppContainer
    get() = (this as JmailApplication).container
