package com.tinyggrok.app.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinyggrok.app.AppDefaults
import com.tinyggrok.app.data.local.LocationRepository
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.model.Message
import com.tinyggrok.app.data.model.TextContent
import com.tinyggrok.app.data.repository.ChatRepository
import com.tinyggrok.app.data.repository.DebugLogRepository
import com.tinyggrok.app.data.repository.ResponseHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** Max dimension for resized image before base64 encoding */
private const val MAX_IMAGE_DIMENSION = 1024

/** JPEG quality for base64 encoding */
private const val JPEG_QUALITY = 85

/** Per-1M-token prices (input, output) for known chat models. */
private fun costRatesPerMillion(model: String): Pair<Double, Double> = when (model) {
    AppDefaults.MODEL_GROK_4_5 -> 2.00 to 6.00
    else -> 1.25 to 2.50 // grok-4.3 default
}

data class ChatUiMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val costInfo: CostInfo? = null,
    val hasImage: Boolean = false,
    val model: String? = null,
    val usedWebSearch: Boolean = false,
    val citations: List<String> = emptyList()
)

data class CostInfo(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val estimatedCostUsd: Double
) {
    fun formatted(): String =
        "%,d in / %,d out tokens  ~ $%.6f"
            .format(promptTokens, completionTokens, estimatedCostUsd)
}

