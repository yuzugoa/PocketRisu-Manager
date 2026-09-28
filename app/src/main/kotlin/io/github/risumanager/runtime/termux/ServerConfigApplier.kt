package io.github.risumanager.runtime.termux

import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.OperationResult
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.validation.Validators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Narrow command boundary so the stop/configure/restart transaction can be tested. */
interface ServerConfigCommands {
    suspend fun ensureInstalled(): HelperResponse
    suspend fun configGet(instanceId: String): HelperResponse
    suspend fun status(instanceId: String): HelperResponse
    suspend fun stop(instanceId: String): HelperResponse
    suspend fun configSet(config: InstanceServerConfig): HelperResponse
    suspend fun configValidate(instanceId: String): HelperResponse
    suspend fun start(instanceId: String): HelperResponse
}

internal class ServerConfigApplier(private val helper: ServerConfigCommands) {
    suspend fun apply(instance: RisuInstance, desired: InstanceServerConfig): OperationResult {
        val validation = Validators.validate(desired)
        if (!validation.isValid || desired.instanceId != instance.instanceId) {
            return OperationResult.Failure(
                "INVALID_CONFIG",
                validation.errors.joinToString("; ").ifBlank { "The configuration belongs to another instance." },
                false,
            )
        }
        var previous: InstanceServerConfig? = null
        var wasRunning = false
        var mutationStarted = false
        return try {
            helper.ensureInstalled()
            val previousResponse = helper.configGet(instance.instanceId)
            previousResponse.requireOk("CONFIG_READ_FAILED")
            previous = previousResponse.toConfig(instance.instanceId)
            val before = helper.status(instance.instanceId)
            before.requireOk("SERVER_STATUS_FAILED")
            wasRunning = before.state == "RUNNING" || before.state == "UNHEALTHY" || before.pid != null
            mutationStarted = true
            if (wasRunning) helper.stop(instance.instanceId).requireOk("SERVER_STOP_FAILED")
            helper.configSet(desired).requireOk("CONFIG_WRITE_FAILED")
            helper.configValidate(instance.instanceId).requireOk("CONFIG_VALIDATE_FAILED")
            if (wasRunning) {
                helper.start(instance.instanceId).requireOk("SERVER_RESTART_FAILED")
                val confirmed = helper.configGet(instance.instanceId).applied?.toConfig(instance.instanceId)
                if (confirmed == null || !confirmed.sameRuntimeValues(desired)) {
                    throw HelperProtocolException("CONFIG_CONFIRM_FAILED", "The restarted server did not confirm the requested configuration.")
                }
            }
            OperationResult.Success(
                if (wasRunning) "Server settings were applied and the instance restarted." else "Server settings were saved and will apply on the next start.",
                port = desired.port,
            )
        } catch (exception: Exception) {
            val rollbackError = withContext(NonCancellable) {
                runCatching {
                    if (mutationStarted) previous?.let {
                        helper.stop(instance.instanceId).requireOk("ROLLBACK_STOP_FAILED")
                        helper.configSet(it).requireOk("CONFIG_ROLLBACK_FAILED")
                        if (wasRunning) helper.start(instance.instanceId).requireOk("ROLLBACK_RESTART_FAILED")
                    }
                }.exceptionOrNull()
            }
            if (exception is CancellationException) {
                rollbackError?.let(exception::addSuppressed)
                throw exception
            }
            val suffix = rollbackError?.message?.let { " Rollback also failed: $it" }.orEmpty()
            OperationResult.Failure(errorCode(exception), (exception.message ?: "Server settings could not be applied.") + suffix, true)
        }
    }

    private fun errorCode(exception: Exception): String = when (exception) {
        is TermuxCommandException.NotInstalled -> "TERMUX_NOT_INSTALLED"
        is TermuxCommandException.PermissionDenied -> "TERMUX_PERMISSION_DENIED"
        is TermuxCommandException.TimedOut -> "TERMUX_TIMEOUT"
        is HelperProtocolException -> exception.code
        else -> "TERMUX_ERROR"
    }

}
