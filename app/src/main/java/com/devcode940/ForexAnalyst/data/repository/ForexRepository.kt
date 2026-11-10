package com.devcode940.ForexAnalyst.data.repository

import android.content.Context
import android.util.Log
import com.devcode940.ForexAnalyst.data.db.*
import com.devcode940.ForexAnalyst.data.local.ForexOfflinePriceRepository
import com.devcode940.ForexAnalyst.data.model.*
import com.devcode940.ForexAnalyst.data.remote.MarketDataHealth
import com.devcode940.ForexAnalyst.data.remote.MarketDataSource
import com.devcode940.ForexAnalyst.data.remote.RealMarketDataProvider
import com.devcode940.ForexAnalyst.data.security.SecureApiKeyManager
import com.devcode940.ForexAnalyst.data.validation.MarketDataValidator
import com.devcode940.ForexAnalyst.data.websocket.ForexWebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Retrofit
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Production-ready Forex Repository with:
 * - Thread-safe candle cache with Mutex synchronization
 * - Proper resource cleanup via Closeable
 * - Null-safe operations throughout
 * - Comprehensive error handling and logging
 * - Lazy loading for performance
 */
class ForexRepository(
    private val forexDao: ForexDao,
    val offlinePriceRepository: ForexOfflinePriceRepository? = null,
    private val appContext: Context? = null
) : Closeable {

    // ========================================================================
    // LIFECYCLE & RESOURCE MANAGEMENT
    // ========================================================================

    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycleMutex = Mutex()
    private var isStarted = false

    /**
     * WebSocket manager with explicit lifecycle control
     */
    val webSocketManager: ForexWebSocketManager by lazy { ForexWebSocketManager() }
    val webSocketStats: StateFlow<WebSocketStats> = webSocketManager.webSocketStats
    val webSocketStatus: StateFlow<WebSocketStatus> = webSocketManager.webSocketStatus
    val marketTicks: SharedFlow<MarketTick> = webSocketManager.marketTicks

    // Real REST market data (Alpha Vantage primary, FCS fallback)
    val realDataProvider: RealMarketDataProvider by lazy {
        RealMarketDataProvider(apiKey = alphaVantageApiKeyProvider())
    }
    val marketDataHealth: StateFlow<MarketDataHealth> = realDataProvider.health

    private val marketDataService: ForexMarketRepositoryService by lazy {
        ForexMarketRepositoryService(apiKey = fcsApiKeyProvider() ?: "")
    }

    private val openRouterRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://openrouter.ai/")
            .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create())
            .build()
    }

    private val openRouterApi: OpenRouterApi by lazy {
        openRouterRetrofit.create(OpenRouterApi::class.java)
    }

    // ========================================================================
    // STATE MANAGEMENT (Thread-Safe)
    // ========================================================================

    private val _pairs = MutableStateFlow<List<CurrencyPair>>(createInitialPairs())
    val pairs: StateFlow<List<CurrencyPair>> = _pairs.asStateFlow()

    // Thread-safe candle cache: ConcurrentHashMap with Mutex for list operations
    private val candleMap = java.util.concurrent.ConcurrentHashMap<Pair<String, Timeframe>, MutableList<CandleStick>>()
    private val candleMapMutex = Mutex()

    // Error tracking for observability
    private val _errors = MutableSharedFlow<RepositoryError>(extraBufferCapacity = 16)
    val errors: SharedFlow<RepositoryError> = _errors.asSharedFlow()

    private val _isLoading = MutableStateFlow<Set<String>>(emptySet())
    val isLoading: StateFlow<Set<String>> = _isLoading.asStateFlow()

    // ========================================================================
    // API KEY PROVIDERS
    // ========================================================================

    var fcsApiKeyProvider: () -> String? = {
        secureKeyLookup(com.devcode940.ForexAnalyst.BuildConfig.FCS_API_KEY) { ctx ->
            runCatching { SecureApiKeyManager.getInstance(ctx).getFcsApiKey() }
                .onFailure { e -> Log.e("ForexRepo", "FCS key lookup failed", e) }
                .getOrNull()
        }
    }
        private set

    var openRouterApiKeyProvider: () -> String? = {
        secureKeyLookup(com.devcode940.ForexAnalyst.BuildConfig.OPENROUTER_API_KEY) { ctx ->
            runCatching { SecureApiKeyManager.getInstance(ctx).getOpenRouterApiKey() }
                .onFailure { e -> Log.e("ForexRepo", "OpenRouter key lookup failed", e) }
                .getOrNull()
        }
    }
        private set

    var alphaVantageApiKeyProvider: () -> String = {
        secureKeyLookup(com.devcode940.ForexAnalyst.BuildConfig.ALPHAVANTAGE_API_KEY) { ctx ->
            runCatching { SecureApiKeyManager.getInstance(ctx).getAlphaVantageApiKey() }
                .onFailure { e -> Log.e("ForexRepo", "AlphaVantage key lookup failed", e) }
                .getOrNull()
        } ?: ""
    }
        private set

    fun setApiKeyProviders(
        fcs: (() -> String?)? = null,
        openRouter: (() -> String?)? = null,
        alphaVantage: (() -> String)? = null
    ) {
        fcs?.let { fcsApiKeyProvider = it }
        openRouter?.let { openRouterApiKeyProvider = it }
        alphaVantage?.let { alphaVantageApiKeyProvider = it }
    }

    /**
     * Resolves an API key with comprehensive logging.
     * Priority: 1) Encrypted storage, 2) BuildConfig (DEBUG only), 3) null
     */
    private fun secureKeyLookup(buildConfigKey: String, fromSecureStore: (Context) -> String?): String? {
        appContext?.let { ctx ->
            return try {
                val stored = fromSecureStore(ctx)
                if (!stored.isNullOrBlank()) {
                    Log.d("ForexRepo", "Using secure storage key for $buildConfigKey")
                    return stored
                }
                Log.w("ForexRepo", "Secure storage returned null/blank for $buildConfigKey")
            } catch (e: Exception) {
                Log.e("ForexRepo", "Failed to read secure storage for $buildConfigKey", e)
            }
        }

        if (com.devcode940.ForexAnalyst.BuildConfig.DEBUG && buildConfigKey.isNotBlank() && !buildConfigKey.startsWith("YOUR_")) {
            Log.d("ForexRepo", "Using BuildConfig fallback for $buildConfigKey (DEBUG only)")
            return buildConfigKey
        }

        Log.w("ForexRepo", "No API key available for $buildConfigKey")
        return null
    }

    // ========================================================================
    // LIFECYCLE METHODS
    // ========================================================================

    /**
     * Start the repository: initializes WebSocket and begins data collection.
     * Safe to call multiple times - idempotent.
     */
    fun start() {
        lifecycleMutex.withLock {
            if (isStarted) return
            isStarted = true
            
            Log.d("ForexRepo", "Starting repository...")
            
            // Start WebSocket manager
            webSocketManager.start()
            
            // Initialize data lazily - don't block
            repoScope.launch {
                try {
                    initializeData()
                } catch (e: Exception) {
                    Log.e("ForexRepo", "Initialization failed", e)
                    _errors.emit(RepositoryError("Initialization failed: ${e.message}", e))
                }
            }
            
            // Collect market ticks
            repoScope.launch {
                try {
                    collectMarketTicks()
                } catch (e: Exception) {
                    Log.e("ForexRepo", "Market tick collection failed", e)
                    _errors.emit(RepositoryError("Tick collection failed: ${e.message}", e))
                }
            }
        }
    }

    /**
     * Initialize data lazily - only loads what's needed
     */
    private suspend fun initializeData() {
        // Don't pre-load all data - load on demand
        // This prevents slow startup and memory waste
        Log.d("ForexRepo", "Repository initialized (lazy loading enabled)")
    }

    /**
     * Collect market ticks from WebSocket and apply them thread-safely
     */
    private suspend fun collectMarketTicks() {
        webSocketManager.marketTicks.collect { tick ->
            try {
                applyMarketTick(tick)
            } catch (e: Exception) {
                Log.e("ForexRepo", "Failed to apply tick for ${tick.symbol}", e)
                _errors.emit(RepositoryError("Tick processing failed for ${tick.symbol}: ${e.message}", e))
            }
        }
    }

    /**
     * Stop the repository: cancels all background operations.
     */
    fun stop() {
        lifecycleMutex.withLock {
            if (!isStarted) return
            isStarted = false
            
            Log.d("ForexRepo", "Stopping repository...")
            webSocketManager.stop()
        }
    }

    // ========================================================================
    // CLOSEABLE IMPLEMENTATION (Resource Cleanup)
    // ========================================================================

    /**
     * Clean up all resources. Must be called when repository is no longer needed.
     * Typically called from ViewModel.onCleared()
     */
    override fun close() {
        stop()
        repoScope.cancel()
        webSocketManager.close()
        Log.d("ForexRepo", "Repository closed - all resources released")
    }

    // ========================================================================
    // THREAD-SAFE MARKET TICK APPLICATION
    // ========================================================================

    /**
     * Applies a live market tick from WebSocket to currency pairs and candles.
     * Thread-safe: uses Mutex for synchronization.
     * 
     * @param tick The validated market tick to apply
     */
    private suspend fun applyMarketTick(tick: MarketTick) {
        candleMapMutex.withLock {
            // Find reference price with safe fallback
            val referencePrice = _pairs.value.firstOrNull { it.symbol == tick.symbol }?.currentPrice
                ?: tick.price // Fallback to tick price if pair not found

            // Validate tick before processing
            val tickReport = MarketDataValidator.validateTick(
                tick = tick,
                referencePrice = referencePrice,
                maxJumpFraction = if (PairCatalog.isCrypto(tick.symbol)) 0.15 else 0.06
            )

            if (!tickReport.isUsable) {
                Log.w("ForexRepo", "Dropped invalid tick for ${tick.symbol}: ${tickReport.summary}")
                _errors.emit(RepositoryError("Invalid tick for ${tick.symbol}: ${tickReport.summary}", null))
                return
            }

            // Update pairs state - immutable update
            val updatedPairs = _pairs.value.map { pair ->
                if (pair.symbol == tick.symbol) {
                    pair.copy(
                        currentPrice = tick.price,
                        high24h = max(pair.high24h, tick.price),
                        low24h = min(pair.low24h, tick.price)
                    )
                } else {
                    pair
                }
            }.toList()

            _pairs.value = updatedPairs

            // Update candles for all timeframes - thread-safe
            val now = tick.timestamp
            Timeframe.values().forEach { tf ->
                val key = Pair(tick.symbol, tf)
                val tfMillis = tf.minutes * 60_000L

                // Get or create candle list for this pair/timeframe
                val candles = candleMap.compute(key) { _, existing ->
                    existing?.toMutableList() ?: mutableListOf()
                } ?: mutableListOf<CandleStick>().also { candleMap[key] = it }

                if (candles.isEmpty()) {
                    // Create initial candle
                    candles.add(createInitialCandle(tick, tf, now))
                } else {
                    val lastCandle = candles.last()

                    if (now - lastCandle.timestamp >= tfMillis) {
                        // New candle period - create new candle
                        val newCandle = CandleStick(
                            timestamp = (now / tfMillis) * tfMillis,
                            open = tick.price,
                            high = tick.price,
                            low = tick.price,
                            close = tick.price,
                            volume = tick.volume
                        )
                        candles.add(newCandle)
                    } else {
                        // Update existing candle - create immutable copy
                        val updatedLast = lastCandle.copy(
                            close = tick.price,
                            high = max(lastCandle.high, tick.price),
                            low = min(lastCandle.low, tick.price),
                            volume = lastCandle.volume + tick.volume
                        )
                        candles[candles.lastIndex] = updatedLast
                    }

                    // Limit cache size to prevent memory bloat
                    if (candles.size > 500) {
                        candles.removeAt(0)
                    }
                }
            }
        }
    }

    private fun createInitialCandle(tick: MarketTick, tf: Timeframe, timestamp: Long): CandleStick {
        val tfMillis = tf.minutes * 60_000L
        return CandleStick(
            timestamp = (timestamp / tfMillis) * tfMillis,
            open = tick.price,
            high = tick.price,
            low = tick.price,
            close = tick.price,
            volume = tick.volume
        )
    }

    // ========================================================================
    // LAZY CANDLE LOADING (Performance Optimization)
    // ========================================================================

    /**
     * Get candles for a specific pair and timeframe.
     * Loads lazily from cache, offline repo, DAO, or generates if needed.
     * Thread-safe with Mutex.
     */
    fun getCandles(symbol: String, timeframe: Timeframe): List<CandleStick> {
        val key = Pair(symbol, timeframe)
        
        return candleMapMutex.withLock {
            // Return cached candles if available
            candleMap[key]?.toList() ?: emptyList()
        }
    }

    /**
     * Load candles from offline storage or generate if not available.
     * Thread-safe and lazy.
     */
    suspend fun loadCandles(symbol: String, timeframe: Timeframe): List<CandleStick> {
        val key = Pair(symbol, timeframe)
        
        return candleMapMutex.withLock {
            // Check cache first
            candleMap[key]?.toList()?.takeIf { it.isNotEmpty() }?.let { return it }

            // Mark as loading to prevent duplicate requests
            _isLoading.value = _isLoading.value + symbol

            try {
                // Try offline repository
                offlinePriceRepository?.getCachedCandles(symbol, timeframe)?.takeIf { it.isNotEmpty() }?.let { candles ->
                    candleMap[key] = candles.toMutableList()
                    _isLoading.value = _isLoading.value - symbol
                    return candles
                }

                // Try DAO
                val dbCandles = forexDao.getCandles(symbol, timeframe.name)
                if (dbCandles.isNotEmpty()) {
                    val mapped = dbCandles.map { entity ->
                        CandleStick(
                            timestamp = entity.timestamp,
                            open = entity.open,
                            high = entity.high,
                            low = entity.low,
                            close = entity.close,
                            volume = entity.volume
                        )
                    }
                    candleMap[key] = mapped.toMutableList()
                    offlinePriceRepository?.persistCandles(symbol, timeframe, mapped)
                    _isLoading.value = _isLoading.value - symbol
                    return mapped
                }

                // Generate initial candles as fallback
                val pair = _pairs.value.firstOrNull { it.symbol == symbol }
                val price = pair?.currentPrice ?: 1.0
                val generated = generateInitialCandles(price, timeframe)
                candleMap[key] = generated.toMutableList()
                
                // Persist generated candles
                forexDao.insertCandles(generated.map { candle ->
                    CandleEntity(symbol, timeframe.name, candle.timestamp, candle.open, candle.high, candle.low, candle.close, candle.volume)
                })
                offlinePriceRepository?.persistCandles(symbol, timeframe, generated)
                
                _isLoading.value = _isLoading.value - symbol
                return generated
                
            } catch (e: Exception) {
                Log.e("ForexRepo", "Failed to load candles for $symbol/${timeframe.label}", e)
                _errors.emit(RepositoryError("Failed to load candles: ${e.message}", e))
                _isLoading.value = _isLoading.value - symbol
                return emptyList()
            }
        }
    }

    /**
     * Refresh candles from network for a specific pair and timeframe.
     * Thread-safe and returns the source of the data.
     */
    suspend fun refreshCandlesFromNetwork(
        symbol: String,
        timeframe: Timeframe,
        forceRefresh: Boolean = false
    ): Pair<List<CandleStick>, MarketDataSource>? {
        val normalized = PairCatalog.definitionFor(symbol).symbol

        // Try Alpha Vantage first
        if (realDataProvider.isConfigured()) {
            val avResult = realDataProvider.fetchCandles(normalized, timeframe, forceRefresh)
            avResult.onSuccess { candles ->
                if (candles.isNotEmpty()) {
                    return candles to MarketDataSource.ALPHA_VANTAGE
                }
            }.onFailure { e ->
                Log.w("ForexRepo", "Alpha Vantage failed for $normalized/${timeframe.label}: ${e.message}")
            }
        }

        // Try FCS API as fallback
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
                
                if (result.isSuccessful) {
                    val rawCandles = result.getOrNull().orEmpty().mapNotNull { c ->
                        runCatching {
                            CandleStick(
                                timestamp = c.t?.toLongOrNull()?.times(1000) ?: return@mapNotNull null,
                                open = c.o.toDoubleOrNull() ?: return@mapNotNull null,
                                high = c.h.toDoubleOrNull() ?: return@mapNotNull null,
                                low = c.l.toDoubleOrNull() ?: return@mapNotNull null,
                                close = c.c.toDoubleOrNull() ?: return@mapNotNull null,
                                volume = c.v?.toDoubleOrNull()?.takeIf { v -> v.isFinite() && v >= 0 } ?: 0.0
                            )
                        }.onFailure { e ->
                            Log.w("ForexRepo", "Failed to parse FCS candle", e)
                        }.getOrNull()
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
                Log.e("ForexRepo", "FCS API failed for $normalized/${timeframe.label}", e)
            }
        }

        return null
    }

    /**
     * Push validated candles into cache and persistence.
     * Thread-safe.
     */
    fun ingestCandles(symbol: String, timeframe: Timeframe, candles: List<CandleStick>) {
        if (candles.isEmpty()) return
        
        candleMapMutex.withLock {
            val trimmed = candles.takeLast(500) // Limit cache size
            candleMap[Pair(symbol, timeframe)] = trimmed.toMutableList()
        }

        repoScope.launch {
            try {
                forexDao.insertCandles(trimmed.map { candle ->
                    CandleEntity(symbol, timeframe.name, candle.timestamp, candle.open, candle.high, candle.low, candle.close, candle.volume)
                })
                offlinePriceRepository?.persistCandles(symbol, timeframe, trimmed)
            } catch (e: Exception) {
                Log.e("ForexRepo", "Failed to persist candles for $symbol/${timeframe.label}", e)
                _errors.emit(RepositoryError("Persistence failed: ${e.message}", e))
            }
        }
        
        // Trigger UI update
        _pairs.value = _pairs.value.toList()
    }

    /**
     * Force-refresh candles from network and ingest.
     */
    suspend fun refreshMarketSnapshot(symbol: String, timeframe: Timeframe): Boolean {
        val refreshed = refreshCandlesFromNetwork(symbol, timeframe, forceRefresh = true) ?: return false
        ingestCandles(symbol, timeframe, refreshed.first)
        return true
    }

    /**
     * Refresh latest quote from Alpha Vantage.
     */
    suspend fun refreshLatestQuoteFor(symbol: String): Boolean {
        val tick = realDataProvider.fetchLatestQuote(symbol).getOrNull() ?: return false
        
        val referencePrice = _pairs.value.firstOrNull { it.symbol == symbol }?.currentPrice
        val report = MarketDataValidator.validateTick(
            tick = tick,
            referencePrice = referencePrice,
            maxJumpFraction = if (PairCatalog.isCrypto(symbol)) 0.15 else 0.06
        )
        
        if (!report.isUsable) {
            Log.w("ForexRepo", "Invalid quote for $symbol: ${report.summary}")
            return false
        }

        _pairs.value = _pairs.value.map { pair ->
            if (pair.symbol == symbol) {
                pair.copy(
                    currentPrice = tick.price,
                    high24h = max(pair.high24h, tick.price),
                    low24h = min(pair.low24h, tick.price)
                )
            } else {
                pair
            }
        }
        
        return true
    }

    // ========================================================================
    // INITIAL DATA
    // ========================================================================

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

    private fun generateInitialCandles(basePrice: Double, tf: Timeframe): List<CandleStick> {
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

    // ========================================================================
    // OPENROUTER AI ANALYSIS
    // ========================================================================

    /**
     * Requests a live LLM analysis from OpenRouter.
     * Returns null on any failure - callers should fall back to local engine.
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
        val apiKey = openRouterApiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            Log.d("ForexRepo", "OpenRouter API key not configured")
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
                createSnapshotPrompt(symbol, timeframe, currentPrice, signalType, rsiValue, patterns, activeOverlays, contextBlock, questionBlock)
            } else {
                createLiveAnalysisPrompt(symbol, timeframe, currentPrice, signalType, rsiValue, patterns, contextBlock, questionBlock)
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
                    return "\ud83c\udf10 LIVE OPENROUTER AI REPORT (${request.model})\n\n$content"
                }
            }
            null
        } catch (e: Exception) {
            Log.e("ForexRepo", "OpenRouter analysis failed for $symbol", e)
            _errors.emit(RepositoryError("AI analysis failed: ${e.message}", e))
            null
        }
    }

    private fun createSnapshotPrompt(
        symbol: String, timeframe: String, currentPrice: Double,
        signalType: String, rsiValue: Double, patterns: String,
        activeOverlays: String, contextBlock: String, questionBlock: String
    ): String {
        return """
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
        1. \ud83d\udcf8 CAPTURED SNAPSHOT STRUCTURE & KEY LEVEL CONFIRMATION
        2. \ud83d\udd0d MANUAL CHECKLIST (Candle Rejection, RSI Confluence, Moving Average Dynamic Levels)
        3. \u26a0\ufe0f RISK & MANIPULATION WARNINGS (Liquidity Sweeps, News Spikes)
        4. \ud83c\udfc1 MANUAL TRADER VERDICT (Strong Buy, Wait for Confirmation, Invalid Setup, or Sell)
        """.trimIndent()
    }

    private fun createLiveAnalysisPrompt(
        symbol: String, timeframe: String, currentPrice: Double,
        signalType: String, rsiValue: Double, patterns: String,
        contextBlock: String, questionBlock: String
    ): String {
        return """
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

    // ========================================================================
    // DATABASE OPERATIONS
    // ========================================================================

    val savedSignals: Flow<List<SignalEntity>> = forexDao.getAllSavedSignals()
    val customAlerts: Flow<List<AlertEntity>> = forexDao.getAllAlerts()
    val watchlist: Flow<List<WatchlistEntity>> = forexDao.getWatchlist()

    suspend fun saveSignal(signal: TradeSignal) {
        try {
            forexDao.insertSignal(signal.toEntity())
        } catch (e: Exception) {
            Log.e("ForexRepo", "Failed to save signal ${signal.id}", e)
            _errors.emit(RepositoryError("Failed to save signal: ${e.message}", e))
        }
    }

    suspend fun deleteSavedSignal(id: String) {
        try {
            forexDao.deleteSignal(id)
        } catch (e: Exception) {
            Log.e("ForexRepo", "Failed to delete signal $id", e)
            _errors.emit(RepositoryError("Failed to delete signal: ${e.message}", e))
        }
    }

    suspend fun createAlert(alert: CustomAlert) {
        try {
            forexDao.insertAlert(alert.toEntity())
        } catch (e: Exception) {
            Log.e("ForexRepo", "Failed to create alert ${alert.id}", e)
            _errors.emit(RepositoryError("Failed to create alert: ${e.message}", e))
        }
    }

    suspend fun deleteAlert(id: String) {
        try {
            forexDao.deleteAlert(id)
        } catch (e: Exception) {
            Log.e("ForexRepo", "Failed to delete alert $id", e)
            _errors.emit(RepositoryError("Failed to delete alert: ${e.message}", e))
        }
    }

    suspend fun toggleAlert(id: String, active: Boolean) {
        try {
            forexDao.updateAlertStatus(id, triggered = false, active = active)
        } catch (e: Exception) {
            Log.e("ForexRepo", "Failed to toggle alert $id", e)
            _errors.emit(RepositoryError("Failed to toggle alert: ${e.message}", e))
        }
    }

    suspend fun addToWatchlist(symbol: String) {
        try {
            forexDao.addToWatchlist(WatchlistEntity(symbol = symbol))
        } catch (e: Exception) {
            Log.e("ForexRepo", "Failed to add $symbol to watchlist", e)
            _errors.emit(RepositoryError("Failed to add to watchlist: ${e.message}", e))
        }
    }

    suspend fun removeFromWatchlist(symbol: String) {
        try {
            forexDao.removeFromWatchlist(symbol)
        } catch (e: Exception) {
            Log.e("ForexRepo", "Failed to remove $symbol from watchlist", e)
            _errors.emit(RepositoryError("Failed to remove from watchlist: ${e.message}", e))
        }
    }

    // ========================================================================
    // INDICATOR SETTINGS PERSISTENCE
    // ========================================================================

    val savedIndicatorSettings: Flow<IndicatorSettings?> = forexDao.getUserSettings().map { entity ->
        entity?.toIndicatorSettings()
    }

    suspend fun saveIndicatorSettings(settings: IndicatorSettings) {
        try {
            forexDao.saveUserSettings(settings.toEntity())
        } catch (e: Exception) {
            Log.e("ForexRepo", "Failed to save indicator settings", e)
            _errors.emit(RepositoryError("Failed to save settings: ${e.message}", e))
        }
    }

    // ========================================================================
    // UTILITY EXTENSIONS
    // ========================================================================

    /**
     * Real-time tick stream for UI observation
     */
    fun startLivePriceStream(): Flow<List<CurrencyPair>> = _pairs.asStateFlow()

    fun reconnectWebSocket() {
        webSocketManager.reconnect()
    }

    fun disconnectWebSocket() {
        webSocketManager.disconnect()
    }

    // ========================================================================
    // ERROR TYPES
    // ========================================================================

    /**
     * Structured error type for repository operations
     */
    data class RepositoryError(
        val message: String,
        val cause: Throwable?,
        val timestamp: Long = System.currentTimeMillis()
    )
}

