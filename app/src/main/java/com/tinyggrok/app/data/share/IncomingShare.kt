package com.tinyggrok.app.data.share

/**
 * Content another app handed us via [android.content.Intent.ACTION_SEND],
 * [android.content.Intent.ACTION_SEND_MULTIPLE], or
 * [android.content.Intent.ACTION_PROCESS_TEXT].
 *
 * [imageUris] are stringified [android.net.Uri]s so the parser stays unit-testable
 * without Robolectric.
 */
data class IncomingShare(
    val text: String? = null,
    val imageUris: List<String> = emptyList()
) {
    val isEmpty: Boolean
        get() = text.isNullOrBlank() && imageUris.isEmpty()
}

object IncomingShareParser {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"

    fun parse(
        action: String?,
        mimeType: String?,
        extraText: String?,
        extraSubject: String?,
        extraProcessText: String?,
        extraStreamUris: List<String> = emptyList(),
        clipDataUris: List<String> = emptyList()
    ): IncomingShare? {
        if (action != ACTION_SEND &&
            action != ACTION_SEND_MULTIPLE &&
            action != ACTION_PROCESS_TEXT
        ) {
            return null
        }

        val text = combineText(
            subject = extraSubject,
            text = extraText,
            processText = extraProcessText
        )
        val imageUris = if (action == ACTION_PROCESS_TEXT) {
            emptyList()
        } else {
            collectImageUris(mimeType, extraStreamUris, clipDataUris)
        }

        return IncomingShare(text = text, imageUris = imageUris).takeUnless { it.isEmpty }
    }

    internal fun combineText(subject: String?, text: String?, processText: String?): String? {
        val processed = processText?.trim().orEmpty()
        val body = text?.trim().orEmpty()
        val sub = subject?.trim().orEmpty()

        val primary = when {
            processed.isNotEmpty() && body.isNotEmpty() && processed != body ->
                listOf(body, processed).distinct().joinToString("\n\n")
            processed.isNotEmpty() -> processed
            body.isNotEmpty() -> body
            else -> ""
        }

        val withSubject = when {
            sub.isEmpty() -> primary
            primary.isEmpty() -> sub
            primary.contains(sub) -> primary
            else -> "$sub\n\n$primary"
        }

        return withSubject.takeIf { it.isNotBlank() }
    }

    internal fun collectImageUris(
        mimeType: String?,
        extraStreamUris: List<String>,
        clipDataUris: List<String>
    ): List<String> {
        val seen = LinkedHashSet<String>()
        for (uri in extraStreamUris + clipDataUris) {
            val trimmed = uri.trim()
            if (trimmed.isEmpty()) continue
            if (shouldIncludeUri(mimeType, trimmed)) {
                seen.add(trimmed)
            }
        }
        return seen.toList()
    }

    internal fun shouldIncludeImages(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank() || mimeType == "*/*") return true
        return mimeType.startsWith("image/")
    }

    private fun shouldIncludeUri(mimeType: String?, uri: String): Boolean {
        if (shouldIncludeImages(mimeType)) return true
        return looksLikeImageUri(uri)
    }

    internal fun looksLikeImageUri(uri: String): Boolean {
        val path = uri.substringBefore('?').substringBefore('#').lowercase()
        return IMAGE_EXTENSIONS.any { path.endsWith(it) }
    }

    private val IMAGE_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".bmp", ".avif"
    )
}

/** Format a chat transcript for ACTION_SEND. [content] should already be plain text. */
fun formatConversationForShare(messages: List<Pair<String, String>>): String =
    messages.joinToString("\n\n") { (role, content) ->
        val who = if (role == "assistant") "Grok" else "You"
        "$who:\n$content"
    }
