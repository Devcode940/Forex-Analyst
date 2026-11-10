package com.devcode940.ForexAnalyst.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.devcode940.ForexAnalyst.data.local.HistoricalForexPriceEntity
import com.devcode940.ForexAnalyst.data.local.HistoricalPriceDao

@Database(
    entities = [
        SignalEntity::class,
        AlertEntity::class,
        WatchlistEntity::class,
        CandleEntity::class,
        HistoricalForexPriceEntity::class,
        UserProfileEntity::class,
        UserSettingsEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class ForexDatabase : RoomDatabase() {
    abstract fun forexDao(): ForexDao
    abstract fun historicalPriceDao(): HistoricalPriceDao

    companion object {
        @Volatile
        private var INSTANCE: ForexDatabase? = null

        fun getDatabase(context: Context): ForexDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ForexDatabase::class.java,
                    "forex_analyzer_db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
