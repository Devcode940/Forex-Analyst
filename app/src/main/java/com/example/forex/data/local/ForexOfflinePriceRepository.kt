package com.example.forex.data.local

import com.example.forex.data.model.CandleStick
import com.example.forex.data.model.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository layer abstracting local Room database persistence for historical forex candles.
 *
 * Provides reactive [Flow] observation for UI consumption and asynchronous persistence
 * methods ensuring offline accessibility of market charts.
 */
class ForexOfflinePriceRepository(
    private val historicalPriceDao: HistoricalPriceDao
) {

    /**
     * Observe historical price candles reactively as a Flow.
     */
    fun observeCachedCandles(pairSymbol: String, timeframe: Timeframe): Flow<List<CandleStick>> {
        return historicalPriceDao.getHistoricalPricesFlow(pairSymbol, timeframe.name)
            .map { entities -> entities.toCandleSticks() }
    }

    /**
     * Query cached historical prices synchronously/asynchronously from Room.
     */
    suspend fun getCachedCandles(pairSymbol: String, timeframe: Timeframe): List<CandleStick> =
        withContext(Dispatchers.IO) {
            historicalPriceDao.getHistoricalPrices(pairSymbol, timeframe.name).toCandleSticks()
        }

    /**
     * Save/persist historical candles into Room for offline usage.
     */
    suspend fun persistCandles(pairSymbol: String, timeframe: Timeframe, candles: List<CandleStick>) =
        withContext(Dispatchers.IO) {
            if (candles.isNotEmpty()) {
                val entities = candles.toHistoricalPriceEntities(pairSymbol, timeframe.name)
                historicalPriceDao.insertPrices(entities)
            }
        }

    /**
     * Check if offline historical data exists for the given pair and timeframe.
     */
    suspend fun hasOfflineData(pairSymbol: String, timeframe: Timeframe): Boolean =
        withContext(Dispatchers.IO) {
            historicalPriceDao.getPriceCount(pairSymbol, timeframe.name) > 0
        }

    /**
     * Delete cached historical data for the given pair and timeframe.
     */
    suspend fun clearCache(pairSymbol: String, timeframe: Timeframe): Int =
        withContext(Dispatchers.IO) {
            historicalPriceDao.deletePricesForPairAndTimeframe(pairSymbol, timeframe.name)
        }

    /**
     * Get the latest timestamp available in offline storage for the pair and timeframe.
     */
    suspend fun getLatestTimestamp(pairSymbol: String, timeframe: Timeframe): Long? =
        withContext(Dispatchers.IO) {
            historicalPriceDao.getLatestTimestamp(pairSymbol, timeframe.name)
        }
}
