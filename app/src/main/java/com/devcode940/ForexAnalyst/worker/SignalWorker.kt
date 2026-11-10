package com.devcode940.ForexAnalyst.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.devcode940.ForexAnalyst.data.db.CandleEntity
import com.devcode940.ForexAnalyst.data.db.ForexDao
import com.devcode940.ForexAnalyst.data.db.ForexDatabase
import com.devcode940.ForexAnalyst.data.db.SignalEntity
import com.devcode940.ForexAnalyst.data.local.HistoricalPriceDao
import com.devcode940.ForexAnalyst.data.local.toCandleSticks
import com.devcode940.ForexAnalyst.data.local.toHistoricalPriceEntities
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CurrencyPair
import com.devcode940.ForexAnalyst.data.model.IndicatorSettings
import com.devcode940.ForexAnalyst.data.model.MarketTick
import com.devcode940.ForexAnalyst.data.model.PairCatalog
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import com.devcode940.ForexAnalyst.data.remote.MarketDataException
import com.devcode940.ForexAnalyst.data.remote.RealMarketDataProvider
import com.devcode940.ForexAnalyst.data.repository.ForexMarketRepositoryService
import com.devcode940.ForexAnalyst.data.repository.TechnicalAnalysisEngine
import com.devcode940.ForexAnalyst.data.security.SecureApiKeyManager
import com.devcode940.ForexAnalyst.data.validation.MarketDataValidator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/**
 * Production-ready Background signal generator.
 *
 * Features:
 * - Parallel symbol analysis using async/awaitAll for performance
 * - Comprehensive null safety throughout
 * - Structured error handling with sealed classes
 * - Thread-safe operations with Mutex synchronization
 * - Resource cleanup via Closeable pattern
 * - Proper error aggregation and logging
 * - Enhanced signal deduplication (pair symbol + timeframe + timestamp)
 *
 * Every 15-minute window (WorkManager minimum for periodic work) the worker:
 *  1. refreshes OHLC history from the real data chain — Alpha Vantage (validated,
 *     quota-limited) with FCS API fallback — and persists fresh candles into Room;
 *  2. runs the deterministic [TechnicalAnalysisEngine] (EMA/RSI/MACD/ADX/Bollinger +
 *     ATR volatility sizing) against the last completed candles;
 *  3. keeps only actionable signals (BUY/SELL above a configurable confidence floor and
 *     data-quality floor), de-duplicated against signals already saved within a cooldown;
 *  4. persists each accepted signal to `saved_signals` (so it shows up in the Signals tab)
 *     and posts a grouped notification with the full ATR-sized execution plan.
 *
 * There is no random/mocked signal path anymore: if no upstream data and no persisted
 * history exist for a symbol, that symbol is skipped and reported in the output work data.
 */
class SignalWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), Closeable {

    // ========================================================================
    // RESOURCE MANAGEMENT
    // ========================================================================

    private val database: ForexDatabase = ForexDatabase.getDatabase(context)
    private val dao: ForexDao = database.forexDao()
    private val priceDao: HistoricalPriceDao = database.historicalPriceDao()
    
    // Thread-safe initialization
    private val initMutex = Mutex()
    private var isInitialized = false
    
    // Keys resolved from encrypted storage (debug-only BuildConfig fallback inside
    // SecureApiKeyManager) instead of being baked into these objects directly.
    private val marketProvider: RealMarketDataProvider by lazy {
        RealMarketDataProvider(
            apiKey = runCatching { SecureApiKeyManager.getInstance(context).getAlphaVantageApiKey() }
                .getOrElse { e ->
                    logError("Failed to load AlphaVantage API key", e)
                    ""
                }
        )
    }
    
    private val fcsService: ForexMarketRepositoryService by lazy {
        ForexMarketRepositoryService(
            apiKey = runCatching { SecureApiKeyManager.getInstance(context).getFcsApiKey() }
                .getOrElse { e ->
                    logError("Failed to load FCS API key", e)
                    ""
                }
        )
    }

