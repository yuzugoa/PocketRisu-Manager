package io.github.risumanager.runtime.termux

import io.github.risumanager.domain.model.EnvironmentState
import io.github.risumanager.domain.model.EnvironmentStatus
import io.github.risumanager.domain.model.BindScope
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.LogLine
import io.github.risumanager.domain.model.OperationResult
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeStatus
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.model.ServerProtocol
import io.github.risumanager.domain.model.SourceRevision
import io.github.risumanager.domain.model.VariantDefinition
import io.github.risumanager.domain.runtime.RisuRuntime
import io.github.risumanager.domain.validation.Validators
import io.github.risumanager.operation.InstanceOperationGate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class TermuxRuntimeAdapter(
    private val commandClient: TermuxCommandClient,
    private val helper: TermuxHelperClient,
    private val operationGate: InstanceOperationGate,
) : RisuRuntime {
    override val type: RuntimeType = RuntimeType.TERMUX_NODE

    override suspend fun checkEnvironment(): EnvironmentStatus {
        if (!commandClient.isInstalled()) return EnvironmentStatus(
            EnvironmentState.TERMUX_NOT_INSTALLED,
            "Install a current Termux build, then return to Risu Manager.",
        )
        if (!commandClient.hasPermission()) return EnvironmentStatus(
            EnvironmentState.TERMUX_PERMISSION_MISSING,
            "Grant Risu Manager permission to run commands in Termux.",
        )
        return try {
            if (!helper.handshake()) {
                EnvironmentStatus(
                    EnvironmentState.TERMUX_EXTERNAL_COMMANDS_DISABLED,
                    "Enable allow-external-apps in Termux, reload settings, then retry.",
                )
            } else {
                helper.ensureInstalled()
                val doctor = helper.doctor()
                EnvironmentStatus(
                    EnvironmentState.TERMUX_READY,
                    "Termux integration is ready.",
                    helperVersion = doctor.helperVersion,
                    diagnostics = mapOf(
                        "Node.js" to doctor.node.orEmpty().ifBlank { "Installed during PocketRisu setup" },
                        "Platform" to doctor.platform.orEmpty().ifBlank { "Unknown" },
                        "Device architecture" to doctor.deviceArchitecture.orEmpty().ifBlank { "Unknown" },
                        "Node architecture" to doctor.nodeArchitecture.orEmpty().ifBlank { "Unknown" },
                        "Node ABI" to doctor.nodeAbi.orEmpty().ifBlank { "Unknown" },
                        "pnpm" to doctor.pnpm.orEmpty().ifBlank { "Installed during PocketRisu setup" },
                        "git" to doctor.git.orEmpty().ifBlank { "Installed during PocketRisu setup" },
                    ),
                )
            }
        } catch (_: TermuxCommandException.PermissionDenied) {
            EnvironmentStatus(EnvironmentState.TERMUX_PERMISSION_MISSING, "RUN_COMMAND permission was denied.")
        } catch (_: TermuxCommandException.TimedOut) {
            EnvironmentStatus(
                EnvironmentState.TERMUX_EXTERNAL_COMMANDS_DISABLED,
                "Termux did not answer. Confirm allow-external-apps=true and reload Termux settings.",
            )
        } catch (exception: HelperProtocolException) {
            if (exception.code == "TERMUX_INTERNAL_ERROR" &&
                exception.message?.contains("allow-external-apps", ignoreCase = true) == true
            ) {
                EnvironmentStatus(
                    EnvironmentState.TERMUX_EXTERNAL_COMMANDS_DISABLED,
                    "Enable allow-external-apps=true in Termux, reload Termux settings, then retry.",
                )
            } else {
                EnvironmentStatus(EnvironmentState.TERMUX_ERROR, exception.message ?: "Termux integration failed.")
            }
        } catch (exception: Exception) {
            EnvironmentStatus(EnvironmentState.TERMUX_ERROR, exception.message ?: "Termux integration failed.")
        }
    }

    override suspend fun install(
        variant: VariantDefinition,
        instance: RisuInstance,
        target: SourceRevision,
        operationId: String,
        progress: ProgressSink,
    ): OperationResult {
        val profile = variant.termuxProfile
        if (variant.runtimeType != type || profile == null || profile.id != variant.id || profile.repository != variant.repository) {
            return OperationResult.Failure("UNSUPPORTED_VARIANT", "The variant has no matching trusted Termux Node profile.", false)
        }
        if (!Validators.isFullCommitSha(target.sha)) {
            return OperationResult.Failure("INVALID_REVISION", "Installation requires a verified full commit SHA.", false)
        }
        return try {
            helper.ensureInstalled()
            coroutineScope {
                progress.report(
                    OperationState.Running(
                        "Installing dependencies and building",
                        "Termux is keeping the trusted build in a foreground task",
                        null,
                    ),
                )
                val installCommand = async {
                    helper.runInstall(
                        operationId,
                        instance.instanceId,
                        variant.id,
                        target.sha,
                        target.installArtifact?.downloadUrl,
                        target.installArtifact?.sha256,
                        target.installArtifact?.checksumUrl,
                        INSTALL_TIMEOUT_MILLIS,
                    )
                }
                withTimeout(INSTALL_TIMEOUT_MILLIS) {
                    while (true) {
                        val response = try {
                            helper.operation(operationId)
                        } catch (exception: HelperProtocolException) {
                            if (exception.code == "OPERATION_NOT_FOUND" && !installCommand.isCompleted) {
                                progress.report(OperationState.Running("Queued", "Waiting for Termux", null))
                                delay(1_500)
                                continue
                            }
                            throw exception
                        }
                        when (response.state) {
                            "QUEUED" -> progress.report(OperationState.Running("Queued", "Waiting for Termux", null))
                            "RUNNING" -> progress.report(
                                OperationState.Running(
                                    stage = stageLabel(response.stage),
                                    detail = response.detail,
                                    progress = null,
                                ),
                            )
                            "SUCCEEDED" -> return@withTimeout OperationResult.Success(
                                message = response.message ?: "${variant.displayName} is ready.",
                                revision = response.commit,
                                version = response.version,
                                port = response.port,
                            )
                            "FAILED" -> return@withTimeout OperationResult.Failure(
                                code = response.code ?: "INSTALL_FAILED",
                                message = response.message ?: "${variant.displayName} installation failed.",
                                recoverable = true,
                            )
                            else -> return@withTimeout OperationResult.Failure(
                                "INVALID_HELPER_STATE",
                                "The Termux helper returned an unknown operation state.",
                                true,
                            )
                        }
                        delay(1_500)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    OperationResult.Failure("INSTALL_FAILED", "Installation ended unexpectedly.")
                }
            }
        } catch (exception: Exception) {
            OperationResult.Failure(errorCode(exception), exception.message ?: "${variant.displayName} installation failed.", true)
        }
    }

    override suspend fun update(
        instance: RisuInstance,
        target: SourceRevision,
        operationId: String,
        progress: ProgressSink,
    ): OperationResult = OperationResult.Failure(
        "UPDATE_NOT_IMPLEMENTED",
        "Updates are scheduled for Phase 5; the current build intentionally does not modify a working instance.",
        false,
    )

    override suspend fun rollback(
        instance: RisuInstance,
        recoveryPoint: RecoveryPoint,
        operationId: String,
        progress: ProgressSink,
    ): OperationResult = OperationResult.Failure(
        "ROLLBACK_NOT_IMPLEMENTED",
        "Recovery-point rollback is scheduled for Phase 6.",
        false,
    )

    override suspend fun start(instance: RisuInstance): OperationResult = operationGate.withInstance(instance.instanceId) {
        // Renewal is deliberately best-effort: an ACME/network failure must not
        // prevent a previously valid PocketRisu instance from starting.
        runCatching { helper.acmeRenew(instance.instanceId) }
        runHelperOperation { helper.start(instance.instanceId) }
    }
    override suspend fun stop(instance: RisuInstance): OperationResult = operationGate.withInstance(instance.instanceId) {
        runHelperOperation { helper.stop(instance.instanceId) }
    }
    override suspend fun restart(instance: RisuInstance): OperationResult = operationGate.withInstance(instance.instanceId) {
        runHelperOperation { helper.restart(instance.instanceId) }
    }

    suspend fun acmeInstall(instanceId: String): HelperResponse = operationGate.withInstance(instanceId) {
        helper.acmeInstall(instanceId).requireOk("ACME_INSTALL_FAILED")
    }

    suspend fun acmePreflight(
        instanceId: String,
        domain: String,
        email: String,
        challengePort: Int,
    ): HelperResponse = operationGate.withInstance(instanceId) {
        helper.acmePreflight(instanceId, domain, email, challengePort)
            .requireOk("ACME_PREFLIGHT_FAILED")
    }

    suspend fun acmeIssue(
        instanceId: String,
        domain: String,
        email: String,
        challengePort: Int,
    ): HelperResponse = operationGate.withInstance(instanceId) {
        helper.acmeIssue(instanceId, domain, email, challengePort)
            .requireOk("ACME_ISSUE_FAILED")
    }

    suspend fun acmeRenew(instanceId: String): HelperResponse = operationGate.withInstance(instanceId) {
        helper.acmeRenew(instanceId).requireOk("ACME_RENEW_FAILED")
    }

    suspend fun acmeCertificateStatus(instanceId: String): HelperResponse = operationGate.withInstance(instanceId) {
        helper.acmeCertificateStatus(instanceId).requireOk("ACME_STATUS_FAILED")
    }

    suspend fun acmeDisable(instanceId: String): HelperResponse = operationGate.withInstance(instanceId) {
        helper.acmeDisable(instanceId).requireOk("ACME_DISABLE_FAILED")
    }

    override suspend fun status(instance: RisuInstance): RuntimeStatus = try {
        helper.ensureInstalled()
        val response = helper.status(instance.instanceId)
        RuntimeStatus(
            state = response.state.toRuntimeState(),
            installed = response.installed ?: true,
            pid = response.pid,
            port = response.port ?: instance.port,
            revision = response.commit,
            detail = response.message,
        )
    } catch (exception: Exception) {
        RuntimeStatus(RuntimeState.UNKNOWN, detail = exception.message)
    }

    override suspend fun logs(instance: RisuInstance, maxLines: Int): List<LogLine> = try {
        helper.logs(instance.instanceId, maxLines.coerceIn(1, 1000))
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { LogLine(source = "server", text = it) }
            .toList()
    } catch (exception: Exception) {
        listOf(LogLine(source = "manager", text = "Unable to read logs: ${exception.message}"))
    }

    suspend fun installationLog(operationId: String, maxLines: Int = 400): List<LogLine> = try {
        helper.ensureInstalled()
        helper.operationLog(operationId, maxLines.coerceIn(1, 2000))
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { LogLine(source = "installer", text = it) }
            .toList()
    } catch (exception: Exception) {
        listOf(LogLine(source = "manager", text = "Unable to read installer log: ${exception.message}"))
    }

    suspend fun readServerConfig(instanceId: String): RuntimeServerConfig = operationGate.withInstance(instanceId) {
        helper.ensureInstalled()
        val response = helper.configGet(instanceId)
        if (!response.ok) throw HelperProtocolException(
            response.code ?: "CONFIG_READ_FAILED",
            response.message ?: "The Termux server configuration could not be read.",
        )
        RuntimeServerConfig(
            desired = response.toConfig(instanceId),
            applied = response.applied?.toConfig(instanceId),
            lastApplyError = response.lastApplyError?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun applyServerConfig(instance: RisuInstance, desired: InstanceServerConfig): OperationResult =
        operationGate.withInstance(instance.instanceId) {
            ServerConfigApplier(helper).apply(instance, desired)
        }

    private suspend fun runHelperOperation(block: suspend () -> HelperResponse): OperationResult = try {
        helper.ensureInstalled()
        val response = block()
        if (response.ok) OperationResult.Success(response.message ?: response.state.orEmpty().ifBlank { "Operation complete" })
        else OperationResult.Failure(response.code ?: "RUNTIME_OPERATION_FAILED", response.message ?: "Runtime operation failed.")
    } catch (exception: Exception) {
        OperationResult.Failure(errorCode(exception), exception.message ?: "Termux operation failed.", true)
    }

    private fun stageLabel(stage: String?): String = when (stage) {
        "PREPARING_TERMUX" -> "Preparing Termux"
        "RECOVERING_DEPENDENCIES" -> "Recovering Termux dependencies"
        "RECOVERING_PROJECT" -> "Recovering project installation"
        "CLONING" -> "Fetching source"
        "BUILDING" -> "Installing dependencies and building"
        "VERIFYING" -> "Verifying installation"
        "READY" -> "Ready"
        else -> stage?.replace('_', ' ')?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Working"
    }

    private fun String?.toRuntimeState(): RuntimeState = runCatching { RuntimeState.valueOf(this ?: "UNKNOWN") }
        .getOrDefault(RuntimeState.UNKNOWN)

    private fun errorCode(exception: Exception): String = when (exception) {
        is TermuxCommandException.NotInstalled -> "TERMUX_NOT_INSTALLED"
        is TermuxCommandException.PermissionDenied -> "TERMUX_PERMISSION_DENIED"
        is TermuxCommandException.TimedOut -> "TERMUX_TIMEOUT"
        is HelperProtocolException -> exception.code
        else -> "TERMUX_ERROR"
    }

    private companion object {
        const val INSTALL_TIMEOUT_MILLIS = 60 * 60 * 1000L
    }
}

data class RuntimeServerConfig(
    val desired: InstanceServerConfig,
    val applied: InstanceServerConfig?,
    val lastApplyError: String?,
)

internal fun HelperResponse.requireOk(defaultCode: String): HelperResponse {
    if (!ok) throw HelperProtocolException(code ?: defaultCode, message ?: "The Termux helper rejected the operation.")
    return this
}

internal fun HelperResponse.toConfig(instanceId: String): InstanceServerConfig = InstanceServerConfig(
    instanceId = instanceId,
    port = requireNotNull(port) { "Helper response omitted port" },
    bindScope = BindScope.valueOf(requireNotNull(bindScope) { "Helper response omitted bind scope" }),
    protocol = ServerProtocol.valueOf(requireNotNull(protocol) { "Helper response omitted protocol" }),
    bodyLimitMb = requireNotNull(bodyLimitMb) { "Helper response omitted body limit" },
    saveIntervalSeconds = requireNotNull(saveIntervalSeconds) { "Helper response omitted save interval" },
    snapshotIntervalMinutes = requireNotNull(snapshotIntervalMinutes) { "Helper response omitted snapshot interval" },
    snapshotRetentionCount = requireNotNull(snapshotRetentionCount) { "Helper response omitted snapshot retention" },
    snapshotCapacityMb = requireNotNull(snapshotMaxMb) { "Helper response omitted snapshot capacity" },
)

internal fun HelperServerConfig.toConfig(instanceId: String): InstanceServerConfig = InstanceServerConfig(
    instanceId = instanceId,
    port = port,
    bindScope = BindScope.valueOf(bindScope),
    protocol = ServerProtocol.valueOf(protocol),
    bodyLimitMb = bodyLimitMb,
    saveIntervalSeconds = saveIntervalSeconds,
    snapshotIntervalMinutes = snapshotIntervalMinutes,
    snapshotRetentionCount = snapshotRetentionCount,
    snapshotCapacityMb = snapshotMaxMb,
)

internal fun InstanceServerConfig.sameRuntimeValues(other: InstanceServerConfig): Boolean =
    port == other.port && bindScope == other.bindScope && protocol == other.protocol &&
        bodyLimitMb == other.bodyLimitMb && saveIntervalSeconds == other.saveIntervalSeconds &&
        snapshotIntervalMinutes == other.snapshotIntervalMinutes &&
        snapshotRetentionCount == other.snapshotRetentionCount && snapshotCapacityMb == other.snapshotCapacityMb
