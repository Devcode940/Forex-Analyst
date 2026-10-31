package com.example.forex.data.remote

import com.example.BuildConfig
import com.example.forex.data.model.CandleStick
import com.example.forex.data.model.MarketTick
import com.example.forex.data.model.PairCatalog
import com.example.forex.data.model.Timeframe
import com.example.forex.data.validation.MarketDataValidator
import com.example.forex.data.validation.ValidationReport
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.max

/** Which upstream currently supplied data (drives UI "Live vs Simulated" badges). */
enum class MarketDataSource { ALPHA_VANTAGE, FCS_API, SIMULATION }

/**
 * Runtime health of the market data pipeline. Surfaced in the ViewModel so the UI
 * (and future telemetry) can explain where numbers came from and why they may be stale.
 */
data class MarketDataHealth(
    val source: MarketDataSource = MarketDataSource.SIMULATION,
    val isConfigured: Boolean = false,
    val requestsToday: Int = 0,
    val dailyQuota: Int = 0,
    val lastSuccessAt: Long = 0L,
    val lastErrorAt: Long = 0L,
    val lastErrorMessage: String? = null,
    val isRateLimited: Boolean = false
)

/** Normalized failure taxonomy for every market data fetch path. */
sealed class MarketDataException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : MarketDataException(
        "ALPHAVANTAGE_API_KEY is not configured in the Secrets panel (.env)."
    )

    class RateLimited(val retryAfterMs: Long) :
        MarketDataException("Alpha Vantage rate limit hit — retry after ${retryAfterMs}ms")

    class DailyQuotaExhausted :
        MarketDataException("Alpha Vantage daily request quota exhausted; falling back to cached/local data.")

    class PremiumFeatureRequired(val function: String) :
        MarketDataException("Endpoint '$function' requires a premium Alpha Vantage plan.")

    class InvalidRequest(reason: String) : MarketDataException("Invalid request: $reason")
    class NoData(reason: String) : MarketDataException("No usable data returned: $reason")
    class Network(cause: Throwable) : MarketDataException("Network failure: ${cause.message}", cause)
    class Validation(val report: ValidationReport) :
        MarketDataException("Payload rejected by validator: ${report.summary}")
}

/**
 * Abstraction over live market data upstreams so the repository can chain
 * Alpha Vantage -> FCS -> simulation without call-site branching.
 */
interface MarketDataProvider {
    fun isConfigured(): Boolean
    val health: StateFlow<MarketDataHealth>

    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        forceRefresh: Boolean = false
    ): Result<List<CandleStick>>

    suspend fun fetchLatestQuote(symbol: String): Result<MarketTick>
}

/**
 * Production market data provider backed by Alpha Vantage.
 *
 * Design notes:
 *  - All requests go through a strict, coroutine-safe limiter: Alpha Vantage enforces a
 *    per-minute *and* per-day quota (free tiers are tiny — currently ~5 req/min, 25/day).
 *    We self-throttle so the app degrades gracefully (stale cache -> fallbacks) instead
 *    of hammering the API. Quota values are constructor parameters, so premium plans can
 *    raise them without touching the implementation.
 *  - `datatype=csv` is used deliberately: the JSON time-series keys are unstable across
 *    endpoint generations ("Time Series FX (15min)", "1. open", "5. Volume (USD)", ...),
 *    while the CSV columns are documented as stable. JSON is only parsed to classify
 *    200-OK error envelopes (Note / Information / Error Message).
 *  - Every payload passes through [MarketDataValidator] before it is cached or returned:
 *    bad OHLC structure, future timestamps, duplicated bars, statistical spikes and
 *    undersized series never reach the analysis engine.
 *  - Timeframes the API does not serve natively (H4) are built client-side by
 *    aggregating 1-hour candles; premium-only intraday functions surface as
 *    [MarketDataException.PremiumFeatureRequired] so the repository can fall back to FCS.
 */
