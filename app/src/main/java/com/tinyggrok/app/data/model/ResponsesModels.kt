package com.tinyggrok.app.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.tinyggrok.app.AppDefaults

/**
 * xAI Agent Tools / Responses API request (POST /v1/responses).
 *
 * This replaces the deprecated chat-completions Live Search. Server-side tools
 * such as [ResponseTool] type "web_search" are executed automatically by xAI:
 * the model decides on its own when to search the web for up-to-date information
 * and returns citations for whatever it finds.
 */
data class ResponsesRequest(
    val model: String = AppDefaults.DEFAULT_MODEL,
    val input: List<InputMessage>,
    val tools: List<ResponseTool>? = null,
    val instructions: String? = null,
    @SerializedName("max_output_tokens")
    val maxOutputTokens: Int = 4096,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
    val store: Boolean = false,
    /**
     * How long the model thinks before answering. Grok defaults to "high", which is
     * the single largest slice of wall-clock time on an ordinary question. Null omits
     * the field so the server default applies.
     */
    val reasoning: ReasoningConfig? = null,
    /**
     * Ceiling on server-side agentic tool turns (each search or page fetch is a turn).
     * Bounds the runaway case where the model keeps searching; null means no limit.
     */
    @SerializedName("max_turns")
    val maxTurns: Int? = null
)

/** Reasoning effort for [ResponsesRequest]. See [AppDefaults.EFFORT_LOW] and friends. */
data class ReasoningConfig(
    val effort: String
)

/**
 * Built-in tool config for the Responses API.
 * For [type] = `"web_search"`, optional [filters] can restrict domains (max 5).
 */
data class ResponseTool(
    val type: String,
    val filters: WebSearchFilters? = null
)

data class WebSearchFilters(
    @SerializedName("allowed_domains")
    val allowedDomains: List<String>? = null,
    @SerializedName("excluded_domains")
    val excludedDomains: List<String>? = null
)

/**
 * A single input turn. [content] is either a plain [String] (text-only / history)
 * or a [List] of [InputContent] parts (used when one or more images are attached).
 * Gson serializes based on the runtime type, so both shapes are emitted correctly.
 */
data class InputMessage(
    val role: String,
    val content: Any
)

data class InputContent(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url")
    val imageUrl: String? = null
)

// ---- Response ----

data class ResponsesResponse(
    val id: String? = null,
    val output: List<OutputItem>? = null,
    @SerializedName("output_text")
    val outputText: String? = null,
    val usage: ResponsesUsage? = null,
    val citations: List<JsonElement>? = null
)

data class OutputItem(
    val type: String? = null,
    val role: String? = null,
    val content: List<OutputContent>? = null
)

data class OutputContent(
    val type: String? = null,
    val text: String? = null,
    val annotations: List<JsonElement>? = null
)

data class ResponsesUsage(
    @SerializedName("input_tokens")
    val inputTokens: Int = 0,
    @SerializedName("output_tokens")
    val outputTokens: Int = 0,
    @SerializedName("total_tokens")
    val totalTokens: Int = 0
)
