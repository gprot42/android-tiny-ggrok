package com.tinyggrok.app.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.repository.ResolvedAuth
import com.tinyggrok.app.data.repository.SuperGrokAuthRepository
import com.tinyggrok.app.data.scan.DocumentCornerDetector
import com.tinyggrok.app.data.scan.DocumentCorners
import com.tinyggrok.app.data.scan.LumaImage
import com.tinyggrok.app.data.scan.NormPoint
import com.tinyggrok.app.data.scan.locatePage
import com.tinyggrok.app.data.scan.isPlausibleQuad
import com.tinyggrok.app.data.scan.loadUprightBitmap
import com.tinyggrok.app.data.scan.orderCorners
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
import java.io.File
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
 * Document scanning without Play services. The page is looked for on the phone first,
 * which settles the everyday case (paper on a desk, floor or carpet) instantly and
 * offline. Only when that is not confident is Grok asked where the page is. Either way
 * the corners are then tightened onto the real paper edges and the platform's own
 * perspective transform flattens the page. The user can always drag the corners, and
 * detection never blocks them.
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

    /** Grayscale copy of the current photo, built once and reused by every analysis pass. */
    private var lumaOf: Pair<Bitmap, LumaImage>? = null

    private suspend fun lumaFor(photo: Bitmap): LumaImage {
        lumaOf?.takeIf { it.first === photo }?.let { return it.second }
        return withContext(Dispatchers.Default) { photo.toLumaImage() }.also { lumaOf = photo to it }
    }

    /** Set once the user drags, so a late automatic result never overrides their hand. */
    private var userAdjusted = false

    fun start(photoUri: Uri) {
        detectJob?.cancel()
        userAdjusted = false
        flattened = null
        lumaOf = null
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

    /** Ask Grok where the page is: the explicit "try harder" when the outline is wrong. */
    fun askGrok() {
        val photo = _uiState.value.photo ?: return
        detectJob?.cancel()
        userAdjusted = false
        _uiState.value = _uiState.value.copy(phase = ScanPhase.DETECTING, note = ASKING_GROK, error = null)
        detectJob = viewModelScope.launch { alignWithGrok(photo) }
    }

    private suspend fun detectAndAlign(photo: Bitmap) {
        // On the phone first. A light page on a darker surface (or the reverse) is found
        // in milliseconds; making the user wait on a model call for that is absurd, and
        // it would fail outright with no network or no key.
        val luma = lumaFor(photo)
        val local = withContext(Dispatchers.Default) { locatePage(luma) }
        if (local != null) {
            if (userAdjusted) return
            _uiState.value = _uiState.value.copy(
                corners = local,
                phase = ScanPhase.ALIGNED,
                note = ALIGNED
            )
            return
        }
        _uiState.value = _uiState.value.copy(note = ASKING_GROK)
        alignWithGrok(photo)
    }

    private suspend fun alignWithGrok(photo: Bitmap) {
        val auth = authRepository.resolveAuth()
        if (auth !is ResolvedAuth.Ok) {
            manual("Couldn't find the page. Drag the corners near it, then Snap. (An xAI API key lets Grok look too.)")
            return
        }
        val model = settingsRepository.chatModel.first()
        val debugMode = settingsRepository.debugMode.first()

        val rough = withContext(Dispatchers.Default) {
            val jpeg = photo.toDetectionJpegBase64()
            detector.detect(auth.bearerToken, jpeg, model, debugMode)
        }
        if (rough == null) {
            manual("Couldn't find the page. Drag the corners near it, then Snap.")
            return
        }
        // The model is right about where the page is and loose about exactly where its
        // corners are; this is the step that makes the result square.
        val luma = lumaFor(photo)
        val aligned = withContext(Dispatchers.Default) { refineCorners(luma, rough) }
        if (userAdjusted) return
        _uiState.value = _uiState.value.copy(corners = aligned, phase = ScanPhase.ALIGNED, note = ALIGNED)
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
            val luma = lumaFor(photo)
            val snapped = withContext(Dispatchers.Default) { refineCorners(luma, state.corners) }
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

    /**
     * Turn the photo a quarter turn clockwise, outline and all. A phone held flat over a
     * page on a desk or floor cannot tell portrait from landscape, so the capture often
     * arrives sideways; what is shown here is exactly what will be flattened.
     */
    fun rotate() {
        val state = _uiState.value
        val photo = state.photo ?: return
        if (!state.canConfirm) return
        viewModelScope.launch {
            try {
                val turned = withContext(Dispatchers.Default) {
                    Bitmap.createBitmap(
                        photo, 0, 0, photo.width, photo.height,
                        Matrix().apply { postRotate(90f) }, true
                    )
                }
                // A point (x, y) lands at (1 - y, x) after a clockwise quarter turn.
                val corners = orderCorners(state.corners.toList().map { NormPoint(1f - it.y, it.x) })
                lumaOf = null
                flattened = null
                // The previous bitmap is left to the garbage collector: the screen may
                // still be drawing it this frame, and recycling under it would crash.
                _uiState.value = _uiState.value.copy(photo = turned, corners = corners)
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    error = "Couldn't rotate: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    /** Flatten the page for the prompt and hand back where it was saved. */
    fun confirm(onScanned: (Uri) -> Unit) = flattenThen { file -> onScanned(Uri.fromFile(file)) }

    /**
     * Flatten the page for another app. The scanner stays open afterwards, so the same
     * aligned page can go to the prompt as well without scanning twice.
     */
    fun share(onReady: (File) -> Unit) = flattenThen(keepOpen = true, then = onReady)

    /**
     * The straightened page for a given set of corners. Kept so that sharing and then
     * adding to the prompt (or sharing twice) warps the photo once, not once per tap.
     */
    private var flattened: Pair<DocumentCorners, File>? = null

    private fun flattenThen(keepOpen: Boolean = false, then: (File) -> Unit) {
        val state = _uiState.value
        val photo = state.photo ?: return
        if (!state.canConfirm) return
        detectJob?.cancel()
        // Detection is abandoned by this point, so there is no "finding edges" to return to.
        val detecting = state.phase == ScanPhase.DETECTING
        val phaseBefore = if (detecting) ScanPhase.MANUAL else state.phase
        val noteBefore = if (detecting) "" else state.note
        _uiState.value = state.copy(phase = ScanPhase.SAVING, note = "Straightening…", error = null)

        viewModelScope.launch {
            try {
                val cached = flattened?.takeIf { it.first == state.corners && it.second.exists() }
                val file = cached?.second ?: withContext(Dispatchers.Default) {
                    val flat = warpDocument(photo, state.corners)
                    try {
                        saveScanJpeg(context, flat)
                    } finally {
                        flat.recycle()
                    }
                }.also { flattened = state.corners to it }

                if (keepOpen) {
                    _uiState.value = _uiState.value.copy(phase = phaseBefore, note = noteBefore)
                }
                then(file)
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
        flattened = null
        lumaOf = null
        _uiState.value = ScanUiState()
    }

    private companion object {
        const val FINDING = "Finding the page…"
        const val ASKING_GROK = "Asking Grok to find the page… you can drag the corners meanwhile."
        const val ALIGNED = "Edges aligned. Drag a corner to adjust."
    }
}
