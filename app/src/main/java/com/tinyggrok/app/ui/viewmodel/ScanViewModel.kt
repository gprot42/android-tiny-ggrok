package com.tinyggrok.app.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.repository.ResolvedAuth
import com.tinyggrok.app.data.repository.SuperGrokAuthRepository
import com.tinyggrok.app.data.scan.DocumentCornerDetector
import com.tinyggrok.app.data.scan.DocumentCorners
import com.tinyggrok.app.data.scan.NormPoint
import com.tinyggrok.app.data.scan.isPlausibleQuad
import com.tinyggrok.app.data.scan.loadUprightBitmap
import com.tinyggrok.app.data.scan.purgeOldScans
import com.tinyggrok.app.data.scan.refineCorners
import com.tinyggrok.app.data.scan.saveScanJpeg
import com.tinyggrok.app.data.scan.toDetectionJpegBase64
import com.tinyggrok.app.data.scan.toLumaImage
import com.tinyggrok.app.data.scan.warpDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ScanPhase {
    /** Decoding the photo. */
    LOADING,

    /** Grok is locating the page; the corners can already be dragged. */
    DETECTING,

    /** Corners were placed automatically and tightened onto the paper edges. */
    ALIGNED,

    /** No automatic result; the user places the corners. */
    MANUAL,

    /** Producing the straightened page. */
    SAVING
}

data class ScanUiState(
    val photo: Bitmap? = null,
    val corners: DocumentCorners = DocumentCorners.DEFAULT,
    val phase: ScanPhase = ScanPhase.LOADING,
    /** One line explaining the current state, shown above the photo. */
    val note: String = "",
    val error: String? = null
) {
    val canConfirm: Boolean
        get() = photo != null && phase != ScanPhase.LOADING && phase != ScanPhase.SAVING
}

/**
 * Document scanning without Play services: Grok finds the page, the app tightens the
 * corners onto the real paper edges, and the platform's own perspective transform
 * flattens it. The user can always drag the corners, and detection never blocks them.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val detector: DocumentCornerDetector,
    private val authRepository: SuperGrokAuthRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState

    private var detectJob: Job? = null

    /** Set once the user drags, so a late automatic result never overrides their hand. */
    private var userAdjusted = false

    fun start(photoUri: Uri) {
        detectJob?.cancel()
        userAdjusted = false
        _uiState.value = ScanUiState(note = "Opening photo…")

        detectJob = viewModelScope.launch {
            val photo = try {
                withContext(Dispatchers.IO) {
                    purgeOldScans(context)
                    loadUprightBitmap(context, photoUri)
                }
            } catch (e: Exception) {
                _uiState.value = ScanUiState(
                    phase = ScanPhase.MANUAL,
                    error = "Couldn't open the photo: ${e.message ?: "unknown error"}"
                )
                return@launch
            }
            _uiState.value = ScanUiState(photo = photo, phase = ScanPhase.DETECTING, note = FINDING)
            detectAndAlign(photo)
        }
    }

    /** Ask Grok again, e.g. after a failed attempt or an unwanted manual edit. */
    fun redetect() {
        val photo = _uiState.value.photo ?: return
        detectJob?.cancel()
        userAdjusted = false
        _uiState.value = _uiState.value.copy(phase = ScanPhase.DETECTING, note = FINDING, error = null)
        detectJob = viewModelScope.launch { detectAndAlign(photo) }
    }

    private suspend fun detectAndAlign(photo: Bitmap) {
        val auth = authRepository.resolveAuth()
        if (auth !is ResolvedAuth.Ok) {
            manual("Add an xAI API key in Settings for automatic edges. Drag the corners, then Snap.")
            return
        }
        val model = settingsRepository.chatModel.first()
        val debugMode = settingsRepository.debugMode.first()

        val rough = withContext(Dispatchers.Default) {
            val jpeg = photo.toDetectionJpegBase64()
            detector.detect(auth.bearerToken, jpeg, model, debugMode)
        }
        if (rough == null) {
            manual("Couldn't find the edges automatically. Drag the corners, then Snap.")
            return
        }
        // The model is right about where the page is and loose about exactly where its
        // corners are; this is the step that makes the result square.
        val aligned = withContext(Dispatchers.Default) { refineCorners(photo.toLumaImage(), rough) }
        if (userAdjusted) return
        _uiState.value = _uiState.value.copy(
            corners = aligned,
            phase = ScanPhase.ALIGNED,
            note = "Edges aligned. Drag a corner to adjust."
        )
    }

    private fun manual(note: String) {
        if (_uiState.value.phase == ScanPhase.SAVING) return
        _uiState.value = _uiState.value.copy(phase = ScanPhase.MANUAL, note = note)
    }

    fun moveCorner(index: Int, point: NormPoint) {
        val state = _uiState.value
        if (state.phase == ScanPhase.SAVING || state.photo == null) return
        val moved = state.corners.withCorner(
            index,
            NormPoint(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
        )
        // Refuse a drag that would fold the quad over itself; the warp needs a convex one.
        if (!isPlausibleQuad(moved)) return
        userAdjusted = true
        detectJob?.cancel()
        _uiState.value = state.copy(
            corners = moved,
            phase = ScanPhase.MANUAL,
            note = "Tap Snap to pull the corners onto the paper edges."
        )
    }

    /** Tighten the current corners onto the nearest paper edges, entirely on-device. */
    fun snapToEdges() {
        val state = _uiState.value
        val photo = state.photo ?: return
        if (state.phase == ScanPhase.SAVING) return
        viewModelScope.launch {
            val snapped = withContext(Dispatchers.Default) {
                refineCorners(photo.toLumaImage(), state.corners)
            }
            val moved = snapped != state.corners
            _uiState.value = _uiState.value.copy(
                corners = snapped,
                note = if (moved) {
                    "Snapped to the paper edges."
                } else {
                    "No clear edge nearby. Move the corners closer to the page and try again."
                }
            )
        }
    }

    /** Flatten the page and hand back where it was saved. */
    fun confirm(onScanned: (Uri) -> Unit) {
        val state = _uiState.value
        val photo = state.photo ?: return
        if (!state.canConfirm) return
        detectJob?.cancel()
        _uiState.value = state.copy(phase = ScanPhase.SAVING, note = "Straightening…", error = null)

        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    val flat = warpDocument(photo, state.corners)
                    try {
                        saveScanJpeg(context, flat)
                    } finally {
                        flat.recycle()
                    }
                }
                onScanned(Uri.fromFile(file))
            } catch (e: Throwable) {
                // OutOfMemoryError included: a huge photo must not take the app down.
                _uiState.value = _uiState.value.copy(
                    phase = ScanPhase.MANUAL,
                    note = "",
                    error = "Couldn't straighten the page: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    /** Release the photo when the scanner closes. */
    fun reset() {
        detectJob?.cancel()
        detectJob = null
        _uiState.value = ScanUiState()
    }

    private companion object {
        const val FINDING = "Finding the page edges… you can drag the corners meanwhile."
    }
}
