package com.tinyggrok.app.data.scan

import android.util.Log
import com.tinyggrok.app.AppDefaults
import com.tinyggrok.app.data.api.XaiApiService
import com.tinyggrok.app.data.model.InputContent
import com.tinyggrok.app.data.model.InputMessage
import com.tinyggrok.app.data.model.ReasoningConfig
import com.tinyggrok.app.data.model.ResponsesRequest
import com.tinyggrok.app.data.model.ResponsesResponse
import com.tinyggrok.app.data.repository.DebugLogRepository
import com.tinyggrok.app.data.repository.isUnsupportedParameterFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** Long enough for a vision call on mobile data, short enough not to strand the user. */
private const val DETECTION_TIMEOUT_MS = 25_000L

/**
 * Asks Grok's vision where the page is. No Play services and no native vision library:
 * the model does the part that classic edge detection is bad at (telling a page from a
 * cluttered desk), and [refineCorners] then does the part the model is bad at (placing
 * the corners to the pixel).
 */
@Singleton
class DocumentCornerDetector @Inject constructor(
    private val apiService: XaiApiService,
    private val debugLogRepository: DebugLogRepository
) {
    private val tag = "DocCornerDetector"

    /** Rough corners, or null when there is no answer worth trusting. Never throws. */
    suspend fun detect(
        apiKey: String,
        jpegBase64: String,
        model: String,
        debugMode: Boolean = false
    ): DocumentCorners? {
        val request = ResponsesRequest(
            model = AppDefaults.normalizeChatModel(model),
            instructions = INSTRUCTIONS,
            input = listOf(
                InputMessage(
                    role = "user",
                    content = listOf(
                        InputContent(
                            type = "input_image",
                            imageUrl = "data:image/jpeg;base64,$jpegBase64"
                        ),
                        InputContent(type = "input_text", text = "Return the document corners as JSON.")
                    )
                )
            ),
            tools = null,
            maxOutputTokens = 2048,
            temperature = 0.0,
            stream = false,
            // Locating four corners needs looking, not deliberating.
            reasoning = ReasoningConfig(effort = AppDefaults.EFFORT_LOW)
        )

        return try {
            val reply = withTimeoutOrNull(DETECTION_TIMEOUT_MS) {
                val response = try {
                    apiService.responses(auth = "Bearer $apiKey", request = request)
                } catch (e: HttpException) {
                    val body = runCatching { e.response()?.errorBody()?.string().orEmpty() }
                        .getOrDefault("")
                    if (isUnsupportedParameterFailure(e.code(), body)) {
                        apiService.responses(
                            auth = "Bearer $apiKey",
                            request = request.copy(reasoning = null)
                        )
                    } else {
                        throw e
                    }
                }
                extractText(response)
            }
            if (debugMode) {
                debugLogRepository.logIncoming(
                    summary = "SCAN corners | model=${request.model}",
                    body = reply ?: "timed out after ${DETECTION_TIMEOUT_MS}ms"
                )
            }
            reply?.let(::parseDocumentCorners)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "Corner detection failed: ${e.javaClass.simpleName}: ${e.message}")
            if (debugMode) {
                debugLogRepository.logIncoming(
                    summary = "SCAN corners FAILED",
                    body = "${e.javaClass.simpleName}: ${e.message}"
                )
            }
            null
        }
    }

    private fun extractText(response: ResponsesResponse): String {
        response.outputText?.takeIf { it.isNotBlank() }?.let { return it }
        return response.output
            ?.filter { it.type == null || it.type == "message" }
            ?.flatMap { it.content.orEmpty() }
            ?.mapNotNull { it.text }
            ?.joinToString("\n")
            .orEmpty()
    }

    private companion object {
        val INSTRUCTIONS = """
            You locate documents in photos. The image shows a photographed sheet of paper,
            receipt, card, letter, form or book page, usually on a desk or table.

            Reply with JSON only, no prose and no code fence:
            {"found":true,"tl":[x,y],"tr":[x,y],"br":[x,y],"bl":[x,y]}

            Coordinates are integers on a 0-1000 grid: x=0 is the left edge of the image,
            x=1000 the right edge, y=0 the top edge, y=1000 the bottom edge.
            tl, tr, br, bl are the four corners of the document's outer paper boundary as
            they appear in the image, clockwise from the top-left. Mark the physical corners
            of the sheet itself, not the text block, not a table printed on it, and not the
            desk. If a corner is hidden or out of frame, extend the visible edges to where
            they would meet and clamp to the 0-1000 range.
            If there is no document in the image, reply {"found":false}.
        """.trimIndent()
    }
}
