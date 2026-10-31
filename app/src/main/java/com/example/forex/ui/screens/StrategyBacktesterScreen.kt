package com.example.forex.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forex.data.model.BacktestResult
import com.example.forex.data.model.BacktestStrategy
import com.example.forex.data.model.BacktestTrade
import com.example.forex.data.model.CurrencyPair
import com.example.forex.data.model.Timeframe
import com.example.forex.ui.viewmodel.ForexViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@Composable
fun StrategyBacktesterScreen(
    viewModel: ForexViewModel,
    modifier: Modifier = Modifier
) {
    val pairs by viewModel.pairs.collectAsState()
    val selectedPair by viewModel.selectedPair.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()

    var selectedStrategy by remember { mutableStateOf(BacktestStrategy.EMA_CROSSOVER) }
    var initialCapital by remember { mutableFloatStateOf(10000f) }
    var riskPct by remember { mutableFloatStateOf(2.0f) }
    var slPips by remember { mutableFloatStateOf(30.0f) }
    var tpPips by remember { mutableFloatStateOf(60.0f) }

    val backtestResult by viewModel.backtestResult.collectAsState()
    val isBacktesting by viewModel.isBacktesting.collectAsState()
    val aiAuditSummary by viewModel.aiBacktestAudit.collectAsState()
    val isAuditingAi by viewModel.isAuditingBacktest.collectAsState()

    LaunchedEffect(selectedStrategy) {
        slPips = selectedStrategy.defaultSlPips.toFloat()
        tpPips = selectedStrategy.defaultTpPips.toFloat()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("strategy_backtester_screen")
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AI Strategy Backtester",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                )
                Text(
                    text = "Test quantitative trading rules against historical candles",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                )
            }

            IconButton(
                onClick = {
                    viewModel.runStrategyBacktest(selectedStrategy, initialCapital.toDouble(), riskPct.toDouble(), slPips.toDouble(), tpPips.toDouble())
                },
                modifier = Modifier.testTag("run_backtest_header_btn")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Rerun Backtest", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Strategy Selector Chips
        Text(
            text = "SELECT STRATEGY SYSTEM",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
        )
        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(BacktestStrategy.values()) { strat ->
                val isSel = strat == selectedStrategy
                FilterChip(
                    selected = isSel,
                    onClick = { selectedStrategy = strat },
                    label = { Text(strat.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("strat_chip_${strat.name}")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = selectedStrategy.description,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp
            )
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Configuration Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Backtest Settings & Risk Parameters",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Capital & Risk Sliders
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Initial Capital: \$${initialCapital.toInt()}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Risk/Trade: ${String.format(Locale.US, "%.1f", riskPct)}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = riskPct,
                    onValueChange = { riskPct = it },
                    valueRange = 0.5f..5.0f,
                    steps = 9,
                    modifier = Modifier.testTag("slider_risk_pct")
                )

                Spacer(modifier = Modifier.height(6.dp))

                // SL / TP Pips Sliders
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Stop Loss: ${slPips.toInt()} pips", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Text("Take Profit: ${tpPips.toInt()} pips (RR 1:${String.format(Locale.US, "%.1f", tpPips / slPips)})", fontSize = 12.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Slider(
                            value = slPips,
                            onValueChange = { slPips = it },
                            valueRange = 10f..100f,
                            modifier = Modifier.testTag("slider_sl_pips")
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Slider(
                            value = tpPips,
                            onValueChange = { tpPips = it },
                            valueRange = 20f..200f,
                            modifier = Modifier.testTag("slider_tp_pips")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        viewModel.runStrategyBacktest(selectedStrategy, initialCapital.toDouble(), riskPct.toDouble(), slPips.toDouble(), tpPips.toDouble())
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("run_backtest_btn"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isBacktesting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Simulating Strategy Engine...")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Run Strategy Backtest on ${selectedPair.symbol}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Results Section
        backtestResult?.let { res ->
            // KPI Summary Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KpiMetricCard(
                    title = "WIN RATE",
                    value = "${String.format(Locale.US, "%.1f", res.winRatePercentage)}%",
                    subValue = "${res.winningTrades}W / ${res.losingTrades}L",
                    color = if (res.winRatePercentage >= 50) Color(0xFF10B981) else Color(0xFFEF4444),
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    title = "NET PROFIT",
                    value = "${if (res.netProfitAmount >= 0) "+" else ""}\$${String.format(Locale.US, "%.2f", res.netProfitAmount)}",
                    subValue = "${String.format(Locale.US, "%.1f", res.netProfitPercentage)}%",
                    color = if (res.netProfitAmount >= 0) Color(0xFF10B981) else Color(0xFFEF4444),
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    title = "PROFIT FACTOR",
                    value = String.format(Locale.US, "%.2f", res.profitFactor),
                    subValue = "Max DD: ${String.format(Locale.US, "%.1f", res.maxDrawdownPercentage)}%",
                    color = if (res.profitFactor >= 1.2) Color(0xFF38BDF8) else Color(0xFFF59E0B),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Equity Curve Canvas
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ACCOUNT EQUITY CURVE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A))
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val curve = res.equityCurve
                            if (curve.size >= 2) {
                                val minBal = curve.minOf { it.second } * 0.98
                                val maxBal = curve.maxOf { it.second } * 1.02
                                val range = max(1.0, maxBal - minBal)

                                val w = size.width
                                val h = size.height
                                val stepX = w / (curve.size - 1)

                                val path = Path()
                                curve.forEachIndexed { i, pt ->
                                    val x = i * stepX
                                    val y = (h * (1f - (pt.second - minBal) / range)).toFloat()
                                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }

                                val lineColor = if (res.netProfitAmount >= 0) Color(0xFF10B981) else Color(0xFFEF4444)
                                drawPath(path = path, color = lineColor, style = Stroke(width = 2.5f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // AI Audit Button & Box
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF38BDF8))
                            Text("AI Strategy Audit & Optimization", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Button(
                            onClick = { viewModel.auditBacktestWithAi(res) },
                            enabled = !isAuditingAi,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                        ) {
                            if (isAuditingAi) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("Audit with AI", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                    }

                    if (!aiAuditSummary.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = aiAuditSummary ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Trades Table Header
            Text(
                text = "BACKTEST TRADE LOG (${res.trades.size} TRADES)",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            res.trades.take(15).forEach { tr ->
                TradeLogCard(trade = tr)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun KpiMetricCard(
    title: String,
    value: String,
    subValue: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
            Text(subValue, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TradeLogCard(trade: BacktestTrade) {
    val df = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
    val color = if (trade.pnlAmount >= 0) Color(0xFF10B981) else Color(0xFFEF4444)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "#${trade.tradeNumber} ${if (trade.isLong) "BUY" else "SELL"}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (trade.isLong) Color(0xFF10B981) else Color(0xFFEF4444)
                    )
                    Text(
                        text = df.format(Date(trade.entryTimestamp)),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Entry: ${String.format(Locale.US, "%.5f", trade.entryPrice)} → Exit: ${String.format(Locale.US, "%.5f", trade.exitPrice)} (${trade.exitReason})",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${if (trade.pnlAmount >= 0) "+" else ""}\$${String.format(Locale.US, "%.2f", trade.pnlAmount)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = color
                )
                Text(
                    text = "${if (trade.pnlPips >= 0) "+" else ""}${trade.pnlPips.toInt()} pips",
                    fontSize = 10.sp,
                    color = color
                )
            }
        }
    }
}
