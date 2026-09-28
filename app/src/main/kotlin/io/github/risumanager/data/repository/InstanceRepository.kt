package io.github.risumanager.data.repository

import io.github.risumanager.data.local.RisuDatabase
import io.github.risumanager.domain.model.BackupMetadata
import io.github.risumanager.domain.model.CloneOperation
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.OperationRecord
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RollbackOperation
import io.github.risumanager.domain.model.RollbackEvent
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.UpdateOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

interface InstanceRepository {
    fun observeInstances(): Flow<List<RisuInstance>>
    suspend fun get(instanceId: String): RisuInstance?
    suspend fun upsert(instance: RisuInstance)
    suspend fun updateStatus(instanceId: String, state: RuntimeState)
    suspend fun upsertOperation(operation: OperationRecord)
    suspend fun operations(instanceId: String): List<OperationRecord>
    suspend fun upsertBackup(backup: BackupMetadata)
    suspend fun backups(instanceId: String): List<BackupMetadata>
    suspend fun getServerConfig(instanceId: String): InstanceServerConfig?
    suspend fun getServerConfigs(): List<InstanceServerConfig>
    suspend fun upsertServerConfig(config: InstanceServerConfig)
    suspend fun deleteServerConfig(instanceId: String)
    suspend fun prepareClone(operationId: String, sourceInstanceId: String, targetInstanceId: String, targetDisplayName: String): CloneOperation
    suspend fun prepareRecoveryClone(
        operationId: String,
        sourceInstanceId: String,
        recoveryPointId: RecoveryPointId,
        targetInstanceId: String,
        targetDisplayName: String,
    ): CloneOperation = updateApiUnavailable()
    suspend fun cloneOperation(operationId: String): CloneOperation?
    suspend fun activeCloneOperations(): List<CloneOperation>
    suspend fun upsertCloneOperation(operation: CloneOperation)
    suspend fun reassignClonePort(operationId: String): CloneOperation
    suspend fun publishClone(operation: CloneOperation, target: RisuInstance, config: InstanceServerConfig)
    suspend fun prepareUpdate(operation: UpdateOperation, recoveryPoint: RecoveryPoint): Unit = updateApiUnavailable()
    suspend fun updateOperation(operationId: String): UpdateOperation? = updateApiUnavailable()
    suspend fun activeUpdateOperations(): List<UpdateOperation> = updateApiUnavailable()
    suspend fun recoveryPoint(id: RecoveryPointId): RecoveryPoint? = updateApiUnavailable()
    suspend fun recoveryPoints(instanceId: String): List<RecoveryPoint> = updateApiUnavailable()
    suspend fun upsertUpdateProgress(operation: UpdateOperation, recoveryPoint: RecoveryPoint? = null): Unit = updateApiUnavailable()
    suspend fun publishUpdateSuccess(operation: UpdateOperation, recoveryPoint: RecoveryPoint): Unit = updateApiUnavailable()
    suspend fun setRecoveryPointPinned(id: RecoveryPointId, pinned: Boolean): Unit = updateApiUnavailable()
    suspend fun expiredRecoveryPoints(now: Long): List<RecoveryPoint> = updateApiUnavailable()
    suspend fun deleteConfirmedRecoveryPoint(id: RecoveryPointId): Boolean = updateApiUnavailable()
    suspend fun prepareRollback(operation: RollbackOperation, preRollbackPoint: RecoveryPoint): Unit = updateApiUnavailable()
    suspend fun rollbackOperation(operationId: String): RollbackOperation? = updateApiUnavailable()
    suspend fun activeRollbackOperations(): List<RollbackOperation> = updateApiUnavailable()
    suspend fun rollbackEvents(instanceId: String): List<RollbackEvent> = updateApiUnavailable()
    suspend fun upsertRollbackProgress(
        operation: RollbackOperation,
        selectedPoint: RecoveryPoint? = null,
        preRollbackPoint: RecoveryPoint? = null,
    ): Unit = updateApiUnavailable()
    suspend fun publishRollbackSuccess(
        operation: RollbackOperation,
        selectedPoint: RecoveryPoint,
        preRollbackPoint: RecoveryPoint,
        restoredConfig: InstanceServerConfig,
    ): Unit = updateApiUnavailable()

    suspend fun serverConfig(instanceId: String): InstanceServerConfig? = getServerConfig(instanceId)
    suspend fun serverConfigs(): List<InstanceServerConfig> = getServerConfigs()
    suspend fun saveServerConfig(config: InstanceServerConfig) = upsertServerConfig(config)
}

private fun <T> updateApiUnavailable(): T = throw UnsupportedOperationException("Update persistence is not implemented")

class SqliteInstanceRepository(private val database: RisuDatabase) : InstanceRepository {
    private val generation = MutableStateFlow(0L)

    override fun observeInstances(): Flow<List<RisuInstance>> = generation
        .map { database.instances() }
        .flowOn(Dispatchers.IO)

    override suspend fun get(instanceId: String): RisuInstance? = withContext(Dispatchers.IO) { database.instance(instanceId) }

    override suspend fun upsert(instance: RisuInstance) = mutate { database.upsertInstance(instance) }

    override suspend fun updateStatus(instanceId: String, state: RuntimeState) = mutate {
        database.updateRuntimeStatus(instanceId, state, System.currentTimeMillis())
    }

    override suspend fun upsertOperation(operation: OperationRecord) = mutate { database.upsertOperation(operation) }

    override suspend fun operations(instanceId: String): List<OperationRecord> = withContext(Dispatchers.IO) {
        database.operations(instanceId)
    }

    override suspend fun upsertBackup(backup: BackupMetadata) = mutate { database.upsertBackup(backup) }

