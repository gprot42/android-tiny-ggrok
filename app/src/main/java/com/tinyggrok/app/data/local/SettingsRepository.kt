package com.tinyggrok.app.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tinyggrok.app.AppDefaults
import com.tinyggrok.app.ui.theme.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val API_KEY_KEY = stringPreferencesKey("api_key")
    /** Management key for billing/credit queries (management-api.x.ai). */
    private val MANAGEMENT_KEY_KEY = stringPreferencesKey("management_key")
    /** Optional team UUID; if blank, resolved from management key validation. */
    private val TEAM_ID_KEY = stringPreferencesKey("team_id")
    /**
     * User-selected consumer Grok plan label (display only).
     * SuperGrok utilisation is not available via API.
     */
    private val CONSUMER_PLAN_KEY = stringPreferencesKey("consumer_plan")
    /**
     * Chat auth mode: API_KEY (default) or SUPERGROK_OAUTH (experimental).
     * @see com.tinyggrok.app.data.repository.AuthMode
     */
    private val AUTH_MODE_KEY = stringPreferencesKey("auth_mode")
    private val OAUTH_ACCESS_TOKEN_KEY = stringPreferencesKey("oauth_access_token")
    private val OAUTH_REFRESH_TOKEN_KEY = stringPreferencesKey("oauth_refresh_token")
    private val OAUTH_EXPIRES_AT_KEY = stringPreferencesKey("oauth_expires_at_epoch_ms")
    private val OAUTH_EMAIL_KEY = stringPreferencesKey("oauth_email")
    private val THEME_KEY = stringPreferencesKey("theme")
    private val SHOW_COST_KEY = booleanPreferencesKey("show_cost")
    private val DEBUG_MODE_KEY = booleanPreferencesKey("debug_mode")
    private val RESPONSE_FORMAT_KEY = stringPreferencesKey("response_format")
    private val FONT_SIZE_KEY = floatPreferencesKey("font_size")
    private val CHAT_MODEL_KEY = stringPreferencesKey("chat_model")
    private val VOICE_ENABLED_KEY = booleanPreferencesKey("voice_enabled")
    private val VOICE_TARGET_LANGUAGE_KEY = stringPreferencesKey("voice_target_language")
    private val VOICE_SOURCE_LANGUAGE_KEY = stringPreferencesKey("voice_source_language")
    private val VOICE_OPTION_KEY = stringPreferencesKey("voice_option")
    private val PERSONALITY_MODE_KEY = stringPreferencesKey("personality_mode")
    private val VOICE_SILENT_MODE_KEY = booleanPreferencesKey("voice_silent_mode")
    private val VOICE_VAD_THRESHOLD_KEY = floatPreferencesKey("voice_vad_threshold")
    private val VOICE_PERMANENT_LISTEN_KEY = booleanPreferencesKey("voice_permanent_listen")
    /** When true (default), chat may attach approximate GPS location to prompts if permitted. */
    private val LOCATION_ENABLED_KEY = booleanPreferencesKey("location_enabled")
    /**
     * How long a GPS fix stays valid without a new chip lookup (minutes).
     * Default [DEFAULT_LOCATION_CACHE_TIMEOUT_MINUTES].
     */
    private val LOCATION_CACHE_TIMEOUT_MINUTES_KEY =
        intPreferencesKey("location_cache_timeout_minutes")

    val apiKey: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[API_KEY_KEY] }

    val authMode: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[AUTH_MODE_KEY] ?: "API_KEY" }

    val oauthAccessToken: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[OAUTH_ACCESS_TOKEN_KEY] }

    val oauthRefreshToken: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[OAUTH_REFRESH_TOKEN_KEY] }

    val oauthExpiresAtEpochMs: Flow<Long?> = context.dataStore.data
        .map { preferences -> preferences[OAUTH_EXPIRES_AT_KEY]?.toLongOrNull() }

    val oauthEmail: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[OAUTH_EMAIL_KEY] }

    val managementKey: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[MANAGEMENT_KEY_KEY] }

    val teamId: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[TEAM_ID_KEY] }

    /** Display-only consumer plan: FREE, SUPERGROK_LITE, SUPERGROK, SUPERGROK_HEAVY. */
    val consumerPlan: Flow<String> = context.dataStore.data
        .map { preferences ->
            normalizeConsumerPlan(preferences[CONSUMER_PLAN_KEY])
        }

    val theme: Flow<AppTheme> = context.dataStore.data
        .map { preferences ->
            when (preferences[THEME_KEY]) {
                "LIGHT" -> AppTheme.LIGHT
                "TOKYO_NIGHT" -> AppTheme.TOKYO_NIGHT
                else -> AppTheme.DARK
            }
        }

    val showCost: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SHOW_COST_KEY] ?: false }

    val debugMode: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[DEBUG_MODE_KEY] ?: false }

    val responseFormat: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[RESPONSE_FORMAT_KEY] ?: "html" }

    val fontSize: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[FONT_SIZE_KEY] ?: 14f }

    /** Chat model id (grok-4.6 / grok-4.5). Defaults to [AppDefaults.DEFAULT_MODEL]. */
    val chatModel: Flow<String> = context.dataStore.data
        .map { preferences ->
            AppDefaults.normalizeChatModel(preferences[CHAT_MODEL_KEY])
        }

    val voiceEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[VOICE_ENABLED_KEY] ?: true }

    val voiceTargetLanguage: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[VOICE_TARGET_LANGUAGE_KEY] ?: "ENGLISH" }

    val voiceSourceLanguage: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[VOICE_SOURCE_LANGUAGE_KEY] ?: "AUTO" }

    val voiceOption: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[VOICE_OPTION_KEY] ?: "EVE" }

    val personalityMode: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[PERSONALITY_MODE_KEY] ?: "ASSISTANT" }

    val voiceSilentMode: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[VOICE_SILENT_MODE_KEY] ?: false }

    val voiceVadThreshold: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[VOICE_VAD_THRESHOLD_KEY] ?: 0.5f }

    val voicePermanentListen: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[VOICE_PERMANENT_LISTEN_KEY] ?: false }

    /** GPS / approximate location for chat context. Default on; user can disable. */
    val locationEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[LOCATION_ENABLED_KEY] ?: true }

    /**
     * GPS cache TTL in minutes. Within this window we reuse the last fix (no GPS chip).
     * Default 10; clamped to [MIN_LOCATION_CACHE_TIMEOUT_MINUTES]–
     * [MAX_LOCATION_CACHE_TIMEOUT_MINUTES].
     */
    val locationCacheTimeoutMinutes: Flow<Int> = context.dataStore.data
        .map { preferences ->
            normalizeLocationCacheTimeoutMinutes(
                preferences[LOCATION_CACHE_TIMEOUT_MINUTES_KEY]
            )
        }

    suspend fun saveApiKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[API_KEY_KEY] = key
        }
    }

    suspend fun clearApiKey() {
        context.dataStore.edit { preferences ->
            preferences.remove(API_KEY_KEY)
        }
    }

    suspend fun saveAuthMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[AUTH_MODE_KEY] = mode.trim().ifBlank { "API_KEY" }
        }
    }

    suspend fun saveOAuthSession(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long,
        email: String?
    ) {
        context.dataStore.edit { preferences ->
            preferences[OAUTH_ACCESS_TOKEN_KEY] = accessToken
            if (refreshToken.isNotBlank()) {
                preferences[OAUTH_REFRESH_TOKEN_KEY] = refreshToken
            }
            preferences[OAUTH_EXPIRES_AT_KEY] = expiresAtEpochMs.toString()
            if (!email.isNullOrBlank()) {
                preferences[OAUTH_EMAIL_KEY] = email
            }
        }
    }

    suspend fun clearOAuthSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(OAUTH_ACCESS_TOKEN_KEY)
            preferences.remove(OAUTH_REFRESH_TOKEN_KEY)
            preferences.remove(OAUTH_EXPIRES_AT_KEY)
            preferences.remove(OAUTH_EMAIL_KEY)
        }
    }

    suspend fun saveManagementKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[MANAGEMENT_KEY_KEY] = key
        }
    }

    suspend fun clearManagementKey() {
        context.dataStore.edit { preferences ->
            preferences.remove(MANAGEMENT_KEY_KEY)
        }
    }

    suspend fun saveTeamId(teamId: String) {
        context.dataStore.edit { preferences ->
            preferences[TEAM_ID_KEY] = teamId.trim()
        }
    }

    suspend fun clearTeamId() {
        context.dataStore.edit { preferences ->
            preferences.remove(TEAM_ID_KEY)
        }
    }

    suspend fun saveConsumerPlan(plan: String) {
        context.dataStore.edit { preferences ->
            preferences[CONSUMER_PLAN_KEY] = normalizeConsumerPlan(plan)
        }
    }

    suspend fun saveTheme(theme: AppTheme) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme.name
        }
    }

    suspend fun saveShowCost(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_COST_KEY] = show
        }
    }

    suspend fun saveDebugMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DEBUG_MODE_KEY] = enabled
        }
    }

    suspend fun saveResponseFormat(format: String) {
        context.dataStore.edit { preferences ->
            preferences[RESPONSE_FORMAT_KEY] = format
        }
    }

    suspend fun saveFontSize(size: Float) {
        context.dataStore.edit { preferences ->
            preferences[FONT_SIZE_KEY] = size
        }
    }

    suspend fun saveChatModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[CHAT_MODEL_KEY] = AppDefaults.normalizeChatModel(model)
        }
    }

    suspend fun saveVoiceEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_ENABLED_KEY] = enabled
        }
    }

    suspend fun saveVoiceTargetLanguage(languageName: String) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_TARGET_LANGUAGE_KEY] = languageName
        }
    }

    suspend fun saveVoiceSourceLanguage(languageName: String) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_SOURCE_LANGUAGE_KEY] = languageName
        }
    }

    suspend fun saveVoiceOption(optionName: String) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_OPTION_KEY] = optionName
        }
    }

    suspend fun savePersonalityMode(modeName: String) {
        context.dataStore.edit { preferences ->
            preferences[PERSONALITY_MODE_KEY] = modeName
        }
    }

    suspend fun saveVoiceSilentMode(silent: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_SILENT_MODE_KEY] = silent
        }
    }

    suspend fun saveVoiceVadThreshold(threshold: Float) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_VAD_THRESHOLD_KEY] = threshold.coerceIn(0.1f, 0.9f)
        }
    }

    suspend fun saveVoicePermanentListen(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_PERMANENT_LISTEN_KEY] = enabled
        }
    }

    suspend fun saveLocationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LOCATION_ENABLED_KEY] = enabled
        }
    }

    suspend fun saveLocationCacheTimeoutMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[LOCATION_CACHE_TIMEOUT_MINUTES_KEY] =
                normalizeLocationCacheTimeoutMinutes(minutes)
        }
    }

    fun logDebug(tag: String, message: String) {
        // Always log to Android logcat; UI visibility is controlled separately
        Log.d(tag, message)
    }

    companion object {
        const val DEFAULT_LOCATION_CACHE_TIMEOUT_MINUTES = 10
        const val MIN_LOCATION_CACHE_TIMEOUT_MINUTES = 1
        /** Upper bound for custom values (24 h). Presets go up to 2 h. */
        const val MAX_LOCATION_CACHE_TIMEOUT_MINUTES = 24 * 60

        /** Built-in chips in Settings (minutes). Values outside this list are “custom”. */
        val LOCATION_CACHE_TIMEOUT_PRESETS_MINUTES: List<Int> =
            listOf(10, 15, 30, 60, 120)

        const val CONSUMER_PLAN_FREE = "FREE"
        const val CONSUMER_PLAN_SUPERGROK_LITE = "SUPERGROK_LITE"
        const val CONSUMER_PLAN_SUPERGROK = "SUPERGROK"
        const val CONSUMER_PLAN_SUPERGROK_HEAVY = "SUPERGROK_HEAVY"

        val CONSUMER_PLANS: List<Pair<String, String>> = listOf(
            "Free / none" to CONSUMER_PLAN_FREE,
            "SuperGrok Lite" to CONSUMER_PLAN_SUPERGROK_LITE,
            "SuperGrok" to CONSUMER_PLAN_SUPERGROK,
            "SuperGrok Heavy" to CONSUMER_PLAN_SUPERGROK_HEAVY
        )

        fun normalizeConsumerPlan(plan: String?): String {
            val p = plan?.trim()?.uppercase().orEmpty()
            return CONSUMER_PLANS.map { it.second }.firstOrNull { it == p }
                ?: CONSUMER_PLAN_FREE
        }

        fun consumerPlanLabel(plan: String?): String =
            CONSUMER_PLANS.firstOrNull { it.second == normalizeConsumerPlan(plan) }?.first
                ?: "Free / none"

        fun normalizeLocationCacheTimeoutMinutes(minutes: Int?): Int {
            val m = minutes ?: DEFAULT_LOCATION_CACHE_TIMEOUT_MINUTES
            return m.coerceIn(
                MIN_LOCATION_CACHE_TIMEOUT_MINUTES,
                MAX_LOCATION_CACHE_TIMEOUT_MINUTES
            )
        }

        fun isLocationCacheTimeoutPreset(minutes: Int): Boolean =
            LOCATION_CACHE_TIMEOUT_PRESETS_MINUTES.contains(
                normalizeLocationCacheTimeoutMinutes(minutes)
            )
    }
}
