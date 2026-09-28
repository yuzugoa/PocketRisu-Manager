package io.github.risumanager.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.risumanager.app.AppContainer
import io.github.risumanager.data.settings.ThemeMode
import io.github.risumanager.data.settings.MAX_UPDATE_RECOVERY_RETENTION_DAYS
import io.github.risumanager.data.settings.MIN_UPDATE_RECOVERY_RETENTION_DAYS
import io.github.risumanager.domain.model.BuiltInVariants
import io.github.risumanager.domain.model.BindScope
import io.github.risumanager.domain.model.ConfigApplyState
import io.github.risumanager.domain.model.Capability
import io.github.risumanager.domain.model.CertificateMode
import io.github.risumanager.domain.model.CloneResult
import io.github.risumanager.domain.model.CloneStage
import io.github.risumanager.domain.model.DEFAULT_RECOVERY_RETENTION_SECONDS
import io.github.risumanager.domain.model.EnvironmentStatus
import io.github.risumanager.domain.model.LogLine
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.OperationResult
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.OperationLiveness
import io.github.risumanager.domain.model.RecoveryEstimate
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.RollbackPreview
import io.github.risumanager.domain.model.RestorationState
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeStatus
import io.github.risumanager.domain.model.ServerProtocol
import io.github.risumanager.domain.model.SourceRevision
import io.github.risumanager.domain.model.UpdateOperation
import io.github.risumanager.domain.model.UpdateResult
import io.github.risumanager.domain.model.VariantDefinition
import io.github.risumanager.domain.validation.Validators
import io.github.risumanager.operation.RisuOperationWorker
import io.github.risumanager.operation.BackupWorker
import io.github.risumanager.operation.OperationCoordinator
import io.github.risumanager.operation.CloneCoordinator
import io.github.risumanager.operation.CloneWorker
import io.github.risumanager.operation.RollbackCoordinator
import io.github.risumanager.operation.RollbackWorker
import io.github.risumanager.operation.UpdateCoordinator
import io.github.risumanager.operation.newestActiveWork
import io.github.risumanager.operation.tagValue
import io.github.risumanager.runtime.termux.HelperProtocolException
import io.github.risumanager.runtime.termux.HelperResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class HomeUiState(
    val instances: List<RisuInstance> = emptyList(),
    val statuses: Map<String, RuntimeStatus> = emptyMap(),
    val configs: Map<String, InstanceServerConfig> = emptyMap(),
    val busyInstanceId: String? = null,
    val backupOperation: OperationState = OperationState.Idle,
    val cloneOperation: OperationState = OperationState.Idle,
    val cloneTargetPort: Int? = null,
    val updateCheckInstanceId: String? = null,
    val updateConfirmation: UpdateConfirmation? = null,
    val updateOperation: OperationState = OperationState.Idle,
    val rollbackOperation: OperationState = OperationState.Idle,
    val recoverySummaries: Map<String, RecoverySummary> = emptyMap(),
    val message: String? = null,
    val refreshing: Boolean = false,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val workManager = WorkManager.getInstance(container.applicationContext)
    private val mutableState = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()
    private var trackedBackupId: UUID? = null
    private var enqueueingBackup = false
    private var lastBackupWork: WorkInfo? = null
    private var recoveringBackupWork = true
    private var trackedCloneId: UUID? = null
    private var enqueueingClone = false
    private var lastCloneWork: WorkInfo? = null
    private var trackedUpdateId: UUID? = null
    private var enqueueingUpdate = false
    private var recoveringUpdates = true
    private var lastUpdateWork: WorkInfo? = null
    private var trackedHomeRollbackId: UUID? = null
    private var recoveringRollbackWork = true
    private var lastHomeRollbackWork: WorkInfo? = null

    init {
        container.instances.observeInstances().onEach { instances ->
            mutableState.value = mutableState.value.copy(instances = instances)
            refresh()
            refreshRecoverySummaries()
        }.launchIn(viewModelScope)
        workManager.getWorkInfosByTagFlow(OperationCoordinator.BACKUP_TAG).onEach(::onBackupWorkChanged)
            .launchIn(viewModelScope)
        workManager.getWorkInfosByTagFlow(CloneCoordinator.CLONE_TAG).onEach(::onCloneWorkChanged)
            .launchIn(viewModelScope)
        workManager.getWorkInfosByTagFlow(UpdateCoordinator.UPDATE_TAG).onEach(::onUpdateWorkChanged)
            .launchIn(viewModelScope)
        workManager.getWorkInfosByTagFlow(RollbackCoordinator.ROLLBACK_TAG).onEach(::onHomeRollbackWorkChanged)
            .launchIn(viewModelScope)
        viewModelScope.launch { recoverPersistedUpdates() }
    }

    fun prepareUpdate(instance: RisuInstance) {
        if (recoveringUpdates || recoveringRollbackWork || mutableState.value.busyInstanceId != null || mutableState.value.updateCheckInstanceId != null || enqueueingUpdate) return
        if (instance.variantId != BuiltInVariants.pocketRisu.id ||
            instance.runtimeType != io.github.risumanager.domain.model.RuntimeType.TERMUX_NODE
        ) return
        mutableState.value = mutableState.value.copy(updateCheckInstanceId = instance.instanceId, message = null)
        viewModelScope.launch {
            try {
                val variant = requireNotNull(BuiltInVariants.find(instance.variantId))
                val channel = requireNotNull(variant.channels.firstOrNull { it.id == instance.channelId }) {
                    "The installed update channel is no longer supported."
                }
                require(channel.type != io.github.risumanager.domain.model.ChannelType.SPECIFIC_COMMIT) {
                    "Pinned installs require an explicit immutable target and cannot be updated from the Home screen."
                }
                val target = container.sources.resolve(variant, channel, null)
                require(Validators.isFullCommitSha(target.sha)) { "The update source did not resolve to a full immutable commit SHA." }
                require(!instance.installedRevision.equals(target.sha, ignoreCase = true)) { "PocketRisu is already at the selected target." }
                val estimate = container.updates.estimate(instance.instanceId)
                val retentionDays = container.preferences.updateRecoveryRetentionDays.first()
                mutableState.value = mutableState.value.copy(
                    updateCheckInstanceId = null,
                    updateConfirmation = UpdateConfirmation(instance, target, estimate, retentionDays),
                )
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    updateCheckInstanceId = null,
                    message = error.message ?: "The update target could not be checked.",
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    fun dismissUpdate() {
        if (!enqueueingUpdate) mutableState.value = mutableState.value.copy(updateConfirmation = null)
    }

    fun confirmUpdate() {
        val confirmation = mutableState.value.updateConfirmation ?: return
        if (!confirmation.estimate.hasEnoughSpace || mutableState.value.busyInstanceId != null || enqueueingUpdate) return
        enqueueingUpdate = true
        trackedUpdateId = null
        lastUpdateWork = null
        mutableState.value = mutableState.value.copy(
            updateConfirmation = null,
            busyInstanceId = confirmation.instance.instanceId,
            updateOperation = OperationState.Running("Queued", "Waiting for durable update reconciliation", null),
            message = null,
        )
        container.applicationScope.launch {
            try {
                val request = container.updates.update(
                    instanceId = confirmation.instance.instanceId,
                    targetRevision = confirmation.target.sha,
                    targetVersion = confirmation.target.displayVersion ?: confirmation.target.releaseName,
                    retentionSeconds = confirmation.retentionDays * 24L * 60L * 60L,
                )
                trackedUpdateId = request.workId
                enqueueingUpdate = false
                reconcileUpdateRecord(request.operation.operationId, confirmation.instance.instanceId)
            } catch (error: Exception) {
                enqueueingUpdate = false
                val message = error.message ?: "The update could not be queued."
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = null,
                    updateOperation = OperationState.Failure("UPDATE_QUEUE_FAILED", message, true),
                    message = message,
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    private suspend fun recoverPersistedUpdates() {
        runCatching { container.updates.reconcilePending() }
        runCatching { container.updates.cleanupExpired() }
        val active = container.instances.activeUpdateOperations()
        active.lastOrNull()?.let { operation ->
            mutableState.value = mutableState.value.copy(busyInstanceId = operation.instanceId)
            renderUpdateRecord(operation)
        }
        refreshRecoverySummaries()
        recoveringUpdates = false
    }

    private fun onUpdateWorkChanged(workInfos: List<WorkInfo>) {
        if (enqueueingUpdate) return
        val tracked = trackedUpdateId?.let { id -> workInfos.firstOrNull { it.id == id } }
        val active = newestActiveWork(workInfos)
        val latest = workInfos.maxByOrNull { info ->
            info.tagValue(OperationCoordinator.CREATED_TAG_PREFIX)?.toLongOrNull() ?: 0L
        }
        val info = when {
            tracked != null && !tracked.state.isFinished -> tracked
            active != null -> active.also { trackedUpdateId = it.id }
            tracked != null -> tracked
            recoveringUpdates -> latest
            else -> null
        } ?: return
        if (info == lastUpdateWork && !info.state.isFinished) return
        lastUpdateWork = info
        val operationId = info.tagValue(OperationCoordinator.OPERATION_TAG_PREFIX)
            ?: info.outputData.getString("operation_id")
            ?: return
        val instanceId = info.tagValue(OperationCoordinator.INSTANCE_TAG_PREFIX)
            ?: mutableState.value.busyInstanceId
        reconcileUpdateRecord(operationId, instanceId)
    }

    private fun reconcileUpdateRecord(operationId: String, fallbackInstanceId: String?) {
        viewModelScope.launch {
            val record = container.instances.updateOperation(operationId)
            if (record == null || (lastUpdateWork?.state?.isFinished == true && record.result == UpdateResult.RUNNING)) {
                // WorkManager terminal state is never authoritative. The durable journals decide
                // whether the helper is still working, restored, or requires recovery.
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = record?.instanceId ?: fallbackInstanceId,
                    updateOperation = OperationState.Running("Reconciling", "Checking the persisted helper operation", null),
                )
                runCatching { container.updates.reconcilePending() }
                return@launch
            }
            renderUpdateRecord(record)
            refreshRecoverySummaries(record)
        }
    }

    private fun renderUpdateRecord(record: UpdateOperation) {
        val stage = record.stage.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        when (record.result) {
            UpdateResult.RUNNING -> mutableState.value = mutableState.value.copy(
                busyInstanceId = record.instanceId,
                updateOperation = OperationState.Running(stage, record.detail, null),
            )
            UpdateResult.SUCCESS -> {
                trackedUpdateId = null
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = null,
                    updateOperation = OperationState.Success("PocketRisu updated and launch verification passed."),
                    message = "PocketRisu updated successfully. The verified recovery point remains available for its retention period.",
                )
                refresh()
            }
            UpdateResult.FAILURE -> {
                trackedUpdateId = null
                val autoRecovered = record.restorationState == RestorationState.SUCCEEDED
                val recoveryRequired = record.restorationState in setOf(
                    RestorationState.FAILED,
                    RestorationState.PENDING,
                    RestorationState.UNKNOWN,
                )
                val code = if (recoveryRequired) "RECOVERY_REQUIRED" else record.errorCode ?: "UPDATE_FAILED"
                val suffix = when {
                    autoRecovered -> " Automatic recovery succeeded; the pre-update instance was restored."
                    recoveryRequired -> " Automatic recovery did not complete. Keep Termux data unchanged and open diagnostics."
                    else -> " The installed instance was not changed."
                }
                val message = (record.detail ?: "PocketRisu update failed.") + suffix
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = null,
                    updateOperation = OperationState.Failure(code, message, true),
                    message = message,
                )
                refresh()
            }
        }
    }

    private fun refreshRecoverySummaries(operation: UpdateOperation? = null) {
        viewModelScope.launch {
            val instances = mutableState.value.instances
            val now = System.currentTimeMillis()
            val summaries = instances.mapNotNull { instance ->
                val point = container.instances.recoveryPoints(instance.instanceId).firstOrNull() ?: return@mapNotNull null
                val relatedOperation = operation?.takeIf { it.recoveryPointId == point.id }
                instance.instanceId to recoverySummary(point, relatedOperation, now)
            }.toMap()
            mutableState.value = mutableState.value.copy(recoverySummaries = summaries)
        }
    }

    private fun onHomeRollbackWorkChanged(workInfos: List<WorkInfo>) {
        val tracked = trackedHomeRollbackId?.let { id -> workInfos.firstOrNull { it.id == id } }
        val active = newestActiveWork(workInfos)
        val latest = workInfos.maxByOrNull { info ->
            info.tagValue(OperationCoordinator.CREATED_TAG_PREFIX)?.toLongOrNull() ?: 0L
        }
        val info = when {
            tracked != null && !tracked.state.isFinished -> tracked
            active != null -> active.also { trackedHomeRollbackId = it.id }
            tracked != null -> tracked
            recoveringRollbackWork -> latest
            else -> null
        }
        recoveringRollbackWork = false
        info?.let(::renderHomeRollbackWork)
    }

    private fun renderHomeRollbackWork(info: WorkInfo) {
        if (info == lastHomeRollbackWork && !info.state.isFinished) return
        lastHomeRollbackWork = info
        val instanceId = info.tagValue(OperationCoordinator.INSTANCE_TAG_PREFIX)
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> mutableState.value = mutableState.value.copy(
                busyInstanceId = instanceId,
                rollbackOperation = OperationState.Running("Queued", "Waiting for durable rollback reconciliation", null),
            )
            WorkInfo.State.RUNNING -> mutableState.value = mutableState.value.copy(
                busyInstanceId = instanceId,
                rollbackOperation = OperationState.Running(
                    info.progress.getString(RollbackWorker.KEY_STAGE) ?: "Restoring",
                    info.progress.getString(RollbackWorker.KEY_DETAIL),
                    null,
                ),
            )
            WorkInfo.State.SUCCEEDED -> {
                trackedHomeRollbackId = null
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = null,
                    rollbackOperation = OperationState.Success("Rollback completed and normal operation was verified."),
                    message = "Rollback completed. The pre-rollback state is retained for 7 days.",
                )
                refresh()
                refreshRecoverySummaries()
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                trackedHomeRollbackId = null
                val code = info.outputData.getString(RollbackWorker.KEY_ERROR_CODE)
                    ?: if (info.state == WorkInfo.State.CANCELLED) "ROLLBACK_CANCELLED" else "ROLLBACK_FAILED"
                val base = info.outputData.getString(RollbackWorker.KEY_ERROR_MESSAGE) ?: "Rollback did not complete."
                val message = when {
                    info.outputData.getBoolean(RollbackWorker.KEY_RECOVERY_REQUIRED, false) ->
                        "$base Automatic recovery did not complete; open diagnostics before changing Termux data."
                    info.outputData.getBoolean(RollbackWorker.KEY_CURRENT_STATE_RESTORED, false) ->
                        "$base The verified pre-rollback state was restored."
                    else -> base
                }
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = null,
                    rollbackOperation = OperationState.Failure(code, message, true),
                    message = message,
                )
                refresh()
                refreshRecoverySummaries()
            }
        }
    }

    fun refresh() {
        val instances = mutableState.value.instances
        if (mutableState.value.refreshing || instances.isEmpty()) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(refreshing = true)
            val statuses = buildMap {
                instances.forEach { instance ->
                    val status = container.runtimes.get(instance.runtimeType).status(instance)
                    put(instance.instanceId, status)
                    if (status.state != RuntimeState.UNKNOWN) container.instances.updateStatus(instance.instanceId, status.state)
                }
            }
            val configs = instances.associate { instance ->
                val stored = container.instances.getServerConfig(instance.instanceId)
                    ?: InstanceServerConfig(instance.instanceId, port = instance.port)
                val runtime = if (instance.runtimeType == io.github.risumanager.domain.model.RuntimeType.TERMUX_NODE) {
                    runCatching { container.termuxRuntime.readServerConfig(instance.instanceId).desired }.getOrNull()
                } else null
                instance.instanceId to (runtime?.withPersistentSettingsFrom(stored) ?: stored)
            }
            mutableState.value = mutableState.value.copy(statuses = statuses, configs = configs, refreshing = false)
        }
    }

    fun start(instance: RisuInstance) = action(instance) { container.runtimes.get(instance.runtimeType).start(instance) }
    fun stop(instance: RisuInstance) = action(instance) { container.runtimes.get(instance.runtimeType).stop(instance) }
    fun restart(instance: RisuInstance) = action(instance) { container.runtimes.get(instance.runtimeType).restart(instance) }

    fun clone(instance: RisuInstance, displayName: String) {
        if (recoveringUpdates || recoveringRollbackWork || mutableState.value.busyInstanceId != null || enqueueingClone) return
        enqueueingClone = true
        trackedCloneId = null
        lastCloneWork = null
        mutableState.value = mutableState.value.copy(
            busyInstanceId = instance.instanceId,
            cloneOperation = OperationState.Running("Preparing", "Reserving an independent identity and port", null),
            cloneTargetPort = null,
            message = null,
        )
        container.applicationScope.launch {
            try {
                val request = container.clones.clone(instance.instanceId, displayName)
                trackedCloneId = request.workId
                enqueueingClone = false
                mutableState.value = mutableState.value.copy(cloneTargetPort = request.operation.targetPort)
                workManager.getWorkInfoByIdFlow(request.workId).first()?.let(::renderCloneWork)
            } catch (error: Exception) {
                enqueueingClone = false
                val message = error.message ?: "Clone could not be queued."
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = null,
                    cloneOperation = OperationState.Failure("CLONE_QUEUE_FAILED", message, true),
                    message = message,
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    private fun onCloneWorkChanged(workInfos: List<WorkInfo>) {
        if (enqueueingClone) return
        val tracked = trackedCloneId?.let { id -> workInfos.firstOrNull { it.id == id } }
        val active = newestActiveWork(workInfos)
        val info = when {
            tracked != null && !tracked.state.isFinished -> tracked
            active != null -> active.also { trackedCloneId = it.id }
            tracked != null -> tracked
            else -> return
        }
        renderCloneWork(info)
    }

    private fun renderCloneWork(info: WorkInfo) {
        if (info == lastCloneWork) return
        lastCloneWork = info
        val sourceId = info.tagValue(OperationCoordinator.INSTANCE_TAG_PREFIX) ?: mutableState.value.busyInstanceId
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                val previous = mutableState.value.cloneOperation as? OperationState.Running
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = sourceId,
                    cloneOperation = OperationState.Running(
                        info.progress.getString(CloneWorker.KEY_STAGE) ?: previous?.stage ?: "Queued",
                        info.progress.getString(CloneWorker.KEY_DETAIL)
                            ?: previous?.detail
                            ?: "Waiting for durable clone reconciliation",
                        null,
                    ),
                )
                refreshCloneRecord(info, includeDetail = true)
            }
            WorkInfo.State.RUNNING -> {
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = sourceId,
                    cloneOperation = OperationState.Running(
                        info.progress.getString(CloneWorker.KEY_STAGE) ?: "Preparing",
                        info.progress.getString(CloneWorker.KEY_DETAIL),
                        null,
                    ),
                )
                refreshCloneRecord(info, includeDetail = false)
            }
            WorkInfo.State.SUCCEEDED -> {
                trackedCloneId = null
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = null,
                    cloneOperation = OperationState.Success("Clone registered in the stopped state."),
                    message = "Clone created. It is stopped, local-only, and independent from the source.",
                )
                refresh()
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> reconcileCloneTerminal(info, sourceId)
        }
    }

    private fun refreshCloneRecord(info: WorkInfo, includeDetail: Boolean) {
        val operationId = info.tagValue(OperationCoordinator.OPERATION_TAG_PREFIX) ?: return
        viewModelScope.launch {
            val record = container.instances.cloneOperation(operationId) ?: return@launch
            if (record.result != CloneResult.RUNNING || mutableState.value.busyInstanceId != record.sourceInstanceId) return@launch
            val current = mutableState.value.cloneOperation as? OperationState.Running
            val stage = when (record.stage) {
                CloneStage.RESTORING_SOURCE -> "Restoring source"
                CloneStage.REGISTERING -> "Registering"
                CloneStage.PUBLISHING -> "Publishing"
                else -> current?.stage ?: "Reconciling"
            }
            mutableState.value = mutableState.value.copy(
                cloneTargetPort = record.targetPort,
                cloneOperation = if (includeDetail && !record.detail.isNullOrBlank()) {
                    OperationState.Running(stage, record.detail, null)
                } else mutableState.value.cloneOperation,
            )
        }
    }

    private fun reconcileCloneTerminal(info: WorkInfo, sourceId: String?) {
        val operationId = info.tagValue(OperationCoordinator.OPERATION_TAG_PREFIX) ?: return
        viewModelScope.launch {
            val record = container.instances.cloneOperation(operationId)
            when (record?.result) {
                CloneResult.SUCCESS -> {
                    mutableState.value = mutableState.value.copy(
                        busyInstanceId = null,
                        cloneOperation = OperationState.Success("Clone registered in the stopped state."),
                        message = "Clone created. It is stopped and local-only.",
                    )
                    refresh()
                }
                CloneResult.FAILURE -> {
                    val restoration = if (record.sourceRestored == false) " The source could not be restored automatically." else ""
                    val message = (record.detail ?: "Cloning failed.") + restoration
                    mutableState.value = mutableState.value.copy(
                        busyInstanceId = null,
                        cloneOperation = OperationState.Failure(record.errorCode ?: "CLONE_FAILED", message, true),
                        message = message,
                    )
                }
                else -> {
                    // A terminal/cancelled WorkManager row is not authoritative. The helper may
                    // still be copying or already published, so keep the source busy and resume.
                    mutableState.value = mutableState.value.copy(
                        busyInstanceId = sourceId,
                        cloneOperation = OperationState.Running("Reconciling", "Checking the durable helper operation", null),
                    )
                    runCatching { container.clones.reconcilePending() }
                }
            }
        }
    }

    fun backup(instance: RisuInstance, destination: Uri) {
        if (recoveringBackupWork || recoveringUpdates || recoveringRollbackWork || mutableState.value.busyInstanceId != null) return
        trackedBackupId = null
        lastBackupWork = null
        enqueueingBackup = true
        mutableState.value = mutableState.value.copy(
            busyInstanceId = instance.instanceId,
            backupOperation = OperationState.Running("Queued", "Waiting for Android worker", null),
            message = null,
        )
        container.applicationScope.launch {
            try {
                val request = container.operations.backup(instance.instanceId, destination)
                val info = checkNotNull(workManager.getWorkInfoByIdFlow(request.workId).first()) {
                    "The queued backup is no longer available."
                }
                trackedBackupId = request.workId
                enqueueingBackup = false
                renderBackupWork(info)
            } catch (error: Exception) {
                enqueueingBackup = false
                val message = error.message ?: "Backup could not be queued."
                mutableState.value = mutableState.value.copy(
                    busyInstanceId = null,
                    backupOperation = OperationState.Failure("BACKUP_QUEUE_FAILED", message, true),
                    message = message,
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    private fun onBackupWorkChanged(workInfos: List<WorkInfo>) {
        recoveringBackupWork = false
        if (enqueueingBackup) return
        val tracked = trackedBackupId?.let { id -> workInfos.firstOrNull { it.id == id } }
        val active = newestActiveWork(workInfos)
        val info = when {
            tracked != null && !tracked.state.isFinished -> tracked
            active != null -> active.also { trackedBackupId = it.id }
            tracked != null -> tracked
            else -> return
        }
        renderBackupWork(info)
    }

    private fun renderBackupWork(info: WorkInfo) {
        if (info == lastBackupWork) return
        lastBackupWork = info
        if (info.state.isFinished) trackedBackupId = null
        val instanceId = info.tagValue(OperationCoordinator.INSTANCE_TAG_PREFIX)
            ?: mutableState.value.busyInstanceId
        when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> updateBackup(OperationState.Running("Queued", null, null))
                WorkInfo.State.RUNNING -> {
                    val stage = info.progress.getString(BackupWorker.KEY_STAGE) ?: "Preparing"
                    val detail = info.progress.getString(BackupWorker.KEY_DETAIL)
                    val progress = info.progress.takeIf { BackupWorker.KEY_PROGRESS in it.keyValueMap }
                        ?.getFloat(BackupWorker.KEY_PROGRESS, 0f)
                    updateBackup(OperationState.Running(stage, detail, progress))
                }
                WorkInfo.State.SUCCEEDED -> {
                    val size = info.outputData.getLong(BackupWorker.KEY_SIZE, 0L)
                    mutableState.value = mutableState.value.copy(
                        busyInstanceId = null,
                        backupOperation = OperationState.Success("Backup saved; transfer verified ($size bytes)."),
                        message = "Backup saved; transfer verified.",
                    )
                    refresh()
                    trackedBackupId = null
                }
                WorkInfo.State.FAILED -> {
                    val code = info.outputData.getString(BackupWorker.KEY_ERROR_CODE) ?: "BACKUP_FAILED"
                    val message = info.outputData.getString(BackupWorker.KEY_ERROR_MESSAGE) ?: "Backup failed. The selected document may be incomplete."
                    mutableState.value = mutableState.value.copy(
                        busyInstanceId = null,
                        backupOperation = OperationState.Failure(code, message, true),
                        message = "$code: $message",
                    )
                    refresh()
                }
                WorkInfo.State.CANCELLED -> {
                    mutableState.value = mutableState.value.copy(
                        busyInstanceId = null,
                        backupOperation = OperationState.Failure("BACKUP_CANCELLED", "Backup was cancelled. The selected document may be incomplete.", true),
                        message = "Backup was cancelled. The selected document may be incomplete.",
                    )
                    refresh()
                    trackedBackupId = null
                }
        }
        if (!info.state.isFinished) {
            mutableState.value = mutableState.value.copy(busyInstanceId = instanceId)
        }
    }

    fun destinationError(message: String) {
        mutableState.value = mutableState.value.copy(message = message)
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }

    private fun updateBackup(operation: OperationState) {
        mutableState.value = mutableState.value.copy(backupOperation = operation)
    }

    private fun action(instance: RisuInstance, block: suspend () -> OperationResult) {
        if (recoveringBackupWork || recoveringUpdates || recoveringRollbackWork || mutableState.value.busyInstanceId != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busyInstanceId = instance.instanceId, message = null)
            val result = block()
            val message = when (result) {
                is OperationResult.Success -> result.message
                is OperationResult.Failure -> "${result.code}: ${result.message}"
            }
            mutableState.value = mutableState.value.copy(busyInstanceId = null, message = message)
            refresh()
        }
    }
}

data class InstallUiState(
    val variants: List<VariantDefinition> = BuiltInVariants.all,
    val selectedVariantId: String = BuiltInVariants.pocketRisu.id,
    val selectedChannelId: String = "stable",
    val requestedCommit: String = "",
    val environment: EnvironmentStatus? = null,
    val operation: OperationState = OperationState.Idle,
    val installedInstanceId: String? = null,
    val operationId: String? = null,
    val installInstanceId: String? = null,
    val logLines: List<LogLine> = emptyList(),
    val logsLoading: Boolean = false,
    val logError: String? = null,
    val lastLogRefreshAt: Long? = null,
)

data class UpdateConfirmation(
    val instance: RisuInstance,
    val target: SourceRevision,
    val estimate: RecoveryEstimate,
    val retentionDays: Int,
)

enum class RecoveryRetentionStatus {
    VALID,
    EXPIRED_CLEANUP_PENDING,
    PINNED,
    IN_USE,
    RECOVERY_REQUIRED,
}

data class RecoverySummary(
    val status: RecoveryRetentionStatus,
    val expiresAt: Long?,
)

internal fun recoverySummary(
    point: RecoveryPoint,
    operation: UpdateOperation?,
    now: Long,
): RecoverySummary {
    val status = when {
        operation?.result == UpdateResult.FAILURE &&
            operation.restorationState == RestorationState.FAILED -> RecoveryRetentionStatus.RECOVERY_REQUIRED
        point.inUse -> RecoveryRetentionStatus.IN_USE
        point.pinned -> RecoveryRetentionStatus.PINNED
        point.state == RecoveryPointState.CLEANUP_PENDING ||
            (point.expiresAt != null && point.expiresAt <= now) -> RecoveryRetentionStatus.EXPIRED_CLEANUP_PENDING
        else -> RecoveryRetentionStatus.VALID
    }
    return RecoverySummary(status, point.expiresAt)
}

class InstallViewModel(
    context: Context,
    private val container: AppContainer,
) : ViewModel() {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val mutableState = MutableStateFlow(InstallUiState())
    val state: StateFlow<InstallUiState> = mutableState.asStateFlow()
    private var trackedInstallId: UUID? = null
    private var recoveringInstallWork = true
    private var enqueueingInstall = false
    private var lastInstallWork: WorkInfo? = null
    private var logRequestSerial = 0L
    private var environmentJob: Job? = null

    init {
        checkEnvironment()
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(OperationCoordinator.INSTALL_TAG)
                .catch { exception ->
                recoveringInstallWork = false
                mutableState.value = mutableState.value.copy(
                    operation = OperationState.Failure(
                        "INSTALL_RECOVERY_FAILED",
                        exception.message ?: "Previous installation state could not be checked.",
                        true,
                    ),
                )
                }
                .collect(::onInstallWorkChanged)
        }
    }

    fun selectVariant(id: String) {
        if (mutableState.value.operation is OperationState.Running) return
        val variant = BuiltInVariants.find(id) ?: return
        mutableState.value = mutableState.value.copy(
            selectedVariantId = id,
            selectedChannelId = variant.channels.first().id,
            requestedCommit = "",
            operation = OperationState.Idle,
            operationId = null,
            installInstanceId = null,
            logLines = emptyList(),
            logError = null,
            lastLogRefreshAt = null,
        )
        if (variant.runtimeType == io.github.risumanager.domain.model.RuntimeType.TERMUX_NODE) checkEnvironment()
    }

    fun selectChannel(id: String) {
        if (mutableState.value.operation !is OperationState.Running) {
            mutableState.value = mutableState.value.copy(selectedChannelId = id)
        }
    }
    fun setRequestedCommit(value: String) {
        if (mutableState.value.operation !is OperationState.Running) {
            mutableState.value = mutableState.value.copy(requestedCommit = value.trim())
        }
    }

    fun checkEnvironment() {
        environmentJob?.cancel()
        environmentJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(environment = container.termuxRuntime.checkEnvironment())
        }
    }

    fun install() {
        val current = mutableState.value
        if (current.operation is OperationState.Running) return
        val variant = BuiltInVariants.find(current.selectedVariantId) ?: return
        if (variant.runtimeType == io.github.risumanager.domain.model.RuntimeType.NATIVE_APK) {
            mutableState.value = current.copy(
                operation = OperationState.Failure(
                    "NATIVE_APK_NOT_AVAILABLE",
                    "The current Haejeok releases do not publish a compatible APK asset; native installation remains disabled.",
                    false,
                ),
            )
            return
        }
        if (current.environment?.state != io.github.risumanager.domain.model.EnvironmentState.TERMUX_READY) {
            mutableState.value = current.copy(
                operation = OperationState.Failure("TERMUX_NOT_READY", "Complete Termux integration before installation.", true),
            )
            return
        }
        val commit = current.requestedCommit.takeIf { current.selectedChannelId == "specific-commit" }
        val resumeInstanceId = current.installInstanceId.takeIf { current.operation is OperationState.Failure }
        trackedInstallId = null
        lastInstallWork = null
        enqueueingInstall = true
        mutableState.value = current.copy(
            operation = OperationState.Running("Queued", "Waiting for Android worker", null),
            operationId = null,
            logLines = emptyList(),
            logsLoading = false,
            logError = null,
        )
        container.applicationScope.launch {
            try {
                val request = container.operations.install(variant.id, current.selectedChannelId, commit, resumeInstanceId)
                mutableState.value = mutableState.value.copy(
                    operationId = request.operationId,
                    installInstanceId = request.instanceId,
                )
                val info = checkNotNull(workManager.getWorkInfoByIdFlow(request.workId).first()) {
                    "The queued installation is no longer available."
                }
                trackedInstallId = request.workId
                enqueueingInstall = false
                renderInstallWork(info)
            } catch (error: Exception) {
                enqueueingInstall = false
                val message = error.message ?: "Installation could not be queued."
                mutableState.value = mutableState.value.copy(
                    operation = OperationState.Failure("INSTALL_QUEUE_FAILED", message, true),
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    private fun onInstallWorkChanged(workInfos: List<WorkInfo>) {
        if (enqueueingInstall) return
        val tracked = trackedInstallId?.let { id -> workInfos.firstOrNull { it.id == id } }
        val active = newestActiveWork(workInfos)
        val info = when {
            tracked != null && !tracked.state.isFinished -> tracked
            active != null -> active.also { trackedInstallId = it.id }
            tracked != null -> tracked
            else -> {
                if (recoveringInstallWork && !enqueueingInstall) {
                    recoveringInstallWork = false
                    mutableState.value = mutableState.value.copy(operation = OperationState.Idle)
                }
                return
            }
        }
        recoveringInstallWork = false
        renderInstallWork(info)
    }

    private fun renderInstallWork(info: WorkInfo) {
        if (info == lastInstallWork) return
        lastInstallWork = info
        val variantId = info.tagValue(OperationCoordinator.VARIANT_TAG_PREFIX)
        val channelId = info.tagValue(OperationCoordinator.CHANNEL_TAG_PREFIX)
        val operationId = info.tagValue(OperationCoordinator.OPERATION_TAG_PREFIX)
        val instanceId = info.tagValue(OperationCoordinator.INSTANCE_TAG_PREFIX)
            ?: info.outputData.getString(RisuOperationWorker.KEY_INSTANCE_ID)
        val variant = variantId?.let(BuiltInVariants::find)
            ?: BuiltInVariants.find(mutableState.value.selectedVariantId)
            ?: return
        mutableState.value = mutableState.value.copy(
            operationId = operationId,
            installInstanceId = instanceId,
        )
        if (!info.state.isFinished) {
            mutableState.value = mutableState.value.copy(
                selectedVariantId = variant.id,
                selectedChannelId = channelId?.takeIf { id -> variant.channels.any { it.id == id } }
                    ?: mutableState.value.selectedChannelId,
            )
        }
        when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    mutableState.value = mutableState.value.copy(
                        operation = OperationState.Running(
                            "Queued",
                            if (info.state == WorkInfo.State.BLOCKED) "Android WorkManager has blocked this operation." else null,
                            null,
                            if (info.state == WorkInfo.State.BLOCKED) OperationLiveness.BLOCKED else OperationLiveness.LOADING,
                        ),
                    )
                }
                WorkInfo.State.RUNNING -> {
                    val stage = info.progress.getString(RisuOperationWorker.KEY_STAGE) ?: "Preparing"
                    val detail = info.progress.getString(RisuOperationWorker.KEY_DETAIL)
                    val progress = info.progress.takeIf { RisuOperationWorker.KEY_PROGRESS in it.keyValueMap }
                        ?.getFloat(RisuOperationWorker.KEY_PROGRESS, 0f)
                    mutableState.value = mutableState.value.copy(operation = OperationState.Running(stage, detail, progress))
                }
                WorkInfo.State.SUCCEEDED -> {
                    mutableState.value = mutableState.value.copy(
                        operation = OperationState.Success("${variant.displayName} installed successfully."),
                        installedInstanceId = instanceId,
                    )
                    trackedInstallId = null
                }
                WorkInfo.State.FAILED -> {
                    mutableState.value = mutableState.value.copy(
                        operation = OperationState.Failure(
                            info.outputData.getString(RisuOperationWorker.KEY_ERROR_CODE) ?: "INSTALL_FAILED",
                            info.outputData.getString(RisuOperationWorker.KEY_ERROR_MESSAGE) ?: "Installation failed. Check the operation details.",
                            true,
                        ),
                    )
                    trackedInstallId = null
                }
                WorkInfo.State.CANCELLED -> {
                    mutableState.value = mutableState.value.copy(operation = OperationState.Failure("CANCELLED", "Installation was cancelled.", true))
                    trackedInstallId = null
                }
        }
        operationId?.let(::refreshInstallLog)
    }

    fun refreshInstallLog() {
        mutableState.value.operationId?.let(::refreshInstallLog)
    }

    private fun refreshInstallLog(operationId: String) {
        if (mutableState.value.logsLoading) return
        val requestSerial = ++logRequestSerial
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(logsLoading = true)
            val result = withTimeoutOrNull(12_000L) {
                runCatching { container.termuxRuntime.installationLog(operationId) }
            }
            // A late response from an older poll must never regress the durable log view.
            if (requestSerial != logRequestSerial || mutableState.value.operationId != operationId) return@launch
            val now = System.currentTimeMillis()
            when {
                result == null -> mutableState.value = mutableState.value.copy(
                    logsLoading = false,
                    logError = "Installer log collection timed out; the operation may still be running.",
                    lastLogRefreshAt = now,
                    operation = (mutableState.value.operation as? OperationState.Running)?.copy(
                        detail = "Log collector timed out; checking the helper again while the operation remains active.",
                        liveness = OperationLiveness.RECONNECTING,
                    ) ?: mutableState.value.operation,
                )
                result.isSuccess -> mutableState.value = mutableState.value.copy(
                    logLines = result.getOrThrow(),
                    logsLoading = false,
                    logError = null,
                    lastLogRefreshAt = now,
                )
                else -> mutableState.value = mutableState.value.copy(
                    logsLoading = false,
                    logError = result.exceptionOrNull()?.message ?: "Installer log collection failed.",
                    lastLogRefreshAt = now,
                    operation = (mutableState.value.operation as? OperationState.Running)?.copy(
                        detail = "Log collector failed; reconnecting without changing the persisted operation.",
                        liveness = OperationLiveness.RECONNECTING,
                    ) ?: mutableState.value.operation,
                )
            }
        }
    }
}

class TermuxIntegrationViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow<EnvironmentStatus?>(null)
    val state: StateFlow<EnvironmentStatus?> = mutableState.asStateFlow()
    private var checkJob: Job? = null

    init { check() }
    fun check() {
        checkJob?.cancel()
        checkJob = viewModelScope.launch { mutableState.value = container.termuxRuntime.checkEnvironment() }
    }
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val themeMode = container.preferences.themeMode
    val updateRecoveryRetentionDays = container.preferences.updateRecoveryRetentionDays
    fun setThemeMode(mode: ThemeMode) { viewModelScope.launch { container.preferences.setThemeMode(mode) } }
    fun setUpdateRecoveryRetentionDays(days: Int) {
        if (days !in MIN_UPDATE_RECOVERY_RETENTION_DAYS..MAX_UPDATE_RECOVERY_RETENTION_DAYS) return
        viewModelScope.launch { container.preferences.setUpdateRecoveryRetentionDays(days) }
    }
}

data class ServerConfigUiState(
    val loading: Boolean = true,
    val instance: RisuInstance? = null,
    val capabilities: Set<Capability> = emptySet(),
    val port: String = "6001",
    val bindScope: BindScope = BindScope.LOOPBACK,
    val protocol: ServerProtocol = ServerProtocol.HTTP,
    val certificateMode: CertificateMode = CertificateMode.MANUAL,
    val tlsDomain: String = "",
    val acmeEmail: String = "",
    val acmeChallengePort: String = "8080",
    val certificateExpiresAt: Long? = null,
    val autoRenew: Boolean = false,
    val lastRenewCheckAt: Long? = null,
    val lastRenewSuccessAt: Long? = null,
    val lastRenewError: String? = null,
    val renewFailureCount: Int = 0,
    val dnsA: List<String> = emptyList(),
    val dnsAAAA: List<String> = emptyList(),
    val internalChallengePortAvailable: Boolean? = null,
    val externalPortCheck: String? = null,
    val preflightChecked: Boolean = false,
    val acmeBusy: Boolean = false,
    val bodyLimitMb: String = "100",
    val saveIntervalSeconds: String = "5",
    val snapshotIntervalMinutes: String = "5",
    val snapshotRetentionCount: String = "20",
    val snapshotCapacityMb: String = "500",
    val imageQuality: String = "85",
    val applied: InstanceServerConfig? = null,
    val lastApplyError: String? = null,
    val validationErrors: List<String> = emptyList(),
    val saving: Boolean = false,
    val message: String? = null,
)

