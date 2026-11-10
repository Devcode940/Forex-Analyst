package com.devcode940.ForexAnalyst.data.ai

import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CurrencyPair
import com.devcode940.ForexAnalyst.data.model.DetectedPattern
import com.devcode940.ForexAnalyst.data.model.IndicatorSettings
import com.devcode940.ForexAnalyst.data.model.PositionSizing
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import com.devcode940.ForexAnalyst.data.model.VolatilityRegime
import com.devcode940.ForexAnalyst.data.repository.TechnicalAnalysisEngine
import java.util.Locale
import kotlin.math.abs

/**
 * Deterministic counterpart of the OpenRouter LLM path.
 *
 * Instead of canned paragraphs, every reply is composed at call time from the live
 * [MarketAnalysisContext]: indicator values, ATR/volatility regime, structure levels and
 * the signal produced by [TechnicalAnalysisEngine]. Ask about the same topic on a different
 * chart (or the same chart an hour later) and the text — including every number, distance
 * and recommendation — changes with the data.
 */
object DynamicAiResponseEngine {

    enum class AiIntent(val chip: String) {
        SUPPORT_RESISTANCE("🪄 Redraw Key Support & Resistance"),
        TRENDLINE_STRUCTURE("📈 Draw Dynamic Trendlines"),
        FIBONACCI("📐 Plot Fibonacci Retracement"),
        TARGETS_RISK("🎯 Breakout & Target Levels (TP/SL)"),
        POSITION_SIZING("🧮 Suggest Lot Size & Risk"),
        VOLATILITY("🌪️ Volatility & ATR Regime"),
        INDICATORS("📊 Indicator Confluence"),
        MARKET_OVERVIEW("🔍 Full Technical Breakdown")
    }

    /**
     * Snapshot of everything an answer may need. Kept as plain data (no flows) so it is
     * trivially testable and reusable from Workers.
     */
    data class MarketAnalysisContext(
        val pair: CurrencyPair,
        val timeframe: Timeframe,
        val candleCount: Int,
        val signal: TradeSignal,
        val patterns: List<DetectedPattern>,
        val emaFast: Double,
        val emaSlow: Double,
        val emaTrend: Double?,
        val rsi: Double,
        val macdHistogram: Double?,
        val adx: Double?,
        val sizing: PositionSizing,
        val bollingerWidthPercent: Double?,
        val supports: List<Double>,
        val resistances: List<Double>,
        val swingLow: Double,
        val swingHigh: Double,
        val parentTrends: List<Pair<String, String>>,
        val dataSource: String,
        val qualityScore: Int
    )

    /** Builds the analysis context from raw candles (all values recomputed, never cached text). */
    fun buildContext(
        pair: CurrencyPair,
        candles: List<CandleStick>,
        timeframe: Timeframe,
        settings: IndicatorSettings,
        parentTrends: List<Pair<String, String>> = emptyList()
    ): MarketAnalysisContext {
        val closes = candles.map { it.close }
        val signal = TechnicalAnalysisEngine.generateTradeSignal(pair, candles, timeframe, settings)
        val patterns = TechnicalAnalysisEngine.detectPatterns(candles)
        val sizing = TechnicalAnalysisEngine.computeVolatilitySizing(pair, candles)
        val bb = TechnicalAnalysisEngine.calculateBollingerBands(
            closes, settings.bollingerPeriod, settings.bollingerStdDev
        ).lastOrNull()
        val (supports, resistances) = TechnicalAnalysisEngine.detectSupportResistance(candles)
        val lows = candles.map { it.low }
        val highs = candles.map { it.high }
        val swingLow = lows.minOrNull() ?: pair.currentPrice
        val swingHigh = highs.maxOrNull() ?: pair.currentPrice
        val macdHist = TechnicalAnalysisEngine.calculateMACD(
            closes, settings.macdFastPeriod, settings.macdSlowPeriod, settings.macdSignalPeriod
        ).histogram.lastOrNull { it != null }

        return MarketAnalysisContext(
            pair = pair,
            timeframe = timeframe,
            candleCount = candles.size,
            signal = signal,
            patterns = patterns,
            emaFast = TechnicalAnalysisEngine.calculateEMA(closes, settings.emaPeriod1).lastOrNull { it != null } ?: pair.currentPrice,
            emaSlow = TechnicalAnalysisEngine.calculateEMA(closes, settings.emaPeriod2).lastOrNull { it != null } ?: pair.currentPrice,
            emaTrend = if (closes.size >= settings.emaPeriod3)
                TechnicalAnalysisEngine.calculateEMA(closes, settings.emaPeriod3).lastOrNull { it != null } else null,
            rsi = signal.rsiValue,
            macdHistogram = macdHist,
            adx = TechnicalAnalysisEngine.latestADX(candles),
            sizing = sizing,
            bollingerWidthPercent = bb?.widthPercent,
            supports = supports,
            resistances = resistances,
            swingLow = swingLow,
            swingHigh = swingHigh,
            parentTrends = parentTrends,
            dataSource = signal.dataSource,
            qualityScore = signal.dataQualityScore
        )
    }

