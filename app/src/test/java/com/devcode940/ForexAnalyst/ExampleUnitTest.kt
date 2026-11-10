package com.devcode940.ForexAnalyst

import com.devcode940.ForexAnalyst.forex.data.model.CurrencyPair
import com.devcode940.ForexAnalyst.forex.data.model.PairCatalog
import com.devcode940.ForexAnalyst.forex.data.model.Timeframe
import com.devcode940.ForexAnalyst.forex.data.repository.PriceGenerator
import com.devcode940.ForexAnalyst.forex.data.repository.TechnicalAnalysisEngine
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testAiChartOverlayGeneration() {
    val pair = PairCatalog.pairFor(symbol = "EUR/USD", currentPrice = 1.0865, high24h = 1.0900, low24h = 1.0820)
    val candles = PriceGenerator.generateCandles(basePrice = 1.0850, count = 100, timeframe = Timeframe.H1)
    
    val overlay = TechnicalAnalysisEngine.generateAiChartOverlay(pair, candles, Timeframe.H1)
    
    assertNotNull(overlay)
    assertEquals("EUR/USD", overlay.pairSymbol)
    assertTrue("Should generate AI demand/supply zones", overlay.zones.isNotEmpty())
    assertTrue("Should generate AI Fibonacci levels", overlay.fibonacciLevels.isNotEmpty())
    assertTrue("Should compute Fibonacci levels with valid prices", overlay.fibonacciLevels.all { it.price > 0 })
    assertTrue("Fibonacci levels count should cover standard ratios", overlay.fibonacciLevels.size >= 5)
  }

  @Test
  fun testTradeSignalGeneration() {
    val pair = PairCatalog.pairFor(symbol = "GBP/USD", currentPrice = 1.2720, high24h = 1.2750, low24h = 1.2680)
    val candles = PriceGenerator.generateCandles(basePrice = 1.2700, count = 100, timeframe = Timeframe.H1)
    
    val signal = TechnicalAnalysisEngine.generateTradeSignal(pair, candles, Timeframe.H1)
    assertNotNull(signal)
    assertTrue("Signal confidence should be between 0 and 100", signal.confidenceScore in 0..100)
    assertTrue("Stop loss should be positive", signal.stopLoss > 0)
    assertTrue("Take profit 1 should be positive", signal.takeProfit1 > 0)
    assertTrue("Take profit 2 should be positive", signal.takeProfit2 > 0)
  }
}
