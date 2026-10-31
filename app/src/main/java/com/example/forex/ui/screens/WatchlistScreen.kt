package com.example.forex.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forex.data.model.CurrencyPair
import com.example.forex.data.model.PairCategory
import com.example.forex.ui.viewmodel.ForexViewModel
import java.util.Locale

enum class WatchlistFilter(val label: String) {
    FAVORITES("Favorites"),
    ALL("All Pairs"),
    MAJOR("Majors"),
    MINOR("Minors"),
    METALS_CRYPTO("Metals & Crypto")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    viewModel: ForexViewModel,
    onNavigateToTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pairs by viewModel.pairs.collectAsState()
    val watchlistItems by viewModel.watchlist.collectAsState()
    val selectedPair by viewModel.selectedPair.collectAsState()

    val watchlistedSymbols = remember(watchlistItems) {
        watchlistItems.map { it.symbol }.toSet()
    }

    var activeFilter by remember { mutableStateOf(WatchlistFilter.FAVORITES) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredPairs = remember(pairs, watchlistedSymbols, activeFilter, searchQuery) {
        pairs.filter { pair ->
            val matchesFilter = when (activeFilter) {
                WatchlistFilter.FAVORITES -> watchlistedSymbols.contains(pair.symbol)
                WatchlistFilter.ALL -> true
                WatchlistFilter.MAJOR -> pair.category == PairCategory.MAJOR
                WatchlistFilter.MINOR -> pair.category == PairCategory.MINOR
                WatchlistFilter.METALS_CRYPTO -> pair.category == PairCategory.METALS_CRYPTO
            }
            val matchesSearch = searchQuery.isEmpty() ||
                    pair.symbol.contains(searchQuery, ignoreCase = true) ||
                    pair.name.contains(searchQuery, ignoreCase = true)

            matchesFilter && matchesSearch
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Header Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Currency Watchlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${watchlistedSymbols.size} pairs saved in Room DB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search EUR/USD, Gold, Bitcoin...", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("watchlist_search_input")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(WatchlistFilter.values()) { filter ->
                val isSelected = filter == activeFilter
                FilterChip(
                    selected = isSelected,
                    onClick = { activeFilter = filter },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (filter == WatchlistFilter.FAVORITES) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFF59E0B)
                                )
                            }
                            Text(filter.label, fontSize = 12.sp)
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("watchlist_chip_${filter.name.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Pair List or Empty State
        if (filteredPairs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (activeFilter == WatchlistFilter.FAVORITES) "No Favorite Pairs Saved" else "No Pairs Found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (activeFilter == WatchlistFilter.FAVORITES)
                                "Tap the star icon next to any pair to persist it in your Room DB watchlist."
                            else
                                "Try adjusting your search query or filter criteria.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        if (activeFilter == WatchlistFilter.FAVORITES) {
                            Button(
                                onClick = {
                                    viewModel.toggleWatchlist("EUR/USD")
                                    viewModel.toggleWatchlist("GBP/USD")
                                    viewModel.toggleWatchlist("XAU/USD")
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.testTag("add_defaults_btn")
                            ) {
                                Text("Add Default Major Pairs", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredPairs, key = { it.symbol }) { pair ->
                    val isWatchlisted = watchlistedSymbols.contains(pair.symbol)
                    val isCurrentlySelected = pair.symbol == selectedPair.symbol

                    WatchlistPairCard(
                        pair = pair,
                        isWatchlisted = isWatchlisted,
                        isSelected = isCurrentlySelected,
                        onToggleWatchlist = { viewModel.toggleWatchlist(pair.symbol) },
                        onSelectAndTrade = {
                            viewModel.selectPair(pair)
                            onNavigateToTerminal()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun WatchlistPairCard(
    pair: CurrencyPair,
    isWatchlisted: Boolean,
    isSelected: Boolean,
    onToggleWatchlist: () -> Unit,
    onSelectAndTrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPositive = pair.priceChange24h >= 0
    val changeColor = if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else
                MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("watchlist_card_${pair.symbol}")
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pair Symbol & Category Info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = pair.symbol,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = pair.category.label,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = pair.name,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "24h H: ${String.format(Locale.US, "%.5f", pair.high24h)}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "24h L: ${String.format(Locale.US, "%.5f", pair.low24h)}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Price & Actions Column
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = String.format(Locale.US, if (pair.pipSize == 1.0) "%.1f" else if (pair.pipSize == 0.01) "%.2f" else "%.5f", pair.currentPrice),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Surface(
                    color = changeColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${if (isPositive) "+" else ""}${String.format(Locale.US, "%.2f", pair.priceChange24h)}%",
                        color = changeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleWatchlist,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("star_toggle_${pair.symbol}")
                    ) {
                        Icon(
                            imageVector = if (isWatchlisted) Icons.Default.Star else Icons.Outlined.StarOutline,
                            contentDescription = "Toggle Watchlist",
                            tint = if (isWatchlisted) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FilledTonalButton(
                        onClick = onSelectAndTrade,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("view_chart_${pair.symbol}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ShowChart,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Text("Chart", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
