package com.tinyggrok.app.ui.viewmodel

import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinyggrok.app.AppDefaults
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.model.PersonalityMode
import com.tinyggrok.app.data.model.VoiceOption
import com.tinyggrok.app.data.repository.ApiKeyCheckResult
import com.tinyggrok.app.data.repository.AuthMode
import com.tinyggrok.app.data.repository.ChatRepository
import com.tinyggrok.app.data.repository.ResolvedAuth
import com.tinyggrok.app.data.repository.SuperGrokAuthRepository
import com.tinyggrok.app.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** UI status for the Settings "Check key" action. */
sealed class ApiKeyCheckUi {
    data object Idle : ApiKeyCheckUi()
    data object Checking : ApiKeyCheckUi()
    data class Success(val message: String) : ApiKeyCheckUi()
    data class Failure(val message: String) : ApiKeyCheckUi()
}

data class SettingsUiState(
    val apiKey: String = "",
    /** Chat credential mode: API key (default) or SuperGrok OAuth (experimental). */
    val authMode: AuthMode = AuthMode.API_KEY,
    val oauthSignedIn: Boolean = false,
    val oauthEmail: String? = null,
    /** Device-code login in progress. */
    val oauthLoginInProgress: Boolean = false,
    val oauthUserCode: String? = null,
    val oauthVerificationUri: String? = null,
    val oauthLoginMessage: String? = null,
    /** Management key for live credits/usage (management-api.x.ai). */
    val managementKey: String = "",
    /** Optional team UUID for billing endpoints. */
    val teamId: String = "",
    val theme: AppTheme = AppTheme.DARK,
    val showCost: Boolean = false,
    val debugMode: Boolean = false,
    val responseFormat: String = "html",
    val fontSize: Float = 14f,
    val chatModel: String = AppDefaults.DEFAULT_MODEL,
    val voiceEnabled: Boolean = true,
    /** GPS / approximate location for chat (default on). */
    val locationEnabled: Boolean = true,
    /**
     * How long a GPS fix is reused before a new lookup (minutes). Default 10.
     * @see SettingsRepository.DEFAULT_LOCATION_CACHE_TIMEOUT_MINUTES
     */
    val locationCacheTimeoutMinutes: Int =
        SettingsRepository.DEFAULT_LOCATION_CACHE_TIMEOUT_MINUTES,
    val voiceOption: VoiceOption = VoiceOption.EVE,
    val personalityMode: PersonalityMode = PersonalityMode.ASSISTANT,
    val savedMessage: String? = null,
    /** Which voice is currently being previewed (null = none) */
    val previewingVoice: VoiceOption? = null,
    /** Which personality is currently being previewed (null = none) */
    val previewingPersonality: PersonalityMode? = null,
    val previewError: String? = null,
    /** VAD (voice activity detection) threshold 0.1–0.9; higher = less sensitive */
    val vadThreshold: Float = 0.5f,
    val apiKeyCheck: ApiKeyCheckUi = ApiKeyCheckUi.Idle
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val superGrokAuthRepository: SuperGrokAuthRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private val ttsClient = OkHttpClient()
    private var mediaPlayer: MediaPlayer? = null
    private var oauthLoginJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.apiKey.collect { apiKey ->
                _uiState.value = _uiState.value.copy(apiKey = apiKey.orEmpty())
            }
        }
        viewModelScope.launch {
            settingsRepository.authMode.collect { mode ->
                _uiState.value = _uiState.value.copy(authMode = AuthMode.fromStorage(mode))
            }
        }
        viewModelScope.launch {
            settingsRepository.oauthAccessToken.collect { token ->
                _uiState.value = _uiState.value.copy(oauthSignedIn = !token.isNullOrBlank())
            }
        }
        viewModelScope.launch {
            settingsRepository.oauthEmail.collect { email ->
                _uiState.value = _uiState.value.copy(oauthEmail = email)
            }
        }
        viewModelScope.launch {
            settingsRepository.managementKey.collect { key ->
                _uiState.value = _uiState.value.copy(managementKey = key.orEmpty())
            }
        }
        viewModelScope.launch {
            settingsRepository.teamId.collect { id ->
                _uiState.value = _uiState.value.copy(teamId = id.orEmpty())
            }
        }
        viewModelScope.launch {
            settingsRepository.theme.collect { theme ->
                _uiState.value = _uiState.value.copy(theme = theme)
            }
        }
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
            settingsRepository.voiceEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(voiceEnabled = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.locationEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(locationEnabled = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.locationCacheTimeoutMinutes.collect { minutes ->
                _uiState.value = _uiState.value.copy(locationCacheTimeoutMinutes = minutes)
            }
        }
        viewModelScope.launch {
            settingsRepository.voiceOption.collect { name ->
                _uiState.value = _uiState.value.copy(voiceOption = VoiceOption.fromName(name))
            }
        }
        viewModelScope.launch {
            settingsRepository.personalityMode.collect { name ->
                _uiState.value = _uiState.value.copy(personalityMode = PersonalityMode.fromName(name))
            }
        }
        viewModelScope.launch {
            settingsRepository.voiceVadThreshold.collect { v ->
                _uiState.value = _uiState.value.copy(vadThreshold = v)
            }
        }
    }

    // ── TTS preview ───────────────────────────────────────────────────────────

    private val voiceSamplePhrases = mapOf<VoiceOption, String>(
        VoiceOption.EVE to "Hi there! I'm Eve — energetic and ready to help.",
        VoiceOption.ARA to "Hello. I'm Ara — warm, friendly, and here for you.",
        VoiceOption.REX to "I'm Rex. Clear, confident, and ready to assist.",
        VoiceOption.SAL to "Hi, I'm Sal — smooth, balanced, and at your service.",
        VoiceOption.LEO to "I'm Leo. Strong and authoritative, here to guide you."
    )

    private val personalitySamplePhrases = mapOf<PersonalityMode, String>(
        PersonalityMode.ASSISTANT      to "Hello! How can I help you today?",
        PersonalityMode.THERAPIST      to "I'm here to listen. Tell me what's on your mind.",
        PersonalityMode.STORYTELLER    to "Once upon a time, in a land of wonder and mystery...",
        PersonalityMode.KIDS_STORY     to "Are you ready for a magical adventure? Let's go!",
        PersonalityMode.KIDS_TRIVIA    to "What has hands but cannot clap? A clock! Did you get it right?",
        PersonalityMode.MEDITATION     to "Breathe in slowly... and breathe out. Let everything go.",
        PersonalityMode.GROK_DOC       to "Based on what you've described, here are some things to consider.",
        PersonalityMode.MOTIVATION     to "You've got this! Every step forward is a victory!",
        PersonalityMode.PROFESSOR      to "Today we'll explore a fascinating concept that will change how you see the world.",
        PersonalityMode.ROMANTIC       to "The evening light is beautiful, and so is this moment.",
        PersonalityMode.SEXY           to "Hey there... I'm glad you called.",
        PersonalityMode.UNHINGED       to "Oh my — are you serious right now?! This is absolutely wild!",
        PersonalityMode.CONSPIRACY     to "Did you know they've been hiding the truth in plain sight all along?",
        PersonalityMode.ARGUMENTATIVE  to "Actually, I'd have to respectfully disagree with that perspective.",
        PersonalityMode.LANGUAGE_TUTOR to "Bonjour! Let's practice French today. Repeat after me: Comment allez-vous?"
    )

    fun previewVoice(voice: VoiceOption) {
        val text = voiceSamplePhrases[voice] ?: "Hello! This is ${voice.displayName}."
        _uiState.value = _uiState.value.copy(previewingVoice = voice, previewError = null)
        viewModelScope.launch {
            playTtsPreview(text, voice.name.lowercase(), onDone = {
                _uiState.value = _uiState.value.copy(previewingVoice = null)
            }, onError = { err ->
                _uiState.value = _uiState.value.copy(previewingVoice = null, previewError = err)
            })
        }
    }

    fun previewPersonality(mode: PersonalityMode) {
        val text = personalitySamplePhrases[mode] ?: mode.description
        val voiceId = _uiState.value.voiceOption.name.lowercase()
        _uiState.value = _uiState.value.copy(previewingPersonality = mode, previewError = null)
        viewModelScope.launch {
            playTtsPreview(text, voiceId, onDone = {
                _uiState.value = _uiState.value.copy(previewingPersonality = null)
            }, onError = { err ->
                _uiState.value = _uiState.value.copy(previewingPersonality = null, previewError = err)
            })
        }
    }

    fun clearPreviewError() { _uiState.value = _uiState.value.copy(previewError = null) }

    private suspend fun playTtsPreview(
        text: String,
        voiceId: String,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            stopPreview()
            val auth = superGrokAuthRepository.resolveAuth()
            val bearer = when (auth) {
                is ResolvedAuth.Ok -> auth.bearerToken
                is ResolvedAuth.Missing -> {
                    withContext(Dispatchers.Main) { onError(auth.message) }
                    return@withContext
                }
            }
            val body = JSONObject().apply {
                put("text", text)
                put("voice_id", voiceId)
                put("language", "en")
                put("response_format", "mp3")
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.x.ai/v1/tts")
                .header("Authorization", "Bearer $bearer")
                .post(body)
                .build()

            val response = ttsClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val msg = response.body?.string()?.take(200) ?: "HTTP ${response.code}"
                withContext(Dispatchers.Main) { onError("TTS error: $msg") }
                return@withContext
            }

            val mp3Bytes = response.body?.bytes() ?: run {
                withContext(Dispatchers.Main) { onError("Empty TTS response") }
                return@withContext
            }

            val file = File(context.cacheDir, "tts_preview.mp3")
            file.writeBytes(mp3Bytes)

            withContext(Dispatchers.Main) {
                val mp = MediaPlayer()
                mediaPlayer = mp
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    it.release()
                    if (mediaPlayer === it) mediaPlayer = null
                    onDone()
                }
                mp.setOnErrorListener { _, _, _ ->
                    mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null
                    onError("Playback error")
                    true
                }
                mp.prepareAsync()
                mp.setOnPreparedListener { it.start() }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message ?: "Preview failed") }
        }
    }

    fun stopPreview() {
        mediaPlayer?.runCatching { stop(); release() }
        mediaPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPreview()
    }

    // ── Settings mutations ────────────────────────────────────────────────────

    fun updateAuthMode(mode: AuthMode) {
        viewModelScope.launch {
            if (mode == AuthMode.SUPERGROK_OAUTH && !_uiState.value.oauthSignedIn) {
                // Switch mode only after a successful sign-in; start login UX instead.
                _uiState.value = _uiState.value.copy(
                    authMode = mode,
                    savedMessage = null,
                    oauthLoginMessage = "Sign in below to use SuperGrok OAuth."
                )
            }
            superGrokAuthRepository.setMode(mode)
            _uiState.value = _uiState.value.copy(authMode = mode, savedMessage = null)
        }
    }

    fun startSuperGrokLogin() {
        if (_uiState.value.oauthLoginInProgress) return
        oauthLoginJob?.cancel()
        oauthLoginJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                oauthLoginInProgress = true,
                oauthLoginMessage = "Requesting device code…",
                oauthUserCode = null,
                oauthVerificationUri = null,
                savedMessage = null
            )
            try {
                val device = withContext(Dispatchers.IO) {
                    superGrokAuthRepository.startDeviceLogin()
                }
                val openUri = device.verificationUriComplete ?: device.verificationUri
                _uiState.value = _uiState.value.copy(
                    oauthUserCode = device.userCode,
                    oauthVerificationUri = openUri,
                    oauthLoginMessage =
                        "Open the link, approve access, then wait — code ${device.userCode}"
                )
                val result = superGrokAuthRepository.completeDeviceLogin(
                    deviceCode = device.deviceCode,
                    intervalSeconds = device.intervalSeconds
                )
                result.fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            oauthLoginInProgress = false,
                            oauthUserCode = null,
                            oauthVerificationUri = null,
                            oauthLoginMessage = null,
                            authMode = AuthMode.SUPERGROK_OAUTH,
                            oauthSignedIn = true,
                            savedMessage = "Signed in with SuperGrok (experimental)."
                        )
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            oauthLoginInProgress = false,
                            oauthLoginMessage = e.message ?: "Sign-in failed"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    oauthLoginInProgress = false,
                    oauthLoginMessage = e.message ?: "Sign-in failed"
                )
            }
        }
    }

    fun cancelSuperGrokLogin() {
        oauthLoginJob?.cancel()
        oauthLoginJob = null
        _uiState.value = _uiState.value.copy(
            oauthLoginInProgress = false,
            oauthUserCode = null,
            oauthVerificationUri = null,
            oauthLoginMessage = "Sign-in cancelled."
        )
    }

    fun signOutSuperGrok() {
        oauthLoginJob?.cancel()
        viewModelScope.launch {
            superGrokAuthRepository.signOut()
            _uiState.value = _uiState.value.copy(
                authMode = AuthMode.API_KEY,
                oauthSignedIn = false,
                oauthEmail = null,
                oauthLoginInProgress = false,
                oauthUserCode = null,
                oauthVerificationUri = null,
                oauthLoginMessage = null,
                savedMessage = "Signed out of SuperGrok. Using API key mode."
            )
        }
    }

    fun updateApiKey(apiKey: String) {
        _uiState.value = _uiState.value.copy(
            apiKey = apiKey,
            savedMessage = null,
            apiKeyCheck = ApiKeyCheckUi.Idle
        )
    }

    /**
     * Probe api.x.ai with the active credential:
     * - SuperGrok mode → current OAuth access token
     * - API key mode → key currently in the field (not necessarily saved)
     */
    fun checkApiKey() {
        if (_uiState.value.apiKeyCheck is ApiKeyCheckUi.Checking) return
        _uiState.value = _uiState.value.copy(apiKeyCheck = ApiKeyCheckUi.Checking, savedMessage = null)
        viewModelScope.launch {
            val bearer = when (_uiState.value.authMode) {
                AuthMode.SUPERGROK_OAUTH -> {
                    superGrokAuthRepository.getValidAccessToken()
                        ?: run {
                            _uiState.value = _uiState.value.copy(
                                apiKeyCheck = ApiKeyCheckUi.Failure(
                                    "Sign in with SuperGrok first."
                                )
                            )
                            return@launch
                        }
                }
                AuthMode.API_KEY -> {
                    val key = _uiState.value.apiKey.trim()
                    if (key.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            apiKeyCheck = ApiKeyCheckUi.Failure("Enter an API key first.")
                        )
                        return@launch
                    }
                    key
                }
            }
            when (val result = chatRepository.checkApiKey(bearer)) {
                is ApiKeyCheckResult.Valid -> {
                    val samples = result.sampleModels.joinToString(", ").ifBlank { "none listed" }
                    val prefix = if (_uiState.value.authMode == AuthMode.SUPERGROK_OAUTH) {
                        "SuperGrok OAuth accepted"
                    } else {
                        "API key valid"
                    }
                    val msg = if (result.modelCount > 0) {
                        "$prefix · ${result.modelCount} models available ($samples)"
                    } else {
                        "$prefix · models list empty (credential accepted)"
                    }
                    _uiState.value = _uiState.value.copy(apiKeyCheck = ApiKeyCheckUi.Success(msg))
                }
                is ApiKeyCheckResult.Invalid -> {
                    _uiState.value = _uiState.value.copy(
                        apiKeyCheck = ApiKeyCheckUi.Failure(result.message)
                    )
                }
                is ApiKeyCheckResult.NetworkError -> {
                    _uiState.value = _uiState.value.copy(
                        apiKeyCheck = ApiKeyCheckUi.Failure(result.message)
                    )
                }
            }
        }
    }

    fun updateManagementKey(key: String) {
        _uiState.value = _uiState.value.copy(managementKey = key, savedMessage = null)
    }

    fun updateTeamId(teamId: String) {
        _uiState.value = _uiState.value.copy(teamId = teamId, savedMessage = null)
    }

    fun updateTheme(theme: AppTheme) {
        _uiState.value = _uiState.value.copy(theme = theme, savedMessage = null)
        viewModelScope.launch { settingsRepository.saveTheme(theme) }
    }

    fun updateShowCost(show: Boolean) {
        _uiState.value = _uiState.value.copy(showCost = show, savedMessage = null)
        viewModelScope.launch { settingsRepository.saveShowCost(show) }
    }

    fun updateDebugMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(debugMode = enabled, savedMessage = null)
        viewModelScope.launch { settingsRepository.saveDebugMode(enabled) }
    }

    fun updateResponseFormat(format: String) {
        _uiState.value = _uiState.value.copy(responseFormat = format, savedMessage = null)
        viewModelScope.launch { settingsRepository.saveResponseFormat(format) }
    }

    fun updateFontSize(size: Float) {
        _uiState.value = _uiState.value.copy(fontSize = size, savedMessage = null)
        viewModelScope.launch { settingsRepository.saveFontSize(size) }
    }

    fun updateChatModel(model: String) {
        val normalized = AppDefaults.normalizeChatModel(model)
        _uiState.value = _uiState.value.copy(chatModel = normalized, savedMessage = null)
        viewModelScope.launch { settingsRepository.saveChatModel(normalized) }
    }

    fun updateVoiceEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(voiceEnabled = enabled, savedMessage = null)
        viewModelScope.launch { settingsRepository.saveVoiceEnabled(enabled) }
    }

    fun updateLocationEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(locationEnabled = enabled, savedMessage = null)
        viewModelScope.launch { settingsRepository.saveLocationEnabled(enabled) }
    }

    fun updateLocationCacheTimeoutMinutes(minutes: Int) {
        val normalized =
            SettingsRepository.normalizeLocationCacheTimeoutMinutes(minutes)
        _uiState.value = _uiState.value.copy(
            locationCacheTimeoutMinutes = normalized,
            savedMessage = null
        )
        viewModelScope.launch {
            settingsRepository.saveLocationCacheTimeoutMinutes(normalized)
        }
    }

    fun updateVoiceOption(option: VoiceOption) {
        _uiState.value = _uiState.value.copy(voiceOption = option, savedMessage = null)
        viewModelScope.launch { settingsRepository.saveVoiceOption(option.name) }
    }

    fun updatePersonalityMode(mode: PersonalityMode) {
        _uiState.value = _uiState.value.copy(personalityMode = mode, savedMessage = null)
        viewModelScope.launch { settingsRepository.savePersonalityMode(mode.name) }
    }

    fun updateVadThreshold(value: Float) {
        _uiState.value = _uiState.value.copy(vadThreshold = value, savedMessage = null)
        viewModelScope.launch { settingsRepository.saveVoiceVadThreshold(value) }
    }

    fun saveApiKey() {
        viewModelScope.launch {
            settingsRepository.saveApiKey(_uiState.value.apiKey.trim())
            _uiState.value = _uiState.value.copy(savedMessage = "Settings saved.")
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            settingsRepository.clearApiKey()
            _uiState.value = _uiState.value.copy(
                apiKey = "",
                savedMessage = "API key cleared.",
                apiKeyCheck = ApiKeyCheckUi.Idle
            )
        }
    }

    fun saveManagementCredentials() {
        viewModelScope.launch {
            settingsRepository.saveManagementKey(_uiState.value.managementKey.trim())
            val team = _uiState.value.teamId.trim()
            if (team.isEmpty()) {
                settingsRepository.clearTeamId()
            } else {
                settingsRepository.saveTeamId(team)
            }
            _uiState.value = _uiState.value.copy(savedMessage = "Management credentials saved.")
        }
    }

    fun clearManagementCredentials() {
        viewModelScope.launch {
            settingsRepository.clearManagementKey()
            settingsRepository.clearTeamId()
            _uiState.value = _uiState.value.copy(
                managementKey = "",
                teamId = "",
                savedMessage = "Management credentials cleared."
            )
        }
    }
}

