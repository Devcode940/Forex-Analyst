package com.example.forex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forex.data.model.SignalType
import com.example.forex.data.model.TradeSignal
import com.example.forex.ui.components.SignalCard
import com.example.forex.ui.viewmodel.ForexViewModel

enum class SignalFilterTab {
    ALL, BUY_ONLY, SELL_ONLY, SAVED
}

@Composable
fun SignalsScreen(
    viewModel: ForexViewModel,
    onInspectSignalOnChart: (TradeSignal) -> Unit,
    modifier: Modifier = Modifier
) {
    val liveSignals by viewModel.liveSignals.collectAsState()
    val savedSignals by viewModel.savedSignals.collectAsState()

    var selectedFilter by remember { mutableStateOf(SignalFilterTab.ALL) }

    val savedIds = remember(savedSignals) { savedSignals.map { it.id }.toSet() }

    val filteredSignals = remember(liveSignals, selectedFilter, savedIds) {
        val signalsWithSavedState = liveSignals.map { sig ->
            sig.copy(isBookmarked = savedIds.contains(sig.id))
        }

        when (selectedFilter) {
            SignalFilterTab.ALL -> signalsWithSavedState
            SignalFilterTab.BUY_ONLY -> signalsWithSavedState.filter { it.type == SignalType.BUY }
            SignalFilterTab.SELL_ONLY -> signalsWithSavedState.filter { it.type == SignalType.SELL }
            SignalFilterTab.SAVED -> signalsWithSavedState.filter { it.isBookmarked }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp)
    ) {
        // Screen Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ElectricBolt,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Real-Time Trade Signals",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Surface(
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "${filteredSignals.size} Signals",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Filter Tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SignalFilterTab.values().forEach { tab ->
                val isSelected = tab == selectedFilter
                val label = when (tab) {
                    SignalFilterTab.ALL -> "All Signals"
                    SignalFilterTab.BUY_ONLY -> "Strong Buy"
                    SignalFilterTab.SELL_ONLY -> "Strong Sell"
                    SignalFilterTab.SAVED -> "Bookmarked"
                }

                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = tab },
                    label = { Text(label, fontSize = 12.sp, color = if (isSelected) Color.Black else Color.White) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (tab == SignalFilterTab.BUY_ONLY) Color(0xFF10B981)
                        else if (tab == SignalFilterTab.SELL_ONLY) Color(0xFFEF4444)
                        else Color(0xFF38BDF8)
                    ),
                    modifier = Modifier.testTag("signal_filter_${tab.name}")
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredSignals.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FilterList, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No signals match this filter right now", color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(filteredSignals, key = { it.id }) { signal ->
                    SignalCard(
                        signal = signal,
                        onSaveSignal = {
                            if (signal.isBookmarked) {
                                viewModel.deleteSavedSignal(signal.id)
                            } else {
                                viewModel.saveSignal(signal)
                            }
                        },
                        onInspectOnChart = { onInspectSignalOnChart(signal) }
                    )
                }
            }
        }
    }
}
