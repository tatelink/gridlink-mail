package app.jmail.core.data

import android.content.Context
import app.jmail.core.data.db.JmailDatabase
import app.jmail.core.data.mail.MailRepository
import app.jmail.core.jmap.JmapClient

/** Builds data-layer components, keeping Room (the database) internal to this module. */
object DataFactory {
    fun mailRepository(context: Context, client: JmapClient): MailRepository {
        val database = JmailDatabase.build(context.applicationContext)
        return MailRepository(client, database.emailDao())
    }
}
