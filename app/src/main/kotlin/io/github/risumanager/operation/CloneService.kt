package io.github.risumanager.operation

import io.github.risumanager.data.repository.InstanceRepository
import io.github.risumanager.domain.model.AcmeChallenge
import io.github.risumanager.domain.model.BindScope
import io.github.risumanager.domain.model.CertificateMode
import io.github.risumanager.domain.model.CloneOperation
import io.github.risumanager.domain.model.CloneResult
import io.github.risumanager.domain.model.CloneStage
import io.github.risumanager.domain.model.ConfigApplyState
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.ServerProtocol
import io.github.risumanager.runtime.termux.HelperProtocolException
import io.github.risumanager.runtime.termux.HelperResponse
import io.github.risumanager.runtime.termux.CloneCommands
import io.github.risumanager.runtime.termux.toConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

sealed interface CloneExecutionResult {
    data class Success(val targetInstanceId: String) : CloneExecutionResult
    data class Failure(val code: String, val message: String, val sourceRestored: Boolean?) : CloneExecutionResult
    data class Retry(val message: String) : CloneExecutionResult
}

class CloneService(
    private val helper: CloneCommands,
    private val instances: InstanceRepository,
    private val operationGate: InstanceOperationGate,
) {
    suspend fun execute(operationId: String, progress: ProgressSink): CloneExecutionResult {
        val initial = instances.cloneOperation(operationId)
            ?: return CloneExecutionResult.Failure("CLONE_OPERATION_MISSING", "Clone operation record is missing.", null)
        if (initial.result == CloneResult.SUCCESS) return CloneExecutionResult.Success(initial.targetInstanceId)
        if (initial.result == CloneResult.FAILURE) {
            return CloneExecutionResult.Failure(
                initial.errorCode ?: "CLONE_FAILED",
                initial.detail ?: "Cloning failed.",
                initial.sourceRestored,
            )
        }
        return operationGate.withInstance(initial.sourceInstanceId) {
            try {
                helper.ensureInstalled()
                withTimeout(CLONE_TIMEOUT_MILLIS) {
                    var dispatched = false
                    var rejectedPorts = 0
                    while (true) {
                        val current = requireNotNull(instances.cloneOperation(operationId))
                        if (current.result == CloneResult.SUCCESS) return@withTimeout CloneExecutionResult.Success(current.targetInstanceId)
                        val response = try {
                            helper.operation(operationId)
                        } catch (exception: HelperProtocolException) {
                            if (exception.code != "OPERATION_NOT_FOUND") throw exception
                            if (!dispatched) {
                                val accepted = current.recoveryPointId?.let { recoveryPointId ->
                                    helper.dispatchRecoveryClone(
                                        current.operationId,
                                        current.sourceInstanceId,
                                        recoveryPointId.value,
                                        current.targetInstanceId,
                                        current.targetPort,
                                    )
                                } ?: helper.dispatchClone(
                                    current.operationId,
                                    current.sourceInstanceId,
                                    current.targetInstanceId,
                                    current.targetPort,
                                )
                                if (!accepted.ok) {
                                    if (accepted.code == "PORT_UNAVAILABLE") {
                                        rejectedPorts += 1
                                        if (rejectedPorts >= MAX_PORT_REASSIGNMENTS) {
                                            return@withTimeout failBeforeDispatch(
                                                current,
                                                "PORT_UNAVAILABLE",
                                                "The helper rejected $rejectedPorts automatically reserved ports.",
                                            )
                                        }
                                        val reassigned = instances.reassignClonePort(operationId)
                                        progress.report(OperationState.Running("Preparing", "Port ${current.targetPort} became busy; reserved ${reassigned.targetPort}", null))
                                        delay(POLL_INTERVAL_MILLIS)
                                        continue
                                    }
                                    val code = accepted.code ?: "CLONE_DISPATCH_FAILED"
                                    if (code in SAFE_DISPATCH_REJECTION_CODES) {
                                        return@withTimeout failBeforeDispatch(
                                            current,
                                            code,
                                            accepted.message ?: "The helper refused the clone operation before changing the source.",
                                        )
                                    }
                                    throw HelperProtocolException(
                                        code,
                                        accepted.message ?: "The helper could not confirm whether clone dispatch changed durable state.",
                                    )
                                }
                                try {
                                    validateOperationIdentity(current, accepted)
                                    require(accepted.state == "QUEUED" || accepted.state == "RUNNING") {
                                        "The helper returned an invalid clone dispatch state"
                                    }
                                } catch (exception: Exception) {
                                    return@withTimeout failAndCleanUnpublished(
                                        current,
                                        "INVALID_HELPER_RESPONSE",
                                        exception.message ?: "The helper returned an invalid clone dispatch response.",
                                    )
                                }
                                dispatched = true
                                reportAndPersist(current, CloneStage.PREPARED, "Waiting for Termux", progress)
                            }
                            delay(POLL_INTERVAL_MILLIS)
                            continue
                        }
                        when (response.state) {
                            "QUEUED", "RUNNING" -> {
                                validateOperationIdentity(current, response)
                                val stage = helperCloneStage(response.stage)
                                reportAndPersist(
                                    current.copy(
                                        sourceWasRunning = response.sourceWasRunning ?: current.sourceWasRunning,
                                        sourceRestored = response.sourceRestored ?: current.sourceRestored,
                                    ),
                                    stage,
                                    response.detail ?: response.message,
                                    progress,
                                )
                            }
                            "READY_TO_PUBLISH" -> {
                                try {
                                    validateSnapshot(current, response)
                                } catch (exception: Exception) {
                                    return@withTimeout failAndCleanUnpublished(
                                        current,
                                        "INVALID_HELPER_RESPONSE",
                                        exception.message ?: "The helper returned an invalid ready-to-publish clone.",
                                    )
                                }
                                val ready = current.copy(
                                    stage = CloneStage.PUBLISHING,
                                    sourceWasRunning = response.sourceWasRunning,
                                    sourceRestored = true,
                                    updatedAt = System.currentTimeMillis(),
                                    detail = response.detail,
                                )
                                instances.upsertCloneOperation(ready)
                                progress.report(OperationState.Running("Publishing", "Publishing the validated clone", null))
                                val published = helper.publishClone(operationId)
                                if (!published.ok) {
                                    return@withTimeout failAndCleanUnpublished(
                                        ready,
                                        published.code ?: "CLONE_PUBLISH_FAILED",
                                        published.message ?: "The helper could not publish the validated clone.",
                                    )
                                }
                                if (published.state != "SUCCEEDED") throw HelperProtocolException(
                                    "INVALID_HELPER_STATE",
                                    "The helper returned an invalid publication state.",
                                )
                                return@withTimeout registerPublished(ready, published)
                            }
                            "SUCCEEDED" -> return@withTimeout registerPublished(current, response)
                            "FAILED" -> {
                                try {
                                    validateFailedOperation(current, response)
                                } catch (exception: Exception) {
                                    return@withTimeout failAndCleanUnpublished(
                                        current,
                                        "INVALID_HELPER_RESPONSE",
                                        exception.message ?: "The helper returned an invalid clone failure.",
                                    )
                                }
                                val pendingFailure = current.copy(
                                    stage = if (response.sourceRestored == false) CloneStage.RESTORING_SOURCE else CloneStage.FAILED,
                                    sourceWasRunning = response.sourceWasRunning ?: current.sourceWasRunning,
                                    sourceRestored = response.sourceRestored ?: current.sourceRestored,
                                    updatedAt = System.currentTimeMillis(),
                                    errorCode = response.code ?: "CLONE_FAILED",
                                    detail = response.message ?: response.detail ?: "Cloning failed.",
                                )
                                instances.upsertCloneOperation(pendingFailure)
                                val result = failAndCleanUnpublished(
                                    pendingFailure,
                                    pendingFailure.errorCode!!,
                                    pendingFailure.detail!!,
                                )
                                if (result is CloneExecutionResult.Retry) {
                                    progress.report(OperationState.Running("Restoring source", pendingFailure.detail, null))
                                }
                                return@withTimeout result
                            }
                            else -> throw HelperProtocolException("INVALID_HELPER_STATE", "Unknown clone state: ${response.state}")
                        }
                        delay(POLL_INTERVAL_MILLIS)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    CloneExecutionResult.Retry("Clone reconciliation ended unexpectedly.")
                }
            } catch (timeout: TimeoutCancellationException) {
                CloneExecutionResult.Retry("Clone helper is still working; reconciliation will continue.")
            } catch (cancelled: CancellationException) {
                throw cancelled // helper journal remains authoritative; startup reconciliation will resume.
            } catch (exception: Exception) {
                CloneExecutionResult.Retry(exception.message ?: "Clone reconciliation will retry.")
            }
        }
    }

    private suspend fun registerPublished(operation: CloneOperation, response: HelperResponse): CloneExecutionResult {
        validateSnapshot(operation, response)
        val published = operation.copy(
            stage = CloneStage.REGISTERING,
            sourceWasRunning = response.sourceWasRunning ?: operation.sourceWasRunning,
            sourceRestored = response.sourceRestored ?: operation.sourceRestored,
            helperPublished = true,
            updatedAt = System.currentTimeMillis(),
            detail = response.message,
        )
        instances.upsertCloneOperation(published)
        val source = instances.get(operation.sourceInstanceId)
            ?: return CloneExecutionResult.Retry("Source metadata is temporarily unavailable.")
        val now = System.currentTimeMillis()
        val target = source.copy(
            instanceId = operation.targetInstanceId,
            displayName = operation.targetDisplayName,
            installedRevision = operation.sourceRevision,
            previousRevision = null,
            installedVersion = response.version ?: operation.sourceVersion,
            latestRevision = source.latestRevision,
            port = operation.targetPort,
            createdAt = now,
            updatedAt = now,
            lastKnownStatus = RuntimeState.STOPPED,
        )
        val helperConfig = helper.configGet(target.instanceId)
        if (!helperConfig.ok) return CloneExecutionResult.Retry(helperConfig.message ?: "Published clone configuration is not readable yet.")
        require(helperConfig.instanceId == target.instanceId) { "Published clone configuration identity mismatch" }
        val confirmedConfig = helperConfig.toConfig(target.instanceId)
        val expectedConfig = published.toTargetConfig()
        check(confirmedConfig.sameCloneRuntimeValues(expectedConfig)) {
            "Published clone configuration does not match the reserved local-only settings"
        }
        instances.publishClone(
            published,
            target,
            expectedConfig,
        )
        return CloneExecutionResult.Success(target.instanceId)
    }

    private suspend fun failBeforeDispatch(
        operation: CloneOperation,
        code: String,
        message: String,
    ): CloneExecutionResult.Failure {
        val failed = operation.copy(
            stage = CloneStage.FAILED,
            result = CloneResult.FAILURE,
            sourceRestored = true,
            updatedAt = System.currentTimeMillis(),
            errorCode = code,
            detail = message,
        )
        instances.upsertCloneOperation(failed)
        return CloneExecutionResult.Failure(code, message, true)
    }

    private suspend fun failAndCleanUnpublished(
        operation: CloneOperation,
        code: String,
        message: String,
    ): CloneExecutionResult {
        val abortVerified = try {
            val response = helper.abortClone(operation.operationId)
            validateAbort(operation, response)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!abortVerified) {
            instances.upsertCloneOperation(
                operation.copy(
                    stage = CloneStage.RESTORING_SOURCE,
                    result = CloneResult.RUNNING,
                    updatedAt = System.currentTimeMillis(),
                    errorCode = code,
                    detail = "$message Cleanup and source restoration are still pending.",
                ),
            )
            return CloneExecutionResult.Retry("$message Cleanup and source restoration will be retried.")
        }
        val failed = operation.copy(
            stage = CloneStage.FAILED,
            result = CloneResult.FAILURE,
            sourceRestored = true,
            updatedAt = System.currentTimeMillis(),
            errorCode = code,
            detail = message,
        )
        instances.upsertCloneOperation(failed)
        return CloneExecutionResult.Failure(code, message, true)
    }

    private fun validateOperationIdentity(operation: CloneOperation, response: HelperResponse) {
        val expectedOperation = if (operation.recoveryPointId == null) "clone" else "recovery-clone"
        require(response.operation == expectedOperation || (operation.recoveryPointId != null && response.operation == "clone")) {
            "Helper omitted clone operation identity"
        }
        require(response.operationId == operation.operationId) { "Helper operation identity mismatch" }
        require(response.sourceInstanceId == operation.sourceInstanceId) { "Helper source identity mismatch" }
        require(response.targetInstanceId == operation.targetInstanceId) { "Helper target identity mismatch" }
        require(response.port == operation.targetPort) { "Helper target port mismatch" }
        operation.recoveryPointId?.let { point ->
            require(response.recoveryPointId == point.value || response.selectedRecoveryPointId == point.value) {
                "Helper recovery point identity mismatch"
            }
        }
    }

    private fun validateFailedOperation(operation: CloneOperation, response: HelperResponse) {
        validateOperationIdentity(operation, response)
        require(response.sourceWasRunning != null) { "Helper omitted the original source state" }
        require(response.sourceRestored != null) { "Helper omitted source restoration state" }
        require(!response.code.isNullOrBlank()) { "Helper omitted the clone failure code" }
    }

    private fun validateSnapshot(operation: CloneOperation, response: HelperResponse) {
        validateOperationIdentity(operation, response)
        require(response.sourceWasRunning != null) { "Helper omitted the original source state" }
        require(response.sourceRestored == true) { "Helper has not restored the source state" }
        val commit = requireNotNull(response.commit) { "Helper omitted cloned revision" }
        require(commit.matches(Regex("^[0-9a-fA-F]{40}$"))) { "Helper returned an invalid cloned revision" }
        require(commit.equals(operation.sourceRevision, ignoreCase = true)) { "Clone revision differs from the reserved source snapshot" }
    }

    private fun validateAbort(operation: CloneOperation, response: HelperResponse) {
        require(response.ok) { response.message ?: "The helper did not complete clone cleanup" }
        require(response.operation == "clone-abort") { "Helper omitted clone-abort operation identity" }
        require(response.operationId == operation.operationId) { "Clone cleanup operation identity mismatch" }
        require(response.sourceInstanceId == operation.sourceInstanceId) { "Clone cleanup source identity mismatch" }
        require(response.targetInstanceId == operation.targetInstanceId) { "Clone cleanup target identity mismatch" }
        require(response.sourceRestored == true) { "Clone cleanup did not restore the source" }
    }

    private suspend fun reportAndPersist(
        operation: CloneOperation,
        stage: CloneStage,
        detail: String?,
        progress: ProgressSink,
    ) {
        instances.upsertCloneOperation(operation.copy(stage = stage, updatedAt = System.currentTimeMillis(), detail = detail))
        progress.report(OperationState.Running(cloneStageLabel(stage), detail, null))
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_500L
        const val CLONE_TIMEOUT_MILLIS = 6 * 60 * 60 * 1000L
        const val MAX_PORT_REASSIGNMENTS = 8
        val SAFE_DISPATCH_REJECTION_CODES = setOf(
            "INVALID_ARGUMENT",
            "INVALID_OPERATION_ID",
            "INVALID_INSTANCE_ID",
            "INVALID_TARGET_INSTANCE_ID",
            "INVALID_TARGET",
            "INVALID_PORT",
            "UNSUPPORTED_VARIANT",
            "SOURCE_NOT_FOUND",
            "INSTANCE_NOT_INSTALLED",
            "TARGET_ALREADY_EXISTS",
        )
    }
}

private fun InstanceServerConfig.sameCloneRuntimeValues(other: InstanceServerConfig): Boolean =
    port == other.port && bindScope == other.bindScope && protocol == other.protocol &&
        bodyLimitMb == other.bodyLimitMb && saveIntervalSeconds == other.saveIntervalSeconds &&
        snapshotIntervalMinutes == other.snapshotIntervalMinutes &&
        snapshotRetentionCount == other.snapshotRetentionCount &&
        snapshotCapacityMb == other.snapshotCapacityMb

internal fun helperCloneStage(value: String?): CloneStage = when (value) {
    "SOURCE_INSPECTION" -> CloneStage.SOURCE_INSPECTION
    "SOURCE_STOPPING", "STOPPING_SOURCE", "STOPPING" -> CloneStage.SOURCE_STOPPING
    "SOURCE_STOPPED" -> CloneStage.STAGING_CREATION
    "STAGING_CREATION", "PREPARING_STAGING" -> CloneStage.STAGING_CREATION
    "COPYING" -> CloneStage.COPYING
    "VALIDATING" -> CloneStage.VALIDATING
    "TRANSFORMING", "APPLYING_CLONE_CONFIG" -> CloneStage.TRANSFORMING
    "RESTORING_SOURCE", "STARTING_SOURCE" -> CloneStage.RESTORING_SOURCE
    "READY_TO_PUBLISH" -> CloneStage.READY_TO_PUBLISH
    "PUBLISHING", "PUBLISHED" -> CloneStage.PUBLISHING
    else -> CloneStage.PREPARED
}

internal fun cloneStageLabel(stage: CloneStage): String = when (stage) {
    CloneStage.PREPARED -> "Preparing"
    CloneStage.SOURCE_INSPECTION -> "Inspecting source"
    CloneStage.SOURCE_STOPPING -> "Stopping source"
    CloneStage.STAGING_CREATION -> "Creating staging area"
    CloneStage.COPYING -> "Copying stopped snapshot"
    CloneStage.VALIDATING -> "Validating clone"
    CloneStage.TRANSFORMING -> "Applying independent settings"
    CloneStage.RESTORING_SOURCE -> "Restoring source state"
    CloneStage.READY_TO_PUBLISH -> "Ready to publish"
    CloneStage.PUBLISHING -> "Publishing"
    CloneStage.REGISTERING -> "Registering"
    CloneStage.COMPLETED -> "Completed"
    CloneStage.FAILED -> "Failed"
}

internal fun CloneOperation.toTargetConfig() = InstanceServerConfig(
    instanceId = targetInstanceId,
    port = targetPort,
    bindScope = BindScope.LOOPBACK,
    protocol = ServerProtocol.HTTP,
    certificateMode = CertificateMode.MANUAL,
    tlsDomain = null,
    acmeEmail = null,
    acmeChallenge = AcmeChallenge.HTTP_01,
    certificateExpiresAt = null,
    autoRenew = false,
    lastRenewCheckAt = null,
    lastRenewSuccessAt = null,
    lastRenewError = null,
    renewFailureCount = 0,
    bodyLimitMb = bodyLimitMb,
    saveIntervalSeconds = saveIntervalSeconds,
    snapshotIntervalMinutes = snapshotIntervalMinutes,
    snapshotRetentionCount = snapshotRetentionCount,
    snapshotCapacityMb = snapshotCapacityMb,
    imageQuality = imageQuality,
    applyState = ConfigApplyState.UNKNOWN,
)
