package com.tinyggrok.app.data.model

import com.google.gson.annotations.SerializedName

/** Response from GET /v1/models (OpenAI-compatible). */
data class ModelsListResponse(
    val data: List<ModelInfo>? = null,
    val objectType: String? = null
)

data class ModelInfo(
    val id: String? = null,
    @SerializedName("owned_by") val ownedBy: String? = null
)
