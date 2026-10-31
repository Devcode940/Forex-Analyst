package com.example.forex.data.ai

import com.example.forex.data.model.CandleStick
import com.example.forex.data.model.IndicatorSettings
import com.example.forex.data.model.PairCatalog
import com.example.forex.data.model.Timeframe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the "dynamic" property: responses are derived from the data, so two different
 * markets produce different text, and intent routing selects the right analytic section.
 */
class DynamicAiResponseEngineTest {

    private val base = 1_700_000_000_000L

    private fun series(count: Int, generator: (Int) -> Double): List<CandleStick> =
        (0 until count).map { i ->
            val close = generator(i)
            CandleStick(base + i * 3_600_000L, close, close + 0.0008, close - 0.0008, close, 100.0)
        }

    private val trending = series(80) { i -> 1.0800 + 0.00025 * i }
    private val ranging = series(80) { i -> 1.0800 + if (i % 4 == 0) 0.0004 else -0.0004 }

    private fun context(candles: List<CandleStick>) = DynamicAiResponseEngine.buildContext(
        pair = PairCatalog.pairFor("EUR/USD", candles.last().close, candles.maxOf { it.high }, candles.minOf { it.low }),
        candles = candles,
        timeframe = Timeframe.H1,
        settings = IndicatorSettings(),
        parentTrends = listOf("4H" to "Bullish")
    )

    @Test
    fun `intent detection covers redraw types and keywords`() {
        assertTrue(DynamicAiResponseEngine.detectIntent("Where is key support?", null) == DynamicAiResponseEngine.AiIntent.SUPPORT_RESISTANCE)
        assertTrue(DynamicAiResponseEngine.detectIntent("plot fib retracement", null) == DynamicAiResponseEngine.AiIntent.FIBONACCI)
        assertTrue(DynamicAiResponseEngine.detectIntent("how much should I risk", null) == DynamicAiResponseEngine.AiIntent.POSITION_SIZING)
        assertTrue(DynamicAiResponseEngine.detectIntent("what is the ATR doing", null) == DynamicAiResponseEngine.AiIntent.VOLATILITY)
        assertTrue(DynamicAiResponseEngine.detectIntent("good morning", null) == DynamicAiResponseEngine.AiIntent.MARKET_OVERVIEW)
        assertTrue(DynamicAiResponseEngine.detectIntent("ignore the text", "TRENDLINES") == DynamicAiResponseEngine.AiIntent.TRENDLINE_STRUCTURE)
    }

    @Test
    fun `responses are recomputed from data, not canned`() {
        val trendAnswer = DynamicAiResponseEngine.compose(
            context(trending),
            DynamicAiResponseEngine.AiIntent.MARKET_OVERVIEW,
            null
        )
        val rangeAnswer = DynamicAiResponseEngine.compose(
            context(ranging),
            DynamicAiResponseEngine.AiIntent.MARKET_OVERVIEW,
            null
        )
        assertTrue(trendAnswer.contains("EUR/USD"))
        assertTrue(trendAnswer.contains("ATR"))
        assertFalse("trending and ranging charts must not produce identical text", trendAnswer == rangeAnswer)
    }

    @Test
    fun `sizing answer quotes lots and stop derived from ATR`() {
        val answer = DynamicAiResponseEngine.compose(
            context(trending),
            DynamicAiResponseEngine.AiIntent.POSITION_SIZING,
            "how many lots at 1% risk?"
        )
        assertTrue(answer.contains("standard lots"))
        assertTrue(answer.contains("Suggested size"))
        assertTrue(answer.contains("You asked"))
    }

    @Test
    fun `follow-up prompts never repeat the current intent chip`() {
        DynamicAiResponseEngine.AiIntent.values().forEach { intent ->
            val prompts = DynamicAiResponseEngine.followUpPrompts(intent)
            assertFalse(prompts.isEmpty())
            assertFalse(prompts.contains(intent.chip))
            assertTrue(prompts.size <= 4)
        }
    }

    @Test
    fun `fact sheet carries quantitative values`() {
        val sheet = DynamicAiResponseEngine.quantitativeFactSheet(context(trending))
        assertTrue(sheet.contains("Rule-engine signal"))
        assertTrue(sheet.contains("ATR(14)"))
        assertTrue(sheet.contains("Volatility regime"))
    }
}
