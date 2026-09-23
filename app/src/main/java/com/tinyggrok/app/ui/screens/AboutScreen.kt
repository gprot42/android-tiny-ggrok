package com.tinyggrok.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tinyggrok.app.AppDefaults
import com.tinyggrok.app.BuildConfig
import com.tinyggrok.app.ui.viewmodel.AboutViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Tiny Ggrok", style = MaterialTheme.typography.headlineMedium)
            Text(
                "A minimal Android client for xAI's Grok chat completions API.",
                style = MaterialTheme.typography.bodyMedium
            )

            InfoRow(label = "Version", value = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            InfoRow(label = "Build type", value = BuildConfig.BUILD_TYPE)
            InfoRow(label = "Build date", value = BuildConfig.BUILD_DATE)
            InfoRow(label = "Application ID", value = BuildConfig.APPLICATION_ID)
            InfoRow(label = "Default model", value = AppDefaults.DEFAULT_MODEL)
            // Blank on Android 10 and older, which keeps no such record.
            viewModel.lastExit?.let { InfoRow(label = "Previous exit", value = it) }
            InfoRow(label = "API endpoint", value = "https://api.x.ai/v1/chat/completions")
            InfoRow(label = "Framework", value = AppDefaults.FRAMEWORK)
            InfoRow(label = "Language", value = "Kotlin 2.1.20 / JVM 17 bytecode")
            InfoRow(label = "Min / Target SDK", value = "24 / 35")
            InfoRow(label = "Android release", value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