class RealMarketDataProvider(
    private val apiKey: String = BuildConfig.ALPHAVANTAGE_API_KEY,
    baseUrl: String = "https://www.alphavantage.co/",
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
    private val dailyQuota: Int = DEFAULT_DAILY_QUOTA,
    private val minRequestIntervalMs: Long = DEFAULT_MIN_REQUEST_INTERVAL_MS,
    private val candleCacheTtlOverrideMs: Long? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    client: OkHttpClient? = null,
    private val api: AlphaVantageApi? = null
) : MarketDataProvider {

    private val okHttpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // NOTE: no HttpLoggingInterceptor — the apikey travels in the query string.
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .build()

    private val service: AlphaVantageApi = api ?: retrofit.create(AlphaVantageApi::class.java)

    private val _health = MutableStateFlow(MarketDataHealth(isConfigured = isConfigured(), dailyQuota = dailyQuota))
    override val health: StateFlow<MarketDataHealth> = _health.asStateFlow()

    // --- self-imposed request budgeting (all mutation happens under requestMutex) ---
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L
    private var requestDayUtc = ""
    private var requestsToday = 0

    private data class CacheEntry(val payload: List<CandleStick>, val fetchedAt: Long)

    private val candleCache = ConcurrentHashMap<String, CacheEntry>()

    override fun isConfigured(): Boolean =
        apiKey.isNotBlank() && !apiKey.equals("YOUR_ALPHAVANTAGE_API_KEY", ignoreCase = true)

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    override suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        forceRefresh: Boolean
    ): Result<List<CandleStick>> {
        if (!isConfigured()) return Result.failure(MarketDataException.NotConfigured())

        val symbolCheck = MarketDataValidator.validateSymbolFormat(symbol)
        if (!symbolCheck.isUsable) {
            return Result.failure(MarketDataException.InvalidRequest(symbolCheck.summary))
        }

        val normalizedSymbol = PairCatalog.definitionFor(symbol).symbol
        val cacheKey = "$normalizedSymbol|${timeframe.name}"
        val now = clock()
        if (!forceRefresh) {
            candleCache[cacheKey]?.let { entry ->
                if (now - entry.fetchedAt <= candleCacheTtl(timeframe)) {
                    markSuccess(MarketDataSource.ALPHA_VANTAGE)
                    return Result.success(entry.payload)
                }
            }
        }

        val plan = runCatching { planFor(normalizedSymbol, timeframe) }
            .getOrElse { return Result.failure(it as? MarketDataException ?: MarketDataException.InvalidRequest(it.message ?: "unknown symbol")) }

        return try {
            val body = executeWithLimits(plan.requestParams)
            var candles = parseTimeSeriesCsv(body).getOrThrow()
            plan.aggregateToMillis?.let { candles = aggregateToBucket(candles, it) }

            val validation = MarketDataValidator.validateCandleSeries(
                series = candles,
                timeframeMillis = timeframe.minutes * 60_000L,
                minValidCount = plan.minValidCount
            )
            if (!validation.report.isUsable) {
                throw MarketDataException.Validation(validation.report)
            }
            // Cap the retained window so "full" daily fetches cannot balloon memory.
            val bounded = validation.candles.takeLast(MAX_CANDLES_KEPT)
            candleCache[cacheKey] = CacheEntry(bounded, clock())
            markSuccess(MarketDataSource.ALPHA_VANTAGE)
            Result.success(bounded)
        } catch (e: MarketDataException) {
            markError(e)
            Result.failure(e)
        } catch (e: Exception) {
            val wrapped = MarketDataException.Network(e)
            markError(wrapped)
            Result.failure(wrapped)
        }
    }

    override suspend fun fetchLatestQuote(symbol: String): Result<MarketTick> {
        if (!isConfigured()) return Result.failure(MarketDataException.NotConfigured())
        val parsed = PairCatalog.parseSymbol(symbol)
            ?: return Result.failure(MarketDataException.InvalidRequest("Cannot split '$symbol' into base/quote"))
        val (base, quote) = parsed
        val displaySymbol = "$base/$quote"

        return try {
            val body = executeWithLimits(
                linkedMapOf(
                    "function" to "CURRENCY_EXCHANGE_RATE",
                    "from_currency" to base,
                    "to_currency" to quote
                )
            )
            val tick = parseQuoteCsv(displaySymbol, body) ?: throw MarketDataException.NoData("Unrecognized quote payload")
            val report = MarketDataValidator.validateTick(
                tick = tick,
                referencePrice = null,
                maxJumpFraction = 1.0, // realtime snapshot vs. no reference: structural checks only
                maxSpreadFraction = 0.05,
                now = clock()
            )
            if (!report.isUsable) throw MarketDataException.Validation(report)
            markSuccess(MarketDataSource.ALPHA_VANTAGE)
            Result.success(tick)
        } catch (e: MarketDataException) {
            markError(e)
            Result.failure(e)
        } catch (e: Exception) {
            val wrapped = MarketDataException.Network(e)
            markError(wrapped)
            Result.failure(wrapped)
        }
    }

    /** Drops cached candles for a symbol (used after manual refresh actions). */
    fun invalidateCache(symbol: String? = null) {
        if (symbol == null) candleCache.clear()
        else candleCache.keys.removeAll { it.startsWith("$symbol|") }
    }

    // ---------------------------------------------------------------------
    // Request planning
    // ---------------------------------------------------------------------

    internal data class RequestPlan(
        val function: String,
        val requestParams: Map<String, String>,
        /** When set, native 60-min candles are aggregated into this bucket size (ms). */
        val aggregateToMillis: Long? = null,
        /** Minimum surviving candles required for the series to be usable. */
        val minValidCount: Int = 40
    )

    internal companion object {

        const val DEFAULT_DAILY_QUOTA = 25
        const val DEFAULT_MIN_REQUEST_INTERVAL_MS = 13_000L

        /** Upper bound for candles retained per symbol/timeframe window. */
        const val MAX_CANDLES_KEPT = 400

        private val CRYPTO_INTRADAY_INTERVALS = mapOf(
            Timeframe.M1 to "1min",
            Timeframe.M5 to "5min",
            Timeframe.M15 to "15min",
            Timeframe.H1 to "60min"
        )
        private val FX_INTRADAY_INTERVALS = mapOf(
            Timeframe.M1 to "1min",
            Timeframe.M5 to "5min",
            Timeframe.M15 to "15min",
            Timeframe.H1 to "60min"
        )

        /**
         * Maps the app's [Timeframe] + instrument type onto an Alpha Vantage function call.
         * Note: FX_INTRADAY / CRYPTO_INTRADAY are premium-gated on the free tier; the
         * provider converts that into [MarketDataException.PremiumFeatureRequired] and
         * callers fall back to FCS. H4 is derived by aggregating 1h candles.
         */
        fun planFor(symbol: String, timeframe: Timeframe): RequestPlan {
            val (base, quote) = PairCatalog.parseSymbol(symbol)
                ?: throw MarketDataException.InvalidRequest("Unsupported symbol '$symbol'")
            val crypto = PairCatalog.isCryptoBase(base)

            return when (timeframe) {
                Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.H1 -> {
                    val interval = if (crypto) {
                        CRYPTO_INTRADAY_INTERVALS[timeframe] ?: throw MarketDataException.InvalidRequest("No crypto interval for $timeframe")
                    } else {
                        FX_INTRADAY_INTERVALS[timeframe] ?: throw MarketDataException.InvalidRequest("No FX interval for $timeframe")
                    }
                    if (crypto) {
                        RequestPlan(
                            function = "CRYPTO_INTRADAY",
                            requestParams = linkedMapOf(
                                "function" to "CRYPTO_INTRADAY",
                                "symbol" to base,
                                "market" to quote,
                                "interval" to interval,
                                "outputsize" to "compact"
                            )
                        )
                    } else {
                        RequestPlan(
                            function = "FX_INTRADAY",
                            requestParams = linkedMapOf(
                                "function" to "FX_INTRADAY",
                                "from_symbol" to base,
                                "to_symbol" to quote,
                                "interval" to interval,
                                "outputsize" to "compact"
                            )
                        )
                    }
                }

                Timeframe.H4 -> {
                    // No native 4-hour series on Alpha Vantage: request 1h and aggregate.
                    // 100 hourly bars (compact) yield ~25 aggregated H4 bars, so the
                    // usability floor is relaxed for this derived timeframe.
                    if (crypto) {
                        RequestPlan(
                            function = "CRYPTO_INTRADAY",
                            requestParams = linkedMapOf(
                                "function" to "CRYPTO_INTRADAY",
                                "symbol" to base,
                                "market" to quote,
                                "interval" to "60min",
                                "outputsize" to "compact"
                            ),
                            aggregateToMillis = 4 * 60 * 60 * 1000L,
                            minValidCount = 24
                        )
                    } else {
                        RequestPlan(
                            function = "FX_INTRADAY",
                            requestParams = linkedMapOf(
                                "function" to "FX_INTRADAY",
                                "from_symbol" to base,
                                "to_symbol" to quote,
                                "interval" to "60min",
                                "outputsize" to "compact"
                            ),
                            aggregateToMillis = 4 * 60 * 60 * 1000L,
                            minValidCount = 24
                        )
                    }
                }

                Timeframe.D1 -> {
                    if (crypto) {
                        RequestPlan(
                            function = "DIGITAL_CURRENCY_DAILY",
                            requestParams = linkedMapOf(
                                "function" to "DIGITAL_CURRENCY_DAILY",
                                "symbol" to base,
                                "market" to quote,
                                "outputsize" to "full"
                            )
                        )
                    } else {
                        RequestPlan(
                            function = "FX_DAILY",
                            requestParams = linkedMapOf(
                                "function" to "FX_DAILY",
                                "from_symbol" to base,
                                "to_symbol" to quote,
                                "outputsize" to "full"
                            )
                        )
                    }
                }
            }
        }

        /**
         * Parses Alpha Vantage time-series CSV ("time,open,high,low,close[,volume][,timestamp]").
         * Column lookup is by name (not position) so schema drift between endpoints/versions
         * is tolerated. Malformed rows are skipped; success requires at least one valid bar.
         */
        fun parseTimeSeriesCsv(text: String): Result<List<CandleStick>> {
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("Thank you", ignoreCase = true) }
                .toList()
            if (lines.size < 2) {
                return Result.failure(MarketDataException.NoData("CSV payload has no data rows"))
            }

            val headers = lines[0].split(',').map { it.trim().removeSurrounding("\"").lowercase(Locale.US) }
            fun indexOfAny(vararg names: String): Int = headers.indexOfFirst { it in names }
            fun indexOfContaining(fragment: String): Int = headers.indexOfFirst { it.contains(fragment) }

            val idxOpen = indexOfAny("open")
            val idxHigh = indexOfAny("high")
            val idxLow = indexOfAny("low")
            val idxClose = indexOfAny("close")
            val idxVolume = indexOfAny("volume").takeIf { it >= 0 } ?: indexOfContaining("volume")
            val idxTimestampEpoch = indexOfAny("timestamp")
            val idxTime = indexOfAny("time", "date")
            if (idxOpen < 0 || idxHigh < 0 || idxLow < 0 || idxClose < 0 || (idxTimestampEpoch < 0 && idxTime < 0)) {
                return Result.failure(MarketDataException.NoData("Missing OHLC columns in '${lines[0]}'"))
            }

            val candles = ArrayList<CandleStick>(lines.size - 1)
            for (i in 1 until lines.size) {
                val row = lines[i].split(',')
                if (row.size <= max(idxOpen, max(idxHigh, max(idxLow, idxClose)))) continue

                val open = row.getOrNull(idxOpen)?.toDoubleOrNull() ?: continue
                val high = row.getOrNull(idxHigh)?.toDoubleOrNull() ?: continue
                val low = row.getOrNull(idxLow)?.toDoubleOrNull() ?: continue
                val close = row.getOrNull(idxClose)?.toDoubleOrNull() ?: continue

                val timestamp = resolveCandleTimestamp(row, idxTimestampEpoch, idxTime) ?: continue

                val volume = idxVolume.takeIf { it >= 0 }
                    ?.let { row.getOrNull(it)?.toDoubleOrNull() }
                    ?.takeIf { it.isFinite() && it >= 0 }
                    ?: 0.0

                candles.add(CandleStick(timestamp, open, high, low, close, volume))
            }

            if (candles.isEmpty()) {
                return Result.failure(MarketDataException.NoData("No parseable OHLC rows"))
            }
            return Result.success(candles.sortedBy { it.timestamp })
        }

        /** Parses CURRENCY_EXCHANGE_RATE CSV into a [MarketTick] (price/bid/ask). */
        fun parseQuoteCsv(displaySymbol: String, text: String): MarketTick? {
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("Thank you", ignoreCase = true) }
                .toList()
            if (lines.size < 2) return null
            val headers = lines[0].split(',').map { it.trim().removeSurrounding("\"").lowercase(Locale.US) }
            val values = lines[1].split(',').map { it.trim().removeSurrounding("\"") }
            fun field(vararg names: String): String? {
                for (name in names) {
                    val idx = headers.indexOf(name)
                    if (idx >= 0 && idx < values.size) return values[idx]
                }
                return null
            }

            val price = field("exchange_rate", "price", "last_price")?.toDoubleOrNull() ?: return null
            val bid = field("bid_price", "bid")?.toDoubleOrNull() ?: price
            val ask = field("ask_price", "ask")?.toDoubleOrNull() ?: price
            val timestamp = field("timestamp", "last_refreshed", "last_refreshed_time")
                ?.let { resolveEpochOrDate(it) }
                ?: System.currentTimeMillis()
            return MarketTick(
                symbol = displaySymbol,
                price = price,
                bid = bid,
                ask = ask,
                volume = 0.0,
                timestamp = timestamp
            )
        }

        /**
         * Aggregates a base-timeframe series into larger buckets (e.g. 1h -> 4h).
         * Buckets are aligned to UTC epoch multiples so all clients derive identical bars.
         */
        fun aggregateToBucket(candles: List<CandleStick>, bucketMillis: Long): List<CandleStick> {
            if (candles.isEmpty() || bucketMillis <= 0) return candles
            val out = ArrayList<CandleStick>()
            var bucket = Long.MIN_VALUE
            var open = 0.0; var high = 0.0; var low = 0.0; var close = 0.0; var volume = 0.0
            for (c in candles) {
                val b = floor(c.timestamp.toDouble() / bucketMillis).toLong() * bucketMillis
                if (b != bucket) {
                    if (bucket != Long.MIN_VALUE) out.add(CandleStick(bucket, open, high, low, close, volume))
                    bucket = b
                    open = c.open; high = c.high; low = c.low; close = c.close; volume = c.volume
                } else {
                    high = max(high, c.high)
                    low = if (low > c.low) c.low else low
                    close = c.close
                    volume += c.volume
                }
            }
            if (bucket != Long.MIN_VALUE) out.add(CandleStick(bucket, open, high, low, close, volume))
            return out
        }

        /** Turns HTTP-200 JSON error envelopes into typed [MarketDataException]s. */
        fun classifyErrorPayload(note: String?, information: String?, errorMessage: String?): MarketDataException {
            val message = (note ?: information ?: errorMessage ?: "Unexpected Alpha Vantage response").lowercase(Locale.US)
            return when {
                message.contains("premium") || message.contains("paid plan") || message.contains("subscribe") ->
                    MarketDataException.PremiumFeatureRequired(message)
                message.contains("per day") || message.contains("daily") || message.contains("quota") ->
                    MarketDataException.DailyQuotaExhausted()
                message.contains("rate limit") || message.contains("per minute") || message.contains("per second") ||
                    message.contains("call frequency") ->
                    MarketDataException.RateLimited(65_000L)
                else -> MarketDataException.InvalidRequest(note ?: information ?: errorMessage ?: "unknown")
            }
        }

        private fun resolveCandleTimestamp(row: List<String>, idxEpoch: Int, idxTime: Int): Long? {
            if (idxEpoch >= 0) {
                row.getOrNull(idxEpoch)?.toLongOrNull()?.let { raw ->
                    return if (raw > 1_000_000_000_000L) raw else raw * 1000L
                }
            }
            return row.getOrNull(idxTime)?.let { resolveEpochOrDate(it) }
        }

        private val DATE_PATTERNS = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")

        internal fun resolveEpochOrDate(raw: String): Long? {
            val value = raw.trim().removeSurrounding("\"")
            value.toLongOrNull()?.let { return if (it > 1_000_000_000_000L) it else it * 1000L }
            for (pattern in DATE_PATTERNS) {
                try {
                    val format = SimpleDateFormat(pattern, Locale.US)
                    format.timeZone = TimeZone.getTimeZone("UTC")
                    format.parse(value)?.let { return it.time }
                } catch (_: Exception) {
                    // try next pattern
                }
            }
            return null
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private suspend fun executeWithLimits(baseParams: Map<String, String>): String {
        requestMutex.withLock {
            val now = clock()
            val today = utcDay(now)
            if (today != requestDayUtc) {
                requestDayUtc = today
                requestsToday = 0
            }
            if (requestsToday >= dailyQuota) throw MarketDataException.DailyQuotaExhausted()
            val waitMs = lastRequestAt + minRequestIntervalMs - now
            if (waitMs > 0) delay(waitMs)

            val params = LinkedHashMap<String, String>(baseParams)
            params["apikey"] = apiKey
            params["datatype"] = "csv"

            try {
                val response: Response<okhttp3.ResponseBody> = service.query(params)
                val raw = (response.body()?.string() ?: response.errorBody()?.string()) ?: ""
                // The request left the device: it counts against both pacing and daily quota,
                // even if the envelope reports a rate limit or premium restriction.
                lastRequestAt = clock()
                requestsToday++
                return classifyAndExtract(raw, response.code())
            } catch (e: IOException) {
                // Transport failure: honor request pacing but do not burn daily quota.
                lastRequestAt = clock()
                throw MarketDataException.Network(e)
            } finally {
                updateRequestGauge()
            }
        }
    }

    private fun classifyAndExtract(rawBody: String, httpCode: Int): String {
        val trimmed = rawBody.trim()
        if (httpCode != 200) {
            throw MarketDataException.Network(IOException("HTTP $httpCode from Alpha Vantage"))
        }
        if (trimmed.isEmpty()) {
            throw MarketDataException.NoData("Empty body")
        }
        if (trimmed.startsWith("{")) {
            val payload = try {
                moshi.adapter(AlphaVantageErrorPayload::class.java).fromJson(trimmed)
            } catch (_: Exception) {
                null
            }
            if (payload == null || payload.isBlank) {
                throw MarketDataException.NoData("Unexpected JSON payload")
            }
            throw classifyErrorPayload(payload.note, payload.information, payload.errorMessage)
        }
        if (trimmed.startsWith("Invalid", ignoreCase = true)) {
            throw MarketDataException.InvalidRequest(trimmed.lineSequence().firstOrNull() ?: "")
        }
        if (!trimmed.contains(',')) {
            throw MarketDataException.NoData(trimmed.take(120))
        }
        return trimmed
    }

    private fun updateRequestGauge() {
        val current = _health.value
        _health.value = current.copy(requestsToday = requestsToday, dailyQuota = dailyQuota)
    }

    private fun markSuccess(source: MarketDataSource) {
        _health.value = _health.value.copy(
            source = source,
            isConfigured = isConfigured(),
            lastSuccessAt = clock(),
            lastErrorMessage = null,
            isRateLimited = false
        )
    }

    private fun markError(error: MarketDataException) {
        val rateLimited = error is MarketDataException.RateLimited || error is MarketDataException.DailyQuotaExhausted
        _health.value = _health.value.copy(
            isConfigured = isConfigured(),
            lastErrorAt = clock(),
            lastErrorMessage = error.message,
            isRateLimited = rateLimited
        )
    }

    private fun candleCacheTtl(timeframe: Timeframe): Long {
        candleCacheTtlOverrideMs?.let { return it }
        return when (timeframe) {
            Timeframe.D1 -> TimeUnit.HOURS.toMillis(4)
            else -> max(TimeUnit.MINUTES.toMillis(1), timeframe.minutes * 30_000L)
        }
    }

    private fun utcDay(millis: Long): String {
        val format = SimpleDateFormat("yyyyMMdd", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(millis))
    }
}
