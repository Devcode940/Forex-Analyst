package com.example.forex.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database definition for local forex market persistence.
 * Defined in package `com.example.forex.data.local` to fulfill the architecture requirements.
 */
@Database(
    entities = [HistoricalForexPriceEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ForexLocalDatabase : RoomDatabase() {

    abstract fun historicalPriceDao(): HistoricalPriceDao

    companion object {
        @Volatile
        private var INSTANCE: ForexLocalDatabase? = null

        fun getDatabase(context: Context): ForexLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ForexLocalDatabase::class.java,
                    "forex_historical_prices_db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
