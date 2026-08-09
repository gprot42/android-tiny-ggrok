package com.tinyggrok.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.repository.ApiUsageSnapshot
import com.tinyggrok.app.data.repository.BalanceChangeRow
import com.tinyggrok.app.ui.viewmodel.UsageViewModel
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: UsageViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Credits & usage") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("Back") }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::refresh,
                        enabled = !uiState.isLoading
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── API credits (live) ──────────────────────────────────────────
            SectionHeader(
                icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                title = "xAI API credits",
                subtitle = "Powers this app via your API key (console.x.ai)"
            )

            when {
                uiState.isLoading -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Loading billing…")
                        }
                    }
                }
                !uiState.hasManagementKey -> {
                    SetupCard(
                        message = "Add a Management Key in Settings to show live prepaid balance, " +
                            "spending limits, and model rate quotas.\n\n" +
                            "Create one at console.x.ai → Settings → Management Keys " +
                            "(separate from your chat API key).",
                        primaryLabel = "Open Settings",
                        onPrimary = onNavigateToSettings,
                        secondaryLabel = "Console billing",
                        onSecondary = { openUrl("https://console.x.ai/team/default/billing") }
                    )
                }
                uiState.errorMessage != null -> {
                    ErrorCard(
                        message = uiState.errorMessage.orEmpty(),
                        onRetry = viewModel::refresh,
                        onSettings = onNavigateToSettings,
                        onBilling = { openUrl("https://console.x.ai/team/default/billing") }
                    )
                }
                uiState.snapshot != null -> {
                    ApiCreditsCard(snapshot = uiState.snapshot!!)
                    RateLimitsCard(snapshot = uiState.snapshot!!)
                    if (uiState.snapshot!!.recentChanges.isNotEmpty()) {
                        RecentChangesCard(changes = uiState.snapshot!!.recentChanges)
                    }
                    OutlinedButton(
                        onClick = { openUrl("https://console.x.ai/team/default/billing") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Top up / manage on console.x.ai")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── SuperGrok consumer plan ─────────────────────────────────────
            SectionHeader(
                icon = { Icon(Icons.Default.Star, contentDescription = null) },
                title = "SuperGrok / consumer plan",
                subtitle = "grok.com & X apps — separate from API credits"
            )

            SuperGrokCard(
                selectedPlan = uiState.consumerPlan,
                onSelectPlan = viewModel::setConsumerPlan,
                onOpenGrok = { openUrl("https://grok.com") },
                onOpenPricing = { openUrl("https://x.ai/pricing") },
                onOpenAccount = { openUrl("https://accounts.x.ai") }
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionHeader(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SetupCard(
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPrimary, modifier = Modifier.weight(1f)) {
                    Text(primaryLabel)
                }
                OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f)) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onBilling: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Could not load API billing",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onSettings) { Text("Settings") }
                TextButton(onClick = onBilling) { Text("Billing") }
            }
        }
    }
}

