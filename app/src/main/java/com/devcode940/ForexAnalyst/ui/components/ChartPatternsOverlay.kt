package com.devcode940.ForexAnalyst.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devcode940.ForexAnalyst.data.model.CurrencyPair
import com.devcode940.ForexAnalyst.data.model.DetectedPattern
import com.devcode940.ForexAnalyst.data.model.PatternType
import com.devcode940.ForexAnalyst.data.repository.TechnicalAnalysisEngine

enum class PatternFilter { ALL, BULLISH, BEARISH }

/**
 * Modern floating list overlay on top of the Candlestick Chart
 * displaying institutional chart patterns (Head & Shoulders, Triangles, Double Tops/Bottoms).
 */
@Composable
fun ChartPatternsOverlay(
    patterns: List<DetectedPattern>,
    pair: CurrencyPair,
    selectedPattern: DetectedPattern?,
    onSelectPattern: (DetectedPattern?) -> Unit,
    modifier: Modifier = Modifier,
    isInitiallyExpanded: Boolean = false,
    onAskAiPattern: ((DetectedPattern) -> Unit)? = null
) {
    var isExpanded by remember { mutableStateOf(isInitiallyExpanded) }
    var activeFilter by remember { mutableStateOf(PatternFilter.ALL) }

    val filteredPatterns = remember(patterns, activeFilter) {
        when (activeFilter) {
            PatternFilter.ALL -> patterns
            PatternFilter.BULLISH -> patterns.filter { it.patternType.isBullish }
            PatternFilter.BEARISH -> patterns.filter { !it.patternType.isBullish }
        }
    }

    val bullishCount = remember(patterns) { patterns.count { it.patternType.isBullish } }
    val bearishCount = remember(patterns) { patterns.count { !it.patternType.isBullish } }

    val dominantColor = when {
        bullishCount > bearishCount -> Color(0xFF10B981)
        bearishCount > bullishCount -> Color(0xFFEF4444)
        patterns.isNotEmpty() -> Color(0xFF38BDF8)
        else -> Color(0xFF94A3B8)
    }

    Box(
        modifier = modifier
            .padding(10.dp)
            .testTag("chart_patterns_overlay_container")
    ) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                    fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            },
            label = "PatternOverlayTransition"
        ) { expanded ->
            if (!expanded) {
                // Collapsed State: Sleek floating badge pill
                Surface(
                    onClick = { isExpanded = true },
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, dominantColor.copy(alpha = 0.45f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier.testTag("patterns_overlay_collapsed_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dominantColor)
                        )

                        Icon(
                            imageVector = Icons.Default.Insights,
                            contentDescription = "Patterns Detected",
                            tint = dominantColor,
                            modifier = Modifier.size(16.dp)
                        )

                        Text(
                            text = if (patterns.isNotEmpty()) {
                                "${patterns.size} Patterns (${patterns.first().patternType.title})"
                            } else {
                                "Pattern Radar"
                            },
                            color = Color(0xFFF8FAFC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (selectedPattern != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = dominantColor.copy(alpha = 0.20f)
                            ) {
                                Text(
                                    text = "Active",
                                    color = dominantColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = "Expand Patterns Panel",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                // Expanded State: High-density pattern inspector card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.85f)),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .widthIn(max = 340.dp)
                        .testTag("patterns_overlay_expanded_card")
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                    ) {
                        // Header Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(dominantColor.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoGraph,
                                        contentDescription = null,
                                        tint = dominantColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "PATTERN RADAR",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 0.5.sp
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = dominantColor.copy(alpha = 0.22f)
                                        ) {
                                            Text(
                                                text = "${patterns.size}",
                                                color = dominantColor,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${pair.symbol} Structural Formations",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectedPattern != null) {
                                    TextButton(
                                        onClick = { onSelectPattern(null) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("Clear", color = Color(0xFFEF4444), fontSize = 10.sp)
                                    }
                                }

                                IconButton(
                                    onClick = { isExpanded = false },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ExpandLess,
                                        contentDescription = "Collapse Pattern Overlay",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Filter Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = activeFilter == PatternFilter.ALL,
                                onClick = { activeFilter = PatternFilter.ALL },
                                label = { Text("All (${patterns.size})", fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF38BDF8).copy(alpha = 0.22f),
                                    selectedLabelColor = Color(0xFF38BDF8)
                                ),
                                modifier = Modifier.height(28.dp)
                            )
                            FilterChip(
                                selected = activeFilter == PatternFilter.BULLISH,
                                onClick = { activeFilter = PatternFilter.BULLISH },
                                label = { Text("Bullish ($bullishCount)", fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.22f),
                                    selectedLabelColor = Color(0xFF10B981)
                                ),
                                modifier = Modifier.height(28.dp)
                            )
                            FilterChip(
                                selected = activeFilter == PatternFilter.BEARISH,
                                onClick = { activeFilter = PatternFilter.BEARISH },
                                label = { Text("Bearish ($bearishCount)", fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFEF4444).copy(alpha = 0.22f),
                                    selectedLabelColor = Color(0xFFEF4444)
                                ),
                                modifier = Modifier.height(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Pattern Items List
                        if (filteredPatterns.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = Color(0xFF64748B),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "No patterns in this filter",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "Scanning real-time candles...",
                                        color = Color(0xFF64748B),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(filteredPatterns) { pattern ->
                                    val isSelected = selectedPattern == pattern
                                    PatternItemCard(
                                        pattern = pattern,
                                        pipSize = pair.pipSize,
                                        isSelected = isSelected,
                                        onSelect = {
                                            if (isSelected) onSelectPattern(null) else onSelectPattern(pattern)
                                        },
                                        onAskAi = onAskAiPattern
                                    )
                                }
                            }
                        }

                        // Hint Footer
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "💡 Tap a pattern to focus and draw projection levels on the chart",
                            color = Color(0xFF64748B),
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternItemCard(
    pattern: DetectedPattern,
    pipSize: Double,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onAskAi: ((DetectedPattern) -> Unit)?
) {
    val isBullish = pattern.patternType.isBullish
    val themeColor = if (isBullish) Color(0xFF10B981) else Color(0xFFEF4444)

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) themeColor.copy(alpha = 0.16f) else Color(0xFF1E293B).copy(alpha = 0.85f),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) themeColor else Color(0xFF334155).copy(alpha = 0.70f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pattern_item_${pattern.patternType.name}")
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isBullish) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = null,
                        tint = themeColor,
                        modifier = Modifier.size(15.dp)
                    )

                    Text(
                        text = pattern.patternType.title,
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = themeColor.copy(alpha = 0.20f)
                    ) {
                        Text(
                            text = if (isBullish) "BULLISH" else "BEARISH",
                            color = themeColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF334155).copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = "${pattern.confidence}%",
                            color = Color(0xFFE2E8F0),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Price details and targets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "@ ${TechnicalAnalysisEngine.fmtPrice(pattern.priceLevel, pipSize)}",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )

                if (pattern.targetProjection != null) {
                    Text(
                        text = "TP: ${TechnicalAnalysisEngine.fmtPrice(pattern.targetProjection, pipSize)}",
                        color = Color(0xFF10B981),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Description / Trading insight
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = pattern.patternType.description,
                color = Color(0xFF94A3B8),
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 11.sp
            )

            // Focus indicator row
            if (isSelected) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(themeColor)
                        )
                        Text(
                            text = "FOCUSED ON CHART",
                            color = themeColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (onAskAi != null) {
                        Text(
                            text = "Analyze with AI ›",
                            color = Color(0xFFA855F7),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onAskAi(pattern) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
