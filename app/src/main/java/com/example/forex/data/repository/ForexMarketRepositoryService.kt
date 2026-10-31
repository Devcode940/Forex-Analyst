package com.example.forex.data.repository

import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit Repository Service for fetching real-time Forex market data from FCS API using FCS_API_KEY.
 */
class ForexMarketRepositoryService(
    private val apiKey: String = BuildConfig.FCS_API_KEY
) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://fcsapi.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val apiService: FcsApi = retrofit.create(FcsApi::class.java)

    fun isConfigured(): Boolean {
        return apiKey.isNotBlank() && apiKey != "YOUR_FCS_API_KEY"
    }

    suspend fun fetchLatestPrices(symbols: List<String>): Result<List<FcsPriceInfo>> {
        if (!isConfigured()) {
            return Result.failure(IllegalStateException("FCS_API_KEY is not configured in Secrets panel."))
        }
        return try {
            val symbolString = symbols.joinToString(",")
            val response = apiService.getLatestPrices(symbolString, apiKey)
            if (response.isSuccessful && response.body()?.status == true) {
                val data = response.body()?.response ?: emptyList()
                Result.success(data)
            } else {
                val msg = response.body()?.msg ?: response.message() ?: "Failed to fetch Forex prices"
                Result.failure(RuntimeException(msg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchHistoricalCandles(symbol: String, period: String = "1h"): Result<List<FcsHistoryCandle>> {
        if (!isConfigured()) {
            return Result.failure(IllegalStateException("FCS_API_KEY is not configured in Secrets panel."))
        }
        return try {
            val response = apiService.getHistoryCandles(symbol, period, apiKey)
            if (response.isSuccessful && response.body()?.status == true) {
                val map = response.body()?.response ?: emptyMap()
                val candleList = map.values.toList()
                Result.success(candleList)
            } else {
                val msg = response.body()?.msg ?: response.message() ?: "Failed to fetch historical candles"
                Result.failure(RuntimeException(msg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun pollRealtimeMarketData(
        symbols: List<String>,
        pollIntervalMs: Long = 5000
    ): Flow<Result<List<FcsPriceInfo>>> = flow {
        while (true) {
            val result = fetchLatestPrices(symbols)
            emit(result)
            delay(pollIntervalMs)
        }
    }
}
