package com.example.forex.data.repository

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.Response

interface FcsApi {
    @GET("api-v3/forex/latest")
    suspend fun getLatestPrices(
        @Query("symbol") symbols: String,
        @Query("access_key") accessKey: String
    ): Response<FcsLatestResponse>

    @GET("api-v3/forex/history")
    suspend fun getHistoryCandles(
        @Query("symbol") symbol: String,
        @Query("period") period: String,
        @Query("access_key") accessKey: String
    ): Response<FcsHistoryResponse>
}

data class FcsLatestResponse(
    val status: Boolean = false,
    val code: Int? = null,
    val msg: String? = null,
    val response: List<FcsPriceInfo>? = null,
    val info: FcsInfo? = null
)

data class FcsPriceInfo(
    val id: String? = null,
    val s: String, // symbol
    val o: String? = null, // open
    val h: String? = null, // high
    val l: String? = null, // low
    val c: String, // close
    val ch: String? = null, // change
    val cp: String? = null, // change percentage
    val t: String? = null, // timestamp
    val tm: String? = null // time
)

data class FcsInfo(
    val server_time: String? = null,
    val credit_count: Int? = null,
    val process_time: String? = null
)

data class FcsHistoryResponse(
    val status: Boolean = false,
    val code: Int? = null,
    val msg: String? = null,
    val response: Map<String, FcsHistoryCandle>? = null,
    val info: FcsInfo? = null
)

data class FcsHistoryCandle(
    val o: String,
    val h: String,
    val l: String,
    val c: String,
    val v: String? = null,
    val t: String? = null
)