class ServerConfigViewModel(
    private val container: AppContainer,
    private val instanceId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ServerConfigUiState())
    val state: StateFlow<ServerConfigUiState> = mutableState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val instance = container.instances.get(instanceId)
            if (instance == null) {
                mutableState.value = ServerConfigUiState(loading = false, message = "Instance not found")
                return@launch
            }
            val stored = container.instances.getServerConfig(instanceId) ?: InstanceServerConfig(instanceId)
            val runtime = runCatching { container.termuxRuntime.readServerConfig(instanceId) }.getOrNull()
            var effective = runtime?.desired?.withPersistentSettingsFrom(stored) ?: stored
            if (instance.variantId == BuiltInVariants.pocketRisu.id) {
                runCatching { container.termuxRuntime.acmeCertificateStatus(instanceId) }
                    .onSuccess { status -> effective = effective.withAcmeStatus(status) }
            }
            mutableState.value = effective.toUiState(
                instance = instance,
                applied = runtime?.applied ?: stored.appliedCopy(),
                lastError = runtime?.lastApplyError ?: stored.lastError,
            )
        }
    }

    fun setPort(value: String) = edit { copy(port = digits(value)) }
    fun setBindScope(value: BindScope) = edit { copy(bindScope = value) }
    fun setCertificateMode(value: CertificateMode) = edit {
        copy(
            certificateMode = value,
            preflightChecked = false,
            dnsA = emptyList(),
            dnsAAAA = emptyList(),
            internalChallengePortAvailable = null,
            externalPortCheck = null,
        )
    }
    fun setTlsDomain(value: String) = edit { copy(tlsDomain = value.trim().take(253), preflightChecked = false) }
    fun setAcmeEmail(value: String) = edit { copy(acmeEmail = value.trim().take(254), preflightChecked = false) }
    fun setAcmeChallengePort(value: String) = edit { copy(acmeChallengePort = digits(value), preflightChecked = false) }
    fun setBodyLimit(value: String) = edit { copy(bodyLimitMb = digits(value)) }
    fun setSaveInterval(value: String) = edit { copy(saveIntervalSeconds = digits(value)) }
    fun setSnapshotInterval(value: String) = edit { copy(snapshotIntervalMinutes = digits(value)) }
    fun setSnapshotRetention(value: String) = edit { copy(snapshotRetentionCount = digits(value)) }
    fun setSnapshotCapacity(value: String) = edit { copy(snapshotCapacityMb = digits(value)) }
    fun setImageQuality(value: String) = edit { copy(imageQuality = digits(value)) }

    fun checkAcmePreflight() {
        val current = mutableState.value
        val desired = current.toConfigOrNull(instanceId, forceLetsEncryptHttps = true)
        val errors = desired?.let { Validators.validate(it).errors } ?: listOf("All numeric fields must contain whole numbers.")
        if (errors.isNotEmpty()) {
            mutableState.value = current.copy(validationErrors = errors)
            return
        }
        runAcme("Checking DNS and the internal challenge port…") {
            container.termuxRuntime.acmeInstall(instanceId)
            val response = container.termuxRuntime.acmePreflight(
                instanceId,
                desired!!.tlsDomain!!,
                desired.acmeEmail!!,
                desired.acmeChallengePort,
            )
            mutableState.value = mutableState.value.copy(
                acmeBusy = false,
                preflightChecked = true,
                dnsA = response.dnsA,
                dnsAAAA = response.dnsAAAA,
                internalChallengePortAvailable = response.internalPortAvailable,
                externalPortCheck = response.externalPortCheck,
                message = response.message ?: "Connection prerequisites checked. External TCP 80 still requires manual verification.",
            )
        }
    }

    fun issueAndEnableHttps() {
        val current = mutableState.value
        val desired = current.toConfigOrNull(instanceId, forceLetsEncryptHttps = true)
        val errors = desired?.let { Validators.validate(it).errors } ?: listOf("All numeric fields must contain whole numbers.")
        if (errors.isNotEmpty()) {
            mutableState.value = current.copy(validationErrors = errors)
            return
        }
        runAcme("Testing staging, then requesting the production certificate…") {
            val response = container.termuxRuntime.acmeIssue(
                instanceId,
                desired!!.tlsDomain!!,
                desired.acmeEmail!!,
                desired.acmeChallengePort,
            )
            val confirmed = desired.withAcmeStatus(response).copy(
                protocol = ServerProtocol.HTTPS,
                certificateMode = CertificateMode.LETS_ENCRYPT,
                autoRenew = true,
                applyState = ConfigApplyState.APPLIED,
                appliedAt = System.currentTimeMillis(),
                appliedPort = desired.port,
                appliedBindScope = desired.bindScope,
                appliedProtocol = ServerProtocol.HTTPS,
            )
            container.instances.upsertServerConfig(confirmed)
            mutableState.value = confirmed.toUiState(
                instance = requireNotNull(current.instance),
                applied = confirmed.appliedCopy(),
                lastError = null,
            ).copy(
                preflightChecked = current.preflightChecked,
                dnsA = current.dnsA,
                dnsAAAA = current.dnsAAAA,
                internalChallengePortAvailable = current.internalChallengePortAvailable,
                externalPortCheck = current.externalPortCheck,
                message = "Let's Encrypt certificate installed and HTTPS verified.",
            )
        }
    }

    fun renewCertificateNow() = runAcme("Checking certificate renewal…") {
        container.termuxRuntime.acmeRenew(instanceId)
        val status = container.termuxRuntime.acmeCertificateStatus(instanceId)
        val stored = requireNotNull(container.instances.getServerConfig(instanceId))
            .withAcmeStatus(status)
            .copy(lastRenewError = null, renewFailureCount = 0)
        container.instances.upsertServerConfig(stored)
        mutableState.value = stored.toUiState(
            instance = requireNotNull(mutableState.value.instance),
            applied = mutableState.value.applied,
            lastError = mutableState.value.lastApplyError,
        ).copy(message = if (status.renewed == true) "Certificate renewed and deployed." else "Certificate is current; no production reissue was needed.")
    }

    fun disableManagedHttps() = runAcme("Disabling managed HTTPS safely…") {
        container.termuxRuntime.acmeDisable(instanceId)
        val current = requireNotNull(container.instances.getServerConfig(instanceId))
        val updated = current.copy(
            protocol = ServerProtocol.HTTP,
            certificateMode = CertificateMode.MANUAL,
            tlsDomain = null,
            acmeEmail = null,
            certificateExpiresAt = null,
            autoRenew = false,
            lastRenewCheckAt = null,
            lastRenewSuccessAt = null,
            lastRenewError = null,
            renewFailureCount = 0,
            appliedProtocol = ServerProtocol.HTTP,
        )
        container.instances.upsertServerConfig(updated)
        mutableState.value = updated.toUiState(
            instance = requireNotNull(mutableState.value.instance),
            applied = updated.appliedCopy(),
            lastError = null,
        ).copy(message = "Managed HTTPS disabled. Existing certificate and ACME account files were preserved in Termux.")
    }

    fun save() {
        val current = mutableState.value
        val instance = current.instance ?: return
        if (current.saving) return
        val desired = current.toConfigOrNull(instanceId)
        val errors = desired?.let { Validators.validate(it).errors } ?: listOf("All numeric fields must contain whole numbers.")
        if (errors.isNotEmpty()) {
            mutableState.value = current.copy(validationErrors = errors)
            return
        }
        mutableState.value = current.copy(saving = true, validationErrors = emptyList(), message = null)
        container.applicationScope.launch {
            try {
                container.instances.upsertServerConfig(desired!!.copy(applyState = ConfigApplyState.APPLYING, lastError = null))
                when (val result = container.termuxRuntime.applyServerConfig(instance, desired)) {
                    is OperationResult.Success -> {
                        val observed = runCatching { container.termuxRuntime.readServerConfig(instanceId) }.getOrNull()
                        val applied = observed?.applied
                        val state = if (applied != null) ConfigApplyState.APPLIED else ConfigApplyState.PENDING
                        val persisted = desired.copy(
                            applyState = state,
                            appliedAt = if (applied != null) System.currentTimeMillis() else null,
                            appliedPort = applied?.port,
                            appliedBindScope = applied?.bindScope,
                            appliedProtocol = applied?.protocol,
                            appliedRequestBodyLimitMb = applied?.bodyLimitMb,
                            appliedSaveIntervalSeconds = applied?.saveIntervalSeconds,
                            appliedSnapshotIntervalMinutes = applied?.snapshotIntervalMinutes,
                            appliedSnapshotRetentionCount = applied?.snapshotRetentionCount,
                            appliedSnapshotCapacityMb = applied?.snapshotCapacityMb,
                        )
                        container.instances.upsertServerConfig(persisted)
                        container.instances.upsert(instance.copy(port = desired.port, updatedAt = System.currentTimeMillis()))
                        mutableState.value = persisted.toUiState(instance.copy(port = desired.port), applied, observed?.lastApplyError)
                            .copy(message = result.message)
                    }
                    is OperationResult.Failure -> {
                        val failed = desired.copy(applyState = ConfigApplyState.FAILED, lastError = result.message)
                        container.instances.upsertServerConfig(failed)
                        mutableState.value = failed.toUiState(instance, current.applied, result.message)
                            .copy(message = "${result.code}: ${result.message}")
                    }
                }
            } catch (exception: Exception) {
                val message = exception.message ?: "Server settings could not be saved."
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching {
                        container.instances.upsertServerConfig(desired!!.copy(
                            applyState = ConfigApplyState.FAILED, lastError = message,
                        ))
                    }
                }
                mutableState.value = mutableState.value.copy(saving = false, lastApplyError = message, message = message)
                if (exception is kotlinx.coroutines.CancellationException) throw exception
            }
        }
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }

    private fun runAcme(progressMessage: String, block: suspend () -> Unit) {
        if (mutableState.value.acmeBusy || mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(
            acmeBusy = true,
            validationErrors = emptyList(),
            message = progressMessage,
        )
        container.applicationScope.launch {
            try {
                block()
            } catch (exception: Exception) {
                val code = (exception as? HelperProtocolException)?.code
                val safeMessage = exception.message.orEmpty().replace(Regex("[\\r\\n\\t]+"), " ").take(300)
                    .ifBlank { "The ACME operation failed." }
                val stored = container.instances.getServerConfig(instanceId)
                if (stored != null) runCatching {
                    container.instances.upsertServerConfig(
                        stored.copy(
                            lastRenewCheckAt = System.currentTimeMillis(),
                            lastRenewError = safeMessage,
                            renewFailureCount = (stored.renewFailureCount + 1).coerceAtMost(1_000_000),
                        ),
                    )
                }
                mutableState.value = mutableState.value.copy(
                    acmeBusy = false,
                    lastRenewError = safeMessage,
                    message = listOfNotNull(code, safeMessage).joinToString(": "),
                )
                if (exception is kotlinx.coroutines.CancellationException) throw exception
            }
        }
    }

    private fun edit(transform: ServerConfigUiState.() -> ServerConfigUiState) {
        mutableState.value = mutableState.value.transform().copy(validationErrors = emptyList(), message = null)
    }

    private fun digits(value: String): String = value.filter(Char::isDigit).take(7)
}

