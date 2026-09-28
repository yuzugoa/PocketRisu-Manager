package io.github.risumanager.operation

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import io.github.risumanager.domain.validation.Validators
import io.github.risumanager.data.repository.InstanceRepository
import io.github.risumanager.domain.model.CloneOperation
import io.github.risumanager.domain.model.RecoveryPointId
import kotlinx.coroutines.flow.first
import java.util.UUID

data class InstallRequest(
    val operationId: String,
    val instanceId: String,
    val workId: UUID,
)

data class BackupRequest(
    val operationId: String,
    val workId: UUID,
)

data class CloneRequest(val operation: CloneOperation, val workId: UUID)

class CloneCoordinator(
    context: Context,
    private val instances: InstanceRepository,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun clone(sourceInstanceId: String, displayName: String): CloneRequest {
        require(Validators.isInstanceId(sourceInstanceId))
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val operationId = "clone_${UUID.randomUUID().toString().replace("-", "")}"
        val operation = instances.prepareClone(
            operationId = operationId,
            sourceInstanceId = sourceInstanceId,
            targetInstanceId = "pocketrisu-$suffix",
            targetDisplayName = displayName,
        )
        return enqueue(operation)
    }

    suspend fun cloneFromRecovery(
        sourceInstanceId: String,
        recoveryPointId: RecoveryPointId,
        displayName: String,
    ): CloneRequest {
        require(Validators.isInstanceId(sourceInstanceId))
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val operationId = "clone_${UUID.randomUUID().toString().replace("-", "")}"
        val operation = instances.prepareRecoveryClone(
            operationId = operationId,
            sourceInstanceId = sourceInstanceId,
            recoveryPointId = recoveryPointId,
            targetInstanceId = "pocketrisu-$suffix",
            targetDisplayName = displayName,
        )
        return enqueue(operation)
    }

    suspend fun reconcilePending(): List<CloneRequest> = instances.activeCloneOperations().map { enqueue(it) }

    private suspend fun enqueue(operation: CloneOperation): CloneRequest {
        val uniqueWorkName = "$CLONE_SOURCE_WORK_PREFIX${operation.sourceInstanceId}"
        val request = OneTimeWorkRequestBuilder<CloneWorker>()
            .setInputData(Data.Builder().putString(CloneWorker.KEY_OPERATION_ID, operation.operationId).build())
            .addTag(OperationCoordinator.OPERATION_TAG)
            .addTag(CLONE_TAG)
            .addTag("${OperationCoordinator.OPERATION_TAG_PREFIX}${operation.operationId}")
            .addTag("${OperationCoordinator.INSTANCE_TAG_PREFIX}${operation.sourceInstanceId}")
            .addTag("$TARGET_TAG_PREFIX${operation.targetInstanceId}")
            .addTag("${OperationCoordinator.CREATED_TAG_PREFIX}${operation.createdAt}")
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, request).await()
        val accepted = checkNotNull(selectAcceptedWork(workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first(), request.id)) {
            "WorkManager did not retain the clone reconciliation work."
        }
        return CloneRequest(operation, accepted.id)
    }

    companion object {
        const val CLONE_TAG = "risu-clone"
        const val TARGET_TAG_PREFIX = "risu-clone-target-"
        private const val CLONE_SOURCE_WORK_PREFIX = "risu-clone-source-"
    }
}

class OperationCoordinator(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun install(variantId: String, channelId: String, requestedCommit: String?, resumeInstanceId: String? = null): InstallRequest {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val instanceId = resumeInstanceId?.also {
            require(Validators.isInstanceId(it)) { "The resumable instance ID is invalid." }
            require(it.startsWith("$variantId-")) { "The resumable instance belongs to another variant." }
        } ?: "$variantId-$suffix"
        val operationId = "install_${UUID.randomUUID().toString().replace("-", "")}"
        val uniqueWorkName = "$OPERATION_TAG_PREFIX$operationId"
        val createdAt = System.currentTimeMillis()
        val input = Data.Builder()
            .putString(RisuOperationWorker.KEY_OPERATION_ID, operationId)
            .putString(RisuOperationWorker.KEY_INSTANCE_ID, instanceId)
            .putString(RisuOperationWorker.KEY_VARIANT_ID, variantId)
            .putString(RisuOperationWorker.KEY_CHANNEL_ID, channelId)
            .putString(RisuOperationWorker.KEY_REQUESTED_COMMIT, requestedCommit)
            .build()
        val request = OneTimeWorkRequestBuilder<RisuOperationWorker>()
            .setInputData(input)
            .addTag(OPERATION_TAG)
            .addTag(INSTALL_TAG)
            .addTag(uniqueWorkName)
            .addTag("$INSTANCE_TAG_PREFIX$instanceId")
            .addTag("$VARIANT_TAG_PREFIX$variantId")
            .addTag("$CHANNEL_TAG_PREFIX$channelId")
            .addTag("$CREATED_TAG_PREFIX$createdAt")
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, request).await()
        val workInfos = workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first()
        val accepted = checkNotNull(selectAcceptedWork(workInfos, request.id)) {
            "WorkManager did not retain the queued operation."
        }
        return InstallRequest(operationId, instanceId, accepted.id)
    }

    suspend fun backup(instanceId: String, destination: Uri): BackupRequest {
        val operationId = "backup_${UUID.randomUUID().toString().replace("-", "")}"
        val uniqueWorkName = "$BACKUP_TAG-$instanceId"
        val createdAt = System.currentTimeMillis()
        val input = Data.Builder()
            .putString(BackupWorker.KEY_OPERATION_ID, operationId)
            .putString(BackupWorker.KEY_INSTANCE_ID, instanceId)
            .putString(BackupWorker.KEY_DESTINATION_URI, destination.toString())
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(input)
            .addTag(OPERATION_TAG)
            .addTag(BACKUP_TAG)
            .addTag("$OPERATION_TAG_PREFIX$operationId")
            .addTag("$INSTANCE_TAG_PREFIX$instanceId")
            .addTag("$CREATED_TAG_PREFIX$createdAt")
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, request).await()
        val workInfos = workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first()
        val accepted = checkNotNull(selectAcceptedWork(workInfos, request.id)) {
            "WorkManager did not retain the queued operation."
        }
        val acceptedOperationId = accepted.tagValue(OPERATION_TAG_PREFIX) ?: operationId
        return BackupRequest(acceptedOperationId, accepted.id)
    }

    companion object {
        const val OPERATION_TAG = "risu-operation"
        const val INSTALL_TAG = "risu-install"
        const val BACKUP_TAG = "risu-backup"
        const val OPERATION_TAG_PREFIX = "risu-operation-"
        const val INSTANCE_TAG_PREFIX = "risu-instance-"
        const val VARIANT_TAG_PREFIX = "risu-variant-"
        const val CHANNEL_TAG_PREFIX = "risu-channel-"
        const val CREATED_TAG_PREFIX = "risu-created-"

    }
}
