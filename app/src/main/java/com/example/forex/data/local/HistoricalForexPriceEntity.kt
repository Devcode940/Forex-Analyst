package com.example.forex.data.local

import androidx.room.Entity
import androidx.room.Index
import com.example.forex.data.model.CandleStick

/**
 * Room database entity persisting historical forex candlestick data for offline access.
 *
 * Stored in the new 'data/local' package schema as requested.
 * Uses a composite primary key (pairSymbol, timeframe, timestamp) with an index
 * to guarantee unique historical records and O(log N) temporal queries.
 */
@Entity(
    tableName = "historical_forex_prices",
    primaryKeys = ["pairSymbol", "timeframe", "timestamp"],
    indices = [
        Index(value = ["pairSymbol", "timeframe", "timestamp"], unique = true),
        Index(value = ["cachedAt"])
    ]
)
data class HistoricalForexPriceEntity(
    val pairSymbol: String,      // e.g. "EUR/USD", "GBP/USD", "XAU/USD"
    val timeframe: String,       // e.g. "M1", "M5", "M15", "H1", "H4", "D1"
    val timestamp: Long,         // Candle open epoch timestamp in milliseconds
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val cachedAt: Long = System.currentTimeMillis() // Metadata timestamp for cache eviction & freshness checks
)

/**
 * Map entity to domain model [CandleStick].
 */
fun HistoricalForexPriceEntity.toCandleStick(): CandleStick {
    return CandleStick(
        timestamp = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        isBullish = close >= open
    )
}

/**
 * Map domain model [CandleStick] to Room [HistoricalForexPriceEntity].
 */
fun CandleStick.toHistoricalPriceEntity(
    pairSymbol: String,
    timeframe: String,
    cachedAt: Long = System.currentTimeMillis()
): HistoricalForexPriceEntity {
    return HistoricalForexPriceEntity(
        pairSymbol = pairSymbol,
        timeframe = timeframe,
        timestamp = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        cachedAt = cachedAt
    )
}

/**
 * Convert a list of database entities to domain candlestick models.
 */
fun List<HistoricalForexPriceEntity>.toCandleSticks(): List<CandleStick> {
    return map { it.toCandleStick() }
}

/**
 * Convert a list of domain candlesticks to database entities.
 */
fun List<CandleStick>.toHistoricalPriceEntities(
    pairSymbol: String,
    timeframe: String,
    cachedAt: Long = System.currentTimeMillis()
): List<HistoricalForexPriceEntity> {
    return map { it.toHistoricalPriceEntity(pairSymbol, timeframe, cachedAt) }
}
