package com.tinyggrok.app.data.api

import com.tinyggrok.app.data.model.InvoicePreviewResponse
import com.tinyggrok.app.data.model.ManagementKeyValidation
import com.tinyggrok.app.data.model.PrepaidBalanceResponse
import com.tinyggrok.app.data.model.SpendingLimitsResponse
import com.tinyggrok.app.data.model.TeamModelsResponse
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

/**
 * xAI Management API — separate from the inference API.
 * Base URL: https://management-api.x.ai/
 * Auth: Bearer management key (not the chat API key).
 *
 * Docs: https://docs.x.ai/developers/rest-api-reference/management/billing
 */
interface XaiManagementApiService {

    @GET("auth/management-keys/validation")
    suspend fun validateManagementKey(
        @Header("Authorization") auth: String
    ): ManagementKeyValidation

    @GET("v1/billing/teams/{teamId}/prepaid/balance")
    suspend fun prepaidBalance(
        @Header("Authorization") auth: String,
        @Path("teamId") teamId: String
    ): PrepaidBalanceResponse

    @GET("v1/billing/teams/{teamId}/postpaid/spending-limits")
    suspend fun spendingLimits(
        @Header("Authorization") auth: String,
        @Path("teamId") teamId: String
    ): SpendingLimitsResponse

    @GET("v1/billing/teams/{teamId}/postpaid/invoice/preview")
    suspend fun invoicePreview(
        @Header("Authorization") auth: String,
        @Path("teamId") teamId: String
    ): InvoicePreviewResponse

    @GET("auth/teams/{teamId}/models")
    suspend fun teamModels(
        @Header("Authorization") auth: String,
        @Path("teamId") teamId: String
    ): TeamModelsResponse
}
