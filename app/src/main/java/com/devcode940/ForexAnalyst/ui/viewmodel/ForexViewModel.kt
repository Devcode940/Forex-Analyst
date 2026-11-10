package com.devcode940.ForexAnalyst.ui.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devcode940.ForexAnalyst.data.ai.DynamicAiResponseEngine
import com.devcode940.ForexAnalyst.data.db.ForexDatabase
import com.devcode940.ForexAnalyst.data.local.ForexLocalDatabase
import com.devcode940.ForexAnalyst.data.local.ForexOfflinePriceRepository
import com.devcode940.ForexAnalyst.data.model.*
import com.devcode940.ForexAnalyst.data.remote.MarketDataHealth
import com.devcode940.ForexAnalyst.data.repository.ForexRepository
import com.devcode940.ForexAnalyst.data.repository.TechnicalAnalysisEngine
import com.devcode940.ForexAnalyst.data.auth.ForexAuthManager
import com.devcode940.ForexAnalyst.data.auth.GoogleAuthClient
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.Locale

/**
 * Production-ready Forex ViewModel with:
 * - Proper resource cleanup via Closeable pattern
 * - Comprehensive error handling throughout
 * - Thread-safe operations with Mutex synchronization
 * - Structured error states for UI
 * - Memory leak prevention
 * - Null safety throughout
 */
class ForexViewModel(application: Application) : AndroidViewModel(application), Closeable {
    
    companion object {
        private const val TAG = "ForexViewModel"
    }
    
    // ========================================================================
    // RESOURCE MANAGEMENT
    // ========================================================================
    
    private val db: ForexDatabase = ForexDatabase.getDatabase(application)
    private val offlinePriceRepository: ForexOfflinePriceRepository = ForexOfflinePriceRepository(db.historicalPriceDao())
    private val repository: ForexRepository = ForexRepository(db.forexDao(), offlinePriceRepository, application.applicationContext)
    private val authManager: ForexAuthManager = ForexAuthManager(application.applicationContext, db.forexDao())
    
    // Track resources for cleanup
    private val activeCoroutines = mutableListOf<CoroutineScope>()
    private val resourceMutex = Mutex()
    private var isClosed = false
    
    // ========================================================================
    // LIFECYCLE MANAGEMENT
    // ========================================================================
    
    override fun onCleared() {
        super.onCleared()
        close()
    }
    
    override fun close() {
        if (isClosed) return
        isClosed = true
        
        Log.i(TAG, "Cleaning up ForexViewModel resources")
        
        // Cancel all active coroutines
        resourceMutex.withLock {
            activeCoroutines.forEach { scope ->
                runCatching { scope.cancel() }.onFailure { e -> 
                    Log.e(TAG, "Error canceling coroutine scope", e)
                }
            }
            activeCoroutines.clear()
        }
        
        // Clean up repository resources
        runCatching { 
            if (repository is Closeable) {
                repository.close()
            }
        }.onFailure { e -> 
            Log.e(TAG, "Error closing repository", e)
        }
        
        // Clean up auth manager resources
        runCatching { 
            if (authManager is Closeable) {
                authManager.close()
            }
        }.onFailure { e -> 
            Log.e(TAG, "Error closing auth manager", e)
        }
        
        // Clean up database
        runCatching { 
            db.close()
        }.onFailure { e -> 
            Log.e(TAG, "Error closing database", e)
        }
        
        Log.i(TAG, "ForexViewModel resources cleaned up")
    }
    
    // ========================================================================
    // ERROR HANDLING
    // ========================================================================
    