    /** Keyword-based intent routing for chat input + explicit redraw requests. */
    fun detectIntent(userText: String, requestedRedrawType: String?): AiIntent {
        requestedRedrawType?.let { type ->
            when (type.uppercase(Locale.US)) {
                "FIBONACCI" -> return AiIntent.FIBONACCI
                "ZONES" -> return AiIntent.SUPPORT_RESISTANCE
                "TRENDLINES" -> return AiIntent.TRENDLINE_STRUCTURE
                "TARGETS" -> return AiIntent.TARGETS_RISK
            }
        }
        val lower = userText.lowercase(Locale.US)
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        fun hasWord(vararg words: String) = words.any { tokens.contains(it) }
        fun has(vararg keys: String) = keys.any { lower.contains(it) }
        return when {
            hasWord("lot", "lots") || has("position size", "sizing", "how much", "risk per trade", "margin") -> AiIntent.POSITION_SIZING
            has("atr", "volatil", "squeeze", "turbulen", "news risk") -> AiIntent.VOLATILITY
            has("fib") -> AiIntent.FIBONACCI
            has("support", "resistance", "zone", "level", "demand", "supply") -> AiIntent.SUPPORT_RESISTANCE
            has("trendline", "trend line", "channel", "structure", "breakout vector") -> AiIntent.TRENDLINE_STRUCTURE
            has("target", "take profit", "tp1", "tp2", "stop loss", "entry") || hasWord("sl", "tp") -> AiIntent.TARGETS_RISK
            has("indicator", "rsi", "macd", "moving average", "ema", "sma", "adx", "bollinger") -> AiIntent.INDICATORS
            else -> AiIntent.MARKET_OVERVIEW
        }
    }

    /** Should this chat turn trigger the chart redraw overlay? */
    fun shouldRedrawChart(userText: String, autoRedrawEnabled: Boolean, intent: AiIntent): Boolean {
        if (autoRedrawEnabled) return true
        return intent == AiIntent.SUPPORT_RESISTANCE ||
            intent == AiIntent.TRENDLINE_STRUCTURE ||
            intent == AiIntent.FIBONACCI ||
            intent == AiIntent.TARGETS_RISK ||
            userText.contains("redraw", ignoreCase = true) ||
            userText.contains("draw", ignoreCase = true) ||
            userText.contains("plot", ignoreCase = true)
    }

    // ------------------------------------------------------------------
    // Response composition
    // ------------------------------------------------------------------

    fun compose(ctx: MarketAnalysisContext, intent: AiIntent, userText: String? = null): String {
        val s = ctx.signal
        val price = ctx.pair.currentPrice
        val pip = ctx.pair.pipSize
        val body = StringBuilder()

        fun priceTxt(v: Double) = TechnicalAnalysisEngine.fmtPrice(v, pip)
        fun distPips(v: Double) = if (pip > 0) String.format(Locale.US, "%.1f", abs(price - v) / pip) else "n/a"
        fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v)
        fun num(v: Double?, decimals: Int = 1) =
            if (v == null || !v.isFinite()) "n/a" else String.format(Locale.US, "%.${decimals}f", v)

        body.append(headerLine(ctx, intent))
        if (!userText.isNullOrBlank()) {
            body.append("You asked: “${userText.trim().take(160)}”\n\n")
        }

