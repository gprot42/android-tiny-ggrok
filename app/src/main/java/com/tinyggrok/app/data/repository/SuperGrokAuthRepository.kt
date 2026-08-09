package com.tinyggrok.app.data.repository

import android.util.Base64
import android.util.Log
import com.tinyggrok.app.data.auth.XaiOAuthClient
import com.tinyggrok.app.data.local.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class AuthMode {
    API_KEY,
    SUPERGROK_OAUTH;

    companion object {
        fun fromStorage(value: String?): AuthMode =
            when (value?.trim()?.uppercase()) {
                SUPERGROK_OAUTH.name, "OAUTH", "SUPERGROK" -> SUPERGROK_OAUTH
                else -> API_KEY
            }
    }
}

sealed class ResolvedAuth {
    data class Ok(val bearerToken: String, val mode: AuthMode, val label: String) : ResolvedAuth()
    data class Missing(val message: String) : ResolvedAuth()
}

data class OAuthSessionInfo(
    val signedIn: Boolean,
    val email: String?,
    val expiresAtEpochMs: Long?
)

/**
 * Resolves chat/voice credentials: either console API key or experimental SuperGrok OAuth.
 */
@Singleton
class SuperGrokAuthRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val oauthClient: XaiOAuthClient
) {
    private val TAG = "SuperGrokAuth"

    suspend fun currentMode(): AuthMode =
        AuthMode.fromStorage(settingsRepository.authMode.first())

    suspend fun sessionInfo(): OAuthSessionInfo {
        val access = settingsRepository.oauthAccessToken.first()
        val email = settingsRepository.oauthEmail.first()
        val exp = settingsRepository.oauthExpiresAtEpochMs.first()
        return OAuthSessionInfo(
            signedIn = !access.isNullOrBlank(),
            email = email,
            expiresAtEpochMs = exp
        )
    }

    suspend fun setMode(mode: AuthMode) {
        settingsRepository.saveAuthMode(mode.name)
    }

    suspend fun startDeviceLogin(): XaiOAuthClient.DeviceCodeResponse =
        withContext(Dispatchers.IO) { oauthClient.requestDeviceCode() }

    suspend fun completeDeviceLogin(
        deviceCode: String,
        intervalSeconds: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        when (
            val result = oauthClient.waitForAuthorization(
                deviceCode = deviceCode,
                intervalSeconds = intervalSeconds
            )
        ) {
            is XaiOAuthClient.PollResult.Success -> {
                persistTokens(result.tokens)
                settingsRepository.saveAuthMode(AuthMode.SUPERGROK_OAUTH.name)
                Result.success(Unit)
            }
            is XaiOAuthClient.PollResult.Denied ->
                Result.failure(IllegalStateException("Access denied: ${result.message}"))
            is XaiOAuthClient.PollResult.Expired ->
                Result.failure(IllegalStateException(result.message))
            is XaiOAuthClient.PollResult.Error ->
                Result.failure(IllegalStateException(result.message))
            is XaiOAuthClient.PollResult.Pending ->
                Result.failure(IllegalStateException("Still pending"))
        }
    }

    suspend fun signOut() {
        settingsRepository.clearOAuthSession()
        // Keep mode as API_KEY after logout so chat still works with a stored key.
        settingsRepository.saveAuthMode(AuthMode.API_KEY.name)
    }

    /**
     * Bearer credential for API calls based on selected [AuthMode].
     * Refreshes OAuth access token when close to expiry.
     */
    suspend fun resolveAuth(): ResolvedAuth {
        return when (currentMode()) {
            AuthMode.API_KEY -> {
                val key = settingsRepository.apiKey.first().orEmpty().trim()
                if (key.isEmpty()) {
                    ResolvedAuth.Missing(
                        "Add your xAI API key in Settings, or sign in with SuperGrok (experimental)."
                    )
                } else {
                    ResolvedAuth.Ok(key, AuthMode.API_KEY, "API key")
                }
            }
            AuthMode.SUPERGROK_OAUTH -> {
                val token = getValidAccessToken()
                if (token.isNullOrBlank()) {
                    ResolvedAuth.Missing(
                        "SuperGrok sign-in expired or missing. Open Settings → Sign in with SuperGrok."
                    )
                } else {
                    ResolvedAuth.Ok(token, AuthMode.SUPERGROK_OAUTH, "SuperGrok OAuth")
                }
            }
        }
    }

    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val access = settingsRepository.oauthAccessToken.first().orEmpty()
        val refresh = settingsRepository.oauthRefreshToken.first().orEmpty()
        val expiresAt = settingsRepository.oauthExpiresAtEpochMs.first() ?: 0L
        val skewMs = 120_000L // refresh 2 minutes early
        val now = System.currentTimeMillis()

        if (access.isNotBlank() && expiresAt > now + skewMs) {
            return@withContext access
        }
        if (refresh.isBlank()) {
            if (access.isNotBlank() && expiresAt > now) return@withContext access
            return@withContext null
        }
        return@withContext try {
            val tokens = oauthClient.refreshAccessToken(refresh)
            persistTokens(tokens, keepRefreshIfMissing = refresh)
            tokens.accessToken
        } catch (e: Exception) {
            Log.e(TAG, "OAuth refresh failed: ${e.message}")
            // Last resort: try existing access token if not expired yet
            if (access.isNotBlank() && expiresAt > now) access else null
        }
    }

    private suspend fun persistTokens(
        tokens: XaiOAuthClient.TokenResponse,
        keepRefreshIfMissing: String? = null
    ) {
        val refresh = tokens.refreshToken ?: keepRefreshIfMissing
        val expiresAt = System.currentTimeMillis() + tokens.expiresInSeconds * 1000L
        val email = extractEmailFromJwt(tokens.accessToken)
            ?: settingsRepository.oauthEmail.first()
        settingsRepository.saveOAuthSession(
            accessToken = tokens.accessToken,
            refreshToken = refresh.orEmpty(),
            expiresAtEpochMs = expiresAt,
            email = email
        )
    }

    /** Best-effort email from JWT payload (no signature verification). */
    private fun extractEmailFromJwt(jwt: String): String? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            )
            val json = JSONObject(payload)
            json.optString("email").takeIf { it.isNotBlank() }
                ?: json.optString("preferred_username").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
