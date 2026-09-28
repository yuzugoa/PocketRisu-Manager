package io.github.risumanager.runtime.termux

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.OperationResult
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ServerConfigApplierTest {
    private val instance = RisuInstance("pocketrisu-test", "pocketrisu", "stable", RuntimeType.TERMUX_NODE,
        "PocketRisu/PocketRisu", createdAt = 1, updatedAt = 1)
    private val original = InstanceServerConfig(instance.instanceId)
    private val desired = original.copy(port = 7001, bodyLimitMb = 256)

    @Test fun cancellationAfterEachMutationRestoresSettingsAndRunningServer() = runTest {
        for (stage in listOf("stop", "write", "start")) {
            val commands = Commands(original, stallAt = stage)
            val job = launch { ServerConfigApplier(commands).apply(instance, desired) }
            commands.stalled.await()
            job.cancelAndJoin()
            assertThat(job.isCancelled).isTrue()
            assertThat(commands.config).isEqualTo(original)
            assertThat(commands.applied).isEqualTo(original)
            assertThat(commands.running).isTrue()
        }
    }

    @Test fun restartFailureRollsBackBeforeReportingFailure() = runTest {
        val commands = Commands(original).apply { failNextStart = true }
        val result = ServerConfigApplier(commands).apply(instance, desired)
        assertThat(result).isInstanceOf(OperationResult.Failure::class.java)
        assertThat(commands.config).isEqualTo(original)
        assertThat(commands.applied).isEqualTo(original)
        assertThat(commands.running).isTrue()
    }

    @Test fun successfulApplyConfirmsRequestedRuntimeValues() = runTest {
        val commands = Commands(original)
        val result = ServerConfigApplier(commands).apply(instance, desired)
        assertThat(result).isInstanceOf(OperationResult.Success::class.java)
        assertThat(commands.config).isEqualTo(desired)
        assertThat(commands.applied).isEqualTo(desired)
        assertThat(commands.running).isTrue()
    }

    @Test fun stoppedInstanceStaysStoppedWhenSettingsAreSaved() = runTest {
        val commands = Commands(original).apply { running = false; applied = null }
        val result = ServerConfigApplier(commands).apply(instance, desired)
        assertThat(result).isInstanceOf(OperationResult.Success::class.java)
        assertThat(commands.config).isEqualTo(desired)
        assertThat(commands.running).isFalse()
        assertThat(commands.applied).isNull()
    }

    @Test fun failedPreflightDoesNotStopOrModifyServer() = runTest {
        val commands = Commands(original).apply { failStatus = true }
        val result = ServerConfigApplier(commands).apply(instance, desired)
        assertThat(result).isInstanceOf(OperationResult.Failure::class.java)
        assertThat(commands.mutations).isEqualTo(0)
        assertThat(commands.running).isTrue()
        assertThat(commands.config).isEqualTo(original)
    }

    private class Commands(var config: InstanceServerConfig, private var stallAt: String? = null) : ServerConfigCommands {
        var running = true
        var applied: InstanceServerConfig? = config
        var failNextStart = false
        var failStatus = false
        var mutations = 0
        val stalled = CompletableDeferred<Unit>()

        override suspend fun ensureInstalled() = HelperResponse(ok = true)
        override suspend fun status(instanceId: String) = HelperResponse(
            ok = !failStatus, state = if (running) "RUNNING" else "STOPPED",
        )
        override suspend fun configGet(instanceId: String) = HelperResponse(
            ok = true, port = config.port, bindScope = config.bindScope.name, protocol = config.protocol.name,
            bodyLimitMb = config.bodyLimitMb, saveIntervalSeconds = config.saveIntervalSeconds,
            snapshotIntervalMinutes = config.snapshotIntervalMinutes,
            snapshotRetentionCount = config.snapshotRetentionCount, snapshotMaxMb = config.snapshotCapacityMb,
            applied = applied?.let { HelperServerConfig(it.port, it.bindScope.name, it.protocol.name,
                it.bodyLimitMb, it.saveIntervalSeconds, it.snapshotIntervalMinutes,
                it.snapshotRetentionCount, it.snapshotCapacityMb) },
        )
        override suspend fun stop(instanceId: String): HelperResponse {
            running = false
            mutate("stop")
            return HelperResponse(ok = true)
        }
        override suspend fun configSet(config: InstanceServerConfig): HelperResponse {
            this.config = config
            mutate("write")
            return HelperResponse(ok = true)
        }
        override suspend fun configValidate(instanceId: String) = HelperResponse(ok = true)
        override suspend fun start(instanceId: String): HelperResponse {
            if (failNextStart) {
                failNextStart = false
                return HelperResponse(ok = false, code = "HEALTH_CHECK_FAILED")
            }
            running = true
            applied = config
            mutate("start")
            return HelperResponse(ok = true)
        }
        private suspend fun mutate(stage: String) {
            mutations++
            if (stallAt == stage) {
                stallAt = null
                stalled.complete(Unit)
                awaitCancellation()
            }
            // Rollback must be able to suspend despite cancellation of its caller.
            delay(1)
        }
    }
}
