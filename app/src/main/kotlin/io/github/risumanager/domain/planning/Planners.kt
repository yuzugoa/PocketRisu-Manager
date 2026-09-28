package io.github.risumanager.domain.planning

import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.SourceRevision
import io.github.risumanager.domain.validation.Validators

sealed interface UpdatePlan {
    data object AlreadyCurrent : UpdatePlan
    data class Ready(val from: String, val to: SourceRevision, val mustStop: Boolean, val createSafetyBackup: Boolean) : UpdatePlan
    data class Invalid(val reason: String) : UpdatePlan
}

object UpdatePlanner {
    fun plan(instance: RisuInstance, target: SourceRevision): UpdatePlan {
        val installed = instance.installedRevision ?: return UpdatePlan.Invalid("Installed revision is unknown")
        if (instance.variantId != "pocketrisu" || instance.runtimeType != RuntimeType.TERMUX_NODE ||
            instance.repository != "PocketRisu/PocketRisu"
        ) return UpdatePlan.Invalid("Updates are supported only for a trusted PocketRisu Termux instance")
        if (!Validators.isFullCommitSha(installed)) return UpdatePlan.Invalid("Installed revision is not a full Git SHA")
        if (!Validators.isFullCommitSha(target.sha)) return UpdatePlan.Invalid("Target revision is not a full Git SHA")
        if (installed.equals(target.sha, ignoreCase = true)) return UpdatePlan.AlreadyCurrent
        return UpdatePlan.Ready(
            from = installed,
            to = target,
            mustStop = instance.lastKnownStatus != RuntimeState.STOPPED,
            createSafetyBackup = true,
        )
    }

}

sealed interface RollbackPlan {
    data class Ready(val recoveryPoint: RecoveryPoint) : RollbackPlan
    data class Invalid(val reason: String) : RollbackPlan
}

object RollbackPlanner {
    fun plan(instance: RisuInstance, recoveryPoint: RecoveryPoint?): RollbackPlan {
        val point = recoveryPoint ?: return RollbackPlan.Invalid("Select a validated recovery point")
        if (instance.variantId != "pocketrisu" || instance.runtimeType != RuntimeType.TERMUX_NODE ||
            instance.repository != "PocketRisu/PocketRisu"
        ) return RollbackPlan.Invalid("Rollback is supported only for a trusted PocketRisu Termux instance")
        if (point.instanceId != instance.instanceId) return RollbackPlan.Invalid("Recovery point belongs to another instance")
        if (point.variantId != instance.variantId) return RollbackPlan.Invalid("Recovery point is incompatible with this variant")
        if (!point.managerOwned || point.formatVersion != 1 || point.layoutVersion != 1) {
            return RollbackPlan.Invalid("Recovery point format or layout is not supported")
        }
        if (point.inUse || point.state !in setOf(RecoveryPointState.AVAILABLE, RecoveryPointState.RESTORED)) {
            return RollbackPlan.Invalid("Recovery point is not currently available")
        }
        if (!Validators.isSha256(point.manifestSha256) ||
            !Validators.isSha256(point.dataDigestSha256) ||
            !Validators.isSha256(point.configurationDigestSha256)
        ) {
            return RollbackPlan.Invalid("Recovery point integrity metadata is invalid")
        }
        if (point.serverRevision == null || !Validators.isFullCommitSha(point.serverRevision)) {
            return RollbackPlan.Invalid("Recovery point server revision is invalid")
        }
        return RollbackPlan.Ready(point)
    }
}

object RuntimeStateTransitions {
    private val allowed = mapOf(
        RuntimeState.STOPPED to setOf(RuntimeState.STARTING, RuntimeState.UNKNOWN),
        RuntimeState.STARTING to setOf(RuntimeState.RUNNING, RuntimeState.UNHEALTHY, RuntimeState.FAILED, RuntimeState.STOPPED),
        RuntimeState.RUNNING to setOf(RuntimeState.STOPPING, RuntimeState.UNHEALTHY, RuntimeState.UNKNOWN),
        RuntimeState.UNHEALTHY to setOf(RuntimeState.RUNNING, RuntimeState.STOPPING, RuntimeState.FAILED, RuntimeState.UNKNOWN),
        RuntimeState.STOPPING to setOf(RuntimeState.STOPPED, RuntimeState.FAILED),
        RuntimeState.FAILED to setOf(RuntimeState.STARTING, RuntimeState.STOPPED, RuntimeState.UNKNOWN),
        RuntimeState.UNKNOWN to RuntimeState.entries.toSet(),
    )

    fun canTransition(from: RuntimeState, to: RuntimeState): Boolean = from == to || to in allowed.getValue(from)
}
