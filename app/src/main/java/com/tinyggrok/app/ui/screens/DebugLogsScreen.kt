package com.tinyggrok.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tinyggrok.app.data.repository.DebugLogEntry
import com.tinyggrok.app.ui.viewmodel.DebugLogsViewModel
import com.tinyggrok.app.ui.viewmodel.LogGroup

/** Chars shown in a collapsed log body. Must stay small — Compose Text layouts the whole string. */
private const val COLLAPSED_BODY_CHARS = 1_500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DebugLogsViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsState()
    val allLogs by viewModel.logs.collectAsState()
    val clipboard = LocalClipboardManager.current

    // Explicit toggles only. Newest group starts expanded; older groups stay collapsed
    // so opening this screen does not measure every payload at once.
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val newestGroupId = groups.lastOrNull()?.id

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Debug Logs",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("Back") }
                },
                actions = {
                    if (allLogs.isNotEmpty()) {
                        TextButton(onClick = {
                            clipboard.setText(
                                AnnotatedString(viewModel.allFormatted(allLogs))
                            )
                        }) { Text("Copy All") }
                    }
                    TextButton(onClick = viewModel::clearLogs) { Text("Clear") }
                }
            )
        }
    ) { innerPadding ->
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "No debug logs yet.\nEnable Debug Mode in Settings, then send a query or start a Voice session.",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "top") { Spacer(Modifier.padding(top = 4.dp)) }

                groups.forEach { group ->
                    val isExpanded = expanded[group.id] ?: (group.id == newestGroupId)
                    item(key = "h-${group.id}") {
                        LogGroupHeader(
                            group = group,
                            isExpanded = isExpanded,
                            onToggle = {
                                expanded[group.id] = !isExpanded
                            },
                            onCopyGroup = {
                                clipboard.setText(AnnotatedString(group.formatted()))
                            }
                        )
                    }
                    if (isExpanded) {
                        items(
                            items = group.entries,
                            key = { entry -> "e-${group.id}-${entry.id}" }
                        ) { entry ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                LogEntryRow(entry = entry)
                            }
                        }
                    }
                }

                item(key = "bottom") { Spacer(Modifier.padding(bottom = 8.dp)) }
            }
        }
    }
}

@Composable
private fun LogGroupHeader(
    group: LogGroup,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onCopyGroup: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = group.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${group.entries.size} entries",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onCopyGroup,
                modifier = Modifier.padding(0.dp)
            ) {
                Text("Copy", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (isExpanded && group.entries.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun LogEntryRow(entry: DebugLogEntry) {
    val directionColor = when {
        entry.direction.startsWith("✗") -> MaterialTheme.colorScheme.error
        entry.direction.startsWith("→") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    var showFullBody by remember(entry.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.direction,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = directionColor
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (entry.body.isNotBlank()) {
            val collapsed = entry.body.length > COLLAPSED_BODY_CHARS
            val displayBody = if (showFullBody || !collapsed) {
                entry.body
            } else {
                entry.body.take(COLLAPSED_BODY_CHARS) + "…"
            }
            Text(
                text = displayBody,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (showFullBody) 80 else 16,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(6.dp)
                    .then(
                        if (collapsed) {
                            Modifier.clickable { showFullBody = !showFullBody }
                        } else {
                            Modifier
                        }
                    )
            )
            if (collapsed) {
                Text(
                    text = if (showFullBody) "Tap body to collapse" else "Tap body to show more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.clickable { showFullBody = !showFullBody }
                )
            }
        }
    }
}
