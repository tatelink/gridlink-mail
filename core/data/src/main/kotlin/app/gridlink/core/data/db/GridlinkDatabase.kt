package app.gridlink.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EmailEntity::class, EmailFtsEntity::class, EmailBodyEntity::class, MailboxEntity::class,
        ScheduledSendEntity::class, SnoozedEntity::class, RecentContactEntity::class, OutboxEntity::class,
        PurgeSnapshotEntity::class, MailboxUidValidityEntity::class,
        DavCollectionEntity::class, CalendarEventEntity::class, AddressBookContactEntity::class,
    ],
    version = 24,
    exportSchema = false,
)
abstract class GridlinkDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao
    abstract fun emailFtsDao(): EmailFtsDao
    abstract fun emailBodyDao(): EmailBodyDao
    abstract fun mailboxDao(): MailboxDao
    abstract fun scheduledSendDao(): ScheduledSendDao
    abstract fun snoozedDao(): SnoozedDao
    abstract fun recentContactDao(): RecentContactDao
    abstract fun outboxDao(): OutboxDao
    abstract fun purgeSnapshotDao(): PurgeSnapshotDao
    abstract fun mailboxUidValidityDao(): MailboxUidValidityDao
    abstract fun davCollectionDao(): DavCollectionDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun addressBookContactDao(): AddressBookContactDao

    companion object {
        fun build(context: Context): GridlinkDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                GridlinkDatabase::class.java,
                "gridlink.db",
            )
                // The outbox holds unsent mail (user data): migrate it additively so a schema
                // bump never destroys a queued send. 13→14 adds draftEmailId to outbox/
                // scheduled_sends (#63); 14→15 rebuilds the FTS index (#71); 15→16 rebuilds
                // emails/bodies/snoozed with composite (accountId, id) keys (issue #31),
                // copying every row over — snoozed is user data too, so the migration must
                // never fall back destructively; 16→17 adds the persisted To: recipients (#63);
                // 17→18 adds `purge_snapshot`, the frozen destroy list of an Empty trash (#99);
                // 18→19 records which UIDVALIDITY a snapshot and a folder's cache belong to (#99);
                // 19→20 adds the CalDAV/CardDAV mirror; 20→21 adds each mailbox's `myRights`,
                // nullable because "not asked yet" is not "not allowed"; 21→22 rebuilds
                // `email_fts` with `preview` tokenized, so a local search finally reaches the
                // opening of the body and not just subject and sender; 22→23 adds the per-folder
                // IMAP sync point (HIGHESTMODSEQ/UIDNEXT/EXISTS) that lets a refresh skip a
                // folder nothing has happened in (RFC 7162); 23→24 adds the custom keywords
                // (tags) a message carries, packed so the tag filter can narrow in SQL.
                .addMigrations(
                    MIGRATION_9_10, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                    MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                    MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                    MIGRATION_22_23, MIGRATION_23_24,
                )
                // The rest of the DB is a disposable mirror of the server: if some other schema
                // change has no migration, rebuilding the cache is an acceptable fallback.
                .fallbackToDestructiveMigration()
                .build()
    }
}
