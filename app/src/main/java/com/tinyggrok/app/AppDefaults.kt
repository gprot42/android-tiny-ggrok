package com.tinyggrok.app

/** App-wide defaults surfaced in multiple places (requests + About screen). */
object AppDefaults {
    const val MODEL_GROK_4_7 = "grok-4.7"
    const val MODEL_GROK_4_6 = "grok-4.6"
    const val MODEL_GROK_4_5 = "grok-4.5"

    /**
     * There is no id here for "Grok 4.7 Fast". It is the same model on faster hardware at
     * twice the token rates, and xAI serves it only through Cursor and Grok Build, not on
     * the public API this app talks to. Offering it would mean sending a model id the API
     * rejects, which costs a round trip and silently lands the user on the backup model.
     */

    /** Default chat model (Grok 4.7). */
    const val DEFAULT_MODEL = MODEL_GROK_4_7

    /** Used when the chosen model is rejected as unknown/unavailable by the API. */
    const val BACKUP_MODEL = MODEL_GROK_4_6

    /** Models the user can pick in Settings. */
    val CHAT_MODELS: List<Pair<String, String>> = listOf(
        "4.7" to MODEL_GROK_4_7,
        "4.6" to MODEL_GROK_4_6,
        "4.5" to MODEL_GROK_4_5
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
