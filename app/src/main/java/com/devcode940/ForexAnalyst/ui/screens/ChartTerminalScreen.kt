package com.devcode940.ForexAnalyst.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devcode940.ForexAnalyst.data.model.CurrencyPair
import com.devcode940.ForexAnalyst.data.model.IndicatorSettings
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import com.devcode940.ForexAnalyst.ui.components.*
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel
import kotlinx.coroutines.launch

@Composable
fun ChartTerminalScreen(
    viewModel: ForexViewModel,
    onNavigateToAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pairs by viewModel.pairs.collectAsState()
    val selectedPair by viewModel.selectedPair.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()
    val indicatorSettings by viewModel.indicatorSettings.collectAsState()
    val inspectedSignal by viewModel.inspectedSignal.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val isChartLoading by viewModel.isChartLoading.collectAsState()
    var showSentimentGauge by remember { mutableStateOf(false) }
    val currentUser by viewModel.currentUser.collectAsState()
    val isAuthLoading by viewModel.isAuthLoading.collectAsState()
    val authErrorMessage by viewModel.authErrorMessage.collectAsState()
    val webSocketStats by viewModel.webSocketStats.collectAsState()
    val candleVersion by viewModel.candleVersion.collectAsState()
    val aiChartOverlay by viewModel.aiChartOverlay.collectAsState()
    val showAiDrawingsOnChart by viewModel.showAiDrawingsOnChart.collectAsState()
    val isAnalyzingAi by viewModel.isAnalyzingAi.collectAsState()
    val isOfflineCached by viewModel.isOfflineCached.collectAsState()

    var showIndicatorConfigDialog by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var showRightSidebar by remember { mutableStateOf(false) }

    val candles = remember(selectedPair.symbol, selectedTimeframe, pairs, candleVersion) {
        viewModel.getCandlesForSelectedPair()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                WaitlistSidebar(onClose = { coroutineScope.launch { drawerState.close() } })
            }
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Theme Toggle, Auth, and Pair Selection (Top Bar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { coroutineScope.launch { drawerState.open() } },
                    modifier = Modifier.testTag("open_sidebar_btn")
                ) {
                    Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu")
                }

                Box(modifier = Modifier.weight(1f)) {
                    PairSelectorBar(
                        pairs = pairs,
                        selectedPair = selectedPair,
                        onPairSelected = { viewModel.selectPair(it) }
                    )
                }

                // Auth Account Button / Avatar
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (currentUser != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showAuthDialog = true }
                        .testTag("user_account_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (currentUser != null) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                val letter = (currentUser?.displayName?.firstOrNull() ?: 'U').uppercaseChar()
                                Text(
                                    text = letter.toString(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = currentUser?.displayName?.take(8) ?: "Trader",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "Sign In",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Sign In",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Indicator Config Dialog Trigger Button
                IconButton(
                    onClick = { showIndicatorConfigDialog = true },
                    modifier = Modifier.testTag("open_indicator_settings_icon_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Customize Indicators",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = { viewModel.refreshChartData() },
                    modifier = Modifier.testTag("refresh_chart_data_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Market Data",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { viewModel.toggleTheme() },
                    modifier = Modifier.testTag("theme_toggle_btn")
                ) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.Brightness7 else Icons.Default.Brightness4,
                        contentDescription = "Toggle Theme",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Live Header
            LiveTickerHeader(
                pair = selectedPair,
                selectedTimeframe = selectedTimeframe,
                onTimeframeSelected = { viewModel.selectTimeframe(it) },
                webSocketStats = webSocketStats,
                onReconnectWebSocket = { viewModel.reconnectWebSocket() }
            )

            // Customizable Indicator Config Dialog
            if (showIndicatorConfigDialog) {
                ChartIndicatorConfigDialog(
                    indicatorSettings = indicatorSettings,
                    onUpdateIndicatorSettings = { newSettings ->
                        viewModel.updateIndicatorSettings(newSettings)
                    },
                    onDismiss = { showIndicatorConfigDialog = false }
                )
            }

            // User Auth & Profile Dialog
            if (showAuthDialog) {
                UserAuthAndProfileDialog(
                    currentUser = currentUser,
                    isLoading = isAuthLoading,
                    errorMessage = authErrorMessage,
                    onSignInEmail = { email, pwd ->
                        viewModel.signInWithEmail(email, pwd) { success ->
                            if (success) showAuthDialog = false
                        }
                    },
                    onSignUpEmail = { email, pwd, name ->
                        viewModel.signUpWithEmail(email, pwd, name) { success ->
                            if (success) showAuthDialog = false
                        }
                    },
                    onSignInGoogle = {
                        viewModel.signInWithGoogle { success ->
                            if (success) showAuthDialog = false
                        }
                    },
                    onSignOut = {
                        viewModel.signOut()
                    },
                    onClearError = {
                        viewModel.clearAuthError()
                    },
                    onDismiss = {
                        viewModel.clearAuthError()
                        showAuthDialog = false
                    }
                )
            }

            // Market Sentiment Gauge Card overlay
            AnimatedVisibility(visible = showSentimentGauge) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    MarketSentimentCard(pair = selectedPair, candles = candles)
                }
            }

            // Inspected Signal Banner Alert
            if (inspectedSignal != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Inspecting ${inspectedSignal?.pairSymbol} Target Lines",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "SL: ${inspectedSignal?.stopLoss} | TP1: ${inspectedSignal?.takeProfit1}",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp
                            )
                        }
                        TextButton(onClick = { viewModel.setInspectedSignal(null) }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Main Content Area with Chart and Right Sidebar
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Main Chart Canvas View
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    CandlestickChartCanvas(
                        candles = candles,
                        pair = selectedPair,
                        indicatorSettings = indicatorSettings,
                        activeSignal = inspectedSignal,
                        aiOverlay = aiChartOverlay,
                        showAiOverlay = showAiDrawingsOnChart,
                        isAnalyzingAi = isAnalyzingAi,
                        onTriggerAiRedraw = { viewModel.redrawChartWithAi() },
                        onAnalyzeSnapshot = { bitmap ->
                            viewModel.analyzeSnapshotWithAi(bitmap)
                            onNavigateToAi()
                        },
                        isLoading = isChartLoading,
                        isOfflineCached = isOfflineCached,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Floating Action Row for Quick Settings & AI Breakdown
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // AI Quick Redraw FAB
                        SmallFloatingActionButton(
                            onClick = { viewModel.redrawChartWithAi() },
                            containerColor = if (aiChartOverlay?.pairSymbol == selectedPair.symbol) Color(0xFF0284C7) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = Color.White,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("floating_ai_redraw_btn")
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "AI Redraw Chart", modifier = Modifier.size(18.dp))
                        }

                        // Quick Customize Settings FAB
                        SmallFloatingActionButton(
                            onClick = { showIndicatorConfigDialog = true },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("floating_indicator_settings_btn")
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = "Indicator Parameters", modifier = Modifier.size(18.dp))
                        }

                        // AI Analysis Floating Action Chip
                        FloatingActionButton(
                            onClick = {
                                viewModel.runAiTechnicalAnalysis()
                                onNavigateToAi()
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.testTag("floating_ai_analyst_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "AI Analysis", tint = MaterialTheme.colorScheme.onPrimary)
                                Text("AI Breakdown", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
                
                // Right Sidebar for Overlays & Customization
                AnimatedVisibility(
                    visible = showRightSidebar,
                    enter = slideInHorizontally { it },
                    exit = slideOutHorizontally { it }
                ) {
                    Surface(
                        modifier = Modifier.width(230.dp).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Indicators",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(onClick = { showRightSidebar = false }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                                }
                            }

                            Button(
                                onClick = { showIndicatorConfigDialog = true },
                                modifier = Modifier.fillMaxWidth().testTag("open_indicator_config_panel"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Custom Parameters", fontSize = 11.sp)
                            }
                            
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            FilterChip(
                                selected = indicatorSettings.showSma20,
                                onClick = { viewModel.toggleIndicator { it.copy(showSma20 = !it.showSma20) } },
                                label = { Text("SMA (${indicatorSettings.smaPeriod1})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFF59E0B)),
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilterChip(
                                selected = indicatorSettings.showSma50,
                                onClick = { viewModel.toggleIndicator { it.copy(showSma50 = !it.showSma50) } },
                                label = { Text("SMA (${indicatorSettings.smaPeriod2})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF84CC16)),
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilterChip(
                                selected = indicatorSettings.showEma20,
                                onClick = { viewModel.toggleIndicator { it.copy(showEma20 = !it.showEma20) } },
                                label = { Text("EMA (${indicatorSettings.emaPeriod1})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF06B6D4)),
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilterChip(
                                selected = indicatorSettings.showEma50,
                                onClick = { viewModel.toggleIndicator { it.copy(showEma50 = !it.showEma50) } },
                                label = { Text("EMA (${indicatorSettings.emaPeriod2})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFA855F7)),
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilterChip(
                                selected = indicatorSettings.showEma200,
                                onClick = { viewModel.toggleIndicator { it.copy(showEma200 = !it.showEma200) } },
                                label = { Text("EMA (${indicatorSettings.emaPeriod3})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFB703)),
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilterChip(
                                selected = indicatorSettings.showRsiSubchart,
                                onClick = { viewModel.toggleIndicator { it.copy(showRsiSubchart = !it.showRsiSubchart) } },
                                label = { Text("RSI (${indicatorSettings.rsiPeriod})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFF97316)),
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilterChip(
                                selected = indicatorSettings.showMacdSubchart,
                                onClick = { viewModel.toggleIndicator { it.copy(showMacdSubchart = !it.showMacdSubchart) } },
                                label = { Text("MACD (${indicatorSettings.macdFastPeriod},${indicatorSettings.macdSlowPeriod},${indicatorSettings.macdSignalPeriod})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF38BDF8)),
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilterChip(
                                selected = indicatorSettings.showBollingerBands,
                                onClick = { viewModel.toggleIndicator { it.copy(showBollingerBands = !it.showBollingerBands) } },
                                label = { Text("Bollinger (${indicatorSettings.bollingerPeriod})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF60A5FA)),
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilterChip(
                                selected = indicatorSettings.showSupportResistance,
                                onClick = { viewModel.toggleIndicator { it.copy(showSupportResistance = !it.showSupportResistance) } },
                                label = { Text("Support/Resistance", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF10B981)),
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilterChip(
                                selected = showSentimentGauge,
                                onClick = { showSentimentGauge = !showSentimentGauge },
                                label = { Text("Sentiment Gauge", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WaitlistSidebar(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pro Features",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }
        
        Text(
            text = "Unlock automated multi-pair alerts, algorithmic backtesting, and full technical parameter suites.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp)
        )
        
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("🔥 Pro Tier Included", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Custom Moving Averages, RSI, MACD & Real-time background workers are now active!", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { onClose() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Got It")
        }
    }
}
