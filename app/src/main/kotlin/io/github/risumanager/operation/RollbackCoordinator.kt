package io.github.risumanager.operation

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import io.github.risumanager.data.repository.InstanceRepository
import io.github.risumanager.domain.model.DEFAULT_RECOVERY_RETENTION_SECONDS
import io.github.risumanager.domain.model.MAX_RECOVERY_RETENTION_SECONDS
import io.github.risumanager.domain.model.MIN_RECOVERY_RETENTION_SECONDS
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.RollbackOperation
import io.github.risumanager.domain.model.RollbackPreview
import kotlinx.coroutines.flow.first
import java.util.UUID

data class RollbackRequest(val operation: RollbackOperation, val workId: UUID)

class RollbackCoordinator(
    context: Context,
    private val instances: InstanceRepository,
    private val service: RollbackService,
    private val clones: CloneCoordinator,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun recoveryPoints(instanceId: String): List<RecoveryPoint> = instances.recoveryPoints(instanceId)

    suspend fun inspect(instanceId: String, recoveryPointId: RecoveryPointId): RollbackPreview =
        service.inspect(instanceId, recoveryPointId)

    suspend fun rollback(
        instanceId: String,
        recoveryPointId: RecoveryPointId,
        retentionSeconds: Long = DEFAULT_RECOVERY_RETENTION_SECONDS,
    ): RollbackRequest {
        require(retentionSeconds in MIN_RECOVERY_RETENTION_SECONDS..MAX_RECOVERY_RETENTION_SECONDS) {
            "Recovery retention is outside the supported range"
        }
        val preview = service.inspect(instanceId, recoveryPointId)
        require(preview.available) { preview.availabilityReason ?: "Recovery point is unavailable" }
        preview.requiredBytes?.let { required ->
            preview.availableBytes?.let { available -> require(available >= required) { "There is not enough free storage for safe rollback" } }
        }
        val instance = requireNotNull(instances.get(instanceId)) { "Instance no longer exists" }
        val selected = requireNotNull(instances.recoveryPoint(recoveryPointId)) { "Recovery point no longer exists" }
        val now = System.currentTimeMillis()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val operation = RollbackOperation(
            operationId = "rollback_$suffix",
            instanceId = instance.instanceId,
            variantId = instance.variantId,
            layoutVersion = selected.layoutVersion,
            selectedRecoveryPointId = selected.id,
            preRollbackRecoveryPointId = RecoveryPointId("recovery_$suffix"),
            currentRevision = requireNotNull(instance.installedRevision) { "Installed revision is unavailable" }.lowercase(),
            currentVersion = instance.installedVersion,
            targetRevision = requireNotNull(selected.serverRevision) { "Recovery point revision is unavailable" }.lowercase(),
            targetVersion = selected.serverVersion,
            retentionSeconds = retentionSeconds,
            createdAt = now,
            updatedAt = now,
        )
        val preRollbackPoint = RecoveryPoint(
            id = operation.preRollbackRecoveryPointId,
            instanceId = instance.instanceId,
            createdAt = now,
            serverRevision = instance.installedRevision,
            serverVersion = instance.installedVersion,
            dataDigestSha256 = "",
            configurationDigestSha256 = "",
            formatVersion = 1,
            variantId = instance.variantId,
            layoutVersion = selected.layoutVersion,
            retentionSeconds = retentionSeconds,
            inUse = true,
            state = RecoveryPointState.CREATING,
        )
        instances.prepareRollback(operation, preRollbackPoint)
        return enqueue(operation)
    }

    suspend fun setPinned(id: RecoveryPointId, pinned: Boolean) = service.setPinned(id, pinned)

    suspend fun delete(id: RecoveryPointId) = service.delete(id)

    suspend fun clone(instanceId: String, id: RecoveryPointId, name: String): CloneRequest =
        clones.cloneFromRecovery(instanceId, id, name)

    suspend fun reconcilePending(): List<RollbackRequest> = instances.activeRollbackOperations().map { enqueue(it) }

    private suspend fun enqueue(operation: RollbackOperation): RollbackRequest {
        val uniqueWorkName = "$ROLLBACK_INSTANCE_WORK_PREFIX${operation.instanceId}"
        val request = OneTimeWorkRequestBuilder<RollbackWorker>()
            .setInputData(Data.Builder().putString(RollbackWorker.KEY_OPERATION_ID, operation.operationId).build())
            .addTag(OperationCoordinator.OPERATION_TAG)
            .addTag(ROLLBACK_TAG)
            .addTag("${OperationCoordinator.OPERATION_TAG_PREFIX}${operation.operationId}")
            .addTag("${OperationCoordinator.INSTANCE_TAG_PREFIX}${operation.instanceId}")
            .addTag("${OperationCoordinator.CREATED_TAG_PREFIX}${operation.createdAt}")
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, request).await()
        val accepted = checkNotNull(
            selectAcceptedWork(workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first(), request.id),
        ) { "WorkManager did not retain rollback reconciliation work." }
        return RollbackRequest(operation, accepted.id)
    }

    companion object {
        const val ROLLBACK_TAG = "risu-rollback"
        private const val ROLLBACK_INSTANCE_WORK_PREFIX = "risu-rollback-instance-"
    }
}
