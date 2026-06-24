package app.sterna.core.data.db

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
abstract class SternaDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao
    abstract fun mailboxDao(): MailboxDao
    abstract fun scheduledSendDao(): ScheduledSendDao
    abstract fun snoozedDao(): SnoozedDao
    abstract fun recentContactDao(): RecentContactDao

    companion object {
        fun build(context: Context): SternaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SternaDatabase::class.java,
                "sterna.db",
            )
                // Cache is a disposable mirror of the server, so just rebuild on schema changes.
                .fallbackToDestructiveMigration()
                .build()
    }
}
