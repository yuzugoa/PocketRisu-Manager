package io.github.risumanager.runtime.termux

import android.content.Context
import android.util.Base64
import io.github.risumanager.domain.validation.Validators
import io.github.risumanager.domain.model.InstanceServerConfig
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

interface CloneCommands {
    suspend fun ensureInstalled(): HelperResponse
    suspend fun operation(operationId: String): HelperResponse
    suspend fun dispatchClone(operationId: String, sourceInstanceId: String, targetInstanceId: String, targetPort: Int): HelperResponse
    suspend fun dispatchRecoveryClone(
        operationId: String,
        sourceInstanceId: String,
        selectedRecoveryPointId: String,
        targetInstanceId: String,
        targetPort: Int,
    ): HelperResponse = throw UnsupportedOperationException("Recovery-point cloning is unavailable")
    suspend fun publishClone(operationId: String): HelperResponse
    suspend fun abortClone(operationId: String): HelperResponse
    suspend fun configGet(instanceId: String): HelperResponse
}

interface UpdateCommands {
    suspend fun ensureInstalled(): HelperResponse
    suspend fun operation(operationId: String): HelperResponse
    suspend fun recoveryEstimate(instanceId: String): HelperResponse
    suspend fun dispatchUpdate(
        operationId: String,
        instanceId: String,
        variantId: String,
        targetRevision: String,
        recoveryPointId: String,
        retentionSeconds: Long,
    ): HelperResponse
    suspend fun cleanupRecovery(recoveryPointId: String, now: Long): HelperResponse
}

interface RollbackCommands {
    suspend fun ensureInstalled(): HelperResponse
    suspend fun operation(operationId: String): HelperResponse
    suspend fun recoveryInspect(recoveryPointId: String, instanceId: String): HelperResponse
    suspend fun recoveryPin(recoveryPointId: String, pinned: Boolean): HelperResponse
    suspend fun recoveryDelete(recoveryPointId: String): HelperResponse
    suspend fun dispatchRollback(
        operationId: String,
        instanceId: String,
        selectedRecoveryPointId: String,
        preRollbackRecoveryPointId: String,
        retentionSeconds: Long,
    ): HelperResponse
    suspend fun dispatchRecoveryClone(
        operationId: String,
        sourceInstanceId: String,
        selectedRecoveryPointId: String,
        targetInstanceId: String,
        targetPort: Int,
    ): HelperResponse
    suspend fun configGet(instanceId: String): HelperResponse
}

