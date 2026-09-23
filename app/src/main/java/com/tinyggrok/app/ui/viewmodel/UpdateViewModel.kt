package com.tinyggrok.app.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinyggrok.app.BuildConfig
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.repository.AppUpdateRepository
import com.tinyggrok.app.data.repository.AvailableUpdate
import com.tinyggrok.app.data.repository.UpdateCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val autoCheck: Boolean = true,
    val checking: Boolean = false,
    val available: AvailableUpdate? = null,
    /** Offer the update in the chat screen too, unless the user closed it for this version. */
    val showBanner: Boolean = false,
    /** 0..1 while downloading, null otherwise. */
    val downloadProgress: Float? = null,
    /** A one-line result of the last thing done, for the Settings row. */
    val message: String? = null
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updates: AppUpdateRepository,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var work: Job? = null

    init {
        viewModelScope.launch {
            settings.autoUpdateCheck.collect { on -> _state.value = _state.value.copy(autoCheck = on) }
        }
    }

    /** On opening: look once a day at most, and only if the user has not turned it off. */
    fun checkIfDue(nowMs: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            if (!settings.autoUpdateCheck.first()) return@launch
            if (nowMs - settings.lastUpdateCheck.first() < CHECK_INTERVAL_MS) return@launch
            runCheck(quiet = true)
        }
    }

    fun checkNow() {
        viewModelScope.launch { runCheck(quiet = false) }
    }

    private suspend fun runCheck(quiet: Boolean) {
        if (_state.value.checking) return
        _state.value = _state.value.copy(checking = true, message = if (quiet) _state.value.message else null)
        val result = updates.check()
        // A failed look is not a look: try again next time rather than in a day.
        if (result !is UpdateCheck.Failed) settings.saveLastUpdateCheck(System.currentTimeMillis())
        val dismissed = settings.dismissedUpdateVersion.first()
        _state.value = when (result) {
            is UpdateCheck.Available -> _state.value.copy(
                checking = false,
                available = result.update,
                showBanner = result.update.versionName != dismissed,
                message = "Version ${result.update.versionName} is available."
            )
            UpdateCheck.UpToDate -> _state.value.copy(
                checking = false,
                available = null,
                showBanner = false,
                message = "You have the latest version."
            )
            // An automatic check that fails says nothing: there is nothing to act on.
            is UpdateCheck.Failed -> _state.value.copy(
                checking = false,
                message = if (quiet) _state.value.message else "Couldn't check: ${result.message}"
            )
        }
    }

    fun setAutoCheck(on: Boolean) {
        viewModelScope.launch { settings.saveAutoUpdateCheck(on) }
    }

    /** Close the chat banner for this version; Settings still offers it. */
    fun dismissBanner() {
        val version = _state.value.available?.versionName ?: return
        _state.value = _state.value.copy(showBanner = false)
        viewModelScope.launch { settings.saveDismissedUpdateVersion(version) }
    }

    /**
     * Fetch the update and hand it to Android's installer. Android first needs this app
     * to be allowed to install apps, which only the user can grant, so the first time
     * this opens that setting instead and the user taps again once they have allowed it.
     */
    fun downloadAndInstall() {
        val update = _state.value.available ?: return
        if (work?.isActive == true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            _state.value = _state.value.copy(
                message = "Allow Tiny Ggrok to install apps, then tap Install again."
            )
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        work = viewModelScope.launch {
            _state.value = _state.value.copy(downloadProgress = 0f, message = "Downloading ${update.versionName}…")
            try {
                val apk = updates.download(update) { progress ->
                    _state.value = _state.value.copy(downloadProgress = progress)
                }
                _state.value = _state.value.copy(downloadProgress = null, message = "Downloaded. Confirm the install.")
                context.startActivity(updates.installerIntent(apk))
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    downloadProgress = null,
                    message = "Download failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