data class LogsUiState(val loading: Boolean = true, val lines: List<LogLine> = emptyList(), val paused: Boolean = false)

class LogsViewModel(private val container: AppContainer, private val instanceId: String) : ViewModel() {
    private val mutableState = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = mutableState.asStateFlow()

    init { refresh() }
    fun refresh() {
        if (mutableState.value.paused) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true)
            val instance = container.instances.get(instanceId)
            val lines = instance?.let { container.runtimes.get(it.runtimeType).logs(it, 200) }.orEmpty()
            mutableState.value = mutableState.value.copy(loading = false, lines = lines)
        }
    }
    fun togglePause() { mutableState.value = mutableState.value.copy(paused = !mutableState.value.paused) }
}

enum class RecoveryPointAvailability {
    AVAILABLE,
    IN_USE,
    CREATING,
    RESTORING,
    CLEANUP_PENDING,
    FAILED,
    UNAVAILABLE,
}

data class RollbackConfirmation(
    val instance: RisuInstance,
    val point: RecoveryPoint,
    val preview: RollbackPreview,
)

data class RecoveryPointsUiState(
    val instance: RisuInstance? = null,
    val points: List<RecoveryPoint> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val inspectingPointId: RecoveryPointId? = null,
    val mutatingPointId: RecoveryPointId? = null,
    val rollbackConfirmation: RollbackConfirmation? = null,
    val rollbackOperation: OperationState = OperationState.Idle,
    val cloneOperation: OperationState = OperationState.Idle,
    val message: String? = null,
) {
    val busy: Boolean
        get() = rollbackOperation is OperationState.Running ||
            cloneOperation is OperationState.Running ||
            inspectingPointId != null || mutatingPointId != null
}

