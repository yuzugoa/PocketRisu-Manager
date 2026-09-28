package io.github.risumanager.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.risumanager.domain.model.BackupMetadata
import io.github.risumanager.domain.model.BackupReason
import io.github.risumanager.domain.model.BindScope
import io.github.risumanager.domain.model.AcmeChallenge
import io.github.risumanager.domain.model.CertificateMode
import io.github.risumanager.domain.model.CloneOperation
import io.github.risumanager.domain.model.CloneResult
import io.github.risumanager.domain.model.CloneStage
import io.github.risumanager.domain.model.ConfigApplyState
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.OperationRecord
import io.github.risumanager.domain.model.OperationResultKind
import io.github.risumanager.domain.model.OperationType
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.model.RollbackOperation
import io.github.risumanager.domain.model.RollbackEvent
import io.github.risumanager.domain.model.RollbackResult
import io.github.risumanager.domain.model.RollbackStage
import io.github.risumanager.domain.model.ServerProtocol
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.RestorationState
import io.github.risumanager.domain.model.UpdateOperation
import io.github.risumanager.domain.model.UpdateResult
import io.github.risumanager.domain.model.UpdateStage
import io.github.risumanager.domain.validation.ServerConfigValidator
import io.github.risumanager.domain.planning.ClonePlanner

class RisuDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME,
    private val isPortAvailable: (Int) -> Boolean = ::isLoopbackPortAvailable,
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE instances (
                instance_id TEXT PRIMARY KEY NOT NULL,
                display_name TEXT,
                variant_id TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                runtime_type TEXT NOT NULL,
                repository TEXT NOT NULL,
                installed_revision TEXT,
                previous_revision TEXT,
                installed_version TEXT,
                latest_revision TEXT,
                port INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_known_status TEXT NOT NULL
            )
            """.trimIndent(),
        )
        createInstanceServerConfigsTable(db)
        db.execSQL(
            """
            CREATE TABLE operations (
                operation_id TEXT PRIMARY KEY NOT NULL,
                instance_id TEXT NOT NULL,
                type TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                result TEXT NOT NULL,
                stage TEXT,
                detail TEXT,
                from_revision TEXT,
                to_revision TEXT,
                error_code TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX operations_instance_started ON operations(instance_id, started_at DESC)")
        db.execSQL(
            """
            CREATE TABLE backups (
                id TEXT PRIMARY KEY NOT NULL,
                instance_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                reason TEXT NOT NULL,
                source_revision TEXT,
                app_version TEXT,
                size INTEGER NOT NULL,
                format_version INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX backups_instance_created ON backups(instance_id, created_at DESC)")
        createCloneOperationsTable(db)
        createRecoveryPointsTable(db)
        createUpdateOperationsTable(db)
        createRollbackOperationsTable(db)
        createRollbackEventsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(newVersion == DATABASE_VERSION) { "Unsupported database version $newVersion" }
        if (oldVersion < 2) {
            createInstanceServerConfigsTable(db)
            // Existing instances were created before server settings were persisted.
            // INSERT IGNORE semantics keep a partially migrated database recoverable.
            db.execSQL(
                """
                INSERT OR IGNORE INTO instance_server_configs (instance_id, port)
                SELECT instance_id, port FROM instances
                """.trimIndent(),
            )
        }
        else if (oldVersion < 3) {
            addTlsColumns(db)
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE instances ADD COLUMN display_name TEXT")
            createCloneOperationsTable(db)
        }
        if (oldVersion < 5) {
            createRecoveryPointsTable(db)
            createUpdateOperationsTable(db)
        }
        if (oldVersion < 6) {
            if (oldVersion >= 4) db.execSQL("ALTER TABLE clone_operations ADD COLUMN recovery_point_id TEXT")
            createRollbackOperationsTable(db)
            createRollbackEventsTable(db)
        }
    }

    fun upsertInstance(instance: RisuInstance) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // REPLACE deletes the old parent row before inserting the new one, which
            // cascades into the separately persisted server settings. Update in place.
            if (db.update("instances", instance.toValues(), "instance_id = ?", arrayOf(instance.instanceId)) == 0) {
                db.insertOrThrow("instances", null, instance.toValues())
            }
            // A new instance gets its configured port from the instance record. IGNORE
            // keeps all existing settings, including the user's configured port.
            db.insertWithOnConflict(
                "instance_server_configs",
                null,
                ContentValues().apply {
                    put("instance_id", instance.instanceId)
                    put("port", instance.port)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun instance(instanceId: String): RisuInstance? = readableDatabase.query(
        "instances", null, "instance_id = ?", arrayOf(instanceId), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }

    fun instances(): List<RisuInstance> = readableDatabase.query(
        "instances", null, null, null, null, null, "updated_at DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toInstance()) } }

    fun updateRuntimeStatus(instanceId: String, status: RuntimeState, updatedAt: Long) {
        val values = ContentValues().apply {
            put("last_known_status", status.name)
            put("updated_at", updatedAt)
        }
        writableDatabase.update("instances", values, "instance_id = ?", arrayOf(instanceId))
    }

    fun upsertServerConfig(config: InstanceServerConfig) {
        ServerConfigValidator.requireValid(config)
        writableDatabase.insertWithOnConflict(
            "instance_server_configs",
            null,
            config.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun serverConfig(instanceId: String): InstanceServerConfig? = readableDatabase.query(
        "instance_server_configs", null, "instance_id = ?", arrayOf(instanceId), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toServerConfig() else null }

    fun serverConfigs(): List<InstanceServerConfig> = readableDatabase.query(
        "instance_server_configs", null, null, null, null, null, "instance_id ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toServerConfig()) } }

    fun deleteServerConfig(instanceId: String) {
        writableDatabase.delete("instance_server_configs", "instance_id = ?", arrayOf(instanceId))
    }

    fun upsertOperation(operation: OperationRecord) {
        writableDatabase.insertWithOnConflict(
            "operations", null, operation.toValues(), SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun operations(instanceId: String, limit: Int = 50): List<OperationRecord> = readableDatabase.query(
        "operations", null, "instance_id = ?", arrayOf(instanceId), null, null, "started_at DESC", limit.coerceIn(1, 200).toString(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toOperation()) } }

    fun upsertBackup(backup: BackupMetadata) {
        writableDatabase.insertWithOnConflict(
            "backups", null, backup.toValues(), SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun backups(instanceId: String): List<BackupMetadata> = readableDatabase.query(
        "backups", null, "instance_id = ?", arrayOf(instanceId), null, null, "created_at DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toBackup()) } }

    /**
     * Atomically reserves the target identity, source ownership, and port before WorkManager is
     * allowed to dispatch the helper. A target is still provisional and absent from instances.
     */
    fun prepareClone(
        operationId: String,
        sourceInstanceId: String,
        targetInstanceId: String,
        targetDisplayName: String,
        now: Long,
    ): CloneOperation {
        val name = requireNotNull(ClonePlanner.normalizeName(targetDisplayName)) { "Clone name is invalid" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val source = db.query("instances", null, "instance_id = ?", arrayOf(sourceInstanceId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }
                ?: error("Source instance no longer exists")
            ClonePlanner.validateSource(source)?.let { error(it) }
            check(db.query("instances", arrayOf("instance_id"), "instance_id = ?", arrayOf(targetInstanceId), null, null, null, "1")
                .use { !it.moveToFirst() }) { "Target instance already exists" }
            check(db.query("clone_operations", arrayOf("operation_id"), "source_instance_id = ? AND result = 'RUNNING'", arrayOf(sourceInstanceId), null, null, null, "1")
                .use { !it.moveToFirst() }) { "A clone operation already owns this source instance" }
            check(!db.hasActiveRollback(sourceInstanceId)) { "A rollback operation already owns this source instance" }

            val usedPorts = buildSet {
                db.query("instances", arrayOf("port"), null, null, null, null, null).use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getInt(0))
                }
                db.query("instance_server_configs", arrayOf("port"), null, null, null, null, null).use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getInt(0))
                }
                db.query("clone_operations", arrayOf("target_port"), "result = 'RUNNING'", null, null, null, null).use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getInt(0))
                }
            }
            val port = checkNotNull(ClonePlanner.allocatePort(usedPorts, source.port + 1, isPortAvailable)) { "No available port could be reserved" }
            val sourceConfig = db.query("instance_server_configs", null, "instance_id = ?", arrayOf(sourceInstanceId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toServerConfig() else InstanceServerConfig(sourceInstanceId, port = source.port) }
            val operation = CloneOperation(
                operationId = operationId,
                sourceInstanceId = sourceInstanceId,
                targetInstanceId = targetInstanceId,
                targetDisplayName = name,
                targetPort = port,
                sourceRevision = requireNotNull(source.installedRevision),
                sourceVersion = source.installedVersion,
                bodyLimitMb = sourceConfig.bodyLimitMb,
                saveIntervalSeconds = sourceConfig.saveIntervalSeconds,
                snapshotIntervalMinutes = sourceConfig.snapshotIntervalMinutes,
                snapshotRetentionCount = sourceConfig.snapshotRetentionCount,
                snapshotCapacityMb = sourceConfig.snapshotCapacityMb,
                imageQuality = sourceConfig.imageQuality,
                createdAt = now,
                updatedAt = now,
            )
            db.insertOrThrow("clone_operations", null, operation.toValues())
            db.setTransactionSuccessful()
            return operation
        } finally {
            db.endTransaction()
        }
    }

    fun prepareRecoveryClone(
        operationId: String,
        sourceInstanceId: String,
        recoveryPointId: RecoveryPointId,
        targetInstanceId: String,
        targetDisplayName: String,
        now: Long,
    ): CloneOperation {
        val name = requireNotNull(ClonePlanner.normalizeName(targetDisplayName)) { "Clone name is invalid" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val source = db.query("instances", null, "instance_id = ?", arrayOf(sourceInstanceId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }
                ?: error("Source instance no longer exists")
            ClonePlanner.validateSource(source)?.let { error(it) }
            val point = db.query(
                "recovery_points", null,
                "recovery_point_id = ? AND instance_id = ? AND manager_owned = 1",
                arrayOf(recoveryPointId.value, sourceInstanceId), null, null, null, "1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecoveryPoint() else null }
                ?: error("Recovery point is unavailable")
            check(point.state in setOf(RecoveryPointState.AVAILABLE, RecoveryPointState.RESTORED) && !point.inUse) {
                "Recovery point is not available for cloning"
            }
            check(point.variantId == source.variantId && point.layoutVersion == 1 && point.formatVersion == 1) {
                "Recovery point is incompatible with this PocketRisu instance"
            }
            check(db.query("instances", arrayOf("instance_id"), "instance_id = ?", arrayOf(targetInstanceId), null, null, null, "1")
                .use { !it.moveToFirst() }) { "Target instance already exists" }
            check(!db.hasActiveInstanceOperation(sourceInstanceId)) { "Another operation already owns this source instance" }
            val usedPorts = db.usedPorts()
            val port = checkNotNull(ClonePlanner.allocatePort(usedPorts, source.port + 1, isPortAvailable)) {
                "No available port could be reserved"
            }
            val sourceConfig = db.query("instance_server_configs", null, "instance_id = ?", arrayOf(sourceInstanceId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toServerConfig() else InstanceServerConfig(sourceInstanceId, port = source.port) }
            val operation = CloneOperation(
                operationId = operationId,
                sourceInstanceId = sourceInstanceId,
                targetInstanceId = targetInstanceId,
                targetDisplayName = name,
                targetPort = port,
                sourceRevision = requireNotNull(point.serverRevision),
                sourceVersion = point.serverVersion,
                recoveryPointId = point.id,
                bodyLimitMb = sourceConfig.bodyLimitMb,
                saveIntervalSeconds = sourceConfig.saveIntervalSeconds,
                snapshotIntervalMinutes = sourceConfig.snapshotIntervalMinutes,
                snapshotRetentionCount = sourceConfig.snapshotRetentionCount,
                snapshotCapacityMb = sourceConfig.snapshotCapacityMb,
                imageQuality = sourceConfig.imageQuality,
                createdAt = now,
                updatedAt = now,
            )
            db.insertOrThrow("clone_operations", null, operation.toValues())
            db.setTransactionSuccessful()
            return operation
        } finally {
            db.endTransaction()
        }
    }

    fun cloneOperation(operationId: String): CloneOperation? = readableDatabase.query(
        "clone_operations", null, "operation_id = ?", arrayOf(operationId), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toCloneOperation() else null }

    fun activeCloneOperations(): List<CloneOperation> = readableDatabase.query(
        "clone_operations", null, "result = ?", arrayOf(CloneResult.RUNNING.name), null, null, "created_at ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toCloneOperation()) } }

    fun upsertCloneOperation(operation: CloneOperation) {
        writableDatabase.insertWithOnConflict(
            "clone_operations", null, operation.toValues(), SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun reassignClonePort(operationId: String, now: Long): CloneOperation {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val operation = db.query("clone_operations", null, "operation_id = ? AND result = 'RUNNING'", arrayOf(operationId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toCloneOperation() else null }
                ?: error("Active clone operation no longer exists")
            val used = buildSet {
                db.query("instances", arrayOf("port"), null, null, null, null, null).use { while (it.moveToNext()) add(it.getInt(0)) }
                db.query("instance_server_configs", arrayOf("port"), null, null, null, null, null).use { while (it.moveToNext()) add(it.getInt(0)) }
                db.query("clone_operations", arrayOf("target_port"), "result = 'RUNNING' AND operation_id != ?", arrayOf(operationId), null, null, null).use {
                    while (it.moveToNext()) add(it.getInt(0))
                }
                add(operation.targetPort)
            }
            val port = checkNotNull(ClonePlanner.allocatePort(used, operation.targetPort + 1, isPortAvailable)) {
                "No replacement port is available"
            }
            val updated = operation.copy(targetPort = port, updatedAt = now, detail = "Reserved replacement port $port")
            db.update("clone_operations", updated.toValues(), "operation_id = ?", arrayOf(operationId))
            db.setTransactionSuccessful()
            return updated
        } finally {
            db.endTransaction()
        }
    }

    /** Publication boundary: target identity/config and completed operation become visible together. */
    fun publishClone(
        operation: CloneOperation,
        target: RisuInstance,
        config: InstanceServerConfig,
        now: Long,
    ) {
        require(operation.targetInstanceId == target.instanceId && config.instanceId == target.instanceId)
        require(operation.helperPublished)
        require(target.lastKnownStatus == RuntimeState.STOPPED)
        require(target.port == operation.targetPort && config.port == operation.targetPort)
        require(config.bindScope == BindScope.LOOPBACK && config.protocol == ServerProtocol.HTTP)
        require(config.certificateMode == CertificateMode.MANUAL && !config.autoRenew && config.tlsDomain == null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stored = db.query("clone_operations", null, "operation_id = ?", arrayOf(operation.operationId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toCloneOperation() else null }
                ?: error("Clone operation no longer exists")
            check(stored.sourceInstanceId == operation.sourceInstanceId && stored.targetInstanceId == operation.targetInstanceId) {
                "Clone operation identity changed"
            }
            val existing = db.query("instances", null, "instance_id = ?", arrayOf(target.instanceId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }
            if (existing == null) {
                db.insertOrThrow("instances", null, target.toValues())
                db.insertOrThrow("instance_server_configs", null, config.toValues())
            } else {
                check(existing.variantId == target.variantId && existing.installedRevision == target.installedRevision && existing.port == target.port) {
                    "Published target conflicts with an existing instance"
                }
            }
            db.update(
                "clone_operations",
                operation.copy(stage = CloneStage.COMPLETED, result = CloneResult.SUCCESS, updatedAt = now).toValues(),
                "operation_id = ?",
                arrayOf(operation.operationId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Reserves the instance and its internal recovery identity before WorkManager is enqueued. */
    fun prepareUpdate(operation: UpdateOperation, recoveryPoint: RecoveryPoint) {
        require(operation.result == UpdateResult.RUNNING && operation.stage == UpdateStage.PREPARED)
        require(operation.recoveryPointId == recoveryPoint.id && operation.instanceId == recoveryPoint.instanceId)
        require(operation.variantId == "pocketrisu" && operation.layoutVersion == 1)
        require(operation.retentionSeconds == recoveryPoint.retentionSeconds)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val instance = db.query("instances", null, "instance_id = ?", arrayOf(operation.instanceId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }
                ?: error("Instance no longer exists")
            check(instance.variantId == "pocketrisu" && instance.runtimeType == RuntimeType.TERMUX_NODE) {
                "Updates are supported only for PocketRisu running in Termux"
            }
            check(instance.installedRevision == operation.currentRevision && instance.installedVersion == operation.currentVersion) {
                "Installed metadata changed while reserving the update"
            }
            check(db.query("clone_operations", arrayOf("operation_id"), "source_instance_id = ? AND result = 'RUNNING'", arrayOf(operation.instanceId), null, null, null, "1")
                .use { !it.moveToFirst() }) { "A clone operation already owns this instance" }
            check(db.query("update_operations", arrayOf("operation_id"), "instance_id = ? AND result = 'RUNNING'", arrayOf(operation.instanceId), null, null, null, "1")
                .use { !it.moveToFirst() }) { "An update operation already owns this instance" }
            check(db.query("operations", arrayOf("operation_id"), "instance_id = ? AND result = 'RUNNING'", arrayOf(operation.instanceId), null, null, null, "1")
                .use { !it.moveToFirst() }) { "Another operation already owns this instance" }
            check(!db.hasActiveRollback(operation.instanceId)) { "A rollback operation already owns this instance" }
            db.insertOrThrow("recovery_points", null, recoveryPoint.toValues())
            db.insertOrThrow("update_operations", null, operation.toValues())
            db.insertOrThrow("operations", null, operation.toOperationRecord().toValues())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateOperation(operationId: String): UpdateOperation? = readableDatabase.query(
        "update_operations", null, "operation_id = ?", arrayOf(operationId), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toUpdateOperation() else null }

    fun activeUpdateOperations(): List<UpdateOperation> = readableDatabase.query(
        "update_operations", null, "result = ?", arrayOf(UpdateResult.RUNNING.name), null, null, "created_at ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toUpdateOperation()) } }

    fun recoveryPoint(id: RecoveryPointId): RecoveryPoint? = readableDatabase.query(
        "recovery_points", null, "recovery_point_id = ?", arrayOf(id.value), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecoveryPoint() else null }

    fun recoveryPoints(instanceId: String): List<RecoveryPoint> = readableDatabase.query(
        "recovery_points", null, "instance_id = ? AND manager_owned = 1", arrayOf(instanceId), null, null, "created_at DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecoveryPoint()) } }

    fun upsertUpdateProgress(operation: UpdateOperation, recoveryPoint: RecoveryPoint? = null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stored = db.query("update_operations", null, "operation_id = ?", arrayOf(operation.operationId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toUpdateOperation() else null }
                ?: error("Update operation no longer exists")
            stored.requireSameIdentity(operation)
            if (recoveryPoint != null) {
                require(recoveryPoint.id == operation.recoveryPointId && recoveryPoint.instanceId == operation.instanceId)
                upsertRecoveryPoint(db, recoveryPoint)
            }
            db.update("update_operations", operation.toValues(), "operation_id = ?", arrayOf(operation.operationId))
            db.insertWithOnConflict("operations", null, operation.toOperationRecord().toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Publishes target metadata, recovery expiry, and both operation journals atomically. */
    fun publishUpdateSuccess(operation: UpdateOperation, recoveryPoint: RecoveryPoint, now: Long) {
        require(operation.result == UpdateResult.RUNNING)
        require(operation.activeRevision.equals(operation.targetRevision, ignoreCase = true))
        require(operation.launchConfirmed && operation.healthConfirmed) {
            "Update success requires both launch and health confirmation"
        }
        require(recoveryPoint.state == RecoveryPointState.VERIFIED || recoveryPoint.state == RecoveryPointState.AVAILABLE)
        require(recoveryPoint.manifestSha256.isNotBlank() && recoveryPoint.dataDigestSha256.isNotBlank())
        require(recoveryPoint.configurationDigestSha256.isNotBlank() && recoveryPoint.completedAt != null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stored = db.query("update_operations", null, "operation_id = ?", arrayOf(operation.operationId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toUpdateOperation() else null }
                ?: error("Update operation no longer exists")
            stored.requireSameIdentity(operation)
            val instance = db.query("instances", null, "instance_id = ?", arrayOf(operation.instanceId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }
                ?: error("Instance no longer exists")
            check(
                instance.installedRevision == operation.currentRevision ||
                    instance.installedRevision.equals(operation.targetRevision, ignoreCase = true),
            ) { "Installed revision changed outside the reserved update" }
            val successfulAt = operation.completedAt ?: now
            val completed = operation.copy(
                stage = UpdateStage.COMPLETED,
                result = UpdateResult.SUCCESS,
                completedAt = successfulAt,
                updatedAt = successfulAt,
                errorCode = null,
            )
            val retained = recoveryPoint.copy(
                state = RecoveryPointState.AVAILABLE,
                inUse = false,
                expiresAt = successfulAt + operation.retentionSeconds * 1000,
                lastError = null,
            )
            db.update(
                "instances",
                instance.copy(
                    previousRevision = operation.currentRevision,
                    installedRevision = operation.targetRevision,
                    installedVersion = operation.targetVersion,
                    latestRevision = operation.targetRevision,
                    updatedAt = now,
                    lastKnownStatus = if (operation.sourceWasRunning == false) RuntimeState.STOPPED else RuntimeState.RUNNING,
                ).toValues(),
                "instance_id = ?",
                arrayOf(operation.instanceId),
            )
            upsertRecoveryPoint(db, retained)
            db.update("update_operations", completed.toValues(), "operation_id = ?", arrayOf(operation.operationId))
            db.insertWithOnConflict("operations", null, completed.toOperationRecord().toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Reserves both recovery identities and the instance before rollback work is enqueued. */
    fun prepareRollback(operation: RollbackOperation, preRollbackPoint: RecoveryPoint) {
        require(operation.result == RollbackResult.RUNNING && operation.stage == RollbackStage.PREPARED)
        require(operation.preRollbackRecoveryPointId == preRollbackPoint.id)
        require(operation.instanceId == preRollbackPoint.instanceId)
        require(operation.selectedRecoveryPointId != operation.preRollbackRecoveryPointId)
        require(operation.variantId == "pocketrisu" && operation.layoutVersion == 1)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val instance = db.query("instances", null, "instance_id = ?", arrayOf(operation.instanceId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }
                ?: error("Instance no longer exists")
            check(instance.variantId == "pocketrisu" && instance.runtimeType == RuntimeType.TERMUX_NODE) {
                "Rollback is supported only for PocketRisu running in Termux"
            }
            check(instance.installedRevision.equals(operation.currentRevision, ignoreCase = true) && instance.installedVersion == operation.currentVersion) {
                "Installed metadata changed while reserving rollback"
            }
            val selected = db.query(
                "recovery_points", null,
                "recovery_point_id = ? AND instance_id = ? AND manager_owned = 1",
                arrayOf(operation.selectedRecoveryPointId.value, operation.instanceId), null, null, null, "1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecoveryPoint() else null }
                ?: error("Selected recovery point is unavailable")
            check(selected.state in setOf(RecoveryPointState.AVAILABLE, RecoveryPointState.RESTORED) && !selected.inUse) {
                "Selected recovery point is not available"
            }
            check(selected.variantId == operation.variantId && selected.layoutVersion == operation.layoutVersion && selected.formatVersion == 1) {
                "Selected recovery point is incompatible"
            }
            check(selected.serverRevision.equals(operation.targetRevision, ignoreCase = true) && selected.serverVersion == operation.targetVersion) {
                "Selected recovery metadata changed"
            }
            check(!db.hasActiveInstanceOperation(operation.instanceId)) { "Another operation already owns this instance" }
            db.insertOrThrow("recovery_points", null, preRollbackPoint.toValues())
            upsertRecoveryPoint(db, selected.copy(inUse = true, state = RecoveryPointState.RESTORING, lastError = null))
            db.insertOrThrow("rollback_operations", null, operation.toValues())
            db.insertOrThrow("operations", null, operation.toOperationRecord().toValues())
            db.insertOrThrow("rollback_events", null, operation.toEventValues())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun rollbackOperation(operationId: String): RollbackOperation? = readableDatabase.query(
        "rollback_operations", null, "operation_id = ?", arrayOf(operationId), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRollbackOperation() else null }

    fun activeRollbackOperations(): List<RollbackOperation> = readableDatabase.query(
        "rollback_operations", null, "result = ?", arrayOf(RollbackResult.RUNNING.name), null, null, "created_at ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRollbackOperation()) } }

    fun rollbackEvents(instanceId: String, limit: Int = 100): List<RollbackEvent> = readableDatabase.query(
        "rollback_events", null, "instance_id = ?", arrayOf(instanceId), null, null,
        "recorded_at DESC, event_id DESC", limit.coerceIn(1, 500).toString(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRollbackEvent()) } }

    fun upsertRollbackProgress(
        operation: RollbackOperation,
        selectedPoint: RecoveryPoint? = null,
        preRollbackPoint: RecoveryPoint? = null,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stored = db.query("rollback_operations", null, "operation_id = ?", arrayOf(operation.operationId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toRollbackOperation() else null }
                ?: error("Rollback operation no longer exists")
            stored.requireSameIdentity(operation)
            selectedPoint?.let {
                require(it.id == operation.selectedRecoveryPointId && it.instanceId == operation.instanceId)
                upsertRecoveryPoint(db, it)
            }
            preRollbackPoint?.let {
                require(it.id == operation.preRollbackRecoveryPointId && it.instanceId == operation.instanceId)
                upsertRecoveryPoint(db, it)
            }
            db.update("rollback_operations", operation.toValues(), "operation_id = ?", arrayOf(operation.operationId))
            db.insertWithOnConflict("operations", null, operation.toOperationRecord().toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            appendRollbackEventIfChanged(db, stored, operation)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Publishes restored identity/config, both recovery points, and terminal history atomically. */
    fun publishRollbackSuccess(
        operation: RollbackOperation,
        selectedPoint: RecoveryPoint,
        preRollbackPoint: RecoveryPoint,
        restoredConfig: InstanceServerConfig,
        now: Long,
    ) {
        require(operation.result == RollbackResult.RUNNING)
        require(operation.activeRevision.equals(operation.targetRevision, ignoreCase = true))
        require(operation.launchConfirmed && operation.healthConfirmed)
        require(selectedPoint.id == operation.selectedRecoveryPointId)
        require(preRollbackPoint.id == operation.preRollbackRecoveryPointId)
        require(preRollbackPoint.manifestSha256.isNotBlank() && preRollbackPoint.dataDigestSha256.isNotBlank())
        require(preRollbackPoint.configurationDigestSha256.isNotBlank() && preRollbackPoint.completedAt != null)
        require(restoredConfig.instanceId == operation.instanceId)
        ServerConfigValidator.requireValid(restoredConfig)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stored = db.query("rollback_operations", null, "operation_id = ?", arrayOf(operation.operationId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toRollbackOperation() else null }
                ?: error("Rollback operation no longer exists")
            stored.requireSameIdentity(operation)
            val instance = db.query("instances", null, "instance_id = ?", arrayOf(operation.instanceId), null, null, null, "1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }
                ?: error("Instance no longer exists")
            check(
                instance.installedRevision.equals(operation.currentRevision, ignoreCase = true) ||
                    instance.installedRevision.equals(operation.targetRevision, ignoreCase = true),
            ) { "Installed revision changed outside the reserved rollback" }
            val completedAt = operation.completedAt ?: now
            val completed = operation.copy(
                stage = RollbackStage.COMPLETED,
                result = RollbackResult.SUCCESS,
                currentStateRestored = false,
                completedAt = completedAt,
                updatedAt = completedAt,
                errorCode = null,
            )
            val restoredSelected = selectedPoint.copy(
                state = RecoveryPointState.RESTORED,
                inUse = false,
                restored = true,
                restorationState = RestorationState.SUCCEEDED,
                lastError = null,
            )
            val retainedCurrent = preRollbackPoint.copy(
                state = RecoveryPointState.AVAILABLE,
                inUse = false,
                expiresAt = completedAt + operation.retentionSeconds * 1000,
                lastError = null,
            )
            db.update(
                "instances",
                instance.copy(
                    previousRevision = operation.currentRevision,
                    installedRevision = operation.targetRevision,
                    installedVersion = operation.targetVersion,
                    latestRevision = operation.targetRevision,
                    port = restoredConfig.port,
                    updatedAt = now,
                    lastKnownStatus = if (operation.sourceWasRunning == false) RuntimeState.STOPPED else RuntimeState.RUNNING,
                ).toValues(),
                "instance_id = ?",
                arrayOf(operation.instanceId),
            )
            db.update("instance_server_configs", restoredConfig.toValues(), "instance_id = ?", arrayOf(operation.instanceId))
            upsertRecoveryPoint(db, restoredSelected)
            upsertRecoveryPoint(db, retainedCurrent)
            db.update("rollback_operations", completed.toValues(), "operation_id = ?", arrayOf(operation.operationId))
            db.insertWithOnConflict("operations", null, completed.toOperationRecord().toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            appendRollbackEventIfChanged(db, stored, completed)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setRecoveryPointPinned(id: RecoveryPointId, pinned: Boolean) {
        writableDatabase.update(
            "recovery_points",
            ContentValues().apply { put("pinned", if (pinned) 1 else 0) },
            "recovery_point_id = ? AND manager_owned = 1",
            arrayOf(id.value),
        )
    }

    fun expiredRecoveryPoints(now: Long): List<RecoveryPoint> = readableDatabase.query(
        "recovery_points",
        null,
        "manager_owned = 1 AND expires_at IS NOT NULL AND expires_at <= ? AND pinned = 0 AND in_use = 0 " +
            "AND state IN ('AVAILABLE','RESTORED') AND NOT EXISTS (SELECT 1 FROM update_operations u " +
            "WHERE u.recovery_point_id = recovery_points.recovery_point_id AND u.result = 'RUNNING') " +
            "AND NOT EXISTS (SELECT 1 FROM rollback_operations r WHERE " +
            "(r.selected_recovery_point_id = recovery_points.recovery_point_id OR r.pre_rollback_recovery_point_id = recovery_points.recovery_point_id) " +
            "AND r.result = 'RUNNING')",
        arrayOf(now.toString()),
        null,
        null,
        "expires_at ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecoveryPoint()) } }

    fun deleteConfirmedRecoveryPoint(id: RecoveryPointId): Boolean = writableDatabase.update(
        "recovery_points",
        ContentValues().apply {
            // Keep an audit tombstone while terminal update_operations retains its
            // ON DELETE RESTRICT reference. manager_owned = 0 hides the physically
            // deleted artifact from active recovery queries and future cleanup.
            put("manager_owned", 0)
            put("pinned", 0)
            put("in_use", 0)
            putNull("last_error")
        },
        "recovery_point_id = ? AND manager_owned = 1 AND pinned = 0 AND in_use = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM update_operations u WHERE u.recovery_point_id = recovery_points.recovery_point_id AND u.result = 'RUNNING') " +
            "AND NOT EXISTS (SELECT 1 FROM rollback_operations r WHERE " +
            "(r.selected_recovery_point_id = recovery_points.recovery_point_id OR r.pre_rollback_recovery_point_id = recovery_points.recovery_point_id) " +
            "AND r.result = 'RUNNING')",
        arrayOf(id.value),
    ) == 1

    companion object {
        private const val DATABASE_NAME = "risu-manager.db"
        private const val DATABASE_VERSION = 6
    }
}

/**
 * Updates recovery metadata without REPLACE semantics. SQLite implements REPLACE
 * as DELETE + INSERT, which violates update_operations' ON DELETE RESTRICT FK as
 * soon as an operation references the recovery point.
 */
private fun upsertRecoveryPoint(db: SQLiteDatabase, recoveryPoint: RecoveryPoint) {
    if (
        db.update(
            "recovery_points",
            recoveryPoint.toValues(),
            "recovery_point_id = ?",
            arrayOf(recoveryPoint.id.value),
        ) == 0
    ) {
        db.insertOrThrow("recovery_points", null, recoveryPoint.toValues())
    }
}

private fun createCloneOperationsTable(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS clone_operations (
            operation_id TEXT PRIMARY KEY NOT NULL,
            source_instance_id TEXT NOT NULL,
            target_instance_id TEXT NOT NULL,
            target_display_name TEXT NOT NULL,
            target_port INTEGER NOT NULL,
            source_revision TEXT NOT NULL,
            source_version TEXT,
            recovery_point_id TEXT,
            body_limit_mb INTEGER NOT NULL,
            save_interval_seconds INTEGER NOT NULL,
            snapshot_interval_minutes INTEGER NOT NULL,
            snapshot_retention_count INTEGER NOT NULL,
            snapshot_capacity_mb INTEGER NOT NULL,
            image_quality INTEGER NOT NULL,
            stage TEXT NOT NULL,
            result TEXT NOT NULL,
            source_was_running INTEGER,
            source_restored INTEGER,
            helper_published INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            error_code TEXT,
            detail TEXT
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS clone_target_identity ON clone_operations(target_instance_id)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS active_clone_source ON clone_operations(source_instance_id) WHERE result = 'RUNNING'")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS active_clone_port ON clone_operations(target_port) WHERE result = 'RUNNING'")
}

private fun createRollbackOperationsTable(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS rollback_operations (
            operation_id TEXT PRIMARY KEY NOT NULL,
            instance_id TEXT NOT NULL,
            variant_id TEXT NOT NULL,
            layout_version INTEGER NOT NULL,
            selected_recovery_point_id TEXT NOT NULL,
            pre_rollback_recovery_point_id TEXT NOT NULL,
            current_revision TEXT NOT NULL,
            current_version TEXT,
            target_revision TEXT NOT NULL,
            target_version TEXT,
            retention_seconds INTEGER NOT NULL,
            stage TEXT NOT NULL,
            result TEXT NOT NULL,
            source_was_running INTEGER,
            current_state_restored INTEGER,
            active_revision TEXT,
            active_version TEXT,
            launch_confirmed INTEGER NOT NULL DEFAULT 0,
            health_confirmed INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            completed_at INTEGER,
            error_code TEXT,
            detail TEXT,
            FOREIGN KEY(selected_recovery_point_id) REFERENCES recovery_points(recovery_point_id) ON DELETE RESTRICT,
            FOREIGN KEY(pre_rollback_recovery_point_id) REFERENCES recovery_points(recovery_point_id) ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS active_rollback_instance ON rollback_operations(instance_id) WHERE result = 'RUNNING'")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS rollback_pre_recovery_identity ON rollback_operations(pre_rollback_recovery_point_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS rollback_instance_created ON rollback_operations(instance_id, created_at DESC)")
}

private fun createRollbackEventsTable(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS rollback_events (
            event_id INTEGER PRIMARY KEY AUTOINCREMENT,
            operation_id TEXT NOT NULL,
            instance_id TEXT NOT NULL,
            stage TEXT NOT NULL,
            recorded_at INTEGER NOT NULL,
            result TEXT NOT NULL,
            error_code TEXT,
            FOREIGN KEY(operation_id) REFERENCES rollback_operations(operation_id) ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS rollback_events_instance_time ON rollback_events(instance_id, recorded_at DESC)")
}

private fun createRecoveryPointsTable(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS recovery_points (
            recovery_point_id TEXT PRIMARY KEY NOT NULL,
            instance_id TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            server_revision TEXT,
            server_version TEXT,
            data_digest_sha256 TEXT NOT NULL,
            configuration_digest_sha256 TEXT NOT NULL,
            manifest_sha256 TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            format_version INTEGER NOT NULL,
            variant_id TEXT NOT NULL,
            layout_version INTEGER NOT NULL,
            completed_at INTEGER,
            expires_at INTEGER,
            retention_seconds INTEGER NOT NULL,
            pinned INTEGER NOT NULL DEFAULT 0,
            in_use INTEGER NOT NULL DEFAULT 0,
            restored INTEGER,
            source_was_running INTEGER,
            restoration_state TEXT NOT NULL,
            state TEXT NOT NULL,
            manager_owned INTEGER NOT NULL DEFAULT 1,
            last_error TEXT
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS recovery_instance_created ON recovery_points(instance_id, created_at DESC)")
    db.execSQL("CREATE INDEX IF NOT EXISTS recovery_expiration ON recovery_points(expires_at) WHERE expires_at IS NOT NULL")
}

private fun createUpdateOperationsTable(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS update_operations (
            operation_id TEXT PRIMARY KEY NOT NULL,
            instance_id TEXT NOT NULL,
            variant_id TEXT NOT NULL,
            layout_version INTEGER NOT NULL,
            target_revision TEXT NOT NULL,
            target_version TEXT,
            current_revision TEXT NOT NULL,
            current_version TEXT,
            recovery_point_id TEXT NOT NULL,
            retention_seconds INTEGER NOT NULL,
            stage TEXT NOT NULL,
            result TEXT NOT NULL,
            source_was_running INTEGER,
            restoration_state TEXT NOT NULL,
            active_revision TEXT,
            launch_confirmed INTEGER NOT NULL DEFAULT 0,
            health_confirmed INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            completed_at INTEGER,
            error_code TEXT,
            detail TEXT,
            FOREIGN KEY(recovery_point_id) REFERENCES recovery_points(recovery_point_id) ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS active_update_instance ON update_operations(instance_id) WHERE result = 'RUNNING'")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS update_recovery_identity ON update_operations(recovery_point_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS update_instance_created ON update_operations(instance_id, created_at DESC)")
}

private fun createInstanceServerConfigsTable(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS instance_server_configs (
            instance_id TEXT PRIMARY KEY NOT NULL REFERENCES instances(instance_id) ON DELETE CASCADE,
            port INTEGER NOT NULL DEFAULT 6001,
            bind_scope TEXT NOT NULL DEFAULT 'LOOPBACK',
            protocol TEXT NOT NULL DEFAULT 'HTTP',
            certificate_mode TEXT NOT NULL DEFAULT 'MANUAL',
            tls_domain TEXT,
            acme_email TEXT,
            acme_challenge TEXT NOT NULL DEFAULT 'HTTP_01',
            acme_challenge_port INTEGER NOT NULL DEFAULT 8080,
            certificate_expires_at INTEGER,
            auto_renew INTEGER NOT NULL DEFAULT 0,
            last_renew_check_at INTEGER,
            last_renew_success_at INTEGER,
            last_renew_error TEXT,
            renew_failure_count INTEGER NOT NULL DEFAULT 0,
            body_limit_mb INTEGER NOT NULL DEFAULT 100,
            save_interval_seconds INTEGER NOT NULL DEFAULT 5,
            snapshot_interval_minutes INTEGER NOT NULL DEFAULT 5,
            snapshot_retention_count INTEGER NOT NULL DEFAULT 20,
            snapshot_capacity_mb INTEGER NOT NULL DEFAULT 500,
            image_quality INTEGER NOT NULL DEFAULT 85,
            apply_state TEXT NOT NULL DEFAULT 'UNKNOWN',
            applied_at INTEGER,
            last_error TEXT,
            applied_port INTEGER,
            applied_bind_scope TEXT,
            applied_protocol TEXT,
            applied_body_limit_mb INTEGER,
            applied_save_interval_seconds INTEGER,
            applied_snapshot_interval_minutes INTEGER,
            applied_snapshot_retention_count INTEGER,
            applied_snapshot_capacity_mb INTEGER
        )
        """.trimIndent(),
    )
}

