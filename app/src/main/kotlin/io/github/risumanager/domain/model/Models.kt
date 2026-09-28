package io.github.risumanager.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RuntimeType { TERMUX_NODE, NATIVE_APK }

@Serializable
enum class TrustLevel { TRUSTED_BUILTIN, USER_SELECTED, UNTRUSTED }

@Serializable
enum class ChannelType { GITHUB_RELEASE, GIT_BRANCH, SPECIFIC_COMMIT }

/** The only installation mechanisms a trusted built-in profile may select. */
@Serializable
enum class InstallStrategy { SOURCE_BUILD, RELEASE_ARCHIVE }

@Serializable
enum class BuildScriptId {
    POCKETRISU_TERMUX_BUILD,
    HAEJEOK_TERMUX_BUILD,
}

@Serializable
enum class ReleaseSelection {
    LATEST_STABLE,
    CHANNEL,
    SPECIFIC_TAG,
    SPECIFIC_COMMIT,
}

@Serializable
enum class Capability {
    NETWORK_SETTINGS,
    BODY_LIMIT_SETTINGS,
    AUTOSAVE_SETTINGS,
    SERVER_SNAPSHOTS,
    IMAGE_OPTIMIZATION,
    HTTPS,
    BACKUP,
}

@Serializable
data class IntegrityDefinition(
    val requireHttps: Boolean = true,
    val sha256AssetPattern: String? = null,
    val signatureAssetPattern: String? = null,
    val trustedSigningKeyIds: Set<String> = emptySet(),
)

@Serializable
data class ChannelDefinition(
    val id: String,
    val displayName: String,
    val type: ChannelType,
    val ref: String? = null,
    val advanced: Boolean = false,
)

@Serializable
data class HealthCheckDefinition(
    val url: String,
)

@Serializable
data class DataLayout(
    val databasePaths: List<String> = emptyList(),
    val assetPaths: List<String> = emptyList(),
    val configPaths: List<String> = emptyList(),
    val backupPaths: List<String> = emptyList(),
)

/** A trusted, declarative description of a Node server runnable in Termux. */
@Serializable
data class VariantProfile(
    val id: String,
    val displayName: String,
    val repository: String,
    val channels: List<ChannelDefinition>,
    val selection: ReleaseSelection = ReleaseSelection.LATEST_STABLE,
    val installStrategy: InstallStrategy,
    val releaseAssetPatterns: List<String> = emptyList(),
    val supportedArchitectures: Set<String> = setOf("aarch64", "arm", "x86_64", "x86"),
    val minimumNodeVersion: String,
    val buildScriptId: BuildScriptId? = null,
    val workDirectory: String,
    val entrypoint: String,
    val defaultPort: Int,
    val healthCheckPath: String = "/",
    val dataPaths: DataLayout = DataLayout(),
    val layoutVersion: Int = 1,
    val capabilities: Set<Capability> = emptySet(),
    val integrity: IntegrityDefinition = IntegrityDefinition(),
    val trustLevel: TrustLevel = TrustLevel.TRUSTED_BUILTIN,
)

@Serializable
data class VariantDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val repository: String,
    val runtimeType: RuntimeType,
    val channels: List<ChannelDefinition>,
    val trustLevel: TrustLevel,
    val healthCheck: HealthCheckDefinition? = null,
    val dataLayout: DataLayout = DataLayout(),
    val expectedPackageName: String? = null,
    val termuxProfile: VariantProfile? = null,
)

@Serializable
data class SourceRevision(
    val sha: String,
    val checkoutRef: String = sha,
    val displayVersion: String? = null,
    val releaseName: String? = null,
    val releaseNotes: String? = null,
    val publishedAt: String? = null,
    val installArtifact: InstallArtifact? = null,
)

@Serializable
data class InstallArtifact(
    val name: String,
    val downloadUrl: String,
    val sha256: String,
    val checksumUrl: String? = null,
)

@Serializable
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val contentType: String? = null,
)

@Serializable
data class RisuRelease(
    val tag: String,
    val name: String,
    val revision: SourceRevision,
    val prerelease: Boolean,
    val draft: Boolean,
    val assets: List<ReleaseAsset>,
)

@Serializable
enum class RuntimeState { STOPPED, STARTING, RUNNING, UNHEALTHY, STOPPING, FAILED, UNKNOWN }

@Serializable
data class RuntimeStatus(
    val state: RuntimeState,
    val installed: Boolean = true,
    val pid: Long? = null,
    val port: Int? = null,
    val revision: String? = null,
    val uptimeSeconds: Long? = null,
    val detail: String? = null,
)

