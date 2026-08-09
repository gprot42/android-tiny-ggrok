package com.tinyggrok.app.data.repository

import com.tinyggrok.app.data.api.XaiManagementApiService
import com.tinyggrok.app.data.model.LanguageModelInfo
import com.tinyggrok.app.data.model.absUsd
import com.tinyggrok.app.data.model.centsStringToUsd
import com.tinyggrok.app.data.model.remainingCreditUsd
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

data class ApiUsageSnapshot(
    val teamId: String,
    val managementKeyName: String?,
    /** Prepaid credits remaining (USD). */
    val prepaidRemainingUsd: Double,
    /** Prepaid credits used this billing period, if reported (USD). */
    val prepaidUsedThisPeriodUsd: Double?,
    /** Soft postpaid spending limit (USD). 0 = prepaid-only. */
    val softSpendingLimitUsd: Double?,
    /** Hard / effective postpaid spending limit (USD). */
    val hardSpendingLimitUsd: Double?,
    /** Postpaid amount after VAT this period (USD). */
    val postpaidAmountThisPeriodUsd: Double?,
    val billingCycleYear: Int?,
    val billingCycleMonth: Int?,
    /** Rate limits for the currently selected chat model, if found. */
    val modelName: String?,
    val modelRpm: String?,
    val modelRps: String?,
    val modelTpm: String?,
    val modelRph: String?,
    val modelRpd: String?,
    val modelMaxPrompt: Int?,
    /** Recent prepaid balance changes (newest first, capped). */
    val recentChanges: List<BalanceChangeRow>,
    val fetchedAtEpochMs: Long = System.currentTimeMillis()
)

data class BalanceChangeRow(
    val origin: String,
    val amountUsd: Double,
    /** True if this change increased available credit (purchase/refund). */
    val isCredit: Boolean,
    val status: String?,
    val time: String?
)

