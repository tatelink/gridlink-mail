package app.jmail.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EmailEntity::class, MailboxEntity::class, ScheduledSendEntity::class,
        SnoozedEntity::class, RecentContactEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class JmailDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao
    abstract fun mailboxDao(): MailboxDao
    abstract fun scheduledSendDao(): ScheduledSendDao
    abstract fun snoozedDao(): SnoozedDao
    abstract fun recentContactDao(): RecentContactDao

    companion object {
        fun build(context: Context): JmailDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                JmailDatabase::class.java,
                "jmail.db",
            )
                // Cache is a disposable mirror of the server, so just rebuild on schema changes.
                .fallbackToDestructiveMigration()
                .build()
    }
}
