package com.example.forex.data.remote

import com.example.forex.data.model.CandleStick
import com.example.forex.data.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Alpha Vantage parsing/aggregation layer — no network, no Android.
 * These exercise the exact code paths [RealMarketDataProvider] runs against live payloads.
 */
class RealMarketDataProviderParsingTest {

    @Test
    fun `parses FX intraday CSV with name-based columns`() {
        val csv = """
            time,open,high,low,close
            "2024-06-12 14:30:00",1.0850,1.0860,1.0845,1.0855
            "2024-06-12 15:30:00",1.0855,1.0870,1.0850,1.0865
            Thank you for using Alpha Vantage! This is a sample of the latest call.
        """.trimIndent()
        val result = RealMarketDataProvider.parseTimeSeriesCsv(csv)
        val candles = result.getOrThrow()
        assertEquals(2, candles.size)
        assertEquals(1.0865, candles[1].close, 1e-9)
        assertTrue(candles[0].timestamp < candles[1].timestamp)
    }

    @Test
    fun `prefers epoch timestamp column and tolerates out-of-order rows`() {
        val csv = """
            time,open,high,low,close,volume,timestamp
            "2024-06-12 16:00:00",67000.0,67500.0,66500.0,67250.0,12.5,1718208000
            "2024-06-12 15:00:00",66800.0,67100.0,66700.0,67000.0,9.25,1718204400
        """.trimIndent()
        val candles = RealMarketDataProvider.parseTimeSeriesCsv(csv).getOrThrow()
        assertEquals(2, candles.size)
        assertEquals(1718204400L * 1000L, candles[0].timestamp)
        assertEquals(12.5, candles[1].volume, 1e-9)
    }

    @Test
    fun `malformed rows are skipped and total garbage fails`() {
        val csv = """
            time,open,high,low,close
            "2024-06-12 14:00:00",N/A,1.0860,1.0845,1.0855
            "2024-06-12 15:00:00",1.0850,1.0860,1.0845,1.0855
        """.trimIndent()
        val candles = RealMarketDataProvider.parseTimeSeriesCsv(csv).getOrThrow()
        assertEquals(1, candles.size)

        val bad = RealMarketDataProvider.parseTimeSeriesCsv("only,one,line")
        assertTrue(bad.isFailure)
    }

    @Test
    fun `quotes parse price bid and ask`() {
        val csv = """
            from_currency_code,from_currency_name,to_currency_code,to_currency_name,exchange_rate,last_refreshed,utc_timezone,bid_price,ask_price
            USD,US Dollar,EUR,Euro,0.9238,2024-06-12 15:00:00,UTC+0,0.9237,0.9239
        """.trimIndent()
        val tick = RealMarketDataProvider.parseQuoteCsv("USD/EUR", csv)
        assertNotNull(tick)
        assertEquals(0.9238, tick!!.price, 1e-9)
        assertEquals(0.9237, tick.bid, 1e-9)
        assertEquals(0.9239, tick.ask, 1e-9)
        assertEquals("USD/EUR", tick.symbol)
    }

    @Test
    fun `one hour candles aggregate into hourly-aligned four hour buckets`() {
        val hour = 3_600_000L
        val start = 1_718_208_000_000L // 2024-06-12 16:00:00 UTC — 4h-aligned
        val hourly = (0 until 8).map { i ->
            val close = 100.0 + i
            CandleStick(
                timestamp = start + i * hour,
                open = close - 0.5,
                high = close + 1.0,
                low = close - 1.5,
                close = close,
                volume = 10.0
            )
        }
        val fourH = RealMarketDataProvider.aggregateToBucket(hourly, 4 * hour)
        assertEquals(2, fourH.size)
        assertEquals(start, fourH[0].timestamp)
        // Bucket 1 (bars 0..3): open of first, max high, min low, close of last, summed volume
        assertEquals(99.5, fourH[0].open, 1e-9)
        assertEquals(104.0, fourH[0].high, 1e-9)
        assertEquals(98.5, fourH[0].low, 1e-9)
        assertEquals(103.0, fourH[0].close, 1e-9)
        assertEquals(40.0, fourH[0].volume, 1e-9)
        assertEquals(107.0, fourH[1].close, 1e-9)
        assertEquals(103.5, fourH[1].open, 1e-9)
    }

    @Test
    fun `error envelopes map to typed exceptions`() {
        assertTrue(
            RealMarketDataProvider.classifyErrorPayload(
                null,
                "Thanks for using our API! This endpoint requires a premium plan.",
                null
            ) is MarketDataException.PremiumFeatureRequired
        )
        assertTrue(
            RealMarketDataProvider.classifyErrorPayload(
                null,
                "You called the API service beyond the maximum of 5 requests per minute.",
                null
            ) is MarketDataException.RateLimited
        )
        assertTrue(
            RealMarketDataProvider.classifyErrorPayload(
                null,
                "You have exceeded the 25 requests per day limit for the free service.",
                null
            ) is MarketDataException.DailyQuotaExhausted
        )
        assertTrue(
            RealMarketDataProvider.classifyErrorPayload(null, null, "Invalid API call.")
                is MarketDataException.InvalidRequest
        )
    }

    @Test
    fun `request plans map timeframes to endpoints`() {
        val fxH1 = RealMarketDataProvider.planFor("EUR/USD", Timeframe.H1)
        assertEquals("FX_INTRADAY", fxH1.function)
        assertEquals("EUR", fxH1.requestParams["from_symbol"])
        assertEquals("USD", fxH1.requestParams["to_symbol"])
        assertEquals("60min", fxH1.requestParams["interval"])
        assertNull(fxH1.aggregateToMillis)

        val h4 = RealMarketDataProvider.planFor("EUR/USD", Timeframe.H4)
        assertEquals(4 * 3_600_000L, h4.aggregateToMillis)
        assertEquals("60min", h4.requestParams["interval"])

        val fxDaily = RealMarketDataProvider.planFor("GBP/USD", Timeframe.D1)
        assertEquals("FX_DAILY", fxDaily.function)

        val btcDaily = RealMarketDataProvider.planFor("BTC/USD", Timeframe.D1)
        assertEquals("DIGITAL_CURRENCY_DAILY", btcDaily.function)
        assertEquals("BTC", btcDaily.requestParams["symbol"])
        assertEquals("USD", btcDaily.requestParams["market"])

        val btcM15 = RealMarketDataProvider.planFor("BTC/USD", Timeframe.M15)
        assertEquals("CRYPTO_INTRADAY", btcM15.function)
        assertEquals("15min", btcM15.requestParams["interval"])

        val gold = RealMarketDataProvider.planFor("XAU/USD", Timeframe.M5)
        assertEquals("FX_INTRADAY", gold.function)
        assertEquals("XAU", gold.requestParams["from_symbol"])
    }

    @Test
    fun `date fallback resolves ISO day strings in UTC`() {
        val millis = RealMarketDataProvider.resolveEpochOrDate("2024-06-12")
        assertNotNull(millis)
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = millis!!
        assertEquals(2024, cal.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.JUNE, cal.get(java.util.Calendar.MONTH))
        assertEquals(12, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }
}
