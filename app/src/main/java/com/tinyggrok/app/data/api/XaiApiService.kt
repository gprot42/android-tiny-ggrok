package com.tinyggrok.app.data.api

import com.tinyggrok.app.data.model.ChatRequest
import com.tinyggrok.app.data.model.ChatResponse
import com.tinyggrok.app.data.model.ModelsListResponse
import com.tinyggrok.app.data.model.ResponsesRequest
import com.tinyggrok.app.data.model.ResponsesResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface XaiApiService {
    @POST("v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): ChatResponse

    @POST("v1/responses")
    suspend fun responses(
        @Header("Authorization") auth: String,
        @Body request: ResponsesRequest
    ): ResponsesResponse

    /** Lightweight auth check — lists models the API key can access. */
    @GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") auth: String
    ): ModelsListResponse
}
