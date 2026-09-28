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
import io.github.risumanager.domain.model.RecoveryEstimate
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.UpdateOperation
import io.github.risumanager.domain.planning.UpdatePlan
import io.github.risumanager.domain.planning.UpdatePlanner
import io.github.risumanager.domain.model.SourceRevision
import kotlinx.coroutines.flow.first
import java.util.UUID

data class UpdateRequest(val operation: UpdateOperation, val workId: UUID)

class UpdateCoordinator(
    context: Context,
    private val instances: InstanceRepository,
    private val service: UpdateService,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun estimate(instanceId: String): RecoveryEstimate = service.estimate(instanceId)

    suspend fun update(
        instanceId: String,
        targetRevision: String,
        targetVersion: String? = null,
        retentionSeconds: Long = DEFAULT_RECOVERY_RETENTION_SECONDS,
    ): UpdateRequest {
        require(retentionSeconds in MIN_RECOVERY_RETENTION_SECONDS..MAX_RECOVERY_RETENTION_SECONDS) {
            "Recovery retention is outside the supported range"
        }
        val instance = requireNotNull(instances.get(instanceId)) { "Instance no longer exists" }
        val plan = UpdatePlanner.plan(instance, SourceRevision(targetRevision, displayVersion = targetVersion))
        require(plan is UpdatePlan.Ready) {
            when (plan) {
                UpdatePlan.AlreadyCurrent -> "PocketRisu is already at the selected target"
                is UpdatePlan.Invalid -> plan.reason
                else -> "The selected update path is not supported"
            }
        }
        val estimate = service.estimate(instanceId)
        require(estimate.hasEnoughSpace) { "There is not enough free storage for a verified recovery point" }
        val now = System.currentTimeMillis()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val operationId = "update_$suffix"
        val recoveryId = RecoveryPointId("recovery_$suffix")
        val operation = UpdateOperation(
            operationId = operationId,
            instanceId = instance.instanceId,
            variantId = instance.variantId,
            layoutVersion = SUPPORTED_LAYOUT_VERSION,
            targetRevision = targetRevision.lowercase(),
            targetVersion = targetVersion,
            currentRevision = requireNotNull(instance.installedRevision).lowercase(),
            currentVersion = instance.installedVersion,
            recoveryPointId = recoveryId,
            retentionSeconds = retentionSeconds,
            createdAt = now,
            updatedAt = now,
        )
        val recoveryPoint = RecoveryPoint(
            id = recoveryId,
            instanceId = instance.instanceId,
            createdAt = now,
            serverRevision = instance.installedRevision,
            serverVersion = instance.installedVersion,
            dataDigestSha256 = "",
            configurationDigestSha256 = "",
            formatVersion = RECOVERY_FORMAT_VERSION,
            variantId = instance.variantId,
            layoutVersion = SUPPORTED_LAYOUT_VERSION,
            retentionSeconds = retentionSeconds,
            inUse = true,
            state = RecoveryPointState.CREATING,
        )
        instances.prepareUpdate(operation, recoveryPoint)
        return enqueue(operation)
    }

    suspend fun reconcilePending(): List<UpdateRequest> = instances.activeUpdateOperations().map { enqueue(it) }

    suspend fun cleanupExpired(now: Long = System.currentTimeMillis()): CleanupResult = service.cleanupExpired(now)

    private suspend fun enqueue(operation: UpdateOperation): UpdateRequest {
        val uniqueWorkName = "$UPDATE_INSTANCE_WORK_PREFIX${operation.instanceId}"
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInputData(Data.Builder().putString(UpdateWorker.KEY_OPERATION_ID, operation.operationId).build())
            .addTag(OperationCoordinator.OPERATION_TAG)
            .addTag(UPDATE_TAG)
            .addTag("${OperationCoordinator.OPERATION_TAG_PREFIX}${operation.operationId}")
            .addTag("${OperationCoordinator.INSTANCE_TAG_PREFIX}${operation.instanceId}")
            .addTag("${OperationCoordinator.CREATED_TAG_PREFIX}${operation.createdAt}")
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, request).await()
        val accepted = checkNotNull(
            selectAcceptedWork(workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first(), request.id),
        ) { "WorkManager did not retain the update reconciliation work." }
        return UpdateRequest(operation, accepted.id)
    }

    companion object {
        const val UPDATE_TAG = "risu-update"
        private const val UPDATE_INSTANCE_WORK_PREFIX = "risu-update-instance-"
        private const val SUPPORTED_LAYOUT_VERSION = 1
        private const val RECOVERY_FORMAT_VERSION = 1
    }
}