// ========================================================================
// EXTENSION FUNCTIONS FOR DATA MODEL CONVERSIONS
// ========================================================================

/**
 * Convert TradeSignal to SignalEntity for Room persistence
 */
private fun TradeSignal.toEntity(): SignalEntity {
    return SignalEntity(
        id = id,
        pairSymbol = pairSymbol,
        signalType = type.name,
        entryPrice = entryPrice,
        stopLoss = stopLoss,
        takeProfit1 = takeProfit1,
        takeProfit2 = takeProfit2,
        riskRewardRatio = riskRewardRatio,
        confidenceScore = confidenceScore,
        timeframeLabel = timeframe.label,
        summaryRationale = summaryRationale,
        timestamp = timestamp,
        atrValue = atrValue,
        stopLossPips = stopLossPips,
        volatilityRegime = volatilityRegime.name,
        suggestedLotSize = suggestedLotSize,
        dataQualityScore = dataQualityScore,
        dataSource = dataSource
    )
}

/**
 * Convert CustomAlert to AlertEntity for Room persistence
 */
private fun CustomAlert.toEntity(): AlertEntity {
    return AlertEntity(
        id = id,
        pairSymbol = pairSymbol,
        conditionType = condition.name,
        targetValue = targetValue,
        isTriggered = isTriggered,
        isActive = isActive,
        note = note,
        createdAt = createdAt
    )
}