private fun addTlsColumns(db: SQLiteDatabase) {
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN certificate_mode TEXT NOT NULL DEFAULT 'MANUAL'")
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN tls_domain TEXT")
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN acme_email TEXT")
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN acme_challenge TEXT NOT NULL DEFAULT 'HTTP_01'")
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN acme_challenge_port INTEGER NOT NULL DEFAULT 8080")
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN certificate_expires_at INTEGER")
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN auto_renew INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN last_renew_check_at INTEGER")
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN last_renew_success_at INTEGER")
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN last_renew_error TEXT")
    db.execSQL("ALTER TABLE instance_server_configs ADD COLUMN renew_failure_count INTEGER NOT NULL DEFAULT 0")
}

private fun RisuInstance.toValues() = ContentValues().apply {
    put("instance_id", instanceId)
    putNullable("display_name", displayName)
    put("variant_id", variantId)
    put("channel_id", channelId)
    put("runtime_type", runtimeType.name)
    put("repository", repository)
    putNullable("installed_revision", installedRevision)
    putNullable("previous_revision", previousRevision)
    putNullable("installed_version", installedVersion)
    putNullable("latest_revision", latestRevision)
    put("port", port)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
    put("last_known_status", lastKnownStatus.name)
}

private fun CloneOperation.toValues() = ContentValues().apply {
    put("operation_id", operationId)
    put("source_instance_id", sourceInstanceId)
    put("target_instance_id", targetInstanceId)
    put("target_display_name", targetDisplayName)
    put("target_port", targetPort)
    put("source_revision", sourceRevision)
    putNullable("source_version", sourceVersion)
    putNullable("recovery_point_id", recoveryPointId?.value)
    put("body_limit_mb", bodyLimitMb)
    put("save_interval_seconds", saveIntervalSeconds)
    put("snapshot_interval_minutes", snapshotIntervalMinutes)
    put("snapshot_retention_count", snapshotRetentionCount)
    put("snapshot_capacity_mb", snapshotCapacityMb)
    put("image_quality", imageQuality)
    put("stage", stage.name)
    put("result", result.name)
    if (sourceWasRunning == null) putNull("source_was_running") else put("source_was_running", if (sourceWasRunning) 1 else 0)
    if (sourceRestored == null) putNull("source_restored") else put("source_restored", if (sourceRestored) 1 else 0)
    put("helper_published", if (helperPublished) 1 else 0)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
    putNullable("error_code", errorCode)
    putNullable("detail", detail)
}

