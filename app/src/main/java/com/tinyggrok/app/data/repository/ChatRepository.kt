package com.tinyggrok.app.data.repository

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.tinyggrok.app.data.api.XaiApiService
import com.tinyggrok.app.data.model.InputContent
import com.tinyggrok.app.data.model.InputMessage
import com.tinyggrok.app.data.model.Message
import com.tinyggrok.app.data.model.ResponseTool
import com.tinyggrok.app.data.model.ResponsesRequest
import com.tinyggrok.app.data.model.ResponsesResponse
import com.tinyggrok.app.data.model.TextContent
import com.tinyggrok.app.data.model.Usage
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

data class ChatResult(
    val assistantMessage: String,
    val usage: Usage? = null,
    val model: String = "",
    val usedWebSearch: Boolean = false
)

@Singleton
class ChatRepository @Inject constructor(
    private val apiService: XaiApiService,
    private val debugLogRepository: DebugLogRepository
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val TAG = "ChatRepository"

    suspend fun sendMessage(
        apiKey: String,
        text: String,
        imageBase64: String?,
        history: List<Message>,
        debugMode: Boolean = false,
        responseFormat: String = "html"
    ): Result<ChatResult> {
        return try {
            val instructions = when (responseFormat) {
                "html" -> "Respond using valid HTML markup only. Use tags like <p>, <ul>, <ol>, <li>, <strong>, <em>, <code>, <pre>, <h1>-<h3>, <table>, <blockquote> where appropriate. Do not wrap in <html> or <body> tags. Do not use markdown. If you are unsure or the answer may rely on recent or factual information you don't reliably know, use the web_search tool to find accurate, up-to-date information instead of guessing."
                "markdown" -> "Respond using Markdown formatting. Use headers, bold, italic, code blocks, lists, tables where appropriate. If you are unsure or the answer may rely on recent or factual information you don't reliably know, use the web_search tool to find accurate, up-to-date information instead of guessing."
                else -> "If you are unsure or the answer may rely on recent or factual information you don't reliably know, use the web_search tool to find accurate, up-to-date information instead of guessing."
            }

            val input = buildList {
                // Prior turns as plain-text messages (Responses API accepts string content)
                history.forEach { msg ->
                    add(InputMessage(role = msg.role, content = flattenText(msg)))
                }
                // Current user turn: typed parts when an image is attached, else plain text
                if (imageBase64 != null) {
                    add(
                        InputMessage(
                            role = "user",
                            content = listOf(
                                InputContent(
                                    type = "input_image",
                                    imageUrl = "data:image/jpeg;base64,$imageBase64"
                                ),
                                InputContent(type = "input_text", text = text)
                            )
                        )
                    )
                } else {
                    add(InputMessage(role = "user", content = text))
                }
            }

            val request = ResponsesRequest(
                input = input,
                instructions = instructions,
                tools = listOf(ResponseTool(type = "web_search"))
            )

            if (debugMode) {
                val requestJson = gson.toJson(request)
                debugLogRepository.logOutgoing(
                    summary = "POST /v1/responses | model=${request.model} | turns=${input.size} | hasImage=${imageBase64 != null} | tools=web_search",
                    body = requestJson
                )
                Log.d(TAG, "REQUEST: $requestJson")
            }

            val response = apiService.responses(
                auth = "Bearer $apiKey",
                request = request
            )

            val baseMessage = extractText(response).ifBlank { "No response" }
            val citations = extractCitations(response)
            val assistantMessage = appendCitations(baseMessage, citations, responseFormat)
            val usedWebSearch = response.output?.any { it.type == "web_search_call" } == true
                    || citations.isNotEmpty()

            val usage = response.usage?.let {
                Usage(
                    prompt_tokens = it.inputTokens,
                    completion_tokens = it.outputTokens,
                    total_tokens = if (it.totalTokens > 0) it.totalTokens else it.inputTokens + it.outputTokens
                )
            }

            if (debugMode) {
                val responseJson = gson.toJson(response)
                debugLogRepository.logIncoming(
                    summary = "HTTP 200 | usage=${response.usage?.totalTokens ?: "N/A"} tokens | citations=${citations.size}",
                    body = responseJson
                )
                Log.d(TAG, "RESPONSE: $responseJson")
            }

            Result.success(ChatResult(assistantMessage, usage, model = request.model, usedWebSearch = usedWebSearch))
        } catch (e: HttpException) {
            val body = try { e.response()?.errorBody()?.string().orEmpty() } catch (_: Throwable) { "" }
            if (debugMode) {
                debugLogRepository.logIncoming(
                    summary = "HTTP ${e.code()}: ${e.message()}",
                    body = body.take(2000)
                )
                Log.e(TAG, "HTTP ERROR ${e.code()}: $body")
            }
            Result.failure(RuntimeException("HTTP ${e.code()}: ${body.take(400).ifBlank { e.message() }}"))
        } catch (e: SocketTimeoutException) {
            if (debugMode) {
                debugLogRepository.logIncoming(summary = "TIMEOUT", body = e.message ?: "Socket timeout")
            }
            Result.failure(RuntimeException("Timed out contacting api.x.ai. Check network."))
        } catch (e: UnknownHostException) {
            if (debugMode) {
                debugLogRepository.logIncoming(summary = "DNS ERROR", body = e.message ?: "Unknown host")
            }
            Result.failure(RuntimeException("Can't reach api.x.ai (DNS). Check network."))
        } catch (e: Exception) {
            if (debugMode) {
                debugLogRepository.logIncoming(summary = "EXCEPTION: ${e.javaClass.simpleName}", body = e.message ?: "unknown")
            }
            Result.failure(RuntimeException("${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"))
        }
    }

    /** Collapse a chat-completions [Message] (list of content parts) into a plain text string. */
    private fun flattenText(message: Message): String =
        message.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }

    /** Pull the assistant text out of a Responses API result. */
    private fun extractText(response: ResponsesResponse): String {
        response.outputText?.takeIf { it.isNotBlank() }?.let { return it }
        val parts = response.output
            ?.filter { it.type == null || it.type == "message" }
            ?.flatMap { it.content.orEmpty() }
            ?.mapNotNull { part ->
                if (part.type == null || part.type == "output_text") part.text else null
            }
            ?: emptyList()
        return parts.joinToString("\n").trim()
    }

    /**
     * Collect citation URLs whether the API returns them as a top-level array of
     * strings, an array of objects with a "url" field, or as inline annotations.
     */
    private fun extractCitations(response: ResponsesResponse): List<String> {
        val urls = LinkedHashSet<String>()
        response.citations?.forEach { el -> urlFrom(el)?.let { urls.add(it) } }
        response.output
            ?.flatMap { it.content.orEmpty() }
            ?.flatMap { it.annotations.orEmpty() }
            ?.forEach { el -> urlFrom(el)?.let { urls.add(it) } }
        return urls.toList()
    }

    private fun urlFrom(el: JsonElement): String? = try {
        when {
            el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.takeIf { it.isNotBlank() }
            el.isJsonObject -> {
                val obj = el.asJsonObject
                (obj.get("url") ?: obj.get("uri"))?.takeIf { it.isJsonPrimitive }?.asString
            }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    /** Append web-search source links so the user can see where the info came from. */
    private fun appendCitations(
        message: String,
        citations: List<String>,
        responseFormat: String
    ): String {
        val links = citations.filter { it.isNotBlank() }.distinct()
        val body = if (responseFormat != "markdown") sanitizeHtml(message) else message
        if (links.isEmpty()) return body
        return when (responseFormat) {
            "markdown" -> buildString {
                append(body)
                append("\n\n**Sources**\n")
                links.forEachIndexed { i, url -> append("${i + 1}. [$url]($url)\n") }
            }
            else -> buildString {
                append(body)
                append("<hr><p><strong>Sources</strong></p><ol>")
                links.forEach { url -> append("<li><a href=\"$url\">$url</a></li>") }
                append("</ol>")
            }
        }
    }

    /**
     * Grok sometimes returns hybrid content (HTML with embedded markdown-style
     * fragments) even when asked for pure HTML. This pass converts the most common
     * leakage patterns to their HTML equivalents so the WebView renders them correctly.
     *
     *  [[n]](url)  →  <sup><a href="url">[n]</a></sup>   (inline citation numbers)
     *  [text](url) →  <a href="url">text</a>              (plain markdown links)
     *  ### Heading  →  <h3>Heading</h3>   (and ## → h2, # → h1)
     *  **text**    →  <strong>text</strong>
     *  *text*      →  <em>text</em>
     */
    private fun sanitizeHtml(text: String): String {
        var out = text

        // Inline citation footnotes:  [[1]](https://...)  →  <sup><a href="...">[1]</a></sup>
        out = out.replace(Regex("""\[\[(\d+)\]\]\(([^)]+)\)""")) { m ->
            "<sup><a href=\"${m.groupValues[2]}\">[${m.groupValues[1]}]</a></sup>"
        }

        // Plain markdown links:  [label](url)  →  <a href="url">label</a>
        out = out.replace(Regex("""\[([^\]]+)\]\((https?://[^)]+)\)""")) { m ->
            "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
        }

        // ATX headings (only if they start a line)
        out = out.replace(Regex("""(?m)^### (.+)$""")) { m -> "<h3>${m.groupValues[1]}</h3>" }
        out = out.replace(Regex("""(?m)^## (.+)$"""))  { m -> "<h2>${m.groupValues[1]}</h2>" }
        out = out.replace(Regex("""(?m)^# (.+)$"""))   { m -> "<h1>${m.groupValues[1]}</h1>" }

        // Bold and italic
        out = out.replace(Regex("""\*\*(.+?)\*\*""")) { m -> "<strong>${m.groupValues[1]}</strong>" }
        out = out.replace(Regex("""\*(.+?)\*"""))      { m -> "<em>${m.groupValues[1]}</em>" }

        return out
    }
}
