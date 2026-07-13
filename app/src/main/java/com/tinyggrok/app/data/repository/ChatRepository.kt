package com.tinyggrok.app.data.repository

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.tinyggrok.app.AppDefaults
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
    val usedWebSearch: Boolean = false,
    val citations: List<String> = emptyList()
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
        responseFormat: String = "html",
        model: String = AppDefaults.DEFAULT_MODEL,
        /** Optional approximate location snippet already formatted for instructions. */
        locationContext: String? = null
    ): Result<ChatResult> {
        return try {
            val transitEnquiry = looksLikeTransitEnquiry(text)
            val formatInstructions = when (responseFormat) {
                "html" -> "Respond using valid HTML markup only. Use tags like <p>, <ul>, <ol>, <li>, <strong>, <em>, <code>, <pre>, <h1>-<h3>, <table>, <blockquote> where appropriate. Do not wrap in <html> or <body> tags. Do not use markdown."
                "markdown" -> "Respond using Markdown formatting. Use headers, bold, italic, code blocks, lists, tables where appropriate."
                else -> ""
            }
            val webInstructions = buildString {
                append(
                    "If you are unsure or the answer may rely on recent or factual information you don't " +
                        "reliably know, use the web_search tool to find accurate, up-to-date information " +
                        "instead of guessing. Prefer official primary sources when available."
                )
                append(" ")
                // Hard requirements first — soft "prefer" lists alone are ignored by the model.
                append(
                    "UK RAIL / LIVE TIMES (mandatory when the user asks about trains, departures, arrivals, " +
                        "journeys, delays, disruptions, platforms, or transport from A/here to B):\n" +
                        "1. You MUST call web_search before giving any clock times, schedules, or disruption claims. " +
                        "Never invent live departures from memory.\n" +
                        "2. Your FIRST search queries MUST target National Rail live data explicitly, using " +
                        "site:www.nationalrail.co.uk and/or site:realtime.nationalrail.co.uk (and plain " +
                        "nationalrail.co.uk / realtime.nationalrail.co.uk). Example queries: " +
                        "\"site:nationalrail.co.uk live departures <station>\", " +
                        "\"site:nationalrail.co.uk journey planner <from> to <to>\", " +
                        "\"site:realtime.nationalrail.co.uk <station>\".\n" +
                        "3. Open/browse National Rail journey planner or live departure board results when " +
                        "search returns them; quote times from those pages.\n" +
                        "4. Then search operator sites if needed (Thameslink, TfL, London Northwestern, etc.).\n" +
                        "5. Always include clickable source URLs (especially nationalrail.co.uk) in the answer. " +
                        "If web_search cannot retrieve live boards, say so clearly and still give the " +
                        "National Rail links the user can open.\n"
                )
                append(
                    "Preferred official sources — hubs: https://www.nationalrail.co.uk , " +
                        "https://realtime.nationalrail.co.uk , https://www.networkrail.co.uk . " +
                        "St Albans / nearby: Thameslink https://www.thameslinkrailway.com " +
                        "(St Albans City); London Northwestern " +
                        "https://www.londonnorthwesternrailway.co.uk (St Albans Abbey / Abbey Line); " +
                        "Great Northern https://www.greatnorthernrail.com ; " +
                        "East Midlands Railway https://www.eastmidlandsrailway.co.uk ; " +
                        "Intalink https://www.intalink.org.uk . " +
                        "London: https://tfl.gov.uk , https://citymapper.com . " +
                        "Other TOCs: greateranglia.co.uk, southeasternrailway.co.uk, southernrailway.com, " +
                        "gatwickexpress.com, southwesternrailway.com, c2c-online.co.uk, " +
                        "chilternrailways.co.uk, gwr.com, avantiwestcoast.co.uk, lner.co.uk, " +
                        "crosscountrytrains.co.uk, tpexpress.co.uk, northernrailway.co.uk, " +
                        "westmidlandsrailway.co.uk, scotrail.co.uk, tfw.wales, hulltrains.co.uk, " +
                        "grandcentralrail.com, lumo.co.uk, sleeper.scot. " +
                        "For non-UK transit use official operators; for other topics use open web_search."
                )
                if (transitEnquiry) {
                    append(" ")
                    append(
                        "THIS USER MESSAGE IS A TRANSIT/RAIL ENQUIRY. " +
                            "Before answering, you MUST web_search National Rail " +
                            "(www.nationalrail.co.uk and realtime.nationalrail.co.uk) for live " +
                            "departures and/or journey options that match the enquiry. " +
                            "Do not answer with only memorised timetable knowledge."
                    )
                }
                if (!locationContext.isNullOrBlank()) {
                    append(" ")
                    append(locationContext)
                    append(
                        " LOCATION RULES: The numeric GPS coordinates are the source of truth. " +
                            "When the user says \"here\", \"current location\", or \"near me\", " +
                            "resolve nearest stations/stops/places from those coordinates " +
                            "(and UK postcode if given), then search National Rail / TfL from those origins. " +
                            "Do NOT treat a reverse-geocoded town or district name as definitive if it " +
                            "conflicts with the coordinates — UK geocoders often name a neighbouring " +
                            "town or district HQ (e.g. wrong Essex district). " +
                            "If accuracy is marked coarse, only use coordinates, never invent a town."
                    )
                }
            }
            val instructions = listOf(formatInstructions, webInstructions)
                .filter { it.isNotBlank() }
                .joinToString(" ")

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

            // Keep web_search open (no allowed_domains lock): transit still needs general
            // search for "nearest station" etc. National Rail is enforced via instructions.
            val request = ResponsesRequest(
                model = model,
                input = input,
                instructions = instructions,
                tools = listOf(ResponseTool(type = "web_search")),
                // Slightly lower temperature for timetable / factual transit answers
                temperature = if (transitEnquiry) 0.3 else 0.7
            )

            if (debugMode) {
                val requestJson = gson.toJson(request)
                debugLogRepository.logOutgoing(
                    summary = "POST /v1/responses | model=${request.model} | turns=${input.size} | " +
                        "hasImage=${imageBase64 != null} | tools=web_search | transit=$transitEnquiry",
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
            val assistantMessage = if (responseFormat != "markdown") sanitizeHtml(baseMessage) else baseMessage
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

            Result.success(ChatResult(assistantMessage, usage, model = request.model, usedWebSearch = usedWebSearch, citations = citations))
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

    /**
     * Heuristic: does this look like a UK rail / live-travel enquiry where we must
     * force National Rail web_search behaviour via stronger instructions?
     */
    private fun looksLikeTransitEnquiry(text: String): Boolean {
        val t = text.lowercase()
        if (t.isBlank()) return false
        val keywords = listOf(
            "train", "trains", "rail", "railway", "departure", "departures", "arrival",
            "arrivals", "timetable", "platform", "platforms", "delay", "delays",
            "disruption", "cancelled", "canceled", "national rail", "thameslink",
            "tube", "underground", "overground", "elizabeth line", "dlr",
            "bus to", "buses", "journey", "how do i get", "how to get",
            "from here", "current location", "near me", "nearest station",
            "st albans", "st. albans", "st pancras", "kings cross", "king's cross",
            "euston", "paddington", "liverpool street", "waterloo", "victoria",
            "greater anglia", "tfl", "live times", "next train", "next trains"
        )
        if (keywords.any { it in t }) return true
        // "to X" / "from A to B" travel phrasing
        if (Regex("""\bfrom\b.+\bto\b""").containsMatchIn(t)) return true
        if (Regex("""\b(get|go|travel|transport)\b.+\bto\b""").containsMatchIn(t)) return true
        return false
    }

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
