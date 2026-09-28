package io.github.risumanager.operation

import io.github.risumanager.data.repository.InstanceRepository
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RecoveryEstimate
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.RestorationState
import io.github.risumanager.domain.model.UpdateOperation
import io.github.risumanager.domain.model.UpdateResult
import io.github.risumanager.domain.model.UpdateStage
import io.github.risumanager.domain.validation.Validators
import io.github.risumanager.runtime.termux.HelperProtocolException
import io.github.risumanager.runtime.termux.HelperResponse
import io.github.risumanager.runtime.termux.UpdateCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

sealed interface UpdateExecutionResult {
    data class Success(val operation: UpdateOperation) : UpdateExecutionResult
    data class Failure(
        val code: String,
        val message: String,
        val restored: Boolean,
        val recoveryRequired: Boolean,
    ) : UpdateExecutionResult
    data class Retry(val message: String) : UpdateExecutionResult
}

data class CleanupResult(val attempted: Int, val deleted: Int, val failed: Int)

class UpdateService(
    private val helper: UpdateCommands,
    private val instances: InstanceRepository,
    private val operationGate: InstanceOperationGate,
) {
    suspend fun estimate(instanceId: String): RecoveryEstimate {
        val instance = requireNotNull(instances.get(instanceId)) { "Instance no longer exists" }
        require(instance.variantId == "pocketrisu") { "Updates are supported only for PocketRisu" }
        helper.ensureInstalled()
        val response = helper.recoveryEstimate(instanceId)
        require(response.ok && response.operation == "recovery-estimate") {
            response.message ?: "The helper could not estimate recovery storage"
        }
        require(response.instanceId == instanceId) { "Recovery estimate identity mismatch" }
        require(response.currentCommit.equals(instance.installedRevision, ignoreCase = true)) {
            "Installed metadata differs from the active PocketRisu checkout"
        }
        val size = requireNotNull(response.size) { "Recovery estimate omitted installed size" }
        val required = requireNotNull(response.requiredBytes) { "Recovery estimate omitted required storage" }
        val available = requireNotNull(response.availableBytes) { "Recovery estimate omitted available storage" }
        require(size >= 0 && required >= size && available >= 0) { "Recovery estimate contained invalid sizes" }
        return RecoveryEstimate(instanceId, size, required, available)
    }

    suspend fun execute(operationId: String, progress: ProgressSink): UpdateExecutionResult {
        val initial = instances.updateOperation(operationId)
            ?: return UpdateExecutionResult.Failure(
                "UPDATE_OPERATION_MISSING", "Update operation record is missing.", restored = false, recoveryRequired = false,
            )
        terminalResult(initial)?.let { return it }
        return operationGate.withInstance(initial.instanceId) {
            try {
                helper.ensureInstalled()
                withTimeout(UPDATE_TIMEOUT_MILLIS) {
                    var dispatched = false
                    while (true) {
                        val current = requireNotNull(instances.updateOperation(operationId))
                        terminalResult(current)?.let { return@withTimeout it }
                        val response = try {
                            helper.operation(operationId)
                        } catch (exception: HelperProtocolException) {
                            if (exception.code != "OPERATION_NOT_FOUND") throw exception
                            if (!dispatched) {
                                val accepted = helper.dispatchUpdate(
                                    current.operationId,
                                    current.instanceId,
                                    current.variantId,
                                    current.targetRevision,
                                    current.recoveryPointId.value,
                                    current.retentionSeconds,
                                )
                                if (!accepted.ok && accepted.state != "FAILED") {
                                    val code = accepted.code ?: "UPDATE_DISPATCH_FAILED"
                                    if (code in SAFE_DISPATCH_REJECTION_CODES) {
                                        return@withTimeout failBeforeMutation(
                                            current,
                                            code,
                                            accepted.message ?: "The helper rejected the update before changing the instance.",
                                        )
                                    }
                                    throw HelperProtocolException(
                                        code,
                                        accepted.message ?: "The helper could not confirm update dispatch.",
                                    )
                                }
                                validateIdentity(current, accepted)
                                dispatched = true
                                if (accepted.state == "FAILED") return@withTimeout finishFailure(current, accepted)
                                require(accepted.state == "QUEUED" || accepted.state == "RUNNING") {
                                    "The helper returned an invalid update dispatch state"
                                }
                                reportAndPersist(current, UpdateStage.PREPARED, accepted.detail, progress)
                            }
                            delay(POLL_INTERVAL_MILLIS)
                            continue
                        }

                        validateIdentity(current, response)
                        when (response.state) {
                            "QUEUED", "RUNNING" -> persistRunning(current, response, progress)
                            "SUCCEEDED" -> return@withTimeout finishSuccess(current, response)
                            "FAILED" -> return@withTimeout finishFailure(current, response)
                            else -> throw HelperProtocolException(
                                "INVALID_HELPER_STATE", "Unknown update state: ${response.state}",
                            )
                        }
                        delay(POLL_INTERVAL_MILLIS)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    UpdateExecutionResult.Retry("Update reconciliation ended unexpectedly.")
                }
            } catch (timeout: TimeoutCancellationException) {
                UpdateExecutionResult.Retry("The update helper is still working; reconciliation will continue.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                UpdateExecutionResult.Retry(exception.message ?: "Update reconciliation will retry.")
            }
        }
    }

    suspend fun cleanupExpired(now: Long = System.currentTimeMillis()): CleanupResult {
        require(now > 0)
        helper.ensureInstalled()
        val expired = instances.expiredRecoveryPoints(now)
        var deleted = 0
        var failed = 0
        expired.forEach { point ->
            val confirmed = runCatching {
                val response = helper.cleanupRecovery(point.id.value, now)
                require(response.ok && response.operation == "recovery-cleanup")
                require(response.recoveryPointId == point.id.value)
                response.deleted != null
            }.getOrDefault(false)
            if (confirmed && instances.deleteConfirmedRecoveryPoint(point.id)) deleted += 1 else failed += 1
        }
        return CleanupResult(expired.size, deleted, failed)
    }

    private suspend fun persistRunning(
        current: UpdateOperation,
        response: HelperResponse,
        progress: ProgressSink,
    ) {
        val stage = helperUpdateStage(response.stage)
        requireValidTransition(current.stage, stage)
        val point = requireNotNull(instances.recoveryPoint(current.recoveryPointId)) {
            "Recovery point reservation is missing"
        }
        val verifiedPoint = if (stage == UpdateStage.RECOVERY_VERIFIED || response.manifestSha256 != null) {
            point.withVerifiedSnapshot(current, response)
        } else {
            point.copy(
                sourceWasRunning = response.sourceWasRunning ?: point.sourceWasRunning,
                restorationState = if (stage == UpdateStage.ROLLING_BACK || stage == UpdateStage.RESTORING) {
                    RestorationState.PENDING
                } else point.restorationState,
                state = if (stage == UpdateStage.ROLLING_BACK || stage == UpdateStage.RESTORING) {
                    RecoveryPointState.RESTORING
                } else point.state,
            )
        }
        val updated = current.copy(
            stage = stage,
            sourceWasRunning = response.sourceWasRunning ?: current.sourceWasRunning,
            restorationState = verifiedPoint.restorationState,
            activeRevision = response.activeCommit ?: current.activeRevision,
            launchConfirmed = response.launchConfirmed ?: current.launchConfirmed,
            healthConfirmed = response.healthConfirmed ?: current.healthConfirmed,
            updatedAt = System.currentTimeMillis(),
            detail = response.detail ?: response.message,
        )
        instances.upsertUpdateProgress(updated, verifiedPoint)
        progress.report(OperationState.Running(updateStageLabel(stage), updated.detail, null))
    }

    private suspend fun finishSuccess(current: UpdateOperation, response: HelperResponse): UpdateExecutionResult {
        validateSuccess(current, response)
        val point = requireNotNull(instances.recoveryPoint(current.recoveryPointId))
            .withVerifiedSnapshot(current, response)
        val completedAt = requireNotNull(response.completedAt)
        val ready = current.copy(
            stage = UpdateStage.HEALTH_CHECKING,
            targetVersion = response.targetVersion ?: current.targetVersion,
            sourceWasRunning = response.sourceWasRunning,
            activeRevision = response.activeCommit,
            launchConfirmed = true,
            healthConfirmed = true,
            restorationState = RestorationState.NOT_REQUIRED,
            updatedAt = completedAt,
            completedAt = completedAt,
            detail = response.message ?: "PocketRisu update verified.",
        )
        instances.publishUpdateSuccess(ready, point)
        return UpdateExecutionResult.Success(requireNotNull(instances.updateOperation(current.operationId)))
    }

    private suspend fun finishFailure(current: UpdateOperation, response: HelperResponse): UpdateExecutionResult.Failure {
        require(!response.code.isNullOrBlank()) { "Helper omitted update failure code" }
        val restored = requireNotNull(response.restored) { "Helper omitted automatic restoration result" }
        val message = response.message ?: response.detail ?: "PocketRisu update failed."
        val point = requireNotNull(instances.recoveryPoint(current.recoveryPointId))
        val recoveryRequired = !restored && (point.state == RecoveryPointState.VERIFIED || point.manifestSha256.isNotBlank())
        val restoration = if (restored) RestorationState.SUCCEEDED else if (recoveryRequired) RestorationState.FAILED else RestorationState.NOT_REQUIRED
        val failedPoint = point.copy(
            inUse = recoveryRequired,
            restored = restored,
            sourceWasRunning = response.sourceWasRunning ?: point.sourceWasRunning,
            restorationState = restoration,
            state = when {
                restored -> RecoveryPointState.RESTORED
                recoveryRequired -> RecoveryPointState.VERIFIED
                else -> RecoveryPointState.FAILED
            },
            lastError = if (restored) null else message,
        )
        val now = System.currentTimeMillis()
        val failed = current.copy(
            stage = UpdateStage.FAILED,
            result = UpdateResult.FAILURE,
            sourceWasRunning = response.sourceWasRunning ?: current.sourceWasRunning,
            restorationState = restoration,
            updatedAt = now,
            completedAt = now,
            errorCode = response.code,
            detail = if (recoveryRequired) "$message Recovery is still required." else message,
        )
        instances.upsertUpdateProgress(failed, failedPoint)
        return UpdateExecutionResult.Failure(failed.errorCode!!, failed.detail!!, restored, recoveryRequired)
    }

    private suspend fun failBeforeMutation(
        current: UpdateOperation,
        code: String,
        message: String,
    ): UpdateExecutionResult.Failure {
        val now = System.currentTimeMillis()
        val point = requireNotNull(instances.recoveryPoint(current.recoveryPointId)).copy(
            inUse = false,
            restored = true,
            state = RecoveryPointState.FAILED,
            lastError = message,
        )
        val failed = current.copy(
            stage = UpdateStage.FAILED,
            result = UpdateResult.FAILURE,
            restorationState = RestorationState.NOT_REQUIRED,
            updatedAt = now,
            completedAt = now,
            errorCode = code,
            detail = message,
        )
        instances.upsertUpdateProgress(failed, point)
        return UpdateExecutionResult.Failure(code, message, restored = true, recoveryRequired = false)
    }

    private fun terminalResult(operation: UpdateOperation): UpdateExecutionResult? = when (operation.result) {
        UpdateResult.RUNNING -> null
        UpdateResult.SUCCESS -> UpdateExecutionResult.Success(operation)
        UpdateResult.FAILURE -> UpdateExecutionResult.Failure(
            operation.errorCode ?: "UPDATE_FAILED",
            operation.detail ?: "PocketRisu update failed.",
            restored = operation.restorationState == RestorationState.SUCCEEDED,
            recoveryRequired = operation.restorationState == RestorationState.FAILED,
        )
    }

    private fun validateIdentity(operation: UpdateOperation, response: HelperResponse) {
        require(response.operation == "update") { "Helper omitted update operation identity" }
        require(response.operationId == operation.operationId) { "Helper operation identity mismatch" }
        require(response.instanceId == operation.instanceId) { "Helper instance identity mismatch" }
        require(response.recoveryPointId == operation.recoveryPointId.value) { "Helper recovery identity mismatch" }
        response.targetCommit?.let {
            require(it.equals(operation.targetRevision, ignoreCase = true)) { "Helper target revision mismatch" }
        }
        response.currentCommit?.let {
            require(it.equals(operation.currentRevision, ignoreCase = true)) { "Helper source revision mismatch" }
        }
    }

    private fun validateSuccess(operation: UpdateOperation, response: HelperResponse) {
        require(response.ok) { response.message ?: "Helper reported unsuccessful update" }
        require(response.targetCommit.equals(operation.targetRevision, ignoreCase = true)) { "Helper omitted immutable target revision" }
        require(response.activeCommit.equals(operation.targetRevision, ignoreCase = true)) { "Active revision does not match update target" }
        require(response.launchConfirmed == true) { "Updated PocketRisu launch was not confirmed" }
        require(response.healthConfirmed == true) { "Updated PocketRisu health was not confirmed" }
        require(response.sourceWasRunning != null) { "Helper omitted original runtime state" }
        require(response.restored == false) { "Successful update unexpectedly reports restoration" }
        val completedAt = requireNotNull(response.completedAt) { "Helper omitted update completion time" }
        val expiresAt = requireNotNull(response.expiresAt) { "Helper omitted recovery expiration time" }
        require(expiresAt == completedAt + operation.retentionSeconds * 1000) { "Recovery expiration does not match retention" }
    }

    private suspend fun reportAndPersist(
        operation: UpdateOperation,
        stage: UpdateStage,
        detail: String?,
        progress: ProgressSink,
    ) {
        val updated = operation.copy(stage = stage, updatedAt = System.currentTimeMillis(), detail = detail)
        instances.upsertUpdateProgress(updated)
        progress.report(OperationState.Running(updateStageLabel(stage), detail, null))
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_500L
        const val UPDATE_TIMEOUT_MILLIS = 6 * 60 * 60 * 1000L
        val SAFE_DISPATCH_REJECTION_CODES = setOf(
            "INVALID_ARGUMENT",
            "INVALID_OPERATION_ID",
            "INVALID_INSTANCE_ID",
            "INVALID_VARIANT_ID",
            "INVALID_REVISION",
            "INVALID_RECOVERY_ID",
            "INVALID_RETENTION",
            "UNSAFE_UPDATE_PATH",
            "UNSUPPORTED_VARIANT",
            "UNSUPPORTED_UPDATE_PATH",
            "UPDATE_PATH_UNSUPPORTED",
            "INSTANCE_NOT_FOUND",
            "INSTANCE_NOT_INSTALLED",
            "UPDATE_NOT_SUPPORTED",
            "ALREADY_AT_TARGET",
            "RECOVERY_POINT_CONFLICT",
            "SIZE_ESTIMATE_FAILED",
            "INSUFFICIENT_DISK_SPACE",
            "OPERATION_STATE_FAILED",
        )
    }
}

private fun RecoveryPoint.withVerifiedSnapshot(operation: UpdateOperation, response: HelperResponse): RecoveryPoint {
    val manifest = requireNotNull(response.manifestSha256) { "Helper omitted recovery manifest digest" }
    val data = requireNotNull(response.dataDigestSha256) { "Helper omitted recovery data digest" }
    val config = requireNotNull(response.configurationDigestSha256) { "Helper omitted recovery configuration digest" }
    require(Validators.isSha256(manifest) && Validators.isSha256(data) && Validators.isSha256(config)) {
        "Helper returned invalid recovery integrity metadata"
    }
    val created = requireNotNull(response.createdAt) { "Helper omitted recovery creation time" }
    val size = requireNotNull(response.size) { "Helper omitted recovery size" }
    require(created > 0 && size >= 0) { "Helper returned invalid recovery metadata" }
    return copy(
        createdAt = created,
        serverRevision = operation.currentRevision,
        serverVersion = response.currentVersion ?: operation.currentVersion,
        dataDigestSha256 = data,
        configurationDigestSha256 = config,
        manifestSha256 = manifest,
        sizeBytes = size,
        completedAt = completedAt ?: System.currentTimeMillis(),
        pinned = response.pinned ?: pinned,
        inUse = response.inUse ?: true,
        sourceWasRunning = response.sourceWasRunning ?: sourceWasRunning,
        state = RecoveryPointState.VERIFIED,
        lastError = null,
    )
}

internal fun helperUpdateStage(value: String?): UpdateStage = when (value) {
    "STAGING" -> UpdateStage.STAGING
    "STOPPING" -> UpdateStage.STOPPING
    "RECOVERY_CREATING" -> UpdateStage.RECOVERY_CREATING
    "RECOVERY_VERIFYING" -> UpdateStage.RECOVERY_VERIFYING
    "RECOVERY_VERIFIED" -> UpdateStage.RECOVERY_VERIFIED
    "SWITCHING" -> UpdateStage.SWITCHING
    "UPDATING" -> UpdateStage.UPDATING
    "STARTING", "LAUNCHING" -> UpdateStage.LAUNCHING
    "HEALTH_CHECKING" -> UpdateStage.HEALTH_CHECKING
    "RESTORING" -> UpdateStage.RESTORING
    "ROLLING_BACK" -> UpdateStage.ROLLING_BACK
    else -> UpdateStage.PREPARED
}

internal fun requireValidTransition(from: UpdateStage, to: UpdateStage) {
    if (from == to) return
    if (to == UpdateStage.RESTORING || to == UpdateStage.ROLLING_BACK || to == UpdateStage.FAILED) return
    val order = listOf(
        UpdateStage.PREPARED,
        UpdateStage.STAGING,
        UpdateStage.STOPPING,
        UpdateStage.RECOVERY_CREATING,
        UpdateStage.RECOVERY_VERIFYING,
        UpdateStage.RECOVERY_VERIFIED,
        UpdateStage.SWITCHING,
        UpdateStage.UPDATING,
        UpdateStage.LAUNCHING,
        UpdateStage.HEALTH_CHECKING,
        UpdateStage.COMPLETED,
    )
    require(from in order && to in order && order.indexOf(to) >= order.indexOf(from)) {
        "Invalid update stage transition: $from -> $to"
    }
}

internal fun updateStageLabel(stage: UpdateStage): String = when (stage) {
    UpdateStage.PREPARED -> "Preparing update"
    UpdateStage.STAGING -> "Building isolated update"
    UpdateStage.STOPPING -> "Stopping PocketRisu"
    UpdateStage.RECOVERY_CREATING -> "Creating recovery point"
    UpdateStage.RECOVERY_VERIFYING -> "Verifying recovery point"
    UpdateStage.RECOVERY_VERIFIED -> "Recovery point verified"
    UpdateStage.SWITCHING, UpdateStage.UPDATING -> "Activating update"
    UpdateStage.LAUNCHING -> "Launching updated PocketRisu"
    UpdateStage.HEALTH_CHECKING -> "Verifying updated PocketRisu"
    UpdateStage.RESTORING, UpdateStage.ROLLING_BACK -> "Restoring pre-update state"
    UpdateStage.RECONCILING -> "Reconciling update"
    UpdateStage.COMPLETED -> "Update complete"
    UpdateStage.FAILED -> "Update failed"
}