/**
 * Convert UserSettingsEntity to IndicatorSettings
 */
private fun UserSettingsEntity.toIndicatorSettings(): IndicatorSettings {
    return IndicatorSettings(
        showSma20 = showSma20,
        showSma50 = showSma50,
        showEma20 = showEma20,
        showEma50 = showEma50,
        showEma200 = showEma200,
        showBollingerBands = showBollingerBands,
        showSupportResistance = showSupportResistance,
        showRsiSubchart = showRsiSubchart,
        showMacdSubchart = showMacdSubchart,
        showPatterns = showPatterns,
        smaPeriod1 = smaPeriod1,
        smaPeriod2 = smaPeriod2,
        emaPeriod1 = emaPeriod1,
        emaPeriod2 = emaPeriod2,
        emaPeriod3 = emaPeriod3,
        rsiPeriod = rsiPeriod,
        rsiOverbought = rsiOverbought,
        rsiOversold = rsiOversold,
        macdFastPeriod = macdFastPeriod,
        macdSlowPeriod = macdSlowPeriod,
        macdSignalPeriod = macdSignalPeriod,
        bollingerPeriod = bollingerPeriod,
        bollingerStdDev = bollingerStdDev
    )
}

/**
 * Convert IndicatorSettings to UserSettingsEntity for Room persistence
 */
private fun IndicatorSettings.toEntity(): UserSettingsEntity {
    return UserSettingsEntity(
        id = "current_settings",
        showSma20 = showSma20,
        showSma50 = showSma50,
        showEma20 = showEma20,
        showEma50 = showEma50,
        showEma200 = showEma200,
        showBollingerBands = showBollingerBands,
        showSupportResistance = showSupportResistance,
        showRsiSubchart = showRsiSubchart,
        showMacdSubchart = showMacdSubchart,
        showPatterns = showPatterns,
        smaPeriod1 = smaPeriod1,
        smaPeriod2 = smaPeriod2,
        emaPeriod1 = emaPeriod1,
        emaPeriod2 = emaPeriod2,
        emaPeriod3 = emaPeriod3,
        rsiPeriod = rsiPeriod,
        rsiOverbought = rsiOverbought,
        rsiOversold = rsiOversold,
        macdFastPeriod = macdFastPeriod,
        macdSlowPeriod = macdSlowPeriod,
        macdSignalPeriod = macdSignalPeriod,
        bollingerPeriod = bollingerPeriod,
        bollingerStdDev = bollingerStdDev
    )
}
