package com.tinyggrok.app.data.local

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One message of a saved conversation. Plain types only, so it survives as JSON. */
data class StoredMessage(
    val id: String,
    val role: String,
    val content: String,
    val imageUris: List<String> = emptyList(),
    val model: String? = null,
    val usedWebSearch: Boolean = false,
    val citations: List<String> = emptyList(),
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val estimatedCostUsd: Double? = null
)

/** A conversation as last seen on screen, with whatever was half-typed under it. */
data class StoredTranscript(
    val messages: List<StoredMessage> = emptyList(),
    val draft: String = ""
) {
    val isEmpty: Boolean get() = messages.isEmpty() && draft.isBlank()
}

/**
 * Keeps the conversation on disk so that losing the process does not lose the answers.
 *
 * Reported as "app sometimes quits and I lose the prompt output". Chat lived only in
 * memory, so anything that ended the process — a crash, or Android reclaiming a
 * backgrounded app, which it does readily to one holding several WebViews and a few
 * megabytes of photos — took the conversation with it. A long answer with web sources
 * can be minutes of waiting and real money; it should not depend on the process
 * surviving. Only the last ten answers were recoverable, and only through the History
 * screen, which is not where anyone looks after the app disappears.
 *
 * Written to the app's private storage, which the backup rules exclude from cloud
 * backup and device transfer, so conversations stay on this phone.
 */
@Singleton
class ChatTranscriptStore @Inject constructor(
    @ApplicationContext context: android.content.Context
) {
    private val gson = Gson()
    private val file = File(context.filesDir, FILE_NAME)
    private val type = object : TypeToken<StoredTranscript>() {}.type

    suspend fun load(): StoredTranscript = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext StoredTranscript()
            gson.fromJson<StoredTranscript>(file.readText(), type) ?: StoredTranscript()
        } catch (e: Throwable) {
            // A half-written or outdated file must never stop the app from opening.
            Log.w(TAG, "Could not read the saved conversation: ${e.javaClass.simpleName}")
            StoredTranscript()
        }
    }

    suspend fun save(messages: List<StoredMessage>, draft: String) = withContext(Dispatchers.IO) {
        val transcript = StoredTranscript(trimForStorage(messages), draft.take(MAX_DRAFT_CHARS))
        if (transcript.isEmpty) {
            clearNow()
            return@withContext
        }
        try {
            // Write beside the file and swap, so a process that dies mid-write leaves
            // the previous conversation intact rather than an unreadable fragment.
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(gson.toJson(transcript))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not save the conversation: ${e.javaClass.simpleName}")
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { clearNow() }

    private fun clearNow() {
        runCatching { if (file.exists()) file.delete() }
    }

    private companion object {
        const val TAG = "ChatTranscriptStore"
        const val FILE_NAME = "chat_transcript.json"
        const val MAX_DRAFT_CHARS = 20_000
    }
}

/**
 * The tail of a conversation that is worth keeping: recent enough to be the one the
 * user was having, small enough that saving it costs nothing noticeable. Older messages
 * are dropped from the front, as the send path already trims them from the prompt.
 */
internal fun trimForStorage(
    messages: List<StoredMessage>,
    maxMessages: Int = MAX_STORED_MESSAGES,
    maxChars: Int = MAX_STORED_CHARS
): List<StoredMessage> {
    var kept = if (messages.size > maxMessages) messages.takeLast(maxMessages) else messages
    while (kept.size > 1 && kept.sumOf { it.content.length } > maxChars) {
        kept = kept.drop(1)
    }
    // A single answer longer than the whole budget is still worth keeping whole: it is
    // the most expensive thing on the screen and the most annoying thing to lose.
    return kept
}

internal const val MAX_STORED_MESSAGES = 60
internal const val MAX_STORED_CHARS = 400_000
