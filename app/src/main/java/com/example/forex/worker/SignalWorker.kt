package com.example.forex.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.forex.data.db.CandleEntity
import com.example.forex.data.db.ForexDao
import com.example.forex.data.db.ForexDatabase
import com.example.forex.data.db.SignalEntity
import com.example.forex.data.model.CurrencyPair
import com.example.forex.data.model.IndicatorSettings
import com.example.forex.data.model.PairCatalog
import com.example.forex.data.model.SignalType
import com.example.forex.data.model.Timeframe
import com.example.forex.data.model.TradeSignal
import com.example.forex.data.model.CandleStick
import com.example.forex.data.remote.MarketDataException
import com.example.forex.data.remote.RealMarketDataProvider
import com.example.forex.data.repository.ForexMarketRepositoryService
import com.example.forex.data.repository.TechnicalAnalysisEngine
import com.example.forex.data.validation.MarketDataValidator
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.abs

/**
 * Background signal generator.
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
) : CoroutineWorker(context, workerParams) {

    private val dao: ForexDao = ForexDatabase.getDatabase(context).forexDao()
    private val marketProvider = RealMarketDataProvider()
    private val fcsService = ForexMarketRepositoryService()

    override suspend fun doWork(): Result {
        val config = WorkConfig.from(inputData)
        val symbols = resolveSymbols(config)
        if (symbols.isEmpty()) {
            return Result.success(output("No symbols tracked; nothing to scan.", 0, 0))
        }

        val settings = loadSettings()
        val analyzed = mutableListOf<TradeSignal>()
        val failures = mutableListOf<String>()
        var networkRetryable = false

        for (symbol in symbols) {
            val outcome = runCatching { analyzeSymbol(symbol, config, settings) }
                .getOrElse { error ->
                    if (error is MarketDataException.Network || error is java.io.IOException) {
                        networkRetryable = true
                    }
                    failures.add("$symbol: ${error.message ?: error.javaClass.simpleName}")
                    return@getOrElse null
                }
            outcome?.let { analyzed.add(it) }
        }

        // Strong, actionable, de-duplicated signals only.
        val recent = dao.getSignalsSince(System.currentTimeMillis() - config.cooldownMs)
        val actionable = analyzed.filter { signal ->
            signal.type != SignalType.NEUTRAL &&
                signal.confidenceScore >= config.minConfidence &&
                signal.dataQualityScore >= config.minQuality &&
                recent.none {
                    it.pairSymbol == signal.pairSymbol &&
                        it.timeframeLabel == signal.timeframe.label &&
                        it.signalType == signal.type.name
                }
        }

        actionable.forEach { signal -> dao.insertSignal(toEntity(signal, bookmarked = false)) }

        if (actionable.isNotEmpty()) {
            postNotifications(actionable)
        }

        if (actionable.isEmpty() && analyzed.isEmpty() && networkRetryable && runAttemptCount < MAX_ATTEMPTS) {
            // Entire scan failed on transient network conditions — let WorkManager back off & retry.
            return Result.retry()
        }

        val note = buildString {
            append("Scanned ${symbols.size} symbol(s); ")
            append("${actionable.size} actionable signal(s) notified; ")
            if (failures.isNotEmpty()) append("skipped: ${failures.joinToString("; ").take(400)}")
            else append("all sources healthy")
        }
        return Result.success(output(note, symbols.size, actionable.size))
    }

    // ------------------------------------------------------------------
    // Analysis pipeline
    // ------------------------------------------------------------------

    private suspend fun analyzeSymbol(symbol: String, config: WorkConfig, settings: IndicatorSettings): TradeSignal? {
        val candles = loadCandles(symbol, config)
        if (candles.size < MIN_CANDLES_FOR_ANALYSIS) {
            throw IllegalStateException("only ${candles.size} validated candles (< $MIN_CANDLES_FOR_ANALYSIS required)")
        }

        // Derive the pair snapshot strictly from observed candles — no hardcoded prices.
        val lastClose = candles.last().close
        val windowStart = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        val recent = candles.filter { it.timestamp >= windowStart }.ifEmpty { candles.takeLast(24) }
        val pair: CurrencyPair = PairCatalog.pairFor(
            symbol = symbol,
            currentPrice = lastClose,
            high24h = recent.maxOf { it.high },
            low24h = recent.minOf { it.low }
        )

        val source = if (config.allowNetwork && marketProvider.isConfigured()) {
            "ALPHA_VANTAGE/FCS"
        } else if (config.allowNetwork) "FCS_API/ROOM" else "ROOM"

        return TechnicalAnalysisEngine.generateTradeSignal(
            pair = pair,
            candles = candles,
            timeframe = config.timeframe,
            settings = settings,
            dataSource = source
        )
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
                dao.deleteCandles(normalized, tfName)
                dao.insertCandles(fetched.takeLast(MAX_CANDLES_KEPT).map {
                    CandleEntity(normalized, tfName, it.timestamp, it.open, it.high, it.low, it.close, it.volume)
                })
                return fetched
            }
            // Network unavailable/exhausted — fall through to locally persisted history.
        }

        return dao.getCandles(normalized, tfName).map {
            CandleStick(it.timestamp, it.open, it.high, it.low, it.close, it.volume)
        }
    }

    private suspend fun fetchFromNetwork(symbol: String, config: WorkConfig): List<CandleStick>? {
        if (marketProvider.isConfigured()) {
            val result = marketProvider.fetchCandles(symbol, config.timeframe)
            val candles = result.getOrNull()
            if (candles != null && candles.size >= MIN_CANDLES_FOR_ANALYSIS) return candles
            // Quota/premium/network misses fall through to FCS without aborting the scan.
        }
        if (fcsService.isConfigured()) {
            val period = when (config.timeframe) {
                Timeframe.M1 -> "1m"
                Timeframe.M5 -> "5m"
                Timeframe.M15 -> "15m"
                Timeframe.H1 -> "1h"
                Timeframe.H4 -> "4h"
                Timeframe.D1 -> "1d"
            }
            val response = fcsService.fetchHistoricalCandles(symbol.replace("/", ""), period)
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
                val validation = MarketDataValidator.validateCandleSeries(
                    mapped, config.timeframe.minutes * 60_000L, minValidCount = MIN_CANDLES_FOR_ANALYSIS
                )
                if (validation.report.isUsable) return validation.candles
            }
        }
        return null
    }

    private suspend fun loadSettings(): IndicatorSettings {
        val entity = dao.getUserSettings().first() ?: return IndicatorSettings()
        return IndicatorSettings(
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
    }

    private suspend fun resolveSymbols(config: WorkConfig): List<String> {
        config.symbolsOverride?.let { return it }
        val watchlist = dao.getWatchlist().first().map { it.symbol }
        return watchlist.ifEmpty { PairCatalog.KNOWN.map { it.symbol } }
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private fun postNotifications(signals: List<TradeSignal>) {
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (signals.size == 1) {
            val signal = signals.first()
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
        } else {
            // One detailed notification per signal, collapsed under an InboxStyle group summary.
            signals.forEach { manager.notify(notificationIdFor(it), notifyForSignal(it)) }
            val inbox = NotificationCompat.InboxStyle()
                .setBigContentTitle("${signals.size} new trade signals")
                .setSummaryText("Tap any signal for the full ATR-sized plan")
            signals.forEach { inbox.addLine("${it.type.title} ${it.pairSymbol} · E ${fmtPrice(it.entryPrice, it)} · RR 1:${fmt1(it.riskRewardRatio)} · ${it.confidenceScore}%") }
            val summary = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("${signals.size} new trade signals")
                .setContentText(signals.joinToString(" · ") { "${it.pairSymbol} ${if (it.type == SignalType.BUY) "▲" else "▼"}" })
                .setStyle(inbox)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            manager.notify(GROUP_ID, summary)
        }
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
        append(" · SL ${fmtPrice(signal.stopLoss, signal)} (${fmt1(signal.stopLossPips)} p)")
        append(" · TP1 ${fmtPrice(signal.takeProfit1, signal)}")
        append(" · 1:${fmt1(signal.riskRewardRatio)}R")
        append(" · ATR ${fmt1(atrPips(signal))}p ${signal.volatilityRegime.label}")
        append(" · ${signal.confidenceScore}% conf · ${signal.dataSource}")
        if (signal.suggestedLotSize > 0) {
            append("\nSuggested size (1% risk): ${fmt2(signal.suggestedLotSize)} lots")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Trade Signals", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Real, volatility-sized trade signals generated from validated market data"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun notificationIdFor(signal: TradeSignal): Int =
        (signal.id.hashCode() and 0x7FFFFFFF) % 100_000 + 1

    private fun output(note: String, scanned: Int, notified: Int): Data = Data.Builder()
        .putString(KEY_OUTPUT_NOTE, note)
        .putInt(KEY_OUTPUT_SCANNED, scanned)
        .putInt(KEY_OUTPUT_NOTIFIED, notified)
        .build()

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Input config
    // ------------------------------------------------------------------

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

    companion object {
        const val CHANNEL_ID = "trade_signals_channel"
        const val GROUP_KEY = "com.example.forex.TRADE_SIGNALS"
        private const val GROUP_ID = 1701
        private const val MAX_ATTEMPTS = 3
        private const val MIN_CANDLES_FOR_ANALYSIS = 30
        private const val MAX_CANDLES_KEPT = 200

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
