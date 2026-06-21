package app.jmail.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [EmailEntity::class], version = 1, exportSchema = false)
abstract class JmailDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao

    companion object {
        fun build(context: Context): JmailDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                JmailDatabase::class.java,
                "jmail.db",
            ).build()
    }
}
