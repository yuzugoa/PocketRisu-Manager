package io.github.risumanager.runtime.nativeapk

import io.github.risumanager.domain.model.EnvironmentState
import io.github.risumanager.domain.model.EnvironmentStatus
import io.github.risumanager.domain.model.LogLine
import io.github.risumanager.domain.model.OperationResult
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeStatus
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.model.SourceRevision
import io.github.risumanager.domain.model.VariantDefinition
import io.github.risumanager.domain.runtime.RisuRuntime

/** Phase 1 boundary for native packages. Download/install behavior intentionally begins in Phase 7. */
class NativeApkRuntimeAdapter : RisuRuntime {
    override val type = RuntimeType.NATIVE_APK
    override suspend fun checkEnvironment() = EnvironmentStatus(
        EnvironmentState.NATIVE_APK_READY,
        "Android's package installer is available; native APK support is not enabled in this phase.",
    )

    override suspend fun install(variant: VariantDefinition, instance: RisuInstance, target: SourceRevision, operationId: String, progress: ProgressSink) = unavailable()
    override suspend fun update(instance: RisuInstance, target: SourceRevision, operationId: String, progress: ProgressSink) = unavailable()
    override suspend fun rollback(instance: RisuInstance, recoveryPoint: RecoveryPoint, operationId: String, progress: ProgressSink) = unavailable()
    override suspend fun start(instance: RisuInstance) = unavailable()
    override suspend fun stop(instance: RisuInstance) = unavailable()
    override suspend fun restart(instance: RisuInstance) = unavailable()
    override suspend fun status(instance: RisuInstance) = RuntimeStatus(RuntimeState.UNKNOWN, installed = false, detail = "Native APK runtime is planned for Phase 7")
    override suspend fun logs(instance: RisuInstance, maxLines: Int): List<LogLine> = emptyList()

    private fun unavailable() = OperationResult.Failure(
        "NATIVE_APK_NOT_AVAILABLE",
        "This release keeps the native APK adapter boundary but does not install APKs yet.",
        false,
    )
}
