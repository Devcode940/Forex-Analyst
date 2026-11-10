package com.devcode940.ForexAnalyst.ui.components

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devcode940.ForexAnalyst.data.model.*
import com.devcode940.ForexAnalyst.data.repository.TechnicalAnalysisEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ChartDrawingTool {
    PAN_ZOOM,
    CROSSHAIR,
    SUPPORT,
    RESISTANCE,
    TRENDLINE,
    FIBONACCI
}

data class UserDrawnLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: ChartDrawingTool,
    val price1: Double,
    val candleIndex1: Int,
    val price2: Double = price1,
    val candleIndex2: Int = candleIndex1
)

@Composable
fun ChartLoadingSkeletonAnimation(
    symbol: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ChartSkeletonLoading")

    val shimmerTranslateX by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 1300f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerX"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val candleBullColor = Color(0xFF10B981).copy(alpha = 0.35f)
    val candleBearColor = Color(0xFFEF4444).copy(alpha = 0.35f)
    val neonCurveColor = Color(0xFF06B6D4)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Horizontal & vertical grid lines
            val numHorizontal = 8
            val stepY = height / numHorizontal
            for (i in 1 until numHorizontal) {
                drawLine(
                    color = gridColor,
                    start = Offset(0f, stepY * i),
                    end = Offset(width, stepY * i),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                )
            }

            val numVertical = 12
            val stepX = width / numVertical
            for (i in 1 until numVertical) {
                drawLine(
                    color = gridColor,
                    start = Offset(stepX * i, 0f),
                    end = Offset(stepX * i, height),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                )
            }

            // Skeleton candlestick bars with shimmer gradient
            val shimmerBrush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.18f),
                    Color.Transparent
                ),
                start = Offset(shimmerTranslateX - 200f, 0f),
                end = Offset(shimmerTranslateX + 200f, height)
            )

            val candleCount = 18
            val candleGap = width / candleCount
            val candleBarWidth = candleGap * 0.45f

            for (i in 0 until candleCount) {
                val cx = candleGap * i + candleGap / 2
                val isBull = i % 2 == 0
                val color = if (isBull) candleBullColor else candleBearColor

                val topY = (height * 0.25f) + (Math.sin(i * 0.8) * (height * 0.2f)).toFloat()
                val bodyHeight = (height * 0.15f) + (Math.cos(i * 0.5) * (height * 0.08f)).toFloat()
                val bottomY = topY + bodyHeight

                val wickTop = topY - (height * 0.05f)
                val wickBottom = bottomY + (height * 0.05f)

                drawLine(
                    color = color.copy(alpha = pulseAlpha * 0.5f),
                    start = Offset(cx, wickTop),
                    end = Offset(cx, wickBottom),
                    strokeWidth = 2f
                )

                drawRect(
                    color = color.copy(alpha = pulseAlpha * 0.7f),
                    topLeft = Offset(cx - candleBarWidth / 2, topY),
                    size = Size(candleBarWidth, bodyHeight)
                )
            }

            // Animated sinusoidal MA curve
            val maPath = Path()
            val pointCount = 30
            for (p in 0..pointCount) {
                val px = (width / pointCount) * p
                val py = (height * 0.5f) + (Math.sin((p * 0.4) + (shimmerTranslateX * 0.005)) * (height * 0.18f)).toFloat()
                if (p == 0) maPath.moveTo(px, py) else maPath.lineTo(px, py)
            }
            drawPath(
                path = maPath,
                color = neonCurveColor.copy(alpha = pulseAlpha),
                style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), shimmerTranslateX * 0.1f))
            )

            // Shimmer highlight overlay
            drawRect(
                brush = shimmerBrush,
                topLeft = Offset.Zero,
                size = size
            )
        }

        // Top Status Badge
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0F172A).copy(alpha = 0.88f))
                .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF10B981).copy(alpha = pulseAlpha))
                )
                Text(
                    text = "RETROFIT REPOSITORY • FETCHING MARKET DATA",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Center Glassmorphism Loading Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.92f)),
            modifier = Modifier
                .padding(24.dp)
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(44.dp),
                        color = Color(0xFF38BDF8),
                        strokeWidth = 3.5.dp
                    )
                    Icon(
                        imageVector = Icons.Default.AutoGraph,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8).copy(alpha = pulseAlpha),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Loading $symbol Chart...",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Fetching live candles via Retrofit API",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun CandlestickChartCanvas(
    candles: List<CandleStick>,
    pair: CurrencyPair,
    indicatorSettings: IndicatorSettings,
    activeSignal: TradeSignal? = null,
    aiOverlay: AiChartOverlayState? = null,
    showAiOverlay: Boolean = true,
    isAnalyzingAi: Boolean = false,
    onTriggerAiRedraw: (() -> Unit)? = null,
    isFullscreenDialog: Boolean = false,
    onAnalyzeSnapshot: ((Bitmap) -> Unit)? = null,
    isLoading: Boolean = false,
    isOfflineCached: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bullColor = MaterialTheme.colorScheme.secondary
    val bearColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outline
    val bgDark = MaterialTheme.colorScheme.background
    val ema20Color = Color(0xFF06B6D4)
    val ema50Color = Color(0xFFA855F7)
    val ema200Color = Color(0xFFFFB703)
    val sma20Color = Color(0xFFF59E0B)
    val sma50Color = Color(0xFF84CC16)
    
    val textColor = MaterialTheme.colorScheme.onBackground
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBg = MaterialTheme.colorScheme.surface
    val tooltipBorder = MaterialTheme.colorScheme.outline

    if (candles.isEmpty()) {
        ChartLoadingSkeletonAnimation(symbol = pair.symbol, modifier = modifier)
        return
    }

    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showSavePreviewDialog by remember { mutableStateOf(false) }
    var showDebugOverlay by remember { mutableStateOf(false) }
    var showOverlays by remember { mutableStateOf(true) }
    var isAiOverlayActive by remember(showAiOverlay) { mutableStateOf(showAiOverlay) }

    val aiRadarTransition = rememberInfiniteTransition(label = "AiRadarSweep")
    val aiRadarYProgress by aiRadarTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "AiRadarY"
    )
    val aiGlowPulse by aiRadarTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AiGlowPulse"
    )
    
    // Debug overlay state
    var fps by remember { mutableStateOf(0) }
    var latency by remember { mutableStateOf(0) }
    
    LaunchedEffect(showDebugOverlay) {
        if (showDebugOverlay) {
            var frameCount = 0
            var lastTime = System.nanoTime()
            while (true) {
                withFrameNanos { time ->
                    frameCount++
                    if (time - lastTime >= 1_000_000_000) {
                        fps = frameCount
                        frameCount = 0
                        lastTime = time
                        // Simulate network latency variance around 45-120ms
                        latency = (45..120).random()
                    }
                }
            }
        }
    }

    var scaleFactor by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var touchPosition by remember { mutableStateOf<Offset?>(null) }

    var selectedTool by remember { mutableStateOf(ChartDrawingTool.PAN_ZOOM) }
    var isFullscreen by remember { mutableStateOf(false) }
    val userDrawnLines = remember { mutableStateListOf<UserDrawnLine>() }
    var trendlineStart by remember { mutableStateOf<Pair<Int, Double>?>(null) }
    var currentDragOffset by remember { mutableStateOf<Offset?>(null) }

    var currentRsiSubchart by remember(indicatorSettings.showRsiSubchart) { mutableStateOf(indicatorSettings.showRsiSubchart) }
    var currentMacdSubchart by remember(indicatorSettings.showMacdSubchart) { mutableStateOf(indicatorSettings.showMacdSubchart) }
    var showAutoFibonacci by remember(indicatorSettings.showAutoFibonacci, isFullscreenDialog) {
        mutableStateOf(indicatorSettings.showAutoFibonacci || isFullscreenDialog)
    }
    var showPositionPriceRange by remember(indicatorSettings.showPositionPriceRange, isFullscreenDialog) {
        mutableStateOf(indicatorSettings.showPositionPriceRange || isFullscreenDialog)
    }
    var showVolumeProfile by remember(indicatorSettings.showVolumeProfile) { mutableStateOf(indicatorSettings.showVolumeProfile) }
    var showOrderBlocks by remember(indicatorSettings.showOrderBlocks) { mutableStateOf(indicatorSettings.showOrderBlocks) }
    var showEconomicFlags by remember(indicatorSettings.showEconomicFlags) { mutableStateOf(indicatorSettings.showEconomicFlags) }
    var showPatternsOverlay by remember(indicatorSettings.showPatterns) { mutableStateOf(indicatorSettings.showPatterns) }
    var selectedPattern by remember { mutableStateOf<DetectedPattern?>(null) }

    var canvasWidthPx by remember { mutableFloatStateOf(1f) }
    var canvasHeightPx by remember { mutableFloatStateOf(1f) }
    var currentMinPrice by remember { mutableDoubleStateOf(0.0) }
    var currentMaxPrice by remember { mutableDoubleStateOf(1.0) }
    var currentPriceRange by remember { mutableDoubleStateOf(1.0) }
    var currentStartIdx by remember { mutableIntStateOf(0) }
    var currentCandleWidth by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(pair.symbol) {
        offsetX = 0f
        scaleFactor = 1f
    }

    val closes = remember(candles) { candles.map { it.close } }
    val sma20 = remember(candles, indicatorSettings.smaPeriod1) { TechnicalAnalysisEngine.calculateSMA(closes, indicatorSettings.smaPeriod1) }
    val sma50 = remember(candles, indicatorSettings.smaPeriod2) { TechnicalAnalysisEngine.calculateSMA(closes, indicatorSettings.smaPeriod2) }
    val ema20 = remember(candles, indicatorSettings.emaPeriod1) { TechnicalAnalysisEngine.calculateEMA(closes, indicatorSettings.emaPeriod1) }
    val ema50 = remember(candles, indicatorSettings.emaPeriod2) { TechnicalAnalysisEngine.calculateEMA(closes, indicatorSettings.emaPeriod2) }
    val ema200 = remember(candles, indicatorSettings.emaPeriod3) { TechnicalAnalysisEngine.calculateEMA(closes, indicatorSettings.emaPeriod3) }
    val rsiList = remember(candles, indicatorSettings.rsiPeriod) { TechnicalAnalysisEngine.calculateRSI(closes, indicatorSettings.rsiPeriod) }
    val bollingerBands = remember(candles, indicatorSettings.bollingerPeriod, indicatorSettings.bollingerStdDev) { TechnicalAnalysisEngine.calculateBollingerBands(closes, indicatorSettings.bollingerPeriod, indicatorSettings.bollingerStdDev) }
    val macdData = remember(candles, indicatorSettings.macdFastPeriod, indicatorSettings.macdSlowPeriod, indicatorSettings.macdSignalPeriod) {
        TechnicalAnalysisEngine.calculateMACD(closes, indicatorSettings.macdFastPeriod, indicatorSettings.macdSlowPeriod, indicatorSettings.macdSignalPeriod)
    }
    val (supports, resistances) = remember(candles) { TechnicalAnalysisEngine.detectSupportResistance(candles) }
    val detectedPatterns = remember(candles) { TechnicalAnalysisEngine.detectPatterns(candles) }

    val hasRsi = currentRsiSubchart
    val hasMacd = currentMacdSubchart
    val mainChartWeight = when {
        hasRsi && hasMacd -> 0.54f
        hasRsi || hasMacd -> 0.75f
        else -> 1.0f
    }
    val subChartWeight = when {
        hasRsi && hasMacd -> 0.23f
        else -> 0.25f
    }

    val textMeasurer = rememberTextMeasurer()
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("candlestick_chart_container")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgDark)
        ) {
        // Main Candlestick Canvas
        Box(
            modifier = Modifier
                .weight(mainChartWeight)
                .fillMaxWidth()
                .pointerInput(selectedTool) {
                    when (selectedTool) {
                        ChartDrawingTool.PAN_ZOOM -> {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scaleFactor = (scaleFactor * zoom).coerceIn(0.4f, 4.0f)
                                offsetX = (offsetX + pan.x).coerceAtLeast(0f)
                            }
                        }
                        ChartDrawingTool.CROSSHAIR -> {
                            // No gesture required for crosshair, it reacts to tap/pointer movement
                        }
                        ChartDrawingTool.SUPPORT -> {
                            detectTapGestures { pos ->
                                val pRange = max(0.0001, currentMaxPrice - currentMinPrice)
                                val price = currentMaxPrice - (pRange * (pos.y / canvasHeightPx))
                                val candleIdx = (currentStartIdx + (pos.x / currentCandleWidth).toInt()).coerceIn(0, candles.lastIndex)
                                userDrawnLines.add(
                                    UserDrawnLine(
                                        type = ChartDrawingTool.SUPPORT,
                                        price1 = price,
                                        candleIndex1 = candleIdx
                                    )
                                )
                            }
                        }
                        ChartDrawingTool.RESISTANCE -> {
                            detectTapGestures { pos ->
                                val pRange = max(0.0001, currentMaxPrice - currentMinPrice)
                                val price = currentMaxPrice - (pRange * (pos.y / canvasHeightPx))
                                val candleIdx = (currentStartIdx + (pos.x / currentCandleWidth).toInt()).coerceIn(0, candles.lastIndex)
                                userDrawnLines.add(
                                    UserDrawnLine(
                                        type = ChartDrawingTool.RESISTANCE,
                                        price1 = price,
                                        candleIndex1 = candleIdx
                                    )
                                )
                            }
                        }
                        ChartDrawingTool.TRENDLINE, ChartDrawingTool.FIBONACCI -> {
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val pRange = max(0.0001, currentMaxPrice - currentMinPrice)
                                    val price = currentMaxPrice - (pRange * (pos.y / canvasHeightPx))
                                    val candleIdx = (currentStartIdx + (pos.x / currentCandleWidth).toInt()).coerceIn(0, candles.lastIndex)
                                    trendlineStart = Pair(candleIdx, price)
                                    currentDragOffset = pos
                                },
                                onDrag = { change, _ ->
                                    currentDragOffset = change.position
                                },
                                onDragEnd = {
                                    val start = trendlineStart
                                    val drag = currentDragOffset
                                    if (start != null && drag != null) {
                                        val pRange = max(0.0001, currentMaxPrice - currentMinPrice)
                                        val endPrice = currentMaxPrice - (pRange * (drag.y / canvasHeightPx))
                                        val endCandleIdx = (currentStartIdx + (drag.x / currentCandleWidth).toInt()).coerceIn(0, candles.lastIndex)
                                        userDrawnLines.add(
                                            UserDrawnLine(
                                                type = selectedTool,
                                                price1 = start.second,
                                                candleIndex1 = start.first,
                                                price2 = endPrice,
                                                candleIndex2 = endCandleIdx
                                            )
                                        )
                                    }
                                    trendlineStart = null
                                    currentDragOffset = null
                                },
                                onDragCancel = {
                                    trendlineStart = null
                                    currentDragOffset = null
                                }
                            )
                        }
                    }
                }
                .pointerInput(selectedTool) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val position = event.changes.firstOrNull()?.position
                            touchPosition = if (selectedTool == ChartDrawingTool.CROSSHAIR && event.changes.any { it.pressed }) position else null
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                val visibleCount = (40 / scaleFactor).toInt().coerceIn(10, candles.size)
                val approxCandleWidth = (canvasWidth / visibleCount).coerceAtLeast(1f)
                val indexShift = (offsetX / approxCandleWidth).toInt()

                val maxShift = (candles.size - visibleCount).coerceAtLeast(0)
                val clampedShift = indexShift.coerceIn(0, maxShift)

                val startIdx = (candles.size - visibleCount - clampedShift).coerceIn(0, candles.size - 1)
                val endIdx = (startIdx + visibleCount - 1).coerceIn(startIdx, candles.size - 1)
                val visibleCandles = candles.subList(startIdx, endIdx + 1)

                var minPrice = visibleCandles.minOf { it.low }
                var maxPrice = visibleCandles.maxOf { it.high }

                if (indicatorSettings.showBollingerBands) {
                    for (i in startIdx..endIdx) {
                        bollingerBands.getOrNull(i)?.let { bb ->
                            minPrice = min(minPrice, bb.lower)
                            maxPrice = max(maxPrice, bb.upper)
                        }
                    }
                }

                if (activeSignal != null) {
                    minPrice = min(minPrice, min(activeSignal.stopLoss, activeSignal.takeProfit2))
                    maxPrice = max(maxPrice, max(activeSignal.stopLoss, activeSignal.takeProfit2))
                }

                val priceRange = max(0.0001, maxPrice - minPrice)
                val candleWidth = (canvasWidth / visibleCandles.size).toFloat()

                // Update geometry state for gesture mapping
                canvasWidthPx = canvasWidth
                canvasHeightPx = canvasHeight
                currentMinPrice = minPrice
                currentMaxPrice = maxPrice
                currentPriceRange = priceRange
                currentStartIdx = startIdx
                currentCandleWidth = candleWidth

                // Draw background Grid lines
                val gridLines = 5
                for (g in 0..gridLines) {
                    val y = canvasHeight * (g.toFloat() / gridLines)
                    val gridPrice = maxPrice - (priceRange * (g.toFloat() / gridLines))
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(canvasWidth, y),
                        strokeWidth = 1f
                    )

                    // Price label
                    drawText(
                        textMeasurer = textMeasurer,
                        text = String.format(Locale.US, "%.5f", gridPrice),
                        style = TextStyle(color = textMuted, fontSize = 10.sp),
                        topLeft = Offset(canvasWidth - 110f, y - 18f)
                    )
                }

                // Draw Bollinger Bands Channel
                if (indicatorSettings.showBollingerBands) {
                    val upperPath = Path()
                    val lowerPath = Path()
                    var first = true

                    for ((idx, candleIdx) in (startIdx..endIdx).withIndex()) {
                        val bb = bollingerBands.getOrNull(candleIdx)
                        if (bb != null) {
                            val x = idx * candleWidth + (candleWidth / 2f)
                            val yUpper = (canvasHeight * (1f - (bb.upper - minPrice) / priceRange)).toFloat()
                            val yLower = (canvasHeight * (1f - (bb.lower - minPrice) / priceRange)).toFloat()

                            if (first) {
                                upperPath.moveTo(x, yUpper)
                                lowerPath.moveTo(x, yLower)
                                first = false
                            } else {
                                upperPath.lineTo(x, yUpper)
                                lowerPath.lineTo(x, yLower)
                            }
                        }
                    }

                    drawPath(
                        path = upperPath,
                        color = Color(0xFF38BDF8).copy(alpha = 0.5f),
                        style = Stroke(width = 2f)
                    )
                    drawPath(
                        path = lowerPath,
                        color = Color(0xFF38BDF8).copy(alpha = 0.5f),
                        style = Stroke(width = 2f)
                    )
                }

                // Draw Support and Resistance lines
                if (indicatorSettings.showSupportResistance) {
                    resistances.forEach { r ->
                        val y = (canvasHeight * (1f - (r - minPrice) / priceRange)).toFloat()
                        if (y in 0f..canvasHeight) {
                            drawLine(
                                color = Color(0xFFF43F5E).copy(alpha = 0.7f),
                                start = Offset(0f, y),
                                end = Offset(canvasWidth, y),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = "Resistance",
                                style = TextStyle(color = Color(0xFFF43F5E), fontSize = 10.sp),
                                topLeft = Offset(16f, y - 20f)
                            )
                        }
                    }

                    supports.forEach { s ->
                        val y = (canvasHeight * (1f - (s - minPrice) / priceRange)).toFloat()
                        if (y in 0f..canvasHeight) {
                            drawLine(
                                color = Color(0xFF10B981).copy(alpha = 0.7f),
                                start = Offset(0f, y),
                                end = Offset(canvasWidth, y),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = "Support",
                                style = TextStyle(color = Color(0xFF10B981), fontSize = 10.sp),
                                topLeft = Offset(16f, y - 20f)
                            )
                        }
                    }
                }

                // Draw Signal Overlay Lines (Entry, SL, TP1, TP2)
                if (activeSignal != null) {
                    val levels = listOf(
                        Triple(activeSignal.entryPrice, "ENTRY", Color(0xFF3B82F6)),
                        Triple(activeSignal.stopLoss, "SL (Stop Loss)", Color(0xFFEF4444)),
                        Triple(activeSignal.takeProfit1, "TP1 Target", Color(0xFF10B981)),
                        Triple(activeSignal.takeProfit2, "TP2 Target", Color(0xFF059669))
                    )

                    levels.forEach { (price, label, col) ->
                        val y = (canvasHeight * (1f - (price - minPrice) / priceRange)).toFloat()
                        if (y in 0f..canvasHeight) {
                            drawLine(
                                color = col,
                                start = Offset(0f, y),
                                end = Offset(canvasWidth, y),
                                strokeWidth = 3f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f))
                            )
                            drawRect(
                                color = col,
                                topLeft = Offset(canvasWidth - 140f, y - 18f),
                                size = Size(140f, 24f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = "$label: ${String.format(Locale.US, "%.4f", price)}",
                                style = TextStyle(color = textColor, fontSize = 9.sp),
                                topLeft = Offset(canvasWidth - 134f, y - 14f)
                            )
                        }
                    }
                }

                // Draw Candlesticks
                for ((i, candle) in visibleCandles.withIndex()) {
                    val xCenter = i * candleWidth + (candleWidth / 2f)
                    val bodyWidth = max(2f, candleWidth * 0.7f)

                    val yHigh = (canvasHeight * (1f - (candle.high - minPrice) / priceRange)).toFloat()
                    val yLow = (canvasHeight * (1f - (candle.low - minPrice) / priceRange)).toFloat()
                    val yOpen = (canvasHeight * (1f - (candle.open - minPrice) / priceRange)).toFloat()
                    val yClose = (canvasHeight * (1f - (candle.close - minPrice) / priceRange)).toFloat()

                    val color = if (candle.isBullish) bullColor else bearColor

                    // High/Low Wick
                    drawLine(
                        color = color,
                        start = Offset(xCenter, yHigh),
                        end = Offset(xCenter, yLow),
                        strokeWidth = 2f
                    )

                    // Open/Close Body
                    val bodyTop = min(yOpen, yClose)
                    val bodyHeight = max(2f, Math.abs(yOpen - yClose))

                    drawRect(
                        color = color,
                        topLeft = Offset(xCenter - (bodyWidth / 2f), bodyTop),
                        size = Size(bodyWidth, bodyHeight)
                    )

                    // Pattern Badge Marker & Focused Pattern Highlight
                    if (indicatorSettings.showPatterns) {
                        val realIdx = startIdx + i
                        detectedPatterns.find { it.candleIndex == realIdx }?.let { pat ->
                            val isFocused = selectedPattern == pat
                            val isBull = pat.patternType.isBullish
                            val badgeColor = if (isBull) bullColor else bearColor
                            val badgeY = if (isBull) yLow + 14f else yHigh - 22f

                            if (isFocused) {
                                // Focused vertical guideline
                                drawLine(
                                    color = badgeColor.copy(alpha = 0.5f),
                                    start = Offset(xCenter, 0f),
                                    end = Offset(xCenter, canvasHeight),
                                    strokeWidth = 2f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                                )
                                drawCircle(
                                    color = badgeColor.copy(alpha = 0.25f),
                                    radius = 16f,
                                    center = Offset(xCenter, badgeY)
                                )
                                drawCircle(
                                    color = badgeColor,
                                    radius = 7f,
                                    center = Offset(xCenter, badgeY)
                                )

                                // Pattern Title Pill on Chart
                                val titleText = "🔍 ${pat.patternType.title}"
                                drawRoundRect(
                                    color = Color(0xFF0F172A).copy(alpha = 0.95f),
                                    topLeft = Offset(xCenter - 65f, if (isBull) yLow + 26f else yHigh - 46f),
                                    size = Size(130f, 20f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                                )
                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = titleText,
                                    style = TextStyle(color = badgeColor, fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                    topLeft = Offset(xCenter - 60f, if (isBull) yLow + 29f else yHigh - 43f)
                                )

                                // Target Projection line
                                if (pat.targetProjection != null) {
                                    val yTarget = (canvasHeight * (1f - (pat.targetProjection - minPrice) / priceRange)).toFloat()
                                    if (yTarget in 0f..canvasHeight) {
                                        drawLine(
                                            color = Color(0xFF10B981).copy(alpha = 0.85f),
                                            start = Offset(xCenter, yTarget),
                                            end = Offset(canvasWidth, yTarget),
                                            strokeWidth = 1.5f,
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f))
                                        )
                                    }
                                }
                            } else {
                                drawCircle(
                                    color = badgeColor.copy(alpha = 0.85f),
                                    radius = 5.5f,
                                    center = Offset(xCenter, badgeY)
                                )
                            }
                        }
                    }
                }

                // Draw Real-time WebSocket Live Price Line & Pulsing Tick Indicator
                val currentLivePrice = pair.currentPrice
                val yLive = (canvasHeight * (1f - (currentLivePrice - minPrice) / priceRange)).toFloat()
                if (yLive in 0f..canvasHeight) {
                    val isLiveBull = (visibleCandles.lastOrNull()?.isBullish ?: true)
                    val liveColor = if (isLiveBull) Color(0xFF10B981) else Color(0xFFEF4444)

                    // Dashed real-time price tracking line across the entire chart
                    drawLine(
                        color = liveColor.copy(alpha = 0.9f),
                        start = Offset(0f, yLive),
                        end = Offset(canvasWidth, yLive),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                    )

                    // Glowing pulse on the active candle position
                    val activeCandleIdx = visibleCandles.lastIndex
                    if (activeCandleIdx >= 0) {
                        val activeX = activeCandleIdx * candleWidth + (candleWidth / 2f)
                        drawCircle(
                            color = liveColor.copy(alpha = 0.35f),
                            radius = 10f,
                            center = Offset(activeX, yLive)
                        )
                        drawCircle(
                            color = liveColor,
                            radius = 4.5f,
                            center = Offset(activeX, yLive)
                        )
                        drawCircle(
                            color = textColor,
                            radius = 2f,
                            center = Offset(activeX, yLive)
                        )
                    }

                    // Price Tag Badge on right edge
                    val badgeW = 95f
                    val badgeH = 22f
                    val badgeX = canvasWidth - badgeW - 6f
                    val badgeY = (yLive - (badgeH / 2f)).coerceIn(4f, canvasHeight - badgeH - 4f)

                    drawRoundRect(
                        color = liveColor,
                        topLeft = Offset(badgeX, badgeY),
                        size = Size(badgeW, badgeH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = String.format(Locale.US, if (pair.pipSize == 0.01) "%.2f" else "%.5f", currentLivePrice),
                        style = TextStyle(color = bgDark, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        topLeft = Offset(badgeX + 6f, badgeY + 3f)
                    )
                }

                // Draw SMA & EMA Curves
                if (indicatorSettings.showSma20) drawEmaPath(sma20, startIdx, endIdx, candleWidth, minPrice, priceRange, canvasHeight, sma20Color)
                if (indicatorSettings.showSma50) drawEmaPath(sma50, startIdx, endIdx, candleWidth, minPrice, priceRange, canvasHeight, sma50Color)
                if (indicatorSettings.showEma20) drawEmaPath(ema20, startIdx, endIdx, candleWidth, minPrice, priceRange, canvasHeight, ema20Color)
                if (indicatorSettings.showEma50) drawEmaPath(ema50, startIdx, endIdx, candleWidth, minPrice, priceRange, canvasHeight, ema50Color)
                if (indicatorSettings.showEma200) drawEmaPath(ema200, startIdx, endIdx, candleWidth, minPrice, priceRange, canvasHeight, ema200Color)

                // Draw Dynamic AI Redrawn Chart Structures (Zones, Trendlines, Fibonacci, Targets)
                if (isAiOverlayActive && aiOverlay != null && aiOverlay.pairSymbol == pair.symbol) {
                    // 1. AI Supply & Demand Zones
                    aiOverlay.zones.forEach { zone ->
                        val yTop = (canvasHeight * (1f - (zone.priceTop - minPrice) / priceRange)).toFloat()
                        val yBottom = (canvasHeight * (1f - (zone.priceBottom - minPrice) / priceRange)).toFloat()
                        val rectTop = min(yTop, yBottom)
                        val rectBottom = max(yTop, yBottom)
                        val rectH = max(8f, rectBottom - rectTop)

                        val zoneColor = when (zone.type) {
                            AiZoneType.DEMAND_SUPPORT -> Color(0xFF10B981)
                            AiZoneType.SUPPLY_RESISTANCE -> Color(0xFFEF4444)
                            AiZoneType.BREAKOUT_ZONE -> Color(0xFFF59E0B)
                        }

                        if (rectBottom >= 0f && rectTop <= canvasHeight) {
                            // Shaded area
                            drawRect(
                                color = zoneColor.copy(alpha = 0.12f),
                                topLeft = Offset(0f, max(0f, rectTop)),
                                size = Size(canvasWidth, min(canvasHeight - max(0f, rectTop), rectH))
                            )
                            // Top & bottom bounds
                            drawLine(
                                color = zoneColor.copy(alpha = 0.85f),
                                start = Offset(0f, rectTop),
                                end = Offset(canvasWidth, rectTop),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))
                            )
                            drawLine(
                                color = zoneColor.copy(alpha = 0.85f),
                                start = Offset(0f, rectBottom),
                                end = Offset(canvasWidth, rectBottom),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))
                            )
                            // Neon AI Badge
                            val zoneTag = "🤖 ${zone.label} • ${zone.confidence}% Conf"
                            drawRoundRect(
                                color = Color(0xFF0F172A).copy(alpha = 0.92f),
                                topLeft = Offset(14f, max(4f, rectTop + 4f)),
                                size = Size(210f, 20f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = zoneTag,
                                style = TextStyle(color = zoneColor, fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                topLeft = Offset(20f, max(8f, rectTop + 7f))
                            )
                        }
                    }

                    // 2. AI Smart Trendlines & Vectors
                    aiOverlay.trendlines.forEach { line ->
                        val x1 = (line.candleIndex1 - startIdx) * candleWidth + (candleWidth / 2f)
                        val x2 = (line.candleIndex2 - startIdx) * candleWidth + (candleWidth / 2f)
                        val y1 = (canvasHeight * (1f - (line.price1 - minPrice) / priceRange)).toFloat()
                        val y2 = (canvasHeight * (1f - (line.price2 - minPrice) / priceRange)).toFloat()

                        val lineColor = if (line.type == AiTrendlineType.BULLISH_SUPPORT) Color(0xFF38BDF8) else Color(0xFFA855F7)

                        drawLine(
                            color = lineColor,
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 3.5f,
                            pathEffect = if (line.isDashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null
                        )

                        // Glowing Anchor Points
                        drawCircle(color = lineColor.copy(alpha = 0.45f), radius = 9f, center = Offset(x1, y1))
                        drawCircle(color = lineColor, radius = 5f, center = Offset(x1, y1))
                        drawCircle(color = Color.White, radius = 2.5f, center = Offset(x1, y1))

                        drawCircle(color = lineColor.copy(alpha = 0.45f), radius = 9f, center = Offset(x2, y2))
                        drawCircle(color = lineColor, radius = 5f, center = Offset(x2, y2))
                        drawCircle(color = Color.White, radius = 2.5f, center = Offset(x2, y2))

                        val midX = (x1 + x2) / 2f
                        val midY = (y1 + y2) / 2f
                        if (midX in 0f..canvasWidth && midY in 0f..canvasHeight) {
                            drawRoundRect(
                                color = Color(0xFF0F172A).copy(alpha = 0.88f),
                                topLeft = Offset(midX - 70f, midY - 18f),
                                size = Size(140f, 18f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = "🤖 ${line.label}",
                                style = TextStyle(color = lineColor, fontSize = 8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                topLeft = Offset(midX - 64f, midY - 15f)
                            )
                        }
                    }

                    // 3. AI Fibonacci Retracement Levels
                    aiOverlay.fibonacciLevels.forEach { fib ->
                        val y = (canvasHeight * (1f - (fib.price - minPrice) / priceRange)).toFloat()
                        if (y in -10f..(canvasHeight + 10f)) {
                            val fibColor = when (fib.ratio) {
                                0.618 -> Color(0xFFF97316) // Golden Pocket
                                0.500 -> Color(0xFF38BDF8) // Equilibrium
                                0.382 -> Color(0xFF34D399)
                                else -> Color(0xFF94A3B8)
                            }
                            drawLine(
                                color = fibColor.copy(alpha = if (fib.ratio == 0.618) 0.95f else 0.75f),
                                start = Offset(0f, y),
                                end = Offset(canvasWidth, y),
                                strokeWidth = if (fib.ratio == 0.618) 2.2f else 1.2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                            )
                            val text = "AI FIB ${fib.percentage} (${String.format(Locale.US, "%.5f", fib.price)})"
                            drawRoundRect(
                                color = Color(0xFF0F172A).copy(alpha = 0.85f),
                                topLeft = Offset(canvasWidth - 195f, y - 10f),
                                size = Size(185f, 18f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = text,
                                style = TextStyle(color = fibColor, fontSize = 8.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                topLeft = Offset(canvasWidth - 188f, y - 8f)
                            )
                        }
                    }

                    // 4. AI Target Projections (TP1, TP2, SL)
                    aiOverlay.targets.forEach { target ->
                        val ySl = (canvasHeight * (1f - (target.stopLoss - minPrice) / priceRange)).toFloat()
                        val yTp1 = (canvasHeight * (1f - (target.takeProfit1 - minPrice) / priceRange)).toFloat()

                        if (yTp1 in 0f..canvasHeight) {
                            drawLine(
                                color = Color(0xFF10B981),
                                start = Offset(0f, yTp1),
                                end = Offset(canvasWidth, yTp1),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                            )
                            val tpText = "🎯 AI TP1: ${String.format(Locale.US, "%.5f", target.takeProfit1)}"
                            drawRoundRect(
                                color = Color(0xFF10B981),
                                topLeft = Offset(14f, yTp1 - 18f),
                                size = Size(135f, 18f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = tpText,
                                style = TextStyle(color = bgDark, fontSize = 8.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                topLeft = Offset(18f, yTp1 - 15f)
                            )
                        }

                        if (ySl in 0f..canvasHeight) {
                            drawLine(
                                color = Color(0xFFEF4444),
                                start = Offset(0f, ySl),
                                end = Offset(canvasWidth, ySl),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                            )
                            val slText = "🛑 AI SL: ${String.format(Locale.US, "%.5f", target.stopLoss)}"
                            drawRoundRect(
                                color = Color(0xFFEF4444),
                                topLeft = Offset(14f, ySl - 18f),
                                size = Size(130f, 18f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = slText,
                                style = TextStyle(color = Color.White, fontSize = 8.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                topLeft = Offset(18f, ySl - 15f)
                            )
                        }
                    }
                }

                // AI Scanning Radar Sweep Effect when Analyzing or Redrawing
                if (isAnalyzingAi || aiOverlay?.isRedrawing == true) {
                    val radarY = canvasHeight * aiRadarYProgress
                    // Glowing scan beam
                    drawLine(
                        color = Color(0xFF38BDF8).copy(alpha = aiGlowPulse),
                        start = Offset(0f, radarY),
                        end = Offset(canvasWidth, radarY),
                        strokeWidth = 2.5f
                    )
                    // Glow wash above radar
                    drawRect(
                        color = Color(0xFF38BDF8).copy(alpha = 0.04f * aiGlowPulse),
                        topLeft = Offset(0f, max(0f, radarY - 60f)),
                        size = Size(canvasWidth, min(60f, radarY))
                    )
                }

                // Draw User-Drawn Support, Resistance & Trendlines
                userDrawnLines.forEach { line ->
                    when (line.type) {
                        ChartDrawingTool.SUPPORT -> {
                            val y = (canvasHeight * (1f - (line.price1 - minPrice) / priceRange)).toFloat()
                            if (y in 0f..canvasHeight) {
                                drawLine(
                                    color = Color(0xFF10B981),
                                    start = Offset(0f, y),
                                    end = Offset(canvasWidth, y),
                                    strokeWidth = 3f
                                )
                                val text = "User Support: ${String.format(Locale.US, "%.5f", line.price1)}"
                                drawRoundRect(
                                    color = Color(0xFF10B981),
                                    topLeft = Offset(16f, y - 18f),
                                    size = Size(155f, 20f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                                )
                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = text,
                                    style = TextStyle(color = bgDark, fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                    topLeft = Offset(22f, y - 14f)
                                )
                            }
                        }
                        ChartDrawingTool.RESISTANCE -> {
                            val y = (canvasHeight * (1f - (line.price1 - minPrice) / priceRange)).toFloat()
                            if (y in 0f..canvasHeight) {
                                drawLine(
                                    color = Color(0xFFEF4444),
                                    start = Offset(0f, y),
                                    end = Offset(canvasWidth, y),
                                    strokeWidth = 3f
                                )
                                val text = "User Resistance: ${String.format(Locale.US, "%.5f", line.price1)}"
                                drawRoundRect(
                                    color = Color(0xFFEF4444),
                                    topLeft = Offset(16f, y - 18f),
                                    size = Size(165f, 20f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                                )
                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = text,
                                    style = TextStyle(color = textColor, fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                    topLeft = Offset(22f, y - 14f)
                                )
                            }
                        }
                        ChartDrawingTool.TRENDLINE -> {
                            val x1 = (line.candleIndex1 - startIdx) * candleWidth + (candleWidth / 2f)
                            val x2 = (line.candleIndex2 - startIdx) * candleWidth + (candleWidth / 2f)
                            val y1 = (canvasHeight * (1f - (line.price1 - minPrice) / priceRange)).toFloat()
                            val y2 = (canvasHeight * (1f - (line.price2 - minPrice) / priceRange)).toFloat()

                            drawLine(
                                color = Color(0xFF38BDF8),
                                start = Offset(x1, y1),
                                end = Offset(x2, y2),
                                strokeWidth = 3.5f
                            )
                            drawCircle(color = Color(0xFF38BDF8), radius = 6f, center = Offset(x1, y1))
                            drawCircle(color = textColor, radius = 3.5f, center = Offset(x1, y1))
                            drawCircle(color = Color(0xFF38BDF8), radius = 6f, center = Offset(x2, y2))
                            drawCircle(color = textColor, radius = 3.5f, center = Offset(x2, y2))
                        }
                        ChartDrawingTool.FIBONACCI -> {
                            val p1 = line.price1
                            val p2 = line.price2
                            val diff = p2 - p1
                            val fibLevels = listOf(
                                0.000 to Pair("0.0%", Color(0xFFE2E8F0)),
                                0.236 to Pair("23.6%", Color(0xFFFACC15)),
                                0.382 to Pair("38.2%", Color(0xFF34D399)),
                                0.500 to Pair("50.0%", Color(0xFF38BDF8)),
                                0.618 to Pair("61.8%", Color(0xFFF97316)),
                                0.786 to Pair("78.6%", Color(0xFFEC4899)),
                                1.000 to Pair("100.0%", Color(0xFF93C5FD))
                            )

                            val yFibStart = (canvasHeight * (1f - (p1 - minPrice) / priceRange)).toFloat()
                            val yFibEnd = (canvasHeight * (1f - (p2 - minPrice) / priceRange)).toFloat()
                            val topY = min(yFibStart, yFibEnd)
                            val bottomY = max(yFibStart, yFibEnd)
                            if (bottomY >= 0f && topY <= canvasHeight) {
                                drawRect(
                                    color = Color(0xFF38BDF8).copy(alpha = 0.06f),
                                    topLeft = Offset(0f, max(0f, topY)),
                                    size = Size(canvasWidth, min(canvasHeight - max(0f, topY), bottomY - max(0f, topY)))
                                )
                            }

                            fibLevels.forEach { (ratio, info) ->
                                val levelPrice = p1 + diff * ratio
                                val y = (canvasHeight * (1f - (levelPrice - minPrice) / priceRange)).toFloat()
                                if (y in -20f..(canvasHeight + 20f)) {
                                    drawLine(
                                        color = info.second.copy(alpha = 0.85f),
                                        start = Offset(0f, y),
                                        end = Offset(canvasWidth, y),
                                        strokeWidth = if (ratio == 0.0 || ratio == 1.0 || ratio == 0.5) 2f else 1.2f,
                                        pathEffect = if (ratio != 0.0 && ratio != 1.0) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null
                                    )
                                    val text = "${info.first}  ${String.format(Locale.US, "%.5f", levelPrice)}"
                                    drawRoundRect(
                                        color = Color(0xFF0F172A).copy(alpha = 0.85f),
                                        topLeft = Offset(12f, y - 10f),
                                        size = Size(125f, 18f),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                                    )
                                    drawText(
                                        textMeasurer = textMeasurer,
                                        text = text,
                                        style = TextStyle(color = info.second, fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                        topLeft = Offset(16f, y - 8f)
                                    )
                                }
                            }
                        }
                        else -> {}
                    }
                }

                // Draw Auto Fibonacci Overlay (Auto activated in Fullscreen or via toolbar)
                if (showOverlays && showAutoFibonacci && visibleCandles.isNotEmpty()) {
                    val autoFibLevels = TechnicalAnalysisEngine.calculateAutoFibonacci(visibleCandles)
                    if (autoFibLevels.isNotEmpty()) {
                        val topFibY = (canvasHeight * (1f - (autoFibLevels.first().price - minPrice) / priceRange)).toFloat()
                        val bottomFibY = (canvasHeight * (1f - (autoFibLevels.last().price - minPrice) / priceRange)).toFloat()
                        
                        val topY = min(topFibY, bottomFibY)
                        val bottomY = max(topFibY, bottomFibY)
                        if (bottomY >= 0f && topY <= canvasHeight) {
                            drawRect(
                                color = Color(0xFFFACC15).copy(alpha = 0.04f),
                                topLeft = Offset(0f, max(0f, topY)),
                                size = Size(canvasWidth, min(canvasHeight - max(0f, topY), bottomY - max(0f, topY)))
                            )
                        }

                        autoFibLevels.forEach { fib ->
                            val y = (canvasHeight * (1f - (fib.price - minPrice) / priceRange)).toFloat()
                            if (y in -20f..(canvasHeight + 20f)) {
                                val levelColor = when {
                                    fib.ratio == 0.618 -> Color(0xFFF97316)
                                    fib.ratio == 0.500 -> Color(0xFF38BDF8)
                                    fib.ratio == 0.0 || fib.ratio == 1.0 -> Color(0xFFFACC15)
                                    else -> Color(0xFF94A3B8)
                                }
                                drawLine(
                                    color = levelColor.copy(alpha = 0.85f),
                                    start = Offset(0f, y),
                                    end = Offset(canvasWidth, y),
                                    strokeWidth = if (fib.isKeyLevel) 2f else 1.2f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                                )
                                val fibText = "AUTO FIB ${fib.label}: ${String.format(Locale.US, "%.5f", fib.price)}"
                                drawRoundRect(
                                    color = Color(0xFF0F172A).copy(alpha = 0.90f),
                                    topLeft = Offset(12f, y - 10f),
                                    size = Size(185f, 20f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                                )
                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = fibText,
                                    style = TextStyle(color = levelColor, fontSize = 9.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                    topLeft = Offset(16f, y - 7f)
                                )
                            }
                        }
                    }
                }

                // Draw Short/Long Position Price Range Box Overlay (Auto activated in Fullscreen or via toolbar)
                if (showOverlays && showPositionPriceRange && visibleCandles.isNotEmpty()) {
                    val posRange = TechnicalAnalysisEngine.calculatePositionPriceRange(visibleCandles, pair)
                    
                    val yEntry = (canvasHeight * (1f - (posRange.entryPrice - minPrice) / priceRange)).toFloat()
                    val ySL = (canvasHeight * (1f - (posRange.stopLoss - minPrice) / priceRange)).toFloat()
                    val yTP1 = (canvasHeight * (1f - (posRange.takeProfit1 - minPrice) / priceRange)).toFloat()
                    val yTP2 = (canvasHeight * (1f - (posRange.takeProfit2 - minPrice) / priceRange)).toFloat()

                    val boxLeft = canvasWidth * 0.35f
                    val boxWidth = canvasWidth * 0.65f

                    // Red risk box (Entry -> SL)
                    val riskTopY = min(yEntry, ySL)
                    val riskBottomY = max(yEntry, ySL)
                    drawRect(
                        color = Color(0xFFEF4444).copy(alpha = 0.12f),
                        topLeft = Offset(boxLeft, riskTopY),
                        size = Size(boxWidth, max(2f, riskBottomY - riskTopY))
                    )

                    // Green reward box (Entry -> TP2)
                    val rewardTopY = min(yEntry, yTP2)
                    val rewardBottomY = max(yEntry, yTP2)
                    drawRect(
                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                        topLeft = Offset(boxLeft, rewardTopY),
                        size = Size(boxWidth, max(2f, rewardBottomY - rewardTopY))
                    )

                    // Entry Line (Blue/Cyan)
                    drawLine(
                        color = Color(0xFF38BDF8),
                        start = Offset(0f, yEntry),
                        end = Offset(canvasWidth, yEntry),
                        strokeWidth = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                    )
                    
                    // Stop Loss Line (Red)
                    drawLine(
                        color = Color(0xFFEF4444),
                        start = Offset(canvasWidth * 0.25f, ySL),
                        end = Offset(canvasWidth, ySL),
                        strokeWidth = 2.5f
                    )

                    // Take Profit 1 & 2 Lines (Green)
                    drawLine(
                        color = Color(0xFF10B981),
                        start = Offset(canvasWidth * 0.25f, yTP1),
                        end = Offset(canvasWidth, yTP1),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                    )
                    drawLine(
                        color = Color(0xFF10B981),
                        start = Offset(canvasWidth * 0.25f, yTP2),
                        end = Offset(canvasWidth, yTP2),
                        strokeWidth = 2.5f
                    )

                    // Entry Badge
                    drawRoundRect(
                        color = Color(0xFF38BDF8),
                        topLeft = Offset(canvasWidth - 195f, (yEntry - 10f).coerceIn(4f, canvasHeight - 24f)),
                        size = Size(185f, 20f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "ENTRY: ${String.format(Locale.US, "%.5f", posRange.entryPrice)}",
                        style = TextStyle(color = bgDark, fontSize = 9.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        topLeft = Offset(canvasWidth - 188f, (yEntry - 6f).coerceIn(8f, canvasHeight - 20f))
                    )

                    // SL Badge
                    drawRoundRect(
                        color = Color(0xFFEF4444),
                        topLeft = Offset(canvasWidth - 195f, (ySL - 10f).coerceIn(4f, canvasHeight - 24f)),
                        size = Size(185f, 20f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "SL: ${String.format(Locale.US, "%.5f", posRange.stopLoss)} (-${posRange.riskPips.toInt()}p)",
                        style = TextStyle(color = Color.White, fontSize = 9.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        topLeft = Offset(canvasWidth - 188f, (ySL - 6f).coerceIn(8f, canvasHeight - 20f))
                    )

                    // TP2 Badge
                    drawRoundRect(
                        color = Color(0xFF10B981),
                        topLeft = Offset(canvasWidth - 195f, (yTP2 - 10f).coerceIn(4f, canvasHeight - 24f)),
                        size = Size(185f, 20f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "TP2: ${String.format(Locale.US, "%.5f", posRange.takeProfit2)} (+${posRange.rewardPips.toInt()}p)",
                        style = TextStyle(color = bgDark, fontSize = 9.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        topLeft = Offset(canvasWidth - 188f, (yTP2 - 6f).coerceIn(8f, canvasHeight - 20f))
                    )
                }

                // 1. Volume Profile (VPVR) Rendering along right edge of main chart
                if (showOverlays && indicatorSettings.showVolumeProfile && visibleCandles.isNotEmpty()) {
                    val vp = TechnicalAnalysisEngine.calculateVolumeProfile(visibleCandles, 22)
                    if (vp.buckets.isNotEmpty() && vp.totalVolume > 0) {
                        val maxVol = vp.buckets.maxOf { it.totalVolume }.coerceAtLeast(1.0)
                        val maxBarWidth = canvasWidth * 0.18f

                        vp.buckets.forEach { b ->
                            val yTop = (canvasHeight * (1f - (b.maxPrice - minPrice) / priceRange)).toFloat()
                            val yBottom = (canvasHeight * (1f - (b.minPrice - minPrice) / priceRange)).toFloat()
                            val h = max(1.5f, abs(yBottom - yTop))

                            val buyWidth = ((b.buyVolume / maxVol) * maxBarWidth).toFloat()
                            val sellWidth = ((b.sellVolume / maxVol) * maxBarWidth).toFloat()

                            // Buy volume (cyan/green)
                            drawRect(
                                color = Color(0xFF10B981).copy(alpha = if (b.isPoc) 0.55f else 0.28f),
                                topLeft = Offset(canvasWidth - buyWidth - sellWidth, min(yTop, yBottom)),
                                size = Size(buyWidth, h)
                            )
                            // Sell volume (orange/red)
                            drawRect(
                                color = Color(0xFFEF4444).copy(alpha = if (b.isPoc) 0.55f else 0.28f),
                                topLeft = Offset(canvasWidth - sellWidth, min(yTop, yBottom)),
                                size = Size(sellWidth, h)
                            )
                        }

                        // Point of Control (POC) yellow dashed line across chart
                        val yPoc = (canvasHeight * (1f - (vp.pocPrice - minPrice) / priceRange)).toFloat()
                        if (yPoc in 0f..canvasHeight) {
                            drawLine(
                                color = Color(0xFFFACC15),
                                start = Offset(0f, yPoc),
                                end = Offset(canvasWidth, yPoc),
                                strokeWidth = 2.2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                            )
                            drawRoundRect(
                                color = Color(0xFFFACC15),
                                topLeft = Offset(canvasWidth - 110f, yPoc - 9f),
                                size = Size(100f, 18f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = "POC: ${String.format(Locale.US, "%.5f", vp.pocPrice)}",
                                style = TextStyle(color = bgDark, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                topLeft = Offset(canvasWidth - 105f, yPoc - 6f)
                            )
                        }
                    }
                }

                // 2. Order Block Liquidity Heatmap Rendering
                if (showOverlays && indicatorSettings.showOrderBlocks && visibleCandles.isNotEmpty()) {
                    val orderBlocks = TechnicalAnalysisEngine.detectOrderBlocks(visibleCandles)
                    orderBlocks.forEach { ob ->
                        val yTop = (canvasHeight * (1f - (ob.topPrice - minPrice) / priceRange)).toFloat()
                        val yBottom = (canvasHeight * (1f - (ob.bottomPrice - minPrice) / priceRange)).toFloat()
                        val topY = min(yTop, yBottom)
                        val bottomY = max(yTop, yBottom)
                        val h = max(4f, bottomY - topY)

                        val obColor = if (ob.isBullish) Color(0xFF10B981) else Color(0xFFA855F7)
                        val xStart = ((ob.candleIndex - startIdx) * candleWidth).coerceAtLeast(0f)

                        drawRect(
                            color = obColor.copy(alpha = 0.16f),
                            topLeft = Offset(xStart, topY),
                            size = Size(canvasWidth - xStart, h)
                        )
                        drawLine(
                            color = obColor.copy(alpha = 0.8f),
                            start = Offset(xStart, topY),
                            end = Offset(canvasWidth, topY),
                            strokeWidth = 1.5f
                        )
                        drawLine(
                            color = obColor.copy(alpha = 0.8f),
                            start = Offset(xStart, bottomY),
                            end = Offset(canvasWidth, bottomY),
                            strokeWidth = 1.5f
                        )
                        drawText(
                            textMeasurer = textMeasurer,
                            text = ob.label,
                            style = TextStyle(color = obColor, fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
                            topLeft = Offset(xStart + 8f, topY + 2f)
                        )
                    }
                }

                // 3. Economic News Event Flags along chart timeline
                if (showOverlays && indicatorSettings.showEconomicFlags && visibleCandles.isNotEmpty()) {
                    val events = TechnicalAnalysisEngine.getEconomicEvents()
                    val pairCurr1 = pair.baseCurrency
                    val pairCurr2 = pair.quoteCurrency

                    val relevantEvents = events.filter { it.currency == pairCurr1 || it.currency == pairCurr2 }
                    val firstCandleTime = visibleCandles.first().timestamp
                    val lastCandleTime = visibleCandles.last().timestamp
                    val timeSpan = max(1L, lastCandleTime - firstCandleTime)

                    relevantEvents.forEach { ev ->
                        if (ev.timestamp in (firstCandleTime - 7200000L)..(lastCandleTime + 7200000L)) {
                            val timeFrac = ((ev.timestamp - firstCandleTime).toDouble() / timeSpan).coerceIn(0.05, 0.95)
                            val xEvent = (canvasWidth * timeFrac).toFloat()

                            val flagColor = if (ev.impact == ImpactLevel.HIGH) Color(0xFFEF4444) else Color(0xFFF59E0B)

                            drawLine(
                                color = flagColor.copy(alpha = 0.7f),
                                start = Offset(xEvent, 0f),
                                end = Offset(xEvent, canvasHeight),
                                strokeWidth = 1.8f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                            )

                            val flagLabel = "🔴 NEWS: ${ev.currency} ${ev.title.take(10)}..."
                            drawRoundRect(
                                color = flagColor,
                                topLeft = Offset(xEvent - 45f, 28f),
                                size = Size(125f, 18f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = flagLabel,
                                style = TextStyle(color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
                                topLeft = Offset(xEvent - 40f, 31f)
                            )
                        }
                    }
                }

                // Draw Volume Profile (VPVR) Horizontal Histogram on the right side
                if (showOverlays && showVolumeProfile && visibleCandles.isNotEmpty()) {
                    val vp = TechnicalAnalysisEngine.calculateVolumeProfile(visibleCandles, bucketCount = 20)
                    val maxBucketVol = vp.buckets.maxOfOrNull { it.totalVolume } ?: 1.0
                    val maxBarWidth = canvasWidth * 0.22f

                    vp.buckets.forEach { bucket ->
                        val yMin = (canvasHeight * (1f - (bucket.maxPrice - minPrice) / priceRange)).toFloat()
                        val yMax = (canvasHeight * (1f - (bucket.minPrice - minPrice) / priceRange)).toFloat()
                        val barHeight = max(1.5f, yMax - yMin)

                        val totalLen = ((bucket.totalVolume / maxBucketVol) * maxBarWidth).toFloat()
                        val buyLen = (if (bucket.totalVolume > 0) (bucket.buyVolume / bucket.totalVolume) * totalLen else totalLen * 0.5f).toFloat()
                        val sellLen = (totalLen - buyLen).toFloat()

                        val barRight = canvasWidth
                        
                        // Buy volume bar (Green)
                        drawRect(
                            color = Color(0xFF10B981).copy(alpha = if (bucket.isPoc) 0.55f else 0.22f),
                            topLeft = Offset(barRight - buyLen, yMin),
                            size = Size(buyLen, barHeight)
                        )

                        // Sell volume bar (Red)
                        drawRect(
                            color = Color(0xFFEF4444).copy(alpha = if (bucket.isPoc) 0.55f else 0.22f),
                            topLeft = Offset(barRight - buyLen - sellLen, yMin),
                            size = Size(sellLen, barHeight)
                        )
                    }

                    // Point of Control (POC Line in bright Yellow/Gold)
                    val yPoc = (canvasHeight * (1f - (vp.pocPrice - minPrice) / priceRange)).toFloat()
                    if (yPoc in 0f..canvasHeight) {
                        drawLine(
                            color = Color(0xFFFACC15),
                            start = Offset(canvasWidth * 0.6f, yPoc),
                            end = Offset(canvasWidth, yPoc),
                            strokeWidth = 2.5f
                        )
                        drawRoundRect(
                            color = Color(0xFFFACC15),
                            topLeft = Offset(canvasWidth - 110f, yPoc - 9f),
                            size = Size(100f, 18f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                        )
                        drawText(
                            textMeasurer = textMeasurer,
                            text = "POC: ${String.format(Locale.US, "%.5f", vp.pocPrice)}",
                            style = TextStyle(color = bgDark, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            topLeft = Offset(canvasWidth - 105f, yPoc - 6f)
                        )
                    }
                }

                // Draw Order Block Liquidity Heatmap
                if (showOverlays && showOrderBlocks && visibleCandles.isNotEmpty()) {
                    val orderBlocks = TechnicalAnalysisEngine.detectOrderBlocks(visibleCandles)
                    orderBlocks.forEach { ob ->
                        val yTop = (canvasHeight * (1f - (ob.topPrice - minPrice) / priceRange)).toFloat()
                        val yBottom = (canvasHeight * (1f - (ob.bottomPrice - minPrice) / priceRange)).toFloat()

                        val topY = min(yTop, yBottom)
                        val h = max(4f, kotlin.math.abs(yBottom - yTop))

                        val blockColor = if (ob.isBullish) Color(0xFF10B981) else Color(0xFFEF4444)
                        
                        drawRect(
                            color = blockColor.copy(alpha = 0.16f),
                            topLeft = Offset(0f, topY),
                            size = Size(canvasWidth * 0.85f, h)
                        )

                        drawLine(
                            color = blockColor.copy(alpha = 0.8f),
                            start = Offset(0f, topY),
                            end = Offset(canvasWidth * 0.85f, topY),
                            strokeWidth = 1.5f
                        )
                        drawLine(
                            color = blockColor.copy(alpha = 0.8f),
                            start = Offset(0f, topY + h),
                            end = Offset(canvasWidth * 0.85f, topY + h),
                            strokeWidth = 1.5f
                        )

                        val obLabel = "${ob.label} (${String.format(Locale.US, "%.5f", ob.topPrice)})"
                        drawRoundRect(
                            color = blockColor,
                            topLeft = Offset(16f, topY + 2f),
                            size = Size(190f, 16f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                        )
                        drawText(
                            textMeasurer = textMeasurer,
                            text = obLabel,
                            style = TextStyle(color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
                            topLeft = Offset(20f, topY + 3f)
                        )
                    }
                }

                // Draw Economic Calendar News Flags on Timeline
                if (showOverlays && showEconomicFlags && visibleCandles.isNotEmpty()) {
                    val events = TechnicalAnalysisEngine.getEconomicEvents()
                    val relevantEvents = events.filter { it.currency == pair.baseCurrency || it.currency == pair.quoteCurrency || it.currency == "USD" }
                    
                    relevantEvents.forEachIndexed { idx, ev ->
                        val flagX = (canvasWidth * 0.15f) + (idx * (canvasWidth * 0.22f))
                        if (flagX < canvasWidth * 0.85f) {
                            val flagY = canvasHeight - 24f
                            val impactClr = when (ev.impact) {
                                ImpactLevel.HIGH -> Color(0xFFEF4444)
                                ImpactLevel.MEDIUM -> Color(0xFFF59E0B)
                                ImpactLevel.LOW -> Color(0xFF10B981)
                            }

                            drawLine(
                                color = impactClr.copy(alpha = 0.45f),
                                start = Offset(flagX, 0f),
                                end = Offset(flagX, canvasHeight),
                                strokeWidth = 1.2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                            )

                            drawRoundRect(
                                color = impactClr,
                                topLeft = Offset(flagX - 35f, flagY),
                                size = Size(70f, 18f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                            )
                            val flagText = "📰 ${ev.currency}"
                            drawText(
                                textMeasurer = textMeasurer,
                                text = flagText,
                                style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                topLeft = Offset(flagX - 28f, flagY + 2f)
                            )
                        }
                    }
                }

                // Render active trendline / fibonacci drag preview
                if (trendlineStart != null && currentDragOffset != null) {
                    val start = trendlineStart
                    val drag = currentDragOffset
                    val x1 = (start.first - startIdx) * candleWidth + (candleWidth / 2f)
                    val y1 = (canvasHeight * (1f - (start.second - minPrice) / priceRange)).toFloat()

                    if (selectedTool == ChartDrawingTool.TRENDLINE) {
                        drawLine(
                            color = Color(0xFF38BDF8).copy(alpha = 0.8f),
                            start = Offset(x1, y1),
                            end = drag,
                            strokeWidth = 3.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                        )
                        drawCircle(color = Color(0xFF38BDF8), radius = 7f, center = Offset(x1, y1))
                        drawCircle(color = Color(0xFF38BDF8), radius = 7f, center = drag)
                    } else if (selectedTool == ChartDrawingTool.FIBONACCI) {
                        val p1 = start.second
                        val p2 = maxPrice - (priceRange * (drag.y / canvasHeight))
                        val diff = p2 - p1
                        val fibLevels = listOf(
                            0.000 to Pair("0.0%", Color(0xFFE2E8F0)),
                            0.236 to Pair("23.6%", Color(0xFFFACC15)),
                            0.382 to Pair("38.2%", Color(0xFF34D399)),
                            0.500 to Pair("50.0%", Color(0xFF38BDF8)),
                            0.618 to Pair("61.8%", Color(0xFFF97316)),
                            0.786 to Pair("78.6%", Color(0xFFEC4899)),
                            1.000 to Pair("100.0%", Color(0xFF93C5FD))
                        )
                        fibLevels.forEach { (ratio, info) ->
                            val levelPrice = p1 + diff * ratio
                            val y = (canvasHeight * (1f - (levelPrice - minPrice) / priceRange)).toFloat()
                            if (y in 0f..canvasHeight) {
                                drawLine(
                                    color = info.second.copy(alpha = 0.9f),
                                    start = Offset(0f, y),
                                    end = Offset(canvasWidth, y),
                                    strokeWidth = 1.5f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                                )
                            }
                        }
                    }
                }

                // Draw Snapping Crosshair & Precision Interactive Tooltip on Touch / Tap / Long-press
                touchPosition?.let { pos ->
                    val hoveredIdx = (pos.x / candleWidth).toInt().coerceIn(0, visibleCandles.lastIndex)
                    val hoveredCandle = visibleCandles[hoveredIdx]
                    val xCenter = hoveredIdx * candleWidth + (candleWidth / 2f)

                    // Calculate candlestick level Y coordinates
                    val yOpen = (canvasHeight * (1f - (hoveredCandle.open - minPrice) / priceRange)).toFloat()
                    val yClose = (canvasHeight * (1f - (hoveredCandle.close - minPrice) / priceRange)).toFloat()
                    val yHigh = (canvasHeight * (1f - (hoveredCandle.high - minPrice) / priceRange)).toFloat()
                    val yLow = (canvasHeight * (1f - (hoveredCandle.low - minPrice) / priceRange)).toFloat()

                    // Snapping logic: find nearest level (Open, Close, High, Low) with snap threshold
                    val snapThresholdPx = 45f
                    val candidates = listOf(
                        SnapCandidate(yOpen, "OPEN", hoveredCandle.open, Color(0xFF38BDF8)),
                        SnapCandidate(yClose, "CLOSE", hoveredCandle.close, Color(0xFFF59E0B)),
                        SnapCandidate(yHigh, "HIGH", hoveredCandle.high, Color(0xFF10B981)),
                        SnapCandidate(yLow, "LOW", hoveredCandle.low, Color(0xFFEF4444))
                    )

                    val closestLevel = candidates.minByOrNull { kotlin.math.abs(it.y - pos.y) }
                    val isSnapped = closestLevel != null && kotlin.math.abs(closestLevel.y - pos.y) <= snapThresholdPx

                    val crosshairY = if (isSnapped) closestLevel.y else pos.y
                    val crosshairPrice = if (isSnapped) closestLevel.price else (maxPrice - (priceRange * (pos.y / canvasHeight)))
                    val snapLabel = if (isSnapped) closestLevel.label else null
                    val snapColor = if (isSnapped) closestLevel.color else Color(0xFFEADDFF)

                    // Vertical guide line snapped to hovered candle center
                    drawLine(
                        color = Color(0xFFEADDFF).copy(alpha = 0.8f),
                        start = Offset(xCenter, 0f),
                        end = Offset(xCenter, canvasHeight),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )

                    // Horizontal price guide line snapped to candlestick level or touch Y
                    drawLine(
                        color = snapColor.copy(alpha = 0.9f),
                        start = Offset(0f, crosshairY),
                        end = Offset(canvasWidth, crosshairY),
                        strokeWidth = if (isSnapped) 2.2f else 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )

                    // Snapping target circle & pulse indicator when locked onto level
                    if (isSnapped) {
                        drawCircle(
                            color = snapColor.copy(alpha = 0.35f),
                            radius = 12f,
                            center = Offset(xCenter, crosshairY)
                        )
                        drawCircle(
                            color = snapColor,
                            radius = 6f,
                            center = Offset(xCenter, crosshairY)
                        )
                        drawCircle(
                            color = textColor,
                            radius = 3f,
                            center = Offset(xCenter, crosshairY)
                        )
                    }

                    // Price level pill badge on the right axis
                    val badgePriceText = if (isSnapped && snapLabel != null) {
                        "$snapLabel: ${String.format(Locale.US, "%.5f", crosshairPrice)}"
                    } else {
                        String.format(Locale.US, "%.5f", crosshairPrice)
                    }
                    val badgeWidth = if (isSnapped) 130f else 85f
                    val badgeHeight = 22f
                    val badgeLeft = (canvasWidth - badgeWidth - 8f).coerceAtLeast(0f)
                    val badgeTop = (crosshairY - badgeHeight / 2f).coerceIn(4f, canvasHeight - badgeHeight - 4f)

                    drawRoundRect(
                        color = if (isSnapped) snapColor else gridColor,
                        topLeft = Offset(badgeLeft, badgeTop),
                        size = Size(badgeWidth, badgeHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = badgePriceText,
                        style = TextStyle(
                            color = if (isSnapped) bgDark else textColor,
                            fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ),
                        topLeft = Offset(badgeLeft + 6f, badgeTop + 3f)
                    )

                    val changePct = if (hoveredCandle.open > 0) ((hoveredCandle.close - hoveredCandle.open) / hoveredCandle.open) * 100 else 0.0
                    val isBull = hoveredCandle.isBullish

                    // Draw High-Density OHLC Tooltip Card Overlay
                    val cardWidth = min(canvasWidth * 0.85f, 320f)
                    val cardHeight = 72f
                    val cardLeft = if (xCenter > canvasWidth / 2f) 16f else canvasWidth - cardWidth - 16f
                    val cardTop = 16f

                    // Tooltip background card
                    drawRoundRect(
                        color = tooltipBg,
                        topLeft = Offset(cardLeft, cardTop),
                        size = Size(cardWidth, cardHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f)
                    )
                    drawRoundRect(
                        color = if (isBull) bullColor else bearColor,
                        topLeft = Offset(cardLeft, cardTop),
                        size = Size(cardWidth, cardHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                        style = Stroke(width = 2f)
                    )

                    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
                    val timeStr = dateFormat.format(Date(hoveredCandle.timestamp))
                    val statusStr = if (isBull) "BULLISH +${String.format(Locale.US, "%.2f", changePct)}%" else "BEARISH ${String.format(Locale.US, "%.2f", changePct)}%"

                    val snapBadgeText = if (isSnapped && snapLabel != null) "  •  LOCKED: $snapLabel" else ""
                    val headerText = "$timeStr  •  $statusStr$snapBadgeText"
                    drawText(
                        textMeasurer = textMeasurer,
                        text = headerText,
                        style = TextStyle(color = if (isSnapped) snapColor else (if (isBull) bullColor else bearColor), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        topLeft = Offset(cardLeft + 12f, cardTop + 10f)
                    )

                    val ohlcText = "O: ${String.format(Locale.US, "%.5f", hoveredCandle.open)}  H: ${String.format(Locale.US, "%.5f", hoveredCandle.high)}\nL: ${String.format(Locale.US, "%.5f", hoveredCandle.low)}  C: ${String.format(Locale.US, "%.5f", hoveredCandle.close)}"
                    drawText(
                        textMeasurer = textMeasurer,
                        text = ohlcText,
                        style = TextStyle(color = textColor, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        topLeft = Offset(cardLeft + 12f, cardTop + 28f)
                    )
                }
            }

            // Floating Interactive Drawing Toolbar
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(tooltipBg.copy(alpha = 0.95f), shape = RoundedCornerShape(12.dp))
                    .border(1.dp, gridColor, shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { showOverlays = !showOverlays },
                    modifier = Modifier.size(32.dp).testTag("tool_toggle_overlays")
                ) {
                    Icon(
                        imageVector = if (showOverlays) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle Overlays",
                        tint = textColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = showOverlays) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { selectedTool = ChartDrawingTool.PAN_ZOOM },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (selectedTool == ChartDrawingTool.PAN_ZOOM) Color(0xFF6750A4) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_pan_zoom")
                ) {
                    Icon(
                        imageVector = Icons.Default.PanTool,
                        contentDescription = "Pan & Zoom",
                        tint = if (selectedTool == ChartDrawingTool.PAN_ZOOM) textColor else textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Interactive Zoom In button
                IconButton(
                    onClick = {
                        scaleFactor = (scaleFactor * 1.25f).coerceIn(0.4f, 4.0f)
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.Transparent, shape = RoundedCornerShape(8.dp))
                        .testTag("tool_zoom_in")
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = "Zoom In",
                        tint = textColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Interactive Zoom Out button
                IconButton(
                    onClick = {
                        scaleFactor = (scaleFactor / 1.25f).coerceIn(0.4f, 4.0f)
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.Transparent, shape = RoundedCornerShape(8.dp))
                        .testTag("tool_zoom_out")
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomOut,
                        contentDescription = "Zoom Out",
                        tint = textColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Reset Zoom & Pan pill
                if (scaleFactor != 1f || offsetX != 0f) {
                    Surface(
                        onClick = {
                            scaleFactor = 1f
                            offsetX = 0f
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF334155),
                        modifier = Modifier
                            .height(26.dp)
                            .testTag("tool_reset_zoom")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset Zoom",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${(scaleFactor * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                IconButton(
                    onClick = { selectedTool = ChartDrawingTool.CROSSHAIR },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (selectedTool == ChartDrawingTool.CROSSHAIR) Color(0xFF6750A4) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_crosshair")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddCircle,
                        contentDescription = "Crosshair",
                        tint = if (selectedTool == ChartDrawingTool.CROSSHAIR) textColor else textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = { selectedTool = ChartDrawingTool.SUPPORT },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (selectedTool == ChartDrawingTool.SUPPORT) Color(0xFF10B981).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_support_line")
                ) {
                    Icon(
                        imageVector = Icons.Default.HorizontalRule,
                        contentDescription = "Draw Support Line",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { selectedTool = ChartDrawingTool.RESISTANCE },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (selectedTool == ChartDrawingTool.RESISTANCE) Color(0xFFEF4444).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_resistance_line")
                ) {
                    Icon(
                        imageVector = Icons.Default.HorizontalRule,
                        contentDescription = "Draw Resistance Line",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { selectedTool = ChartDrawingTool.TRENDLINE },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (selectedTool == ChartDrawingTool.TRENDLINE) Color(0xFF38BDF8).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_trendline")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = "Draw Trendline",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { selectedTool = ChartDrawingTool.FIBONACCI },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (selectedTool == ChartDrawingTool.FIBONACCI) Color(0xFFFACC15).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_fibonacci")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoGraph,
                        contentDescription = "Fibonacci Retracement",
                        tint = Color(0xFFFACC15),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(gridColor)
                )

                // Quick Indicator Toggles: RSI, Position Target Range, Auto Fib
                IconButton(
                    onClick = { currentRsiSubchart = !currentRsiSubchart },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (currentRsiSubchart) Color(0xFFF97316).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_toggle_rsi_quick")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = "Toggle RSI Subchart",
                        tint = if (currentRsiSubchart) Color(0xFFF97316) else textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { showPositionPriceRange = !showPositionPriceRange },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (showPositionPriceRange) Color(0xFF10B981).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_toggle_position_range_quick")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoGraph,
                        contentDescription = "Toggle Short/Long Price Range",
                        tint = if (showPositionPriceRange) Color(0xFF10B981) else textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { showAutoFibonacci = !showAutoFibonacci },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (showAutoFibonacci) Color(0xFFFACC15).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_toggle_auto_fib_quick")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Toggle Auto Fibonacci",
                        tint = if (showAutoFibonacci) Color(0xFFFACC15) else textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { showVolumeProfile = !showVolumeProfile },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (showVolumeProfile) Color(0xFF38BDF8).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_toggle_vpvr_quick")
                ) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = "Toggle Volume Profile (VPVR)",
                        tint = if (showVolumeProfile) Color(0xFF38BDF8) else textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { showOrderBlocks = !showOrderBlocks },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (showOrderBlocks) Color(0xFFA855F7).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_toggle_order_blocks_quick")
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Toggle Order Blocks",
                        tint = if (showOrderBlocks) Color(0xFFA855F7) else textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { showEconomicFlags = !showEconomicFlags },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (showEconomicFlags) Color(0xFFEF4444).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_toggle_economic_flags_quick")
                ) {
                    Icon(
                        imageVector = Icons.Default.Event,
                        contentDescription = "Toggle Economic News Flags",
                        tint = if (showEconomicFlags) Color(0xFFEF4444) else textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { showPatternsOverlay = !showPatternsOverlay },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (showPatternsOverlay) Color(0xFFF59E0B).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_toggle_patterns_overlay_quick")
                ) {
                    Icon(
                        imageVector = Icons.Default.Insights,
                        contentDescription = "Toggle Chart Patterns Radar",
                        tint = if (showPatternsOverlay) Color(0xFFF59E0B) else textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(gridColor)
                )

                IconButton(
                    onClick = { isFullscreen = !isFullscreen },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("tool_fullscreen")
                ) {
                    Icon(
                        imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (isFullscreen) "Exit Full Screen" else "Full Screen",
                        tint = textColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val w = view.width
                        val h = view.height
                        if (w > 0 && h > 0) {
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bitmap)
                            view.draw(canvas)
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                    val filename = "Chart_${pair.symbol.replace("/", "_")}_$timeStamp.png"

                                    val contentValues = ContentValues().apply {
                                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ForexSignals")
                                        }
                                    }
                                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                                    if (uri != null) {
                                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        capturedBitmap = bitmap
                                        showSavePreviewDialog = true
                                        Toast.makeText(context, "Chart image saved to Gallery!", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    withContext(Dispatchers.Main) {
                                        capturedBitmap = bitmap
                                        showSavePreviewDialog = true
                                        Toast.makeText(context, "Chart snapshot captured!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("tool_save_image")
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Save Chart as Image",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (userDrawnLines.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .height(20.dp)
                            .width(1.dp)
                            .background(gridColor)
                    )

                    IconButton(
                        onClick = { if (userDrawnLines.isNotEmpty()) userDrawnLines.removeAt(userDrawnLines.lastIndex) },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("tool_undo_line")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo Line",
                            tint = textColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = { userDrawnLines.clear() },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("tool_clear_lines")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear All Lines",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(gridColor)
                )
                
                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(gridColor)
                )

                // AI Redraw Overlay Toggle Button
                IconButton(
                    onClick = {
                        isAiOverlayActive = !isAiOverlayActive
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (isAiOverlayActive && aiOverlay != null) Color(0xFF38BDF8).copy(alpha = 0.25f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_ai_overlay_toggle")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Toggle AI Redraw Overlay",
                        tint = if (isAiOverlayActive) Color(0xFF38BDF8) else textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (onTriggerAiRedraw != null) {
                    IconButton(
                        onClick = { onTriggerAiRedraw.invoke() },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("tool_trigger_ai_redraw")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Trigger AI Re-draw",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                IconButton(
                    onClick = { showDebugOverlay = !showDebugOverlay },
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (showDebugOverlay) Color(0xFF10B981).copy(alpha = 0.35f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("tool_debug_overlay")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoGraph, // Using an existing icon as a placeholder
                        contentDescription = "Toggle Debug Overlay",
                        tint = if (showDebugOverlay) Color(0xFF10B981) else textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
                    }
                } // End of AnimatedVisibility
            }

            // Guidance Overlay Badge when Drawing Mode is active
            if (showOverlays && selectedTool != ChartDrawingTool.PAN_ZOOM) {
                val hintText = when (selectedTool) {
                    ChartDrawingTool.SUPPORT -> "Tap chart to add Support Line"
                    ChartDrawingTool.RESISTANCE -> "Tap chart to add Resistance Line"
                    ChartDrawingTool.TRENDLINE -> "Drag across chart to draw Trendline"
                    ChartDrawingTool.FIBONACCI -> "Drag from High to Low to draw Fibonacci levels"
                    else -> ""
                }
                Surface(
                    color = Color(0xFF6750A4).copy(alpha = 0.9f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = hintText,
                        color = textColor,
                        fontSize = 10.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            } else if (showOverlays && selectedTool == ChartDrawingTool.PAN_ZOOM && (scaleFactor != 1f || offsetX != 0f)) {
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = 0.88f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 40.dp, start = 8.dp)
                ) {
                    Text(
                        text = "🔍 Zoom ${(scaleFactor * 100).toInt()}% • Pinch to zoom, drag to pan",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Offline Room Persistence Status Badge
            if (isOfflineCached) {
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = 0.92f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 40.dp, end = 8.dp)
                        .testTag("badge_offline_room_cached")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "OFFLINE • ROOM DB CACHED",
                            color = Color(0xFF10B981),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp
                        )
                    }
                }
            }

            // Landscape Fullscreen Top Floating Control HUD
            if (isFullscreenDialog) {
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = 0.95f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.55f)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .testTag("fullscreen_top_hud_bar")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Text(
                                text = "${pair.symbol} LANDSCAPE FULLSCREEN",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }

                        Box(modifier = Modifier.height(18.dp).width(1.dp).background(Color(0xFF334155)))

                        FilterChip(
                            selected = showPositionPriceRange,
                            onClick = { showPositionPriceRange = !showPositionPriceRange },
                            label = { Text("🎯 Range Target", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFF10B981)
                            ),
                            modifier = Modifier.testTag("fullscreen_chip_range")
                        )

                        FilterChip(
                            selected = showAutoFibonacci,
                            onClick = { showAutoFibonacci = !showAutoFibonacci },
                            label = { Text("📐 Auto Fib", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFACC15).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFFFACC15)
                            ),
                            modifier = Modifier.testTag("fullscreen_chip_fib")
                        )

                        FilterChip(
                            selected = currentRsiSubchart,
                            onClick = { currentRsiSubchart = !currentRsiSubchart },
                            label = { Text("📊 RSI", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFF97316).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFFF97316)
                            ),
                            modifier = Modifier.testTag("fullscreen_chip_rsi")
                        )

                        FilterChip(
                            selected = currentMacdSubchart,
                            onClick = { currentMacdSubchart = !currentMacdSubchart },
                            label = { Text("📉 MACD", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF38BDF8).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFF38BDF8)
                            ),
                            modifier = Modifier.testTag("fullscreen_chip_macd")
                        )

                        FilterChip(
                            selected = showPatternsOverlay,
                            onClick = { showPatternsOverlay = !showPatternsOverlay },
                            label = { Text("⚡ Patterns (${detectedPatterns.size})", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFF59E0B).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFFF59E0B)
                            ),
                            modifier = Modifier.testTag("fullscreen_chip_patterns")
                        )

                        Box(modifier = Modifier.height(18.dp).width(1.dp).background(Color(0xFF334155)))

                        IconButton(
                            onClick = { isFullscreen = false },
                            modifier = Modifier.size(28.dp).testTag("fullscreen_exit_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FullscreenExit,
                                contentDescription = "Exit Fullscreen",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // AI Redraw Status / Live Scan Banner
            if (showOverlays && isAiOverlayActive && (isAnalyzingAi || (aiOverlay != null && aiOverlay.pairSymbol == pair.symbol))) {
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = 0.94f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.55f)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isAnalyzingAi || aiOverlay?.isRedrawing == true) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Color(0xFF38BDF8),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = if (aiOverlay?.redrawStep?.isNotEmpty() == true) "AI REDRAWING: ${aiOverlay.redrawStep}" else "AI SCANNING & REDRAWING MARKET STRUCTURE...",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "AI REDRAW ACTIVE • ${aiOverlay?.zones?.size ?: 0} ZONES • ${aiOverlay?.trendlines?.size ?: 0} VECTORS • ${aiOverlay?.fibonacciLevels?.size ?: 0} FIB LEVELS",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp
                            )
                        }
                    }
                }
            }

            // Modern Detected Chart Patterns List Overlay
            if (showOverlays && showPatternsOverlay) {
                ChartPatternsOverlay(
                    patterns = detectedPatterns,
                    pair = pair,
                    selectedPattern = selectedPattern,
                    onSelectPattern = { pat ->
                        selectedPattern = pat
                    },
                    onAskAiPattern = {
                        onTriggerAiRedraw?.invoke()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = if (isFullscreenDialog) 44.dp else 40.dp, start = 8.dp)
                )
            }
            
            // Debug FPS and Latency Overlay
            if (showOverlays && showDebugOverlay) {
                Surface(
                    color = bgDark.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (fps >= 30) Color(0xFF10B981) else Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "FPS: $fps",
                                color = textColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (latency < 100) Color(0xFF10B981) else Color(0xFFF59E0B))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Lat: ${latency}ms",
                                color = textColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // RSI Sub-chart Canvas
        if (currentRsiSubchart) {
            Box(
                modifier = Modifier
                    .weight(subChartWeight)
                    .fillMaxWidth()
                    .background(Color(0xFF020617))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height

                    val visibleCount = (40 / scaleFactor).toInt().coerceIn(10, candles.size)
                    val approxCandleWidth = (canvasWidth / visibleCount).coerceAtLeast(1f)
                    val indexShift = (offsetX / approxCandleWidth).toInt()

                    val maxShift = (candles.size - visibleCount).coerceAtLeast(0)
                    val clampedShift = indexShift.coerceIn(0, maxShift)

                    val startIdx = (candles.size - visibleCount - clampedShift).coerceIn(0, candles.size - 1)
                    val endIdx = (startIdx + visibleCount - 1).coerceIn(startIdx, candles.size - 1)
                    val candleWidth = (canvasWidth / (endIdx - startIdx + 1)).toFloat()

                    // RSI Overbought and Oversold threshold lines
                    val overbought = indicatorSettings.rsiOverbought.toFloat()
                    val oversold = indicatorSettings.rsiOversold.toFloat()
                    val yOverbought = canvasHeight * (1f - (overbought / 100f))
                    val yOversold = canvasHeight * (1f - (oversold / 100f))

                    // Draw shaded region between oversold and overbought
                    drawRect(
                        color = Color(0xFF38BDF8).copy(alpha = 0.04f),
                        topLeft = Offset(0f, yOverbought),
                        size = Size(canvasWidth, kotlin.math.abs(yOversold - yOverbought))
                    )

                    drawLine(
                        color = Color(0xFFEF4444).copy(alpha = 0.6f),
                        start = Offset(0f, yOverbought),
                        end = Offset(canvasWidth, yOverbought),
                        strokeWidth = 1.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                    drawLine(
                        color = Color(0xFF10B981).copy(alpha = 0.6f),
                        start = Offset(0f, yOversold),
                        end = Offset(canvasWidth, yOversold),
                        strokeWidth = 1.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )

                    val latestRsi = rsiList.lastOrNull { it != null }
                    val rsiStatus = when {
                        latestRsi != null && latestRsi >= overbought -> "OVERBOUGHT"
                        latestRsi != null && latestRsi <= oversold -> "OVERSOLD"
                        else -> "NEUTRAL"
                    }
                    val rsiColor = when (rsiStatus) {
                        "OVERBOUGHT" -> Color(0xFFEF4444)
                        "OVERSOLD" -> Color(0xFF10B981)
                        else -> Color(0xFFF97316)
                    }

                    val rsiTitleText = "RSI (${indicatorSettings.rsiPeriod}): ${if (latestRsi != null) String.format(Locale.US, "%.1f", latestRsi) else "--"} • $rsiStatus"
                    drawText(
                        textMeasurer = textMeasurer,
                        text = rsiTitleText,
                        style = TextStyle(color = rsiColor, fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                        topLeft = Offset(8f, 4f)
                    )

                    val rsiPath = Path()
                    var rsiStarted = false

                    for ((idx, candleIdx) in (startIdx..endIdx).withIndex()) {
                        val rsiVal = rsiList.getOrNull(candleIdx)
                        if (rsiVal != null) {
                            val x = idx * candleWidth + (candleWidth / 2f)
                            val yRsi = canvasHeight * (1f - (rsiVal.toFloat() / 100f))

                            if (!rsiStarted) {
                                rsiPath.moveTo(x, yRsi)
                                rsiStarted = true
                            } else {
                                rsiPath.lineTo(x, yRsi)
                            }
                        }
                    }

                    drawPath(
                        path = rsiPath,
                        color = Color(0xFFF97316),
                        style = Stroke(width = 2.5f)
                    )

                    // Touch / Crosshair hover sync dot on RSI subchart
                    touchPosition?.let { pos ->
                        val hoveredIdx = (pos.x / candleWidth).toInt().coerceIn(0, (endIdx - startIdx).coerceAtLeast(0))
                        val candleIdx = startIdx + hoveredIdx
                        val hoveredRsi = rsiList.getOrNull(candleIdx)
                        if (hoveredRsi != null) {
                            val xCenter = hoveredIdx * candleWidth + (candleWidth / 2f)
                            val yRsi = canvasHeight * (1f - (hoveredRsi.toFloat() / 100f))
                            
                            drawLine(
                                color = Color(0xFFEADDFF).copy(alpha = 0.5f),
                                start = Offset(xCenter, 0f),
                                end = Offset(xCenter, canvasHeight),
                                strokeWidth = 1f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                            )
                            drawCircle(color = rsiColor, radius = 5f, center = Offset(xCenter, yRsi))
                            drawCircle(color = Color.White, radius = 2.5f, center = Offset(xCenter, yRsi))
                        }
                    }
                }
            }
        } // end of RSI subchart

        // MACD Sub-chart Canvas
        if (currentMacdSubchart) {
            Box(
                modifier = Modifier
                    .weight(subChartWeight)
                    .fillMaxWidth()
                    .background(Color(0xFF030712))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height

                    val visibleCount = (40 / scaleFactor).toInt().coerceIn(10, candles.size)
                    val approxCandleWidth = (canvasWidth / visibleCount).coerceAtLeast(1f)
                    val indexShift = (offsetX / approxCandleWidth).toInt()

                    val maxShift = (candles.size - visibleCount).coerceAtLeast(0)
                    val clampedShift = indexShift.coerceIn(0, maxShift)

                    val startIdx = (candles.size - visibleCount - clampedShift).coerceIn(0, candles.size - 1)
                    val endIdx = (startIdx + visibleCount - 1).coerceIn(startIdx, candles.size - 1)
                    val candleWidth = (canvasWidth / (endIdx - startIdx + 1)).toFloat()

                    // Calculate max absolute range for MACD in visible window
                    var maxAbsVal = 0.0005
                    for (i in startIdx..endIdx) {
                        macdData.macdLine.getOrNull(i)?.let { maxAbsVal = kotlin.math.max(maxAbsVal, kotlin.math.abs(it)) }
                        macdData.signalLine.getOrNull(i)?.let { maxAbsVal = kotlin.math.max(maxAbsVal, kotlin.math.abs(it)) }
                        macdData.histogram.getOrNull(i)?.let { maxAbsVal = kotlin.math.max(maxAbsVal, kotlin.math.abs(it)) }
                    }
                    val macdRange = maxAbsVal * 2.2
                    val zeroY = canvasHeight / 2f

                    // Draw Zero Center Line
                    drawLine(
                        color = Color(0xFF475569),
                        start = Offset(0f, zeroY),
                        end = Offset(canvasWidth, zeroY),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    )

                    drawText(
                        textMeasurer = textMeasurer,
                        text = "MACD (${indicatorSettings.macdFastPeriod}, ${indicatorSettings.macdSlowPeriod}, ${indicatorSettings.macdSignalPeriod})",
                        style = TextStyle(color = textMuted, fontSize = 9.sp),
                        topLeft = Offset(8f, 4f)
                    )

                    // Draw MACD Histogram bars
                    val barWidth = max(2f, candleWidth * 0.6f)
                    for ((idx, candleIdx) in (startIdx..endIdx).withIndex()) {
                        val histVal = macdData.histogram.getOrNull(candleIdx)
                        if (histVal != null) {
                            val xCenter = idx * candleWidth + (candleWidth / 2f)
                            val yHist = (zeroY - (histVal / macdRange) * canvasHeight).toFloat()
                            val barColor = if (histVal >= 0) Color(0xFF10B981) else Color(0xFFEF4444)
                            val top = kotlin.math.min(zeroY, yHist)
                            val height = kotlin.math.max(1.5f, kotlin.math.abs(zeroY - yHist))

                            drawRect(
                                color = barColor.copy(alpha = 0.7f),
                                topLeft = Offset(xCenter - (barWidth / 2f), top),
                                size = Size(barWidth, height)
                            )
                        }
                    }

                    // Draw MACD Line & Signal Line
                    val macdPath = Path()
                    val signalPath = Path()
                    var macdStarted = false
                    var signalStarted = false

                    for ((idx, candleIdx) in (startIdx..endIdx).withIndex()) {
                        val x = idx * candleWidth + (candleWidth / 2f)

                        macdData.macdLine.getOrNull(candleIdx)?.let { mVal ->
                            val yM = (zeroY - (mVal / macdRange) * canvasHeight).toFloat()
                            if (!macdStarted) {
                                macdPath.moveTo(x, yM)
                                macdStarted = true
                            } else {
                                macdPath.lineTo(x, yM)
                            }
                        }

                        macdData.signalLine.getOrNull(candleIdx)?.let { sVal ->
                            val yS = (zeroY - (sVal / macdRange) * canvasHeight).toFloat()
                            if (!signalStarted) {
                                signalPath.moveTo(x, yS)
                                signalStarted = true
                            } else {
                                signalPath.lineTo(x, yS)
                            }
                        }
                    }

                    // MACD Line (Cyan)
                    drawPath(
                        path = macdPath,
                        color = Color(0xFF38BDF8),
                        style = Stroke(width = 2f)
                    )
                    // Signal Line (Orange)
                    drawPath(
                        path = signalPath,
                        color = Color(0xFFF97316),
                        style = Stroke(width = 1.8f)
                    )
                }
            }
        } // end of MACD subchart
        
    } // end of Column

    AnimatedVisibility(
        visible = isLoading,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(250))
    ) {
        ChartLoadingSkeletonAnimation(symbol = pair.symbol, modifier = Modifier.fillMaxSize())
    }
} // end of Box

    if (isFullscreen && !isFullscreenDialog) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgDark)
            ) {
                CandlestickChartCanvas(
                    candles = candles,
                    pair = pair,
                    indicatorSettings = indicatorSettings,
                    activeSignal = activeSignal,
                    aiOverlay = aiOverlay,
                    showAiOverlay = showAiOverlay,
                    isAnalyzingAi = isAnalyzingAi,
                    onTriggerAiRedraw = onTriggerAiRedraw,
                    isFullscreenDialog = true,
                    onAnalyzeSnapshot = onAnalyzeSnapshot,
                    isLoading = isLoading,
                    isOfflineCached = isOfflineCached,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showSavePreviewDialog && capturedBitmap != null) {
        val bitmap = capturedBitmap
        Dialog(onDismissRequest = { showSavePreviewDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = tooltipBg,
                shadowElevation = 8.dp,
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8)
                        )
                        Text(
                            text = "Chart Snapshot Captured!",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = textColor,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Saved to Gallery / Pictures/ForexSignals",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8))
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Chart Snapshot Preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, gridColor, RoundedCornerShape(8.dp))
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Pair: ${pair.symbol}",
                            style = MaterialTheme.typography.labelMedium.copy(color = textColor)
                        )
                        Text(
                            text = "${bitmap.width} x ${bitmap.height} px",
                            style = MaterialTheme.typography.labelMedium.copy(color = Color(0xFF38BDF8))
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    if (onAnalyzeSnapshot != null) {
                        Button(
                            onClick = {
                                showSavePreviewDialog = false
                                onAnalyzeSnapshot.invoke(bitmap)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("analyze_snapshot_ai_btn"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Analyze Snapshot in AI Panel",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showSavePreviewDialog = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
                        ) {
                            Text("Close")
                        }

                        Button(
                            onClick = {
                                try {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "Check out my ${pair.symbol} chart analysis on ForexSignals AI!")
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Chart"))
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEmaPath(
    emaValues: List<Double?>,
    startIdx: Int,
    endIdx: Int,
    candleWidth: Float,
    minPrice: Double,
    priceRange: Double,
    canvasHeight: Float,
    color: Color
) {
    val path = Path()
    var started = false

    for ((idx, candleIdx) in (startIdx..endIdx).withIndex()) {
        val ema = emaValues.getOrNull(candleIdx)
        if (ema != null) {
            val x = idx * candleWidth + (candleWidth / 2f)
            val y = (canvasHeight * (1f - (ema - minPrice) / priceRange)).toFloat()

            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.5f)
    )
}

private data class SnapCandidate(
    val y: Float,
    val label: String,
    val price: Double,
    val color: Color
)
