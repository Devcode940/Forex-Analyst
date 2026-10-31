package com.example.forex.data.repository

import com.example.forex.data.db.*
import com.example.forex.data.model.*
import com.example.forex.data.remote.MarketDataHealth
import com.example.forex.data.remote.MarketDataSource
import com.example.forex.data.remote.RealMarketDataProvider
import com.example.forex.data.validation.MarketDataValidator
import com.example.forex.data.websocket.ForexWebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import com.example.forex.data.local.ForexOfflinePriceRepository
import kotlin.random.Random

class ForexRepository(
    private val forexDao: ForexDao,
    val offlinePriceRepository: ForexOfflinePriceRepository? = null
) {

    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _pairs = MutableStateFlow<List<CurrencyPair>>(createInitialPairs())
    val pairs: StateFlow<List<CurrencyPair>> = _pairs.asStateFlow()

    private val candleMap = java.util.concurrent.ConcurrentHashMap<Pair<String, Timeframe>, MutableList<CandleStick>>()

    // Real-time WebSocket Stream Manager
    val webSocketManager = ForexWebSocketManager()
    val webSocketStats: StateFlow<WebSocketStats> = webSocketManager.webSocketStats
    val webSocketStatus: StateFlow<WebSocketStatus> = webSocketManager.webSocketStatus
    val marketTicks: SharedFlow<MarketTick> = webSocketManager.marketTicks

    // Real REST market data (Alpha Vantage primary, FCS fallback)
    val realDataProvider = RealMarketDataProvider()
    val marketDataHealth: StateFlow<MarketDataHealth> = realDataProvider.health

    init {
        // Initialize candle history from offline Room repository, existing DB, or bootstrap generator
        repoScope.launch {
            _pairs.value.forEach { pair ->
                Timeframe.values().forEach { tf ->
                    val key = Pair(pair.symbol, tf)
                    // Check local offline room repository first
                    val offlineCandles = offlinePriceRepository?.getCachedCandles(pair.symbol, tf) ?: emptyList()
                    if (offlineCandles.isNotEmpty()) {
                        candleMap[key] = offlineCandles.toMutableList()
                    } else {
                        val dbCandles = forexDao.getCandles(pair.symbol, tf.name)
                        if (dbCandles.isNotEmpty()) {
                            val mapped = dbCandles.map {
                                CandleStick(it.timestamp, it.open, it.high, it.low, it.close, it.volume)
                            }.toMutableList()
                            candleMap[key] = mapped
                            offlinePriceRepository?.persistCandles(pair.symbol, tf, mapped)
                        } else {
                            val generated = generateInitialCandles(pair.currentPrice, tf)
                            candleMap[key] = generated
                            forexDao.insertCandles(generated.map {
                                CandleEntity(pair.symbol, tf.name, it.timestamp, it.open, it.high, it.low, it.close, it.volume)
                            })
                            offlinePriceRepository?.persistCandles(pair.symbol, tf, generated)
                        }
                    }
                }
            }
        }

        // Real-time WebSocket Market Tick Consumer
        repoScope.launch {
            webSocketManager.marketTicks.collect { tick ->
                applyMarketTick(tick)
            }
        }
    }

    /**
     * Applies a live market tick from WebSocket directly to currency pairs and active candles.
     *
     * Every tick first passes through the data validation layer: malformed, inverted-quote or
     * out-of-band (fat-finger / stale replay) ticks are discarded and can never corrupt
     * candles, signals or the 24h extremes shown in the UI.
     */
    private fun applyMarketTick(tick: MarketTick) {
        val referencePrice = _pairs.value.find { it.symbol == tick.symbol }?.currentPrice
        val tickReport = MarketDataValidator.validateTick(
            tick = tick,
            referencePrice = referencePrice,
            maxJumpFraction = if (PairCatalog.isCrypto(tick.symbol)) 0.15 else 0.06
        )
        if (!tickReport.isUsable) {
            return // corrupt tick — drop silently (stats counters still counted it upstream)
        }

        val currentList = _pairs.value
        var pairFound = false

        val updatedList = currentList.map { pair ->
            if (pair.symbol == tick.symbol) {
                pairFound = true
                val newPrice = tick.price
                val newHigh = max(pair.high24h, newPrice)
                val newLow = min(pair.low24h, newPrice)

                // Update latest candle for all timeframes
                val now = tick.timestamp
                Timeframe.values().forEach { tf ->
                    val key = Pair(pair.symbol, tf)
                    val candles = candleMap[key]
                    if (candles != null && candles.isNotEmpty()) {
                        val lastCandle = candles.last()
                        val tfMillis = tf.minutes * 60 * 1000L

                        // If candle period elapsed, start a new candle
                        if (now - lastCandle.timestamp >= tfMillis) {
                            val newCandle = CandleStick(
                                timestamp = (now / tfMillis) * tfMillis,
                                open = newPrice,
                                high = newPrice,
                                low = newPrice,
                                close = newPrice,
                                volume = tick.volume
                            )
                            candles.add(newCandle)
                            if (candles.size > 150) {
                                candles.removeAt(0)
                            }
                        } else {
                            // Update active candle with new high/low/close and accumulate volume
                            val updatedLast = lastCandle.copy(
                                close = newPrice,
                                high = max(lastCandle.high, newPrice),
                                low = min(lastCandle.low, newPrice),
                                volume = lastCandle.volume + tick.volume
                            )
                            candles[candles.lastIndex] = updatedLast
                        }
                    }
                }

                pair.copy(
                    currentPrice = newPrice,
                    high24h = newHigh,
                    low24h = newLow
                )
            } else {
                pair
            }
        }

        if (pairFound) {
            _pairs.value = updatedList
        }
    }


    private fun createInitialPairs(): List<CurrencyPair> {
        return listOf(
            CurrencyPair("EUR/USD", "Euro / US Dollar", PairCategory.MAJOR, "EUR", "USD", 1.0854, +0.32, 1.0885, 1.0820, 0.0001, true),
            CurrencyPair("GBP/USD", "British Pound / US Dollar", PairCategory.MAJOR, "GBP", "USD", 1.2940, +0.54, 1.2980, 1.2890, 0.0001, true),
            CurrencyPair("USD/JPY", "US Dollar / Japanese Yen", PairCategory.MAJOR, "USD", "JPY", 154.25, -0.41, 155.10, 153.80, 0.01, true),
            CurrencyPair("AUD/USD", "Australian Dollar / US Dollar", PairCategory.MAJOR, "AUD", "USD", 0.6580, +0.18, 0.6610, 0.6550, 0.0001, false),
            CurrencyPair("USD/CAD", "US Dollar / Canadian Dollar", PairCategory.MAJOR, "USD", "CAD", 1.3780, -0.12, 1.3820, 1.3750, 0.0001, false),
            CurrencyPair("EUR/JPY", "Euro / Japanese Yen", PairCategory.MINOR, "EUR", "JPY", 167.42, -0.15, 168.10, 166.90, 0.01, false),
            CurrencyPair("GBP/JPY", "British Pound / Japanese Yen", PairCategory.MINOR, "GBP", "JPY", 199.60, +0.28, 200.20, 198.90, 0.01, false),
            CurrencyPair("XAU/USD", "Gold / US Dollar", PairCategory.METALS_CRYPTO, "XAU", "USD", 2385.50, +1.15, 2398.00, 2362.00, 0.01, true),
            CurrencyPair("BTC/USD", "Bitcoin / US Dollar", PairCategory.METALS_CRYPTO, "BTC", "USD", 67450.00, +2.45, 68200.00, 65800.00, 1.0, true)
        )
    }

    private fun generateInitialCandles(basePrice: Double, tf: Timeframe): MutableList<CandleStick> {
        val list = mutableListOf<CandleStick>()
        var current = basePrice * 0.985
        val now = System.currentTimeMillis()
        val tfMillis = tf.minutes * 60 * 1000L
        val count = 60

        for (i in count downTo 0) {
            val time = now - (i * tfMillis)
            val volatility = basePrice * 0.003
            val change = Random.nextDouble(-volatility, volatility)
            val open = current
            val close = open + change
            val high = maxOf(open, close) + Random.nextDouble(0.0, volatility * 0.5)
            val low = minOf(open, close) - Random.nextDouble(0.0, volatility * 0.5)
            val volume = Random.nextDouble(500.0, 5000.0)

            list.add(CandleStick(time, open, high, low, close, volume))
            current = close
        }
        return list
    }

    fun getCandles(symbol: String, timeframe: Timeframe): List<CandleStick> {
        val key = Pair(symbol, timeframe)
        if (!candleMap.containsKey(key)) {
            val pair = _pairs.value.find { it.symbol == symbol }
            val price = pair?.currentPrice ?: 1.0
            val generated = generateInitialCandles(price, timeframe)
            candleMap[key] = generated

            repoScope.launch {
                val refreshed = refreshCandlesFromNetwork(symbol, timeframe)
                if (refreshed != null) {
                    candleMap[key] = refreshed.first.toMutableList()
                    forexDao.insertCandles(refreshed.first.map {
                        CandleEntity(symbol, timeframe.name, it.timestamp, it.open, it.high, it.low, it.close, it.volume)
                    })
                    offlinePriceRepository?.persistCandles(symbol, timeframe, refreshed.first)
                    // Trigger UI update
                    _pairs.value = _pairs.value.toList()
                } else {
                    forexDao.insertCandles(generated.map {
                        CandleEntity(symbol, timeframe.name, it.timestamp, it.open, it.high, it.low, it.close, it.volume)
                    })
                    offlinePriceRepository?.persistCandles(symbol, timeframe, generated)
                }
            }
        }
        return candleMap[key] ?: emptyList()
    }

    /**
     * Validated network refresh for a symbol/timeframe used by the UI, pull-to-refresh and
     * [com.example.forex.worker.SignalWorker].
     *
     * Chain: Alpha Vantage (if configured; already validated inside the provider) ->
     * FCS API (validated here) -> null, letting callers fall back to persisted/simulated data.
     * Never throws: upstream failures are collapsed into a null result and surfaced via
     * [marketDataHealth].
     */
    suspend fun refreshCandlesFromNetwork(
        symbol: String,
        timeframe: Timeframe,
        forceRefresh: Boolean = false
    ): Pair<List<CandleStick>, MarketDataSource>? {
        val normalized = PairCatalog.definitionFor(symbol).symbol

        if (realDataProvider.isConfigured()) {
            val avResult = realDataProvider.fetchCandles(normalized, timeframe, forceRefresh)
            val avCandles = avResult.getOrNull()
            if (avCandles != null && avCandles.isNotEmpty()) {
                return avCandles to MarketDataSource.ALPHA_VANTAGE
            }
        }

        if (marketDataService.isConfigured()) {
            try {
                val tfStr = when (timeframe) {
                    Timeframe.M1 -> "1m"
                    Timeframe.M5 -> "5m"
                    Timeframe.M15 -> "15m"
                    Timeframe.H1 -> "1h"
                    Timeframe.H4 -> "4h"
                    Timeframe.D1 -> "1d"
                }
                val apiSymbol = normalized.replace("/", "")
                val result = marketDataService.fetchHistoricalCandles(apiSymbol, tfStr)
                if (result.isSuccess) {
                    val rawCandles = result.getOrNull().orEmpty().mapNotNull { c ->
                        val o = c.o.toDoubleOrNull() ?: return@mapNotNull null
                        val h = c.h.toDoubleOrNull() ?: return@mapNotNull null
                        val l = c.l.toDoubleOrNull() ?: return@mapNotNull null
                        val cl = c.c.toDoubleOrNull() ?: return@mapNotNull null
                        CandleStick(
                            timestamp = c.t?.toLongOrNull()?.times(1000) ?: return@mapNotNull null,
                            open = o,
                            high = h,
                            low = l,
                            close = cl,
                            volume = c.v?.toDoubleOrNull()?.takeIf { v -> v.isFinite() && v >= 0 } ?: 0.0
                        )
                    }
                    if (rawCandles.isNotEmpty()) {
                        val validation = MarketDataValidator.validateCandleSeries(
                            series = rawCandles,
                            timeframeMillis = timeframe.minutes * 60_000L,
                            minValidCount = 24
                        )
                        if (validation.report.isUsable) {
                            return validation.candles to MarketDataSource.FCS_API
                        }
                    }
                }
            } catch (e: Exception) {
                // FCS failure is non-fatal: callers fall back to cache/simulation.
                e.printStackTrace()
            }
        }
        return null
    }

    private val marketDataService = ForexMarketRepositoryService()

    /**
     * Pushes a freshly validated candle series (from any source) into the live cache and
     * Room, then nudges [pairs] so signal regeneration sees the new history immediately.
     */
    fun ingestCandles(symbol: String, timeframe: Timeframe, candles: List<CandleStick>) {
        if (candles.isEmpty()) return
        val trimmed = candles.takeLast(200)
        candleMap[Pair(symbol, timeframe)] = trimmed.toMutableList()
        repoScope.launch {
            forexDao.insertCandles(trimmed.map {
                CandleEntity(symbol, timeframe.name, it.timestamp, it.open, it.high, it.low, it.close, it.volume)
            })
            offlinePriceRepository?.persistCandles(symbol, timeframe, trimmed)
        }
        _pairs.value = _pairs.value.toList()
    }

    /** Force-refreshes a symbol's candles from the network and ingests the result. */
    suspend fun refreshMarketSnapshot(symbol: String, timeframe: Timeframe): Boolean {
        val refreshed = refreshCandlesFromNetwork(symbol, timeframe, forceRefresh = true) ?: return false
        ingestCandles(symbol, timeframe, refreshed.first)
        return true
    }

    /**
     * Pulls a realtime spot quote (Alpha Vantage CURRENCY_EXCHANGE_RATE) for [symbol] and,
     * after validation, applies it to the live pair state. Used by manual refresh actions
     * when the WebSocket simulator is paused or unavailable.
     */
    suspend fun refreshLatestQuoteFor(symbol: String): Boolean {
        val tick = realDataProvider.fetchLatestQuote(symbol).getOrNull() ?: return false
        val report = MarketDataValidator.validateTick(
            tick = tick,
            referencePrice = _pairs.value.find { it.symbol == symbol }?.currentPrice,
            maxJumpFraction = if (PairCatalog.isCrypto(symbol)) 0.15 else 0.06
        )
        if (!report.isUsable) return false
        _pairs.value = _pairs.value.map { pair ->
            if (pair.symbol == symbol) {
                pair.copy(
                    currentPrice = tick.price,
                    high24h = max(pair.high24h, tick.price),
                    low24h = min(pair.low24h, tick.price)
                )
            } else pair
        }
        return true
    }

    private val openRouterRetrofit = retrofit2.Retrofit.Builder()
        .baseUrl("https://openrouter.ai/")
        .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create())
        .build()

    private val openRouterApi = openRouterRetrofit.create(OpenRouterApi::class.java)

    /**
     * Requests a live LLM analysis from OpenRouter.
     *
     * @param userQuestion when set (chat mode), the analyst prompt is framed around the
     *   trader's actual question instead of the generic report template.
     * @param quantitativeContext a deterministic, engine-computed fact sheet (ATR, regime,
     *   ADX, EMA stack, key levels…) appended to every prompt so the LLM reasons over the
     *   same validated numbers the local rule engine uses.
     *
     * Returns null on any failure (missing key, rate limit, empty completion) so the
     * ViewModel can fall back to the dynamic local engine without the UI noticing.
     */
    suspend fun fetchOpenRouterAnalysis(
        symbol: String,
        timeframe: String,
        currentPrice: Double,
        signalType: String,
        rsiValue: Double,
        patterns: String,
        isSnapshotAnalysis: Boolean = false,
        activeOverlays: String = "",
        userQuestion: String? = null,
        quantitativeContext: String = ""
    ): String? {
        val apiKey = com.example.BuildConfig.OPENROUTER_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_OPENROUTER_API_KEY") {
            return null
        }

        return try {
            val contextBlock = if (quantitativeContext.isNotBlank()) {
                "\n- Deterministic Indicator Readings (already computed from validated candles; trust these numbers):\n$quantitativeContext\n"
            } else ""
            val questionBlock = if (!userQuestion.isNullOrBlank()) {
                "\nTRADER QUESTION TO ANSWER: \"${userQuestion.take(600)}\"\nAddress this question directly in your answer.\n"
            } else ""

            val prompt = if (isSnapshotAnalysis) {
                """
                You are a senior institutional Forex & Crypto technical analyst performing a MANUAL MARKET CONFIRMATION on a captured chart snapshot.
                Analyze the captured snapshot chart state for $symbol:
                - Timeframe: $timeframe
                - Current Price at Snapshot: $currentPrice
                - Technical Signal Bias: $signalType
                - 14-period RSI: $rsiValue
                - Active Chart Overlays & Technical Indicators: $activeOverlays
                - Chart Patterns Detected: $patterns
                $contextBlock
                $questionBlock
                Please generate a structured, rigorous MANUAL MARKET CONFIRMATION REPORT with the following sections:
                1. 📸 CAPTURED SNAPSHOT STRUCTURE & KEY LEVEL CONFIRMATION
                2. 🔍 MANUAL CHECKLIST (Candle Rejection, RSI Confluence, Moving Average Dynamic Levels)
                3. ⚠️ RISK & MANIPULATION WARNINGS (Liquidity Sweeps, News Spikes)
                4. 🏁 MANUAL TRADER VERDICT (Strong Buy, Wait for Confirmation, Invalid Setup, or Sell)
                """.trimIndent()
            } else {
                """
                You are a senior professional Forex & Crypto technical analyst.
                Analyze the following live market data for $symbol:
                - Timeframe: $timeframe
                - Current Price: $currentPrice
                - Technical Signal Bias: $signalType
                - 14-period RSI: $rsiValue
                - Chart Patterns Detected: $patterns
                $contextBlock
                $questionBlock
                Please generate a structured, concise report with 4 sections:
                1. MARKET STRUCTURE & BIAS
                2. INDICATOR CONFLUENCE
                3. DETECTED PATTERNS & PRICE ACTION
                4. STRATEGY EXECUTION PLAN (Entry, Stop Loss, Take Profit 1 & 2, Risk/Reward)
                """.trimIndent()
            }

            val request = OpenRouterRequest(
                model = "mistralai/mistral-large",
                messages = listOf(OpenRouterMessage(role = "user", content = prompt))
            )

            val response = openRouterApi.generateCompletion(
                authHeader = "Bearer $apiKey",
                request = request
            )

            if (response.isSuccessful) {
                val content = response.body()?.choices?.firstOrNull()?.message?.content
                if (!content.isNullOrBlank()) {
                    "🌐 LIVE OPENROUTER AI REPORT (${request.model})\n\n$content"
                } else null
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    /**
     * Real-time tick stream: Emits real-time pair state driven by WebSocket ticks
     */
    fun startLivePriceStream(): Flow<List<CurrencyPair>> = _pairs.asStateFlow()

    fun reconnectWebSocket() {
        webSocketManager.reconnect()
    }

    fun disconnectWebSocket() {
        webSocketManager.disconnect()
    }


    // Room DB methods
    val savedSignals: Flow<List<SignalEntity>> = forexDao.getAllSavedSignals()
    val customAlerts: Flow<List<AlertEntity>> = forexDao.getAllAlerts()
    val watchlist: Flow<List<WatchlistEntity>> = forexDao.getWatchlist()

    suspend fun saveSignal(signal: TradeSignal) {
        forexDao.insertSignal(
            SignalEntity(
                id = signal.id,
                pairSymbol = signal.pairSymbol,
                signalType = signal.type.name,
                entryPrice = signal.entryPrice,
                stopLoss = signal.stopLoss,
                takeProfit1 = signal.takeProfit1,
                takeProfit2 = signal.takeProfit2,
                riskRewardRatio = signal.riskRewardRatio,
                confidenceScore = signal.confidenceScore,
                timeframeLabel = signal.timeframe.label,
                summaryRationale = signal.summaryRationale,
                timestamp = signal.timestamp,
                atrValue = signal.atrValue,
                stopLossPips = signal.stopLossPips,
                volatilityRegime = signal.volatilityRegime.name,
                suggestedLotSize = signal.suggestedLotSize,
                dataQualityScore = signal.dataQualityScore,
                dataSource = signal.dataSource
            )
        )
    }

    suspend fun deleteSavedSignal(id: String) {
        forexDao.deleteSignal(id)
    }

    suspend fun createAlert(alert: CustomAlert) {
        forexDao.insertAlert(
            AlertEntity(
                id = alert.id,
                pairSymbol = alert.pairSymbol,
                conditionType = alert.condition.name,
                targetValue = alert.targetValue,
                isTriggered = alert.isTriggered,
                isActive = alert.isActive,
                note = alert.note,
                createdAt = alert.createdAt
            )
        )
    }

    suspend fun deleteAlert(id: String) {
        forexDao.deleteAlert(id)
    }

    suspend fun toggleAlert(id: String, active: Boolean) {
        forexDao.updateAlertStatus(id, triggered = false, active = active)
    }

    suspend fun addToWatchlist(symbol: String) {
        forexDao.addToWatchlist(WatchlistEntity(symbol = symbol))
    }

    suspend fun removeFromWatchlist(symbol: String) {
        forexDao.removeFromWatchlist(symbol)
    }

    // Indicator Settings Persistence
    val savedIndicatorSettings: Flow<IndicatorSettings?> = forexDao.getUserSettings().map { entity ->
        entity?.let {
            IndicatorSettings(
                showSma20 = it.showSma20,
                showSma50 = it.showSma50,
                showEma20 = it.showEma20,
                showEma50 = it.showEma50,
                showEma200 = it.showEma200,
                showBollingerBands = it.showBollingerBands,
                showSupportResistance = it.showSupportResistance,
                showRsiSubchart = it.showRsiSubchart,
                showMacdSubchart = it.showMacdSubchart,
                showPatterns = it.showPatterns,
                smaPeriod1 = it.smaPeriod1,
                smaPeriod2 = it.smaPeriod2,
                emaPeriod1 = it.emaPeriod1,
                emaPeriod2 = it.emaPeriod2,
                emaPeriod3 = it.emaPeriod3,
                rsiPeriod = it.rsiPeriod,
                rsiOverbought = it.rsiOverbought,
                rsiOversold = it.rsiOversold,
                macdFastPeriod = it.macdFastPeriod,
                macdSlowPeriod = it.macdSlowPeriod,
                macdSignalPeriod = it.macdSignalPeriod,
                bollingerPeriod = it.bollingerPeriod,
                bollingerStdDev = it.bollingerStdDev
            )
        }
    }

    suspend fun saveIndicatorSettings(settings: IndicatorSettings) {
        forexDao.saveUserSettings(
            UserSettingsEntity(
                id = "current_settings",
                showSma20 = settings.showSma20,
                showSma50 = settings.showSma50,
                showEma20 = settings.showEma20,
                showEma50 = settings.showEma50,
                showEma200 = settings.showEma200,
                showBollingerBands = settings.showBollingerBands,
                showSupportResistance = settings.showSupportResistance,
                showRsiSubchart = settings.showRsiSubchart,
                showMacdSubchart = settings.showMacdSubchart,
                showPatterns = settings.showPatterns,
                smaPeriod1 = settings.smaPeriod1,
                smaPeriod2 = settings.smaPeriod2,
                emaPeriod1 = settings.emaPeriod1,
                emaPeriod2 = settings.emaPeriod2,
                emaPeriod3 = settings.emaPeriod3,
                rsiPeriod = settings.rsiPeriod,
                rsiOverbought = settings.rsiOverbought,
                rsiOversold = settings.rsiOversold,
                macdFastPeriod = settings.macdFastPeriod,
                macdSlowPeriod = settings.macdSlowPeriod,
                macdSignalPeriod = settings.macdSignalPeriod,
                bollingerPeriod = settings.bollingerPeriod,
                bollingerStdDev = settings.bollingerStdDev
            )
        )
    }
}