    override suspend fun backups(instanceId: String): List<BackupMetadata> = withContext(Dispatchers.IO) {
        database.backups(instanceId)
    }

    override suspend fun getServerConfig(instanceId: String): InstanceServerConfig? = withContext(Dispatchers.IO) {
        database.serverConfig(instanceId)
    }

    override suspend fun getServerConfigs(): List<InstanceServerConfig> = withContext(Dispatchers.IO) {
        database.serverConfigs()
    }

    override suspend fun upsertServerConfig(config: InstanceServerConfig) = mutate {
        database.upsertServerConfig(config)
    }

    override suspend fun deleteServerConfig(instanceId: String) = mutate {
        database.deleteServerConfig(instanceId)
    }

    override suspend fun prepareClone(
        operationId: String,
        sourceInstanceId: String,
        targetInstanceId: String,
        targetDisplayName: String,
    ): CloneOperation = mutateWithResult {
        database.prepareClone(operationId, sourceInstanceId, targetInstanceId, targetDisplayName, System.currentTimeMillis())
    }

    override suspend fun prepareRecoveryClone(
        operationId: String,
        sourceInstanceId: String,
        recoveryPointId: RecoveryPointId,
        targetInstanceId: String,
        targetDisplayName: String,
    ): CloneOperation = mutateWithResult {
        database.prepareRecoveryClone(
            operationId,
            sourceInstanceId,
            recoveryPointId,
            targetInstanceId,
            targetDisplayName,
            System.currentTimeMillis(),
        )
    }

    override suspend fun cloneOperation(operationId: String): CloneOperation? = withContext(Dispatchers.IO) {
        database.cloneOperation(operationId)
    }

    override suspend fun activeCloneOperations(): List<CloneOperation> = withContext(Dispatchers.IO) {
        database.activeCloneOperations()
    }

    override suspend fun upsertCloneOperation(operation: CloneOperation) = mutate {
        database.upsertCloneOperation(operation)
    }

    override suspend fun reassignClonePort(operationId: String): CloneOperation = mutateWithResult {
        database.reassignClonePort(operationId, System.currentTimeMillis())
    }

    override suspend fun publishClone(operation: CloneOperation, target: RisuInstance, config: InstanceServerConfig) = mutate {
        database.publishClone(operation, target, config, System.currentTimeMillis())
    }

    override suspend fun prepareUpdate(operation: UpdateOperation, recoveryPoint: RecoveryPoint) = mutate {
        database.prepareUpdate(operation, recoveryPoint)
    }

    override suspend fun updateOperation(operationId: String): UpdateOperation? = withContext(Dispatchers.IO) {
        database.updateOperation(operationId)
    }

    override suspend fun activeUpdateOperations(): List<UpdateOperation> = withContext(Dispatchers.IO) {
        database.activeUpdateOperations()
    }

    override suspend fun recoveryPoint(id: RecoveryPointId): RecoveryPoint? = withContext(Dispatchers.IO) {
        database.recoveryPoint(id)
    }

    override suspend fun recoveryPoints(instanceId: String): List<RecoveryPoint> = withContext(Dispatchers.IO) {
        database.recoveryPoints(instanceId)
    }

    override suspend fun upsertUpdateProgress(operation: UpdateOperation, recoveryPoint: RecoveryPoint?) = mutate {
        database.upsertUpdateProgress(operation, recoveryPoint)
    }

    override suspend fun publishUpdateSuccess(operation: UpdateOperation, recoveryPoint: RecoveryPoint) = mutate {
        database.publishUpdateSuccess(operation, recoveryPoint, System.currentTimeMillis())
    }

    override suspend fun setRecoveryPointPinned(id: RecoveryPointId, pinned: Boolean) = mutate {
        database.setRecoveryPointPinned(id, pinned)
    }

    override suspend fun expiredRecoveryPoints(now: Long): List<RecoveryPoint> = withContext(Dispatchers.IO) {
        database.expiredRecoveryPoints(now)
    }

    override suspend fun deleteConfirmedRecoveryPoint(id: RecoveryPointId): Boolean = mutateWithResult {
        database.deleteConfirmedRecoveryPoint(id)
    }

    override suspend fun prepareRollback(operation: RollbackOperation, preRollbackPoint: RecoveryPoint) = mutate {
        database.prepareRollback(operation, preRollbackPoint)
    }

    override suspend fun rollbackOperation(operationId: String): RollbackOperation? = withContext(Dispatchers.IO) {
        database.rollbackOperation(operationId)
    }

    override suspend fun activeRollbackOperations(): List<RollbackOperation> = withContext(Dispatchers.IO) {
        database.activeRollbackOperations()
    }

    override suspend fun rollbackEvents(instanceId: String): List<RollbackEvent> = withContext(Dispatchers.IO) {
        database.rollbackEvents(instanceId)
    }

    override suspend fun upsertRollbackProgress(
        operation: RollbackOperation,
        selectedPoint: RecoveryPoint?,
        preRollbackPoint: RecoveryPoint?,
    ) = mutate {
        database.upsertRollbackProgress(operation, selectedPoint, preRollbackPoint)
    }

    override suspend fun publishRollbackSuccess(
        operation: RollbackOperation,
        selectedPoint: RecoveryPoint,
        preRollbackPoint: RecoveryPoint,
        restoredConfig: InstanceServerConfig,
    ) = mutate {
        database.publishRollbackSuccess(operation, selectedPoint, preRollbackPoint, restoredConfig, System.currentTimeMillis())
    }

    private suspend fun mutate(block: () -> Unit) = withContext(Dispatchers.IO) {
        block()
        generation.value += 1
    }

    private suspend fun <T> mutateWithResult(block: () -> T): T = withContext(Dispatchers.IO) {
        block().also { generation.value += 1 }
    }
}
