package io.github.risumanager.operation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.risumanager.R
import io.github.risumanager.RisuManagerApplication
import io.github.risumanager.domain.model.BackupMetadata
import io.github.risumanager.domain.model.BackupReason
import io.github.risumanager.domain.model.OperationRecord
import io.github.risumanager.domain.model.OperationResultKind
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.OperationType
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RuntimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val container = (appContext as RisuManagerApplication).container

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Backing up PocketRisu", "Preparing a consistent snapshot", null)

    override suspend fun doWork(): Result {
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return Result.failure(error("INVALID_OPERATION"))
        val instanceId = inputData.getString(KEY_INSTANCE_ID) ?: return Result.failure(error("INVALID_INSTANCE"))
        val destination = inputData.getString(KEY_DESTINATION_URI)?.let(Uri::parse)
            ?: return Result.failure(error("INVALID_DESTINATION"))
        val instance = container.instances.get(instanceId)
            ?: return Result.failure(error("INSTANCE_NOT_FOUND", "The selected PocketRisu instance no longer exists."))
        if (instance.runtimeType != RuntimeType.TERMUX_NODE) {
            return Result.failure(error("BACKUP_UNSUPPORTED", "Backups are supported only for Termux PocketRisu instances."))
        }

        val startedAt = System.currentTimeMillis()
        val baseRecord = OperationRecord(
            operationId = operationId,
            instanceId = instanceId,
            type = OperationType.BACKUP,
            startedAt = startedAt,
        )
        container.instances.upsertOperation(baseRecord)
        return try {
            setForeground(foreground("Backing up PocketRisu", "Preparing a consistent snapshot", null))
            val exported = container.backups.export(
                instance = instance,
                operationId = operationId,
                destination = destination,
                progress = ProgressSink { running -> reportProgress(baseRecord, running) },
            )
            val endedAt = System.currentTimeMillis()
            container.instances.upsertBackup(
                BackupMetadata(
                    id = operationId,
                    instanceId = instanceId,
                    createdAt = endedAt,
                    reason = BackupReason.MANUAL,
                    sourceRevision = instance.installedRevision,
                    appVersion = instance.installedVersion,
                    size = exported.size,
                    formatVersion = exported.formatVersion,
                ),
            )
            container.instances.upsertOperation(
                baseRecord.copy(
                    endedAt = endedAt,
                    result = OperationResultKind.SUCCESS,
                    stage = "SAVED",
                    detail = "Backup saved; transfer verified (${exported.size} bytes, SHA-256 ${exported.sha256.take(12)}…)",
                ),
            )
            Result.success(
                Data.Builder()
                    .putLong(KEY_SIZE, exported.size)
                    .putString(KEY_SHA256, exported.sha256)
                    .build(),
            )
        } catch (exception: CancellationException) {
            withContext(NonCancellable) {
                container.instances.upsertOperation(
                    baseRecord.copy(
                        endedAt = System.currentTimeMillis(),
                        result = OperationResultKind.FAILURE,
                        stage = "CANCELLED",
                        detail = "Backup was cancelled. The selected document may be incomplete.",
                        errorCode = "BACKUP_CANCELLED",
                    ),
                )
            }
            throw exception
        } catch (exception: BackupException) {
            fail(baseRecord, exception.code, exception.message ?: "PocketRisu backup failed.")
        } catch (exception: Exception) {
            fail(baseRecord, "BACKUP_FAILED", exception.message ?: "PocketRisu backup failed.")
        }
    }

    private suspend fun fail(record: OperationRecord, code: String, message: String): Result {
        container.instances.upsertOperation(
            record.copy(
                endedAt = System.currentTimeMillis(),
                result = OperationResultKind.FAILURE,
                stage = "FAILED",
                detail = "$message The selected document may be incomplete.",
                errorCode = code,
            ),
        )
        return Result.failure(error(code, "$message The selected document may be incomplete."))
    }

    private suspend fun reportProgress(record: OperationRecord, running: OperationState.Running) {
        val data = Data.Builder()
            .putString(KEY_STAGE, running.stage)
            .putString(KEY_DETAIL, running.detail)
            .apply { running.progress?.let { putFloat(KEY_PROGRESS, it) } }
            .build()
        setProgress(data)
        setForeground(foreground("Backing up PocketRisu", running.detail ?: running.stage, running.progress))
        container.instances.upsertOperation(record.copy(stage = running.stage, detail = running.detail))
    }

    private fun foreground(title: String, detail: String, progress: Float?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.operation_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = applicationContext.getString(R.string.operation_channel_description)
                },
            )
        }
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (progress == null) builder.setProgress(0, 0, true)
        else builder.setProgress(1000, (progress.coerceIn(0f, 1f) * 1000).toInt(), false)
        val notificationId = NOTIFICATION_ID_BASE + (id.hashCode() and 0x7ff)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, builder.build())
        }
    }

    private fun error(code: String, message: String? = null): Data = Data.Builder()
        .putString(KEY_ERROR_CODE, code)
        .putString(KEY_ERROR_MESSAGE, message)
        .build()

    companion object {
        const val KEY_OPERATION_ID = "operation-id"
        const val KEY_INSTANCE_ID = "instance-id"
        const val KEY_DESTINATION_URI = "destination-uri"
        const val KEY_STAGE = "stage"
        const val KEY_DETAIL = "detail"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR_CODE = "error-code"
        const val KEY_ERROR_MESSAGE = "error-message"
        const val KEY_SIZE = "size"
        const val KEY_SHA256 = "sha256"
        private const val CHANNEL_ID = "risu-operations"
        private const val NOTIFICATION_ID_BASE = 5000
    }
}
