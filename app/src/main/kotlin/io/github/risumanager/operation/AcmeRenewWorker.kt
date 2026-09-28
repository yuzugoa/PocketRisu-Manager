package io.github.risumanager.operation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.risumanager.RisuManagerApplication
import io.github.risumanager.domain.model.CertificateMode
import java.util.concurrent.TimeUnit

/**
 * Gives acme.sh regular renewal opportunities. acme.sh remains responsible for
 * deciding whether a production certificate is actually due for renewal.
 */
class AcmeRenewWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val container = (appContext as RisuManagerApplication).container

    override suspend fun doWork(): Result {
        var failures = 0
        var firstError: String? = null
        container.instances.serverConfigs()
            .filter { it.certificateMode == CertificateMode.LETS_ENCRYPT && it.autoRenew }
            .forEach { config ->
                val instance = container.instances.get(config.instanceId)
                if (instance?.variantId != POCKETRISU_VARIANT) return@forEach
                val checkedAt = System.currentTimeMillis()
                runCatching { container.termuxRuntime.acmeRenew(config.instanceId) }
                    .onSuccess { response ->
                        val updated = config.copy(
                            certificateExpiresAt = response.certificateExpiresAt ?: config.certificateExpiresAt,
                            lastRenewCheckAt = response.lastRenewCheckAt ?: checkedAt,
                            lastRenewSuccessAt = response.lastRenewSuccessAt ?: config.lastRenewSuccessAt,
                            lastRenewError = null,
                            renewFailureCount = 0,
                        )
                        container.instances.upsertServerConfig(updated)
                    }
                    .onFailure { error ->
                        failures += 1
                        val message = sanitizeError(error.message)
                        if (firstError == null) firstError = message
                        container.instances.upsertServerConfig(
                            config.copy(
                                lastRenewCheckAt = checkedAt,
                                lastRenewError = message,
                                renewFailureCount = (config.renewFailureCount + 1).coerceAtMost(1_000_000),
                            ),
                        )
                    }
            }

        if (failures == 0) return Result.success()
        if (runAttemptCount < MAX_RETRY_ATTEMPTS) return Result.retry()
        return Result.failure(
            Data.Builder()
                .putInt(KEY_FAILURE_COUNT, failures)
                .putString(KEY_ERROR_MESSAGE, firstError)
                .build(),
        )
    }

    private fun sanitizeError(message: String?): String = message
        .orEmpty()
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .trim()
        .take(MAX_ERROR_LENGTH)
        .ifBlank { "Certificate renewal check failed." }

    companion object {
        const val KEY_FAILURE_COUNT = "failure-count"
        const val KEY_ERROR_MESSAGE = "error-message"
        private const val POCKETRISU_VARIANT = "pocketrisu"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val MAX_ERROR_LENGTH = 300
    }
}

object AcmeRenewScheduler {
    private const val PERIODIC_WORK = "risu-acme-renew-periodic"
    private const val STARTUP_WORK = "risu-acme-renew-startup"
    private const val TAG = "risu-acme-renew"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<AcmeRenewWorker>(
            24, TimeUnit.HOURS,
            6, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        val startup = OneTimeWorkRequestBuilder<AcmeRenewWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).apply {
            enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic)
            enqueueUniqueWork(STARTUP_WORK, ExistingWorkPolicy.KEEP, startup)
        }
    }
}
