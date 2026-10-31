package com.example.forex.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forex.data.model.CandleStick
import com.example.forex.data.model.CurrencyPair
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

enum class SentimentState(val label: String, val color: Color) {
    STRONG_BEARISH("Strong Sell", Color(0xFFEF4444)),
    BEARISH("Bearish", Color(0xFFF97316)),
    NEUTRAL("Neutral", Color(0xFFFACC15)),
    BULLISH("Bullish", Color(0xFF34D399)),
    STRONG_BULLISH("Strong Buy", Color(0xFF10B981))
}

@Composable
fun MarketSentimentCard(
    pair: CurrencyPair,
    candles: List<CandleStick>,
    modifier: Modifier = Modifier
) {
    // Calculate sentiment score based on 24h change and recent candle direction
    val sentimentScore = remember(pair.priceChange24h, candles.lastOrNull()?.close) {
        val changeFactor = (pair.priceChange24h.coerceIn(-4.0, 4.0) / 4.0).toFloat()
        val recentBullishCount = candles.takeLast(10).count { it.isBullish }
        val candleFactor = ((recentBullishCount / 10f) - 0.5f) * 2f
        val raw = 50f + (changeFactor * 30f) + (candleFactor * 20f)
        raw.coerceIn(5f, 95f)
    }

    val animatedScore by animateFloatAsState(
        targetValue = sentimentScore,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "sentiment_score"
    )

    val sentimentState = when {
        animatedScore < 25f -> SentimentState.STRONG_BEARISH
        animatedScore < 45f -> SentimentState.BEARISH
        animatedScore <= 55f -> SentimentState.NEUTRAL
        animatedScore <= 75f -> SentimentState.BULLISH
        else -> SentimentState.STRONG_BULLISH
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("market_sentiment_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Real-Time Market Sentiment",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${pair.symbol} Live Momentum Gauge",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = sentimentState.color.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = sentimentState.label.uppercase(Locale.US),
                        color = sentimentState.color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Canvas Gauge
            MarketSentimentGaugeCanvas(
                score = animatedScore,
                sentimentColor = sentimentState.color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .testTag("market_sentiment_canvas")
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Bull / Bear Ratio Bar
            val bullPercent = animatedScore.toInt()
            val bearPercent = 100 - bullPercent

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$bearPercent% Bearish",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEF4444)
                )

                Text(
                    text = "$bullPercent% Bullish",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Dual Progress bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color(0xFFEF4444).copy(alpha = 0.3f), shape = RoundedCornerShape(3.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(bearPercent.toFloat())
                        .background(Color(0xFFEF4444), shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp))
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(bullPercent.toFloat())
                        .background(Color(0xFF10B981), shape = RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                )
            }
        }
    }
}

@Composable
fun MarketSentimentGaugeCanvas(
    score: Float,
    sentimentColor: Color,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val needleColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        // Gauge Arc parameters
        val arcRadius = (width.coerceAtMost(height * 2f) * 0.42f)
        val center = Offset(width / 2f, height - 12f)
        val strokeWidth = 16.dp.toPx()

        val topLeft = Offset(center.x - arcRadius, center.y - arcRadius)
        val arcSize = Size(arcRadius * 2f, arcRadius * 2f)

        // Draw outer background track arc (180 deg sweep)
        drawArc(
            color = trackColor,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Gradient colored arc segments (Bearish Red -> Neutral Yellow -> Bullish Green)
        val colors = listOf(
            Color(0xFFEF4444), // Strong Bearish
            Color(0xFFF97316), // Bearish
            Color(0xFFFACC15), // Neutral
            Color(0xFF34D399), // Bullish
            Color(0xFF10B981)  // Strong Bullish
        )

        val sweepPerSegment = 180f / colors.size
        for (i in colors.indices) {
            drawArc(
                color = colors[i],
                startAngle = 180f + (i * sweepPerSegment),
                sweepAngle = sweepPerSegment,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth - 2f)
            )
        }

        // Draw active arc up to score angle
        val activeSweep = (score / 100f) * 180f
        drawArc(
            color = sentimentColor,
            startAngle = 180f,
            sweepAngle = activeSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth + 4f, cap = StrokeCap.Round)
        )

        // Draw Needle Pointer
        val needleAngleRad = Math.toRadians((180f + activeSweep).toDouble())
        val needleLength = arcRadius * 0.78f

        val needleEnd = Offset(
            x = (center.x + needleLength * cos(needleAngleRad)).toFloat(),
            y = (center.y + needleLength * sin(needleAngleRad)).toFloat()
        )

        // Needle line
        drawLine(
            color = needleColor,
            start = center,
            end = needleEnd,
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Needle Center Circle Pivot
        drawCircle(
            color = needleColor,
            radius = 8.dp.toPx(),
            center = center
        )
        drawCircle(
            color = sentimentColor,
            radius = 4.dp.toPx(),
            center = center
        )
    }
}
