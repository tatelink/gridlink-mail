package app.jmail.core.data

import android.content.Context
import app.jmail.core.data.account.AccountStore
import app.jmail.core.data.db.JmailDatabase
import app.jmail.core.data.mail.ImapMailService
import app.jmail.core.data.mail.MailRepository
import app.jmail.core.data.storage.StorageRepository
import app.jmail.core.imap.ImapClient
import app.jmail.core.imap.SmtpClient
import app.jmail.core.jmap.JmapClient

/** Builds data-layer components, keeping Room (the database) internal to this module. */
object DataFactory {
    /** Data-layer repositories that share a single database instance. */
    class DataLayer(
        val mailRepository: MailRepository,
        val storageRepository: StorageRepository,
    )

    fun create(context: Context, client: JmapClient, accountStore: AccountStore): DataLayer {
        val appContext = context.applicationContext
        val database = JmailDatabase.build(appContext)
        val imapService = ImapMailService(ImapClient(), SmtpClient())
        return DataLayer(
            mailRepository = MailRepository(
                client, database.emailDao(), database.mailboxDao(), imapService,
                database.scheduledSendDao(), database.snoozedDao(), database.recentContactDao(),
                accountStore,
            ),
            storageRepository = StorageRepository(appContext, database.emailDao(), database.mailboxDao()),
        )
    }
}
