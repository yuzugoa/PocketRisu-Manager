package io.github.risumanager.testing.fake

import io.github.risumanager.domain.model.EnvironmentState
import io.github.risumanager.domain.model.EnvironmentStatus
import io.github.risumanager.domain.model.LogLine
import io.github.risumanager.domain.model.OperationResult
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeStatus
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.model.SourceRevision
import io.github.risumanager.domain.model.VariantDefinition
import io.github.risumanager.domain.runtime.RisuRuntime
import java.util.concurrent.ConcurrentHashMap

open class FakeRisuRuntime(override val type: RuntimeType) : RisuRuntime {
    private val states = ConcurrentHashMap<String, RuntimeState>()
    var environment = EnvironmentStatus(
        if (type == RuntimeType.TERMUX_NODE) EnvironmentState.TERMUX_READY else EnvironmentState.NATIVE_APK_READY,
        "Fake runtime ready",
    )
    var nextFailure: OperationResult.Failure? = null

    override suspend fun checkEnvironment() = environment
    override suspend fun install(variant: VariantDefinition, instance: RisuInstance, target: SourceRevision, operationId: String, progress: ProgressSink): OperationResult {
        nextFailure?.let { nextFailure = null; return it }
        progress.report(OperationState.Running("Installing", null, null))
        states[instance.instanceId] = RuntimeState.STOPPED
        return OperationResult.Success("Installed", target.sha, target.displayVersion, instance.port)
    }
    override suspend fun update(instance: RisuInstance, target: SourceRevision, operationId: String, progress: ProgressSink) = OperationResult.Success("Updated", target.sha)
    override suspend fun rollback(instance: RisuInstance, recoveryPoint: RecoveryPoint, operationId: String, progress: ProgressSink) =
        OperationResult.Failure(
            "ROLLBACK_NOT_IMPLEMENTED",
            "The fake runtime does not implement recovery-point restore.",
            false,
        )
    override suspend fun start(instance: RisuInstance): OperationResult { states[instance.instanceId] = RuntimeState.RUNNING; return OperationResult.Success("Started") }
    override suspend fun stop(instance: RisuInstance): OperationResult { states[instance.instanceId] = RuntimeState.STOPPED; return OperationResult.Success("Stopped") }
    override suspend fun restart(instance: RisuInstance): OperationResult { states[instance.instanceId] = RuntimeState.RUNNING; return OperationResult.Success("Restarted") }
    override suspend fun status(instance: RisuInstance) = RuntimeStatus(states[instance.instanceId] ?: RuntimeState.STOPPED, port = instance.port)
    override suspend fun logs(instance: RisuInstance, maxLines: Int) = listOf(LogLine("fake", "Fake runtime log"))
}

class FakeTermuxRuntime : FakeRisuRuntime(RuntimeType.TERMUX_NODE)
class FakeNativeApkRuntime : FakeRisuRuntime(RuntimeType.NATIVE_APK)
