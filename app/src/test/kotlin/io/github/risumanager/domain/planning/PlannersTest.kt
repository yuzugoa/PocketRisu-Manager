package io.github.risumanager.domain.planning

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RecoveryPointState
import io.github.risumanager.domain.model.SourceRevision
import org.junit.Test

class PlannersTest {
    private val sourceSha = "278251f85a19bfdfd4cf3faae780e62682878f9e"
    private val targetSha = "b315d898abd543fffaf5346d8eb3246b20da92cd"
    private val instance = RisuInstance(
        instanceId = "pocketrisu-ab12cd34",
        variantId = "pocketrisu",
        channelId = "stable",
        runtimeType = RuntimeType.TERMUX_NODE,
        repository = "PocketRisu/PocketRisu",
        installedRevision = sourceSha,
        previousRevision = "b".repeat(40),
        createdAt = 1,
        updatedAt = 1,
        lastKnownStatus = RuntimeState.RUNNING,
    )

    @Test fun updatePlanPreservesSafetyState() {
        val plan = UpdatePlanner.plan(instance, SourceRevision(targetSha))
        assertThat(plan).isInstanceOf(UpdatePlan.Ready::class.java)
        plan as UpdatePlan.Ready
        assertThat(plan.mustStop).isTrue()
        assertThat(plan.createSafetyBackup).isTrue()
        assertThat(plan.from).isEqualTo(sourceSha)
    }

    @Test fun identicalCommitDoesNotPlanUpdate() {
        assertThat(UpdatePlanner.plan(instance, SourceRevision(sourceSha))).isEqualTo(UpdatePlan.AlreadyCurrent)
    }

    @Test fun anyDistinctFullCommitPairCanBePlanned() {
        val plan = UpdatePlanner.plan(instance.copy(installedRevision = "a".repeat(40)), SourceRevision("c".repeat(40)))
        assertThat(plan).isInstanceOf(UpdatePlan.Ready::class.java)
    }

    @Test fun nonFullCommitIsRejected() {
        assertThat(UpdatePlanner.plan(instance, SourceRevision("c".repeat(39))))
            .isEqualTo(UpdatePlan.Invalid("Target revision is not a full Git SHA"))
    }

    @Test fun updatePlannerDoesNotExposeUpdatesForOtherVariants() {
        val haejeok = instance.copy(variantId = "haejeok", repository = "nevaeh5379/HaejeokRisuai")
        assertThat(UpdatePlanner.plan(haejeok, SourceRevision(targetSha)))
            .isEqualTo(UpdatePlan.Invalid("Updates are supported only for a trusted PocketRisu Termux instance"))
    }

    @Test fun rollbackRequiresCompleteRecoveryPointInsteadOfRevisionOnly() {
        assertThat(RollbackPlanner.plan(instance, null)).isEqualTo(RollbackPlan.Invalid("Select a validated recovery point"))
        val point = RecoveryPoint(
            id = RecoveryPointId("rp-1"),
            instanceId = instance.instanceId,
            createdAt = 2,
            serverRevision = "b".repeat(40),
            dataDigestSha256 = "c".repeat(64),
            configurationDigestSha256 = "d".repeat(64),
            manifestSha256 = "e".repeat(64),
            formatVersion = 1,
            variantId = instance.variantId,
            layoutVersion = 1,
            state = RecoveryPointState.AVAILABLE,
        )
        assertThat(RollbackPlanner.plan(instance, point)).isEqualTo(RollbackPlan.Ready(point))
        assertThat(RollbackPlanner.plan(instance, point.copy(inUse = true)))
            .isEqualTo(RollbackPlan.Invalid("Recovery point is not currently available"))
        assertThat(RollbackPlanner.plan(instance, point.copy(manifestSha256 = "")))
            .isEqualTo(RollbackPlan.Invalid("Recovery point integrity metadata is invalid"))
        assertThat(RollbackPlanner.plan(instance, point.copy(serverRevision = null)))
            .isEqualTo(RollbackPlan.Invalid("Recovery point server revision is invalid"))
        assertThat(RollbackPlanner.plan(instance.copy(variantId = "haejeok"), point))
            .isEqualTo(RollbackPlan.Invalid("Rollback is supported only for a trusted PocketRisu Termux instance"))
    }

    @Test fun stateTransitionsRejectImpossibleJump() {
        assertThat(RuntimeStateTransitions.canTransition(RuntimeState.STOPPED, RuntimeState.RUNNING)).isFalse()
        assertThat(RuntimeStateTransitions.canTransition(RuntimeState.STOPPED, RuntimeState.STARTING)).isTrue()
    }
}
