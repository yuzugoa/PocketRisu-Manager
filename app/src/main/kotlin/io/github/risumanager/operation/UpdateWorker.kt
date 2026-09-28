package io.github.risumanager.operation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.risumanager.R
import io.github.risumanager.RisuManagerApplication
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.ProgressSink

class UpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val container = (appContext as RisuManagerApplication).container

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground("Preparing PocketRisu update", "Waiting for durable update state")

    override suspend fun doWork(): Result {
        val operationId = inputData.getString(KEY_OPERATION_ID)
            ?: return Result.failure(error("INVALID_OPERATION", "Update operation ID is missing."))
        setForeground(getForegroundInfo())
        return when (val result = container.updateService.execute(operationId, ProgressSink(::reportProgress))) {
            is UpdateExecutionResult.Success -> Result.success(
                Data.Builder().putString(KEY_OPERATION_ID, result.operation.operationId).build(),
            )
            is UpdateExecutionResult.Failure -> Result.failure(
                Data.Builder()
                    .putString(KEY_ERROR_CODE, result.code)
                    .putString(KEY_ERROR_MESSAGE, result.message)
                    .putBoolean(KEY_RESTORED, result.restored)
                    .putBoolean(KEY_RECOVERY_REQUIRED, result.recoveryRequired)
                    .build(),
            )
            is UpdateExecutionResult.Retry -> {
                setProgress(Data.Builder().putString(KEY_STAGE, "Reconciling").putString(KEY_DETAIL, result.message).build())
                Result.retry()
            }
        }
    }

    private suspend fun reportProgress(running: OperationState.Running) {
        setProgress(Data.Builder().putString(KEY_STAGE, running.stage).putString(KEY_DETAIL, running.detail).build())
        setForeground(foreground("Updating PocketRisu", running.detail ?: running.stage))
    }

    private fun foreground(title: String, detail: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.operation_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
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
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun error(code: String, message: String): Data = Data.Builder()
        .putString(KEY_ERROR_CODE, code)
        .putString(KEY_ERROR_MESSAGE, message)
        .build()

    companion object {
        const val KEY_OPERATION_ID = "update-operation-id"
        const val KEY_STAGE = "update-stage"
        const val KEY_DETAIL = "update-detail"
        const val KEY_ERROR_CODE = "update-error-code"
        const val KEY_ERROR_MESSAGE = "update-error-message"
        const val KEY_RESTORED = "update-restored"
        const val KEY_RECOVERY_REQUIRED = "update-recovery-required"
        private const val CHANNEL_ID = "risu-operations"
        private const val NOTIFICATION_ID = 4204
    }
}