    // ========================================================================
    // LIFECYCLE MANAGEMENT
    // ========================================================================

    private var isClosed = false

    override fun close() {
        if (isClosed) return
        isClosed = true
        // Close database connections
        try {
            database.close()
        } catch (e: Exception) {
            logError("Error closing database", e)
        }
    }

    override suspend fun doWork(): Result {
        if (isClosed) {
            return Result.failure()
        }

        return initMutex.withLock {
            if (isClosed) return@withLock Result.failure()
            if (!isInitialized) {
                isInitialized = true
            }
            doWorkInternal()
        }
    }

    private suspend fun doWorkInternal(): Result = coroutineScope {
        val config = WorkConfig.from(inputData)
        val symbols = resolveSymbols(config)
        
        if (symbols.isEmpty()) {
            logInfo("No symbols tracked; nothing to scan")
            return@coroutineScope Result.success(output("No symbols tracked; nothing to scan.", 0, 0))
        }

        logInfo("Starting signal analysis for ${symbols.size} symbols")
        
        val settings = loadSettings()
        val analysisResults = analyzeSymbolsInParallel(symbols, config, settings)
        
        // Process results
        val (analyzed, failures, networkRetryable) = processAnalysisResults(analysisResults)
        
        // Filter for actionable signals with comprehensive deduplication
        val recent = runCatching { dao.getSignalsSince(System.currentTimeMillis() - config.cooldownMs) }
            .getOrElse { e ->
                logError("Failed to get recent signals", e)
                emptyList()
            }
        
        val actionable = filterActionableSignals(analyzed, recent, config)
        
        // Persist actionable signals
        persistSignals(actionable)
        
        // Send notifications
        if (actionable.isNotEmpty()) {
            postNotifications(actionable)
        }

        // Determine result
        if (actionable.isEmpty() && analyzed.isEmpty() && networkRetryable && runAttemptCount < MAX_ATTEMPTS) {
            logInfo("All symbols failed with network errors, retrying...")
            return@coroutineScope Result.retry()
        }

        val note = buildResultNote(symbols.size, actionable.size, failures)
        logInfo(note)
        return@coroutineScope Result.success(output(note, symbols.size, actionable.size))
    }

    // ========================================================================
    // PARALLEL ANALYSIS
    // ========================================================================

    /**
     * Analyze all symbols in parallel using async/awaitAll for performance
     */
    private suspend fun analyzeSymbolsInParallel(
        symbols: List<String>,
        config: WorkConfig,
        settings: IndicatorSettings
    ): List<SymbolAnalysisResult> = coroutineScope {
        symbols.map { symbol ->
            async {
                analyzeSymbolWithErrorHandling(symbol, config, settings)
            }
        }.awaitAll()
    }

