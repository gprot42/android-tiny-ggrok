package com.tinyggrok.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.tinyggrok.app.data.share.formatConversationForShare
import com.tinyggrok.app.data.share.htmlToPlainText
import com.tinyggrok.app.data.share.startPlainTextShare
import com.tinyggrok.app.ui.viewmodel.AttachedImage
import com.tinyggrok.app.ui.viewmodel.ChatUiMessage
import com.tinyggrok.app.ui.viewmodel.ChatViewModel
import com.tinyggrok.app.ui.viewmodel.MAX_ATTACHED_IMAGES
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToDebugLogs: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToVoiceTranslator: () -> Unit = {},
    onNavigateToUsage: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Stable heights for assistant WebViews, keyed by message id. Without this,
    // LazyColumn recycles items at ~0 height and jumps when content re-measures —
    // the main cause of jarring scroll across previous results.
    val webViewHeights = remember { mutableStateMapOf<String, Int>() }

    // Stick to bottom only when the user is already near it (or on first load).
    // If they scroll up to read earlier results, new replies won't yank them down.
    var stickToBottom by remember { mutableStateOf(true) }

    // Whole-screen pinch-to-zoom state (applies to the entire chat content, not just LLM output)
    var screenScale by remember { mutableStateOf(1f) }
    var screenOffset by remember { mutableStateOf(Offset.Zero) }
    var contentSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // Clamp the pan offset so the scaled content can be moved fully into view
    // (top-left transform origin means valid translation is negative, 0..-(scale-1)*size).
    fun clampOffset(offset: Offset, scale: Float): Offset {
        if (scale <= 1f) return Offset.Zero
        val maxX = (scale - 1f) * contentSize.width
        val maxY = (scale - 1f) * contentSize.height
        return Offset(
            x = offset.x.coerceIn(-maxX, 0f),
            y = offset.y.coerceIn(-maxY, 0f)
        )
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.attachImages(uris)
        }
    }

    // Ask for location once when GPS is on and the user sends (then send either way).
    var locationPermissionAsked by remember { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.sendPrompt()
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun sendWithOptionalLocationPermission() {
        if (uiState.locationEnabled &&
            !hasLocationPermission() &&
            !locationPermissionAsked
        ) {
            locationPermissionAsked = true
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            viewModel.sendPrompt()
        }
    }

    // Track whether the user is near the bottom so we only auto-follow new content
    // when they already are (avoids jarring jumps while browsing earlier results).
    // Sample only when the list is idle — mid-scroll "near bottom" is unreliable,
    // and sampling only on nearBottom changes can miss the idle-after-scroll-up case.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.isNearBottom() }
            .distinctUntilChanged()
            .collect { (scrolling, nearBottom) ->
                if (!scrolling) {
                    stickToBottom = nearBottom
                }
            }
    }

    // Scroll to the sentinel (true bottom) when new messages arrive / typing toggles,
    // but only if the user is already following the bottom of the list.
    LaunchedEffect(Unit) {
        snapshotFlow {
            Triple(
                uiState.messages.size,
                uiState.isSending,
                uiState.messages.lastOrNull()?.id
            )
        }
            .distinctUntilChanged()
            .filter { (size, isSending, _) -> size > 0 || isSending }
            .collect { (size, isSending, _) ->
                // Sending always re-engages follow mode (user just submitted a prompt).
                if (isSending) stickToBottom = true
                if (!stickToBottom) return@collect
                val sentinelIndex = size + (if (isSending) 1 else 0)
                // Instant jump: animateScrollToItem fights WebView height growth and
                // feels more jarring when a tall answer lands.
                delay(16)
                listState.scrollToItem(sentinelIndex)
                stickToBottom = true
            }
    }

    // When the last assistant message finishes measuring its WebView height, pin
    // back to the bottom so the newly expanded answer doesn't leave a gap.
    val lastAssistantId = uiState.messages.lastOrNull { it.role == "assistant" }?.id
    val lastAssistantHeight = lastAssistantId?.let { webViewHeights[it] }
    LaunchedEffect(lastAssistantId, lastAssistantHeight, uiState.isSending) {
        if (!stickToBottom || lastAssistantHeight == null || lastAssistantHeight <= 0) return@LaunchedEffect
        val sentinelIndex = uiState.messages.size + (if (uiState.isSending) 1 else 0)
        listState.scrollToItem(sentinelIndex)
    }

    // Drop height cache entries for messages that are no longer in the list.
    LaunchedEffect(uiState.messages) {
        if (uiState.messages.isEmpty()) {
            webViewHeights.clear()
        } else {
            val liveIds = uiState.messages.map { it.id }.toSet()
            webViewHeights.keys.retainAll(liveIds)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Tiny Ggrok") },
                actions = {
                    if (uiState.messages.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearMessages) {
                            Text("Clear")
                        }
                    }
                    TextButton(onClick = onNavigateToVoiceTranslator) {
                        Text("Voice")
                    }
                    TextButton(onClick = onNavigateToUsage) {
                        Text("Credits")
                    }
                    if (uiState.debugMode) {
                        TextButton(onClick = onNavigateToDebugLogs) {
                            Text("Logs")
                        }
                    }
                    TextButton(onClick = onNavigateToSettings) {
                        Text("Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .onSizeChanged { contentSize = it }
                // Two-finger pinch zooms the whole screen. Once zoomed in, a single
                // finger pans the content in any direction (left/right/up/down) so the
                // user can reach everything; at 1x, single-finger gestures pass through
                // to the list for normal scrolling & text selection.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val pointers = event.changes.count { it.pressed }
                            if (pointers >= 2) {
                                // Pinch: zoom around the centroid (the point between the
                                // user's fingers) so the area they're focused on stays put,
                                // instead of always scaling from the top-left corner.
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val oldScale = screenScale
                                val newScale = (oldScale * zoom).coerceIn(1f, 4f)
                                val scaleFactor = newScale / oldScale
                                // Keep the content point under the centroid fixed, then apply pan.
                                val focusedOffset = Offset(
                                    x = centroid.x - scaleFactor * (centroid.x - screenOffset.x),
                                    y = centroid.y - scaleFactor * (centroid.y - screenOffset.y)
                                )
                                screenScale = newScale
                                screenOffset = clampOffset(focusedOffset + pan, newScale)
                                event.changes.forEach { it.consume() }
                            } else if (pointers == 1 && screenScale > 1f) {
                                // Single-finger pan while zoomed in
                                if (pan != Offset.Zero) {
                                    screenOffset = clampOffset(screenOffset + pan, screenScale)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    scaleX = screenScale
                    scaleY = screenScale
                    translationX = screenOffset.x
                    translationY = screenOffset.y
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Ask Grok something, or attach an image.")
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.messages, key = { it.id }) { message ->
                            MessageItem(
                                message = message,
                                showCost = uiState.showCost,
                                responseFormat = uiState.responseFormat,
                                fontSize = uiState.fontSize,
                                cachedWebViewHeightPx = webViewHeights[message.id] ?: 0,
                                onWebViewHeight = { h ->
                                    if (h > 0 && webViewHeights[message.id] != h) {
                                        webViewHeights[message.id] = h
                                    }
                                },
                                onShare = { msg ->
                                    startPlainTextShare(
                                        context,
                                        shareableMessageText(msg),
                                        "Share response"
                                    )
                                }
                            )
                        }
                        if (uiState.isSending) {
                            item {
                                TypingIndicator()
                            }
                        }
                        // Sentinel: always the last item so scrollToItem(sentinelIndex) reaches
                        // the true bottom regardless of how tall the last message is.
                        item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
                    }
                }
            }

            uiState.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Multi-image attachment previews
            if (uiState.attachedImages.isNotEmpty()) {
                AttachedImagesRow(
                    images = uiState.attachedImages,
                    onRemove = viewModel::removeImage
                )
            }

            // Prompt stays editable while a reply is in flight so the user can draft
            // the next question. Send/Resend stay disabled until the current request finishes.
            OutlinedTextField(
                value = uiState.prompt,
                onValueChange = viewModel::updatePrompt,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(if (uiState.isSending) "Next prompt (waiting for reply…)" else "Prompt")
                },
                minLines = 2,
                maxLines = 4,
                enabled = true,
                trailingIcon = {
                    IconButton(
                        onClick = { imagePicker.launch("image/*") },
                        enabled = uiState.attachedImages.size < MAX_ATTACHED_IMAGES
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = "Attach images"
                        )
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onNavigateToHistory) {
                        Text("History")
                    }
                    TextButton(
                        onClick = {
                            val text = formatConversationForShare(
                                uiState.messages.map { it.role to shareableMessageText(it) }
                            )
                            startPlainTextShare(context, text, "Share conversation")
                        },
                        enabled = uiState.messages.isNotEmpty()
                    ) {
                        Text("Share")
                    }
                }

                // Right side: prompt actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val copyText = uiState.prompt.ifEmpty { uiState.lastSentPrompt }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(copyText))
                            Toast.makeText(context, "Prompt copied", Toast.LENGTH_SHORT).show()
                        },
                        enabled = copyText.isNotEmpty()
                    ) {
                        Text("Copy")
                    }
                    TextButton(
                        onClick = {
                            val last = uiState.lastSentPrompt
                            if (last.isNotBlank() && !uiState.isSending) {
                                viewModel.updatePrompt(last)
                                sendWithOptionalLocationPermission()
                            }
                        },
                        enabled = uiState.lastSentPrompt.isNotBlank() && !uiState.isSending
                    ) {
                        Text("Resend")
                    }
                    TextButton(
                        onClick = viewModel::clearPrompt,
                        enabled = uiState.prompt.isNotEmpty() || uiState.hasAttachedImages
                    ) {
                        Text("Clear")
                    }
                    Button(
                        onClick = { sendWithOptionalLocationPermission() },
                        modifier = Modifier.padding(start = 8.dp),
                        enabled = (uiState.prompt.isNotBlank() || uiState.hasAttachedImages) &&
                            !uiState.isSending
                    ) {
                        Text(if (uiState.isSending) "Sending..." else "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachedImagesRow(
    images: List<AttachedImage>,
    onRemove: (Uri) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (images.size == 1) "1 image attached" else "${images.size} images attached",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(images, key = { it.uri.toString() }) { image ->
                Card(shape = RoundedCornerShape(8.dp)) {
                    Box {
                        AsyncImage(
                            model = image.uri,
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .size(96.dp)
                                .padding(0.dp),
                            contentScale = ContentScale.Crop
                        )
                        IconButton(
                            onClick = { onRemove(image.uri) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove image",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val alpha3 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Grok",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha1)
        ) {}
        Spacer(modifier = Modifier.width(4.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha2)
        ) {}
        Spacer(modifier = Modifier.width(4.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha3)
        ) {}
    }
}

@Composable
private fun MessageItem(
    message: ChatUiMessage,
    showCost: Boolean,
    responseFormat: String,
    fontSize: Float,
    cachedWebViewHeightPx: Int = 0,
    onWebViewHeight: (Int) -> Unit = {},
    onShare: ((ChatUiMessage) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    val isAssistant = message.role == "assistant"
    // Full-screen viewer for one of this message's images. A Dialog, so it sits above
    // the chat and is unaffected by the screen's pinch-zoom transform.
    var viewerUri by remember(message.id) { mutableStateOf<Uri?>(null) }

    viewerUri?.let { uri ->
        ImageViewerDialog(uri = uri, onDismiss = { viewerUri = null })
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isAssistant) "Grok" else "You",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (isAssistant) {
                    val modelLabel = message.model
                        ?.replaceFirstChar { it.uppercaseChar() }
                        ?.replace("-", " ")
                        ?: "Grok"
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (message.usedWebSearch) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "Web search",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            if (isAssistant) {
                val context = LocalContext.current
                IconButton(
                    onClick = { onShare?.invoke(message) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline)
                }
                TextButton(
                    onClick = {
                        // Strip any HTML tags so the clipboard receives clean plain text
                        val plain = htmlToPlainText(message.content)
                        clipboard.setText(AnnotatedString(plain))
                        Toast.makeText(context, "Response copied", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Copy")
                }
            }
        }
        if (message.hasImage && !isAssistant) {
            SentImagesRow(
                uris = message.imageUris,
                onOpen = { viewerUri = it }
            )
        }
        if (isAssistant) {
            val htmlContent = if (responseFormat == "markdown") {
                markdownToHtml(message.content)
            } else {
                message.content
            }
            HtmlContent(
                html = htmlContent,
                fontSize = fontSize,
                cachedHeightPx = cachedWebViewHeightPx,
                onHeightMeasured = onWebViewHeight
            )
            SourcesList(urls = message.citations)
        } else if (!message.isImageOnly || message.imageUris.isEmpty()) {
            // Compose Text layouts the full string on the main thread. Cap display so a
            // huge paste cannot ANR the same way debug-log bodies used to.
            val display = if (message.content.length > 20_000) {
                message.content.take(20_000) + "…"
            } else {
                message.content
            }
            Text(
                text = display,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp)
            )
        }
        if (showCost && isAssistant && message.costInfo != null) {
            Text(
                text = message.costInfo.formatted(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Thumbnails of the images sent with a prompt, so they stay visible in the transcript
 * instead of collapsing to a placeholder icon once the composer strip is cleared.
 */
@Composable
private fun SentImagesRow(
    uris: List<Uri>,
    onOpen: (Uri) -> Unit
) {
    if (uris.isEmpty()) return

    val fallback = rememberVectorPainter(Icons.Outlined.Image)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp)
    ) {
        itemsIndexed(uris) { index, uri ->
            Card(shape = RoundedCornerShape(8.dp)) {
                AsyncImage(
                    model = uri,
                    contentDescription = if (uris.size == 1) {
                        "Attached image, tap to view"
                    } else {
                        "Attached image ${index + 1} of ${uris.size}, tap to view"
                    },
                    contentScale = ContentScale.Crop,
                    error = fallback,
                    fallback = fallback,
                    modifier = Modifier
                        .size(88.dp)
                        .clickable { onOpen(uri) }
                )
            }
        }
    }
}

/** Full-screen image viewer: pinch to zoom, drag to pan, tap to close. */
@Composable
private fun ImageViewerDialog(uri: Uri, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 6f)
                        scale = newScale
                        // Panning only means anything while zoomed in.
                        offset = if (newScale <= 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // Tapping while zoomed would close by accident; reset instead.
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                onDismiss()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val fallback = rememberVectorPainter(Icons.Outlined.Image)
            AsyncImage(
                model = uri,
                contentDescription = "Attached image",
                contentScale = ContentScale.Fit,
                error = fallback,
                fallback = fallback,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close image",
                    tint = Color.White
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourcesList(urls: List<String>) {
    if (urls.isEmpty()) return

    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uniqueUrls = urls.filter { it.isNotBlank() }.distinct()
    if (uniqueUrls.isEmpty()) return

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = "Sources",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Tap to open · hold to copy",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        uniqueUrls.forEachIndexed { index, url ->
            Text(
                text = "${index + 1}. $url",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            try {
                                uriHandler.openUri(url)
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    "No app can open this link",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onLongClick = {
                            clipboard.setText(AnnotatedString(url))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        }
                    )
                    .padding(vertical = 4.dp)
            )
        }
    }
}

private fun shareableMessageText(message: ChatUiMessage): String {
    val body = htmlToPlainText(message.content)
    val sources = message.citations.filter { it.isNotBlank() }.distinct()
    return if (sources.isEmpty()) {
        body
    } else {
        body + "\n\nSources:\n" + sources.joinToString("\n")
    }
}

/** Convert GFM markdown (including tables) to HTML using commonmark. */
private fun markdownToHtml(markdown: String): String {
    val extensions = listOf(TablesExtension.create())
    val parser = Parser.builder().extensions(extensions).build()
    val renderer = HtmlRenderer.builder().extensions(extensions).build()
    return renderer.render(parser.parse(markdown))
}

@Composable
private fun HtmlContent(
    html: String,
    fontSize: Float = 14f,
    cachedHeightPx: Int = 0,
    onHeightMeasured: (Int) -> Unit = {}
) {
    val bgColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val fullHtml = """
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=5.0, user-scalable=yes">
            <style>
                body {
                    margin: 0;
                    padding: 8px 0;
                    font-family: sans-serif;
                    font-size: ${fontSize}px;
                    line-height: 1.5;
                    color: ${colorToHex(textColor)};
                    background-color: ${colorToHex(bgColor)};
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    max-width: 100%;
                    overflow-x: hidden;
                    -webkit-user-select: text;
                    user-select: text;
                    -webkit-touch-callout: default;
                }
                a { color: ${colorToHex(linkColor)}; }
                pre {
                    background: rgba(128,128,128,0.15);
                    padding: 8px;
                    border-radius: 4px;
                    overflow-x: auto;
                    white-space: pre-wrap;
                    word-break: break-all;
                }
                code {
                    font-family: monospace;
                    background: rgba(128,128,128,0.15);
                    padding: 2px 4px;
                    border-radius: 3px;
                    word-break: break-all;
                }
                blockquote {
                    border-left: 3px solid ${colorToHex(linkColor)};
                    margin: 8px 0;
                    padding-left: 12px;
                    opacity: 0.7;
                }
                table {
                    border-collapse: collapse;
                    width: 100%;
                    max-width: 100%;
                    overflow-x: auto;
                    display: block;
                    word-break: normal;
                }
                th, td {
                    border: 1px solid rgba(128,128,128,0.3);
                    padding: 6px 8px;
                    text-align: left;
                    white-space: normal;
                    word-wrap: break-word;
                }
                th {
                    background: rgba(128,128,128,0.15);
                    font-weight: bold;
                }
                tr:nth-child(even) {
                    background: rgba(128,128,128,0.05);
                }
            </style>
        </head>
        <body>$html</body>
        </html>
    """.trimIndent()

    // Tracks the last HTML string we actually loaded so the update lambda can skip
    // unnecessary reloads (a reload causes the WebView to flash/re-measure its height,
    // which is the main cause of jumpy scrolling when recompositions happen).
    val lastLoadedHtml = remember { arrayOfNulls<String>(1) }
    // Prefer the cached height from the parent so recycled list items keep a stable
    // size while the WebView reloads; fall back to live measurement.
    var measuredHeightPx by remember { mutableIntStateOf(cachedHeightPx) }
    LaunchedEffect(cachedHeightPx) {
        if (cachedHeightPx > 0 && cachedHeightPx != measuredHeightPx) {
            measuredHeightPx = cachedHeightPx
        }
    }
    val onHeightMeasuredState = rememberUpdatedState(onHeightMeasured)

    val heightModifier = if (measuredHeightPx > 0) {
        Modifier.height(with(density) { measuredHeightPx.toDp() })
    } else {
        // Placeholder until first measure so the item doesn't claim the whole viewport.
        Modifier.height(1.dp)
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .then(heightModifier)
            .nestedScroll(rememberNestedScrollInteropConnection()),
        factory = { context ->
            NonScrollingWebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = false
                isNestedScrollingEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = openExternally(context, request.url)

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.measureContentHeight { h ->
                            if (h > 0) {
                                measuredHeightPx = h
                                onHeightMeasuredState.value(h)
                            }
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (newProgress == 100 && view != null) {
                            view.measureContentHeight { h ->
                                if (h > 0) {
                                    measuredHeightPx = h
                                    onHeightMeasuredState.value(h)
                                }
                            }
                        }
                    }
                }
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                isLongClickable = true
                // Hold a link to copy its URL (tap still opens via shouldOverrideUrlLoading).
                setOnLongClickListener { view ->
                    val webView = view as WebView
                    val result = webView.hitTestResult
                    val url = when (result.type) {
                        WebView.HitTestResult.SRC_ANCHOR_TYPE,
                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> result.extra
                        else -> null
                    }
                    if (!url.isNullOrBlank()) {
                        copyTextToClipboard(context, url, label = "link")
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        true
                    } else {
                        false
                    }
                }
                lastLoadedHtml[0] = fullHtml
                loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            // Only reload when content actually changed (font size, theme, message content).
            if (lastLoadedHtml[0] != fullHtml) {
                lastLoadedHtml[0] = fullHtml
                webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
            }
        }
    )
}

/**
 * Measure the laid-out HTML content height in device pixels using
 * [WebView.getContentHeight] (CSS px × density). Retries briefly because
 * contentHeight often lags a frame or two after [WebViewClient.onPageFinished].
 */
private fun WebView.measureContentHeight(onResult: (Int) -> Unit) {
    val density = resources.displayMetrics.density
    fun currentHeightPx(): Int =
        if (contentHeight > 0) ceil(contentHeight * density).toInt() else 0

    fun report() {
        val h = currentHeightPx()
        if (h > 0) onResult(h)
    }

    post {
        report()
        // contentHeight often settles shortly after onPageFinished
        postDelayed({ report() }, 50)
        postDelayed({ report() }, 150)
    }
}

/** True when the last list item is visible (or the list is empty / not laid out yet). */
private fun LazyListState.isNearBottom(): Boolean {
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return true
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    // "Near bottom" = last item is on screen, or within ~1 item of the end.
    return lastVisible.index >= total - 2
}

/**
 * WebView that never scrolls internally on the Y axis so the surrounding LazyColumn
 * owns all vertical scrolling. This ensures the WebView's scrollY stays 0, which
 * means tap coordinates always map correctly to content positions (no offset) and
 * upward swipes are immediately passed to the list instead of draining internal scroll.
 * Sources are now native Compose rows, so there is no link hit-test concern.
 */
private class NonScrollingWebView(context: android.content.Context) : WebView(context) {
    override fun scrollTo(x: Int, y: Int) = super.scrollTo(x, 0)
    override fun scrollBy(x: Int, y: Int) = super.scrollBy(x, 0)
    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) =
        super.onOverScrolled(scrollX, 0, clampedX, false)
}

private fun openExternally(context: android.content.Context, uri: Uri): Boolean {
    return try {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
        true
    }
}

private fun copyTextToClipboard(context: Context, text: String, label: String = "text") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun colorToHex(color: androidx.compose.ui.graphics.Color): String {
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    return String.format("#%02X%02X%02X", r, g, b)
}