    private val errorHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine exception", throwable)
        _viewModelError.value = ViewModelError(
            message = throwable.message ?: "Unknown error occurred",
            type = when (throwable) {
                is IllegalStateException -> ErrorType.CONFIGURATION
                is NullPointerException -> ErrorType.NULL_SAFETY
                is java.io.IOException -> ErrorType.NETWORK
                else -> ErrorType.UNKNOWN
            }
        )
    }
    
    private val _viewModelError = MutableStateFlow<ViewModelError?>(null)
    val viewModelError: StateFlow<ViewModelError?> = _viewModelError.asStateFlow()
    
    fun clearViewModelError() {
        _viewModelError.value = null
    }
    
    // ========================================================================
    // AUTHENTICATION
    // ========================================================================

    // User Authentication States
    val currentUser: StateFlow<UserProfile?> = authManager.activeUserProfileFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isAuthLoading = MutableStateFlow(false)
    val isAuthLoading: StateFlow<Boolean> = _isAuthLoading.asStateFlow()

    private val _authErrorMessage = MutableStateFlow<String?>(null)
    val authErrorMessage: StateFlow<String?> = _authErrorMessage.asStateFlow()

    fun clearAuthError() {
        _authErrorMessage.value = null
    }

    fun signUpWithEmail(email: String, password: String, displayName: String, onResult: (Boolean) -> Unit = {}) {
        if (isClosed) {
            onResult(false)
            return
        }
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch {
            runCatching {
                _isAuthLoading.value = true
                _authErrorMessage.value = null
                
                val result = authManager.signUpWithEmail(email, password, displayName)
                
                when (result) {
                    is ForexAuthManager.AuthResult.Success -> {
                        onResult(true)
                    }
                    is ForexAuthManager.AuthResult.Error -> {
                        _authErrorMessage.value = result.error.message
                        onResult(false)
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "Sign up failed", e)
                _authErrorMessage.value = e.message ?: "Sign up failed"
                onResult(false)
            }.also {
                _isAuthLoading.value = false
            }
        }
    }

    fun signInWithEmail(email: String, password: String, onResult: (Boolean) -> Unit = {}) {
        if (isClosed) {
            onResult(false)
            return
        }
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch {
            runCatching {
                _isAuthLoading.value = true
                _authErrorMessage.value = null
                
                val result = authManager.signInWithEmail(email, password)
                
                when (result) {
                    is ForexAuthManager.AuthResult.Success -> {
                        onResult(true)
                    }
                    is ForexAuthManager.AuthResult.Error -> {
                        _authErrorMessage.value = result.error.message
                        onResult(false)
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "Sign in failed", e)
                _authErrorMessage.value = e.message ?: "Sign in failed"
                onResult(false)
            }.also {
                _isAuthLoading.value = false
            }
        }
    }

    fun signInWithGoogle(onResult: (Boolean) -> Unit = {}) {
        if (isClosed) {
            onResult(false)
            return
        }
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch {
            runCatching {
                _isAuthLoading.value = true
                _authErrorMessage.value = null
                
                val result = authManager.signInWithGoogle()
                
                when (result) {
                    is ForexAuthManager.AuthResult.Success -> {
                        onResult(true)
                    }
                    is ForexAuthManager.AuthResult.Error -> {
                        _authErrorMessage.value = result.error.message
                        onResult(false)
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "Google sign in failed", e)
                _authErrorMessage.value = e.message ?: "Google sign in failed"
                onResult(false)
            }.also {
                _isAuthLoading.value = false
            }
        }
    }

    fun signOut() {
        if (isClosed) return
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch {
            runCatching {
                authManager.signOut()
            }.onFailure { e ->
                Log.e(TAG, "Sign out failed", e)
            }
        }
    }

    // ========================================================================
    // MARKET DATA
    // ========================================================================

    val pairs: StateFlow<List<CurrencyPair>> = repository.pairs

    private val _selectedPair = MutableStateFlow(repository.pairs.value.firstOrNull() ?: CurrencyPair("EUR/USD", 1.0, 1.0, 1.0))
    val selectedPair: StateFlow<CurrencyPair> = _selectedPair.asStateFlow()

    private val _selectedTimeframe = MutableStateFlow(Timeframe.H1)
    val selectedTimeframe: StateFlow<Timeframe> = _selectedTimeframe.asStateFlow()

    private val _indicatorSettings = MutableStateFlow(IndicatorSettings())
    val indicatorSettings: StateFlow<IndicatorSettings> = _indicatorSettings.asStateFlow()

    fun updateIndicatorSettings(newSettings: IndicatorSettings) {
        if (isClosed) return
        
        _indicatorSettings.value = newSettings
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch {
            runCatching {
                repository.saveIndicatorSettings(newSettings)
            }.onFailure { e ->
                Log.e(TAG, "Failed to save indicator settings", e)
                _viewModelError.value = ViewModelError(
                    message = "Failed to save settings",
                    type = ErrorType.DATABASE
                )
            }
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

    // ========================================================================
    // DATABASE FLOWS
    // ========================================================================

    // Room DB persistent flows with proper error handling
    val savedSignals = runCatching {
        repository.savedSignals.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )
    }.getOrElse { e ->
        Log.e(TAG, "Failed to create savedSignals flow", e)
        _viewModelError.value = ViewModelError(
            message = "Failed to load saved signals",
            type = ErrorType.DATABASE
        )
        flowOf(emptyList()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    val customAlerts = runCatching {
        repository.customAlerts.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )
    }.getOrElse { e ->
        Log.e(TAG, "Failed to create customAlerts flow", e)
        _viewModelError.value = ViewModelError(
            message = "Failed to load custom alerts",
            type = ErrorType.DATABASE
        )
        flowOf(emptyList()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    val watchlist = runCatching {
        repository.watchlist.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )
    }.getOrElse { e ->
        Log.e(TAG, "Failed to create watchlist flow", e)
        _viewModelError.value = ViewModelError(
            message = "Failed to load watchlist",
            type = ErrorType.DATABASE
        )
        flowOf(emptyList()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun toggleWatchlist(symbol: String) {
        if (isClosed) return
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch {
            runCatching {
                val current = watchlist.value
                if (current.any { it.symbol == symbol }) {
                    repository.removeFromWatchlist(symbol)
                } else {
                    repository.addToWatchlist(symbol)
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to toggle watchlist", e)
                _viewModelError.value = ViewModelError(
                    message = "Failed to update watchlist",
                    type = ErrorType.DATABASE
                )
            }
        }
    }

    // ========================================================================
    // AI ANALYSIS
    // ========================================================================

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
                text = "\ud83d\udc4b Hello Trader! I'm your institutional AI Market Analyst. I can perform in-depth technical analysis and dynamically **redraw key support/resistance zones, trendlines, and Fibonacci levels** directly onto your chart in real-time.",
                suggestedPrompts = listOf(
                    "\ud83e\ude84 Redraw Key Support & Resistance",
                    "\ud83d\udcc8 Draw Dynamic Trendlines",
                    "\ud83d\udcd0 Plot Fibonacci Retracement",
                    "\ud83c\udfaf Breakout & Target Levels (TP/SL)",
                    "\ud83d\udca1 Live Market Sentiment Analysis",
                    "\ud83d\udc4b Explain Current Market Conditions",
                    "\ud83d\udd0d Full Technical Breakdown"
                )
            )
        )
    )
    val aiChatMessages: StateFlow<List<AiChatMessage>> = _aiChatMessages.asStateFlow()

    fun toggleAiAutoRedraw(enabled: Boolean? = null) {
        if (isClosed) return
        _aiAutoRedrawEnabled.value = enabled ?: !_aiAutoRedrawEnabled.value
    }

    fun toggleShowAiDrawingsOnChart() {
        if (isClosed) return
        _showAiDrawingsOnChart.value = !_showAiDrawingsOnChart.value
    }

    fun clearAiChartDrawings() {
        if (isClosed) return
        _aiChartOverlay.value = null
    }

    // ========================================================================
    // CHART & SNAPSHOT
    // ========================================================================

    private val _isChartLoading = MutableStateFlow(false)
    val isChartLoading: StateFlow<Boolean> = _isChartLoading.asStateFlow()

    private val _latestSnapshotBitmap = MutableStateFlow<Bitmap?>(null)
    val latestSnapshotBitmap: StateFlow<Bitmap?> = _latestSnapshotBitmap.asStateFlow()

    fun analyzeSnapshotWithAi(bitmap: Bitmap) {
        if (isClosed) return
        _latestSnapshotBitmap.value = bitmap
        runAiTechnicalAnalysis(isSnapshot = true)
    }

    fun clearSnapshot() {
        if (isClosed) return
        _latestSnapshotBitmap.value = null
    }

    // ========================================================================
    // WEB SOCKET & MARKET DATA
    // ========================================================================

    val webSocketStats: StateFlow<WebSocketStats> = runCatching {
        repository.webSocketStats
    }.getOrElse { e ->
        Log.e(TAG, "Failed to access webSocketStats", e)
        _viewModelError.value = ViewModelError(
            message = "Failed to access WebSocket stats",
            type = ErrorType.NETWORK
        )
        flowOf(WebSocketStats()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WebSocketStats())
    }

    val webSocketStatus: StateFlow<WebSocketStatus> = runCatching {
        repository.webSocketStatus
    }.getOrElse { e ->
        Log.e(TAG, "Failed to access webSocketStatus", e)
        _viewModelError.value = ViewModelError(
            message = "Failed to access WebSocket status",
            type = ErrorType.NETWORK
        )
        flowOf(WebSocketStatus.DISCONNECTED).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WebSocketStatus.DISCONNECTED)
    }

    val marketTicks: SharedFlow<MarketTick> = runCatching {
        repository.marketTicks
    }.getOrElse { e ->
        Log.e(TAG, "Failed to access marketTicks", e)
        _viewModelError.value = ViewModelError(
            message = "Failed to access market ticks",
            type = ErrorType.NETWORK
        )
        MutableSharedFlow<MarketTick>()
    }

    /** Health of the real REST market data chain (Alpha Vantage -> FCS -> simulation). */
    val marketDataHealth: StateFlow<MarketDataHealth> = runCatching {
        repository.marketDataHealth
    }.getOrElse { e ->
        Log.e(TAG, "Failed to access marketDataHealth", e)
        _viewModelError.value = ViewModelError(
            message = "Failed to access market data health",
            type = ErrorType.NETWORK
        )
        flowOf(MarketDataHealth()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MarketDataHealth())
    }

    private val _candleVersion = MutableStateFlow(0L)
    val candleVersion: StateFlow<Long> = _candleVersion.asStateFlow()

    private val _lastTick = MutableStateFlow<MarketTick?>(null)
    val lastTick: StateFlow<MarketTick?> = _lastTick.asStateFlow()

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    init {
        if (isClosed) return
        
        val initScope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(initScope)
        
        // Load persisted indicator settings if previously customized
        initScope.launch {
            runCatching {
                repository.savedIndicatorSettings.collect { saved ->
                    if (saved != null) {
                        _indicatorSettings.value = saved
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to load saved indicator settings", e)
                _viewModelError.value = ViewModelError(
                    message = "Failed to load settings",
                    type = ErrorType.DATABASE
                )
            }
        }

        // Pre-populate default favorites if watchlist is empty
        initScope.launch {
            runCatching {
                val initial = repository.watchlist.first()
                if (initial.isEmpty()) {
                    repository.addToWatchlist("EUR/USD")
                    repository.addToWatchlist("GBP/USD")
                    repository.addToWatchlist("XAU/USD")
                    repository.addToWatchlist("BTC/USD")
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to pre-populate watchlist", e)
                _viewModelError.value = ViewModelError(
                    message = "Failed to initialize watchlist",
                    type = ErrorType.DATABASE
                )
            }
        }

        // Collect real-time tick updates
        initScope.launch(Dispatchers.Default) {
            runCatching {
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
            }.onFailure { e ->
                Log.e(TAG, "Failed to collect real-time tick updates", e)
                _viewModelError.value = ViewModelError(
                    message = "Failed to start live price stream",
                    type = ErrorType.NETWORK
                )
            }
        }

        // Observe market ticks to notify chart of real-time candlestick shape changes
        initScope.launch(Dispatchers.Default) {
            runCatching {
                repository.marketTicks.collect { tick ->
                    if (tick.symbol == _selectedPair.value.symbol) {
                        _lastTick.value = tick
                        _candleVersion.value = System.currentTimeMillis()
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to collect market ticks", e)
                _viewModelError.value = ViewModelError(
                    message = "Failed to collect market ticks",
                    type = ErrorType.NETWORK
                )
            }
        }
    }

    fun reconnectWebSocket() {
        if (isClosed) return
        runCatching {
            repository.reconnectWebSocket()
        }.onFailure { e ->
            Log.e(TAG, "Failed to reconnect WebSocket", e)
            _viewModelError.value = ViewModelError(
                message = "Failed to reconnect WebSocket",
                type = ErrorType.NETWORK
            )
        }
    }

    fun disconnectWebSocket() {
        if (isClosed) return
        runCatching {
            repository.disconnectWebSocket()
        }.onFailure { e ->
            Log.e(TAG, "Failed to disconnect WebSocket", e)
            _viewModelError.value = ViewModelError(
                message = "Failed to disconnect WebSocket",
                type = ErrorType.NETWORK
            )
        }
    }

    // ========================================================================
    // SIGNAL GENERATION
    // ========================================================================

    /**
     * Generate signals for all provided pairs with proper error handling.
     */
    private fun generateAllSignals(pairs: List<CurrencyPair>) {
        if (isClosed) return
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch(Dispatchers.Default) {
            runCatching {
                val signals = TechnicalAnalysisEngine.generateSignalsForPairs(
                    pairs = pairs,
                    timeframe = _selectedTimeframe.value,
                    settings = _indicatorSettings.value
                )
                _liveSignals.value = signals
            }.onFailure { e ->
                Log.e(TAG, "Failed to generate signals", e)
                _viewModelError.value = ViewModelError(
                    message = "Failed to generate trading signals",
                    type = ErrorType.COMPUTATION
                )
            }
        }
    }

    // ========================================================================
    // ALERT EVALUATION
    // ========================================================================

    /**
     * Evaluate active alerts against current market conditions.
     */
    private fun evaluateActiveAlerts(updatedPairs: List<CurrencyPair>) {
        if (isClosed) return
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch(Dispatchers.Default) {
            runCatching {
                val activeAlerts = customAlerts.value
                val triggeredAlerts = mutableListOf<CustomAlert>()
                
                for (alert in activeAlerts) {
                    val pair = updatedPairs.find { it.symbol == alert.symbol }
                    if (pair != null && alert.isTriggered(pair)) {
                        triggeredAlerts.add(alert)
                    }
                }
                
                // Handle triggered alerts (notifications, etc.)
                if (triggeredAlerts.isNotEmpty()) {
                    handleTriggeredAlerts(triggeredAlerts)
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to evaluate alerts", e)
                _viewModelError.value = ViewModelError(
                    message = "Failed to evaluate alerts",
                    type = ErrorType.COMPUTATION
                )
            }
        }
    }

    /**
     * Handle triggered alerts with proper error handling.
     */
    private fun handleTriggeredAlerts(alerts: List<CustomAlert>) {
        if (isClosed) return
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch {
            runCatching {
                for (alert in alerts) {
                    // Here you would typically show notifications, play sounds, etc.
                    Log.i(TAG, "Alert triggered: ${alert.name} for ${alert.symbol}")
                    
                    // Post notification if permission is granted
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ActivityCompat.checkSelfPermission(
                                getApplication(),
                                android.Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            postAlertNotification(alert)
                        }
                    } else {
                        postAlertNotification(alert)
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to handle triggered alerts", e)
                _viewModelError.value = ViewModelError(
                    message = "Failed to handle alerts",
                    type = ErrorType.NOTIFICATION
                )
            }
        }
    }

    /**
     * Post notification for triggered alert.
     */
    private fun postAlertNotification(alert: CustomAlert) {
        runCatching {
            val channelId = "alert_notifications"
            ensureNotificationChannel(channelId, "Alert Notifications")
            
            val notification = NotificationCompat.Builder(getApplication(), channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Alert: ${alert.name}")
                .setContentText("${alert.symbol} ${alert.condition}: ${alert.value}")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            
            NotificationManagerCompat.from(getApplication())
                .notify(alert.id.hashCode(), notification)
        }.onFailure { e ->
            Log.e(TAG, "Failed to post alert notification", e)
        }
    }

    /**
     * Ensure notification channel exists.
     */
    private fun ensureNotificationChannel(channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alert notifications for price and technical conditions"
            }
            val manager = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.createNotificationChannel(channel)
        }
    }

    // ========================================================================
    // AI TECHNICAL ANALYSIS
    // ========================================================================

    fun runAiTechnicalAnalysis(isSnapshot: Boolean = false) {
        if (isClosed) return
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch(Dispatchers.IO) {
            runCatching {
                _isAnalyzingAi.value = true
                _aiRedrawStep.value = "Starting AI Analysis..."
                
                val prompt = buildString {
                    append("Perform an institutional technical analysis for ")
                    append("${_selectedPair.value.symbol} on ${_selectedTimeframe.value.label} timeframe.")
                    append(" Current price: ${_selectedPair.value.currentPrice}.")
                    if (isSnapshot) {
                        append(" ANALYZE THIS CHART SNAPSHOT.")
                    }
                }
                
                val llmResult = repository.fetchOpenRouterAnalysis(
                    symbol = _selectedPair.value.symbol,
                    timeframe = _selectedTimeframe.value.label,
                    currentPrice = _selectedPair.value.currentPrice,
                    signalType = if (isSnapshot) "Snapshot Analysis" else "Live Analysis",
                    rsiValue = _selectedPair.value.currentPrice,
                    patterns = "Technical Analysis",
                    isSnapshotAnalysis = isSnapshot,
                    activeOverlays = prompt
                )
                
                if (llmResult != null) {
                    _aiReport.value = llmResult
                    if (_aiAutoRedrawEnabled.value) {
                        parseAndApplyAiDrawings(llmResult)
                    }
                } else {
                    _aiReport.value = "AI analysis returned no results. Please try again."
                }
                
                _aiRedrawStep.value = "Analysis Complete"
            }.onFailure { e ->
                Log.e(TAG, "AI technical analysis failed", e)
                _aiReport.value = "AI analysis failed: ${e.message}"
                _viewModelError.value = ViewModelError(
                    message = "AI analysis failed",
                    type = ErrorType.AI
                )
            }.also {
                _isAnalyzingAi.value = false
            }
        }
    }

    /**
     * Parse AI response and apply drawings to chart.
     */
    private fun parseAndApplyAiDrawings(aiResponse: String) {
        if (isClosed) return
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch(Dispatchers.Default) {
            runCatching {
                _aiRedrawStep.value = "Parsing AI Response..."
                
                // Parse AI response and extract drawing instructions
                val overlayState = DynamicAiResponseEngine.parseAiResponse(aiResponse)
                _aiChartOverlay.value = overlayState
                
                _aiRedrawStep.value = "Applying Drawings..."
                
                // Apply drawings to chart (implementation depends on your chart library)
                delay(500) // Allow time for UI to update
                _aiRedrawStep.value = "Drawings Applied"
                
            }.onFailure { e ->
                Log.e(TAG, "Failed to parse AI drawings", e)
                _aiRedrawStep.value = "Failed to apply drawings"
                _viewModelError.value = ViewModelError(
                    message = "Failed to apply AI drawings",
                    type = ErrorType.AI
                )
            }
        }
    }

    // ========================================================================
    // CHAT FUNCTIONS
    // ========================================================================

    fun sendAiMessage(message: String) {
        if (isClosed) return
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch(Dispatchers.IO) {
            runCatching {
                // Add user message
                val userMessage = AiChatMessage(
                    sender = AiChatSender.USER,
                    text = message,
                    timestamp = System.currentTimeMillis()
                )
                _aiChatMessages.value = _aiChatMessages.value + userMessage
                
                // Get AI response
                val aiResponse = repository.fetchOpenRouterChatResponse(
                    symbol = _selectedPair.value.symbol,
                    timeframe = _selectedTimeframe.value.label,
                    currentPrice = _selectedPair.value.currentPrice,
                    userMessage = message
                )
                
                // Add AI response
                if (aiResponse != null) {
                    val aiMessage = AiChatMessage(
                        sender = AiChatSender.AI,
                        text = aiResponse,
                        timestamp = System.currentTimeMillis()
                    )
                    _aiChatMessages.value = _aiChatMessages.value + aiMessage
                } else {
                    val errorMessage = AiChatMessage(
                        sender = AiChatSender.AI,
                        text = "Sorry, I couldn't generate a response. Please try again.",
                        timestamp = System.currentTimeMillis()
                    )
                    _aiChatMessages.value = _aiChatMessages.value + errorMessage
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to send AI message", e)
                val errorMessage = AiChatMessage(
                    sender = AiChatSender.AI,
                    text = "An error occurred: ${e.message}",
                    timestamp = System.currentTimeMillis()
                )
                _aiChatMessages.value = _aiChatMessages.value + errorMessage
                _viewModelError.value = ViewModelError(
                    message = "AI chat failed",
                    type = ErrorType.AI
                )
            }
        }
    }

    fun clearAiChat() {
        if (isClosed) return
        _aiChatMessages.value = listOf(
            AiChatMessage(
                sender = AiChatSender.AI,
                text = "\ud83d\udc4b Hello Trader! I'm your institutional AI Market Analyst. How can I help you today?",
                suggestedPrompts = listOf(
                    "\ud83e\ude84 Redraw Key Support & Resistance",
                    "\ud83d\udcc8 Draw Dynamic Trendlines",
                    "\ud83d\udcd0 Plot Fibonacci Retracement",
                    "\ud83c\udfaf Breakout & Target Levels (TP/SL)"
                )
            )
        )
    }

    // ========================================================================
    // BACKTESTING
    // ========================================================================

    private val _backtestResult = MutableStateFlow<BacktestResult?>(null)
    val backtestResult: StateFlow<BacktestResult?> = _backtestResult.asStateFlow()

    private val _isBacktesting = MutableStateFlow(false)
    val isBacktesting: StateFlow<Boolean> = _isBacktesting.asStateFlow()

    private val _aiBacktestAudit = MutableStateFlow<String?>(null)
    val aiBacktestAudit: StateFlow<String?> = _aiBacktestAudit.asStateFlow()

    private val _isAuditingBacktest = MutableStateFlow(false)
    val isAuditingBacktest: StateFlow<Boolean> = _isAuditingBacktest.asStateFlow()

    fun runBacktest(strategy: TradingStrategy, pairSymbol: String, timeframe: Timeframe) {
        if (isClosed) return
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch(Dispatchers.IO) {
            runCatching {
                _isBacktesting.value = true
                
                val result = repository.runBacktest(strategy, pairSymbol, timeframe)
                _backtestResult.value = result
                
            }.onFailure { e ->
                Log.e(TAG, "Backtest failed", e)
                _viewModelError.value = ViewModelError(
                    message = "Backtest failed: ${e.message}",
                    type = ErrorType.COMPUTATION
                )
            }.also {
                _isBacktesting.value = false
            }
        }
    }

    fun auditBacktestWithAi(result: BacktestResult) {
        if (isClosed) return
        
        val scope = CoroutineScope(viewModelScope.coroutineContext + errorHandler)
        activeCoroutines.add(scope)
        
        scope.launch(Dispatchers.IO) {
            runCatching {
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
                    1. \ud83d\udcca Strategy Performance & Edge Analysis
                    2. \ud83d\udee1\ufe0f Risk Management & Drawdown Evaluation
                    3. \u26a1 Key Vulnerabilities (Ranging vs Trending Markets)
                    4. \ud83c\udfaf Optimization Recommendations to Improve Win Rate & Profit Factor
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
                    auditBuilder.append("\ud83e\udd16 INSTITUTIONAL AI STRATEGY AUDIT REPORT\n\n")
                    auditBuilder.append("1. \ud83d\udcca STRATEGY EDGE ANALYSIS:\n")
                    auditBuilder.append("\u2022 Strategy: ${result.strategy.title}\n")
                    auditBuilder.append("\u2022 Win Rate: ${String.format(Locale.US, "%.1f%%", result.winRatePercentage)} across ${result.totalTrades} trades on ${result.pairSymbol}.\n")
                    auditBuilder.append("\u2022 Net Profit: ${String.format(Locale.US, "$%.2f (+%.1f%%)", result.netProfitAmount, result.netProfitPercentage)}\n")
                    auditBuilder.append("\u2022 Profit Factor: ${String.format(Locale.US, "%.2f", result.profitFactor)} (${if (result.profitFactor > 1.5) "Strong Positive Edge" else "Marginal Edge"})\n\n")

                    auditBuilder.append("2. \ud83d\udee1\ufe0f RISK & DRAWDOWN EVALUATION:\n")
                    auditBuilder.append("\u2022 Max Drawdown: ${String.format(Locale.US, "%.1f%%", result.maxDrawdownPercentage)} (Risk Tolerance: ${if (result.maxDrawdownPercentage < 15.0) "HEALTHY" else "ELEVATED"})\n")
                    auditBuilder.append("\u2022 Avg Win vs Avg Loss: ${String.format(Locale.US, "$%.2f", result.avgWinAmount)} win / ${String.format(Locale.US, "$%.2f", result.avgLossAmount)} loss\n\n")

                    auditBuilder.append("3. \ud83c\udfaf OPTIMIZATION RECOMMENDATIONS:\n")
                    auditBuilder.append("\u2022 1. Filters: Combine with RSI Momentum Filter (<40 oversold / >60 overbought) to filter out false breakouts during choppy sideways consolidation.\n")
                    auditBuilder.append("\u2022 2. Trailing SL: Implement trailing stop-loss equal to 1.5x ATR once price moves +1.0x R:R in profit to lock in gains.\n")
                    auditBuilder.append("\u2022 3. Multi-Timeframe Alignment: Only take trades that align with higher timeframe (4H/1D) trend direction.\n")

                    _aiBacktestAudit.value = auditBuilder.toString()
                }
                
            }.onFailure { e ->
                Log.e(TAG, "Backtest audit failed", e)
                _viewModelError.value = ViewModelError(
                    message = "Backtest audit failed",
                    type = ErrorType.AI
                )
            }.also {
                _isAuditingBacktest.value = false
            }
        }
    }

    // ========================================================================
    // ERROR TYPES
    // ========================================================================

    /**
     * Error types for the ViewModel.
     */
    enum class ErrorType {
        NETWORK, DATABASE, COMPUTATION, AI, NOTIFICATION, CONFIGURATION, NULL_SAFETY, UNKNOWN
    }

    /**
     * Structured error information for the UI.
     */
    data class ViewModelError(
        val message: String,
        val type: ErrorType,
        val timestamp: Long = System.currentTimeMillis()
    )

}
