package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.Timeframe
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generator for synthetic OHLCV candle series used for unit testing,
 * offline development, and fallback simulation feeds.
 */
object PriceGenerator {

    fun generateCandles(
        basePrice: Double = 1.0850,
        count: Int = 100,
        timeframe: Timeframe = Timeframe.H1,
        seed: Long = 42L
    ): List<CandleStick> {
        val random = Random(seed)
        val timeframeMillis = timeframe.minutes * 60_000L

        val now = System.currentTimeMillis()
        val startTimestamp = now - (count.toLong() * timeframeMillis)
        val candles = ArrayList<CandleStick>(count)

        var currentClose = basePrice
        val volatility = basePrice * 0.0015

        for (i in 0 until count) {
            val timestamp = startTimestamp + (i.toLong() * timeframeMillis)
            val open = currentClose
            val trend = sin(i / 10.0) * (volatility * 0.4)
            val noise = (random.nextDouble() - 0.5) * volatility
            val close = (open + trend + noise).coerceAtLeast(0.0001)

            val high = maxOf(open, close) + random.nextDouble() * volatility * 0.6
            val low = (minOf(open, close) - random.nextDouble() * volatility * 0.6).coerceAtLeast(0.0001)
            val volume = 100.0 + random.nextDouble() * 500.0

            candles.add(
                CandleStick(
                    timestamp = timestamp,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume
                )
            )
            currentClose = close
        }

        return candles
    }
}