private fun RollbackOperation.toValues() = ContentValues().apply {
    put("operation_id", operationId)
    put("instance_id", instanceId)
    put("variant_id", variantId)
    put("layout_version", layoutVersion)
    put("selected_recovery_point_id", selectedRecoveryPointId.value)
    put("pre_rollback_recovery_point_id", preRollbackRecoveryPointId.value)
    put("current_revision", currentRevision)
    putNullable("current_version", currentVersion)
    put("target_revision", targetRevision)
    putNullable("target_version", targetVersion)
    put("retention_seconds", retentionSeconds)
    put("stage", stage.name)
    put("result", result.name)
    if (sourceWasRunning == null) putNull("source_was_running") else put("source_was_running", if (sourceWasRunning) 1 else 0)
    if (currentStateRestored == null) putNull("current_state_restored") else put("current_state_restored", if (currentStateRestored) 1 else 0)
    putNullable("active_revision", activeRevision)
    putNullable("active_version", activeVersion)
    put("launch_confirmed", if (launchConfirmed) 1 else 0)
    put("health_confirmed", if (healthConfirmed) 1 else 0)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
    if (completedAt == null) putNull("completed_at") else put("completed_at", completedAt)
    putNullable("error_code", errorCode)
    putNullable("detail", detail)
}

