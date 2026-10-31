package com.example.forex.data.model

/**
 * Canonical registry of tradable instruments.
 *
 * Provides symbol parsing, pip-size inference and [CurrencyPair] construction for
 * components that operate outside the in-memory repository state (Workers, market
 * data providers, validation) so pair metadata is defined in exactly one place.
 */
object PairCatalog {

    data class Definition(
        val symbol: String,
        val name: String,
        val category: PairCategory,
        val baseCurrency: String,
        val quoteCurrency: String,
        val pipSize: Double
    )

    val KNOWN: List<Definition> = listOf(
        Definition("EUR/USD", "Euro / US Dollar", PairCategory.MAJOR, "EUR", "USD", 0.0001),
        Definition("GBP/USD", "British Pound / US Dollar", PairCategory.MAJOR, "GBP", "USD", 0.0001),
        Definition("USD/JPY", "US Dollar / Japanese Yen", PairCategory.MAJOR, "USD", "JPY", 0.01),
        Definition("AUD/USD", "Australian Dollar / US Dollar", PairCategory.MAJOR, "AUD", "USD", 0.0001),
        Definition("USD/CAD", "US Dollar / Canadian Dollar", PairCategory.MAJOR, "USD", "CAD", 0.0001),
        Definition("EUR/JPY", "Euro / Japanese Yen", PairCategory.MINOR, "EUR", "JPY", 0.01),
        Definition("GBP/JPY", "British Pound / Japanese Yen", PairCategory.MINOR, "GBP", "JPY", 0.01),
        Definition("XAU/USD", "Gold / US Dollar", PairCategory.METALS_CRYPTO, "XAU", "USD", 0.1),
        Definition("BTC/USD", "Bitcoin / US Dollar", PairCategory.METALS_CRYPTO, "BTC", "USD", 1.0)
    )

    private val bySymbol = KNOWN.associateBy { it.symbol }

    /** Base currencies treated as digital assets by upstream market data providers. */
    val CRYPTO_BASES: Set<String> = setOf(
        "BTC", "ETH", "XRP", "LTC", "BCH", "SOL", "ADA", "DOGE",
        "XMR", "ETC", "UNI", "LINK", "AVAX", "DOT", "SHIB", "USDT", "USDC"
    )

    /**
     * Splits "EUR/USD" (or "EURUSD") into a base/quote pair. Falls back to the
     * conventional 3+3 split for concatenated FX ticker symbols.
     */
    fun parseSymbol(rawSymbol: String): Pair<String, String>? {
        val cleaned = rawSymbol.trim().uppercase()
        val parts = cleaned.split('/', '-', '_')
        return when {
            parts.size >= 2 && parts[0].length >= 3 && parts[1].length >= 3 -> parts[0] to parts[1]
            cleaned.length == 6 -> cleaned.substring(0, 3) to cleaned.substring(3)
            cleaned.contains(':') -> { // e.g. "BINANCE:BTCUSDT"
                val tail = cleaned.substringAfterLast(':')
                if (tail.length == 6) tail.substring(0, 3) to tail.substring(3) else null
            }
            else -> null
        }
    }

    fun isCryptoBase(base: String): Boolean = CRYPTO_BASES.contains(base.uppercase())

    fun isCrypto(symbol: String): Boolean {
        val (base, _) = parseSymbol(symbol) ?: return false
        return isCryptoBase(base)
    }

    fun definitionFor(symbol: String): Definition {
        bySymbol[symbol.trim().uppercase()]?.let { return it }
        val parsed = parseSymbol(symbol)
        val base = parsed?.first ?: symbol.uppercase()
        val quote = parsed?.second ?: "USD"
        val pip = inferPipSize(base, quote)
        val category = when {
            isCryptoBase(base) -> PairCategory.METALS_CRYPTO
            quote == "USD" || quote == "EUR" || quote == "JPY" || quote == "GBP" || quote == "CHF" || quote == "CAD" || quote == "AUD" || quote == "NZD" || quote == "XAU" ->
                PairCategory.MAJOR
            else -> PairCategory.EXOTIC
        }
        return Definition(
            symbol = "$base/$quote",
            name = "$base / $quote",
            category = category,
            baseCurrency = base,
            quoteCurrency = quote,
            pipSize = pip
        )
    }

    fun pipSizeFor(symbol: String): Double = definitionFor(symbol).pipSize

    private fun inferPipSize(base: String, quote: String): Double = when {
        isCryptoBase(base) -> 1.0
        base == "XAU" || base == "XAG" || quote == "XAU" -> 0.1
        quote == "JPY" || base == "JPY" -> 0.01
        else -> 0.0001
    }

    /**
     * Builds a display/analysis [CurrencyPair] for symbols that are not tracked by the
     * live repository (e.g. inside [com.example.forex.worker.SignalWorker]) from observed
     * candle data — all numbers passed in must come from real market data, never hardcoded.
     */
    fun pairFor(symbol: String, currentPrice: Double, high24h: Double, low24h: Double): CurrencyPair {
        val def = definitionFor(symbol)
        val change = if (low24h > 0 && high24h > low24h) {
            ((currentPrice - low24h) / (high24h - low24h) - 0.5) * 2.0 *
                ((high24h - low24h) / maxOf(currentPrice, low24h, Double.MIN_VALUE) * 100.0).coerceAtMost(25.0)
        } else 0.0
        return CurrencyPair(
            symbol = def.symbol,
            name = def.name,
            category = def.category,
            baseCurrency = def.baseCurrency,
            quoteCurrency = def.quoteCurrency,
            currentPrice = currentPrice,
            priceChange24h = change,
            high24h = high24h,
            low24h = low24h,
            pipSize = def.pipSize
        )
    }
}
