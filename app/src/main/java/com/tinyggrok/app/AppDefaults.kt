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

    const val FRAMEWORK = "Jetpack Compose + Material3, Hilt, Retrofit/OkHttp, DataStore"
}
