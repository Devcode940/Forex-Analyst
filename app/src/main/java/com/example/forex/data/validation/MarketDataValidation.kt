package com.example.forex.data.validation

import com.example.forex.data.model.CandleStick
import com.example.forex.data.model.MarketTick
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Data validation layer for all inbound market data (REST candles, WebSocket ticks,
 * provider quotes) before it reaches the analysis engine, persistence or UI state.
 *
 * All functions are pure and side-effect free so they are unit-testable on the JVM and
 * safe to call from background workers as well as the UI process.
 */

enum class ValidationSeverity { HEALTHY, DEGRADED, REJECTED }

data class ValidationIssue(
    val field: String,
    val message: String,
    val isCritical: Boolean = false
)

data class ValidationReport(
    val severity: ValidationSeverity,
    /** 0-100 usability score of the payload (100 = pristine). */
    val qualityScore: Int,
    val issues: List<ValidationIssue> = emptyList(),
    /** Number of records dropped because they failed critical checks. */
    val rejectedCount: Int = 0,
    /** Number of records that were checked in total. */
    val checkedCount: Int = 0
) {
    val isUsable: Boolean get() = severity != ValidationSeverity.REJECTED

    val summary: String
        get() = when (severity) {
            ValidationSeverity.HEALTHY -> "OK ($checkedCount records)"
            ValidationSeverity.DEGRADED -> "OK with ${issues.size} warning(s), quality ${qualityScore}%"
            ValidationSeverity.REJECTED -> "REJECTED: ${issues.firstOrNull()?.message ?: "invalid payload"}"
        }

    companion object {
        fun healthy(checkedCount: Int = 1) = ValidationReport(ValidationSeverity.HEALTHY, 100, emptyList(), 0, checkedCount)
    }
}

/**
 * Stateless validator for market data payloads.
 */
object MarketDataValidator {

    /** Accept candles at most this far ahead of device time (guards clock skew). */
    const val FUTURE_TOLERANCE_MS: Long = 120_000L

    /** A series with fewer usable candles than this must never drive signal generation. */
    const val MIN_SERIES_LENGTH: Int = 40

    /** Below this quality score a payload is considered unusable. */
    const val MIN_SERIES_QUALITY: Int = 60

    /** Maximum single-candle move, as a fraction of price, before the bar is deemed corrupt. */
    const val MAX_SINGLE_BAR_MOVE_FRACTION: Double = 0.25

    private const val PRICE_EPSILON = 1e-9

    fun isFinitePositive(value: Double): Boolean = value.isFinite() && value > 0.0

    /**
     * Validates a single OHLCV candle against structural invariants:
     * finite positive prices, high >= max(open, close), low <= min(open, close),
     * non-negative finite volume, plausible timestamp, and a bounded per-bar move.
     */
    fun validateCandle(
        candle: CandleStick,
        referencePrice: Double? = null,
        maxMoveFraction: Double = MAX_SINGLE_BAR_MOVE_FRACTION,
        now: Long = System.currentTimeMillis()
    ): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()

        fun critical(field: String, message: String) {
            issues.add(ValidationIssue(field, message, isCritical = true))
        }

        fun warning(field: String, message: String) {
            issues.add(ValidationIssue(field, message, isCritical = false))
        }

        val o = candle.open; val h = candle.high; val l = candle.low; val c = candle.close
        if (!(isFinitePositive(o) && isFinitePositive(h) && isFinitePositive(l) && isFinitePositive(c))) {
            critical("ohlc", "Non-finite or non-positive OHLC value")
        } else {
            if (h + PRICE_EPSILON < max(o, c)) critical("high", "High ($h) below open/close")
            if (l - PRICE_EPSILON > min(o, c)) critical("low", "Low ($l) above open/close")
            if (h + PRICE_EPSILON < l) critical("range", "High below low")
            if (c > 0) {
                val move = abs(c - o) / c
                if (move > maxMoveFraction) critical("close", "Bar move ${(move * 100).toInt()}% exceeds ${(maxMoveFraction * 100).toInt()}% guard")
                val rangeMove = (h - l) / c
                if (rangeMove > maxMoveFraction) critical("range", "Bar range ${(rangeMove * 100).toInt()}% exceeds ${(maxMoveFraction * 100).toInt()}% guard")
            }
        }
        if (!candle.volume.isFinite() || candle.volume < 0) {
            warning("volume", "Invalid volume ${candle.volume}; treated as missing")
        }
        if (candle.timestamp <= 0L) {
            critical("timestamp", "Non-positive candle timestamp")
        } else if (candle.timestamp > now + FUTURE_TOLERANCE_MS) {
            critical("timestamp", "Candle timestamp is in the future")
        }
        if (referencePrice != null && referencePrice > 0 && c > 0) {
            val drift = abs(c - referencePrice) / referencePrice
            if (drift > maxMoveFraction) warning("close", "Close deviates ${(drift * 100).toInt()}% from reference price")
        }

