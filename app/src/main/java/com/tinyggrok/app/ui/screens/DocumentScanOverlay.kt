package com.tinyggrok.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tinyggrok.app.data.scan.DocumentCorners
import com.tinyggrok.app.data.scan.NormPoint
import com.tinyggrok.app.data.share.startImageFileShare
import com.tinyggrok.app.ui.viewmodel.ScanPhase
import com.tinyggrok.app.ui.viewmodel.ScanViewModel
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a bitmap lands when fitted, centred, inside a container. */
private fun fitRect(container: Size, imageWidth: Int, imageHeight: Int): Rect {
    val scale = min(container.width / imageWidth, container.height / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    val left = (container.width - w) / 2f
    val top = (container.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/**
 * Review a captured photo: the page outline is placed automatically and can be dragged
 * by its corners. Confirming flattens the page and returns the straightened image.
 *
 * A full-screen layer for the caller to draw over its own content, not a Dialog: see
 * the root Box in ChatScreen for why.
 */
@Composable
fun DocumentScanOverlay(
    photoUri: Uri,
    onDismiss: () -> Unit,
    onScanned: (Uri) -> Unit,
    viewModel: ScanViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(photoUri) { viewModel.start(photoUri) }
    DisposableEffect(Unit) { onDispose { viewModel.reset() } }

    BackHandler(onBack = onDismiss)

    run {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Swallow touches so the chat underneath cannot be pressed through.
                .pointerInput(Unit) { detectTapGestures { } }
                .systemBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.phase == ScanPhase.LOADING ||
                    state.phase == ScanPhase.DETECTING ||
                    state.phase == ScanPhase.SAVING
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White
                    )
                }
                Text(
                    text = state.error ?: state.note,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                state.photo?.let { photo ->
                    val image = remember(photo) { photo.asImageBitmap() }
                    CornerEditor(
                        image = image,
                        imageWidth = photo.width,
                        imageHeight = photo.height,
                        corners = state.corners,
                        enabled = state.phase != ScanPhase.SAVING,
                        onMove = viewModel::moveCorner
                    )
                }
            }

            // Alignment tools, kept apart from the outcomes below so that a fifth button
            // never squeezes the row: crowded rows are how Send once fell off screen.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = viewModel::rotate,
                    enabled = state.canConfirm
                ) { Text("Rotate", maxLines = 1, softWrap = false) }
                TextButton(
                    onClick = viewModel::snapToEdges,
                    enabled = state.canConfirm
                ) { Text("Snap", maxLines = 1, softWrap = false) }
                TextButton(
                    onClick = viewModel::askGrok,
                    enabled = state.canConfirm && state.phase != ScanPhase.DETECTING
                ) { Text("Ask Grok", maxLines = 1, softWrap = false) }
            }

            // Where the aligned page goes. Share leaves the scanner open, so one scan can
            // be sent to another app and added to the prompt as well.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.White, maxLines = 1, softWrap = false)
                }
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        viewModel.share { file ->
                            try {
                                startImageFileShare(context, file, "Share scan")
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "No app available to share to.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    enabled = state.canConfirm
                ) { Text("Share", maxLines = 1, softWrap = false) }
                Button(
                    onClick = { viewModel.confirm(onScanned) },
                    enabled = state.canConfirm,
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("To prompt", maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
private fun CornerEditor(
    image: androidx.compose.ui.graphics.ImageBitmap,
    imageWidth: Int,
    imageHeight: Int,
    corners: DocumentCorners,
    enabled: Boolean,
    onMove: (Int, NormPoint) -> Unit
) {
    val currentCorners by rememberUpdatedState(corners)
    val currentEnabled by rememberUpdatedState(enabled)
    val density = LocalDensity.current
    val handleRadius = with(density) { 11.dp.toPx() }
    // Generous grab area: corners sit near screen edges and fingers are not styluses.
    val grabRadius = with(density) { 44.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(imageWidth, imageHeight) {
                var active = -1
                detectDragGestures(
                    onDragStart = { start ->
                        val rect = fitRect(Size(size.width.toFloat(), size.height.toFloat()), imageWidth, imageHeight)
                        active = if (!currentEnabled) {
                            -1
                        } else {
                            currentCorners.toList()
                                .mapIndexed { i, p ->
                                    val x = rect.left + p.x * rect.width
                                    val y = rect.top + p.y * rect.height
                                    i to hypot(start.x - x, start.y - y)
                                }
                                .filter { it.second <= grabRadius }
                                .minByOrNull { it.second }
                                ?.first ?: -1
                        }
                    },
                    onDrag = { change, _ ->
                        if (active >= 0) {
                            change.consume()
                            val rect = fitRect(Size(size.width.toFloat(), size.height.toFloat()), imageWidth, imageHeight)
                            onMove(
                                active,
                                NormPoint(
                                    (change.position.x - rect.left) / rect.width,
                                    (change.position.y - rect.top) / rect.height
                                )
                            )
                        }
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 }
                )
            }
    ) {
        val rect = fitRect(size, imageWidth, imageHeight)
        drawImage(
            image = image,
            dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
            dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt())
        )

        val points = corners.toList().map {
            Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height)
        }
        val quad = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1..3) lineTo(points[i].x, points[i].y)
            close()
        }
        // Dim everything that will be cropped away so the kept area reads at a glance.
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(rect)
            addPath(quad)
        }
        drawPath(outside, Color.Black.copy(alpha = 0.55f))
        drawPath(quad, accent, style = Stroke(width = 2.dp.toPx()))
        points.forEach { p ->
            drawCircle(Color.White, radius = handleRadius, center = p)
            drawCircle(accent, radius = handleRadius * 0.62f, center = p)
        }
    }
}
