package app.gridlink.core.data

import android.content.Context
import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.dav.DavRepository
import app.gridlink.core.data.db.GridlinkDatabase
import app.gridlink.core.data.mail.ImapMailService
import app.gridlink.core.data.mail.MailboxUidValidityStore
import app.gridlink.core.data.mail.MailRepository
import app.gridlink.core.data.mail.OAuthTokenRefresher
import app.gridlink.core.data.mail.SyncStateStore
import app.gridlink.core.data.pgp.PgpEngine
import app.gridlink.core.data.settings.SettingsRepository
import app.gridlink.core.data.storage.StorageRepository
import app.gridlink.core.dav.DavClient
import app.gridlink.core.imap.ImapClient
import app.gridlink.core.imap.SmtpClient
import app.gridlink.core.jmap.JmapClient
import app.gridlink.core.jmap.OAuthClient

/** Builds data-layer components, keeping Room (the database) internal to this module. */
object DataFactory {
    /** Data-layer repositories that share a single database instance. */
    class DataLayer(
        val mailRepository: MailRepository,
        val storageRepository: StorageRepository,
        val davRepository: DavRepository,
    )

    fun create(
        context: Context,
        client: JmapClient,
        accountStore: AccountStore,
        pgpEngine: PgpEngine? = null,
        settings: SettingsRepository? = null,
    ): DataLayer {
        val appContext = context.applicationContext
        val database = GridlinkDatabase.build(appContext)
        // The IMAP service verifies each folder's UIDVALIDITY through this store, and it is the
        // store — not the service — that says what a renumbering invalidates (Codeberg #99).
        val uidValidity = MailboxUidValidityStore(
            database.mailboxUidValidityDao(), database.emailBodyDao(), database.purgeSnapshotDao(),
        )
        val imapService = ImapMailService(
            ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), accountStore), uidValidity,
        )
        return DataLayer(
            mailRepository = MailRepository(
                client, database.emailDao(), database.emailFtsDao(), database.emailBodyDao(),
                database.mailboxDao(), imapService,
                database.scheduledSendDao(), database.snoozedDao(), database.recentContactDao(),
                accountStore,
                outboxDao = database.outboxDao(),
                purgeSnapshotDao = database.purgeSnapshotDao(),
                outboxFilesDir = java.io.File(appContext.filesDir, "outbox"),
                pgpEngine = pgpEngine,
                settings = settings,
                syncStateStore = SyncStateStore(appContext),
            ),
            storageRepository = StorageRepository(
                appContext, database.emailDao(), database.emailFtsDao(), database.emailBodyDao(),
                database.mailboxDao(), database.snoozedDao(), database.purgeSnapshotDao(),
                database.mailboxUidValidityDao(),
            ),
            davRepository = DavRepository(
                // Its own transport: a DAV first sync can be one large slow response, and sharing
                // the JMAP client's connection pool would let a calendar fetch hold up mail.
                client = DavClient(),
                accountStore = accountStore,
                collectionDao = database.davCollectionDao(),
                eventDao = database.calendarEventDao(),
                contactDao = database.addressBookContactDao(),
                // The shared JMAP client, for contact writes on servers that speak RFC 9610. A
                // write is one small request, so mail's connection pool is the right one for it.
                jmap = client,
            ),
        )
    }
}