        when (intent) {
            AiIntent.SUPPORT_RESISTANCE -> {
                body.append("🪄 **LIVE LEVEL SCAN (${ctx.pair.symbol} · ${ctx.timeframe.label})**\n\n")
                body.append("Recomputed from ${ctx.candleCount} validated candles (swing detection, 2-bar confirmation):\n\n")
                if (ctx.resistances.isEmpty() && ctx.supports.isEmpty()) {
                    body.append("• No confirmed swing levels in the visible window — price is in price-discovery within the ${priceTxt(ctx.swingLow)}–${priceTxt(ctx.swingHigh)} range.\n\n")
                }
                ctx.resistances.sortedDescending().forEach { r ->
                    val room = if (ctx.swingHigh > ctx.swingLow) (r - ctx.swingLow) / (ctx.swingHigh - ctx.swingLow) * 100 else 0.0
                    body.append("• 🔴 **Resistance** `${priceTxt(r)}` — ${distPips(r)} pips away (${pct((r - price) / price * 100)} from spot")
                    if (room > 0) body.append(", sits ${pct(room)} of the tested range")
                    body.append(")\n")
                }
                ctx.supports.sortedDescending().forEach { sup ->
                    body.append("• 🟢 **Support** `${priceTxt(sup)}` — ${distPips(sup)} pips from spot (${pct((sup - price) / price * 100)})\n")
                }
                body.append("\n💡 **Execution note:** ")
                if (s.type == SignalType.BUY) {
                    body.append("prefer longs from the nearest tested demand level with ${s.volatilityRegime.label} stops (${priceTxt(s.stopLoss)}).")
                } else {
                    body.append("shorts become favorable on rejection at the mapped supply band (${priceTxt(ctx.resistances.lastOrNull() ?: ctx.swingHigh)}) — invalidation ${priceTxt(s.takeProfit1)}.")
                }
            }

            AiIntent.TRENDLINE_STRUCTURE -> {
                val stack = if (ctx.emaFast > ctx.emaSlow) "BULLISH (${ctx.timeframe.label} EMA stack intact)" else "BEARISH (fast EMA below slow)"
                body.append("📈 **DYNAMIC STRUCTURE READ**\n\n")
                body.append("• EMA vectors: fast `${priceTxt(ctx.emaFast)}` vs slow `${priceTxt(ctx.emaSlow)}` → $stack\n")
                body.append("• Swing lattice: ${ctx.supports.size} higher-lows / ${ctx.resistances.size} lower-highs detected\n")
                ctx.emaTrend?.let { trend ->
                    val side = if (price > trend) "above" else "below"
                    body.append("• Long-horizon filter (EMA 200): price is **$side** `${priceTxt(trend)}` — only trade $side-flow\n")
                }
                if (ctx.parentTrends.isNotEmpty()) {
                    body.append("• Higher-timeframe alignment: ")
                    body.append(ctx.parentTrends.joinToString(" · ") { (tf, trend) -> "$tf→$trend" })
                    body.append("\n")
                }
                body.append("\n💡 **Trendline redraw:** pivots plotted on canvas; watch the ${distPips(ctx.emaFast)}-pip gap between spot and fast EMA for mean-reversion entries.")
            }

            AiIntent.FIBONACCI -> {
                val range = (ctx.swingHigh - ctx.swingLow).coerceAtLeast(1e-9)
                val retrPct = (price - ctx.swingLow) / range * 100.0
                val golden = ctx.swingLow + range * 0.618
                body.append("📐 **FIBONACCI GRID RECOMPUTED**\n\n")
                body.append("Impulse: `${priceTxt(ctx.swingLow)}` → `${priceTxt(ctx.swingHigh)}` (range ${distPips(ctx.swingHigh)} pips).\n\n")
                listOf(
                    "23.6%" to 0.236, "38.2%" to 0.382, "50.0%" to 0.500,
                    "61.8%" to 0.618, "78.6%" to 0.786
                ).forEach { (label, ratio) ->
                    val lvl = ctx.swingLow + range * ratio
                    val proximity = if (pip > 0) abs(price - lvl) / pip else Double.MAX_VALUE
                    val tag = if (proximity < ctx.sizing.atrPips * 0.5) " ◀ price reacting here" else ""
                    body.append("• **$label**: `${priceTxt(lvl)}`$tag\n")
                }
                body.append("\nCurrent position: **${pct(retrPct)}** retraced of the swing. ")
                when {
                    retrPct >= 78.6 -> body.append("Extended — pullback risk into the golden pocket before continuation.")
                    retrPct in 55.0..75.0 -> body.append("Price is probing the Golden Pocket (61.8%) — the highest-probability reversal band; demand a ${ctx.timeframe.label} close confirmation.")
                    retrPct <= 23.6 -> body.append("Near swing origin — momentum flush; invalidation if ${priceTxt(ctx.swingLow)} loses.")
                    else -> body.append("Mid-range noise zone — wait for an edge at 38.2/61.8 before committing.")
                }
                body.append("\n\nGolden pocket sits at `${priceTxt(golden)}` (±${num(ctx.sizing.atrPips / 4)} pips tolerance).")
            }

            AiIntent.TARGETS_RISK -> {
                body.append("🎯 **ATR-SIZED EXECUTION PLAN (${ctx.pair.symbol})**\n\n")
                body.append("• **Bias:** ${s.type.title} — ${s.confidenceScore}% confidence, regime **${s.volatilityRegime.label}**\n")
                body.append("• **Entry:** `${priceTxt(s.entryPrice)}` (validated ${s.dataSource} feed)\n")
                body.append("• **Stop (SL):** `${priceTxt(s.stopLoss)}` — ${num(s.stopLossPips)} pips ≈ ${num(ctx.sizing.atrValue * ctx.sizing.regime.atrStopMultiplier / pip, 1)}×ATR cushion\n")
                body.append("• **Target 1:** `${priceTxt(s.takeProfit1)}` → **Target 2:** `${priceTxt(s.takeProfit2)}`\n")
                body.append("• **R:R:** `1:${num(s.riskRewardRatio)}`\n\n")
                body.append("⚠️ Stops are volatility-adaptive: they widen ${pct((ctx.sizing.regime.atrStopMultiplier / 1.5 - 1) * 100)} vs a NORMAL-regime baseline in current conditions. Never widen TP to preserve a dead trade.")
            }

            AiIntent.POSITION_SIZING -> {
                val sz = ctx.sizing
                body.append("🧮 **VOLATILITY POSITION SIZING (${ctx.pair.symbol})**\n\n")
                body.append("Model: 1.0% of a 10,000 quote-account risked against an ATR stop.\n\n")
                body.append("• ATR(14): **${num(sz.atrPips)} pips** → regime **${sz.regime.label}** (stop multiplier ×${num(sz.regime.atrStopMultiplier, 1)})\n")
                body.append("• Suggested stop: **${num(sz.stopLossPips)} pips** (`${priceTxt(sz.stopDistance)}` distance)\n")
                body.append("• Dollar risk budget: **${String.format(Locale.US, "%.2f", sz.riskAmount)}** ${ctx.pair.quoteCurrency}\n")
                body.append("• Per-lot risk at that stop: **${String.format(Locale.US, "%.2f", sz.stopDistance * sz.contractSize)}** ${ctx.pair.quoteCurrency}\n")
                body.append("• **Suggested size: ${num(sz.suggestedLots, 2)} standard lots** (${num(sz.suggestedLots * 10, 1)} mini / ${num(sz.suggestedLots * 100, 0)} micro)\n")
                if (sz.note.isNotBlank()) body.append("\nℹ️ ${sz.note}\n")
                body.append("\n💡 If you size manually, the Risk Calculator's fixed-pip mode should use ${num(sz.stopLossPips)}-pip stops today — the ${num(sz.atrPips)}-pip ATR makes the default 25-pip stop ")
                body.append(if (sz.stopLossPips > 25) "too tight for current volatility." else "comfortably wide.")
            }

            AiIntent.VOLATILITY -> {
                val sz = ctx.sizing
                body.append("🌪️ **VOLATILITY REGIME REPORT**\n\n")
                body.append("• ATR(14): **${num(sz.atrPips)} pips** (${priceTxt(sz.atrValue)} price units)\n")
                body.append("• Regime: **${sz.regime.label}** — derived from the percentile rank of ATR against its own ${ctx.candleCount}-bar history\n")
                ctx.bollingerWidthPercent?.let {
                    body.append("• Bollinger width: **${pct(it)}** of midline ")
                    if (it > 0 && it < 0.6) body.append("(compressing — breakout watch)") else body.append("(expanded / normal dispersion)")
                    body.append("\n")
                }
                body.append("• Session range: ${priceTxt(ctx.pair.low24h)} ↔ ${priceTxt(ctx.pair.high24h)} (${pct((ctx.pair.high24h - ctx.pair.low24h) / maxOf(ctx.pair.currentPrice, 1e-9) * 100)} of spot)\n\n")
                val advice = when (sz.regime) {
                    VolatilityRegime.EXTREME ->
                        "Halve size, or stand aside: the engine suppresses non-extreme signals here because stops must clear ${num(sz.stopLossPips)} pips."
                    VolatilityRegime.ELEVATED ->
                        "Keep 1% risk max and prefer structural stops; scalp TP1 into the widened band."
                    VolatilityRegime.COMPRESSED ->
                        "Compression precedes expansion — mark the range edges (${priceTxt(ctx.swingLow)} / ${priceTxt(ctx.swingHigh)}) and trade the break, not the chop."
                    VolatilityRegime.NORMAL ->
                        "Standard playbook; ATR-based stops (${num(VolatilityRegime.NORMAL.atrStopMultiplier, 1)}×) are appropriate."
                }
                body.append("💡 $advice")
            }

            AiIntent.INDICATORS -> {
                body.append("📊 **INDICATOR CONFLUENCE MATRIX**\n\n")
                val rsiState = when {
                    ctx.rsi < 30 -> "oversold — mean-reversion bid likely"
                    ctx.rsi < 45 -> "momentum fading, sellers marginally in control"
                    ctx.rsi <= 55 -> "neutral / indecision"
                    ctx.rsi <= 70 -> "momentum expanding, buyers in control"
                    else -> "overbought — chasing longs here is low EV"
                }
                body.append("• RSI(14): **${num(ctx.rsi)}** — $rsiState\n")
                val emaOrder = if (ctx.emaFast > ctx.emaSlow) "fast above slow (bullish stack)" else "fast below slow (bearish stack)"
                body.append("• EMA pair: `${priceTxt(ctx.emaFast)}` / `${priceTxt(ctx.emaSlow)}` — $emaOrder\n")
                body.append("• MACD: `${ctx.signal.macdStatus}`")
                ctx.macdHistogram?.let { body.append(" (hist ${if (it >= 0) "+" else ""}${String.format(Locale.US, "%.6f", it)})") }
                body.append("\n")
                body.append("• ADX(14): ${if (ctx.adx == null) "insufficient bars" else "**${num(ctx.adx)}** ${if (ctx.adx >= 25) "→ trending" else if (ctx.adx <= 18) "→ chop (signals de-rated)" else "→ transitional"}"}\n")
                if (ctx.patterns.isNotEmpty()) {
                    body.append("\n• Price-action overlays: ")
                    body.append(ctx.patterns.joinToString(", ") { "${it.patternType.title} @${priceTxt(it.priceLevel)} (${it.confidence}%)" })
                    body.append("\n")
                }
                body.append("\n💡 Composite read: **${s.type.title}** at ${s.confidenceScore}% — each layer above contributes to the signal score; missing data (null ADX/EMA200) is explicitly absent, never guessed.")
            }

            AiIntent.MARKET_OVERVIEW -> {
                body.append("🤖 **LIVE TECHNICAL BREAKDOWN — ${ctx.pair.symbol} (${ctx.timeframe.label})**\n\n")
                body.append("Data: ${ctx.candleCount} candles · source ${ctx.dataSource} · quality ${ctx.qualityScore}/100\n\n")
                body.append("1. **Structure & Bias:** ${s.type.title} (${s.confidenceScore}% conf). Swing lattice ${priceTxt(ctx.swingLow)} ↔ ${priceTxt(ctx.swingHigh)}; 24h tape ${ctx.pair.priceChange24h}%. ")
                body.append(if (ctx.patterns.isEmpty()) "No confirmed reversal clusters — consolidation regime.\n\n" else "Patterns: ${ctx.patterns.joinToString { it.patternType.title }}.\n\n")
                body.append("2. **Momentum & Volatility:** RSI ${num(ctx.rsi)}, MACD ${s.macdStatus}, ATR ${num(ctx.sizing.atrPips)}p (**${s.volatilityRegime.label}**). ")
                ctx.adx?.let { body.append("ADX ${num(it)} ${if (it >= 25) "supports trend trades" else "warns of chop"}. ") }
                body.append("\n\n3. **Levels:** ")
                body.append("Nearest demand ${if (ctx.supports.isEmpty()) "n/a" else "`${priceTxt(ctx.supports.last())}` (${distPips(ctx.supports.last())}p)"} · ")
                body.append("nearest supply ${if (ctx.resistances.isEmpty()) "n/a" else "`${priceTxt(ctx.resistances.last())}` (${distPips(ctx.resistances.last())}p)"} · ")
                body.append("EMA fast/slow ${priceTxt(ctx.emaFast)}/${priceTxt(ctx.emaSlow)}\n\n")
                body.append("4. **Plan:** entry `${priceTxt(s.entryPrice)}`, ATR stop `${priceTxt(s.stopLoss)}` (${num(s.stopLossPips)}p), TP1 `${priceTxt(s.takeProfit1)}`, TP2 `${priceTxt(s.takeProfit2)}`, R:R 1:${num(s.riskRewardRatio)}. ")
                body.append("Suggested size ${num(ctx.sizing.suggestedLots, 2)} lots at 1% risk.")
                if (ctx.parentTrends.isNotEmpty()) {
                    body.append("\n\nMulti-TF filter: ")
                    body.append(ctx.parentTrends.joinToString(" · ") { (tf, tr) -> "$tf→$tr" })
                    body.append(" — only trade with the dominant stack.")
                }
            }
        }

