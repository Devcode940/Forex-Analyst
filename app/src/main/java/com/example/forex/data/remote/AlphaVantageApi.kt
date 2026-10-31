package com.example.forex.data.remote

import com.squareup.moshi.Json
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.QueryMap

/**
 * Retrofit binding for the Alpha Vantage market data API (https://www.alphavantage.co/documentation/).
 *
 * Alpha Vantage multiplexes every dataset through a single `/query` endpoint keyed by the
 * `function` parameter, so the raw call surface is intentionally generic: responses are
 * consumed as CSV text (stable, schema-light) with JSON only returned for errors.
 *
 * Rate limits (documented at signup): the free tier is limited to a small number of
 * requests per minute AND per day; premium-only functions (e.g. FX_INTRADAY,
 * CRYPTO_INTRADAY for some intervals) return an informational JSON payload rather than
 * HTTP 4xx for unauthorized plans. All of that is normalized by [RealMarketDataProvider].
 */
interface AlphaVantageApi {

    @GET("query")
    suspend fun query(@QueryMap params: Map<String, String>): Response<ResponseBody>
}

/**
 * Non-data payloads Alpha Vantage returns with HTTP 200:
 *  - "Note"         legacy rate-limit notice
 *  - "Information"  current rate-limit / plan / premium-function notice
 *  - "Error Message" invalid parameters
 */
data class AlphaVantageErrorPayload(
    @Json(name = "Note") val note: String? = null,
    @Json(name = "Information") val information: String? = null,
    @Json(name = "Error Message") val errorMessage: String? = null
) {
    val firstMessage: String?
        get() = note ?: information ?: errorMessage

    val isBlank: Boolean
        get() = note == null && information == null && errorMessage == null
}