@Serializable
enum class EnvironmentState {
    TERMUX_NOT_INSTALLED,
    TERMUX_PERMISSION_MISSING,
    TERMUX_EXTERNAL_COMMANDS_DISABLED,
    TERMUX_READY,
    TERMUX_ERROR,
    NATIVE_APK_READY,
}

@Serializable
data class EnvironmentStatus(
    val state: EnvironmentState,
    val message: String,
    val helperVersion: Int? = null,
    val diagnostics: Map<String, String> = emptyMap(),
)

@Serializable
data class RisuInstance(
    val instanceId: String,
    val variantId: String,
    val channelId: String,
    val runtimeType: RuntimeType,
    val repository: String,
    val installedRevision: String? = null,
    val previousRevision: String? = null,
    val installedVersion: String? = null,
    val latestRevision: String? = null,
    val port: Int = 6001,
    val createdAt: Long,
    val updatedAt: Long,
    val lastKnownStatus: RuntimeState = RuntimeState.UNKNOWN,
    /** User-facing metadata only. Filesystem and operation identity always use [instanceId]. */
    val displayName: String? = null,
)

@Serializable
enum class BindScope {
    LOOPBACK,
    LOCAL_NETWORK,
}

@Serializable
enum class ServerProtocol { HTTP, HTTPS }

/** How PocketRisu's certificate is managed. MANUAL is the legacy/default mode. */
@Serializable
enum class CertificateMode { MANUAL, LETS_ENCRYPT }

/** ACME challenge types supported by the first HTTPS implementation. */
@Serializable
enum class AcmeChallenge { HTTP_01 }

@Serializable
enum class ConfigApplyState { UNKNOWN, PENDING, APPLYING, APPLIED, FAILED }

@Serializable
data class ServerConfigApplyStatus(
    val state: ConfigApplyState = ConfigApplyState.UNKNOWN,
    val appliedAt: Long? = null,
    val lastError: String? = null,
    val observedPort: Int? = null,
    val observedProtocol: ServerProtocol? = null,
)

/** Per-instance server settings. Values are stored in SQLite separately from instance identity. */
@Serializable
data class InstanceServerConfig(
    val instanceId: String,
    val port: Int = 6001,
    val bindScope: BindScope = BindScope.LOOPBACK,
    val protocol: ServerProtocol = ServerProtocol.HTTP,
    val certificateMode: CertificateMode = CertificateMode.MANUAL,
    val tlsDomain: String? = null,
    val acmeEmail: String? = null,
    val acmeChallenge: AcmeChallenge = AcmeChallenge.HTTP_01,
    val acmeChallengePort: Int = 8080,
    /** Epoch milliseconds; Android persistence uses Long rather than java.time.Instant. */
    val certificateExpiresAt: Long? = null,
    val autoRenew: Boolean = false,
    val lastRenewCheckAt: Long? = null,
    val lastRenewSuccessAt: Long? = null,
    val lastRenewError: String? = null,
    val renewFailureCount: Int = 0,
    val bodyLimitMb: Int = 100,
    val saveIntervalSeconds: Int = 5,
    val snapshotIntervalMinutes: Int = 5,
    val snapshotRetentionCount: Int = 20,
    val snapshotCapacityMb: Int = 500,
    val imageQuality: Int = 85,
    val applyState: ConfigApplyState = ConfigApplyState.UNKNOWN,
    val appliedAt: Long? = null,
    val lastError: String? = null,
    val appliedPort: Int? = null,
    val appliedBindScope: BindScope? = null,
    val appliedProtocol: ServerProtocol? = null,
    val appliedRequestBodyLimitMb: Int? = null,
    val appliedSaveIntervalSeconds: Int? = null,
    val appliedSnapshotIntervalMinutes: Int? = null,
    val appliedSnapshotRetentionCount: Int? = null,
    val appliedSnapshotCapacityMb: Int? = null,
) {
    /** Compatibility name used by callers that describe the setting as a request limit. */
    val requestBodyLimitMb: Int get() = bodyLimitMb
}

/** The observed configuration returned by the Termux helper after an apply operation. */
@Serializable
data class AppliedServerConfig(
    val instanceId: String,
    val config: InstanceServerConfig,
    val observedAt: Long,
    val source: String = "termux-helper",
)

@Serializable
data class LogLine(
    val source: String,
    val text: String,
    val timestamp: Long? = null,
)

@Serializable
enum class OperationType { INSTALL, START, STOP, RESTART, UPDATE, ROLLBACK, BACKUP, DOWNLOAD, CLONE }

@Serializable
enum class OperationResultKind { RUNNING, SUCCESS, FAILURE }

@Serializable
data class OperationRecord(
    val operationId: String,
    val instanceId: String,
    val type: OperationType,
    val startedAt: Long,
    val endedAt: Long? = null,
    val result: OperationResultKind = OperationResultKind.RUNNING,
    val stage: String? = null,
    val detail: String? = null,
    val fromRevision: String? = null,
    val toRevision: String? = null,
    val errorCode: String? = null,
)

