package app.sterna.core.data

import android.content.Context
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.db.SternaDatabase
import app.sterna.core.data.mail.ImapMailService
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OAuthTokenRefresher
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient

/** Builds data-layer components, keeping Room (the database) internal to this module. */
object DataFactory {
    /** Data-layer repositories that share a single database instance. */
    class DataLayer(
        val mailRepository: MailRepository,
        val storageRepository: StorageRepository,
    )

    fun create(context: Context, client: JmapClient, accountStore: AccountStore): DataLayer {
        val appContext = context.applicationContext
        val database = SternaDatabase.build(appContext)
        val imapService = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), accountStore))
        return DataLayer(
            mailRepository = MailRepository(
                client, database.emailDao(), database.emailBodyDao(), database.mailboxDao(), imapService,
                database.scheduledSendDao(), database.snoozedDao(), database.recentContactDao(),
                accountStore,
            ),
            storageRepository = StorageRepository(
                appContext, database.emailDao(), database.emailBodyDao(), database.mailboxDao(),
            ),
        )
    }
}
