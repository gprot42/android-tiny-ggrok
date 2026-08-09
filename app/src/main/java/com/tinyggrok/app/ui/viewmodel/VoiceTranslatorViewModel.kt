package com.tinyggrok.app.ui.viewmodel

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.model.LanguageDetector
import com.tinyggrok.app.data.model.PersonalityMode
import com.tinyggrok.app.data.model.SourceLanguage
import com.tinyggrok.app.data.model.TranslationLanguage
import com.tinyggrok.app.data.model.VoiceOption
import com.tinyggrok.app.data.model.TranscriptLine
import com.tinyggrok.app.data.model.VoiceEvent
import com.tinyggrok.app.data.model.VoiceSessionCost
import com.tinyggrok.app.data.model.VoiceSessionState
import com.tinyggrok.app.data.repository.RealtimeVoiceRepository
import com.tinyggrok.app.data.repository.ResolvedAuth
import com.tinyggrok.app.data.repository.SuperGrokAuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class VoiceTranslatorUiState(
    val sessionState: VoiceSessionState = VoiceSessionState.IDLE,
    val targetLanguage: TranslationLanguage = TranslationLanguage.ENGLISH,
    val sourceLanguage: SourceLanguage = SourceLanguage.AUTO,
    val voiceOption: VoiceOption = VoiceOption.EVE,
    val personalityMode: PersonalityMode = PersonalityMode.ASSISTANT,
    val silentMode: Boolean = false,
    val isUserSpeaking: Boolean = false,
    val isAssistantSpeaking: Boolean = false,
    val sessionDurationSeconds: Long = 0L,
    val costInfo: VoiceSessionCost? = null,
    val showCost: Boolean = false,
    val debugMode: Boolean = false,
    val voiceEnabled: Boolean = true,
    val errorMessage: String? = null,
    val transcriptLines: List<TranscriptLine> = emptyList(),
    val partialTranscript: String = "",
    val partialAssistantText: String = "",
    val detectedLanguage: String? = null,
    val silenceCountdownSeconds: Int? = null,
    /** When true: session auto-restarts instead of stopping after silence/response. */
    val permanentListenMode: Boolean = false,
    /** When true: audio is also being saved to a local PCM file. */
    val offlineRecordMode: Boolean = false,
    /** List of saved offline PCM recording files. */
    val offlineRecordings: List<java.io.File> = emptyList(),
    /** Current VAD threshold (mirrored from settings). */
    val vadThreshold: Float = 0.5f
)