        val footer = "\n\n_" +
            "Generated by the local Dynamic Rule Engine v2 (ATR-aware) at ${s.confidenceScore}% conviction · " +
            "not financial advice · Configure OPENROUTER_API_KEY for LLM narrative mode._"
        return body.toString() + footer
    }

    /** Compact quant fact sheet appended to OpenRouter prompts (keeps LLM + engine in sync). */
    fun quantitativeFactSheet(ctx: MarketAnalysisContext): String {
        val s = ctx.signal
        return buildString {
            appendLine("- Price: ${ctx.pair.currentPrice} | 24h range: ${ctx.pair.low24h}-${ctx.pair.high24h} (${ctx.pair.priceChange24h}%)")
            appendLine("- Rule-engine signal: ${s.type.title} @ ${s.confidenceScore}% confidence")
            appendLine("- RSI(14): ${String.format(Locale.US, "%.1f", ctx.rsi)} | MACD: ${s.macdStatus}")
            appendLine("- EMA stack: fast ${ctx.emaFast} / slow ${ctx.emaSlow}" + (ctx.emaTrend?.let { " / trend $it" } ?: ""))
            appendLine("- ATR(14): ${String.format(Locale.US, "%.1f", ctx.sizing.atrPips)} pips | Volatility regime: ${ctx.sizing.regime.label}")
            ctx.adx?.let { appendLine("- ADX(14): ${String.format(Locale.US, "%.1f", it)}") }
            appendLine("- Swing levels: supports ${ctx.supports.joinToString() { String.format(Locale.US, "%.5f", it) }.ifBlank { "n/a" }} | resistances ${ctx.resistances.joinToString() { String.format(Locale.US, "%.5f", it) }.ifBlank { "n/a" }}")
            append("- ATR-sized plan: entry ${s.entryPrice} SL ${s.stopLoss} (${String.format(Locale.US, "%.1f", s.stopLossPips)} pips) TP1 ${s.takeProfit1} TP2 ${s.takeProfit2} RR 1:${String.format(Locale.US, "%.1f", s.riskRewardRatio)}")
        }
    }

    /** Suggested next prompts, rotated from the topic graph so chips reflect the answer given. */
    fun followUpPrompts(intent: AiIntent): List<String> {
        val all = AiIntent.values().toList()
        val start = (all.indexOf(intent) + 1).coerceAtLeast(0)
        return buildList {
            for (offset in 0 until all.size) {
                val candidate = all[(start + offset) % all.size]
                if (candidate != intent && size < 4) add(candidate.chip)
            }
        }
    }

    private fun headerLine(ctx: MarketAnalysisContext, intent: AiIntent): String {
        val stamp = String.format(Locale.US, "%s · %s · ", ctx.pair.symbol, ctx.timeframe.label)
        return "🧠 $stamp${intent.name.lowercase(Locale.US).replace('_', ' ').uppercase(Locale.US)} · data source: ${ctx.dataSource}\n\n"
    }
}