class TermuxHelperClient(
    private val context: Context,
    private val commandClient: TermuxCommandClient,
    private val json: Json,
) : ServerConfigCommands, CloneCommands, UpdateCommands, RollbackCommands {
    suspend fun handshake(): Boolean {
        val result = commandClient.execute(
            TermuxCommand(
                executable = TermuxContract.PRINTF,
                arguments = listOf(HANDSHAKE_VALUE),
                label = "Risu Manager handshake",
                description = "Verifies that Termux accepts external commands.",
            ),
        )
        return result.internalError == android.app.Activity.RESULT_OK &&
            result.exitCode == 0 &&
            matchesTermuxHandshake(result.stdout)
    }

    override suspend fun ensureInstalled(): HelperResponse {
        val current = runCatching { call("version") }.getOrNull()
        if (current?.ok == true && current.helperVersion == EXPECTED_VERSION) return current
        val installer = context.assets.open("termux-helper/install.sh").bufferedReader().use { it.readText() }
        val compressedInstaller = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(installer.toByteArray(Charsets.UTF_8)) }
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }
        val result = commandClient.execute(
            TermuxCommand(
                executable = TermuxContract.SHELL,
                arguments = listOf("-c", "base64 -d | gzip -d | sh -s"),
                stdin = compressedInstaller,
                label = "Install Risu Manager helper",
                description = "Installs the versioned, manager-owned helper in Termux.",
                timeoutMillis = 60_000,
            ),
        )
        val response = decode(result)
        if (!response.ok || response.helperVersion != EXPECTED_VERSION) {
            throw HelperProtocolException(response.code ?: "HELPER_INSTALL_FAILED", response.message ?: "Helper verification failed")
        }
        return response
    }

    suspend fun doctor(): HelperResponse = call("doctor")

    suspend fun dispatchInstall(
        operationId: String,
        instanceId: String,
        variantId: String,
        revision: String,
        artifactUrl: String? = null,
        artifactSha256: String? = null,
        checksumUrl: String? = null,
    ): HelperResponse {
        require(Validators.isOperationId(operationId))
        require(Validators.isInstanceId(instanceId))
        require(Validators.isFullCommitSha(revision))
        if (artifactUrl == null) return call("dispatch", "install", operationId, instanceId, variantId, revision)
        require(Validators.isHttpsUrl(artifactUrl))
        val hash = requireNotNull(artifactSha256)
        val checksum = requireNotNull(checksumUrl)
        require(hash.matches(Regex("^[0-9a-f]{64}$")))
        require(Validators.isHttpsUrl(checksum))
        return call("dispatch", "install", operationId, instanceId, variantId, revision, artifactUrl, hash, checksum)
    }

    suspend fun runInstall(
        operationId: String,
        instanceId: String,
        variantId: String,
        revision: String,
        artifactUrl: String? = null,
        artifactSha256: String? = null,
        checksumUrl: String? = null,
        timeoutMillis: Long,
    ): TermuxCommandResult {
        require(Validators.isOperationId(operationId))
        require(Validators.isInstanceId(instanceId))
        require(Validators.isFullCommitSha(revision))
        val arguments = mutableListOf("_install", operationId, instanceId, variantId, revision)
        if (artifactUrl != null) {
            require(Validators.isHttpsUrl(artifactUrl))
            val hash = requireNotNull(artifactSha256)
            val checksum = requireNotNull(checksumUrl)
            require(hash.matches(Regex("^[0-9a-f]{64}$")))
            require(Validators.isHttpsUrl(checksum))
            arguments += listOf(artifactUrl, hash, checksum)
        }
        return executeHelper(arguments, timeoutMillis)
    }

    override suspend fun operation(operationId: String): HelperResponse {
        require(Validators.isOperationId(operationId))
        return call("operation", operationId).requireReadableOperationResult()
    }

    suspend fun operationLog(operationId: String, maxLines: Int): String {
        require(Validators.isOperationId(operationId))
        require(maxLines in 1..2000)
        val result = executeHelper(listOf("operation-log", operationId, maxLines.toString()), 30_000)
        if (result.internalError != android.app.Activity.RESULT_OK) {
            throw HelperProtocolException("TERMUX_INTERNAL_ERROR", result.internalErrorMessage ?: "Termux failed to read the operation log")
        }
        if (result.exitCode != 0) throw HelperProtocolException("LOG_READ_FAILED", result.stderr.ifBlank { result.stdout })
        return result.stdout
    }

    suspend fun dispatchBackup(operationId: String, instanceId: String): HelperResponse {
        require(Validators.isOperationId(operationId))
        require(Validators.isInstanceId(instanceId))
        return call("dispatch", "backup", operationId, instanceId)
    }

    override suspend fun dispatchClone(operationId: String, sourceInstanceId: String, targetInstanceId: String, targetPort: Int): HelperResponse {
        require(Validators.isOperationId(operationId))
        require(Validators.isInstanceId(sourceInstanceId))
        require(Validators.isInstanceId(targetInstanceId))
        require(sourceInstanceId != targetInstanceId)
        require(targetPort in 1024..65535)
        return call("dispatch", "clone", operationId, sourceInstanceId, targetInstanceId, targetPort.toString())
    }

    override suspend fun recoveryEstimate(instanceId: String): HelperResponse {
        require(Validators.isInstanceId(instanceId))
        return call("recovery-estimate", instanceId, timeoutMillis = 60_000)
    }

    override suspend fun dispatchUpdate(
        operationId: String,
        instanceId: String,
        variantId: String,
        targetRevision: String,
        recoveryPointId: String,
        retentionSeconds: Long,
    ): HelperResponse {
        require(Validators.isOperationId(operationId))
        require(Validators.isInstanceId(instanceId))
        require(variantId == "pocketrisu")
        require(Validators.isFullCommitSha(targetRevision))
        require(RECOVERY_ID.matches(recoveryPointId))
        require(retentionSeconds in 1..MAX_HELPER_RETENTION_SECONDS)
        return call(
            "dispatch", "update", operationId, instanceId, variantId, targetRevision,
            recoveryPointId, retentionSeconds.toString(),
        )
    }

    override suspend fun cleanupRecovery(recoveryPointId: String, now: Long): HelperResponse {
        require(RECOVERY_ID.matches(recoveryPointId))
        require(now > 0)
        return call("recovery-cleanup", recoveryPointId, now.toString(), timeoutMillis = 60_000)
    }

    override suspend fun recoveryInspect(recoveryPointId: String, instanceId: String): HelperResponse {
        require(RECOVERY_ID.matches(recoveryPointId))
        require(Validators.isInstanceId(instanceId))
        return call("recovery-inspect", instanceId, recoveryPointId, timeoutMillis = 60_000)
    }

    override suspend fun recoveryPin(recoveryPointId: String, pinned: Boolean): HelperResponse {
        require(RECOVERY_ID.matches(recoveryPointId))
        return call("recovery-pin", recoveryPointId, if (pinned) "1" else "0", timeoutMillis = 60_000)
    }

    override suspend fun recoveryDelete(recoveryPointId: String): HelperResponse {
        require(RECOVERY_ID.matches(recoveryPointId))
        return call("recovery-delete", recoveryPointId, timeoutMillis = 60_000)
    }

    override suspend fun dispatchRollback(
        operationId: String,
        instanceId: String,
        selectedRecoveryPointId: String,
        preRollbackRecoveryPointId: String,
        retentionSeconds: Long,
    ): HelperResponse {
        require(Validators.isOperationId(operationId))
        require(Validators.isInstanceId(instanceId))
        require(RECOVERY_ID.matches(selectedRecoveryPointId))
        require(RECOVERY_ID.matches(preRollbackRecoveryPointId))
        require(selectedRecoveryPointId != preRollbackRecoveryPointId)
        require(retentionSeconds in 1..MAX_HELPER_RETENTION_SECONDS)
        return call(
            "dispatch", "rollback", operationId, instanceId, selectedRecoveryPointId,
            preRollbackRecoveryPointId, retentionSeconds.toString(),
        )
    }

    override suspend fun dispatchRecoveryClone(
        operationId: String,
        sourceInstanceId: String,
        selectedRecoveryPointId: String,
        targetInstanceId: String,
        targetPort: Int,
    ): HelperResponse {
        require(Validators.isOperationId(operationId))
        require(Validators.isInstanceId(sourceInstanceId))
        require(RECOVERY_ID.matches(selectedRecoveryPointId))
        require(Validators.isInstanceId(targetInstanceId))
        require(sourceInstanceId != targetInstanceId)
        require(targetPort in 1024..65535)
        return call(
            "dispatch", "recovery-clone", operationId, sourceInstanceId,
            selectedRecoveryPointId, targetInstanceId, targetPort.toString(),
        )
    }

    override suspend fun publishClone(operationId: String): HelperResponse {
        require(Validators.isOperationId(operationId))
        return call("clone-publish", operationId, timeoutMillis = 60_000)
    }

    override suspend fun abortClone(operationId: String): HelperResponse {
        require(Validators.isOperationId(operationId))
        return call("clone-abort", operationId, timeoutMillis = 60_000)
    }

    suspend fun cleanupBackup(operationId: String, instanceId: String, token: String): HelperResponse {
        require(Validators.isOperationId(operationId))
        require(Validators.isInstanceId(instanceId))
        require(TOKEN.matches(token))
        return call("backup-cleanup", operationId, instanceId, token)
    }

    override suspend fun start(instanceId: String): HelperResponse = callInstance("start", instanceId, timeoutMillis = 45_000)
    override suspend fun stop(instanceId: String): HelperResponse = callInstance("stop", instanceId, timeoutMillis = 20_000)
    suspend fun restart(instanceId: String): HelperResponse = callInstance("restart", instanceId, timeoutMillis = 60_000)
    override suspend fun status(instanceId: String): HelperResponse = callInstance("status", instanceId)

    suspend fun acmeInstall(instanceId: String): HelperResponse {
        ensureInstalled()
        return callInstance("acme-install", instanceId, timeoutMillis = 5 * 60_000L)
    }

    suspend fun acmePreflight(instanceId: String, domain: String, email: String, challengePort: Int): HelperResponse {
        ensureInstalled()
        val normalizedDomain = requireNotNull(Validators.normalizeTlsDomain(domain)) { "Invalid TLS domain" }
        val normalizedEmail = requireNotNull(Validators.normalizeAcmeEmail(email)) { "Invalid ACME email" }
        require(challengePort in 1024..65535) { "Invalid ACME challenge port" }
        return call(
            "acme-preflight", instanceId, normalizedDomain, normalizedEmail, challengePort.toString(),
            timeoutMillis = 60_000,
        )
    }

    suspend fun acmeIssue(instanceId: String, domain: String, email: String, challengePort: Int): HelperResponse {
        ensureInstalled()
        val normalizedDomain = requireNotNull(Validators.normalizeTlsDomain(domain)) { "Invalid TLS domain" }
        val normalizedEmail = requireNotNull(Validators.normalizeAcmeEmail(email)) { "Invalid ACME email" }
        require(challengePort in 1024..65535) { "Invalid ACME challenge port" }
        return call(
            "acme-issue", instanceId, normalizedDomain, normalizedEmail, challengePort.toString(),
            timeoutMillis = 15 * 60_000L,
        )
    }

    suspend fun acmeRenew(instanceId: String): HelperResponse {
        ensureInstalled()
        return callInstance("acme-renew-check", instanceId, timeoutMillis = 10 * 60_000)
    }

    suspend fun acmeCertificateStatus(instanceId: String): HelperResponse {
        ensureInstalled()
        return callInstance("acme-certificate-status", instanceId, timeoutMillis = 30_000)
    }

    suspend fun acmeDisable(instanceId: String): HelperResponse {
        ensureInstalled()
        return callInstance("acme-disable", instanceId, timeoutMillis = 60_000)
    }

    override suspend fun configGet(instanceId: String): HelperResponse = callInstance("config-get", instanceId)

    override suspend fun configValidate(instanceId: String): HelperResponse = callInstance("config-validate", instanceId)

    override suspend fun configSet(config: InstanceServerConfig): HelperResponse {
        require(Validators.validate(config).isValid)
        require(Validators.isInstanceId(config.instanceId))
        return call(
            "config-set",
            config.instanceId,
            config.port.toString(),
            config.bindScope.name,
            config.protocol.name,
            config.bodyLimitMb.toString(),
            config.saveIntervalSeconds.toString(),
            config.snapshotIntervalMinutes.toString(),
            config.snapshotRetentionCount.toString(),
            config.snapshotCapacityMb.toString(),
        )
    }

    suspend fun logs(instanceId: String, maxLines: Int): String {
        require(Validators.isInstanceId(instanceId))
        require(maxLines in 1..1000)
        val result = executeHelper(listOf("logs", instanceId, maxLines.toString()), 30_000)
        if (result.internalError != android.app.Activity.RESULT_OK) {
            throw HelperProtocolException("TERMUX_INTERNAL_ERROR", result.internalErrorMessage ?: "Termux failed to read logs")
        }
        if (result.exitCode != 0) throw HelperProtocolException("LOG_READ_FAILED", result.stderr.ifBlank { result.stdout })
        return result.stdout
    }

    private suspend fun callInstance(command: String, instanceId: String, timeoutMillis: Long = 30_000): HelperResponse {
        require(Validators.isInstanceId(instanceId))
        return call(command, instanceId, timeoutMillis = timeoutMillis)
    }

    private suspend fun call(vararg arguments: String, timeoutMillis: Long = 30_000): HelperResponse =
        decode(executeHelper(arguments.toList(), timeoutMillis))

    private suspend fun executeHelper(arguments: List<String>, timeoutMillis: Long): TermuxCommandResult = commandClient.execute(
        TermuxCommand(
            executable = TermuxContract.HELPER,
            arguments = arguments,
            label = "Risu Manager ${arguments.firstOrNull().orEmpty()}",
            description = "Runs an allowlisted Risu Manager helper operation.",
            timeoutMillis = timeoutMillis,
        ),
    )

    private fun decode(result: TermuxCommandResult): HelperResponse {
        if (result.internalError != android.app.Activity.RESULT_OK) {
            throw HelperProtocolException("TERMUX_INTERNAL_ERROR", result.internalErrorMessage ?: "Termux rejected the command")
        }
        val payload = result.stdout.trim().lineSequence().lastOrNull { it.isNotBlank() }
            ?: throw HelperProtocolException("EMPTY_HELPER_RESPONSE", result.stderr.ifBlank { "Helper returned no structured output" })
        return runCatching { json.decodeFromString<HelperResponse>(payload) }
            .getOrElse { throw HelperProtocolException("INVALID_HELPER_RESPONSE", "Helper returned invalid structured output") }
    }

    companion object {
        const val EXPECTED_VERSION = 24
        const val HANDSHAKE_VALUE = "RISU_MANAGER_OK"
        private val TOKEN = Regex("^[0-9a-f]{64}$")
        private val RECOVERY_ID = Regex("^[A-Za-z0-9_-]{8,80}$")
        private const val MAX_HELPER_RETENTION_SECONDS = 365L * 24 * 60 * 60
    }
}

internal fun HelperResponse.requireReadableOperationResult(): HelperResponse {
    // A terminal install/backup failure is a structured operation result. Keep
    // it available to its coordinator so it can surface the installer
    // code/message instead of replacing it with a generic protocol exception.
    if (!ok && !(state == "FAILED" && operation in setOf("install", "backup", "clone", "update", "rollback"))) {
        throw HelperProtocolException(
            code ?: "HELPER_OPERATION_FAILED",
            message ?: "The Termux helper could not read the operation state.",
        )
    }
    return this
}

internal fun matchesTermuxHandshake(stdout: String): Boolean =
    stdout.trimEnd('\r', '\n') == TermuxHelperClient.HANDSHAKE_VALUE
