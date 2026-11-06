package com.example.forex.ui.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import java.util.Locale
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.forex.data.ai.DynamicAiResponseEngine
import com.example.forex.data.db.ForexDatabase
import com.example.forex.data.model.*
import com.example.forex.data.remote.MarketDataHealth
import com.example.forex.data.repository.ForexRepository
import com.example.forex.data.repository.TechnicalAnalysisEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import com.example.forex.data.local.ForexLocalDatabase
import com.example.forex.data.local.ForexOfflinePriceRepository
import kotlinx.coroutines.launch
import com.example.forex.data.auth.ForexAuthManager
import com.example.forex.data.auth.GoogleAuthClient
import com.google.firebase.auth.FirebaseUser

class ForexViewModel(application: Application) : AndroidViewModel(application) {
    private val db = ForexDatabase.getDatabase(application)
    val offlinePriceRepository = ForexOfflinePriceRepository(db.historicalPriceDao())
    private val repository = ForexRepository(db.forexDao(), offlinePriceRepository, application.applicationContext)
    private val authManager = ForexAuthManager(application.applicationContext, db.forexDao())

    // Offline status tracking for chart UI
    private val _isOfflineCached = MutableStateFlow(false)
    val isOfflineCached: StateFlow<Boolean> = _isOfflineCached.asStateFlow()

    // User Authentication States
    val currentUser: StateFlow<UserProfile?> = authManager.activeUserProfileFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    private val _isAuthLoading = MutableStateFlow(false)
    val isAuthLoading: StateFlow<Boolean> = _isAuthLoading.asStateFlow()

    private val _authErrorMessage = MutableStateFlow<String?>(null)
    val authErrorMessage: StateFlow<String?> = _authErrorMessage.asStateFlow()

    fun clearAuthError() {
        _authErrorMessage.value = null
    }

