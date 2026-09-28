package io.github.risumanager.operation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.risumanager.R
import io.github.risumanager.RisuManagerApplication
import io.github.risumanager.domain.model.BuiltInVariants
import io.github.risumanager.domain.model.OperationRecord
import io.github.risumanager.domain.model.OperationResult
import io.github.risumanager.domain.model.OperationResultKind
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.OperationType
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.InstallStrategy
import io.github.risumanager.domain.planning.InstallArtifactSelector

class RisuOperationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val container = (appContext as RisuManagerApplication).container

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Preparing operation", "Risu Manager is working")

    override suspend fun doWork(): Result {
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return Result.failure(error("INVALID_OPERATION"))
        val instanceId = inputData.getString(KEY_INSTANCE_ID) ?: return Result.failure(error("INVALID_INSTANCE"))
        val variantId = inputData.getString(KEY_VARIANT_ID) ?: return Result.failure(error("INVALID_VARIANT"))
        val channelId = inputData.getString(KEY_CHANNEL_ID) ?: return Result.failure(error("INVALID_CHANNEL"))
        val requestedCommit = inputData.getString(KEY_REQUESTED_COMMIT)
        val variant = BuiltInVariants.find(variantId) ?: return Result.failure(error("UNKNOWN_VARIANT"))
        val channel = variant.channels.firstOrNull { it.id == channelId } ?: return Result.failure(error("UNKNOWN_CHANNEL"))
        val startedAt = System.currentTimeMillis()
        val baseRecord = OperationRecord(
            operationId = operationId,
            instanceId = instanceId,
            type = OperationType.INSTALL,
            startedAt = startedAt,
        )
        container.instances.upsertOperation(baseRecord)
        setForeground(foreground("Installing ${variant.displayName}", "Resolving source revision"))

        return try {
            var target = container.sources.resolve(variant, channel, requestedCommit)
            if (variant.termuxProfile?.installStrategy == InstallStrategy.RELEASE_ARCHIVE) {
                require(channel.type == io.github.risumanager.domain.model.ChannelType.GITHUB_RELEASE) {
                    "Release archive profiles require a GitHub release channel"
                }
                val release = container.sources.latestRelease(variant.repository)
                require(release.revision.sha == target.sha) { "Release metadata changed while resolving the install" }
                val artifact = requireNotNull(InstallArtifactSelector.select(variant.termuxProfile, release)) {
                    "The release has no asset matching the trusted archive and checksum profile"
                }
                target = target.copy(installArtifact = artifact)
            }
            val existing = container.instances.get(instanceId)
            val instance = existing ?: RisuInstance(
                instanceId = instanceId,
                variantId = variant.id,
                channelId = channel.id,
                runtimeType = variant.runtimeType,
                repository = variant.repository,
                createdAt = startedAt,
                updatedAt = startedAt,
                lastKnownStatus = RuntimeState.STOPPED,
            )
            val runtime = container.runtimes.get(variant.runtimeType)
            val result = runtime.install(
                variant = variant,
                instance = instance,
                target = target,
                operationId = operationId,
                progress = ProgressSink { progress -> reportProgress(baseRecord, progress, target.sha, variant.displayName) },
            )
            when (result) {
                is OperationResult.Success -> {
                    val now = System.currentTimeMillis()
                    container.instances.upsert(
                        instance.copy(
                            installedRevision = result.revision ?: target.sha,
                            installedVersion = result.version ?: target.displayVersion,
                            latestRevision = target.sha,
                            port = result.port ?: instance.port,
                            updatedAt = now,
                            lastKnownStatus = RuntimeState.STOPPED,
                        ),
                    )
                    container.instances.upsertOperation(
                        baseRecord.copy(
                            endedAt = now,
                            result = OperationResultKind.SUCCESS,
                            stage = "READY",
                            detail = result.message,
                            toRevision = target.sha,
                        ),
                    )
                    Result.success(Data.Builder().putString(KEY_INSTANCE_ID, instanceId).build())
                }
                is OperationResult.Failure -> {
                    container.instances.upsertOperation(
                        baseRecord.copy(
                            endedAt = System.currentTimeMillis(),
                            result = OperationResultKind.FAILURE,
                            detail = result.message,
                            toRevision = target.sha,
                            errorCode = result.code,
                        ),
                    )
                    Result.failure(error(result.code, result.message))
                }
            }
        } catch (exception: Exception) {
            container.instances.upsertOperation(
                baseRecord.copy(
                    endedAt = System.currentTimeMillis(),
                    result = OperationResultKind.FAILURE,
                    detail = exception.message,
                    errorCode = "INSTALL_PREPARATION_FAILED",
                ),
            )
            Result.failure(error("INSTALL_PREPARATION_FAILED", exception.message))
        }
    }

    private suspend fun reportProgress(base: OperationRecord, progress: OperationState.Running, targetRevision: String, variantName: String) {
        val data = Data.Builder()
            .putString(KEY_STAGE, progress.stage)
            .putString(KEY_DETAIL, progress.detail)
            .apply { progress.progress?.let { putFloat(KEY_PROGRESS, it) } }
            .build()
        setProgress(data)
        setForeground(foreground("Installing $variantName", progress.detail ?: progress.stage))
        container.instances.upsertOperation(
            base.copy(stage = progress.stage, detail = progress.detail, toRevision = targetRevision),
        )
    }

    private fun foreground(title: String, detail: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.operation_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = applicationContext.getString(R.string.operation_channel_description)
                },
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun error(code: String, message: String? = null): Data = Data.Builder()
        .putString(KEY_ERROR_CODE, code)
        .putString(KEY_ERROR_MESSAGE, message)
        .build()

    companion object {
        const val KEY_OPERATION_ID = "operation-id"
        const val KEY_INSTANCE_ID = "instance-id"
        const val KEY_VARIANT_ID = "variant-id"
        const val KEY_CHANNEL_ID = "channel-id"
        const val KEY_REQUESTED_COMMIT = "requested-commit"
        const val KEY_STAGE = "stage"
        const val KEY_DETAIL = "detail"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR_CODE = "error-code"
        const val KEY_ERROR_MESSAGE = "error-message"
        private const val CHANNEL_ID = "risu-operations"
        private const val NOTIFICATION_ID = 4201
    }
}
