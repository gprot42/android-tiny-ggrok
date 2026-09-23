package com.tinyggrok.app.data.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.tinyggrok.app.BuildConfig
import com.tinyggrok.app.data.share.FILE_PROVIDER_AUTHORITY
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A release newer than the one running, and the file to fetch for it. */
data class AvailableUpdate(
    val versionName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val notes: String,
    val releaseUrl: String
)

sealed class UpdateCheck {
    data class Available(val update: AvailableUpdate) : UpdateCheck()
    data object UpToDate : UpdateCheck()
    data class Failed(val message: String) : UpdateCheck()
}

/**
 * Offers the newest build from the project's own GitHub releases.
 *
 * The app is installed by hand from a release page, so nothing tells anyone that a newer
 * build exists; this asks. Only the release list is read, over HTTPS, without credentials
 * — it is a public repository — and nothing is downloaded or installed without the user
 * saying so. Android then asks for its own confirmation, and refuses outright unless the
 * new file is signed with the same key as the copy already installed, which is the part
 * that makes downloading an APK safe: a substituted file cannot install over this app.
 */
@Singleton
class AppUpdateRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {

    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): UpdateCheck =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(LATEST_RELEASE_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "TinyGgrok/$currentVersion")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext UpdateCheck.Failed(
                            "GitHub said ${response.code} when asked for the latest release."
                        )
                    }
                    val release = parseLatestRelease(body)
                        ?: return@withContext UpdateCheck.Failed("No installable release found.")
                    if (!isNewerVersion(currentVersion, release.versionName)) {
                        UpdateCheck.UpToDate
                    } else {
                        UpdateCheck.Available(release)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Update check failed: ${e.javaClass.simpleName}: ${e.message}")
                UpdateCheck.Failed(e.message ?: "Couldn't reach GitHub.")
            }
        }

    /** Download the release APK into the cache. Reports progress 0..1 as it goes. */
    suspend fun download(update: AvailableUpdate, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
            // One build at a time: a half-finished earlier download is of no use.
            directory.listFiles()?.forEach { it.delete() }
            val target = File(directory, "tiny-ggrok-${update.versionName}.apk")

            val request = Request.Builder().url(update.downloadUrl)
                .header("User-Agent", "TinyGgrok/${BuildConfig.VERSION_NAME}")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body ?: throw IllegalStateException("Empty download")
                if (!response.isSuccessful) throw IllegalStateException("Download failed (${response.code})")
                val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            target
        }

    /**
     * Hand the downloaded build to Android's package installer. It shows its own
     * confirmation and does the signature check; this app never installs anything itself.
     */
    fun installerIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private companion object {
        const val TAG = "AppUpdateRepository"
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/gprot42/android-tiny-ggrok/releases/latest"
        const val UPDATE_DIR = "updates"
    }
}

/**
 * Whether [candidate] is a later version than [current], comparing the numbers in turn
 * (so 0.0.9 precedes 0.0.10, which sorting them as text gets wrong). Anything that is not
 * a run of dotted numbers counts as not newer, so a tag the project does not use cannot
 * push an update at anyone.
 */
internal fun isNewerVersion(current: String, candidate: String): Boolean {
    val here = versionParts(current) ?: return false
    val there = versionParts(candidate) ?: return false
    for (i in 0 until maxOf(here.size, there.size)) {
        val a = here.getOrElse(i) { 0 }
        val b = there.getOrElse(i) { 0 }
        if (b != a) return b > a
    }
    return false
}

private fun versionParts(version: String): List<Int>? {
    val trimmed = version.trim().removePrefix("v").trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(".")
    return parts.map { it.toIntOrNull() ?: return null }
}

/**
 * The installable build in GitHub's description of a release: the universal APK, which
 * runs on any phone. Null for a draft, or a release with no such file (a tag pushed
 * before its build was attached must not send anyone to download nothing).
 */
internal fun parseLatestRelease(json: String): AvailableUpdate? {
    val release = JsonParser.parseString(json).asJsonObject
    if (release.get("draft")?.asBoolean == true) return null
    val tag = release.get("tag_name")?.asString ?: return null
    val asset = release.getAsJsonArray("assets")?.firstOrNull { element ->
        val name = element.asJsonObject.get("name")?.asString.orEmpty()
        name.endsWith(".apk") && "universal" in name
    }?.asJsonObject ?: return null
    return AvailableUpdate(
        versionName = tag.removePrefix("v"),
        downloadUrl = asset.get("browser_download_url")?.asString ?: return null,
        sizeBytes = asset.get("size")?.asLong ?: 0L,
        notes = release.get("name")?.asString.orEmpty(),
        releaseUrl = release.get("html_url")?.asString.orEmpty()
    )
}