data class ChatUiState(
    val messages: List<ChatUiMessage> = emptyList(),
    val prompt: String = "",
    val attachedImageUri: Uri? = null,
    val attachedImageBase64: String? = null,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val showCost: Boolean = false,
    val debugMode: Boolean = false,
    val responseFormat: String = "html",
    val fontSize: Float = 14f,
    val chatModel: String = AppDefaults.DEFAULT_MODEL,
    val lastSentPrompt: String = "",
    /** When true, chat attaches approximate GPS (if OS permission granted). */
    val locationEnabled: Boolean = true
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val locationRepository: LocationRepository,
    private val debugLogRepository: DebugLogRepository,
    private val responseHistoryRepository: ResponseHistoryRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    init {
        viewModelScope.launch {
            settingsRepository.showCost.collect { show ->
                _uiState.value = _uiState.value.copy(showCost = show)
            }
        }
        viewModelScope.launch {
            settingsRepository.debugMode.collect { debug ->
                _uiState.value = _uiState.value.copy(debugMode = debug)
            }
        }
        viewModelScope.launch {
            settingsRepository.responseFormat.collect { format ->
                _uiState.value = _uiState.value.copy(responseFormat = format)
            }
        }
        viewModelScope.launch {
            settingsRepository.fontSize.collect { size ->
                _uiState.value = _uiState.value.copy(fontSize = size)
            }
        }
        viewModelScope.launch {
            settingsRepository.chatModel.collect { model ->
                _uiState.value = _uiState.value.copy(chatModel = model)
            }
        }
        viewModelScope.launch {
            settingsRepository.locationEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(locationEnabled = enabled)
                // MainActivity already warms GPS on app start; re-trigger here when the
                // user toggles the setting (or grants permission) without leaving chat.
                if (enabled) {
                    locationRepository.startGpsWarmup()
                } else {
                    locationRepository.stopGpsWarmup()
                }
            }
        }
    }

    fun updatePrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(prompt = prompt, errorMessage = null)
    }

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            try {
                val base64 = uriToBase64(uri)
                _uiState.value = _uiState.value.copy(
                    attachedImageUri = uri,
                    attachedImageBase64 = base64,
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to load image: ${e.message}"
                )
            }
        }
    }

    fun removeImage() {
        _uiState.value = _uiState.value.copy(
            attachedImageUri = null,
            attachedImageBase64 = null
        )
    }

    fun sendPrompt() {
        val prompt = _uiState.value.prompt.trim()
        val imageBase64 = _uiState.value.attachedImageBase64

        if ((prompt.isEmpty() && imageBase64 == null) || _uiState.value.isSending) {
            return
        }

        viewModelScope.launch {
            val apiKey = settingsRepository.apiKey.first().orEmpty()
            if (apiKey.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Add your xAI API key in Settings first."
                )
                return@launch
            }

            val debugMode = settingsRepository.debugMode.first()
            val chatModel = settingsRepository.chatModel.first()
            val previousMessages = _uiState.value.messages
            val displayText = prompt.ifEmpty { "[Image]" }
            val optimisticMessages = previousMessages + ChatUiMessage(
                role = "user",
                content = displayText,
                hasImage = imageBase64 != null
            )

            _uiState.value = _uiState.value.copy(
                messages = optimisticMessages,
                prompt = "",
                attachedImageUri = null,
                attachedImageBase64 = null,
                isSending = true,
                errorMessage = null,
                lastSentPrompt = prompt
            )

            // Keep only the last 10 assistant responses (and their paired user messages)
            val history = previousMessages
                .let { msgs ->
                    var assistantCount = 0
                    msgs.reversed()
                        .takeWhile { msg ->
                            if (msg.role == "assistant") assistantCount++
                            assistantCount <= 10
                        }
                        .reversed()
                }
                .map { msg -> Message(role = msg.role, text = msg.content) }

            val locationContext = if (settingsRepository.locationEnabled.first()) {
                runCatching {
                    locationRepository.getApproximateLocation()?.toInstructionSnippet()
                }.getOrNull()
            } else {
                null
            }

            val result = chatRepository.sendMessage(
                apiKey = apiKey,
                text = prompt,
                imageBase64 = imageBase64,
                history = history,
                debugMode = debugMode,
                responseFormat = _uiState.value.responseFormat,
                model = chatModel,
                locationContext = locationContext
            )
            _uiState.value = result.fold(
                onSuccess = { response ->
                    val costInfo = response.usage?.let { usage ->
                        val (inPerM, outPerM) = costRatesPerMillion(chatModel)
                        val cost = usage.prompt_tokens * (inPerM / 1_000_000.0) +
                                usage.completion_tokens * (outPerM / 1_000_000.0)
                        CostInfo(
                            promptTokens = usage.prompt_tokens,
                            completionTokens = usage.completion_tokens,
                            totalTokens = usage.total_tokens,
                            estimatedCostUsd = cost
                        )
                    }
                    responseHistoryRepository.add(
                        prompt = displayText,
                        response = response.assistantMessage
                    )
                    _uiState.value.copy(
                        messages = optimisticMessages + ChatUiMessage(
                            role = "assistant",
                            content = response.assistantMessage,
                            costInfo = costInfo,
                            model = response.model.ifBlank { null },
                            usedWebSearch = response.usedWebSearch,
                            citations = response.citations
                        ),
                        isSending = false
                    )
                },
                onFailure = { error ->
                    _uiState.value.copy(
                        isSending = false,
                        errorMessage = error.message ?: "Unable to send prompt."
                    )
                }
            )
        }
    }

    fun clearDebugLogs() {
        debugLogRepository.clear()
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            messages = emptyList(),
            errorMessage = null
        )
    }

    fun clearPrompt() {
        _uiState.value = _uiState.value.copy(
            prompt = "",
            attachedImageUri = null,
            attachedImageBase64 = null,
            errorMessage = null
        )
    }

    fun resendLastPrompt() {
        val lastPrompt = _uiState.value.lastSentPrompt
        if (lastPrompt.isNotBlank() && !_uiState.value.isSending) {
            _uiState.value = _uiState.value.copy(prompt = lastPrompt)
            sendPrompt()
        }
    }

    private fun uriToBase64(uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open image URI")

        return inputStream.use { stream ->
            val originalBitmap = BitmapFactory.decodeStream(stream)
                ?: throw IllegalArgumentException("Cannot decode image")

            val resizedBitmap = resizeBitmap(originalBitmap)
            if (resizedBitmap != originalBitmap) {
                originalBitmap.recycle()
            }

            val outputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            resizedBitmap.recycle()

            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun resizeBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION) {
            return bitmap
        }

        val ratio = width.toFloat() / height.toFloat()
        val (newWidth, newHeight) = if (width > height) {
            MAX_IMAGE_DIMENSION to (MAX_IMAGE_DIMENSION / ratio).toInt()
        } else {
            (MAX_IMAGE_DIMENSION * ratio).toInt() to MAX_IMAGE_DIMENSION
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