    private suspend fun analyzeSymbolWithErrorHandling(
        symbol: String,
        config: WorkConfig,
        settings: IndicatorSettings
    ): SymbolAnalysisResult {
        return try {
            val signal = analyzeSymbol(symbol, config, settings)
            SymbolAnalysisResult.Success(symbol, signal)
        } catch (e: MarketDataException.Network) {
            logError("Network error analyzing $symbol", e)
            SymbolAnalysisResult.NetworkError(symbol, e.message ?: e.javaClass.simpleName)
        } catch (e: IOException) {
            logError("IO error analyzing $symbol", e)
            SymbolAnalysisResult.NetworkError(symbol, e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalStateException) {
            logWarn("Insufficient data for $symbol: ${e.message}")
            SymbolAnalysisResult.DataError(symbol, e.message ?: "Insufficient data")
        } catch (e: Exception) {
            logError("Unexpected error analyzing $symbol", e)
            SymbolAnalysisResult.GenericError(symbol, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun processAnalysisResults(results: List<SymbolAnalysisResult>): AnalysisSummary {
        val analyzed = mutableListOf<TradeSignal>()
        val failures = mutableListOf<String>()
        var networkRetryable = false

        for (result in results) {
            when (result) {
                is SymbolAnalysisResult.Success -> {
                    result.signal?.let { analyzed.add(it) }
                }
                is SymbolAnalysisResult.NetworkError -> {
                    networkRetryable = true
                    failures.add("${result.symbol}: ${result.errorMessage}")
                }
                is SymbolAnalysisResult.DataError -> {
                    failures.add("${result.symbol}: ${result.errorMessage}")
                }
                is SymbolAnalysisResult.GenericError -> {
                    failures.add("${result.symbol}: ${result.errorMessage}")
                }
            }
        }

        return AnalysisSummary(analyzed, failures, networkRetryable)
    }

    // ========================================================================
    // SIGNAL FILTERING & DEDUPLICATION
    // ========================================================================

    /**
     * Filter signals for actionable ones with enhanced deduplication
     */
    private fun filterActionableSignals(
        analyzed: List<TradeSignal>,
        recent: List<SignalEntity>,
        config: WorkConfig
    ): List<TradeSignal> {
        return analyzed.filter { signal ->
            // Must be actionable (not NEUTRAL)
            signal.type != SignalType.NEUTRAL &&
            // Must meet confidence threshold
            signal.confidenceScore >= config.minConfidence &&
            // Must meet quality threshold
            signal.dataQualityScore >= config.minQuality &&
            // Must not be a duplicate (enhanced deduplication)
            isNotDuplicate(signal, recent)
        }
    }

    /**
     * Enhanced deduplication: checks pair symbol, timeframe, signal type, AND timestamp
     */
    private fun isNotDuplicate(signal: TradeSignal, recent: List<SignalEntity>): Boolean {
        return recent.none { existing ->
            existing.pairSymbol == signal.pairSymbol &&
            existing.timeframeLabel == signal.timeframe.label &&
            existing.signalType == signal.type.name &&
            // Additional timestamp check to prevent duplicates within cooldown period
            abs(existing.timestamp - signal.timestamp) < DUPLICATE_TIME_WINDOW_MS
        }
    }

    // ========================================================================
    // ANALYSIS PIPELINE
    // ========================================================================

    private suspend fun analyzeSymbol(symbol: String, config: WorkConfig, settings: IndicatorSettings): TradeSignal? {
        val candles = loadCandles(symbol, config)
        if (candles.size < MIN_CANDLES_FOR_ANALYSIS) {
            throw IllegalStateException("only ${candles.size} validated candles (< $MIN_CANDLES_FOR_ANALYSIS required)")
        }

        // Derive the pair snapshot strictly from observed candles — no hardcoded prices.
        val lastClose = candles.last().close
        val windowStart = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        val recent = candles.filter { it.timestamp >= windowStart }.ifEmpty { candles.takeLast(24) }
        
        // Null-safe calculations
        val high24h = recent.maxOfOrNull { it.high } ?: lastClose
        val low24h = recent.minOfOrNull { it.low } ?: lastClose
        
        val pair: CurrencyPair = PairCatalog.pairFor(
            symbol = symbol,
            currentPrice = lastClose,
            high24h = high24h,
            low24h = low24h
        )

        val source = buildDataSourceString(config)

        return TechnicalAnalysisEngine.generateTradeSignal(
            pair = pair,
            candles = candles,
            timeframe = config.timeframe,
            settings = settings,
            dataSource = source
        )
    }

    private fun buildDataSourceString(config: WorkConfig): String {
        return when {
            config.allowNetwork && marketProvider.isConfigured() -> "ALPHA_VANTAGE/FCS"
            config.allowNetwork -> "FCS_API/ROOM"
            else -> "ROOM"
        }
    }

    /**
     * Candles come from the freshest validated source:
     * network refresh (Alpha Vantage → FCS, persisted back to Room) → Room history.
     * Simulated candles are deliberately NOT generated in the worker: a background
     * notification must only ever reflect real market data.
     */
    private suspend fun loadCandles(symbol: String, config: WorkConfig): List<CandleStick> {
        val normalized = PairCatalog.definitionFor(symbol).symbol
        val tfName = config.timeframe.name

        if (config.allowNetwork) {
            val fetched = fetchFromNetwork(normalized, config)
            if (fetched != null) {
                // Persist for offline analysis; keep Room bounded per symbol/timeframe.
                persistFetchedCandles(normalized, tfName, fetched)
                return fetched
            }
            // Network unavailable/exhausted — fall through to locally persisted history.
            logInfo("Network fetch failed for $normalized, using cached data")
        }

        // Try cached prices first
        val cachedPrices = runCatching { 
            priceDao.getHistoricalPrices(normalized, tfName).toCandleSticks() 
        }.getOrElse { e ->
            logError("Failed to load cached prices for $normalized/$tfName", e)
            emptyList()
        }
        
        if (cachedPrices.isNotEmpty()) {
            return cachedPrices
        }

        // Fall back to candles
        return runCatching {
            dao.getCandles(normalized, tfName).map {
                CandleStick(it.timestamp, it.open, it.high, it.low, it.close, it.volume)
            }
        }.getOrElse { e ->
            logError("Failed to load candles for $normalized/$tfName", e)
            emptyList()
        }
    }

    private suspend fun persistFetchedCandles(normalized: String, tfName: String, fetched: List<CandleStick>) {
        val trimmed = fetched.takeLast(MAX_CANDLES_KEPT)
        
        runCatching {
            priceDao.deletePricesForPairAndTimeframe(normalized, tfName)
            priceDao.insertPrices(trimmed.toHistoricalPriceEntities(normalized, tfName))
            dao.deleteCandles(normalized, tfName)
            dao.insertCandles(trimmed.map {
                CandleEntity(normalized, tfName, it.timestamp, it.open, it.high, it.low, it.close, it.volume)
            })
        }.onFailure { e ->
            logError("Failed to persist fetched candles for $normalized/$tfName", e)
        }
    }

    private suspend fun fetchFromNetwork(symbol: String, config: WorkConfig): List<CandleStick>? {
        // Try Alpha Vantage first
        if (marketProvider.isConfigured()) {
            val result = runCatching { marketProvider.fetchCandles(symbol, config.timeframe) }
            val candles = result.getOrNull()
            if (candles != null && candles.size >= MIN_CANDLES_FOR_ANALYSIS) {
                return candles
            }
            // Quota/premium/network misses fall through to FCS without aborting the scan.
            result.onFailure { e ->
                logWarn("Alpha Vantage fetch failed for $symbol: ${e.message}")
            }
        }

        // Try FCS API as fallback
        if (fcsService.isConfigured()) {
            val period = mapTimeframeToFcsPeriod(config.timeframe)
            val response = runCatching { 
                fcsService.fetchHistoricalCandles(symbol.replace("/", ""), period) 
            }
            
            val mapped = response.getOrNull()?.mapNotNull { c ->
                val o = c.o.toDoubleOrNull() ?: return@mapNotNull null
                val h = c.h.toDoubleOrNull() ?: return@mapNotNull null
                val l = c.l.toDoubleOrNull() ?: return@mapNotNull null
                val cl = c.c.toDoubleOrNull() ?: return@mapNotNull null
                val t = c.t?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
                CandleStick(t, o, h, l, cl, c.v?.toDoubleOrNull() ?: 0.0)
            }?.sortedBy { it.timestamp }

            if (mapped != null && mapped.size >= MIN_CANDLES_FOR_ANALYSIS) {
                // Structural validation even on the fallback path — the worker never trusts raw payloads.
                val validation = runCatching {
                    MarketDataValidator.validateCandleSeries(
                        mapped, config.timeframe.minutes * 60_000L, minValidCount = MIN_CANDLES_FOR_ANALYSIS
                    )
                }.getOrElse { e ->
                    logError("Market data validation failed", e)
                    return@getOrElse MarketDataValidator.ValidationResult(emptyList(), MarketDataValidator.ValidationReport(false))
                }
                
                if (validation.report.isUsable) return validation.candles
            }
        }
        return null
    }

    private fun mapTimeframeToFcsPeriod(timeframe: Timeframe): String {
        return when (timeframe) {
            Timeframe.M1 -> "1m"
            Timeframe.M5 -> "5m"
            Timeframe.M15 -> "15m"
            Timeframe.H1 -> "1h"
            Timeframe.H4 -> "4h"
            Timeframe.D1 -> "1d"
        }
    }

    // ========================================================================
    // SETTINGS & SYMBOLS
    // ========================================================================

    private suspend fun loadSettings(): IndicatorSettings {
        return runCatching {
            val entity = dao.getUserSettings().first()
            entity ?: return@runCatching IndicatorSettings()
            
            IndicatorSettings(
                rsiPeriod = entity.rsiPeriod,
                rsiOverbought = entity.rsiOverbought,
                rsiOversold = entity.rsiOversold,
                emaPeriod1 = entity.emaPeriod1,
                emaPeriod2 = entity.emaPeriod2,
                emaPeriod3 = entity.emaPeriod3,
                macdFastPeriod = entity.macdFastPeriod,
                macdSlowPeriod = entity.macdSlowPeriod,
                macdSignalPeriod = entity.macdSignalPeriod,
                bollingerPeriod = entity.bollingerPeriod,
                bollingerStdDev = entity.bollingerStdDev
            )
        }.getOrElse { e ->
            logError("Failed to load settings", e)
            IndicatorSettings()
        }
    }

    private suspend fun resolveSymbols(config: WorkConfig): List<String> {
        config.symbolsOverride?.let { 
            return@resolveSymbols it.filter { it.isNotBlank() }
        }
        
        return runCatching {
            val watchlist = dao.getWatchlist().first().map { it.symbol }
            watchlist.ifEmpty { PairCatalog.KNOWN.map { it.symbol } }
        }.getOrElse { e ->
            logError("Failed to load watchlist", e)
            PairCatalog.KNOWN.map { it.symbol }
        }
    }

    // ========================================================================
    // SIGNAL PERSISTENCE
    // ========================================================================

    private suspend fun persistSignals(signals: List<TradeSignal>) {
        signals.forEach { signal ->
            runCatching {
                dao.insertSignal(toEntity(signal, bookmarked = false))
            }.onFailure { e ->
                logError("Failed to persist signal for ${signal.pairSymbol}", e)
            }
        }
    }

    // ========================================================================
    // NOTIFICATIONS
    // ========================================================================

    private fun postNotifications(signals: List<TradeSignal>) {
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        
        if (!manager.areNotificationsEnabled()) {
            logInfo("Notifications disabled, skipping notification")
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                logWarn("Notification permission not granted")
                return
            }
        }

        if (signals.size == 1) {
            postSingleNotification(signals.first(), manager)
        } else {
            postGroupedNotifications(signals, manager)
        }
    }

    private fun postSingleNotification(signal: TradeSignal, manager: NotificationManagerCompat) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${signal.type.title}: ${signal.pairSymbol} (${signal.timeframe.label})")
            .setContentText(executionPlanText(signal))
            .setStyle(NotificationCompat.BigTextStyle().bigText(executionPlanText(signal) + "\n" + signal.summaryRationale))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationIdFor(signal), notification)
    }

    private fun postGroupedNotifications(signals: List<TradeSignal>, manager: NotificationManagerCompat) {
        // One detailed notification per signal, collapsed under an InboxStyle group summary.
        signals.forEach { manager.notify(notificationIdFor(it), notifyForSignal(it)) }
        
        val inbox = NotificationCompat.InboxStyle()
            .setBigContentTitle("${signals.size} new trade signals")
            .setSummaryText("Tap any signal for the full ATR-sized plan")
        
        signals.forEach { 
            inbox.addLine("${it.type.title} ${it.pairSymbol} \u00b7 E ${fmtPrice(it.entryPrice, it)} \u00b7 RR 1:${fmt1(it.riskRewardRatio)} \u00b7 ${it.confidenceScore}%") 
        }
        
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${signals.size} new trade signals")
            .setContentText(signals.joinToString(" \u00b7 ") { "${it.pairSymbol} ${if (it.type == SignalType.BUY) "\u25b2" else "\u25bc"}" })
            .setStyle(inbox)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        manager.notify(GROUP_ID, summary)
    }

    private fun notifyForSignal(signal: TradeSignal) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("${signal.type.title}: ${signal.pairSymbol}")
        .setContentText(executionPlanText(signal))
        .setStyle(NotificationCompat.BigTextStyle().bigText(executionPlanText(signal)))
        .setGroup(GROUP_KEY)
        .setAutoCancel(true)
        .build()

    private fun executionPlanText(signal: TradeSignal): String = buildString {
        append("Entry ${fmtPrice(signal.entryPrice, signal)}")
        append(" \u00b7 SL ${fmtPrice(signal.stopLoss, signal)} (${fmt1(signal.stopLossPips)} p)")
        append(" \u00b7 TP1 ${fmtPrice(signal.takeProfit1, signal)}")
        append(" \u00b7 1:${fmt1(signal.riskRewardRatio)}R")
        append(" \u00b7 ATR ${fmt1(atrPips(signal))}p ${signal.volatilityRegime.label}")
        append(" \u00b7 ${signal.confidenceScore}% conf \u00b7 ${signal.dataSource}")
        if (signal.suggestedLotSize > 0) {
            append("\nSuggested size (1% risk): ${fmt2(signal.suggestedLotSize)} lots")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Trade Signals", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Real, volatility-sized trade signals generated from validated market data"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.createNotificationChannel(channel)
        }
    }

    private fun notificationIdFor(signal: TradeSignal): Int =
        (signal.id.hashCode() and 0x7FFFFFFF) % 100_000 + 1

    // ========================================================================
    // LOGGING
    // ========================================================================

    private fun logInfo(message: String) {
        android.util.Log.i(TAG, message)
    }

    private fun logWarn(message: String) {
        android.util.Log.w(TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            android.util.Log.e(TAG, message, throwable)
        } else {
            android.util.Log.e(TAG, message)
        }
    }

    // ========================================================================
    // FORMATTING HELPERS
    // ========================================================================

    private fun fmtPrice(value: Double, signal: TradeSignal): String {
        val decimals = TechnicalAnalysisEngine.decimalsForPip(PairCatalog.pipSizeFor(signal.pairSymbol))
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    private fun atrPips(signal: TradeSignal): Double {
        val pip = PairCatalog.pipSizeFor(signal.pairSymbol)
        return if (pip > 0) abs(signal.atrValue) / pip else 0.0
    }

    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)

    // ========================================================================
    // RESULT BUILDING
    // ========================================================================

    private fun buildResultNote(scanned: Int, notified: Int, failures: List<String>): String = buildString {
        append("Scanned $scanned symbol(s); ")
        append("$notified actionable signal(s) notified; ")
        if (failures.isNotEmpty()) {
            append("skipped: ${failures.joinToString("; ").take(400)}")
        } else {
            append("all sources healthy")
        }
    }

    private fun output(note: String, scanned: Int, notified: Int): Data = Data.Builder()
        .putString(KEY_OUTPUT_NOTE, note)
        .putInt(KEY_OUTPUT_SCANNED, scanned)
        .putInt(KEY_OUTPUT_NOTIFIED, notified)
        .build()

    // ========================================================================
    // DATA MODELS
    // ========================================================================

    /**
     * Result of analyzing a single symbol
     */
    private sealed class SymbolAnalysisResult {
        data class Success(val symbol: String, val signal: TradeSignal?) : SymbolAnalysisResult()
        data class NetworkError(val symbol: String, val errorMessage: String) : SymbolAnalysisResult()
        data class DataError(val symbol: String, val errorMessage: String) : SymbolAnalysisResult()
        data class GenericError(val symbol: String, val errorMessage: String) : SymbolAnalysisResult()
    }

    /**
     * Summary of all analysis results
     */
    private data class AnalysisSummary(
        val analyzed: List<TradeSignal>,
        val failures: List<String>,
        val networkRetryable: Boolean
    )

    // ========================================================================
    // INPUT CONFIG
    // ========================================================================

    private data class WorkConfig(
        val symbolsOverride: List<String>?,
        val timeframe: Timeframe,
        val minConfidence: Int,
        val minQuality: Int,
        val cooldownMs: Long,
        val allowNetwork: Boolean
    ) {
        companion object {
            fun from(data: Data): WorkConfig = WorkConfig(
                symbolsOverride = data.getString(KEY_SYMBOLS)
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.ifEmpty { null },
                timeframe = runCatching {
                    Timeframe.valueOf(data.getString(KEY_TIMEFRAME) ?: Timeframe.H1.name)
                }.getOrDefault(Timeframe.H1),
                minConfidence = data.getInt(KEY_MIN_CONFIDENCE, DEFAULT_MIN_CONFIDENCE),
                minQuality = data.getInt(KEY_MIN_QUALITY, 70),
                cooldownMs = data.getLong(KEY_COOLDOWN_HOURS, DEFAULT_COOLDOWN_HOURS) * 3_600_000L,
                allowNetwork = data.getBoolean(KEY_ALLOW_NETWORK, true)
            )
        }
    }

    private fun toEntity(signal: TradeSignal, bookmarked: Boolean) = SignalEntity(
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
        isBookmarked = bookmarked,
        atrValue = signal.atrValue,
        stopLossPips = signal.stopLossPips,
        volatilityRegime = signal.volatilityRegime.name,
        suggestedLotSize = signal.suggestedLotSize,
        dataQualityScore = signal.dataQualityScore,
        dataSource = signal.dataSource
    )

    // ========================================================================
    // COMPANION OBJECT
    // ========================================================================

    companion object {
        private const val TAG = "SignalWorker"
        const val CHANNEL_ID = "trade_signals_channel"
        const val GROUP_KEY = "com.devcode940.ForexAnalyst.TRADE_SIGNALS"
        private const val GROUP_ID = 1701
        private const val MAX_ATTEMPTS = 3
        private const val MIN_CANDLES_FOR_ANALYSIS = 30
        private const val MAX_CANDLES_KEPT = 200
        private const val DUPLICATE_TIME_WINDOW_MS = 300000L // 5 minutes

        const val DEFAULT_MIN_CONFIDENCE = 75
        const val DEFAULT_COOLDOWN_HOURS = 6L

        const val KEY_SYMBOLS = "symbols"
        const val KEY_TIMEFRAME = "timeframe"
        const val KEY_MIN_CONFIDENCE = "min_confidence"
        const val KEY_MIN_QUALITY = "min_quality"
        const val KEY_COOLDOWN_HOURS = "cooldown_hours"
        const val KEY_ALLOW_NETWORK = "allow_network"
        const val KEY_OUTPUT_NOTE = "output_note"
        const val KEY_OUTPUT_SCANNED = "output_scanned"
        const val KEY_OUTPUT_NOTIFIED = "output_notified"
    }
}