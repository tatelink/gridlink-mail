package app.sterna.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EmailEntity::class, EmailFtsEntity::class, EmailBodyEntity::class, MailboxEntity::class,
        ScheduledSendEntity::class, SnoozedEntity::class, RecentContactEntity::class, OutboxEntity::class,
        PurgeSnapshotEntity::class, MailboxUidValidityEntity::class,
    ],
    version = 19,
    exportSchema = false,
)
abstract class SternaDatabase : RoomDatabase() {
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

    companion object {
        fun build(context: Context): SternaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SternaDatabase::class.java,
                "sterna.db",
            )
                // The outbox holds unsent mail (user data): migrate it additively so a schema
                // bump never destroys a queued send. 13→14 adds draftEmailId to outbox/
                // scheduled_sends (#63); 14→15 rebuilds the FTS index (#71); 15→16 rebuilds
                // emails/bodies/snoozed with composite (accountId, id) keys (issue #31),
                // copying every row over — snoozed is user data too, so the migration must
                // never fall back destructively; 16→17 adds the persisted To: recipients (#63);
                // 17→18 adds `purge_snapshot`, the frozen destroy list of an Empty trash (#99);
                // 18→19 records which UIDVALIDITY a snapshot and a folder's cache belong to (#99).
                .addMigrations(
                    MIGRATION_9_10, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                    MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                    MIGRATION_18_19,
                )
                // The rest of the DB is a disposable mirror of the server: if some other schema
                // change has no migration, rebuilding the cache is an acceptable fallback.
                .fallbackToDestructiveMigration()
                .build()
    }
}
