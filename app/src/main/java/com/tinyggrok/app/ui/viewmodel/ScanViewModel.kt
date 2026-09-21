package com.tinyggrok.app.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.repository.DebugLogRepository
import com.tinyggrok.app.data.repository.ResolvedAuth
import com.tinyggrok.app.data.repository.SuperGrokAuthRepository
import com.tinyggrok.app.data.scan.Capture
import com.tinyggrok.app.data.scan.DocumentCornerDetector
import com.tinyggrok.app.data.scan.DocumentCorners
import com.tinyggrok.app.data.scan.NormPoint
import com.tinyggrok.app.data.scan.PageViews
import com.tinyggrok.app.data.scan.holdsPrint
import com.tinyggrok.app.data.scan.locatePage
import com.tinyggrok.app.data.scan.looksLikePrint
import com.tinyggrok.app.data.scan.isPlausibleQuad
import com.tinyggrok.app.data.scan.SCAN_PROMPT_MAX_SIDE
import com.tinyggrok.app.data.scan.cleanEdges
import com.tinyggrok.app.data.scan.enhanceDocument
import com.tinyggrok.app.data.scan.SCAN_OUTPUT_MAX_SIDE
import com.tinyggrok.app.data.scan.flattenFromCapture
import com.tinyggrok.app.data.scan.flattenedSize
import com.tinyggrok.app.data.scan.loadCapture
import com.tinyggrok.app.data.scan.promptSizedCopy
import com.tinyggrok.app.data.scan.quadArea
import com.tinyggrok.app.data.scan.orderCorners
import com.tinyggrok.app.data.scan.purgeOldScans
import com.tinyggrok.app.data.scan.refineCorners
import com.tinyggrok.app.data.scan.saveScanJpeg
import com.tinyggrok.app.data.scan.TEXT_LUMA_MAX_SIDE
import com.tinyggrok.app.data.scan.squareUp
import com.tinyggrok.app.data.scan.straightenByText
import com.tinyggrok.app.data.scan.toDetectionJpegBase64
import com.tinyggrok.app.data.scan.toLumaImage
import com.tinyggrok.app.data.scan.toPageViews
import com.tinyggrok.app.data.scan.warpDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /**
     * The page as it will be saved: cut out, straightened and (optionally) enhanced. When
     * set, the screen shows this instead of the outline editor. Without it the straightening
     * only ever happened invisibly at the moment of sharing, and the editor, which shows the
     * original tilted photo with an outline on it, looked as if it found the borders and
     * then never aligned anything.
     */
    val result: Bitmap? = null,
    /** Whether the saved page is cleaned up to read like a scan (white paper, dark ink). */
    val enhance: Boolean = true,
    /**
     * Plain facts about what is being worked with: the photo's size as the camera stored
     * it, and the size the page will come out at. Sharpness complaints are impossible to
     * reason about without these, and they answer "should I move closer?" at a glance.
     */
    val facts: String = "",
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
    private val debugLog: DebugLogRepository,
    private val detector: DocumentCornerDetector,
    private val authRepository: SuperGrokAuthRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())

    /** The state as the screen sees it, with [ScanUiState.facts] always matching the corners. */
    val uiState: StateFlow<ScanUiState> = _uiState
        .map { if (it.photo == null) it else it.copy(facts = factsFor(it.corners)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScanUiState())

    private var detectJob: Job? = null

    /** The capture behind the preview: where the final page is cut from, at full detail. */
    private var captureUri: Uri? = null
    private var capture: Capture? = null

    /** Quarter turns the user has added on top of the capture's own EXIF rotation. */
    private var userTurn = 0

    /** Edge-finding views of the current photo, built once and reused by every analysis pass. */
    private var viewsOf: Pair<Bitmap, PageViews>? = null

    /**
     * Let the print have the final say on what is level. Paper edges are what the outline
     * is fitted to, and they can mislead (a plastic sleeve, a sheet underneath, a printer
     * that laid the text slightly askew); see [straightenByText].
     */
    private suspend fun levelToPrint(photo: Bitmap, views: PageViews, corners: DocumentCorners): DocumentCorners =
        withContext(Dispatchers.Default) {
            try {
                // Only what is printed on paper has lines that ought to be level. On a
                // cover or a photograph the nearest thing to lines of print is a row of
                // sequins or a horizon. This asks what the page *is*, not how it was
                // found: a newspaper half on pale carpet is found by its edges alone, as
                // a cover is, and came out with its print two degrees askew when that was
                // taken to mean it had none.
                if (!holdsPrint(views.colour, corners)) return@withContext corners
                straightenByText(photo.toLumaImage(TEXT_LUMA_MAX_SIDE), corners)
            } catch (e: Throwable) {
                Log.w(TAG, "Levelling to print skipped: ${e.javaClass.simpleName}: ${e.message}")
                corners
            }
        }

    private suspend fun viewsFor(photo: Bitmap): PageViews {
        viewsOf?.takeIf { it.first === photo }?.let { return it.second }
        return withContext(Dispatchers.Default) { photo.toPageViews() }.also { viewsOf = photo to it }
    }

    /** Set once the user drags, so a late automatic result never overrides their hand. */
    private var userAdjusted = false

    fun start(photoUri: Uri) {
        detectJob?.cancel()
        userAdjusted = false
        flattened = null
        viewsOf = null
        capture = null
        captureUri = null
        userTurn = 0
        enhanceChoice = null
        _uiState.value = ScanUiState(note = "Opening photo…", enhance = true)

        detectJob = viewModelScope.launch {
            val photo = try {
                withContext(Dispatchers.IO) {
                    purgeOldScans(context)
                    loadCapture(context, photoUri)
                }.also {
                    capture = it
                    captureUri = photoUri
                }.preview
            } catch (e: Exception) {
                _uiState.value = ScanUiState(
                    phase = ScanPhase.MANUAL,
                    enhance = true,
                    error = "Couldn't open the photo: ${e.message ?: "unknown error"}"
                )
                return@launch
            }
            _uiState.value = ScanUiState(
                photo = photo,
                phase = ScanPhase.DETECTING,
                note = FINDING,
                enhance = true
            )
            detectAndAlign(photo)
        }
    }

    /** Ask Grok where the page is: the explicit "try harder" when the outline is wrong. */
    fun askGrok() {
        val photo = _uiState.value.photo ?: return
        detectJob?.cancel()
        userAdjusted = false
        _uiState.value = _uiState.value.copy(
            phase = ScanPhase.DETECTING,
            note = ASKING_GROK,
            error = null,
            result = null
        )
        detectJob = viewModelScope.launch { alignWithGrok(photo) }
    }

    private suspend fun detectAndAlign(photo: Bitmap) {
        // On the phone first. A light page on a darker surface (or the reverse) is found
        // in milliseconds; making the user wait on a model call for that is absurd, and
        // it would fail outright with no network or no key.
        val views = viewsFor(photo)
        val local = withContext(Dispatchers.Default) { locatePage(views) }?.let { levelToPrint(photo, views, it) }
        if (local != null) {
            if (userAdjusted) return
            _uiState.value = _uiState.value.copy(
                corners = local,
                phase = ScanPhase.ALIGNED,
                note = alignedNote(local)
            )
            align()
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
        val views = viewsFor(photo)
        val aligned = withContext(Dispatchers.Default) {
            // Same treatment as the on-device route, so a page running out of frame gets
            // its missing sides reconstructed. Grok saw the whole scene, so if its outline
            // cannot be verified it is still used, merely tightened where edges are found.
            squareUp(views, rough) ?: refineCorners(views, rough)
        }.let { levelToPrint(photo, views, it) }
        if (userAdjusted) return
        _uiState.value = _uiState.value.copy(
            corners = aligned,
            phase = ScanPhase.ALIGNED,
            note = alignedNote(aligned)
        )
        align()
    }

    /**
     * A page that fills little of the frame cannot come out sharp however it is processed:
     * the detail was never captured. Say so while retaking is still one tap away.
     */
    private fun alignedNote(corners: DocumentCorners): String =
        if (quadArea(corners) < SMALL_PAGE_AREA) ALIGNED_BUT_SMALL else ALIGNED

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
            result = null,
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
            val views = viewsFor(photo)
            val onEdges = withContext(Dispatchers.Default) { refineCorners(views, state.corners) }
            val moved = onEdges != state.corners
            // Only level to the print once the corners are actually on the page.
            val snapped = if (moved) levelToPrint(photo, views, onEdges) else onEdges
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
                viewsOf = null
                flattened = null
                userTurn = (userTurn + 90) % 360
                // The previous bitmap is left to the garbage collector: the screen may
                // still be drawing it this frame, and recycling under it would crash.
                val showing = _uiState.value.result != null
                _uiState.value = _uiState.value.copy(photo = turned, corners = corners, result = null)
                if (showing) align()
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    error = "Couldn't rotate: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * Flatten the page for the prompt and hand back where it was saved. The prompt gets a
     * copy capped for upload size; the full-detail file is what Share sends.
     */
    fun confirm(onScanned: (Uri) -> Unit) = flattenThen { file ->
        viewModelScope.launch {
            val forPrompt = withContext(Dispatchers.Default) {
                runCatching { promptSizedCopy(context, file) }.getOrDefault(file)
            }
            onScanned(Uri.fromFile(forPrompt))
        }
    }

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
    private var flattenedEnhanced = true

    /** The last page was judged to be mostly pictures, so it was not enhanced. */
    private var leftAsShot = false

    /** Whether the last page came from the original photo or had to fall back, and why. */
    private var flattenedHow = ""

    /**
     * What the user asked for on this page, or null to let the page decide: print on
     * paper is enhanced, a page that is mostly pictures is left as shot (see
     * [looksLikePrint]). Forgotten with each new photo, because it is a judgement about
     * one page, not a setting.
     */
    private var enhanceChoice: Boolean? = null

    fun toggleEnhance() {
        val wanted = !_uiState.value.enhance
        enhanceChoice = wanted
        val showing = _uiState.value.result != null
        _uiState.value = _uiState.value.copy(enhance = wanted)
        if (showing) align()
    }

    /**
     * Produce the page for the current outline and show it. This is the file that Share
     * and To prompt will use, so what is on screen is exactly what gets sent.
     */
    fun align() {
        val state = _uiState.value
        if (state.photo == null || !state.canConfirm) return
        flattenThen(keepOpen = true) { file ->
            viewModelScope.launch {
                val shown = withContext(Dispatchers.Default) { decodeForDisplay(file) }
                if (shown == null) {
                    _uiState.value = _uiState.value.copy(error = "Couldn't show the aligned page.")
                } else {
                    _uiState.value = _uiState.value.copy(
                        result = shown,
                        note = when {
                            flattenedHow.startsWith("REDUCED") -> _uiState.value.note
                            leftAsShot -> ALIGNED_AS_SHOT
                            else -> ALIGNED_RESULT
                        }
                    )
                }
            }
        }
    }

    /** Back to the outline editor, keeping the corners as they are. */
    fun adjustCorners() {
        _uiState.value = _uiState.value.copy(
            result = null,
            note = "Drag a corner, or tap Snap. Then Align."
        )
    }

    private fun decodeForDisplay(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DISPLAY_MAX_SIDE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Cut the page out of the capture as stored, not out of the reduced preview. If that
     * fails for any reason (an odd file format, not enough memory for a huge capture),
     * fall back to the preview: a softer scan beats no scan.
     */
    private fun flattenAtBestResolution(preview: Bitmap, corners: DocumentCorners): Bitmap {
        val uri = captureUri
        val source = capture
        if (uri != null && source != null) {
            try {
                return flattenFromCapture(
                    context = context,
                    uri = uri,
                    capture = source,
                    corners = corners,
                    turned = source.exifRotation + userTurn
                ).also { flattenedHow = "from the original photo" }
            } catch (e: Throwable) {
                Log.w(TAG, "Full-resolution flatten failed, using preview: ${e.javaClass.simpleName}: ${e.message}")
                flattenedHow = "REDUCED, from the preview, because ${e.javaClass.simpleName}: ${e.message}"
            }
        } else {
            flattenedHow = "REDUCED, from the preview, because the original photo was not available"
        }
        return warpDocument(preview, corners, SCAN_PROMPT_MAX_SIDE)
    }

    private fun flattenThen(keepOpen: Boolean = false, then: (File) -> Unit) {
        val state = _uiState.value
        val photo = state.photo ?: return
        if (!state.canConfirm) return
        detectJob?.cancel()
        // Detection is abandoned by this point, so there is no "finding edges" to return to.
        val detecting = state.phase == ScanPhase.DETECTING
        val phaseBefore = if (detecting) ScanPhase.MANUAL else state.phase
        val noteBefore = if (detecting) "" else state.note
        _uiState.value = state.copy(
            phase = ScanPhase.SAVING,
            note = if (enhanceChoice == true) "Straightening and enhancing…" else "Straightening…",
            error = null
        )

        viewModelScope.launch {
            try {
                val choice = enhanceChoice
                val cached = flattened?.takeIf {
                    it.first == state.corners && (choice == null || choice == flattenedEnhanced) && it.second.exists()
                }
                var enhanced = flattenedEnhanced
                val file = cached?.second ?: withContext(Dispatchers.Default) {
                    val flat = flattenAtBestResolution(photo, state.corners)
                    try {
                        enhanced = choice ?: looksLikePrint(flat)
                        if (enhanced) {
                            // Cosmetic: if it cannot run, the plain page is still a good scan.
                            try {
                                enhanceDocument(flat)
                                cleanEdges(flat)
                            } catch (e: Throwable) {
                                Log.w(TAG, "Enhancement skipped: ${e.javaClass.simpleName}: ${e.message}")
                            }
                        }
                        saveScanJpeg(context, flat)
                    } finally {
                        flat.recycle()
                    }
                }.also {
                    flattened = state.corners to it
                    flattenedEnhanced = enhanced
                }
                // Show what was actually done, which the page may have decided.
                _uiState.value = _uiState.value.copy(enhance = enhanced)
                leftAsShot = choice == null && !enhanced

                // Never let a quality fallback pass silently again: say what was saved.
                val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    .also { BitmapFactory.decodeFile(file.path, it) }
                val summary = "Saved ${size.outWidth} × ${size.outHeight} px, $flattenedHow."
                debugLog.logIncoming(
                    summary = "SCAN ${size.outWidth}×${size.outHeight} | enhance=$enhanced" +
                        if (choice == null) " (decided by the page)" else "",
                    body = "$summary\n${factsFor(state.corners)}\n" +
                        "exif=${capture?.exifRotation} userTurn=$userTurn file=${file.name} (${file.length() / 1024} KB)"
                )
                if (keepOpen) {
                    _uiState.value = _uiState.value.copy(
                        phase = phaseBefore,
                        note = if (flattenedHow.startsWith("REDUCED")) summary else noteBefore
                    )
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

    /** "Photo 4080 × 3072 · page about 2100 × 2900 px", for the current corners. */
    private fun factsFor(corners: DocumentCorners): String {
        val source = capture ?: return ""
        val sideways = ((source.exifRotation + userTurn) / 90) % 2 != 0
        val shownW = if (sideways) source.storedHeight else source.storedWidth
        val shownH = if (sideways) source.storedWidth else source.storedHeight
        val (pageW, pageH) = flattenedSize(corners, shownW, shownH, SCAN_OUTPUT_MAX_SIDE)
        return "Photo ${source.storedWidth} × ${source.storedHeight} · page about $pageW × $pageH px"
    }

    /** Release the photo when the scanner closes. */
    fun reset() {
        detectJob?.cancel()
        detectJob = null
        flattened = null
        viewsOf = null
        capture = null
        captureUri = null
        userTurn = 0
        _uiState.value = ScanUiState(enhance = true)
    }

    private companion object {
        const val TAG = "ScanViewModel"

        const val FINDING = "Finding the page…"
        const val ASKING_GROK = "Asking Grok to find the page… you can drag the corners meanwhile."
        const val ALIGNED = "Edges aligned. Drag a corner to adjust."
        const val ALIGNED_RESULT = "Aligned. Pinch to zoom in and check it."
        const val ALIGNED_AS_SHOT =
            "Aligned. This looks like a cover or a photo, so its colours are left as shot; Enhance is for print."

        /** Longest side of the aligned page as held for the screen. */
        const val DISPLAY_MAX_SIDE = 2048
        const val ALIGNED_BUT_SMALL =
            "Edges aligned. For a sharper scan, retake closer so the page fills the frame."

        /** Below this share of the frame, the page is small enough to be worth a retake. */
        const val SMALL_PAGE_AREA = 0.45f
    }
}