        return buildReport(issues, checkedCount = 1, rejectedCount = if (issues.any { it.isCritical }) 1 else 0)
    }

    /**
     * Result of validating a full candle series. [candles] contains only records that
     * passed critical checks, sorted ascending and de-duplicated by timestamp.
     */
    data class SeriesValidation(val candles: List<CandleStick>, val report: ValidationReport)

    /**
     * Full-series validation used before candles enter the cache/DB or drive signal generation:
     *  - structure + sanity per candle (critical failures are dropped),
     *  - sort + de-duplication by timestamp (last write wins),
     *  - single-bar spike removal (in-and-out of a statistically extreme close),
     *  - data-gap detection,
     *  - minimum length and quality thresholds for usability.
     */
    fun validateCandleSeries(
        series: List<CandleStick>,
        timeframeMillis: Long,
        minValidCount: Int = MIN_SERIES_LENGTH,
        now: Long = System.currentTimeMillis()
    ): SeriesValidation {
        if (series.isEmpty()) {
            return SeriesValidation(
                emptyList(),
                ValidationReport(ValidationSeverity.REJECTED, 0, listOf(ValidationIssue("series", "Empty candle payload", true)), 0, 0)
            )
        }

        val issues = mutableListOf<ValidationIssue>()
        var rejected = 0

        // 1. Per-candle structural validation (drop critical failures).
        val structurallyValid = series.filter { candle ->
            val report = validateCandle(candle, now = now)
            if (report.severity == ValidationSeverity.REJECTED) {
                rejected++
                false
            } else true
        }
        if (rejected > 0) {
            issues.add(ValidationIssue("series", "Dropped $rejected malformed candle(s)", false))
        }

        // 2. Sort + de-duplicate by timestamp (last write wins for live updates).
        val sorted = structurallyValid.sortedBy { it.timestamp }
        val deduped = LinkedHashMap<Long, CandleStick>()
        for (c in sorted) deduped[c.timestamp] = c
        val duplicates = sorted.size - deduped.size
        if (duplicates > 0) {
            issues.add(ValidationIssue("timestamp", "Collapsed $duplicates duplicate timestamp entries", false))
        }

        var cleaned = deduped.values.toList()

        // 3. Single-bar spike filter (needs a decent sample to estimate the median move).
        if (cleaned.size >= 10) {
            cleaned = removeSingleBarSpikes(cleaned, issues)
        }

        // 4. Gap detection on the surviving series.
        if (timeframeMillis > 0 && cleaned.size >= 2) {
            var gaps = 0
            for (i in 1 until cleaned.size) {
                val delta = cleaned[i].timestamp - cleaned[i - 1].timestamp
                if (delta > timeframeMillis * 3L) gaps++
            }
            if (gaps > 0) {
                issues.add(ValidationIssue("series", "$gaps session gap(s) detected in candle sequence", false))
            }
        }

        val usable = cleaned.size >= minValidCount
        if (!usable) {
            issues.add(ValidationIssue("series", "Insufficient valid candles: ${cleaned.size} < $minValidCount", true))
        }

        val quality = computeSeriesQuality(
            validCount = cleaned.size,
            totalCount = series.size,
            warnings = issues.count { !it.isCritical },
            gapsPenalty = issues.count { it.field == "series" && it.message.contains("gap") }
        )
        if (usable && quality < MIN_SERIES_QUALITY) {
            issues.add(ValidationIssue("series", "Series quality $quality% below threshold", true))
        }

        val severity = when {
            issues.any { it.isCritical } -> ValidationSeverity.REJECTED
            issues.isNotEmpty() -> ValidationSeverity.DEGRADED
            else -> ValidationSeverity.HEALTHY
        }
        val report = ValidationReport(
            severity = severity,
            qualityScore = if (severity == ValidationSeverity.REJECTED) min(quality, 20) else quality,
            issues = issues,
            rejectedCount = rejected,
            checkedCount = series.size
        )
        return SeriesValidation(if (report.isUsable) cleaned else emptyList(), report)
    }

    /**
     * Validates a live WebSocket/polling tick against the last known price.
     * Critical failures (NaN/inf/negative prices, inverted quotes, out-of-band jumps)
     * mean the tick must not mutate app state.
     */
    fun validateTick(
        tick: MarketTick,
        referencePrice: Double? = null,
        maxJumpFraction: Double = 0.05,
        maxSpreadFraction: Double = 0.02,
        now: Long = System.currentTimeMillis()
    ): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()

        if (!isFinitePositive(tick.price)) {
            issues.add(ValidationIssue("price", "Tick price ${tick.price} is not finite-positive", true))
        }
        if (tick.bid != 0.0 && !isFinitePositive(tick.bid)) {
            issues.add(ValidationIssue("bid", "Bid ${tick.bid} is not finite-positive", true))
        }
        if (tick.ask != 0.0 && !isFinitePositive(tick.ask)) {
            issues.add(ValidationIssue("ask", "Ask ${tick.ask} is not finite-positive", true))
        }
        if (tick.bid > 0 && tick.ask > 0 && tick.bid > tick.ask + PRICE_EPSILON) {
            issues.add(ValidationIssue("quote", "Inverted bid/ask quote (${tick.bid} > ${tick.ask})", true))
        } else if (tick.bid > 0 && tick.ask > 0) {
            val mid = (tick.bid + tick.ask) / 2.0
            val spread = (tick.ask - tick.bid) / max(mid, PRICE_EPSILON)
            if (spread > maxSpreadFraction) {
                issues.add(ValidationIssue("quote", "Implied spread ${(spread * 10000).toInt()}bp is anomalous", false))
            }
        }
        if (!tick.volume.isFinite() || tick.volume < 0) {
            issues.add(ValidationIssue("volume", "Invalid tick volume", false))
        }
        if (tick.timestamp <= 0L || tick.timestamp > now + FUTURE_TOLERANCE_MS) {
            issues.add(ValidationIssue("timestamp", "Implausible tick timestamp", true))
        }
        if (referencePrice != null && referencePrice > 0 && tick.price.isFinite() && tick.price > 0) {
            val jump = abs(tick.price - referencePrice) / referencePrice
            if (jump > maxJumpFraction) {
                issues.add(ValidationIssue("price", "Tick jumps ${(jump * 100).toInt()}% past last price — likely stale/corrupt", true))
            }
        }

        return buildReport(issues, checkedCount = 1, rejectedCount = if (issues.any { it.isCritical }) 1 else 0)
    }

    /** Validates the raw symbol format before provider/network work is attempted. */
    fun validateSymbolFormat(symbol: String): ValidationReport {
        val cleaned = symbol.trim().uppercase()
        val ok = Regex("^[A-Z0-9]{2,6}[/:\\-_]?[A-Z]{2,4}$").matches(cleaned) ||
            Regex("^[A-Z0-9]{2,6}/[A-Z]{2,4}$").matches(cleaned)
        return if (ok) {
            ValidationReport.healthy()
        } else {
            ValidationReport(ValidationSeverity.REJECTED, 0, listOf(ValidationIssue("symbol", "Unsupported symbol format '$symbol'", true)))
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun buildReport(
        issues: List<ValidationIssue>,
        checkedCount: Int,
        rejectedCount: Int,
        gapPenalty: Int = 0
    ): ValidationReport {
        val critical = issues.count { it.isCritical }
        val warnings = issues.count { !it.isCritical }
        val severity = when {
            critical > 0 -> ValidationSeverity.REJECTED
            warnings > 0 -> ValidationSeverity.DEGRADED
            else -> ValidationSeverity.HEALTHY
        }
        var quality = 100 - critical * 45 - warnings * 5 - gapPenalty * 3
        quality = quality.coerceIn(0, 100)
        return ValidationReport(
            severity = severity,
            qualityScore = if (severity == ValidationSeverity.REJECTED) minOf(quality, 20) else quality,
            issues = issues,
            rejectedCount = rejectedCount,
            checkedCount = checkedCount
        )
    }

    /**
     * Drops candles whose close spikes out and immediately back relative to the robust
     * median absolute close-to-close change. Real gap/news moves (one-way) are preserved.
     */
    private fun removeSingleBarSpikes(candles: List<CandleStick>, issues: MutableList<ValidationIssue>): List<CandleStick> {
        val changes = ArrayList<Double>(candles.size)
        for (i in 1 until candles.size) changes.add(abs(candles[i].close - candles[i - 1].close))
        if (changes.isEmpty()) return candles
        val median = median(changes)
        // Floor tied to price magnitude so dead-flat series (median ~ 0) stay untouched
        // and a spike needs a large relative excursion too before removal.
        val avgPrice = candles.sumOf { it.close } / candles.size
        val threshold = max(median * 8.0, avgPrice * 0.05)
        if (threshold <= 0 || !threshold.isFinite()) return candles

        val keep = ArrayList<CandleStick>(candles.size)
        var spikes = 0
        for (i in candles.indices) {
            val isSpike = i > 0 && i < candles.lastIndex &&
                abs(candles[i].close - candles[i - 1].close) > threshold &&
                abs(candles[i + 1].close - candles[i].close) > threshold &&
                (candles[i].close - candles[i - 1].close > 0) != (candles[i + 1].close - candles[i].close > 0)
            if (isSpike) spikes++ else keep.add(candles[i])
        }
        if (spikes > 0) {
            issues.add(ValidationIssue("series", "Removed $spikes statistical price spike(s)", false))
        }
        return keep
    }

    private fun computeSeriesQuality(validCount: Int, totalCount: Int, warnings: Int, gapsPenalty: Int): Int {
        if (totalCount <= 0) return 0
        // Logarithmic warning dampening keeps scores realistic for large series.
        val warningPenalty = (10.0 * ln(1.0 + warnings)).toInt()
        val integrity = (100.0 * validCount / totalCount).toInt()
        val quality = integrity - warningPenalty - gapsPenalty * 3
        return quality.coerceIn(0, 100)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