private fun RollbackOperation.toOperationRecord() = OperationRecord(
    operationId = operationId,
    instanceId = instanceId,
    type = OperationType.ROLLBACK,
    startedAt = createdAt,
    endedAt = completedAt,
    result = when (result) {
        RollbackResult.RUNNING -> OperationResultKind.RUNNING
        RollbackResult.SUCCESS -> OperationResultKind.SUCCESS
        RollbackResult.FAILURE -> OperationResultKind.FAILURE
    },
    stage = stage.name,
    detail = detail,
    fromRevision = currentRevision,
    toRevision = targetRevision,
    errorCode = errorCode,
)

private fun RollbackOperation.toEventValues() = ContentValues().apply {
    put("operation_id", operationId)
    put("instance_id", instanceId)
    put("stage", stage.name)
    put("recorded_at", updatedAt)
    put("result", result.name)
    putNullable("error_code", errorCode)
}

private fun appendRollbackEventIfChanged(db: SQLiteDatabase, old: RollbackOperation, new: RollbackOperation) {
    if (old.stage != new.stage || old.result != new.result || old.errorCode != new.errorCode) {
        db.insertOrThrow("rollback_events", null, new.toEventValues())
    }
}

private fun RollbackOperation.requireSameIdentity(other: RollbackOperation) {
    check(operationId == other.operationId && instanceId == other.instanceId) { "Rollback operation identity changed" }
    check(variantId == other.variantId && layoutVersion == other.layoutVersion) { "Rollback compatibility identity changed" }
    check(selectedRecoveryPointId == other.selectedRecoveryPointId && preRollbackRecoveryPointId == other.preRollbackRecoveryPointId) {
        "Rollback recovery identity changed"
    }
    check(currentRevision.equals(other.currentRevision, ignoreCase = true)) { "Rollback current revision changed" }
    check(targetRevision.equals(other.targetRevision, ignoreCase = true)) { "Rollback target revision changed" }
    check(retentionSeconds == other.retentionSeconds && createdAt == other.createdAt) { "Rollback reservation changed" }
}

