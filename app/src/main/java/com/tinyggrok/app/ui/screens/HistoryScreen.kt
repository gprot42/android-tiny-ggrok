package com.tinyggrok.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.tinyggrok.app.data.repository.ResponseHistoryEntry
import com.tinyggrok.app.ui.viewmodel.HistoryViewModel
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()
    val reversed = entries.reversed()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Response History (${entries.size}/10)") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("Back") }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = viewModel::clear) { Text("Clear") }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (entries.isEmpty()) {
                Text(
                    "No history yet. Responses will appear here after you send queries.",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                reversed.forEachIndexed { idx, entry ->
                    HistoryEntryCard(entry = entry, index = entries.size - idx)
                    if (idx < reversed.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(entry: ResponseHistoryEntry, index: Int) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val bgColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary

    // Detect HTML vs markdown: run markdown through commonmark; pass HTML straight through.
    val isHtml = entry.response.trimStart().startsWith("<")
    val renderedHtml = if (isHtml) entry.response else historyMarkdownToHtml(entry.response)
    val fullHtml = buildHistoryHtml(renderedHtml, bgColor, textColor, linkColor)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "#$index",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        entry.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, entry.response)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share response"))
                }) { Text("Share") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(entry.response)) }) {
                    Text("Copy")
                }
            }

            Text(
                "You: ${entry.prompt.take(120)}${if (entry.prompt.length > 120) "…" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    // Forward the WebView's vertical scrolls to the surrounding
                    // scrollable Column so the history page scrolls normally.
                    .nestedScroll(rememberNestedScrollInteropConnection()),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.javaScriptEnabled = false
                        isNestedScrollingEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean = openLinkExternally(ctx, request.url)
                        }
                        settings.useWideViewPort = false
                        settings.loadWithOverviewMode = false
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                    }
                },
                update = { wv ->
                    wv.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                }
            )
        }
    }
}

/** Open a link in the user's external browser instead of the WebView. */
private fun openLinkExternally(context: android.content.Context, uri: Uri): Boolean {
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
        true
    }
}

/** Convert GFM markdown (including tables) to HTML. */
private fun historyMarkdownToHtml(markdown: String): String {
    val extensions = listOf(TablesExtension.create())
    val parser = Parser.builder().extensions(extensions).build()
    val renderer = HtmlRenderer.builder().extensions(extensions).build()
    return renderer.render(parser.parse(markdown))
}

/** Build a complete HTML document with consistent CSS matching the chat screen. */
private fun buildHistoryHtml(body: String, bg: Color, text: Color, link: Color): String {
    val bgHex = colorHex(bg)
    val textHex = colorHex(text)
    val linkHex = colorHex(link)
    return """
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body {
                    margin: 0;
                    padding: 4px 0;
                    font-family: sans-serif;
                    font-size: 14px;
                    line-height: 1.5;
                    color: $textHex;
                    background-color: $bgHex;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    max-width: 100%;
                    overflow-x: hidden;
                }
                a { color: $linkHex; }
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
                    border-left: 3px solid $linkHex;
                    margin: 8px 0;
                    padding-left: 12px;
                    opacity: 0.8;
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
                ul, ol { padding-left: 20px; margin: 4px 0; }
                li { margin-bottom: 2px; }
                h1, h2, h3 { margin: 8px 0 4px; }
                p { margin: 4px 0; }
            </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()
}

private fun colorHex(color: Color): String {
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    return String.format("#%02X%02X%02X", r, g, b)
}
