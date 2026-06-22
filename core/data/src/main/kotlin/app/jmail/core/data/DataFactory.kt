package app.jmail.core.data

import android.content.Context
import app.jmail.core.data.db.JmailDatabase
import app.jmail.core.data.mail.MailRepository
import app.jmail.core.data.storage.StorageRepository
import app.jmail.core.jmap.JmapClient

/** Builds data-layer components, keeping Room (the database) internal to this module. */
object DataFactory {
    /** Data-layer repositories that share a single database instance. */
    class DataLayer(
        val mailRepository: MailRepository,
        val storageRepository: StorageRepository,
    )

    fun create(context: Context, client: JmapClient): DataLayer {
        val appContext = context.applicationContext
        val database = JmailDatabase.build(appContext)
        return DataLayer(
            mailRepository = MailRepository(client, database.emailDao(), database.mailboxDao()),
            storageRepository = StorageRepository(appContext, database.emailDao(), database.mailboxDao()),
        )
    }
}
