package io.github.risumanager.operation

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.data.repository.InstanceRepository
import io.github.risumanager.domain.model.BackupMetadata
import io.github.risumanager.domain.model.BindScope
import io.github.risumanager.domain.model.CloneOperation
import io.github.risumanager.domain.model.CloneResult
import io.github.risumanager.domain.model.CloneStage
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.OperationRecord
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.model.ServerProtocol
import io.github.risumanager.runtime.termux.CloneCommands
import io.github.risumanager.runtime.termux.HelperProtocolException
import io.github.risumanager.runtime.termux.HelperResponse
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class CloneServiceTest {
    @Test fun `stopped source clone publishes an independent stopped local target`() = runTest {
        val fixture = fixture(sourceRunning = false)
        fixture.helper.operationSteps += { ready(fixture.operation, sourceWasRunning = false) }

        val result = fixture.execute()

        assertThat(result).isEqualTo(CloneExecutionResult.Success(TARGET_ID))
        val target = fixture.repository.instanceRows[TARGET_ID]!!
        assertThat(target.instanceId).isNotEqualTo(SOURCE_ID)
        assertThat(target.port).isNotEqualTo(fixture.source.port)
        assertThat(target.installedRevision).isEqualTo(fixture.source.installedRevision)
        assertThat(target.installedVersion).isEqualTo(fixture.source.installedVersion)
        assertThat(target.lastKnownStatus).isEqualTo(RuntimeState.STOPPED)
        assertThat(fixture.repository.configRows[TARGET_ID]!!.bindScope).isEqualTo(BindScope.LOOPBACK)
        assertThat(fixture.repository.configRows[TARGET_ID]!!.protocol).isEqualTo(ServerProtocol.HTTP)
        assertThat(fixture.repository.configRows[TARGET_ID]!!.autoRenew).isFalse()
        assertThat(fixture.repository.configRows[TARGET_ID]!!.tlsDomain).isNull()
    }

    @Test fun `running source clone requires confirmed source restoration before success`() = runTest {
        val fixture = fixture(sourceRunning = true)
        fixture.helper.operationSteps += { ready(fixture.operation, sourceWasRunning = true) }

        assertThat(fixture.execute()).isEqualTo(CloneExecutionResult.Success(TARGET_ID))

        val completed = fixture.repository.cloneRows.getValue(OPERATION_ID)
        assertThat(completed.sourceWasRunning).isTrue()
        assertThat(completed.sourceRestored).isTrue()
        assertThat(completed.result).isEqualTo(CloneResult.SUCCESS)
    }

    @Test fun `case A missing helper journal is idempotently dispatched and then completed`() = runTest {
        val fixture = fixture()
        fixture.helper.operationSteps += { throw HelperProtocolException("OPERATION_NOT_FOUND", "missing") }
        fixture.helper.operationSteps += { ready(fixture.operation, false) }
        fixture.helper.dispatchSteps += { queued(fixture.operation) }

        assertThat(fixture.execute()).isEqualTo(CloneExecutionResult.Success(TARGET_ID))
        assertThat(fixture.helper.dispatchPorts).containsExactly(fixture.operation.targetPort)
        assertThat(fixture.repository.publicationInserts).isEqualTo(1)
    }

    @Test fun `case B failed copy aborts owned staging and restores source before terminal failure`() = runTest {
        val fixture = fixture(sourceRunning = true, stage = CloneStage.COPYING)
        fixture.helper.operationSteps += { failed(fixture.operation, sourceWasRunning = true, sourceRestored = false) }
        fixture.helper.abortSteps += { aborted(fixture.operation) }

        val result = fixture.execute()

        assertThat(result).isEqualTo(CloneExecutionResult.Failure("COPY_FAILED", "copy failed", true))
        assertThat(fixture.helper.abortCalls).isEqualTo(1)
        assertThat(fixture.repository.instanceRows).doesNotContainKey(TARGET_ID)
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).sourceRestored).isTrue()
    }

    @Test fun `case C ready staging is explicitly published before database registration`() = runTest {
        val fixture = fixture(stage = CloneStage.READY_TO_PUBLISH)
        fixture.helper.operationSteps += { ready(fixture.operation, false) }

        assertThat(fixture.execute()).isInstanceOf(CloneExecutionResult.Success::class.java)
        assertThat(fixture.helper.publishCalls).isEqualTo(1)
        assertThat(fixture.repository.publishAttempts).isEqualTo(1)
    }

    @Test fun `case D completed database operation returns success without contacting helper`() = runTest {
        val fixture = fixture()
        fixture.repository.cloneRows[OPERATION_ID] = fixture.operation.copy(
            stage = CloneStage.COMPLETED,
            result = CloneResult.SUCCESS,
            helperPublished = true,
            sourceRestored = true,
        )

        assertThat(fixture.execute()).isEqualTo(CloneExecutionResult.Success(TARGET_ID))
        assertThat(fixture.helper.ensureCalls).isEqualTo(0)
        assertThat(fixture.helper.operationCalls).isEqualTo(0)
    }

    @Test fun `dispatch rejection is terminal because source mutation never began`() = runTest {
        val fixture = fixture()
        fixture.helper.operationSteps += { throw HelperProtocolException("OPERATION_NOT_FOUND", "missing") }
        fixture.helper.dispatchSteps += { HelperResponse(false, code = "SOURCE_NOT_FOUND", message = "source missing") }

        assertThat(fixture.execute()).isEqualTo(CloneExecutionResult.Failure("SOURCE_NOT_FOUND", "source missing", true))
        assertThat(fixture.helper.abortCalls).isEqualTo(0)
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).result).isEqualTo(CloneResult.FAILURE)
    }

    @Test fun `ambiguous dispatch failure stays resumable instead of claiming safe cleanup`() = runTest {
        val fixture = fixture()
        fixture.helper.operationSteps += { throw HelperProtocolException("OPERATION_NOT_FOUND", "missing") }
        fixture.helper.dispatchSteps += { HelperResponse(false, code = "CLONE_BUSY", message = "dispatch lock busy") }

        val result = fixture.execute()

        assertThat(result).isInstanceOf(CloneExecutionResult.Retry::class.java)
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).result).isEqualTo(CloneResult.RUNNING)
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).sourceRestored).isNull()
    }

    @Test fun `source restart cleanup failure remains resumable until a verified abort succeeds`() = runTest {
        val fixture = fixture(sourceRunning = true, stage = CloneStage.RESTORING_SOURCE)
        repeat(2) {
            fixture.helper.operationSteps += { failed(fixture.operation, sourceWasRunning = true, sourceRestored = false) }
        }
        fixture.helper.abortSteps.clear()
        fixture.helper.abortSteps += { HelperResponse(false, code = "SOURCE_RESTART_FAILED", message = "still stopped") }
        fixture.helper.abortSteps += { aborted(fixture.operation) }

        assertThat(fixture.execute()).isInstanceOf(CloneExecutionResult.Retry::class.java)
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).result).isEqualTo(CloneResult.RUNNING)
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).stage).isEqualTo(CloneStage.RESTORING_SOURCE)

        assertThat(fixture.execute()).isEqualTo(CloneExecutionResult.Failure("COPY_FAILED", "copy failed", true))
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).result).isEqualTo(CloneResult.FAILURE)
    }

    @Test fun `publish rejection cleans provisional target before recording failure`() = runTest {
        val fixture = fixture()
        fixture.helper.operationSteps += { ready(fixture.operation, false) }
        fixture.helper.publishSteps.clear()
        fixture.helper.publishSteps += { HelperResponse(false, code = "PUBLISH_FAILED", message = "rename failed") }
        fixture.helper.abortSteps += { aborted(fixture.operation) }

        assertThat(fixture.execute()).isEqualTo(CloneExecutionResult.Failure("PUBLISH_FAILED", "rename failed", true))
        assertThat(fixture.helper.abortCalls).isEqualTo(1)
        assertThat(fixture.repository.instanceRows).doesNotContainKey(TARGET_ID)
    }

    @Test fun `database failure after helper publication resumes same id without duplicate publish`() = runTest {
        val fixture = fixture(sourceRunning = true)
        fixture.helper.operationSteps += { ready(fixture.operation, true) }
        fixture.helper.operationSteps += { succeeded(fixture.operation, true) }
        fixture.repository.failPublications = 1

        assertThat(fixture.execute()).isInstanceOf(CloneExecutionResult.Retry::class.java)
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).helperPublished).isTrue()
        assertThat(fixture.repository.instanceRows).doesNotContainKey(TARGET_ID)

        assertThat(fixture.execute()).isEqualTo(CloneExecutionResult.Success(TARGET_ID))
        assertThat(fixture.helper.publishCalls).isEqualTo(1)
        assertThat(fixture.repository.publicationInserts).isEqualTo(1)
    }

    @Test fun `malformed ready response is rejected and cannot register a partial clone`() = runTest {
        val fixture = fixture()
        fixture.helper.operationSteps += {
            ready(fixture.operation, false).copy(operationId = null)
        }
        fixture.helper.abortSteps += { aborted(fixture.operation) }

        val result = fixture.execute()

        assertThat(result).isInstanceOf(CloneExecutionResult.Failure::class.java)
        result as CloneExecutionResult.Failure
        assertThat(result.code).isEqualTo("INVALID_HELPER_RESPONSE")
        assertThat(fixture.helper.abortCalls).isEqualTo(1)
        assertThat(fixture.repository.instanceRows).doesNotContainKey(TARGET_ID)
    }

    @Test fun `cancellation is propagated and the durable operation resumes on the next execution`() = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        fixture.helper.operationHandler = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val running = async { fixture.execute() }
        entered.await()
        running.cancel()
        try {
            running.await()
            fail("CancellationException was swallowed")
        } catch (_: CancellationException) {
            // Expected: WorkManager cancellation must leave the durable journal resumable.
        }
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).result).isEqualTo(CloneResult.RUNNING)

        fixture.helper.operationHandler = { ready(fixture.repository.cloneRows.getValue(OPERATION_ID), false) }
        assertThat(fixture.execute()).isEqualTo(CloneExecutionResult.Success(TARGET_ID))
        assertThat(fixture.repository.publicationInserts).isEqualTo(1)
    }

    @Test fun `busy reserved port is replaced and the helper receives the new reservation`() = runTest {
        val fixture = fixture()
        fixture.helper.operationSteps += { throw HelperProtocolException("OPERATION_NOT_FOUND", "missing") }
        fixture.helper.operationSteps += { throw HelperProtocolException("OPERATION_NOT_FOUND", "missing") }
        fixture.helper.operationSteps += { ready(fixture.operation.copy(targetPort = fixture.operation.targetPort + 1), false) }
        fixture.helper.dispatchSteps += { HelperResponse(false, code = "PORT_UNAVAILABLE", message = "busy") }
        fixture.helper.dispatchSteps += { queued(fixture.operation.copy(targetPort = fixture.operation.targetPort + 1)) }
        fixture.helper.configResponse = configResponse(fixture.operation.copy(targetPort = fixture.operation.targetPort + 1))
        fixture.helper.publishSteps.clear()
        fixture.helper.publishSteps += { succeeded(fixture.operation.copy(targetPort = fixture.operation.targetPort + 1), false) }

        assertThat(fixture.execute()).isEqualTo(CloneExecutionResult.Success(TARGET_ID))
        assertThat(fixture.helper.dispatchPorts).containsExactly(fixture.operation.targetPort, fixture.operation.targetPort + 1).inOrder()
        assertThat(fixture.repository.instanceRows.getValue(TARGET_ID).port).isEqualTo(fixture.operation.targetPort + 1)
    }

    @Test fun `repeated helper port rejection terminates instead of polling forever`() = runTest {
        val fixture = fixture()
        repeat(8) {
            fixture.helper.operationSteps += { throw HelperProtocolException("OPERATION_NOT_FOUND", "missing") }
            fixture.helper.dispatchSteps += { HelperResponse(false, code = "PORT_UNAVAILABLE", message = "busy") }
        }

        val result = fixture.execute()

        assertThat(result).isInstanceOf(CloneExecutionResult.Failure::class.java)
        result as CloneExecutionResult.Failure
        assertThat(result.code).isEqualTo("PORT_UNAVAILABLE")
        assertThat(fixture.helper.dispatchPorts).hasSize(8)
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).result).isEqualTo(CloneResult.FAILURE)
    }

    @Test fun `published helper config must exactly match reserved safe settings before registration`() = runTest {
        val fixture = fixture()
        fixture.helper.operationSteps += { ready(fixture.operation, false) }
        fixture.helper.configResponse = configResponse(fixture.operation).copy(bodyLimitMb = fixture.operation.bodyLimitMb + 1)

        assertThat(fixture.execute()).isInstanceOf(CloneExecutionResult.Retry::class.java)
        assertThat(fixture.repository.cloneRows.getValue(OPERATION_ID).helperPublished).isTrue()
        assertThat(fixture.repository.instanceRows).doesNotContainKey(TARGET_ID)
    }

    private fun fixture(sourceRunning: Boolean = false, stage: CloneStage = CloneStage.PREPARED): Fixture {
        val source = RisuInstance(
            instanceId = SOURCE_ID,
            variantId = "pocketrisu",
            channelId = "stable",
            runtimeType = RuntimeType.TERMUX_NODE,
            repository = "PocketRisu/PocketRisu",
            installedRevision = REVISION,
            installedVersion = "1.2.3",
            port = 6001,
            createdAt = 1,
            updatedAt = 1,
            lastKnownStatus = if (sourceRunning) RuntimeState.RUNNING else RuntimeState.STOPPED,
        )
        val operation = CloneOperation(
            operationId = OPERATION_ID,
            sourceInstanceId = SOURCE_ID,
            targetInstanceId = TARGET_ID,
            targetDisplayName = "Independent copy",
            targetPort = 6002,
            sourceRevision = REVISION,
            sourceVersion = "1.2.3",
            bodyLimitMb = 321,
            saveIntervalSeconds = 12,
            snapshotIntervalMinutes = 17,
            snapshotRetentionCount = 27,
            snapshotCapacityMb = 987,
            imageQuality = 72,
            stage = stage,
            sourceWasRunning = if (stage == CloneStage.PREPARED) null else sourceRunning,
            sourceRestored = if (stage == CloneStage.PREPARED) null else false,
            createdAt = 1,
            updatedAt = 1,
        )
        val repository = InMemoryInstanceRepository(source, operation)
        val helper = FakeCloneCommands().apply {
            publishSteps += { succeeded(repository.cloneRows.getValue(OPERATION_ID), sourceRunning) }
            abortSteps += { aborted(repository.cloneRows.getValue(OPERATION_ID)) }
            configResponse = configResponse(operation)
        }
        return Fixture(source, operation, repository, helper)
    }

    private data class Fixture(
        val source: RisuInstance,
        val operation: CloneOperation,
        val repository: InMemoryInstanceRepository,
        val helper: FakeCloneCommands,
    ) {
        suspend fun execute(): CloneExecutionResult = CloneService(helper, repository, InstanceOperationGate())
            .execute(OPERATION_ID, ProgressSink { })
    }

    private class FakeCloneCommands : CloneCommands {
        val operationSteps = ArrayDeque<suspend () -> HelperResponse>()
        val dispatchSteps = ArrayDeque<suspend () -> HelperResponse>()
        val publishSteps = ArrayDeque<suspend () -> HelperResponse>()
        val abortSteps = ArrayDeque<suspend () -> HelperResponse>()
        val dispatchPorts = mutableListOf<Int>()
        var ensureCalls = 0
        var operationCalls = 0
        var publishCalls = 0
        var abortCalls = 0
        lateinit var configResponse: HelperResponse
        var operationHandler: suspend (String) -> HelperResponse = {
            check(operationSteps.isNotEmpty()) { "No fake operation response remains" }
            operationSteps.removeFirst().invoke()
        }

        override suspend fun ensureInstalled(): HelperResponse {
            ensureCalls += 1
            return HelperResponse(true, helperVersion = 15)
        }

        override suspend fun operation(operationId: String): HelperResponse {
            operationCalls += 1
            return operationHandler(operationId)
        }

        override suspend fun dispatchClone(
            operationId: String,
            sourceInstanceId: String,
            targetInstanceId: String,
            targetPort: Int,
        ): HelperResponse {
            dispatchPorts += targetPort
            return dispatchSteps.removeFirst().invoke()
        }

        override suspend fun publishClone(operationId: String): HelperResponse {
            publishCalls += 1
            return publishSteps.removeFirst().invoke()
        }

        override suspend fun abortClone(operationId: String): HelperResponse {
            abortCalls += 1
            return abortSteps.removeFirst().invoke()
        }

        override suspend fun configGet(instanceId: String): HelperResponse = configResponse
    }

    private class InMemoryInstanceRepository(source: RisuInstance, operation: CloneOperation) : InstanceRepository {
        val instanceRows = linkedMapOf(source.instanceId to source)
        val cloneRows = linkedMapOf(operation.operationId to operation)
        val configRows = linkedMapOf(source.instanceId to InstanceServerConfig(source.instanceId, port = source.port))
        var failPublications = 0
        var publishAttempts = 0
        var publicationInserts = 0

        override fun observeInstances(): Flow<List<RisuInstance>> = flowOf(instanceRows.values.toList())
        override suspend fun get(instanceId: String): RisuInstance? = instanceRows[instanceId]
        override suspend fun upsert(instance: RisuInstance) { instanceRows[instance.instanceId] = instance }
        override suspend fun updateStatus(instanceId: String, state: RuntimeState) {
            instanceRows[instanceId]?.let { instanceRows[instanceId] = it.copy(lastKnownStatus = state) }
        }
        override suspend fun upsertOperation(operation: OperationRecord) = Unit
        override suspend fun operations(instanceId: String): List<OperationRecord> = emptyList()
        override suspend fun upsertBackup(backup: BackupMetadata) = Unit
        override suspend fun backups(instanceId: String): List<BackupMetadata> = emptyList()
        override suspend fun getServerConfig(instanceId: String): InstanceServerConfig? = configRows[instanceId]
        override suspend fun getServerConfigs(): List<InstanceServerConfig> = configRows.values.toList()
        override suspend fun upsertServerConfig(config: InstanceServerConfig) { configRows[config.instanceId] = config }
        override suspend fun deleteServerConfig(instanceId: String) { configRows.remove(instanceId) }
        override suspend fun prepareClone(
            operationId: String,
            sourceInstanceId: String,
            targetInstanceId: String,
            targetDisplayName: String,
        ): CloneOperation = error("Not used by CloneService")
        override suspend fun cloneOperation(operationId: String): CloneOperation? = cloneRows[operationId]
        override suspend fun activeCloneOperations(): List<CloneOperation> = cloneRows.values.filter { !it.isTerminal }
        override suspend fun upsertCloneOperation(operation: CloneOperation) { cloneRows[operation.operationId] = operation }
        override suspend fun reassignClonePort(operationId: String): CloneOperation {
            val updated = cloneRows.getValue(operationId).let { it.copy(targetPort = it.targetPort + 1, updatedAt = it.updatedAt + 1) }
            cloneRows[operationId] = updated
            return updated
        }
        override suspend fun publishClone(operation: CloneOperation, target: RisuInstance, config: InstanceServerConfig) {
            publishAttempts += 1
            if (failPublications > 0) {
                failPublications -= 1
                error("database write failed")
            }
            val existing = instanceRows[target.instanceId]
            if (existing == null) {
                instanceRows[target.instanceId] = target
                configRows[target.instanceId] = config
                publicationInserts += 1
            } else {
                check(existing.port == target.port && existing.installedRevision == target.installedRevision)
            }
            cloneRows[operation.operationId] = operation.copy(
                stage = CloneStage.COMPLETED,
                result = CloneResult.SUCCESS,
                updatedAt = operation.updatedAt + 1,
            )
        }
    }

    companion object {
        private const val OPERATION_ID = "clone_12345678"
        private const val SOURCE_ID = "pocketrisu-source"
        private const val TARGET_ID = "pocketrisu-target"
        private val REVISION = "a".repeat(40)

        private fun queued(operation: CloneOperation) = cloneResponse(
            operation,
            state = "QUEUED",
            stage = "QUEUED",
            sourceWasRunning = false,
            sourceRestored = false,
            includeSnapshot = false,
        )

        private fun ready(operation: CloneOperation, sourceWasRunning: Boolean) = cloneResponse(
            operation,
            state = "READY_TO_PUBLISH",
            stage = "READY_TO_PUBLISH",
            sourceWasRunning = sourceWasRunning,
            sourceRestored = true,
        )

        private fun succeeded(operation: CloneOperation, sourceWasRunning: Boolean) = cloneResponse(
            operation,
            state = "SUCCEEDED",
            stage = "PUBLISHED",
            sourceWasRunning = sourceWasRunning,
            sourceRestored = true,
        )

        private fun failed(operation: CloneOperation, sourceWasRunning: Boolean, sourceRestored: Boolean) = cloneResponse(
            operation,
            ok = false,
            state = "FAILED",
            stage = "RESTORING_SOURCE",
            sourceWasRunning = sourceWasRunning,
            sourceRestored = sourceRestored,
            includeSnapshot = false,
            code = "COPY_FAILED",
            message = "copy failed",
        )

        private fun cloneResponse(
            operation: CloneOperation,
            ok: Boolean = true,
            state: String,
            stage: String,
            sourceWasRunning: Boolean,
            sourceRestored: Boolean,
            includeSnapshot: Boolean = true,
            code: String? = null,
            message: String? = null,
        ) = HelperResponse(
            ok = ok,
            operation = "clone",
            operationId = operation.operationId,
            sourceInstanceId = operation.sourceInstanceId,
            targetInstanceId = operation.targetInstanceId,
            state = state,
            stage = stage,
            code = code,
            message = message,
            commit = operation.sourceRevision.takeIf { includeSnapshot },
            version = operation.sourceVersion.takeIf { includeSnapshot },
            port = operation.targetPort,
            sourceWasRunning = sourceWasRunning,
            sourceRestored = sourceRestored,
        )

        private fun aborted(operation: CloneOperation) = HelperResponse(
            ok = true,
            operation = "clone-abort",
            operationId = operation.operationId,
            sourceInstanceId = operation.sourceInstanceId,
            targetInstanceId = operation.targetInstanceId,
            sourceRestored = true,
        )

        private fun configResponse(operation: CloneOperation) = HelperResponse(
            ok = true,
            instanceId = operation.targetInstanceId,
            port = operation.targetPort,
            bindScope = BindScope.LOOPBACK.name,
            protocol = ServerProtocol.HTTP.name,
            bodyLimitMb = operation.bodyLimitMb,
            saveIntervalSeconds = operation.saveIntervalSeconds,
            snapshotIntervalMinutes = operation.snapshotIntervalMinutes,
            snapshotRetentionCount = operation.snapshotRetentionCount,
            snapshotMaxMb = operation.snapshotCapacityMb,
        )
    }
}
