package com.tinyggrok.app.data.model

import com.google.gson.annotations.SerializedName

// ── Management key validation ───────────────────────────────────────────────

data class ManagementKeyValidation(
    val apiKeyId: String? = null,
    val teamId: String? = null,
    val scope: String? = null,
    val scopeId: String? = null,
    val name: String? = null,
    val acls: List<String>? = null
)

// ── Prepaid balance ─────────────────────────────────────────────────────────

data class PrepaidBalanceResponse(
    val changes: List<PrepaidBalanceChange>? = null,
    val total: UsdCents? = null
)

data class PrepaidBalanceChange(
    val teamId: String? = null,
    val changeOrigin: String? = null,
    val topupStatus: String? = null,
    val amount: UsdCents? = null,
    val invoiceNumber: String? = null,
    val createTime: String? = null,
    val createTs: String? = null
)

// ── Spending limits ─────────────────────────────────────────────────────────

data class SpendingLimitsResponse(
    val spendingLimits: SpendingLimits? = null
)

data class SpendingLimits(
    val hardSlOverride: UsdCents? = null,
    val hardSlAuto: UsdCents? = null,
    val effectiveHardSl: UsdCents? = null,
    val softSl: UsdCents? = null,
    val effectiveSl: UsdCents? = null
)

// ── Postpaid invoice preview (current period) ───────────────────────────────

data class InvoicePreviewResponse(
    val coreInvoice: CoreInvoice? = null,
    val effectiveSpendingLimit: String? = null,
    val defaultCredits: String? = null,
    val billingCycle: BillingCycle? = null
)

data class CoreInvoice(
    val amountBeforeVat: String? = null,
    val vatCost: String? = null,
    val amountAfterVat: String? = null,
    val autoCreditsIssued: String? = null,
    val defaultCreditsIssued: String? = null,
    val prepaidCredits: UsdCents? = null,
    val prepaidCreditsUsed: UsdCents? = null,
    val totalWithCorr: UsdCents? = null
)

data class BillingCycle(
    val year: Int? = null,
    val month: Int? = null
)

// ── Team models (rate limits / quota) ───────────────────────────────────────

data class TeamModelsResponse(
    val clusterConfigs: List<ClusterConfig>? = null
)

data class ClusterConfig(
    val clusterName: String? = null,
    val languageModels: List<LanguageModelInfo>? = null
)

data class LanguageModelInfo(
    val name: String? = null,
    val rps: String? = null,
    val rpm: String? = null,
    val tpm: String? = null,
    val rph: String? = null,
    val rpd: String? = null,
    val maxPromptLength: Int? = null,
    val aliases: List<String>? = null
)

/** Money amount stored by xAI as USD cents (string integer). */
data class UsdCents(
    val `val`: String? = null
)

/**
 * Convert xAI prepaid-balance style cents to a display dollar amount.
 *
 * Prepaid accounting uses a sign convention where purchases are negative and
 * spends are positive. Remaining credit is therefore typically a **negative**
 * total (e.g. `"-4500"` → **$45.00** remaining).
 */
fun UsdCents?.remainingCreditUsd(): Double {
    val cents = this?.`val`?.toLongOrNull() ?: return 0.0
    // Negative total → positive remaining balance
    return if (cents < 0) (-cents) / 100.0 else 0.0
}

/** Absolute dollar amount from a signed cents string (ignores sign). */
fun UsdCents?.absUsd(): Double {
    val cents = this?.`val`?.toLongOrNull() ?: return 0.0
    return kotlin.math.abs(cents) / 100.0
}

/** Absolute dollar amount from a plain cents string (may be signed). */
fun centsStringToUsd(cents: String?): Double {
    val v = cents?.toLongOrNull() ?: return 0.0
    return kotlin.math.abs(v) / 100.0
}
