package io.github.risumanager.operation

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.data.repository.InstanceRepository
import io.github.risumanager.domain.model.BackupMetadata
import io.github.risumanager.domain.model.CloneOperation
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.OperationRecord
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.RestorationState
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.UpdateOperation
import io.github.risumanager.domain.model.UpdateResult
import io.github.risumanager.domain.model.UpdateStage
import io.github.risumanager.runtime.termux.HelperProtocolException
import io.github.risumanager.runtime.termux.HelperResponse
import io.github.risumanager.runtime.termux.UpdateCommands
import java.util.ArrayDeque
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UpdateServiceTest {
    @Test fun `known pre-worker dispatch rejections become durable terminal failures`() = runTest {
        val rejectionCodes = listOf(
            "UPDATE_PATH_UNSUPPORTED",
            "INSUFFICIENT_DISK_SPACE",
            "RECOVERY_POINT_CONFLICT",
            "SIZE_ESTIMATE_FAILED",
            "OPERATION_STATE_FAILED",
        )

        rejectionCodes.forEach { code ->
            val fixture = fixture()
            fixture.helper.operationSteps += {
                throw HelperProtocolException("OPERATION_NOT_FOUND", "missing")
            }
            fixture.helper.dispatchStep = {
                HelperResponse(ok = false, code = code, message = "rejected: $code")
            }

            val result = fixture.execute()

            assertThat(result).isEqualTo(
                UpdateExecutionResult.Failure(
                    code = code,
                    message = "rejected: $code",
                    restored = true,
                    recoveryRequired = false,
                ),
            )
            val stored = fixture.repository.operation
            assertThat(stored.result).isEqualTo(UpdateResult.FAILURE)
            assertThat(stored.stage).isEqualTo(UpdateStage.FAILED)
            assertThat(stored.errorCode).isEqualTo(code)
            assertThat(stored.completedAt).isNotNull()
            val recovery = fixture.repository.recoveryPoint
            assertThat(recovery.state).isEqualTo(RecoveryPointState.FAILED)
            assertThat(recovery.inUse).isFalse()
            assertThat(recovery.restorationState).isEqualTo(RestorationState.NOT_REQUIRED)
        }
    }

    @Test fun `transport exception during dispatch leaves reservation running for reconciliation`() = runTest {
        val fixture = fixture()
        fixture.helper.operationSteps += {
            throw HelperProtocolException("OPERATION_NOT_FOUND", "missing")
        }
        fixture.helper.dispatchStep = { throw IllegalStateException("transport interrupted") }

        val result = fixture.execute()

        assertThat(result).isEqualTo(UpdateExecutionResult.Retry("transport interrupted"))
        assertThat(fixture.repository.operation.result).isEqualTo(UpdateResult.RUNNING)
        assertThat(fixture.repository.operation.completedAt).isNull()
        assertThat(fixture.repository.recoveryPoint.state).isEqualTo(RecoveryPointState.CREATING)
        assertThat(fixture.repository.recoveryPoint.inUse).isTrue()
    }

    private fun fixture(): Fixture {
        val operation = UpdateOperation(
            operationId = "update_12345678",
            instanceId = "pocketrisu-test",
            variantId = "pocketrisu",
            layoutVersion = 1,
            targetRevision = "b".repeat(40),
            targetVersion = "1.12.0",
            currentRevision = "a".repeat(40),
            currentVersion = "1.11.2",
            recoveryPointId = RecoveryPointId("recovery_12345678"),
            createdAt = 1,
            updatedAt = 1,
        )
        val recoveryPoint = RecoveryPoint(
            id = operation.recoveryPointId,
            instanceId = operation.instanceId,
            createdAt = 1,
            serverRevision = operation.currentRevision,
            dataDigestSha256 = "",
            configurationDigestSha256 = "",
            formatVersion = 1,
            variantId = operation.variantId,
            layoutVersion = operation.layoutVersion,
            inUse = true,
        )
        val repository = InMemoryUpdateRepository(operation, recoveryPoint)
        return Fixture(repository, FakeUpdateCommands())
    }

    private data class Fixture(
        val repository: InMemoryUpdateRepository,
        val helper: FakeUpdateCommands,
    ) {
        suspend fun execute(): UpdateExecutionResult = UpdateService(helper, repository, InstanceOperationGate())
            .execute(repository.operation.operationId, ProgressSink { })
    }

    private class FakeUpdateCommands : UpdateCommands {
        val operationSteps = ArrayDeque<suspend () -> HelperResponse>()
        var dispatchStep: suspend () -> HelperResponse = { error("No dispatch response configured") }

        override suspend fun ensureInstalled() = HelperResponse(ok = true, helperVersion = 17)

        override suspend fun operation(operationId: String): HelperResponse =
            operationSteps.removeFirst().invoke()

        override suspend fun recoveryEstimate(instanceId: String) = error("Not used")

        override suspend fun dispatchUpdate(
            operationId: String,
            instanceId: String,
            variantId: String,
            targetRevision: String,
            recoveryPointId: String,
            retentionSeconds: Long,
        ): HelperResponse = dispatchStep()

        override suspend fun cleanupRecovery(recoveryPointId: String, now: Long) = error("Not used")
    }

    private class InMemoryUpdateRepository(
        initialOperation: UpdateOperation,
        initialRecoveryPoint: RecoveryPoint,
    ) : InstanceRepository {
        var operation = initialOperation
        var recoveryPoint = initialRecoveryPoint

        override fun observeInstances(): Flow<List<RisuInstance>> = flowOf(emptyList())
        override suspend fun get(instanceId: String): RisuInstance? = null
        override suspend fun upsert(instance: RisuInstance) = Unit
        override suspend fun updateStatus(instanceId: String, state: RuntimeState) = Unit
        override suspend fun upsertOperation(operation: OperationRecord) = Unit
        override suspend fun operations(instanceId: String): List<OperationRecord> = emptyList()
        override suspend fun upsertBackup(backup: BackupMetadata) = Unit
        override suspend fun backups(instanceId: String): List<BackupMetadata> = emptyList()
        override suspend fun getServerConfig(instanceId: String): InstanceServerConfig? = null
        override suspend fun getServerConfigs(): List<InstanceServerConfig> = emptyList()
        override suspend fun upsertServerConfig(config: InstanceServerConfig) = Unit
        override suspend fun deleteServerConfig(instanceId: String) = Unit
        override suspend fun prepareClone(
            operationId: String,
            sourceInstanceId: String,
            targetInstanceId: String,
            targetDisplayName: String,
        ): CloneOperation = error("Not used")
        override suspend fun cloneOperation(operationId: String): CloneOperation? = null
        override suspend fun activeCloneOperations(): List<CloneOperation> = emptyList()
        override suspend fun upsertCloneOperation(operation: CloneOperation) = Unit
        override suspend fun reassignClonePort(operationId: String): CloneOperation = error("Not used")
        override suspend fun publishClone(
            operation: CloneOperation,
            target: RisuInstance,
            config: InstanceServerConfig,
        ) = Unit

        override suspend fun updateOperation(operationId: String): UpdateOperation? =
            operation.takeIf { it.operationId == operationId }

        override suspend fun recoveryPoint(id: RecoveryPointId): RecoveryPoint? =
            recoveryPoint.takeIf { it.id == id }

        override suspend fun upsertUpdateProgress(operation: UpdateOperation, recoveryPoint: RecoveryPoint?) {
            this.operation = operation
            recoveryPoint?.let { this.recoveryPoint = it }
        }
    }
}
