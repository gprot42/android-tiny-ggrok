package com.tinyggrok.app.data.auth

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Experimental xAI SuperGrok OAuth via the public OIDC device-code flow.
 *
 * Uses the same public OIDC client family as Grok Build (`auth.x.ai`, grant
 * `urn:ietf:params:oauth:grant-type:device_code`, auth method `none`).
 *
 * This is **not** an official Tiny Grok entitlement API. xAI may still bill or
 * gate usage separately from SuperGrok Heavy consumer chat.
 */
@Singleton
class XaiOAuthClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresInSeconds: Int,
        val intervalSeconds: Int
    )

    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Int,
        val tokenType: String?,
        val scope: String?
    )

    sealed class PollResult {
        data class Success(val tokens: TokenResponse) : PollResult()
        data class Pending(val reason: String) : PollResult()
        data class Denied(val message: String) : PollResult()
        data class Expired(val message: String) : PollResult()
        data class Error(val message: String) : PollResult()
    }

    fun requestDeviceCode(): DeviceCodeResponse {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()
        val request = Request.Builder()
            .url(DEVICE_CODE_URL)
            .post(body)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Device code request failed HTTP ${response.code}: ${raw.take(300)}"
                )
            }
            val json = JSONObject(raw)
            return DeviceCodeResponse(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUri = json.optString("verification_uri")
                    .ifBlank { "https://accounts.x.ai/oauth2/device" },
                verificationUriComplete = json.optString("verification_uri_complete")
                    .takeIf { it.isNotBlank() },
                expiresInSeconds = json.optInt("expires_in", 1800),
                intervalSeconds = json.optInt("interval", 5).coerceAtLeast(1)
            )
        }
    }

    /**
     * Single poll of the token endpoint. Caller should loop with [intervalSeconds]
     * until Success / Denied / Expired / Error.
     */
    fun pollToken(deviceCode: String): PollResult {
        val body = FormBody.Builder()
            .add("grant_type", DEVICE_GRANT)
            .add("device_code", deviceCode)
            .add("client_id", CLIENT_ID)
            .build()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    return PollResult.Success(parseTokenResponse(raw))
                }
                val json = runCatching { JSONObject(raw) }.getOrNull()
                val err = json?.optString("error").orEmpty()
                val desc = json?.optString("error_description")
                    ?.ifBlank { null }
                    ?: json?.optString("error")
                    ?: raw.take(200).ifBlank { "HTTP ${response.code}" }
                when (err) {
                    "authorization_pending", "slow_down" -> PollResult.Pending(err)
                    "access_denied" -> PollResult.Denied(desc)
                    "expired_token" -> PollResult.Expired(desc)
                    else -> {
                        if (response.code == 400 && "pending" in desc.lowercase()) {
                            PollResult.Pending(desc)
                        } else {
                            Log.w(TAG, "token poll HTTP ${response.code}: $raw")
                            PollResult.Error("HTTP ${response.code}: $desc")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            PollResult.Error(e.message ?: "network error")
        }
    }

    /**
     * Poll until the user approves, the code expires, or [maxWaitMs] elapses.
     */
    suspend fun waitForAuthorization(
        deviceCode: String,
        intervalSeconds: Int,
        maxWaitMs: Long = 15 * 60_000L
    ): PollResult {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var intervalMs = intervalSeconds.coerceAtLeast(1) * 1000L
        while (System.currentTimeMillis() < deadline) {
            when (val result = pollToken(deviceCode)) {
                is PollResult.Success,
                is PollResult.Denied,
                is PollResult.Expired,
                is PollResult.Error -> return result
                is PollResult.Pending -> {
                    if (result.reason == "slow_down") {
                        intervalMs = (intervalMs + 2000L).coerceAtMost(15_000L)
                    }
                    delay(intervalMs)
                }
            }
        }
        return PollResult.Expired("Timed out waiting for SuperGrok approval.")
    }

    fun refreshAccessToken(refreshToken: String): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Token refresh failed HTTP ${response.code}: ${raw.take(300)}"
                )
            }
            return parseTokenResponse(raw)
        }
    }

    private fun parseTokenResponse(raw: String): TokenResponse {
        val json = JSONObject(raw)
        val access = json.optString("access_token")
        if (access.isBlank()) {
            throw IllegalStateException("Token response missing access_token")
        }
        return TokenResponse(
            accessToken = access,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresInSeconds = json.optInt("expires_in", 3600),
            tokenType = json.optString("token_type").takeIf { it.isNotBlank() },
            scope = json.optString("scope").takeIf { it.isNotBlank() }
        )
    }

    companion object {
        private const val TAG = "XaiOAuthClient"

        /** Public Grok CLI / Grok Build OIDC client (auth method: none). */
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"
        const val TOKEN_URL = "https://auth.x.ai/oauth2/token"
        const val SCOPE =
            "openid profile email offline_access api:access grok-cli:access"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    }
}
