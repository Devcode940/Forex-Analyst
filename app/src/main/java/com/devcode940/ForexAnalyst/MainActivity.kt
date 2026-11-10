package com.devcode940.ForexAnalyst

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import com.devcode940.ForexAnalyst.ui.screens.*
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel
import com.devcode940.ForexAnalyst.worker.scheduleSignalWorker
import com.devcode940.ForexAnalyst.ui.theme.MyApplicationTheme

enum class ForexAppTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    TERMINAL("Terminal", Icons.AutoMirrored.Filled.ShowChart, Icons.AutoMirrored.Filled.ShowChart, "nav_tab_terminal"),
    SIGNALS("Signals", Icons.Filled.ElectricBolt, Icons.Outlined.ElectricBolt, "nav_tab_signals"),
    CALENDAR("Calendar", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth, "nav_tab_calendar"),
    BACKTESTER("Backtest", Icons.Filled.QueryStats, Icons.Outlined.QueryStats, "nav_tab_backtester"),
    WATCHLIST("Watchlist", Icons.Filled.Star, Icons.Outlined.StarOutline, "nav_tab_watchlist"),
    ALERTS("Alerts", Icons.Filled.NotificationsActive, Icons.Outlined.NotificationsActive, "nav_tab_alerts"),
    CALCULATOR("Risk Calc", Icons.Filled.Calculate, Icons.Outlined.Calculate, "nav_tab_calculator"),
    AI_BREAKDOWN("AI Analyst", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, "nav_tab_ai")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        scheduleSignalWorker(this)

        setContent {
            val viewModel: ForexViewModel = viewModel()
            val isDarkTheme by viewModel.isDarkTheme.collectAsState()
            
            MyApplicationTheme(darkTheme = isDarkTheme) {
                ForexAppContent(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun ForexAppContent(viewModel: ForexViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(ForexAppTab.TERMINAL) }
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                ForexAppTab.values().forEach { tab ->
                    val isSelected = tab == selectedTab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.title
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier.testTag(tab.testTag)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                ForexAppTab.TERMINAL -> {
                    ChartTerminalScreen(
                        viewModel = viewModel,
                        onNavigateToAi = { selectedTab = ForexAppTab.AI_BREAKDOWN }
                    )
                }
                ForexAppTab.SIGNALS -> {
                    SignalsScreen(
                        viewModel = viewModel,
                        onInspectSignalOnChart = { signal ->
                            viewModel.setInspectedSignal(signal)
                            selectedTab = ForexAppTab.TERMINAL
                        }
                    )
                }
                ForexAppTab.CALENDAR -> {
                    EconomicCalendarScreen(
                        viewModel = viewModel,
                        onNavigateToChart = { selectedTab = ForexAppTab.TERMINAL }
                    )
                }
                ForexAppTab.BACKTESTER -> {
                    StrategyBacktesterScreen(viewModel = viewModel)
                }
                ForexAppTab.WATCHLIST -> {
                    WatchlistScreen(
                        viewModel = viewModel,
                        onNavigateToTerminal = { selectedTab = ForexAppTab.TERMINAL }
                    )
                }
                ForexAppTab.ALERTS -> {
                    AlertsScreen(viewModel = viewModel)
                }
                ForexAppTab.CALCULATOR -> {
                    RiskCalculatorScreen(viewModel = viewModel)
                }
                ForexAppTab.AI_BREAKDOWN -> {
                    AiAnalystScreen(viewModel = viewModel)
                }
            }
        }
    }
}
