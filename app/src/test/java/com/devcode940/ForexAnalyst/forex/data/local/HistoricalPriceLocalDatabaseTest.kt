package com.devcode940.ForexAnalyst.forex.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.devcode940.ForexAnalyst.forex.data.model.CandleStick
import com.devcode940.ForexAnalyst.forex.data.model.Timeframe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HistoricalPriceLocalDatabaseTest {

    private lateinit var database: ForexLocalDatabase
    private lateinit var dao: HistoricalPriceDao
    private lateinit var repository: ForexOfflinePriceRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ForexLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.historicalPriceDao()
        repository = ForexOfflinePriceRepository(dao)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndQueryHistoricalPricesChronologically() = runTest {
        val now = System.currentTimeMillis()
        val candles = listOf(
            CandleStick(timestamp = now - 3600000, open = 1.0800, high = 1.0850, low = 1.0790, close = 1.0840, volume = 1200.0),
            CandleStick(timestamp = now - 1800000, open = 1.0840, high = 1.0870, low = 1.0820, close = 1.0865, volume = 1500.0),
            CandleStick(timestamp = now, open = 1.0865, high = 1.0890, low = 1.0850, close = 1.0880, volume = 2000.0)
        )

        repository.persistCandles("EUR/USD", Timeframe.H1, candles)

        val retrieved = repository.getCachedCandles("EUR/USD", Timeframe.H1)
        assertEquals(3, retrieved.size)
        assertEquals(candles[0].timestamp, retrieved[0].timestamp)
        assertEquals(candles[1].timestamp, retrieved[1].timestamp)
        assertEquals(candles[2].timestamp, retrieved[2].timestamp)
        assertEquals(1.0880, retrieved.last().close, 0.00001)

        assertTrue(repository.hasOfflineData("EUR/USD", Timeframe.H1))
        assertFalse(repository.hasOfflineData("GBP/USD", Timeframe.H1))
    }

    @Test
    fun testFlowEmissionOnPriceInsertion() = runTest {
        val now = System.currentTimeMillis()
        val initialList = listOf(
            CandleStick(timestamp = now - 1000, open = 1.2500, high = 1.2550, low = 1.2480, close = 1.2520, volume = 500.0)
        )
        repository.persistCandles("GBP/USD", Timeframe.M15, initialList)

        val firstFlowItem = repository.observeCachedCandles("GBP/USD", Timeframe.M15).first()
        assertEquals(1, firstFlowItem.size)
        assertEquals(1.2520, firstFlowItem[0].close, 0.00001)

        // Insert second candle
        val secondList = listOf(
            CandleStick(timestamp = now, open = 1.2520, high = 1.2580, low = 1.2510, close = 1.2570, volume = 800.0)
        )
        repository.persistCandles("GBP/USD", Timeframe.M15, secondList)

        val updatedFlow = repository.observeCachedCandles("GBP/USD", Timeframe.M15).first()
        assertEquals(2, updatedFlow.size)
    }

    @Test
    fun testOnConflictReplacement() = runTest {
        val now = 1700000000000L
        val original = CandleStick(timestamp = now, open = 1.1000, high = 1.1050, low = 1.0950, close = 1.1020, volume = 100.0)
        repository.persistCandles("USD/JPY", Timeframe.H4, listOf(original))

        // Updated candle with same composite key (USD/JPY, H4, 1700000000000L)
        val revised = CandleStick(timestamp = now, open = 1.1000, high = 1.1080, low = 1.0950, close = 1.1075, volume = 450.0)
        repository.persistCandles("USD/JPY", Timeframe.H4, listOf(revised))

        val cached = repository.getCachedCandles("USD/JPY", Timeframe.H4)
        assertEquals(1, cached.size)
        assertEquals(1.1075, cached[0].close, 0.00001)
        assertEquals(1.1080, cached[0].high, 0.00001)
    }

    @Test
    fun testDeleteAndPurge() = runTest {
        val now = System.currentTimeMillis()
        val c1 = CandleStick(timestamp = now - 5000, open = 2000.0, high = 2010.0, low = 1995.0, close = 2005.0, volume = 300.0)
        repository.persistCandles("XAU/USD", Timeframe.D1, listOf(c1))

        assertEquals(1, repository.getCachedCandles("XAU/USD", Timeframe.D1).size)

        repository.clearCache("XAU/USD", Timeframe.D1)
        assertEquals(0, repository.getCachedCandles("XAU/USD", Timeframe.D1).size)
        assertFalse(repository.hasOfflineData("XAU/USD", Timeframe.D1))
    }

    @Test
    fun testLatestTimestamp() = runTest {
        val t1 = 100000L
        val t2 = 200000L
        val candles = listOf(
            CandleStick(timestamp = t1, open = 1.0, high = 1.1, low = 0.9, close = 1.05, volume = 10.0),
            CandleStick(timestamp = t2, open = 1.05, high = 1.2, low = 1.0, close = 1.15, volume = 20.0)
        )
        repository.persistCandles("BTC/USD", Timeframe.H1, candles)

        val latest = repository.getLatestTimestamp("BTC/USD", Timeframe.H1)
        assertNotNull(latest)
        assertEquals(t2, latest)
    }
}