@Composable
private fun ApiCreditsCard(snapshot: ApiUsageSnapshot) {
    val low = snapshot.prepaidRemainingUsd < 5.0
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = if (low) {
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            )
        } else {
            CardDefaults.elevatedCardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Prepaid balance", style = MaterialTheme.typography.labelLarge)
            Text(
                text = formatUsd(snapshot.prepaidRemainingUsd),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (low) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
            if (low) {
                Text(
                    "Low balance — image prompts often fail first. Top up on console.x.ai.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()

            MetricRow("Team", shortenId(snapshot.teamId))
            snapshot.managementKeyName?.takeIf { it.isNotBlank() }?.let {
                MetricRow("Management key", it)
            }
            snapshot.billingCycleYear?.let { y ->
                val m = snapshot.billingCycleMonth
                MetricRow(
                    "Billing cycle",
                    if (m != null) String.format(Locale.US, "%04d-%02d", y, m) else y.toString()
                )
            }
            snapshot.prepaidUsedThisPeriodUsd?.let {
                MetricRow("Prepaid used (period)", formatUsd(it))
            }
            snapshot.postpaidAmountThisPeriodUsd?.let {
                MetricRow("Postpaid this period", formatUsd(it))
            }
            snapshot.softSpendingLimitUsd?.let {
                MetricRow(
                    "Postpaid soft limit",
                    if (it <= 0.0) "$0 (prepaid only)" else formatUsd(it)
                )
            }
            snapshot.hardSpendingLimitUsd?.let {
                MetricRow("Postpaid hard limit", formatUsd(it))
            }
        }
    }
}

@Composable
private fun RateLimitsCard(snapshot: ApiUsageSnapshot) {
    val hasAny = listOf(
        snapshot.modelRpm, snapshot.modelRps, snapshot.modelTpm,
        snapshot.modelRph, snapshot.modelRpd, snapshot.modelMaxPrompt?.toString()
    ).any { !it.isNullOrBlank() }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "API rate quotas · ${snapshot.modelName ?: "selected model"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!hasAny) {
                Text(
                    "No per-model rate limits returned for this team/model " +
                        "(or the model name didn’t match).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                snapshot.modelRps?.let { MetricRow("Requests / second", it) }
                snapshot.modelRpm?.let { MetricRow("Requests / minute", it) }
                snapshot.modelRph?.let { MetricRow("Requests / hour", it) }
                snapshot.modelRpd?.let { MetricRow("Requests / day", it) }
                snapshot.modelTpm?.let { MetricRow("Tokens / minute", formatIntString(it)) }
                snapshot.modelMaxPrompt?.let { MetricRow("Max prompt tokens", "%,d".format(it)) }
            }
            Text(
                "These are team/model caps from the Management API, not SuperGrok chat limits.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecentChangesCard(changes: List<BalanceChangeRow>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recent prepaid activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            changes.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.origin, fontWeight = FontWeight.Medium)
                        row.time?.let {
                            Text(
                                it.take(19).replace('T', ' '),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = (if (row.isCredit) "+" else "−") + formatUsd(row.amountUsd),
                        fontWeight = FontWeight.SemiBold,
                        color = if (row.isCredit) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SuperGrokCard(
    selectedPlan: String,
    onSelectPlan: (String) -> Unit,
    onOpenGrok: () -> Unit,
    onOpenPricing: () -> Unit,
    onOpenAccount: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Live SuperGrok Heavy utilisation is not available via any public API. " +
                    "xAI only exposes usage inside the official Grok / X apps and account UI. " +
                    "This section is for reference so you don’t confuse it with API credits.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text("Your consumer plan (label only)", style = MaterialTheme.typography.labelLarge)
            Column(modifier = Modifier.selectableGroup()) {
                SettingsRepository.CONSUMER_PLANS.forEach { (label, id) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedPlan == id,
                                onClick = { onSelectPlan(id) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedPlan == id,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }

            HorizontalDivider()

            Text(
                planBlurb(selectedPlan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (selectedPlan == SettingsRepository.CONSUMER_PLAN_SUPERGROK_HEAVY) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("SuperGrok Heavy (public plan notes)", fontWeight = FontWeight.SemiBold)
                        Text("• Highest consumer tier on grok.com / X (~\$300/mo)")
                        Text("• Priority access to frontier models & Heavy / multi-agent modes")
                        Text("• Higher rate limits than SuperGrok — still subject to fair-use windows")
                        Text("• Does not fund Tiny Ggrok or console.x.ai API prepaid credits")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenGrok, modifier = Modifier.weight(1f)) {
                    Text("grok.com")
                }
                OutlinedButton(onClick = onOpenPricing, modifier = Modifier.weight(1f)) {
                    Text("Pricing")
                }
                OutlinedButton(onClick = onOpenAccount, modifier = Modifier.weight(1f)) {
                    Text("Account")
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatUsd(amount: Double): String {
    val fmt = NumberFormat.getCurrencyInstance(Locale.US)
    fmt.currency = Currency.getInstance("USD")
    return fmt.format(amount)
}

private fun formatIntString(raw: String): String {
    val n = raw.toLongOrNull() ?: return raw
    return NumberFormat.getIntegerInstance(Locale.US).format(n)
}

private fun shortenId(id: String): String =
    if (id.length <= 12) id else id.take(8) + "…" + id.takeLast(4)

private fun planBlurb(plan: String): String = when (plan) {
    SettingsRepository.CONSUMER_PLAN_SUPERGROK_HEAVY ->
        "You marked SuperGrok Heavy. Check remaining consumer quotas inside the official Grok app " +
            "or grok.com — this app cannot read those counters."
    SettingsRepository.CONSUMER_PLAN_SUPERGROK ->
        "You marked SuperGrok. Consumer limits live on grok.com / X, not on the developer API."
    SettingsRepository.CONSUMER_PLAN_SUPERGROK_LITE ->
        "You marked SuperGrok Lite. Consumer limits live on grok.com / X, not on the developer API."
    else ->
        "No SuperGrok subscription marked. This app only needs API prepaid credits to chat."
}
