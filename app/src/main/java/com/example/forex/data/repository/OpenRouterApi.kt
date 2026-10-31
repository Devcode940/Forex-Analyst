package com.example.forex.data.repository

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenRouterApi {
    @POST("api/v1/chat/completions")
    suspend fun generateCompletion(
        @Header("Authorization") authHeader: String,
        @Header("HTTP-Referer") referer: String = "https://ai.studio.app",
        @Header("X-Title") title: String = "Forex Chart App",
        @Body request: OpenRouterRequest
    ): Response<OpenRouterResponse>
}

data class OpenRouterRequest(
    val model: String = "google/gemini-2.5-flash",
    val messages: List<OpenRouterMessage>
)

data class OpenRouterMessage(
    val role: String,
    val content: String
)

data class OpenRouterResponse(
    val id: String?,
    val choices: List<OpenRouterChoice>?
)

data class OpenRouterChoice(
    val message: OpenRouterMessage?
)
