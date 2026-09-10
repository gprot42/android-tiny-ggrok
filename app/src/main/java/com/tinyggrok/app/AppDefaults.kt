package com.tinyggrok.app

/** App-wide defaults surfaced in multiple places (requests + About screen). */
object AppDefaults {
    const val MODEL_GROK_4_6 = "grok-4.6"
    const val MODEL_GROK_4_5 = "grok-4.5"

    /** Default chat model (Grok 4.6). */
    const val DEFAULT_MODEL = MODEL_GROK_4_6

    /** Used when 4.6 is rejected as unknown/unavailable by the API. */
    const val BACKUP_MODEL = MODEL_GROK_4_5

    /** Models the user can pick in Settings — only 4.6 and 4.5. */
    val CHAT_MODELS: List<Pair<String, String>> = listOf(
        "Grok 4.6" to MODEL_GROK_4_6,
        "Grok 4.5" to MODEL_GROK_4_5
    )

    fun isKnownChatModel(id: String): Boolean =
        CHAT_MODELS.any { it.second == id }

    fun normalizeChatModel(id: String?): String =
        if (id != null && isKnownChatModel(id)) id else DEFAULT_MODEL

    /**
     * Reasoning effort sent with each chat request. The API default is "high", which
     * costs tens of seconds of thinking before the first token even on simple asks.
     * Ordinary prompts use [EFFORT_LOW]; rail/transit prompts keep [EFFORT_HIGH]
     * because they combine several searches with timetable arithmetic.
     */
    const val EFFORT_LOW = "low"
    const val EFFORT_HIGH = "high"

    /**
     * Ceiling on agentic tool turns per request (a search or a page fetch is a turn).
     * Generous enough not to truncate normal research, low enough to stop a runaway.
     */
    const val MAX_TURNS_DEFAULT = 6
    const val MAX_TURNS_TRANSIT = 12

    const val FRAMEWORK = "Jetpack Compose + Material3, Hilt, Retrofit/OkHttp, DataStore"
}