@Serializable
enum class BackupReason { MANUAL, PRE_UPDATE, PRE_ROLLBACK, PRE_UNINSTALL }

@Serializable
data class BackupMetadata(
    val id: String,
    val instanceId: String,
    val createdAt: Long,
    val reason: BackupReason,
    val sourceRevision: String?,
    val appVersion: String?,
    val size: Long,
    val formatVersion: Int,
)

@JvmInline
@Serializable
value class RecoveryPointId(val value: String)

/**
 * Identity and integrity metadata for a complete historical state. A recovery point couples
 * server, user-data, and configuration state; it is deliberately not a source revision alias.
 * Recovery points are created and verified by the Manager-owned helper before an update may
 * modify the installed instance. Their contents are private implementation data, not exports.
 */
@Serializable
data class RecoveryPoint(
    val id: RecoveryPointId,
    val instanceId: String,
    val createdAt: Long,
    val serverRevision: String?,
    val dataDigestSha256: String,
    val configurationDigestSha256: String,
    val formatVersion: Int,
    val variantId: String,
    val layoutVersion: Int,
    val serverVersion: String? = null,
    val manifestSha256: String = "",
    val sizeBytes: Long = 0,
    val completedAt: Long? = null,
    val expiresAt: Long? = null,
    val retentionSeconds: Long = DEFAULT_RECOVERY_RETENTION_SECONDS,
    val pinned: Boolean = false,
    val inUse: Boolean = false,
    val restored: Boolean? = null,
    val sourceWasRunning: Boolean? = null,
    val restorationState: RestorationState = RestorationState.NOT_REQUIRED,
    val state: RecoveryPointState = RecoveryPointState.CREATING,
    val managerOwned: Boolean = true,
    val lastError: String? = null,
)

const val DEFAULT_RECOVERY_RETENTION_SECONDS: Long = 7 * 24 * 60 * 60
const val MIN_RECOVERY_RETENTION_SECONDS: Long = 60
const val MAX_RECOVERY_RETENTION_SECONDS: Long = 365 * 24 * 60 * 60

@Serializable
enum class RecoveryPointState { CREATING, VERIFIED, AVAILABLE, RESTORING, RESTORED, CLEANUP_PENDING, FAILED }

@Serializable
enum class RestorationState { NOT_REQUIRED, PENDING, SUCCEEDED, FAILED, UNKNOWN }

@Serializable
data class RecoveryEstimate(
    val instanceId: String,
    val estimatedSizeBytes: Long,
    val requiredBytes: Long,
    val availableBytes: Long,
) {
    val hasEnoughSpace: Boolean get() = availableBytes >= requiredBytes
}

@Serializable
enum class UpdateStage {
    PREPARED,
    STAGING,
    STOPPING,
    RECOVERY_CREATING,
    RECOVERY_VERIFYING,
    RECOVERY_VERIFIED,
    SWITCHING,
    UPDATING,
    LAUNCHING,
    HEALTH_CHECKING,
    RESTORING,
    ROLLING_BACK,
    RECONCILING,
    COMPLETED,
    FAILED,
}

@Serializable
enum class UpdateResult { RUNNING, SUCCESS, FAILURE }

/** Durable Android-side reservation and reconciliation state for one PocketRisu update. */
@Serializable
data class UpdateOperation(
    val operationId: String,
    val instanceId: String,
    val variantId: String,
    val layoutVersion: Int,
    val targetRevision: String,
    val targetVersion: String? = null,
    val currentRevision: String,
    val currentVersion: String? = null,
    val recoveryPointId: RecoveryPointId,
    val retentionSeconds: Long = DEFAULT_RECOVERY_RETENTION_SECONDS,
    val stage: UpdateStage = UpdateStage.PREPARED,
    val result: UpdateResult = UpdateResult.RUNNING,
    val sourceWasRunning: Boolean? = null,
    val restorationState: RestorationState = RestorationState.NOT_REQUIRED,
    val activeRevision: String? = null,
    val launchConfirmed: Boolean = false,
    val healthConfirmed: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val errorCode: String? = null,
    val detail: String? = null,
) {
    val isTerminal: Boolean get() = result != UpdateResult.RUNNING
}

@Serializable
enum class CloneStage {
    PREPARED,
    SOURCE_INSPECTION,
    SOURCE_STOPPING,
    STAGING_CREATION,
    COPYING,
    VALIDATING,
    TRANSFORMING,
    RESTORING_SOURCE,
    READY_TO_PUBLISH,
    PUBLISHING,
    REGISTERING,
    COMPLETED,
    FAILED,
}

