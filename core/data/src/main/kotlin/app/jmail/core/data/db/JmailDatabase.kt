package app.jmail.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [EmailEntity::class, MailboxEntity::class], version = 3, exportSchema = false)
abstract class JmailDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao
    abstract fun mailboxDao(): MailboxDao

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
