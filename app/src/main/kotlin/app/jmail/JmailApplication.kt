package app.jmail

import android.app.Application
import android.content.Context
import app.jmail.core.data.DataFactory
import app.jmail.core.data.account.AccountStore
import app.jmail.core.data.mail.MailRepository
import app.jmail.core.jmap.JmapClient
import app.jmail.security.AppLock

/** Simple manual DI container — holds app-wide singletons. */
class AppContainer(context: Context) {
    val accountStore: AccountStore = AccountStore(context.applicationContext)
    private val jmapClient: JmapClient = JmapClient()
    val mailRepository: MailRepository = DataFactory.mailRepository(context.applicationContext, jmapClient)
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
