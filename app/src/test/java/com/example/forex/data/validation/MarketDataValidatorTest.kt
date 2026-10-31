package com.example.forex.data.validation

import com.example.forex.data.model.CandleStick
import com.example.forex.data.model.MarketTick
import com.example.forex.data.model.PriceDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataValidatorTest {

    private val now = 1_700_000_000_000L

    private fun candle(
        ts: Long = now,
        open: Double = 100.0,
        high: Double = 110.0,
        low: Double = 90.0,
        close: Double = 105.0,
        volume: Double = 1000.0
    ) = CandleStick(ts, open, high, low, close, volume)

    @Test
    fun `valid candle is healthy`() {
        val report = MarketDataValidator.validateCandle(candle(), now = now + 60_000)
        assertEquals(ValidationSeverity.HEALTHY, report.severity)
        assertTrue(report.isUsable)
    }

    @Test
    fun `candle with high below close is rejected`() {
        val report = MarketDataValidator.validateCandle(
            candle(high = 100.0, close = 105.0),
            now = now + 60_000
        )
        assertEquals(ValidationSeverity.REJECTED, report.severity)
        assertFalse(report.isUsable)
    }

    @Test
    fun `non-finite prices are rejected`() {
        val report = MarketDataValidator.validateCandle(candle(close = Double.NaN), now = now)
        assertEquals(ValidationSeverity.REJECTED, report.severity)
    }

    @Test
    fun `future timestamps are rejected`() {
        val report = MarketDataValidator.validateCandle(
            candle(ts = now + 10L * 60L * 60L * 1000L),
            now = now
        )
        assertEquals(ValidationSeverity.REJECTED, report.severity)
    }

    @Test
    fun `invalid volume is a warning not a rejection`() {
        val report = MarketDataValidator.validateCandle(candle(volume = -5.0), now = now + 60_000)
        assertEquals(ValidationSeverity.DEGRADED, report.severity)
        assertTrue(report.isUsable)
    }

    @Test
    fun `series is sorted deduplicated and size-checked`() {
        val base = now - 10 * 60_000L
        val series = listOf(
            candle(ts = base + 60_000, close = 102.0),
            candle(ts = base, close = 101.0),
            candle(ts = base + 60_000, close = 103.0), // duplicate timestamp, later wins
            candle(ts = base + 2 * 60_000L, close = 104.0)
        )
        val result = MarketDataValidator.validateCandleSeries(series, 60_000L, minValidCount = 2)
        assertEquals(3, result.candles.size)
        assertEquals(103.0, result.candles[1].close, 1e-9)
        assertTrue(result.candles.map { it.timestamp }.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(result.report.issues.any { it.message.contains("duplicate", ignoreCase = true) })
    }

    @Test
    fun `series below minimum length is rejected`() {
        val series = (0 until 10).map { candle(ts = now + it * 60_000L) }
        val result = MarketDataValidator.validateCandleSeries(series, 60_000L, minValidCount = 40)
        assertFalse(result.report.isUsable)
        assertTrue(result.candles.isEmpty())
    }

    @Test
    fun `in-and-out single bar spikes are removed while real one-way jumps survive`() {
        // Flat series at 100 with one +15% bar that immediately reverses.
        val series = (0 until 41).map { i ->
            if (i == 20) candle(ts = now + i * 60_000L, open = 115.0, high = 115.0, low = 100.0, close = 115.0)
            else candle(ts = now + i * 60_000L, open = 100.0, high = 100.0, low = 100.0, close = 100.0)
        }
        val cleaned = MarketDataValidator.validateCandleSeries(series, 60_000L, minValidCount = 40)
        assertEquals(40, cleaned.candles.size)
        assertTrue(cleaned.candles.none { it.close == 115.0 })
        assertTrue(cleaned.report.issues.any { it.message.contains("spike", ignoreCase = true) })

        // A genuine step change (news gap that does NOT revert) must be preserved.
        val step = (0 until 41).map { i ->
            val price = if (i >= 20) 115.0 else 100.0
            candle(ts = now + i * 60_000L, open = price, high = price, low = price, close = price)
        }
        val kept = MarketDataValidator.validateCandleSeries(step, 60_000L, minValidCount = 40)
        assertEquals(41, kept.candles.size)
    }

    @Test
    fun `corrupt structural candles are dropped from series not fatal`() {
        val series = (0 until 40).map { i ->
            if (i == 5) candle(ts = now + i * 60_000L, high = -1.0) // impossible
            else candle(ts = now + i * 60_000L)
        }
        val result = MarketDataValidator.validateCandleSeries(series, 60_000L, minValidCount = 30)
        assertTrue(result.report.isUsable)
        assertEquals(39, result.candles.size)
    }

    @Test
    fun `tick with inverted quote is rejected`() {
        val tick = MarketTick("EUR/USD", 1.0850, bid = 1.0860, ask = 1.0840, volume = 1.0, timestamp = now)
        val report = MarketDataValidator.validateTick(tick, referencePrice = 1.0850, now = now + 1000)
        assertEquals(ValidationSeverity.REJECTED, report.severity)
    }

    @Test
    fun `tick price far from reference is rejected as corrupt`() {
        val tick = MarketTick("EUR/USD", 1.2000, bid = 1.1999, ask = 1.2001, volume = 1.0, timestamp = now)
        val report = MarketDataValidator.validateTick(tick, referencePrice = 1.0850, maxJumpFraction = 0.06, now = now + 1000)
        assertEquals(ValidationSeverity.REJECTED, report.severity)
    }

    @Test
    fun `healthy tick passes and wide spread degrades only`() {
        val ok = MarketTick("EUR/USD", 1.0850, bid = 1.0849, ask = 1.0851, volume = 1.0, timestamp = now, priceChangeDirection = PriceDirection.UP)
        assertEquals(ValidationSeverity.HEALTHY, MarketDataValidator.validateTick(ok, 1.0850, now = now + 1000).severity)

        val wide = ok.copy(bid = 1.0500, ask = 1.1500)
        val report = MarketDataValidator.validateTick(wide, 1.0850, now = now + 1000)
        assertEquals(ValidationSeverity.DEGRADED, report.severity)
        assertTrue(report.isUsable)
    }

    @Test
    fun `symbol format validation`() {
        assertTrue(MarketDataValidator.validateSymbolFormat("EUR/USD").isUsable)
        assertTrue(MarketDataValidator.validateSymbolFormat("XAU/USD").isUsable)
        assertFalse(MarketDataValidator.validateSymbolFormat("bad symbol!").isUsable)
        assertFalse(MarketDataValidator.validateSymbolFormat("").isUsable)
    }
}