@Serializable
enum class CloneResult { RUNNING, SUCCESS, FAILURE }

/** Durable Android-side ownership and reconciliation state for one clone attempt. */
@Serializable
data class CloneOperation(
    val operationId: String,
    val sourceInstanceId: String,
    val targetInstanceId: String,
    val targetDisplayName: String,
    val targetPort: Int,
    val sourceRevision: String,
    val sourceVersion: String? = null,
    /** Set only when the clone source is an immutable Manager recovery point. */
    val recoveryPointId: RecoveryPointId? = null,
    val bodyLimitMb: Int = 100,
    val saveIntervalSeconds: Int = 5,
    val snapshotIntervalMinutes: Int = 5,
    val snapshotRetentionCount: Int = 20,
    val snapshotCapacityMb: Int = 500,
    val imageQuality: Int = 85,
    val stage: CloneStage = CloneStage.PREPARED,
    val result: CloneResult = CloneResult.RUNNING,
    val sourceWasRunning: Boolean? = null,
    val sourceRestored: Boolean? = null,
    val helperPublished: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val errorCode: String? = null,
    val detail: String? = null,
) {
    val isTerminal: Boolean get() = result != CloneResult.RUNNING
}

@Serializable
enum class RollbackStage {
    PREPARED,
    VALIDATING,
    PRE_ROLLBACK_CREATING,
    PRE_ROLLBACK_VERIFYING,
    PRE_ROLLBACK_VERIFIED,
    STAGING,
    STOPPING,
    RESTORING,
    SWITCHING,
    LAUNCHING,
    HEALTH_CHECKING,
    RESTORING_CURRENT,
    RECONCILING,
    COMPLETED,
    FAILED,
}

@Serializable
enum class RollbackResult { RUNNING, SUCCESS, FAILURE }

/** Content-free audit event suitable for diagnostics. */
@Serializable
data class RollbackEvent(
    val operationId: String,
    val instanceId: String,
    val stage: RollbackStage,
    val recordedAt: Long,
    val result: RollbackResult,
    val errorCode: String? = null,
)

/** Durable Android-side identity and reconciliation state for a manual full-state rollback. */
@Serializable
data class RollbackOperation(
    val operationId: String,
    val instanceId: String,
    val variantId: String,
    val layoutVersion: Int,
    val selectedRecoveryPointId: RecoveryPointId,
    val preRollbackRecoveryPointId: RecoveryPointId,
    val currentRevision: String,
    val currentVersion: String? = null,
    val targetRevision: String,
    val targetVersion: String? = null,
    val retentionSeconds: Long = DEFAULT_RECOVERY_RETENTION_SECONDS,
    val stage: RollbackStage = RollbackStage.PREPARED,
    val result: RollbackResult = RollbackResult.RUNNING,
    val sourceWasRunning: Boolean? = null,
    val currentStateRestored: Boolean? = null,
    val activeRevision: String? = null,
    val activeVersion: String? = null,
    val launchConfirmed: Boolean = false,
    val healthConfirmed: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val errorCode: String? = null,
    val detail: String? = null,
) {
    val isTerminal: Boolean get() = result != RollbackResult.RUNNING
}

/** Allowlisted metadata shown before a rollback. Recovery content never crosses this boundary. */
@Serializable
data class RollbackPreview(
    val instanceId: String,
    val recoveryPointId: RecoveryPointId,
    val currentRevision: String?,
    val currentVersion: String?,
    val targetRevision: String?,
    val targetVersion: String?,
    val createdAt: Long,
    val sizeBytes: Long,
    val expiresAt: Long?,
    val pinned: Boolean,
    val available: Boolean,
    val availabilityReason: String? = null,
    val requiredBytes: Long? = null,
    val availableBytes: Long? = null,
    val dataChanged: Boolean? = null,
    val configurationChanged: Boolean? = null,
)

enum class OperationLiveness { LOADING, BLOCKED, RECONNECTING }

sealed interface OperationState {
    data object Idle : OperationState
    data class Running(
        val stage: String,
        val detail: String?,
        val progress: Float? = null,
        val liveness: OperationLiveness = OperationLiveness.LOADING,
    ) : OperationState
    data class Success(val message: String) : OperationState
    data class Failure(val code: String, val message: String, val recoverable: Boolean) : OperationState
}

sealed interface OperationResult {
    data class Success(
        val message: String,
        val revision: String? = null,
        val version: String? = null,
        val port: Int? = null,
    ) : OperationResult

    data class Failure(
        val code: String,
        val message: String,
        val recoverable: Boolean = true,
    ) : OperationResult
}

fun interface ProgressSink {
    suspend fun report(state: OperationState.Running)
}
