package io.github.risumanager.testing.fake

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.BuiltInVariants
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.OperationResult
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeType
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeRuntimeTest {
    @Test fun fakeRuntimeSupportsViewModelLifecycleWithoutTermux() = runTest {
        val runtime = FakeTermuxRuntime()
        val instance = RisuInstance(
            instanceId = "pocketrisu-ab12cd34",
            variantId = BuiltInVariants.pocketRisu.id,
            channelId = "stable",
            runtimeType = RuntimeType.TERMUX_NODE,
            repository = BuiltInVariants.pocketRisu.repository,
            createdAt = 1,
            updatedAt = 1,
        )
        runtime.start(instance)
        assertThat(runtime.status(instance).state).isEqualTo(RuntimeState.RUNNING)
        runtime.stop(instance)
        assertThat(runtime.status(instance).state).isEqualTo(RuntimeState.STOPPED)
    }

    @Test fun rollbackContractRequiresARecoveryPointAndRemainsExplicitlyUnsupported() = runTest {
        val runtime = FakeTermuxRuntime()
        val instance = RisuInstance(
            instanceId = "pocketrisu-ab12cd34",
            variantId = BuiltInVariants.pocketRisu.id,
            channelId = "stable",
            runtimeType = RuntimeType.TERMUX_NODE,
            repository = BuiltInVariants.pocketRisu.repository,
            createdAt = 1,
            updatedAt = 1,
        )
        val point = RecoveryPoint(
            id = RecoveryPointId("rp-1"),
            instanceId = instance.instanceId,
            createdAt = 2,
            serverRevision = "a".repeat(40),
            dataDigestSha256 = "b".repeat(64),
            configurationDigestSha256 = "c".repeat(64),
            formatVersion = 1,
            variantId = instance.variantId,
            layoutVersion = 1,
        )

        val result = runtime.rollback(instance, point, "rollback_12345678", ProgressSink { })

        assertThat(result).isInstanceOf(OperationResult.Failure::class.java)
        assertThat(result.code).isEqualTo("ROLLBACK_NOT_IMPLEMENTED")
    }
}