private fun RecoveryPoint.toValues() = ContentValues().apply {
    put("recovery_point_id", id.value)
    put("instance_id", instanceId)
    put("created_at", createdAt)
    putNullable("server_revision", serverRevision)
    putNullable("server_version", serverVersion)
    put("data_digest_sha256", dataDigestSha256)
    put("configuration_digest_sha256", configurationDigestSha256)
    put("manifest_sha256", manifestSha256)
    put("size_bytes", sizeBytes)
    put("format_version", formatVersion)
    put("variant_id", variantId)
    put("layout_version", layoutVersion)
    if (completedAt == null) putNull("completed_at") else put("completed_at", completedAt)
    if (expiresAt == null) putNull("expires_at") else put("expires_at", expiresAt)
    put("retention_seconds", retentionSeconds)
    put("pinned", if (pinned) 1 else 0)
    put("in_use", if (inUse) 1 else 0)
    if (restored == null) putNull("restored") else put("restored", if (restored) 1 else 0)
    if (sourceWasRunning == null) putNull("source_was_running") else put("source_was_running", if (sourceWasRunning) 1 else 0)
    put("restoration_state", restorationState.name)
    put("state", state.name)
    put("manager_owned", if (managerOwned) 1 else 0)
    putNullable("last_error", lastError)
}

private fun UpdateOperation.toValues() = ContentValues().apply {
    put("operation_id", operationId)
    put("instance_id", instanceId)
    put("variant_id", variantId)
    put("layout_version", layoutVersion)
    put("target_revision", targetRevision)
    putNullable("target_version", targetVersion)
    put("current_revision", currentRevision)
    putNullable("current_version", currentVersion)
    put("recovery_point_id", recoveryPointId.value)
    put("retention_seconds", retentionSeconds)
    put("stage", stage.name)
    put("result", result.name)
    if (sourceWasRunning == null) putNull("source_was_running") else put("source_was_running", if (sourceWasRunning) 1 else 0)
    put("restoration_state", restorationState.name)
    putNullable("active_revision", activeRevision)
    put("launch_confirmed", if (launchConfirmed) 1 else 0)
    put("health_confirmed", if (healthConfirmed) 1 else 0)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
    if (completedAt == null) putNull("completed_at") else put("completed_at", completedAt)
    putNullable("error_code", errorCode)
    putNullable("detail", detail)
}

