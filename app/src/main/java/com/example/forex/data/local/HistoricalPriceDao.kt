package com.example.forex.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for persisting and querying historical forex candlestick prices.
 *
 * Provides reactive [Flow] queries for UI streaming and `suspend` methods
 * for background worker / repository operations.
 */
@Dao
interface HistoricalPriceDao {

    /**
     * Reactive stream of historical prices for a given symbol and timeframe,
     * sorted chronologically ascending.
     */
    @Query("""
        SELECT * FROM historical_forex_prices 
        WHERE pairSymbol = :pairSymbol AND timeframe = :timeframe 
        ORDER BY timestamp ASC
    """)
    fun getHistoricalPricesFlow(pairSymbol: String, timeframe: String): Flow<List<HistoricalForexPriceEntity>>

    /**
     * Query historical prices synchronously/asynchronously in chronological order.
     */
    @Query("""
        SELECT * FROM historical_forex_prices 
        WHERE pairSymbol = :pairSymbol AND timeframe = :timeframe 
        ORDER BY timestamp ASC
    """)
    suspend fun getHistoricalPrices(pairSymbol: String, timeframe: String): List<HistoricalForexPriceEntity>

    /**
     * Batch insert historical prices with REPLACE conflict strategy
     * to safely overwrite candles when refreshed from live feeds or APIs.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrices(prices: List<HistoricalForexPriceEntity>)

    /**
     * Insert or update a single price candle.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrice(price: HistoricalForexPriceEntity)

    /**
     * Purge historical cached prices for a specific currency pair and timeframe.
     */
    @Query("DELETE FROM historical_forex_prices WHERE pairSymbol = :pairSymbol AND timeframe = :timeframe")
    suspend fun deletePricesForPairAndTimeframe(pairSymbol: String, timeframe: String): Int

    /**
     * Purge all price records older than [cutoffTimestamp] for cache size management.
     */
    @Query("DELETE FROM historical_forex_prices WHERE timestamp < :cutoffTimestamp")
    suspend fun purgeOldPrices(cutoffTimestamp: Long): Int

    /**
     * Count how many cached candles exist for a specific pair and timeframe.
     */
    @Query("SELECT COUNT(*) FROM historical_forex_prices WHERE pairSymbol = :pairSymbol AND timeframe = :timeframe")
    suspend fun getPriceCount(pairSymbol: String, timeframe: String): Int

    /**
     * Get the latest candle timestamp available in local cache.
     */
    @Query("SELECT MAX(timestamp) FROM historical_forex_prices WHERE pairSymbol = :pairSymbol AND timeframe = :timeframe")
    suspend fun getLatestTimestamp(pairSymbol: String, timeframe: String): Long?
}