internal fun recoveryPointAvailability(point: RecoveryPoint): RecoveryPointAvailability = when {
    point.inUse -> RecoveryPointAvailability.IN_USE
    point.state == RecoveryPointState.CREATING -> RecoveryPointAvailability.CREATING
    point.state == RecoveryPointState.RESTORING -> RecoveryPointAvailability.RESTORING
    point.state == RecoveryPointState.CLEANUP_PENDING -> RecoveryPointAvailability.CLEANUP_PENDING
    point.state == RecoveryPointState.FAILED -> RecoveryPointAvailability.FAILED
    point.state == RecoveryPointState.AVAILABLE || point.state == RecoveryPointState.RESTORED ->
        RecoveryPointAvailability.AVAILABLE
    else -> RecoveryPointAvailability.UNAVAILABLE
}

/**
 * Presentation owner for historical recovery points. The helper-backed coordinator remains the
 * authority for integrity, compatibility, storage, and concurrency checks; this ViewModel only
 * performs conservative pre-gating so stale UI state can never enable an unsafe operation.
 */
class RecoveryPointsViewModel(
    private val container: AppContainer,
    private val instanceId: String,
) : ViewModel() {
    private val workManager = WorkManager.getInstance(container.applicationContext)
    private val mutableState = MutableStateFlow(RecoveryPointsUiState())
    val state: StateFlow<RecoveryPointsUiState> = mutableState.asStateFlow()
    private var trackedRollbackId: UUID? = null
    private var trackedCloneId: UUID? = null
    private var lastRollbackWork: WorkInfo? = null
    private var lastRecoveryCloneWork: WorkInfo? = null
    private var recoveringRollbackWork = true

    init {
        refresh(initial = true)
        workManager.getWorkInfosByTagFlow(RollbackCoordinator.ROLLBACK_TAG)
            .onEach(::onRollbackWorkChanged)
            .launchIn(viewModelScope)
        workManager.getWorkInfosByTagFlow(CloneCoordinator.CLONE_TAG)
            .onEach(::onCloneWorkChanged)
            .launchIn(viewModelScope)
    }

    fun refresh() = refresh(initial = false)

    private fun refresh(initial: Boolean) {
        if (mutableState.value.refreshing) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                loading = initial && mutableState.value.instance == null,
                refreshing = !initial,
            )
            try {
                val instance = requireNotNull(container.instances.get(instanceId)) { "Instance no longer exists." }
                require(instance.variantId == BuiltInVariants.pocketRisu.id &&
                    instance.runtimeType == io.github.risumanager.domain.model.RuntimeType.TERMUX_NODE
                ) { "Recovery points are available only for PocketRisu in Termux." }
                val points = container.rollbacks.recoveryPoints(instanceId)
                mutableState.value = mutableState.value.copy(
                    instance = instance,
                    points = points,
                    loading = false,
                    refreshing = false,
                )
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    refreshing = false,
                    message = error.message ?: "Recovery points could not be loaded.",
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    fun prepareRollback(point: RecoveryPoint) {
        val instance = mutableState.value.instance ?: return
        if (mutableState.value.busy || recoveryPointAvailability(point) != RecoveryPointAvailability.AVAILABLE) return
        mutableState.value = mutableState.value.copy(inspectingPointId = point.id, message = null)
        viewModelScope.launch {
            try {
                // Inspection revalidates the private on-disk artifact. The UI intentionally does
                // not render paths, manifests, or user-data details from the inspection response.
                val preview = container.rollbacks.inspect(instance.instanceId, point.id)
                require(preview.instanceId == instance.instanceId && preview.recoveryPointId == point.id) {
                    "Recovery preview identity mismatch."
                }
                val current = requireNotNull(container.instances.get(instance.instanceId)) { "Instance no longer exists." }
                mutableState.value = mutableState.value.copy(
                    instance = current,
                    inspectingPointId = null,
                    rollbackConfirmation = RollbackConfirmation(current, point, preview),
                )
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    inspectingPointId = null,
                    message = error.message ?: "The selected recovery point is unavailable.",
                )
                refresh()
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    fun dismissRollback() {
        if (mutableState.value.rollbackOperation !is OperationState.Running) {
            mutableState.value = mutableState.value.copy(rollbackConfirmation = null)
        }
    }

    fun confirmRollback() {
        val confirmation = mutableState.value.rollbackConfirmation ?: return
        val enoughSpace = confirmation.preview.requiredBytes == null || confirmation.preview.availableBytes == null ||
            confirmation.preview.availableBytes >= confirmation.preview.requiredBytes
        if (mutableState.value.busy || !confirmation.preview.available || !enoughSpace) return
        mutableState.value = mutableState.value.copy(
            rollbackConfirmation = null,
            rollbackOperation = OperationState.Running(
                "Queued",
                "Waiting to create and verify the pre-rollback recovery point",
                null,
            ),
            message = null,
        )
        container.applicationScope.launch {
            try {
                val request = container.rollbacks.rollback(
                    confirmation.instance.instanceId,
                    confirmation.point.id,
                    DEFAULT_RECOVERY_RETENTION_SECONDS,
                )
                trackedRollbackId = request.workId
                workManager.getWorkInfoByIdFlow(request.workId).first()?.let(::renderRollbackWork)
            } catch (error: Exception) {
                val message = error.message ?: "Rollback could not be queued."
                mutableState.value = mutableState.value.copy(
                    rollbackOperation = OperationState.Failure("ROLLBACK_QUEUE_FAILED", message, true),
                    message = message,
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    fun setPinned(point: RecoveryPoint, pinned: Boolean) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(mutatingPointId = point.id, message = null)
        viewModelScope.launch {
            try {
                container.rollbacks.setPinned(point.id, pinned)
                mutableState.value = mutableState.value.copy(
                    mutatingPointId = null,
                    message = if (pinned) "Recovery point pinned." else "Recovery point unpinned.",
                )
                refresh()
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    mutatingPointId = null,
                    message = error.message ?: "The pin state could not be changed.",
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    fun delete(point: RecoveryPoint) {
        if (mutableState.value.busy || point.pinned || point.inUse) return
        mutableState.value = mutableState.value.copy(mutatingPointId = point.id, message = null)
        viewModelScope.launch {
            try {
                container.rollbacks.delete(point.id)
                mutableState.value = mutableState.value.copy(
                    mutatingPointId = null,
                    message = "Recovery point deleted.",
                )
                refresh()
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    mutatingPointId = null,
                    message = error.message ?: "The recovery point could not be deleted.",
                )
                refresh()
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    fun clone(point: RecoveryPoint, displayName: String) {
        val instance = mutableState.value.instance ?: return
        if (mutableState.value.busy || recoveryPointAvailability(point) != RecoveryPointAvailability.AVAILABLE) return
        mutableState.value = mutableState.value.copy(
            cloneOperation = OperationState.Running("Preparing", "Validating the historical source and reserving a port", null),
            message = null,
        )
        container.applicationScope.launch {
            try {
                val request = container.rollbacks.clone(instance.instanceId, point.id, displayName)
                trackedCloneId = request.workId
                workManager.getWorkInfoByIdFlow(request.workId).first()?.let(::renderRecoveryCloneWork)
            } catch (error: Exception) {
                val message = error.message ?: "The recovery-point clone could not be queued."
                mutableState.value = mutableState.value.copy(
                    cloneOperation = OperationState.Failure("RECOVERY_CLONE_QUEUE_FAILED", message, true),
                    message = message,
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }

    private fun onRollbackWorkChanged(workInfos: List<WorkInfo>) {
        val relevant = workInfos.filter {
            it.tagValue(OperationCoordinator.INSTANCE_TAG_PREFIX) == instanceId
        }
        val tracked = trackedRollbackId?.let { id -> relevant.firstOrNull { it.id == id } }
        val active = newestActiveWork(relevant)
        val latest = relevant.maxByOrNull {
            it.tagValue(OperationCoordinator.CREATED_TAG_PREFIX)?.toLongOrNull() ?: 0L
        }
        val info = when {
            tracked != null && !tracked.state.isFinished -> tracked
            active != null -> active.also { trackedRollbackId = it.id }
            tracked != null -> tracked
            recoveringRollbackWork -> latest
            else -> null
        }
        recoveringRollbackWork = false
        info?.let(::renderRollbackWork)
    }

    private fun renderRollbackWork(info: WorkInfo) {
        if (info == lastRollbackWork && !info.state.isFinished) return
        lastRollbackWork = info
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> mutableState.value = mutableState.value.copy(
                rollbackOperation = OperationState.Running("Queued", "Waiting for durable rollback reconciliation", null),
            )
            WorkInfo.State.RUNNING -> mutableState.value = mutableState.value.copy(
                rollbackOperation = OperationState.Running(
                    info.progress.getString(RollbackWorker.KEY_STAGE) ?: "Restoring",
                    info.progress.getString(RollbackWorker.KEY_DETAIL),
                    null,
                ),
            )
            WorkInfo.State.SUCCEEDED -> {
                trackedRollbackId = null
                mutableState.value = mutableState.value.copy(
                    rollbackOperation = OperationState.Success("Rollback completed and normal PocketRisu operation was verified."),
                    message = "Rollback completed. The pre-rollback state is retained as a separate recovery point for 7 days.",
                )
                refresh()
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                trackedRollbackId = null
                val code = info.outputData.getString(RollbackWorker.KEY_ERROR_CODE)
                    ?: if (info.state == WorkInfo.State.CANCELLED) "ROLLBACK_CANCELLED" else "ROLLBACK_FAILED"
                val baseMessage = info.outputData.getString(RollbackWorker.KEY_ERROR_MESSAGE)
                    ?: "Rollback did not complete."
                val message = when {
                    info.outputData.getBoolean(RollbackWorker.KEY_RECOVERY_REQUIRED, false) ->
                        "$baseMessage Automatic recovery did not complete. Keep Termux data unchanged and open diagnostics."
                    info.outputData.getBoolean(RollbackWorker.KEY_CURRENT_STATE_RESTORED, false) ->
                        "$baseMessage The verified pre-rollback state was restored."
                    else -> "$baseMessage Inspect diagnostics before changing the Termux instance."
                }
                mutableState.value = mutableState.value.copy(
                    rollbackOperation = OperationState.Failure(code, message, true),
                    message = message,
                )
                refresh()
            }
        }
    }

    private fun onCloneWorkChanged(workInfos: List<WorkInfo>) {
        val id = trackedCloneId ?: return
        workInfos.firstOrNull { it.id == id }?.let(::renderRecoveryCloneWork)
    }

    private fun renderRecoveryCloneWork(info: WorkInfo) {
        if (info == lastRecoveryCloneWork && !info.state.isFinished) return
        lastRecoveryCloneWork = info
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> mutableState.value = mutableState.value.copy(
                cloneOperation = OperationState.Running("Queued", "Waiting for durable clone reconciliation", null),
            )
            WorkInfo.State.RUNNING -> mutableState.value = mutableState.value.copy(
                cloneOperation = OperationState.Running(
                    info.progress.getString(CloneWorker.KEY_STAGE) ?: "Cloning",
                    info.progress.getString(CloneWorker.KEY_DETAIL),
                    null,
                ),
            )
            WorkInfo.State.SUCCEEDED -> {
                trackedCloneId = null
                mutableState.value = mutableState.value.copy(
                    cloneOperation = OperationState.Success("A stopped, local-only instance was created from the recovery point."),
                    message = "Recovery-point clone created successfully.",
                )
                refresh()
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                trackedCloneId = null
                val message = info.outputData.getString(CloneWorker.KEY_ERROR_MESSAGE)
                    ?: "The recovery-point clone did not complete."
                mutableState.value = mutableState.value.copy(
                    cloneOperation = OperationState.Failure(
                        info.outputData.getString(CloneWorker.KEY_ERROR_CODE) ?: "RECOVERY_CLONE_FAILED",
                        message,
                        true,
                    ),
                    message = message,
                )
                refresh()
            }
        }
    }
}

class AppViewModelFactory(
    private val context: Context,
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(container) as T
        modelClass.isAssignableFrom(InstallViewModel::class.java) -> InstallViewModel(context, container) as T
        modelClass.isAssignableFrom(TermuxIntegrationViewModel::class.java) -> TermuxIntegrationViewModel(container) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container) as T
        else -> error("Unsupported ViewModel ${modelClass.name}")
    }
}

class LogsViewModelFactory(private val container: AppContainer, private val instanceId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LogsViewModel(container, instanceId) as T
}

class ServerConfigViewModelFactory(private val container: AppContainer, private val instanceId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ServerConfigViewModel(container, instanceId) as T
}

private fun InstanceServerConfig.toUiState(
    instance: RisuInstance,
    applied: InstanceServerConfig?,
    lastError: String?,
) = ServerConfigUiState(
    loading = false,
    instance = instance,
    capabilities = BuiltInVariants.find(instance.variantId)?.termuxProfile?.capabilities.orEmpty(),
    port = port.toString(),
    bindScope = bindScope,
    protocol = protocol,
    certificateMode = certificateMode,
    tlsDomain = tlsDomain.orEmpty(),
    acmeEmail = acmeEmail.orEmpty(),
    acmeChallengePort = acmeChallengePort.toString(),
    certificateExpiresAt = certificateExpiresAt,
    autoRenew = autoRenew,
    lastRenewCheckAt = lastRenewCheckAt,
    lastRenewSuccessAt = lastRenewSuccessAt,
    lastRenewError = lastRenewError,
    renewFailureCount = renewFailureCount,
    bodyLimitMb = bodyLimitMb.toString(),
    saveIntervalSeconds = saveIntervalSeconds.toString(),
    snapshotIntervalMinutes = snapshotIntervalMinutes.toString(),
    snapshotRetentionCount = snapshotRetentionCount.toString(),
    snapshotCapacityMb = snapshotCapacityMb.toString(),
    imageQuality = imageQuality.toString(),
    applied = applied,
    lastApplyError = lastError,
)

private fun ServerConfigUiState.toConfigOrNull(
    instanceId: String,
    forceLetsEncryptHttps: Boolean = false,
): InstanceServerConfig? {
    val parsedPort = port.toIntOrNull() ?: return null
    val parsedBodyLimit = bodyLimitMb.toIntOrNull() ?: return null
    val parsedSaveInterval = saveIntervalSeconds.toIntOrNull() ?: return null
    val parsedSnapshotInterval = snapshotIntervalMinutes.toIntOrNull() ?: return null
    val parsedSnapshotRetention = snapshotRetentionCount.toIntOrNull() ?: return null
    val parsedSnapshotCapacity = snapshotCapacityMb.toIntOrNull() ?: return null
    val parsedImageQuality = imageQuality.toIntOrNull() ?: return null
    val parsedChallengePort = acmeChallengePort.toIntOrNull() ?: return null
    return InstanceServerConfig(
        instanceId = instanceId,
        port = parsedPort,
        bindScope = bindScope,
        protocol = if (forceLetsEncryptHttps) ServerProtocol.HTTPS else protocol,
        certificateMode = if (forceLetsEncryptHttps) CertificateMode.LETS_ENCRYPT else certificateMode,
        tlsDomain = tlsDomain.trim().ifBlank { null },
        acmeEmail = acmeEmail.trim().ifBlank { null },
        acmeChallengePort = parsedChallengePort,
        certificateExpiresAt = certificateExpiresAt,
        autoRenew = autoRenew,
        lastRenewCheckAt = lastRenewCheckAt,
        lastRenewSuccessAt = lastRenewSuccessAt,
        lastRenewError = lastRenewError,
        renewFailureCount = renewFailureCount,
        bodyLimitMb = parsedBodyLimit,
        saveIntervalSeconds = parsedSaveInterval,
        snapshotIntervalMinutes = parsedSnapshotInterval,
        snapshotRetentionCount = parsedSnapshotRetention,
        snapshotCapacityMb = parsedSnapshotCapacity,
        imageQuality = parsedImageQuality,
    )
}

class RecoveryPointsViewModelFactory(private val container: AppContainer, private val instanceId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = RecoveryPointsViewModel(container, instanceId) as T
}

private fun InstanceServerConfig.withPersistentSettingsFrom(stored: InstanceServerConfig): InstanceServerConfig = copy(
    imageQuality = stored.imageQuality,
    certificateMode = stored.certificateMode,
    tlsDomain = stored.tlsDomain,
    acmeEmail = stored.acmeEmail,
    acmeChallenge = stored.acmeChallenge,
    acmeChallengePort = stored.acmeChallengePort,
    certificateExpiresAt = stored.certificateExpiresAt,
    autoRenew = stored.autoRenew,
    lastRenewCheckAt = stored.lastRenewCheckAt,
    lastRenewSuccessAt = stored.lastRenewSuccessAt,
    lastRenewError = stored.lastRenewError,
    renewFailureCount = stored.renewFailureCount,
)

private fun InstanceServerConfig.withAcmeStatus(response: HelperResponse): InstanceServerConfig {
    val mode = runCatching { response.certificateMode?.let(CertificateMode::valueOf) }.getOrNull()
        ?: certificateMode
    return copy(
        certificateMode = mode,
        tlsDomain = response.domain ?: tlsDomain,
        acmeEmail = response.email ?: acmeEmail,
        acmeChallengePort = response.challengePort ?: acmeChallengePort,
        certificateExpiresAt = response.certificateExpiresAt ?: certificateExpiresAt,
        autoRenew = response.autoRenew ?: autoRenew,
        lastRenewCheckAt = response.lastRenewCheckAt ?: lastRenewCheckAt,
        lastRenewSuccessAt = response.lastRenewSuccessAt ?: lastRenewSuccessAt,
        lastRenewError = response.lastRenewError?.takeIf(String::isNotBlank),
        renewFailureCount = response.renewFailureCount ?: renewFailureCount,
    )
}

private fun InstanceServerConfig.appliedCopy(): InstanceServerConfig? = appliedPort?.let {
    copy(
        port = it,
        bindScope = appliedBindScope ?: bindScope,
        protocol = appliedProtocol ?: protocol,
        bodyLimitMb = appliedRequestBodyLimitMb ?: bodyLimitMb,
        saveIntervalSeconds = appliedSaveIntervalSeconds ?: saveIntervalSeconds,
        snapshotIntervalMinutes = appliedSnapshotIntervalMinutes ?: snapshotIntervalMinutes,
        snapshotRetentionCount = appliedSnapshotRetentionCount ?: snapshotRetentionCount,
        snapshotCapacityMb = appliedSnapshotCapacityMb ?: snapshotCapacityMb,
    )
}
