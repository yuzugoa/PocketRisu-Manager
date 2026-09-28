package io.github.risumanager.operation

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.data.repository.InstanceRepository
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.RollbackOperation
import io.github.risumanager.domain.model.RollbackResult
import io.github.risumanager.domain.model.RollbackStage
import io.github.risumanager.runtime.termux.HelperProtocolException
import io.github.risumanager.runtime.termux.HelperResponse
import io.github.risumanager.runtime.termux.RollbackCommands
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RollbackServiceTest {
    @Test fun `known dispatch rejection closes rollback before mutation`() = runTest {
        val fixture = Fixture()
        fixture.helper.dispatch = { HelperResponse(ok = false, code = "RECOVERY_CORRUPTED", message = "Recovery point failed verification") }

        val result = fixture.execute()

        assertThat(result).isEqualTo(RollbackExecutionResult.Failure("RECOVERY_CORRUPTED", "Recovery point failed verification", true, false))
        assertThat(fixture.operation.stage).isEqualTo(RollbackStage.FAILED)
        assertThat(fixture.operation.result).isEqualTo(RollbackResult.FAILURE)
        assertThat(fixture.selected.inUse).isFalse()
        assertThat(fixture.preRollback.inUse).isFalse()
    }

    @Test fun `uncertain dispatch keeps rollback reserved for reconciliation`() = runTest {
        val fixture = Fixture()
        fixture.helper.dispatch = { throw IllegalStateException("Transport interrupted") }

        val result = fixture.execute()

        assertThat(result).isEqualTo(RollbackExecutionResult.Retry("Transport interrupted"))
        assertThat(fixture.operation.result).isEqualTo(RollbackResult.RUNNING)
        assertThat(fixture.operation.completedAt).isNull()
        assertThat(fixture.selected.inUse).isTrue()
    }

    @Test fun `terminal failure before backup does not require recovery after replay`() = runTest {
        val fixture = Fixture()
        fixture.operation = fixture.operation.copy(
            stage = RollbackStage.FAILED, result = RollbackResult.FAILURE,
            currentStateRestored = false, errorCode = "ROLLBACK_PROCESS_LOST", detail = "Stopped before mutation",
        )
        fixture.preRollback = fixture.preRollback.copy(inUse = false, state = RecoveryPointState.FAILED)

        val result = fixture.execute()

        assertThat(result).isEqualTo(RollbackExecutionResult.Failure(
            "ROLLBACK_PROCESS_LOST", "Stopped before mutation", false, false,
        ))
    }

    @Test fun `terminal failure with verified reserved backup requires recovery after replay`() = runTest {
        val fixture = Fixture()
        fixture.operation = fixture.operation.copy(
            stage = RollbackStage.FAILED, result = RollbackResult.FAILURE,
            currentStateRestored = false, errorCode = "ROLLBACK_RECOVERY_FAILED", detail = "Recovery failed",
        )
        fixture.preRollback = fixture.preRollback.copy(inUse = true, state = RecoveryPointState.VERIFIED)

        val result = fixture.execute()

        assertThat(result).isEqualTo(RollbackExecutionResult.Failure(
            "ROLLBACK_RECOVERY_FAILED", "Recovery failed", false, true,
        ))
    }

    private class Fixture {
        var operation = RollbackOperation(
            operationId = "rollback_12345678", instanceId = "pocketrisu-test", variantId = "pocketrisu", layoutVersion = 1,
            selectedRecoveryPointId = RecoveryPointId("selected_12345678"),
            preRollbackRecoveryPointId = RecoveryPointId("before_12345678"),
            currentRevision = "b".repeat(40), targetRevision = "a".repeat(40), createdAt = 1, updatedAt = 1,
        )
        var selected = RecoveryPoint(
            id = operation.selectedRecoveryPointId, instanceId = operation.instanceId, createdAt = 1,
            serverRevision = operation.targetRevision, dataDigestSha256 = "", configurationDigestSha256 = "",
            formatVersion = 1, variantId = "pocketrisu", layoutVersion = 1, inUse = true,
        )
        var preRollback = selected.copy(id = operation.preRollbackRecoveryPointId, serverRevision = operation.currentRevision)
        val helper = FakeRollbackCommands()
        private val repository = Proxy.newProxyInstance(
            InstanceRepository::class.java.classLoader, arrayOf(InstanceRepository::class.java),
        ) { _, method, args ->
            when {
                method.name == "rollbackOperation" -> operation
                method.name.startsWith("recoveryPoint-") -> when (args?.get(0)) {
                    operation.selectedRecoveryPointId.value -> selected
                    operation.preRollbackRecoveryPointId.value -> preRollback
                    else -> null
                }
                method.name == "upsertRollbackProgress" -> {
                    operation = args!![0] as RollbackOperation
                    (args[1] as? RecoveryPoint)?.let { selected = it }
                    (args[2] as? RecoveryPoint)?.let { preRollback = it }
                    Unit
                }
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as InstanceRepository

        suspend fun execute(): RollbackExecutionResult = RollbackService(helper, repository, InstanceOperationGate())
            .execute(operation.operationId, ProgressSink { })
    }

    private class FakeRollbackCommands : RollbackCommands {
        var dispatch: suspend () -> HelperResponse = { error("Dispatch response not configured") }
        override suspend fun ensureInstalled() = HelperResponse(ok = true, helperVersion = 19)
        override suspend fun operation(operationId: String): HelperResponse =
            throw HelperProtocolException("OPERATION_NOT_FOUND", "Missing")
        override suspend fun dispatchRollback(
            operationId: String, instanceId: String, selectedRecoveryPointId: String,
            preRollbackRecoveryPointId: String, retentionSeconds: Long,
        ): HelperResponse = dispatch()
        override suspend fun recoveryInspect(recoveryPointId: String, instanceId: String): HelperResponse = error("Not used")
        override suspend fun recoveryPin(recoveryPointId: String, pinned: Boolean): HelperResponse = error("Not used")
        override suspend fun recoveryDelete(recoveryPointId: String): HelperResponse = error("Not used")
        override suspend fun dispatchRecoveryClone(
            operationId: String, sourceInstanceId: String, selectedRecoveryPointId: String,
            targetInstanceId: String, targetPort: Int,
        ): HelperResponse = error("Not used")
        override suspend fun configGet(instanceId: String): HelperResponse = error("Not used")
    }
}
