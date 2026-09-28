package io.github.risumanager.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.AcmeChallenge
import io.github.risumanager.domain.model.BindScope
import io.github.risumanager.domain.model.CertificateMode
import io.github.risumanager.domain.model.ConfigApplyState
import io.github.risumanager.domain.model.CloneStage
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.RestorationState
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.model.ServerProtocol
import io.github.risumanager.domain.model.UpdateOperation
import io.github.risumanager.domain.model.UpdateResult
import io.github.risumanager.domain.model.UpdateStage
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RisuDatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun resetDatabase() {
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @After fun cleanUp() {
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test fun upsertExistingInstancePreservesServerSettingsAndAppliedState() {
        val original = testInstance(port = 6123)
        val updated = original.copy(port = 6234, updatedAt = 2)
        val settings = InstanceServerConfig(
            instanceId = original.instanceId,
            port = 6345,
            bindScope = BindScope.LOCAL_NETWORK,
            protocol = ServerProtocol.HTTPS,
            bodyLimitMb = 256,
            saveIntervalSeconds = 12,
            snapshotIntervalMinutes = 30,
            snapshotRetentionCount = 42,
            snapshotCapacityMb = 2048,
            imageQuality = 73,
            applyState = ConfigApplyState.APPLIED,
            appliedAt = 123456789L,
            lastError = "previous error",
            appliedPort = 6345,
            appliedBindScope = BindScope.LOCAL_NETWORK,
            appliedProtocol = ServerProtocol.HTTPS,
            appliedRequestBodyLimitMb = 256,
            appliedSaveIntervalSeconds = 12,
            appliedSnapshotIntervalMinutes = 30,
            appliedSnapshotRetentionCount = 42,
            appliedSnapshotCapacityMb = 2048,
        )

        RisuDatabase(context, TEST_DATABASE_NAME).use { manager ->
            manager.upsertInstance(original)
            manager.upsertServerConfig(settings)
            manager.upsertInstance(updated)

            assertThat(manager.instance(original.instanceId)).isEqualTo(updated)
            assertThat(manager.serverConfig(original.instanceId)).isEqualTo(settings)
        }
    }

    @Test fun upsertNewInstanceInitializesServerConfigFromInstancePort() {
        val instance = testInstance(instanceId = "pocketrisu-new", port = 6456)

        RisuDatabase(context, TEST_DATABASE_NAME).use { manager ->
            manager.upsertInstance(instance)

            assertThat(manager.serverConfig(instance.instanceId)?.port).isEqualTo(instance.port)
        }
    }

    @Test fun versionOneInstancesReceiveSafeServerDefaults() {
        val path = context.getDatabasePath(TEST_DATABASE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """CREATE TABLE instances (
                    instance_id TEXT PRIMARY KEY NOT NULL, variant_id TEXT NOT NULL,
                    channel_id TEXT NOT NULL, runtime_type TEXT NOT NULL, repository TEXT NOT NULL,
                    installed_revision TEXT, previous_revision TEXT, installed_version TEXT,
                    latest_revision TEXT, port INTEGER NOT NULL, created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL, last_known_status TEXT NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("INSERT INTO instances VALUES ('pocketrisu-old','pocketrisu','stable','TERMUX_NODE','PocketRisu/PocketRisu',NULL,NULL,NULL,NULL,6457,1,1,'STOPPED')")
            db.version = 1
        }

        RisuDatabase(context, TEST_DATABASE_NAME).use { manager ->
            val config = manager.serverConfig("pocketrisu-old")
            assertThat(manager.readableDatabase.version).isEqualTo(6)
            assertThat(config).isNotNull()
            assertThat(config!!.port).isEqualTo(6457)
            assertThat(config.bodyLimitMb).isEqualTo(100)
            assertThat(config.saveIntervalSeconds).isEqualTo(5)
            assertThat(config.applyState.name).isEqualTo("UNKNOWN")
            assertThat(config.certificateMode).isEqualTo(CertificateMode.MANUAL)
            assertThat(config.acmeChallenge).isEqualTo(AcmeChallenge.HTTP_01)
            assertThat(config.acmeChallengePort).isEqualTo(8080)
            assertThat(config.autoRenew).isFalse()
        }
    }

    @Test fun letsEncryptFieldsRoundTripThroughVersionThreeSchema() {
        val instance = testInstance(instanceId = "pocketrisu-le", port = 6001)
        val settings = InstanceServerConfig(
            instanceId = instance.instanceId,
            protocol = ServerProtocol.HTTPS,
            certificateMode = CertificateMode.LETS_ENCRYPT,
            tlsDomain = "risu.example.com",
            acmeEmail = "admin@risu.example.com",
            acmeChallenge = AcmeChallenge.HTTP_01,
            acmeChallengePort = 8080,
            certificateExpiresAt = 1_700_000_000_000L,
            autoRenew = true,
            lastRenewCheckAt = 1_600_000_000_000L,
            lastRenewSuccessAt = 1_600_000_000_100L,
            lastRenewError = "",
            renewFailureCount = 0,
        )
        RisuDatabase(context, TEST_DATABASE_NAME).use { manager ->
            manager.upsertInstance(instance)
            manager.upsertServerConfig(settings)
            assertThat(manager.serverConfig(instance.instanceId)).isEqualTo(settings)
            assertThat(manager.serverConfigs()).containsExactly(settings)
        }
    }

    @Test fun versionTwoManualHttpsSettingsGainSafeAcmeDefaultsWithoutChangingProtocol() {
        val path = context.getDatabasePath(TEST_DATABASE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """CREATE TABLE instances (
                    instance_id TEXT PRIMARY KEY NOT NULL, variant_id TEXT NOT NULL,
                    channel_id TEXT NOT NULL, runtime_type TEXT NOT NULL, repository TEXT NOT NULL,
                    installed_revision TEXT, previous_revision TEXT, installed_version TEXT,
                    latest_revision TEXT, port INTEGER NOT NULL, created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL, last_known_status TEXT NOT NULL
                )""".trimIndent(),
            )
            db.execSQL(
                """CREATE TABLE instance_server_configs (
                    instance_id TEXT PRIMARY KEY NOT NULL REFERENCES instances(instance_id) ON DELETE CASCADE,
                    port INTEGER NOT NULL DEFAULT 6001, bind_scope TEXT NOT NULL DEFAULT 'LOOPBACK',
                    protocol TEXT NOT NULL DEFAULT 'HTTP', body_limit_mb INTEGER NOT NULL DEFAULT 100,
                    save_interval_seconds INTEGER NOT NULL DEFAULT 5, snapshot_interval_minutes INTEGER NOT NULL DEFAULT 5,
                    snapshot_retention_count INTEGER NOT NULL DEFAULT 20, snapshot_capacity_mb INTEGER NOT NULL DEFAULT 500,
                    image_quality INTEGER NOT NULL DEFAULT 85, apply_state TEXT NOT NULL DEFAULT 'UNKNOWN',
                    applied_at INTEGER, last_error TEXT, applied_port INTEGER, applied_bind_scope TEXT,
                    applied_protocol TEXT, applied_body_limit_mb INTEGER, applied_save_interval_seconds INTEGER,
                    applied_snapshot_interval_minutes INTEGER, applied_snapshot_retention_count INTEGER,
                    applied_snapshot_capacity_mb INTEGER
                )""".trimIndent(),
            )
            db.execSQL("INSERT INTO instances VALUES ('pocketrisu-v2','pocketrisu','stable','TERMUX_NODE','PocketRisu/PocketRisu',NULL,NULL,NULL,NULL,6001,1,1,'STOPPED')")
            db.execSQL("INSERT INTO instance_server_configs (instance_id, protocol, applied_protocol) VALUES ('pocketrisu-v2','HTTPS','HTTPS')")
            db.version = 2
        }

        RisuDatabase(context, TEST_DATABASE_NAME).use { manager ->
            val config = manager.serverConfig("pocketrisu-v2")
            assertThat(manager.readableDatabase.version).isEqualTo(6)
            assertThat(config).isNotNull()
            assertThat(config!!.protocol).isEqualTo(ServerProtocol.HTTPS)
            assertThat(config.appliedProtocol).isEqualTo(ServerProtocol.HTTPS)
            assertThat(config.certificateMode).isEqualTo(CertificateMode.MANUAL)
            assertThat(config.tlsDomain).isNull()
            assertThat(config.autoRenew).isFalse()
            assertThat(config.renewFailureCount).isEqualTo(0)
        }
    }

    @Test fun cloneReservationAvoidsManagedPortsAndPublishesTargetAndConfigAtomically() {
        val source = testInstance(instanceId = "pocketrisu-source", port = 6001).copy(
            installedRevision = "a".repeat(40),
            installedVersion = "1.2.3",
        )
        val other = testInstance(instanceId = "pocketrisu-other", port = 6002)
        RisuDatabase(context, TEST_DATABASE_NAME) { true }.use { manager ->
            manager.upsertInstance(source)
            manager.upsertInstance(other)
            manager.upsertServerConfig(
                InstanceServerConfig(
                    instanceId = source.instanceId,
                    port = source.port,
                    bindScope = BindScope.LOCAL_NETWORK,
                    protocol = ServerProtocol.HTTPS,
                    certificateMode = CertificateMode.LETS_ENCRYPT,
                    tlsDomain = "source.example.com",
                    acmeEmail = "admin@example.com",
                    autoRenew = true,
                    bodyLimitMb = 256,
                    imageQuality = 77,
                ),
            )
            val operation = manager.prepareClone(
                "clone_12345678", source.instanceId, "pocketrisu-target", "Local copy", 10,
            )
            assertThat(operation.targetPort).isEqualTo(6003)
            assertThat(manager.instance(operation.targetInstanceId)).isNull()

            val publishable = operation.copy(
                stage = CloneStage.REGISTERING,
                helperPublished = true,
                sourceWasRunning = true,
                sourceRestored = true,
            )
            manager.upsertCloneOperation(publishable)
            val target = source.copy(
                instanceId = publishable.targetInstanceId,
                displayName = publishable.targetDisplayName,
                port = publishable.targetPort,
                previousRevision = null,
                createdAt = 20,
                updatedAt = 20,
                lastKnownStatus = RuntimeState.STOPPED,
            )
            val config = InstanceServerConfig(
                instanceId = target.instanceId,
                port = target.port,
                bindScope = BindScope.LOOPBACK,
                protocol = ServerProtocol.HTTP,
                certificateMode = CertificateMode.MANUAL,
                bodyLimitMb = operation.bodyLimitMb,
                imageQuality = operation.imageQuality,
            )
            manager.publishClone(publishable, target, config, 20)
            manager.publishClone(publishable, target, config, 21) // idempotent process-death replay

            assertThat(manager.instance(target.instanceId)).isEqualTo(target)
            assertThat(manager.serverConfig(target.instanceId)).isEqualTo(config)
            assertThat(manager.cloneOperation(operation.operationId)?.stage).isEqualTo(CloneStage.COMPLETED)
            assertThat(manager.cloneOperation(operation.operationId)?.helperPublished).isTrue()
        }
    }

    @Test fun provisionalCloneNeverAppearsAsManagedInstanceAndLocksSourceAndPortDurably() {
        val source = testInstance(instanceId = "pocketrisu-source", port = 6200).copy(installedRevision = "a".repeat(40))
        RisuDatabase(context, TEST_DATABASE_NAME) { true }.use { manager ->
            manager.upsertInstance(source)
            val first = manager.prepareClone("clone_aaaaaaaa", source.instanceId, "pocketrisu-first", "First", 1)
            assertThat(manager.instance(first.targetInstanceId)).isNull()
            assertThat(manager.activeCloneOperations()).containsExactly(first)
            assertThrows(IllegalStateException::class.java) {
                manager.prepareClone("clone_bbbbbbbb", source.instanceId, "pocketrisu-second", "Second", 2)
            }
        }
    }

    @Test fun referencedRecoveryPointUpdatesInPlaceAndPublishesSuccess() {
        RisuDatabase(context, TEST_DATABASE_NAME) { true }.use { manager ->
            val (operation, recoveryPoint) = prepareUpdate(manager)
            val verifiedPoint = recoveryPoint.copy(
                serverRevision = operation.currentRevision,
                serverVersion = operation.currentVersion,
                dataDigestSha256 = "1".repeat(64),
                configurationDigestSha256 = "2".repeat(64),
                manifestSha256 = "3".repeat(64),
                sizeBytes = 4096,
                completedAt = 20,
                inUse = true,
                state = RecoveryPointState.VERIFIED,
            )
            val verifiedOperation = operation.copy(
                stage = UpdateStage.RECOVERY_VERIFIED,
                sourceWasRunning = false,
                updatedAt = 20,
            )

            // Both calls update a recovery row already referenced by the operation.
            // REPLACE would attempt DELETE first and fail the ON DELETE RESTRICT FK.
            manager.upsertUpdateProgress(verifiedOperation, verifiedPoint)
            manager.publishUpdateSuccess(
                verifiedOperation.copy(
                    stage = UpdateStage.HEALTH_CHECKING,
                    activeRevision = verifiedOperation.targetRevision,
                    launchConfirmed = true,
                    healthConfirmed = true,
                    updatedAt = 30,
                ),
                verifiedPoint,
                now = 30,
            )

            val storedOperation = manager.updateOperation(operation.operationId)
            val storedPoint = manager.recoveryPoint(recoveryPoint.id)
            assertThat(storedOperation?.result).isEqualTo(UpdateResult.SUCCESS)
            assertThat(storedOperation?.stage).isEqualTo(UpdateStage.COMPLETED)
            assertThat(storedPoint?.state).isEqualTo(RecoveryPointState.AVAILABLE)
            assertThat(storedPoint?.inUse).isFalse()
            assertThat(storedPoint?.expiresAt).isEqualTo(60_030L)
            assertNoForeignKeyViolations(manager)
        }
    }

    @Test fun confirmedCleanupHidesReferencedRecoveryPointButKeepsTerminalJournal() {
        RisuDatabase(context, TEST_DATABASE_NAME) { true }.use { manager ->
            val (operation, recoveryPoint) = prepareSuccessfulUpdate(manager)

            assertThat(manager.expiredRecoveryPoints(60_031)).hasSize(1)
            assertThat(manager.deleteConfirmedRecoveryPoint(recoveryPoint.id)).isTrue()

            // The artifact is gone from active recovery UI/cleanup queries, but the
            // tombstone keeps the immutable terminal operation journal referentially valid.
            assertThat(manager.recoveryPoints(operation.instanceId)).isEmpty()
            assertThat(manager.expiredRecoveryPoints(60_031)).isEmpty()
            assertThat(manager.updateOperation(operation.operationId)?.result).isEqualTo(UpdateResult.SUCCESS)
            assertThat(manager.recoveryPoint(recoveryPoint.id)?.managerOwned).isFalse()
            assertThat(manager.deleteConfirmedRecoveryPoint(recoveryPoint.id)).isFalse()
            assertNoForeignKeyViolations(manager)
        }
    }

    private fun prepareUpdate(manager: RisuDatabase): Pair<UpdateOperation, RecoveryPoint> {
        val currentRevision = "a".repeat(40)
        val targetRevision = "b".repeat(40)
        val instance = testInstance(instanceId = "pocketrisu-update", port = 6300).copy(
            installedRevision = currentRevision,
            installedVersion = "1.11.2",
        )
        val pointId = RecoveryPointId("recovery_update_test")
        val operation = UpdateOperation(
            operationId = "update_test",
            instanceId = instance.instanceId,
            variantId = "pocketrisu",
            layoutVersion = 1,
            targetRevision = targetRevision,
            targetVersion = "1.12.0",
            currentRevision = currentRevision,
            currentVersion = instance.installedVersion,
            recoveryPointId = pointId,
            retentionSeconds = 60,
            stage = UpdateStage.PREPARED,
            result = UpdateResult.RUNNING,
            restorationState = RestorationState.NOT_REQUIRED,
            createdAt = 10,
            updatedAt = 10,
        )
        val recoveryPoint = RecoveryPoint(
            id = pointId,
            instanceId = instance.instanceId,
            createdAt = 10,
            serverRevision = currentRevision,
            serverVersion = instance.installedVersion,
            dataDigestSha256 = "",
            configurationDigestSha256 = "",
            manifestSha256 = "",
            formatVersion = 1,
            variantId = "pocketrisu",
            layoutVersion = 1,
            retentionSeconds = 60,
            inUse = true,
            state = RecoveryPointState.CREATING,
        )
        manager.upsertInstance(instance)
        manager.prepareUpdate(operation, recoveryPoint)
        return operation to recoveryPoint
    }

    private fun prepareSuccessfulUpdate(manager: RisuDatabase): Pair<UpdateOperation, RecoveryPoint> {
        val (operation, recoveryPoint) = prepareUpdate(manager)
        val verifiedPoint = recoveryPoint.copy(
            dataDigestSha256 = "1".repeat(64),
            configurationDigestSha256 = "2".repeat(64),
            manifestSha256 = "3".repeat(64),
            sizeBytes = 4096,
            completedAt = 20,
            inUse = true,
            state = RecoveryPointState.VERIFIED,
        )
        val verifiedOperation = operation.copy(
            stage = UpdateStage.RECOVERY_VERIFIED,
            sourceWasRunning = false,
            updatedAt = 20,
        )
        manager.upsertUpdateProgress(verifiedOperation, verifiedPoint)
        manager.publishUpdateSuccess(
            verifiedOperation.copy(
                stage = UpdateStage.HEALTH_CHECKING,
                activeRevision = operation.targetRevision,
                launchConfirmed = true,
                healthConfirmed = true,
                updatedAt = 30,
            ),
            verifiedPoint,
            now = 30,
        )
        return operation to recoveryPoint
    }

    private fun assertNoForeignKeyViolations(manager: RisuDatabase) {
        manager.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            assertThat(cursor.moveToFirst()).isFalse()
        }
    }

    private fun testInstance(
        instanceId: String = "pocketrisu-existing",
        port: Int,
    ) = RisuInstance(
        instanceId = instanceId,
        variantId = "pocketrisu",
        channelId = "stable",
        runtimeType = RuntimeType.TERMUX_NODE,
        repository = "PocketRisu/PocketRisu",
        port = port,
        createdAt = 1,
        updatedAt = 1,
        lastKnownStatus = RuntimeState.STOPPED,
    )

    private companion object {
        const val TEST_DATABASE_NAME = "risu-manager-migration-test.db"
    }
}
