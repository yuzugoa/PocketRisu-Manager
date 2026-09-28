package io.github.risumanager.operation

import io.github.risumanager.data.repository.InstanceRepository
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.RestorationState
import io.github.risumanager.domain.model.RollbackOperation
import io.github.risumanager.domain.model.RollbackPreview
import io.github.risumanager.domain.model.RollbackResult
import io.github.risumanager.domain.model.RollbackStage
import io.github.risumanager.domain.validation.Validators
import io.github.risumanager.runtime.termux.HelperProtocolException
import io.github.risumanager.runtime.termux.HelperResponse
import io.github.risumanager.runtime.termux.RollbackCommands
import io.github.risumanager.runtime.termux.toConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

sealed interface RollbackExecutionResult {
    data class Success(val operation: RollbackOperation) : RollbackExecutionResult
    data class Failure(
        val code: String,
        val message: String,
        val currentStateRestored: Boolean,
        val recoveryRequired: Boolean,
    ) : RollbackExecutionResult
    data class Retry(val message: String) : RollbackExecutionResult
}

class RollbackService(
    private val helper: RollbackCommands,
    private val instances: InstanceRepository,
    private val operationGate: InstanceOperationGate,
) {
    suspend fun inspect(instanceId: String, recoveryPointId: RecoveryPointId): RollbackPreview {
        val instance = requireNotNull(instances.get(instanceId)) { "Instance no longer exists" }
        val point = requireNotNull(instances.recoveryPoint(recoveryPointId)) { "Recovery point no longer exists" }
        val localReason = point.unavailableReason(instanceId, instance.variantId)
        if (localReason != null) return point.preview(instance.installedRevision, instance.installedVersion, false, localReason)
        return try {
            helper.ensureInstalled()
            val response = helper.recoveryInspect(point.id.value, instanceId)
            require(response.operation == "recovery-inspect") { "Helper omitted recovery inspection identity" }
            require(response.recoveryPointId == point.id.value) { "Helper recovery point identity mismatch" }
            require(response.instanceId == instanceId) { "Helper instance identity mismatch" }
            val compatible = response.ok && response.available != false &&
                (response.formatVersion == null || response.formatVersion == point.formatVersion) &&
                (response.variantId == null || response.variantId == point.variantId) &&
                (response.layoutVersion == null || response.layoutVersion == point.layoutVersion) &&
                (response.manifestSha256 == null || response.manifestSha256.equals(point.manifestSha256, ignoreCase = true))
            point.preview(
                instance.installedRevision,
                instance.installedVersion,
                compatible,
                if (compatible) null else response.availabilityReason ?: response.message ?: "Recovery point validation failed",
                response.requiredBytes,
                response.availableBytes,
                response.dataChanged,
                response.configurationChanged,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            point.preview(instance.installedRevision, instance.installedVersion, false, exception.message ?: "Recovery point inspection failed")
        }
    }

    suspend fun setPinned(id: RecoveryPointId, pinned: Boolean) {
        val point = requireNotNull(instances.recoveryPoint(id)) { "Recovery point no longer exists" }
        require(point.managerOwned && !point.inUse) { "Recovery point is currently in use" }
        helper.ensureInstalled()
        val response = helper.recoveryPin(id.value, pinned)
        require(response.ok && response.operation == "recovery-pin" && response.recoveryPointId == id.value) {
            response.message ?: "The helper did not confirm the pin change"
        }
        require(response.pinned == null || response.pinned == pinned) { "Helper pin state mismatch" }
        instances.setRecoveryPointPinned(id, pinned)
    }

    suspend fun delete(id: RecoveryPointId) {
        val point = requireNotNull(instances.recoveryPoint(id)) { "Recovery point no longer exists" }
        require(point.managerOwned && !point.pinned && !point.inUse) { "Recovery point is pinned or currently in use" }
        helper.ensureInstalled()
        val response = helper.recoveryDelete(id.value)
        require(response.ok && response.operation == "recovery-delete" && response.recoveryPointId == id.value && response.deleted == true) {
            response.message ?: "The helper did not confirm recovery point deletion"
        }
        check(instances.deleteConfirmedRecoveryPoint(id)) { "Recovery point changed before deletion was recorded" }
    }

    suspend fun execute(operationId: String, progress: ProgressSink): RollbackExecutionResult {
        val initial = instances.rollbackOperation(operationId)
            ?: return RollbackExecutionResult.Failure(
                "ROLLBACK_OPERATION_MISSING", "Rollback operation record is missing.", false, false,
            )
        terminalResult(initial)?.let { return it }
        return operationGate.withInstance(initial.instanceId) {
            try {
                helper.ensureInstalled()
                withTimeout(ROLLBACK_TIMEOUT_MILLIS) {
                    var dispatched = false
                    while (true) {
                        val current = requireNotNull(instances.rollbackOperation(operationId))
                        terminalResult(current)?.let { return@withTimeout it }
                        val response = try {
                            helper.operation(operationId)
                        } catch (exception: HelperProtocolException) {
                            if (exception.code != "OPERATION_NOT_FOUND") throw exception
                            if (!dispatched) {
                                val accepted = helper.dispatchRollback(
                                    current.operationId,
                                    current.instanceId,
                                    current.selectedRecoveryPointId.value,
                                    current.preRollbackRecoveryPointId.value,
                                    current.retentionSeconds,
                                )
                                if (!accepted.ok && accepted.state != "FAILED") {
                                    val code = accepted.code ?: "ROLLBACK_DISPATCH_FAILED"
                                    if (code in SAFE_DISPATCH_REJECTION_CODES) {
                                        return@withTimeout failBeforeMutation(current, code, accepted.message ?: "Rollback was rejected before mutation.")
                                    }
                                    throw HelperProtocolException(code, accepted.message ?: "Rollback dispatch could not be confirmed.")
                                }
                                validateIdentity(current, accepted)
                                dispatched = true
                                if (accepted.state == "FAILED") return@withTimeout finishFailure(current, accepted)
                                require(accepted.state == "QUEUED" || accepted.state == "RUNNING") {
                                    "The helper returned an invalid rollback dispatch state"
                                }
                                persistRunning(current, RollbackStage.PREPARED, accepted, progress)
                            }
                            delay(POLL_INTERVAL_MILLIS)
                            continue
                        }
                        validateIdentity(current, response)
                        when (response.state) {
                            "QUEUED", "RUNNING" -> persistRunning(current, helperRollbackStage(response.stage), response, progress)
                            "SUCCEEDED" -> return@withTimeout finishSuccess(current, response)
                            "FAILED" -> return@withTimeout finishFailure(current, response)
                            else -> throw HelperProtocolException("INVALID_HELPER_STATE", "Unknown rollback state: ${response.state}")
                        }
                        delay(POLL_INTERVAL_MILLIS)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    RollbackExecutionResult.Retry("Rollback reconciliation ended unexpectedly.")
                }
            } catch (timeout: TimeoutCancellationException) {
                RollbackExecutionResult.Retry("The rollback helper is still working; reconciliation will continue.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                RollbackExecutionResult.Retry(exception.message ?: "Rollback reconciliation will retry.")
            }
        }
    }

    private suspend fun persistRunning(
        current: RollbackOperation,
        stage: RollbackStage,
        response: HelperResponse,
        progress: ProgressSink,
    ) {
        requireValidRollbackTransition(current.stage, stage)
        val selected = requireNotNull(instances.recoveryPoint(current.selectedRecoveryPointId))
        val pre = requireNotNull(instances.recoveryPoint(current.preRollbackRecoveryPointId))
        val verifiedPre = if (response.manifestSha256 != null || stage == RollbackStage.PRE_ROLLBACK_VERIFIED) {
            pre.withVerifiedPreRollbackSnapshot(current, response)
        } else pre.copy(
            sourceWasRunning = response.sourceWasRunning ?: pre.sourceWasRunning,
            state = if (stage in MUTATING_STAGES) RecoveryPointState.RESTORING else pre.state,
        )
        val updated = current.copy(
            stage = stage,
            sourceWasRunning = response.sourceWasRunning ?: current.sourceWasRunning,
            currentStateRestored = response.restored ?: current.currentStateRestored,
            activeRevision = response.activeCommit ?: current.activeRevision,
            activeVersion = response.activeVersion ?: response.version ?: current.activeVersion,
            launchConfirmed = response.launchConfirmed ?: current.launchConfirmed,
            healthConfirmed = response.healthConfirmed ?: current.healthConfirmed,
            updatedAt = System.currentTimeMillis(),
            detail = response.detail ?: response.message,
        )
        instances.upsertRollbackProgress(updated, selected.copy(inUse = true, state = RecoveryPointState.RESTORING), verifiedPre)
        progress.report(OperationState.Running(rollbackStageLabel(stage), updated.detail, null))
    }

    private suspend fun finishSuccess(current: RollbackOperation, response: HelperResponse): RollbackExecutionResult.Success {
        require(response.ok) { response.message ?: "Helper reported unsuccessful rollback" }
        require(response.activeCommit.equals(current.targetRevision, ignoreCase = true)) { "Active revision does not match selected recovery point" }
        val activeVersion = response.activeVersion ?: response.version
        current.targetVersion?.let { require(activeVersion == null || activeVersion == it) { "Active version does not match selected recovery point" } }
        require(response.launchConfirmed == true && response.healthConfirmed == true) { "Restored PocketRisu was not verified healthy" }
        require(response.sourceWasRunning != null) { "Helper omitted original runtime state" }
        val completedAt = requireNotNull(response.completedAt) { "Helper omitted rollback completion time" }
        val selected = requireNotNull(instances.recoveryPoint(current.selectedRecoveryPointId))
        val pre = requireNotNull(instances.recoveryPoint(current.preRollbackRecoveryPointId)).withVerifiedPreRollbackSnapshot(current, response)
        val configResponse = helper.configGet(current.instanceId)
        require(configResponse.ok && configResponse.instanceId == current.instanceId) { "Restored configuration could not be verified" }
        val ready = current.copy(
            stage = RollbackStage.HEALTH_CHECKING,
            sourceWasRunning = response.sourceWasRunning,
            currentStateRestored = false,
            activeRevision = response.activeCommit,
            activeVersion = activeVersion ?: current.targetVersion,
            launchConfirmed = true,
            healthConfirmed = true,
            completedAt = completedAt,
            updatedAt = completedAt,
            detail = response.message ?: "PocketRisu rollback verified.",
        )
        instances.publishRollbackSuccess(ready, selected, pre, configResponse.toConfig(current.instanceId))
        return RollbackExecutionResult.Success(requireNotNull(instances.rollbackOperation(current.operationId)))
    }

    private suspend fun finishFailure(current: RollbackOperation, response: HelperResponse): RollbackExecutionResult {
        val code = requireNotNull(response.code) { "Helper omitted rollback failure code" }
        val restored = response.restored == true
        val message = response.message ?: response.detail ?: "PocketRisu rollback failed."
        val selected = requireNotNull(instances.recoveryPoint(current.selectedRecoveryPointId))
        val pre = requireNotNull(instances.recoveryPoint(current.preRollbackRecoveryPointId))
        val verifiedPre = if (response.manifestSha256 != null) pre.withVerifiedPreRollbackSnapshot(current, response) else pre
        val now = System.currentTimeMillis()
        val recoveryRequired = !restored && verifiedPre.manifestSha256.isNotBlank()
        val failed = current.copy(
            stage = RollbackStage.FAILED,
            result = RollbackResult.FAILURE,
            sourceWasRunning = response.sourceWasRunning ?: current.sourceWasRunning,
            currentStateRestored = restored,
            updatedAt = now,
            completedAt = now,
            errorCode = code,
            detail = if (recoveryRequired) "$message Recovery of the pre-rollback state is still required." else message,
        )
        instances.upsertRollbackProgress(
            failed,
            selected.copy(inUse = false, state = RecoveryPointState.AVAILABLE, lastError = message),
            verifiedPre.copy(
                inUse = recoveryRequired,
                restored = restored,
                restorationState = when {
                    restored -> RestorationState.SUCCEEDED
                    recoveryRequired -> RestorationState.FAILED
                    else -> RestorationState.NOT_REQUIRED
                },
                state = when {
                    restored -> RecoveryPointState.RESTORED
                    recoveryRequired -> RecoveryPointState.VERIFIED
                    else -> RecoveryPointState.FAILED
                },
                lastError = if (restored) null else message,
            ),
        )
        return RollbackExecutionResult.Failure(code, failed.detail!!, restored, recoveryRequired)
    }

    private suspend fun failBeforeMutation(current: RollbackOperation, code: String, message: String): RollbackExecutionResult.Failure {
        val now = System.currentTimeMillis()
        val selected = requireNotNull(instances.recoveryPoint(current.selectedRecoveryPointId))
        val pre = requireNotNull(instances.recoveryPoint(current.preRollbackRecoveryPointId))
        val failed = current.copy(
            stage = RollbackStage.FAILED,
            result = RollbackResult.FAILURE,
            currentStateRestored = true,
            updatedAt = now,
            completedAt = now,
            errorCode = code,
            detail = message,
        )
        instances.upsertRollbackProgress(
            failed,
            selected.copy(inUse = false, state = RecoveryPointState.AVAILABLE, lastError = message),
            pre.copy(inUse = false, state = RecoveryPointState.FAILED, lastError = message),
        )
        return RollbackExecutionResult.Failure(code, message, true, false)
    }

    private suspend fun terminalResult(operation: RollbackOperation): RollbackExecutionResult? = when (operation.result) {
        RollbackResult.RUNNING -> null
        RollbackResult.SUCCESS -> RollbackExecutionResult.Success(operation)
        RollbackResult.FAILURE -> {
            val pre = instances.recoveryPoint(operation.preRollbackRecoveryPointId)
            RollbackExecutionResult.Failure(
                operation.errorCode ?: "ROLLBACK_FAILED",
                operation.detail ?: "PocketRisu rollback failed.",
                operation.currentStateRestored == true,
                operation.currentStateRestored == false && pre?.inUse == true && pre.state == RecoveryPointState.VERIFIED,
            )
        }
    }

    private fun validateIdentity(operation: RollbackOperation, response: HelperResponse) {
        require(response.operation == "rollback") { "Helper omitted rollback operation identity" }
        require(response.operationId == operation.operationId) { "Helper operation identity mismatch" }
        require(response.instanceId == operation.instanceId) { "Helper instance identity mismatch" }
        require(response.selectedRecoveryPointId == operation.selectedRecoveryPointId.value || response.recoveryPointId == operation.selectedRecoveryPointId.value) {
            "Helper selected recovery identity mismatch"
        }
        require(response.preRollbackRecoveryPointId == operation.preRollbackRecoveryPointId.value) {
            "Helper pre-rollback recovery identity mismatch"
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_500L
        const val ROLLBACK_TIMEOUT_MILLIS = 6 * 60 * 60 * 1000L
        val MUTATING_STAGES = setOf(
            RollbackStage.STOPPING, RollbackStage.RESTORING, RollbackStage.SWITCHING,
            RollbackStage.LAUNCHING, RollbackStage.HEALTH_CHECKING, RollbackStage.RESTORING_CURRENT,
        )
        val SAFE_DISPATCH_REJECTION_CODES = setOf(
            "INVALID_ARGUMENT", "INVALID_OPERATION_ID", "INVALID_INSTANCE_ID", "INVALID_RECOVERY_ID",
            "INVALID_RETENTION", "INSTANCE_NOT_FOUND", "RECOVERY_NOT_FOUND", "RECOVERY_UNAVAILABLE",
            "RECOVERY_IN_USE", "RECOVERY_CORRUPTED", "RECOVERY_VERIFY_FAILED", "UNSUPPORTED_RECOVERY_FORMAT",
            "INCOMPATIBLE_RECOVERY_POINT", "INSUFFICIENT_DISK_SPACE", "PORT_UNAVAILABLE",
            "PRE_ROLLBACK_BACKUP_FAILED", "OPERATION_CONFLICT", "OPERATION_STATE_FAILED",
        )
    }
}

private fun RecoveryPoint.unavailableReason(instanceId: String, variantId: String): String? = when {
    this.instanceId != instanceId -> "Recovery point belongs to another instance"
    this.variantId != variantId || variantId != "pocketrisu" -> "Recovery point is incompatible with this variant"
    formatVersion != 1 || layoutVersion != 1 -> "Recovery point format is unsupported"
    !managerOwned -> "Recovery point is no longer available"
    inUse -> "Recovery point is currently in use"
    state !in setOf(RecoveryPointState.AVAILABLE, RecoveryPointState.RESTORED) -> "Recovery point is not available"
    !Validators.isFullCommitSha(serverRevision.orEmpty()) -> "Recovery point server revision is invalid"
    !Validators.isSha256(manifestSha256) || !Validators.isSha256(dataDigestSha256) || !Validators.isSha256(configurationDigestSha256) ->
        "Recovery point integrity metadata is invalid"
    else -> null
}

private fun RecoveryPoint.preview(
    currentRevision: String?,
    currentVersion: String?,
    available: Boolean,
    reason: String?,
    requiredBytes: Long? = null,
    availableBytes: Long? = null,
    dataChanged: Boolean? = null,
    configurationChanged: Boolean? = null,
) = RollbackPreview(
    instanceId = instanceId,
    recoveryPointId = id,
    currentRevision = currentRevision,
    currentVersion = currentVersion,
    targetRevision = serverRevision,
    targetVersion = serverVersion,
    createdAt = createdAt,
    sizeBytes = sizeBytes,
    expiresAt = expiresAt,
    pinned = pinned,
    available = available,
    availabilityReason = reason,
    requiredBytes = requiredBytes,
    availableBytes = availableBytes,
    dataChanged = dataChanged,
    configurationChanged = configurationChanged,
)

private fun RecoveryPoint.withVerifiedPreRollbackSnapshot(operation: RollbackOperation, response: HelperResponse): RecoveryPoint {
    val manifest = requireNotNull(response.manifestSha256) { "Helper omitted pre-rollback manifest digest" }
    val data = requireNotNull(response.dataDigestSha256) { "Helper omitted pre-rollback data digest" }
    val config = requireNotNull(response.configurationDigestSha256) { "Helper omitted pre-rollback configuration digest" }
    require(Validators.isSha256(manifest) && Validators.isSha256(data) && Validators.isSha256(config)) {
        "Helper returned invalid pre-rollback integrity metadata"
    }
    val created = requireNotNull(response.createdAt) { "Helper omitted pre-rollback creation time" }
    val size = requireNotNull(response.size) { "Helper omitted pre-rollback size" }
    require(created > 0 && size >= 0) { "Helper returned invalid pre-rollback metadata" }
    return copy(
        createdAt = created,
        serverRevision = operation.currentRevision,
        serverVersion = operation.currentVersion,
        dataDigestSha256 = data,
        configurationDigestSha256 = config,
        manifestSha256 = manifest,
        sizeBytes = size,
        completedAt = response.completedAt ?: completedAt ?: System.currentTimeMillis(),
        pinned = response.pinned ?: pinned,
        inUse = response.inUse ?: true,
        sourceWasRunning = response.sourceWasRunning ?: sourceWasRunning,
        state = RecoveryPointState.VERIFIED,
        lastError = null,
    )
}

internal fun helperRollbackStage(value: String?): RollbackStage = when (value) {
    "VALIDATING" -> RollbackStage.VALIDATING
    "PRE_ROLLBACK_CREATING", "BACKUP_CREATING" -> RollbackStage.PRE_ROLLBACK_CREATING
    "PRE_RECOVERY_CREATING" -> RollbackStage.PRE_ROLLBACK_CREATING
    "PRE_ROLLBACK_VERIFYING", "BACKUP_VERIFYING" -> RollbackStage.PRE_ROLLBACK_VERIFYING
    "PRE_ROLLBACK_VERIFIED", "BACKUP_VERIFIED" -> RollbackStage.PRE_ROLLBACK_VERIFIED
    "PRE_RECOVERY_VERIFIED" -> RollbackStage.PRE_ROLLBACK_VERIFIED
    "STAGING" -> RollbackStage.STAGING
    "STOPPING" -> RollbackStage.STOPPING
    "RESTORING" -> RollbackStage.RESTORING
    "SWITCHING" -> RollbackStage.SWITCHING
    "STARTING", "LAUNCHING" -> RollbackStage.LAUNCHING
    "HEALTH_CHECKING", "VERIFYING" -> RollbackStage.HEALTH_CHECKING
    "RESTORING_CURRENT", "RECOVERING_CURRENT", "ROLLING_BACK" -> RollbackStage.RESTORING_CURRENT
    "RECONCILING" -> RollbackStage.RECONCILING
    else -> RollbackStage.PREPARED
}

internal fun requireValidRollbackTransition(from: RollbackStage, to: RollbackStage) {
    if (from == to || to == RollbackStage.RESTORING_CURRENT || to == RollbackStage.FAILED) return
    val order = listOf(
        RollbackStage.PREPARED, RollbackStage.VALIDATING, RollbackStage.PRE_ROLLBACK_CREATING,
        RollbackStage.PRE_ROLLBACK_VERIFYING, RollbackStage.PRE_ROLLBACK_VERIFIED, RollbackStage.STAGING,
        RollbackStage.STOPPING, RollbackStage.RESTORING, RollbackStage.SWITCHING, RollbackStage.LAUNCHING,
        RollbackStage.HEALTH_CHECKING, RollbackStage.COMPLETED,
    )
    require(from in order && to in order && order.indexOf(to) >= order.indexOf(from)) {
        "Invalid rollback stage transition: $from -> $to"
    }
}

internal fun rollbackStageLabel(stage: RollbackStage): String = when (stage) {
    RollbackStage.PREPARED -> "Preparing rollback"
    RollbackStage.VALIDATING -> "Validating recovery point"
    RollbackStage.PRE_ROLLBACK_CREATING -> "Saving current state"
    RollbackStage.PRE_ROLLBACK_VERIFYING -> "Verifying current-state recovery point"
    RollbackStage.PRE_ROLLBACK_VERIFIED -> "Current state safely saved"
    RollbackStage.STAGING -> "Staging selected recovery point"
    RollbackStage.STOPPING -> "Stopping PocketRisu"
    RollbackStage.RESTORING, RollbackStage.SWITCHING -> "Restoring selected state"
    RollbackStage.LAUNCHING -> "Launching restored PocketRisu"
    RollbackStage.HEALTH_CHECKING -> "Verifying restored PocketRisu"
    RollbackStage.RESTORING_CURRENT -> "Recovering pre-rollback state"
    RollbackStage.RECONCILING -> "Reconciling rollback"
    RollbackStage.COMPLETED -> "Rollback complete"
    RollbackStage.FAILED -> "Rollback failed"
}