    fun signUpWithEmail(email: String, password: String, displayName: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _isAuthLoading.value = true
            _authErrorMessage.value = null
            val result = authManager.signUpWithEmail(email, password, displayName)
            _isAuthLoading.value = false
            if (result.success) {
                onResult(true)
            } else {
                _authErrorMessage.value = result.errorMessage
                onResult(false)
            }
        }
    }

    fun signInWithEmail(email: String, password: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _isAuthLoading.value = true
            _authErrorMessage.value = null
            val result = authManager.signInWithEmail(email, password)
            _isAuthLoading.value = false
            if (result.success) {
                onResult(true)
            } else {
                _authErrorMessage.value = result.errorMessage
                onResult(false)
            }
        }
    }

    fun signInWithGoogle(onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _isAuthLoading.value = true
            _authErrorMessage.value = null
            val result = authManager.signInWithGoogle()
            _isAuthLoading.value = false
            if (result.success) {
                onResult(true)
            } else {
                _authErrorMessage.value = result.errorMessage
                onResult(false)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
        }
    }

    val pairs: StateFlow<List<CurrencyPair>> = repository.pairs

    private val _selectedPair = MutableStateFlow(repository.pairs.value.first())
    val selectedPair: StateFlow<CurrencyPair> = _selectedPair.asStateFlow()

    private val _selectedTimeframe = MutableStateFlow(Timeframe.H1)
    val selectedTimeframe: StateFlow<Timeframe> = _selectedTimeframe.asStateFlow()

    private val _indicatorSettings = MutableStateFlow(IndicatorSettings())
    val indicatorSettings: StateFlow<IndicatorSettings> = _indicatorSettings.asStateFlow()

    fun updateIndicatorSettings(newSettings: IndicatorSettings) {
        _indicatorSettings.value = newSettings
        viewModelScope.launch {
            repository.saveIndicatorSettings(newSettings)
        }
        generateAllSignals(pairs.value)
    }

    private val _inspectedSignal = MutableStateFlow<TradeSignal?>(null)
    val inspectedSignal: StateFlow<TradeSignal?> = _inspectedSignal.asStateFlow()

    private val _liveSignals = MutableStateFlow<List<TradeSignal>>(emptyList())
    val liveSignals: StateFlow<List<TradeSignal>> = _liveSignals.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    fun toggleTheme() {
        _isDarkTheme.value = !_isDarkTheme.value
    }

    // Room DB persistent flows
    val savedSignals = repository.savedSignals.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val customAlerts = repository.customAlerts.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val watchlist = repository.watchlist.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    fun toggleWatchlist(symbol: String) {
        viewModelScope.launch {
            val current = watchlist.value
            if (current.any { it.symbol == symbol }) {
                repository.removeFromWatchlist(symbol)
            } else {
                repository.addToWatchlist(symbol)
            }
        }
    }

    // AI Analysis & Redraw State
    private val _aiReport = MutableStateFlow<String?>(null)
    val aiReport: StateFlow<String?> = _aiReport.asStateFlow()

    private val _isAnalyzingAi = MutableStateFlow(false)
    val isAnalyzingAi: StateFlow<Boolean> = _isAnalyzingAi.asStateFlow()

    private val _aiAutoRedrawEnabled = MutableStateFlow(true)
    val aiAutoRedrawEnabled: StateFlow<Boolean> = _aiAutoRedrawEnabled.asStateFlow()

    private val _showAiDrawingsOnChart = MutableStateFlow(true)
    val showAiDrawingsOnChart: StateFlow<Boolean> = _showAiDrawingsOnChart.asStateFlow()

    private val _aiChartOverlay = MutableStateFlow<AiChartOverlayState?>(null)
    val aiChartOverlay: StateFlow<AiChartOverlayState?> = _aiChartOverlay.asStateFlow()

    private val _aiRedrawStep = MutableStateFlow("")
    val aiRedrawStep: StateFlow<String> = _aiRedrawStep.asStateFlow()

    private val _aiChatMessages = MutableStateFlow<List<AiChatMessage>>(
        listOf(
            AiChatMessage(
                sender = AiChatSender.AI,
                text = "👋 Hello Trader! I'm your institutional AI Market Analyst. I can perform in-depth technical analysis and dynamically **redraw key support/resistance zones, trendlines, and Fibonacci levels** directly onto your chart in real-time.",
                suggestedPrompts = listOf(
                    "🪄 Redraw Key Support & Resistance",
                    "📈 Draw Dynamic Trendlines",
                    "📐 Plot Fibonacci Retracement",
                    "🎯 Breakout & Target Levels (TP/SL)",
                    "🔍 Full Technical Breakdown"
                )
            )
        )
    )
    val aiChatMessages: StateFlow<List<AiChatMessage>> = _aiChatMessages.asStateFlow()

    fun toggleAiAutoRedraw(enabled: Boolean? = null) {
        _aiAutoRedrawEnabled.value = enabled ?: !_aiAutoRedrawEnabled.value
    }

    fun toggleShowAiDrawingsOnChart() {
        _showAiDrawingsOnChart.value = !_showAiDrawingsOnChart.value
    }

    fun clearAiChartDrawings() {
        _aiChartOverlay.value = null
    }

    private val _isChartLoading = MutableStateFlow(false)
    val isChartLoading: StateFlow<Boolean> = _isChartLoading.asStateFlow()

    private val _latestSnapshotBitmap = MutableStateFlow<Bitmap?>(null)
    val latestSnapshotBitmap: StateFlow<Bitmap?> = _latestSnapshotBitmap.asStateFlow()

    fun analyzeSnapshotWithAi(bitmap: Bitmap) {
        _latestSnapshotBitmap.value = bitmap
        runAiTechnicalAnalysis(isSnapshot = true)
    }

    fun clearSnapshot() {
        _latestSnapshotBitmap.value = null
    }

    // Real-time WebSocket Stream & Market Ticks
    val webSocketStats: StateFlow<WebSocketStats> = repository.webSocketStats
    val webSocketStatus: StateFlow<WebSocketStatus> = repository.webSocketStatus
    val marketTicks: SharedFlow<MarketTick> = repository.marketTicks

    /** Health of the real REST market data chain (Alpha Vantage -> FCS -> simulation). */
    val marketDataHealth: StateFlow<MarketDataHealth> = repository.marketDataHealth

    private val _candleVersion = MutableStateFlow(0L)
    val candleVersion: StateFlow<Long> = _candleVersion.asStateFlow()

    private val _lastTick = MutableStateFlow<MarketTick?>(null)
    val lastTick: StateFlow<MarketTick?> = _lastTick.asStateFlow()

    init {
        // Load persisted indicator settings if previously customized
        viewModelScope.launch {
            repository.savedIndicatorSettings.collect { saved ->
                if (saved != null) {
                    _indicatorSettings.value = saved
                }
            }
        }

        // Pre-populate default favorites if watchlist is empty
        viewModelScope.launch {
            val initial = repository.watchlist.first()
            if (initial.isEmpty()) {
                repository.addToWatchlist("EUR/USD")
                repository.addToWatchlist("GBP/USD")
                repository.addToWatchlist("XAU/USD")
                repository.addToWatchlist("BTC/USD")
            }
        }

        // Collect real-time tick updates
        viewModelScope.launch(Dispatchers.Default) {
            repository.startLivePriceStream().collect { updatedPairs ->
                // Update selected pair if changed
                updatedPairs.find { it.symbol == _selectedPair.value.symbol }?.let {
                    _selectedPair.value = it
                }
                // Generate live signals across active pairs
                generateAllSignals(updatedPairs)
                // Real-time evaluation of custom price & technical alerts
                evaluateActiveAlerts(updatedPairs)
            }
        }

        // Observe market ticks to notify chart of real-time candlestick shape changes
        viewModelScope.launch(Dispatchers.Default) {
            repository.marketTicks.collect { tick ->
                if (tick.symbol == _selectedPair.value.symbol) {
                    _lastTick.value = tick
                    _candleVersion.value = System.currentTimeMillis()
                }
            }
        }
    }

    fun reconnectWebSocket() {
        repository.reconnectWebSocket()
    }

    fun disconnectWebSocket() {
        repository.disconnectWebSocket()
    }


    private fun generateAllSignals(currentPairs: List<CurrencyPair>) {
        val signals = currentPairs.map { pair ->
            val candles = repository.getCandles(pair.symbol, _selectedTimeframe.value)
            TechnicalAnalysisEngine.generateTradeSignal(pair, candles, _selectedTimeframe.value, _indicatorSettings.value)
        }
        _liveSignals.value = signals
    }

    /**
     * Pull fresh market data for the selected symbol: validated candle history via the
     * Alpha Vantage -> FCS chain (force refresh) plus a realtime spot quote, then rebuilds
     * signals across all pairs. Falls back silently to cached Room history offline.
     */
    fun refreshMarketData() {
        viewModelScope.launch(Dispatchers.Default) {
            triggerChartLoading(650)
            repository.refreshMarketSnapshot(_selectedPair.value.symbol, _selectedTimeframe.value)
            repository.refreshLatestQuoteFor(_selectedPair.value.symbol)
            generateAllSignals(pairs.value)
        }
    }

    private fun parentTrendAlignment(currentTf: Timeframe): List<Pair<String, String>> {
        val all = Timeframe.values().toList()
        val idx = all.indexOf(currentTf)
        if (idx < 0) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (higher in all.drop(idx + 1).take(2)) {
            val candles = repository.getCandles(_selectedPair.value.symbol, higher)
            if (candles.size >= 55) {
                val closes = candles.map { it.close }
                val fast = TechnicalAnalysisEngine.calculateEMA(closes, _indicatorSettings.value.emaPeriod1).lastOrNull { it != null }
                val slow = TechnicalAnalysisEngine.calculateEMA(closes, _indicatorSettings.value.emaPeriod2).lastOrNull { it != null }
                if (fast != null && slow != null) {
                    out += higher.label to if (fast > slow) "Bullish" else "Bearish"
                }
            }
        }
        return out
    }

    private fun activeOverlayNames(): String {
        val settings = _indicatorSettings.value
        val overlays = mutableListOf<String>()
        if (settings.showSma20) overlays.add("SMA ${settings.smaPeriod1}")
        if (settings.showSma50) overlays.add("SMA ${settings.smaPeriod2}")
        if (settings.showEma20) overlays.add("EMA ${settings.emaPeriod1}")
        if (settings.showEma50) overlays.add("EMA ${settings.emaPeriod2}")
        if (settings.showEma200) overlays.add("EMA ${settings.emaPeriod3}")
        if (settings.showBollingerBands) overlays.add("Bollinger Bands")
        if (settings.showSupportResistance) overlays.add("Support/Resistance")
        if (settings.showRsiSubchart) overlays.add("RSI (${settings.rsiPeriod})")
        if (settings.showMacdSubchart) overlays.add("MACD")
        if (settings.showPatterns) overlays.add("Pattern Markers")
        return overlays.joinToString(", ").ifEmpty { "Clean Candlesticks" }
    }

    fun triggerChartLoading(durationMs: Long = 450) {
        viewModelScope.launch {
            _isChartLoading.value = true
            delay(durationMs)
            _isChartLoading.value = false
        }
    }

    fun selectPair(pair: CurrencyPair) {
        if (_selectedPair.value.symbol != pair.symbol) {
            _selectedPair.value = pair
            _inspectedSignal.value = null
            triggerChartLoading(400)
        }
    }

    fun selectPairForCurrency(currency: String) {
        val targetSymbol = when (currency.uppercase()) {
            "USD" -> "EUR/USD"
            "EUR" -> "EUR/USD"
            "GBP" -> "GBP/USD"
            "JPY" -> "USD/JPY"
            "AUD" -> "AUD/USD"
            "CAD" -> "USD/CAD"
            "XAU", "GOLD" -> "XAU/USD"
            "BTC", "CRYPTO" -> "BTC/USD"
            else -> "EUR/USD"
        }
        val p = pairs.value.find { it.symbol == targetSymbol } ?: pairs.value.first()
        selectPair(p)
    }

    fun selectTimeframe(tf: Timeframe) {
        if (_selectedTimeframe.value != tf) {
            _selectedTimeframe.value = tf
            triggerChartLoading(400)
        }
    }

    fun refreshChartData() {
        triggerChartLoading(600)
    }

    fun getCandlesForSelectedPair(): List<CandleStick> {
        return repository.getCandles(_selectedPair.value.symbol, _selectedTimeframe.value)
    }

    fun toggleIndicator(update: (IndicatorSettings) -> IndicatorSettings) {
        val updated = update(_indicatorSettings.value)
        _indicatorSettings.value = updated
        viewModelScope.launch {
            repository.saveIndicatorSettings(updated)
        }
        generateAllSignals(pairs.value)
    }

    fun setInspectedSignal(signal: TradeSignal?) {
        _inspectedSignal.value = signal
        if (signal != null) {
            val p = pairs.value.find { it.symbol == signal.pairSymbol }
            if (p != null) _selectedPair.value = p
            _selectedTimeframe.value = signal.timeframe
        }
    }

    fun saveSignal(signal: TradeSignal) {
        viewModelScope.launch {
            repository.saveSignal(signal)
        }
    }

    fun deleteSavedSignal(id: String) {
        viewModelScope.launch {
            repository.deleteSavedSignal(id)
        }
    }

    fun createAlert(pairSymbol: String, condition: AlertCondition, targetValue: Double, note: String) {
        viewModelScope.launch {
            val alert = CustomAlert(
                id = "alert_${System.currentTimeMillis()}",
                pairSymbol = pairSymbol,
                condition = condition,
                targetValue = targetValue,
                note = note
            )
            repository.createAlert(alert)
        }
    }

    fun toggleAlert(id: String, active: Boolean) {
        viewModelScope.launch {
            repository.toggleAlert(id, active)
        }
    }

    fun deleteAlert(id: String) {
        viewModelScope.launch {
            repository.deleteAlert(id)
        }
    }

    private fun evaluateActiveAlerts(currentPairs: List<CurrencyPair>) {
        val alerts = customAlerts.value.filter { it.isActive && !it.isTriggered }
        if (alerts.isEmpty()) return

        currentPairs.forEach { pair ->
            val pairAlerts = alerts.filter { it.pairSymbol == pair.symbol }
            if (pairAlerts.isNotEmpty()) {
                val candles = repository.getCandles(pair.symbol, _selectedTimeframe.value)
                val closes = candles.map { it.close }
                val currentRsi = TechnicalAnalysisEngine.calculateRSI(closes, 14).lastOrNull() ?: 50.0

                pairAlerts.forEach { alert ->
                    val condition = try { AlertCondition.valueOf(alert.conditionType) } catch (e: Exception) { AlertCondition.PRICE_ABOVE }
                    var triggered = false
                    var alertTitle = ""
                    var alertMsg = ""

                    when (condition) {
                        AlertCondition.PRICE_ABOVE -> {
                            if (pair.currentPrice >= alert.targetValue) {
                                triggered = true
                                alertTitle = "🚨 Price Alert: ${pair.symbol} Above ${alert.targetValue}"
                                alertMsg = "${pair.symbol} reached ${pair.currentPrice}. ${alert.note}"
                            }
                        }
                        AlertCondition.PRICE_BELOW -> {
                            if (pair.currentPrice <= alert.targetValue) {
                                triggered = true
                                alertTitle = "🚨 Price Alert: ${pair.symbol} Below ${alert.targetValue}"
                                alertMsg = "${pair.symbol} dropped to ${pair.currentPrice}. ${alert.note}"
                            }
                        }
                        AlertCondition.RSI_OVERSOLD -> {
                            val threshold = if (alert.targetValue > 0) alert.targetValue else 30.0
                            if (currentRsi <= threshold) {
                                triggered = true
                                alertTitle = "📊 RSI Oversold Alert: ${pair.symbol} (${String.format(Locale.US, "%.1f", currentRsi)})"
                                alertMsg = "RSI fell to ${String.format(Locale.US, "%.1f", currentRsi)} below threshold $threshold."
                            }
                        }
                        AlertCondition.RSI_OVERBOUGHT -> {
                            val threshold = if (alert.targetValue > 0) alert.targetValue else 70.0
                            if (currentRsi >= threshold) {
                                triggered = true
                                alertTitle = "📊 RSI Overbought Alert: ${pair.symbol} (${String.format(Locale.US, "%.1f", currentRsi)})"
                                alertMsg = "RSI rose to ${String.format(Locale.US, "%.1f", currentRsi)} above threshold $threshold."
                            }
                        }
                        AlertCondition.MA_BULLISH_CROSS -> {
                            val e20 = TechnicalAnalysisEngine.calculateEMA(closes, 20).lastOrNull() ?: 0.0
                            val e50 = TechnicalAnalysisEngine.calculateEMA(closes, 50).lastOrNull() ?: 0.0
                            if (e20 > e50) {
                                triggered = true
                                alertTitle = "📈 Golden Cross Alert: ${pair.symbol}"
                                alertMsg = "EMA 20 crossed above EMA 50 at price ${pair.currentPrice}."
                            }
                        }
                        AlertCondition.MA_BEARISH_CROSS -> {
                            val e20 = TechnicalAnalysisEngine.calculateEMA(closes, 20).lastOrNull() ?: 0.0
                            val e50 = TechnicalAnalysisEngine.calculateEMA(closes, 50).lastOrNull() ?: 0.0
                            if (e20 < e50) {
                                triggered = true
                                alertTitle = "📉 Death Cross Alert: ${pair.symbol}"
                                alertMsg = "EMA 20 crossed below EMA 50 at price ${pair.currentPrice}."
                            }
                        }
                    }

                    if (triggered) {
                        viewModelScope.launch {
                            repository.toggleAlert(alert.id, active = false)
                            sendSystemNotification(alertTitle, alertMsg)
                        }
                    }
                }
            }
        }
    }

    private fun sendSystemNotification(title: String, message: String) {
        try {
            val context = getApplication<Application>()
            val channelId = "price_alerts_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "Price & Technical Alerts"
                val descriptionText = "Real-time alerts for price targets, RSI, and moving average crossovers"
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(channelId, name, importance).apply {
                    description = descriptionText
                    enableVibration(true)
                }
                val notificationManager: NotificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)

            if (ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun redrawChartWithAi(focusType: String? = null) {
        viewModelScope.launch {
            val pair = _selectedPair.value
            val tf = _selectedTimeframe.value
            val candles = getCandlesForSelectedPair()
            
            _aiRedrawStep.value = "Scanning historical high/low pivots..."
            _aiChartOverlay.value = (_aiChartOverlay.value ?: AiChartOverlayState(pair.symbol, tf)).copy(
                isRedrawing = true,
                redrawStep = "Scanning historical pivots..."
            )
            delay(280)

            _aiRedrawStep.value = "Calculating Demand/Supply zones & Fibonacci levels..."
            _aiChartOverlay.value = _aiChartOverlay.value?.copy(
                isRedrawing = true,
                redrawStep = "Calculating Fibonacci ratios & key zones..."
            )
            delay(280)

            _aiRedrawStep.value = "Plotting AI trendlines & target vectors on chart..."
            val newOverlay = TechnicalAnalysisEngine.generateAiChartOverlay(pair, candles, tf, focusType)
            _aiChartOverlay.value = newOverlay.copy(
                isRedrawing = false,
                redrawStep = "Chart redrawn successfully"
            )
            _showAiDrawingsOnChart.value = true
            _aiRedrawStep.value = ""
        }
    }

    /**
     * Chat entry point for the AI analyst.
     *
     * The reply is never a canned paragraph: every turn re-reads the live candles for the
     * selected pair/timeframe, recomputes the full indicator stack (EMA/RSI/MACD/ADX/Bollinger/
     * ATR + volatility sizing) into a [DynamicAiResponseEngine.MarketAnalysisContext], detects
     * the user's intent, optionally redraws the chart overlay from the same candles, then asks
     * OpenRouter to answer the question grounded in the deterministic fact sheet. If the LLM
     * is not configured (or fails), the dynamic local engine composes the answer from the same
     * numbers — so the response always reflects current data, in either mode.
     */
    fun sendAiChatMessage(userText: String, requestedRedrawType: String? = null) {
        if (userText.isBlank()) return

        val userMsg = AiChatMessage(
            sender = AiChatSender.USER,
            text = userText.trim()
        )
        _aiChatMessages.value = _aiChatMessages.value + userMsg

        viewModelScope.launch {
            _isAnalyzingAi.value = true
            try {
                val pair = _selectedPair.value
                val tf = _selectedTimeframe.value
                val settings = _indicatorSettings.value
                val candles = getCandlesForSelectedPair()
                val ctx = DynamicAiResponseEngine.buildContext(
                    pair = pair,
                    candles = candles,
                    timeframe = tf,
                    settings = settings,
                    parentTrends = parentTrendAlignment(tf)
                )
                val intent = DynamicAiResponseEngine.detectIntent(userText, requestedRedrawType)

                var overlayResult = _aiChartOverlay.value
                    ?.takeIf { it.pairSymbol == pair.symbol && it.timeframe == tf }

                if (DynamicAiResponseEngine.shouldRedrawChart(userText, _aiAutoRedrawEnabled.value, intent)) {
                    _aiRedrawStep.value = "AI analyzing ${pair.symbol} structure..."
                    _aiChartOverlay.value = (overlayResult ?: AiChartOverlayState(pair.symbol, tf)).copy(
                        isRedrawing = true,
                        redrawStep = "Recomputing pivots & ATR bands on ${tf.label}..."
                    )
                    delay(220)
                    overlayResult = TechnicalAnalysisEngine.generateAiChartOverlay(pair, candles, tf, requestedRedrawType)
                    _aiChartOverlay.value = overlayResult.copy(
                        isRedrawing = false,
                        redrawStep = "Chart redrawn from ${candles.size} validated candles"
                    )
                    _showAiDrawingsOnChart.value = true
                    _aiRedrawStep.value = ""
                }

                val patternNames = ctx.patterns.joinToString(", ") { it.patternType.title }
                    .ifEmpty { "Consolidation" }

                val llmAnswer = repository.fetchOpenRouterAnalysis(
                    symbol = pair.symbol,
                    timeframe = tf.label,
                    currentPrice = pair.currentPrice,
                    signalType = ctx.signal.type.title,
                    rsiValue = ctx.signal.rsiValue,
                    patterns = patternNames,
                    isSnapshotAnalysis = false,
                    activeOverlays = activeOverlayNames(),
                    userQuestion = userText,
                    quantitativeContext = DynamicAiResponseEngine.quantitativeFactSheet(ctx)
                )

                val answer = llmAnswer ?: DynamicAiResponseEngine.compose(ctx, intent, userText)

                // Perception: pacing scales with content length instead of fixed fake waits.
                delay((answer.length / 40L).coerceIn(80L, 550L))

                val aiResponse = AiChatMessage(
                    sender = AiChatSender.AI,
                    text = answer,
                    chartOverlay = overlayResult,
                    suggestedPrompts = DynamicAiResponseEngine.followUpPrompts(intent)
                )
                _aiChatMessages.value = _aiChatMessages.value + aiResponse
                _aiReport.value = answer
            } finally {
                _isAnalyzingAi.value = false
            }
        }
    }

    /**
     * Full-chart analysis (report panel + optional auto-redraw). Same data-driven path as
     * the chat, without the intent routing: overview composition locally, LLM when configured.
     */
    fun runAiTechnicalAnalysis(isSnapshot: Boolean = false) {
        viewModelScope.launch {
            _isAnalyzingAi.value = true
            _aiReport.value = null
            try {

            val pair = _selectedPair.value
            val tf = _selectedTimeframe.value
            val settings = _indicatorSettings.value
            val candles = getCandlesForSelectedPair()
            val ctx = DynamicAiResponseEngine.buildContext(
                pair = pair,
                candles = candles,
                timeframe = tf,
                settings = settings,
                parentTrends = parentTrendAlignment(tf)
            )
            val activeOverlaysStr = activeOverlayNames()

            // Trigger AI chart redraw from the same candles if enabled
            if (_aiAutoRedrawEnabled.value) {
                _aiRedrawStep.value = "AI analyzing ${pair.symbol} structure..."
                _aiChartOverlay.value = (_aiChartOverlay.value ?: AiChartOverlayState(pair.symbol, tf)).copy(
                    isRedrawing = true,
                    redrawStep = "Scanning chart extremes & ATR volatility envelope..."
                )
                delay(250)
                val newOverlay = TechnicalAnalysisEngine.generateAiChartOverlay(pair, candles, tf)
                _aiChartOverlay.value = newOverlay.copy(isRedrawing = false)
                _showAiDrawingsOnChart.value = true
                _aiRedrawStep.value = ""
            }

            val patternNames = ctx.patterns.joinToString(", ") { it.patternType.title }
                .ifEmpty { "Consolidation" }

            val openRouterResult = repository.fetchOpenRouterAnalysis(
                symbol = pair.symbol,
                timeframe = tf.label,
                currentPrice = pair.currentPrice,
                signalType = ctx.signal.type.title,
                rsiValue = ctx.signal.rsiValue,
                patterns = patternNames,
                isSnapshotAnalysis = isSnapshot,
                activeOverlays = activeOverlaysStr,
                quantitativeContext = DynamicAiResponseEngine.quantitativeFactSheet(ctx)
            )

            val report = if (openRouterResult != null) {
                openRouterResult
            } else {
                val overview = DynamicAiResponseEngine.compose(
                    ctx,
                    DynamicAiResponseEngine.AiIntent.MARKET_OVERVIEW,
                    userText = if (isSnapshot) "Confirm the captured chart snapshot before I execute." else null
                )
                val sourceLine = when (marketDataHealth.value.source) {
                    com.example.forex.data.remote.MarketDataSource.ALPHA_VANTAGE ->
                        "Inputs: live Alpha Vantage candles (${candles.size} bars, quality ${ctx.qualityScore}/100)."
                    com.example.forex.data.remote.MarketDataSource.FCS_API ->
                        "Inputs: FCS API candles (${candles.size} bars, quality ${ctx.qualityScore}/100)."
                    else ->
                        "Inputs: local/simulated candle history (${candles.size} bars). Configure ALPHAVANTAGE_API_KEY or FCS_API_KEY in the Secrets panel for live market data."
                }
                if (isSnapshot) {
                    "📸 SNAPSHOT CONFIRMATION REPORT — MANUAL TRADER CHECKLIST\n" +
                        "Asset: ${pair.symbol} | Timeframe: ${tf.label} | Price at capture: ${pair.currentPrice}\n" +
                        "Active Overlays: $activeOverlaysStr\n\n$overview\n\n" +
                        "⚠️ Risk & manipulation warnings:\n" +
                        "• Beware of liquidity sweeps below ${TechnicalAnalysisEngine.fmtPrice(ctx.supports.lastOrNull() ?: pair.low24h, pair.pipSize)} / above ${TechnicalAnalysisEngine.fmtPrice(ctx.resistances.lastOrNull() ?: pair.high24h, pair.pipSize)}.\n" +
                        "• Await a ${tf.label} candle close before executing; keep account risk at 1% (${TechnicalAnalysisEngine.fmt(ctx.sizing.suggestedLots, 2)} lots suggested).\n" +
                        "• ${sourceLine}"
                } else {
                    "📊 AI TECHNICAL ANALYSIS REPORT (Dynamic Rule Engine v2 — ATR-aware)\n" +
                        "$sourceLine\n\n$overview"
                }
            }

            _aiReport.value = report
            } finally {
                _isAnalyzingAi.value = false
            }
        }
    }

    // --- Economic Calendar, VPVR, Order Blocks, and Strategy Backtester ---
    val economicEvents: StateFlow<List<EconomicEvent>> = MutableStateFlow(TechnicalAnalysisEngine.getEconomicEvents()).asStateFlow()

    private val _isVolumeProfileEnabled = MutableStateFlow(true)
    val isVolumeProfileEnabled: StateFlow<Boolean> = _isVolumeProfileEnabled.asStateFlow()

    private val _isOrderBlocksEnabled = MutableStateFlow(true)
    val isOrderBlocksEnabled: StateFlow<Boolean> = _isOrderBlocksEnabled.asStateFlow()

    private val _isEconomicFlagsEnabled = MutableStateFlow(true)
    val isEconomicFlagsEnabled: StateFlow<Boolean> = _isEconomicFlagsEnabled.asStateFlow()

    fun toggleVolumeProfile() {
        _isVolumeProfileEnabled.value = !_isVolumeProfileEnabled.value
        updateIndicatorSettings(_indicatorSettings.value.copy(showVolumeProfile = _isVolumeProfileEnabled.value))
    }

    fun toggleOrderBlocks() {
        _isOrderBlocksEnabled.value = !_isOrderBlocksEnabled.value
        updateIndicatorSettings(_indicatorSettings.value.copy(showOrderBlocks = _isOrderBlocksEnabled.value))
    }

    fun toggleEconomicFlags() {
        _isEconomicFlagsEnabled.value = !_isEconomicFlagsEnabled.value
        updateIndicatorSettings(_indicatorSettings.value.copy(showEconomicFlags = _isEconomicFlagsEnabled.value))
    }

    // Strategy Backtester Flow
    private val _backtestResult = MutableStateFlow<BacktestResult?>(null)
    val backtestResult: StateFlow<BacktestResult?> = _backtestResult.asStateFlow()

    private val _isBacktesting = MutableStateFlow(false)
    val isBacktesting: StateFlow<Boolean> = _isBacktesting.asStateFlow()

    private val _aiBacktestAudit = MutableStateFlow<String?>(null)
    val aiBacktestAudit: StateFlow<String?> = _aiBacktestAudit.asStateFlow()

    private val _isAuditingBacktest = MutableStateFlow(false)
    val isAuditingBacktest: StateFlow<Boolean> = _isAuditingBacktest.asStateFlow()

    fun runStrategyBacktest(
        strategy: BacktestStrategy = BacktestStrategy.EMA_CROSSOVER,
        initialCapital: Double = 10000.0,
        riskPercentPerTrade: Double = 2.0,
        slPips: Double = strategy.defaultSlPips,
        tpPips: Double = strategy.defaultTpPips
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBacktesting.value = true
            _aiBacktestAudit.value = null
            delay(400) // Brief calculation delay
            val candles = getCandlesForSelectedPair()
            val result = TechnicalAnalysisEngine.runStrategyBacktest(
                candles = candles,
                pair = _selectedPair.value,
                strategy = strategy,
                initialBalance = initialCapital,
                riskPercentPerTrade = riskPercentPerTrade,
                customSlPips = slPips,
                customTpPips = tpPips
            )
            _backtestResult.value = result
            _isBacktesting.value = false
        }
    }

    fun auditBacktestWithAi(result: BacktestResult) {
        viewModelScope.launch(Dispatchers.IO) {
            _isAuditingBacktest.value = true
            
            val prompt = """
                Perform an institutional AI Strategy Audit on these Backtest Results:
                - Strategy: ${result.strategy.title} (${result.strategy.description})
                - Asset: ${result.pairSymbol} | Timeframe: ${result.timeframeLabel}
                - Initial Capital: ${String.format(Locale.US, "$%.2f", result.initialBalance)} | Final Capital: ${String.format(Locale.US, "$%.2f", result.finalBalance)}
                - Total Trades: ${result.totalTrades} | Win Rate: ${String.format(Locale.US, "%.1f%%", result.winRatePercentage)}
                - Net Profit: ${String.format(Locale.US, "$%.2f (%.1f%%)", result.netProfitAmount, result.netProfitPercentage)}
                - Profit Factor: ${String.format(Locale.US, "%.2f", result.profitFactor)}
                - Max Drawdown: ${String.format(Locale.US, "%.1f%%", result.maxDrawdownPercentage)}
                - Avg Win: ${String.format(Locale.US, "$%.2f", result.avgWinAmount)} | Avg Loss: ${String.format(Locale.US, "$%.2f", result.avgLossAmount)}

                Provide an institutional summary breaking down:
                1. 📊 Strategy Performance & Edge Analysis
                2. 🛡️ Risk Management & Drawdown Evaluation
                3. ⚡ Key Vulnerabilities (Ranging vs Trending Markets)
                4. 🎯 Optimization Recommendations to Improve Win Rate & Profit Factor
            """.trimIndent()

            val llmResult = repository.fetchOpenRouterAnalysis(
                symbol = _selectedPair.value.symbol,
                timeframe = _selectedTimeframe.value.label,
                currentPrice = _selectedPair.value.currentPrice,
                signalType = "Strategy Backtest Audit",
                rsiValue = 50.0,
                patterns = "Backtest Results",
                isSnapshotAnalysis = false,
                activeOverlays = prompt
            )

            if (llmResult != null) {
                _aiBacktestAudit.value = llmResult
            } else {
                val auditBuilder = StringBuilder()
                auditBuilder.append("🤖 INSTITUTIONAL AI STRATEGY AUDIT REPORT\n\n")
                auditBuilder.append("1. 📊 STRATEGY EDGE ANALYSIS:\n")
                auditBuilder.append("• Strategy: ${result.strategy.title}\n")
                auditBuilder.append("• Win Rate: ${String.format(Locale.US, "%.1f%%", result.winRatePercentage)} across ${result.totalTrades} trades on ${result.pairSymbol}.\n")
                auditBuilder.append("• Net Profit: ${String.format(Locale.US, "$%.2f (+%.1f%%)", result.netProfitAmount, result.netProfitPercentage)}\n")
                auditBuilder.append("• Profit Factor: ${String.format(Locale.US, "%.2f", result.profitFactor)} (${if (result.profitFactor > 1.5) "Strong Positive Edge" else "Marginal Edge"})\n\n")

                auditBuilder.append("2. 🛡️ RISK & DRAWDOWN EVALUATION:\n")
                auditBuilder.append("• Max Drawdown: ${String.format(Locale.US, "%.1f%%", result.maxDrawdownPercentage)} (Risk Tolerance: ${if (result.maxDrawdownPercentage < 15.0) "HEALTHY" else "ELEVATED"})\n")
                auditBuilder.append("• Avg Win vs Avg Loss: ${String.format(Locale.US, "$%.2f", result.avgWinAmount)} win / ${String.format(Locale.US, "$%.2f", result.avgLossAmount)} loss\n\n")

                auditBuilder.append("3. 🎯 OPTIMIZATION RECOMMENDATIONS:\n")
                auditBuilder.append("• 1. Filters: Combine with RSI Momentum Filter (<40 oversold / >60 overbought) to filter out false breakouts during choppy sideways consolidation.\n")
                auditBuilder.append("• 2. Trailing SL: Implement trailing stop-loss equal to 1.5x ATR once price moves +1.0x R:R in profit to lock in gains.\n")
                auditBuilder.append("• 3. Multi-Timeframe Alignment: Only take trades that align with higher timeframe (4H/1D) trend direction.\n")

                _aiBacktestAudit.value = auditBuilder.toString()
            }
            _isAuditingBacktest.value = false
        }
    }

}
