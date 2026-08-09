package com.tinyggrok.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BugReport
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import com.tinyggrok.app.AppDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.repository.AuthMode
import com.tinyggrok.app.ui.theme.AppTheme
import com.tinyggrok.app.ui.viewmodel.ApiKeyCheckUi
import com.tinyggrok.app.ui.viewmodel.SettingsViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToUsage: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    var locationPermissionGranted by remember { mutableStateOf(hasLocationPermission()) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationPermissionGranted =
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    uiState.previewError?.let { err ->
        LaunchedEffect(err) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearPreviewError()
        }
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToAbout) {
                        Icon(Icons.Default.Info, contentDescription = "About")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Appearance ───────────────────────────────────────────────────
            SettingsSection(title = "Appearance", icon = Icons.Default.Palette) {
                SectionLabel("Theme")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        "Light" to AppTheme.LIGHT,
                        "Dark" to AppTheme.DARK,
                        "Tokyo Night" to AppTheme.TOKYO_NIGHT
                    ).forEachIndexed { index, (label, theme) ->
                        SegmentedButton(
                            selected = uiState.theme == theme,
                            onClick = { viewModel.updateTheme(theme) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                        ) { Text(label, maxLines = 1) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                FontSizeSlider(
                    value = uiState.fontSize,
                    onValueChange = viewModel::updateFontSize
                )
            }

            // ── Chat ─────────────────────────────────────────────────────────
            SettingsSection(title = "Chat", icon = Icons.Default.TextFields) {
                SectionLabel("Model")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AppDefaults.CHAT_MODELS.forEachIndexed { index, (label, id) ->
                        SegmentedButton(
                            selected = uiState.chatModel == id,
                            onClick = { viewModel.updateChatModel(id) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = AppDefaults.CHAT_MODELS.size
                            )
                        ) { Text(label, maxLines = 1) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                SectionLabel("Response format")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("HTML" to "html", "Markdown" to "markdown")
                        .forEachIndexed { index, (label, value) ->
                            SegmentedButton(
                                selected = uiState.responseFormat == value,
                                onClick = { viewModel.updateResponseFormat(value) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                            ) { Text(label) }
                        }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                SettingsToggleRow(
                    title = "Show cost per query",
                    subtitle = "Display token cost below each response",
                    icon = Icons.Default.AttachMoney,
                    checked = uiState.showCost,
                    onCheckedChange = viewModel::updateShowCost
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                SettingsToggleRow(
                    title = "Debug mode",
                    subtitle = "Log all API requests and responses",
                    icon = Icons.Default.BugReport,
                    checked = uiState.debugMode,
                    onCheckedChange = viewModel::updateDebugMode
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                val locationSubtitle = when {
                    !uiState.locationEnabled ->
                        "Off — answers won't use your location"
                    locationPermissionGranted ->
                        "On — approximate location used for local / transit answers"
                    else ->
                        "On — location permission needed (tap switch again or grant in system settings)"
                }
                SettingsToggleRow(
                    title = "Use GPS location",
                    subtitle = locationSubtitle,
                    icon = Icons.Default.LocationOn,
                    checked = uiState.locationEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.updateLocationEnabled(enabled)
                        if (enabled && !hasLocationPermission()) {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        } else {
                            locationPermissionGranted = hasLocationPermission()
                        }
                    }
                )

                if (uiState.locationEnabled) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    GpsCacheTimeoutPicker(
                        minutes = uiState.locationCacheTimeoutMinutes,
                        onMinutesChange = viewModel::updateLocationCacheTimeoutMinutes
                    )
                }
            }

            // ── Voice Translator ─────────────────────────────────────────────
            SettingsSection(title = "Voice Translator", icon = Icons.Default.Mic) {
                SettingsToggleRow(
                    title = "Enable Voice Translator",
                    subtitle = "Real-time translation via Grok Voice API · \$0.05 / min",
                    icon = Icons.Default.RecordVoiceOver,
                    checked = uiState.voiceEnabled,
                    onCheckedChange = viewModel::updateVoiceEnabled
                )

                if (uiState.voiceEnabled) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // ── Speaking voice ───────────────────────────────────────
                    Text(
                        "Speaking Voice",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    VoicePicker(
                        selected = uiState.voiceOption,
                        previewingVoice = uiState.previewingVoice,
                        onSelect = viewModel::updateVoiceOption,
                        onPreview = viewModel::previewVoice
                    )

                    Spacer(Modifier.height(14.dp))

                    // ── Personality mode ─────────────────────────────────────
                    Text(
                        "Personality Mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    PersonalityPicker(
                        selected = uiState.personalityMode,
                        previewingPersonality = uiState.previewingPersonality,
                        onSelect = viewModel::updatePersonalityMode,
                        onPreview = viewModel::previewPersonality
                    )

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // ── Microphone sensitivity ───────────────────────────────
                    // Five named levels, each with a bar-count for the signal visualisation
                    val micTileData = listOf(
                        Triple("Max",  0.10f, 5),
                        Triple("High", 0.30f, 4),
                        Triple("Mid",  0.50f, 3),
                        Triple("Low",  0.70f, 2),
                        Triple("Min",  0.90f, 1)
                    )
                    val micTileDescs = listOf(
                        "Picks up whispers",
                        "Quiet speech",
                        "Balanced",
                        "Loud speech only",
                        "Filters noise"
                    )
                    val micTileBarHeights = listOf(8, 11, 16, 20, 24) // dp, short → tall
                    val selMicIdx = micTileData
                        .indexOfFirst { abs(it.second - uiState.vadThreshold) < 0.06f }
                        .coerceAtLeast(0)

                    // Section label row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Microphone Sensitivity",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            micTileDescs[selMicIdx],
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // Custom signal-bar tile row — no standard slider or segmented buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        micTileData.forEachIndexed { idx, (label, value, activeBars) ->
                            val isSel = idx == selMicIdx
                            val tileBg = if (isSel)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            val activeBarColor = if (isSel)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            val inactiveBarColor = activeBarColor.copy(alpha = 0.12f)
                            val labelColor = if (isSel)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(tileBg)
                                    .clickable { viewModel.updateVadThreshold(value) }
                                    .padding(vertical = 10.dp, horizontal = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Audio-level bars — bottom-anchored, short→tall left→right
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.Bottom,
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        micTileBarHeights.forEachIndexed { barIdx, h ->
                                            Box(
                                                modifier = Modifier
                                                    .width(4.dp)
                                                    .height(h.dp)
                                                    .clip(
                                                        RoundedCornerShape(
                                                            topStart = 2.dp,
                                                            topEnd = 2.dp,
                                                            bottomStart = 1.dp,
                                                            bottomEnd = 1.dp
                                                        )
                                                    )
                                                    .background(
                                                        if (barIdx < activeBars) activeBarColor
                                                        else inactiveBarColor
                                                    )
                                            )
                                        }
                                    }
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = labelColor,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "← more sensitive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            "less sensitive →",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // ── Auth (API key / SuperGrok OAuth experimental) ────────────────
            SettingsSection(title = "Authentication", icon = Icons.Default.VpnKey) {
                val showKey = remember { mutableStateOf(false) }
                val checkingKey = uiState.apiKeyCheck is ApiKeyCheckUi.Checking
                val uriHandler = LocalUriHandler.current

                Text(
                    text = "Choose how Tiny Grok authenticates to api.x.ai. " +
                        "SuperGrok OAuth is experimental — it may use subscription quota " +
                        "or still hit API prepaid limits depending on xAI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                SectionLabel("Mode")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        "API key" to AuthMode.API_KEY,
                        "SuperGrok" to AuthMode.SUPERGROK_OAUTH
                    ).forEachIndexed { index, (label, mode) ->
                        SegmentedButton(
                            selected = uiState.authMode == mode,
                            onClick = { viewModel.updateAuthMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                            enabled = !uiState.oauthLoginInProgress
                        ) { Text(label, maxLines = 1) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // SuperGrok OAuth block
                Text(
                    "SuperGrok sign-in (experimental)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Device-code login via auth.x.ai (same OIDC family as Grok Build). " +
                        "Not an official Tiny Grok entitlement — no Heavy guarantee.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                if (uiState.oauthSignedIn) {
                    Text(
                        text = "Signed in" +
                            (uiState.oauthEmail?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = viewModel::signOutSuperGrok,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Sign out")
                        }
                        if (uiState.authMode == AuthMode.SUPERGROK_OAUTH) {
                            OutlinedButton(
                                onClick = viewModel::checkApiKey,
                                modifier = Modifier.weight(1f),
                                enabled = !checkingKey
                            ) {
                                if (checkingKey) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(if (checkingKey) "Checking…" else "Check")
                            }
                        }
                    }
                } else if (uiState.oauthLoginInProgress) {
                    val verifyUri = uiState.oauthVerificationUri
                    // Open verification page once when the device code arrives.
                    LaunchedEffect(verifyUri) {
                        if (!verifyUri.isNullOrBlank()) {
                            runCatching { uriHandler.openUri(verifyUri) }
                        }
                    }
                    uiState.oauthUserCode?.let { code ->
                        Text(
                            text = code,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    uiState.oauthLoginMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (!verifyUri.isNullOrBlank()) {
                                    runCatching { uriHandler.openUri(verifyUri) }
                                        .onFailure {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(verifyUri))
                                            )
                                        }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !verifyUri.isNullOrBlank()
                        ) {
                            Icon(
                                Icons.Default.OpenInBrowser,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Open browser")
                        }
                        OutlinedButton(
                            onClick = viewModel::cancelSuperGrokLogin,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                    }
                } else {
                    Button(
                        onClick = viewModel::startSuperGrokLogin,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Login,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Sign in with SuperGrok")
                    }
                    uiState.oauthLoginMessage?.let { msg ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // API key check result also shown for SuperGrok check
                if (uiState.authMode == AuthMode.SUPERGROK_OAUTH) {
                    when (val check = uiState.apiKeyCheck) {
                        is ApiKeyCheckUi.Success -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = check.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        is ApiKeyCheckUi.Failure -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = check.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> Unit
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    "xAI API key",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Used when mode is API key. Create keys at console.x.ai (prepaid credits).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = viewModel::updateApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("xAI API key") },
                    singleLine = true,
                    enabled = !checkingKey,
                    leadingIcon = {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    visualTransformation = if (showKey.value)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey.value = !showKey.value }) {
                            Icon(
                                imageVector = if (showKey.value) Icons.Default.VisibilityOff
                                              else Icons.Default.Visibility,
                                contentDescription = if (showKey.value) "Hide key" else "Show key"
                            )
                        }
                    }
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::saveApiKey,
                        modifier = Modifier.weight(1f),
                        enabled = !checkingKey
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = viewModel::checkApiKey,
                        modifier = Modifier.weight(1f),
                        enabled = !checkingKey && uiState.apiKey.isNotBlank()
                    ) {
                        if (checkingKey) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (checkingKey) "Checking…" else "Check")
                    }
                    OutlinedButton(
                        onClick = viewModel::clearApiKey,
                        modifier = Modifier.weight(1f),
                        enabled = !checkingKey
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                }

                when (val check = uiState.apiKeyCheck) {
                    is ApiKeyCheckUi.Success -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = check.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    is ApiKeyCheckUi.Failure -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = check.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> Unit
                }

                uiState.savedMessage?.let { msg ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ── Management key (credits / usage) ─────────────────────────────
            SettingsSection(title = "API credits / Management", icon = Icons.Default.AttachMoney) {
                Text(
                    "Optional. Used only by Credits & usage to load prepaid balance and rate quotas. " +
                        "Create a key at console.x.ai → Settings → Management Keys " +
                        "(not the same as your chat API key).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                val showMgmtKey = remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = uiState.managementKey,
                    onValueChange = viewModel::updateManagementKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Management key") },
                    singleLine = true,
                    visualTransformation = if (showMgmtKey.value)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showMgmtKey.value = !showMgmtKey.value }) {
                            Icon(
                                imageVector = if (showMgmtKey.value) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = if (showMgmtKey.value) "Hide key" else "Show key"
                            )
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = uiState.teamId,
                    onValueChange = viewModel::updateTeamId,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Team ID (optional)") },
                    singleLine = true,
                    supportingText = {
                        Text("Leave blank to auto-detect from the management key.")
                    }
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::saveManagementCredentials,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = viewModel::clearManagementCredentials,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                }
            }

            // ── Credits & About ──────────────────────────────────────────────
            OutlinedButton(
                onClick = onNavigateToUsage,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.AttachMoney,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Credits & usage")
            }

            OutlinedButton(
                onClick = onNavigateToAbout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("About Tiny Ggrok")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Reusable composables
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (checked) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// Need Box at top level of this file
@Suppress("NOTHING_TO_INLINE")
@Composable
private inline fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit
) = androidx.compose.foundation.layout.Box(
    modifier = modifier,
    contentAlignment = contentAlignment,
    content = { content() }
)

// ────────────────────────────────────────────────────────────────────────────
// GPS cache timeout (presets + custom)
// ────────────────────────────────────────────────────────────────────────────

/**
 * How long a GPS fix is reused before the next chip lookup.
 * Presets: 10m, 15m, 30m, 1h, 2h, or Custom (enter minutes). Default 10m.
 */
@Composable
private fun GpsCacheTimeoutPicker(
    minutes: Int,
    onMinutesChange: (Int) -> Unit
) {
    val active = SettingsRepository.normalizeLocationCacheTimeoutMinutes(minutes)
    val isCustom = !SettingsRepository.isLocationCacheTimeoutPreset(active)
    var showCustomDialog by remember { mutableStateOf(false) }
    var customDraft by remember(active) {
        mutableStateOf(active.toString())
    }

    // Chip options: fixed presets + Custom
    data class TimeoutOption(val label: String, val minutes: Int?) // null = custom

    val options = listOf(
        TimeoutOption("10m", 10),
        TimeoutOption("15m", 15),
        TimeoutOption("30m", 30),
        TimeoutOption("1h", 60),
        TimeoutOption("2h", 120),
        TimeoutOption("Custom", null)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "GPS cache timeout",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatCacheTimeoutLabel(active),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = "Reuse the last GPS fix for this long without waking the chip again. " +
                "Default 10 minutes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                val selected = if (option.minutes == null) {
                    isCustom
                } else {
                    active == option.minutes
                }
                val bg = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
                val fg = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .clickable {
                            if (option.minutes != null) {
                                onMinutesChange(option.minutes)
                            } else {
                                customDraft = active.toString()
                                showCustomDialog = true
                            }
                        }
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = fg,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }

    if (showCustomDialog) {
        val parsed = customDraft.toIntOrNull()
        val valid = parsed != null &&
            parsed in SettingsRepository.MIN_LOCATION_CACHE_TIMEOUT_MINUTES..
            SettingsRepository.MAX_LOCATION_CACHE_TIMEOUT_MINUTES
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Custom GPS cache timeout") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter minutes (1–${SettingsRepository.MAX_LOCATION_CACHE_TIMEOUT_MINUTES}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customDraft,
                        onValueChange = { draft ->
                            if (draft.isEmpty() || draft.all { it.isDigit() }) {
                                customDraft = draft.take(5)
                            }
                        },
                        label = { Text("Minutes") },
                        singleLine = true,
                        isError = customDraft.isNotEmpty() && !valid,
                        supportingText = {
                            if (customDraft.isNotEmpty() && !valid) {
                                Text("Enter a number from 1 to ${SettingsRepository.MAX_LOCATION_CACHE_TIMEOUT_MINUTES}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (parsed != null && valid) {
                            onMinutesChange(parsed)
                            showCustomDialog = false
                        }
                    },
                    enabled = valid
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatCacheTimeoutLabel(minutes: Int): String = when {
    minutes < 60 -> if (minutes == 1) "1 minute" else "$minutes minutes"
    minutes % 60 == 0 -> {
        val h = minutes / 60
        if (h == 1) "1 hour" else "$h hours"
    }
    else -> {
        val h = minutes / 60
        val m = minutes % 60
        if (h == 0) "$m minutes"
        else if (h == 1) "1 h $m m"
        else "$h h $m m"
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Elegant font-size picker
// ────────────────────────────────────────────────────────────────────────────

/**
 * A stepped font-size picker that shows 5 labelled size presets as tappable tiles.
 * Each tile displays a progressively larger "A" glyph so the effect is immediately
 * obvious at a glance.  The selected tile gets a filled primary background; the rest
 * use a subtle surface-variant card.  Below the tiles a live preview line shows the
 * chosen size in action.
 *
 * Range 10–24 sp is mapped to 5 discrete steps:  10, 13, 16, 20, 24 sp.
 */
@Composable
private fun FontSizeSlider(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val steps = listOf(10f, 13f, 16f, 20f, 24f)
    val labels = listOf("XS", "S", "M", "L", "XL")

    // Snap the incoming value to whichever step is closest
    val activeIndex = steps.indices.minByOrNull { kotlin.math.abs(steps[it] - value) } ?: 2

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // Header row: label on the left, current size value on the right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Font Size",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${steps[activeIndex].roundToInt()} sp",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Tile row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            steps.forEachIndexed { index, size ->
                val isActive = index == activeIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .then(
                            if (!isActive) Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp)
                            ) else Modifier
                        )
                        .clickable { onValueChange(size) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "A",
                            fontSize = (10 + index * 3).sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = (10 + index * 3 + 2).sp
                        )
                        Text(
                            text = labels[index],
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Live preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Preview: The quick brown fox",
                fontSize = steps[activeIndex].sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Voice picker — 5 tiles (Eve, Ara, Rex, Sal, Leo)
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoicePicker(
    selected: com.tinyggrok.app.data.model.VoiceOption,
    previewingVoice: com.tinyggrok.app.data.model.VoiceOption?,
    onSelect: (com.tinyggrok.app.data.model.VoiceOption) -> Unit,
    onPreview: (com.tinyggrok.app.data.model.VoiceOption) -> Unit
) {
    val voices = com.tinyggrok.app.data.model.VoiceOption.values()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        voices.forEach { voice ->
            val isActive = voice == selected
            val isPreviewing = voice == previewingVoice
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .then(
                        if (!isActive) Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp)
                        ) else Modifier
                    )
                    .clickable { onSelect(voice) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = voice.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = voice.gender,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                                else MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    // Try button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            )
                            .clickable(enabled = !isPreviewing) { onPreview(voice) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isPreviewing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "▶ Try",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = selected.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

// ────────────────────────────────────────────────────────────────────────────
// Personality mode picker — grouped card list with emoji + age-gate badge
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun PersonalityPicker(
    selected: com.tinyggrok.app.data.model.PersonalityMode,
    previewingPersonality: com.tinyggrok.app.data.model.PersonalityMode?,
    onSelect: (com.tinyggrok.app.data.model.PersonalityMode) -> Unit,
    onPreview: (com.tinyggrok.app.data.model.PersonalityMode) -> Unit
) {
    val modes = com.tinyggrok.app.data.model.PersonalityMode.values()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        modes.forEach { mode ->
            val isActive = mode == selected
            val isPreviewing = mode == previewingPersonality
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(mode) },
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                                     else MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.elevatedCardElevation(
                    defaultElevation = if (isActive) 4.dp else 1.dp
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = mode.emoji,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // ▶ Try button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            )
                            .clickable(enabled = !isPreviewing) { onPreview(mode) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isPreviewing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "▶ Try",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (isActive) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