private fun UpdateOperation.toOperationRecord() = OperationRecord(
    operationId = operationId,
    instanceId = instanceId,
    type = OperationType.UPDATE,
    startedAt = createdAt,
    endedAt = completedAt,
    result = when (result) {
        UpdateResult.RUNNING -> OperationResultKind.RUNNING
        UpdateResult.SUCCESS -> OperationResultKind.SUCCESS
        UpdateResult.FAILURE -> OperationResultKind.FAILURE
    },
    stage = stage.name,
    detail = detail,
    fromRevision = currentRevision,
    toRevision = targetRevision,
    errorCode = errorCode,
)

private fun UpdateOperation.requireSameIdentity(other: UpdateOperation) {
    check(operationId == other.operationId && instanceId == other.instanceId) { "Update operation identity changed" }
    check(variantId == other.variantId && layoutVersion == other.layoutVersion) { "Update compatibility identity changed" }
    check(currentRevision.equals(other.currentRevision, ignoreCase = true)) { "Update source revision changed" }
    check(targetRevision.equals(other.targetRevision, ignoreCase = true)) { "Update target revision changed" }
    check(recoveryPointId == other.recoveryPointId && retentionSeconds == other.retentionSeconds) {
        "Update recovery identity changed"
    }
    check(createdAt == other.createdAt) { "Update creation time changed" }
}

private fun OperationRecord.toValues() = ContentValues().apply {
    put("operation_id", operationId)
    put("instance_id", instanceId)
    put("type", type.name)
    put("started_at", startedAt)
    if (endedAt == null) putNull("ended_at") else put("ended_at", endedAt)
    put("result", result.name)
    putNullable("stage", stage)
    putNullable("detail", detail)
    putNullable("from_revision", fromRevision)
    putNullable("to_revision", toRevision)
    putNullable("error_code", errorCode)
}

private fun BackupMetadata.toValues() = ContentValues().apply {
    put("id", id)
    put("instance_id", instanceId)
    put("created_at", createdAt)
    put("reason", reason.name)
    putNullable("source_revision", sourceRevision)
    putNullable("app_version", appVersion)
    put("size", size)
    put("format_version", formatVersion)
}

private fun InstanceServerConfig.toValues() = ContentValues().apply {
    put("instance_id", instanceId)
    put("port", port)
    put("bind_scope", bindScope.name)
    put("protocol", protocol.name)
    put("certificate_mode", certificateMode.name)
    putNullable("tls_domain", tlsDomain)
    putNullable("acme_email", acmeEmail)
    put("acme_challenge", acmeChallenge.name)
    put("acme_challenge_port", acmeChallengePort)
    if (certificateExpiresAt == null) putNull("certificate_expires_at") else put("certificate_expires_at", certificateExpiresAt)
    put("auto_renew", if (autoRenew) 1 else 0)
    if (lastRenewCheckAt == null) putNull("last_renew_check_at") else put("last_renew_check_at", lastRenewCheckAt)
    if (lastRenewSuccessAt == null) putNull("last_renew_success_at") else put("last_renew_success_at", lastRenewSuccessAt)
    putNullable("last_renew_error", lastRenewError)
    put("renew_failure_count", renewFailureCount)
    put("body_limit_mb", bodyLimitMb)
    put("save_interval_seconds", saveIntervalSeconds)
    put("snapshot_interval_minutes", snapshotIntervalMinutes)
    put("snapshot_retention_count", snapshotRetentionCount)
    put("snapshot_capacity_mb", snapshotCapacityMb)
    put("image_quality", imageQuality)
    put("apply_state", applyState.name)
    if (appliedAt == null) putNull("applied_at") else put("applied_at", appliedAt)
    putNullable("last_error", lastError)
    if (appliedPort == null) putNull("applied_port") else put("applied_port", appliedPort)
    putNullable("applied_bind_scope", appliedBindScope?.name)
    putNullable("applied_protocol", appliedProtocol?.name)
    if (appliedRequestBodyLimitMb == null) putNull("applied_body_limit_mb") else put("applied_body_limit_mb", appliedRequestBodyLimitMb)
    if (appliedSaveIntervalSeconds == null) putNull("applied_save_interval_seconds") else put("applied_save_interval_seconds", appliedSaveIntervalSeconds)
    if (appliedSnapshotIntervalMinutes == null) putNull("applied_snapshot_interval_minutes") else put("applied_snapshot_interval_minutes", appliedSnapshotIntervalMinutes)
    if (appliedSnapshotRetentionCount == null) putNull("applied_snapshot_retention_count") else put("applied_snapshot_retention_count", appliedSnapshotRetentionCount)
    if (appliedSnapshotCapacityMb == null) putNull("applied_snapshot_capacity_mb") else put("applied_snapshot_capacity_mb", appliedSnapshotCapacityMb)
}

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun Cursor.toInstance() = RisuInstance(
    instanceId = string("instance_id"),
    displayName = nullableString("display_name"),
    variantId = string("variant_id"),
    channelId = string("channel_id"),
    runtimeType = RuntimeType.valueOf(string("runtime_type")),
    repository = string("repository"),
    installedRevision = nullableString("installed_revision"),
    previousRevision = nullableString("previous_revision"),
    installedVersion = nullableString("installed_version"),
    latestRevision = nullableString("latest_revision"),
    port = int("port"),
    createdAt = long("created_at"),
    updatedAt = long("updated_at"),
    lastKnownStatus = RuntimeState.valueOf(string("last_known_status")),
)

private fun Cursor.toCloneOperation() = CloneOperation(
    operationId = string("operation_id"),
    sourceInstanceId = string("source_instance_id"),
    targetInstanceId = string("target_instance_id"),
    targetDisplayName = string("target_display_name"),
    targetPort = int("target_port"),
    sourceRevision = string("source_revision"),
    sourceVersion = nullableString("source_version"),
    recoveryPointId = nullableString("recovery_point_id")?.let(::RecoveryPointId),
    bodyLimitMb = int("body_limit_mb"),
    saveIntervalSeconds = int("save_interval_seconds"),
    snapshotIntervalMinutes = int("snapshot_interval_minutes"),
    snapshotRetentionCount = int("snapshot_retention_count"),
    snapshotCapacityMb = int("snapshot_capacity_mb"),
    imageQuality = int("image_quality"),
    stage = CloneStage.valueOf(string("stage")),
    result = CloneResult.valueOf(string("result")),
    sourceWasRunning = nullableInt("source_was_running")?.let { it != 0 },
    sourceRestored = nullableInt("source_restored")?.let { it != 0 },
    helperPublished = int("helper_published") != 0,
    createdAt = long("created_at"),
    updatedAt = long("updated_at"),
    errorCode = nullableString("error_code"),
    detail = nullableString("detail"),
)

private fun Cursor.toRollbackOperation() = RollbackOperation(
    operationId = string("operation_id"),
    instanceId = string("instance_id"),
    variantId = string("variant_id"),
    layoutVersion = int("layout_version"),
    selectedRecoveryPointId = RecoveryPointId(string("selected_recovery_point_id")),
    preRollbackRecoveryPointId = RecoveryPointId(string("pre_rollback_recovery_point_id")),
    currentRevision = string("current_revision"),
    currentVersion = nullableString("current_version"),
    targetRevision = string("target_revision"),
    targetVersion = nullableString("target_version"),
    retentionSeconds = long("retention_seconds"),
    stage = RollbackStage.valueOf(string("stage")),
    result = RollbackResult.valueOf(string("result")),
    sourceWasRunning = nullableInt("source_was_running")?.let { it != 0 },
    currentStateRestored = nullableInt("current_state_restored")?.let { it != 0 },
    activeRevision = nullableString("active_revision"),
    activeVersion = nullableString("active_version"),
    launchConfirmed = int("launch_confirmed") != 0,
    healthConfirmed = int("health_confirmed") != 0,
    createdAt = long("created_at"),
    updatedAt = long("updated_at"),
    completedAt = nullableLong("completed_at"),
    errorCode = nullableString("error_code"),
    detail = nullableString("detail"),
)