@HiltViewModel
class VoiceTranslatorViewModel @Inject constructor(
    private val voiceRepository: RealtimeVoiceRepository,
    private val settingsRepository: SettingsRepository,
    private val superGrokAuthRepository: SuperGrokAuthRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceTranslatorUiState())
    val uiState: StateFlow<VoiceTranslatorUiState> = _uiState

    private var sessionTimerJob: Job? = null

    /** Fires [IDLE_STOP_MS] after the last response finishes if no new speech starts. */
    private var idleStopJob: Job? = null
    private val IDLE_STOP_MS = 8_000L

    /** Counts down silence since the last speech activity; auto-stops the session on expiry. */
    private var idleTimerJob: Job? = null

    companion object {
        private const val TAG = "VoiceVM"
        /** Total seconds of silence before the session auto-stops. */
        private const val SILENCE_TIMEOUT_SECONDS = 15
        /** Show an on-screen countdown during the final N seconds of the timeout. */
        private const val COUNTDOWN_VISIBLE_SECONDS = 5
    }

    // ── Text-to-Speech (Android fallback) ────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { voiceRepository.setMicMuted(false) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { voiceRepository.setMicMuted(false) }
                })
            } else {
                Log.w("VoiceTTS", "TextToSpeech init failed: $status")
            }
        }
        viewModelScope.launch { settingsRepository.showCost.collect { _uiState.value = _uiState.value.copy(showCost = it) } }
        viewModelScope.launch { settingsRepository.voiceEnabled.collect { _uiState.value = _uiState.value.copy(voiceEnabled = it) } }
        viewModelScope.launch { settingsRepository.debugMode.collect { _uiState.value = _uiState.value.copy(debugMode = it) } }
        viewModelScope.launch { settingsRepository.voiceTargetLanguage.collect { _uiState.value = _uiState.value.copy(targetLanguage = TranslationLanguage.fromName(it)) } }
        viewModelScope.launch { settingsRepository.voiceSourceLanguage.collect { _uiState.value = _uiState.value.copy(sourceLanguage = SourceLanguage.fromName(it)) } }
        viewModelScope.launch { settingsRepository.voiceOption.collect { _uiState.value = _uiState.value.copy(voiceOption = VoiceOption.fromName(it)) } }
        viewModelScope.launch { settingsRepository.personalityMode.collect { _uiState.value = _uiState.value.copy(personalityMode = PersonalityMode.fromName(it)) } }
        viewModelScope.launch { settingsRepository.voiceSilentMode.collect { _uiState.value = _uiState.value.copy(silentMode = it) } }
        viewModelScope.launch { settingsRepository.voiceVadThreshold.collect { _uiState.value = _uiState.value.copy(vadThreshold = it) } }
        viewModelScope.launch { settingsRepository.voicePermanentListen.collect { _uiState.value = _uiState.value.copy(permanentListenMode = it) } }
        collectVoiceEvents()
        refreshOfflineRecordings()
    }

    // ---------------------------------------------------------------------------
    // Session control
    // ---------------------------------------------------------------------------

    /** Start voice session. Returns false if RECORD_AUDIO permission is missing. */
    fun startSession(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return false

        if (_uiState.value.sessionState != VoiceSessionState.IDLE &&
            _uiState.value.sessionState != VoiceSessionState.ERROR) return true

        voiceRepository.disconnect()

        viewModelScope.launch {
            val auth = superGrokAuthRepository.resolveAuth()
            val apiKey = when (auth) {
                is ResolvedAuth.Ok -> auth.bearerToken
                is ResolvedAuth.Missing -> {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = auth.message,
                        sessionState = VoiceSessionState.ERROR
                    )
                    return@launch
                }
            }
            val s = _uiState.value
            _uiState.value = s.copy(
                sessionState = VoiceSessionState.CONNECTING,
                errorMessage = null,
                partialTranscript = "",
                partialAssistantText = "",
                sessionDurationSeconds = 0L,
                costInfo = null
            )
            voiceRepository.connect(
                apiKey,
                s.targetLanguage,
                s.sourceLanguage,
                s.voiceOption,
                s.personalityMode,
                s.silentMode,
                s.vadThreshold
            )
        }
        return true
    }

    fun stopSession() {
        cancelIdleStop()
        stopIdleTimer()
        sessionTimerJob?.cancel()
        sessionTimerJob = null
        voiceRepository.disconnect()
        _uiState.value = _uiState.value.copy(
            sessionState = VoiceSessionState.IDLE,
            isUserSpeaking = false,
            isAssistantSpeaking = false,
            partialTranscript = "",
            partialAssistantText = "",
            silenceCountdownSeconds = null
        )
    }

    fun setTargetLanguage(language: TranslationLanguage) {
        _uiState.value = _uiState.value.copy(targetLanguage = language)
        viewModelScope.launch { settingsRepository.saveVoiceTargetLanguage(language.name) }
        if (_uiState.value.sessionState == VoiceSessionState.ACTIVE) {
            val s = _uiState.value
            voiceRepository.switchLanguage(language, s.sourceLanguage, s.voiceOption, s.personalityMode, s.silentMode, s.vadThreshold)
        }
    }

    fun setSourceLanguage(language: SourceLanguage) {
        _uiState.value = _uiState.value.copy(sourceLanguage = language)
        viewModelScope.launch { settingsRepository.saveVoiceSourceLanguage(language.name) }
        if (_uiState.value.sessionState == VoiceSessionState.ACTIVE) {
            val s = _uiState.value
            voiceRepository.switchLanguage(s.targetLanguage, language, s.voiceOption, s.personalityMode, s.silentMode, s.vadThreshold)
        }
    }

    fun toggleSilentMode() {
        val newVal = !_uiState.value.silentMode
        _uiState.value = _uiState.value.copy(silentMode = newVal)
        viewModelScope.launch { settingsRepository.saveVoiceSilentMode(newVal) }
        if (_uiState.value.sessionState == VoiceSessionState.ACTIVE) {
            val s = _uiState.value
            voiceRepository.switchLanguage(s.targetLanguage, s.sourceLanguage, s.voiceOption, s.personalityMode, newVal, s.vadThreshold)
        }
    }

    /** Toggle permanent listen mode — session auto-restarts instead of stopping on silence. */
    fun togglePermanentListenMode() {
        val newVal = !_uiState.value.permanentListenMode
        _uiState.value = _uiState.value.copy(permanentListenMode = newVal)
        viewModelScope.launch { settingsRepository.saveVoicePermanentListen(newVal) }
    }

    fun clearTranscript() {
        _uiState.value = _uiState.value.copy(
            transcriptLines = emptyList(),
            partialTranscript = "",
            partialAssistantText = "",
            detectedLanguage = null
        )
    }

    // ── Audio save/share ──────────────────────────────────────────────────────

    suspend fun saveAudio(text: String): String? = voiceRepository.saveAudioToDownloads(text)
    suspend fun saveLastAudio(): String? = voiceRepository.saveAudioToDownloads("")
    suspend fun getAudioUri(text: String): android.net.Uri? = voiceRepository.getAudioUri(text)

    // ── Flip languages ────────────────────────────────────────────────────────

    fun flipLanguages() {
        val current = _uiState.value
        val oldSource = current.sourceLanguage
        val oldTarget = current.targetLanguage
        val newSource = SourceLanguage.values().firstOrNull { it.code == oldTarget.code } ?: oldSource
        val newTarget = if (oldSource == SourceLanguage.AUTO) oldTarget
                        else TranslationLanguage.values().firstOrNull { it.code == oldSource.code } ?: oldTarget
        _uiState.value = current.copy(sourceLanguage = newSource, targetLanguage = newTarget)
        viewModelScope.launch {
            settingsRepository.saveVoiceSourceLanguage(newSource.name)
            settingsRepository.saveVoiceTargetLanguage(newTarget.name)
        }
        if (current.sessionState == VoiceSessionState.ACTIVE) {
            voiceRepository.switchLanguage(newTarget, newSource, current.voiceOption, current.personalityMode, current.silentMode, current.vadThreshold)
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(errorMessage = null) }

    // ── Speak translation ─────────────────────────────────────────────────────

    /**
     * Speak [text] aloud.
     * Priority: 1) cached Grok voice PCM bytes  2) xAI TTS REST API  3) Android TTS fallback
     */
    fun speakTranslation(text: String, language: TranslationLanguage) {
        if (text.isBlank()) return
        // 1. Replay exact cached PCM (only available if audio mode was active during translation)
        if (voiceRepository.replayAudio(text)) return
        // 2. Use xAI TTS REST API for high-quality playback (works in text-only/silent mode too)
        viewModelScope.launch {
            val played = voiceRepository.playTtsAudio(text)
            if (!played) {
                // 3. Last-resort: Android device TTS (often missing many languages)
                if (!ttsReady) return@launch
                val locale = language.toLocale()
                val langResult = tts?.setLanguage(locale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.ENGLISH)
                }
                voiceRepository.setMicMuted(true)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
            }
        }
    }

    fun replayLastTranslation() {
        if (voiceRepository.replayLastAudio()) return
        val last = _uiState.value.transcriptLines.lastOrNull { it.role == "assistant" }
        val lang = last?.spokenLanguage ?: _uiState.value.targetLanguage
        val text = last?.text ?: return
        speakTranslation(text, lang)
    }

    // ── Offline recording ─────────────────────────────────────────────────────

    /** Toggle offline recording mode (records audio to local file for later translation). */
    fun toggleOfflineRecordMode() {
        val active = _uiState.value.offlineRecordMode
        if (active) {
            // Stop recording
            val file = voiceRepository.stopOfflineRecording()
            refreshOfflineRecordings()
            _uiState.value = _uiState.value.copy(
                offlineRecordMode = false,
                errorMessage = if (file != null) "Saved: ${file.name}" else null
            )
        } else {
            // Start recording
            val started = voiceRepository.startOfflineRecording()
            _uiState.value = _uiState.value.copy(
                offlineRecordMode = started,
                errorMessage = if (!started) "Could not start recording" else null
            )
        }
    }

    /** Process (translate) a saved offline recording — requires an active session. */
    fun processOfflineRecording(file: java.io.File) {
        if (_uiState.value.sessionState != VoiceSessionState.ACTIVE) {
            startSession()
        }
        voiceRepository.processOfflineRecording(file)
    }

    fun deleteOfflineRecording(file: java.io.File) {
        voiceRepository.deleteOfflineRecording(file)
        refreshOfflineRecordings()
    }

    private fun refreshOfflineRecordings() {
        _uiState.value = _uiState.value.copy(offlineRecordings = voiceRepository.getOfflineRecordings())
    }

    // ---------------------------------------------------------------------------
    // Event collection
    // ---------------------------------------------------------------------------

    private fun collectVoiceEvents() {
        viewModelScope.launch {
            voiceRepository.events.collect { event ->
                when (event) {
                    is VoiceEvent.SessionCreated -> {
                        _uiState.value = _uiState.value.copy(sessionState = VoiceSessionState.ACTIVE, errorMessage = null)
                        startSessionTimer()
                        // Always On + Text Only: no idle timer — session stays open until user stops it
                        if (!(_uiState.value.permanentListenMode && _uiState.value.silentMode)) {
                            startIdleTimer()
                        }
                    }

                    is VoiceEvent.SessionError -> {
                        stopSessionTimer()
                        stopIdleTimer()
                        _uiState.value = _uiState.value.copy(
                            sessionState = VoiceSessionState.ERROR,
                            errorMessage = event.message,
                            isUserSpeaking = false,
                            isAssistantSpeaking = false,
                            silenceCountdownSeconds = null
                        )
                        // In permanent mode: auto-retry after a short pause
                        if (_uiState.value.permanentListenMode) {
                            viewModelScope.launch {
                                delay(3000)
                                if (_uiState.value.permanentListenMode) startSession()
                            }
                        }
                    }

                    is VoiceEvent.SessionClosed -> {
                        stopSessionTimer()
                        stopIdleTimer()
                        if (_uiState.value.sessionState != VoiceSessionState.IDLE) {
                            _uiState.value = _uiState.value.copy(
                                sessionState = VoiceSessionState.IDLE,
                                isUserSpeaking = false,
                                isAssistantSpeaking = false,
                                partialTranscript = "",
                                partialAssistantText = "",
                                silenceCountdownSeconds = null
                            )
                        }
                        // In permanent mode: auto-restart
                        if (_uiState.value.permanentListenMode) {
                            viewModelScope.launch {
                                delay(1000)
                                if (_uiState.value.permanentListenMode) startSession()
                            }
                        }
                    }

                    is VoiceEvent.SpeechStarted -> {
                        cancelIdleStop()
                        _uiState.value = _uiState.value.copy(isUserSpeaking = true, partialTranscript = "")
                    }

                    is VoiceEvent.SpeechStopped -> {
                        _uiState.value = _uiState.value.copy(isUserSpeaking = false)
                    }

                    is VoiceEvent.TranscriptPartial -> {
                        _uiState.value = _uiState.value.copy(partialTranscript = _uiState.value.partialTranscript + event.text)
                    }

                    is VoiceEvent.TranscriptFinal -> {
                        val selectedSource = _uiState.value.sourceLanguage
                        val detected: String? = if (selectedSource != SourceLanguage.AUTO) {
                            // User explicitly chose the source language — trust it over the
                            // transcriber's (unreliable) auto-detection. xAI STT can't detect
                            // low-resource languages like Cebuano and mislabels them as English.
                            selectedSource.displayName
                        } else {
                            val heuristic = LanguageDetector.detect(event.text)
                            val serverDetected = event.serverLanguage?.let { LanguageDetector.fromBcp47(it) }
                            when {
                                serverDetected != null && serverDetected != "English" -> serverDetected
                                heuristic != null && heuristic != "English" -> heuristic
                                serverDetected != null -> serverDetected
                                else -> heuristic
                            }
                        }
                        val lines = _uiState.value.transcriptLines.toMutableList()
                        lines.add(TranscriptLine("user", event.text, detectedLanguage = detected))
                        _uiState.value = _uiState.value.copy(
                            transcriptLines = lines.takeLast(30),
                            partialTranscript = "",
                            detectedLanguage = detected
                        )
                    }

                    is VoiceEvent.AssistantAudioStarted -> {
                        stopIdleTimer()
                        _uiState.value = _uiState.value.copy(isAssistantSpeaking = true, silenceCountdownSeconds = null)
                    }

                    is VoiceEvent.AssistantAudioEnded -> {
                        val partial = _uiState.value.partialAssistantText
                        if (partial.isNotBlank()) {
                            val lines = _uiState.value.transcriptLines.toMutableList()
                            lines.add(TranscriptLine(role = "assistant", text = partial, spokenLanguage = _uiState.value.targetLanguage))
                            _uiState.value = _uiState.value.copy(
                                isAssistantSpeaking = false,
                                transcriptLines = lines.takeLast(100),
                                partialAssistantText = ""
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(isAssistantSpeaking = false)
                        }
                        if (_uiState.value.permanentListenMode && _uiState.value.silentMode) {
                            // Always On + Text Only: stay live, no timers
                            cancelIdleStop()
                            idleTimerJob?.cancel(); idleTimerJob = null
                        } else if (_uiState.value.permanentListenMode) {
                            startIdleTimer()
                        } else {
                            startIdleStop()
                        }
                    }

                    is VoiceEvent.AssistantTextDelta -> {
                        _uiState.value = _uiState.value.copy(partialAssistantText = _uiState.value.partialAssistantText + event.text)
                    }

                    is VoiceEvent.AssistantTextDone -> {
                        val text = event.fullText.ifBlank { _uiState.value.partialAssistantText }
                        if (text.isNotBlank()) {
                            val lines = _uiState.value.transcriptLines.toMutableList()
                            lines.add(TranscriptLine(role = "assistant", text = text, spokenLanguage = _uiState.value.targetLanguage))
                            _uiState.value = _uiState.value.copy(transcriptLines = lines.takeLast(100), partialAssistantText = "")
                        }
                        // In silent mode, text done means we're done with this turn
                        if (_uiState.value.silentMode) {
                            if (_uiState.value.permanentListenMode) {
                                // Always On + Text Only: stay connected forever, just cancel any pending stop
                                cancelIdleStop()
                                idleTimerJob?.cancel()
                                idleTimerJob = null
                            } else {
                                startIdleStop()
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Session cost timer
    // ---------------------------------------------------------------------------

    private fun startSessionTimer() {
        sessionTimerJob?.cancel()
        sessionTimerJob = viewModelScope.launch {
            var seconds = 0L
            while (true) {
                delay(1_000)
                seconds++
                _uiState.value = _uiState.value.copy(
                    sessionDurationSeconds = seconds,
                    costInfo = VoiceSessionCost(seconds, seconds * RealtimeVoiceRepository.COST_PER_SECOND)
                )
            }
        }
    }

    private fun stopSessionTimer() {
        sessionTimerJob?.cancel()
        sessionTimerJob = null
    }

    private fun startIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = viewModelScope.launch {
            delay(IDLE_STOP_MS)
            if (_uiState.value.sessionState == VoiceSessionState.ACTIVE) {
                if (_uiState.value.permanentListenMode) {
                    // Restart rather than stop
                    startIdleTimer()
                } else {
                    stopSession()
                }
            }
        }
    }

    private fun cancelIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = null
    }

    // ---------------------------------------------------------------------------
    // Idle / silence auto-stop
    // ---------------------------------------------------------------------------

    private fun startIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = viewModelScope.launch {
            for (elapsed in 1..SILENCE_TIMEOUT_SECONDS) {
                delay(1_000)
                val s = _uiState.value
                if (s.sessionState != VoiceSessionState.ACTIVE || s.isUserSpeaking || s.isAssistantSpeaking) {
                    _uiState.value = s.copy(silenceCountdownSeconds = null)
                    return@launch
                }
                val remaining = SILENCE_TIMEOUT_SECONDS - elapsed
                _uiState.value = s.copy(
                    silenceCountdownSeconds = if (remaining in 1..COUNTDOWN_VISIBLE_SECONDS) remaining else null
                )
            }
            // Timeout expired
            _uiState.value = _uiState.value.copy(silenceCountdownSeconds = null)
            if (_uiState.value.permanentListenMode) {
                // Permanent mode: restart session
                stopSession()
                delay(500)
                startSession()
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Session auto-stopped after ${SILENCE_TIMEOUT_SECONDS}s of silence. Tap the mic to continue."
                )
                stopSession()
            }
        }
    }

    private fun stopIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = null
        if (_uiState.value.silenceCountdownSeconds != null) {
            _uiState.value = _uiState.value.copy(silenceCountdownSeconds = null)
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelIdleStop()
        tts?.stop()
        tts?.shutdown()
        tts = null
        voiceRepository.disconnect()
    }
}

fun TranslationLanguage.toLocale(): Locale = when (this) {
    TranslationLanguage.ARABIC        -> Locale("ar", "SA")
    TranslationLanguage.CEBUANO       -> Locale("ceb", "PH")
    TranslationLanguage.ENGLISH       -> Locale.ENGLISH
    TranslationLanguage.TAGALOG       -> Locale("fil", "PH")
    TranslationLanguage.GERMAN        -> Locale.GERMAN
    TranslationLanguage.FRENCH        -> Locale.FRENCH
    TranslationLanguage.CHINESE       -> Locale.CHINESE
    TranslationLanguage.HINDI         -> Locale("hi", "IN")
    TranslationLanguage.INDONESIAN    -> Locale("id", "ID")
    TranslationLanguage.JAPANESE      -> Locale.JAPANESE
    TranslationLanguage.KOREAN        -> Locale.KOREAN
    TranslationLanguage.MALAY         -> Locale("ms", "MY")
    TranslationLanguage.MARATHI       -> Locale("mr", "IN")
    TranslationLanguage.PORTUGUESE_BR -> Locale("pt", "BR")
    TranslationLanguage.PORTUGUESE_PT -> Locale("pt", "PT")
    TranslationLanguage.SPANISH_ES    -> Locale("es", "ES")
    TranslationLanguage.SPANISH_MX    -> Locale("es", "MX")
    TranslationLanguage.THAI          -> Locale("th", "TH")
}
