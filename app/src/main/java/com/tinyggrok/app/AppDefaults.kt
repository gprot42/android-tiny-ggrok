package com.tinyggrok.app

/** App-wide defaults surfaced in multiple places (requests + About screen). */
object AppDefaults {
    /** Default chat model (Grok 4.5). */
    const val DEFAULT_MODEL = "grok-4.5"

    const val MODEL_GROK_4_3 = "grok-4.3"
    const val MODEL_GROK_4_5 = "grok-4.5"

    /** Models the user can pick in Settings. */
    val CHAT_MODELS: List<Pair<String, String>> = listOf(
        "Grok 4.5" to MODEL_GROK_4_5,
        "Grok 4.3" to MODEL_GROK_4_3
    )

    fun isKnownChatModel(id: String): Boolean =
        CHAT_MODELS.any { it.second == id }

    fun normalizeChatModel(id: String?): String =
        if (id != null && isKnownChatModel(id)) id else DEFAULT_MODEL

    const val FRAMEWORK = "Jetpack Compose + Material3, Hilt, Retrofit/OkHttp, DataStore"
}