private fun Cursor.toRollbackEvent() = RollbackEvent(
    operationId = string("operation_id"),
    instanceId = string("instance_id"),
    stage = RollbackStage.valueOf(string("stage")),
    recordedAt = long("recorded_at"),
    result = RollbackResult.valueOf(string("result")),
    errorCode = nullableString("error_code"),
)

private fun Cursor.toRecoveryPoint() = RecoveryPoint(
    id = RecoveryPointId(string("recovery_point_id")),
    instanceId = string("instance_id"),
    createdAt = long("created_at"),
    serverRevision = nullableString("server_revision"),
    serverVersion = nullableString("server_version"),
    dataDigestSha256 = string("data_digest_sha256"),
    configurationDigestSha256 = string("configuration_digest_sha256"),
    manifestSha256 = string("manifest_sha256"),
    sizeBytes = long("size_bytes"),
    formatVersion = int("format_version"),
    variantId = string("variant_id"),
    layoutVersion = int("layout_version"),
    completedAt = nullableLong("completed_at"),
    expiresAt = nullableLong("expires_at"),
    retentionSeconds = long("retention_seconds"),
    pinned = int("pinned") != 0,
    inUse = int("in_use") != 0,
    restored = nullableInt("restored")?.let { it != 0 },
    sourceWasRunning = nullableInt("source_was_running")?.let { it != 0 },
    restorationState = RestorationState.valueOf(string("restoration_state")),
    state = RecoveryPointState.valueOf(string("state")),
    managerOwned = int("manager_owned") != 0,
    lastError = nullableString("last_error"),
)

private fun Cursor.toUpdateOperation() = UpdateOperation(
    operationId = string("operation_id"),
    instanceId = string("instance_id"),
    variantId = string("variant_id"),
    layoutVersion = int("layout_version"),
    targetRevision = string("target_revision"),
    targetVersion = nullableString("target_version"),
    currentRevision = string("current_revision"),
    currentVersion = nullableString("current_version"),
    recoveryPointId = RecoveryPointId(string("recovery_point_id")),
    retentionSeconds = long("retention_seconds"),
    stage = UpdateStage.valueOf(string("stage")),
    result = UpdateResult.valueOf(string("result")),
    sourceWasRunning = nullableInt("source_was_running")?.let { it != 0 },
    restorationState = RestorationState.valueOf(string("restoration_state")),
    activeRevision = nullableString("active_revision"),
    launchConfirmed = int("launch_confirmed") != 0,
    healthConfirmed = int("health_confirmed") != 0,
    createdAt = long("created_at"),
    updatedAt = long("updated_at"),
    completedAt = nullableLong("completed_at"),
    errorCode = nullableString("error_code"),
    detail = nullableString("detail"),
)

private fun Cursor.toOperation() = OperationRecord(
    operationId = string("operation_id"),
    instanceId = string("instance_id"),
    type = OperationType.valueOf(string("type")),
    startedAt = long("started_at"),
    endedAt = nullableLong("ended_at"),
    result = OperationResultKind.valueOf(string("result")),
    stage = nullableString("stage"),
    detail = nullableString("detail"),
    fromRevision = nullableString("from_revision"),
    toRevision = nullableString("to_revision"),
    errorCode = nullableString("error_code"),
)

private fun Cursor.toBackup() = BackupMetadata(
    id = string("id"),
    instanceId = string("instance_id"),
    createdAt = long("created_at"),
    reason = BackupReason.valueOf(string("reason")),
    sourceRevision = nullableString("source_revision"),
    appVersion = nullableString("app_version"),
    size = long("size"),
    formatVersion = int("format_version"),
)

private fun Cursor.toServerConfig() = InstanceServerConfig(
    instanceId = string("instance_id"),
    port = int("port"),
    bindScope = BindScope.valueOf(string("bind_scope")),
    protocol = ServerProtocol.valueOf(string("protocol")),
    certificateMode = CertificateMode.valueOf(string("certificate_mode")),
    tlsDomain = nullableString("tls_domain"),
    acmeEmail = nullableString("acme_email"),
    acmeChallenge = AcmeChallenge.valueOf(string("acme_challenge")),
    acmeChallengePort = int("acme_challenge_port"),
    certificateExpiresAt = nullableLong("certificate_expires_at"),
    autoRenew = int("auto_renew") != 0,
    lastRenewCheckAt = nullableLong("last_renew_check_at"),
    lastRenewSuccessAt = nullableLong("last_renew_success_at"),
    lastRenewError = nullableString("last_renew_error"),
    renewFailureCount = int("renew_failure_count"),
    bodyLimitMb = int("body_limit_mb"),
    saveIntervalSeconds = int("save_interval_seconds"),
    snapshotIntervalMinutes = int("snapshot_interval_minutes"),
    snapshotRetentionCount = int("snapshot_retention_count"),
    snapshotCapacityMb = int("snapshot_capacity_mb"),
    imageQuality = int("image_quality"),
    applyState = ConfigApplyState.valueOf(string("apply_state")),
    appliedAt = nullableLong("applied_at"),
    lastError = nullableString("last_error"),
    appliedPort = nullableInt("applied_port"),
    appliedBindScope = nullableString("applied_bind_scope")?.let(BindScope::valueOf),
    appliedProtocol = nullableString("applied_protocol")?.let(ServerProtocol::valueOf),
    appliedRequestBodyLimitMb = nullableInt("applied_body_limit_mb"),
    appliedSaveIntervalSeconds = nullableInt("applied_save_interval_seconds"),
    appliedSnapshotIntervalMinutes = nullableInt("applied_snapshot_interval_minutes"),
    appliedSnapshotRetentionCount = nullableInt("applied_snapshot_retention_count"),
    appliedSnapshotCapacityMb = nullableInt("applied_snapshot_capacity_mb"),
)

private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
private fun Cursor.nullableString(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
private fun Cursor.nullableLong(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
private fun Cursor.nullableInt(column: String): Int? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }

private fun SQLiteDatabase.hasActiveRollback(instanceId: String): Boolean = query(
    "rollback_operations", arrayOf("operation_id"), "instance_id = ? AND result = 'RUNNING'",
    arrayOf(instanceId), null, null, null, "1",
).use { it.moveToFirst() }

private fun SQLiteDatabase.hasActiveInstanceOperation(instanceId: String): Boolean =
    query("clone_operations", arrayOf("operation_id"), "source_instance_id = ? AND result = 'RUNNING'", arrayOf(instanceId), null, null, null, "1")
        .use { it.moveToFirst() } ||
        query("update_operations", arrayOf("operation_id"), "instance_id = ? AND result = 'RUNNING'", arrayOf(instanceId), null, null, null, "1")
            .use { it.moveToFirst() } ||
        hasActiveRollback(instanceId) ||
        query("operations", arrayOf("operation_id"), "instance_id = ? AND result = 'RUNNING'", arrayOf(instanceId), null, null, null, "1")
            .use { it.moveToFirst() }

private fun SQLiteDatabase.usedPorts(): Set<Int> = buildSet {
    query("instances", arrayOf("port"), null, null, null, null, null).use { while (it.moveToNext()) add(it.getInt(0)) }
    query("instance_server_configs", arrayOf("port"), null, null, null, null, null).use { while (it.moveToNext()) add(it.getInt(0)) }
    query("clone_operations", arrayOf("target_port"), "result = 'RUNNING'", null, null, null, null).use {
        while (it.moveToNext()) add(it.getInt(0))
    }
}

private fun isLoopbackPortAvailable(port: Int): Boolean = runCatching {
    java.net.ServerSocket().use { socket ->
        socket.reuseAddress = false
        socket.bind(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port))
    }
}.isSuccess
