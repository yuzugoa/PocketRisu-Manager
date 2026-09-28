package io.github.risumanager.domain.runtime

import io.github.risumanager.domain.model.EnvironmentStatus
import io.github.risumanager.domain.model.LogLine
import io.github.risumanager.domain.model.OperationResult
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeStatus
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.model.SourceRevision
import io.github.risumanager.domain.model.VariantDefinition

interface RisuRuntime {
    val type: RuntimeType
    suspend fun checkEnvironment(): EnvironmentStatus
    suspend fun install(
        variant: VariantDefinition,
        instance: RisuInstance,
        target: SourceRevision,
        operationId: String,
        progress: ProgressSink,
    ): OperationResult
    suspend fun update(instance: RisuInstance, target: SourceRevision, operationId: String, progress: ProgressSink): OperationResult
    suspend fun rollback(instance: RisuInstance, recoveryPoint: RecoveryPoint, operationId: String, progress: ProgressSink): OperationResult
    suspend fun start(instance: RisuInstance): OperationResult
    suspend fun stop(instance: RisuInstance): OperationResult
    suspend fun restart(instance: RisuInstance): OperationResult
    suspend fun status(instance: RisuInstance): RuntimeStatus
    suspend fun logs(instance: RisuInstance, maxLines: Int): List<LogLine>
}

class RuntimeRegistry(runtimes: Set<RisuRuntime>) {
    private val byType = runtimes.associateBy { it.type }
    fun get(type: RuntimeType): RisuRuntime = requireNotNull(byType[type]) { "No runtime registered for $type" }
}