@Singleton
class BillingRepository @Inject constructor(
    private val managementApi: XaiManagementApiService
) {

    suspend fun fetchUsage(
        managementKey: String,
        teamIdHint: String?,
        chatModel: String?
    ): Result<ApiUsageSnapshot> {
        if (managementKey.isBlank()) {
            return Result.failure(
                IllegalStateException(
                    "Add a Management Key in Settings to load live API credits. " +
                        "Create one at console.x.ai → Settings → Management Keys."
                )
            )
        }
        val auth = "Bearer $managementKey"
        return try {
            val validation = managementApi.validateManagementKey(auth)
            val teamId = teamIdHint?.takeIf { it.isNotBlank() }
                ?: validation.scopeId?.takeIf { it.isNotBlank() }
                ?: validation.teamId?.takeIf { it.isNotBlank() }
                ?: return Result.failure(
                    IllegalStateException(
                        "Could not resolve Team ID from the management key. " +
                            "Paste your Team ID from console.x.ai → Team settings."
                    )
                )

            val balance = managementApi.prepaidBalance(auth, teamId)
            val limits = runCatching { managementApi.spendingLimits(auth, teamId) }.getOrNull()
            val preview = runCatching { managementApi.invoicePreview(auth, teamId) }.getOrNull()
            val models = runCatching { managementApi.teamModels(auth, teamId) }.getOrNull()

            val modelInfo = findModel(models?.clusterConfigs?.flatMap {
                it.languageModels.orEmpty()
            }.orEmpty(), chatModel)

            val changes = balance.changes
                .orEmpty()
                .sortedByDescending { it.createTs ?: it.createTime.orEmpty() }
                .take(8)
                .map { c ->
                    val cents = c.amount?.`val`?.toLongOrNull() ?: 0L
                    // PURCHASE/REFUND amounts are negative; SPEND is positive.
                    BalanceChangeRow(
                        origin = c.changeOrigin ?: "UNKNOWN",
                        amountUsd = kotlin.math.abs(cents) / 100.0,
                        isCredit = cents < 0 ||
                            c.changeOrigin.equals("PURCHASE", true) ||
                            c.changeOrigin.equals("REFUND", true) ||
                            c.changeOrigin.equals("AUTO_PURCHASE", true),
                        status = c.topupStatus,
                        time = c.createTs ?: c.createTime
                    )
                }

            val prepaidFromBalance = balance.total.remainingCreditUsd()
            val prepaidFromPreview = preview?.coreInvoice?.prepaidCredits?.remainingCreditUsd()
            val remaining = when {
                prepaidFromBalance > 0 -> prepaidFromBalance
                prepaidFromPreview != null && prepaidFromPreview > 0 -> prepaidFromPreview
                else -> prepaidFromBalance
            }

            Result.success(
                ApiUsageSnapshot(
                    teamId = teamId,
                    managementKeyName = validation.name,
                    prepaidRemainingUsd = remaining,
                    prepaidUsedThisPeriodUsd = preview?.coreInvoice?.prepaidCreditsUsed?.absUsd(),
                    softSpendingLimitUsd = limits?.spendingLimits?.effectiveSl?.absUsd()
                        ?: limits?.spendingLimits?.softSl?.absUsd(),
                    hardSpendingLimitUsd = limits?.spendingLimits?.effectiveHardSl?.absUsd()
                        ?: limits?.spendingLimits?.hardSlAuto?.absUsd(),
                    postpaidAmountThisPeriodUsd = centsStringToUsd(
                        preview?.coreInvoice?.amountAfterVat
                    ).takeIf { preview?.coreInvoice?.amountAfterVat != null },
                    billingCycleYear = preview?.billingCycle?.year,
                    billingCycleMonth = preview?.billingCycle?.month,
                    modelName = modelInfo?.name ?: chatModel,
                    modelRpm = modelInfo?.rpm?.takeIf { it.isNotBlank() && it != "0" },
                    modelRps = modelInfo?.rps?.takeIf { it.isNotBlank() && it != "0" },
                    modelTpm = modelInfo?.tpm?.takeIf { it.isNotBlank() && it != "0" },
                    modelRph = modelInfo?.rph?.takeIf { it.isNotBlank() && it != "0" },
                    modelRpd = modelInfo?.rpd?.takeIf { it.isNotBlank() && it != "0" },
                    modelMaxPrompt = modelInfo?.maxPromptLength,
                    recentChanges = changes
                )
            )
        } catch (e: HttpException) {
            val body = try {
                e.response()?.errorBody()?.string().orEmpty()
            } catch (_: Throwable) {
                ""
            }
            val hint = when (e.code()) {
                401, 403 -> " Management key rejected — check key and team permissions."
                404 -> " Team or billing endpoint not found — check Team ID."
                else -> ""
            }
            Result.failure(
                RuntimeException(
                    "HTTP ${e.code()}: ${body.take(300).ifBlank { e.message() }}$hint"
                )
            )
        } catch (e: SocketTimeoutException) {
            Result.failure(RuntimeException("Timed out contacting management-api.x.ai."))
        } catch (e: UnknownHostException) {
            Result.failure(RuntimeException("Can't reach management-api.x.ai (DNS)."))
        } catch (e: Exception) {
            Result.failure(
                RuntimeException("${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
            )
        }
    }

    private fun findModel(
        models: List<LanguageModelInfo>,
        chatModel: String?
    ): LanguageModelInfo? {
        if (chatModel.isNullOrBlank() || models.isEmpty()) return null
        val want = chatModel.lowercase()
        return models.firstOrNull { it.name.equals(chatModel, ignoreCase = true) }
            ?: models.firstOrNull { m ->
                m.aliases.orEmpty().any { it.equals(chatModel, ignoreCase = true) }
            }
            ?: models.firstOrNull { m ->
                m.name?.lowercase()?.startsWith(want) == true ||
                    m.aliases.orEmpty().any { it.lowercase().startsWith(want) }
            }
    }
}
